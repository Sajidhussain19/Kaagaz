package com.kaagaz.app.rag

import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.Fact
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.Normalizer
import com.kaagaz.app.llm.LlmClient
import com.kaagaz.app.llm.LlmResult

/**
 * Type-aware document summary. The "at a glance" part is deterministic (extracted facts with page evidence);
 * the optional narrative comes from Gemma and is accepted only if every number in it occurs in the document text.
 */
object DocumentSummarizer {
    data class GlanceRow(val label: String, val value: String, val page: Int, val evidence: String)
    data class Narrative(val text: String?, val source: String, val note: String)

    /** Fact types worth showing first for each kind of document. */
    private fun priority(kind: DocKind): List<FactType> = when (kind) {
        DocKind.RESUME -> listOf(FactType.FULL_NAME, FactType.EMAIL, FactType.PHONE, FactType.JOB_TITLE, FactType.EMPLOYER, FactType.DEGREE, FactType.INSTITUTION, FactType.GRADUATION_YEAR, FactType.CGPA, FactType.SKILLS)
        DocKind.EDUCATION_CERTIFICATE, DocKind.TRANSCRIPT -> listOf(FactType.FULL_NAME, FactType.DEGREE, FactType.INSTITUTION, FactType.GRADUATION_YEAR, FactType.CGPA, FactType.DATE_OF_BIRTH)
        DocKind.LOAN_STATEMENT -> listOf(FactType.LOAN_AMOUNT, FactType.EMI_AMOUNT, FactType.DUE_DATE, FactType.OUTSTANDING_PRINCIPAL, FactType.INTEREST_RATE, FactType.LOAN_TENURE, FactType.ACCOUNT_HOLDER)
        DocKind.BILL -> listOf(FactType.BILLER, FactType.ACCOUNT_HOLDER, FactType.BILL_NUMBER, FactType.BILL_DATE, FactType.BILL_AMOUNT, FactType.DUE_DATE)
        else -> FactType.values().toList()
    }

