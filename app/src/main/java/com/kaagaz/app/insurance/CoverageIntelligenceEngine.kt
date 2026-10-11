package com.kaagaz.app.insurance

import com.kaagaz.app.core.ClauseAssembler
import com.kaagaz.app.core.InsuranceField
import com.kaagaz.app.core.InsuranceFieldKey
import com.kaagaz.app.core.OcrBox
import com.kaagaz.app.core.OcrClause
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.core.VerificationStatus
import com.kaagaz.app.core.ocrBox
import java.util.Locale

/** Deterministic, offline retrieval over one saved document's OCR lines. */
object CoverageIntelligenceEngine {
    enum class Category(val label: String) {
        BENEFIT("Explicit benefit or coverage"),
        CONDITIONAL("Coverage subject to conditions"),
        EXCLUSION("Exclusion"),
        WAITING_PERIOD("Waiting period"),
        LIMIT("Limit or sub-limit"),
        CLAIM_REQUIREMENT("Claim requirement"),
        PAYMENT_RECORD("Verified payment record"),
        PREMIUM_SCHEDULE("Scheduled premium"),
        OUTSTANDING_DUE("Outstanding due"),
        MATURITY("Maturity benefit"),
        PROJECTED_BENEFIT("Projected benefit (not guaranteed)"),
        IMPORTANT_DATE("Important policy date"),
        MENTION_ONLY("Mentioned; coverage not established")
    }

    enum class AnswerKind { RESULTS, NOT_FOUND, AMBIGUOUS, UNSUPPORTED }

    data class Passage(
        val category: Category,
        val explanation: String,
        val wording: String,
        val evidence: List<OcrLineEvidence>,
        val score: Int,
        val sourceDocumentId: String? = null,
        val sourceDocumentName: String? = null
    )

    data class Answer(
        val question: String,
        val summary: String,
        val kind: AnswerKind,
        val passages: List<Passage> = emptyList()
    )

    private enum class Intent { BENEFITS, CONDITIONS, EXCLUSIONS, WAITING, EXCLUSIONS_AND_WAITING, LIMITS, CLAIMS, DISEASES, DISEASE_QUERY, EXPLAIN, PAYMENTS, FUTURE_PREMIUM, PREMIUM_REMAINING, MATURITY, PROJECTED_MATURITY, GUARANTEED_AND_PROJECTED, IMPORTANT_DATES }

    /** True when the question matches one of the supported coverage, benefit or payment question types. */
    fun supports(question: String): Boolean = question.isNotBlank() && identifyIntent(normalize(question.trim())) != null

    /** The first explicit currency amount in a sentence, exactly as written. */
    fun amountIn(text: String): String? = supportedAmount(text)

