from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output" / "pdf"
OUT.mkdir(parents=True, exist_ok=True)
HEALTH = OUT / "kaagaz-eval2-health-policy.pdf"
CHILD = OUT / "kaagaz-eval2-child-savings-policy.pdf"

green = colors.HexColor("#176B52")
ink = colors.HexColor("#17221E")
muted = colors.HexColor("#5B6962")
line = colors.HexColor("#D9DED9")
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name="BrandX", parent=styles["Heading1"], fontName="Helvetica-Bold", fontSize=21, leading=25, textColor=green, spaceAfter=4))
styles.add(ParagraphStyle(name="TitleX", parent=styles["Heading2"], fontName="Helvetica-Bold", fontSize=14, leading=18, textColor=ink, spaceBefore=10, spaceAfter=6))
styles.add(ParagraphStyle(name="BodyX", parent=styles["BodyText"], fontName="Helvetica", fontSize=10.5, leading=15, textColor=ink, spaceAfter=7))
styles.add(ParagraphStyle(name="SmallX", parent=styles["BodyText"], fontName="Helvetica", fontSize=8.5, leading=11, textColor=muted))
styles.add(ParagraphStyle(name="WarnX", parent=styles["BodyText"], fontName="Helvetica-Bold", fontSize=9, leading=12, textColor=colors.HexColor("#A74236"), backColor=colors.HexColor("#FCEBE8"), borderPadding=7, spaceAfter=10))


def footer_for(label):
    def footer(canvas, doc):
        canvas.saveState()
        canvas.setStrokeColor(line); canvas.line(18*mm, 15*mm, 192*mm, 15*mm)
        canvas.setFont("Helvetica", 8); canvas.setFillColor(muted)
        canvas.drawString(18*mm, 10*mm, label)
        canvas.drawRightString(192*mm, 10*mm, f"Page {doc.page}")
        canvas.restoreState()
    return footer


def schedule(rows, widths=(50, 110)):
    table = Table(rows, colWidths=[w*mm for w in widths], hAlign="LEFT")
    table.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (0,-1), colors.HexColor("#E5F1EB")),
        ("FONTNAME", (0,0), (0,-1), "Helvetica-Bold"), ("FONTNAME", (1,0), (1,-1), "Helvetica"),
        ("GRID", (0,0), (-1,-1), .5, line), ("FONTSIZE", (0,0), (-1,-1), 10),
        ("VALIGN", (0,0), (-1,-1), "TOP"), ("TOPPADDING", (0,0), (-1,-1), 7), ("BOTTOMPADDING", (0,0), (-1,-1), 7),
    ]))
    return table


def grid(rows, widths):
    table = Table(rows, colWidths=[w*mm for w in widths], hAlign="LEFT")
    table.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (-1,0), green), ("TEXTCOLOR", (0,0), (-1,0), colors.white),
        ("FONTNAME", (0,0), (-1,0), "Helvetica-Bold"), ("GRID", (0,0), (-1,-1), .5, line),
        ("FONTSIZE", (0,0), (-1,-1), 9.5), ("TOPPADDING", (0,0), (-1,-1), 7), ("BOTTOMPADDING", (0,0), (-1,-1), 7),
    ]))
    return table


def page_head(brand, sub):
    return [Paragraph(brand, styles["BrandX"]), Paragraph(sub, styles["SmallX"]), Spacer(1, 5*mm)]


def section(title, *sentences):
    return [Paragraph(title, styles["TitleX"])] + [Paragraph(s, styles["BodyX"]) for s in sentences]


def build(path, title, footer_label, story):
    doc = SimpleDocTemplate(str(path), pagesize=A4, leftMargin=18*mm, rightMargin=18*mm, topMargin=17*mm, bottomMargin=20*mm,
                            title=title, author="Kaagaz Demo")
    footer = footer_for(footer_label)
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print(path)


