package com.kaagaz.app.integration

import com.kaagaz.app.audit.CrashTest
import com.kaagaz.app.audit.FindingKind
import com.kaagaz.app.brief.DailyBrief
import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.TrustLedger
import com.kaagaz.app.letters.FixItLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyBriefTest {
    private val docs = MissionOsFixtures.docs()
    private val facts = MissionOsFixtures.facts(docs)
    private val kinds = docs.associate { it.id to it.kind }
    private val ledger = TrustLedger.build(facts, kinds)
    private val today = LocalDate.of(2026, 10, 10)

    @Test fun plainLanguageActionsFromRealFixtures() {
        val findings = CrashTest.run(docs, facts, ledger, today = today)
        val brief = DailyBrief.brief(ledger, facts, findings, today, kinds)
        assertEquals(4, brief.items.size)
        assertEquals("4 things need you · nothing overdue", brief.status)
        val titles = brief.items.map { it.title }
        assertTrue(titles.toString(), "Confirm your date of birth" in titles && "Confirm your graduation year" in titles)
        val grad = brief.items.first { it.title == "Confirm your graduation year" }
        assertTrue(grad.detail, grad.detail.contains("says 2024") && grad.detail.contains("says 2023"))
        assertTrue(brief.items.any { it.title == "Pay electricity bill · ₹2,340" && it.detail == "Due Tue 20 Oct · in 10 days" })
        assertTrue(titles.none { it.contains("conflicting records") })
    }

    @Test fun overdueBillIsMostUrgent() {
        val brief = DailyBrief.brief(ledger, facts, emptyList(), LocalDate.of(2026, 10, 25), kinds)
        assertEquals("Was due Tue 20 Oct · 5 days overdue", brief.items.first().detail)
        assertTrue(brief.status.endsWith("1 overdue"))
    }

    @Test fun emptyStateIsAllClear() {
        assertEquals("You're all caught up", DailyBrief.brief(emptyList(), emptyList(), emptyList()).status)
    }

    @Test fun correctionLetterQuotesBothSources() {
        val entry = ledger.first { it.type == FactType.GRADUATION_YEAR }
        val letter = FixItLetters.correction(entry, kinds, "Aarav Mehta")!!
        assertEquals("Registrar / Examinations office", letter.recipient)
        assertTrue(letter.body, letter.body.contains("degree-certificate.pdf, page 1) states: 2023"))
        assertTrue(letter.body.contains("aarav-resume.pdf, page 1) states: 2024"))
        assertTrue(letter.body.contains("Aarav Mehta"))
    }

    @Test fun duplicateBillLetterNamesTheBill() {
        val finding = CrashTest.run(docs, facts, ledger, today = today).first { it.kind == FindingKind.DUPLICATE_BILL }
        val letter = FixItLetters.duplicateBill(finding, "Aarav Mehta")
        assertNotNull(letter)
        assertTrue(letter!!.subject.contains("DPD-2026-0915"))
    }
}