    fun answer(
        question: String,
        pages: List<OcrPageResult>,
        fields: List<InsuranceField> = emptyList(),
        maxResults: Int = 6,
        boxOf: (OcrLineEvidence) -> OcrBox? = { it.ocrBox() }
    ): Answer {
        val clean = question.trim()
        if (clean.isBlank()) return Answer(clean, "Type one of the supported coverage questions.", AnswerKind.UNSUPPORTED)
        val normalized = normalize(clean)
        val intent = identifyIntent(normalized)
            ?: return Answer(clean, "This question is not supported yet. Ask about benefits, diseases or conditions, exclusions, waiting periods, limits, or claim requirements.", AnswerKind.UNSUPPORTED)
        if (pages.isEmpty() || pages.all { it.lines.isEmpty() }) {
            return Answer(clean, "No saved OCR text is available for this document.", AnswerKind.NOT_FOUND)
        }

        val queryTerms = if (intent == Intent.DISEASE_QUERY || intent == Intent.EXPLAIN) meaningfulTerms(normalized) else emptySet()
        if (intent == Intent.EXPLAIN && queryTerms.isEmpty()) {
            return Answer(clean, "Name or paste a distinctive phrase from the clause so Kaagaz can find it in this policy.", AnswerKind.AMBIGUOUS)
        }
        val fieldDates = if (intent == Intent.IMPORTANT_DATES) importantDatePassages(fields) else emptyList()
        val requestedAge = if (intent == Intent.MATURITY) Regex("(?:age\\s*|turns\\s*|reaches\\s*)(\\d{1,2})", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.get(1) else null
        val candidates = mergeSameSource(pages.flatMap { page -> retrievePage(page, intent, queryTerms, boxOf) } + fieldDates)
            .filter { passage -> requestedAge == null || Regex("(?:age|aged)[\\s-]*$requestedAge\\b", RegexOption.IGNORE_CASE).containsMatchIn(passage.wording) }
            .filter { passage ->
                intent != Intent.PREMIUM_REMAINING || !normalized.contains("outstanding") || passage.category == Category.OUTSTANDING_DUE
            }
            .filter { passage -> intent != Intent.FUTURE_PREMIUM || passage.category == Category.PREMIUM_SCHEDULE }
            .sortedWith(compareByDescending<Passage> { it.score }.thenBy { it.evidence.firstOrNull()?.pageNumber ?: Int.MAX_VALUE })
            .take(maxResults)

        if (candidates.isEmpty()) {
            val direct = when (intent) {
                Intent.PAYMENTS -> "Payment history not found."
                Intent.FUTURE_PREMIUM -> "A future scheduled premium was not found."
                Intent.PREMIUM_REMAINING -> "No verified outstanding premium or future scheduled premium was found."
                Intent.MATURITY -> "Maturity date, amount, and eligibility conditions were not found."
                Intent.PROJECTED_MATURITY -> "A projected benefit was not found."
                Intent.IMPORTANT_DATES -> "Supported policy dates were not found."
                else -> null
            }
            if (direct != null) return Answer(clean, direct, AnswerKind.NOT_FOUND)
            val subject = when (intent) {
                Intent.BENEFITS -> "benefit or coverage clauses"
                Intent.CONDITIONS -> "coverage conditions"
                Intent.EXCLUSIONS -> "exclusions"
                Intent.WAITING -> "waiting periods"
                Intent.EXCLUSIONS_AND_WAITING -> "exclusions or waiting periods"
                Intent.LIMITS -> "limits or sub-limits"
                Intent.CLAIMS -> "claim requirements"
                Intent.DISEASES, Intent.DISEASE_QUERY -> "relevant disease or condition wording"
                Intent.EXPLAIN -> "that clause"
                Intent.PAYMENTS -> "payment history"
                Intent.FUTURE_PREMIUM -> "a future scheduled premium"
                Intent.PREMIUM_REMAINING -> "an outstanding premium or future premium schedule"
                Intent.MATURITY -> "a maturity date, amount, or eligibility clause"
                Intent.PROJECTED_MATURITY -> "a projected benefit"
                Intent.GUARANTEED_AND_PROJECTED -> "guaranteed or projected benefit wording"
                Intent.IMPORTANT_DATES -> "supported policy dates"
            }
            return Answer(clean, "I could not find $subject in the saved OCR text. Check the complete policy or insurer before relying on this result.", AnswerKind.NOT_FOUND)
        }

        val resultPassages = when {
            intent == Intent.PAYMENTS -> enrichPaymentPassages(candidates, pages)
            intent == Intent.PREMIUM_REMAINING && normalized.contains("outstanding") ->
                candidates.filter { it.category == Category.OUTSTANDING_DUE }
            else -> candidates
        }

        if (resultPassages.isEmpty()) {
            return Answer(clean, "No verified outstanding premium was found.", AnswerKind.NOT_FOUND)
        }

        if (intent == Intent.MATURITY && requestedAge == null) {
            val maturityAmounts = labelledClauses(resultPassages, maturityTerms)
                .filter { normalize(it).contains("maturity") }
                .mapNotNull(::supportedAmount)
                .distinctBy { it.filter(Char::isDigit) }
            if (maturityAmounts.size > 1) return Answer(
                clean,
                "The document states different maturity amounts (${maturityAmounts.take(2).joinToString(" and ")}). Check both source clauses before relying on either.",
                AnswerKind.AMBIGUOUS,
                resultPassages
            )
        }

        val summary = when (intent) {
            Intent.DISEASE_QUERY -> diseaseSummary(resultPassages, queryTerms)
            Intent.DISEASES -> diseaseSummary(resultPassages, emptySet())
            Intent.BENEFITS -> benefitSummary(resultPassages)
            Intent.CONDITIONS -> "${countSummary(resultPassages, mapOf(Category.CONDITIONAL to "conditional benefit"))} Review the original wording before relying on the benefit."
            Intent.EXCLUSIONS -> "${countSummary(resultPassages, mapOf(Category.EXCLUSION to "exclusion"))} ${firstExample(resultPassages, Category.EXCLUSION) ?: "These are worded as exclusions or non-payable situations, not benefits."}"
            Intent.WAITING -> waitingSummary(resultPassages)
            Intent.EXCLUSIONS_AND_WAITING -> "${countSummary(resultPassages, mapOf(Category.WAITING_PERIOD to "waiting period", Category.EXCLUSION to "exclusion"))} Check both before relying on a benefit."
            Intent.LIMITS -> "${countSummary(resultPassages, mapOf(Category.LIMIT to "limit or cap"))} ${firstExample(resultPassages, Category.LIMIT) ?: "Payment may be restricted to these amounts."}"
            Intent.CLAIMS -> "${countSummary(resultPassages, mapOf(Category.CLAIM_REQUIREMENT to "claim requirement"))} Follow the exact wording when making a claim."
            Intent.EXPLAIN -> "Kaagaz found related wording and classified it using explicit policy language. The original clause remains authoritative."
            Intent.PAYMENTS -> paymentSummary(resultPassages, pages)
            Intent.FUTURE_PREMIUM -> futurePremiumSummary(resultPassages)
            Intent.PREMIUM_REMAINING -> premiumRemainingSummary(resultPassages)
            Intent.MATURITY -> maturitySummary(resultPassages, requestedAge)
            Intent.PROJECTED_MATURITY -> projectedBenefitSummary(resultPassages)
            Intent.GUARANTEED_AND_PROJECTED -> guaranteedAndProjectedSummary(resultPassages)
            Intent.IMPORTANT_DATES -> importantDateSummary(resultPassages)
        }
        return Answer(clean, summary, AnswerKind.RESULTS, resultPassages)
    }

    /**
     * Retrieval works on whole sentences rebuilt from wrapped OCR lines (see [ClauseAssembler]). A passage's wording
     * is exactly one sentence and its evidence is exactly the lines that sentence came from; neighbouring sentences
     * are used only as classification context, never shown or highlighted as part of the passage.
     */
    private fun retrievePage(page: OcrPageResult, intent: Intent, queryTerms: Set<String>, boxOf: (OcrLineEvidence) -> OcrBox?): List<Passage> {
        val clauses = ClauseAssembler.clauses(page, boxOf)
        return clauses.mapIndexedNotNull { index, clause ->
            if (isHeading(clause, clauses.getOrNull(index + 1)) || isBareLabel(clause)) return@mapIndexedNotNull null
            val clauseText = normalize(clause.text)
            val neighbours = clauses.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(clauses.size))
            val context = normalize(neighbours.joinToString(" ") { it.text })
            val score = score(intent, clauseText, context, queryTerms)
            if (score <= 0) null else (classify(clauseText) ?: classify(context))?.let { category ->
                Passage(category, explanation(category), clause.text, clause.lines, score)
            }
        }
    }

