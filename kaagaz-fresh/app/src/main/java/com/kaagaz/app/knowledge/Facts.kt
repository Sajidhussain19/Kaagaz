package com.kaagaz.app.knowledge

import com.kaagaz.app.core.InsuranceField
import com.kaagaz.app.core.InsuranceFieldKey
import com.kaagaz.app.core.OcrBox
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.core.VerificationStatus
import com.kaagaz.app.core.ocrBox
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** PERSON facts describe the user and are compared across documents; DOCUMENT facts belong to one paper only. */
enum class FactScope { PERSON, DOCUMENT }

enum class FactType(val label: String, val scope: FactScope, val profileGroup: String? = null) {
    FULL_NAME("Full name", FactScope.PERSON, "Contact"),
    DATE_OF_BIRTH("Date of birth", FactScope.PERSON, "Contact"),
    EMAIL("Email", FactScope.PERSON, "Contact"),
    PHONE("Phone", FactScope.PERSON, "Contact"),
    ADDRESS("Address", FactScope.PERSON, "Contact"),
    DEGREE("Degree", FactScope.PERSON, "Education"),
    INSTITUTION("Institution", FactScope.PERSON, "Education"),
    GRADUATION_YEAR("Graduation year", FactScope.PERSON, "Education"),
    CGPA("CGPA / score", FactScope.PERSON, "Education"),
    EMPLOYER("Current employer", FactScope.PERSON, "Work"),
    JOB_TITLE("Job title", FactScope.PERSON, "Work"),
    SKILLS("Skills", FactScope.PERSON, "Work"),
    INSURED_PERSON("Insured person", FactScope.DOCUMENT),
    ACCOUNT_HOLDER("Account holder", FactScope.DOCUMENT),
    POLICY_NUMBER("Policy number", FactScope.DOCUMENT),
    PREMIUM("Premium", FactScope.DOCUMENT),
    BILLER("Biller", FactScope.DOCUMENT),
    BILL_NUMBER("Bill number", FactScope.DOCUMENT),
    BILL_DATE("Bill date", FactScope.DOCUMENT),
    BILL_AMOUNT("Amount due", FactScope.DOCUMENT),
    DUE_DATE("Due date", FactScope.DOCUMENT),
    POLICY_EXPIRY("Policy expiry", FactScope.DOCUMENT)
}

enum class ExtractionMethod(val label: String) {
    LABEL_RULE("Label rule on OCR text"),
    PATTERN_RULE("Pattern rule on OCR text"),
    INSURANCE_EXTRACTOR("Insurance field extractor"),
    USER_CORRECTION("Corrected by you")
}

enum class DocKind(val title: String) {
    RESUME("Résumé / CV"),
    EDUCATION_CERTIFICATE("Education certificate"),
    TRANSCRIPT("Mark sheet / transcript"),
    BILL("Bill / invoice"),
    PAYMENT_STATEMENT("Payment statement"),
    INSURANCE_POLICY("Insurance policy"),
    OTHER("General paperwork")
}

/** One extracted claim with the exact line it came from. [confidence] is the OCR confidence of the source line. */
data class Fact(
    val documentId: String,
    val documentName: String,
    val type: FactType,
    val rawValue: String,
    val normalized: String,
    val page: Int,
    val evidence: String,
    val confidence: Float,
    val method: ExtractionMethod,
    val extractedAt: Long = 0L,
    val userConfirmed: Boolean = false
) {
    val key: String get() = "$documentId|${type.name}|$normalized"
}

