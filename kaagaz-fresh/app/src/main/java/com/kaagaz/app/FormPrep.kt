package com.kaagaz.app

import java.util.Locale

data class FormField(val id: String, val label: String, val type: FactType?, val required: Boolean, val sensitiveReason: String? = null)

data class FormSpec(val id: String, val title: String, val fields: List<FormField>, val requiredDocuments: Set<DocKind> = emptySet())

/** Turns a free-text form label into an expected fact type, and flags labels that request sensitive data. */
object FieldIdentifier {
    private val synonyms: List<Pair<FactType, List<String>>> = listOf(
        FactType.DATE_OF_BIRTH to listOf("date of birth", "dob", "birth date", "born"),
        FactType.GRADUATION_YEAR to listOf("graduation year", "year of graduation", "year of passing", "passing year", "graduate", "graduated", "graduation"),
        FactType.CGPA to listOf("cgpa", "gpa", "percentage", "grade", "score", "marks"),
        FactType.EMAIL to listOf("email", "e mail", "mail id"),
        FactType.PHONE to listOf("phone", "mobile", "contact number", "telephone"),
        FactType.ADDRESS to listOf("address", "residence"),
        FactType.DEGREE to listOf("degree", "qualification", "programme", "program", "course"),
        FactType.INSTITUTION to listOf("university", "college", "institution", "institute", "school"),
        FactType.EMPLOYER to listOf("employer", "company", "organisation", "organization", "work at", "work for"),
        FactType.JOB_TITLE to listOf("job title", "designation", "role", "position"),
        FactType.SKILLS to listOf("skills", "skill"),
        FactType.FULL_NAME to listOf("full name", "applicant name", "candidate name", "your name", "name", "who am i"),
        FactType.DUE_DATE to listOf("due date", "pay by", "when is the bill due", "bill due"),
        FactType.BILL_AMOUNT to listOf("amount due", "bill amount", "how much is the bill", "how much do i owe")
    )

    private val sensitive: List<Pair<Regex, String>> = listOf(
        Regex("pass ?word|net ?banking|login") to "Asks for a password. Legitimate application forms never need your banking or account password.",
        Regex("\\botp\\b|one time password") to "Asks for an OTP. OTPs authorise transactions and should never be written on a form.",
        Regex("\\b(atm |card )?pin\\b|\\bcvv\\b|card number") to "Asks for card credentials, which an application does not need.",
        Regex("maiden name") to "Asks for a common security-question answer. Check why it is needed.",
        Regex("aadhaar|aadhar") to "Asks for a full national ID number. Check whether a masked number or no number would do.",
        Regex("bank account|ifsc") to "Asks for bank account details. Check that this is needed (for example, for refunds) before sharing."
    )

    fun identify(label: String): FactType? {
        val text = " " + Normalizer.text(label) + " "
        return synonyms.firstOrNull { (_, words) -> words.any { text.contains(" ${Normalizer.text(it)} ") || text.contains(" ${Normalizer.text(it)}") } }?.first
    }

    fun sensitivity(label: String): String? {
        val text = label.lowercase(Locale.ENGLISH)
        return sensitive.firstOrNull { it.first.containsMatchIn(text) }?.second
    }
}

object FormLibrary {
    /** Fictional demo form. Two fields are deliberately over-reaching so the crash test has something to flag. */
    val universityApplication = FormSpec(
        "uni-app", "Fictional university application (demo)",
        listOf(
            FormField("name", "Full name", FactType.FULL_NAME, true),
            FormField("dob", "Date of birth", FactType.DATE_OF_BIRTH, true),
            FormField("email", "Email", FactType.EMAIL, true),
            FormField("phone", "Mobile number", FactType.PHONE, true),
            FormField("degree", "Previous degree", FactType.DEGREE, true),
            FormField("institution", "University / college", FactType.INSTITUTION, true),
            FormField("year", "Year of graduation", FactType.GRADUATION_YEAR, true),
            FormField("cgpa", "CGPA", FactType.CGPA, true),
            FormField("employer", "Current employer", FactType.EMPLOYER, false),
            FormField("skills", "Key skills", FactType.SKILLS, false),
            FormField("bank_password", "Net-banking password (for fee verification)", null, false, FieldIdentifier.sensitivity("net-banking password")),
            FormField("aadhaar", "Full Aadhaar number", null, false, FieldIdentifier.sensitivity("aadhaar"))
        ),
        setOf(DocKind.RESUME, DocKind.EDUCATION_CERTIFICATE, DocKind.TRANSCRIPT)
    )

    /** Builds a spec from pasted labels, e.g. copied from a real form. Unknown labels stay unmapped, never guessed. */
    fun fromLabels(title: String, labels: List<String>): FormSpec = FormSpec(
        "custom-" + Normalizer.text(title).replace(' ', '-').take(24),
        title.ifBlank { "Pasted form" },
        labels.map { it.trim().trimEnd(':', '*') }.filter { it.isNotBlank() }.distinct().mapIndexed { i, label ->
            val sensitive = FieldIdentifier.sensitivity(label)
            FormField("f$i", label, if (sensitive != null) null else FieldIdentifier.identify(label), label.trim().endsWith("*"), sensitive)
        }
    )
}

data class FieldProposal(
    val field: FormField,
    val value: String?,
    val state: TrustState,
    val sources: List<Fact>,
    val note: String
) {
    val fillable: Boolean get() = value != null && state == TrustState.VERIFIED && this.field.sensitiveReason == null
}

/** Field-by-field mapping from the Trust Ledger. Only VERIFIED values are proposed; nothing is filled here. */
object FormMapper {
    fun map(spec: FormSpec, ledger: List<LedgerEntry>): List<FieldProposal> = spec.fields.map { field ->
        when {
            field.sensitiveReason != null -> FieldProposal(field, null, TrustState.NEEDS_CONFIRMATION, emptyList(), "Review flag: ${field.sensitiveReason} Kaagaz will not fill this.")
            field.type == null -> FieldProposal(field, null, TrustState.MISSING, emptyList(), "Kaagaz does not know what this field expects.")
            else -> {
                val entry = ledger.firstOrNull { it.type == field.type }
                when (entry?.state) {
                    null, TrustState.MISSING -> FieldProposal(field, null, TrustState.MISSING, emptyList(), "No saved document states your ${field.type.label.lowercase(Locale.ENGLISH)}.")
                    TrustState.VERIFIED -> entry.groups.first().let { g ->
                        FieldProposal(field, g.display, TrustState.VERIFIED, g.facts, sourceNote(g) + if (entry.resolvedByUser) " (chosen by you)" else "")
                    }
                    TrustState.CONFLICTING -> FieldProposal(field, null, TrustState.CONFLICTING, entry.groups.flatMap { it.facts },
                        "Sources disagree: " + entry.groups.joinToString(" vs ") { "${it.display} (${it.documents.joinToString()})" } + ". Resolve before filling.")
                    TrustState.NEEDS_CONFIRMATION -> FieldProposal(field, entry.groups.first().display, TrustState.NEEDS_CONFIRMATION, entry.groups.flatMap { it.facts },
                        entry.reason + " Confirm in the Trust Ledger first.")
                }
            }
        }
    }

    fun sourceNote(group: ValueGroup): String = group.facts.joinToString("; ") { "${it.documentName} p.${it.page}" }
}