    /** A two-word table label such as "Sum Insured" says nothing on its own. */
    private fun isBareLabel(clause: OcrClause): Boolean {
        val text = clause.text.trim()
        return text.split(Regex("\\s+")).size <= 2 && text.none(Char::isDigit) && !Regex("[.!?]$").containsMatchIn(text)
    }

    /** Section headings ("3. Guaranteed Education Benefit at Age 18", "Verified Outstanding Premium") repeat the clause below them. */
    private fun isHeading(clause: OcrClause, next: OcrClause?): Boolean {
        val text = clause.text.trim()
        if (Regex("[.!?;]$").containsMatchIn(text) || text.contains(':') || amountRegex.containsMatchIn(text)) return false
        val words = text.split(Regex("\\s+"))
        if (words.size > 8) return false
        if (Regex("^\\d{1,2}\\.\\s+\\S").containsMatchIn(text)) return true
        if (next == null || next.pageNumber != clause.pageNumber || words.size < 2 || text.any(Char::isDigit)) return false
        val nextText = next.text.trim()
        val nextIsSentence = Regex("[.!?]$").containsMatchIn(nextText) && nextText.split(Regex("\\s+")).size >= 6
        if (words.size <= 6 && (nextIsSentence || Regex("^\\d{1,2}\\.\\s+\\S").containsMatchIn(nextText))) return true
        val headingWords = normalize(text).split(' ').filter { it.length >= 3 }
        val opening = normalize(next.text).split(' ').filterNot { it in setOf("a", "an", "the") }.take(8)
        return headingWords.size >= 2 && headingWords.all { word -> opening.any { it.startsWith(word.take(5)) || word.startsWith(it.take(5)) } }
    }

    /** Two sentences from the same OCR lines and category become one passage, so one highlight never appears twice. */
    private fun mergeSameSource(passages: List<Passage>): List<Passage> = passages
        .groupBy { passage ->
            passage.evidence.takeIf { it.isNotEmpty() }
                ?.joinToString("|", prefix = "${passage.category}|") { "${it.pageNumber}:${it.boundingBox}:${normalize(it.text)}" }
                ?: "${passage.category}:${normalize(passage.wording)}"
        }
        .values.map { group ->
            if (group.size == 1) group.single()
            else group.first().copy(wording = group.map { it.wording }.distinct().joinToString(" "), score = group.maxOf { it.score })
        }

    private fun score(intent: Intent, line: String, context: String, queryTerms: Set<String>): Int {
        val intentHits = when (intent) {
            Intent.BENEFITS -> count(line, benefitTerms)
            Intent.CONDITIONS -> count(line, conditionTerms)
            Intent.EXCLUSIONS -> count(withoutExclusionReferences(line), exclusionTerms)
            Intent.WAITING -> count(line, waitingTerms)
            Intent.EXCLUSIONS_AND_WAITING -> count(withoutExclusionReferences(line), exclusionTerms + waitingTerms)
            Intent.LIMITS -> count(line, limitTerms)
            Intent.CLAIMS -> count(line, claimTerms)
            Intent.DISEASES -> count(line, diseaseTerms)
            Intent.DISEASE_QUERY, Intent.EXPLAIN -> queryTerms.count { line.contains(it) }
            Intent.PAYMENTS -> count(line, paymentTerms)
            Intent.FUTURE_PREMIUM -> count(line, scheduleTerms)
            Intent.PREMIUM_REMAINING -> count(line, outstandingTerms + scheduleTerms)
            Intent.MATURITY -> count(line, maturityTerms)
            Intent.PROJECTED_MATURITY -> count(line, projectedTerms)
            Intent.GUARANTEED_AND_PROJECTED -> count(line, maturityTerms + projectedTerms)
            Intent.IMPORTANT_DATES -> count(line, importantDateTerms)
        }
        if (intentHits == 0) return 0
        var score = intentHits * 10
        score += when (classify(context)) {
            Category.EXCLUSION -> if (intent == Intent.EXCLUSIONS || intent == Intent.EXCLUSIONS_AND_WAITING || intent == Intent.DISEASE_QUERY) 8 else 0
            Category.WAITING_PERIOD -> if (intent == Intent.WAITING || intent == Intent.EXCLUSIONS_AND_WAITING || intent == Intent.DISEASE_QUERY) 8 else 0
            Category.LIMIT -> if (intent == Intent.LIMITS) 8 else 0
            Category.CLAIM_REQUIREMENT -> if (intent == Intent.CLAIMS) 8 else 0
            Category.PAYMENT_RECORD -> if (intent == Intent.PAYMENTS) 8 else 0
            Category.PREMIUM_SCHEDULE -> if (intent == Intent.PREMIUM_REMAINING || intent == Intent.FUTURE_PREMIUM) 8 else 0
            Category.OUTSTANDING_DUE -> if (intent == Intent.PREMIUM_REMAINING) 8 else 0
            Category.MATURITY -> if (intent == Intent.MATURITY || intent == Intent.GUARANTEED_AND_PROJECTED) 8 else 0
            Category.PROJECTED_BENEFIT -> if (intent == Intent.PROJECTED_MATURITY || intent == Intent.GUARANTEED_AND_PROJECTED) 8 else 0
            Category.IMPORTANT_DATE -> if (intent == Intent.IMPORTANT_DATES) 8 else 0
            Category.BENEFIT, Category.CONDITIONAL -> if (intent == Intent.BENEFITS || intent == Intent.CONDITIONS || intent == Intent.DISEASE_QUERY) 6 else 0
            Category.MENTION_ONLY, null -> 0
        }
        if (queryTerms.any { line.contains(it) }) score += 5
        if (intent in financialIntents && amountRegex.containsMatchIn(line)) score += 12
        if (hasAny(line, negativeTerms) && intent == Intent.BENEFITS) score -= 20
        return score
    }

