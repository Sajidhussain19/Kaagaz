package com.kaagaz.app.core

import com.kaagaz.app.fixtures.RealisticOcrFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClauseAssemblerTest {
    private fun line(text: String, page: Int = 1) = OcrLineEvidence(page, text, null, .95f, 1240, 1754)
    private fun page(vararg lines: OcrLineEvidence) = OcrPageResult(1, "", lines.toList())

    @Test fun wrappedSentenceIsRebuiltWithBothSourceLines() {
        val first = line("Guaranteed Education Benefit of INR 1,00,000 is payable at age 18 on 15 June 2029 only if every")
        val second = line("premium due through 01 April 2029 has been paid and the policy remains in force.")
        val clauses = ClauseAssembler.clauses(page(first, second))

        assertEquals(1, clauses.size)
        assertTrue(clauses.single().text.contains("only if every premium due through 01 April 2029"))
        assertEquals(listOf(first, second), clauses.single().lines)
    }

    @Test fun sentencesInsideAParagraphMapToTheirOwnLines() {
        val p = RealisticOcrFixtures.childPolicy[2]
        val clauses = ClauseAssembler.clauses(p, RealisticOcrFixtures::boxOf)
        val waiting = clauses.single { it.text.startsWith("A waiting period of 90 days") }
        val noBenefit = clauses.single { it.text.startsWith("No premium-waiver rider benefit is payable during") }

        assertEquals(1, waiting.lines.size)
        assertEquals(3, noBenefit.lines.size)
        assertTrue(noBenefit.text.endsWith("except where the policy explicitly states otherwise."))
    }

    @Test fun columnOrderedTableCellsAreNeverJoined() {
        val p = RealisticOcrFixtures.childPolicy[0]
        listOf<(OcrLineEvidence) -> OcrBox?>({ null }, RealisticOcrFixtures::boxOf).forEach { boxes ->
            val paragraphs = ClauseAssembler.paragraphs(p.lines, boxes)
            listOf("Policy number", "Policyholder", "Life insured", "Asha Example", "INR 2,500 quarterly", "Quarterly").forEach { cell ->
                assertEquals(cell, listOf(cell), paragraphs.single { para -> para.any { it.text == cell } }.map { it.text })
            }
        }
    }

    @Test fun geometryVetoesTextThatOnlyLooksContinuous() {
        val left = line("Benefit payable to the nominee and")
        val farRight = line("the policy remains in force.")
        val farBelow = line("conditions apply as stated.")
        val boxes = mapOf(left to OcrBox(100, 100, 600, 122), farRight to OcrBox(700, 100, 1100, 122), farBelow to OcrBox(100, 400, 500, 422))
        val boxOf: (OcrLineEvidence) -> OcrBox? = { boxes[it] }

        assertEquals(2, ClauseAssembler.paragraphs(listOf(left, farRight), boxOf).size)
        assertEquals(2, ClauseAssembler.paragraphs(listOf(left, farBelow), boxOf).size)
        assertEquals(1, ClauseAssembler.paragraphs(listOf(left, farRight), { null }).size)
    }

    @Test fun headingsAbbreviationsAndAmountsDoNotCreateFalseSentences() {
        val clauses = ClauseAssembler.clauses(page(
            line("3. Guaranteed Education Benefit at Age 18"),
            line("Rs. 500 is payable per day. No benefit is payable for cosmetic care.")
        ))
        assertEquals(listOf("3. Guaranteed Education Benefit at Age 18", "Rs. 500 is payable per day.", "No benefit is payable for cosmetic care."), clauses.map { it.text })
    }

    @Test fun completeLinesAndLabelValueRowsStaySeparate() {
        val paragraphs = ClauseAssembler.paragraphs(listOf(
            line("Premium paid"), line("01 January 2027 INR 2,500"),
            line("Cosmetic treatment is excluded and not payable."), line("Room rent is capped at 1% of Sum Insured per day.")
        ))
        assertEquals(4, paragraphs.size)
    }
}
