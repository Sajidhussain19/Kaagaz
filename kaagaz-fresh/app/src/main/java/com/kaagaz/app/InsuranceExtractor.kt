package com.kaagaz.app

import java.text.SimpleDateFormat
import java.util.Locale

/** Deterministic, conservative extraction. A value is returned only with source-line evidence. */
object InsuranceExtractor {
    private val policyNumber = Regex("(?i)\\b[A-Z0-9][A-Z0-9/-]{5,30}\\b")
    private val vehicleNumber = Regex("(?i)\\b[A-Z]{2}[ -]?\\d{1,2}[ -]?[A-Z]{1,3}[ -]?\\d{4}\\b")
    private val money = Regex("(?i)(?:₹|INR|Rs\\.?)\\s*([0-9]{1,3}(?:[, ]?[0-9]{2,3})*(?:\\.[0-9]{1,2})?)")
    private val date = Regex("(?i)\\b(?:\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{1,2}\\s+(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+\\d{2,4})\\b")
    private val excludedDateLabels = Regex("(?i)issue|payment|receipt|invoice|registration|manufactur|birth|proposal")
    private val excludedAmounts = Regex("(?i)IDV|sum insured|repair|estimate|claim|deductible|tax|GST|payment|receipt")

    /** Labels that appear in policy schedules; a line that is itself one of these is never accepted as a value. */
    private val knownLabels = Regex(
        "(?i)^\\s*(?:insurer|insurance company|company name|policy\\s*(?:number|no\\.?|#)|insured(?: person's)? name|insured person|" +
            "name of insured|policyholder(?: name)?|life insured|life assured|nominee|vehicle registration(?: number| no\\.?)?|" +
            "registration\\s+(?:number|no\\.?)|total premium|net premium|premium(?: amount| payable| frequency)?|" +
            "policy start(?: date)?|policy expiry(?: date)?|expiry(?: date)?|maturity date|statement date|date of birth|" +
            "regular due dates|premium due dates?|status|payment date|amount|receipt|sum assured|sum insured|plan name|policy term)\\s*:?\\s*$"
    )

    fun extract(pages: List<OcrPageResult>, boxOf: (OcrLineEvidence) -> OcrBox? = { it.ocrBox() }): List<InsuranceField> {
        val lines = pages.flatMap { it.lines }.filter { it.text.isNotBlank() }
        val values = ValueFinder(lines, boxOf)
        return listOf(
            labeledText(InsuranceFieldKey.INSURER, lines, values,
                Regex("(?i)\\b(?:insurer|insurance company|company name)\\b"),
                ::cleanName,
                { value -> value.contains("insurance", true) || value.length >= 4 }),
            labeledText(InsuranceFieldKey.POLICY_NUMBER, lines, values,
                Regex("(?i)\\bpolicy\\s*(?:number|no\\.?|#)\\b"),
                { source -> policyNumber.find(valuePart(source))?.value },
                { it.any(Char::isDigit) && it.any(Char::isLetter) }),
            labeledText(InsuranceFieldKey.INSURED_NAME, lines, values,
                Regex("(?i)\\b(?:insured(?: person's)? name|insured person|name of insured|policyholder(?: name)?)\\b"),
                ::cleanName,
                { it.matches(Regex("[A-Za-z][A-Za-z .'-]{2,60}")) }),
            labeledText(InsuranceFieldKey.VEHICLE_REGISTRATION, lines, values,
                Regex("(?i)\\b(?:vehicle\\s+registration(?: number| no\\.?)?|registration\\s+(?:number|no\\.?)|regn\\.?\\s*no\\.?)\\b"),
                { source -> vehicleNumber.find(source)?.value?.uppercase(Locale.ENGLISH)?.replace(Regex("\\s+"), " ") },
                { true }),
            premium(lines, values),
            policyDate(InsuranceFieldKey.POLICY_START, lines, values,
                Regex("(?i)\\b(?:policy\\s+start(?: date)?|period\\s+from|valid\\s+from|effective\\s+from|commencement(?: date)?)\\b"), false),
            policyDate(InsuranceFieldKey.POLICY_EXPIRY, lines, values,
                Regex("(?i)\\b(?:policy\\s+expiry(?: date)?|period\\s+to|valid\\s+(?:to|upto|until)|expiry(?: date)?)\\b"), true)
        )
    }

    /**
     * Finds the value line for a label-only line. Schedule tables are often read column by column, so the line after
     * a label can be the next label ("Policyholder" → "Statement date"). Preference order: the line on the same row to
     * the right of the label (when boxes exist), then the next line only if it is not itself a known label.
     */
    private class ValueFinder(private val lines: List<OcrLineEvidence>, private val boxOf: (OcrLineEvidence) -> OcrBox?) {
        fun after(index: Int): OcrLineEvidence? {
            val label = lines[index]
            val labelBox = boxOf(label)
            if (labelBox != null) {
                val sameRow = lines.asSequence()
                    .filter { it !== label && it.pageNumber == label.pageNumber }
                    .mapNotNull { candidate -> boxOf(candidate)?.let { candidate to it } }
                    .filter { (_, box) -> box.left >= labelBox.right && kotlin.math.abs(box.centerY - labelBox.centerY) <= maxOf(labelBox.height, box.height) / 2 }
                    .minByOrNull { (_, box) -> box.left - labelBox.right }
                if (sameRow != null) return sameRow.first.takeUnless { knownLabels.containsMatchIn(it.text) }
            }
            return lines.getOrNull(index + 1)?.takeUnless { knownLabels.containsMatchIn(it.text) }
        }
    }

