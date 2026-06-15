"""
Generates the SmartHelm — Unisys Innovation Program report PDF.
Pure reportlab (Platypus + graphics). No external assets required.
"""
import os
from datetime import date

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_JUSTIFY
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, HRFlowable, KeepTogether, Flowable,
)
from reportlab.graphics.shapes import Drawing, Line, Circle, String, Rect
from reportlab.graphics import renderPDF

# ---------------------------------------------------------------- palette
NAVY   = colors.HexColor("#11294D")
TEAL   = colors.HexColor("#0F6E56")
TEALBG = colors.HexColor("#E1F5EE")
CORAL  = colors.HexColor("#993C1D")
CORALBG= colors.HexColor("#FAECE7")
GRAY   = colors.HexColor("#5F5E5A")
LIGHT  = colors.HexColor("#F1EFE8")
RULE   = colors.HexColor("#C9C6BE")
AMBER  = colors.HexColor("#854F0B")
INK    = colors.HexColor("#1A1A24")

OUT = os.path.join(os.path.dirname(__file__), "SmartHelm_Unisys_Innovation_Report.pdf")

# ---------------------------------------------------------------- styles
ss = getSampleStyleSheet()

def style(name, **kw):
    base = kw.pop("parent", ss["Normal"])
    return ParagraphStyle(name, parent=base, **kw)

H1   = style("H1", fontName="Helvetica-Bold", fontSize=19, textColor=NAVY,
             spaceBefore=4, spaceAfter=6, leading=23)
H2   = style("H2", fontName="Helvetica-Bold", fontSize=13, textColor=NAVY,
             spaceBefore=14, spaceAfter=5, leading=16)
H3   = style("H3", fontName="Helvetica-Bold", fontSize=10.5, textColor=TEAL,
             spaceBefore=8, spaceAfter=3, leading=13)
BODY = style("BODY", fontName="Helvetica", fontSize=9.5, textColor=INK,
             leading=14, alignment=TA_JUSTIFY, spaceAfter=5)
BULL = style("BULL", parent=BODY, leftIndent=12, bulletIndent=2, spaceAfter=3,
             alignment=TA_LEFT)
SMALL= style("SMALL", fontName="Helvetica", fontSize=8, textColor=GRAY, leading=10.5)
CELL = style("CELL", fontName="Helvetica", fontSize=8, textColor=INK, leading=10)
CELLB= style("CELLB", fontName="Helvetica-Bold", fontSize=8, textColor=INK, leading=10)
CELLH= style("CELLH", fontName="Helvetica-Bold", fontSize=8, textColor=colors.white, leading=10)
CELLW= style("CELLW", fontName="Helvetica-Bold", fontSize=8, textColor=colors.white, leading=10)
TAGOK= style("TAGOK", fontName="Helvetica-Bold", fontSize=8, textColor=TEAL, leading=10)
TAGNO= style("TAGNO", fontName="Helvetica", fontSize=8, textColor=GRAY, leading=10)

def P(t, s=BODY): return Paragraph(t, s)

