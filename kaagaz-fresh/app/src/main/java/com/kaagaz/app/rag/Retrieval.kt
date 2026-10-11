package com.kaagaz.app.rag

import com.kaagaz.app.core.ClauseAssembler
import com.kaagaz.app.core.OcrBox
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.core.ocrBox
import com.kaagaz.app.forms.FieldIdentifier
import com.kaagaz.app.knowledge.Fact
import com.kaagaz.app.knowledge.Normalizer
import kotlin.math.abs
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
 *
 * Two lexical bridges stand in for an embedding model: a paperwork concept thesaurus ("EMI" ↔ instalment, "dies" ↔
 * death benefit) and OCR-tolerant fuzzy matching, where a question word absent from every document also matches a
 * document word one or two edits away ("premlum" ↔ "premium") at reduced weight. Numbers never match fuzzily.
 */
class HybridRetriever(private val chunks: List<Chunk>, private val facts: List<Fact> = emptyList()) {
    private val stop = setOf("a", "an", "the", "is", "are", "was", "were", "what", "which", "who", "when", "where", "how", "my", "me", "i", "of", "in", "on",
        "for", "to", "do", "does", "did", "this", "that", "and", "or", "any", "much", "many", "there", "be", "it", "with", "as", "by", "at", "from", "have", "has", "can", "you", "your", "about", "tell")
    private val expansions = mapOf(
        "paid" to listOf("payment", "premium"), "pay" to listOf("payment", "due"), "owe" to listOf("due", "outstanding"),
        "cover" to listOf("covered", "coverage"), "covered" to listOf("cover", "coverage"), "graduate" to listOf("graduation", "passing"),
        "graduated" to listOf("graduation", "passing"), "born" to listOf("birth"), "dob" to listOf("birth"), "bill" to listOf("amount", "due"),
        "college" to listOf("university", "institute"), "university" to listOf("college", "institute"), "salary" to listOf("ctc"),
        "deadline" to listOf("due"), "expire" to listOf("expiry"), "expires" to listOf("expiry"),
        // Paperwork concept thesaurus: everyday words → the words documents actually use.
        "emi" to listOf("instalment", "installment", "loan"), "instalment" to listOf("emi"), "installment" to listOf("emi"),
        "loan" to listOf("emi", "principal", "borrower"), "interest" to listOf("rate", "roi"), "balance" to listOf("outstanding", "principal"),
        "die" to listOf("death", "demise", "nominee"), "dies" to listOf("death", "demise", "nominee"), "died" to listOf("death", "demise"),
        "death" to listOf("demise", "nominee"), "hospital" to listOf("hospitalisation", "hospitalization", "inpatient"),
        "marks" to listOf("cgpa", "grade", "percentage"), "grade" to listOf("cgpa", "marks"), "gpa" to listOf("cgpa", "sgpa"), "score" to listOf("cgpa", "marks"),
        "phone" to listOf("mobile", "contact"), "mobile" to listOf("phone", "contact"), "email" to listOf("mail"),
        "renew" to listOf("renewal", "expiry"), "units" to listOf("consumed", "kwh"), "electricity" to listOf("power", "energy", "units"),
        "penalty" to listOf("late", "fee", "fine"), "fine" to listOf("late", "penalty"), "refund" to listOf("refunded", "deposit"),
        "job" to listOf("experience", "employer", "role"), "work" to listOf("experience", "employer"), "skills" to listOf("skill"),
        "insured" to listOf("policyholder", "assured"), "policyholder" to listOf("insured", "assured"), "start" to listOf("commencement"),
        "maturity" to listOf("mature", "matures"), "sum" to listOf("assured", "cover")
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

    /** Document words within one edit (two for long words) of a question word that no document contains. */
    fun fuzzyTerms(terms: Set<String>): Set<String> = terms.filter { it !in df && it.length >= 5 && it.none(Char::isDigit) }.flatMap { t ->
        val max = if (t.length >= 8) 2 else 1
        df.keys.filter { v -> v.length >= 4 && v.none(Char::isDigit) && abs(v.length - t.length) <= max && editDistance(t, v, max) <= max }
    }.toSet()

    fun search(question: String, k: Int = 4, minBm25: Double = 1.2): List<ScoredChunk> {
        val exact = queryTerms(question)
        if (exact.isEmpty() || chunks.isEmpty()) return emptyList()
        val weights = exact.associateWith { 1.0 } + (fuzzyTerms(exact) - exact).associateWith { FUZZY_WEIGHT }
        val terms = weights.keys
        val n = chunks.size.toDouble()
        val bm25 = chunks.indices.map { i ->
            val doc = tokenized[i]; val tf = doc.groupingBy { it }.eachCount()
            terms.sumOf { t ->
                val f = tf[t] ?: return@sumOf 0.0
                val idf = ln(1 + (n - (df[t] ?: 0) + .5) / ((df[t] ?: 0) + .5))
                weights.getValue(t) * idf * (f * 2.2) / (f + 1.2 * (1 - .75 + .75 * doc.size / avgLen))
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

    companion object {
        const val FUZZY_WEIGHT = 0.7

        /** Levenshtein distance, giving up early once it must exceed [max]. */
        fun editDistance(a: String, b: String, max: Int): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1); cur[0] = i; var best = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    best = minOf(best, cur[j])
                }
                if (best > max) return max + 1
                prev = cur
            }
            return prev[b.length]
        }
    }
}