object Normalizer {
    private val titles = Regex("^(mr|mrs|ms|miss|dr|shri|smt|kumari)\\.?\\s+", RegexOption.IGNORE_CASE)
    private val dateFormats: List<DateTimeFormatter> = listOf("d MMMM uuuu", "d MMM uuuu", "d-M-uuuu", "d/M/uuuu", "d.M.uuuu", "uuuu-M-d", "MMMM d, uuuu", "MMM d, uuuu", "d-MMM-uuuu")
        .map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }

    fun name(raw: String): String = raw.trim().replace(titles, "").lowercase(Locale.ENGLISH)
        .replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun date(raw: String): String? {
        val cleaned = raw.trim().replace(Regex("(\\d)(st|nd|rd|th)\\b"), "$1").replace(Regex("\\s+"), " ")
        return dateFormats.firstNotNullOfOrNull { runCatching { LocalDate.parse(cleaned, it) }.getOrNull() }?.toString()
    }

    /** Exact decimal amount as a plain string ("12450.00" → "12450"); never rounds. */
    fun money(raw: String): String? {
        val digits = Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?").find(raw)?.value?.replace(",", "") ?: return null
        return runCatching { BigDecimal(digits).stripTrailingZeros().toPlainString() }.getOrNull()
    }

    fun phone(raw: String): String? = raw.filter(Char::isDigit).takeLast(10).takeIf { it.length == 10 }

    fun text(raw: String): String = raw.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]+"), " ").trim()

    fun forType(type: FactType, raw: String): String? = when (type) {
        FactType.FULL_NAME, FactType.INSURED_PERSON, FactType.ACCOUNT_HOLDER -> name(raw).takeIf { it.split(' ').size in 1..6 && it.length >= 3 }
        FactType.DATE_OF_BIRTH, FactType.BILL_DATE, FactType.DUE_DATE, FactType.POLICY_EXPIRY -> date(raw)
        FactType.EMAIL -> raw.trim().replace(Regex("\\s*@\\s*"), "@").lowercase(Locale.ENGLISH).takeIf { Regex("^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$").matches(it) }
        FactType.PHONE -> phone(raw)
        FactType.GRADUATION_YEAR -> Regex("\\b(19|20)\\d{2}\\b").find(raw)?.value
        FactType.CGPA -> Regex("\\d{1,3}(?:\\.\\d{1,2})?").find(raw)?.value
        FactType.PREMIUM, FactType.BILL_AMOUNT -> money(raw)
        FactType.POLICY_NUMBER, FactType.BILL_NUMBER -> raw.trim().uppercase(Locale.ENGLISH).replace(Regex("\\s+"), "").takeIf { it.length >= 3 && it.any(Char::isDigit) }
        else -> text(raw).takeIf { it.length >= 2 }
    }

    /** Readable value for display: dates as "12 Mar 2003", money as "₹12,450". */
    fun display(type: FactType, normalized: String, raw: String): String = when (type) {
        FactType.DATE_OF_BIRTH, FactType.BILL_DATE, FactType.DUE_DATE, FactType.POLICY_EXPIRY ->
            runCatching { LocalDate.parse(normalized).format(DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)) }.getOrDefault(raw)
        FactType.PREMIUM, FactType.BILL_AMOUNT -> "₹" + indianGrouping(normalized)
        FactType.EMAIL -> normalized
        else -> raw.trim()
    }

    fun indianGrouping(plain: String): String {
        val whole = plain.substringBefore('.'); val fraction = plain.substringAfter('.', "")
        val grouped = if (whole.length <= 3) whole else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        return grouped + if (fraction.isNotEmpty()) ".$fraction" else ""
    }
}

/**
 * Label-anchored extraction for everyday paperwork (résumés, certificates, bills). A value is produced only when an
 * explicit label or an unambiguous pattern (email) is present on the page; nothing is inferred from context.
 * Geometry is used when available, because ML Kit reads two-column tables column by column.
 */
object FactExtractor {
    private data class Rule(val type: FactType, val label: Regex)

    private fun lbl(vararg alternatives: String) = Regex("(?i)^\\s*(?:${alternatives.joinToString("|")})\\s*(?::|-|–)?\\s*")