    /** For money questions, a sentence that states an amount outranks a general sentence that only names the topic. */
    private val financialIntents = setOf(Intent.PAYMENTS, Intent.FUTURE_PREMIUM, Intent.PREMIUM_REMAINING, Intent.MATURITY, Intent.PROJECTED_MATURITY, Intent.GUARANTEED_AND_PROJECTED)

    private fun classify(text: String): Category? = when {
        hasAny(withoutExclusionReferences(text), exclusionTerms) -> Category.EXCLUSION
        // "X is covered …, subject to this policy's waiting periods, exclusions, limits…": a conditional benefit whose
        // condition merely lists the other sections, not a waiting period or limit in itself.
        text.contains(" subject to ") && hasAny(text.substringBefore(" subject to "), listOf("covered", "payable", "reimburse")) &&
            !hasAny(text.substringBefore(" subject to "), negativeTerms + waitingTerms + limitTerms.filterNot { it == "up to" || it == "sum insured" }) -> Category.CONDITIONAL
        hasAny(text, waitingTerms) -> Category.WAITING_PERIOD
        hasAny(text, claimTerms) -> Category.CLAIM_REQUIREMENT
        // A whole benefit sentence often names premiums inside its condition ("…only if all scheduled premiums
        // have been paid"); the benefit label decides its category, not the premium wording in the condition.
        hasAny(text, projectedTerms) -> Category.PROJECTED_BENEFIT
        hasAny(text, maturityTerms) -> Category.MATURITY
        hasAny(text, outstandingTerms) -> Category.OUTSTANDING_DUE
        hasAny(text, scheduleTerms) -> Category.PREMIUM_SCHEDULE
        hasAny(text, paymentTerms) -> Category.PAYMENT_RECORD
        hasAny(text, importantDateTerms) -> Category.IMPORTANT_DATE
        hasAny(text, negativeTerms) && hasAny(text, diseaseTerms) -> Category.MENTION_ONLY
        hasAny(text, benefitTerms) && hasAny(text, conditionTerms) -> Category.CONDITIONAL
        hasAny(text, benefitTerms) && !hasAny(text, negativeTerms) -> Category.BENEFIT
        hasAny(text, limitTerms) -> Category.LIMIT
        hasAny(text, diseaseTerms) -> Category.MENTION_ONLY
        else -> null
    }

    private fun diseaseSummary(passages: List<Passage>, queryTerms: Set<String>): String {
        val supportingLines = passages.map { normalize(it.wording) }.filter { text ->
            (queryTerms.isEmpty() || queryTerms.any(text::contains)) && hasAny(text, benefitTerms) && !hasAny(text, negativeTerms + exclusionTerms)
        }
        val excluded = passages.firstOrNull { passage ->
            passage.category == Category.EXCLUSION && (queryTerms.isEmpty() || queryTerms.any { normalize(passage.wording).contains(it) })
        }
        if (supportingLines.isEmpty() && excluded != null && queryTerms.isNotEmpty()) {
            return "Excluded: the policy says “${shortSentence(excluded.wording.trim(), 150).trimEnd('.')}”."
        }
        return if (supportingLines.any { hasAny(it, conditionTerms + waitingTerms) }) {
            "The condition is conditionally covered, subject to the waiting period or requirements shown below."
        } else if (supportingLines.isNotEmpty()) {
            "The policy contains relevant benefit wording, but any conditions, exclusions, waiting periods, and limits shown below still apply."
        } else {
            "A disease or condition is mentioned, but these passages do not establish that treatment is covered. Check the complete policy or insurer."
        }
    }

    /** "For example: cosmetic treatment … is excluded and is not payable (exceptions apply)." — one sentence from the document. */
    private fun firstExample(passages: List<Passage>, category: Category): String? =
        passages.firstOrNull { it.category == category && it.wording.split(' ').size >= 4 }
            ?.let { "For example: ${shortSentence(it.wording.trim(), 140).trimEnd('.')}." }

    private val durationRegex = Regex("\\b(\\d{1,3})[\\s-]*(day|month|year)s?\\b", RegexOption.IGNORE_CASE)

    /** States the waiting time itself ("24 months") when the waiting-period sentence gives one. */
    private fun waitingSummary(passages: List<Passage>): String {
        val waiting = passages.filter { it.category == Category.WAITING_PERIOD }
        val stated = waiting.flatMap { passage ->
            durationRegex.findAll(passage.wording).map { "${it.groupValues[1]} ${it.groupValues[2].lowercase(Locale.ENGLISH)}${if (it.groupValues[1] == "1") "" else "s"}" }.toList()
                .map { it to passage }
        }.distinctBy { it.first }
        if (stated.isEmpty()) return "${countSummary(passages, mapOf(Category.WAITING_PERIOD to "waiting period"))} Cover affected by a waiting period starts only after that time."
        val pages = stated.flatMap { it.second.evidence }.map { it.pageNumber }.distinct().sorted()
        val where = if (pages.isEmpty()) "" else " (${if (pages.size == 1) "page" else "pages"} ${pages.joinToString(", ")})"
        val what = stated.take(2).joinToString(" and ") { (duration, passage) ->
            val applies = Regex("applies to ([^.;,]{3,60})", RegexOption.IGNORE_CASE).find(passage.wording)?.groupValues?.get(1)?.trim()
            duration + (applies?.let { " for $it" } ?: "")
        }
        return "The policy states a waiting period of $what$where. Cover affected by it starts only after that time."
    }

