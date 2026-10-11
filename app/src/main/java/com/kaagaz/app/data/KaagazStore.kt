package com.kaagaz.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Rect
import android.net.Uri
import com.kaagaz.app.core.DeadlineReminder
import com.kaagaz.app.core.DocumentType
import com.kaagaz.app.core.InsuranceField
import com.kaagaz.app.core.InsuranceFieldKey
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.core.ReminderState
import com.kaagaz.app.core.SavedDocumentSummary
import com.kaagaz.app.core.SelectedDocument
import com.kaagaz.app.core.StoredDocument
import com.kaagaz.app.core.VerificationStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class KaagazStore(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE documents (
            id TEXT PRIMARY KEY, original_name TEXT NOT NULL, document_type TEXT NOT NULL,
            mime_type TEXT NOT NULL, page_count INTEGER NOT NULL, local_path TEXT NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE ocr_pages (
            document_id TEXT NOT NULL, page_number INTEGER NOT NULL, page_text TEXT NOT NULL,
            PRIMARY KEY(document_id, page_number),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE ocr_lines (
            document_id TEXT NOT NULL, page_number INTEGER NOT NULL, line_order INTEGER NOT NULL,
            line_text TEXT NOT NULL, box_left INTEGER, box_top INTEGER, box_right INTEGER, box_bottom INTEGER,
            confidence REAL NOT NULL, source_width INTEGER NOT NULL, source_height INTEGER NOT NULL,
            PRIMARY KEY(document_id, page_number, line_order),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE insurance_fields (
            document_id TEXT NOT NULL, field_key TEXT NOT NULL, original_value TEXT,
            status TEXT NOT NULL, review_reason TEXT, user_value TEXT, user_confirmed INTEGER NOT NULL,
            PRIMARY KEY(document_id, field_key),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE field_evidence (
            document_id TEXT NOT NULL, field_key TEXT NOT NULL, evidence_order INTEGER NOT NULL,
            page_number INTEGER NOT NULL, line_text TEXT NOT NULL,
            box_left INTEGER, box_top INTEGER, box_right INTEGER, box_bottom INTEGER,
            confidence REAL NOT NULL, source_width INTEGER NOT NULL, source_height INTEGER NOT NULL,
            PRIMARY KEY(document_id, field_key, evidence_order),
            FOREIGN KEY(document_id, field_key) REFERENCES insurance_fields(document_id, field_key) ON DELETE CASCADE)""")
        createRemindersTable(db)
        createDocumentLinksTable(db)
        createMissionTables(db)
        com.kaagaz.app.reminders.NoteBook.createTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRemindersTable(db)
        if (oldVersion < 3) createDocumentLinksTable(db)
        if (oldVersion < 4) createMissionTables(db)
        if (oldVersion < 5) com.kaagaz.app.reminders.NoteBook.createTable(db)
    }

    fun import(uri: Uri, name: String, type: DocumentType, mimeType: String, pageCount: Int): SelectedDocument {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "documents")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Private storage is unavailable")
        val extension = safeExtension(name, type)
        val temporary = File(directory, "$id.tmp")
        val destination = File(directory, "$id.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            } ?: throw IOException("Document could not be opened")
            if (temporary.length() == 0L) throw IOException("Document is empty")
            if (!temporary.renameTo(destination)) throw IOException("Document could not be stored")
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("id", id); put("original_name", name); put("document_type", type.name)
                put("mime_type", mimeType); put("page_count", pageCount); put("local_path", destination.absolutePath)
                put("created_at", now); put("updated_at", now)
            }
            if (writableDatabase.insertOrThrow("documents", null, values) == -1L) throw IOException("Document record was not saved")
            return SelectedDocument(id, Uri.fromFile(destination), name, type, mimeType, pageCount)
        } catch (error: Exception) {
            temporary.delete(); destination.delete()
            throw error
        }
    }

    fun saveAnalysis(documentId: String, pages: List<OcrPageResult>, fields: List<InsuranceField>) {
        writableDatabase.inTransaction {
            delete("field_evidence", "document_id=?", arrayOf(documentId))
            delete("insurance_fields", "document_id=?", arrayOf(documentId))
            delete("ocr_lines", "document_id=?", arrayOf(documentId))
            delete("ocr_pages", "document_id=?", arrayOf(documentId))
            pages.forEach { page ->
                insertOrThrow("ocr_pages", null, ContentValues().apply {
                    put("document_id", documentId); put("page_number", page.pageNumber); put("page_text", page.text)
                })
                page.lines.forEachIndexed { index, line -> insertEvidenceRow("ocr_lines", documentId, null, index, line) }
            }
            fields.forEach { field ->
                insertOrThrow("insurance_fields", null, fieldValues(documentId, field))
                field.evidence.forEachIndexed { index, line -> insertEvidenceRow("field_evidence", documentId, field.key.name, index, line) }
            }
            execSQL("UPDATE documents SET updated_at=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), documentId))
        }
    }

    fun updateField(documentId: String, field: InsuranceField) {
        val values = fieldValues(documentId, field).apply { remove("document_id"); remove("field_key") }
        writableDatabase.update("insurance_fields", values, "document_id=? AND field_key=?", arrayOf(documentId, field.key.name))
    }

    fun saveReminder(reminder: DeadlineReminder) {
        writableDatabase.insertWithOnConflict("reminders", null, ContentValues().apply {
            put("document_id", reminder.documentId); put("deadline_type", reminder.deadlineType)
            put("deadline_epoch_day", reminder.deadlineEpochDay); put("source_value", reminder.sourceValue)
            put("source_status", reminder.sourceStatus); put("offsets_days", reminder.offsetsDays.sortedDescending().joinToString(","))
            put("state", reminder.state.name); put("updated_at", reminder.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getReminder(documentId: String, deadlineType: String = "POLICY_EXPIRY"): DeadlineReminder? = readableDatabase.rawQuery(
        "SELECT deadline_type,deadline_epoch_day,source_value,source_status,offsets_days,state,updated_at FROM reminders WHERE document_id=? AND deadline_type=?",
        arrayOf(documentId, deadlineType)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else DeadlineReminder(
            documentId, cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3),
            cursor.getString(4).split(',').mapNotNull(String::toIntOrNull).toSet(),
            ReminderState.valueOf(cursor.getString(5)), cursor.getLong(6)
        )
    }

    fun listReminders(): List<DeadlineReminder> = readableDatabase.rawQuery(
        "SELECT document_id,deadline_type,deadline_epoch_day,source_value,source_status,offsets_days,state,updated_at FROM reminders", null
    ).use { c -> buildList { while (c.moveToNext()) add(DeadlineReminder(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4),
        c.getString(5).split(',').mapNotNull(String::toIntOrNull).toSet(), ReminderState.valueOf(c.getString(6)), c.getLong(7))) } }

    fun deleteReminder(documentId: String) = writableDatabase.delete("reminders", "document_id=?", arrayOf(documentId))

    fun linkedDocumentIds(policyDocumentId: String): Set<String> = readableDatabase.rawQuery(
        "SELECT supporting_document_id FROM document_links WHERE policy_document_id=? ORDER BY created_at",
        arrayOf(policyDocumentId)
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun replaceLinkedDocuments(policyDocumentId: String, supportingDocumentIds: Set<String>) {
        require(policyDocumentId !in supportingDocumentIds) { "A document cannot support itself" }
        writableDatabase.inTransaction {
            delete("document_links", "policy_document_id=?", arrayOf(policyDocumentId))
            supportingDocumentIds.forEach { supportingId ->
                insertOrThrow("document_links", null, ContentValues().apply {
                    put("policy_document_id", policyDocumentId)
                    put("supporting_document_id", supportingId)
                    put("created_at", System.currentTimeMillis())
                })
            }
        }
    }

    fun loadLinkedDocuments(policyDocumentId: String): List<StoredDocument> =
        linkedDocumentIds(policyDocumentId).mapNotNull(::load)

    fun listDocuments(): List<SavedDocumentSummary> {
        val sql = """SELECT d.id,d.original_name,d.document_type,d.mime_type,d.page_count,d.created_at,d.local_path,
            MAX(CASE WHEN f.field_key='POLICY_NUMBER' THEN COALESCE(f.user_value,f.original_value) END),
            MAX(CASE WHEN f.field_key='INSURED_NAME' THEN COALESCE(f.user_value,f.original_value) END),
            MAX(CASE WHEN f.field_key='POLICY_EXPIRY' THEN COALESCE(f.user_value,f.original_value) END)
            FROM documents d LEFT JOIN insurance_fields f ON d.id=f.document_id
            GROUP BY d.id ORDER BY d.created_at DESC"""
        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SavedDocumentSummary(
                    cursor.getString(0), cursor.getString(1), DocumentType.valueOf(cursor.getString(2)),
                    cursor.getString(3), cursor.getInt(4), cursor.getLong(5),
                    cursor.getString(7), cursor.getString(8), cursor.getString(9), File(cursor.getString(6)).isFile
                ))
            }
        }
    }

    fun load(documentId: String): StoredDocument? {
        val document = readableDatabase.rawQuery(
            "SELECT original_name,document_type,mime_type,page_count,local_path FROM documents WHERE id=?", arrayOf(documentId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            SelectedDocument(documentId, Uri.fromFile(File(cursor.getString(4))), cursor.getString(0),
                DocumentType.valueOf(cursor.getString(1)), cursor.getString(2), cursor.getInt(3))
        }
        val pages = readableDatabase.rawQuery(
            "SELECT page_number,page_text FROM ocr_pages WHERE document_id=? ORDER BY page_number", arrayOf(documentId)
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val page = cursor.getInt(0)
                add(OcrPageResult(page, cursor.getString(1), loadLines(documentId, page)))
            }
        } }
        val fields = readableDatabase.rawQuery(
            "SELECT field_key,original_value,status,review_reason,user_value,user_confirmed FROM insurance_fields WHERE document_id=? ORDER BY rowid",
            arrayOf(documentId)
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val key = InsuranceFieldKey.valueOf(cursor.getString(0))
                add(InsuranceField(key, cursor.nullableString(1), loadFieldEvidence(documentId, key),
                    VerificationStatus.valueOf(cursor.getString(2)), cursor.nullableString(3), cursor.nullableString(4), cursor.getInt(5) == 1))
            }
        } }
        return StoredDocument(document, pages, fields)
    }

    fun delete(documentId: String): Boolean {
        val path = readableDatabase.rawQuery("SELECT local_path FROM documents WHERE id=?", arrayOf(documentId)).use {
            if (!it.moveToFirst()) return true else it.getString(0)
        }
        val file = File(path)
        if (file.exists() && !file.delete()) return false
        return writableDatabase.delete("documents", "id=?", arrayOf(documentId)) > 0
    }

    private fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    private fun SQLiteDatabase.insertEvidenceRow(table: String, documentId: String, key: String?, order: Int, line: OcrLineEvidence) {
        insertOrThrow(table, null, ContentValues().apply {
            put("document_id", documentId)
            if (key == null) { put("page_number", line.pageNumber); put("line_order", order) }
            else { put("field_key", key); put("evidence_order", order); put("page_number", line.pageNumber) }
            put("line_text", line.text); put("confidence", line.confidence)
            put("source_width", line.sourceWidth); put("source_height", line.sourceHeight)
            line.boundingBox?.let { put("box_left", it.left); put("box_top", it.top); put("box_right", it.right); put("box_bottom", it.bottom) }
        })
    }

    private fun loadLines(documentId: String, page: Int): List<OcrLineEvidence> = readableDatabase.rawQuery(
        "SELECT line_text,box_left,box_top,box_right,box_bottom,confidence,source_width,source_height FROM ocr_lines WHERE document_id=? AND page_number=? ORDER BY line_order",
        arrayOf(documentId, page.toString())
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.evidence(page)) } }

    private fun loadFieldEvidence(documentId: String, key: InsuranceFieldKey): List<OcrLineEvidence> = readableDatabase.rawQuery(
        "SELECT line_text,box_left,box_top,box_right,box_bottom,confidence,source_width,source_height,page_number FROM field_evidence WHERE document_id=? AND field_key=? ORDER BY evidence_order",
        arrayOf(documentId, key.name)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.evidence(cursor.getInt(8))) } }

    private fun android.database.Cursor.evidence(page: Int): OcrLineEvidence {
        val box = if (isNull(1) || isNull(2) || isNull(3) || isNull(4)) null else Rect(getInt(1), getInt(2), getInt(3), getInt(4))
        return OcrLineEvidence(page, getString(0), box, getFloat(5), getInt(6), getInt(7))
    }

    private fun android.database.Cursor.nullableString(index: Int) = if (isNull(index)) null else getString(index)

    private fun fieldValues(documentId: String, field: InsuranceField) = ContentValues().apply {
        put("document_id", documentId); put("field_key", field.key.name); put("original_value", field.value)
        put("status", field.status.name); put("review_reason", field.reviewReason); put("user_value", field.userValue)
        put("user_confirmed", if (field.isUserConfirmed) 1 else 0)
    }

    private fun safeExtension(name: String, type: DocumentType): String {
        val candidate = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
        return candidate.takeIf { it.isNotBlank() } ?: if (type == DocumentType.PDF) "pdf" else "img"
    }

    private fun createRemindersTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS reminders (
            document_id TEXT NOT NULL, deadline_type TEXT NOT NULL, deadline_epoch_day INTEGER NOT NULL,
            source_value TEXT NOT NULL, source_status TEXT NOT NULL, offsets_days TEXT NOT NULL,
            state TEXT NOT NULL, updated_at INTEGER NOT NULL,
            PRIMARY KEY(document_id, deadline_type),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
    }

    private fun createDocumentLinksTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS document_links (
            policy_document_id TEXT NOT NULL, supporting_document_id TEXT NOT NULL, created_at INTEGER NOT NULL,
            PRIMARY KEY(policy_document_id, supporting_document_id),
            CHECK(policy_document_id <> supporting_document_id),
            FOREIGN KEY(policy_document_id) REFERENCES documents(id) ON DELETE CASCADE,
            FOREIGN KEY(supporting_document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
    }

    /** Mission OS state: only user decisions are stored; facts are re-derived from saved OCR text on demand. */
    private fun createMissionTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS fact_resolutions (
            fact_type TEXT PRIMARY KEY, chosen_normalized TEXT NOT NULL, resolved_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS fact_corrections (
            document_id TEXT NOT NULL, fact_type TEXT NOT NULL, original_normalized TEXT NOT NULL,
            corrected_value TEXT NOT NULL, corrected_at INTEGER NOT NULL,
            PRIMARY KEY(document_id, fact_type, original_normalized),
            FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finding_states (
            finding_id TEXT PRIMARY KEY, state TEXT NOT NULL, note TEXT, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS missions (
            id TEXT PRIMARY KEY, goal TEXT NOT NULL, planned_by TEXT NOT NULL, phase TEXT NOT NULL,
            steps_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS form_values (
            form_id TEXT NOT NULL, field_id TEXT NOT NULL, value TEXT NOT NULL, source TEXT NOT NULL, filled_at INTEGER NOT NULL,
            PRIMARY KEY(form_id, field_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_corrections_doc ON fact_corrections(document_id)")
    }

    companion object { private const val DB_NAME = "kaagaz.db"; private const val DB_VERSION = 5 }
}