    private fun labeledText(
        key: InsuranceFieldKey,
        lines: List<OcrLineEvidence>,
        values: ValueFinder,
        label: Regex,
        parser: (String) -> String?,
        validator: (String) -> Boolean
    ): InsuranceField {
        val candidates = mutableListOf<Candidate>()
        lines.forEachIndexed { index, line ->
            if (!isLeadingLabel(line.text, label)) return@forEachIndexed
            parser(line.text)?.takeIf(validator)?.let { candidates += Candidate(it, listOf(line)) }
                ?: values.after(index)?.let { next ->
                    parser(next.text)?.takeIf(validator)?.let { candidates += Candidate(it, listOf(line, next)) }
                }
        }
        return decide(key, candidates)
    }

    private fun premium(lines: List<OcrLineEvidence>, values: ValueFinder): InsuranceField {
        val label = Regex("(?i)\\b(?:total\\s+premium|net\\s+premium|premium\\s+(?:payable|amount)|premium)\\b")
        val candidates = mutableListOf<Candidate>()
        lines.forEachIndexed { index, line ->
            if (!isLeadingLabel(line.text, label) || excludedAmounts.containsMatchIn(line.text)) return@forEachIndexed
            val pair = listOfNotNull(line, values.after(index))
            pair.firstNotNullOfOrNull { evidence ->
                if (excludedAmounts.containsMatchIn(evidence.text)) null
                else money.find(evidence.text)?.let { normalizeMoney(it.groupValues[1]) }
            }?.let { value ->
                val used = if (money.containsMatchIn(line.text)) listOf(line) else pair
                candidates += Candidate(value, used)
            }
        }
        return decide(InsuranceFieldKey.PREMIUM, candidates)
    }

    private fun policyDate(
        key: InsuranceFieldKey,
        lines: List<OcrLineEvidence>,
        values: ValueFinder,
        label: Regex,
        chooseLast: Boolean
    ): InsuranceField {
        val candidates = mutableListOf<Candidate>()
        lines.forEachIndexed { index, line ->
            if (!isLeadingLabel(line.text, label) || excludedDateLabels.containsMatchIn(line.text)) return@forEachIndexed
            val sameLineDates = date.findAll(line.text).map { it.value }.toList()
            val raw = when {
                sameLineDates.size >= 2 -> if (chooseLast) sameLineDates.last() else sameLineDates.first()
                sameLineDates.size == 1 -> sameLineDates.first()
                else -> values.after(index)?.text?.let { date.find(it)?.value }
            }
            raw?.let {
                val evidence = if (sameLineDates.isNotEmpty()) listOf(line) else listOfNotNull(line, values.after(index))
                candidates += Candidate(normalizeDate(it) ?: it, evidence, normalizeDate(it) == null)
            }
        }
        return decide(key, candidates)
    }

    private fun decide(key: InsuranceFieldKey, candidates: List<Candidate>): InsuranceField {
        if (candidates.isEmpty()) return InsuranceField(key, null, emptyList(), VerificationStatus.NOT_FOUND)
        val distinct = candidates.distinctBy { it.value.lowercase(Locale.ENGLISH) }
        val chosen = distinct.first()
        val lowConfidence = chosen.evidence.any { it.confidence in 0f..0.64f }
        val ambiguous = distinct.size > 1
        val status = if (ambiguous || lowConfidence || chosen.parserUncertain) VerificationStatus.NEEDS_REVIEW else VerificationStatus.SUPPORTED
        val reason = when {
            ambiguous -> "More than one plausible value was found."
            lowConfidence -> "The supporting OCR line has low confidence."
            chosen.parserUncertain -> "The source value could not be normalized confidently."
            else -> null
        }
        return InsuranceField(key, chosen.value, distinct.flatMap { it.evidence }.distinct(), status, reason)
    }

    private fun cleanName(source: String): String? {
        val withoutLabel = source.replaceFirst(
            Regex("(?i)^\\s*(?:insurer|insurance company|company name|insured(?: person's)? name|insured person|name of insured|policyholder(?: name)?)\\s*(?::|[-–])?\\s*"),
            ""
        )
        val value = withoutLabel.replace(Regex("(?i)^(?:name\\s*)"), "").trim()
        return value.takeIf { it.isNotBlank() }
    }

    private fun isLeadingLabel(source: String, label: Regex): Boolean {
        val match = label.find(source) ?: return false
        return source.substring(0, match.range.first).none { it.isLetterOrDigit() }
    }

    private fun valuePart(source: String): String {
        if (':' in source) return source.substringAfter(':').trim()
        return source.replaceFirst(Regex("^.*?\\s[-–]\\s"), "").trim()
    }

    private fun normalizeMoney(raw: String): String? {
        val numeric = raw.replace(",", "").replace(" ", "")
        val parts = numeric.split('.')
        val whole = parts[0].toLongOrNull() ?: return null
        val digits = whole.toString()
        val grouped = if (digits.length <= 3) digits else {
            val tail = digits.takeLast(3)
            val head = digits.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
        }
        return "₹$grouped" + if (parts.size == 2) ".${parts[1]}" else ""
    }

    private fun normalizeDate(raw: String): String? {
        val inputs = listOf("d/M/yyyy", "d-M-yyyy", "d.M.yyyy", "d MMM yyyy", "d MMMM yyyy", "d/M/yy", "d-M-yy")
        return inputs.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }.parse(raw)?.let {
                    SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(it)
                }
            }.getOrNull()
        }
    }

    private data class Candidate(
        val value: String,
        val evidence: List<OcrLineEvidence>,
        val parserUncertain: Boolean = false
    )
}
