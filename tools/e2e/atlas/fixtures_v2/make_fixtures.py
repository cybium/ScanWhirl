#!/usr/bin/env python3
"""Generate a small, deterministic FICTIONAL fixture corpus for the Tool Atlas.

Run on the GitHub runner (Linux, Pillow + reportlab) by run_atlas.sh. Nothing
binary is committed: the public-repo pre-push guard rejects binaries, and the
verifier's thresholds only need a consistent corpus, not a photographic one.

Every page says "FICTIONAL / नमूना"; every name is a Sample; no real identity,
face, signature, document or personal data is used. The skin tone matches the
illustrated portrait that verify_result.py::skin_stats was calibrated for.
"""
import hashlib
import io
import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

HERE = Path(__file__).resolve().parent
REF = HERE / "references"
A4 = (595.2756, 841.8898)  # points, same as the original fixture corpus
PAGE = (1240, 1754)        # 150 dpi A4
SKIN = (198, 142, 101)     # inside the verifier's calibrated skin window
SEED = 20261122

NOTICE = [
    "नमूना नगर पंचायत", "कार्यालय सूचना", "दिनांक: २२ नवंबर २०२६",
    "विषय: छात्रवृत्ति आवेदन शिविर",
    "सभी विद्यार्थियों को सूचित किया जाता है कि छात्रवृत्ति आवेदन",
    "शिविर ३० नवंबर २०२६ को सुबह १० बजे से शाम ४ बजे तक",
    "सामुदायिक भवन, नमूना नगर में आयोजित किया जाएगा।",
    "कृपया अपना अंक पत्र, फोटो और पते का प्रमाण साथ लाएँ।",
    "आवेदन की जाँच निःशुल्क है। मूल कागज़ वापस दिए जाएँगे।",
    "अधूरी जानकारी होने पर सहायता कक्ष से संपर्क करें।",
    "यह केवल परीक्षण के लिए काल्पनिक सूचना है।",
    "नमूना — किसी वास्तविक कार्यालय द्वारा जारी नहीं।",
]


def font(size, bold=False):
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold
        else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold
        else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
    ]
    for path in candidates:
        if Path(path).is_file():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def rng_for(name):
    return random.Random(int(hashlib.sha256(name.encode()).hexdigest()[:8], 16))


def paper(title, subtitle, page_no, total, tint=(250, 248, 239)):
    im = Image.new("RGB", PAGE, tint)
    d = ImageDraw.Draw(im)
    d.rectangle((60, 60, PAGE[0] - 60, PAGE[1] - 60), outline=(105, 115, 120), width=4)
    d.text((90, 95), "न", font=font(52, True), fill=(45, 94, 119))
    d.text((180, 90), title, font=font(46, True), fill=(35, 39, 43))
    d.text((180, 160), subtitle, font=font(28), fill=(60, 66, 70))
    d.line((80, 215, PAGE[0] - 80, 215), fill=(75, 94, 102), width=4)
    d.text((90, PAGE[1] - 130), "FICTIONAL / नमूना - Not valid for official use",
           font=font(24), fill=(90, 96, 100))
    d.text((PAGE[0] - 190, PAGE[1] - 130), f"{page_no} / {total}", font=font(24), fill=(90, 96, 100))
    return im


def write_lines(im, lines, y0=270, step=62, size=30, bold=False, x=110):
    d = ImageDraw.Draw(im)
    for i, line in enumerate(lines):
        d.text((x, y0 + i * step), line, font=font(size, bold), fill=(35, 39, 43))
    return im


def camera(im, name, background="wood"):
    """A small, synthetic 'photo of a page' with a deterministic background/grain."""
    r = rng_for(name)
    w, h = 1600, 1200
    base = (150, 112, 74) if background == "wood" else (206, 216, 226)
    scene = Image.new("RGB", (w, h), base)
    d = ImageDraw.Draw(scene)
    if background == "wood":
        for y in range(0, h, 90):
            d.line((0, y + r.randint(-8, 8), w, y + r.randint(-8, 8)),
                   fill=(base[0] - 22, base[1] - 18, base[2] - 12), width=3)
    else:
        for y in range(0, h, 26):
            d.line((0, y, w, y), fill=(base[0] - 10, base[1] - 8, base[2] - 6), width=1)
    page = im.copy()
    page.thumbnail((1040, 1100), Image.Resampling.LANCZOS)
    angle = r.choice([-3, -2, 2, 3])
    page = page.rotate(angle, Image.Resampling.BICUBIC, expand=True, fillcolor=(0, 0, 0, 0))
    px = (w - page.width) // 2 + r.randint(-40, 40)
    py = (h - page.height) // 2 + r.randint(-30, 30)
    shadow = Image.new("RGB", page.size, (60, 50, 40))
    scene.paste(shadow, (px + 12, py + 14))
    scene.paste(page, (px, py))
    # Lightweight sensor grain, deterministic, no numpy dependency.
    sd = ImageDraw.Draw(scene)
    for _ in range(1400):
        x, y = r.randrange(w), r.randrange(h)
        c = r.choice([-12, -6, 6, 12])
        p = scene.getpixel((x, y))
        sd.point((x, y), fill=tuple(max(0, min(255, v + c)) for v in p[:3]))
    scene.save(HERE / name, "JPEG", quality=82, subsampling=0, dpi=(300, 300))
    return scene


