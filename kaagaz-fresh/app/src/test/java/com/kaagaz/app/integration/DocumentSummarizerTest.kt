package com.kaagaz.app.integration

import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.FactExtractor
import com.kaagaz.app.llm.LlmClient
import com.kaagaz.app.llm.LlmResult
import com.kaagaz.app.llm.NoLlm
import com.kaagaz.app.rag.DocumentSummarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSummarizerTest {
    private val resumeFacts = FactExtractor.extract("r", "aarav-resume.pdf", MissionOsFixtures.resume)
    private val billFacts = FactExtractor.extract("b", "electricity-bill.pdf", MissionOsFixtures.billOriginal)

    private class Fake(private val reply: String) : LlmClient {
        override val isReady = true
        override val label = "fake-gemma"
        override fun generate(prompt: String, system: String, maxTokens: Int, timeoutMs: Long) = LlmResult.Text(reply, 3)
    }

    @Test fun glanceIsOrderedForTheDocumentKind() {
        val rows = DocumentSummarizer.glance(DocKind.RESUME, resumeFacts)
        assertEquals("Full name", rows.first().label)
        assertTrue(rows.any { it.label == "Email" && it.value == "aarav.mehta.demo@example.com" && it.page == 1 })
        // The fictional bill has no "Biller:" label, so the account holder leads, followed by the bill details.
        assertEquals(listOf("Account holder", "Bill number", "Bill date", "Amount due", "Due date"), DocumentSummarizer.glance(DocKind.BILL, billFacts).map { it.label })
    }

    @Test fun headlineFitsTheKind() {
        assertEquals("Aarav Mehta · Android Engineer at Nimbus Labs", DocumentSummarizer.headline(DocKind.RESUME, resumeFacts))
        assertEquals("₹2,340 due on 20 Oct 2026", DocumentSummarizer.headline(DocKind.BILL, billFacts))
    }

    @Test fun narrativeWithInventedNumberIsRejected() {
        val n = DocumentSummarizer.narrative(DocKind.BILL, MissionOsFixtures.billOriginal, Fake("An electricity bill of INR 2,999 due on 20 October 2026."))
        assertNull(n.text)
        assertTrue(n.note.contains("2999"))
    }

    @Test fun groundedNarrativeIsAccepted() {
        val n = DocumentSummarizer.narrative(DocKind.BILL, MissionOsFixtures.billOriginal, Fake("An electricity bill of INR 2,340.00 is due on 20 October 2026."))
        assertEquals("An electricity bill of INR 2,340.00 is due on 20 October 2026.", n.text)
    }

    @Test fun sentenceWithWrongNameIsRemovedButGroundedOneKept() {
        // Exactly what Gemma 1B produced on the test phone for the fictional transcript.
        val reply = "Aarav Mehta studied Bachelor of Technology in Computer Science. They studied at Kaagaz Institute of Technology. Their final CGPA is 8.4."
        val n = DocumentSummarizer.narrative(DocKind.TRANSCRIPT, MissionOsFixtures.transcript, Fake(reply))
        assertEquals("Aarav Mehta studied Bachelor of Technology in Computer Science. Their final CGPA is 8.4.", n.text)
        assertTrue(n.note, n.note.contains("Kaagaz Institute of Technology"))
    }

    @Test fun genderedPronounsFromTheModelBecomeThey() {
        assertEquals("Aarav Mehta's bill shows they consumed 312 units; their due date is set.",
            DocumentSummarizer.neutralizePronouns("Aarav Mehta's bill shows he consumed 312 units; his due date is set."))
        assertEquals("They are a student and they have a CGPA of 8.4. The college awarded them their degree.",
            DocumentSummarizer.neutralizePronouns("She is a student and she has a CGPA of 8.4. The college awarded her her degree."))
        assertEquals("The theme and other help remain unchanged.", DocumentSummarizer.neutralizePronouns("The theme and other help remain unchanged."))
    }

    @Test fun noModelMeansFactsOnly() {
        assertNull(DocumentSummarizer.narrative(DocKind.RESUME, MissionOsFixtures.resume, NoLlm).text)
    }
}
