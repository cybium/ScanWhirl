"""Fictional sample documents for Play Store screenshots (no real people, numbers or brands).
Usage (WSL): python3 tools/store_samples.py /mnt/c/Users/Cybium/Desktop/.sw-qa/store/samples
"""
import os
import sys

from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT = sys.argv[1] if len(sys.argv) > 1 else "samples"
os.makedirs(OUT, exist_ok=True)
W, H = A4
INK = HexColor("#1d2330")
MUTED = HexColor("#5b6475")
ACCENT = HexColor("#0e7c86")


def page(name):
    c = canvas.Canvas(os.path.join(OUT, name), pagesize=A4)
    c.setTitle(name.replace(".pdf", ""))
    return c


def header(c, title, sub):
    c.setFillColor(ACCENT)
    c.rect(0, H - 90, W, 90, stroke=0, fill=1)
    c.setFillColor(HexColor("#ffffff"))
    c.setFont("Helvetica-Bold", 22)
    c.drawString(40, H - 52, title)
    c.setFont("Helvetica", 11)
    c.drawString(40, H - 72, sub)
    c.setFillColor(INK)


def lines(c, x, y, rows, size=11, gap=18, font="Helvetica"):
    c.setFont(font, size)
    for r in rows:
        c.drawString(x, y, r)
        y -= gap
    return y


# 1. Invoice
c = page("Invoice - Sharma Traders.pdf")
header(c, "TAX INVOICE", "Sharma Traders  |  12 MG Road, Pune 411001  |  GSTIN 27ABCDE1234F1Z5")
y = lines(c, 40, H - 130, ["Invoice no: ST-2026-0418", "Date: 12 Nov 2026", "Bill to: Rao & Sons, Nashik",
                           "Phone: 98765 43210   Email: accounts@example.com"])
c.setFillColor(HexColor("#eef3f4")); c.rect(40, y - 10, W - 80, 24, stroke=0, fill=1); c.setFillColor(INK)
c.setFont("Helvetica-Bold", 11)
for x, t in [(48, "Item"), (300, "Qty"), (370, "Rate"), (460, "Amount")]:
    c.drawString(x, y - 2, t)
y -= 34
items = [("A4 copier paper (500 sheets)", 20, 260), ("Ballpoint pens, box of 50", 6, 180),
         ("Stapler, heavy duty", 4, 420), ("Box files, pack of 10", 3, 650), ("Printer toner", 2, 2400)]
total = 0
c.setFont("Helvetica", 11)
for name, q, r in items:
    amt = q * r; total += amt
    c.drawString(48, y, name); c.drawString(300, y, str(q)); c.drawString(370, y, f"{r:,}"); c.drawString(460, y, f"{amt:,}")
    y -= 22
gst = round(total * 0.18)
y -= 10
for label, val in [("Subtotal", total), ("GST 18%", gst), ("Total (INR)", total + gst)]:
    c.setFont("Helvetica-Bold" if label.startswith("Total") else "Helvetica", 12)
    c.drawString(370, y, label); c.drawString(460, y, f"{val:,}"); y -= 22
lines(c, 40, 120, ["Bank: Demo Bank, A/c 123456789012, IFSC DEMO0001234", "Thank you for your business."], 10, 16)
c.save()

# 2. Offer letter
c = page("Offer letter - Priya Nair.pdf")
header(c, "Offer of Employment", "Brightline Software Pvt Ltd  |  Bengaluru")
body = ["Date: 3 Nov 2026", "", "Dear Priya Nair,", "",
        "We are pleased to offer you the position of Junior Data Analyst at Brightline",
        "Software, starting on 1 December 2026. Your annual compensation will be",
        "INR 6,40,000, paid monthly, along with health cover for you and your family.",
        "", "Please bring the following documents on your first day:",
        "  - Degree certificate and mark sheets", "  - Two passport-size photographs",
        "  - PAN card and an address proof", "",
        "Contact HR at hr@example.com or +91 91234 56789 with any questions.", "",
        "We look forward to working with you.", "", "Anita Desai", "Head of People, Brightline Software"]
lines(c, 50, H - 140, body, 12, 20)
c.save()

# 3. Physics notes (handwriting-like layout)
c = page("Physics notes - Chapter 4.pdf")
c.setFillColor(HexColor("#fbfaf4")); c.rect(0, 0, W, H, stroke=0, fill=1)
c.setStrokeColor(HexColor("#c9d7e8"))
for yy in range(int(H) - 80, 40, -26):
    c.line(40, yy, W - 40, yy)
c.setFillColor(HexColor("#1b3a8a"))
notes = ["Chapter 4 - Laws of Motion", "", "1. Newton's first law: a body stays at rest or in uniform",
         "   motion unless an external force acts on it (inertia).",
         "2. Second law: F = m a.  Force is the rate of change of momentum.",
         "3. Third law: every action has an equal and opposite reaction.", "",
         "Momentum p = m v   (unit: kg m/s)", "Impulse J = F t = change in momentum", "",
         "Example: m = 2 kg, a = 3 m/s^2  ->  F = 6 N", "",
         "Friction: f = mu N.  Static friction > kinetic friction.", "",
         "Revise: conservation of momentum, rocket propulsion, recoil of a gun."]
lines(c, 52, H - 100, notes, 14, 26, "Helvetica-Oblique")
c.save()

# 4. Rent receipt
c = page("Rent receipt - October.pdf")
header(c, "RENT RECEIPT", "Flat 304, Green Park Residency, Jaipur")
lines(c, 50, H - 150, ["Receipt no: RR-10-2026", "Date: 31 Oct 2026", "",
                       "Received with thanks from Rahul Verma the sum of INR 18,000",
                       "(Eighteen thousand only) towards rent for October 2026", "for the above premises.", "",
                       "Payment mode: UPI   Reference: 6301 2245 9981", "",
                       "Landlord: K. Mehta   Phone: 99887 66554", "", "", "Signature: ____________________"], 12, 22)
c.save()

# 5. Electricity bill
c = page("Electricity bill - Nov 2026.pdf")
header(c, "Electricity Bill", "City Power Distribution Ltd  |  Consumer care 1800 000 0000")
y = lines(c, 40, H - 130, ["Consumer: Meera Iyer", "Consumer no: 1100 2233 4455", "Bill month: November 2026",
                           "Due date: 25 Nov 2026"], 12, 20)
y -= 10
for label, val in [("Units consumed", "286 kWh"), ("Energy charges", "INR 1,844.00"), ("Fixed charges", "INR 120.00"),
                   ("Electricity duty", "INR 98.20"), ("Amount payable", "INR 2,062.20")]:
    c.setFont("Helvetica-Bold" if label.startswith("Amount") else "Helvetica", 12)
    c.drawString(60, y, label); c.drawString(360, y, val); y -= 22
c.setFillColor(ACCENT)
for i, v in enumerate([180, 210, 240, 260, 230, 286]):
    c.rect(70 + i * 70, 160, 40, v * 0.6, stroke=0, fill=1)
c.setFillColor(MUTED); c.setFont("Helvetica", 9)
for i, m in enumerate(["Jun", "Jul", "Aug", "Sep", "Oct", "Nov"]):
    c.drawString(78 + i * 70, 145, m)
c.save()

print("wrote", sorted(os.listdir(OUT)))