    /** "Found 1 waiting period and 2 exclusions (page 3)." Counts only passages of the named categories. */
    private fun countSummary(passages: List<Passage>, names: Map<Category, String>): String {
        val counted = passages.filter { it.category in names }
        val parts = names.mapNotNull { (category, name) ->
            val count = counted.count { it.category == category }
            if (count == 0) null else "$count ${if (count == 1) name else name.replace("limit or cap", "limits or caps") + if (name.endsWith("cap")) "" else "s"}"
        }
        if (parts.isEmpty()) return "Related wording was found, but it is not worded as ${names.values.joinToString(" or ") { "a $it" }}."
        val pages = counted.flatMap { it.evidence }.map { it.pageNumber }.distinct().sorted()
        val where = if (pages.isEmpty()) "" else " (${if (pages.size == 1) "page" else "pages"} ${pages.joinToString(", ")})"
        return "Found ${parts.joinToString(" and ")}$where."
    }

    /** A one-line statement of a single benefit, built only from values found in that benefit's own sentence. */
    data class BenefitHighlight(val text: String, val passage: Passage)

    private val benefitHighlightCategories = setOf(Category.BENEFIT, Category.CONDITIONAL, Category.MATURITY, Category.LIMIT)
    private val amountPhraseRegex = Regex("(?:(?:up to|upto|maximum of)\\s+)?(?:inr|₹|rs\\.?)\\s*[0-9](?:[0-9,]*[0-9])?(?:\\.[0-9]{1,2})?(?:\\s+per\\s+(?:claim|day|month|year|annum|policy year))?", RegexOption.IGNORE_CASE)
    private val benefitLabelRegex = Regex("^(?:the\\s+|an?\\s+)?([A-Za-z][A-Za-z0-9' -]{1,60}?\\b(?:benefit|cover|coverage|charges|expenses))\\b", RegexOption.IGNORE_CASE)
    private val ageRegex = Regex("\\bage[\\s-]*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)

    /** Benefits explicitly stated in the retrieved sentences, most relevant first, one per distinct amount/age. */
    fun benefitHighlights(passages: List<Passage>, limit: Int = 2): List<BenefitHighlight> = passages
        .filter { it.category in benefitHighlightCategories }
        .mapNotNull { passage ->
            val sentence = passage.wording.trim()
            val text = normalize(sentence)
            if (!hasAny(text, benefitTerms) || hasAny(withoutExclusionReferences(text), negativeTerms + exclusionTerms + projectedTerms)) return@mapNotNull null
            val amount = amountPhraseRegex.find(sentence)?.value
            // Without an amount, only a sentence that names what is covered is a highlight; "Benefits are payable only
            // when the policy wording supports the claim" is a general statement, not a benefit.
            if (amount == null && !hasAny(text, listOf("covered", "hospitalisation", "hospitalization", "reimburse", "indemnif"))) return@mapNotNull null
            val label = benefitLabelRegex.find(sentence)?.groupValues?.get(1)?.trim()?.takeIf { it.split(' ').size <= 6 }
            val compact = if (amount != null && label != null) buildString {
                append(label.replaceFirstChar { it.uppercase() }).append(": ").append(amount)
                ageRegex.find(sentence)?.let { append(" at age ").append(it.groupValues[1]) }
                supportedDate(sentence)?.let { append(" on ").append(it) }
                if (hasAny(text, conditionTerms) || text.contains(" provided ")) append(" (conditions apply)")
            } else shortSentence(sentence)
            val key = amount?.let { "${it.filter(Char::isDigit)}|${ageRegex.find(sentence)?.groupValues?.get(1)}" } ?: text
            key to BenefitHighlight(compact.trimEnd('.', ' ', ','), passage)
        }
        .distinctBy { it.first }
        .map { it.second }
        .take(limit)

    private fun benefitSummary(passages: List<Passage>): String {
        val highlights = benefitHighlights(passages)
        if (highlights.isEmpty()) return "Coverage could not be established from the retrieved wording. Review the source passages below."
        return highlights.joinToString(" ") { "${it.text}." }
    }

    /** Keeps a sentence whole; a very long one is cut only at a clause boundary and says that conditions follow. */
    private fun shortSentence(sentence: String, max: Int = 180): String {
        if (sentence.length <= max) return sentence
        // Prefer cutting where the condition starts, so the kept part is a whole statement and the condition is flagged.
        val conditionCut = listOf(", subject to ", " subject to ", ", only if ", " only if ", ", provided ", " provided ", ", except ", " except ")
            .map { sentence.indexOf(it) }.filter { it in 41..max }.minOrNull()
        val cut = conditionCut ?: sentence.lastIndexOf(", ", max).takeIf { it > 40 } ?: return sentence
        val rest = normalize(sentence.substring(cut))
        return sentence.substring(0, cut).trimEnd(',', ' ') + when {
            rest.contains("except") -> " (exceptions apply)"
            hasAny(rest, conditionTerms + waitingTerms) || rest.contains("provided") -> " (conditions apply)"
            else -> ""
        }
    }

    private fun explanation(category: Category) = when (category) {
        Category.BENEFIT -> "The wording explicitly describes a payable benefit or covered treatment."
        Category.CONDITIONAL -> "The wording describes coverage only when stated conditions are met."
        Category.EXCLUSION -> "The wording says this situation is excluded or not payable."
        Category.WAITING_PERIOD -> "The wording delays coverage for a stated period."
        Category.LIMIT -> "The wording places a cap, sub-limit, or other restriction on payment."
        Category.CLAIM_REQUIREMENT -> "The wording describes an action or document required for a claim."
        Category.PAYMENT_RECORD -> "The wording records a payment; only explicit recorded amounts are counted."
        Category.PREMIUM_SCHEDULE -> "The wording describes a future premium schedule, not a verified outstanding debt."
        Category.OUTSTANDING_DUE -> "The wording explicitly describes an amount as outstanding or due."
        Category.MATURITY -> "The wording states a maturity date, amount, or eligibility condition."
        Category.PROJECTED_BENEFIT -> "The wording describes an estimate and explicitly does not guarantee this amount."
        Category.IMPORTANT_DATE -> "This date is supported by the saved policy record or OCR evidence."
        Category.MENTION_ONLY -> "The condition is mentioned, but the wording does not establish coverage."
    }

    private fun identifyIntent(text: String): Intent? = when {
        hasAny(text, listOf("how much have i paid", "how much premium have i paid", "payment history", "payments made", "total paid")) -> Intent.PAYMENTS
        hasAny(text, listOf("future premium", "premium scheduled", "scheduled premium", "premium remains scheduled", "premium remaining scheduled", "remains scheduled", "remaining scheduled", "still scheduled", "premiums are scheduled")) -> Intent.FUTURE_PREMIUM
        hasAny(text, listOf("premium remains", "premium remaining", "outstanding premium", "premium due", "how much is outstanding", "amount outstanding", "what is outstanding", "is anything outstanding", "how much is due")) -> Intent.PREMIUM_REMAINING
        text.contains("guaranteed") && text.contains("projected") -> Intent.GUARANTEED_AND_PROJECTED
        hasAny(text, listOf("projected benefit", "projected maturity", "projected addition", "non guaranteed", "non-guaranteed")) -> Intent.PROJECTED_MATURITY
        hasAny(text, listOf("receive the benefit", "maturity date", "maturity amount", "maturity benefit", "policy mature", "policy matures", "payable at age", "payable at maturity", "payable on maturity", "at maturity", "on maturity", "when does it mature", "benefit payable at", "benefit is payable at", "benefit at age", "turns age", "reaches age", "child benefit", "education benefit")) -> Intent.MATURITY
        hasAny(text, listOf("important dates", "dates should i know", "premium due dates")) -> Intent.IMPORTANT_DATES
        text.startsWith("explain") || text.contains("simple english") || text.contains("what does this clause mean") -> Intent.EXPLAIN
        text.contains("what does this policy cover") -> Intent.BENEFITS
        (text.contains("does") && Regex("\\bcover\\b").containsMatchIn(text)) || Regex("\\bis .+ covered\\b").containsMatchIn(text) -> Intent.DISEASE_QUERY
        hasAny(text, listOf("disease", "diseases", "medical condition", "conditions mentioned", "illness")) -> Intent.DISEASES
        hasAny(text, listOf("exclusion", "excluded", "not covered")) && hasAny(text, listOf("waiting period", "waiting periods")) -> Intent.EXCLUSIONS_AND_WAITING
        hasAny(text, listOf("exclusion", "excluded", "not covered")) -> Intent.EXCLUSIONS
        hasAny(text, listOf("waiting period", "how long before", "wait before")) -> Intent.WAITING
        hasAny(text, listOf("sub limit", "sublimit", "limit", "cap", "maximum", "conditions apply")) -> Intent.LIMITS
        hasAny(text, listOf("claim requirement", "claim documents", "make a claim", "claim process", "claim notice")) -> Intent.CLAIMS
        hasAny(text, listOf("subject to conditions", "coverage condition", "conditional benefit")) -> Intent.CONDITIONS
        hasAny(text, listOf("benefit", "benefits", "what is covered", "coverage", "policy provide")) -> Intent.BENEFITS
        else -> null
    }

    private fun meaningfulTerms(text: String): Set<String> {
        val stop = setOf("does", "this", "policy", "cover", "covered", "coverage", "explain", "clause", "simple", "english", "what", "mean", "means", "the", "a", "an", "in", "is", "my", "treatment", "treatments", "expenses", "charges", "condition", "conditions", "disease", "diseases", "illness", "benefit", "benefits", "payable", "will", "under")
        return text.split(' ').filter { it.length >= 4 && it !in stop }.toSet()
    }

    private fun normalize(text: String) = text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9%₹]+"), " ").trim()
    private fun count(text: String, terms: List<String>) = terms.count(text::contains)
    private fun hasAny(text: String, terms: List<String>) = terms.any(text::contains)

