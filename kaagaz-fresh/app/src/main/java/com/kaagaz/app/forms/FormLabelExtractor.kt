package com.kaagaz.app.forms

import com.kaagaz.app.core.OcrPageResult

/**
 * Reads the field labels of a blank form from OCR: "Full name: ________", "Date of birth : __ / __ / ____",
 * or a short label line ending in ":" / "*". Instructions, headings and filled values are ignored.
 */
object FormLabelExtractor {
    private val blankLabel = Regex("^\\s*([A-Za-z][A-Za-z0-9 ()/&.'-]{1,48}?)\\s*\\*?\\s*[:：]\\s*(?:[_.\\-/ ]{0,}|\\[\\s*])\\s*$")
    private val underscoreLabel = Regex("^\\s*([A-Za-z][A-Za-z0-9 ()/&.'-]{1,48}?)\\s*\\*?\\s*_{3,}.*$")

    fun labels(pages: List<OcrPageResult>): List<String> = pages.flatMap { it.lines }.mapNotNull { line ->
        val text = line.text.trim()
        if (text.length > 70 || text.split(' ').size > 9) return@mapNotNull null
        (blankLabel.find(text) ?: underscoreLabel.find(text))?.groupValues?.get(1)?.trim()?.trimEnd('.', '-')
    }.filter { it.length >= 2 && it.any(Char::isLetter) }.distinctBy { it.lowercase() }

    /** A document is treated as a blank form when it has several empty labelled fields. */
    fun isBlankForm(pages: List<OcrPageResult>): Boolean = labels(pages).size >= 4
}
