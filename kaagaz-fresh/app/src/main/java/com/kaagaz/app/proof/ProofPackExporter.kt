package com.kaagaz.app.proof

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.kaagaz.app.audit.Finding
import com.kaagaz.app.data.KaagazStore
import com.kaagaz.app.data.KnowledgeSnapshot
import com.kaagaz.app.forms.FieldProposal
import com.kaagaz.app.forms.FormSpec
import com.kaagaz.app.knowledge.TrustState
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The Proof Pack: a portable PDF in which every fact and proposed form value carries its source document, page and
 * quoted evidence, plus a SHA-256 fingerprint of each source file so a reviewer can check the evidence came from
 * exactly those documents. Generated on device; it leaves the phone only if the user shares it.
 */
class ProofPackExporter(private val context: Context, private val store: KaagazStore) {
    data class Input(
        val snapshot: KnowledgeSnapshot,
        val form: FormSpec?,
        val proposals: List<FieldProposal>,
        val findings: List<Finding>,
        val modelLine: String
    )

    fun export(input: Input): File {
        val pdf = PdfDocument()
        val writer = Writer(pdf)
        val s = input.snapshot
        writer.title("Kaagaz Proof Pack")
        writer.muted("Generated ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm"))} on this device · ${input.modelLine}")
        writer.muted("Every value below cites the document, page and exact text it came from. Conflicts and gaps are shown, not hidden.")
        writer.gap()

        val c = s.counts
        writer.heading("Trust ledger")
        writer.body("${c.verified} verified · ${c.conflicting} conflicting · ${c.needsConfirmation} need confirmation · ${c.missing} missing")
        s.ledger.forEach { e ->
            writer.row(e.type.label, e.groups.firstOrNull()?.display ?: "—", e.state)
            e.groups.forEach { g ->
                g.facts.forEach { f -> writer.evidence("${g.display} — ${f.documentName}, p.${f.page} (${f.method.label}): “${f.evidence.take(150)}”") }
            }
            if (e.resolvedByUser) writer.evidence("Chosen by the user; competing sources kept on record.")
        }

        if (input.form != null) {
            writer.gap(); writer.heading("Form mapping · ${input.form.title}")
            input.proposals.forEach { p ->
                writer.row(p.field.label, p.value ?: "—", if (p.field.sensitiveReason != null) null else p.state, flag = p.field.sensitiveReason?.let { "REVIEW FLAG" })
                writer.evidence(p.note)
            }
        }

        writer.gap(); writer.heading("Open crash-test findings (${input.findings.size})")
        if (input.findings.isEmpty()) writer.body("None.")
        input.findings.forEach { f ->
            writer.body("[${f.severity.label}] ${f.title}")
            writer.evidence(f.explanation)
            f.evidence.take(2).forEach { writer.evidence("↳ ${it.documentName}, p.${it.page}: “${it.text.take(140)}”") }
        }

        writer.gap(); writer.heading("Source fingerprints (SHA-256)")
        val usedDocs = (s.ledger.flatMap { e -> e.groups.flatMap { g -> g.facts.map { it.documentId } } } +
            input.findings.flatMap { f -> f.evidence.map { it.documentId } } + input.proposals.flatMap { p -> p.sources.map { it.documentId } }).distinct()
        usedDocs.mapNotNull { id ->
            val stored = runCatching { store.load(id) }.getOrNull() ?: return@mapNotNull null
            val file = stored.document.uri.path?.let(::File)
            "${stored.document.name} · ${if (file?.isFile == true) sha256(file) else "file unavailable"}"
        }.distinct().forEach(writer::evidence) // the same file imported twice has one fingerprint
        writer.gap()
        writer.muted("Kaagaz never submits forms. Values marked conflicting, missing or review flag were not filled automatically.")
        writer.finish()

        val dir = File(context.cacheDir, "proof").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "kaagaz-proof-pack-${System.currentTimeMillis() / 1000}.pdf")
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return out
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Minimal flowing-text PDF writer on A4 (595×842 pt) with automatic page breaks. */
    private class Writer(private val pdf: PdfDocument) {
        private val margin = 44f; private val width = 595f - 2 * 44f
        private var pageNo = 0; private var page: PdfDocument.Page? = null; private var y = 0f
        private fun paint(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
        }
        private val ink = Color.rgb(20, 28, 33); private val muted = Color.rgb(96, 110, 116)

        private fun ensure(height: Float) {
            if (page == null || y + height > 842f - margin) {
                page?.let { footer(it); pdf.finishPage(it) }
                pageNo++; page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); y = margin
            }
        }

        private fun footer(p: PdfDocument.Page) {
            p.canvas.drawText("Kaagaz Proof Pack · page $pageNo · generated on device", margin, 842f - 22f, paint(8f, muted))
        }

        private fun lines(text: String, p: Paint, maxWidth: Float): List<String> {
            val out = mutableListOf<String>(); var line = ""
            text.split(' ').forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= maxWidth) line = candidate else { if (line.isNotEmpty()) out += line; line = word }
            }
            if (line.isNotEmpty()) out += line
            return out
        }

        private fun draw(text: String, p: Paint, indent: Float = 0f, spacing: Float = 1.35f) {
            lines(text, p, width - indent).forEach { l -> ensure(p.textSize * spacing); y += p.textSize * spacing; page!!.canvas.drawText(l, margin + indent, y, p) }
        }

        fun title(text: String) = draw(text, paint(22f, ink, bold = true))
        fun heading(text: String) { ensure(30f); y += 8f; draw(text.uppercase(), paint(10.5f, Color.rgb(23, 107, 82), bold = true)) }
        fun body(text: String) = draw(text, paint(10.5f, ink))
        fun muted(text: String) = draw(text, paint(9f, muted))
        fun evidence(text: String) = draw(text, paint(8.5f, muted), indent = 14f)
        fun gap() { y += 8f }

        fun row(label: String, value: String, state: TrustState?, flag: String? = null) {
            val stateText = flag ?: state?.label?.uppercase() ?: ""
            val color = when { flag != null -> Color.rgb(120, 80, 200); state == TrustState.VERIFIED -> Color.rgb(23, 107, 82)
                state == TrustState.CONFLICTING -> Color.rgb(190, 60, 50); state == TrustState.MISSING -> muted; else -> Color.rgb(190, 120, 20) }
            ensure(18f); y += 16f
            val canvas = page!!.canvas
            canvas.drawText(label, margin, y, paint(10.5f, ink, bold = true))
            canvas.drawText(value.take(48), margin + 150f, y, paint(10.5f, ink))
            val sp = paint(8.5f, color, bold = true); canvas.drawText(stateText, 595f - margin - sp.measureText(stateText), y, sp)
        }

        fun finish() { page?.let { footer(it); pdf.finishPage(it) } }
    }
}