    private val rules = listOf(
        Rule(FactType.FULL_NAME, lbl("full name", "name of (?:the )?(?:candidate|student|applicant)", "candidate(?:'s)? name", "student(?:'s)? name", "applicant(?:'s)? name", "name")),
        Rule(FactType.ACCOUNT_HOLDER, lbl("customer name", "account holder(?: name)?", "consumer name")),
        Rule(FactType.DATE_OF_BIRTH, lbl("date of birth", "d\\.?o\\.?b\\.?", "birth date")),
        Rule(FactType.EMAIL, lbl("e-?mail(?: address)?")),
        Rule(FactType.PHONE, lbl("phone(?: number)?", "mobile(?: number| no\\.?)?", "contact(?: number| no\\.?)?")),
        Rule(FactType.ADDRESS, lbl("address", "residential address")),
        Rule(FactType.DEGREE, lbl("degree(?: awarded)?", "programme", "program", "qualification", "course")),
        Rule(FactType.INSTITUTION, lbl("university", "institution", "college", "institute")),
        Rule(FactType.GRADUATION_YEAR, lbl("year of graduation", "graduation year", "year of passing", "passing year", "graduated(?: in)?", "class of")),
        Rule(FactType.CGPA, lbl("cgpa", "gpa", "final cgpa", "aggregate(?: percentage)?", "percentage")),
        Rule(FactType.EMPLOYER, lbl("current employer", "employer", "company")),
        Rule(FactType.JOB_TITLE, lbl("designation", "job title", "current role", "role")),
        Rule(FactType.SKILLS, lbl("skills", "key skills", "technical skills")),
        Rule(FactType.BILLER, lbl("biller", "service provider", "provider")),
        Rule(FactType.BILL_NUMBER, lbl("bill (?:number|no\\.?)", "invoice (?:number|no\\.?)")),
        Rule(FactType.BILL_DATE, lbl("bill date", "invoice date", "date of bill")),
        Rule(FactType.BILL_AMOUNT, lbl("total amount due", "amount due", "bill amount", "amount payable", "total due", "net payable")),
        Rule(FactType.DUE_DATE, lbl("due date", "pay by", "last date(?: of payment)?", "payment due(?: date)?"))
    )

    private val anyLabel = Regex("(?i)^\\s*(?:" + listOf(
        "full name", "name", "date of birth", "dob", "e-?mail", "phone", "mobile", "contact", "address", "degree", "programme", "program",
        "qualification", "course", "university", "institution", "college", "institute", "year of graduation", "graduation year",
        "year of passing", "cgpa", "gpa", "percentage", "employer", "company", "designation", "job title", "role", "skills",
        "biller", "service provider", "provider", "bill (?:number|no)", "invoice", "bill date", "amount due", "total amount due",
        "bill amount", "amount payable", "due date", "pay by", "consumer number", "account number", "billing period", "status",
        "customer name", "account holder", "consumer name"
    ).joinToString("|") + ")\\b[^:]{0,20}:?\\s*$")