# ---------------------------------------------------------------- positioning map
def positioning_map():
    W, H = 470, 320
    d = Drawing(W, H)
    x0, x1, y0, y1 = 64, 452, 48, 286
    xm, ym = (x0 + x1) / 2.0, (y0 + y1) / 2.0

    # axes
    d.add(Line(x0, y0, x1, y0, strokeColor=GRAY, strokeWidth=0.8))
    d.add(Line(x0, y0, x0, y1, strokeColor=GRAY, strokeWidth=0.8))
    # quadrant dividers (dashed)
    d.add(Line(xm, y0, xm, y1, strokeColor=RULE, strokeWidth=0.6, strokeDashArray=[3, 3]))
    d.add(Line(x0, ym, x1, ym, strokeColor=RULE, strokeWidth=0.6, strokeDashArray=[3, 3]))

    # axis captions
    d.add(String(xm, 26, "Vehicle segment served", fontName="Helvetica-Oblique",
                 fontSize=8.5, fillColor=GRAY, textAnchor="middle"))
    d.add(String(x0 + 4, 36, "4-wheeler / cab / truck", fontName="Helvetica", fontSize=7.5, fillColor=GRAY))
    d.add(String(x1 - 4, 36, "2-wheeler / rider", fontName="Helvetica", fontSize=7.5, fillColor=GRAY, textAnchor="end"))
    d.add(String(x0 + 4, y1 - 6, "edge AI / private", fontName="Helvetica", fontSize=7.5, fillColor=GRAY))
    d.add(String(x0 + 4, y0 + 6, "cloud video upload", fontName="Helvetica", fontSize=7.5, fillColor=GRAY))
    # rotated y caption
    d.add(String(0, 0, "Data architecture", fontName="Helvetica-Oblique", fontSize=8.5,
                 fillColor=GRAY, textAnchor="middle",
                 transform=[0, 1, -1, 0, 26, ym]))

    def dot(x, y, r, fill, label=None, lx=0, ly=0, anchor="start", lcol=INK, bold=False):
        d.add(Circle(x, y, r, fillColor=fill, strokeColor=colors.white, strokeWidth=0.8))
        if label:
            d.add(String(x + lx, y + ly, label, fontName="Helvetica-Bold" if bold else "Helvetica",
                         fontSize=7.2, fillColor=lcol, textAnchor=anchor))

    # incumbents (bottom-left, coral)
    dot(118, 150, 6, CORAL, "Cautio", 9, -2)
    dot(205, 168, 5, CORAL, "Netradyne", 9, -2)
    dot(108, 112, 5, CORAL, "LightMetrics", 9, -2)
    dot(196, 96,  5, CORAL, "Samsara/Lytx/Motive", 0, -12, "middle")
    dot(150, 134, 4.5, CORAL, "Intangles/OKDriver", 8, -2)
    dot(232, 132, 4.5, CORAL, "Tata/LocoNav/Fleetx", 8, -2)

    # 2-wheeler-but-not-biometric (bottom-right)
    dot(322, 150, 6, CORAL, "NAYAN AI", 9, -2)
    dot(312, 116, 5, CORAL, "SafetyConnect", 9, -2)
    dot(392, 92,  5.5, GRAY, "Forcite/Jarvish/Sena", 0, -12, "middle")

    # SmartHelm (top-right, teal, emphasized)
    d.add(Circle(372, 238, 11, fillColor=TEAL, strokeColor=colors.white, strokeWidth=1.2))
    d.add(String(372, 258, "SmartHelm", fontName="Helvetica-Bold", fontSize=9,
                 fillColor=TEAL, textAnchor="middle"))
    d.add(String(372, 214, "the open quadrant", fontName="Helvetica-Oblique", fontSize=7.2,
                 fillColor=GRAY, textAnchor="middle"))

    # legend
    ly = 8
    d.add(Circle(x1 - 232, ly, 4, fillColor=TEAL, strokeColor=colors.white, strokeWidth=0.6))
    d.add(String(x1 - 224, ly - 3, "SmartHelm", fontName="Helvetica", fontSize=7, fillColor=GRAY))
    d.add(Circle(x1 - 150, ly, 4, fillColor=CORAL, strokeColor=colors.white, strokeWidth=0.6))
    d.add(String(x1 - 142, ly - 3, "cloud DMS / telematics", fontName="Helvetica", fontSize=7, fillColor=GRAY))
    d.add(Circle(x1 - 30, ly, 4, fillColor=GRAY, strokeColor=colors.white, strokeWidth=0.6))
    d.add(String(x1 - 22, ly - 3, "adjacent", fontName="Helvetica", fontSize=7, fillColor=GRAY))
    return d

# ---------------------------------------------------------------- table helper
def make_table(data, col_widths, header_bg=NAVY, zebra=True, font_hdr=CELLH):
    t = Table(data, colWidths=col_widths, repeatRows=1)
    cmds = [
        ("BACKGROUND", (0, 0), (-1, 0), header_bg),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LINEBELOW", (0, 0), (-1, 0), 0.6, NAVY),
        ("GRID", (0, 0), (-1, -1), 0.3, RULE),
    ]
    if zebra:
        for r in range(1, len(data)):
            if r % 2 == 0:
                cmds.append(("BACKGROUND", (0, r), (-1, r), LIGHT))
    t.setStyle(TableStyle(cmds))
    return t

