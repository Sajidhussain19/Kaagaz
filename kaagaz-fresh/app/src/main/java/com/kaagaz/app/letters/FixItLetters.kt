package com.kaagaz.app.letters

import com.kaagaz.app.audit.Finding
import com.kaagaz.app.audit.FindingKind
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.LedgerEntry
import com.kaagaz.app.knowledge.TrustState
import java.util.Locale

/**
 * Fix-it Letters: the email nobody wants to write. Drafts a correction or dispute request that quotes the exact
 * evidence (document, page, line) so the recipient can act on it. Deterministic templates; nothing is sent by Kaagaz.
 */
object FixItLetters {
    data class Letter(val title: String, val recipient: String, val subject: String, val body: String)

    fun kindName(kind: DocKind?) = when (kind) {
        DocKind.RESUME -> "résumé"; DocKind.EDUCATION_CERTIFICATE -> "degree certificate"; DocKind.TRANSCRIPT -> "transcript"
        DocKind.BILL -> "bill"; DocKind.PAYMENT_STATEMENT -> "payment statement"; DocKind.INSURANCE_POLICY -> "policy"; else -> "document"
    }

    /** For a conflicting record: ask the issuer of the official document(s) to confirm and correct. */
    fun correction(entry: LedgerEntry, kinds: Map<String, DocKind>, applicantName: String?): Letter? {
        if (entry.state != TrustState.CONFLICTING || entry.groups.size < 2) return null
        val field = entry.type.label.lowercase(Locale.ENGLISH)
        val official = entry.groups.flatMap { it.facts }.firstOrNull { kinds[it.documentId] in setOf(DocKind.EDUCATION_CERTIFICATE, DocKind.TRANSCRIPT) }
        val recipient = if (official != null) "Registrar / Examinations office" else "Records office"
        val lines = entry.groups.joinToString("\n") { g ->
            val f = g.facts.first()
            "• My ${kindName(kinds[f.documentId])} (${f.documentName}, page ${f.page}) states: ${g.display} — “${f.evidence.substringAfterLast("→").trim().take(90)}”"
        }
        val name = applicantName ?: "[your full name]"
        val body = """
            |Dear $recipient,
            |
            |I am writing to request verification and, if needed, correction of my $field. My documents do not agree:
            |
            |$lines
            |
            |Please confirm the correct $field in your records and, if your document is in error, issue a corrected copy. I have attached copies of the documents listed above.
            |
            |Name: $name
            |
            |Thank you,
            |$name
            """.trimMargin()
        return Letter("Ask to correct your $field", recipient, "Request to verify and correct $field — $name", body)
    }

    /** For a possible duplicate bill: ask the biller to confirm it was issued (and should be paid) once. */
    fun duplicateBill(finding: Finding, accountHolder: String?): Letter? {
        if (finding.kind != FindingKind.DUPLICATE_BILL) return null
        val number = Regex("Bill ([A-Z0-9-]+)").find(finding.title)?.groupValues?.get(1) ?: "the bill below"
        val refs = finding.evidence.map { "${it.documentName} (page ${it.page})" }.distinct().joinToString(", ")
        val name = accountHolder ?: "[account holder name]"
        val body = """
            |Dear Customer Care,
            |
            |I have received bill $number more than once ($refs). Please confirm that this bill was issued once and should be paid only once, and that no duplicate charge will be applied to my account.
            |
            |Account holder: $name
            |
            |Thank you,
            |$name
            """.trimMargin()
        return Letter("Confirm bill $number is not charged twice", "Customer Care", "Duplicate bill $number — please confirm single payment", body)
    }

    fun fullName(entries: List<LedgerEntry>) = entries.firstOrNull { it.type == FactType.FULL_NAME }?.usable?.display
}