# ---------------- Health insurance policy ----------------
HB = "SURAKSHA DEMO HEALTH PLUS"
health = page_head(HB, "Fictional comprehensive health insurance policy schedule and wording") + [
    Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL POLICY, PERSON, OR INSURER", styles["WarnX"]),
    schedule([
        ["Insurer", "Suraksha Demo Health Insurance Ltd"], ["Policy Number", "SDH-HEALTH-2026-7731"],
        ["Insured Person", "Ravi Example"], ["Premium Amount", "INR 24,500"],
        ["Policy Start Date", "01 April 2026"], ["Policy Expiry Date", "31 March 2027"],
        ["Sum Insured", "INR 10,00,000"], ["Plan name", "Health Plus Gold (fictional)"],
    ]),
    *section("1. About this fictional policy",
             "This fictional policy explains its cover, its rules and its claim steps.",
             "Read every section together, because one clause can limit another."),
    PageBreak(),
    *page_head(HB, "Major illness cover"),
    *section("2. Cancer",
             "Cancer treatment, including chemotherapy, radiotherapy and surgery, is covered up to INR 10,00,000, subject to the 90-day major illness waiting period."),
    *section("3. Heart attack",
             "Heart attack treatment, including angioplasty and bypass surgery, is covered up to INR 10,00,000, subject to the 90-day major illness waiting period."),
    *section("4. Stroke",
             "Stroke treatment, including hospital care and rehabilitation for up to 30 days, is covered up to INR 5,00,000, subject to the 90-day major illness waiting period."),
    *section("5. Kidney failure",
             "Kidney failure treatment, including dialysis and kidney transplant, is covered up to INR 3,00,000 per policy year, subject to the 90-day major illness waiting period."),
    *section("6. Hospitalisation",
             "In-patient hospitalisation for other medically necessary treatment is covered up to the Sum Insured, subject to the waiting periods and exclusions in this policy."),
    PageBreak(),
    *page_head(HB, "Waiting periods and pre-existing conditions"),
    *section("7. Major illness waiting period",
             "A waiting period of 90 days applies to major illnesses, counted from the policy start date."),
    *section("8. Pre-existing conditions",
             "A waiting period of 36 months applies to pre-existing conditions, such as declared diabetes and hypertension.",
             "Declared pre-existing diabetes and hypertension are covered only after completion of the 36-month waiting period."),
    *section("9. Specified surgeries",
             "A waiting period of 24 months applies to cataract and hernia surgery."),
    PageBreak(),
    *page_head(HB, "Exclusions"),
    *section("10. What is not covered",
             "Undeclared pre-existing conditions are excluded and not payable.",
             "Cosmetic or plastic surgery is excluded unless it is medically necessary after an accident.",
             "Treatment arising from alcohol or drug abuse is not covered.",
             "Self-inflicted injury is excluded and not payable.",
             "Treatment taken outside India is not covered."),
    PageBreak(),
    *page_head(HB, "Limits, co-pay, room rent and claims"),
    *section("11. Room rent and ICU limits",
             "Room rent is limited to INR 5,000 per day for a single private room.",
             "ICU charges are limited to INR 10,000 per day."),
    *section("12. Co-pay",
             "Co-pay limit of 10 percent per claim: the insured person pays 10 percent of every approved claim and the insurer pays the rest up to the Sum Insured."),
    *section("13. Ambulance",
             "Road ambulance charges are payable up to INR 3,000 per claim."),
    *section("14. Claim process",
             "Notify the insurer within 24 hours of an emergency admission.",
             "Submit the signed claim form, discharge summary, original bills and test reports within 30 days of discharge.",
             "Cashless claims at a network hospital need pre-authorisation from the insurer."),
    Spacer(1, 6*mm), Paragraph("END OF FICTIONAL DEMONSTRATION POLICY", styles["WarnX"]),
]
build(HEALTH, "Kaagaz Eval 2 Fictional Health Policy", "Fictional Kaagaz health-policy demo - not valid insurance", health)