# ---------------------------------------------------------------- page chrome
def header_footer(canvas, doc):
    canvas.saveState()
    w, h = A4
    # header band
    canvas.setFillColor(NAVY)
    canvas.rect(0, h - 16 * mm, w, 16 * mm, stroke=0, fill=1)
    canvas.setFillColor(colors.white)
    canvas.setFont("Helvetica-Bold", 10)
    canvas.drawString(18 * mm, h - 10.5 * mm, "SmartHelm")
    canvas.setFont("Helvetica", 8)
    canvas.setFillColor(TEALBG)
    canvas.drawString(40 * mm, h - 10.5 * mm, "Edge-AI drowsiness detection for two-wheeler gig riders")
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(TEALBG)
    canvas.drawRightString(w - 18 * mm, h - 10.5 * mm, "Unisys Innovation Program")
    # footer
    canvas.setStrokeColor(RULE)
    canvas.setLineWidth(0.5)
    canvas.line(18 * mm, 13 * mm, w - 18 * mm, 13 * mm)
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(GRAY)
    canvas.drawString(18 * mm, 9.5 * mm, "Competitive Analysis & Hackathon Execution Plan")
    canvas.drawRightString(w - 18 * mm, 9.5 * mm, "Page %d" % doc.page)
    canvas.restoreState()

def cover(canvas, doc):
    canvas.saveState()
    w, h = A4
    canvas.setFillColor(NAVY)
    canvas.rect(0, 0, w, h, stroke=0, fill=1)
    canvas.setFillColor(TEAL)
    canvas.rect(0, h - 6 * mm, w, 6 * mm, stroke=0, fill=1)
    canvas.rect(0, 0, w, 4 * mm, stroke=0, fill=1)
    # title block
    canvas.setFillColor(colors.white)
    canvas.setFont("Helvetica-Bold", 40)
    canvas.drawString(22 * mm, h - 70 * mm, "SmartHelm")
    canvas.setFillColor(TEALBG)
    canvas.setFont("Helvetica", 15)
    canvas.drawString(22 * mm, h - 80 * mm, "Edge-AI drowsiness detection for India's")
    canvas.drawString(22 * mm, h - 88 * mm, "two-wheeler gig delivery riders")
    # divider
    canvas.setStrokeColor(TEAL)
    canvas.setLineWidth(1.4)
    canvas.line(22 * mm, h - 96 * mm, 120 * mm, h - 96 * mm)
    # subtitle
    canvas.setFillColor(colors.white)
    canvas.setFont("Helvetica-Bold", 14)
    canvas.drawString(22 * mm, h - 110 * mm, "Competitive Analysis & Hackathon Execution Plan")
    canvas.setFillColor(TEALBG)
    canvas.setFont("Helvetica", 11)
    canvas.drawString(22 * mm, h - 119 * mm, "Prepared for the Unisys Innovation Program")
    canvas.drawString(22 * mm, h - 126 * mm, "Theme: Connected World - IoT, Edge Computing & AI")
    # meta box
    canvas.setFillColor(colors.HexColor("#1B3A66"))
    canvas.roundRect(22 * mm, h - 184 * mm, 150 * mm, 44 * mm, 3 * mm, stroke=0, fill=1)
    canvas.setFillColor(TEALBG)
    canvas.setFont("Helvetica-Bold", 9)
    canvas.drawString(28 * mm, h - 152 * mm, "AUTHOR")
    canvas.drawString(28 * mm, h - 165 * mm, "PROJECT")
    canvas.drawString(28 * mm, h - 178 * mm, "DATE")
    canvas.setFillColor(colors.white)
    canvas.setFont("Helvetica", 10)
    canvas.drawString(70 * mm, h - 152 * mm, "Siddharth Prashood")
    canvas.drawString(70 * mm, h - 165 * mm, "SmartHelm - Pi + Android + Fleet Dashboard")
    canvas.drawString(70 * mm, h - 178 * mm, "10 June 2026")
    # footer tagline
    canvas.setFillColor(TEAL)
    canvas.setFont("Helvetica-Bold", 10)
    canvas.drawString(22 * mm, 16 * mm, "We see fatigue 20 minutes before the eyes close -")
    canvas.drawString(22 * mm, 10 * mm, "on a rider population nobody else serves, with zero faces in the cloud.")
    canvas.restoreState()

# ---------------------------------------------------------------- build story
story = []

# ---- page 1 (after cover): exec summary
story.append(P("Executive summary", H1))
story.append(HRFlowable(width="100%", thickness=1.2, color=TEAL, spaceAfter=8))
story.append(P(
    "India loses roughly nine two-wheeler riders every hour, and rider deaths have nearly doubled in a "
    "decade to about 173,000 road deaths a year. Gig delivery riders - the 500,000-plus workers powering "
    "Zomato, Swiggy and Zepto - ride fatigued under ten-minute-delivery pressure, yet "
    "<b>not a single commercial product is built for them.</b> Every drowsiness and driver-monitoring "
    "company in the market - Cautio, Netradyne, LightMetrics, Samsara and a dozen others - is built around "
    "the four-wheeler cabin: a windshield-mounted dashcam streaming video to the cloud.", BODY))
