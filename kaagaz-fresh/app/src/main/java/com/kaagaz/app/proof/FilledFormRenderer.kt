package com.kaagaz.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import com.kaagaz.app.audit.AuditDocument
import com.kaagaz.app.core.DocumentType
import com.kaagaz.app.core.ocrBox
import com.kaagaz.app.data.KaagazStore
import com.kaagaz.app.forms.FieldProposal
import com.kaagaz.app.forms.FormLabelExtractor
import java.io.File
import java.time.LocalDate

/**
 * Print-ready filled form: the user's own blank form (scan or PDF) with every verified value written beside its label,
 * at the position OCR found. Conflicting, sensitive and signature fields stay blank with a margin note.
 * Page 2 lists the source document and page of every value. Generated on device; printed or shared only by the user.
 */
class FilledFormRenderer(private val context: Context, private val store: KaagazStore) {
    data class Result(val file: File, val filled: Int, val leftBlank: Int)

    fun render(form: AuditDocument, proposals: List<FieldProposal>): Result {
        val stored = store.load(form.id) ?: error("Form not found")
        val byLabel = proposals.associateBy { it.field.label.lowercase() }
        val pdf = PdfDocument()
        var filled = 0; var blank = 0
        form.pages.forEachIndexed { index, page ->
            val lines = page.lines.filter { it.boundingBox != null }
            val first = lines.firstOrNull() ?: page.lines.firstOrNull() ?: return@forEachIndexed
            val w = first.sourceWidth; val h = first.sourceHeight
            val bitmap = pageBitmap(stored.document.uri.path!!, stored.document.type, index, w, h)
            val canvas = android.graphics.Canvas(bitmap)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 52, 140); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
            val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 60, 50); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC) }
            val hits = FormLabelExtractor.hits(listOf(page))
            // One consistent "handwriting" size: the median label-line height on this page.
            val lineHeight = hits.mapNotNull { it.line.ocrBox()?.height }.sorted().let { if (it.isEmpty()) h / 60 else it[it.size / 2] }.toFloat()
            hits.forEach { hit ->
                val box = hit.line.ocrBox() ?: return@forEach
                val p = byLabel[hit.label.lowercase()] ?: return@forEach
                // Value starts just after the label text ("Applicant name:"), on the blank line.
                val labelEnd = hit.line.text.indexOf(':').takeIf { it > 0 } ?: hit.label.length
                val x = box.left + (box.right - box.left) * (labelEnd + 2f) / hit.line.text.length.coerceAtLeast(1)
                ink.textSize = lineHeight * 0.85f; note.textSize = lineHeight * 0.6f
                if (p.fillable) {
                    canvas.drawText(p.value!!, x, box.centerY + lineHeight * 0.3f, ink); filled++
                } else {
                    val why = when {
                        p.field.label.contains("signature", true) -> "sign here yourself"
                        p.field.sensitiveReason != null -> "left blank: sensitive, check before sharing"
                        p.state.name == "CONFLICTING" -> "left blank: your documents disagree"
                        else -> "left blank: not in your documents"
                    }
                    canvas.drawText(why, x, box.centerY + lineHeight * 0.25f, note); blank++
                }
            }
            val stamp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 107, 82); textSize = w * 0.014f }
            canvas.drawText("Prepared with Kaagaz from verified documents · sources on the last page · check before signing", w * 0.06f, h * 0.955f, stamp)
            val pageOut = pdf.startPage(PdfDocument.PageInfo.Builder(595, (595f * h / w).toInt(), index + 1).create())
            pageOut.canvas.drawBitmap(bitmap, Rect(0, 0, w, h), RectF(0f, 0f, 595f, 595f * h / w), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(pageOut); bitmap.recycle()
        }
        // Sources page.
        val src = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, form.pages.size + 1).create())
        val c = src.canvas; var y = 60f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(20, 28, 33) }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.rgb(20, 28, 33) }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(96, 110, 116) }
        c.drawText("Where each value came from", 44f, y, title); y += 14f
        c.drawText("${form.name} · filled ${LocalDate.now()} on device · $filled filled, $blank left for you", 44f, y + 10f, muted); y += 34f
        proposals.forEach { p ->
            if (y > 800f) return@forEach
            c.drawText("${p.field.label}: ${p.value ?: "—"}", 44f, y, body); y += 13f
            c.drawText(p.note.take(110), 58f, y, muted); y += 17f
        }
        pdf.finishPage(src)
        val dir = File(context.cacheDir, "proof").apply { mkdirs() }
        val out = File(dir, "filled-${form.name.substringBeforeLast('.')}-${System.currentTimeMillis() / 1000}.pdf")
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return Result(out, filled, blank)
    }

    /** Renders the page at exactly the size OCR saw, so line boxes line up with the drawing. */
    private fun pageBitmap(path: String, type: DocumentType, index: Int, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        if (type == DocumentType.PDF) {
            android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r -> r.openPage(index).use { it.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) } }
            }
        } else {
            BitmapFactory.decodeFile(path)?.let { src ->
                android.graphics.Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG)); src.recycle()
            }
        }
        return out
    }
}
