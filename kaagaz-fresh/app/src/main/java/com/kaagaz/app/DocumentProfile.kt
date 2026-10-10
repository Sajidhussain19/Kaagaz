package com.kaagaz.app

import java.util.Locale

/** What kind of document this is, decided only from explicit words in its own OCR text. */
enum class DocumentKind(val title: String) {
    MOTOR_INSURANCE("Motor insurance policy"),
    HEALTH_INSURANCE("Health insurance policy"),
    LIFE_SAVINGS("Life or savings policy"),
    PAYMENT_STATEMENT("Premium payment statement"),
    INSURANCE_OTHER("Insurance document"),
    UNKNOWN("Document")
}

/**
 * Separates document-type presentation from generic field extraction: the extractor still produces every field,
 * and this decides which fields and questions are relevant to show. It never hides a value that was found or edited.
 */
object DocumentProfile {
    private val signals = mapOf(
        DocumentKind.PAYMENT_STATEMENT to listOf("payment statement", "payment history", "premium paid", "payment received", "statement date", "receipt number"),
        DocumentKind.MOTOR_INSURANCE to listOf("vehicle registration", "registration no", "registration number", "own damage", "third party", "idv", "motor", "chassis", "engine no", "engine number", "private car", "two wheeler"),
        DocumentKind.LIFE_SAVINGS to listOf("maturity", "sum assured", "life insured", "life assured", "child", "education benefit", "savings plan", "bonus", "endowment", "death benefit", "policy term"),
        DocumentKind.HEALTH_INSURANCE to listOf("hospitalisation", "hospitalization", "room rent", "cashless", "sum insured", "pre existing", "day care", "in patient", "ambulance")
    )

    fun detect(pages: List<OcrPageResult>): DocumentKind {
        val text = normalize(pages.joinToString(" ") { page -> page.lines.joinToString(" ") { it.text }.ifBlank { page.text } })
        if (text.isBlank()) return DocumentKind.UNKNOWN
        val scores = signals.mapValues { (_, terms) -> terms.count { Regex("\\b${Regex.escape(it)}").containsMatchIn(text) } }
        val best = scores.maxByOrNull { it.value } ?: return DocumentKind.UNKNOWN
        val tied = scores.count { it.value == best.value } > 1
        return when {
            best.value >= 2 && !tied -> best.key
            text.contains("insur") || text.contains("policy") -> DocumentKind.INSURANCE_OTHER
            else -> DocumentKind.UNKNOWN
        }
    }

    private fun relevantKeys(kind: DocumentKind): Set<InsuranceFieldKey> = when (kind) {
        DocumentKind.MOTOR_INSURANCE -> InsuranceFieldKey.values().toSet()
        DocumentKind.PAYMENT_STATEMENT -> setOf(InsuranceFieldKey.INSURER, InsuranceFieldKey.POLICY_NUMBER, InsuranceFieldKey.INSURED_NAME)
        DocumentKind.LIFE_SAVINGS -> setOf(InsuranceFieldKey.INSURER, InsuranceFieldKey.POLICY_NUMBER, InsuranceFieldKey.INSURED_NAME, InsuranceFieldKey.PREMIUM, InsuranceFieldKey.POLICY_START)
        else -> InsuranceFieldKey.values().toSet() - InsuranceFieldKey.VEHICLE_REGISTRATION
    }

    /** Field cards to show: relevant fields that have a value, plus any field with a found or user-entered value. */
    fun fieldsToShow(kind: DocumentKind, fields: List<InsuranceField>): List<InsuranceField> =
        fields.filter { it.displayedValue != null || it.isUserConfirmed }

    /** Relevant fields with no value, listed once instead of as a column of "Not found" cards. */
    fun missingFields(kind: DocumentKind, fields: List<InsuranceField>): List<InsuranceFieldKey> {
        val keys = relevantKeys(kind)
        val shown = fieldsToShow(kind, fields).map { it.key }.toSet()
        return InsuranceFieldKey.values().filter { it in keys && it !in shown }
    }

    fun suggestedQuestions(kind: DocumentKind, pages: List<OcrPageResult>): List<String> {
        val ages = pages.flatMap { page -> page.lines.flatMap { line ->
            Regex("payable at age (\\d{1,2})", RegexOption.IGNORE_CASE).findAll(line.text).map { it.groupValues[1] }.toList()
        } }.distinct().take(1)
        return when (kind) {
            DocumentKind.LIFE_SAVINGS -> listOf("What does this policy cover?") +
                ages.map { "How much is payable at age $it?" } +
                listOf("What is payable at maturity?", "What is guaranteed and what is projected?", "How much have I paid?", "How much is outstanding?", "What are the exclusions and waiting periods?")
            DocumentKind.HEALTH_INSURANCE -> listOf("What does this policy cover?", "Does this policy cover diabetes?", "What are the exclusions and waiting periods?", "What limits or sub-limits apply?", "What are the claim requirements?", "How much have I paid?")
            DocumentKind.MOTOR_INSURANCE -> listOf("What does this policy cover?", "What exclusions are listed?", "What are the claim requirements?", "What important dates should I know?", "How much have I paid?")
            DocumentKind.PAYMENT_STATEMENT -> listOf("How much have I paid?", "How much remains scheduled?", "How much is outstanding?")
            else -> listOf("What does this policy cover?", "What exclusions are listed?", "Are there waiting periods?", "What important dates should I know?", "How much have I paid?")
        }.distinct().take(6)
    }