story.append(P(
    "SmartHelm closes that gap with an <b>edge-AI helmet-and-phone system</b> that detects drowsiness on the "
    "rider's own device, fuses eye-closure (EAR / PERCLOS) with <b>heart-rate-variability biometrics</b>, and "
    "alerts the rider and fleet manager in under two seconds - <b>without uploading a single face to the cloud.</b> "
    "This report maps the full competitive field of 14 companies, isolates the three gaps that remain genuinely "
    "uncontested, and lays out a feasible one-week plan to ship a winning hackathon build.", BODY))

# three-moat strip
moat_tbl = Table([
    [P("<b>1. Biometric fusion</b>", CELLB),
     P("<b>2. Helmet-native + edge / privacy-first</b>", CELLB),
     P("<b>3. Gig-rider-native product</b>", CELLB)],
    [P("HR / SpO<sub>2</sub> / HRV signals every camera-only rival is blind to. HRV degrades 20-30 min before the eyes close.", CELL),
     P("Inside the helmet, faces never leave the device. DPDP Act 2023 aligned by architecture, not retrofit.", CELL),
     P("Built for the two-wheeler delivery rider - a segment of 500k+ that incumbents structurally cannot reach.", CELL)],
], colWidths=[58 * mm, 58 * mm, 58 * mm])
moat_tbl.setStyle(TableStyle([
    ("BACKGROUND", (0, 0), (-1, 0), TEAL),
    ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
    ("BACKGROUND", (0, 1), (-1, 1), TEALBG),
    ("GRID", (0, 0), (-1, -1), 0.4, colors.white),
    ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ("LEFTPADDING", (0, 0), (-1, -1), 6),
    ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ("TOPPADDING", (0, 0), (-1, -1), 6),
    ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
]))
# fix header text colour to white via paragraph restyle
moat_hdr = ParagraphStyle("mh", parent=CELLB, textColor=colors.white)
moat_tbl._cellvalues[0] = [Paragraph("<b>1. Biometric fusion</b>", moat_hdr),
                           Paragraph("<b>2. Helmet-native + edge / privacy-first</b>", moat_hdr),
                           Paragraph("<b>3. Gig-rider-native product</b>", moat_hdr)]
story.append(Spacer(1, 4))
story.append(moat_tbl)

# ---- problem & market
story.append(P("The problem and the market", H2))
story.append(P(
    "The opportunity is large, urgent, and structurally ignored:", BODY))
for b in [
    "<b>~9 deaths / hour.</b> Two-wheeler rider fatalities in India nearly doubled in a decade; ~173,000 road deaths a year (IndiaSpend, 2025).",
    "<b>500,000+ gig riders.</b> Quick-commerce riders race ten-minute-delivery targets; fatigue, heat exhaustion and fainting on-road are documented (The Week, 2025).",
    "<b>Regulatory tailwind.</b> India's MoRTH is mandating Driver Drowsiness & Attention Warning Systems (DDAWS) for commercial vehicles from 2026 - the policy direction favours fatigue tech.",
    "<b>Compliance shift.</b> The DPDP Act 2023 turns cloud-stored worker face video into a liability - exactly the architecture every incumbent depends on.",
]:
    story.append(Paragraph(b, BULL, bulletText="•"))

story.append(PageBreak())

# ---- competitive landscape
story.append(P("The competitive landscape", H1))
story.append(HRFlowable(width="100%", thickness=1.2, color=TEAL, spaceAfter=8))
story.append(P(
    "Every drowsiness / driver-monitoring company falls into one of three tiers, and none occupies "
    "SmartHelm's quadrant. The map below plots the field on two axes: vehicle segment served (horizontal) "
    "and data architecture (vertical). The top-right quadrant - two-wheeler plus edge / privacy-first - is empty "
    "except for SmartHelm.", BODY))
story.append(Spacer(1, 4))
story.append(positioning_map())
story.append(Spacer(1, 2))
story.append(P("Figure 1. Competitive positioning. Incumbents cluster in the four-wheeler / cloud quadrant; "
               "NAYAN AI and SafetyConnect touch two-wheelers but not biometrics; SmartHelm sits alone.", SMALL))

