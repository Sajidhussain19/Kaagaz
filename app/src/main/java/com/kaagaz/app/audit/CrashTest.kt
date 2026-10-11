package com.kaagaz.app.audit

import com.kaagaz.app.core.InsuranceField
import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.forms.FormMapper
import com.kaagaz.app.forms.FormSpec
import com.kaagaz.app.insurance.CoverageIntelligenceEngine
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.Fact
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.LedgerEntry
import com.kaagaz.app.knowledge.Normalizer
import com.kaagaz.app.knowledge.TrustLedger
import com.kaagaz.app.knowledge.TrustState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Severity(val label: String) { HIGH("High"), MEDIUM("Medium"), LOW("Low"), REVIEW("Review flag") }

enum class FindingKind(val label: String) {
    CONFLICT("Conflicting records"),
    NAME_VARIANT("Name variant"),
    LOW_CONFIDENCE("Unverified value"),
    MISSING_FIELD("Missing required fact"),
    MISSING_DOCUMENT("Missing supporting document"),
    UNSUPPORTED_VALUE("Unsupported value"),
    DUPLICATE_BILL("Possible duplicate bill"),
    OUTSTANDING_PAYMENT("Outstanding payment"),
    DUE_DATE("Payment deadline"),
    SENSITIVE_REQUEST("Sensitive data request"),
    UNANSWERABLE_INSURANCE("Not established by policy text")
}

data class EvidenceRef(val documentId: String, val documentName: String, val page: Int, val text: String)

data class Finding(
    val id: String,
    val kind: FindingKind,
    val severity: Severity,
    val title: String,
    val explanation: String,
    val evidence: List<EvidenceRef>,
    val nextAction: String,
    val factType: FactType? = null
)

data class AuditDocument(val id: String, val name: String, val kind: DocKind, val pages: List<OcrPageResult>, val insuranceFields: List<InsuranceField> = emptyList())

/**
 * The Paperwork Crash Test. Every finding is computed from the supplied documents and form; nothing is scripted.
 * Arithmetic, dates and amounts are compared deterministically. Sensitive-data findings are review flags only.
 */
