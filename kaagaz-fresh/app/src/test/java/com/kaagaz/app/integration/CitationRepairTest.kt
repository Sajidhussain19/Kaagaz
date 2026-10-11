package com.kaagaz.app.integration

import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.llm.NoLlm
import com.kaagaz.app.rag.Chunk
import com.kaagaz.app.rag.GroundedAnswerer
import com.kaagaz.app.rag.ScoredChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationRepairTest {
    private fun hit(id: String, text: String) = ScoredChunk(Chunk(id, id, "$id.pdf", 1, text, emptyList()), 1.0, 1.0, 0, null, emptySet())
    private val policy = hit("policy", "If the policyholder dies during the policy term, all future premiums are waived and the guaranteed benefit of INR 5,00,000 is paid to the nominee.")
    private val bill = hit("bill", "Units consumed: 312. Amount due: INR 2,340.00. Due date: 20 October 2026.")
    private val answerer = GroundedAnswerer(NoLlm)

    @Test fun uncitedAnswerIsAttributedToTheOnePassageThatSupportsIt() {
        val v = answerer.validate("If the policyholder dies, future premiums are waived and the nominee receives the guaranteed benefit of INR 5,00,000.", listOf(bill, policy))
        assertTrue(v.reason, v.ok)
        assertEquals(listOf(2), v.cited)
    }

    @Test fun uncitedAnswerWithAnInventedNumberIsStillRejected() {
        val v = answerer.validate("If the policyholder dies, future premiums are waived and the nominee receives INR 7,00,000.", listOf(bill, policy))
        assertFalse(v.ok)
        assertEquals("it cited no evidence", v.reason)
    }

    @Test fun uncitedAnswerUnrelatedToEveryPassageIsRejected() {
        assertFalse(answerer.validate("Your flight to Goa departs from terminal two tomorrow morning.", listOf(bill, policy)).ok)
    }

    @Test fun fallbackQuotesTheWholeSentenceNotAWrappedOcrLine() {
        val page = MissionOsFixtures.page(2, "BENEFITS", "If the policyholder dies during the policy term, all future premiums are waived and the guaranteed",
            "benefit of INR 5,00,000 is paid to the nominee.")
        val corpus = GroundedAnswerer.Corpus(emptyList(), MissionOsFixtures.chunks() + com.kaagaz.app.rag.Chunker.chunks("p", "policy.pdf", listOf(page)) { null }, emptyList(), emptyList())
        val answer = answerer.answer("What happens if the policyholder dies?", corpus)
        assertTrue(answer.text, answer.text.contains("paid to the nominee"))
    }

    @Test fun scheduleWithoutFullStopsQuotesTheMatchingLineNotTheWholePage() {
        val page = MissionOsFixtures.page(3, "APEX WAREHOUSE (FICTIONAL)", "GOODS ISSUE (GI) PLAN", "Goods issue date: 12 October 2026 (tomorrow)",
            "GI document number: GI-2026-1012", "Number of products to be goods issued: 10", "GI-01 WH-P1001 Steel bolts M8 - Issue qty: 120 units")
        val corpus = GroundedAnswerer.Corpus(emptyList(), MissionOsFixtures.chunks() + com.kaagaz.app.rag.Chunker.chunks("w", "warehouse.pdf", listOf(page)) { null }, emptyList(), emptyList())
        assertEquals("“Goods issue date: 12 October 2026 (tomorrow)”", answerer.answer("When is the goods issue date?", corpus).text)
    }
}