# master gap table
story.append(P("Master gap table", H2))
story.append(P("The three right-hand columns are the gaps. Read down them - they are almost entirely \"No\".", BODY))

hdr = [P("Company", CELLH), P("Delivery model", CELLH), P("2-wheeler gig?", CELLH),
       P("Driver-state method", CELLH), P("Biometric HR/HRV?", CELLH), P("Edge + privacy?", CELLH)]
rows = [
    ("OKDriver", "Phone app (software)", ("Yes (generic)", CORAL), "Phone camera CV - fatigue", "No", "Partial"),
    ("LightMetrics RideView", "Phone ADAS SDK", ("Fleets / TSPs", GRAY), "Phone camera CV - drowsiness", "No", "On-phone + cloud"),
    ("SafetyConnect", "Phone app (software)", ("Yes (field-force)", CORAL), "Phone motion sensors", "No", "Sensors + cloud"),
    ("Cautio", "Dual-cam + cloud", ("No (4W / 3W)", GRAY), "Cabin camera - PERCLOS", "No", "Cloud video"),
    ("NAYAN AI", "External / dashcam CV", ("Yes - enforcement", CORAL), "Helmet/seatbelt compliance", "No", "Cloud + clips"),
    ("Netradyne", "Cabin cam DMS", ("No (4W)", GRAY), "Camera - gen-3 PERCLOS", "No", "Edge + cloud video"),
    ("Roadcast", "Cabin cam + app", ("No (4W)", GRAY), "Camera - microsleep", "No", "Cloud"),
    ("Intangles", "Digital twin + video", ("No (4W)", GRAY), "Camera + diagnostics", "No", "Cloud"),
    ("Fleetx", "Sensor + AI", ("No (4W)", GRAY), "Telematics scorecards", "No", "Cloud"),
    ("Go Motive", "AI dashcam", ("No (trucks)", GRAY), "Camera - live coaching", "No", "Cloud"),
    ("LocoNav", "Fleet telematics", ("No (4W)", GRAY), "Overspeed / SOS", "No", "Cloud/embedded"),
    ("Tata Fleet Edge", "Embedded vehicle", ("No (4W)", GRAY), "Vehicle telemetry", "No", "Cloud"),
    ("Tata Elxsi (AIVA)", "Custom DMS (B2B)", ("No (4W)", GRAY), "Camera facial recog.", "No", "Cloud"),
    ("Samsara / Lytx", "AI dashcam + telematics", ("No (US 4W)", GRAY), "Camera DMS", "No", "Cloud"),
]
data = [hdr]
for name, model, (gig, gcol), method, bio, edge in rows:
    gstyle = ParagraphStyle("g", parent=CELL, textColor=gcol,
                            fontName="Helvetica-Bold" if gcol == CORAL else "Helvetica")
    data.append([P(name, CELLB), P(model, CELL), P(gig, gstyle), P(method, CELL),
                 P(bio, TAGNO), P(edge, CELL)])
# SmartHelm highlighted row
shrow = [P("<b>SmartHelm</b>", CELLB), P("Helmet / phone - edge", CELL),
         P("Yes - native", TAGOK), P("EAR+PERCLOS+head-pose+HRV", CELL),
         P("Yes", TAGOK), P("Yes", TAGOK)]
data.append(shrow)
cw = [27 * mm, 30 * mm, 25 * mm, 36 * mm, 22 * mm, 24 * mm]
gap_tbl = make_table(data, cw)
# emphasise SmartHelm last row
n = len(data) - 1
gap_tbl.setStyle(TableStyle([
    ("BACKGROUND", (0, n), (-1, n), TEALBG),
    ("LINEABOVE", (0, n), (-1, n), 1.0, TEAL),
    ("BOX", (0, n), (-1, n), 1.0, TEAL),
]))
story.append(gap_tbl)
story.append(Spacer(1, 3))
story.append(P("Across the entire field, the Biometric column is unanimously \"No\", and no competitor places "
               "a driver-state sensor on the rider's body inside a helmet.", SMALL))

story.append(PageBreak())

# ---- who narrows the gap
story.append(P("Who actually narrows the gap", H1))
story.append(HRFlowable(width="100%", thickness=1.2, color=TEAL, spaceAfter=8))
story.append(P("Three names get close. Precision here matters, because a weak novelty claim is the first thing a "
               "judging panel attacks.", BODY))
