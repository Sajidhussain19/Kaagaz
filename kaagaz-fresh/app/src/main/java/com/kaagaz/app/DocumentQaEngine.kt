package com.kaagaz.app

import java.util.Locale

/** Offline, deterministic question routing over persisted insurance fields. */
object DocumentQaEngine {
    enum class AnswerKind { DOCUMENT_SUPPORTED, NEEDS_REVIEW, USER_CONFIRMED, NOT_FOUND, UNSUPPORTED, REVIEW_SUMMARY }

    data class Answer(
        val question: String,
        val text: String,
        val kind: AnswerKind,
        val fieldKey: InsuranceFieldKey? = null,
        val evidence: List<OcrLineEvidence> = emptyList()
    )

    fun answer(question: String, fields: List<InsuranceField>): Answer {
        val clean = question.trim()
        if (clean.isBlank()) return unsupported(clean, "Type a question about this insurance document.")
        val normalized = clean.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (isReviewQuestion(normalized)) return reviewSummary(clean, fields)
        val key = identifyField(normalized)
            ?: return unsupported(clean, "I can answer questions about the insurer, policy number, insured person, vehicle registration, premium, policy dates, or fields that need review.")
        val field = fields.firstOrNull { it.key == key }
            ?: InsuranceField(key, null, emptyList(), VerificationStatus.NOT_FOUND)
        return fieldAnswer(clean, field)
    }

    private fun identifyField(text: String): InsuranceFieldKey? = when {
        hasAny(text, "expire", "expiry", "expiration", "valid until", "valid upto", "end date") -> InsuranceFieldKey.POLICY_EXPIRY
        hasAny(text, "start date", "policy start", "coverage start", "starts", "begin", "effective from", "commencement") -> InsuranceFieldKey.POLICY_START
        hasAny(text, "policy number", "policy no", "policy id") -> InsuranceFieldKey.POLICY_NUMBER
        hasAny(text, "premium", "cost", "amount paid", "how much") -> InsuranceFieldKey.PREMIUM
        hasAny(text, "insured person", "insured name", "policyholder", "who is insured", "whose policy") -> InsuranceFieldKey.INSURED_NAME
        hasAny(text, "vehicle registration", "registration number", "vehicle number", "registration no", "regn") -> InsuranceFieldKey.VEHICLE_REGISTRATION
        hasAny(text, "insurer", "insurance company", "which company") -> InsuranceFieldKey.INSURER
        else -> null
    }

    private fun fieldAnswer(question: String, field: InsuranceField): Answer {
        val source = field.value
        val shown = field.displayedValue
        if (shown == null) return Answer(
            question,
            "I could not find ${field.key.label.lowercase(Locale.ENGLISH)} in the saved document.",
            AnswerKind.NOT_FOUND,
            field.key
        )
        if (field.isEdited && field.isUserConfirmed) return Answer(
            question,
            "You confirmed ${field.key.label.lowercase(Locale.ENGLISH)} as $shown. This is a user correction, not a value independently verified in the document.${source?.let { " The OCR originally extracted $it." } ?: " The OCR did not find an original value."}",
            AnswerKind.USER_CONFIRMED,
            field.key,
            field.evidence
        )
        if (field.isEdited) return Answer(
            question,
            "Your saved edit is $shown, but it has not been confirmed. ${source?.let { "The document text supports $it." } ?: "The document did not provide a supported original value."}",
            AnswerKind.NEEDS_REVIEW,
            field.key,
            field.evidence
        )
        if (field.isUserConfirmed) return Answer(
            question,
            "$shown. You explicitly confirmed this value; ${if (field.evidence.isEmpty()) "no source highlight is available" else "the original OCR evidence remains available"}.",
            AnswerKind.USER_CONFIRMED,
            field.key,
            field.evidence
        )
        return when (field.status) {
            VerificationStatus.SUPPORTED -> Answer(
                question, "$shown. Supported by document text.", AnswerKind.DOCUMENT_SUPPORTED, field.key, field.evidence
            )
            VerificationStatus.NEEDS_REVIEW -> Answer(
                question,
                "A possible ${field.key.label.lowercase(Locale.ENGLISH)} is $shown, but it needs review.${field.reviewReason?.let { " $it" }.orEmpty()}",
                AnswerKind.NEEDS_REVIEW,
                field.key,
                field.evidence
            )
            VerificationStatus.NOT_FOUND -> Answer(
                question, "I could not find ${field.key.label.lowercase(Locale.ENGLISH)} in the saved document.", AnswerKind.NOT_FOUND, field.key
            )
        }
    }

    private fun reviewSummary(question: String, fields: List<InsuranceField>): Answer {
        val missing = fields.filter { it.displayedValue == null }.map { it.key.label }
        val review = fields.filter { it.status == VerificationStatus.NEEDS_REVIEW }.map { it.key.label }
        val unconfirmedEdits = fields.filter { it.isEdited && !it.isUserConfirmed }.map { it.key.label }
        val parts = buildList {
            add(if (missing.isEmpty()) "No tracked fields are missing." else "Missing: ${missing.joinToString()}.")
            add(if (review.isEmpty()) "No OCR fields are marked as needing review." else "Needs review: ${review.joinToString()}.")
            if (unconfirmedEdits.isNotEmpty()) add("Unconfirmed edits: ${unconfirmedEdits.joinToString()}.")
        }
        return Answer(question, parts.joinToString(" "), AnswerKind.REVIEW_SUMMARY)
    }

    private fun isReviewQuestion(text: String) =
        hasAny(text, "missing", "needs review", "need review", "uncertain", "ambiguous", "not found", "incomplete")

    private fun hasAny(text: String, vararg phrases: String) = phrases.any(text::contains)

    private fun unsupported(question: String, message: String) = Answer(question, message, AnswerKind.UNSUPPORTED)
}
