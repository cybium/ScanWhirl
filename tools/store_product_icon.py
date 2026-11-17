"""Play one-time product icon for ScanWhirl Pro: the in-app gold brand mark (ic_brand_mark_gold.xml,
same geometry as the launcher icon) on the launcher's opaque full-bleed ground. No text.
Usage (WSL): python3 tools/store_product_icon.py /mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/store/product_pro_512.png
"""
import sys

from PIL import Image, ImageDraw

S = 2048                      # supersampled, downscaled to 512
GROUND = (0x16, 0x21, 0x3A)   # @color/sw_brand_ground (launcher icon background)
GOLD = (0xFF, 0xB8, 0x00)     # @color/gold_pro_primary / pro_gold
PAPER = (0xE9, 0xE8, 0xE3)    # @color/sw_paper

# Mark geometry in the launcher's 108-unit space, centred on (54, 54). Mark spans x 27.65..80.35.
MARK_FRACTION = 0.66          # mark width / canvas width
scale = S * MARK_FRACTION / (80.35 - 27.65)
off = S / 2 - 54 * scale


def p(x, y):
    return off + x * scale, off + y * scale


def circle(draw, cx, cy, r, fill):
    x0, y0 = p(cx - r, cy - r)
    x1, y1 = p(cx + r, cy + r)
    draw.ellipse((x0, y0, x1, y1), fill=fill)


def rrect(draw, l, t, r, b, rad, fill):
    draw.rounded_rectangle((*p(l, t), *p(r, b)), radius=rad * scale, fill=fill)


# Rings: outer disc minus offset hole (evenOdd in the vector).
rings = Image.new("L", (S, S), 0)
rd = ImageDraw.Draw(rings)
circle(rd, 41.73, 40.12, 14.08, 255)
circle(rd, 42.40, 40.88, 9.05, 0)
circle(rd, 66.27, 67.88, 14.08, 255)
circle(rd, 65.60, 67.12, 9.05, 0)
# Clip: a gap around the page (the vector's clip-path hole).
rrect(rd, 38.21, 35.70, 69.79, 72.30, 4.22, 0)

img = Image.new("RGB", (S, S), GROUND)
img.paste(Image.new("RGB", (S, S), GOLD), mask=rings)
d = ImageDraw.Draw(img)
# Page with three text lines cut out to the ground colour.
rrect(d, 40.42, 37.91, 67.58, 70.09, 2.01, PAPER)
rrect(d, 45.65, 44.95, 55.00, 48.57, 1.81, GROUND)
rrect(d, 45.65, 51.99, 62.35, 55.61, 1.81, GROUND)
rrect(d, 45.65, 59.03, 62.35, 62.65, 1.81, GROUND)

img.resize((512, 512), Image.LANCZOS).save(sys.argv[1], "PNG")
print("wrote", sys.argv[1])
