package com.kaagaz.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kaagaz.app.audit.AuditDocument
import com.kaagaz.app.audit.CrashTest
import com.kaagaz.app.audit.EvidenceRef
import com.kaagaz.app.audit.Severity
import com.kaagaz.app.core.DeadlineReminder
import com.kaagaz.app.core.ReminderState
import com.kaagaz.app.core.SavedDocumentSummary
import com.kaagaz.app.forms.FormLibrary
import com.kaagaz.app.forms.FormMapper
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.ExtractionMethod
import com.kaagaz.app.knowledge.Fact
import com.kaagaz.app.knowledge.FactExtractor
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.LedgerEntry
import com.kaagaz.app.knowledge.Normalizer
import com.kaagaz.app.knowledge.Resolution
import com.kaagaz.app.knowledge.TrustLedger
import com.kaagaz.app.mission.MissionRunner
import com.kaagaz.app.mission.MissionTools
import com.kaagaz.app.mission.ToolResult
import com.kaagaz.app.rag.Chunk
import com.kaagaz.app.rag.Chunker
import com.kaagaz.app.rag.GroundedAnswerer
import com.kaagaz.app.rag.HybridRetriever
import com.kaagaz.app.rag.ScoredChunk
import com.kaagaz.app.reminders.ReminderScheduler
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Everything Mission OS knows at one moment, derived from saved OCR text plus the user's own decisions. */
data class KnowledgeSnapshot(
    val documents: List<AuditDocument>,
    val summaries: List<SavedDocumentSummary>,
    val facts: List<Fact>,
    val chunks: List<Chunk>,
    val ledger: List<LedgerEntry>,
    val builtAt: Long
) {
    val kinds: Map<String, DocKind> get() = documents.associate { it.id to it.kind }
    val corpus get() = GroundedAnswerer.Corpus(documents, chunks, facts, ledger)
    val counts get() = TrustLedger.counts(ledger)
}

class KnowledgeService(private val context: Context, private val store: KaagazStore) {
    @Volatile private var cached: KnowledgeSnapshot? = null
    val profileRequired = setOf(FactType.FULL_NAME, FactType.DATE_OF_BIRTH, FactType.EMAIL, FactType.PHONE, FactType.DEGREE, FactType.INSTITUTION, FactType.GRADUATION_YEAR)
    private val db: SQLiteDatabase get() = store.writableDatabase

    fun invalidate() { cached = null }

    /** Builds (or returns the cached) snapshot. Call off the main thread. */
    fun snapshot(): KnowledgeSnapshot = cached ?: build().also { cached = it }