# ---------------- Child insurance / savings policy ----------------
CB = "NIRMAL DEMO LIFE INSURANCE"
child = page_head(CB, "Bright Future Child Savings Plan - fictional policy schedule") + [
    Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL POLICY, CUSTOMER, OR FINANCIAL PROMISE", styles["WarnX"]),
    schedule([
        ["Policy number", "NDL-CHILD-2025-0108"], ["Policyholder", "Kavya Example"],
        ["Life insured", "Aarav Example (fictional child)"], ["Date of birth", "10 March 2010"],
        ["Policy start date", "01 April 2025"], ["Maturity date", "10 March 2031"],
        ["Premium", "INR 6,000 quarterly"], ["Premium frequency", "Quarterly"],
        ["Regular due dates", "01 January, 01 April, 01 July, and 01 October each policy year"],
    ]),
    *section("1. Child age milestones",
             "Aarav turns age 18 on 10 March 2028 and age 21 on 10 March 2031.",
             "The plan pays the education benefit at age 18 and the final benefit at age 21."),
    PageBreak(),
    *page_head(CB, "Guaranteed benefits"),
    *section("2. Guaranteed Education Benefit at Age 18",
             "Guaranteed Education Benefit of INR 2,00,000 is payable at age 18 on 10 March 2028 only if every premium due through 01 January 2028 has been paid and the policy remains in force."),
    *section("3. Guaranteed Maturity Benefit at Age 21",
             "Guaranteed Maturity Benefit of INR 3,00,000 is payable at age 21 on 10 March 2031 only if all scheduled premiums have been paid and the policy remains in force."),
    *section("4. Premium waiver",
             "If the policyholder dies during the policy term, all future premiums are waived and the guaranteed benefits remain payable to the child, subject to the exclusion on page 4."),
    PageBreak(),
    *page_head(CB, "Projected (non-guaranteed) benefits"),
    *section("5. Projected Addition at Age 21",
             "Projected non-guaranteed addition at age 21 is INR 75,000.",
             "This projected value is an illustration only, is not guaranteed, and may be zero."),
    *section("6. Guaranteed versus projected",
             "Only the two benefits on page 2 are guaranteed.",
             "The projected addition must always be shown separately as non-guaranteed."),
    *section("7. Information not stated in this document",
             "This document does not state a surrender value, a loan value, or a declared bonus rate."),
    PageBreak(),
    *page_head(CB, "Premium payment history"),
    Paragraph("Verified Payment History", styles["TitleX"]),
    grid([
        ["Status", "Payment date", "Amount", "Receipt"],
        ["Premium paid", "01 April 2025", "INR 6,000", "DEMO-B001"],
        ["Premium paid", "01 July 2025", "INR 6,000", "DEMO-B002"],
        ["Premium paid", "01 October 2025", "INR 6,000", "DEMO-B003"],
        ["Premium paid", "01 January 2026", "INR 6,000", "DEMO-B004"],
        ["Premium paid", "01 April 2026", "INR 6,000", "DEMO-B005"],
        ["Premium paid", "01 July 2026", "INR 6,000", "DEMO-B006"],
    ], (42, 42, 34, 42)),
    *section("Paid total",
             "Premium paid total: INR 36,000 from the six payment records listed above, as of 10 October 2026."),
    *section("Verified Outstanding Premium",
             "Verified outstanding premium: INR 6,000 due on 01 October 2026 has not been received."),
    *section("Future Premium Schedule",
             "The next scheduled premium of INR 6,000 is due on 01 January 2027."),
    *section("8. Exclusion",
             "No premium waiver rider benefit is payable if the claim results from fraud or a false statement in the application."),
    Spacer(1, 6*mm), Paragraph("END OF FICTIONAL DEMONSTRATION POLICY", styles["WarnX"]),
]
build(CHILD, "Kaagaz Eval 2 Fictional Child Savings Policy", "Fictional Kaagaz child-policy demo - not valid insurance", child)