object CrashTest {
    fun run(
        documents: List<AuditDocument>,
        facts: List<Fact>,
        ledger: List<LedgerEntry>,
        form: FormSpec? = null,
        draft: Map<String, String> = emptyMap(),
        today: LocalDate = LocalDate.now()
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        val ids = documents.map { it.id }.toSet()
        val scopedFacts = facts.filter { it.documentId in ids }

        ledger.forEach { entry ->
            when (entry.state) {
                TrustState.CONFLICTING -> findings += Finding(
                    "conflict:${entry.type}", FindingKind.CONFLICT, Severity.HIGH,
                    "${entry.type.label} conflicts across documents",
                    entry.groups.joinToString(" vs ") { "${it.display} (${it.documents.joinToString()})" } + ".",
                    entry.groups.flatMap { g -> g.facts.map { it.ref() } },
                    "Check the original papers, then choose the correct value in the Trust Ledger. Consider getting the wrong document corrected.",
                    entry.type)
                TrustState.NEEDS_CONFIRMATION -> findings += Finding(
                    "confirm:${entry.type}", if (entry.groups.size > 1) FindingKind.NAME_VARIANT else FindingKind.LOW_CONFIDENCE, Severity.MEDIUM,
                    "${entry.type.label} needs confirmation", entry.reason + " " + entry.groups.joinToString(" / ") { it.display },
                    entry.groups.flatMap { g -> g.facts.map { it.ref() } },
                    "Confirm the exact spelling you want used on forms.", entry.type)
                else -> Unit
            }
        }

        if (form != null) {
            val proposals = FormMapper.map(form, ledger)
            proposals.filter { it.field.required && it.state == TrustState.MISSING }.forEach { p ->
                findings += Finding("missing:${form.id}:${p.field.id}", FindingKind.MISSING_FIELD, Severity.HIGH,
                    "${p.field.label} is required but not found", p.note, emptyList(),
                    "Add a document that states it, or enter it yourself (it will be marked as unverified).", p.field.type)
            }
            val kinds = documents.map { it.kind }.toSet()
            (form.requiredDocuments - kinds).forEach { kind ->
                findings += Finding("missingdoc:${form.id}:$kind", FindingKind.MISSING_DOCUMENT, Severity.MEDIUM,
                    "No ${kind.title.lowercase()} in this set", "${form.title} normally needs a ${kind.title.lowercase()} as supporting evidence.",
                    emptyList(), "Scan or import the ${kind.title.lowercase()}.")
            }
            form.fields.filter { it.sensitiveReason != null }.forEach { field ->
                findings += Finding("sensitive:${form.id}:${field.id}", FindingKind.SENSITIVE_REQUEST, Severity.REVIEW,
                    "Review: “${field.label}”", field.sensitiveReason!!, emptyList(),
                    "Kaagaz will not fill this. Confirm with the organisation through an official channel before sharing.")
            }
            draft.forEach { (fieldId, value) ->
                val field = form.fields.firstOrNull { it.id == fieldId } ?: return@forEach
                val type = field.type ?: return@forEach
                if (value.isBlank()) return@forEach
                val normalized = Normalizer.forType(type, value)
                val supporting = scopedFacts.filter { it.type == type && (it.normalized == normalized || TrustLedger.group(type, listOf(it)).first().normalized == normalized) }
                if (supporting.isEmpty()) findings += Finding("unsupported:${form.id}:$fieldId", FindingKind.UNSUPPORTED_VALUE, Severity.HIGH,
                    "“$value” for ${field.label} has no supporting document",
                    "None of the selected documents states this value." + (ledger.firstOrNull { it.type == type }?.groups?.firstOrNull()?.let { " Documents say ${it.display}." } ?: ""),
                    scopedFacts.filter { it.type == type }.map { it.ref() },
                    "Use a document-backed value or attach evidence for this one.", type)
            }
        }

        duplicateBills(scopedFacts).let(findings::addAll)
        deadlines(scopedFacts, today).let(findings::addAll)

        documents.filter { it.kind == DocKind.PAYMENT_STATEMENT || it.kind == DocKind.INSURANCE_POLICY }.forEach { doc ->
            val outstanding = CoverageIntelligenceEngine.answer("What is the outstanding premium?", doc.pages, doc.insuranceFields)
            if (outstanding.kind == CoverageIntelligenceEngine.AnswerKind.RESULTS && outstanding.passages.isNotEmpty()) {
                findings += Finding("outstanding:${doc.id}", FindingKind.OUTSTANDING_PAYMENT, Severity.MEDIUM,
                    "Outstanding premium in ${doc.name}", outstanding.summary,
                    outstanding.passages.take(2).map { p -> EvidenceRef(doc.id, doc.name, p.evidence.firstOrNull()?.pageNumber ?: 1, p.wording) },
                    "Pay or confirm the outstanding premium before the grace period ends.")
            }
        }
        // One finding per topic (not per policy), listing every policy whose text cannot establish it.
        val policies = documents.filter { it.kind == DocKind.INSURANCE_POLICY }
        listOf("What are the claim requirements?" to "claim requirements", "What exclusions are listed?" to "exclusions").forEach { (question, what) ->
            val silent = policies.filter { CoverageIntelligenceEngine.answer(question, it.pages, it.insuranceFields).kind == CoverageIntelligenceEngine.AnswerKind.NOT_FOUND }
            if (silent.isNotEmpty()) findings += Finding("unanswerable:$what:${silent.map { it.id }.sorted().hashCode()}",
                FindingKind.UNANSWERABLE_INSURANCE, Severity.LOW,
                "${what.replaceFirstChar { it.uppercase() }} not stated in ${silent.size} ${if (silent.size == 1) "policy" else "policies"}",
                "Kaagaz cannot answer questions about $what for: ${silent.joinToString { it.name }}.", emptyList(),
                "Ask the insurer for the complete policy wording.")
        }
        val order = Severity.values()
        return findings.distinctBy { it.id }.sortedWith(compareBy({ order.indexOf(it.severity) }, { it.title }))
    }

