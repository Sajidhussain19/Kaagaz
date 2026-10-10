from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output" / "pdf"
OUT.mkdir(parents=True, exist_ok=True)
POLICY = OUT / "kaagaz-fictional-child-savings-policy.pdf"
STATEMENT = OUT / "kaagaz-fictional-child-payment-statement.pdf"

green = colors.HexColor("#176B52")
ink = colors.HexColor("#17221E")
muted = colors.HexColor("#5B6962")
line = colors.HexColor("#D9DED9")
cream = colors.HexColor("#F7F5EF")
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name="BrandX", parent=styles["Heading1"], fontName="Helvetica-Bold", fontSize=21, leading=25, textColor=green, spaceAfter=4))
styles.add(ParagraphStyle(name="TitleX", parent=styles["Heading2"], fontName="Helvetica-Bold", fontSize=14, leading=18, textColor=ink, spaceBefore=10, spaceAfter=6))
styles.add(ParagraphStyle(name="BodyX", parent=styles["BodyText"], fontName="Helvetica", fontSize=10.5, leading=15, textColor=ink, spaceAfter=7))
styles.add(ParagraphStyle(name="SmallX", parent=styles["BodyText"], fontName="Helvetica", fontSize=8.5, leading=11, textColor=muted))
styles.add(ParagraphStyle(name="WarnX", parent=styles["BodyText"], fontName="Helvetica-Bold", fontSize=9, leading=12, textColor=colors.HexColor("#A74236"), backColor=colors.HexColor("#FCEBE8"), borderPadding=7, spaceAfter=10))

def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(line); canvas.line(18*mm, 15*mm, 192*mm, 15*mm)
    canvas.setFont("Helvetica", 8); canvas.setFillColor(muted)
    canvas.drawString(18*mm, 10*mm, "Fictional Kaagaz child-policy fixture - not valid insurance")
    canvas.drawRightString(192*mm, 10*mm, f"Page {doc.page}")
    canvas.restoreState()

def schedule(rows, widths=(50, 110)):
    table = Table(rows, colWidths=[w*mm for w in widths], hAlign="LEFT")
    table.setStyle(TableStyle([
        ("BACKGROUND", (0,0), (0,-1), colors.HexColor("#E5F1EB")),
        ("FONTNAME", (0,0), (0,-1), "Helvetica-Bold"),
        ("FONTNAME", (1,0), (1,-1), "Helvetica"),
        ("GRID", (0,0), (-1,-1), .5, line), ("FONTSIZE", (0,0), (-1,-1), 10),
        ("VALIGN", (0,0), (-1,-1), "TOP"), ("TOPPADDING", (0,0), (-1,-1), 7), ("BOTTOMPADDING", (0,0), (-1,-1), 7),
    ]))
    return table

policy = SimpleDocTemplate(str(POLICY), pagesize=A4, leftMargin=18*mm, rightMargin=18*mm, topMargin=17*mm, bottomMargin=20*mm,
                           title="Kaagaz Fictional Child Savings Policy", author="Kaagaz Demo")
