from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output" / "pdf" / "kaagaz-fictional-coverage-policy.pdf"
OUT.parent.mkdir(parents=True, exist_ok=True)

green = colors.HexColor("#176B52")
ink = colors.HexColor("#17221E")
muted = colors.HexColor("#5B6962")
paper = colors.HexColor("#F7F5EF")
line = colors.HexColor("#D9DED9")

styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name="Brand", parent=styles["Heading1"], fontName="Helvetica-Bold", fontSize=22, leading=25, textColor=green, spaceAfter=4))
styles.add(ParagraphStyle(name="Title2", parent=styles["Heading2"], fontName="Helvetica-Bold", fontSize=15, leading=19, textColor=ink, spaceBefore=10, spaceAfter=7))
styles.add(ParagraphStyle(name="Body2", parent=styles["BodyText"], fontName="Helvetica", fontSize=10.5, leading=15, textColor=ink, spaceAfter=7))
styles.add(ParagraphStyle(name="Small2", parent=styles["BodyText"], fontName="Helvetica", fontSize=8.5, leading=12, textColor=muted))
styles.add(ParagraphStyle(name="Disclaimer", parent=styles["BodyText"], fontName="Helvetica-Bold", fontSize=9, leading=12, alignment=TA_CENTER, textColor=colors.HexColor("#A74236"), backColor=colors.HexColor("#FCEBE8"), borderPadding=7, spaceAfter=12))

def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(line)
    canvas.line(18*mm, 15*mm, 192*mm, 15*mm)
    canvas.setFont("Helvetica", 8)
    canvas.setFillColor(muted)
    canvas.drawString(18*mm, 10*mm, "Kaagaz fictional policy fixture - not valid insurance")
    canvas.drawRightString(192*mm, 10*mm, f"Page {doc.page}")
    canvas.restoreState()

doc = SimpleDocTemplate(str(OUT), pagesize=A4, rightMargin=18*mm, leftMargin=18*mm, topMargin=17*mm, bottomMargin=20*mm,
                        title="Kaagaz Fictional Health Coverage Policy", author="Kaagaz Demo")
story = [
    Paragraph("KAAGAZ DEMO HEALTH SECURE", styles["Brand"]),
    Paragraph("Fictional health insurance policy schedule and wording", styles["Small2"]),
    Spacer(1, 7*mm),
    Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL POLICY AND NOT VALID FOR ANY CLAIM", styles["Disclaimer"]),
]

details = [
    ["Insurer", "Kaagaz Demo Assurance Company"],
    ["Policy Number", "KZG-HEALTH-2027-010A"],
    ["Insured Person", "Neha Example"],
    ["Premium Amount", "INR 18,600"],
    ["Policy Start Date", "01 January 2027"],
    ["Policy Expiry Date", "31 December 2027"],
    ["Sum Insured", "INR 5,00,000"],
]
table = Table(details, colWidths=[48*mm, 112*mm], hAlign="LEFT")
table.setStyle(TableStyle([
    ("BACKGROUND", (0,0), (0,-1), colors.HexColor("#E5F1EB")), ("TEXTCOLOR", (0,0), (-1,-1), ink),
    ("FONTNAME", (0,0), (0,-1), "Helvetica-Bold"), ("FONTNAME", (1,0), (1,-1), "Helvetica"),
    ("FONTSIZE", (0,0), (-1,-1), 10), ("GRID", (0,0), (-1,-1), .5, line),
    ("VALIGN", (0,0), (-1,-1), "TOP"), ("TOPPADDING", (0,0), (-1,-1), 7), ("BOTTOMPADDING", (0,0), (-1,-1), 7),
]))
story += [table, Spacer(1, 7*mm), Paragraph("1. Hospitalisation Benefit", styles["Title2"]),
          Paragraph("In-patient hospitalisation expenses for medically necessary treatment are covered up to the Sum Insured, subject to this policy's waiting periods, exclusions, limits, and claim requirements.", styles["Body2"]),
          Paragraph("2. Conditional Ambulance Benefit", styles["Title2"]),
          Paragraph("Emergency road ambulance charges are payable up to INR 2,000 per claim, provided the insured person is admitted for a covered in-patient hospitalisation.", styles["Body2"]),
          Paragraph("3. Disease and Condition Mentions", styles["Title2"]),
          Paragraph("Diabetes and hypertension are recorded in this fictional schedule for medical disclosure. Their appearance in this section does not establish that treatment for either condition is covered.", styles["Body2"]),
          Paragraph("Pre-existing diabetes may be eligible only after the waiting period below and remains subject to all other policy terms.", styles["Body2"]),
          PageBreak(),
          Paragraph("KAAGAZ DEMO HEALTH SECURE", styles["Brand"]),
          Paragraph("Policy wording - limitations and claims", styles["Small2"]),
          Spacer(1, 5*mm),
          Paragraph("4. Waiting Period", styles["Title2"]),
          Paragraph("A waiting period of 24 months of continuous coverage applies to pre-existing diabetes and hypertension. No benefit is payable for these conditions during that waiting period unless a written endorsement states otherwise.", styles["Body2"]),
          Paragraph("5. Exclusion", styles["Title2"]),
          Paragraph("Cosmetic treatment performed only to improve appearance is excluded and is not payable, except reconstructive surgery that is medically necessary following a covered accident.", styles["Body2"]),
          Paragraph("6. Limits and Sub-limits", styles["Title2"]),
          Paragraph("Room rent is limited to 1 percent of the Sum Insured per day. The ambulance benefit is capped at INR 2,000 per claim. These sub-limits do not increase the overall Sum Insured.", styles["Body2"]),
          Paragraph("7. Claim Requirements", styles["Title2"]),
          Paragraph("Notify the fictional insurer within 48 hours of emergency admission. Submit the signed claim form, hospital bills, discharge summary, and payment receipts within 30 days after discharge.", styles["Body2"]),
          Paragraph("8. Reading This Policy", styles["Title2"]),
          Paragraph("Benefits are payable only when the complete policy wording supports the claim. A disease name, example, or medical definition alone is not a promise of coverage.", styles["Body2"]),
          Spacer(1, 8*mm), Paragraph("END OF FICTIONAL DEMONSTRATION DOCUMENT", styles["Disclaimer"])]

doc.build(story, onFirstPage=footer, onLaterPages=footer)
print(OUT)
