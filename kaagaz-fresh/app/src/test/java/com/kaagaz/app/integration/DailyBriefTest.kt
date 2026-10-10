package com.kaagaz.app.integration

import com.kaagaz.app.audit.CrashTest
import com.kaagaz.app.brief.DailyBrief
import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.knowledge.TrustLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyBriefTest {
    private val docs = MissionOsFixtures.docs()
    private val facts = MissionOsFixtures.facts(docs)
    private val ledger = TrustLedger.build(facts, docs.associate { it.id to it.kind })

    @Test fun ranksConflictsAndDeadlinesFromRealFixtures() {
        val today = LocalDate.of(2026, 10, 10)
        val findings = CrashTest.run(docs, facts, ledger, today = today)
        val brief = DailyBrief.build(ledger, facts, findings, today)
        assertEquals("2 conflicting records", brief.first().title)
        assertTrue(brief.any { it.title == "₹2,340 due in 10 days" && it.target == DailyBrief.Target.DOCUMENT })
        assertTrue(brief.any { it.title == "Possible duplicate bill" })
        assertTrue(brief.size <= 4)
    }

    @Test fun overdueBillIsMostUrgent() {
        val brief = DailyBrief.build(ledger, facts, emptyList(), LocalDate.of(2026, 10, 25))
        assertEquals("₹2,340 due 5 days ago", brief.first().title)
        assertEquals(DailyBrief.Tone.URGENT, brief.first().tone)
    }

    @Test fun emptyStateIsAllClear() {
        assertEquals("All clear", DailyBrief.build(emptyList(), emptyList(), emptyList()).single().title)
    }
}
