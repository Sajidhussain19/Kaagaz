package com.kaagaz.app

import java.util.Locale

/** Plain bounding box. Android's Rect cannot be constructed in JVM unit tests, so geometry logic uses this type. */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
    val centerY: Int get() = (top + bottom) / 2
}

/** Reads the public Rect fields only (no Rect methods), so it also works against the Android unit-test stubs. */
fun OcrLineEvidence.ocrBox(): OcrBox? = boundingBox?.let { OcrBox(it.left, it.top, it.right, it.bottom) }

/** One complete sentence rebuilt from one or more OCR lines, with the exact lines it came from. */
data class OcrClause(
    val pageNumber: Int,
    val text: String,
    val lines: List<OcrLineEvidence>,
    val paragraphIndex: Int
)

/**
 * ML Kit returns visual lines, so a single policy sentence is often split across two or three lines
 * ("…on 15 June 2029 only if every" / "premium due through 01 April 2029 has been paid…").
 * This rebuilds paragraphs and then sentences, conservatively:
 * - a line continues the previous one only if the previous line does not end a sentence AND the text clearly
 *   continues (next line starts in lower case, or the previous line ends with a comma, hyphen or joining word);
 * - when both lines have boxes, they must also be left-aligned and vertically adjacent;
 * - anything uncertain stays a separate line, which is the previous behaviour.
 */
object ClauseAssembler {
    private const val MAX_PARAGRAPH_LINES = 12
    private val terminal = Regex("[.!?:;]$")
    private val joiningWords = setOf(
        "a", "an", "and", "any", "are", "as", "at", "be", "been", "by", "every", "for", "from", "has", "have", "if",
        "in", "is", "of", "on", "or", "per", "than", "that", "the", "through", "to", "under", "until", "when",
        "where", "which", "with", "all", "not", "no"
    )
    private val currencyWords = setOf("inr", "rs", "₹")
    private val abbreviations = setOf("no", "nos", "rs", "mr", "mrs", "ms", "dr", "st", "vs", "viz", "etc", "eg", "ie", "co", "ltd", "sr", "jr", "approx", "inc", "fig")

    fun clauses(page: OcrPageResult, boxOf: (OcrLineEvidence) -> OcrBox? = { it.ocrBox() }): List<OcrClause> =
        paragraphs(page.lines, boxOf).flatMapIndexed { index, paragraph -> sentences(page.pageNumber, paragraph, index) }

    fun paragraphs(lines: List<OcrLineEvidence>, boxOf: (OcrLineEvidence) -> OcrBox? = { it.ocrBox() }): List<List<OcrLineEvidence>> {
        val result = mutableListOf<MutableList<OcrLineEvidence>>()
        lines.filter { it.text.isNotBlank() }.forEach { line ->
            val current = result.lastOrNull()
            if (current != null && current.size < MAX_PARAGRAPH_LINES && continues(current.last(), line, boxOf)) current += line
            else result += mutableListOf(line)
        }
        return result
    }

    internal fun continues(previous: OcrLineEvidence, next: OcrLineEvidence, boxOf: (OcrLineEvidence) -> OcrBox?): Boolean {
        if (previous.pageNumber != next.pageNumber) return false
        val before = previous.text.trim()
        val after = next.text.trim()
        if (before.isEmpty() || after.isEmpty() || terminal.containsMatchIn(before)) return false
        val lastWord = before.substringAfterLast(' ').lowercase(Locale.ENGLISH).trimEnd('.')
        val textual = after.first().isLowerCase() || before.endsWith(",") || before.endsWith("-") ||
            lastWord in joiningWords || lastWord in currencyWords
        val a = boxOf(previous)
        val b = boxOf(next)
        if (a == null || b == null) return textual
        val lineHeight = maxOf(a.height, b.height, 1)
        val below = b.top >= a.top
        val adjacent = b.top - a.bottom <= lineHeight
        val aligned = kotlin.math.abs(b.left - a.left) <= lineHeight * 3 / 2
        // A line that runs to the right margin and does not end a sentence is a wrapped line even when the next
        // line starts with a capital ("…covered up to the Sum" / "Insured, subject to…").
        val runsToMargin = previous.sourceWidth > 0 && a.right >= previous.sourceWidth * 3 / 4 && after.first().isLetterOrDigit()
        return below && adjacent && aligned && (textual || runsToMargin)
    }

    private fun sentences(pageNumber: Int, paragraph: List<OcrLineEvidence>, paragraphIndex: Int): List<OcrClause> {
        val text = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        paragraph.forEach { line ->
            val part = line.text.trim()
            if (text.isNotEmpty()) {
                val hyphenWrap = text.endsWith("-") && part.firstOrNull()?.isLowerCase() == true
                if (!hyphenWrap) text.append(' ')
            }
            val start = text.length
            text.append(part)
            ranges += start until text.length
        }
        val joined = text.toString()
        val result = mutableListOf<OcrClause>()
        var start = 0
        boundaries(joined).plus(joined.length).forEach { end ->
            val raw = joined.substring(start, end)
            val sentence = raw.trim()
            if (sentence.isNotEmpty()) {
                val from = start + raw.indexOf(sentence.first())
                val to = from + sentence.length - 1
                val lines = paragraph.filterIndexed { index, _ -> ranges[index].first <= to && ranges[index].last >= from }
                result += OcrClause(pageNumber, sentence, lines.ifEmpty { paragraph }, paragraphIndex)
            }
            start = end
        }
        return result
    }

    /** Indexes just after a sentence-ending mark that is followed by a space and an upper-case letter or digit. */
    private fun boundaries(text: String): List<Int> {
        val result = mutableListOf<Int>()
        for (i in 0 until text.length - 2) {
            if (text[i] !in ".!?" || text[i + 1] != ' ') continue
            val next = text.substring(i + 2).trimStart().firstOrNull() ?: continue
            if (!next.isUpperCase() && !next.isDigit()) continue
            val token = text.substring(0, i).substringAfterLast(' ').replace(".", "").lowercase(Locale.ENGLISH)
            if (token in abbreviations) continue
            val soFar = text.substring(result.lastOrNull() ?: 0, i).trim()
            if (soFar.length <= 2 && soFar.all(Char::isDigit)) continue // numbered heading such as "3. Guaranteed…"
            result += i + 1
        }
        return result
    }
}
