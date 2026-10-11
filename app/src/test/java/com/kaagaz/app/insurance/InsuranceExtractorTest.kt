package com.kaagaz.app.insurance

import com.kaagaz.app.core.InsuranceFieldKey
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.core.VerificationStatus
import com.kaagaz.app.fixtures.RealisticOcrFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsuranceExtractorTest {
    @Test fun demoPolicyLabelsExtractAllSevenFields() {
        val text = listOf(
            "Insurer: Bharat Demo General Insurance Ltd",
            "Policy Number: KZG-DEMO-2027-004219",
            "Insured Person: Asha Sample",
            "Vehicle Registration: KA 05 DM 2047",
            "Total Premium: INR 14,750",
            "Policy Start Date: 01 Jan 2027",
            "Policy Expiry Date: 31 Dec 2027"
        )
        val lines = text.mapIndexed { index, value ->
            OcrLineEvidence(1, value, null, .95f, 600, 800)
        }
        val fields = InsuranceExtractor.extract(listOf(OcrPageResult(1, text.joinToString("\n"), lines))).associateBy { it.key }
        assertEquals("Asha Sample", fields[InsuranceFieldKey.INSURED_NAME]?.value)
        assertEquals("KZG-DEMO-2027-004219", fields[InsuranceFieldKey.POLICY_NUMBER]?.value)
        assertEquals("₹14,750", fields[InsuranceFieldKey.PREMIUM]?.value)
        assertEquals("31 Dec 2027", fields[InsuranceFieldKey.POLICY_EXPIRY]?.value)
        assertTrue(fields.values.all { it.status == VerificationStatus.SUPPORTED })
        assertEquals(7, fields.size)
    }

    @Test fun labelOnlyLinesUseTheFollowingNameInsteadOfTheLabel() {
        val text = listOf(
            "INSURER", "Bharat Demo General Insurance Ltd",
            "INSURED PERSON", "Asha Sample",
            "This does not represent a real person, vehicle, insurer, or insurance contract."
        )
        val lines = text.map { OcrLineEvidence(1, it, null, .94f, 600, 800) }
        val fields = InsuranceExtractor.extract(listOf(OcrPageResult(1, text.joinToString("\n"), lines))).associateBy { it.key }
        assertEquals("Bharat Demo General Insurance Ltd", fields[InsuranceFieldKey.INSURER]?.value)
        assertEquals("Asha Sample", fields[InsuranceFieldKey.INSURED_NAME]?.value)
        assertEquals(VerificationStatus.SUPPORTED, fields[InsuranceFieldKey.INSURER]?.status)
    }

    @Test fun columnOrderedTableNeverUsesTheNextLabelAsAValue() {
        val fields = InsuranceExtractor.extract(RealisticOcrFixtures.childStatement, { null }).associateBy { it.key }
        assertTrue(fields.values.none { it.value.equals("Statement date", ignoreCase = true) })
        assertEquals(VerificationStatus.NOT_FOUND, fields[InsuranceFieldKey.INSURED_NAME]?.status)
    }

    @Test fun sameRowGeometryPairsScheduleLabelsWithTheirValues() {
        val statement = InsuranceExtractor.extract(RealisticOcrFixtures.childStatement, RealisticOcrFixtures::boxOf).associateBy { it.key }
        val policy = InsuranceExtractor.extract(RealisticOcrFixtures.childPolicy, RealisticOcrFixtures::boxOf).associateBy { it.key }

        assertEquals("Asha Example", statement[InsuranceFieldKey.INSURED_NAME]?.value)
        assertEquals("NDL-CHILD-2027-0042", statement[InsuranceFieldKey.POLICY_NUMBER]?.value)
        assertEquals("NDL-CHILD-2027-0042", policy[InsuranceFieldKey.POLICY_NUMBER]?.value)
        assertEquals("₹2,500", policy[InsuranceFieldKey.PREMIUM]?.value)
        assertEquals("01 Jan 2027", policy[InsuranceFieldKey.POLICY_START]?.value)
        assertEquals(VerificationStatus.NOT_FOUND, policy[InsuranceFieldKey.VEHICLE_REGISTRATION]?.status)
        assertEquals(VerificationStatus.NOT_FOUND, policy[InsuranceFieldKey.POLICY_EXPIRY]?.status)
    }
}
