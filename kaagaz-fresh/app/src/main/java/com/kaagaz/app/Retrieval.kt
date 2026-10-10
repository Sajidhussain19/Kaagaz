package com.kaagaz.app

import kotlin.math.ln

/** A retrievable passage: one or more whole sentences from one page, with the OCR lines they came from. */
data class Chunk(val id: String, val documentId: String, val documentName: String, val page: Int, val text: String, val lines: List<OcrLineEvidence>)

data class ScoredChunk(val chunk: Chunk, val score: Double, val bm25: Double, val bm25Rank: Int?, val factRank: Int?, val matched: Set<String>)

object Chunker {
    const val MAX_CHARS = 360

    fun chunks(documentId: String, documentName: String, pages: List<OcrPageResult>, boxOf: (OcrLineEvidence) -> OcrBox? = { it.ocrBox() }): List<Chunk> {
        val out = mutableListOf<Chunk>()
        pages.forEach { page ->
            val clauses = ClauseAssembler.clauses(page, boxOf)
            var buffer = StringBuilder(); var lines = mutableListOf<OcrLineEvidence>()
            fun flush() {
                if (buffer.isNotBlank()) out += Chunk("$documentId:${page.pageNumber}:${out.size}", documentId, documentName, page.pageNumber, buffer.toString().trim(), lines.distinct())
                buffer = StringBuilder(); lines = mutableListOf()
            }
            clauses.forEach { clause ->
                if (buffer.length + clause.text.length > MAX_CHARS) flush()
                buffer.append(clause.text).append(' '); lines.addAll(clause.lines)
                if (Regex("[.!?]$").containsMatchIn(clause.text.trim()) && buffer.length > MAX_CHARS / 2) flush()
            }
            flush()
        }
        return out
    }
}

/**
 * Okapi BM25 keyword ranking (k1 = 1.2, b = 0.75) fused with a structured-fact signal by Reciprocal Rank Fusion
 * (k = 60): a chunk containing the evidence line of a fact whose type the question asks about gets a second rank.
 * There is no embedding model in this build, so there is no semantic signal; the UI says so.
 */
class HybridRetriever(private val chunks: List<Chunk>, private val facts: List<Fact> = emptyList()) {
    private val stop = setOf("a", "an", "the", "is", "are", "was", "were", "what", "which", "who", "when", "where", "how", "my", "me", "i", "of", "in", "on",
        "for", "to", "do", "does", "did", "this", "that", "and", "or", "any", "much", "many", "there", "be", "it", "with", "as", "by", "at", "from", "have", "has", "can", "you", "your", "about", "tell")
    private val expansions = mapOf(
        "paid" to listOf("payment", "premium"), "pay" to listOf("payment", "due"), "owe" to listOf("due", "outstanding"),
        "cover" to listOf("covered", "coverage"), "covered" to listOf("cover", "coverage"), "graduate" to listOf("graduation", "passing"),
        "graduated" to listOf("graduation", "passing"), "born" to listOf("birth"), "dob" to listOf("birth"), "bill" to listOf("amount", "due"),
        "college" to listOf("university", "institute"), "university" to listOf("college", "institute"), "salary" to listOf("ctc"),
        "deadline" to listOf("due"), "expire" to listOf("expiry"), "expires" to listOf("expiry")
    )
    private val tokenized = chunks.map { tokens(it.text) }
    private val avgLen = tokenized.map { it.size }.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
    private val df: Map<String, Int> = tokenized.flatMap { it.toSet() }.groupingBy { it }.eachCount()

    fun tokens(text: String): List<String> = Normalizer.text(text).split(' ').filter { it.length > 1 && it !in stop }.map(::stem)

    private fun stem(word: String) = when {
        word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("es") && !word.endsWith("ses") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    fun queryTerms(question: String): Set<String> {
        val base = Normalizer.text(question).split(' ').filter { it.length > 1 && it !in stop }
        return (base + base.flatMap { expansions[it].orEmpty() }).map(::stem).toSet()
    }

    fun search(question: String, k: Int = 4, minBm25: Double = 1.2): List<ScoredChunk> {
        val terms = queryTerms(question)
        if (terms.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val bm25 = chunks.indices.map { i ->
            val doc = tokenized[i]; val tf = doc.groupingBy { it }.eachCount()
            terms.sumOf { t ->
                val f = tf[t] ?: return@sumOf 0.0
                val idf = ln(1 + (n - (df[t] ?: 0) + .5) / ((df[t] ?: 0) + .5))
                idf * (f * 2.2) / (f + 1.2 * (1 - .75 + .75 * doc.size / avgLen))
            }
        }
        val bmRanked = chunks.indices.filter { bm25[it] >= minBm25 }.sortedByDescending { bm25[it] }
        val wanted = FieldIdentifier.identify(question)
        val factLines = facts.filter { it.type == wanted }.map { Normalizer.text(it.evidence.substringAfterLast("→").trim()) }.filter { it.isNotBlank() }
        val factRanked = if (factLines.isEmpty()) emptyList() else chunks.indices.filter { i -> factLines.any { Normalizer.text(chunks[i].text).contains(it) } }
        val candidates = (bmRanked + factRanked).distinct()
        return candidates.map { i ->
            val br = bmRanked.indexOf(i).takeIf { it >= 0 }; val fr = factRanked.indexOf(i).takeIf { it >= 0 }
            val fused = (br?.let { 1.0 / (60 + it + 1) } ?: 0.0) + (fr?.let { 1.0 / (60 + it + 1) } ?: 0.0)
            ScoredChunk(chunks[i], fused, bm25[i], br, fr, terms.filter { it in tokenized[i] }.toSet())
        }.sortedByDescending { it.score }.take(k)
    }
}