story = [
    Paragraph("NIRMAL DEMO LIFE INSURANCE", styles["BrandX"]),
    Paragraph("Little Steps Child Savings Plan - fictional policy schedule", styles["SmallX"]), Spacer(1, 5*mm),
    Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL POLICY, CUSTOMER, OR FINANCIAL PROMISE", styles["WarnX"]),
    schedule([
        ["Policy number", "NDL-CHILD-2027-0042"], ["Policyholder", "Asha Example"],
        ["Life insured", "Mira Example (fictional child)"], ["Date of birth", "15 June 2011"],
        ["Policy start date", "01 January 2027"], ["Maturity date", "15 June 2032"],
        ["Premium", "INR 2,500 quarterly"], ["Premium frequency", "Quarterly"],
        ["Regular due dates", "01 January, 01 April, 01 July, and 01 October each policy year"],
    ]),
    Spacer(1, 6*mm), Paragraph("1. Purpose of this fictional plan", styles["TitleX"]),
    Paragraph("This fictional savings and insurance plan provides the benefits stated below only when their eligibility conditions are satisfied. It is created solely to test Kaagaz.", styles["BodyX"]),
    Paragraph("2. Premium schedule", styles["TitleX"]),
    Paragraph("A premium of INR 2,500 is scheduled quarterly. Premium due dates are 01 January, 01 April, 01 July, and 01 October of each policy year until 01 April 2032.", styles["BodyX"]),
    PageBreak(),
    Paragraph("LITTLE STEPS CHILD SAVINGS PLAN", styles["BrandX"]),
    Paragraph("Benefits and eligibility conditions", styles["SmallX"]), Spacer(1, 5*mm),
    Paragraph("3. Guaranteed Education Benefit at Age 18", styles["TitleX"]),
    Paragraph("Guaranteed Education Benefit of INR 1,00,000 is payable at age 18 on 15 June 2029 only if every premium due through 01 April 2029 has been paid and the policy remains in force.", styles["BodyX"]),
    Paragraph("4. Guaranteed Maturity Benefit at Age 21", styles["TitleX"]),
    Paragraph("Guaranteed Maturity Benefit of INR 1,50,000 is payable at age 21 on the maturity date, 15 June 2032, only if all scheduled premiums have been paid and the policy remains in force.", styles["BodyX"]),
    Paragraph("5. Projected Non-Guaranteed Addition", styles["TitleX"]),
    Paragraph("Projected non-guaranteed addition at age 21 is INR 40,000. This projected benefit is an illustration only, is not guaranteed, and may be zero or vary with declared bonuses.", styles["BodyX"]),
    Paragraph("6. Total shown at age 21", styles["TitleX"]),
    Paragraph("The guaranteed age-21 maturity benefit is INR 1,50,000. Any projected addition must be shown separately and must not be described as guaranteed.", styles["BodyX"]),
    PageBreak(),
    Paragraph("LITTLE STEPS CHILD SAVINGS PLAN", styles["BrandX"]),
    Paragraph("Waiting periods, exclusions, and claims", styles["SmallX"]), Spacer(1, 5*mm),
    Paragraph("7. Waiting Period", styles["TitleX"]),
    Paragraph("A waiting period of 90 days from the policy start date applies to the premium-waiver rider. No premium-waiver rider benefit is payable during this waiting period, except where the policy explicitly states otherwise.", styles["BodyX"]),
    Paragraph("8. Exclusion", styles["TitleX"]),
    Paragraph("No premium-waiver rider benefit is payable if the qualifying event results from deliberate fraud or a materially false statement in the application. This exclusion does not reduce a guaranteed savings benefit that has already vested under the policy terms.", styles["BodyX"]),
    Paragraph("9. Important distinction", styles["TitleX"]),
    Paragraph("The education benefit and maturity benefit are separate guaranteed benefits with different ages, dates, amounts, and eligibility conditions. The projected addition is not guaranteed.", styles["BodyX"]),
    Paragraph("END OF FICTIONAL DEMONSTRATION POLICY", styles["WarnX"]),
]
policy.build(story, onFirstPage=footer, onLaterPages=footer)

statement = SimpleDocTemplate(str(STATEMENT), pagesize=A4, leftMargin=18*mm, rightMargin=18*mm, topMargin=17*mm, bottomMargin=20*mm,
                              title="Kaagaz Fictional Child Policy Payment Statement", author="Kaagaz Demo")
payments = Table([
    ["Status", "Payment date", "Amount", "Receipt"],
    ["Premium paid", "01 January 2027", "INR 2,500", "DEMO-C001"],
    ["Premium paid", "01 April 2027", "INR 2,500", "DEMO-C002"],
], colWidths=[42*mm, 42*mm, 34*mm, 42*mm], hAlign="LEFT")
payments.setStyle(TableStyle([
    ("BACKGROUND", (0,0), (-1,0), green), ("TEXTCOLOR", (0,0), (-1,0), colors.white),
    ("FONTNAME", (0,0), (-1,0), "Helvetica-Bold"), ("GRID", (0,0), (-1,-1), .5, line),
    ("FONTSIZE", (0,0), (-1,-1), 9.5), ("TOPPADDING", (0,0), (-1,-1), 7), ("BOTTOMPADDING", (0,0), (-1,-1), 7),
]))
statement_story = [
    Paragraph("NIRMAL DEMO LIFE INSURANCE", styles["BrandX"]),
    Paragraph("Fictional child-policy payment statement", styles["SmallX"]), Spacer(1, 5*mm),
    Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL PAYMENT RECORD", styles["WarnX"]),
    schedule([["Policy number", "NDL-CHILD-2027-0042"], ["Policyholder", "Asha Example"], ["Statement date", "15 August 2027"]]),
    Paragraph("Verified Payment History", styles["TitleX"]), payments,
    Paragraph("Paid total", styles["TitleX"]),
    Paragraph("Premium paid total: INR 5,000 from the two payment records listed above.", styles["BodyX"]),
    Paragraph("Verified Outstanding Premium", styles["TitleX"]),
    Paragraph("Verified outstanding premium: INR 2,500 due on 01 July 2027. This amount is explicitly recorded as outstanding on this fictional statement.", styles["BodyX"]),
    Paragraph("Future Premium Schedule", styles["TitleX"]),
    Paragraph("A future scheduled premium of INR 2,500 is due on 01 October 2027. This scheduled premium is not currently outstanding as of the statement date.", styles["BodyX"]),
    Paragraph("Important distinction", styles["TitleX"]),
    Paragraph("Paid premiums, verified outstanding premiums, and future scheduled premiums are different categories and must not be combined.", styles["BodyX"]),
]
statement.build(statement_story, onFirstPage=footer)
print(POLICY)
print(STATEMENT)