    // ML Kit sometimes inserts a space next to "@" ("aarav.mehta @example.com").
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+ ?@ ?[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** A "Skills:" line below a "Skills" heading is its own labelled fact, not the heading's value. */
    private fun isLabelledLine(text: String): Boolean = text.contains(':') && rules.any { it.label.containsMatchIn(text.substringBefore(':') + ":") }

    fun extract(
        documentId: String,
        documentName: String,
        pages: List<OcrPageResult>,
        insuranceFields: List<InsuranceField> = emptyList(),
        extractedAt: Long = 0L,
        boxOf: (OcrLineEvidence) -> OcrBox? = { it.ocrBox() }
    ): List<Fact> {
        val facts = mutableListOf<Fact>()
        pages.forEach { page ->
            val lines = page.lines.filter { it.text.isNotBlank() }
            lines.forEachIndexed { index, line ->
                for (rule in rules) {
                    val match = rule.label.find(line.text) ?: continue
                    // "Name" must be the whole label, not the start of "Name of policy" etc.
                    if (rule.type == FactType.FULL_NAME && Regex("(?i)^\\s*name\\s+of\\s+(?!the candidate|candidate|student|applicant)").containsMatchIn(line.text)) continue
                    val inline = line.text.substring(match.range.last + 1).trim()
                    val hasSeparator = match.value.contains(Regex("[:–-]"))
                    val (rawValue, source) = when {
                        inline.isNotBlank() && hasSeparator -> inline to line
                        // "DEGREE CERTIFICATE" is a title, not "Degree: Certificate": no separator means no inline value.
                        inline.isNotBlank() -> continue
                        else -> valueFor(lines, index, boxOf)?.takeUnless { isLabelledLine(it.text) }?.let { it.text.trim() to it } ?: continue
                    }
                    val normalized = Normalizer.forType(rule.type, rawValue) ?: continue
                    facts += Fact(documentId, documentName, rule.type, rawValue, normalized, page.pageNumber,
                        if (source === line) line.text else "${line.text} → ${source.text}", minOf(line.confidence, source.confidence), ExtractionMethod.LABEL_RULE, extractedAt)
                    break
                }
            }
            lines.forEach { line ->
                emailPattern.findAll(line.text).forEach { m ->
                    Normalizer.forType(FactType.EMAIL, m.value)?.let { facts += Fact(documentId, documentName, FactType.EMAIL, m.value, it, page.pageNumber, line.text, line.confidence, ExtractionMethod.PATTERN_RULE, extractedAt) }
                }
            }
            certificatePhrases(documentId, documentName, page, extractedAt).let(facts::addAll)
        }
        // Only supported or user-confirmed insurance values become facts; NEEDS_REVIEW pairs are often label/label mix-ups.
        insuranceFields.filter { it.displayedValue != null && (it.status == VerificationStatus.SUPPORTED || it.isUserConfirmed) }.forEach { field ->
            val type = when (field.key) {
                InsuranceFieldKey.POLICY_NUMBER -> FactType.POLICY_NUMBER
                InsuranceFieldKey.PREMIUM -> FactType.PREMIUM
                InsuranceFieldKey.POLICY_EXPIRY -> FactType.POLICY_EXPIRY
                InsuranceFieldKey.INSURED_NAME -> FactType.INSURED_PERSON
                else -> null
            } ?: return@forEach
            val raw = field.displayedValue!!
            val normalized = Normalizer.forType(type, raw) ?: return@forEach
            val evidence = field.evidence.firstOrNull()
            facts += Fact(documentId, documentName, type, raw, normalized, evidence?.pageNumber ?: 1,
                field.evidence.joinToString(" → ") { it.text }.ifBlank { raw }, evidence?.confidence ?: 1f,
                if (field.isEdited) ExtractionMethod.USER_CORRECTION else ExtractionMethod.INSURANCE_EXTRACTOR, extractedAt, field.isUserConfirmed)
        }
        return facts.distinctBy { it.key }
    }

    /** "This is to certify that Aarav Mehta has been awarded the degree of Bachelor of Technology … in the year 2023". */
    private fun certificatePhrases(documentId: String, documentName: String, page: OcrPageResult, at: Long): List<Fact> {
        val text = page.lines.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")
        val out = mutableListOf<Fact>()
        fun add(type: FactType, raw: String, sentence: String) {
            val normalized = Normalizer.forType(type, raw) ?: return
            val line = page.lines.firstOrNull { raw.split(' ').first() in it.text } ?: page.lines.firstOrNull()
            out += Fact(documentId, documentName, type, raw.trim(), normalized, page.pageNumber, sentence.trim(), line?.confidence ?: 1f, ExtractionMethod.PATTERN_RULE, at)
        }
        // Names are matched case-sensitively (capitalised words), so "has been awarded" is never part of the name.
        Regex("(?i:certify that)\\s+(?:Mr\\.?|Ms\\.?|Mrs\\.?|Shri|Smt\\.?)?\\s*([A-Z][a-zA-Z.]+(?:\\s+[A-Z][a-zA-Z.]+){1,4})").find(text)?.let { add(FactType.FULL_NAME, it.groupValues[1], it.value) }
        Regex("(?i)degree of\\s+((?:Bachelor|Master|Doctor)[^.,;]{3,60}?)(?=\\s+(?:in the year|with|from|on|has|at)\\b|[.,;])").find(text)?.let { add(FactType.DEGREE, it.groupValues[1], it.value) }
        Regex("(?i)in the year\\s+((?:19|20)\\d{2})").find(text)?.let { add(FactType.GRADUATION_YEAR, it.groupValues[1], it.value) }
        return out
    }

    private fun valueFor(lines: List<OcrLineEvidence>, index: Int, boxOf: (OcrLineEvidence) -> OcrBox?): OcrLineEvidence? {
        val label = lines[index]
        val labelBox = boxOf(label)
        if (labelBox != null) {
            val sameRow = lines.asSequence().filter { it !== label }
                .mapNotNull { c -> boxOf(c)?.let { c to it } }
                .filter { (_, b) -> b.left >= labelBox.right && kotlin.math.abs(b.centerY - labelBox.centerY) <= maxOf(labelBox.height, b.height) / 2 }
                .minByOrNull { (_, b) -> b.left - labelBox.right }
            if (sameRow != null) return sameRow.first.takeUnless { anyLabel.containsMatchIn(it.text) }
        }
        // Column-by-column table without geometry: N labels followed by N values pair up by position.
        var start = index; while (start > 0 && anyLabel.containsMatchIn(lines[start - 1].text)) start--
        var end = index; while (end + 1 < lines.size && anyLabel.containsMatchIn(lines[end + 1].text)) end++
        val labelsInRun = end - start + 1
        val candidate = lines.getOrNull(end + 1 + (index - start))
        if (labelsInRun > 1) {
            val values = lines.drop(end + 1).take(labelsInRun)
            return if (values.size == labelsInRun && values.none { anyLabel.containsMatchIn(it.text) }) candidate else null
        }
        return lines.getOrNull(index + 1)?.takeUnless { anyLabel.containsMatchIn(it.text) }
    }

    private val kindSignals = mapOf(
        DocKind.RESUME to listOf("resume", "curriculum vitae", "work experience", "professional experience", "skills", "objective", "projects"),
        DocKind.EDUCATION_CERTIFICATE to listOf("certify that", "degree of", "awarded", "convocation", "provisional certificate", "degree certificate"),
        DocKind.TRANSCRIPT to listOf("mark sheet", "marksheet", "transcript", "grade card", "semester", "cgpa", "sgpa"),
        DocKind.BILL to listOf("bill number", "bill no", "invoice", "amount due", "due date", "billing period", "consumer number", "units consumed"),
        DocKind.PAYMENT_STATEMENT to listOf("payment statement", "payment history", "premium paid", "receipt"),
        DocKind.INSURANCE_POLICY to listOf("policy number", "sum insured", "sum assured", "insurer", "premium", "waiting period", "exclusion", "maturity")
    )

    /** Classifies from explicit words on the page; the highest score wins, ties fall back to OTHER. */
    fun classify(pages: List<OcrPageResult>): DocKind {
        val text = Normalizer.text(pages.joinToString(" ") { p -> p.lines.joinToString(" ") { it.text }.ifBlank { p.text } })
        if (text.isBlank()) return DocKind.OTHER
        val scores = kindSignals.mapValues { (_, terms) -> terms.count { text.contains(Normalizer.text(it)) } }
        val best = scores.maxByOrNull { it.value } ?: return DocKind.OTHER
        if (best.value < 2 || scores.count { it.value == best.value } > 1) return DocKind.OTHER
        return best.key
    }
}
