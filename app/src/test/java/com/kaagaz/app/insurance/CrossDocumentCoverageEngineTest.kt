package com.kaagaz.app.insurance

import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossDocumentCoverageEngineTest {
    private fun page(vararg text: String) = listOf(OcrPageResult(1, text.joinToString("\n"), text.mapIndexed { index, value ->
        OcrLineEvidence(1, value, null, .9f, 1000, 1400)
    }))
    private fun source(id: String, name: String, vararg lines: String) =
        CrossDocumentCoverageEngine.Source(id, name, page(*lines))

    @Test fun associatedStatementAnswersWithDocumentIdentity() {
        val answer = CrossDocumentCoverageEngine.answer(
            "How much premium have I paid?",
            source("policy", "Child policy.pdf", "Premium schedule INR 2,500 quarterly"),
            listOf(source("statement", "Payment statement.pdf", "Premium paid total: INR 5,000"))
        )
        assertTrue(answer.summary.contains("₹5000"))
        assertTrue(answer.passages.all { it.sourceDocumentId == "statement" && it.sourceDocumentName == "Payment statement.pdf" })
    }

    @Test fun conflictingTotalsAreNeedsReviewAndKeepBothSources() {
        val answer = CrossDocumentCoverageEngine.answer(
            "How much premium have I paid?", source("policy", "Policy.pdf", "Policy schedule"),
            listOf(
                source("one", "Statement A.pdf", "Premium paid total: INR 5,000"),
                source("two", "Statement B.pdf", "Premium paid total: INR 7,500")
            )
        )
        assertEquals(CoverageIntelligenceEngine.AnswerKind.AMBIGUOUS, answer.kind)
        assertEquals(setOf("one", "two"), answer.passages.mapNotNull { it.sourceDocumentId }.toSet())
    }

    @Test fun duplicateAssociatedStatementDoesNotDoubleCount() {
        val answer = CrossDocumentCoverageEngine.answer(
            "How much premium have I paid?", source("policy", "Policy.pdf", "Policy schedule"),
            listOf(
                source("one", "Statement copy 1.pdf", "Premium paid total: INR 5,000"),
                source("two", "Statement copy 2.pdf", "Premium paid total: INR 5,000")
            )
        )
        assertEquals(CoverageIntelligenceEngine.AnswerKind.RESULTS, answer.kind)
        assertTrue(answer.summary.contains("₹5000"))
    }

    @Test fun missingStatementDoesNotInventPayment() {
        val answer = CrossDocumentCoverageEngine.answer(
            "How much premium have I paid?", source("policy", "Policy.pdf", "Premium INR 2,500 quarterly"), emptyList()
        )
        assertEquals(CoverageIntelligenceEngine.AnswerKind.NOT_FOUND, answer.kind)
        assertTrue(answer.summary.contains("not found", ignoreCase = true))
    }

    @Test fun outstandingAndScheduledRemainSeparateAcrossAssociation() {
        val statement = source("statement", "Statement.pdf",
            "Verified outstanding premium: INR 2,500 due 01 July 2027",
            "Future scheduled premium: INR 2,500 due 01 October 2027")
        val primary = source("policy", "Policy.pdf", "Premium schedule INR 2,500 quarterly")
        val outstanding = CrossDocumentCoverageEngine.answer("How much is outstanding?", primary, listOf(statement))
        val scheduled = CrossDocumentCoverageEngine.answer("How much premium remains scheduled?", primary, listOf(statement))
        assertTrue(outstanding.passages.all { it.category == CoverageIntelligenceEngine.Category.OUTSTANDING_DUE })
        assertTrue(scheduled.passages.all { it.category == CoverageIntelligenceEngine.Category.PREMIUM_SCHEDULE })
        assertTrue(scheduled.passages.all { it.sourceDocumentId == "statement" })
    }

    @Test fun linkedFutureScheduleTakesPrecedenceOverGeneralPolicySchedule() {
        val policy = source("policy", "Policy.pdf", "Premium schedule: INR 2,500 quarterly")
        val statement = source("statement", "Statement.pdf", "Future scheduled premium: INR 2,500 due 01 October 2027")

        val answer = CrossDocumentCoverageEngine.answer("How much premium remains scheduled?", policy, listOf(statement))

        assertEquals(CoverageIntelligenceEngine.AnswerKind.RESULTS, answer.kind)
        assertTrue(answer.summary.contains("01 October 2027"))
        assertTrue(answer.passages.all { it.sourceDocumentId == "statement" })
    }
}
