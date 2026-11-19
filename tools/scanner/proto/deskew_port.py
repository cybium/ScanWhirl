"""Python port of the shipped `Deskew.detectQuad` (app/src/main/java/.../Deskew.kt).

The shipped detector is a strong-corner heuristic: threshold the Sobel gradient,
then pick the four pixels that are extreme along the two image diagonals. It is
cheap, it has no notion of "paper", and on an empty scene it still returns a quad
(that is why it false-accepts). Round 2 uses it as *one candidate generator* and
lets the paper/rejection stage decide whether the candidate is a page.

The port deliberately keeps the shipped constants (DETECT_DIM 720, STRONG_AREA
0.06, 0.18 gradient threshold, 3 % inset, 0.05 separation) so it can be compared
against the Kotlin behaviour, and returns corners in source pixels plus the
normalised quad.

numpy + PIL only; no Android dependency.
"""

from __future__ import annotations

from typing import Optional

import numpy as np
from PIL import Image

Quad = list[tuple[float, float]]


def shoelace(points: np.ndarray) -> float:
    acc = 0.0
    for i in range(4):
        j = (i + 1) % 4
        acc += points[i, 0] * points[j, 1] - points[j, 0] * points[i, 1]
    return acc / 2.0


def sobel_max_axis(gray: np.ndarray) -> np.ndarray:
    """The shipped max(|gx|,|gy|) Sobel, interior only; border stays zero."""
    h, w = gray.shape
    g = np.zeros((h, w), dtype=np.float32)
    if h < 3 or w < 3:
        return g
    p = gray.astype(np.float32)
    gx = (-(p[:-2, :-2] + 2.0 * p[1:-1, :-2] + p[2:, :-2])
          + (p[:-2, 2:] + 2.0 * p[1:-1, 2:] + p[2:, 2:]))
    gy = (-(p[:-2, :-2] + 2.0 * p[:-2, 1:-1] + p[:-2, 2:])
          + (p[2:, :-2] + 2.0 * p[2:, 1:-1] + p[2:, 2:]))
    g[1:-1, 1:-1] = np.maximum(np.abs(gx), np.abs(gy))
    return g


def detect_quad_shipped(rgb: np.ndarray) -> Optional[Quad]:
    """Return the shipped detector's quad in the *input image's* pixel grid.

    ``rgb`` is an RGB array in [0,1] (already downscaled to <= 720 long side by
    the caller, mirroring BitmapFactory.inSampleSize). Returns None when the
    Kotlin code would return null.
    """
    h, w = rgb.shape[:2]
    if w < 48 or h < 48:
        return None
    gray = (0.299 * rgb[:, :, 0] + 0.587 * rgb[:, :, 1] + 0.114 * rgb[:, :, 2])
    gray = np.clip(gray, 0.0, 1.0).astype(np.float32)
    grad = sobel_max_axis(gray)
    max_grad = float(grad.max())
    if max_grad <= 0.0:
        return None

    inset_x = int(w * 0.03)
    inset_y = int(h * 0.03)
    if inset_x * 2 >= w or inset_y * 2 >= h:
        return None
    ys, xs = np.mgrid[inset_y:h - inset_y, inset_x:w - inset_x]
    sub = grad[inset_y:h - inset_y, inset_x:w - inset_x]
    strong = sub >= max_grad * 0.18
    if not strong.any():
        return None
    # the Kotlin loop keeps the first pixel that attains the minimum score; use
    # argmin over flattened coordinates for the same behaviour deterministically.
    scores = (
        (xs + ys).astype(np.float64),
        ((w - 1 - xs) + ys).astype(np.float64),
        ((w - 1 - xs) + (h - 1 - ys)).astype(np.float64),
        (xs + (h - 1 - ys)).astype(np.float64),
    )
    idx = []
    for score in scores:
        masked = np.where(strong, score, np.inf)
        flat = int(np.argmin(masked))
        yy, xx = np.unravel_index(flat, masked.shape)
        idx.append((int(xs[yy, xx]), int(ys[yy, xx])))
    # the four diagonal extremes can coincide on a degenerate image
    pts = [(float(x), float(y)) for x, y in idx]
    if len(set(idx)) < 4:
        return None
    area_norm = abs(shoelace(np.array(pts))) / float(w * h)
    if area_norm < 0.06:
        return None
    # Kotlin checks flat-array pairs apart(0,2) apart(2,4) apart(4,6) apart(6,0):
    # TL-BR diagonal, TR-BR, BR-BL, BL-TL.
    for i, j in ((0, 2), (1, 2), (2, 3), (3, 0)):
        if np.hypot(pts[i][0] - pts[j][0], pts[i][1] - pts[j][1]) <= 0.05 * max(w, h):
            return None
    return pts


def detect_quad_shipped_path(path: str, detect_dim: int = 720) -> Optional[Quad]:
    """Convenience wrapper: load a JPEG, downscale like the app, run the port."""
    with Image.open(path) as img:
        img = img.convert("RGB")
        src_w, src_h = img.size
        f = detect_dim / float(max(src_w, src_h))
        if f < 1.0:
            size = (max(8, int(round(src_w * f))), max(8, int(round(src_h * f))))
            img = img.resize(size, Image.BILINEAR)
        rgb = np.asarray(img, dtype=np.float32) / 255.0
    return detect_quad_shipped(rgb)
