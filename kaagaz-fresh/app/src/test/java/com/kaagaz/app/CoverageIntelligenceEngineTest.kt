package com.kaagaz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageIntelligenceEngineTest {
    private fun line(page: Int, text: String, top: Int) = OcrLineEvidence(page, text, null, .94f, 1000, 1400)
    private val pages = listOf(
        OcrPageResult(1, "", listOf(
            line(1, "Hospitalisation Benefit", 80),
            line(1, "In-patient hospitalisation is covered up to the Sum Insured.", 130),
            line(1, "Diabetes is mentioned for medical disclosure only.", 210),
            line(1, "This mention does not establish coverage.", 260),
            line(1, "Pre-existing diabetes has a waiting period of 24 months.", 340),
            line(1, "Cosmetic treatment is excluded and not payable.", 420)
        )),
        OcrPageResult(2, "", listOf(
            line(2, "Room rent is capped at 1% of Sum Insured per day.", 80),
            line(2, "Ambulance benefit is payable up to INR 2,000 per claim, provided hospitalisation is admitted.", 150),
            line(2, "Submit the claim form and discharge summary within 30 days.", 230)
        ))
    )

    @Test fun retrievesBenefitsAndConditions() {
        val answer = CoverageIntelligenceEngine.answer("What benefits or coverage does this policy describe?", pages)
        assertEquals(CoverageIntelligenceEngine.AnswerKind.RESULTS, answer.kind)
        assertTrue(answer.passages.any { it.category == CoverageIntelligenceEngine.Category.BENEFIT })
        assertTrue(answer.passages.any { it.evidence.first().pageNumber == 1 })
    }

    @Test fun diseaseMentionDoesNotBecomeCoverage() {
        val answer = CoverageIntelligenceEngine.answer("Does this policy cover diabetes?", pages)
        assertTrue(answer.passages.any { it.category == CoverageIntelligenceEngine.Category.MENTION_ONLY })
        assertTrue(answer.summary.contains("do not establish") || answer.passages.none { it.category == CoverageIntelligenceEngine.Category.BENEFIT })
    }

    @Test fun preservesWaitingAndExclusionClassification() {
        val waiting = CoverageIntelligenceEngine.answer("Are there waiting periods?", pages)
        val exclusions = CoverageIntelligenceEngine.answer("What exclusions are listed?", pages)
        assertTrue(waiting.passages.all { it.category == CoverageIntelligenceEngine.Category.WAITING_PERIOD })
        assertTrue(exclusions.passages.all { it.category == CoverageIntelligenceEngine.Category.EXCLUSION })
    }

    @Test fun retrievesLimitsAndClaimRequirementsOnCorrectPage() {
        val limits = CoverageIntelligenceEngine.answer("What limits or sub-limits apply?", pages)
        val claims = CoverageIntelligenceEngine.answer("What are the claim requirements?", pages)
        assertTrue(limits.passages.any { it.category == CoverageIntelligenceEngine.Category.LIMIT && it.evidence.first().pageNumber == 2 })
        assertTrue(claims.passages.any { it.category == CoverageIntelligenceEngine.Category.CLAIM_REQUIREMENT && it.evidence.first().pageNumber == 2 })
    }

    @Test fun explainsLocatedClauseAndRejectsUnsupportedQuestion() {
        val explanation = CoverageIntelligenceEngine.answer("Explain the room rent clause in simple English", pages)
        val unsupported = CoverageIntelligenceEngine.answer("Can I renew online?", pages)
        assertEquals(CoverageIntelligenceEngine.Category.LIMIT, explanation.passages.first().category)
        assertEquals(CoverageIntelligenceEngine.AnswerKind.UNSUPPORTED, unsupported.kind)
    }

    @Test fun handlesMissingAndConflictingEvidenceCautiously() {
        val missing = CoverageIntelligenceEngine.answer("What exclusions are listed?", listOf(OcrPageResult(1, "", listOf(line(1, "General policy wording", 20)))))
        val disease = CoverageIntelligenceEngine.answer("Which diseases are mentioned?", pages)
        assertEquals(CoverageIntelligenceEngine.AnswerKind.NOT_FOUND, missing.kind)
        assertTrue(disease.passages.any { it.category == CoverageIntelligenceEngine.Category.WAITING_PERIOD })
        assertTrue(disease.passages.any { it.category == CoverageIntelligenceEngine.Category.MENTION_ONLY })
    }

    @Test fun broadCoverageSummaryIsShortAndEvidenceBased() {
        val answer = CoverageIntelligenceEngine.answer("What does this policy cover?", pages)
        assertEquals(CoverageIntelligenceEngine.AnswerKind.RESULTS, answer.kind)
        assertTrue(answer.summary.contains("hospitalisation", ignoreCase = true))
        assertTrue(answer.summary.split(Regex("[.!?]")).count { it.isNotBlank() } <= 2)
        assertTrue(answer.passages.first().evidence.isNotEmpty())
    }

    @Test fun paymentAndPremiumQuestionsDoNotInventValues() {
        val missingPayment = CoverageIntelligenceEngine.answer("How much have I paid?", pages)
        val scheduled = CoverageIntelligenceEngine.answer("How much premium remains?", listOf(OcrPageResult(1, "", listOf(
            line(1, "Future premium schedule: INR 3,000 due 01 June 2027", 20)
        ))))
        assertEquals("Payment history not found.", missingPayment.summary)
        assertTrue(scheduled.summary.contains("does not prove"))
        assertTrue(scheduled.passages.all { it.category == CoverageIntelligenceEngine.Category.PREMIUM_SCHEDULE })
    }

    @Test fun verifiedPaymentsCanBeTotalledFromExplicitRecords() {
        val paymentPages = listOf(OcrPageResult(1, "", listOf(
            line(1, "Premium paid INR 1,200 on 01 January 2027", 20),
            line(1, "Payment received INR 800 on 01 February 2027", 80)
        )))
        val answer = CoverageIntelligenceEngine.answer("How much have I paid?", paymentPages)
        assertTrue(answer.summary.contains("₹2000"))
        assertTrue(answer.passages.flatMap { it.evidence }.map { it.text }.toSet().size >= 2)
    }

    @Test fun importantDatesUseOnlySupportedOrConfirmedFields() {
        val fields = listOf(
            InsuranceField(InsuranceFieldKey.POLICY_START, "01 Jan 2027", emptyList(), VerificationStatus.SUPPORTED),
            InsuranceField(InsuranceFieldKey.POLICY_EXPIRY, "31 Dec 2027", emptyList(), VerificationStatus.SUPPORTED),
            InsuranceField(InsuranceFieldKey.PREMIUM, "₹18,600", emptyList(), VerificationStatus.SUPPORTED)
        )
        val answer = CoverageIntelligenceEngine.answer("What important dates should I know?", pages, fields)
        assertTrue(answer.summary.contains("01 Jan 2027"))
        assertTrue(answer.summary.contains("31 Dec 2027"))
        assertTrue(answer.summary.contains("No unsupported"))
    }

    @Test fun missingMaturityIsReportedHonestly() {
        val answer = CoverageIntelligenceEngine.answer("When will I receive the benefit?", pages)
        assertEquals(CoverageIntelligenceEngine.AnswerKind.NOT_FOUND, answer.kind)
        assertTrue(answer.summary.contains("not found"))
    }

    @Test fun financialAnswersSurfaceOnlySupportedAmountsAndDates() {
        val statement = listOf(OcrPageResult(1, "", listOf(
            line(1, "A future scheduled premium of INR 1,500 is due on 01 April 2027.", 20),
            line(1, "A guaranteed maturity benefit of INR 1,00,000 is payable on 31 December 2036 only if all premiums are paid.", 80),
            line(1, "The projected maturity value is INR 1,25,000 and is not guaranteed.", 140)
        )))

        val future = CoverageIntelligenceEngine.answer("What future premium is scheduled?", statement)
        val maturity = CoverageIntelligenceEngine.answer("When will I receive the benefit?", statement)
        val projected = CoverageIntelligenceEngine.answer("What projected benefit is shown?", statement)

        assertTrue(future.summary.contains("INR 1,500") && future.summary.contains("01 April 2027"))
        assertTrue(future.summary.contains("does not prove"))
        assertTrue(maturity.summary.contains("INR 1,00,000") && maturity.summary.contains("31 December 2036"))
        assertTrue(maturity.summary.contains("eligibility conditions"))
        assertTrue(projected.summary.contains("INR 1,25,000") && projected.summary.contains("not guaranteed"))
    }

    @Test fun childBenefitAtAgeRequiresExplicitSupportedWording() {
        val childPlan = listOf(OcrPageResult(1, "", listOf(
            line(1, "Education benefit of INR 50,000 is payable at age 18 only if the policy remains in force.", 20)
        )))
        val supported = CoverageIntelligenceEngine.answer("What benefit is payable at age 18?", childPlan)
        val missing = CoverageIntelligenceEngine.answer("What benefit is payable at age 21?", childPlan)

        assertEquals(CoverageIntelligenceEngine.AnswerKind.RESULTS, supported.kind)
        assertTrue(supported.summary, supported.summary.contains("INR 50,000") && supported.summary.contains("age 18"))
        assertEquals(CoverageIntelligenceEngine.AnswerKind.NOT_FOUND, missing.kind)
        assertTrue(missing.passages.isEmpty())
    }

    @Test fun splitOcrDoesNotAttachNearbyUnlabelledFinancialValues() {
        val split = listOf(OcrPageResult(1, "", listOf(
            line(1, "Payment received 01 February 2027", 20),
            line(1, "Guaranteed Maturity Benefit", 80),
            line(1, "INR 1,00,000", 140)
        )))
        val answer = CoverageIntelligenceEngine.answer("When will I receive the benefit?", split)

        assertEquals(CoverageIntelligenceEngine.AnswerKind.RESULTS, answer.kind)
        assertTrue(answer.summary.contains("specified benefit"))
        assertTrue(!answer.summary.contains("01 February 2027"))
        assertTrue(!answer.summary.contains("INR 1,00,000"))
    }

    @Test fun evaluatorChildPolicyQuestionsUseDistinctGroundedValues() {
        val policy = listOf(
            OcrPageResult(2, "", listOf(
                line(2, "Guaranteed Education Benefit of INR 1,00,000 is payable at age 18 on 15 June 2029 only if every premium due has been paid and the policy remains in force.", 20),
                line(2, "Guaranteed Maturity Benefit of INR 1,50,000 is payable at age 21 on 15 June 2032 only if all scheduled premiums have been paid.", 80),
                line(2, "Projected non-guaranteed addition at age 21 is INR 40,000 and is not guaranteed.", 140)
            )),
            OcrPageResult(3, "", listOf(
                line(3, "A waiting period of 90 days applies to the premium-waiver rider.", 20),
                line(3, "No premium-waiver rider benefit is payable following deliberate fraud. This exclusion does not reduce vested savings benefits.", 80)
            ))
        )
        val age18 = CoverageIntelligenceEngine.answer("How much is payable at age 18?", policy)
        val age21 = CoverageIntelligenceEngine.answer("How much is payable at age 21?", policy)
        val benefits = CoverageIntelligenceEngine.answer("What benefits does this policy provide?", policy)
        val comparison = CoverageIntelligenceEngine.answer("What is guaranteed and what is projected?", policy)
        val safeguards = CoverageIntelligenceEngine.answer("What are the exclusions and waiting periods?", policy)
        val maturity = CoverageIntelligenceEngine.answer("When does the policy mature?", policy)

        assertTrue(age18.summary.contains("INR 1,00,000") && !age18.summary.contains("INR 1,50,000"))
        assertTrue(age21.summary.contains("INR 1,50,000") && !age21.summary.contains("INR 1,00,000"))
        assertTrue(benefits.summary.contains("INR 1,00,000"))
        assertTrue(comparison.summary.contains("INR 1,00,000") && comparison.summary.contains("INR 1,50,000"))
        assertTrue(comparison.summary.contains("INR 40,000") && comparison.summary.contains("not guaranteed"))
        assertTrue(safeguards.passages.joinToString { it.category.name }, safeguards.passages.any { it.category == CoverageIntelligenceEngine.Category.EXCLUSION })
        assertTrue(safeguards.passages.any { it.category == CoverageIntelligenceEngine.Category.WAITING_PERIOD })
        assertTrue(maturity.summary.contains("15 June 2032"))
    }

    @Test fun evaluatorPaymentPhrasingKeepsPaidScheduledAndOutstandingSeparate() {
        val statement = listOf(OcrPageResult(1, "", listOf(
            line(1, "Premium paid INR 2,400 on 01 January 2027", 20),
            line(1, "Premium paid INR 2,600 on 01 April 2027", 80),
            line(1, "Verified outstanding premium: INR 2,500 due on 01 July 2027.", 140),
            line(1, "A future scheduled premium of INR 2,500 is due on 01 October 2027.", 200)
        )))
        val paid = CoverageIntelligenceEngine.answer("How much premium have I paid?", statement)
        val scheduled = CoverageIntelligenceEngine.answer("How much premium remains scheduled?", statement)
        val outstanding = CoverageIntelligenceEngine.answer("How much is outstanding?", statement)

        assertTrue(paid.summary.contains("₹5000"))
        assertTrue(scheduled.summary.contains("INR 2,500") && scheduled.summary.contains("01 October 2027"))
        assertTrue(outstanding.summary.contains("INR 2,500"))
        assertTrue(scheduled.passages.all { it.category == CoverageIntelligenceEngine.Category.PREMIUM_SCHEDULE })
        assertTrue(outstanding.passages.isNotEmpty())
        assertTrue(outstanding.passages.all { it.category == CoverageIntelligenceEngine.Category.OUTSTANDING_DUE })
    }

    @Test fun explicitPaidTotalDoesNotDoubleCountIndividualPaymentRows() {
        val statement = listOf(OcrPageResult(1, "", listOf(
            line(1, "Premium paid", 20),
            line(1, "01 January 2027 INR 2,500", 50),
            line(1, "Premium paid", 80),
            line(1, "01 April 2027 INR 2,500", 110),
            line(1, "Premium paid total: INR 5,000", 140)
        )))

        val paid = CoverageIntelligenceEngine.answer("How much premium have I paid?", statement)

        assertTrue(paid.summary.contains("₹5000"))
        assertTrue(!paid.summary.contains("₹10000"))
    }

    @Test fun outstandingEvidenceIsFilteredBeforeResultLimit() {
        val lines = buildList {
            repeat(8) { index -> add(line(1, "Future scheduled premium INR 2,500 due instalment ${index + 1}", 20 + index * 30)) }
            add(line(1, "Verified outstanding premium: INR 2,500 due on 01 July 2027", 300))
        }

        val answer = CoverageIntelligenceEngine.answer("How much is outstanding?", listOf(OcrPageResult(1, "", lines)))

        assertTrue(answer.summary.contains("INR 2,500"))
        assertTrue(answer.passages.isNotEmpty())
        assertTrue(answer.passages.all { it.category == CoverageIntelligenceEngine.Category.OUTSTANDING_DUE })
    }
}