    private val benefitTerms = listOf("covered", "coverage", "benefit", "payable", "reimburse", "indemnify", "hospitalisation", "hospitalization")
    private val conditionTerms = listOf("subject to", "provided that", "only if", "only when", "provided the", "conditional", "after completion")
    /** "This exclusion does not reduce…" refers back to an exclusion; it is not itself a new exclusion. */
    private fun withoutExclusionReferences(text: String): String {
        val stripped = text.replace(Regex("\\b(?:this|the|that|such|these|above) exclusions?\\b"), " ")
        // "…are covered…, subject to this policy's waiting periods, exclusions, limits…" lists exclusions; it is not one.
        val strong = hasAny(stripped, exclusionTerms - listOf("exclusion"))
        return if (!strong && hasAny(stripped, benefitTerms)) stripped.replace(Regex("\\bexclusions?\\b"), " ") else stripped
    }

    private val exclusionTerms = listOf("excluded", "exclusion", "not covered", "shall not pay", "not payable", "no benefit", "no premium waiver rider benefit is payable", "does not cover")
    private val waitingTerms = listOf("waiting period", "after 30 days", "after 90 days", "after 24 months", "after 36 months", "continuous coverage")
    private val limitTerms = listOf("sub limit", "sub-limit", "maximum", "up to", "capped", "limit of", "sum insured", "per day", "per claim")
    private val claimTerms = listOf("claim form", "claim requirement", "submit", "notify", "notification", "documents required", "discharge summary", "within 30 days", "within 15 days")
    private val diseaseTerms = listOf("diabetes", "hypertension", "cancer", "cardiac", "heart disease", "asthma", "cataract", "arthritis", "kidney", "pre existing", "pre-existing")
    private val negativeTerms = listOf("not covered", "excluded", "not payable", "shall not pay", "no benefit", "does not establish", "not a promise")
    private val paymentTerms = listOf("payment received", "premium paid", "paid on", "receipt number")
    private val paymentTotalTerms = listOf("premium paid total", "total premium paid", "total paid")
    private val scheduleTerms = listOf("premium schedule", "scheduled premium", "next premium", "premium due date", "future premium")
    private val outstandingTerms = listOf("outstanding premium", "amount due", "premium overdue", "balance due")
    private val maturityTerms = listOf("maturity date", "maturity amount", "maturity benefit", "payable on maturity", "child benefit", "education benefit", "payable at age", "reaches age", "turns age")
    private val projectedTerms = listOf("projected maturity", "projected benefit", "non guaranteed", "non-guaranteed", "illustration is not guaranteed", "projected value")
    private val importantDateTerms = listOf("policy start date", "policy expiry date", "premium due date", "maturity date")

