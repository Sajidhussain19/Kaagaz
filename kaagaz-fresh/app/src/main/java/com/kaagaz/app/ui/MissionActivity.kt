package com.kaagaz.app.ui

import com.kaagaz.app.llm.Services
import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.kaagaz.app.audit.CrashTest
import com.kaagaz.app.audit.EvidenceRef
import com.kaagaz.app.audit.Finding
import com.kaagaz.app.audit.FindingKind
import com.kaagaz.app.audit.Severity
import com.kaagaz.app.data.KaagazStore
import com.kaagaz.app.data.KnowledgeService
import com.kaagaz.app.data.KnowledgeSnapshot
import com.kaagaz.app.forms.FormLibrary
import com.kaagaz.app.forms.FormMapper
import com.kaagaz.app.forms.FormSpec
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.Fact
import com.kaagaz.app.knowledge.FactScope
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.LedgerEntry
import com.kaagaz.app.knowledge.Normalizer
import com.kaagaz.app.knowledge.TrustLedger
import com.kaagaz.app.knowledge.TrustState
import com.kaagaz.app.llm.GemmaRuntime
import com.kaagaz.app.llm.LlmClient
import com.kaagaz.app.llm.NoLlm
import com.kaagaz.app.mission.MissionAction
import com.kaagaz.app.mission.MissionPhase
import com.kaagaz.app.mission.MissionPlanner
import com.kaagaz.app.mission.MissionRunner
import com.kaagaz.app.mission.StepStatus
import com.kaagaz.app.rag.AnswerSource
import com.kaagaz.app.rag.GroundedAnswer
import com.kaagaz.app.rag.GroundedAnswerer
import com.kaagaz.app.reminders.ReminderScheduler
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

class MissionActivity : ComponentActivity() {
    private enum class Screen { HOME, ASK, LEDGER, CRASH, MISSION, FORM, MODEL }

    private lateinit var ui: Ui
    private lateinit var store: KaagazStore
    private lateinit var knowledge: KnowledgeService
    private lateinit var gemma: GemmaRuntime
    private val worker = Executors.newFixedThreadPool(2)
    private var screen = Screen.HOME
    private var snap: KnowledgeSnapshot? = null