def pdf(name, pages, text_layer=None):
    c = canvas.Canvas(str(HERE / name), pagesize=A4, pageCompression=1)
    for i, page in enumerate(pages):
        buf = io.BytesIO()
        page.save(buf, "JPEG", quality=82, subsampling=0)
        buf.seek(0)
        c.drawImage(ImageReader(buf), 0, 0, *A4)
        if i == 0 and text_layer:
            c.setFont("Helvetica", 10)
            c.drawString(42, 88, text_layer)
        c.showPage()
    c.save()


def portrait():
    im = Image.new("RGB", (600, 690), (234, 234, 225))
    d = ImageDraw.Draw(im)
    d.ellipse((110, 60, 490, 560), fill=SKIN)            # face: calibrated skin tone
    d.pieslice((95, 20, 505, 250), 180, 360, fill=(46, 36, 31))
    d.ellipse((120, 130, 165, 270), fill=(46, 36, 31))
    d.ellipse((435, 130, 480, 270), fill=(46, 36, 31))
    for x in (230, 370):
        d.ellipse((x - 14, 250, x + 14, 268), fill=(36, 28, 27))
    d.arc((255, 340, 345, 400), 10, 170, fill=(103, 48, 46), width=5)
    d.polygon([(45, 690), (110, 500), (240, 455), (370, 455), (505, 525), (560, 690)],
              fill=(43, 74, 112))
    d.rectangle((262, 400, 338, 500), fill=(190, 131, 92))
    REF.mkdir(exist_ok=True)
    im.save(REF / "portrait_crop.png")
    return im


def exam_photo(portrait_im):
    """A fictional 'exam photo': the illustrated subject in a simple room."""
    scene = Image.new("RGB", (1600, 1200), (224, 219, 205))
    d = ImageDraw.Draw(scene)
    d.rectangle((0, 0, 120, 1200), fill=(112, 78, 50))
    d.rectangle((1380, 300, 1500, 500), fill=(238, 235, 226), outline=(188, 183, 168), width=4)
    d.rectangle((300, 200, 760, 560), fill=(231, 226, 210), outline=(96, 66, 44), width=12)
    person = portrait_im.resize((520, 600), Image.Resampling.LANCZOS)
    scene.paste(person, (620, 480))
    d.polygon([(0, 1120), (1600, 1090), (1600, 1200), (0, 1200)], fill=(90, 101, 108))
    scene.save(HERE / "exam_photo.jpg", "JPEG", quality=82, subsampling=0)


def signature():
    im = Image.new("RGB", (1100, 360), "white")
    d = ImageDraw.Draw(im)
    d.text((70, 45), "Ravi Sample", font=font(96), fill=(28, 57, 146))
    pts = [(60, 250), (240, 232), (470, 260), (720, 220), (1030, 238)]
    d.line(pts, fill=(28, 57, 146), width=9)
    d.arc((150, 100, 320, 330), 100, 310, fill=(28, 57, 146), width=9)
    im.save(REF / "signature_crop.png")
    sheet = Image.new("RGB", PAGE, (250, 247, 231))
    sd = ImageDraw.Draw(sheet)
    for y in range(220, 3300, 120):
        sd.line((120, y, PAGE[0] - 120, y), fill=(178, 194, 207), width=3)
    ink = im.resize((1650, 540))
    sheet.paste(ink, (110, 1450))
    sd.text((110, 200), "Signature practice - FICTIONAL / नमूना", font=font(40, True), fill=(35, 39, 43))
    camera(sheet, "signature_on_paper.jpg", "sheet")