    private fun importantDatePassages(fields: List<InsuranceField>): List<Passage> = fields.mapNotNull { field ->
        if (field.key !in setOf(InsuranceFieldKey.POLICY_START, InsuranceFieldKey.POLICY_EXPIRY) || field.displayedValue == null) return@mapNotNull null
        if (field.status == VerificationStatus.NOT_FOUND || (field.isEdited && !field.isUserConfirmed)) return@mapNotNull null
        Passage(Category.IMPORTANT_DATE, explanation(Category.IMPORTANT_DATE), "${field.key.label}: ${field.displayedValue}", field.evidence, 40)
    }

    private fun paymentSummary(passages: List<Passage>, pages: List<OcrPageResult>): String {
        val amountRegex = Regex("(?:inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        val paymentLines = passages.flatMap { it.evidence }.distinctBy { "${it.pageNumber}:${it.boundingBox}:${normalize(it.text)}" }
            .filter { hasAny(normalize(it.text), paymentTerms) }
        val explicitTotals = paymentLines
            .filter { hasAny(normalize(it.text), paymentTotalTerms) }
            .mapNotNull { line -> amountRegex.find(line.text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }
            .distinct()
        if (explicitTotals.size == 1) {
            val total = explicitTotals.single()
            return "The available payment records show a verified total of ₹${if (total % 1.0 == 0.0) total.toLong() else total}."
        }
        val direct = paymentLines.mapNotNull { line -> amountRegex.find(line.text)?.let { line to it } }
        val spatial = paymentLines.mapNotNull { label ->
            val labelBox = label.boundingBox ?: return@mapNotNull null
            val maxDistance = (label.sourceHeight * .025f).coerceAtLeast(12f)
            pages.firstOrNull { it.pageNumber == label.pageNumber }?.lines
                ?.asSequence()
                ?.filter { it !== label && it.boundingBox != null && amountRegex.containsMatchIn(it.text) }
                ?.filterNot { hasAny(normalize(it.text), scheduleTerms + outstandingTerms + maturityTerms + projectedTerms) }
                ?.map { candidate -> candidate to kotlin.math.abs(candidate.boundingBox!!.centerY() - labelBox.centerY()).toFloat() }
                ?.filter { it.second <= maxDistance }
                ?.minByOrNull { it.second }
                ?.first
                ?.let { amountLine -> amountRegex.find(amountLine.text)?.let { amountLine to it } }
        }
        val amounts = (direct + spatial).distinctBy { (line, match) -> "${line.pageNumber}:${line.boundingBox}:${match.value}" }
            .mapNotNull { (_, match) -> match.groupValues[1].replace(",", "").toDoubleOrNull() }
        if (amounts.isEmpty()) return "Payment records were found, but a reliable paid total could not be calculated from the OCR text."
        val total = amounts.sum()
        return "The available payment records show a verified total of ₹${if (total % 1.0 == 0.0) total.toLong() else total}."
    }

    private fun enrichPaymentPassages(passages: List<Passage>, pages: List<OcrPageResult>): List<Passage> = passages.map { passage ->
        if (passage.category != Category.PAYMENT_RECORD) return@map passage
        val labels = passage.evidence.filter { hasAny(normalize(it.text), paymentTerms) }
        val amounts = labels.mapNotNull { label -> nearestPaymentAmount(label, pages) }
        val exact = (labels + amounts).distinctBy { "${it.pageNumber}:${it.boundingBox}:${normalize(it.text)}" }
        if (exact.isEmpty()) passage else passage.copy(wording = exact.joinToString("\n") { it.text.trim() }, evidence = exact)
    }

    private fun nearestPaymentAmount(label: OcrLineEvidence, pages: List<OcrPageResult>): OcrLineEvidence? {
        val labelBox = label.boundingBox ?: return null
        val amountRegex = Regex("(?:inr|₹)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?", RegexOption.IGNORE_CASE)
        val maxDistance = (label.sourceHeight * .025f).coerceAtLeast(12f)
        return pages.firstOrNull { it.pageNumber == label.pageNumber }?.lines
            ?.asSequence()
            ?.filter { it !== label && it.boundingBox != null && amountRegex.containsMatchIn(it.text) }
            ?.filterNot { hasAny(normalize(it.text), scheduleTerms + outstandingTerms + maturityTerms + projectedTerms) }
            ?.map { candidate -> candidate to kotlin.math.abs(candidate.boundingBox!!.centerY() - labelBox.centerY()).toFloat() }
            ?.filter { it.second <= maxDistance }
            ?.minByOrNull { it.second }
            ?.first
    }

    private fun premiumRemainingSummary(passages: List<Passage>) = when {
        passages.any { it.category == Category.OUTSTANDING_DUE } -> {
            val clause = bestLabelledClause(passages.filter { it.category == Category.OUTSTANDING_DUE }, outstandingTerms)
            val amount = supportedAmount(clause)
            val due = supportedDate(clause)?.let { ", due $it" }.orEmpty()
            if (amount == null) "The document explicitly lists an outstanding premium below."
            else "The document explicitly lists an outstanding premium of $amount$due."
        }
        passages.any { it.category == Category.PREMIUM_SCHEDULE } -> "The document lists future scheduled premiums; this does not prove that an amount is currently outstanding."
        else -> "No verified outstanding premium or future premium schedule was found."
    }

    private fun futurePremiumSummary(passages: List<Passage>): String {
        val text = bestLabelledClause(passages, scheduleTerms)
        val amount = supportedAmount(text)
        val date = supportedDate(text)
        val details = listOfNotNull(amount, date?.let { "due $it" }).joinToString(" ")
        return if (details.isBlank()) {
            "The document lists a future scheduled premium below; this does not prove that it is currently outstanding."
        } else {
            "The document lists a future scheduled premium of $details; this does not prove that it is currently outstanding."
        }
    }

    private fun maturitySummary(passages: List<Passage>, requestedAge: String? = null): String {
        val labelled = labelledClauses(passages, maturityTerms, requestedAge)
        val preferred = if (requestedAge == null) labelled.filter { normalize(it).contains("maturity") }.ifEmpty { labelled } else labelled
        val text = preferred.firstOrNull { supportedAmount(it) != null }
            ?: preferred.firstOrNull { supportedDate(it) != null }
            ?: preferred.firstOrNull().orEmpty()
        val amount = supportedAmount(text)
        val age = ageRegex.find(text)?.groupValues?.get(1)
        val date = supportedDate(text)
        val details = listOfNotNull(amount, age?.let { "at age $it" }, date?.let { "on $it" }).joinToString(" ")
        return if (details.isBlank()) {
            "The document states a guaranteed or specified benefit below. Payment depends on the eligibility conditions in the source clause."
        } else {
            "The document states a guaranteed or specified benefit of $details. Payment depends on the eligibility conditions in the source clause."
        }
    }

    private fun projectedBenefitSummary(passages: List<Passage>): String {
        val amount = supportedAmount(bestLabelledClause(passages, projectedTerms))
        return if (amount == null) "The document shows a projected benefit below. It is explicitly not guaranteed."
        else "The document shows a projected benefit of $amount. It is explicitly not guaranteed."
    }

    private fun guaranteedAndProjectedSummary(passages: List<Passage>): String {
        // Each guaranteed amount is paired only with the age stated in its own sentence, listed youngest age first.
        val guaranteedItems = labelledClauses(passages.filter { it.category == Category.MATURITY }, maturityTerms)
            .mapNotNull { clause -> supportedAmount(clause)?.let { amount -> amount to ageRegex.find(clause)?.groupValues?.get(1)?.toIntOrNull() } }
            .distinctBy { (amount, age) -> "${amount.filter(Char::isDigit)}|$age" }
            .sortedBy { (_, age) -> age ?: Int.MAX_VALUE }
            .take(3)
        val projectedText = bestLabelledClause(passages.filter { it.category == Category.PROJECTED_BENEFIT }, projectedTerms)
        val projected = supportedAmount(projectedText)
        val guaranteed = guaranteedItems.joinToString(" and ") { (amount, age) -> amount + (age?.let { " at age $it" } ?: "") }
        val first = when {
            guaranteedItems.isEmpty() -> "A guaranteed amount could not be read reliably from the labelled source wording."
            guaranteedItems.size == 1 -> "The labelled guaranteed benefit is $guaranteed, subject to its stated eligibility conditions."
            else -> "The labelled guaranteed benefits are $guaranteed, each subject to its stated eligibility conditions."
        }
        val second = if (projected == null) "A separate projected amount was not found."
            else "The projected benefit is $projected and is not guaranteed."
        return "$first $second"
    }

    private val amountRegex = Regex("(?:inr|₹)\\s*[0-9](?:[0-9,]*[0-9])?(?:\\.[0-9]{1,2})?", RegexOption.IGNORE_CASE)

    private fun supportedAmount(text: String): String? = amountRegex.find(text)?.value

    private fun supportedDate(text: String): String? =
        Regex("\\b(?:0?[1-9]|[12][0-9]|3[01])(?:[ /.-]+)(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|0?[1-9]|1[0-2])(?:[ /.-]+)(?:19|20)\\d{2}\\b", RegexOption.IGNORE_CASE).find(text)?.value

    /**
     * Values are surfaced only from a sentence that itself carries the relevant label. Guaranteed (maturity) wording
     * never borrows a sentence that is worded as projected or non-guaranteed.
     */
    private fun labelledClauses(passages: List<Passage>, labels: List<String>, requestedAge: String? = null): List<String> =
        passages.map { it.wording.trim() }
            .filter { hasAny(normalize(it), labels) }
            .filter { labels === projectedTerms || !hasAny(normalize(it), projectedTerms) }
            .filter { requestedAge == null || Regex("(?:age|aged)[\\s-]*$requestedAge\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .distinct()

    /** The single most relevant labelled sentence that states an amount (or, failing that, the first labelled one). */
    private fun bestLabelledClause(passages: List<Passage>, labels: List<String>): String {
        val clauses = labelledClauses(passages, labels)
        return clauses.firstOrNull { supportedAmount(it) != null } ?: clauses.firstOrNull().orEmpty()
    }

    private fun importantDateSummary(passages: List<Passage>): String {
        val evidenceDates = passages.flatMap { it.evidence }.map { it.text.trim() }
            .filter { hasAny(normalize(it), importantDateTerms) }
        val fieldDates = passages.filter { it.category == Category.IMPORTANT_DATE && it.evidence.isEmpty() }.map { it.wording.trim() }
        val labels = (evidenceDates + fieldDates).distinct().take(4).joinToString("; ")
        return if (labels.isBlank()) "Important policy dates were not found." else "$labels. No unsupported due or maturity dates are added."
    }
}