    fun glance(kind: DocKind, facts: List<Fact>): List<GlanceRow> {
        val order = priority(kind)
        return facts.sortedBy { order.indexOf(it.type).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .distinctBy { it.type }
            .map { GlanceRow(it.type.label, Normalizer.display(it.type, it.normalized, it.rawValue), it.page, it.evidence) }
    }

    fun headline(kind: DocKind, facts: List<Fact>): String {
        fun v(t: FactType) = facts.firstOrNull { it.type == t }?.let { Normalizer.display(t, it.normalized, it.rawValue) }
        return when (kind) {
            DocKind.RESUME -> listOfNotNull(v(FactType.FULL_NAME), v(FactType.JOB_TITLE)?.let { "· $it" }, v(FactType.EMPLOYER)?.let { "at $it" }).joinToString(" ").ifBlank { "Résumé" }
            DocKind.EDUCATION_CERTIFICATE, DocKind.TRANSCRIPT -> listOfNotNull(v(FactType.DEGREE), v(FactType.GRADUATION_YEAR)?.let { "· $it" }).joinToString(" ").ifBlank { kind.title }
            DocKind.LOAN_STATEMENT -> listOfNotNull(v(FactType.EMI_AMOUNT)?.let { "EMI $it" }, v(FactType.DUE_DATE)?.let { "due $it" }).joinToString(" ").ifBlank { "Loan statement" }
            DocKind.BILL -> listOfNotNull(v(FactType.BILL_AMOUNT)?.let { "$it due" }, v(FactType.DUE_DATE)?.let { "on $it" }).joinToString(" ").ifBlank { "Bill" }
            else -> kind.title
        }
    }

    fun prompt(kind: DocKind, pages: List<OcrPageResult>): String {
        val text = pages.flatMap { it.lines }.joinToString("\n") { it.text }.take(MAX_CHARS)
        return "Document type: ${kind.title}\nDocument text:\n$text\n\nSummarise this document for its owner in at most three short sentences. " +
            "State only what the text explicitly says; do not infer events (such as graduating or paying) that it does not state. " +
            "Refer to people by their name, never with gendered pronouns. Copy numbers exactly and do not give advice."
    }

    fun narrative(kind: DocKind, pages: List<OcrPageResult>, llm: LlmClient): Narrative {
        if (!llm.isReady) return Narrative(null, "none", "No language model is loaded; the summary above is built from extracted facts only.")
        return when (val r = llm.generate(prompt(kind, pages), SYSTEM, maxTokens = 140, timeoutMs = 45_000)) {
            is LlmResult.Text -> {
                val docText = pages.joinToString(" ") { p -> p.lines.joinToString(" ") { it.text } }
                val checked = groundSentences(r.text.lines().filter { it.isNotBlank() }.joinToString(" "), docText, GroundedAnswerer(llm))
                val kept = checked.filter { it.second == null }.joinToString(" ") { it.first }
                val removed = checked.mapNotNull { it.second }
                when {
                    kept.isBlank() -> Narrative(null, llm.label, "Gemma's summary was rejected: ${removed.joinToString("; ")}.")
                    else -> Narrative(runCatching { neutralizePronouns(kept) }.getOrDefault(kept).take(420), llm.label,
                        "Written by ${llm.label} on device in ${r.latencyMs} ms; names and numbers checked against the document" +
                            (if (removed.isEmpty()) "." else ". Removed ${removed.size} sentence${if (removed.size == 1) "" else "s"}: ${removed.joinToString("; ")}."))
                }
            }
            is LlmResult.Failed -> Narrative(null, llm.label, "Gemma could not summarise this document: ${r.reason}.")
            is LlmResult.Unavailable -> Narrative(null, "none", r.reason)
        }
    }

    /**
     * Sentence-level grounding: a sentence survives only if every number and every multi-word capitalised name in it
     * occurs in the document. Returns each sentence with the reason it was removed (null when kept).
     */
    fun groundSentences(text: String, documentText: String, numbersOf: GroundedAnswerer): List<Pair<String, String?>> {
        val docNumbers = numbersOf.numbers(documentText)
        val docNorm = Normalizer.text(documentText)
        val name = Regex("\\b[A-Z][A-Za-z]+(?:\\s+(?:of|and|&)?\\s*[A-Z][A-Za-z]+)+")
        return text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.map { sentence ->
            val badNumber = numbersOf.numbers(sentence).firstOrNull { it !in docNumbers }
            val badName = name.findAll(sentence).map { it.value }.firstOrNull { !docNorm.contains(Normalizer.text(it)) }
            sentence.trim() to when {
                badNumber != null -> "it stated $badNumber, which is not in the document"
                badName != null -> "it named “$badName”, which is not in the document"
                else -> null
            }
        }
    }

    /**
     * Documents rarely state anyone's pronouns and small models guess them anyway, so gendered pronouns in model
     * output become singular "they" (with verb agreement for the common auxiliaries).
     */
    fun neutralizePronouns(text: String): String {
        val verbs = mapOf("is" to "are", "was" to "were", "has" to "have", "does" to "do")
        var out = Regex("\\b(he|she|He|She)\\s+(is|was|has|does)\\b").replace(text) { m ->
            val subject = if (m.groupValues[1][0].isUpperCase()) "They" else "they"
            "$subject ${verbs.getValue(m.groupValues[2])}"
        }
        val words = mapOf("he" to "they", "she" to "they", "him" to "them", "his" to "their", "hers" to "theirs", "himself" to "themselves", "herself" to "themselves")
        out = Regex("\\b(he|she|him|his|hers|himself|herself|He|She|Him|His|Hers|Himself|Herself)\\b").replace(out) { m ->
            val lower = m.value.lowercase()
            val repl = words.getValue(lower)
            if (m.value[0].isUpperCase()) repl.replaceFirstChar { it.uppercase() } else repl
        }
        // "her" is either object ("them") or possessive ("their"); before a noun-like word it is possessive.
        val notNouns = setOf("her", "his", "their", "them", "to", "and", "a", "an", "the", "in", "on", "for", "with", "by", "at", "from", "that", "as", "or", "is", "was")
        // Android's ICU regex rejects a quantified lookahead "(?=…)?"; an empty alternative is portable.
        out = Regex("\\b([Hh])er\\b(?=\\s+([a-z]+)|)").replace(out) { m ->
            val next = m.groups[2]?.value
            val base = if (next != null && next !in notNouns) "their" else "them"
            if (m.groupValues[1] == "H") base.replaceFirstChar { it.uppercase() } else base
        }
        return out
    }

    const val MAX_CHARS = 3_000
    private const val SYSTEM = "You summarise personal documents accurately. Never invent facts, names, dates or amounts."
}