story.append(P("NAYAN AI - the only true two-wheeler player", H3))
story.append(P("NAYAN watches riders <b>from the outside in</b> (CCTV / dashcam) to catch whether a rider is "
               "<i>wearing</i> a helmet, for e-challan and compliance. SmartHelm <b>is</b> the helmet, watching "
               "<b>from the inside out</b> to measure whether the rider is <i>awake</i>. Opposite vantage, opposite "
               "purpose - this is complementary, and a partnership angle, not a competitor.", BODY))
story.append(P("SafetyConnect - closest on segment", H3))
story.append(P("Phone-only, gig / field-force focus, SOS and gamification - but it scores risk from <b>phone "
               "motion sensors</b> (acceleration, braking, cornering), not eyes or biometrics. It infers fatigue "
               "from how the bike moves; SmartHelm measures it from the rider's body, catching a microsleep "
               "before the bike ever swerves.", BODY))
story.append(P("LightMetrics RideView - proves the tech, wrong customer", H3))
story.append(P("A phone-based drowsiness SDK that validates the approach at scale, but is sold to four-wheeler "
               "fleet operators and TSPs, camera-only, no biometrics, not gig-rider-native.", BODY))
story.append(P("<b>Honest takeaway:</b> the \"phone eye-drowsiness for riders\" niche is no longer empty. So the "
               "pitch must lead with the three moats that are still uncontested - not with \"first to do phone "
               "drowsiness,\" which is attackable.", BODY))

# novelty assessment
story.append(P("Novelty - what survives scrutiny", H2))
nov = [
    [P("<b>Claim</b>", CELLH), P("<b>Verdict</b>", CELLH), P("<b>Why</b>", CELLH)],
    [P("Helmet + drowsiness concept", CELL), P("Not novel", TAGNO),
     P("Dozens of academic prototypes (IEEE / Springer 2024-26).", CELL)],
    [P("EAR / PERCLOS detection", CELL), P("Not novel", TAGNO),
     P("Industry standard; Netradyne uses PERCLOS.", CELL)],
    [P("Phone-based eye drowsiness", CELL), P("Contested", ParagraphStyle("a", parent=CELL, textColor=AMBER, fontName="Helvetica-Bold")),
     P("OKDriver, LightMetrics, SafetyConnect circle this niche.", CELL)],
    [P("Biometric (HRV) fatigue fusion", CELL), P("Novel + uncontested", TAGOK),
     P("Zero competitors; a camera physically cannot see HRV.", CELL)],
    [P("Helmet-native edge / privacy", CELL), P("Novel", TAGOK),
     P("Nobody is inside the helmet or keeps faces off the cloud.", CELL)],
    [P("Gig-rider-native GTM", CELL), P("Novel", TAGOK),
     P("Incumbents serve cabs, trucks, or enforcement.", CELL)],
]
story.append(make_table(nov, [44 * mm, 34 * mm, 86 * mm]))
story.append(Spacer(1, 4))
story.append(P("The defensible novelty is the <b>intersection</b> of biometric fusion, edge privacy, and the "
               "gig-rider segment - not any single algorithm. The deepest part of that moat is HRV early-warning.", BODY))

story.append(PageBreak())

# ---- solution & architecture
story.append(P("The solution and architecture", H1))
story.append(HRFlowable(width="100%", thickness=1.2, color=TEAL, spaceAfter=8))
story.append(P("SmartHelm already exists as a working three-part system; the hackathon week hardens the moat on "
               "top of it.", BODY))
arch = [
    [P("<b>Layer</b>", CELLH), P("<b>Stack</b>", CELLH), P("<b>Role</b>", CELLH)],
    [P("Edge detection (rider)", CELLB), P("Android / Kotlin, CameraX, MediaPipe FaceLandmarker (VIDEO mode)", CELL),
     P("On-device EAR / PERCLOS, overlay alert, SMS - no video leaves the phone.", CELL)],
    [P("Edge detection (helmet)", CELLB), P("Raspberry Pi 4 + Pi Camera + MAX30102 + SIM800L", CELL),
     P("Same pipeline plus HR / SpO2 biometrics; offline SMS via GSM.", CELL)],
    [P("Cloud sync", CELLB), P("Firebase Firestore (flat riders/{id} doc), Anonymous Auth", CELL),
     P("Real-time status + eye-strip snapshot only; smart-throttled writes.", CELL)],
    [P("Fleet dashboard", CELLB), P("Vanilla JS + Firebase SDK v9, Firebase Hosting", CELL),
     P("Live rider cards, alert banner, HR/SpO2, zero backend.", CELL)],
    [P("Alerting", CELLB), P("MSG91 transactional SMS (route 4, bypasses DND) + audio + vibration", CELL),
     P("Rider, fleet manager and emergency contact notified in < 2 s.", CELL)],
]
story.append(make_table(arch, [34 * mm, 60 * mm, 70 * mm]))
story.append(Spacer(1, 4))
story.append(P("<b>Data flow:</b> front camera -> MediaPipe (468 landmarks) -> EAR + PERCLOS + head-pose + yawn "
               "-> fatigue score -> alert + Firestore push -> dashboard + MSG91 SMS. Biometric HRV from the Pi "
               "raises the score 20-30 minutes earlier than eyes alone.", BODY))