    // Screen state
    private var lastAnswer: GroundedAnswer? = null
    private var askBusy = false
    private var crashSelection: MutableSet<String>? = null
    private var crashWithForm = true
    private var crashResults: List<Finding>? = null
    private var crashBusy = false
    private var mission: MissionRunner? = null
    private var missionId: String? = null
    private var missionBusy = false
    private var formSpec: FormSpec = FormLibrary.universityApplication
    private var modelBusyText: String? = null
    private var pendingApproval: Set<MissionAction>? = null

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::copyModel) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingApproval?.let { approved -> pendingApproval = null; executeApproved(approved) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = KaagazStore(applicationContext)
        knowledge = KnowledgeService(applicationContext, store)
        gemma = Services.gemma(this)
        // Auto-load an imported model in the background. The runtime's crash marker turns a model that killed the
        // app last time into FAILED, so this can never loop.
        if (gemma.state.status == GemmaRuntime.Status.INSTALLED) gemma.load { runOnUiThread { if (!isDestroyed) render() } }
        worker.execute {
            runCatching {
                val version = if (Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode else 0L
                ReminderScheduler(applicationContext).rescheduleAllOnUpgrade(applicationContext, store.listReminders(), version)
            }
        }
        window.statusBarColor = Palette.ground; window.navigationBarColor = Palette.ground
        screen = savedInstanceState?.getString("screen")?.let { runCatching { Screen.valueOf(it) }.getOrNull() } ?: Screen.HOME
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (screen == Screen.HOME) finish() else go(Screen.HOME) }
        })
    }

    override fun onResume() {
        super.onResume()
        knowledge.invalidate(); refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("screen", screen.name) }

    override fun onDestroy() { worker.shutdownNow(); store.close(); super.onDestroy() }

    private fun <T> bg(task: () -> T, done: (Result<T>) -> Unit) {
        worker.execute { val r = runCatching(task); runOnUiThread { if (!isDestroyed) done(r) } }
    }

    private fun refresh() {
        if (snap == null) render()
        bg({ knowledge.snapshot() }) { r -> r.onSuccess { snap = it }.onFailure { toast("Could not read saved documents: ${it.message}") }; render() }
    }

    private fun go(target: Screen) { screen = target; render() }

    private fun render() {
        val content = ui.column(20, 22).apply { setBackgroundColor(Palette.ground) }
        when (screen) {
            Screen.HOME -> home(content)
            Screen.ASK -> ask(content)
            Screen.LEDGER -> ledger(content)
            Screen.CRASH -> crash(content)
            Screen.MISSION -> missionScreen(content)
            Screen.FORM -> form(content)
            Screen.MODEL -> model(content)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Palette.ground); addView(content); padForSystemBars() }
        setContentView(scroll)
    }

    private fun header(content: LinearLayout, eyebrow: String, title: String, subtitle: String? = null) {
        content.addView(ui.link("←  Mission control") { go(Screen.HOME) })
        content.addView(ui.eyebrow(eyebrow), ui.lp(10))
        content.addView(ui.text(title, 30f, Palette.ink, bold = true), ui.lp(8))
        subtitle?.let { content.addView(ui.text(it, 14.5f, Palette.muted), ui.lp(8)) }
    }

    private fun loading(content: LinearLayout, message: String) {
        val box = ui.card(Palette.mint).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(PulseView(this), LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
        box.addView(ui.text(message, 14f, Palette.muted).apply { gravity = Gravity.CENTER }, ui.lp(10))
        content.addView(box, ui.lp(18))
    }

    // ================================================================= HOME

    private fun home(content: LinearLayout) {
        val s = snap
        val top = ui.row()
        top.addView(ui.eyebrow("Kaagaz · Mission OS"), ui.weight())
        top.addView(modelChip().apply { setOnClickListener { go(Screen.MODEL) } })
        content.addView(top)
        val hour = LocalTime.now().hour
        val greeting = when (hour) { in 5..11 -> "Good morning."; in 12..16 -> "Good afternoon."; else -> "Good evening." }
        content.addView(ui.text(greeting, 34f, Palette.ink, bold = true), ui.lp(18))
        content.addView(ui.text("Your life, documented. Your paperwork, handled.", 15f, Palette.muted), ui.lp(6))

        val privacy = ui.row()
        privacy.addView(ui.chip("● ON-DEVICE", Palette.mint))
        privacy.addView(ui.chip(if (hasInternetPermission()) "NETWORK PERMISSION PRESENT" else "NO NETWORK PERMISSION", if (hasInternetPermission()) Palette.red else Palette.mint), ui.weight(0f, 8).apply { width = -2 })
        privacy.addView(ui.chip("${s?.summaries?.size ?: 0} DOCS", Palette.blue), ui.weight(0f, 8).apply { width = -2 })
        content.addView(privacy, ui.lp(14))

        // Start a mission
        val missionCard = ui.card(Palette.mint, Palette.raised)
        missionCard.addView(ui.eyebrow("Mission mode"))
        missionCard.addView(ui.text("Start a Mission", 24f, Palette.ink, bold = true), ui.lp(6))
        missionCard.addView(ui.text("Describe a goal. Kaagaz plans it, checks your documents, and asks before changing anything.", 13.5f, Palette.muted), ui.lp(6))
        val goal = ui.input("e.g. Prepare my university application and show what is missing", 2)
        missionCard.addView(goal, ui.lp(14))
        val examples = listOf("Prepare my university application and show me what is missing", "Remind me before my electricity bill is due", "Save my verified details to my profile")
        val chips = ui.column()
        examples.forEach { ex -> chips.addView(ui.chip("↳ $ex", Palette.muted).apply { setOnClickListener { goal.setText(ex) } }, ui.lp(6, -2)) }
        missionCard.addView(chips, ui.lp(8))
        missionCard.addView(ui.primary("Launch mission") { startMission(goal.text.toString()) }, ui.lp(14))
        content.addView(missionCard, ui.lp(18))

        mission?.let { m ->
            val live = ui.card(if (m.phase == MissionPhase.FAILED) Palette.red else Palette.lime)
            val r = ui.row()
            if (missionBusy) r.addView(PulseView(this, Palette.lime), LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
            r.addView(ui.text("Mission · ${m.phase.name.lowercase().replaceFirstChar { it.uppercase() }}", 15f, Palette.ink, bold = true), ui.weight(1f, 10))
            live.addView(r)
            live.addView(ui.text(m.plan.goal, 13f, Palette.muted).apply { maxLines = 2 }, ui.lp(6))
            live.addView(progress(m), ui.lp(10))
            live.setOnClickListener { go(Screen.MISSION) }
            content.addView(live, ui.lp(12))
        }

        // Quick actions
        content.addView(ui.eyebrow("Quick actions", Palette.muted), ui.lp(26))
        val grid = GridLayout(this).apply { columnCount = 2 }
        listOf(
            Triple("Ask Kaagaz", "Grounded answers with page evidence", Palette.mint) to { go(Screen.ASK) },
            Triple("Scan document", "Import a PDF or photo, OCR on device", Palette.blue) to { startActivity(Intent(this, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_PICK_DOCUMENT, true)) },
            Triple("Prepare form", "Field-by-field with sources", Palette.lime) to { go(Screen.FORM) },
            Triple("Audit documents", "Run the Paperwork Crash Test", Palette.amber) to { go(Screen.CRASH) }
        ).forEachIndexed { i, (meta, action) ->
            val tile = ui.column(14, 14).apply { background = ui.rounded(Palette.surface, 20, Palette.line); minimumHeight = ui.dp(112); setOnClickListener { action() } }
            tile.addView(ui.text("◆", 18f, meta.third))
            tile.addView(ui.text(meta.first, 16f, Palette.ink, bold = true), ui.lp(10))
            tile.addView(ui.text(meta.second, 12f, Palette.muted), ui.lp(4))
            grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(i / 2, 1f), GridLayout.spec(i % 2, 1f)).apply {
                width = 0; setMargins(if (i % 2 == 0) 0 else ui.dp(6), ui.dp(6), if (i % 2 == 0) ui.dp(6) else 0, ui.dp(6))
            })
        }
        content.addView(grid, ui.lp(6))

        // Trust ledger
        content.addView(ui.eyebrow("Trust ledger", Palette.muted), ui.lp(22))
        val ledgerCard = ui.card()
        val lr = ui.row()
        val ring = TrustRingView(this).apply { counts = s?.counts ?: TrustLedger.Counts(0, 0, 0, 0) }
        lr.addView(ring, LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)))
        val stats = ui.column()
        val c = s?.counts
        listOf("Verified" to (c?.verified to Palette.mint), "Conflicting" to (c?.conflicting to Palette.red), "Needs confirmation" to (c?.needsConfirmation to Palette.amber), "Missing" to (c?.missing to Palette.faint)).forEach { (name, v) ->
            val line = ui.row()
            line.addView(ui.text("●", 12f, v.second))
            line.addView(ui.text(name, 13.5f, Palette.muted), ui.weight(1f, 8))
            line.addView(ui.text(v.first?.toString() ?: "…", 15f, Palette.ink, bold = true))
            stats.addView(line, ui.lp(5))
        }
        lr.addView(stats, ui.weight(1f, 18))
        ledgerCard.addView(lr)
        ledgerCard.addView(ui.text(if (s == null) "Reading your documents…" else "Person facts from ${s.documents.count { it.kind in TrustLedger.personKinds }} identity documents. Every value links to its source line.", 12.5f, Palette.faint), ui.lp(12))
        ledgerCard.setOnClickListener { go(Screen.LEDGER) }
        content.addView(ledgerCard, ui.lp(8))

        // Bills & deadlines
        content.addView(ui.eyebrow("Bills & deadlines", Palette.muted), ui.lp(22))
        val deadlines = deadlines()
        if (deadlines.isEmpty()) content.addView(emptyCard(if (s == null) "Loading…" else "No due dates found yet. Bills and policies you scan will appear here."), ui.lp(8))
        deadlines.take(4).forEach { d ->
            val card = ui.card(if (d.days < 0) Palette.red else if (d.days <= 14) Palette.amber else null)
            val r = ui.row()
            val col = ui.column()
            col.addView(ui.text(d.title, 15.5f, Palette.ink, bold = true))
            col.addView(ui.text("${d.docName} · p.${d.page}", 12f, Palette.faint), ui.lp(4))
            r.addView(col, ui.weight())
            r.addView(ui.chip(when { d.days < 0 -> "${-d.days}D OVERDUE"; d.days == 0L -> "TODAY"; else -> "IN ${d.days}D" }, if (d.days < 0) Palette.red else if (d.days <= 14) Palette.amber else Palette.mint))
            card.addView(r)
            card.setOnClickListener { openDocument(d.docId) }
            content.addView(card, ui.lp(8))
        }

        // Recent documents
        val rr = ui.row()
        rr.addView(ui.eyebrow("Recent documents", Palette.muted), ui.weight())
        rr.addView(ui.link("All →") { startActivity(Intent(this, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_LIBRARY, true)) })
        content.addView(rr, ui.lp(16))
        val recents = s?.summaries.orEmpty().take(5)
        if (recents.isEmpty()) content.addView(emptyCard(if (s == null) "Loading…" else "No documents yet. Tap Scan document to add one."), ui.lp(4))
        recents.forEach { d ->
            val kind = s?.documents?.firstOrNull { it.id == d.id }?.kind
            val card = ui.card()
            val r = ui.row()
            val col = ui.column()
            col.addView(ui.text(d.name, 15f, Palette.ink, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            col.addView(ui.text("${d.pageCount} page${if (d.pageCount == 1) "" else "s"} · ${if (kind == null) "OCR not run" else kind.title}", 12f, Palette.faint), ui.lp(4))
            r.addView(col, ui.weight())
            r.addView(ui.text("›", 22f, Palette.faint))
            card.addView(r)
            card.setOnClickListener { openDocument(d.id) }
            content.addView(card, ui.lp(8))
        }

        content.addView(ui.ghost("Privacy, model & attributions", Palette.muted) { go(Screen.MODEL) }, ui.lp(24))
    }

    private fun modelChip(): TextView {
        val st = gemma.state
        return when (st.status) {
            GemmaRuntime.Status.VERIFIED -> ui.chip("GEMMA · ${st.backend}", Palette.lime, filled = true)
            GemmaRuntime.Status.LOADING -> ui.chip("GEMMA · LOADING", Palette.amber)
            GemmaRuntime.Status.INSTALLED -> ui.chip("GEMMA · NOT LOADED", Palette.amber)
            GemmaRuntime.Status.FAILED -> ui.chip("GEMMA · FAILED", Palette.red)
            GemmaRuntime.Status.NOT_INSTALLED -> ui.chip("NO LLM · RULES", Palette.muted)
        }
    }

    private data class Deadline(val title: String, val days: Long, val docId: String, val docName: String, val page: Int)

    private fun deadlines(): List<Deadline> {
        val s = snap ?: return emptyList(); val today = LocalDate.now()
        return s.facts.filter { it.type == FactType.DUE_DATE || it.type == FactType.POLICY_EXPIRY }.mapNotNull { f ->
            val date = runCatching { LocalDate.parse(f.normalized) }.getOrNull() ?: return@mapNotNull null
            val amount = s.facts.firstOrNull { it.documentId == f.documentId && it.type == FactType.BILL_AMOUNT }
            val what = if (f.type == FactType.POLICY_EXPIRY) "Policy expires" else (amount?.let { "${Normalizer.display(FactType.BILL_AMOUNT, it.normalized, it.rawValue)} due" } ?: "Payment due")
            Deadline("$what · ${Normalizer.display(f.type, f.normalized, f.rawValue)}", ChronoUnit.DAYS.between(today, date), f.documentId, f.documentName, f.page)
        }.filter { it.days > -60 }.sortedWith(compareBy({ it.days < 0 }, { it.days })).distinctBy { it.docName + it.title }
    }

    private fun emptyCard(message: String) = ui.card().apply { addView(ui.text(message, 13.5f, Palette.muted)) }

    private fun openDocument(id: String) = startActivity(Intent(this, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_OPEN_DOCUMENT, id))

    // ================================================================= ASK

    private fun ask(content: LinearLayout) {
        header(content, "Ask Kaagaz", "Grounded answers", "Answers come only from your saved documents. Evidence is one tap away.")
        val llmLine = if (gemma.isReady) "Gemma ${gemma.state.backend} verified · answers checked against evidence" else "No language model loaded · deterministic answers, clearly labelled"
        content.addView(ui.chip(llmLine.uppercase(), if (gemma.isReady) Palette.lime else Palette.muted), ui.lp(12, -2))
        val input = ui.input("Ask about your documents…")
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        lastAnswer?.let { input.setText(it.question) }
        content.addView(input, ui.lp(16))
        val submit = { submitQuestion(input.text.toString()) }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        content.addView(ui.primary(if (askBusy) "Thinking…" else "Ask") { if (!askBusy) submit() }, ui.lp(10))
        if (askBusy) loading(content, if (gemma.isReady) "Retrieving evidence and running Gemma on device…" else "Retrieving evidence…")
        lastAnswer?.takeIf { !askBusy }?.let { content.addView(answerCard(it), ui.lp(18)) }
        if (lastAnswer == null && !askBusy) {
            content.addView(ui.eyebrow("Try", Palette.muted), ui.lp(22))
            listOf("What is my graduation year?", "When is the electricity bill due?", "Does this policy cover cancer?", "What is the outstanding premium?", "What is my email?").forEach { q ->
                content.addView(ui.chip("↳ $q", Palette.muted).apply { setOnClickListener { submitQuestion(q) } }, ui.lp(8, -2))
            }
        }
    }

    private fun submitQuestion(q: String) {
        if (q.isBlank()) return
        val s = snap ?: return toast("Still reading documents…")
        askBusy = true; render()
        val llm: LlmClient = if (gemma.isReady) gemma else NoLlm
        bg({ GroundedAnswerer(llm).answer(q, s.corpus) }) { r ->
            askBusy = false; lastAnswer = r.getOrElse { GroundedAnswer(q, "Something went wrong: ${it.message}", AnswerSource.ABSTAIN, emptyList()) }; render()
        }
    }

    private fun answerCard(a: GroundedAnswer): View {
        val color = when (a.source) { AnswerSource.GEMMA -> Palette.lime; AnswerSource.CONFLICT -> Palette.red; AnswerSource.ABSTAIN -> Palette.faint; else -> Palette.mint }
        val card = ui.card(color, Palette.raised)
        card.addView(ui.chip(a.source.label.uppercase(), color))
        card.addView(ui.text(a.text, 19f, Palette.ink, bold = true), ui.lp(12))
        a.note?.let { card.addView(ui.text(it, 12.5f, Palette.muted), ui.lp(8)) }
        if (a.evidence.isNotEmpty()) {
            val evidenceBox = ui.column().apply { visibility = View.GONE }
            a.evidence.forEach { evidenceBox.addView(evidenceRow(it), ui.lp(8)) }
            card.addView(ui.link("Show evidence (${a.evidence.size}) ▾") {}.apply {
                setOnClickListener { evidenceBox.visibility = if (evidenceBox.visibility == View.GONE) View.VISIBLE else View.GONE; text = if (evidenceBox.visibility == View.GONE) "Show evidence (${a.evidence.size}) ▾" else "Hide evidence ▴" }
            }, ui.lp(6))
            card.addView(evidenceBox)
        }
        if (a.retrieved.isNotEmpty()) {
            val how = ui.column().apply { visibility = View.GONE }
            a.retrieved.forEachIndexed { i, h ->
                how.addView(ui.text("[${i + 1}] ${h.chunk.documentName} p.${h.chunk.page} · BM25 ${"%.2f".format(h.bm25)}${h.bm25Rank?.let { " (#${it + 1})" } ?: ""}${h.factRank?.let { " · fact match #${it + 1}" } ?: ""} · terms ${h.matched.joinToString()}", 11f, Palette.faint, mono = true), ui.lp(4))
            }
            how.addView(ui.text("Hybrid rank = reciprocal-rank fusion of BM25 and structured-fact matches. No embedding model in this build.", 11f, Palette.faint), ui.lp(6))
            card.addView(ui.link("How this was answered ▾", Palette.muted) {}.apply { setOnClickListener { how.visibility = if (how.visibility == View.GONE) View.VISIBLE else View.GONE } })
            card.addView(how)
        }
        if (a.followUps.isNotEmpty()) {
            card.addView(ui.divider())
            a.followUps.forEach { f ->
                card.addView(ui.chip("↳ $f", Palette.mint).apply {
                    setOnClickListener {
                        when { f.contains("Trust Ledger") -> go(Screen.LEDGER); f.contains("Crash Test") -> go(Screen.CRASH); f.startsWith("Scan") -> startActivity(Intent(this@MissionActivity, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_PICK_DOCUMENT, true)); else -> submitQuestion(f) }
                    }
                }, ui.lp(6, -2))
            }
        }
        return card
    }

    private fun evidenceRow(e: EvidenceRef): View {
        val box = ui.column(14, 12).apply { background = ui.rounded(Palette.surface, 14, Palette.line) }
        box.addView(ui.text("${e.documentName} · page ${e.page}", 11.5f, Palette.blue, mono = true))
        box.addView(ui.text("“${e.text.take(280)}”", 13.5f, Palette.ink), ui.lp(6))
        box.setOnClickListener { openDocument(e.documentId) }
        return box
    }

    // ================================================================= LEDGER

    private fun ledger(content: LinearLayout) {
        header(content, "Trust ledger", "Proof-carrying facts", "Each fact keeps its source document, page, extraction method and status. Conflicts are never overwritten.")
        val s = snap ?: return loading(content, "Reading documents…")
        val graphCard = ui.card()
        graphCard.addView(ui.eyebrow("Evidence graph", Palette.muted))
        graphCard.addView(EvidenceGraphView(this).apply { bind(s.ledger) }, ui.lp(8))
        val legend = ui.row()
        TrustState.values().forEach { st -> legend.addView(ui.chip(st.label, Palette.state(st)), ui.weight(0f, 4).apply { width = -2 }) }
        graphCard.addView(legend, ui.lp(8))
        content.addView(graphCard, ui.lp(16))

        listOf(TrustState.CONFLICTING, TrustState.NEEDS_CONFIRMATION, TrustState.VERIFIED, TrustState.MISSING).forEach { state ->
            val entries = s.ledger.filter { it.state == state }
            if (entries.isEmpty()) return@forEach
            content.addView(ui.eyebrow("${state.label} · ${entries.size}", Palette.state(state)), ui.lp(22))
            entries.forEach { content.addView(ledgerCard(it), ui.lp(8)) }
        }
        val docFacts = s.facts.filter { it.type.scope == FactScope.DOCUMENT }.groupBy { it.documentName }
        if (docFacts.isNotEmpty()) {
            content.addView(ui.eyebrow("Document facts (bills, policies)", Palette.muted), ui.lp(22))
            docFacts.entries.take(8).forEach { (doc, facts) ->
                val card = ui.card()
                card.addView(ui.text(doc, 14.5f, Palette.ink, bold = true))
                facts.take(6).forEach { f -> card.addView(ui.text("${f.type.label}: ${Normalizer.display(f.type, f.normalized, f.rawValue)}  ·  p.${f.page} · ${f.method.label}", 12.5f, Palette.muted), ui.lp(5)) }
                card.setOnClickListener { openDocument(facts.first().documentId) }
                content.addView(card, ui.lp(8))
            }
        }
    }

    private fun ledgerCard(e: LedgerEntry): View {
        val color = Palette.state(e.state)
        val card = ui.card(color)
        val r = ui.row()
        r.addView(ui.text(e.type.label, 16f, Palette.ink, bold = true), ui.weight())
        r.addView(ui.chip(if (e.resolvedByUser) "CHOSEN BY YOU" else e.state.label.uppercase(), color))
        card.addView(r)
        card.addView(ui.text(e.reason, 12.5f, Palette.muted), ui.lp(6))
        e.groups.forEach { g ->
            val gBox = ui.column(12, 10).apply { background = ui.rounded(Palette.raised, 14, Palette.line) }
            gBox.addView(ui.text(g.display, 17f, Palette.ink, bold = true))
            g.facts.forEach { f -> gBox.addView(ui.text("${f.documentName} · p.${f.page} · ${f.method.label}${if (f.confidence < TrustLedger.MIN_CONFIDENCE) " · low OCR confidence" else ""}\n“${f.evidence.take(140)}”", 11.5f, Palette.faint), ui.lp(6)) }
            if (e.state == TrustState.CONFLICTING || e.state == TrustState.NEEDS_CONFIRMATION || (e.resolvedByUser && g != e.groups.first())) {
                gBox.addView(ui.ghost("Use “${g.display}”") { confirm("Use ${g.display} for ${e.type.label}?", "The other sources stay visible and the conflict stays on record. You can undo this.") { knowledge.resolve(e.type, g.normalized); refreshSoon() } }, ui.lp(8))
            }
            gBox.addView(ui.link("Correct this value", Palette.muted) { correctDialog(g.facts.first()) })
            card.addView(gBox, ui.lp(10))
        }
        if (e.resolvedByUser) card.addView(ui.link("Undo my choice", Palette.amber) { knowledge.clearResolution(e.type); refreshSoon() })
        if (e.state == TrustState.MISSING) card.addView(ui.ghost("Scan a document that states it") { startActivity(Intent(this, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_PICK_DOCUMENT, true)) }, ui.lp(10))
        return card
    }

    private fun correctDialog(fact: Fact) {
        val input = EditText(this).apply { setText(fact.rawValue); setTextColor(Palette.ink) }
        AlertDialog.Builder(this).setTitle("Correct ${fact.type.label}")
            .setMessage("Source: ${fact.documentName}, page ${fact.page}. Your correction is stored separately and the original OCR value is kept as provenance.")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save correction") { _, _ ->
                val v = input.text.toString().trim()
                if (Normalizer.forType(fact.type, v) == null) toast("That doesn't look like a valid ${fact.type.label.lowercase()}.") else { knowledge.correct(fact, v); refreshSoon() }
            }.show()
    }

    private fun refreshSoon() { snap = null; knowledge.invalidate(); crashResults = null; refresh() }

    // ================================================================= CRASH TEST

    private fun crash(content: LinearLayout) {
        header(content, "Paperwork crash test", "Stress-test your papers", "Finds conflicting records, missing facts and documents, duplicate bills, deadlines, unsupported values and over-reaching data requests.")
        val s = snap ?: return loading(content, "Reading documents…")
        if (s.documents.isEmpty()) return content.addView(emptyCard("No documents with extracted text yet. Scan a document first."), ui.lp(16))
        // Default: the papers a crash test is most useful for (identity documents, bills, statements); policies opt-in.
        val selection = crashSelection ?: s.documents.filter { it.kind != DocKind.INSURANCE_POLICY }.map { it.id }
            .ifEmpty { s.documents.map { it.id } }.toMutableSet().also { crashSelection = it }
        val pick = ui.card()
        pick.addView(ui.eyebrow("Documents in this test · ${selection.size}/${s.documents.size}", Palette.muted))
        s.documents.forEach { d ->
            pick.addView(CheckBox(this).apply {
                text = "${d.name}  ·  ${d.kind.title}"; setTextColor(Palette.ink); textSize = 13.5f; isChecked = d.id in selection
                buttonTintList = android.content.res.ColorStateList.valueOf(Palette.mint)
                setOnCheckedChangeListener { _, on -> if (on) selection += d.id else selection -= d.id; crashResults = null }
            }, ui.lp(2))
        }
        pick.addView(CheckBox(this).apply {
            text = "Also check against: ${FormLibrary.universityApplication.title}"; setTextColor(Palette.lime); textSize = 13.5f; isChecked = crashWithForm
            buttonTintList = android.content.res.ColorStateList.valueOf(Palette.lime)
            setOnCheckedChangeListener { _, on -> crashWithForm = on; crashResults = null }
        }, ui.lp(8))
        content.addView(pick, ui.lp(16))
        content.addView(ui.primary(if (crashBusy) "Running…" else "Run crash test") { runCrash() }, ui.lp(12))
        if (crashBusy) return loading(content, "Cross-checking names, dates, amounts, bills and form fields…")
        val results = crashResults ?: return
        val states = knowledge.findingStates()
        val open = results.filter { states[it.id] == null }; val closed = results.filter { states[it.id] != null }
        val summary = ui.card(if (open.any { it.severity == Severity.HIGH }) Palette.red else Palette.mint, Palette.raised)
        summary.addView(ui.text(if (open.isEmpty()) "No open findings" else "${open.size} open findings", 22f, Palette.ink, bold = true))
        val chips = ui.row()
        Severity.values().forEach { sev -> val n = open.count { it.severity == sev }; if (n > 0) chips.addView(ui.chip("$n ${sev.label.uppercase()}", Palette.severity(sev)), ui.weight(0f, 6).apply { width = -2 }) }
        summary.addView(chips, ui.lp(10))
        summary.addView(ui.text("Checked ${selection.size} documents${if (crashWithForm) " and 1 form" else ""}. Every finding below cites its source.", 12.5f, Palette.muted), ui.lp(10))
        content.addView(summary, ui.lp(18))
        open.forEach { content.addView(findingCard(it, null), ui.lp(10)) }
        if (closed.isNotEmpty()) {
            content.addView(ui.eyebrow("Resolved or dismissed · ${closed.size}", Palette.faint), ui.lp(22))
            closed.forEach { content.addView(findingCard(it, states[it.id]), ui.lp(8)) }
        }
    }

    private fun runCrash() {
        val s = snap ?: return
        val selected = s.documents.filter { it.id in (crashSelection ?: emptySet<String>()) }
        if (selected.isEmpty()) return toast("Select at least one document.")
        crashBusy = true; render()
        val form = if (crashWithForm) FormLibrary.universityApplication else null
        bg({
            Thread.sleep(700) // keep the scanning state visible long enough to read; the work itself is fast
            val facts = s.facts.filter { f -> selected.any { it.id == f.documentId } }
            val ledger = TrustLedger.build(facts, s.kinds, form?.fields?.filter { it.required }?.mapNotNull { it.type }?.toSet() ?: emptySet(), knowledge.resolutions())
            val draft = form?.let { knowledge.formValues(it.id).filter { (_, v) -> v.second == SOURCE_TYPED }.mapValues { it.value.first } } ?: emptyMap()
            CrashTest.run(selected, facts, ledger, form, draft)
        }) { r -> crashBusy = false; crashResults = r.getOrElse { toast("Crash test failed: ${it.message}"); emptyList() }; render() }
    }

    private fun findingCard(f: Finding, state: String?): View {
        val color = if (state != null) Palette.faint else Palette.severity(f.severity)
        val card = ui.card(color)
        val r = ui.row()
        r.addView(ui.chip(f.severity.label.uppercase(), color))
        r.addView(ui.text(f.kind.label, 11.5f, Palette.faint, mono = true), ui.weight(1f, 8))
        state?.let { r.addView(ui.chip(it, Palette.faint)) }
        card.addView(r)
        card.addView(ui.text(f.title, 16.5f, if (state != null) Palette.muted else Palette.ink, bold = true), ui.lp(10))
        card.addView(ui.text(f.explanation, 13.5f, Palette.muted), ui.lp(6))
        if (state == null) {
            if (f.evidence.isNotEmpty()) {
                val ev = ui.column().apply { visibility = View.GONE }
                f.evidence.forEach { ev.addView(evidenceRow(it), ui.lp(6)) }
                card.addView(ui.link("Evidence (${f.evidence.size}) ▾") {}.apply { setOnClickListener { ev.visibility = if (ev.visibility == View.GONE) View.VISIBLE else View.GONE } })
                card.addView(ev)
            }
            card.addView(ui.text("Next: ${f.nextAction}", 13f, Palette.lime), ui.lp(8))
            val actions = ui.row()
            if (f.kind == FindingKind.CONFLICT || f.kind == FindingKind.NAME_VARIANT || f.kind == FindingKind.LOW_CONFIDENCE) actions.addView(ui.ghost("Resolve in ledger") { go(Screen.LEDGER) }, ui.weight())
            else actions.addView(ui.ghost("Mark resolved") { confirm("Mark as resolved?", "“${f.title}” will move to the resolved list. You can reopen it.") { knowledge.setFindingState(f.id, "RESOLVED"); render() } }, ui.weight())
            actions.addView(ui.ghost("Dismiss", Palette.muted) { confirm("Dismiss this finding?", "Only dismiss it if you have checked the evidence. You can reopen it later.") { knowledge.setFindingState(f.id, "DISMISSED"); render() } }, ui.weight(1f, 8))
            card.addView(actions, ui.lp(10))
        } else card.addView(ui.link("Reopen", Palette.muted) { knowledge.setFindingState(f.id, null); render() })
        return card
    }

    // ================================================================= MISSION

    private fun startMission(goal: String) {
        if (goal.isBlank()) return toast("Describe a goal first.")
        if (missionBusy) return go(Screen.MISSION)
        missionBusy = true; mission = null; screen = Screen.MISSION; render()
        val llm: LlmClient = if (gemma.isReady) gemma else NoLlm
        val tools = knowledge.tools { hasNotificationPermission() }
        bg({
            val plan = MissionPlanner.plan(goal, llm)
            val id = KnowledgeService.newMissionId()
            val runner = MissionRunner(tools, plan) { r -> runOnUiThread { if (!isDestroyed) { mission = r; if (screen == Screen.MISSION || screen == Screen.HOME) render() } } }
            runOnUiThread { mission = runner; missionId = id; render() }
            runner.runAutomatic()
            knowledge.saveMission(id, runner)
            runner
        }) { r -> missionBusy = false; missionError = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }; snap = null; refresh() }
    }

    private var missionError: String? = null

    private fun executeApproved(approved: Set<MissionAction>) {
        val m = mission ?: return
        missionBusy = true; render()
        bg({ m.approve(approved); missionId?.let { knowledge.saveMission(it, m) } }) { missionBusy = false; knowledge.invalidate(); snap = null; refresh() }
    }

    private fun progress(m: MissionRunner): View {
        val done = m.steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = m.steps.size; this.progress = done
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.lime)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.line)
        }
    }

    private fun missionScreen(content: LinearLayout) {
        header(content, "Mission mode", "Agent workflow", "PLAN → RETRIEVE → EXTRACT → VALIDATE → PROPOSE → APPROVAL → EXECUTE → VERIFY")
        val m = mission
        if (m == null) {
            if (missionBusy) loading(content, if (gemma.isReady) "Gemma is planning the mission…" else "Planning with the rule-based planner…")
            else {
                missionError?.let { content.addView(ui.card(Palette.red).apply { addView(ui.text("The last mission stopped: $it", 13f, Palette.red)) }, ui.lp(14)) }
                val goal = ui.input("Describe a goal", 2); content.addView(goal, ui.lp(16))
                content.addView(ui.primary("Launch mission") { startMission(goal.text.toString()) }, ui.lp(10))
            }
            return
        }
        val planCard = ui.card(Palette.lime, Palette.raised)
        planCard.addView(ui.text(m.plan.goal, 18f, Palette.ink, bold = true))
        val pr = ui.row()
        pr.addView(ui.chip("PLANNED BY ${m.plan.plannedBy.uppercase()}", if (m.plan.plannedBy.contains("Rule")) Palette.muted else Palette.lime))
        planCard.addView(pr, ui.lp(10))
        m.plan.plannerNote?.let { planCard.addView(ui.text(it, 12.5f, Palette.muted), ui.lp(8)) }
        planCard.addView(ui.text("Phase: ${m.phase.name}", 13f, Palette.lime, mono = true), ui.lp(10))
        planCard.addView(progress(m), ui.lp(8))
        content.addView(planCard, ui.lp(16))

        m.steps.forEachIndexed { i, step ->
            val color = when (step.status) { StepStatus.DONE -> Palette.mint; StepStatus.RUNNING -> Palette.lime; StepStatus.NEEDS_APPROVAL -> Palette.amber; StepStatus.FAILED -> Palette.red; else -> Palette.faint }
            val card = ui.card(if (step.status == StepStatus.PENDING) null else color)
            val r = ui.row()
            if (step.status == StepStatus.RUNNING) r.addView(PulseView(this, Palette.lime), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
            else r.addView(ui.text(when (step.status) { StepStatus.DONE -> "✓"; StepStatus.FAILED -> "✕"; StepStatus.NEEDS_APPROVAL -> "!"; StepStatus.SKIPPED -> "–"; else -> "○" }, 16f, color, bold = true))
            r.addView(ui.text("${i + 1}. ${step.action.title}", 15f, Palette.ink, bold = true), ui.weight(1f, 10))
            if (step.action.consequential) r.addView(when (step.status) {
                StepStatus.DONE -> ui.chip("APPROVED · VERIFIED", Palette.mint)
                StepStatus.SKIPPED -> ui.chip("NOT APPROVED", Palette.faint)
                StepStatus.FAILED -> ui.chip("APPROVED · FAILED", Palette.red)
                else -> ui.chip("NEEDS APPROVAL", Palette.amber)
            })
            card.addView(r)
            step.result?.let { card.addView(ui.text(it, 13f, Palette.muted), ui.lp(6)) }
            step.evidence.take(2).forEach { card.addView(ui.text("↳ ${it.documentName} p.${it.page}: “${it.text.take(110)}”", 11.5f, Palette.faint), ui.lp(4)) }
            content.addView(card, ui.lp(8))
        }

        if (m.phase == MissionPhase.APPROVAL && !missionBusy) {
            val approval = ui.card(Palette.amber, Palette.raised)
            approval.addView(ui.eyebrow("Your approval is required", Palette.amber))
            approval.addView(ui.text("Nothing below has happened yet. Choose what Kaagaz may do.", 14f, Palette.ink), ui.lp(6))
            val boxes = m.pendingApproval.map { step ->
                CheckBox(this).apply {
                    text = step.action.title + approvalDetail(step.action); setTextColor(Palette.ink); isChecked = true; textSize = 14f
                    buttonTintList = android.content.res.ColorStateList.valueOf(Palette.amber)
                }.also { approval.addView(it, ui.lp(6)) } to step.action
            }
            approval.addView(ui.primary("Approve & execute") {
                val chosen = boxes.filter { it.first.isChecked }.map { it.second }.toSet()
                if (MissionAction.SCHEDULE_REMINDER in chosen && !hasNotificationPermission() && Build.VERSION.SDK_INT >= 33) {
                    pendingApproval = chosen; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else executeApproved(chosen)
            }, ui.lp(12))
            approval.addView(ui.ghost("Reject all", Palette.muted) { executeApproved(emptySet()) }, ui.lp(8))
            content.addView(approval, ui.lp(14))
        }
        if (missionBusy && m.phase != MissionPhase.APPROVAL) content.addView(ui.ghost("Cancel mission", Palette.red) { m.cancel() }, ui.lp(12))
        if (m.phase == MissionPhase.FAILED && !missionBusy) content.addView(ui.ghost("Retry failed steps", Palette.amber) {
            missionBusy = true; render(); bg({ m.retryFailed() }) { missionBusy = false; render() }
        }, ui.lp(12))

        if (m.phase in setOf(MissionPhase.DONE, MissionPhase.FAILED, MissionPhase.CANCELLED)) {
            val summary = ui.card(Palette.mint)
            summary.addView(ui.eyebrow("Mission summary"))
            val done = m.steps.filter { it.status == StepStatus.DONE }; val other = m.steps.filter { it.status != StepStatus.DONE }
            summary.addView(ui.text("Completed: ${done.size} · Not completed: ${other.size}", 16f, Palette.ink, bold = true), ui.lp(8))
            other.forEach { summary.addView(ui.text("• ${it.action.title}: ${it.status.name.lowercase()}${it.result?.let { r -> " — $r" } ?: ""}", 12.5f, Palette.muted), ui.lp(4)) }
            val r = ui.row()
            r.addView(ui.ghost("Open form") { go(Screen.FORM) }, ui.weight())
            r.addView(ui.ghost("Crash test") { crashResults = null; go(Screen.CRASH) }, ui.weight(1f, 8))
            summary.addView(r, ui.lp(12))
            content.addView(summary, ui.lp(16))
            content.addView(ui.ghost("New mission", Palette.muted) { mission = null; render() }, ui.lp(10))
        }
    }

    private fun approvalDetail(action: MissionAction) = when (action) {
        MissionAction.FILL_FORM -> "\nWrites verified values into the in-app form. Does not submit anything."
        MissionAction.SAVE_PROFILE -> "\nEncrypts verified facts with a key held in the Android Keystore."
        MissionAction.SCHEDULE_REMINDER -> "\nSchedules local notifications 7 days, 1 day and on the due date."
        else -> ""
    }

    // ================================================================= FORM

    private fun form(content: LinearLayout) {
        header(content, "Smart form", formSpec.title, "Kaagaz proposes only verified values and shows the source of each. It never submits.")
        val s = snap ?: return loading(content, "Reading documents…")
        val switch = ui.row()
        switch.addView(ui.ghost("Demo university form", if (formSpec.id == FormLibrary.universityApplication.id) Palette.lime else Palette.muted) { formSpec = FormLibrary.universityApplication; render() }, ui.weight())
        switch.addView(ui.ghost("Paste form labels", if (formSpec.id.startsWith("custom")) Palette.lime else Palette.muted) { pasteLabelsDialog() }, ui.weight(1f, 8))
        content.addView(switch, ui.lp(14))

        val ledger = TrustLedger.build(s.facts, s.kinds, formSpec.fields.mapNotNull { it.type }.toSet(), knowledge.resolutions())
        val proposals = FormMapper.map(formSpec, ledger)
        val saved = knowledge.formValues(formSpec.id)
        val fillable = proposals.filter { it.fillable }
        val sum = ui.card(Palette.lime, Palette.raised)
        sum.addView(ui.text("${fillable.size} of ${proposals.size} fields can be filled from evidence", 18f, Palette.ink, bold = true))
        val chips = ui.row()
        TrustState.values().forEach { st -> val n = proposals.count { it.state == st }; if (n > 0) chips.addView(ui.chip("$n ${st.label.uppercase()}", Palette.state(st)), ui.weight(0f, 6).apply { width = -2 }) }
        sum.addView(chips, ui.lp(10))
        content.addView(sum, ui.lp(14))

        content.addView(ui.eyebrow("Field-by-field preview", Palette.muted), ui.lp(20))
        proposals.forEach { p ->
            val color = if (p.field.sensitiveReason != null) Palette.violet else Palette.state(p.state)
            val card = ui.card(color)
            val r = ui.row()
            r.addView(ui.text(p.field.label + if (p.field.required) " *" else "", 14.5f, Palette.ink, bold = true), ui.weight())
            r.addView(ui.chip(if (p.field.sensitiveReason != null) "REVIEW FLAG" else p.state.label.uppercase(), color))
            card.addView(r)
            card.addView(ui.text(p.value ?: "—", 17f, if (p.value != null) Palette.ink else Palette.faint), ui.lp(6))
            card.addView(ui.text(if (p.fillable) "Source: ${p.note}" else p.note, 12f, Palette.muted), ui.lp(4))
            val edit = ui.input(if (p.field.sensitiveReason != null) "Not filled by Kaagaz" else "Value in the form")
            saved[p.field.id]?.let { edit.setText(it.first) }
            edit.isEnabled = p.field.sensitiveReason == null
            edit.setOnFocusChangeListener { _, has ->
                if (!has) {
                    val v = edit.text.toString().trim(); val prior = saved[p.field.id]
                    if (v.isNotEmpty() && v != prior?.first) { knowledge.saveFormValue(formSpec.id, p.field.id, v, if (v == p.value) p.note else SOURCE_TYPED) }
                }
            }
            card.addView(edit, ui.lp(10))
            saved[p.field.id]?.let { card.addView(ui.text(if (it.second == SOURCE_TYPED) "Typed by you · not backed by a document" else "Filled from evidence", 11f, if (it.second == SOURCE_TYPED) Palette.amber else Palette.mint, mono = true), ui.lp(4)) }
            content.addView(card, ui.lp(8))
        }
        content.addView(ui.primary("Fill ${fillable.size} verified fields") {
            if (fillable.isEmpty()) return@primary toast("No field has a verified value yet.")
            confirm("Fill ${fillable.size} fields?", fillable.joinToString("\n") { "• ${it.field.label}: ${it.value}" } + "\n\nNothing is submitted.") {
                fillable.forEach { knowledge.saveFormValue(formSpec.id, it.field.id, it.value!!, it.note) }
                val stored = knowledge.formValues(formSpec.id)
                val ok = fillable.all { stored[it.field.id]?.first == it.value }
                toast(if (ok) "Filled and verified ${fillable.size} fields" else "Some fields could not be verified"); render()
            }
        }, ui.lp(16))
        content.addView(ui.ghost("Review & save application packet") {
            val values = knowledge.formValues(formSpec.id)
            val missing = proposals.filter { it.field.required && values[it.field.id] == null }
            confirm("Save prepared application?", "Filled: ${values.size}. Required but empty: ${missing.size}${if (missing.isNotEmpty()) " (" + missing.joinToString { it.field.label } + ")" else ""}.\n\nThis stores the packet on this device only. Kaagaz never submits forms; you submit on the official site yourself.") {
                knowledge.saveFormValue(formSpec.id, "__saved__", System.currentTimeMillis().toString(), "packet"); toast("Saved locally. Not submitted."); render()
            }
        }, ui.lp(10))
        content.addView(ui.ghost("Run crash test on this form", Palette.amber) { crashWithForm = formSpec.id == FormLibrary.universityApplication.id; crashResults = null; go(Screen.CRASH) }, ui.lp(10))
        content.addView(ui.ghost("Clear form values", Palette.muted) { confirm("Clear this form?", "Removes saved values for this form only.") { knowledge.clearForm(formSpec.id); render() } }, ui.lp(10))

        content.addView(ui.eyebrow("Encrypted profile", Palette.muted), ui.lp(24))
        val profile = knowledge.loadProfile()
        val pc = ui.card()
        if (profile.isEmpty()) pc.addView(ui.text("No profile saved. Only verified ledger facts can be saved; values are encrypted with an Android Keystore key.", 13f, Palette.muted))
        profile.forEach { (k, v) -> pc.addView(ui.text("${runCatching { FactType.valueOf(k).label }.getOrDefault(k)}: ${v.first}\n   ↳ ${v.second}", 12.5f, Palette.ink), ui.lp(5)) }
        pc.addView(ui.ghost("Save verified facts to profile") {
            val usable = s.ledger.filter { it.usable != null }
            if (usable.isEmpty()) return@ghost toast("No verified facts to save.")
            confirm("Save ${usable.size} verified facts?", usable.joinToString("\n") { "• ${it.type.label}: ${it.usable!!.display}" }) { knowledge.saveProfile(s.ledger); render() }
        }, ui.lp(10))
        if (profile.isNotEmpty()) pc.addView(ui.link("Delete profile", Palette.red) { confirm("Delete profile?", "Removes the encrypted profile file.") { knowledge.deleteProfile(); render() } })
        content.addView(pc, ui.lp(8))
    }

    private fun pasteLabelsDialog() {
        val input = EditText(this).apply { hint = "One form label per line"; minLines = 5; setTextColor(Palette.ink) }
        AlertDialog.Builder(this).setTitle("Paste form labels").setMessage("Copy the field labels from a form. Kaagaz maps each one to a verified fact or marks it unknown.")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Map fields") { _, _ ->
                val labels = input.text.lines().filter { it.isNotBlank() }
                if (labels.isEmpty()) toast("No labels entered") else { formSpec = FormLibrary.fromLabels("Pasted form", labels); render() }
            }.show()
    }

    // ================================================================= MODEL & PRIVACY

    private fun model(content: LinearLayout) {
        header(content, "Privacy & model", "What runs where", null)
        val st = gemma.state
        val color = when (st.status) { GemmaRuntime.Status.VERIFIED -> Palette.lime; GemmaRuntime.Status.FAILED -> Palette.red; GemmaRuntime.Status.NOT_INSTALLED -> Palette.faint; else -> Palette.amber }
        val card = ui.card(color, Palette.raised)
        card.addView(ui.eyebrow("On-device language model", color))
        card.addView(ui.text(when (st.status) {
            GemmaRuntime.Status.VERIFIED -> "Gemma verified on this phone"
            GemmaRuntime.Status.LOADING -> "Loading Gemma…"
            GemmaRuntime.Status.INSTALLED -> "Model imported, not loaded"
            GemmaRuntime.Status.FAILED -> "Gemma could not run"
            GemmaRuntime.Status.NOT_INSTALLED -> "No model installed"
        }, 21f, Palette.ink, bold = true), ui.lp(8))
        st.modelName?.let { name -> card.addView(ui.text("File: $name · ${gemma.installedModel()?.length()?.let { "%.0f MB".format(it / 1e6) } ?: ""}", 12.5f, Palette.muted, mono = true), ui.lp(6)) }
        st.backend?.let { card.addView(ui.text("Backend: $it${st.lastLatencyMs?.let { l -> " · last run $l ms" } ?: ""}", 12.5f, Palette.muted, mono = true), ui.lp(4)) }
        st.detail?.let { card.addView(ui.text(it, 13f, Palette.muted), ui.lp(8)) }
        modelBusyText?.let { card.addView(PulseView(this, Palette.amber), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { topMargin = ui.dp(10) }); card.addView(ui.text(it, 12.5f, Palette.amber), ui.lp(4)) }
        if (modelBusyText == null && st.status != GemmaRuntime.Status.LOADING) {
            card.addView(ui.primary(if (st.status == GemmaRuntime.Status.NOT_INSTALLED) "Import Gemma model (.litertlm)" else "Replace model") { importModel.launch(arrayOf("*/*")) }, ui.lp(14))
            if (st.status == GemmaRuntime.Status.INSTALLED || st.status == GemmaRuntime.Status.FAILED || st.status == GemmaRuntime.Status.VERIFIED)
                card.addView(ui.ghost(if (st.status == GemmaRuntime.Status.VERIFIED) "Re-run verification" else "Load & verify on device") { gemma.load { runOnUiThread { if (!isDestroyed) render() } }; render() }, ui.lp(8))
            if (st.status != GemmaRuntime.Status.NOT_INSTALLED) card.addView(ui.link("Remove model", Palette.red) { confirm("Remove model?", "Deletes the model file from app storage.") { gemma.removeModel(); render() } })
        }
        card.addView(ui.text("Kaagaz does not include or download model weights. Get a Gemma .litertlm build (for example Gemma 3 1B IT int4 from the litert-community organisation on Hugging Face) after accepting Google's Gemma terms, copy it to this phone, then import it here. Until a model is verified, every answer is produced by deterministic rules and labelled as such.", 12.5f, Palette.faint), ui.lp(12))
        content.addView(card, ui.lp(16))

        val device = ui.card()
        device.addView(ui.eyebrow("This device", Palette.muted))
        val mem = ActivityManager.MemoryInfo().also { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        device.addView(ui.text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}\n${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE} · RAM ${"%.1f".format(mem.totalMem / 1e9)} GB total, ${"%.1f".format(mem.availMem / 1e9)} GB free", 13f, Palette.ink, mono = true), ui.lp(8))
        content.addView(device, ui.lp(10))

        val privacy = ui.card(Palette.mint)
        privacy.addView(ui.eyebrow("Privacy"))
        listOf(
            "Network: " + if (hasInternetPermission()) "this build declares INTERNET permission." else "this build has no INTERNET permission, so it cannot send anything off the device.",
            "Documents are copied into app-private storage. OCR uses Google ML Kit's bundled on-device recogniser.",
            "Facts, findings, missions and form values are stored in a local SQLite database.",
            "Your profile is encrypted with AES-256-GCM using a key held in the Android Keystore.",
            "Kaagaz never asks for or stores passwords, banking credentials or OTPs, and flags forms that do.",
            "Backups are disabled (allowBackup=false). Nothing leaves the device unless you share it yourself.",
            "Retrieval: BM25 keyword ranking fused with structured-fact matches. Semantic embeddings are not available in this build."
        ).forEach { privacy.addView(ui.text("• $it", 13f, Palette.ink), ui.lp(6)) }
        content.addView(privacy, ui.lp(10))

        val credits = ui.card()
        credits.addView(ui.eyebrow("Attributions", Palette.muted))
        listOf(
            "Google ML Kit Text Recognition (on-device OCR) — Google APIs Terms",
            "LiteRT-LM Android 0.16.1 — Apache License 2.0",
            "Gemma models — Gemma Terms of Use; supplied by the user, not bundled",
            "AndroidX Activity, Core, WorkManager — Apache License 2.0",
            "Gson (via LiteRT-LM) — Apache License 2.0",
            "Pre-existing Kaagaz work reused: OCR pipeline, insurance extractor, coverage engine and reminders were built before Mission OS."
        ).forEach { credits.addView(ui.text("• $it", 12.5f, Palette.muted), ui.lp(5)) }
        content.addView(credits, ui.lp(10))
    }

    private fun copyModel(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "model.litertlm"
        if (!name.endsWith(".litertlm")) return toast("Choose a .litertlm Gemma model file.")
        modelBusyText = "Copying $name into private storage…"; render()
        bg({ contentResolver.openInputStream(uri)?.use { gemma.importModel(it, name) } ?: error("Could not open file") }) { r ->
            modelBusyText = null
            r.onSuccess { toast("Model imported. Verifying on device…"); gemma.load { runOnUiThread { if (!isDestroyed) render() } } }.onFailure { toast("Import failed: ${it.message}") }
            render()
        }
    }

    // ================================================================= helpers

    private fun confirm(title: String, message: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ -> yes() }.show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasInternetPermission(): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(Manifest.permission.INTERNET) == true
    }.getOrDefault(false)

    companion object { const val SOURCE_TYPED = "typed" }
}
