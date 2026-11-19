"""Shared helpers for the Scanner X ML data tooling (local CPU-only pipeline).

Scope: data preparation only. Nothing in this module touches the production
scanner, Android permissions, network or cloud. Private recordings are read
from the local qa workspace and never written into Git.

Conventions
- A quad is a list of four (x, y) integer/float pixel points ordered
  TL, TR, BR, BL as seen in the image (see qa/recordings/labels/LABEL_SPEC.md).
- Masks are single-channel uint8 arrays, 255 = paper, 0 = not paper, stored as
  PNG next to a resized copy of the source frame.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

QUAD_ORDER = ("TL", "TR", "BR", "BL")
MASK_VALUE = 255


# --------------------------------------------------------------------------
# Files / hashing
# --------------------------------------------------------------------------
def sha256_file(path: str | Path, chunk: int = 1 << 20) -> str:
    """SHA256 of a file's bytes (used to prove which source frame was read)."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(chunk), b""):
            digest.update(block)
    return digest.hexdigest()


# --------------------------------------------------------------------------
# Geometry
# --------------------------------------------------------------------------
def resize_long_side(width: int, height: int, long_side: int = 512) -> tuple[int, int]:
    """Target size whose longest edge is exactly ``long_side`` (never upscales past it)."""
    if width <= 0 or height <= 0:
        raise ValueError(f"bad size {width}x{height}")
    if width >= height:
        return long_side, max(1, int(round(height * long_side / width)))
    return max(1, int(round(width * long_side / height))), long_side


def scale_points(points, scale_x: float, scale_y: float):
    """Scale a quad from source pixels to the resized frame's pixels."""
    return [(p[0] * scale_x, p[1] * scale_y) for p in points]


def polygon_area(points) -> float:
    """Absolute shoelace area of a simple polygon."""
    total = 0.0
    count = len(points)
    for i in range(count):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % count]
        total += x1 * y2 - x2 * y1
    return abs(total) / 2.0


def is_convex_quad(points, min_area_ratio: float = 1e-4) -> bool:
    """True when the 4 points form a strictly convex, non-degenerate quad."""
    if len(points) != 4:
        return False
    signs = []
    for i in range(4):
        ax, ay = points[i]
        bx, by = points[(i + 1) % 4]
        cx, cy = points[(i + 2) % 4]
        cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        signs.append(cross)
    if any(abs(s) < 1e-6 for s in signs):
        return False
    if not (all(s > 0 for s in signs) or all(s < 0 for s in signs)):
        return False
    # Reject slivers: area must be a sane fraction of the bounding box.
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    box = (max(xs) - min(xs)) * (max(ys) - min(ys))
    return box > 0 and polygon_area(points) >= min_area_ratio * box


def rasterize_mask(corners, size: tuple[int, int], supersample: int = 4) -> np.ndarray:
    """Rasterise a quad to a uint8 mask of ``size`` = (width, height).

    Corners are clamped by the rasteriser; callers should pass points already
    inside the frame. Supersampling gives an anti-aliased boundary that is then
    thresholded to binary so the mask stays a clean target.
    """
    width, height = size
    if supersample < 1:
        raise ValueError("supersample must be >= 1")
    big = (width * supersample, height * supersample)
    canvas = Image.new("L", big, 0)
    scaled = [(x * supersample, y * supersample) for x, y in corners]
    ImageDraw.Draw(canvas).polygon(scaled, fill=MASK_VALUE)
    if supersample > 1:
        canvas = canvas.resize((width, height), Image.LANCZOS)
    mask = np.asarray(canvas, dtype=np.uint8)
    return np.where(mask >= 128, MASK_VALUE, 0).astype(np.uint8)


def point_in_polygon(points, x: float, y: float) -> bool:
    """Ray-casting point test, independent of the PIL rasteriser (test oracle)."""
    inside = False
    count = len(points)
    for i in range(count):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % count]
        if (y1 > y) != (y2 > y):
            x_at = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            if x < x_at:
                inside = not inside
    return inside


def clip_quad_to_frame(corners, width: int, height: int):
    """Clamp corners into [0, width-1] x [0, height-1] (labels may sit on the edge)."""
    return [
        (min(max(float(x), 0.0), float(width - 1)), min(max(float(y), 0.0), float(height - 1)))
        for x, y in corners
    ]


# --------------------------------------------------------------------------
# Loading labels
# --------------------------------------------------------------------------
def parse_quad(row: dict) -> list[tuple[float, float]] | None:
    """Return the quad from a label row, or None when corners are empty/partial."""
    keys = ("x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4")
    raw = [str(row.get(k, "") or "").strip() for k in keys]
    if not all(raw):
        return None
    try:
        values = [float(v) for v in raw]
    except ValueError:
        return None
    return [(values[i], values[i + 1]) for i in range(0, 8, 2)]