# ---- hackathon plan
story.append(P("One-week hackathon execution plan", H1))
story.append(HRFlowable(width="100%", thickness=1.2, color=TEAL, spaceAfter=8))
story.append(P("The codebase is ~80% built, so this week is <b>not</b> \"build the product\" - it is "
               "\"build the moat and nail the demo.\" Scope discipline is the priority.", BODY))
plan = [
    [P("<b>Day</b>", CELLH), P("<b>Focus</b>", CELLH), P("<b>Deliverables</b>", CELLH), P("<b>Risk control</b>", CELLH)],
    [P("1 - Wed", CELLB), P("Multimodal score (software only)", CELL),
     P("FatigueScorer.kt fusing EAR + PERCLOS + head-pose (nod) + yawn (MAR) into 0-100; speed-gating; N-frame false-alarm filter.", CELL),
     P("Android-only; cannot be blocked by hardware.", CELL)],
    [P("2 - Thu", CELLB), P("The biometric moat", CELL),
     P("HR / HRV from MAX30102 on Pi if wired; else simulated HRV feed + dashboard early-fatigue (amber) state. Safe-riding score + streaks on dashboard.", CELL),
     P("Simulation path guarantees a demo if hardware slips.", CELL)],
    [P("3 - Fri", CELLB), P("End-to-end + polish", CELL),
     P("Full chain test phone -> Firestore -> dashboard -> live MSG91 SMS; calibration screen; bug-bash; DPDP one-pager.", CELL),
     P("Freeze features after today.", CELL)],
    [P("4 - Sat", CELLB), P("Demo + deck", CELL),
     P("Rehearse 90-second live demo; record backup demo video; finalize pitch deck (slides already drafted).", CELL),
     P("Backup video insures against live-demo failure.", CELL)],
    [P("5 - Sun", CELLB), P("Submit", CELL),
     P("Final polish, this report, submission package, buffer.", CELL),
     P("-", CELL)],
    [P("6 - Mon", CELLB), P("Demo day", CELL), P("Present.", CELL), P("Buffer day.", CELL)],
]
story.append(make_table(plan, [16 * mm, 33 * mm, 75 * mm, 40 * mm]))
story.append(Spacer(1, 4))
story.append(P("<b>Explicitly deferred (do NOT build this week):</b> full ESP32-CAM helmet wiring, incident-clip "
               "video buffer, SDK packaging, Cloud Function on the Blaze plan. They are roadmap, not scope.", SMALL))

story.append(PageBreak())

# ---- demo script
story.append(P("The 90-second demo script", H1))
story.append(HRFlowable(width="100%", thickness=1.2, color=TEAL, spaceAfter=8))
demo = [
    [P("<b>t</b>", CELLH), P("<b>Action</b>", CELLH), P("<b>What you say</b>", CELLH)],
    [P("0:00", CELLB), P("Open fleet dashboard, rider card live, eyes OPEN.", CELL),
     P("\"This is a live delivery rider, monitored on his own phone - no dashcam, no video in the cloud.\"", CELL)],
    [P("0:20", CELLB), P("HR / SpO2 chips update from the Pi sensor.", CELL),
     P("\"We also read his heart-rate variability - the one signal a camera can never see.\"", CELL)],
    [P("0:35", CELLB), P("HRV drops -> card turns amber (early fatigue).", CELL),
     P("\"HRV degrades 20 minutes before the eyes do. We catch fatigue before it is visible.\"", CELL)],
    [P("0:55", CELLB), P("Rider closes eyes -> red pill overlay + beep + vibration.", CELL),
     P("\"Eye-closure confirms it. The rider is alerted on-helmet, instantly.\"", CELL)],
    [P("1:10", CELLB), P("Dashboard alert banner + MSG91 SMS arrives on phone.", CELL),
     P("\"The fleet manager and emergency contact get an SMS in under two seconds - even with no data signal.\"", CELL)],
    [P("1:25", CELLB), P("Show the empty top-right quadrant slide.", CELL),
     P("\"500,000 riders, zero competitors, fully DPDP-compliant by design.\"", CELL)],
]
story.append(make_table(demo, [12 * mm, 62 * mm, 90 * mm]))