def card(side, portrait_im):
    im = Image.new("RGB", (1712, 1080), (239, 243, 228))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((10, 10, 1700, 1068), radius=60, outline=(97, 115, 95), width=8)
    d.rectangle((40, 90, 1670, 230), fill=(58, 92, 97))
    d.text((80, 125), "KALPANA COMMUNITY CARD / नमूना परिचय", font=font(44, True), fill="white")
    if side == "front":
        im.paste(portrait_im.resize((340, 390)), (95, 285))
        for j, s in enumerate(["RAVI SAMPLE / रवि नमूना", "DOB: XX/XX/2007",
                               "XXXX XXXX 1234", "NOT GOVERNMENT ID"]):
            d.text((500, 320 + j * 125), s, font=font(48), fill=(35, 39, 43))
    else:
        for j, s in enumerate(["ADDRESS / पता", "12 Sample Lane, Namuna Nagar",
                               "यह पहचान का प्रमाण नहीं है।", "FICTIONAL CARD - BACK SIDE",
                               "Ref: XXXX-SAMPLE-BACK"]):
            d.text((110, 290 + j * 120), s, font=font(46), fill=(35, 39, 43))
    d.text((410, 870), "SAMPLE / नमूना", font=font(90, True), fill=(172, 70, 65))
    im.save(REF / f"id_{side}.png")
    camera(im, f"id_{side}.jpg", "steel")


def marksheet():
    pages = []
    for n in (1, 2):
        im = paper("कल्पना माध्यमिक शिक्षा परिषद", "KALPANA SCHOOL EXAMINATION BOARD - FICTIONAL", n, 2)
        write_lines(im, [
            "SENIOR SCHOOL CERTIFICATE / उच्च माध्यमिक अंक पत्र",
            "Student: Ravi Sample / रवि नमूना    Roll No: SAMPLE-12-0042",
            "School: Kalpana Vidya School, Namuna Nagar (fictional)",
            "Class XII - Session 2025-26    Statement 1 of 2",
        ], size=34)
        # Dense fine print in the J2 region (normalized y .68-.81).
        fine = [
            "1. This fictional specimen reproduces dense tabular paperwork, not an official board document.",
            "2. Abbreviations: TH = theory; IA = internal assessment; P = practical; marks are illustrative.",
            "3. Grade ranges: A1 91-100; A2 81-90; B1 71-80; B2 61-70; C1 51-60; C2 41-50.",
            "4. This sample has no legal validity. No institution, student or record number here is real.",
            "5. छोटे अक्षरों में लिखे निर्देश भी संपीडन के बाद पढ़ने योग्य होने चाहिए। यह केवल नमूना है।",
            "6. अंक पत्र की दोनों प्रतियाँ साथ रखें। किसी पोर्टल द्वारा स्वीकृति की गारंटी नहीं है।",
        ]
        write_lines(im, fine, y0=1190, step=34, size=22, x=100)
        im.save(REF / f"marksheet_p{n}.png")
        camera(im, f"marksheet_p{n}.jpg", "wood")
        pages.append(im)
    pdf("marksheet_scan_300dpi.pdf", pages)


def bill():
    pages = []
    for n in range(1, 4):
        im = paper("कल्पना विद्युत सेवा", "KALPANA ELECTRICITY - FICTIONAL UTILITY", n, 3)
        write_lines(im, [
            ["Customer: Arjun Sample", "Billing period: 01 Oct - 31 Oct 2026",
             "Energy used: 310 kWh", "Total due: Rs 2516.60", "Pay before: 20 Nov 2026"],
            ["Consumer No 88-4471-SAMPLE", "मीटर-संख्या 4471", "Previous: 14250 kWh",
             "Current: 14560 kWh", "Sample reading officer: QA ONLY"],
            ["Slab 0-100: Rs 5.00 / kWh", "Slab 101-300: Rs 7.00 / kWh",
             "Slab above 300: Rs 9.00 / kWh", "यह बिल केवल परीक्षण के लिए है।"],
        ][n - 1], size=36)
        pages.append(im)
    pdf("electricity_bill_3p.pdf", pages, "Bill summary: Arjun Sample. Amount Rs 2516.60. Fictional utility.")


