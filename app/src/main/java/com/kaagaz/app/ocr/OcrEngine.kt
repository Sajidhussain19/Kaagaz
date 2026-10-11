package com.kaagaz.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kaagaz.app.core.DocumentType
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking, on-device OCR (Google ML Kit Latin recogniser, bundled model) for background threads.
 * Same rendering parameters as the interactive document screen, so results are identical.
 */
class OcrEngine(private val context: Context) {
    fun pageCount(uri: Uri): Int {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Unable to open PDF")
        return descriptor.use { file -> PdfRenderer(file).use { it.pageCount } }
    }

    /** Recognises up to [maxPages] pages. [onPage] reports progress; [cancelled] stops between pages. */
    fun recognize(
        uri: Uri,
        type: DocumentType,
        maxPages: Int = MAX_PAGES,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<OcrPageResult> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            if (type == DocumentType.IMAGE) {
                val bitmap = decodeImage(uri) ?: throw IOException("Image could not be decoded")
                return listOf(recognizeBitmap(recognizer, bitmap, 1)).also { onPage(1, 1) }
            }
            val total = minOf(pageCount(uri), maxPages)
            return (0 until total).map { index ->
                if (cancelled.get()) throw InterruptedException("OCR cancelled")
                recognizeBitmap(recognizer, renderPdfPage(uri, index, RENDER_WIDTH), index + 1).also { onPage(index + 1, total) }
            }
        } finally {
            recognizer.close()
        }
    }

    private fun recognizeBitmap(recognizer: com.google.mlkit.vision.text.TextRecognizer, bitmap: Bitmap, pageNumber: Int): OcrPageResult {
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 60, TimeUnit.SECONDS)
            val lines = result.textBlocks.flatMap { block ->
                block.lines.map { line -> OcrLineEvidence(pageNumber, line.text, line.boundingBox, line.confidence, bitmap.width, bitmap.height) }
            }
            return OcrPageResult(pageNumber, result.text, lines)
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderPdfPage(uri: Uri, pageIndex: Int, targetWidth: Int): Bitmap {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Unable to open PDF")
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
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > 2200) decoder.setTargetSampleSize((largest + 2199) / 2200)
        }
    } else context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)

    companion object {
        const val MAX_PAGES = 10
        const val RENDER_WIDTH = 1800
    }
}
