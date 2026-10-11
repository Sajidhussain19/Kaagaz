package com.kaagaz.app.integration

import com.kaagaz.app.brief.DailyBrief
import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.FactExtractor
import com.kaagaz.app.knowledge.FactType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoanStatementTest {
    private val loan = listOf(MissionOsFixtures.page(1,
        "SURYA DEMO FINANCE LTD (FICTIONAL)", "PERSONAL LOAN STATEMENT", "Customer name: Aarav Mehta",
        "Loan account number: SDF-PL-2026-4417", "Loan amount: INR 1,00,000.00", "Interest rate: 13.5% per annum",
        "Tenure: 12 months", "EMI amount: INR 8,958.00", "EMI due date: 18 October 2026",
        "Outstanding principal: INR 83,120.00", "EMIs paid: 2 of 12"))
    private val facts = FactExtractor.extract("loan", "personal-loan-statement.pdf", loan)
    private fun v(t: FactType) = facts.first { it.type == t }.normalized

    @Test fun readsLoanAmountEmiAndDueDate() {
        assertEquals(DocKind.LOAN_STATEMENT, FactExtractor.classify(loan))
        assertEquals("100000", v(FactType.LOAN_AMOUNT))
        assertEquals("8958", v(FactType.EMI_AMOUNT))
        assertEquals("2026-10-18", v(FactType.DUE_DATE))
        assertEquals("83120", v(FactType.OUTSTANDING_PRINCIPAL))
    }

    @Test fun todayShowsThisMonthsEmi() {
        val brief = DailyBrief.brief(emptyList(), facts, emptyList(), LocalDate.of(2026, 10, 11), mapOf("loan" to DocKind.LOAN_STATEMENT))
        val item = brief.items.single()
        assertEquals("Pay loan EMI · ₹8,958", item.title)
        assertTrue(item.detail, item.detail.startsWith("Due Sun 18 Oct"))
    }
}
