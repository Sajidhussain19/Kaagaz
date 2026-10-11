"""FICTIONAL Mission OS demo documents. Text mirrors app/src/test/.../MissionOsFixtures.kt.

Planted problems (detected by the app from OCR, not hard-coded): graduation year 2024 (resume) vs 2023
(certificate), date of birth 14 March 2002 vs 14 May 2002 (transcript), and one bill saved twice.
"""
from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer

OUT = Path(__file__).resolve().parents[2] / "output" / "pdf" / "mission-os"
OUT.mkdir(parents=True, exist_ok=True)
ink = colors.HexColor("#17221E"); muted = colors.HexColor("#5B6962"); accent = colors.HexColor("#1F4E79")
ss = getSampleStyleSheet()
H = ParagraphStyle("H", parent=ss["Heading1"], fontName="Helvetica-Bold", fontSize=19, leading=23, textColor=accent, spaceAfter=4)
S = ParagraphStyle("S", parent=ss["Heading2"], fontName="Helvetica-Bold", fontSize=13, leading=17, textColor=ink, spaceBefore=10, spaceAfter=4)
B = ParagraphStyle("B", parent=ss["BodyText"], fontName="Helvetica", fontSize=11.5, leading=17, textColor=ink, spaceAfter=3)
W = ParagraphStyle("W", parent=ss["BodyText"], fontName="Helvetica-Bold", fontSize=8.5, leading=11, textColor=colors.HexColor("#A74236"), spaceAfter=10)


def footer(canvas, doc):
    canvas.saveState(); canvas.setFont("Helvetica", 8); canvas.setFillColor(muted)
    canvas.drawString(18 * mm, 10 * mm, "FICTIONAL Kaagaz demo document - not a real person or record")
    canvas.drawRightString(192 * mm, 10 * mm, f"Page {doc.page}"); canvas.restoreState()


def build(name, story):
    path = OUT / name
    SimpleDocTemplate(str(path), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=18 * mm, bottomMargin=20 * mm,
                      title=name, author="Kaagaz Demo").build(story, onFirstPage=footer, onLaterPages=footer)
    print(path)


def lines(*items):
    return [Paragraph(t, B) for t in items]


warn = Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL PERSON, INSTITUTION OR BILL", W)

build("aarav-resume.pdf", [Paragraph("AARAV MEHTA - RESUME (FICTIONAL DEMO)", H), warn,
    *lines("Full name: Aarav Mehta", "Date of birth: 14 March 2002", "Email: aarav.mehta.demo@example.com", "Phone: +91 98765 43210"),
    Paragraph("Professional experience", S), *lines("Current employer: Nimbus Labs", "Designation: Android Engineer"),
    Paragraph("Education", S), *lines("Degree: Bachelor of Technology in Computer Science", "University: Kaveri Institute of Technology",
                                      "Year of graduation: 2024", "CGPA: 8.4"),
    Paragraph("Skills", S), *lines("Skills: Kotlin, Android, SQLite, Jetpack")])

build("degree-certificate.pdf", [Paragraph("KAVERI INSTITUTE OF TECHNOLOGY (FICTIONAL)", H), warn, Paragraph("DEGREE CERTIFICATE", S),
    *lines("This is to certify that Aarav Mehta has been awarded the degree of Bachelor of Technology in Computer Science in the year 2023."),
    Spacer(1, 6 * mm), *lines("Name: Aarav Mehta", "Year of graduation: 2023", "CGPA: 8.40")])

build("transcript.pdf", [Paragraph("KAVERI INSTITUTE OF TECHNOLOGY (FICTIONAL)", H), warn, Paragraph("CONSOLIDATED MARK SHEET / TRANSCRIPT", S),
    *lines("Name of the candidate: Aarav Mehta", "Date of birth: 14 May 2002", "Programme: Bachelor of Technology in Computer Science",
           "Semester 8 SGPA 8.6", "Final CGPA: 8.4")])

build("scholarship-application-form.pdf", [Paragraph("NATIONAL MERIT SCHOLARSHIP BOARD (FICTIONAL)", H), warn,
    Paragraph("SCHOLARSHIP APPLICATION FORM", S),
    *lines("Please fill in every field in block letters.",
           "Applicant name: ____________________", "Date of birth: ____________________", "Email address: ____________________",
           "Mobile number: ____________________", "University / college: ____________________", "Degree programme: ____________________",
           "Year of passing: ____________________", "CGPA: ____________________", "Current employer: ____________________",
           "Bank account number: ____________________", "Net banking password: ____________________", "Signature: ____________________")])

build("personal-loan-statement.pdf", [Paragraph("SURYA DEMO FINANCE LTD (FICTIONAL)", H), warn, Paragraph("PERSONAL LOAN STATEMENT", S),
    *lines("Customer name: Aarav Mehta", "Loan account number: SDF-PL-2026-4417", "Loan amount: INR 1,00,000.00",
           "Interest rate: 13.5% per annum", "Tenure: 12 months", "EMI amount: INR 8,958.00",
           "EMI due date: 18 October 2026", "Outstanding principal: INR 83,120.00", "EMIs paid: 2 of 12")])

build("internet-bill.pdf", [Paragraph("SKYLINK FIBRE (FICTIONAL)", H), warn, Paragraph("INTERNET BILL", S),
    *lines("Customer name: Aarav Mehta", "Account number: SKY-7781204", "Bill number: SKY-2026-1007", "Bill date: 07 October 2026",
           "Amount due: INR 799.00", "Due date: 28 October 2026")])

for name, title in [("electricity-bill.pdf", "ELECTRICITY BILL"), ("electricity-bill-copy.pdf", "ELECTRICITY BILL - DUPLICATE COPY")]:
    build(name, [Paragraph("DEMO POWER DISTRIBUTION LTD (FICTIONAL)", H), warn, Paragraph(title, S),
        *lines("Customer name: Aarav Mehta", "Consumer number: 4410029381", "Bill number: DPD-2026-0915", "Bill date: 15 September 2026",
               "Units consumed: 312", "Amount due: INR 2,340.00", "Due date: 20 October 2026",
               "Pay before the due date to avoid a late fee of INR 50.")])
