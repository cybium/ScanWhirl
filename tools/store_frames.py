"""Compose Play Store screenshots: headline + framed phone capture on a branded background, 1080x1920.
Usage (WSL): python3 tools/store_frames.py <raw_dir> <out_dir>
Captions come from CAPTIONS below (file stem -> (headline, subline)); raw files without a caption are skipped.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RAW, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
W, H = 1080, 1920
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

CAPTIONS = {
    "01_home": ("Scan to PDF on your phone", "No ads. No account. Your files stay on your phone."),
    "02_tools": ("35+ PDF tools", "Merge, compress, convert, sign, protect and more"),
    "04_text_ocr": ("Read text from any page", "English and Hindi, right on your phone"),
    "05_pro": ("Pro: pay once, keep it forever", "No subscription. Works on all your phones."),
    "06_private": ("Hide private info", "Cover phone numbers, emails and ID numbers before you share"),
    "06a_private_choices": ("Choose what to hide", "Phone numbers, emails, ID numbers and card numbers"),
    "08_vault": ("Vault for sensitive files", "Locked behind your fingerprint"),
    "09_backup": ("One encrypted backup", "Every scan, the Vault and your settings in one file"),
}


def background():
    img = Image.new("RGB", (W, H), (9, 16, 20))
    glow = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse((-300, -200, W + 300, 900), fill=(8, 92, 104))
    glow = glow.filter(ImageFilter.GaussianBlur(220))
    return Image.blend(img, glow, 0.55)


def fit_text(draw, text, font_path, size, max_w):
    while size > 20:
        f = ImageFont.truetype(font_path, size)
        if draw.textlength(text, font=f) <= max_w:
            return f
        size -= 2
    return ImageFont.truetype(font_path, size)


def wrap(draw, text, font, max_w):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if draw.textlength(t, font=font) <= max_w:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def frame(raw_path, headline, subline, out_path):
    img = background()
    d = ImageDraw.Draw(img)
    f1 = fit_text(d, headline, BOLD, 74, W - 120)
    d.text((W / 2, 120), headline, font=f1, fill=(255, 255, 255), anchor="mm")
    f2 = ImageFont.truetype(REG, 38)
    y = 200
    for line in wrap(d, subline, f2, W - 160):
        d.text((W / 2, y), line, font=f2, fill=(110, 230, 240), anchor="mm")
        y += 50

    shot = Image.open(raw_path).convert("RGB")
    target_h = H - 330
    scale = target_h / shot.height
    shot = shot.resize((int(shot.width * scale), target_h), Image.LANCZOS)
    x = (W - shot.width) // 2
    top = 300
    radius = 42
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), radius, fill=255)
    shadow = Image.new("RGBA", (shot.width + 80, shot.height + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((40, 40, shot.width + 40, shot.height + 40), radius, fill=(0, 0, 0, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(24))
    img.paste(shadow, (x - 40, top - 28), shadow)
    border = Image.new("RGBA", (shot.width + 12, shot.height + 12), (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle((0, 0, shot.width + 11, shot.height + 11), radius + 6, fill=(40, 210, 225, 255))
    img.paste(border, (x - 6, top - 6), border)
    img.paste(shot, (x, top), mask)
    img.save(out_path, "PNG")


done = []
for name in sorted(os.listdir(RAW)):
    stem, ext = os.path.splitext(name)
    if ext.lower() != ".png" or stem not in CAPTIONS:
        continue
    head, sub = CAPTIONS[stem]
    out = os.path.join(OUT, stem + ".png")
    frame(os.path.join(RAW, name), head, sub, out)
    done.append(out)
print("framed:", [os.path.basename(p) for p in done])
