package com.kaagaz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentQaEngineTest {
    private val supported = listOf(
        field(InsuranceFieldKey.INSURER, "Suraksha Sample General Insurance Ltd"),
        field(InsuranceFieldKey.POLICY_NUMBER, "KZG-MTR-2026-000123"),
        field(InsuranceFieldKey.INSURED_NAME, "Aarav Demo"),
        field(InsuranceFieldKey.VEHICLE_REGISTRATION, "KA 01 ZZ 9999"),
        field(InsuranceFieldKey.PREMIUM, "₹12,500"),
        field(InsuranceFieldKey.POLICY_START, "01 Jan 2026"),
        field(InsuranceFieldKey.POLICY_EXPIRY, "31 Dec 2026")
    )

    @Test fun answersEverySupportedFieldIntent() {
        val questions = mapOf(
            "Which insurance company issued this?" to InsuranceFieldKey.INSURER,
            "What is my policy number?" to InsuranceFieldKey.POLICY_NUMBER,
            "Who is the insured person?" to InsuranceFieldKey.INSURED_NAME,
            "What is the vehicle registration number?" to InsuranceFieldKey.VEHICLE_REGISTRATION,
            "How much premium is listed?" to InsuranceFieldKey.PREMIUM,
            "When does the policy start?" to InsuranceFieldKey.POLICY_START,
            "When does my policy expire?" to InsuranceFieldKey.POLICY_EXPIRY
        )
        questions.forEach { (question, key) ->
            val answer = DocumentQaEngine.answer(question, supported)
            assertEquals(key, answer.fieldKey)
            assertEquals(DocumentQaEngine.AnswerKind.DOCUMENT_SUPPORTED, answer.kind)
        }
    }

    @Test fun reportsMissingAndAmbiguousFields() {
        val fields = supported.map {
            when (it.key) {
                InsuranceFieldKey.PREMIUM -> it.copy(value = null, status = VerificationStatus.NOT_FOUND)
                InsuranceFieldKey.POLICY_EXPIRY -> it.copy(status = VerificationStatus.NEEDS_REVIEW, reviewReason = "More than one plausible value was found.")
                else -> it
            }
        }
        assertEquals(DocumentQaEngine.AnswerKind.NOT_FOUND, DocumentQaEngine.answer("What is the premium?", fields).kind)
        assertEquals(DocumentQaEngine.AnswerKind.NEEDS_REVIEW, DocumentQaEngine.answer("When does it expire?", fields).kind)
        val review = DocumentQaEngine.answer("What information is missing or needs review?", fields)
        assertTrue(review.text.contains("Premium amount"))
        assertTrue(review.text.contains("Policy expiry date"))
    }

    @Test fun distinguishesUnconfirmedAndConfirmedCorrections() {
        val original = supported.first { it.key == InsuranceFieldKey.INSURED_NAME }
        val edited = original.copy(userValue = "Aarav Test", isUserConfirmed = false)
        val unconfirmed = DocumentQaEngine.answer("Who is insured?", supported.replace(original, edited))
        assertEquals(DocumentQaEngine.AnswerKind.NEEDS_REVIEW, unconfirmed.kind)
        assertTrue(unconfirmed.text.contains("has not been confirmed"))
        assertTrue(unconfirmed.text.contains("Aarav Demo"))

        val confirmed = DocumentQaEngine.answer("Who is insured?", supported.replace(original, edited.copy(isUserConfirmed = true)))
        assertEquals(DocumentQaEngine.AnswerKind.USER_CONFIRMED, confirmed.kind)
        assertTrue(confirmed.text.contains("user correction"))
        assertTrue(confirmed.text.contains("OCR originally extracted Aarav Demo"))
    }

    @Test fun refusesUnsupportedQuestions() {
        assertEquals(
            DocumentQaEngine.AnswerKind.UNSUPPORTED,
            DocumentQaEngine.answer("Should I renew with another company?", supported).kind
        )
    }

    private fun field(key: InsuranceFieldKey, value: String) =
        InsuranceField(key, value, emptyList(), VerificationStatus.SUPPORTED)

    private fun List<InsuranceField>.replace(old: InsuranceField, new: InsuranceField) = map { if (it == old) new else it }
}
