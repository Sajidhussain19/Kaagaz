package com.kaagaz.app.ocr

import android.content.Context
import android.net.Uri
import com.kaagaz.app.data.KaagazStore

/**
 * Five clearly labelled FICTIONAL documents bundled in assets/demo, with planted problems
 * (graduation year 2024 vs 2023, date of birth 14 March vs 14 May, one bill saved twice).
 * Installing them runs the real pipeline, so every finding is computed from OCR, not scripted.
 */
object DemoPack {
    val files = listOf("aarav-resume.pdf", "degree-certificate.pdf", "transcript.pdf", "electricity-bill.pdf", "electricity-bill-copy.pdf", "scholarship-application-form.pdf", "personal-loan-statement.pdf")

    fun missing(store: KaagazStore): List<String> {
        val present = store.listDocuments().map { it.name }.toSet()
        return files.filter { it !in present }
    }

    fun install(context: Context, store: KaagazStore, onProgress: (String) -> Unit): Int {
        val ingestor = DocumentIngestor(context, store)
        val todo = missing(store)
        todo.forEachIndexed { i, name ->
            onProgress("Demo pack ${i + 1}/${todo.size}: $name")
            val file = ingestor.assetToFile("demo/$name")
            try { ingestor.ingest(Uri.fromFile(file), name) { onProgress("${i + 1}/${todo.size} · $it") } } finally { file.delete() }
        }
        return todo.size
    }
}
