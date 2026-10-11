package com.kaagaz.app.integration

import com.kaagaz.app.audit.CrashTest
import com.kaagaz.app.audit.FindingKind
import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.forms.FormLibrary
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.TrustLedger
import com.kaagaz.app.letters.FixItLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FixItLettersTest {
    private val docs = MissionOsFixtures.docs()
    private val facts = MissionOsFixtures.facts(docs)
    private val kinds = docs.associate { it.id to it.kind }
    private val ledger = TrustLedger.build(facts, kinds, setOf(FactType.FULL_NAME))

    @Test fun correctionLetterQuotesBothSourcesAndGoesToTheRegistrar() {
        val entry = ledger.first { it.type == FactType.GRADUATION_YEAR }
        val letter = FixItLetters.correction(entry, kinds, FixItLetters.fullName(ledger))!!
        assertEquals("Registrar / Examinations office", letter.recipient)
        assertTrue(letter.body, "aarav-resume.pdf, page 1" in letter.body && "degree-certificate.pdf, page 1" in letter.body)
        assertTrue(letter.body, "2024" in letter.body && "2023" in letter.body)
        assertTrue(letter.subject, letter.subject.endsWith("Aarav Mehta"))
    }

    @Test fun noLetterForVerifiedFacts() {
        assertNull(FixItLetters.correction(ledger.first { it.type == FactType.FULL_NAME }, kinds, null))
    }

    @Test fun duplicateBillLetterNamesTheBillAndBothCopies() {
        val findings = CrashTest.run(docs, facts, ledger, FormLibrary.universityApplication, emptyMap(), LocalDate.of(2026, 10, 10))
        val dup = findings.first { it.kind == FindingKind.DUPLICATE_BILL }
        val letter = FixItLetters.duplicateBill(dup, null)!!
        assertTrue(letter.body, "electricity-bill.pdf" in letter.body && "electricity-bill-copy.pdf" in letter.body)
        assertTrue(letter.body, "[account holder name]" in letter.body)
        assertNull(FixItLetters.duplicateBill(findings.first { it.kind != FindingKind.DUPLICATE_BILL }, null))
    }
}
