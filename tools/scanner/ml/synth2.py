"""Second synthetic generator: the scenes v1 could not see (screens, clutter, hands).

Why a second generator instead of editing ``synth.py``: the v1 pool is referenced by the v1
run's provenance (its generator SHA256 is recorded), so it must stay byte-identical. This
module imports the v1 primitives it needs (paper rendering, perspective, lighting) and adds:

* **composite backgrounds** from the registered CC0 texture pool (real wood/fabric/concrete
  instead of procedural noise), with a held-out split for the VAL-NEG proxy,
* **hard negatives** labelled ``has_doc=0`` - a document *displayed on a screen* (bezel,
  emissive panel, moire/subpixel grid, blue cast, keyboard/stand), framed pictures, white
  boxes/books/calendars, tissues, doors/walls/windows, empty desks with clutter,
* **positives with hands** - a page filling 70-100 % of the frame, slightly curled, with
  skin-tone fingers entering from the edges and a hand shadow; the mask is the *visible* page
  (fingers are cut out of it), the label quad stays the four page corners.

Everything is drawn here: no photo, font glyph or text is downloaded, and the only external
pixels are the CC0 textures registered in ``LICENSE_REGISTER.md`` (addendum 2026-11-19).
"""

from __future__ import annotations

import csv
import io
import json
import math
from pathlib import Path
from typing import Optional, Sequence

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from mlcommon import rasterize_mask, sha256_file
import synth as synth_v1

CC0_DIR = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\commercial\cc0")

PAPER_KINDS = ("white_a4", "cream", "receipt", "card", "notebook")
HARD_NEGATIVE_KINDS = ("screen_document", "framed_picture", "box_stack", "tissue",
                       "door_wall", "empty_desk")
POSITIVE_KINDS = ("hand_page", "page_on_texture")

# Skin tones roughly spanning the Fitzpatrick range; only used to draw an occluder shape,
# never to depict or label a person.
SKIN_TONES = ((247, 214, 187), (232, 190, 158), (214, 163, 124), (188, 133, 96),
              (156, 104, 68), (120, 78, 50), (92, 58, 38), (66, 40, 26))


