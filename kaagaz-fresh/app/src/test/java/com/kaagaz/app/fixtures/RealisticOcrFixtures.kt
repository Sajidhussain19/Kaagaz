package com.kaagaz.app.fixtures

import com.kaagaz.app.core.OcrBox
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import java.util.IdentityHashMap

/**
 * OCR-like fixtures generated from output/pdf/kaagaz-fictional-child-savings-policy.pdf and
 * kaagaz-fictional-child-payment-statement.pdf and kaagaz-fictional-coverage-policy.pdf (generator: tmp/pdfs/create_realistic_ocr_fixture.py).
 *
 * Unlike the hand-written fixtures, paragraphs wrap exactly where the rendered page wraps (one OCR line per
 * visual line) and two-column tables are emitted column by column, which is what ML Kit produced on device.
 * Android's Rect cannot be constructed in JVM unit tests, so lines carry no Rect; [boxes] keeps the rendered
 * geometry (1240 px wide page) for tests that exercise the geometry-aware code paths.
 */
object RealisticOcrFixtures {
    class Line(val text: String, val box: OcrBox)

    val boxes: MutableMap<OcrLineEvidence, OcrBox> = IdentityHashMap()

    fun boxOf(line: OcrLineEvidence): OcrBox? = boxes[line]

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = OcrBox(left, top, right, bottom)
    private infix fun String.at(box: OcrBox) = Line(this, box)

    private fun page(number: Int, vararg lines: Line): OcrPageResult {
        val evidence = lines.map { line ->
            OcrLineEvidence(number, line.text, null, .95f, 1240, 1754).also { boxes[it] = line.box }
        }
        return OcrPageResult(number, lines.joinToString("\n") { it.text }, evidence)
    }

    val childPolicy: List<OcrPageResult> = listOf(
        page(1,
            "NIRMAL DEMO LIFE INSURANCE" at box(119, 122, 811, 166),
            "Little Steps Child Savings Plan - fictional policy schedule" at box(119, 181, 562, 199),
            "FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL POLICY, CUSTOMER, OR FINANCIAL PROMISE" at box(119, 246, 1063, 265),
            "Policy number" at box(131, 307, 275, 328),
            "Policyholder" at box(131, 361, 256, 382),
            "Life insured" at box(131, 415, 249, 436),
            "Date of birth" at box(131, 470, 254, 490),
            "Policy start date" at box(131, 524, 292, 545),
            "Maturity date" at box(131, 578, 261, 599),
            "Premium" at box(131, 632, 220, 653),
            "Premium frequency" at box(131, 686, 326, 707),
            "Regular due dates" at box(131, 740, 312, 761),
            "NDL-CHILD-2027-0042" at box(427, 307, 644, 328),
            "Asha Example" at box(427, 361, 561, 382),
            "Mira Example (fictional child)" at box(427, 415, 693, 436),
            "15 June 2011" at box(427, 470, 553, 490),
            "01 January 2027" at box(427, 524, 582, 545),
            "15 June 2032" at box(427, 578, 553, 599),
            "INR 2,500 quarterly" at box(427, 632, 607, 653),
            "Quarterly" at box(427, 686, 512, 707),
            "01 January, 01 April, 01 July, and 01 October each policy year" at box(427, 740, 1001, 761),
            "1. Purpose of this fictional plan" at box(119, 838, 551, 867),
            "This fictional savings and insurance plan provides the benefits stated below only when their eligibility" at box(119, 886, 1090, 908),
            "conditions are satisfied. It is created solely to test Kaagaz." at box(119, 918, 680, 940),
            "2. Premium schedule" at box(119, 971, 411, 1000),
            "A premium of INR 2,500 is scheduled quarterly. Premium due dates are 01 January, 01 April, 01 July," at box(119, 1020, 1100, 1042),
            "and 01 October of each policy year until 01 April 2032." at box(119, 1051, 645, 1073),
            "Fictional Kaagaz child-policy fixture - not valid insurance Page 1" at box(106, 1681, 1134, 1698),
        ),
        page(2,
            "LITTLE STEPS CHILD SAVINGS PLAN" at box(119, 122, 909, 166),
            "Benefits and eligibility conditions" at box(119, 181, 374, 199),
            "3. Guaranteed Education Benefit at Age 18" at box(119, 257, 707, 286),
            "Guaranteed Education Benefit of INR 1,00,000 is payable at age 18 on 15 June 2029 only if every" at box(119, 305, 1065, 327),
            "premium due through 01 April 2029 has been paid and the policy remains in force." at box(119, 336, 914, 358),
            "4. Guaranteed Maturity Benefit at Age 21" at box(119, 390, 680, 419),
            "Guaranteed Maturity Benefit of INR 1,50,000 is payable at age 21 on the maturity date, 15 June 2032," at box(119, 439, 1104, 460),
            "only if all scheduled premiums have been paid and the policy remains in force." at box(119, 470, 876, 492),
            "5. Projected Non-Guaranteed Addition" at box(119, 523, 647, 553),
            "Projected non-guaranteed addition at age 21 is INR 40,000. This projected benefit is an illustration only," at box(119, 572, 1121, 594),
            "is not guaranteed, and may be zero or vary with declared bonuses." at box(119, 603, 766, 625),
            "6. Total shown at age 21" at box(119, 657, 454, 686),
            "The guaranteed age-21 maturity benefit is INR 1,50,000. Any projected addition must be shown" at box(119, 705, 1042, 727),
            "separately and must not be described as guaranteed." at box(119, 736, 635, 758),
            "Fictional Kaagaz child-policy fixture - not valid insurance Page 2" at box(106, 1681, 1134, 1698),
        ),
        page(3,
            "LITTLE STEPS CHILD SAVINGS PLAN" at box(119, 122, 909, 166),
            "Waiting periods, exclusions, and claims" at box(119, 181, 428, 199),
            "7. Waiting Period" at box(119, 257, 355, 286),
            "A waiting period of 90 days from the policy start date applies to the premium-waiver rider. No" at box(119, 305, 1015, 327),
            "premium-waiver rider benefit is payable during this waiting period, except where the policy explicitly" at box(119, 336, 1078, 358),
            "states otherwise." at box(119, 368, 283, 390),
            "8. Exclusion" at box(119, 421, 289, 450),
            "No premium-waiver rider benefit is payable if the qualifying event results from deliberate fraud or a" at box(119, 470, 1068, 492),
            "materially false statement in the application. This exclusion does not reduce a guaranteed savings" at box(119, 501, 1066, 523),
            "benefit that has already vested under the policy terms." at box(119, 532, 644, 554),
            "9. Important distinction" at box(119, 586, 441, 615),
            "The education benefit and maturity benefit are separate guaranteed benefits with different ages, dates," at box(119, 634, 1111, 656),
            "amounts, and eligibility conditions. The projected addition is not guaranteed." at box(119, 666, 854, 687),
            "END OF FICTIONAL DEMONSTRATION POLICY" at box(119, 711, 541, 730),
            "Fictional Kaagaz child-policy fixture - not valid insurance Page 3" at box(106, 1681, 1134, 1698),
        ),
    )

