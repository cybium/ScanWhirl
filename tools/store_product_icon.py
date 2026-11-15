"""Play one-time product icon for ScanWhirl Pro: gold award medal, no text, no logo. 1024x1024 rendered, 512x512 out.
Usage (WSL): python3 tools/store_product_icon.py /mnt/c/Users/Cybium/Desktop/.sw-qa/store/product_pro_512.png
"""
import math
import sys

from PIL import Image, ImageDraw, ImageFilter

S = 1024
out = sys.argv[1]
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
# background: deep navy rounded square with a warm glow
d.rounded_rectangle((0, 0, S - 1, S - 1), radius=200, fill=(18, 24, 38, 255))
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((170, 120, 854, 804), fill=(255, 190, 40, 90))
img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(110)))
d = ImageDraw.Draw(img)

GOLD = (255, 196, 44, 255)
GOLD_DARK = (214, 140, 10, 255)
cx, cy = S // 2, 430
# ribbons
for sx in (-1, 1):
    x0 = cx + sx * 70
    pts = [(x0 - 75, cy + 150), (x0 + 75, cy + 150), (x0 + 75 + sx * 10, cy + 470), (x0 + sx * 10, cy + 405), (x0 - 75 + sx * 10, cy + 470)]
    d.polygon(pts, fill=GOLD_DARK)
# medal rings
d.ellipse((cx - 250, cy - 250, cx + 250, cy + 250), fill=GOLD)
d.ellipse((cx - 205, cy - 205, cx + 205, cy + 205), fill=(18, 24, 38, 255))
d.ellipse((cx - 180, cy - 180, cx + 180, cy + 180), fill=GOLD)
# five-point star (dark, inside the gold disc)
r_out, r_in = 140, 58
star = []
for i in range(10):
    a = -math.pi / 2 + i * math.pi / 5
    r = r_out if i % 2 == 0 else r_in
    star.append((cx + r * math.cos(a), cy + r * math.sin(a)))
d.polygon(star, fill=(18, 24, 38, 255))

img.resize((512, 512), Image.LANCZOS).save(out, "PNG")
print("wrote", out)
