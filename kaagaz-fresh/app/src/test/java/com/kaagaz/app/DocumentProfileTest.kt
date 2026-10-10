package com.kaagaz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentProfileTest {
    private fun pages(vararg text: String) = listOf(OcrPageResult(1, text.joinToString("\n"), text.map { OcrLineEvidence(1, it, null, .95f, 1000, 1400) }))
    private fun field(key: InsuranceFieldKey, value: String?) =
        InsuranceField(key, value, emptyList(), if (value == null) VerificationStatus.NOT_FOUND else VerificationStatus.SUPPORTED)

    @Test fun detectsDocumentKindsFromExplicitWording() {
        assertEquals(DocumentKind.LIFE_SAVINGS, DocumentProfile.detect(RealisticOcrFixtures.childPolicy))
        assertEquals(DocumentKind.PAYMENT_STATEMENT, DocumentProfile.detect(RealisticOcrFixtures.childStatement))
        assertEquals(DocumentKind.MOTOR_INSURANCE, DocumentProfile.detect(pages("Vehicle Registration: KA 05 DM 2047", "Own damage premium INR 4,000", "Motor policy schedule")))
        assertEquals(DocumentKind.HEALTH_INSURANCE, DocumentProfile.detect(pages("In-patient hospitalisation is covered up to the Sum Insured.", "Room rent is capped at 1% of Sum Insured per day.")))
        assertEquals(DocumentKind.INSURANCE_OTHER, DocumentProfile.detect(pages("Policy schedule", "Insurer: Demo Insurance Ltd")))
        assertEquals(DocumentKind.UNKNOWN, DocumentProfile.detect(emptyList()))
    }

    @Test fun childPolicyNeverShowsAnEmptyVehicleField() {
        val fields = InsuranceFieldKey.values().map { field(it, if (it == InsuranceFieldKey.POLICY_NUMBER) "NDL-CHILD-2027-0042" else null) }
        val shown = DocumentProfile.fieldsToShow(DocumentKind.LIFE_SAVINGS, fields).map { it.key }
        val missing = DocumentProfile.missingFields(DocumentKind.LIFE_SAVINGS, fields)

        assertEquals(listOf(InsuranceFieldKey.POLICY_NUMBER), shown)
        assertFalse(InsuranceFieldKey.VEHICLE_REGISTRATION in missing)
        assertTrue(InsuranceFieldKey.PREMIUM in missing)
        assertTrue(InsuranceFieldKey.VEHICLE_REGISTRATION in DocumentProfile.missingFields(DocumentKind.MOTOR_INSURANCE, fields))
    }

    @Test fun aFoundOrEditedValueIsNeverHidden() {
        val vehicle = field(InsuranceFieldKey.VEHICLE_REGISTRATION, "KA 05 DM 2047")
        val edited = field(InsuranceFieldKey.POLICY_EXPIRY, null).copy(userValue = "31 Dec 2030")
        val shown = DocumentProfile.fieldsToShow(DocumentKind.LIFE_SAVINGS, listOf(vehicle, edited)).map { it.key }
        assertEquals(listOf(InsuranceFieldKey.VEHICLE_REGISTRATION, InsuranceFieldKey.POLICY_EXPIRY), shown)
    }

    @Test fun childPolicyOverviewIsCompactAndGrounded() {
        val fields = InsuranceExtractor.extract(RealisticOcrFixtures.childPolicy, RealisticOcrFixtures::boxOf)
        val rows = DocumentProfile.overview(DocumentKind.LIFE_SAVINGS, RealisticOcrFixtures.childPolicy, fields)
        val text = rows.joinToString("\n") { "${it.label}: ${it.value}" }

        assertTrue(text, rows.any { it.label == "Provides" && it.value.contains("INR 1,00,000 at age 18 on 15 June 2029") })
        assertTrue(text, rows.any { it.label == "Provides" && it.value.contains("INR 1,50,000 at age 21 on 15 June 2032") })
        assertTrue(text, rows.any { it.label == "Projected" && it.value == "INR 40,000, not guaranteed" })
        assertTrue(text, rows.any { it.label == "Premium" && it.value == "₹2,500 · Quarterly" })
        assertTrue(text, rows.any { it.label == "Watch for" })
        assertTrue(text, rows.filter { it.label == "Provides" }.all { it.passage != null && it.passage.evidence.isNotEmpty() })
        assertFalse(text, text.contains("vehicle", ignoreCase = true))
        assertTrue(text, rows.size <= 7)
    }

    @Test fun statementOverviewSeparatesPaidOutstandingAndScheduled() {
        val rows = DocumentProfile.overview(DocumentKind.PAYMENT_STATEMENT, RealisticOcrFixtures.childStatement, emptyList()).associateBy { it.label }
        assertEquals("₹5000", rows["Paid"]?.value)
        assertEquals("INR 2,500", rows["Outstanding"]?.value)
        assertEquals("INR 2,500", rows["Scheduled"]?.value)
    }

    @Test fun suggestionsMatchTheDocumentKind() {
        val life = DocumentProfile.suggestedQuestions(DocumentKind.LIFE_SAVINGS, RealisticOcrFixtures.childPolicy)
        assertTrue(life.contains("How much is payable at age 18?"))
        assertTrue(life.size <= 6)
        assertTrue(life.all { CoverageIntelligenceEngine.supports(it) })
        DocumentKind.values().forEach { kind ->
            DocumentProfile.suggestedQuestions(kind, emptyList()).forEach { assertTrue("$kind: $it", CoverageIntelligenceEngine.supports(it)) }
            DocumentProfile.fieldQuestions(kind).forEach { assertFalse("$kind field question routed to coverage: $it", CoverageIntelligenceEngine.supports(it)) }
        }
        assertFalse(DocumentProfile.askIntro(DocumentKind.LIFE_SAVINGS).contains("vehicle", ignoreCase = true))
    }
}
