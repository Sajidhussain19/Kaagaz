package com.kaagaz.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReminderPolicyTest {
    private val today = LocalDate.of(2026, 1, 1)
    private fun field(value: String?, status: VerificationStatus = VerificationStatus.SUPPORTED, user: String? = null, confirmed: Boolean = false) =
        InsuranceField(InsuranceFieldKey.POLICY_EXPIRY, value, emptyList(), status, userValue = user, isUserConfirmed = confirmed)

    @Test fun validSupportedDateIsEligible() {
        val result = ReminderPolicy.evaluate(field("31 Dec 2026"), today)
        assertTrue(result.allowed); assertEquals(LocalDate.of(2026, 12, 31), result.date)
        assertEquals("Supported by document text", result.sourceLabel)
    }

    @Test fun pastDateIsRejected() = assertFalse(ReminderPolicy.evaluate(field("31 Dec 2025"), today).allowed)

    @Test fun ambiguousDateRequiresConfirmation() {
        assertFalse(ReminderPolicy.evaluate(field("31 Dec 2026", VerificationStatus.NEEDS_REVIEW), today).allowed)
        assertTrue(ReminderPolicy.evaluate(field("31 Dec 2026", VerificationStatus.NEEDS_REVIEW, confirmed = true), today).allowed)
    }

    @Test fun editedDateRequiresConfirmationAndKeepsUserSourceLabel() {
        assertFalse(ReminderPolicy.evaluate(field("31 Dec 2026", user = "15 Jan 2027"), today).allowed)
        val confirmed = ReminderPolicy.evaluate(field("31 Dec 2026", user = "15 Jan 2027", confirmed = true), today)
        assertTrue(confirmed.allowed); assertEquals("User-confirmed correction", confirmed.sourceLabel)
    }

    @Test fun invalidAndMissingDatesAreRejected() {
        assertFalse(ReminderPolicy.evaluate(field("sometime later"), today).allowed)
        assertFalse(ReminderPolicy.evaluate(field(null, VerificationStatus.NOT_FOUND), today).allowed)
    }

    @Test fun duplicateIdentityIsStableButOffsetsAreDistinct() {
        val first = ReminderPolicy.workName("doc-1", "POLICY_EXPIRY", 7)
        assertEquals(first, ReminderPolicy.workName("doc-1", "POLICY_EXPIRY", 7))
        assertNotEquals(first, ReminderPolicy.workName("doc-1", "POLICY_EXPIRY", 1))
        assertNotEquals(first, ReminderPolicy.workName("doc-2", "POLICY_EXPIRY", 7))
    }

    @Test fun selectedOffsetKeepsItsOriginalChoice() {
        assertEquals(setOf(7), ReminderPolicy.selectedOffsets(listOf(30, 7, 1, 0), listOf(false, true, false, false)))
    }
}
