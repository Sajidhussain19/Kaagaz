package com.kaagaz.app.integration

import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.rag.Chunker
import com.kaagaz.app.rag.HybridRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The concept thesaurus and OCR-tolerant fuzzy matching that stand in for an embedding model. */
class RetrievalBridgesTest {
    private val loan = listOf(MissionOsFixtures.page(1,
        "SURYA DEMO FINANCE LTD (FICTIONAL)", "PERSONAL LOAN STATEMENT", "Loan amount: INR 1,00,000.00",
        "Installment amount: INR 8,958.00", "Installment due date: 18 October 2026", "Outstanding principal: INR 83,120.00"))
    private val chunks = MissionOsFixtures.chunks() + Chunker.chunks("loan", "personal-loan-statement.pdf", loan) { null }
    private val retriever = HybridRetriever(chunks)

    @Test fun misspelledQuestionStillFindsTheBill() {
        val hits = retriever.search("How many units were consumd?")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().chunk.documentId.startsWith("bill"))
        assertTrue("consumed" in hits.first().matched)
    }

    @Test fun ocrMisreadInTheDocumentIsBridged() {
        val ocr = listOf(MissionOsFixtures.page(1, "Annual premlum: INR 12,000", "Policy expiry: 31 March 2027"))
        val hits = HybridRetriever(chunks + Chunker.chunks("p", "policy.pdf", ocr) { null }).search("What is the annual premium?")
        assertEquals("p", hits.first().chunk.documentId)
        assertTrue("premlum" in hits.first().matched)
    }

    @Test fun everydayWordsReachDocumentVocabulary() {
        // "EMI" never appears in this statement; the thesaurus maps it to "installment".
        assertEquals("loan", retriever.search("How much is my EMI?").first().chunk.documentId)
        assertTrue("instalment" in retriever.queryTerms("emi") || "installment" in retriever.queryTerms("emi"))
    }

    @Test fun numbersNeverMatchFuzzily() {
        assertTrue(retriever.fuzzyTerms(setOf("4410029382", "2025")).isEmpty())
        assertTrue(retriever.search("Is consumer number 4410029382 mine?").none { "4410029381" in it.matched })
    }

    @Test fun unrelatedQuestionsStillAbstain() {
        assertTrue(retriever.search("What is the weathr in Pune tomorow?").isEmpty())
        assertTrue(retriever.search("Who won the cricket match?").isEmpty())
    }

    @Test fun editDistanceStopsEarly() {
        assertEquals(1, HybridRetriever.editDistance("premlum", "premium", 2))
        assertEquals(3, HybridRetriever.editDistance("abcdef", "uvwxyz", 2))
    }
}
