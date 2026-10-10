package com.kaagaz.app

import com.kaagaz.app.CoverageIntelligenceEngine.AnswerKind
import com.kaagaz.app.CoverageIntelligenceEngine.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Evaluator questions on OCR-like fixtures generated from the fictional PDFs, with device-like line geometry. */
class EvaluatorScenarioTest {
    private val health = RealisticOcrFixtures.coveragePolicy
    private fun ask(question: String, pages: List<OcrPageResult> = health) =
        CoverageIntelligenceEngine.answer(question, pages, boxOf = RealisticOcrFixtures::boxOf)
    private fun sentences(text: String) = text.split(Regex("[.!?](\\s|$)")).count { it.isNotBlank() }

    @Test fun coverAnswerIsShortAndNamesWhatIsCovered() {
        val answer = ask("What does this policy cover?")
        assertTrue(answer.summary, answer.summary.contains("INR 2,000 per claim (conditions apply)"))
        assertTrue(answer.summary, answer.summary.contains("hospitalisation", ignoreCase = true))
        assertFalse(answer.summary, answer.summary.contains("Benefits are payable only when"))
        assertTrue(answer.summary, sentences(answer.summary) <= 2)
    }

    @Test fun coveredSubjectToExclusionsIsConditionalNotAnExclusion() {
        val excluded = ask("What is excluded?")
        assertTrue(excluded.passages.none { it.category == Category.EXCLUSION && it.wording.startsWith("In-patient hospitalisation") })
        assertTrue(excluded.summary, excluded.summary.contains("Cosmetic treatment"))
    }

    @Test fun diseaseAnswersDistinguishConditionalExcludedAndNotFound() {
        val diabetes = ask("Does it cover diabetes?")
        val dengue = ask("Does this policy cover dengue?")
        val cosmetic = ask("Is cosmetic treatment covered?")

        assertTrue(diabetes.summary, diabetes.summary.contains("conditionally covered"))
        assertTrue(diabetes.passages.any { it.category == Category.WAITING_PERIOD && it.wording.contains("24 months") })
        assertEquals(AnswerKind.NOT_FOUND, dengue.kind)
        assertTrue(cosmetic.summary, cosmetic.summary.startsWith("Excluded:"))
    }

    @Test fun waitingPeriodAnswerStatesTheDuration() {
        val answer = ask("What is the waiting period?")
        assertTrue(answer.summary, answer.summary.contains("24 months") && answer.summary.contains("page 2"))
    }

    @Test fun limitsAnswerGivesAConcreteExampleAndNoBareLabels() {
        val answer = ask("What limits or conditions apply?")
        assertTrue(answer.summary, answer.summary.contains("Room rent is limited to 1 percent of the Sum Insured per day"))
        assertTrue(answer.passages.none { it.wording.trim() == "Sum Insured" })
    }

    @Test fun unsupportedAgeDoesNotInheritAnotherAgesBenefit() {
        val answer = ask("How much is payable at age 25?", RealisticOcrFixtures.childPolicy)
        assertEquals(AnswerKind.NOT_FOUND, answer.kind)
        assertFalse(answer.summary.contains("INR"))
    }

    @Test fun childPolicyWithGeometryGivesTheSameGroundedAnswers() {
        val policy = RealisticOcrFixtures.childPolicy
        assertTrue(ask("How much is payable at age 21?", policy).summary.contains("INR 1,50,000 at age 21 on 15 June 2032"))
        assertTrue(ask("How much is payable at age 18?", policy).summary.contains("INR 1,00,000 at age 18 on 15 June 2029"))
        assertTrue(ask("What projected benefit is shown?", policy).summary.contains("INR 40,000. It is explicitly not guaranteed"))
    }

    @Test fun conflictingLinkedTotalsNeedReviewAndKeepEverySource() {
        fun source(id: String, total: String) = CrossDocumentCoverageEngine.Source(id, "$id.pdf",
            listOf(OcrPageResult(1, "", listOf(OcrLineEvidence(1, "Premium paid total: INR $total", null, .9f, 1240, 1754)))))
        val answer = CrossDocumentCoverageEngine.answer(
            "How much have I paid?",
            CrossDocumentCoverageEngine.Source("policy", "policy.pdf", RealisticOcrFixtures.childPolicy),
            listOf(source("statement-a", "5,000"), source("statement-b", "7,500"))
        )
        assertEquals(AnswerKind.AMBIGUOUS, answer.kind)
        assertFalse(answer.summary, answer.summary.contains("₹"))
        assertEquals(setOf("statement-a.pdf", "statement-b.pdf"), answer.passages.mapNotNull { it.sourceDocumentName }.toSet())
    }

    @Test fun linkedStatementAnswersCarryTheirFilenameAndPage() {
        val answer = CrossDocumentCoverageEngine.answer(
            "How much have I paid?",
            CrossDocumentCoverageEngine.Source("policy", "Child policy.pdf", RealisticOcrFixtures.childPolicy),
            listOf(CrossDocumentCoverageEngine.Source("statement", "Payment statement.pdf", RealisticOcrFixtures.childStatement))
        )
        assertTrue(answer.summary, answer.summary.contains("₹5000"))
        assertTrue(answer.passages.isNotEmpty() && answer.passages.all { it.sourceDocumentName == "Payment statement.pdf" && it.evidence.all { e -> e.pageNumber == 1 } })
    }
}