    private fun duplicateBills(facts: List<Fact>): List<Finding> {
        val out = mutableListOf<Finding>()
        facts.filter { it.type == FactType.BILL_NUMBER }.groupBy { it.normalized }.filter { (_, f) -> f.map { it.documentId }.distinct().size > 1 }
            .forEach { (number, list) ->
                out += Finding("dup:number:$number", FindingKind.DUPLICATE_BILL, Severity.MEDIUM, "Bill $number appears in ${list.map { it.documentId }.distinct().size} documents",
                    "The same bill number is recorded more than once; paying both copies would pay twice.", list.map { it.ref() },
                    "Check whether this is the same bill before paying again.")
            }
        val amountByDoc = facts.filter { it.type == FactType.BILL_AMOUNT }.associateBy { it.documentId }
        val dateByDoc = facts.filter { it.type == FactType.BILL_DATE }.associateBy { it.documentId }
        amountByDoc.keys.filter { it in dateByDoc }.groupBy { amountByDoc[it]!!.normalized + "|" + dateByDoc[it]!!.normalized }
            .filter { it.value.size > 1 }.forEach { (key, docs) ->
                val numbers = facts.filter { it.type == FactType.BILL_NUMBER && it.documentId in docs }.map { it.normalized }.toSet()
                if (numbers.size == 1) return@forEach // already reported by bill number
                out += Finding("dup:amount:$key", FindingKind.DUPLICATE_BILL, Severity.MEDIUM, "Two bills with the same amount and date",
                    "${Normalizer.display(FactType.BILL_AMOUNT, amountByDoc[docs.first()]!!.normalized, "")} dated ${Normalizer.display(FactType.BILL_DATE, dateByDoc[docs.first()]!!.normalized, "")} appears in ${docs.size} documents.",
                    docs.flatMap { listOfNotNull(amountByDoc[it]?.ref(), dateByDoc[it]?.ref()) }, "Check whether one is a duplicate copy.")
            }
        return out
    }

    private fun deadlines(facts: List<Fact>, today: LocalDate): List<Finding> = facts.filter { it.type == FactType.DUE_DATE }.mapNotNull { due ->
        val date = runCatching { LocalDate.parse(due.normalized) }.getOrNull() ?: return@mapNotNull null
        val days = ChronoUnit.DAYS.between(today, date)
        val amount = facts.firstOrNull { it.type == FactType.BILL_AMOUNT && it.documentId == due.documentId }
        val what = amount?.let { Normalizer.display(FactType.BILL_AMOUNT, it.normalized, it.rawValue) } ?: "A payment"
        when {
            days < 0 -> Finding("due:${due.documentId}", FindingKind.DUE_DATE, Severity.MEDIUM, "$what was due ${-days} days ago",
                "${due.documentName} states a due date of ${Normalizer.display(FactType.DUE_DATE, due.normalized, due.rawValue)}. Kaagaz has no record that it was paid.",
                listOfNotNull(due.ref(), amount?.ref()), "Check whether it was paid; late fees may apply.")
            days <= 14 -> Finding("due:${due.documentId}", FindingKind.DUE_DATE, Severity.LOW, "$what due in $days days",
                "${due.documentName} is due on ${Normalizer.display(FactType.DUE_DATE, due.normalized, due.rawValue)}.",
                listOfNotNull(due.ref(), amount?.ref()), "Set a reminder or pay before the due date.")
            else -> null
        }
    }

    private fun Fact.ref() = EvidenceRef(documentId, documentName, page, evidence)
}