    private fun build(): KnowledgeSnapshot {
        val summaries = store.listDocuments()
        val corrections = loadCorrections()
        val documents = mutableListOf<AuditDocument>(); val facts = mutableListOf<Fact>(); val chunks = mutableListOf<Chunk>()
        val seenFiles = HashSet<String>()
        // Oldest first, so the original upload is kept and identical re-uploads are counted once.
        summaries.sortedBy { it.createdAt }.forEach { summary ->
            val stored = runCatching { store.load(summary.id) }.getOrNull() ?: return@forEach
            if (stored.ocrPages.isEmpty()) return@forEach
            val fingerprint = stored.document.uri.path?.let(::File)?.takeIf { it.isFile }?.let(::sha256)
            if (fingerprint != null && !seenFiles.add(fingerprint)) return@forEach
            val kind = FactExtractor.classify(stored.ocrPages)
            documents += AuditDocument(summary.id, summary.name, kind, stored.ocrPages, stored.fields)
            facts += FactExtractor.extract(summary.id, summary.name, stored.ocrPages, stored.fields, summary.createdAt).map { fact ->
                corrections[Triple(fact.documentId, fact.type, fact.normalized)]?.let { corrected ->
                    val normalized = Normalizer.forType(fact.type, corrected) ?: corrected
                    fact.copy(rawValue = corrected, normalized = normalized, method = ExtractionMethod.USER_CORRECTION, userConfirmed = true,
                        evidence = "Corrected by you from “${fact.rawValue}”. Original source: ${fact.evidence}")
                } ?: fact
            }
            chunks += Chunker.chunks(summary.id, summary.name, stored.ocrPages)
        }
        val ledger = TrustLedger.build(facts, documents.associate { it.id to it.kind }, profileRequired, resolutions())
        return KnowledgeSnapshot(documents, summaries, facts, chunks, ledger, System.currentTimeMillis())
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- user decisions -------------------------------------------------------------------------------------

    fun resolutions(): Map<FactType, Resolution> = db.rawQuery("SELECT fact_type,chosen_normalized,resolved_at FROM fact_resolutions", null).use { c ->
        buildMap { while (c.moveToNext()) runCatching { FactType.valueOf(c.getString(0)) }.getOrNull()?.let { put(it, Resolution(it, c.getString(1), c.getLong(2))) } }
    }

    fun resolve(type: FactType, chosenNormalized: String) {
        db.insertWithOnConflict("fact_resolutions", null, ContentValues().apply {
            put("fact_type", type.name); put("chosen_normalized", chosenNormalized); put("resolved_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE); invalidate()
    }

    fun clearResolution(type: FactType) { db.delete("fact_resolutions", "fact_type=?", arrayOf(type.name)); invalidate() }

    fun correct(fact: Fact, value: String) {
        db.insertWithOnConflict("fact_corrections", null, ContentValues().apply {
            put("document_id", fact.documentId); put("fact_type", fact.type.name); put("original_normalized", fact.normalized)
            put("corrected_value", value.trim()); put("corrected_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE); invalidate()
    }

    private fun loadCorrections(): Map<Triple<String, FactType, String>, String> =
        db.rawQuery("SELECT document_id,fact_type,original_normalized,corrected_value FROM fact_corrections", null).use { c ->
            buildMap { while (c.moveToNext()) runCatching { FactType.valueOf(c.getString(1)) }.getOrNull()?.let { put(Triple(c.getString(0), it, c.getString(2)), c.getString(3)) } }
        }

    fun findingStates(): Map<String, String> = db.rawQuery("SELECT finding_id,state FROM finding_states", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }

    fun setFindingState(id: String, state: String?) {
        if (state == null) db.delete("finding_states", "finding_id=?", arrayOf(id))
        else db.insertWithOnConflict("finding_states", null, ContentValues().apply {
            put("finding_id", id); put("state", state); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun formValues(formId: String): Map<String, Pair<String, String>> =
        db.rawQuery("SELECT field_id,value,source FROM form_values WHERE form_id=?", arrayOf(formId)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) to c.getString(2)) }
        }

    fun saveFormValue(formId: String, fieldId: String, value: String, source: String) {
        db.insertWithOnConflict("form_values", null, ContentValues().apply {
            put("form_id", formId); put("field_id", fieldId); put("value", value); put("source", source); put("filled_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearForm(formId: String) { db.delete("form_values", "form_id=?", arrayOf(formId)) }

    fun saveMission(id: String, runner: MissionRunner) {
        val steps = Gson().toJson(runner.steps.map { mapOf("action" to it.action.name, "status" to it.status.name, "result" to (it.result ?: "")) })
        db.insertWithOnConflict("missions", null, ContentValues().apply {
            put("id", id); put("goal", runner.plan.goal); put("planned_by", runner.plan.plannedBy); put("phase", runner.phase.name)
            put("steps_json", steps); put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class MissionRecord(val goal: String, val phase: String, val plannedBy: String, val updatedAt: Long)

    fun recentMissions(limit: Int = 3): List<MissionRecord> =
        db.rawQuery("SELECT goal,phase,planned_by,updated_at FROM missions ORDER BY updated_at DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(MissionRecord(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    // ---- encrypted profile ----------------------------------------------------------------------------------

    /** Profile values encrypted with an AES-GCM key that never leaves the Android Keystore. */
    object Vault {
        private const val ALIAS = "kaagaz_profile_key"
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            }.generateKey()
        }

        fun write(file: File, plain: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            file.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP))
        }

        fun read(file: File): String? {
            if (!file.isFile) return null
            val (iv, data) = file.readText().split(':').takeIf { it.size == 2 } ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
            return String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    val profileFile: File get() = File(context.filesDir, "profile.enc")

    fun saveProfile(entries: List<LedgerEntry>): Map<String, String> {
        val json = JsonObject()
        entries.mapNotNull { e -> e.usable?.let { e to it } }.forEach { (e, g) ->
            json.add(e.type.name, JsonObject().apply { addProperty("value", g.display); addProperty("sources", FormMapper.sourceNote(g)) })
        }
        Vault.write(profileFile, json.toString())
        return json.entrySet().associate { it.key to it.value.asJsonObject.get("value").asString }
    }

    fun loadProfile(): Map<String, Pair<String, String>> = runCatching {
        val obj = com.google.gson.JsonParser.parseString(Vault.read(profileFile) ?: return emptyMap()).asJsonObject
        obj.entrySet().associate { (k, v) -> k to (v.asJsonObject.get("value").asString to v.asJsonObject.get("sources").asString) }
    }.getOrDefault(emptyMap())

    fun deleteProfile() { profileFile.delete() }

    // ---- deterministic mission tools ------------------------------------------------------------------------

    fun tools(hasNotificationPermission: () -> Boolean): MissionTools = object : MissionTools {
        override fun search(query: String): ToolResult {
            val snap = snapshot()
            if (snap.documents.isEmpty()) return ToolResult(false, "No documents with extracted text yet. Scan or import a document first.")
            val retriever = HybridRetriever(snap.chunks, snap.facts)
            val hits = retriever.search(query, k = 6, minBm25 = 0.5)
            val docs = hits.map { it.chunk.documentName }.distinct()
            // Cite the single OCR line that matched best, not the page header the chunk starts with.
            fun bestLine(h: ScoredChunk) = h.chunk.lines.maxByOrNull { l -> retriever.tokens(l.text).count { it in h.matched } }?.text ?: h.chunk.text
            return ToolResult(true, if (hits.isEmpty()) "Searched ${snap.documents.size} documents; no passage matched strongly, so all documents will be checked."
                else "Found ${hits.size} relevant passages in ${docs.size} documents: ${docs.take(3).joinToString()}.",
                hits.distinctBy { it.chunk.documentId }.take(3).map { EvidenceRef(it.chunk.documentId, it.chunk.documentName, it.chunk.page, bestLine(it).take(160)) })
        }

        override fun buildLedger(): ToolResult {
            invalidate(); val snap = snapshot(); val c = snap.counts
            return ToolResult(true, "Extracted ${snap.facts.size} facts from ${snap.documents.size} documents · ${c.verified} verified, ${c.conflicting} conflicting, ${c.missing} missing, ${c.needsConfirmation} to confirm.")
        }

        override fun crashTest(formId: String?): ToolResult {
            val snap = snapshot(); val states = findingStates()
            val form = if (formId == FormLibrary.universityApplication.id) FormLibrary.universityApplication else null
            val open = CrashTest.run(snap.documents, snap.facts, snap.ledger, form).filter { states[it.id] == null }
            val high = open.count { it.severity == Severity.HIGH }
            return ToolResult(true, "${open.size} open findings (${high} high severity)." + (open.firstOrNull()?.let { " Top: ${it.title}." } ?: ""), open.take(3).flatMap { it.evidence.take(1) })
        }

        override fun prepareForm(formId: String): ToolResult {
            val proposals = FormMapper.map(FormLibrary.universityApplication, snapshot().ledger)
            val fillable = proposals.count { it.fillable }
            return ToolResult(true, "$fillable of ${proposals.size} fields can be filled from verified evidence; ${proposals.size - fillable} need you.",
                proposals.filter { it.fillable }.take(3).flatMap { p -> p.sources.take(1).map { EvidenceRef(it.documentId, it.documentName, it.page, it.evidence) } })
        }

        override fun fillForm(formId: String): ToolResult {
            val spec = FormLibrary.universityApplication
            val proposals = FormMapper.map(spec, snapshot().ledger).filter { it.fillable }
            if (proposals.isEmpty()) return ToolResult(false, "No field has a verified value to fill.")
            proposals.forEach { saveFormValue(spec.id, it.field.id, it.value!!, it.note) }
            val stored = formValues(spec.id)
            val ok = proposals.all { stored[it.field.id]?.first == it.value }
            return ToolResult(true, "Filled ${proposals.size} verified fields into “${spec.title}”. Nothing was submitted.", verified = ok)
        }

        override fun saveProfile(): ToolResult {
            val written = saveProfile(snapshot().ledger)
            val readBack = loadProfile().mapValues { it.value.first }
            return ToolResult(true, "Saved ${written.size} verified facts to the encrypted profile.", verified = readBack == written && written.isNotEmpty())
        }

        override fun scheduleReminder(target: String?): ToolResult {
            val snap = snapshot(); val today = LocalDate.now()
            val due = snap.facts.filter { it.type == FactType.DUE_DATE }
                .mapNotNull { f -> runCatching { LocalDate.parse(f.normalized) }.getOrNull()?.let { f to it } }
                .filter { !it.second.isBefore(today) }.minByOrNull { it.second }
                ?: return ToolResult(false, "No upcoming due date was found in your documents.")
            val (fact, date) = due
            val amount = snap.facts.firstOrNull { it.type == FactType.BILL_AMOUNT && it.documentId == fact.documentId }
            val label = amount?.let { Normalizer.display(FactType.BILL_AMOUNT, it.normalized, it.rawValue) } ?: fact.documentName
            val permitted = hasNotificationPermission()
            val reminder = DeadlineReminder(fact.documentId, "BILL_DUE", date.toEpochDay(), label, "Supported by document text",
                setOf(7, 1, 0), if (permitted) ReminderState.SCHEDULED else ReminderState.SAVED_PERMISSION_REQUIRED, System.currentTimeMillis())
            store.saveReminder(reminder)
            val scheduled = permitted && ReminderScheduler(context).schedule(reminder)
            val readBack = store.getReminder(fact.documentId, "BILL_DUE")
            val verified = readBack?.deadlineEpochDay == date.toEpochDay() && (!permitted || scheduled)
            return ToolResult(true, if (permitted) "Reminder scheduled for $label due ${Normalizer.display(FactType.DUE_DATE, fact.normalized, fact.rawValue)} (7 days, 1 day and same day, 9 AM)."
                else "Reminder saved, but notifications are not allowed, so it cannot alert you yet.",
                listOf(EvidenceRef(fact.documentId, fact.documentName, fact.page, fact.evidence)), verified = verified && permitted)
        }
    }

    companion object { fun newMissionId() = UUID.randomUUID().toString() }
}
