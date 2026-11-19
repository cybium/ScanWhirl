"""Procedurally generated, legally clean synthetic training data for Scanner X.

Everything here is drawn by this script: paper sheets of different kinds, text-like
strokes (uniform dashes, never real words), tables, receipt columns, cards, and
procedural backgrounds (wood, fabric, marble, desk clutter, wall/door, emissive
screens with UI blocks). No dataset, photo, font glyph or text is downloaded or
copied, so the output is commercially usable.

Each sample yields image + paper mask + the four paper corners used to render it.
Screen-like emissive rectangles and empty surfaces are labelled has_doc=0 (hard
negatives): the model must not auto-capture a monitor or a door.

Outputs (private, outside Git):
  qa/scannerx-ml/commercial/synth/images/<id>.png
  qa/scannerx-ml/commercial/synth/masks/<id>.png
  qa/scannerx-ml/commercial/synth/manifest.csv
  qa/scannerx-ml/synth_contact_sheet.png   (24 samples with corner overlay)

Usage:
  python synth.py --n 300 --seed 20261119
  python synth.py --n 300 --seed 20261119 --out "<qa>\\scannerx-ml\\commercial\\synth"
  python synth.py --sheet-only ... (rebuild the contact sheet from an existing run)
"""

from __future__ import annotations

import argparse
import csv
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from mlcommon import is_convex_quad, polygon_area, rasterize_mask, sha256_file

PAPER_KINDS = ("white_a4", "cream", "receipt", "card", "notebook")
BACKGROUND_KINDS = ("wood", "fabric", "marble", "desk", "wall", "screen")
NEGATIVE_BACKGROUNDS = ("desk", "wall", "screen")  # screen is the hard negative