    val childStatement: List<OcrPageResult> = listOf(
        page(1,
            "NIRMAL DEMO LIFE INSURANCE" at box(119, 122, 811, 166),
            "Fictional child-policy payment statement" at box(119, 181, 433, 199),
            "FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL PAYMENT RECORD" at box(119, 246, 818, 265),
            "Policy number" at box(131, 307, 275, 328),
            "Policyholder" at box(131, 361, 256, 382),
            "Statement date" at box(131, 415, 281, 436),
            "NDL-CHILD-2027-0042" at box(427, 307, 644, 328),
            "Asha Example" at box(427, 361, 561, 382),
            "15 August 2027" at box(427, 415, 572, 436),
            "Verified Payment History" at box(119, 478, 464, 507),
            "Status" at box(131, 540, 192, 560),
            "Premium paid" at box(131, 594, 253, 614),
            "Premium paid" at box(131, 649, 253, 668),
            "Payment date" at box(379, 540, 508, 560),
            "01 January 2027" at box(379, 594, 527, 614),
            "01 April 2027" at box(379, 649, 496, 668),
            "Amount" at box(627, 540, 702, 560),
            "INR 2,500" at box(627, 594, 716, 614),
            "INR 2,500" at box(627, 649, 716, 668),
            "Receipt" at box(828, 540, 900, 560),
            "DEMO-C001" at box(828, 594, 941, 614),
            "DEMO-C002" at box(828, 649, 941, 668),
            "Paid total" at box(119, 711, 250, 740),
            "Premium paid total: INR 5,000 from the two payment records listed above." at box(119, 759, 836, 781),
            "Verified Outstanding Premium" at box(119, 813, 538, 842),
            "Verified outstanding premium: INR 2,500 due on 01 July 2027. This amount is explicitly recorded as" at box(119, 861, 1082, 883),
            "outstanding on this fictional statement." at box(119, 893, 491, 915),
            "Future Premium Schedule" at box(119, 946, 480, 975),
            "A future scheduled premium of INR 2,500 is due on 01 October 2027. This scheduled premium is not" at box(119, 995, 1094, 1017),
            "currently outstanding as of the statement date." at box(119, 1026, 569, 1048),
            "Important distinction" at box(119, 1080, 409, 1109),
            "Paid premiums, verified outstanding premiums, and future scheduled premiums are different categories" at box(119, 1128, 1117, 1150),
            "and must not be combined." at box(119, 1159, 383, 1181),
            "Fictional Kaagaz child-policy fixture - not valid insurance Page 1" at box(106, 1681, 1134, 1698),
        ),
    )