    /** Field questions for Ask Kaagaz that make sense for this kind of document. */
    fun fieldQuestions(kind: DocumentKind): List<String> = when (kind) {
        DocumentKind.MOTOR_INSURANCE -> listOf("When does my policy expire?", "What is the vehicle registration number?", "What is my policy number?", "How much premium is listed?")
        DocumentKind.PAYMENT_STATEMENT -> listOf("What is my policy number?", "Who is the insured person?")
        DocumentKind.LIFE_SAVINGS -> listOf("What is my policy number?", "When does the policy start?", "How much premium is listed?")
        else -> listOf("When does my policy expire?", "What is my policy number?", "How much premium is listed?")
    } + "What information is missing or needs review?"

    fun askIntro(kind: DocumentKind): String = when (kind) {
        DocumentKind.MOTOR_INSURANCE -> "Ask about cover, exclusions, claims, payments, policy dates, number, premium, insured person or vehicle registration."
        DocumentKind.LIFE_SAVINGS -> "Ask what the policy provides, what is payable at an age or at maturity, what is guaranteed or projected, and what has been paid or is still due."
        DocumentKind.HEALTH_INSURANCE -> "Ask whether a condition is covered, and about exclusions, waiting periods, limits, claims, payments and policy dates."
        DocumentKind.PAYMENT_STATEMENT -> "Ask how much has been paid, what is still scheduled, and what is outstanding."
        else -> "Ask about cover, exclusions, waiting periods, payments, policy dates, number, premium or insured person."
    }

    /** One short line in the at-a-glance card; [passage] is the source sentence when there is one. */
    data class OverviewRow(val label: String, val value: String, val passage: CoverageIntelligenceEngine.Passage? = null)

    /** A compact summary built only from engine answers and supported fields; rows with no evidence are left out. */
    fun overview(kind: DocumentKind, pages: List<OcrPageResult>, fields: List<InsuranceField>): List<OverviewRow> {
        fun ask(question: String) = CoverageIntelligenceEngine.answer(question, pages, fields)
        val rows = mutableListOf<OverviewRow>()
        if (kind != DocumentKind.PAYMENT_STATEMENT) {
            val cover = ask("What does this policy cover?")
            // Prefer benefits that state an amount; a general sentence is shown only when nothing more specific exists.
            val highlights = CoverageIntelligenceEngine.benefitHighlights(cover.passages, limit = 4)
            val specific = highlights.filter { CoverageIntelligenceEngine.amountIn(it.text) != null }.take(3)
            specific.ifEmpty { highlights.take(1) }.forEach { rows += OverviewRow("Provides", it.text, it.passage) }
        }
        if (kind == DocumentKind.LIFE_SAVINGS) {
            val projected = ask("What projected benefit is shown?")
            val amount = projected.takeIf { it.kind == CoverageIntelligenceEngine.AnswerKind.RESULTS }?.summary?.let(CoverageIntelligenceEngine::amountIn)
            if (amount != null) rows += OverviewRow("Projected", "$amount, not guaranteed", projected.passages.firstOrNull())
        }
        fields.firstOrNull { it.key == InsuranceFieldKey.PREMIUM && it.displayedValue != null && it.status != VerificationStatus.NOT_FOUND }?.let { premium ->
            val frequency = premiumFrequency(premium.evidence.map { it.text } + pages.flatMap { it.lines }.map { it.text }.filter { normalize(it).contains("premium") })
            rows += OverviewRow("Premium", listOfNotNull(premium.displayedValue, frequency).joinToString(" · ") + if (premium.status == VerificationStatus.NEEDS_REVIEW) " (needs review)" else "")
        }
        if (kind == DocumentKind.PAYMENT_STATEMENT) {
            listOf("Paid" to "How much have I paid?", "Outstanding" to "How much is outstanding?", "Scheduled" to "How much remains scheduled?").forEach { (label, question) ->
                val answer = ask(question)
                val amount = answer.takeIf { it.kind == CoverageIntelligenceEngine.AnswerKind.RESULTS }?.summary?.let(CoverageIntelligenceEngine::amountIn)
                if (amount != null) rows += OverviewRow(label, amount, answer.passages.firstOrNull())
            }
        } else {
            val safeguards = ask("What are the exclusions and waiting periods?").passages
            val waiting = safeguards.count { it.category == CoverageIntelligenceEngine.Category.WAITING_PERIOD }
            val exclusions = safeguards.count { it.category == CoverageIntelligenceEngine.Category.EXCLUSION }
            val parts = listOfNotNull(
                waiting.takeIf { it > 0 }?.let { "$it waiting period${if (it > 1) "s" else ""}" },
                exclusions.takeIf { it > 0 }?.let { "$it exclusion${if (it > 1) "s" else ""}" }
            )
            if (parts.isNotEmpty()) rows += OverviewRow("Watch for", parts.joinToString(" · "))
        }
        return rows
    }

    private fun premiumFrequency(lines: List<String>): String? {
        val text = normalize(lines.joinToString(" "))
        return listOf("half yearly" to "Half-yearly", "quarterly" to "Quarterly", "monthly" to "Monthly", "annually" to "Yearly", "annual" to "Yearly", "yearly" to "Yearly", "single premium" to "Single premium")
            .firstOrNull { (term, _) -> Regex("\\b$term\\b").containsMatchIn(text) }?.second
    }

    private fun normalize(text: String) = text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9%₹]+"), " ").trim()
}
