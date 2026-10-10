package com.kaagaz.app.fixtures

import com.kaagaz.app.audit.AuditDocument
import com.kaagaz.app.core.OcrLineEvidence
import com.kaagaz.app.core.OcrPageResult
import com.kaagaz.app.knowledge.FactExtractor
import com.kaagaz.app.rag.Chunker

/**
 * FICTIONAL test documents for Mission OS. The same text is rendered into PDFs by
 * tmp/pdfs/create_mission_os_fixtures.py. Planted problems: graduation year 2024 vs 2023, date of birth
 * 14 March vs 14 May 2002, and the same electricity bill saved twice.
 */
object MissionOsFixtures {
    fun page(number: Int, vararg lines: String) = OcrPageResult(number, lines.joinToString("\n"),
        lines.map { OcrLineEvidence(number, it, null, .95f, 1240, 1754) })

    // Lines below are exactly what ML Kit returned on the test phone (Android 16), including its quirks:
    // a space before "@", the certificate sentence wrapped mid-phrase, title lines and a bare "Skills" heading.
    val resume = listOf(page(1,
        "AARAV MEHTA - RESUME (FICTIONAL DEMO)",
        "FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL PERSON, INSTITUTION OR BILL",
        "Full name: Aarav Mehta",
        "Date of birth: 14 March 2002",
        "Email: aarav.mehta.demo @example.com",
        "Phone: +91 98765 43210",
        "Professional experience",
        "Current employer: Nimbus Labs",
        "Designation: Android Engineer",
        "Education",
        "Degree: Bachelor of Technology in Computer Science",
        "University: Kaveri Institute of Technology",
        "Year of graduation: 2024",
        "CGPA: 8.4",
        "Skills",
        "Skills: Kotlin, Android, SQLite, Jetpack",
        "FICTIONAL Kaagaz demo document - not a real person or record",
        "Page 1"
    ))

    val certificate = listOf(page(1,
        "KAVERI INSTITUTE OF TECHNOLOGY (FICTIONAL)",
        "FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL PERSON, INSTITUTION OR BILL",
        "DEGREE CERTIFICATE",
        "This is to certify that Aarav Mehta has been awarded the degree of Bachelor of Technology in",
        "Computer Science in the year 2023.",
        "Name: Aarav Mehta",
        "Year of graduation: 2023",
        "CGPA: 8.40",
        "FICTIONAL Kaagaz demo document - not a real person or record",
        "Page 1"
    ))

    val transcript = listOf(page(1,
        "KAVERI INSTITUTE OF TECHNOLOGY (FICTIONAL)",
        "CONSOLIDATED MARK SHEET / TRANSCRIPT",
        "Name of the candidate: Aarav Mehta",
        "Date of birth: 14 May 2002",
        "Programme: Bachelor of Technology in Computer Science",
        "Semester 8 SGPA 8.6",
        "Final CGPA: 8.4"
    ))

    private fun bill(title: String) = listOf(page(1,
        "DEMO POWER DISTRIBUTION LTD (FICTIONAL)",
        title,
        "Customer name: Aarav Mehta",
        "Consumer number: 4410029381",
        "Bill number: DPD-2026-0915",
        "Bill date: 15 September 2026",
        "Units consumed: 312",
        "Amount due: INR 2,340.00",
        "Due date: 20 October 2026",
        "Pay before the due date to avoid a late fee of INR 50."
    ))

    val billOriginal = bill("ELECTRICITY BILL")
    val billCopy = bill("ELECTRICITY BILL - DUPLICATE COPY")

    fun docs(): List<AuditDocument> = listOf(
        AuditDocument("resume", "aarav-resume.pdf", FactExtractor.classify(resume), resume),
        AuditDocument("cert", "degree-certificate.pdf", FactExtractor.classify(certificate), certificate),
        AuditDocument("transcript", "transcript.pdf", FactExtractor.classify(transcript), transcript),
        AuditDocument("bill1", "electricity-bill.pdf", FactExtractor.classify(billOriginal), billOriginal),
        AuditDocument("bill2", "electricity-bill-copy.pdf", FactExtractor.classify(billCopy), billCopy)
    )

    fun facts(docs: List<AuditDocument> = docs()) = docs.flatMap { FactExtractor.extract(it.id, it.name, it.pages) }

    fun chunks(docs: List<AuditDocument> = docs()) = docs.flatMap { Chunker.chunks(it.id, it.name, it.pages) { null } }
}