def rent():
    pages = []
    clauses = [
        "Owner: Meera Sample. Tenant: Arjun Sample. Both names are invented.",
        "Rent of Rs 12,000 is payable by the fifth day of each month.",
        "Electricity and water readings are recorded at the date of handover.",
        "Either party gives thirty days of written notice before termination.",
        "The parties confirm they have read the five specimen sheets. END OF SPECIMEN.",
    ]
    for n in range(1, 6):
        im = paper("किरायानामा / RENT AGREEMENT", "FICTIONAL - Not a legal instrument", n, 5)
        write_lines(im, [f"{n}. FICTIONAL CLAUSE", clauses[n - 1],
                         "Premises: 12 Sample Lane, Namuna Nagar - invented address",
                         "No genuine signature or identification number is reproduced.",
                         "Tenant signature --------------------------------",
                         "Owner signature ---------------------------------"],
                    size=32, step=70)
        pages.append(im)
    pdf("rent_agreement_5p.pdf", pages)


def receipts():
    for i, month in enumerate(("aug", "sep", "oct"), 1):
        im = Image.new("RGB", (1150, 1700 + i * 180), (248, 246, 229))
        d = ImageDraw.Draw(im)
        lines = ["NAMUNA STATIONERY", "नमूना स्टेशनरी", "FICTIONAL SHOP RECEIPT",
                 f"Receipt: SAMPLE-{month.upper()}", f"Month: {month} 2026",
                 "Customer: Ravi Sample", "--------------------------",
                 "Notebook        3 x 60.00", "Exam form       1 x 50.00",
                 f"Print pages    {i*10} x 2.00", "--------------------------",
                 f"TOTAL Rs {230+i*20}.00", "Paid: CASH / नकद", "No GST / UPI identifiers",
                 "SAMPLE - Thank you"]
        for j, s in enumerate(lines):
            d.text((80, 110 + j * 96), s, font=font(58 if j == 0 else 42, j == 0), fill=(55, 55, 50))
        im.save(HERE / f"receipt_{month}.jpg", "JPEG", quality=82)
        pdf(f"receipt_{month}.pdf", [im])


def main():
    REF.mkdir(exist_ok=True)
    portrait_im = portrait()
    exam_photo(portrait_im)
    signature()
    card("front", portrait_im)
    card("back", portrait_im)
    marksheet()
    bill()
    rent()
    receipts()
    # A photographed notice for OCR/Hindi journeys (the text itself is the ground truth).
    notice = paper("नमूना नगर पंचायत", "कार्यालय सूचना - FICTIONAL", 1, 1)
    write_lines(notice, NOTICE, y0=270, step=62, size=34)
    camera(notice, "hindi_notice.jpg", "wood")
    thumb = paper("बाएँ अंगूठे का निशान", "SYNTHETIC RIDGES - NOT A REAL BIOMETRIC", 1, 1)
    camera(thumb, "left_thumb.jpg", "steel")
    rx = paper("नमूना स्वास्थ्य केंद्र", "SAMPLE CLINIC - fictional prescription", 1, 1)
    write_lines(rx, ["Dr Fictional / Reg: SAMPLE-ONLY", "Patient: Sunita Sample",
                     "Sample medicine A - NOT FOR USE", "No clinical recommendation in this fixture"],
                size=40)
    camera(rx, "prescription.jpg", "sheet")

    gt = {
        "fictional": True, "seed": SEED, "generator": "tools/e2e/atlas/fixtures_v2/make_fixtures.py",
        "notice_text": "\n".join(NOTICE),
        "bill_page2_only": ["मीटर-संख्या 4471", "Consumer No 88-4471-SAMPLE"],
        "bill_text_layer_pages": [1], "receipt_order": ["aug", "sep", "oct"],
        "signature": {"template": "references/signature_crop.png", "fictional": True},
        "face_geometry_reference": "references/portrait_crop.png",
        "phone_root": "/sdcard/Download/ScanWhirlQA",
        "marksheet_scan_dpi": 300, "marksheet_fine_print_points": 7.44,
        "camera_realism": ["synthetic page photo", "background", "grain"],
    }
    (HERE / "ground_truth.json").write_text(json.dumps(gt, ensure_ascii=False, indent=2), encoding="utf-8")
    files = []
    for p in sorted(HERE.iterdir()):
        if p.suffix.lower() in (".jpg", ".pdf") and p.name != "contact_sheet.jpg":
            files.append({"file": p.name, "bytes": p.stat().st_size,
                          "sha256": hashlib.sha256(p.read_bytes()).hexdigest()})
    (HERE / "manifest.json").write_text(json.dumps(files, indent=2), encoding="utf-8")
    print(f"generated {len(files)} fixtures ({sum(f['bytes'] for f in files)} bytes) in {HERE}")


if __name__ == "__main__":
    main()
