from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "output" / "pdf" / "kaagaz-fictional-payment-statement.pdf"
OUT.parent.mkdir(parents=True, exist_ok=True)
green = colors.HexColor("#176B52")
ink = colors.HexColor("#17221E")
muted = colors.HexColor("#5B6962")
line = colors.HexColor("#D9DED9")
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name="Brand", parent=styles["Heading1"], fontName="Helvetica-Bold", fontSize=22, leading=27, textColor=green))
styles.add(ParagraphStyle(name="Section", parent=styles["Heading2"], fontName="Helvetica-Bold", fontSize=14, leading=18, textColor=ink, spaceBefore=12, spaceAfter=7))
styles.add(ParagraphStyle(name="Body2", parent=styles["BodyText"], fontName="Helvetica", fontSize=10.5, leading=15, textColor=ink, spaceAfter=7))
styles.add(ParagraphStyle(name="Warn", parent=styles["BodyText"], fontName="Helvetica-Bold", fontSize=9, leading=12, textColor=colors.HexColor("#A74236"), backColor=colors.HexColor("#FCEBE8"), borderPadding=7, spaceAfter=12))

def footer(canvas, doc):
    canvas.saveState(); canvas.setFont("Helvetica", 8); canvas.setFillColor(muted)
    canvas.drawString(18*mm, 11*mm, "Fictional Kaagaz validation statement - not a real financial record")
    canvas.drawRightString(192*mm, 11*mm, "Page 1"); canvas.restoreState()

doc = SimpleDocTemplate(str(OUT), pagesize=A4, leftMargin=18*mm, rightMargin=18*mm, topMargin=17*mm, bottomMargin=20*mm,
                        title="Kaagaz Fictional Payment and Benefit Statement", author="Kaagaz Demo")
story = [Paragraph("KAAGAZ DEMO LIFE PLAN", styles["Brand"]),
         Paragraph("Fictional payment and benefit statement", styles["Body2"]), Spacer(1, 4*mm),
         Paragraph("FICTIONAL DEMONSTRATION DOCUMENT - NOT A REAL POLICY OR PAYMENT RECORD", styles["Warn"])]
details = [["Policy Number", "KZG-LIFE-2027-0910"], ["Policyholder", "Ravi Example"],
           ["Statement Date", "15 March 2027"], ["Policy Start Date", "01 January 2027"],
           ["Policy Expiry Date", "31 December 2036"]]
t = Table(details, colWidths=[48*mm,112*mm]); t.setStyle(TableStyle([
    ("BACKGROUND",(0,0),(0,-1),colors.HexColor("#E5F1EB")),("GRID",(0,0),(-1,-1),.5,line),
    ("FONTNAME",(0,0),(0,-1),"Helvetica-Bold"),("FONTSIZE",(0,0),(-1,-1),10),
    ("TOPPADDING",(0,0),(-1,-1),7),("BOTTOMPADDING",(0,0),(-1,-1),7)]))
story += [t, Paragraph("Verified Payment History", styles["Section"])]
payments = [["Status", "Date", "Amount", "Receipt"], ["Premium paid", "01 Jan 2027", "INR 1,200", "DEMO-R001"],
            ["Payment received", "01 Feb 2027", "INR 1,300", "DEMO-R002"]]
pt = Table(payments, colWidths=[42*mm,36*mm,35*mm,47*mm]); pt.setStyle(TableStyle([
    ("BACKGROUND",(0,0),(-1,0),green),("TEXTCOLOR",(0,0),(-1,0),colors.white),("FONTNAME",(0,0),(-1,0),"Helvetica-Bold"),
    ("GRID",(0,0),(-1,-1),.5,line),("FONTSIZE",(0,0),(-1,-1),9.5),("TOPPADDING",(0,0),(-1,-1),7),("BOTTOMPADDING",(0,0),(-1,-1),7)]))
story += [pt,
          Paragraph("Future Premium Schedule", styles["Section"]),
          Paragraph("A future scheduled premium of INR 1,500 is due on 01 April 2027. A scheduled premium is not proof that the amount is currently outstanding.", styles["Body2"]),
          Paragraph("Outstanding Due", styles["Section"]),
          Paragraph("Verified outstanding premium: INR 500. Balance due date: 20 March 2027.", styles["Body2"]),
          Paragraph("Guaranteed Maturity Benefit", styles["Section"]),
          Paragraph("A guaranteed maturity benefit of INR 1,00,000 is payable on 31 December 2036 only if all scheduled premiums are paid and the policy remains in force.", styles["Body2"]),
          Paragraph("Non-guaranteed Projection", styles["Section"]),
          Paragraph("The projected maturity value is INR 1,25,000. This illustration is not guaranteed and may be higher or lower depending on declared bonuses.", styles["Body2"]),
          Paragraph("Important notice", styles["Section"]),
          Paragraph("Amounts described as projected are estimates only. They must not be presented as guaranteed benefits.", styles["Body2"])]
doc.build(story, onFirstPage=footer)
print(OUT)
