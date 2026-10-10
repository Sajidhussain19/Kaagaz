package com.kaagaz.app

import com.kaagaz.app.CoverageIntelligenceEngine.AnswerKind
import com.kaagaz.app.CoverageIntelligenceEngine.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unsupported questions, OCR noise, conditions, exclusions, missing values and conflicts must fail safe. */
class AnswerSafetyRegressionTest {
    private fun pages(vararg text: String) = listOf(OcrPageResult(1, text.joinToString("\n"), text.map { OcrLineEvidence(1, it, null, .9f, 1240, 1754) }))
    private fun ask(question: String, vararg text: String) = CoverageIntelligenceEngine.answer(question, pages(*text))

    @Test fun unsupportedQuestionsAreNotGuessed() {
        listOf("Can I renew online?", "What is the weather tomorrow?", "Tell me a joke").forEach { question ->
            assertFalse(question, CoverageIntelligenceEngine.supports(question))
            assertEquals(question, AnswerKind.UNSUPPORTED, ask(question, "Guaranteed Maturity Benefit of INR 1,50,000 is payable on 15 June 2032.").kind)
        }
    }

    @Test fun ocrNoiseDoesNotCreateOrCorruptAmounts() {
        val answer = ask("What is payable at maturity?",
            "|| ~ ::",
            "Guaranteed Maturity Benefit of lNR 1,50,000 is payable at age 21 on 15 June 2032 only if all",
            "premiums have been paid.")
        assertFalse(answer.summary, answer.summary.contains("1,50,000"))
        assertTrue(answer.summary, answer.summary.contains("15 June 2032"))
        assertTrue(answer.passages.single().evidence.size == 2)
    }

    @Test fun conditionalBenefitIsMarkedAndExclusionNeverBecomesABenefit() {
        val conditional = ask("What does this policy cover?", "Ambulance benefit is payable up to INR 2,000 per claim, subject to prior approval.")
        val excludedOnly = ask("What does this policy cover?", "No benefit is payable for cosmetic treatment.", "Cosmetic surgery is excluded.")

        assertEquals("Ambulance benefit: up to INR 2,000 per claim (conditions apply).", conditional.summary)
        assertTrue(excludedOnly.passages.none { it.category == Category.BENEFIT })
        assertTrue(CoverageIntelligenceEngine.benefitHighlights(excludedOnly.passages).isEmpty())
        assertFalse(excludedOnly.summary.contains("cosmetic", ignoreCase = true))
    }

    @Test fun sentenceEndsAreNotMergedAcrossDifferentClauses() {
        val answer = ask("What does this policy cover?",
            "Cosmetic treatment is excluded and not payable.",
            "Day care benefit is payable up to INR 10,000 per year.")
        assertEquals("Day care benefit: up to INR 10,000 per year.", answer.summary)
        assertTrue(answer.passages.none { it.wording.contains("Cosmetic") && it.category == Category.BENEFIT })
    }

    @Test fun missingValuesAreReportedAsMissing() {
        val maturity = ask("What is payable at maturity?", "Premium paid INR 2,500 on 01 January 2027")
        val age = ask("How much is payable at age 21?", "Education benefit of INR 50,000 is payable at age 18.")
        assertEquals(AnswerKind.NOT_FOUND, maturity.kind)
        assertEquals(AnswerKind.NOT_FOUND, age.kind)
        assertFalse(age.summary.contains("50,000"))
    }

    @Test fun conflictingMaturityAmountsAreFlaggedNotPicked() {
        val answer = ask("What is payable at maturity?",
            "Maturity benefit of INR 1,50,000 is payable on 15 June 2032.",
            "The maturity amount shown in the schedule is INR 1,60,000.")
        assertEquals(AnswerKind.AMBIGUOUS, answer.kind)
        assertTrue(answer.summary, answer.summary.contains("INR 1,50,000") && answer.summary.contains("INR 1,60,000"))
        assertTrue(answer.passages.size >= 2)
    }
}
