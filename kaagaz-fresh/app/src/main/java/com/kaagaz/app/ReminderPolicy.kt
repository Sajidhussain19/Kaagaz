package com.kaagaz.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object ReminderPolicy {
    val allowedOffsets = setOf(30, 7, 1, 0)
    private val formats = listOf(
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH),
        DateTimeFormatter.ISO_LOCAL_DATE
    )

    data class Eligibility(val date: LocalDate?, val allowed: Boolean, val reason: String, val sourceLabel: String)

    fun evaluate(field: InsuranceField?, today: LocalDate = LocalDate.now()): Eligibility {
        if (field == null || field.displayedValue.isNullOrBlank()) return Eligibility(null, false, "No policy expiry date was found.", "Not found")
        val date = parse(field.displayedValue!!) ?: return Eligibility(null, false, "The expiry date format could not be understood safely.", source(field))
        if (date.isBefore(today)) return Eligibility(date, false, "This deadline has already passed.", source(field))
        val uncertain = field.status != VerificationStatus.SUPPORTED || field.isEdited
        if (uncertain && !field.isUserConfirmed) return Eligibility(date, false, "Review and confirm this date before setting a reminder.", source(field))
        return Eligibility(date, true, "Ready to schedule locally.", source(field))
    }

    fun parse(value: String): LocalDate? {
        val clean = value.trim().replace(Regex("(?i)(st|nd|rd|th)"), "")
        for (format in formats) try { return LocalDate.parse(clean, format) } catch (_: DateTimeParseException) { }
        return null
    }

    fun source(field: InsuranceField) = when {
        field.isEdited && field.isUserConfirmed -> "User-confirmed correction"
        field.isUserConfirmed -> "User confirmed"
        field.status == VerificationStatus.SUPPORTED -> "Supported by document text"
        else -> "Needs review"
    }

    fun workName(documentId: String, deadlineType: String, offset: Int) = "reminder_${documentId}_${deadlineType}_$offset"

    fun selectedOffsets(available: List<Int>, checked: List<Boolean>): Set<Int> =
        available.zip(checked).filter { (_, selected) -> selected }.map { (offset, _) -> offset }.toSet()
}
