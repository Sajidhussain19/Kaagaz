package com.kaagaz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageValidationMatrixTest {
    private fun line(page: Int, text: String) = OcrLineEvidence(page, text, null, .94f, 1240, 1754)

    private val policy = listOf(
        OcrPageResult(1, "", listOf(
            line(1, "Hospitalisation expenses are covered up to the Sum Insured, subject to policy conditions."),
            line(1, "Dengue is mentioned in an example of a medical disclosure."),
            line(1, "This mention does not establish coverage for dengue."),
            line(1, "Pre-existing diabetes is covered only after a waiting period of 24 months."),
            line(1, "Cosmetic treatment is excluded and not payable."),
            line(1, "Emergency ambulance benefit is payable up to INR 2,000 per claim."),
            line(1, "Policy Start Date: 01 January 2027"),
            line(1, "Policy Expiry Date: 31 December 2027")
        )),
        OcrPageResult(2, "", listOf(
            line(2, "Room rent is capped at 1% of Sum Insured per day."),
            line(2, "Submit the claim form and discharge summary within 30 days."),
            line(2, "Cancer is not covered during the initial 90 day waiting period.")
        ))
    )

    private val statement = listOf(OcrPageResult(1, "", listOf(
        line(1, "Premium paid INR 1,200 on 01 January 2027 Receipt DEMO-R001"),
        line(1, "Payment received INR 1,300 on 01 February 2027 Receipt DEMO-R002"),
        line(1, "A future scheduled premium of INR 1,500 is due on 01 April 2027."),
        line(1, "A scheduled premium is not proof that the amount is currently outstanding."),
        line(1, "Verified outstanding premium: INR 500. Balance due date: 20 March 2027."),
        line(1, "A guaranteed maturity benefit of INR 1,00,000 is payable on 31 December 2036 only if all scheduled premiums are paid and the policy remains in force."),
        line(1, "The projected maturity value is INR 1,25,000. This illustration is not guaranteed."),
        line(1, "Policy Start Date: 01 January 2027"),
        line(1, "Policy Expiry Date: 31 December 2036"),
        line(1, "Premium Due Date: 01 April 2027"),
        line(1, "Maturity Date: 31 December 2036")
    )))

    private data class Case(val question: String, val pages: List<OcrPageResult>, val expected: String, val expectedPage: Int?, val assertion: (CoverageIntelligenceEngine.Answer) -> Boolean)

    @Test fun realisticQuestionMatrixIsGroundedAndConcise() {
        val cases = listOf(
            Case("What does this policy cover?", policy, "Explicit document-supported benefit", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.BENEFIT || p.category == CoverageIntelligenceEngine.Category.CONDITIONAL } },
            Case("What benefits do I get?", policy, "Document-supported benefits only", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.BENEFIT } },
            Case("What exclusions are listed?", policy, "Cosmetic treatment exclusion", 1) { it.passages.all { p -> p.category == CoverageIntelligenceEngine.Category.EXCLUSION } },
            Case("Are there waiting periods?", policy, "24-month and 90-day waiting wording", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.WAITING_PERIOD } },
            Case("What conditional benefit is described?", policy, "Conditional coverage, not unconditional", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.CONDITIONAL } },
            Case("Is dengue covered?", policy, "Mention only; coverage unable to determine", 1) { it.summary.contains("do not establish", true) },
            Case("Does this policy cover diabetes?", policy, "Conditional after 24 months", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.WAITING_PERIOD } },
            Case("Does this policy cover tuberculosis?", policy, "Missing evidence", null) { it.kind == CoverageIntelligenceEngine.AnswerKind.NOT_FOUND },
            Case("What limits or sub-limits apply?", policy, "Room-rent and benefit caps", 2) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.LIMIT && p.evidence.any { e -> e.pageNumber == 2 } } },
            Case("What are the claim requirements?", policy, "Claim form/discharge summary", 2) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.CLAIM_REQUIREMENT } },
            Case("How much have I paid?", statement, "Verified total ₹2500", 1) { it.summary.contains("₹2500") },
            Case("What future premium is scheduled?", statement, "INR 1,500 schedule, not outstanding", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.PREMIUM_SCHEDULE } },
            Case("How much premium remains?", statement, "Verified outstanding INR 500", 1) { it.summary.contains("500") },
            Case("What guaranteed maturity benefit will I receive?", statement, "Guaranteed INR 1,00,000 with conditions", 1) { it.summary.contains("guaranteed", true) && it.passages.none { p -> p.category == CoverageIntelligenceEngine.Category.PROJECTED_BENEFIT } },
            Case("What projected benefit is shown?", statement, "Projected INR 1,25,000; not guaranteed", 1) { it.summary.contains("not guaranteed", true) && it.passages.all { p -> p.category == CoverageIntelligenceEngine.Category.PROJECTED_BENEFIT } },
            Case("When will I receive the benefit?", statement, "31 Dec 2036, amount and eligibility clause", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.MATURITY } },
            Case("What important dates should I know?", statement, "Supported start, expiry, due, maturity dates", 1) { it.passages.any { p -> p.category == CoverageIntelligenceEngine.Category.IMPORTANT_DATE } }
        )

        cases.forEachIndexed { index, case ->
            val answer = CoverageIntelligenceEngine.answer(case.question, case.pages)
            val actualPages = answer.passages.flatMap { it.evidence }.map { it.pageNumber }.distinct().sorted()
            val concise = answer.summary.split(Regex("[.!?]")).count { it.isNotBlank() } <= 2
            println("VALIDATION|${index + 1}|${case.question}|${case.expected}|${answer.summary}|${actualPages.joinToString(",")}|$concise")
            assertTrue("Wrong answer for: ${case.question} -> ${answer.summary}", case.assertion(answer))
            assertTrue("Answer is not concise: ${case.question}", concise)
            case.expectedPage?.let { expected -> assertTrue("Missing page $expected evidence for ${case.question}", actualPages.contains(expected)) }
            answer.passages.forEach { passage ->
                assertTrue("Displayed clause lacks evidence for ${case.question}", passage.evidence.isNotEmpty())
                assertTrue("Evidence lacks usable page for ${case.question}", passage.evidence.all { it.pageNumber > 0 })
            }
        }
        assertEquals(17, cases.size)
    }
}