# ---- Unisys alignment
story.append(P("Alignment to Unisys judging criteria", H2))
story.append(P("The Unisys Innovation Program scores on feasibility, creativity, technical excellence and impact, "
               "under the 2025 \"Connected World - IoT, Edge Computing & AI\" theme. SmartHelm maps directly onto "
               "all four.", BODY))
uni = [
    [P("<b>Criterion</b>", CELLH), P("<b>How SmartHelm scores</b>", CELLH)],
    [P("Feasibility", CELLB),
     P("Working build already exists - Android app, Pi backend, Firestore, dashboard and live SMS. This week hardens it, not invents it.", CELL)],
    [P("Creativity", CELLB),
     P("Reframes drowsiness from a four-wheeler cabin problem to a wearable, biometric, privacy-first one for an unserved population.", CELL)],
    [P("Technical excellence", CELLB),
     P("Edge AI (MediaPipe), multimodal sensor fusion (EAR + PERCLOS + head-pose + HRV), real-time cloud sync, offline GSM fallback.", CELL)],
    [P("Impact", CELLB),
     P("Targets ~9 rider deaths/hour and 500k+ gig riders; aligns with MoRTH DDAWS 2026 and the DPDP Act - a measurable social-safety outcome.", CELL)],
    [P("Theme fit", CELLB),
     P("Connected World embodied: IoT helmet sensors + edge computing + AI making real-time safety decisions.", CELL)],
]
story.append(make_table(uni, [34 * mm, 130 * mm]))

# ---- roadmap beyond
story.append(P("Roadmap beyond the hackathon", H2))
for b in [
    "<b>Productize the moat:</b> ship RMSSD/SDNN HRV scoring end-to-end on wired MAX30102 hardware.",
    "<b>Daily fatigue PDF per rider</b> for fleet managers - per-shift drowsiness trend that incumbents do not offer.",
    "<b>Incident-clip buffer:</b> rolling 30 s on-device video, uploaded only on a confirmed alert - video evidence without the privacy cost.",
    "<b>SmartHelm-as-SDK:</b> let Zomato / Swiggy embed detection in their rider app - the scalable go-to-market that counters LightMetrics.",
    "<b>NAYAN partnership:</b> certify \"helmet-worn + rider-alert\" as a compliance signal, turning the only two-wheeler incumbent into a channel.",
]:
    story.append(Paragraph(b, BULL, bulletText="•"))

story.append(Spacer(1, 8))
story.append(HRFlowable(width="100%", thickness=0.8, color=RULE, spaceAfter=6))
story.append(P("Sources: IndiaSpend (2025); The Week (2025); Netradyne / MoRTH (2025); company websites for Cautio, "
               "NAYAN AI, SafetyConnect, LightMetrics, Netradyne, Samsara; Unisys Innovation Program. Prepared "
               "10 June 2026 for the Unisys Innovation Program.", SMALL))

# ---------------------------------------------------------------- build
doc = SimpleDocTemplate(
    OUT, pagesize=A4,
    leftMargin=18 * mm, rightMargin=18 * mm,
    topMargin=22 * mm, bottomMargin=16 * mm,
    title="SmartHelm - Unisys Innovation Program Report",
    author="Siddharth Prashood",
)

def first_page(canvas, doc):
    cover(canvas, doc)

# cover is its own page; use a PageBreak after a tiny spacer trick:
from reportlab.platypus import NextPageTemplate
from reportlab.platypus.frames import Frame
from reportlab.platypus.doctemplate import PageTemplate

frame = Frame(18 * mm, 16 * mm, A4[0] - 36 * mm, A4[1] - 38 * mm, id="body")
cover_tmpl = PageTemplate(id="cover", frames=[frame], onPage=cover)
body_tmpl = PageTemplate(id="body", frames=[frame], onPage=header_footer)
doc.addPageTemplates([cover_tmpl, body_tmpl])

story = [NextPageTemplate("body"), PageBreak()] + story

doc.build(story)
print("OK ->", OUT)
print("bytes:", os.path.getsize(OUT))
