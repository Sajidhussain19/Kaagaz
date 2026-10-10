package com.kaagaz.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val forest = Color.rgb(124, 242, 196)
    private val ink = Color.rgb(234, 241, 237)
    private val muted = Color.rgb(143, 160, 153)
    private val paper = Color.rgb(9, 13, 17)
    private val worker = Executors.newSingleThreadExecutor()

    private var screen = Screen.HOME
    private var currentDocument: SelectedDocument? = null
    private var currentOcr: List<OcrPageResult> = emptyList()
    private var currentInsurance: List<InsuranceField> = emptyList()
    private var evidenceBitmap: Bitmap? = null
    private var ocrRun: OcrRun? = null
    private lateinit var store: KaagazStore
    private var previewReturnsToLibrary = false
    private var currentEvidenceKey: InsuranceFieldKey? = null
    private var currentEvidencePage: Int? = null
    private var currentQaQuestion: String? = null
    private var currentCoverageQuestion: String? = null
    private var currentCoveragePassage: CoverageIntelligenceEngine.Passage? = null
    private var coverageRootDocumentId: String? = null
    private var evidenceReturnsToQa = false
    private var pendingReminder: DeadlineReminder? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val reminder = pendingReminder ?: return@registerForActivityResult
        pendingReminder = null
        if (granted) persistAndSchedule(reminder) else {
            store.saveReminder(reminder.copy(state = ReminderState.SAVED_PERMISSION_REQUIRED, updatedAt = System.currentTimeMillis()))
            Toast.makeText(this, "Reminder saved, but notifications are not allowed", Toast.LENGTH_LONG).show()
            showReminderSetup()
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) Toast.makeText(this, "Document selection cancelled", Toast.LENGTH_SHORT).show()
        else inspectAndPreview(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KaagazStore(applicationContext)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility = 0
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        if (savedInstanceState == null && handleMissionIntent()) return
        if (!restoreSession(savedInstanceState)) showHome()
    }

    /** Entry points used by Mission OS: open one saved document, the library, or the document picker. */
    private fun handleMissionIntent(): Boolean {
        val openId = intent.getStringExtra(EXTRA_OPEN_DOCUMENT)
        when {
            openId != null -> {
                val item = runCatching { store.listDocuments().firstOrNull { it.id == openId } }.getOrNull() ?: return false
                openSavedDocument(item)
            }
            intent.getBooleanExtra(EXTRA_PICK_DOCUMENT, false) -> { showHome(); launchPicker() }
            intent.getBooleanExtra(EXTRA_LIBRARY, false) -> showLibrary()
            else -> return false
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentDocument?.let { outState.putString(STATE_DOCUMENT_ID, it.id) }
        outState.putString(STATE_SCREEN, screen.name)
        currentEvidenceKey?.let { outState.putString(STATE_FIELD_KEY, it.name) }
        currentEvidencePage?.let { outState.putInt(STATE_EVIDENCE_PAGE, it) }
        currentQaQuestion?.let { outState.putString(STATE_QA_QUESTION, it) }
        currentCoverageQuestion?.let { outState.putString(STATE_COVERAGE_QUESTION, it) }
        outState.putBoolean(STATE_EVIDENCE_RETURNS_QA, evidenceReturnsToQa)
    }

    override fun onDestroy() {
        cancelOcr(silent = true)
        clearEvidenceBitmap()
        worker.shutdownNow()
        store.close()
        super.onDestroy()
    }

    private fun navigateBack() {
        when (screen) {
            Screen.HOME -> finish()
            Screen.LIBRARY -> showHome()
            Screen.PREVIEW -> if (previewReturnsToLibrary) showLibrary() else showHome()
            Screen.ERROR -> showHome()
            Screen.OCR_LOADING -> {
                cancelOcr(silent = true)
                currentDocument?.let(::showDocumentPreview) ?: showHome()
            }
            Screen.OCR_RESULTS -> currentDocument?.let(::showDocumentPreview) ?: showHome()
            Screen.INSURANCE_SUMMARY -> showOcrResults(currentOcr)
            Screen.QA -> currentDocument?.let(::showDocumentPreview) ?: showHome()
            Screen.FIELD_EVIDENCE -> if (evidenceReturnsToQa) showAskKaagaz() else showInsuranceSummary()
            Screen.COVERAGE -> currentDocument?.let(::showDocumentPreview) ?: showHome()
            Screen.COVERAGE_EVIDENCE -> showCoverageIntelligence()
            Screen.REMINDER -> showInsuranceSummary()
        }
    }

    private fun showHome() {
        screen = Screen.HOME
        currentDocument = null
        currentOcr = emptyList()
        currentInsurance = emptyList()
        previewReturnsToLibrary = false
        currentEvidenceKey = null
        currentEvidencePage = null
        currentQaQuestion = null
        currentCoverageQuestion = null
        currentCoveragePassage = null
        evidenceReturnsToQa = false
        clearSavedSession()
        setContentView(buildHome())
    }

    private fun buildHome(): View {
        val content = page()
        content.addView(label("KAAGAZ", 14, forest).apply { letterSpacing = .18f })
        content.addView(label("Your papers,\nunderstood.", 38, ink).apply {
            setPadding(0, dp(12), 0, dp(10)); setLineSpacing(0f, .94f)
        })
        content.addView(label("A private place for the documents that matter to your family.", 17, muted).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        content.addView(privacyCard(), params(top = 28))
        content.addView(actionButton("＋  Add a document", true) { launchPicker() }, params(top = 24))
        content.addView(actionButton("View saved documents", false) {
            showLibrary()
        }, params(top = 12))
        content.addView(label("YOUR DOCUMENTS", 12, muted).apply {
            letterSpacing = .14f; setPadding(0, dp(34), 0, dp(12))
        })
        val savedCount = runCatching { store.listDocuments().size }.getOrDefault(0)
        content.addView(if (savedCount == 0) emptyState() else messageCard("$savedCount saved ${if (savedCount == 1) "document" else "documents"}", "Open your private on-device library to continue."))
        return scroll(content)
    }

    private fun launchPicker() = openDocument.launch(arrayOf("image/*", "application/pdf"))

    private fun inspectAndPreview(uri: Uri) {
        val mime = contentResolver.getType(uri)?.lowercase()
        val type = when {
            mime == "application/pdf" -> DocumentType.PDF
            mime?.startsWith("image/") == true -> DocumentType.IMAGE
            else -> null
        }
        if (type == null) {
            showError("Unsupported file", "Choose a JPG, PNG, WEBP, HEIC, or PDF document.")
            return
        }
        val name = queryName(uri) ?: "Selected document"
        try {
            val pages = if (type == DocumentType.PDF) pdfPageCount(uri) else 1
            val resolvedMime = mime ?: "application/octet-stream"
            worker.execute {
                try {
                    val document = store.import(uri, name, type, resolvedMime, pages)
                    runOnUiThread {
                        currentDocument = document; currentOcr = emptyList(); currentInsurance = emptyList()
                currentQaQuestion = null; evidenceReturnsToQa = false
                currentCoverageQuestion = null; currentCoveragePassage = null
                        previewReturnsToLibrary = false
                        showDocumentPreview(document)
                        Toast.makeText(this, "Saved privately on this device", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    runOnUiThread { showError("Could not save this document", "Kaagaz could not copy the file into private storage. Check available space and try again.") }
                }
            }
        } catch (_: SecurityException) {
            showError("Cannot access this file", "The document provider did not grant Kaagaz permission to read it.")
        } catch (_: Exception) {
            showError("Cannot read this file", "The selected document may be damaged or unavailable.")
        }
    }

    private fun showDocumentPreview(document: SelectedDocument) {
        screen = Screen.PREVIEW
        saveSession()
        val content = page()
        content.addView(textButton("←  Back") { if (previewReturnsToLibrary) showLibrary() else showHome() })
        content.addView(label("DOCUMENT PREVIEW", 12, forest).apply {
            letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10))
        })
        content.addView(label(document.name, 28, ink))
        content.addView(label(documentTypeLabel(document), 14, muted).apply { setPadding(0, dp(8), 0, dp(22)) })

        try {
            val bitmap = when (document.type) {
                DocumentType.IMAGE -> decodeImage(document.uri)
                DocumentType.PDF -> renderPdfPage(document.uri, 0, previewWidth())
            } ?: throw IOException("Unable to decode preview")
            content.addView(imageFrame(bitmap, document.name, document.type == DocumentType.PDF))
            content.addView(statusCard(if (currentOcr.isEmpty()) "Saved privately on this device. OCR has not run yet." else "Saved privately on this device with on-device OCR results."), params(top = 18))
            if (currentOcr.isEmpty()) {
                content.addView(actionButton("Extract text on device", true) { startOcr(document) }, params(top = 18))
            } else {
                content.addView(policyCoverageSummaryCard(), params(top = 18))
                content.addView(actionButton("Review extracted fields", true) { showInsuranceSummary() }, params(top = 18))
                content.addView(actionButton("Understand my coverage", false) { showCoverageIntelligence() }, params(top = 12))
                content.addView(actionButton("Link payment statement", false) { showDocumentLinks() }, params(top = 12))
                content.addView(actionButton("Ask Kaagaz", false) { showAskKaagaz() }, params(top = 12))
                content.addView(actionButton("View OCR text", false) { showOcrResults(currentOcr) }, params(top = 12))
                content.addView(actionButton("Run OCR again", false) { startOcr(document) }, params(top = 12))
            }
        } catch (_: Exception) {
            content.addView(messageCard("Preview unavailable", "You can return and choose a different document."))
        }
        content.addView(actionButton("Choose another document", false) { launchPicker() }, params(top = 12))
        content.addView(actionButton("Delete this document", false) { confirmDelete(document) }, params(top = 12))
        setContentView(scroll(content))
    }

    private fun startOcr(document: SelectedDocument) {
        cancelOcr(silent = true)
        val run = OcrRun(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
        ocrRun = run
        showOcrLoading(document, 0, ocrPageLimit(document))
        if (document.type == DocumentType.IMAGE) processImageDocument(document, run)
        else processPdfPage(document, run, 0, mutableListOf())
    }

    private fun processImageDocument(document: SelectedDocument, run: OcrRun) {
        worker.execute {
            try {
                val bitmap = decodeImage(document.uri) ?: throw IOException("Image could not be decoded")
                if (run.cancelled.get()) { bitmap.recycle(); finishCancelled(run); return@execute }
                recognizeBitmap(bitmap, 1, run) { result ->
                    if (result == null) showOcrFailure(run, "Text extraction failed. Try a clearer image.")
                    else finishOcr(run, listOf(result))
                }
            } catch (_: Exception) {
                showOcrFailure(run, "This image could not be read. Try a different file.")
            }
        }
    }

    private fun processPdfPage(
        document: SelectedDocument,
        run: OcrRun,
        pageIndex: Int,
        results: MutableList<OcrPageResult>
    ) {
        val limit = ocrPageLimit(document)
        if (run.cancelled.get()) { finishCancelled(run); return }
        if (pageIndex >= limit) { finishOcr(run, results); return }
        runOnUiThread { if (ocrRun === run) updateOcrProgress(pageIndex + 1, limit) }
        worker.execute {
            try {
                val bitmap = renderPdfPage(document.uri, pageIndex, OCR_RENDER_WIDTH)
                if (run.cancelled.get()) { bitmap.recycle(); finishCancelled(run); return@execute }
                recognizeBitmap(bitmap, pageIndex + 1, run) { page ->
                    if (page == null) showOcrFailure(run, "Text extraction failed on page ${pageIndex + 1}.")
                    else {
                        results.add(page)
                        processPdfPage(document, run, pageIndex + 1, results)
                    }
                }
            } catch (_: Exception) {
                showOcrFailure(run, "Page ${pageIndex + 1} could not be read.")
            }
        }
    }

    private fun recognizeBitmap(
        bitmap: Bitmap,
        pageNumber: Int,
        run: OcrRun,
        done: (OcrPageResult?) -> Unit
    ) {
        val width = bitmap.width
        val height = bitmap.height
        run.recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val page = OcrPageResult(
                    pageNumber,
                    result.text,
                    result.textBlocks.flatMap { block ->
                        block.lines.map { line -> lineEvidence(pageNumber, line, width, height) }
                    }
                )
                if (!run.cancelled.get()) done(page) else finishCancelled(run)
            }
            .addOnFailureListener { if (!run.cancelled.get()) done(null) else finishCancelled(run) }
            .addOnCompleteListener { bitmap.recycle() }
    }

    private fun lineEvidence(page: Int, line: Text.Line, width: Int, height: Int) = OcrLineEvidence(
        page, line.text, line.boundingBox, line.confidence, width, height
    )

    private fun finishOcr(run: OcrRun, results: List<OcrPageResult>) {
        runOnUiThread {
            if (ocrRun !== run || run.cancelled.get()) return@runOnUiThread
            closeRun(run)
            ocrRun = null
            currentOcr = results
            currentInsurance = InsuranceExtractor.extract(results)
            val documentId = currentDocument?.id
            if (documentId == null) showOcrResults(results)
            else worker.execute {
                try {
                    store.saveAnalysis(documentId, results, currentInsurance)
                    runOnUiThread { showOcrResults(results) }
                } catch (_: Exception) {
                    runOnUiThread {
                        showOcrResults(results)
                        Toast.makeText(this, "OCR completed, but results could not be saved", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showOcrFailure(run: OcrRun, message: String) {
        runOnUiThread {
            if (ocrRun !== run || run.cancelled.get()) return@runOnUiThread
            closeRun(run); ocrRun = null
            showOcrError(message)
        }
    }

    private fun finishCancelled(run: OcrRun) {
        runOnUiThread {
            closeRun(run)
            if (ocrRun === run) ocrRun = null
        }
    }

    private fun cancelOcr(silent: Boolean) {
        val run = ocrRun ?: return
        run.cancelled.set(true)
        if (!silent) Toast.makeText(this, "Text extraction cancelled", Toast.LENGTH_SHORT).show()
        ocrRun = null
    }

    private fun showOcrLoading(document: SelectedDocument, page: Int, total: Int) {
        screen = Screen.OCR_LOADING
        saveSession()
        val progress = TextView(this).apply { tag = PROGRESS_TAG; textSize = 16f; setTextColor(ink) }
        val content = page()
        content.addView(label("EXTRACTING TEXT", 12, forest).apply { letterSpacing = .14f })
        content.addView(label(document.name, 28, ink).apply { setPadding(0, dp(18), 0, dp(10)) })
        content.addView(ProgressBar(this).apply { isIndeterminate = true }, params(top = 22))
        content.addView(progress, params(top = 18))
        content.addView(label("OCR runs locally with the bundled Latin model. Nothing is uploaded.", 15, muted).apply {
            setPadding(0, dp(12), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
        })
        content.addView(actionButton("Cancel extraction", false) {
            cancelOcr(silent = false); showDocumentPreview(document)
        }, params(top = 28))
        setContentView(scroll(content))
        updateOcrProgress(if (page == 0) 1 else page, total)
    }

    private fun updateOcrProgress(page: Int, total: Int) {
        window.decorView.findViewWithTag<TextView>(PROGRESS_TAG)?.text =
            if (total == 1) "Reading document…" else "Reading page $page of $total…"
    }

    private fun showOcrResults(results: List<OcrPageResult>) {
        screen = Screen.OCR_RESULTS
        saveSession()
        val fullText = results.joinToString("\n\n") { page ->
            if (results.size > 1) "Page ${page.pageNumber}\n${page.text.trim()}" else page.text.trim()
        }.trim()
        val content = page()
        content.addView(textButton("←  Back to document") {
            currentDocument?.let(::showDocumentPreview) ?: showHome()
        })
        content.addView(label("OCR RESULTS", 12, forest).apply {
            letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10))
        })
        content.addView(label(if (fullText.isBlank()) "No text found" else "Text extracted", 30, ink))
        content.addView(label(resultSummary(results), 14, muted).apply { setPadding(0, dp(9), 0, dp(20)) })
        if (fullText.isBlank()) {
            content.addView(messageCard("The document appears empty", "Try a sharper image or a document with larger text."))
        } else {
            content.addView(actionButton("View insurance summary", true) { showInsuranceSummary() })
            lateinit var copyButton: Button
            copyButton = actionButton("Copy text", false) {
                copyText(fullText)
                copyButton.text = "Copied to clipboard  ✓"
            }
            content.addView(copyButton, params(top = 12))
            results.forEach { page ->
                if (results.size > 1) content.addView(label("PAGE ${page.pageNumber}", 12, forest).apply {
                    letterSpacing = .12f; setPadding(0, dp(26), 0, dp(10))
                })
                content.addView(textCard(page.text.ifBlank { "No text found on this page." }))
            }
        }
        content.addView(label("OCR output is unverified. Check it against the original document.", 13, muted).apply {
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(scroll(content))
    }

    private fun showInsuranceSummary() {
        clearEvidenceBitmap()
        screen = Screen.INSURANCE_SUMMARY
        saveSession()
        if (currentInsurance.isEmpty() && currentOcr.isNotEmpty()) {
            currentInsurance = InsuranceExtractor.extract(currentOcr)
        }
        val kind = DocumentProfile.detect(currentOcr)
        val content = page()
        content.addView(textButton("←  Back to OCR text") { showOcrResults(currentOcr) })
        content.addView(label(kind.title.uppercase(), 12, forest).apply {
            letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10))
        })
        content.addView(label("Key details", 30, ink))
        content.addView(label("Tap a detail to see where it appears in the document.", 15, muted).apply {
            setPadding(0, dp(10), 0, dp(22)); setLineSpacing(dp(3).toFloat(), 1f)
        })
        val shownFields = DocumentProfile.fieldsToShow(kind, currentInsurance)
        shownFields.forEachIndexed { index, field ->
            content.addView(insuranceFieldCard(field) {
                evidenceReturnsToQa = false
                showFieldEvidence(field)
            }, params(top = if (index == 0) 0 else 12))
        }
        val missing = DocumentProfile.missingFields(kind, currentInsurance)
        if (missing.isNotEmpty()) {
            content.addView(label("Not found in this document: ${missing.joinToString { it.label.lowercase() }}.", 14, muted).apply {
                setPadding(0, dp(if (shownFields.isEmpty()) 0 else 16), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        content.addView(actionButton("Understand my coverage", true) { showCoverageIntelligence() }, params(top = 22))
        content.addView(actionButton("Ask Kaagaz", false) { showAskKaagaz() }, params(top = 12))
        content.addView(actionButton("Set expiry reminder", false) { showReminderSetup() }, params(top = 12))
        content.addView(actionButton("View original document", false) {
            currentDocument?.let(::showDocumentPreview) ?: showHome()
        }, params(top = 12))
        content.addView(label("Supported means the value is backed by an explicit label and readable OCR evidence. It is not user-verified yet.", 13, muted).apply {
            setPadding(0, dp(18), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
        setContentView(scroll(content))
    }

    private fun insuranceFieldCard(field: InsuranceField, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Color.rgb(18, 24, 30), 18, Color.rgb(36, 46, 54))
        isClickable = true; isFocusable = true; minimumHeight = dp(88)
        contentDescription = "${field.key.label}: ${field.displayedValue ?: "Not found"}. ${fieldStatusLabel(field)}"
        setOnClickListener { action() }
        addView(label(field.key.label.uppercase(), 11, muted).apply { letterSpacing = .08f })
        addView(label(field.displayedValue ?: "Not found", 19, ink).apply { setPadding(0, dp(7), 0, dp(8)) })
        addView(label("●  ${fieldStatusLabel(field)}  ›", 12, fieldStatusColor(field)))
    }

    private fun showCoverageIntelligence(question: String? = currentCoverageQuestion) {
        clearEvidenceBitmap()
        if (screen == Screen.COVERAGE_EVIDENCE) restoreCoverageRoot()
        if (screen != Screen.COVERAGE) coverageRootDocumentId = currentDocument?.id
        screen = Screen.COVERAGE
        if (question != null) currentCoverageQuestion = question
        currentCoveragePassage = null
        saveSession()
        val answer = currentCoverageQuestion?.let { crossDocumentCoverageAnswer(it) }
        val content = page()
        content.addView(textButton("←  Back to document") {
            currentDocument?.let(::showDocumentPreview) ?: showHome()
        })
        content.addView(label("UNDERSTAND MY COVERAGE", 12, forest).apply {
            letterSpacing = .12f; setPadding(0, dp(28), 0, dp(10))
        })
        content.addView(label("What does this policy say?", 30, ink))
        content.addView(label("Answers come only from this document's text, on this device. Mentioning a condition does not mean it is covered.", 15, muted).apply {
            setPadding(0, dp(10), 0, dp(8)); setLineSpacing(dp(3).toFloat(), 1f)
        })

        answer?.let {
            content.addView(qaBubble(it.question, true), params(top = 16))
            content.addView(coverageAnswerHeader(it), params(top = 12))
            it.passages.groupBy { passage -> passage.category }.forEach { (category, passages) ->
                content.addView(label(category.label.uppercase(), 11, coverageCategoryColor(category)).apply {
                    letterSpacing = .08f; setPadding(0, dp(24), 0, dp(4))
                })
                content.addView(label(passages.first().explanation, 13, muted).apply {
                    setPadding(0, 0, 0, dp(4)); setLineSpacing(dp(2).toFloat(), 1f)
                })
                passages.forEach { passage -> content.addView(coveragePassageCard(passage), params(top = 10)) }
            }
        }

        content.addView(label("SUGGESTED QUESTIONS", 11, muted).apply {
            letterSpacing = .1f; setPadding(0, dp(26), 0, dp(8))
        })
        DocumentProfile.suggestedQuestions(DocumentProfile.detect(currentOcr), currentOcr).forEach { example ->
            content.addView(actionButton(example, false) { showCoverageIntelligence(example) }, params(top = 8))
        }

        val input = EditText(this).apply {
            hint = "Ask a supported coverage question"
            textSize = 16f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false); maxLines = 3; minHeight = dp(58); setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.rgb(18, 24, 30), 14, Color.rgb(70, 82, 90))
            contentDescription = "Coverage question"
        }
        fun submit() {
            val value = input.text.toString().trim()
            if (value.isBlank()) Toast.makeText(this, "Type a coverage question first", Toast.LENGTH_SHORT).show()
            else showCoverageIntelligence(value)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        content.addView(label("YOUR QUESTION", 11, muted).apply { letterSpacing = .1f; setPadding(0, dp(26), 0, dp(9)) })
        content.addView(input)
        content.addView(actionButton("Search this policy", true) { submit() }, params(top = 12))
        content.addView(label("Unsupported questions are not guessed. No AI model or internet service is used.", 13, muted).apply {
            setPadding(0, dp(16), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
        setContentView(scroll(content))
    }

    private fun coverageAnswerHeader(answer: CoverageIntelligenceEngine.Answer) = messageCard(
        when (answer.kind) {
            CoverageIntelligenceEngine.AnswerKind.RESULTS -> "From the document"
            CoverageIntelligenceEngine.AnswerKind.NOT_FOUND -> "Not found"
            CoverageIntelligenceEngine.AnswerKind.AMBIGUOUS -> "Needs review"
            CoverageIntelligenceEngine.AnswerKind.UNSUPPORTED -> "Question not supported"
        }, answer.summary
    )

    private fun coveragePassageCard(passage: CoverageIntelligenceEngine.Passage) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Color.rgb(18, 24, 30), 18, Color.rgb(36, 46, 54))
        // The wording is one complete sentence from the document, so it is shown as written, not re-explained.
        val wording = passage.wording.trim()
        val long = wording.length > 320
        addView(label("“${if (long) wording.take(300).substringBeforeLast(' ') + "…" else wording}”", 16, ink).apply {
            setLineSpacing(dp(3).toFloat(), 1f); setTextIsSelectable(true)
        })
        val pages = passage.evidence.map { it.pageNumber }.distinct().sorted().joinToString()
        val source = passage.sourceDocumentName?.let { "$it · " }.orEmpty()
        if (pages.isNotEmpty()) addView(label("$source${if (pages.contains(',')) "Pages" else "Page"} $pages", 12, muted).apply { setPadding(0, dp(8), 0, 0) })
        if (long) addView(textButton("Show full clause") {
            AlertDialog.Builder(this@MainActivity).setTitle(passage.category.label).setMessage(wording).setNegativeButton("Close", null).show()
        })
        if (passage.evidence.isNotEmpty()) addView(actionButton("Highlight in document", false) {
            currentCoveragePassage = passage; showCoverageEvidence(passage)
        }, params(top = 10))
    }

    private fun showCoverageEvidence(passage: CoverageIntelligenceEngine.Passage, requestedPage: Int? = null) {
        clearEvidenceBitmap()
        passage.sourceDocumentId?.takeIf { it != currentDocument?.id }?.let { sourceId ->
            store.load(sourceId)?.let(::applyStoredDocument)
        }
        screen = Screen.COVERAGE_EVIDENCE
        currentCoveragePassage = passage
        val pages = passage.evidence.map { it.pageNumber }.distinct().sorted()
        val pageNumber = requestedPage?.takeIf { it in pages } ?: pages.firstOrNull()
        currentEvidencePage = pageNumber
        saveSession()
        val content = page()
        content.addView(textButton("←  Back to coverage results") { showCoverageIntelligence() })
        content.addView(label("POLICY EVIDENCE", 12, forest).apply { letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10)) })
        content.addView(label(passage.category.label, 29, ink))
        content.addView(label(passage.explanation, 15, muted).apply { setPadding(0, dp(10), 0, dp(12)); setLineSpacing(dp(3).toFloat(), 1f) })
        if (pages.size > 1) pages.forEach { page ->
            content.addView(actionButton("Page $page", page == pageNumber) { showCoverageEvidence(passage, page) }, params(top = 8))
        }
        if (pageNumber == null) {
            content.addView(messageCard("Highlight unavailable", "No source page is available."), params(top = 18))
        } else {
            val evidence = passage.evidence.filter { it.pageNumber == pageNumber }
            val valid = evidence.filter(::hasValidCoordinates)
            if (valid.isEmpty()) content.addView(messageCard("Highlight unavailable", "The original wording is preserved, but usable OCR coordinates are unavailable."), params(top = 18))
            else runCatching { buildEvidenceOverlay(pageNumber, valid) }
                .onSuccess { content.addView(it, params(top = 18)) }
                .onFailure { content.addView(messageCard("Highlight unavailable", "The original page could not be rendered. Source wording remains below."), params(top = 18)) }
            evidence.forEach { content.addView(evidenceCard(it), params(top = 12)) }
        }
        content.addView(label("Classification is based only on explicit nearby policy wording; it is not a coverage guarantee.", 13, muted).apply {
            setPadding(0, dp(18), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
        setContentView(scroll(content))
    }

    private fun crossDocumentCoverageAnswer(question: String): CoverageIntelligenceEngine.Answer {
        val document = currentDocument ?: return CoverageIntelligenceEngine.answer(question, currentOcr, currentInsurance)
        val primary = CrossDocumentCoverageEngine.Source(document.id, document.name, currentOcr, currentInsurance)
        val associated = runCatching { store.loadLinkedDocuments(document.id) }.getOrDefault(emptyList()).map {
            CrossDocumentCoverageEngine.Source(it.document.id, it.document.name, it.ocrPages, it.fields)
        }
        return CrossDocumentCoverageEngine.answer(question, primary, associated)
    }

    private fun restoreCoverageRoot() {
        val rootId = coverageRootDocumentId ?: return
        if (rootId != currentDocument?.id) store.load(rootId)?.let(::applyStoredDocument)
    }

    private fun applyStoredDocument(stored: StoredDocument) {
        currentDocument = stored.document
        currentOcr = stored.ocrPages
        currentInsurance = stored.fields
    }

    private fun showDocumentLinks() {
        val policy = currentDocument ?: return
        val candidates = runCatching { store.listDocuments().filter { it.id != policy.id && it.fileAvailable } }.getOrElse {
            Toast.makeText(this, "Saved documents could not be read", Toast.LENGTH_LONG).show(); return
        }
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Link payment statement")
                .setMessage("Import and run OCR on a payment statement first. Kaagaz will not guess a relationship between documents.")
                .setPositiveButton("OK", null).show()
            return
        }
        val selected = runCatching { store.linkedDocumentIds(policy.id).toMutableSet() }.getOrDefault(mutableSetOf())
        val labels = candidates.map { item ->
            "${item.name}\n${item.policyNumber?.let { "Policy $it" } ?: "No matching policy number confirmed"}"
        }.toTypedArray()
        val checks = candidates.map { it.id in selected }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Select only documents you know belong to this policy")
            .setMultiChoiceItems(labels, checks) { _, index, checked ->
                if (checked) selected.add(candidates[index].id) else selected.remove(candidates[index].id)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save links") { _, _ ->
                runCatching { store.replaceLinkedDocuments(policy.id, selected) }
                    .onSuccess { Toast.makeText(this, "Supporting documents updated", Toast.LENGTH_SHORT).show(); showDocumentPreview(policy) }
                    .onFailure { Toast.makeText(this, "Links could not be saved", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun coverageCategoryColor(category: CoverageIntelligenceEngine.Category) = when (category) {
        CoverageIntelligenceEngine.Category.BENEFIT -> forest
        CoverageIntelligenceEngine.Category.CONDITIONAL, CoverageIntelligenceEngine.Category.WAITING_PERIOD,
        CoverageIntelligenceEngine.Category.LIMIT, CoverageIntelligenceEngine.Category.MENTION_ONLY -> Color.rgb(255, 190, 92)
        CoverageIntelligenceEngine.Category.EXCLUSION -> Color.rgb(255, 120, 104)
        CoverageIntelligenceEngine.Category.CLAIM_REQUIREMENT, CoverageIntelligenceEngine.Category.PAYMENT_RECORD,
        CoverageIntelligenceEngine.Category.PREMIUM_SCHEDULE, CoverageIntelligenceEngine.Category.OUTSTANDING_DUE,
        CoverageIntelligenceEngine.Category.MATURITY, CoverageIntelligenceEngine.Category.IMPORTANT_DATE -> Color.rgb(130, 176, 255)
        CoverageIntelligenceEngine.Category.PROJECTED_BENEFIT -> Color.rgb(255, 190, 92)
    }

    /** At-a-glance card: one short line per fact, each taken from a single source sentence or a supported field. */
    private fun policyCoverageSummaryCard(): View {
        val kind = DocumentProfile.detect(currentOcr)
        val rows = runCatching { DocumentProfile.overview(kind, currentOcr, currentInsurance) }.getOrDefault(emptyList())
        val firstQuestion = DocumentProfile.suggestedQuestions(kind, currentOcr).first()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(Color.rgb(18, 24, 30), 22, Color.rgb(36, 46, 54))
            addView(label(if (kind == DocumentKind.PAYMENT_STATEMENT) "YOUR PAYMENTS AT A GLANCE" else "YOUR POLICY AT A GLANCE", 11, forest).apply { letterSpacing = .08f })
            addView(label(kind.title, 13, muted).apply { setPadding(0, dp(4), 0, 0) })
            if (rows.isEmpty()) {
                addView(label("Kaagaz could not find a clearly stated benefit or amount. Ask a question to search the text.", 15, ink).apply {
                    setPadding(0, dp(10), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
                })
            }
            rows.forEach { row ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0)
                    addView(label(row.label.uppercase(), 10, muted).apply { letterSpacing = .08f })
                    addView(label(row.value, 16, ink).apply { setPadding(0, dp(3), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
                    row.passage?.takeIf { passage -> passage.evidence.isNotEmpty() }?.let { passage ->
                        val page = passage.evidence.map { it.pageNumber }.distinct().sorted().joinToString()
                        addView(textButton("Page $page · Highlight source  ›") {
                            currentCoverageQuestion = firstQuestion
                            coverageRootDocumentId = currentDocument?.id
                            currentCoveragePassage = passage
                            showCoverageEvidence(passage)
                        })
                    }
                })
            }
            addView(label("From this document's text only. Conditions and exclusions apply as written.", 12, muted).apply {
                setPadding(0, dp(14), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
            })
            addView(actionButton("See sources and ask questions", false) {
                showCoverageIntelligence(firstQuestion)
            }, params(top = 12))
        }
    }

    private fun showAskKaagaz(question: String? = currentQaQuestion) {
        clearEvidenceBitmap()
        screen = Screen.QA
        if (question != null) currentQaQuestion = question
        saveSession()
        val kind = DocumentProfile.detect(currentOcr)
        // One place to ask: coverage, benefit and payment questions go to the coverage engine, field questions
        // (policy number, dates, insured person…) to the field engine. Neither engine guesses outside its patterns.
        val coverageAnswer = currentQaQuestion?.takeIf { CoverageIntelligenceEngine.supports(it) }?.let { crossDocumentCoverageAnswer(it) }
        val answer = if (coverageAnswer == null) currentQaQuestion?.let { DocumentQaEngine.answer(it, currentInsurance) } else null
        val content = page()
        content.addView(textButton("←  Back to document") {
            currentDocument?.let(::showDocumentPreview) ?: showHome()
        })
        content.addView(label("ASK KAAGAZ", 12, forest).apply {
            letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10))
        })
        content.addView(label("Ask about this document", 30, ink))
        content.addView(label("Answers come from this document's text on this device. Kaagaz only answers the kinds of question shown below.", 15, muted).apply {
            setPadding(0, dp(10), 0, dp(20)); setLineSpacing(dp(3).toFloat(), 1f)
        })

        content.addView(qaBubble(DocumentProfile.askIntro(kind), false))
        answer?.let {
            content.addView(qaBubble(it.question, true), params(top = 12))
            content.addView(qaAnswerCard(it), params(top = 12))
        }
        coverageAnswer?.let {
            content.addView(qaBubble(it.question, true), params(top = 12))
            content.addView(qaCoverageAnswerCard(it), params(top = 12))
        }

        content.addView(label("EXAMPLE QUESTIONS", 11, muted).apply {
            letterSpacing = .1f; setPadding(0, dp(24), 0, dp(8))
        })
        (DocumentProfile.suggestedQuestions(kind, currentOcr).take(4) + DocumentProfile.fieldQuestions(kind)).distinct().forEach { example ->
            content.addView(actionButton(example, false) { showAskKaagaz(example) }, params(top = 8))
        }

        val input = EditText(this).apply {
            hint = "Ask about this document"
            textSize = 16f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true); minHeight = dp(56); setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.rgb(18, 24, 30), 14, Color.rgb(70, 82, 90))
            contentDescription = "Question for Kaagaz"
        }
        fun submit() {
            val value = input.text.toString().trim()
            if (value.isBlank()) Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
            else showAskKaagaz(value)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
        content.addView(label("YOUR QUESTION", 11, muted).apply {
            letterSpacing = .1f; setPadding(0, dp(26), 0, dp(9))
        })
        content.addView(input)
        content.addView(actionButton("Send question", true) { submit() }, params(top = 12))
        content.addView(label("No general AI or internet service is used. Unsupported questions are not guessed.", 13, muted).apply {
            setPadding(0, dp(16), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
        setContentView(scroll(content))
    }

    private fun qaBubble(text: String, user: Boolean) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(if (user) paper else ink)
        setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(18), dp(15), dp(18), dp(15))
        background = rounded(if (user) forest else Color.rgb(18, 24, 30), 18, if (user) null else Color.rgb(36, 46, 54))
        gravity = if (user) Gravity.END else Gravity.START
    }

    /** Short answer first; the source sentence and its page are one tap away on the coverage screen. */
    private fun qaCoverageAnswerCard(answer: CoverageIntelligenceEngine.Answer) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Color.rgb(18, 24, 30), 18, Color.rgb(36, 46, 54))
        val (status, color) = when (answer.kind) {
            CoverageIntelligenceEngine.AnswerKind.RESULTS -> "FROM THE DOCUMENT" to forest
            CoverageIntelligenceEngine.AnswerKind.AMBIGUOUS -> "NEEDS REVIEW" to Color.rgb(255, 190, 92)
            CoverageIntelligenceEngine.AnswerKind.NOT_FOUND -> "NOT FOUND" to muted
            CoverageIntelligenceEngine.AnswerKind.UNSUPPORTED -> "QUESTION NOT SUPPORTED" to muted
        }
        addView(label("●  $status", 11, color).apply { letterSpacing = .07f })
        addView(label(answer.summary, 17, ink).apply {
            setPadding(0, dp(10), 0, 0); setLineSpacing(dp(4).toFloat(), 1f); setTextIsSelectable(true)
        })
        if (answer.passages.isNotEmpty()) {
            val pages = answer.passages.flatMap { it.evidence }.map { it.pageNumber }.distinct().sorted().joinToString()
            val documents = answer.passages.mapNotNull { it.sourceDocumentName }.distinct()
            val from = when {
                documents.size > 1 -> "${documents.size} documents · "
                documents.size == 1 && documents.single() != currentDocument?.name -> "${documents.single()} · "
                else -> ""
            }
            addView(label("Source: $from${if (pages.contains(',')) "pages" else "page"} $pages", 13, muted).apply { setPadding(0, dp(12), 0, 0) })
            addView(actionButton("Show source wording", false) { showCoverageIntelligence(answer.question) }, params(top = 12))
        }
    }

    private fun qaAnswerCard(answer: DocumentQaEngine.Answer) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Color.rgb(18, 24, 30), 18, Color.rgb(36, 46, 54))
        val status = when (answer.kind) {
            DocumentQaEngine.AnswerKind.DOCUMENT_SUPPORTED -> "SUPPORTED BY DOCUMENT TEXT"
            DocumentQaEngine.AnswerKind.NEEDS_REVIEW -> "NEEDS REVIEW"
            DocumentQaEngine.AnswerKind.USER_CONFIRMED -> "USER CONFIRMED"
            DocumentQaEngine.AnswerKind.NOT_FOUND -> "NOT FOUND"
            DocumentQaEngine.AnswerKind.UNSUPPORTED -> "QUESTION NOT SUPPORTED"
            DocumentQaEngine.AnswerKind.REVIEW_SUMMARY -> "DOCUMENT REVIEW"
        }
        val color = when (answer.kind) {
            DocumentQaEngine.AnswerKind.DOCUMENT_SUPPORTED -> forest
            DocumentQaEngine.AnswerKind.USER_CONFIRMED -> Color.rgb(130, 176, 255)
            DocumentQaEngine.AnswerKind.NEEDS_REVIEW -> Color.rgb(255, 190, 92)
            else -> muted
        }
        addView(label("●  $status", 11, color).apply { letterSpacing = .07f })
        addView(label(answer.text, 17, ink).apply {
            setPadding(0, dp(10), 0, 0); setLineSpacing(dp(4).toFloat(), 1f); setTextIsSelectable(true)
        })
        if (answer.evidence.isNotEmpty()) {
            val pages = answer.evidence.map { it.pageNumber }.distinct().sorted().joinToString()
            val snippets = answer.evidence.take(2).joinToString("\n") { "“${it.text}”" }
            addView(label("Evidence · ${if (pages.contains(',')) "Pages" else "Page"} $pages\n$snippets", 13, muted).apply {
                setPadding(0, dp(14), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
            })
            addView(actionButton("View highlighted evidence", false) {
                val field = answer.fieldKey?.let { key -> currentInsurance.firstOrNull { it.key == key } }
                if (field == null) Toast.makeText(this@MainActivity, "Evidence is unavailable", Toast.LENGTH_SHORT).show()
                else {
                    evidenceReturnsToQa = true
                    showFieldEvidence(field, answer.evidence.first().pageNumber)
                }
            }, params(top = 14))
        }
    }

    private fun showFieldEvidence(requestedField: InsuranceField, requestedPage: Int? = null) {
        clearEvidenceBitmap()
        screen = Screen.FIELD_EVIDENCE
        val field = currentInsurance.firstOrNull { it.key == requestedField.key } ?: requestedField
        val evidencePages = field.evidence.map { it.pageNumber }.distinct().sorted()
        val pageNumber = requestedPage?.takeIf { it in evidencePages } ?: evidencePages.firstOrNull()
        currentEvidenceKey = field.key
        currentEvidencePage = pageNumber
        saveSession()
        val content = page()
        content.addView(textButton(if (evidenceReturnsToQa) "←  Back to conversation" else "←  Back to summary") {
            if (evidenceReturnsToQa) showAskKaagaz() else showInsuranceSummary()
        })
        content.addView(label("VERIFY FIELD", 12, forest).apply {
            letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10))
        })
        content.addView(label(field.key.label, 29, ink))
        content.addView(label(field.displayedValue ?: "Not found", 19, if (field.displayedValue == null) muted else ink).apply {
            setPadding(0, dp(10), 0, dp(12))
        })
        content.addView(label("●  ${fieldStatusLabel(field)}", 13, fieldStatusColor(field)))
        if (field.isEdited) {
            content.addView(label("OCR extracted: ${field.value ?: "Not found"} · ${statusLabel(field.status)}", 13, muted).apply { setPadding(0, dp(10), 0, 0) })
            content.addView(label("The edited value is user-provided; Kaagaz does not claim it appears in the source.", 13, muted).apply {
                setPadding(0, dp(5), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        field.reviewReason?.let { content.addView(messageCard("Why review is needed", it), params(top = 18)) }

        if (evidencePages.size > 1) {
            content.addView(label("EVIDENCE PAGES", 11, muted).apply { letterSpacing = .1f; setPadding(0, dp(20), 0, dp(8)) })
            evidencePages.forEach { page ->
                content.addView(actionButton("Page $page", page == pageNumber) { showFieldEvidence(field, page) }, params(top = 8))
            }
        }

        if (field.evidence.isEmpty() || pageNumber == null) {
            content.addView(messageCard("No supporting text found", "Kaagaz did not find a source line that safely supports this field."), params(top = 20))
        } else {
            val pageEvidence = field.evidence.filter { it.pageNumber == pageNumber }
            val validEvidence = pageEvidence.filter(::hasValidCoordinates)
            if (validEvidence.isEmpty()) {
                content.addView(messageCard("Highlight unavailable", "The supporting OCR text is preserved, but it does not contain usable source coordinates."), params(top = 20))
            } else {
                runCatching { buildEvidenceOverlay(pageNumber, validEvidence) }
                    .onSuccess { content.addView(it, params(top = 20)) }
                    .onFailure { content.addView(messageCard("Highlight unavailable", "The original page could not be rendered. Supporting text is still available below."), params(top = 20)) }
                if (validEvidence.size > 1) {
                    content.addView(label("Numbered colors distinguish each supporting or conflicting source line.", 13, muted).apply {
                        setPadding(0, dp(10), 0, 0)
                    })
                }
            }
            pageEvidence.forEachIndexed { index, evidence ->
                content.addView(evidenceCard(evidence), params(top = if (index == 0) 20 else 12))
            }
        }

        val editor = EditText(this).apply {
            setText(field.displayedValue ?: "")
            hint = "Enter a value"
            textSize = 16f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(16), dp(13), dp(16), dp(13)); minHeight = dp(56)
            background = rounded(Color.rgb(18, 24, 30), 14, Color.rgb(70, 82, 90))
            contentDescription = "Edit ${field.key.label}"
        }
        content.addView(label("EDIT VALUE", 11, muted).apply { letterSpacing = .1f; setPadding(0, dp(26), 0, dp(9)) })
        content.addView(editor)
        content.addView(actionButton("Save edited value", false) {
            saveFieldValue(field, editor.text.toString(), confirm = false)
        }, params(top = 12))
        content.addView(actionButton(if (field.isUserConfirmed) "Remove confirmation" else "Confirm this value", true) {
            if (field.isUserConfirmed) setConfirmation(field, false)
            else saveFieldValue(field, editor.text.toString(), confirm = true)
        }, params(top = 12))
        if (field.key == InsuranceFieldKey.POLICY_EXPIRY) {
            content.addView(actionButton("Set reminder", false) { showReminderSetup() }, params(top = 12))
        }
        content.addView(label("Confirmation is an explicit user action. OCR support and user confirmation remain separate.", 13, muted).apply {
            setPadding(0, dp(16), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
        setContentView(scroll(content))
    }

    private fun buildEvidenceOverlay(pageNumber: Int, evidence: List<OcrLineEvidence>): EvidenceOverlayView {
        val document = currentDocument ?: throw IOException("No document")
        val sourceWidth = evidence.first().sourceWidth
        val bitmap = when (document.type) {
            DocumentType.IMAGE -> decodeImage(document.uri)
            DocumentType.PDF -> renderPdfPage(document.uri, pageNumber - 1, sourceWidth)
        } ?: throw IOException("Unable to render evidence")
        evidenceBitmap = bitmap
        val colors = intArrayOf(forest, Color.rgb(255, 170, 80), Color.rgb(130, 176, 255), Color.rgb(240, 120, 170))
        val highlights = evidence.mapIndexed { index, item ->
            EvidenceOverlayView.Highlight(item.boundingBox!!, item.sourceWidth, item.sourceHeight, colors[index % colors.size], index + 1)
        }
        return EvidenceOverlayView(this, bitmap, highlights).apply {
            background = rounded(Color.rgb(18, 24, 30), 18, Color.rgb(36, 46, 54)); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun hasValidCoordinates(evidence: OcrLineEvidence): Boolean {
        val box = evidence.boundingBox ?: return false
        return evidence.sourceWidth > 0 && evidence.sourceHeight > 0 && box.width() > 0 && box.height() > 0 &&
            box.left >= 0 && box.top >= 0 && box.right <= evidence.sourceWidth && box.bottom <= evidence.sourceHeight
    }

    private fun saveFieldValue(field: InsuranceField, entered: String, confirm: Boolean) {
        val value = entered.trim()
        if (value.isBlank()) {
            Toast.makeText(this, "Enter a value before saving", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = field.copy(
            userValue = value.takeUnless { it == field.value },
            isUserConfirmed = confirm
        )
        replaceInsuranceField(updated)
        showFieldEvidence(updated)
        Toast.makeText(this, if (confirm) "Value confirmed by you" else "Edited value saved", Toast.LENGTH_SHORT).show()
    }

    private fun setConfirmation(field: InsuranceField, confirmed: Boolean) {
        val updated = field.copy(isUserConfirmed = confirmed)
        replaceInsuranceField(updated)
        showFieldEvidence(updated)
    }

    private fun replaceInsuranceField(updated: InsuranceField) {
        currentInsurance = currentInsurance.map { if (it.key == updated.key) updated else it }
        currentDocument?.id?.let { documentId ->
            runCatching { store.updateField(documentId, updated) }.onFailure {
                Toast.makeText(this, "Change could not be saved", Toast.LENGTH_LONG).show()
            }
            if (updated.key == InsuranceFieldKey.POLICY_EXPIRY) {
                ReminderScheduler(applicationContext).cancel(documentId)
                store.deleteReminder(documentId)
            }
        }
    }

    private fun clearEvidenceBitmap() {
        evidenceBitmap?.takeUnless { it.isRecycled }?.recycle()
        evidenceBitmap = null
    }

    private fun evidenceCard(evidence: OcrLineEvidence) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Color.rgb(18, 24, 30), 18, Color.rgb(36, 46, 54))
        addView(label("PAGE ${evidence.pageNumber}", 11, forest).apply { letterSpacing = .1f })
        addView(label(evidence.text, 17, ink).apply {
            setPadding(0, dp(10), 0, dp(10)); setTextIsSelectable(true)
        })
        val confidence = if (evidence.confidence >= 0f) "${(evidence.confidence * 100).toInt()}%" else "Unavailable"
        val box = evidence.boundingBox?.let { "${it.left}, ${it.top}, ${it.right}, ${it.bottom}" } ?: "Unavailable"
        addView(label("OCR confidence: $confidence\nSource box: $box · ${evidence.sourceWidth}×${evidence.sourceHeight}", 12, muted).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
    }

    private fun statusLabel(status: VerificationStatus) = when (status) {
        VerificationStatus.SUPPORTED -> "Supported by document text"
        VerificationStatus.NEEDS_REVIEW -> "Needs review"
        VerificationStatus.NOT_FOUND -> "Not found"
    }

    private fun statusColor(status: VerificationStatus) = when (status) {
        VerificationStatus.SUPPORTED -> forest
        VerificationStatus.NEEDS_REVIEW -> Color.rgb(255, 190, 92)
        VerificationStatus.NOT_FOUND -> muted
    }

    private fun fieldStatusLabel(field: InsuranceField) = when {
        field.isUserConfirmed -> if (field.isEdited) "User confirmed · edited" else "User confirmed"
        field.isEdited -> "Edited · not confirmed"
        else -> statusLabel(field.status)
    }

    private fun fieldStatusColor(field: InsuranceField) = when {
        field.isUserConfirmed -> Color.rgb(130, 176, 255)
        field.isEdited -> Color.rgb(255, 190, 92)
        else -> statusColor(field.status)
    }

    private fun showOcrError(message: String) {
        screen = Screen.OCR_RESULTS
        val content = page()
        content.addView(textButton("←  Back to document") {
            currentDocument?.let(::showDocumentPreview) ?: showHome()
        })
        content.addView(label("OCR COULDN'T FINISH", 12, Color.rgb(255, 120, 104)).apply {
            letterSpacing = .12f; setPadding(0, dp(30), 0, dp(12))
        })
        content.addView(label("Text extraction failed", 30, ink))
        content.addView(label(message, 16, muted).apply { setPadding(0, dp(12), 0, dp(24)) })
        content.addView(actionButton("Try again", true) { currentDocument?.let(::startOcr) })
        setContentView(scroll(content))
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Kaagaz OCR text", text))
        Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
    }

    private fun resultSummary(results: List<OcrPageResult>): String {
        val lineCount = results.sumOf { it.lines.size }
        return "${results.size} ${if (results.size == 1) "page" else "pages"} · $lineCount recognized ${if (lineCount == 1) "line" else "lines"}"
    }

    private fun ocrPageLimit(document: SelectedDocument) = minOf(document.pageCount, MAX_PDF_PAGES)

    private fun documentTypeLabel(document: SelectedDocument) = when (document.type) {
        DocumentType.IMAGE -> document.mimeType.substringAfter('/').uppercase() + " image"
        DocumentType.PDF -> "PDF · ${document.pageCount} ${if (document.pageCount == 1) "page" else "pages"}" +
            if (document.pageCount > MAX_PDF_PAGES) " · OCR first $MAX_PDF_PAGES" else ""
    }

    private fun showLibrary() {
        clearEvidenceBitmap()
        screen = Screen.LIBRARY
        clearSavedSession()
        val documents = runCatching { store.listDocuments() }.getOrElse {
            showError("Library unavailable", "Saved documents could not be read. Your local files have not been changed.")
            return
        }
        val content = page()
        content.addView(textButton("←  Back") { showHome() })
        content.addView(label("SAVED DOCUMENTS", 12, forest).apply {
            letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10))
        })
        content.addView(label("Your private library", 30, ink))
        content.addView(label("Stored only in Kaagaz's private app storage on this device.", 15, muted).apply {
            setPadding(0, dp(10), 0, dp(22))
        })
        if (documents.isEmpty()) content.addView(emptyState())
        documents.forEachIndexed { index, item ->
            content.addView(savedDocumentCard(item) { openSavedDocument(item) }, params(top = if (index == 0) 0 else 12))
        }
        content.addView(actionButton("＋  Add a document", true) { launchPicker() }, params(top = 22))
        setContentView(scroll(content))
    }

    private fun savedDocumentCard(item: SavedDocumentSummary, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(17), dp(18), dp(17))
        background = rounded(Color.rgb(18, 24, 30), 18, if (item.fileAvailable) Color.rgb(36, 46, 54) else Color.rgb(255, 140, 110))
        isClickable = true; isFocusable = true; setOnClickListener { action() }
        addView(label(item.name, 19, ink))
        val type = if (item.type == DocumentType.PDF) "PDF · ${item.pageCount} ${if (item.pageCount == 1) "page" else "pages"}" else "Image"
        addView(label(type, 13, muted).apply { setPadding(0, dp(7), 0, 0) })
        val summary = listOfNotNull(
            item.policyNumber?.let { "Policy $it" }, item.insuredName, item.expiryDate?.let { "Expires $it" }
        ).joinToString(" · ").ifBlank { "OCR summary not created yet" }
        addView(label(summary, 14, if (item.fileAvailable) forest else Color.rgb(255, 120, 104)).apply {
            setPadding(0, dp(9), 0, 0); maxLines = 2
        })
        if (!item.fileAvailable) addView(label("Original file unavailable · tap for options", 13, Color.rgb(255, 120, 104)).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun openSavedDocument(item: SavedDocumentSummary) {
        val stored = runCatching { store.load(item.id) }.getOrNull()
        if (stored == null) {
            Toast.makeText(this, "This saved record could not be opened", Toast.LENGTH_LONG).show()
            return
        }
        if (!item.fileAvailable) {
            AlertDialog.Builder(this)
                .setTitle("Original file unavailable")
                .setMessage("The private document copy is missing. Its saved record can still be deleted safely.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete record") { _, _ -> deleteDocument(stored.document) }
                .show()
            return
        }
        currentDocument = stored.document; currentOcr = stored.ocrPages; currentInsurance = stored.fields
        currentQaQuestion = null; currentCoverageQuestion = null; currentCoveragePassage = null; evidenceReturnsToQa = false
        previewReturnsToLibrary = true
        showDocumentPreview(stored.document)
    }

    private fun confirmDelete(document: SelectedDocument) {
        AlertDialog.Builder(this)
            .setTitle("Delete this document?")
            .setMessage("This permanently removes the private file, OCR text, extracted fields, edits, and confirmations from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteDocument(document) }
            .show()
    }

    private fun deleteDocument(document: SelectedDocument) {
        try {
            ReminderScheduler(applicationContext).cancel(document.id)
            if (!store.delete(document.id)) {
                Toast.makeText(this, "Could not delete the private file. Nothing was removed.", Toast.LENGTH_LONG).show()
                return
            }
            if (currentDocument?.id == document.id) {
                currentDocument = null; currentOcr = emptyList(); currentInsurance = emptyList(); clearSavedSession()
            }
            Toast.makeText(this, "Document deleted", Toast.LENGTH_SHORT).show()
            showLibrary()
        } catch (_: Exception) {
            Toast.makeText(this, "Document could not be deleted safely", Toast.LENGTH_LONG).show()
        }
    }

    private fun showReminderSetup() {
        clearEvidenceBitmap()
        screen = Screen.REMINDER
        saveSession()
        val document = currentDocument ?: return showHome()
        val field = currentInsurance.firstOrNull { it.key == InsuranceFieldKey.POLICY_EXPIRY }
        val eligibility = ReminderPolicy.evaluate(field)
        var existing = runCatching { store.getReminder(document.id) }.getOrNull()
        if (existing?.state == ReminderState.SCHEDULED && !hasNotificationPermission()) {
            existing = existing.copy(state = ReminderState.SAVED_PERMISSION_REQUIRED, updatedAt = System.currentTimeMillis())
            runCatching { store.saveReminder(existing) }
        }
        val content = page()
        content.addView(textButton("←  Back to summary") { showInsuranceSummary() })
        content.addView(label("DEADLINE REMINDER", 12, forest).apply { letterSpacing = .14f; setPadding(0, dp(28), 0, dp(10)) })
        content.addView(label("Policy expiry", 30, ink))
        content.addView(label("Kaagaz schedules approximate local background work. Android may delay delivery to protect battery.", 15, muted).apply {
            setPadding(0, dp(10), 0, dp(20)); setLineSpacing(dp(3).toFloat(), 1f)
        })
        content.addView(messageCard("Deadline", "${field?.displayedValue ?: "Not found"}\n${eligibility.sourceLabel}\n${eligibility.reason}"))
        field?.evidence?.firstOrNull()?.let { evidence ->
            content.addView(actionButton("Review source evidence · page ${evidence.pageNumber}", false) {
                evidenceReturnsToQa = false; showFieldEvidence(field, evidence.pageNumber)
            }, params(top = 12))
        }
        if (!eligibility.allowed || eligibility.date == null) {
            content.addView(messageCard("Reminder not ready", when {
                field?.isEdited == true && !field.isUserConfirmed -> "This edited date must be explicitly confirmed before scheduling."
                field?.status == VerificationStatus.NEEDS_REVIEW && field.isUserConfirmed.not() -> "Resolve or confirm the uncertain date before scheduling."
                else -> eligibility.reason
            }), params(top = 18))
            setContentView(scroll(content)); return
        }
        val now = System.currentTimeMillis()
        val available = ReminderPolicy.allowedOffsets.filter { offset ->
            eligibility.date.minusDays(offset.toLong()).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() > now
        }.sortedDescending()
        if (available.isEmpty()) {
            content.addView(messageCard("No future reminder times", "The deadline has not passed, but all available 9:00 AM reminder times have."), params(top = 18))
            setContentView(scroll(content)); return
        }
        content.addView(label("REMIND ME", 11, muted).apply { letterSpacing = .1f; setPadding(0, dp(24), 0, dp(8)) })
        val checks = available.map { offset -> CheckBox(this).apply {
            text = when (offset) { 0 -> "On the deadline day"; 1 -> "1 day before"; else -> "$offset days before" }
            textSize = 16f; setTextColor(ink); minHeight = dp(52); isChecked = existing?.offsetsDays?.contains(offset) ?: (offset == 7)
            content.addView(this)
        } }
        existing?.let { content.addView(messageCard("Saved reminder", "${it.offsetsDays.sortedDescending().joinToString()} day offset(s) · ${reminderStateLabel(it.state)}"), params(top = 14)) }
        content.addView(actionButton("Review and save reminder", true) {
            val offsets = ReminderPolicy.selectedOffsets(available, checks.map { it.isChecked })
            if (offsets.isEmpty()) Toast.makeText(this, "Choose at least one reminder time", Toast.LENGTH_SHORT).show()
            else confirmReminder(document, field!!, eligibility.date, eligibility.sourceLabel, offsets)
        }, params(top = 18))
        existing?.let {
            content.addView(actionButton("Cancel reminder", false) {
                ReminderScheduler(applicationContext).cancel(document.id); store.deleteReminder(document.id)
                Toast.makeText(this, "Reminder cancelled", Toast.LENGTH_SHORT).show(); showReminderSetup()
            }, params(top = 12))
        }
        setContentView(scroll(content))
    }

    private fun confirmReminder(document: SelectedDocument, field: InsuranceField, date: LocalDate, sourceLabel: String, offsets: Set<Int>) {
        val schedule = offsets.sortedDescending().joinToString("\n") { if (it == 0) "Deadline day at 9:00 AM" else "$it day(s) before at 9:00 AM" }
        AlertDialog.Builder(this).setTitle("Save this reminder?")
            .setMessage("Policy expiry: ${field.displayedValue}\nSource: $sourceLabel\n\n$schedule\n\nDelivery time is approximate and stays on this device.")
            .setNegativeButton("Back", null).setPositiveButton("Save") { _, _ ->
                val reminder = DeadlineReminder(document.id, "POLICY_EXPIRY", date.toEpochDay(), field.displayedValue!!, sourceLabel, offsets,
                    if (hasNotificationPermission()) ReminderState.SCHEDULED else ReminderState.SAVED_PERMISSION_REQUIRED, System.currentTimeMillis())
                if (hasNotificationPermission()) persistAndSchedule(reminder)
                else if (Build.VERSION.SDK_INT >= 33) {
                    pendingReminder = reminder
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else persistAndSchedule(reminder)
            }.show()
    }

    private fun persistAndSchedule(reminder: DeadlineReminder) {
        store.saveReminder(reminder.copy(state = ReminderState.SCHEDULED, updatedAt = System.currentTimeMillis()))
        val scheduled = ReminderScheduler(applicationContext).schedule(reminder)
        val saved = reminder.copy(state = if (scheduled) ReminderState.SCHEDULED else ReminderState.SCHEDULING_FAILED, updatedAt = System.currentTimeMillis())
        store.saveReminder(saved)
        Toast.makeText(this, if (scheduled) "Reminder scheduled locally" else "Reminder saved, but scheduling failed", Toast.LENGTH_LONG).show()
        showReminderSetup()
    }

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun reminderStateLabel(state: ReminderState) = when (state) {
        ReminderState.SCHEDULED -> "Scheduled locally"
        ReminderState.SAVED_PERMISSION_REQUIRED -> "Saved · notification permission unavailable"
        ReminderState.SCHEDULING_FAILED -> "Saved · scheduling failed"
    }

    private fun restoreSession(state: Bundle?): Boolean {
        val preferences = getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        val documentId = state?.getString(STATE_DOCUMENT_ID) ?: preferences.getString(STATE_DOCUMENT_ID, null) ?: return false
        val stored = runCatching { store.load(documentId) }.getOrNull() ?: return false
        if (!java.io.File(stored.document.uri.path.orEmpty()).isFile) return false
        currentDocument = stored.document; currentOcr = stored.ocrPages; currentInsurance = stored.fields
        previewReturnsToLibrary = preferences.getBoolean(STATE_FROM_LIBRARY, false)
        val screenName = state?.getString(STATE_SCREEN) ?: preferences.getString(STATE_SCREEN, Screen.PREVIEW.name)
        val target = runCatching { Screen.valueOf(screenName ?: Screen.PREVIEW.name) }.getOrDefault(Screen.PREVIEW)
        val keyName = state?.getString(STATE_FIELD_KEY) ?: preferences.getString(STATE_FIELD_KEY, null)
        currentEvidenceKey = keyName?.let { runCatching { InsuranceFieldKey.valueOf(it) }.getOrNull() }
        currentEvidencePage = if (state?.containsKey(STATE_EVIDENCE_PAGE) == true) state.getInt(STATE_EVIDENCE_PAGE) else
            preferences.getInt(STATE_EVIDENCE_PAGE, 0).takeIf { it > 0 }
        currentQaQuestion = state?.getString(STATE_QA_QUESTION) ?: preferences.getString(STATE_QA_QUESTION, null)
        currentCoverageQuestion = state?.getString(STATE_COVERAGE_QUESTION) ?: preferences.getString(STATE_COVERAGE_QUESTION, null)
        evidenceReturnsToQa = state?.getBoolean(STATE_EVIDENCE_RETURNS_QA)
            ?: preferences.getBoolean(STATE_EVIDENCE_RETURNS_QA, false)
        when (target) {
            Screen.OCR_RESULTS -> showOcrResults(currentOcr)
            Screen.INSURANCE_SUMMARY -> showInsuranceSummary()
            Screen.QA -> showAskKaagaz()
            Screen.COVERAGE -> showCoverageIntelligence()
            Screen.COVERAGE_EVIDENCE -> {
                val passage = currentCoverageQuestion?.let { CoverageIntelligenceEngine.answer(it, currentOcr, currentInsurance).passages.firstOrNull() }
                if (passage == null) showCoverageIntelligence() else showCoverageEvidence(passage, currentEvidencePage)
            }
            Screen.REMINDER -> showReminderSetup()
            Screen.FIELD_EVIDENCE -> currentEvidenceKey?.let { key -> currentInsurance.firstOrNull { it.key == key }?.let { showFieldEvidence(it, currentEvidencePage) } }
                ?: showInsuranceSummary()
            else -> showDocumentPreview(stored.document)
        }
        return true
    }

    private fun saveSession() {
        val document = currentDocument ?: return
        getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit()
            .putString(STATE_DOCUMENT_ID, document.id)
            .putString(STATE_SCREEN, screen.name)
            .putBoolean(STATE_FROM_LIBRARY, previewReturnsToLibrary)
            .apply {
                currentEvidenceKey?.let { putString(STATE_FIELD_KEY, it.name) } ?: remove(STATE_FIELD_KEY)
                currentEvidencePage?.let { putInt(STATE_EVIDENCE_PAGE, it) } ?: remove(STATE_EVIDENCE_PAGE)
                currentQaQuestion?.let { putString(STATE_QA_QUESTION, it) } ?: remove(STATE_QA_QUESTION)
                currentCoverageQuestion?.let { putString(STATE_COVERAGE_QUESTION, it) } ?: remove(STATE_COVERAGE_QUESTION)
                putBoolean(STATE_EVIDENCE_RETURNS_QA, evidenceReturnsToQa)
            }.apply()
    }

    private fun clearSavedSession() {
        getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun pdfPageCount(uri: Uri): Int {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Unable to open PDF")
        return descriptor.use { file -> PdfRenderer(file).use { renderer -> renderer.pageCount } }
    }

    private fun renderPdfPage(uri: Uri, pageIndex: Int, targetWidth: Int): Bitmap {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Unable to open PDF")
        return descriptor.use { file ->
            PdfRenderer(file).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val width = targetWidth.coerceAtMost(page.width * 3)
                    val height = (width.toFloat() / page.width * page.height).toInt()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > 2200) decoder.setTargetSampleSize((largest + 2199) / 2200)
        }
    } else contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)

    private fun queryName(uri: Uri): String? = contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.take(160) else null }

    private fun previewWidth() = resources.displayMetrics.widthPixels - dp(48)

    private fun imageFrame(bitmap: Bitmap, name: String, pdf: Boolean) = LinearLayout(this).apply {
        gravity = Gravity.CENTER; minimumHeight = dp(300)
        background = rounded(Color.rgb(18, 24, 30), 22, Color.rgb(36, 46, 54))
        addView(ImageView(this@MainActivity).apply {
            setImageBitmap(bitmap); adjustViewBounds = true
            scaleType = if (pdf) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_INSIDE
            setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = if (pdf) "First page preview of $name" else "Preview of $name"
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showError(title: String, message: String) {
        screen = Screen.ERROR
        val content = page()
        content.addView(textButton("←  Back") { showHome() })
        content.addView(label("COULDN'T OPEN DOCUMENT", 12, Color.rgb(255, 120, 104)).apply {
            letterSpacing = .12f; setPadding(0, dp(32), 0, dp(12))
        })
        content.addView(label(title, 30, ink))
        content.addView(label(message, 16, muted).apply { setPadding(0, dp(12), 0, dp(24)) })
        content.addView(actionButton("Choose a different document", true) { launchPicker() })
        setContentView(scroll(content))
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(32)); setBackgroundColor(paper)
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true; setBackgroundColor(paper); addView(content); padForSystemBars()
    }

    private fun privacyCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
        background = rounded(Color.rgb(16, 38, 32), 22)
        addView(label("●  PRIVATE BY DESIGN", 12, forest).apply { letterSpacing = .09f })
        addView(label("Your files stay on this device. OCR runs locally with a bundled model.", 15, ink).apply {
            setPadding(0, dp(9), 0, 0); setLineSpacing(dp(2).toFloat(), 1f)
        })
    }

    private fun statusCard(note: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
        background = rounded(Color.rgb(16, 38, 32), 18)
        addView(label("●  ON-DEVICE ONLY", 12, forest).apply { letterSpacing = .08f })
        addView(label(note, 14, ink).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun messageCard(title: String, message: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20))
        background = rounded(Color.rgb(18, 24, 30), 22, Color.rgb(36, 46, 54))
        addView(label(title, 19, ink))
        addView(label(message, 14, muted).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun textCard(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(ink); setTextIsSelectable(true)
        setLineSpacing(dp(4).toFloat(), 1f); setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(Color.rgb(18, 24, 30), 18, Color.rgb(36, 46, 54))
    }

    private fun emptyState() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(24), dp(28), dp(24), dp(28)); background = rounded(Color.rgb(18, 24, 30), 22, Color.rgb(36, 46, 54))
        addView(label("▤", 32, forest).apply { gravity = Gravity.CENTER })
        addView(label("No documents yet", 20, ink).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(6)) })
        addView(label("Add an image or PDF to begin.", 14, muted).apply { gravity = Gravity.CENTER })
    }

    private fun actionButton(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 16f; isAllCaps = false; gravity = Gravity.CENTER
        setTextColor(if (primary) paper else forest)
        background = rounded(if (primary) forest else Color.TRANSPARENT, 18, forest)
        minHeight = dp(58); setOnClickListener { action() }
    }

    private fun textButton(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 16f; setTextColor(forest); gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8)); isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    private fun label(text: String, size: Int, color: Int) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(radius).toFloat()
            setColor(fill); stroke?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun closeRun(run: OcrRun) {
        if (run.closed.compareAndSet(false, true)) run.recognizer.close()
    }

    private data class OcrRun(
        val recognizer: TextRecognizer,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val closed: AtomicBoolean = AtomicBoolean(false)
    )
    private enum class Screen { HOME, LIBRARY, PREVIEW, OCR_LOADING, OCR_RESULTS, INSURANCE_SUMMARY, QA, COVERAGE, FIELD_EVIDENCE, COVERAGE_EVIDENCE, REMINDER, ERROR }

    companion object {
        const val EXTRA_OPEN_DOCUMENT = "com.kaagaz.app.OPEN_DOCUMENT"
        const val EXTRA_PICK_DOCUMENT = "com.kaagaz.app.PICK_DOCUMENT"
        const val EXTRA_LIBRARY = "com.kaagaz.app.LIBRARY"
        private const val MAX_PDF_PAGES = 10
        private const val OCR_RENDER_WIDTH = 1800
        private const val PROGRESS_TAG = "ocr_progress"
        private const val SESSION_PREFS = "kaagaz_session"
        private const val STATE_DOCUMENT_ID = "document_id"
        private const val STATE_SCREEN = "screen"
        private const val STATE_FIELD_KEY = "field_key"
        private const val STATE_EVIDENCE_PAGE = "evidence_page"
        private const val STATE_FROM_LIBRARY = "from_library"
        private const val STATE_QA_QUESTION = "qa_question"
        private const val STATE_COVERAGE_QUESTION = "coverage_question"
        private const val STATE_EVIDENCE_RETURNS_QA = "evidence_returns_qa"
    }
}