# --------------------------------------------------------------------------- backgrounds
def load_cc0_backgrounds(cc0_dir: Path = CC0_DIR) -> list[dict]:
    """All CC0 photos with their id/category, loaded lazily later (paths only)."""
    manifest = cc0_dir / "manifest.csv"
    if not manifest.is_file():
        raise FileNotFoundError(f"CC0 manifest missing: {manifest}")
    rows = []
    with open(manifest, encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            if row.get("licence", "").strip().upper() != "CC0":
                raise ValueError(f"non-CC0 row in the pool: {row}")
            rows.append({"id": row["id"], "category": row["category"],
                         "path": str(cc0_dir / row["file"])})
    return rows


def split_cc0(rows: Sequence[dict], valneg_ids: Sequence[str]) -> tuple[list[dict], list[dict]]:
    """Deterministic train/val split by *asset*, never by crop."""
    valneg = set(valneg_ids)
    train = [r for r in rows if r["id"] not in valneg]
    holdout = [r for r in rows if r["id"] in valneg]
    return train, holdout


def _texture_background(rng, height: int, width: int, photo_path: str) -> np.ndarray:
    """Random crop/rotate/flip of a CC0 photo, scaled to the canvas, with light falloff."""
    with Image.open(photo_path) as img:
        img = img.convert("RGB")
        side = min(img.size)
        if rng.random() < 0.5:            # random square-ish crop, then scale
            cx = int(rng.uniform(0.25, 0.75) * img.width)
            cy = int(rng.uniform(0.25, 0.75) * img.height)
            half = int(side * rng.uniform(0.30, 0.50))
            box = (max(0, cx - half), max(0, cy - half),
                   min(img.width, cx + half), min(img.height, cy + half))
            img = img.crop(box)
        angle = float(rng.integers(0, 4)) * 90.0
        # only quarter turns: an arbitrary rotation leaves black corners behind after the
        # crop, which would teach the model a "black triangle = surface" shortcut
        rotated = img.rotate(angle, resample=Image.BILINEAR, expand=True)
        img = rotated
        if rng.random() < 0.5:
            img = img.transpose(Image.FLIP_LEFT_RIGHT)
        if rng.random() < 0.5:
            img = img.transpose(Image.FLIP_TOP_BOTTOM)
        # cover-fit the canvas
        scale = max(width / img.width, height / img.height)
        img = img.resize((max(1, int(img.width * scale)), max(1, int(img.height * scale))),
                         Image.BILINEAR)
        left = int(rng.integers(0, max(1, img.width - width + 1)))
        top = int(rng.integers(0, max(1, img.height - height + 1)))
        img = img.crop((left, top, left + width, top + height))
        arr = np.asarray(img, dtype=np.float32) / 255.0
    gain = float(rng.uniform(0.75, 1.15))
    return np.clip(arr * gain, 0.0, 1.0)


def background(rng, height: int, width: int, photos: Sequence[dict]) -> tuple[np.ndarray, str]:
    """A CC0 texture when available (85 %), otherwise a procedural surface."""
    if photos and rng.random() < 0.85:
        photo = photos[int(rng.integers(0, len(photos)))]
        return _texture_background(rng, height, width, photo["path"]), photo["id"]
    kind = ("wood", "fabric", "marble", "desk", "wall")[int(rng.integers(0, 5))]
    image = synth_v1.render_background(kind, rng, height, width)
    return np.asarray(image, dtype=np.float32) / 255.0, f"proc_{kind}"


# --------------------------------------------------------------------------- drawing
def _paste_card(canvas: np.ndarray, card: Image.Image, corners, opacity: float = 1.0,
                shadow: bool = True) -> np.ndarray:
    """Warp a card onto the canvas with a soft edge and optional drop shadow."""
    height, width = canvas.shape[:2]
    src = [(0.0, 0.0), (float(card.width), 0.0), (float(card.width), float(card.height)),
           (0.0, float(card.height))]
    coeffs = synth_v1._perspective_coeffs(corners, src)
    warped = card.transform((width, height), Image.PERSPECTIVE, coeffs,
                            resample=Image.BILINEAR)
    warped_arr = np.asarray(warped, dtype=np.float32) / 255.0
    alpha = synth_v1._soft_alpha(corners, (width, height))
    out = canvas
    if shadow:
        shifted = np.zeros_like(alpha)
        dy, dx = int(rng_uniform_shadow()), 0
        shifted[max(0, dy):, :] = alpha[: alpha.shape[0] - max(0, dy), :]
        blur = np.asarray(Image.fromarray((shifted * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(7)), dtype=np.float32) / 255.0
        out = out * (1.0 - 0.35 * blur[..., None])
    return out * (1 - alpha[..., None] * opacity) + warped_arr * (alpha[..., None] * opacity)


_SHADOW_OFFSET = [9]


def rng_uniform_shadow() -> int:
    return _SHADOW_OFFSET[0]


def _rounded_rect_mask(size, box, radius, supersample=3) -> np.ndarray:
    width, height = size
    big = Image.new("L", (width * supersample, height * supersample), 0)
    box_big = [int(v * supersample) for v in box]
    ImageDraw.Draw(big).rounded_rectangle(box_big, radius=radius * supersample, fill=255)
    return np.asarray(big.resize((width, height), Image.LANCZOS), dtype=np.float32) / 255.0


def _draw_fingers(canvas: np.ndarray, rng, corners) -> tuple[np.ndarray, np.ndarray]:
    """Skin-tone fingers entering from a frame edge; returns (canvas, occluder alpha)."""
    height, width = canvas.shape[:2]
    skin = SKIN_TONES[int(rng.integers(0, len(SKIN_TONES)))]
    shade = np.asarray(skin, dtype=np.float32) / 255.0
    occluder = np.zeros((height, width), dtype=np.float32)
    layer = canvas.copy()
    xs = [p[0] for p in corners]
    ys = [p[1] for p in corners]
    page_cx = (min(xs) + max(xs)) / 2.0
    page_cy = (min(ys) + max(ys)) / 2.0
    count = int(rng.integers(2, 5))
    from_left = rng.random() < 0.5
    # Fingers enter over the page (not beside it): the failure we must teach is a hand
    # covering a held page, so the band is centred on the page and anchored to its edge.
    xs_sorted = sorted(xs)
    if from_left:
        anchor = xs_sorted[0] + 0.04 * width
    else:
        anchor = xs_sorted[-1] - 0.04 * width
    for i in range(count):
        length = rng.uniform(0.13, 0.26) * width
        thickness = rng.uniform(0.055, 0.09) * width
        centre_y = page_cy + (i - (count - 1) / 2.0) * thickness * 1.5
        centre_y += float(rng.normal(0, 0.025 * height))
        if from_left:
            x0 = anchor - length * rng.uniform(0.75, 1.0)
            x1 = anchor + length * rng.uniform(0.15, 0.35)
        else:
            x1 = anchor + length * rng.uniform(0.75, 1.0)
            x0 = anchor - length * rng.uniform(0.15, 0.35)
        box = [x0, centre_y - thickness / 2.0, x1, centre_y + thickness / 2.0]
        alpha = _rounded_rect_mask((width, height), box, radius=thickness * 0.5)
        alpha = np.asarray(Image.fromarray((alpha * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(1.6)), dtype=np.float32) / 255.0
        # finger shading across its length, plus a soft specular highlight on top
        yy = np.linspace(0.0, 1.0, height, dtype=np.float32)[:, None]
        lengthwise = 0.82 + 0.18 * np.sin(np.pi * np.clip(
            (np.arange(width, dtype=np.float32)[None, :] - x0) / max(1.0, x1 - x0), 0, 1))
        tone = shade[None, None, :] * (0.78 + 0.30 * np.cos(
            (yy - 0.5) * 2.4).__abs__())[..., None] * lengthwise[..., None]
        layer = layer * (1 - alpha[..., None]) + np.clip(tone, 0, 1) * alpha[..., None]
        occluder = np.maximum(occluder, alpha)
        if rng.random() < 0.5:  # the hand throws a small shadow onto the page
            shifted = np.zeros_like(alpha)
            dy = int(0.012 * height) + 3
            dx = int(0.010 * width) * (1 if from_left else -1)
            shifted[max(0, dy):, max(0, dx):] = alpha[: alpha.shape[0] - max(0, dy),
                                                       : alpha.shape[1] - max(0, dx)]
            blur = np.asarray(Image.fromarray((shifted * 255).astype(np.uint8)).filter(
                ImageFilter.GaussianBlur(6)), dtype=np.float32) / 255.0
            layer = layer * (1.0 - 0.30 * blur[..., None])
    return np.clip(layer, 0, 1), occluder


def _curl_wave(rng, height: int, width: int, amount: float = 1.0):
    """Row-wise horizontal shift that bends the page edges (a cheap 'slightly curled')."""
    amplitude = rng.uniform(3.0, 9.0) * amount
    phase = rng.uniform(0, 2 * math.pi)
    frequency = rng.uniform(0.7, 1.6)
    rows = np.arange(height, dtype=np.float32) / max(1.0, height)
    return (amplitude * np.sin(2 * math.pi * frequency * rows + phase)).astype(np.int32)


def _apply_wave(image: np.ndarray, alpha: np.ndarray, shift: np.ndarray):
    """Vectorised row-wise horizontal shift (bends the page edges)."""
    height, width = alpha.shape
    cols = np.arange(width, dtype=np.int32)[None, :]
    src_cols = (cols - shift[:, None]) % width
    rows = np.arange(height, dtype=np.int32)[:, None]
    out_img = image[rows, src_cols]
    out_alpha = alpha[rows, src_cols]
    wrapped = (cols < shift[:, None]) | (cols >= (width + shift[:, None]))
    out_img[wrapped] = 0.0
    out_alpha[wrapped] = 0.0
    return out_img, out_alpha


# --------------------------------------------------------------------------- negatives
def screen_scene(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    """A document displayed on a laptop/monitor/phone: the v1 blocker (clip 27)."""
    canvas, bg_id = background(rng, height, width, photos)
    device = ("laptop", "monitor", "phone")[int(rng.integers(0, 3))]
    if device == "phone":
        panel_w = rng.uniform(0.55, 0.80) * width
        panel_h = panel_w * rng.uniform(1.7, 2.1)
    elif device == "monitor":
        panel_w = rng.uniform(0.62, 0.88) * width
        panel_h = panel_w * rng.uniform(0.55, 0.65)
    else:
        panel_w = rng.uniform(0.60, 0.86) * width
        panel_h = panel_w * rng.uniform(0.60, 0.68)
    panel_h = min(panel_h, 0.86 * height)
    cx = width * float(rng.uniform(0.42, 0.58))
    cy = height * float(rng.uniform(0.34, 0.46))
    bezel = max(6.0, 0.022 * width)
    x0, y0 = cx - panel_w / 2, cy - panel_h / 2
    x1, y1 = cx + panel_w / 2, cy + panel_h / 2
    # device body: bezel + chin
    body = [x0 - bezel, y0 - bezel, x1 + bezel, y1 + bezel + (0.06 * panel_h if device == "laptop" else 0.02 * panel_h)]
    alpha_body = _rounded_rect_mask((width, height), body, radius=0.012 * width)
    body_colour = np.asarray([0.07, 0.07, 0.08], dtype=np.float32)
    canvas = canvas * (1 - alpha_body[..., None]) + body_colour * alpha_body[..., None]

    # the document on the panel: a rendered page, perspective-warped inside the glass
    page, content = synth_v1.make_paper("white_a4" if rng.random() < 0.7 else "receipt", rng)
    inset = rng.uniform(0.03, 0.10)
    quad = [(x0 + inset * panel_w + rng.uniform(-0.01, 0.01) * panel_w, y0 + inset * panel_h),
            (x1 - inset * panel_w, y0 + inset * panel_h + rng.uniform(-0.01, 0.01) * panel_h),
            (x1 - inset * panel_w + rng.uniform(-0.01, 0.01) * panel_w, y1 - inset * panel_h),
            (x0 + inset * panel_w, y1 - inset * panel_h + rng.uniform(-0.01, 0.01) * panel_h)]
    canvas = _paste_card(canvas, page, quad, opacity=0.97, shadow=False)

    # emissive panel: raise brightness, add a blue cast and a soft bloom
    alpha_panel = _rounded_rect_mask((width, height), [x0, y0, x1, y1], radius=0.008 * width)
    emission = 1.0 + rng.uniform(0.10, 0.34)
    tint = np.asarray([0.94, 0.97, 1.06], dtype=np.float32)
    lit = np.clip(canvas * emission * tint[None, None, :] + 0.06 * alpha_panel[..., None], 0, 1)
    bloom = np.asarray(Image.fromarray((alpha_panel * 255).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(0.03 * width)), dtype=np.float32) / 255.0
    canvas = np.clip(lit + 0.10 * bloom[..., None], 0, 1)

    # subpixel / moire texture: a fine grid that survives as a high-frequency pattern
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    period = rng.uniform(1.6, 3.4)
    grid = 0.5 + 0.5 * np.sin(2 * math.pi * xx / period) * np.sin(2 * math.pi * yy / period)
    moire = 1.0 - rng.uniform(0.03, 0.10) * grid
    canvas = np.clip(canvas * moire[..., None], 0, 1)

    # keyboard below a laptop, or a stand under a monitor
    if device == "laptop" and y1 + 0.06 * panel_h < 0.96 * height:
        kb_top = y1 + bezel + 0.05 * panel_h
        kb_h = min(0.16 * height, height * 0.97 - kb_top)
        if kb_h > 8:
            alpha_kb = _rounded_rect_mask((width, height),
                                          [x0 - bezel, kb_top, x1 + bezel, kb_top + kb_h],
                                          radius=0.006 * width)
            key_colour = np.asarray([0.10, 0.10, 0.11], dtype=np.float32)
            keys = 0.55 + 0.45 * (0.5 + 0.5 * np.cos(2 * math.pi * (xx - x0) / 22.0))
            canvas = canvas * (1 - alpha_kb[..., None]) + (
                key_colour[None, None, :] * keys[..., None]) * alpha_kb[..., None]

    glare_alpha = np.exp(-(((xx - rng.uniform(0.2, 0.8) * width) ** 2
                            + (yy - rng.uniform(0.1, 0.5) * height) ** 2)
                           / (2 * (0.22 * width) ** 2))) * rng.uniform(0.25, 0.65)
    canvas = np.clip(canvas + glare_alpha[..., None] * alpha_panel[..., None], 0, 1)
    meta = {"kind": "screen_document", "device": device, "content": content,
            "background": bg_id, "hard_negative": 1}
    return canvas, "screen_document", meta


def framed_picture(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    canvas, bg_id = background(rng, height, width, photos)
    fw = rng.uniform(0.30, 0.62) * width
    fh = fw * rng.uniform(0.55, 1.25)
    cx, cy = width * rng.uniform(0.35, 0.65), height * rng.uniform(0.32, 0.55)
    x0, y0, x1, y1 = cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2
    frame_t = max(6.0, 0.022 * width)
    alpha_frame = _rounded_rect_mask((width, height), [x0, y0, x1, y1], radius=3)
    frame_colour = np.asarray([rng.uniform(0.10, 0.72)] * 3, dtype=np.float32)
    canvas = canvas * (1 - alpha_frame[..., None]) + frame_colour * alpha_frame[..., None]
    # the picture inside: a smooth gradient landscape (clearly not a white page)
    inner = [x0 + frame_t, y0 + frame_t, x1 - frame_t, y1 - frame_t]
    alpha_inner = _rounded_rect_mask((width, height), inner, radius=2)
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    v = (yy - y0) / max(1.0, (y1 - y0))
    sky = np.stack([0.30 + 0.45 * (1 - v), 0.42 + 0.42 * (1 - v), 0.62 + 0.34 * (1 - v)],
                   axis=2)
    wav = (0.10 * np.sin((xx - x0) * 0.05 + rng.uniform(0, 6)) * np.clip(1.0 - v, 0, 1))
    art = np.clip(sky + wav[..., None], 0, 1)
    canvas = canvas * (1 - alpha_inner[..., None]) + art * alpha_inner[..., None]
    return canvas, "framed_picture", {"kind": "framed_picture", "background": bg_id,
                                      "hard_negative": 1}


def box_stack(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    """White boxes / books / calendars: the classic white-rectangle false positive."""
    canvas, bg_id = background(rng, height, width, photos)
    base_x = width * rng.uniform(0.12, 0.30)
    base_y = height * rng.uniform(0.55, 0.78)
    count = int(rng.integers(2, 5))
    for i in range(count):
        w = rng.uniform(0.22, 0.42) * width * (1.0 - 0.08 * i)
        h = rng.uniform(0.10, 0.18) * height
        x = base_x + rng.uniform(-0.03, 0.03) * width
        y = base_y - i * h * 1.02
        angle = rng.uniform(-4, 4)
        corners = [(x, y), (x + w, y + math.tan(math.radians(angle)) * w),
                   (x + w, y + h), (x, y + h)]
        tone = rng.uniform(0.80, 0.97)
        card = Image.new("RGB", (int(w), int(h)), (int(255 * tone),) * 3)
        draw = ImageDraw.Draw(card)
        draw.rectangle([0, 0, card.width - 1, card.height - 1], outline=(120, 120, 120), width=2)
        if rng.random() < 0.4:  # a printed label band, so it is not a blank page
            draw.rectangle([card.width * 0.1, card.height * 0.3,
                            card.width * 0.9, card.height * 0.45], fill=(40, 60, 120))
        _SHADOW_OFFSET[0] = int(rng.integers(3, 12))
        canvas = _paste_card(canvas, card, corners, opacity=0.99)
    return canvas, "box_stack", {"kind": "box_stack", "background": bg_id, "hard_negative": 1}


def tissue_scene(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    canvas, bg_id = background(rng, height, width, photos)
    cx, cy = width * rng.uniform(0.3, 0.7), height * rng.uniform(0.4, 0.7)
    size = rng.uniform(0.20, 0.42) * width
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
    # crumpled tissue: a noisy blob with folds, still a bright non-paper object
    folds = (np.sin(xx * 0.55 + 2.0 * np.sin(yy * 0.09))
             * np.cos(yy * 0.42 + 1.5 * np.sin(xx * 0.07)))
    blob = np.exp(-(((xx - cx) ** 2 / (0.95 * size) ** 2 + (yy - cy) ** 2 / (0.80 * size) ** 2)))
    alpha = np.clip((blob + 0.10 * folds * blob - 0.30) * 2.9, 0, 1)
    tone = 0.86 + 0.10 * folds
    canvas = canvas * (1 - alpha[..., None]) + (tone[..., None] * np.asarray(
        [1.0, 0.995, 0.98], dtype=np.float32)) * alpha[..., None]
    return canvas, "tissue", {"kind": "tissue", "background": bg_id, "hard_negative": 1}


def door_wall(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    canvas, bg_id = background(rng, height, width, photos)
    kind = ("door", "window", "wall_panel")[int(rng.integers(0, 3))]
    x0 = width * rng.uniform(0.10, 0.45)
    w = width * rng.uniform(0.25, 0.45)
    y0 = height * rng.uniform(0.10, 0.30)
    h = height * rng.uniform(0.45, 0.80)
    alpha = _rounded_rect_mask((width, height), [x0, y0, x0 + w, min(height - 1, y0 + h)], radius=2)
    if kind == "window":
        fill = np.asarray([0.72, 0.80, 0.90], dtype=np.float32)
        canvas = canvas * (1 - alpha[..., None]) + fill * alpha[..., None]
        bar = _rounded_rect_mask((width, height), [x0 + w / 2 - 3, y0, x0 + w / 2 + 3, y0 + h], radius=0)
        canvas = canvas * (1 - bar[..., None]) + 0.35 * bar[..., None]
    elif kind == "door":
        fill = np.asarray([rng.uniform(0.30, 0.65)] * 3, dtype=np.float32)
        canvas = canvas * (1 - alpha[..., None]) + fill * alpha[..., None]
        knob = _rounded_rect_mask((width, height), [x0 + w * 0.82, y0 + h * 0.45,
                                                    x0 + w * 0.88, y0 + h * 0.50], radius=2)
        canvas = canvas * (1 - knob[..., None]) + 0.55 * knob[..., None]
    else:
        fill = np.asarray([rng.uniform(0.55, 0.85)] * 3, dtype=np.float32)
        canvas = canvas * (1 - alpha[..., None]) + fill * alpha[..., None]
    return canvas, kind, {"kind": f"door_wall_{kind}", "background": bg_id, "hard_negative": 1}


def empty_desk(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    """Clutter (mug, pens, cables, phone) and no paper - the 'quiet desk' case."""
    canvas, bg_id = background(rng, height, width, photos)
    # keep desk cluttered but not garish: desaturate a very saturated texture a little
    grey = canvas.mean(axis=2, keepdims=True)
    canvas = np.clip(canvas * 0.72 + grey * 0.28, 0, 1)
    draw_layer = Image.fromarray((canvas * 255).astype(np.uint8))
    draw = ImageDraw.Draw(draw_layer)
    for _ in range(int(rng.integers(5, 10))):
        kind = int(rng.integers(0, 4))
        if kind == 0:      # mug
            cx, cy = rng.uniform(0.15, 0.85) * width, rng.uniform(0.5, 0.85) * height
            r = rng.uniform(0.03, 0.06) * width
            colour = tuple(int(c * 255) for c in rng.uniform(0.15, 0.85, size=3))
            draw.ellipse([cx - r, cy - r * 1.05, cx + r, cy + r * 1.05], fill=colour)
            draw.ellipse([cx - r * 0.7, cy - r * 0.75, cx + r * 0.7, cy + r * 0.75],
                         fill=tuple(int(v * 0.6) for v in colour))
        elif kind == 1:    # pens
            x0, y0 = rng.uniform(0.1, 0.9) * width, rng.uniform(0.4, 0.9) * height
            angle = rng.uniform(0, math.pi)
            length = rng.uniform(0.10, 0.20) * width
            x1, y1 = x0 + length * math.cos(angle), y0 + length * math.sin(angle)
            colour = tuple(int(c * 255) for c in rng.uniform(0.05, 0.9, size=3))
            draw.line([x0, y0, x1, y1], fill=colour, width=int(max(2, 0.006 * width)))
        elif kind == 2:    # cable curve
            pts = []
            x, y = rng.uniform(0, 1) * width, rng.uniform(0.4, 1.0) * height
            for _k in range(9):
                x += rng.uniform(-0.08, 0.08) * width
                y += rng.uniform(-0.03, 0.05) * height
                pts.append((x, y))
            draw.line(pts, fill=(30, 30, 32), width=int(max(2, 0.005 * width)))
        else:              # phone lying flat
            x0, y0 = rng.uniform(0.1, 0.7) * width, rng.uniform(0.5, 0.85) * height
            w, h = 0.14 * width, 0.26 * width
            draw.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=int(0.02 * width),
                                   fill=(18, 18, 20), outline=(90, 90, 95), width=2)
    canvas = np.asarray(draw_layer, dtype=np.float32) / 255.0
    return canvas, "empty_desk", {"kind": "empty_desk", "background": bg_id, "hard_negative": 1}


# --------------------------------------------------------------------------- positives
def page_on_texture(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    """A normal page on a real CC0 surface, 10-42 % of the frame (v1-like but real texture)."""
    canvas, bg_id = background(rng, height, width, photos)
    kind = PAPER_KINDS[int(rng.integers(0, len(PAPER_KINDS)))]
    page, content = synth_v1.make_paper(kind, rng)
    ratio = page.width / page.height
    corners, _, _ = synth_v1._quad_from_placement(
        rng, width, height, ratio, float(rng.uniform(0.10, 0.42)))
    if corners is None:
        return canvas, "none", {"kind": "page_on_texture", "has_doc": 0, "background": bg_id}
    _SHADOW_OFFSET[0] = int(rng.integers(4, 16))
    canvas = _paste_card(canvas, page, corners, opacity=0.97)
    return canvas, "page_on_texture", {"kind": "page_on_texture", "corners": corners,
                                       "background": bg_id, "content": content,
                                       "paper_kind": kind, "has_doc": 1}


def hand_page(rng, height: int, width: int, photos) -> tuple[np.ndarray, str, dict]:
    """A page filling 70-100 % of the frame, held: curled edges, fingers, hand shadow."""
    canvas, bg_id = background(rng, height, width, photos)
    kind = PAPER_KINDS[int(rng.integers(0, 3))]
    page, content = synth_v1.make_paper(kind, rng)
    span = float(rng.uniform(0.70, 1.00))
    # A page that big is nearly frame-aligned: small rotation and mild perspective.
    # Aspect is preserved (a stretched page would teach the wrong shape) and the page is
    # kept inside the frame so the four-corner label stays honest.
    ratio = page.width / page.height
    cx, cy = width * rng.uniform(0.47, 0.53), height * rng.uniform(0.46, 0.54)
    w = span * width * rng.uniform(0.92, 1.0)
    h = w / ratio
    limit_h = span * height * rng.uniform(0.92, 1.0)
    if h > limit_h:
        h = limit_h
        w = h * ratio
    angle = math.radians(rng.uniform(-9, 9))
    jitter = 0.018 * min(w, h)
    corners = []
    for bx, by in ((-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)):
        x = bx + float(rng.normal(0, jitter))
        y = by + float(rng.normal(0, jitter * 0.8))
        corners.append((cx + x * math.cos(angle) - y * math.sin(angle),
                        cy + x * math.sin(angle) + y * math.cos(angle)))
    corners = [(min(max(x, 2.0), width - 2.0), min(max(y, 2.0), height - 2.0)) for x, y in corners]
    _SHADOW_OFFSET[0] = int(rng.integers(5, 18))
    canvas = _paste_card(canvas, page, corners, opacity=0.98)
    # curl: bend the page rows, then rebuild the visible page mask from the warped alpha
    alpha = synth_v1._soft_alpha(corners, (width, height))
    shift = _curl_wave(rng, height, width, amount=1.0)
    page_only = canvas * alpha[..., None]
    bent_img, bent_alpha = _apply_wave(page_only, alpha, shift)
    canvas = canvas * (1 - alpha[..., None]) + bent_img
    # shading along the curl so it reads as a bent sheet
    yy = np.arange(height, dtype=np.float32)[:, None] / max(1.0, height)
    shade = 1.0 - 0.10 * (0.5 + 0.5 * np.sin(2 * math.pi * yy * 1.4 + rng.uniform(0, 6)))
    canvas = np.clip(canvas * (1.0 - bent_alpha[..., None] * (1.0 - shade[..., None])), 0, 1)
    canvas, occluder = _draw_fingers(canvas, rng, corners)
    visible = np.clip(bent_alpha - occluder, 0, 1)
    meta = {"kind": "hand_page", "corners": corners, "background": bg_id, "content": content,
            "paper_kind": kind, "has_doc": 1, "span": round(span, 3),
            "visible_frac": round(float((visible > 0.5).mean()), 3)}
    return canvas, "hand_page", {**meta, "_visible": visible}


# --------------------------------------------------------------------------- assembly
def _postprocess_finish(rng, canvas: np.ndarray, alpha: np.ndarray) -> tuple[Image.Image, dict]:
    flags = {"glare": 0, "dim": 0, "shadow": 0}
    canvas, flags = synth_v1._apply_lighting(canvas, rng, alpha)
    image = Image.fromarray((np.clip(canvas, 0, 1) * 255).astype(np.uint8))
    if rng.random() < 0.45:
        radius = float(rng.uniform(0.4, 2.0))
        flags["blur"] = round(radius, 2)
        image = image.filter(ImageFilter.GaussianBlur(radius))
    if rng.random() < 0.5:
        sigma = float(rng.uniform(1.2, 6.5))
        flags["noise"] = round(sigma, 2)
        arr = np.asarray(image, np.float32) + rng.normal(0, sigma, (canvas.shape[0], canvas.shape[1], 1))
        image = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    quality = int(rng.integers(58, 96))
    flags["jpeg_quality"] = quality
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=quality)
    image = Image.open(io.BytesIO(buffer.getvalue())).convert("RGB")
    return image, flags


def generate_sample_v2(index: int, rng, photos: Sequence[dict],
                       allowed: Optional[Sequence[str]] = None,
                       mix: Optional[dict] = None) -> dict:
    """Generate one v2 sample: image, paper mask (positives), has_doc and metadata."""
    sizes = ((720, 960), (960, 720), (800, 800), (640, 960), (900, 1080), (1080, 1440))
    width, height = sizes[int(rng.integers(0, len(sizes)))]
    mix = mix or {"hand_page": 0.34, "page_on_texture": 0.16, "screen_document": 0.24,
                  "box_stack": 0.08, "framed_picture": 0.06, "tissue": 0.04,
                  "door_wall": 0.04, "empty_desk": 0.04}
    kinds = list(allowed) if allowed else list(mix.keys())
    weights = np.asarray([mix[k] for k in kinds], dtype=np.float64)
    weights = weights / weights.sum()
    kind = kinds[int(rng.choice(len(kinds), p=weights))]

    if kind == "screen_document":
        canvas, label, meta = screen_scene(rng, height, width, photos)
    elif kind == "framed_picture":
        canvas, label, meta = framed_picture(rng, height, width, photos)
    elif kind == "box_stack":
        canvas, label, meta = box_stack(rng, height, width, photos)
    elif kind == "tissue":
        canvas, label, meta = tissue_scene(rng, height, width, photos)
    elif kind == "door_wall":
        canvas, label, meta = door_wall(rng, height, width, photos)
    elif kind == "empty_desk":
        canvas, label, meta = empty_desk(rng, height, width, photos)
    elif kind == "hand_page":
        canvas, label, meta = hand_page(rng, height, width, photos)
    elif kind == "page_on_texture":
        canvas, label, meta = page_on_texture(rng, height, width, photos)
    else:
        raise ValueError(f"unknown kind {kind}")

    visible = meta.pop("_visible", None)
    has_doc = int(meta.get("has_doc", 0))
    alpha = visible if visible is not None else (
        synth_v1._soft_alpha(meta["corners"], (width, height)) if has_doc else
        np.zeros((height, width), dtype=np.float32))
    image, flags = _postprocess_finish(rng, np.clip(canvas, 0, 1), alpha)
    meta.update(flags)
    mask = (np.clip(alpha, 0, 1) >= 0.5).astype(np.uint8) * 255 if has_doc else \
        np.zeros((height, width), np.uint8)
    info = {
        "id": f"{index:06d}", "kind": label, "has_doc": has_doc,
        "background": meta.get("background", ""), "image": image, "mask": mask,
        "size": (width, height), "corners": meta.get("corners"),
        "meta": meta,
    }
    return info


def write_dataset_v2(out_dir: Path, count: int, seed: int, photos: Sequence[dict],
                     allowed: Optional[Sequence[str]] = None, mix: Optional[dict] = None,
                     log=print, shard: int = 0, shards: int = 1) -> list[dict]:
    """Write a pool in the same layout the trainers load: 256-grid letterboxed image + mask.

    ``shard``/``shards`` split one logical pool across processes (each shard gets its own
    RNG stream and every ``shards``-th index, so ids stay unique and every shard is
    deterministic on its own). :func:`merge_shards` joins the manifests afterwards.
    """
    import data_ml  # local import: data_ml imports synth only lazily, no cycle at import time

    out_dir = Path(out_dir)
    (out_dir / "images").mkdir(parents=True, exist_ok=True)
    (out_dir / "masks").mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed + 7919 * shard)
    local_count = (count - shard + shards - 1) // shards
    rows = []
    for k in range(max(0, local_count)):
        index = shard + k * shards
        sample = generate_sample_v2(index, rng, photos, allowed=allowed, mix=mix)
        ident = sample["id"]
        src_w, src_h = sample["size"]
        box = data_ml.letterbox(src_w, src_h)
        image = sample["image"].resize((box.net_w, box.net_h), Image.BILINEAR)
        mask = Image.fromarray(sample["mask"]).resize((box.net_w, box.net_h), Image.BILINEAR)
        canvas = Image.new("RGB", (data_ml.NET_SIZE, data_ml.NET_SIZE), (0, 0, 0))
        canvas.paste(image, (0, 0))
        mcanvas = Image.new("L", (data_ml.NET_SIZE, data_ml.NET_SIZE), 0)
        mcanvas.paste(Image.fromarray(np.where(np.asarray(mask) >= 128, 255, 0).astype(np.uint8)),
                      (0, 0))
        canvas.save(out_dir / "images" / f"{ident}.jpg", quality=92)
        mcanvas.save(out_dir / "masks" / f"{ident}.png")
        row = {
            "id": ident, "kind": sample["kind"], "has_doc": sample["has_doc"],
            "background": sample["background"],
            "src_w": src_w, "src_h": src_h,
            "net_w": box.net_w, "net_h": box.net_h,
            "visible_frac": sample["meta"].get("visible_frac", ""),
            "span": sample["meta"].get("span", ""),
            "corners": json.dumps(sample["corners"]) if sample["corners"] else "",
            "meta": json.dumps({k: v for k, v in sample["meta"].items() if k != "corners"}),
        }
        rows.append(row)
        if log and (k + 1) % 250 == 0:
            log(f"  synth2 shard {shard}: {k + 1}/{local_count}")
    manifest_name = "manifest.csv" if shards == 1 else f"manifest_shard{shard}.csv"
    with open(out_dir / manifest_name, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    positives = sum(r["has_doc"] for r in rows)
    counts: dict[str, int] = {}
    for row in rows:
        counts[row["kind"]] = counts.get(row["kind"], 0) + 1
    meta = {"count": count, "seed": seed, "positives": positives,
            "negatives": len(rows) - positives, "kinds": counts,
            "allowed": list(allowed) if allowed else None, "mix": mix,
            "generator": "tools/scanner/ml/synth2.py", "version": "synth2_v1",
            "net_size": data_ml.NET_SIZE}
    with open(out_dir / "pool.json", "w", encoding="utf-8") as handle:
        json.dump(meta, handle, indent=2)
    log(f"synth2: {count} samples ({positives} positive, {len(rows) - positives} negative) "
        f"-> {out_dir}")
    return rows


def merge_shards(out_dir: Path, shards: int, seed: int, count: int,
                 allowed=None, mix=None, log=print) -> list[dict]:
    """Join ``manifest_shard*.csv`` into ``manifest.csv`` + ``pool.json`` and delete the parts."""
    out_dir = Path(out_dir)
    rows: list[dict] = []
    for shard in range(shards):
        path = out_dir / f"manifest_shard{shard}.csv"
        with open(path, encoding="utf-8", newline="") as handle:
            rows.extend(csv.DictReader(handle))
        path.unlink()
    rows.sort(key=lambda r: r["id"])
    if len(rows) != count:
        log(f"  WARNING: merged {len(rows)} rows, expected {count}")
    with open(out_dir / "manifest.csv", "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    positives = sum(int(r["has_doc"]) for r in rows)
    counts: dict[str, int] = {}
    for row in rows:
        counts[row["kind"]] = counts.get(row["kind"], 0) + 1
    meta = {"count": len(rows), "seed": seed, "positives": positives,
            "negatives": len(rows) - positives, "kinds": counts,
            "allowed": list(allowed) if allowed else None, "mix": mix,
            "generator": "tools/scanner/ml/synth2.py", "version": "synth2_v1",
            "shards": shards, "net_size": 256}
    with open(out_dir / "pool.json", "w", encoding="utf-8") as handle:
        json.dump(meta, handle, indent=2)
    log(f"synth2 merged: {len(rows)} samples ({positives} positive, "
        f"{len(rows) - positives} negative) -> {out_dir}")
    return rows


def write_photo_negatives(out_dir: Path, photos: Sequence[dict], crops_per: int = 3,
                          seed: int = 777, log=print) -> list[dict]:
    """CC0 photos (assets held out of training) as pure-texture negatives, in pool layout."""
    import data_ml

    out_dir = Path(out_dir)
    (out_dir / "images").mkdir(parents=True, exist_ok=True)
    (out_dir / "masks").mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    rows = []
    index = 0
    for photo in photos:
        with Image.open(photo["path"]) as img:
            img = img.convert("RGB")
            for _ in range(crops_per):
                side = int(min(img.size) * rng.uniform(0.45, 0.95))
                left = int(rng.integers(0, max(1, img.width - side + 1)))
                top = int(rng.integers(0, max(1, img.height - side + 1)))
                crop = img.crop((left, top, left + side, top + side))
                box = data_ml.letterbox(crop.width, crop.height)
                small = crop.resize((box.net_w, box.net_h), Image.BILINEAR)
                canvas = Image.new("RGB", (data_ml.NET_SIZE, data_ml.NET_SIZE), (0, 0, 0))
                canvas.paste(small, (0, 0))
                ident = f"photo_{index:05d}"
                canvas.save(out_dir / "images" / f"{ident}.jpg", quality=92)
                Image.new("L", (data_ml.NET_SIZE, data_ml.NET_SIZE), 0).save(
                    out_dir / "masks" / f"{ident}.png")
                rows.append({"id": ident, "kind": "cc0_photo", "has_doc": 0,
                             "background": photo["id"], "src_w": crop.width, "src_h": crop.height,
                             "net_w": box.net_w, "net_h": box.net_h, "visible_frac": "",
                             "span": "", "corners": "",
                             "meta": json.dumps({"source": photo["id"], "crop": [left, top, side]})})
                index += 1
    with open(out_dir / "manifest.csv", "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    log(f"photo negatives: {len(rows)} from {len(photos)} held-out CC0 assets -> {out_dir}")
    return rows


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Build a v2 synthetic pool.")
    parser.add_argument("--n", type=int, default=3000)
    parser.add_argument("--seed", type=int, default=20261120)
    parser.add_argument("--out", default=r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\commercial\synth_train_v2")
    parser.add_argument("--neg-only", action="store_true")
    parser.add_argument("--valneg", action="store_true",
                        help="build the VAL-NEG proxy set (held-out CC0 assets, other seed)")
    parser.add_argument("--valneg-ids", default="")
    args = parser.parse_args()

    rows = load_cc0_backgrounds()
    holdout_ids = [v for v in args.valneg_ids.split(",") if v.strip()]
    train_photos, holdout_photos = split_cc0(rows, holdout_ids)
    if args.valneg:
        photos = holdout_photos or rows
        write_dataset_v2(Path(args.out), args.n, args.seed, photos,
                         allowed=["screen_document", "box_stack", "framed_picture", "tissue",
                                  "door_wall", "empty_desk"],
                         log=print)
    elif args.neg_only:
        write_dataset_v2(Path(args.out), args.n, args.seed, train_photos,
                         allowed=["screen_document", "box_stack", "framed_picture", "tissue",
                                  "door_wall", "empty_desk"], log=print)
    else:
        write_dataset_v2(Path(args.out), args.n, args.seed, train_photos, log=print)