# --------------------------------------------------------------------------
# Noise / background primitives (numpy + PIL only, deterministic per rng)
# --------------------------------------------------------------------------
def _value_noise(rng, h: int, w: int, res: int) -> np.ndarray:
    grid = rng.random((max(2, res), max(2, res))).astype(np.float32)
    image = Image.fromarray((grid * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
    return np.asarray(image, dtype=np.float32) / 255.0


def _fbm(rng, h: int, w: int, base: int = 6, octaves: int = 4) -> np.ndarray:
    total = np.zeros((h, w), dtype=np.float32)
    amplitude = 1.0
    norm = 0.0
    for level in range(octaves):
        total += amplitude * _value_noise(rng, h, w, base * (2**level))
        norm += amplitude
        amplitude *= 0.5
    return total / norm


def _shade(color, amount: np.ndarray) -> np.ndarray:
    base = np.asarray(color, dtype=np.float32) / 255.0
    return np.clip(base[None, None, :] * (0.55 + 0.9 * amount)[..., None], 0, 1)


def _aniso_noise(rng, h: int, w: int, gy: int, gx: int) -> np.ndarray:
    """Noise with independent resolution per axis (long fibres vs blotches)."""
    grid = rng.random((max(2, gy), max(2, gx))).astype(np.float32)
    image = Image.fromarray((grid * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
    return np.asarray(image, dtype=np.float32) / 255.0


def _bg_wood(rng, h: int, w: int) -> np.ndarray:
    # Anisotropic: long horizontal fibres, fine vertical grain stripes, broad blotches.
    fibre = _aniso_noise(rng, h, w, gy=max(4, h // 26), gx=max(8, w // 5))
    blotch = _aniso_noise(rng, h, w, gy=5, gx=5)
    period = rng.uniform(7.0, 19.0)
    grain = 0.5 + 0.5 * np.sin(np.arange(h, dtype=np.float32)[:, None] * (2 * np.pi / period) + fibre * 8.0 + blotch * 3.0)
    field = 0.42 * grain + 0.38 * fibre + 0.20 * blotch
    image = _shade((146, 100, 62), field) * rng.uniform(0.85, 1.12)
    image = np.clip(image, 0, 1)
    for _ in range(int(rng.integers(0, 4))):  # knots / dark streaks
        y = int(rng.integers(0, h))
        thickness = int(rng.integers(2, 7))
        image[max(0, y - thickness): y + thickness, :] *= rng.uniform(0.7, 0.88)
    for _ in range(int(rng.integers(0, 3))):  # plank seams
        y = int(rng.integers(0, h))
        image[max(0, y - 1): y + 2, :] *= 0.6
    return image


def _bg_fabric(rng, h: int, w: int) -> np.ndarray:
    noise = _fbm(rng, h, w, base=3, octaves=4)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    weave = 0.5 + 0.22 * np.sin(xx * rng.uniform(1.0, 2.2) + noise * 4.0) * np.sin(yy * rng.uniform(1.0, 2.2) + noise * 4.0)
    colour = [(96, 104, 118), (78, 92, 74), (112, 96, 88), (60, 66, 82)][int(rng.integers(0, 4))]
    field = 0.45 * noise + 0.55 * weave
    return np.clip(_shade(colour, field) * rng.uniform(0.8, 1.1), 0, 1)


def _bg_marble(rng, h: int, w: int) -> np.ndarray:
    noise = _fbm(rng, h, w, base=5, octaves=5)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    phase = (xx * rng.uniform(0.004, 0.012) + yy * rng.uniform(0.002, 0.008) + noise * 7.0) * np.pi
    veins = np.abs(np.sin(phase)) ** 0.14
    field = 0.55 + 0.45 * veins
    return np.clip(_shade((206, 202, 196), field) * rng.uniform(0.85, 1.05), 0, 1)


def _bg_desk(rng, h: int, w: int) -> Image.Image:
    base = _bg_wood(rng, h, w) if rng.random() < 0.6 else _bg_fabric(rng, h, w)
    canvas = Image.fromarray((np.clip(base, 0, 1) * 255).astype(np.uint8))
    draw = ImageDraw.Draw(canvas)
    for _ in range(int(rng.integers(3, 9))):  # clutter: books, papers, mugs, pens
        kind = int(rng.integers(0, 4))
        x0, y0 = float(rng.integers(0, max(1, w - 40))), float(rng.integers(0, max(1, h - 40)))
        if kind == 0:
            x1 = x0 + rng.uniform(40, 220)
            y1 = y0 + rng.uniform(30, 160)
            colour = tuple(int(c) for c in rng.integers(30, 210, 3))
            draw.rectangle([x0 + 6, y0 + 6, x1 + 6, y1 + 6], fill=(28, 24, 20))
            draw.rectangle([x0, y0, x1, y1], fill=colour, outline=(20, 20, 20), width=2)
        elif kind == 1:
            r = rng.uniform(18, 52)
            draw.ellipse([x0, y0, x0 + r * 2, y0 + r * 2], fill=(40, 36, 34), outline=(150, 150, 150), width=2)
            draw.ellipse([x0 + 8, y0 + 8, x0 + r * 2 - 8, y0 + r * 2 - 8], fill=(96, 70, 54))
        elif kind == 2:
            draw.line([x0, y0, x0 + rng.uniform(80, 260), y0 + rng.uniform(-90, 90)], fill=tuple(int(c) for c in rng.integers(20, 120, 3)), width=int(rng.integers(3, 9)))
        else:
            draw.rectangle([x0, y0, x0 + rng.uniform(60, 200), y0 + rng.uniform(40, 120)], outline=tuple(int(c) for c in rng.integers(120, 240, 3)), width=3)
    return canvas


def _bg_wall(rng, h: int, w: int) -> np.ndarray:
    noise = _fbm(rng, h, w, base=3, octaves=4)
    tone = np.linspace(rng.uniform(0.75, 1.15), rng.uniform(0.6, 1.0), h, dtype=np.float32)[:, None]
    field = 0.75 * noise + 0.25 * tone
    colour = [(214, 208, 198), (186, 188, 190), (201, 190, 176)][int(rng.integers(0, 3))]
    return np.clip(_shade(colour, field) * 0.92, 0, 1)


def _bg_screen(rng, h: int, w: int) -> Image.Image:
    """Emissive monitor with UI blocks: the primary hard negative for auto-capture."""
    canvas = Image.new("RGB", (w, h), (18, 18, 20))
    draw = ImageDraw.Draw(canvas)
    x0, y0 = w * rng.uniform(0.05, 0.2), h * rng.uniform(0.08, 0.3)
    x1, y1 = w * rng.uniform(0.75, 0.95), h * rng.uniform(0.7, 0.92)
    draw.rectangle([x0 - 8, y0 - 8, x1 + 8, y1 + 8], fill=(32, 32, 34))
    draw.rectangle([x0, y0, x1, y1], fill=(238, 240, 244))  # bright page-like panel
    width, height = x1 - x0, y1 - y0
    draw.rectangle([x0, y0, x1, y0 + height * 0.08], fill=(54, 88, 148))
    for row in range(int(rng.integers(3, 6))):  # cards / panels
        cy0 = y0 + height * (0.14 + row * 0.15)
        if cy0 + height * 0.12 > y1:
            break
        draw.rounded_rectangle(
            [x0 + width * 0.06, cy0, x1 - width * rng.uniform(0.2, 0.55), cy0 + height * 0.11],
            radius=int(max(2, width * 0.012)),
            fill=tuple(int(c) for c in rng.integers(225, 252, 3)),
            outline=(196, 200, 208),
        )
        bar_w = 0.0
        for _ in range(int(rng.integers(2, 5))):
            seg = width * rng.uniform(0.08, 0.3)
            draw.rectangle(
                [x0 + width * 0.09 + bar_w, cy0 + height * 0.035, x0 + width * 0.09 + bar_w + seg, cy0 + height * 0.06],
                fill=(120, 126, 138),
            )
            bar_w += seg + width * 0.02
    return canvas


def render_background(kind: str, rng, h: int, w: int) -> Image.Image:
    if kind == "wood":
        return Image.fromarray((_bg_wood(rng, h, w) * 255).astype(np.uint8))
    if kind == "fabric":
        return Image.fromarray((_bg_fabric(rng, h, w) * 255).astype(np.uint8))
    if kind == "marble":
        return Image.fromarray((_bg_marble(rng, h, w) * 255).astype(np.uint8))
    if kind == "desk":
        return _bg_desk(rng, h, w)
    if kind == "wall":
        return Image.fromarray((_bg_wall(rng, h, w) * 255).astype(np.uint8))
    if kind == "screen":
        return _bg_screen(rng, h, w)
    raise ValueError(kind)


# --------------------------------------------------------------------------
# Paper rendering (procedural content, no real text)
# --------------------------------------------------------------------------
def _text_lines(draw, x0, y0, x1, y1, rng, colour, line_h, stroke_h, gap=0.10):
    y = y0
    while y + stroke_h <= y1:
        if rng.random() < gap:
            y += line_h
            continue
        x = x0
        while True:
            seg = int(rng.integers(5, 30))
            if x + seg > x1 - 2:
                break
            draw.rectangle([x, y, x + seg, y + stroke_h], fill=colour)
            x += seg + int(rng.integers(3, 9))
        y += line_h


def _heading(draw, x0, y0, x1, rng, colour, height):
    width = (x1 - x0) * rng.uniform(0.35, 0.7)
    draw.rectangle([x0, y0, x0 + width, y0 + height], fill=colour)


def make_paper(kind: str, rng) -> tuple[Image.Image, str]:
    """Return a paper image plus a short description of the content drawn on it."""
    if kind == "receipt":
        width, height = 300, 900
    elif kind == "card":
        width, height = 856, 540
    else:
        width, height = 595, 842
    base = {
        "white_a4": (250, 250, 247),
        "cream": (247, 240, 222),
        "receipt": (252, 252, 249),
        "card": (242, 244, 247),
        "notebook": (248, 246, 238),
    }[kind]
    sheet = Image.new("RGB", (width, height), base)
    draw = ImageDraw.Draw(sheet)
    ink = (36, 34, 32)
    margin = int(width * 0.09)
    content = "text"

    if kind in ("white_a4", "cream"):
        if rng.random() < 0.45:
            _heading(draw, margin, margin, width - margin, rng, ink, int(height * 0.018))
            y = margin + int(height * 0.05)
        else:
            y = margin
        blocks = int(rng.integers(2, 5))
        for block in range(blocks):
            style = rng.random()
            if style < 0.15:
                content = "table"
                cols = int(rng.integers(2, 5))
                rows = int(rng.integers(3, 7))
                table_w, row_h = width - 2 * margin, int(height * 0.035)
                for row in range(rows):
                    for col in range(cols + 1):
                        cx = margin + int(table_w * col / cols)
                        draw.line([cx, y, cx, y + rows * row_h], fill=(150, 150, 150), width=1)
                    draw.line([margin, y + row * row_h, margin + table_w, y + row * row_h], fill=(150, 150, 150), width=1)
                    for col in range(cols):
                        cx0 = margin + int(table_w * col / cols) + 6
                        cx1 = margin + int(table_w * (col + 1) / cols) - 8
                        if cx1 - cx0 > 10:
                            seg = int((cx1 - cx0) * rng.uniform(0.3, 0.9))
                            draw.rectangle([cx0, y + row * row_h + 8, cx0 + seg, y + row * row_h + 13], fill=ink)
                y += rows * row_h + int(height * 0.03)
            else:
                _text_lines(draw, margin, y, width - margin, y + int(height * 0.16), rng, ink, int(height * 0.026), int(height * 0.011))
                y += int(height * 0.18)
            if y > height - int(height * 0.15):
                break
    elif kind == "receipt":
        content = "receipt"
        _heading(draw, int(width * 0.28), margin, int(width * 0.72), rng, ink, 16)
        y = margin + 32
        for _ in range(int(rng.integers(5, 10))):
            _text_lines(draw, margin, y, width - margin, y + 26, rng, ink, 16, 6, gap=0.0)
            if rng.random() < 0.35:
                price = int(rng.integers(18, 70))
                draw.rectangle([width - margin - price, y, width - margin, y + 6], fill=ink)
            y += 22
            if y > height * 0.78:
                break
        y = int(height * 0.8)
        for _ in range(int(rng.integers(2, 4))):
            draw.line([margin, y, width - margin, y], fill=(120, 120, 120), width=2)
            y += 8
        draw.rectangle([margin, y, width - margin, y + 10], fill=ink)  # total bar
        bar_y = int(height * 0.9)
        x = margin
        while x < width - margin:  # barcode
            bar = int(rng.integers(1, 4))
            draw.rectangle([x, bar_y, x + bar, height - 40], fill=ink)
            x += bar + int(rng.integers(2, 6))
    elif kind == "card":
        content = "card"
        draw.rounded_rectangle([margin, int(height * 0.16), margin + 92, int(height * 0.16) + 66], radius=10, outline=(178, 150, 70), width=3, fill=(214, 186, 96))
        draw.rectangle([margin, int(height * 0.44), width - margin, int(height * 0.5)], fill=(52, 54, 62))  # magstripe
        _text_lines(draw, margin, int(height * 0.58), width - margin, int(height * 0.75), rng, ink, 22, 9, gap=0.0)
        x = margin
        while x < width * 0.55:  # number groups
            group = int(rng.integers(30, 60))
            draw.rectangle([x, int(height * 0.82), x + group, int(height * 0.86)], fill=(90, 90, 96))
            x += group + 26
    else:  # notebook
        content = "ruled"
        line_h = int(height * 0.035)
        y = int(height * 0.12)
        while y < height - margin:
            draw.line([margin, y, width - margin, y], fill=(186, 206, 226), width=1)
            y += line_h
        draw.line([margin * 1.6, 0, margin * 1.6, height], fill=(206, 140, 140), width=2)
        y = int(height * 0.12)
        while y < height * 0.7:
            if rng.random() < 0.7:
                _text_lines(draw, margin * 1.8, y, width - margin, y + line_h, rng, (60, 62, 86), line_h, 7, gap=0.0)
            y += line_h * 2

    # Paper fibre and slight tonal variation.
    arr = np.asarray(sheet, dtype=np.float32)
    arr += rng.normal(0.0, 2.6, arr.shape[:2])[..., None]
    arr *= rng.uniform(0.985, 1.0)
    sheet = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    return sheet, content


# --------------------------------------------------------------------------
# Geometry: quad placement, homography, compositing
# --------------------------------------------------------------------------
def _perspective_coeffs(dst, src):
    """PIL PERSPECTIVE coefficients mapping output (dst) pixels back to source."""
    rows, rhs = [], []
    for (x, y), (sx, sy) in zip(dst, src):
        rows.append([x, y, 1, 0, 0, 0, -sx * x, -sx * y])
        rhs.append(sx)
        rows.append([0, 0, 0, x, y, 1, -sy * x, -sy * y])
        rhs.append(sy)
    solution = np.linalg.solve(np.asarray(rows, np.float64), np.asarray(rhs, np.float64))
    return tuple(solution.tolist())


def _quad_from_placement(rng, canvas_w, canvas_h, paper_ratio, area_frac):
    """Place a rotated, perspective-jittered paper quad inside the canvas."""
    for _ in range(60):
        display_w = np.sqrt(area_frac * canvas_w * canvas_h * paper_ratio)
        display_h = display_w / paper_ratio
        if display_h > 0.86 * canvas_h or display_w > 0.86 * canvas_w:
            scale = min(0.86 * canvas_w / display_w, 0.86 * canvas_h / display_h)
            display_w, display_h = display_w * scale, display_h * scale
        if display_w < 40 or display_h < 40:
            continue
        cx = rng.uniform(0.18, 0.82) * canvas_w
        cy = rng.uniform(0.18, 0.82) * canvas_h
        angle = np.deg2rad(rng.uniform(-32, 32))
        cos_a, sin_a = np.cos(angle), np.sin(angle)
        half_w, half_h = display_w / 2.0, display_h / 2.0
        base = [(-half_w, -half_h), (half_w, -half_h), (half_w, half_h), (-half_w, half_h)]
        jitter = 0.055 * display_w
        points = []
        for bx, by in base:
            x = bx + rng.normal(0, jitter)
            y = by + rng.normal(0, jitter * 0.6)
            points.append((cx + x * cos_a - y * sin_a, cy + x * sin_a + y * cos_a))
        margin = 4.0
        points = [(min(max(x, margin), canvas_w - margin), min(max(y, margin), canvas_h - margin)) for x, y in points]
        if is_convex_quad(points) and polygon_area(points) > 0.04 * canvas_w * canvas_h:
            return [(float(x), float(y)) for x, y in points], display_w, display_h
    return None, 0.0, 0.0


def _soft_alpha(corners, size, supersample=4) -> np.ndarray:
    width, height = size
    big = Image.new("L", (width * supersample, height * supersample), 0)
    ImageDraw.Draw(big).polygon([(x * supersample, y * supersample) for x, y in corners], fill=255)
    return np.asarray(big.resize((width, height), Image.LANCZOS), np.float32) / 255.0


def _apply_lighting(image: np.ndarray, rng, alpha: np.ndarray) -> tuple[np.ndarray, dict]:
    h, w = alpha.shape
    flags = {"glare": 0, "dim": 0, "shadow": 0}
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    # Directional light falloff across the whole frame.
    gx, gy = rng.uniform(-0.5, 0.5), rng.uniform(-0.45, 0.45)
    plane = 1.0 + gx * (xx / w - 0.5) + gy * (yy / h - 0.5)
    if rng.random() < 0.55:  # paper drops a shadow
        flags["shadow"] = 1
        shadow_mask = Image.fromarray((alpha * 255).astype(np.uint8))
        offset = (int(rng.integers(6, 26)), int(rng.integers(6, 26)))
        shifted = Image.new("L", (w, h), 0)
        shifted.paste(shadow_mask, offset)
        shadow = np.asarray(shifted.filter(ImageFilter.GaussianBlur(rng.uniform(6, 16))), np.float32) / 255.0
        plane = plane * (1.0 - 0.45 * shadow)
    image = image * plane[..., None]
    if rng.random() < 0.35:  # dim / low light
        flags["dim"] = 1
        image = image * rng.uniform(0.35, 0.72) + np.asarray([8, 9, 12], np.float32) / 255.0
    if rng.random() < 0.35:  # glare blob, sometimes over the page
        flags["glare"] = 1
        cx = rng.uniform(0, w) if rng.random() < 0.5 else rng.uniform(0.3, 0.7) * w
        cy = rng.uniform(0, h) if rng.random() < 0.5 else rng.uniform(0.3, 0.7) * h
        radius = rng.uniform(0.18, 0.55) * max(h, w)
        dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / radius
        glow = np.exp(-(dist**2) * 2.2) * rng.uniform(0.18, 0.8)
        image = image + glow[..., None] * np.asarray([1.0, 0.995, 0.97], np.float32)
    return np.clip(image, 0, 1), flags


def generate_sample(index: int, rng, canvas_choices=None) -> dict:
    """Generate one sample: image, mask, corners, has_doc and provenance metadata."""
    if canvas_choices is None:
        canvas_choices = [(720, 960), (960, 720), (800, 800), (640, 960), (900, 1080)]
    canvas_w, canvas_h = canvas_choices[int(rng.integers(0, len(canvas_choices)))]
    is_negative = rng.random() < 0.28
    bg_kind = NEGATIVE_BACKGROUNDS[int(rng.integers(0, len(NEGATIVE_BACKGROUNDS)))] if is_negative else BACKGROUND_KINDS[int(rng.integers(0, len(BACKGROUND_KINDS)))]

    background = render_background(bg_kind, rng, canvas_h, canvas_w)
    canvas = np.asarray(background, np.float32) / 255.0

    info = {
        "id": f"{index:05d}",
        "has_doc": 0,
        "paper_kind": "none",
        "content": "none",
        "background": bg_kind,
        "hard_negative": int(bg_kind == "screen"),
        "glare": 0,
        "dim": 0,
        "shadow": 0,
        "blur": 0.0,
        "jpeg_quality": 0,
        "noise": 0.0,
    }

    if not is_negative:
        paper_kind = PAPER_KINDS[int(rng.integers(0, len(PAPER_KINDS)))]
        paper, content = make_paper(paper_kind, rng)
        ratio = paper.width / paper.height
        corners, display_w, display_h = _quad_from_placement(
            rng, canvas_w, canvas_h, ratio, float(rng.uniform(0.10, 0.42))
        )
        if corners is None:
            info["has_doc"] = 0
            alpha = np.zeros((canvas_h, canvas_w), np.float32)
        else:
            render_w = int(max(display_w, display_h, 220) * 1.1)
            render_w = min(render_w, 900)
            render_h = int(render_w / ratio)
            if render_h > 1200:
                render_h = 1200
                render_w = int(render_h * ratio)
            paper_scaled = paper.resize((render_w, render_h), Image.LANCZOS)
            source = [(0.0, 0.0), (float(render_w), 0.0), (float(render_w), float(render_h)), (0.0, float(render_h))]
            coeffs = _perspective_coeffs(corners, source)
            warped = paper_scaled.transform((canvas_w, canvas_h), Image.PERSPECTIVE, coeffs, resample=Image.BILINEAR)
            warped_arr = np.asarray(warped, np.float32) / 255.0
            alpha = _soft_alpha(corners, (canvas_w, canvas_h))
            # Receipt paper lets a little background through (thin, slightly translucent).
            opacity = 0.93 if paper_kind == "receipt" else 1.0
            blended = canvas * (1 - alpha[..., None] * opacity) + warped_arr * (alpha[..., None] * opacity)
            canvas = blended
            info.update(
                has_doc=1,
                paper_kind=paper_kind,
                content=content,
                corners=[[round(float(x), 2), round(float(y), 2)] for x, y in corners],
            )
    else:
        alpha = np.zeros((canvas_h, canvas_w), np.float32)

    canvas, flags = _apply_lighting(canvas, rng, alpha)
    info.update(flags)

    image = Image.fromarray((canvas * 255).astype(np.uint8))
    if rng.random() < 0.4:  # motion / focus blur
        radius = float(rng.uniform(0.4, 2.2))
        info["blur"] = round(radius, 2)
        image = image.filter(ImageFilter.GaussianBlur(radius))
    if rng.random() < 0.5:  # sensor noise
        sigma = float(rng.uniform(1.5, 7.0))
        info["noise"] = round(sigma, 2)
        arr = np.asarray(image, np.float32) + rng.normal(0, sigma, (canvas_h, canvas_w, 1))
        image = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    quality = int(rng.integers(55, 96))  # JPEG round-trip so artefacts are realistic
    info["jpeg_quality"] = quality
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=quality)
    image = Image.open(io.BytesIO(buffer.getvalue())).convert("RGB")

    mask = (
        rasterize_mask(info["corners"], (canvas_w, canvas_h))
        if info["has_doc"]
        else np.zeros((canvas_h, canvas_w), np.uint8)
    )
    info["image"] = image
    info["mask"] = mask
    info["size"] = (canvas_w, canvas_h)
    return info


# --------------------------------------------------------------------------
# Dataset writing + contact sheet
# --------------------------------------------------------------------------
def write_dataset(out_dir: Path, count: int, seed: int, log=print) -> list[dict]:
    images_dir = out_dir / "images"
    masks_dir = out_dir / "masks"
    images_dir.mkdir(parents=True, exist_ok=True)
    masks_dir.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    rows = []
    for index in range(count):
        sample = generate_sample(index, rng)
        image_path = images_dir / f"{sample['id']}.png"
        mask_path = masks_dir / f"{sample['id']}.png"
        sample["image"].save(image_path)
        Image.fromarray(sample["mask"]).save(mask_path)
        rows.append(
            {
                "id": sample["id"],
                "split": "synth",
                "has_doc": sample["has_doc"],
                "paper_kind": sample["paper_kind"],
                "content": sample["content"],
                "background": sample["background"],
                "hard_negative": sample["hard_negative"],
                "glare": sample["glare"],
                "dim": sample["dim"],
                "shadow": sample["shadow"],
                "blur": sample["blur"],
                "noise": sample["noise"],
                "jpeg_quality": sample["jpeg_quality"],
                "width": sample["size"][0],
                "height": sample["size"][1],
                "corners": json.dumps(sample["corners"]) if sample["has_doc"] else "",
                "image": f"images/{sample['id']}.png",
                "mask": f"masks/{sample['id']}.png",
                "mask_sha256": sha256_file(mask_path),
            }
        )
        if (index + 1) % 25 == 0:
            log(f"  synth: {index + 1}/{count}")
    columns = list(rows[0].keys())
    with open(out_dir / "manifest.csv", "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
    positives = sum(r["has_doc"] for r in rows)
    log(f"synth: {len(rows)} samples ({positives} positive, {len(rows) - positives} negative, "
        f"{sum(r['hard_negative'] for r in rows)} screen hard negatives) -> {out_dir}")
    return rows


def build_contact_sheet(out_dir: Path, sheet_path: Path, count: int = 24, log=print) -> Path:
    """Render 24 existing samples with their corner quads for a visual label check."""
    import csv as _csv

    with open(out_dir / "manifest.csv", encoding="utf-8", newline="") as handle:
        rows = list(_csv.DictReader(handle))
    positives = [r for r in rows if r["has_doc"] == "1"]
    negatives = [r for r in rows if r["has_doc"] == "0"]
    hard = [r for r in negatives if r["hard_negative"] == "1"]
    plain_neg = [r for r in negatives if r["hard_negative"] != "1"]

    picks = []
    want_neg = max(3, count // 6)
    picks.extend(hard[: max(1, want_neg // 2)])
    picks.extend(plain_neg[: max(1, want_neg - max(1, want_neg // 2))])
    remaining = count - len(picks)
    if positives:
        step = max(1, len(positives) // remaining)
        picks.extend(positives[::step][:remaining])
    picks = picks[:count]

    tile_w, img_h, mask_h, cap_h = 300, 250, 60, 22
    cols = 6
    rows_n = (len(picks) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tile_w, rows_n * (img_h + mask_h + cap_h)), (24, 24, 26))
    draw = ImageDraw.Draw(sheet)
    for position, row in enumerate(picks):
        image = Image.open(out_dir / row["image"]).convert("RGB")
        mask = Image.open(out_dir / row["mask"])
        tile_x = (position % cols) * tile_w
        tile_y = (position // cols) * (img_h + mask_h + cap_h)

        scale = min((tile_w - 8) / image.width, (img_h - 8) / image.height)
        thumb = image.resize((max(1, int(image.width * scale)), max(1, int(image.height * scale))), Image.LANCZOS)
        ox, oy = tile_x + (tile_w - thumb.width) // 2, tile_y + (img_h - thumb.height) // 2
        sheet.paste(thumb, (ox, oy))

        corners = json.loads(row["corners"]) if row["corners"] else None
        if corners:
            points = [(ox + x * scale, oy + y * scale) for x, y in corners]
            draw.line(points + [points[0]], fill=(40, 230, 90), width=2)
            for px, py in points:
                draw.ellipse([px - 2, py - 2, px + 2, py + 2], fill=(255, 70, 70))
        else:
            draw.rectangle([tile_x + 3, tile_y + 3, tile_x + tile_w - 3, tile_y + img_h - 3], outline=(230, 60, 60), width=2)

        mask_thumb = mask.resize((max(1, int(mask.width * (mask_h - 6) / mask.height)), mask_h - 6), Image.LANCZOS).convert("RGB")
        my = tile_y + img_h + 3
        sheet.paste(mask_thumb, (tile_x + 4, my))
        if corners:
            msx = (mask_h - 6) / mask.height
            mpoints = [(tile_x + 4 + x * msx, my + y * msx) for x, y in corners]
            draw.line(mpoints + [mpoints[0]], fill=(70, 220, 120), width=1)
        caption = f"{row['id']} {row['paper_kind']}/{row['background']} {'POS' if row['has_doc'] == '1' else 'NEG'}"
        draw.text((tile_x + 5, tile_y + img_h + mask_h + 4), caption, fill=(225, 225, 225))

    sheet_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(sheet_path)
    log(f"contact sheet: {sheet_path} ({len(picks)} tiles)")
    return sheet_path


def main(argv=None) -> int:
    default_out = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\commercial\synth")
    default_sheet = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\synth_contact_sheet.png")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--n", type=int, default=300)
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--out", type=Path, default=default_out)
    parser.add_argument("--sheet", type=Path, default=default_sheet)
    parser.add_argument("--sheet-only", action="store_true", help="rebuild only the contact sheet")
    args = parser.parse_args(argv)
    if "lanes" in str(args.out) or "current" in str(args.out):
        raise SystemExit("refusing to write generated data inside the Git checkout")
    if not args.sheet_only:
        write_dataset(args.out, args.n, args.seed)
    build_contact_sheet(args.out, args.sheet)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