    val coveragePolicy: List<OcrPageResult> = listOf(
        page(1,
            "KAAGAZ DEMO HEALTH SECURE" at box(119, 122, 867, 168),
            "Fictional health insurance policy schedule and wording" at box(119, 181, 547, 199),
            "FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL POLICY AND NOT VALID FOR ANY CLAIM" at box(172, 260, 1068, 279),
            "Insurer" at box(131, 325, 202, 346),
            "Policy Number" at box(131, 379, 277, 400),
            "Insured Person" at box(131, 433, 283, 454),
            "Premium Amount" at box(131, 488, 305, 508),
            "Policy Start Date" at box(131, 542, 297, 563),
            "Policy Expiry Date" at box(131, 596, 313, 617),
            "Sum Insured" at box(131, 650, 257, 671),
            "Kaagaz Demo Assurance Company" at box(415, 325, 746, 346),
            "KZG-HEALTH-2027-010A" at box(415, 379, 655, 400),
            "Neha Example" at box(415, 433, 551, 454),
            "INR 18,600" at box(415, 488, 520, 508),
            "01 January 2027" at box(415, 542, 570, 563),
            "31 December 2027" at box(415, 596, 592, 617),
            "INR 5,00,000" at box(415, 650, 537, 671),
            "1. Hospitalisation Benefit" at box(119, 754, 492, 785),
            "In-patient hospitalisation expenses for medically necessary treatment are covered up to the Sum" at box(119, 806, 1051, 828),
            "Insured, subject to this policy's waiting periods, exclusions, limits, and claim requirements." at box(119, 837, 988, 859),
            "2. Conditional Ambulance Benefit" at box(119, 892, 617, 923),
            "Emergency road ambulance charges are payable up to INR 2,000 per claim, provided the insured" at box(119, 944, 1061, 966),
            "person is admitted for a covered in-patient hospitalisation." at box(119, 975, 678, 997),
            "3. Disease and Condition Mentions" at box(119, 1029, 636, 1060),
            "Diabetes and hypertension are recorded in this fictional schedule for medical disclosure. Their" at box(119, 1081, 1026, 1103),
            "appearance in this section does not establish that treatment for either condition is covered." at box(119, 1112, 994, 1134),
            "Pre-existing diabetes may be eligible only after the waiting period below and remains subject to all other" at box(119, 1158, 1121, 1180),
            "policy terms." at box(119, 1190, 242, 1211),
            "Kaagaz fictional policy fixture - not valid insurance Page 1" at box(106, 1681, 1134, 1698),
        ),
        page(2,
            "KAAGAZ DEMO HEALTH SECURE" at box(119, 122, 867, 168),
            "Policy wording - limitations and claims" at box(119, 181, 417, 199),
            "4. Waiting Period" at box(119, 259, 372, 291),
            "A waiting period of 24 months of continuous coverage applies to pre-existing diabetes and" at box(119, 311, 989, 333),
            "hypertension. No benefit is payable for these conditions during that waiting period unless a written" at box(119, 343, 1065, 365),
            "endorsement states otherwise." at box(119, 374, 417, 396),
            "5. Exclusion" at box(119, 428, 301, 459),
            "Cosmetic treatment performed only to improve appearance is excluded and is not payable, except" at box(119, 480, 1066, 502),
            "reconstructive surgery that is medically necessary following a covered accident." at box(119, 511, 888, 533),
            "6. Limits and Sub-limits" at box(119, 565, 470, 597),
            "Room rent is limited to 1 percent of the Sum Insured per day. The ambulance benefit is capped at INR" at box(119, 618, 1106, 640),
            "2,000 per claim. These sub-limits do not increase the overall Sum Insured." at box(119, 649, 838, 671),
            "7. Claim Requirements" at box(119, 703, 456, 734),
            "Notify the fictional insurer within 48 hours of emergency admission. Submit the signed claim form," at box(119, 755, 1061, 777),
            "hospital bills, discharge summary, and payment receipts within 30 days after discharge." at box(119, 786, 964, 808),
            "8. Reading This Policy" at box(119, 840, 450, 872),
            "Benefits are payable only when the complete policy wording supports the claim. A disease name," at box(119, 893, 1057, 915),
            "example, or medical definition alone is not a promise of coverage." at box(119, 924, 755, 946),
            "END OF FICTIONAL DEMONSTRATION DOCUMENT" at box(390, 1029, 850, 1048),
            "Kaagaz fictional policy fixture - not valid insurance Page 2" at box(106, 1681, 1134, 1698),
        ),
    )
}
