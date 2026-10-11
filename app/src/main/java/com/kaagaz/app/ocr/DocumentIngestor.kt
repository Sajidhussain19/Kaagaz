package com.kaagaz.app.ocr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kaagaz.app.core.DocumentType
import com.kaagaz.app.data.KaagazStore
import com.kaagaz.app.insurance.InsuranceExtractor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One pipeline for every way a document arrives (picker, share sheet, camera, demo pack):
 * copy into private storage → on-device OCR → field extraction → save. Call from a background thread.
 */
class DocumentIngestor(private val context: Context, private val store: KaagazStore) {
    data class Result(val documentId: String, val name: String, val pages: Int, val lines: Int)

    fun ingest(uri: Uri, nameHint: String? = null, mimeHint: String? = null, cancelled: AtomicBoolean = AtomicBoolean(false), onProgress: (String) -> Unit = {}): Result {
        val name = nameHint ?: displayName(uri) ?: "Shared document"
        // Providers do not always report a type (MediaStore downloads may return null); fall back to the sender's
        // declared type, then the display name's extension, never to the opaque last path segment alone.
        val mime = listOfNotNull(context.contentResolver.getType(uri), mimeHint)
            .map { it.lowercase() }.firstOrNull { it == "application/pdf" || it.startsWith("image/") }
            ?: guessMime(name).takeIf { it != OCTET } ?: guessMime(uri.lastPathSegment.orEmpty())
        val type = when {
            mime == "application/pdf" -> DocumentType.PDF
            mime.startsWith("image/") -> DocumentType.IMAGE
            else -> throw IllegalArgumentException("Unsupported file type: $mime")
        }
        val engine = OcrEngine(context)
        val pages = if (type == DocumentType.PDF) engine.pageCount(uri) else 1
        onProgress("Saving $name privately…")
        val document = store.import(uri, name, type, mime, pages)
        // If OCR fails the saved file is kept, so the user can retry from the document screen.
        val results = engine.recognize(document.uri, type, cancelled = cancelled) { done, total -> onProgress("Reading $name · page $done of $total") }
        store.saveAnalysis(document.id, results, InsuranceExtractor.extract(results))
        return Result(document.id, name, results.size, results.sumOf { it.lines.size })
    }

    private fun displayName(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment
    else context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "heic" -> "image/heic"
        else -> OCTET
    }

    private companion object { const val OCTET = "application/octet-stream" }

    /** Copies a bundled asset into the cache so it can be ingested like any user file. */
    fun assetToFile(assetPath: String): File {
        val target = File(context.cacheDir, "intake").apply { mkdirs() }.resolve(assetPath.substringAfterLast('/'))
        context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }
}
