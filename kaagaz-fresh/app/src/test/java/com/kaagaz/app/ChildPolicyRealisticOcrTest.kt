package com.kaagaz.app

import com.kaagaz.app.CoverageIntelligenceEngine.AnswerKind
import com.kaagaz.app.CoverageIntelligenceEngine.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests on wrapped, column-ordered OCR that mirrors what the device produced for the child policy. */
class ChildPolicyRealisticOcrTest {
    private val policy = RealisticOcrFixtures.childPolicy
    private val statement = RealisticOcrFixtures.childStatement

    private fun ask(question: String, pages: List<OcrPageResult> = policy) = CoverageIntelligenceEngine.answer(question, pages)
    private fun sentenceCount(text: String) = text.split(Regex("[.!?](\\s|$)")).count { it.isNotBlank() }

    @Test fun coverSummaryNeverStitchesWrappedLineFragments() {
        val answer = ask("What does this policy cover?")

        assertEquals(AnswerKind.RESULTS, answer.kind)
        assertFalse(answer.summary, answer.summary.contains("; "))
        assertFalse(answer.summary, answer.summary.contains("only if every"))
        assertFalse(answer.summary, answer.summary.contains(",."))
        assertTrue(answer.summary, answer.summary.contains("INR 1,00,000") && answer.summary.contains("INR 1,50,000"))
        assertTrue(answer.summary, answer.summary.contains("age 18") && answer.summary.contains("15 June 2029"))
        assertTrue(answer.summary, sentenceCount(answer.summary) <= 2)
    }

    @Test fun passagesAreWholeSentencesWithTheFullCondition() {
        val answer = ask("What does this policy cover?")
        val education = answer.passages.single { it.wording.startsWith("Guaranteed Education Benefit of INR 1,00,000") }

        assertTrue(education.wording, education.wording.endsWith("only if every premium due through 01 April 2029 has been paid and the policy remains in force."))
        assertEquals(2, education.evidence.size)
        assertTrue(education.evidence.all { it.pageNumber == 2 })
        answer.passages.forEach { passage ->
            assertFalse("Heading shown as a clause: ${passage.wording}", Regex("^\\d{1,2}\\.\\s").containsMatchIn(passage.wording))
        }
    }

    @Test fun noClauseIsRepeatedAcrossCards() {
        listOf("What does this policy cover?", "What are the exclusions and waiting periods?", "What is guaranteed and what is projected?").forEach { question ->
            val answer = ask(question)
            val wordings = answer.passages.map { it.wording }
            assertEquals("Repeated clause for $question: $wordings", wordings.size, wordings.distinct().size)
            val evidence = answer.passages.map { passage -> passage.evidence.map { it.text } }
            assertEquals("Repeated highlight for $question", evidence.size, evidence.distinct().size)
        }
    }

    @Test fun ageAndMaturityQuestionsUseTheirOwnClause() {
        val age18 = ask("How much is payable at age 18?")
        val maturity = ask("What is payable at maturity?")

        assertTrue(age18.summary, age18.summary.contains("INR 1,00,000") && age18.summary.contains("15 June 2029"))
        assertFalse(age18.summary, age18.summary.contains("INR 1,50,000"))
        assertNotEquals(AnswerKind.UNSUPPORTED, maturity.kind)
        assertTrue(maturity.summary, maturity.summary.contains("INR 1,50,000") && maturity.summary.contains("15 June 2032"))
        assertFalse(maturity.summary, maturity.summary.contains("INR 40,000"))
    }

    @Test fun guaranteedAndProjectedStaySeparate() {
        val answer = ask("What is guaranteed and what is projected?")

        assertTrue(answer.summary, answer.summary.contains("INR 1,00,000") && answer.summary.contains("INR 1,50,000"))
        assertTrue(answer.summary, answer.summary.contains("INR 40,000") && answer.summary.contains("not guaranteed"))
        assertTrue(answer.passages.filter { it.category == Category.MATURITY }.none { it.wording.contains("INR 40,000") })
    }

    @Test fun exclusionAndWaitingPeriodKeepCompleteWording() {
        val answer = ask("What are the exclusions and waiting periods?")
        val exclusion = answer.passages.first { it.category == Category.EXCLUSION && it.wording.contains("deliberate fraud") }

        assertTrue(answer.passages.any { it.category == Category.WAITING_PERIOD })
        assertTrue(exclusion.wording, exclusion.wording.contains("deliberate fraud or a materially false statement in the application."))
        assertTrue(exclusion.evidence.size >= 2)
    }

    @Test fun unmentionedDiseaseIsNotFound() {
        val answer = ask("Does this policy cover dengue?")
        assertEquals(AnswerKind.NOT_FOUND, answer.kind)
        assertTrue(answer.passages.isEmpty())
    }

    @Test fun statementKeepsPaidScheduledAndOutstandingSeparate() {
        val paid = ask("How much have I paid?", statement)
        val scheduled = ask("How much remains scheduled?", statement)
        val outstanding = ask("How much is outstanding?", statement)

        assertTrue(paid.summary, paid.summary.contains("₹5000"))
        assertNotEquals(AnswerKind.UNSUPPORTED, scheduled.kind)
        assertTrue(scheduled.summary, scheduled.summary.contains("INR 2,500") && scheduled.summary.contains("01 October 2027"))
        assertTrue(scheduled.passages.all { it.category == Category.PREMIUM_SCHEDULE })
        assertTrue(outstanding.summary, outstanding.summary.contains("INR 2,500"))
        assertTrue(outstanding.passages.isNotEmpty() && outstanding.passages.all { it.category == Category.OUTSTANDING_DUE })
    }

    @Test fun everyHandoverQuestionIsSupportedAcrossLinkedDocuments() {
        val primary = CrossDocumentCoverageEngine.Source("policy", "Child policy.pdf", policy)
        val linked = listOf(CrossDocumentCoverageEngine.Source("statement", "Payment statement.pdf", statement))
        listOf(
            "Does this policy cover dengue?", "What does this policy cover?", "How much is payable at age 18?",
            "What is payable at maturity?", "How much have I paid?", "How much remains scheduled?", "How much is outstanding?"
        ).forEach { question ->
            val answer = CrossDocumentCoverageEngine.answer(question, primary, linked)
            assertNotEquals(question, AnswerKind.UNSUPPORTED, answer.kind)
            assertTrue("Too long for $question: ${answer.summary}", sentenceCount(answer.summary) <= 2)
            answer.passages.forEach { assertTrue(question, it.evidence.isNotEmpty() && it.evidence.all { e -> e.pageNumber > 0 }) }
        }
        assertTrue(CrossDocumentCoverageEngine.answer("How much remains scheduled?", primary, linked).passages.all { it.sourceDocumentId == "statement" })
    }
}
