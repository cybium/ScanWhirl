"""Scanner X prototype: single-frame page-corner detector (numpy + Pillow only).

This module is a *portable blueprint* for the future Kotlin implementation: every
step is one of the primitives Android can host without native code --

    box filter (integral image) -> histogram percentile / Otsu -> binary morphology
    -> run-length connected components -> convex hull -> min-area rectangle
    -> total-least-squares line fits -> line intersections -> gradient-profile
    edge refinement -> a small hand-tuned confidence score

Nothing here needs OpenCV, scipy, a neural model or a network. The only inputs are a
JPEG path and the parameter dataclass.

Pipeline (all coordinates inside ``detect`` are in the **work image**; the returned
quad is scaled back to **source JPEG pixels**, TL/TR/BR/BL clockwise as the labels
are - see qa/recordings/labels/LABEL_SPEC.md):

  1. downscale so the long side is <= ``work_side`` (640 default)
  2. paper-likelihood map S = normalised lightness - chroma_weight * normalised
     chroma, thresholded with Otsu (per frame, so dim light is fine)
  3. light open/close, run-length connected components
  4. candidate = a component that is 5-96 % of the frame and not mostly glued to
     the frame border (the border of the frame is not a page edge)
  5. quad = four support lines of the component's convex hull, refit by total least
     squares, corners = intersections ("min-area rectangle" gives the directions)
  6. edge refinement: for each side, sample the intensity profile along the normal
     at ``refine_side`` resolution, take the strongest step, robust-refit the line,
     recompute the corners; one extra pass with the updated normals
  7. confidence in [0,1] from rectangularity, corner angles, edge strength, the
     brightness step across the border and how much of the component sits on the
     frame border. ``confidence >= threshold`` is the accept decision.

Measured milliseconds are PC CPython on this Windows machine, never phone latency.
"""

from __future__ import annotations

import math
import os
import sys
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image

Point = Tuple[float, float]
Quad = List[Point]


# --------------------------------------------------------------------------- config


@dataclass
class Config:
    work_side: int = 640
    refine_side: int = 1280
    chroma_weight: float = 0.35
    morph_radius: int = 0          # 0 -> derived from work_side
    min_area_frac: float = 0.05
    max_area_frac: float = 0.96
    max_border_hug: float = 0.35   # component boundary on the frame border
    max_candidates: int = 3
    band_frac: float = 0.06        # support-line band, fraction of the hull extent
    refine_wide_frac: float = 0.12  # pass 1 search band, fraction of the frame diagonal
    refine_narrow_frac: float = 0.02
    refine_local_frac: float = 0.004
    refine_pass_points: int = 15
    refine_samples: int = 61
    refine_enabled: bool = True
    refine_prior: float = 0.40     # 0 = pick the strongest step, 1 = strongly prefer the mask line
    refine_outward: float = 0.0    # extra penalty for moving a side inward (prefers the outer edge)
    accept: float = 0.53


# ------------------------------------------------------------------------ numerics


def hist_percentile(values: np.ndarray, q: float, bins: int = 256) -> float:
    """Percentile from a 256-bin histogram -- the Kotlin version is the same loop."""
    flat = values.ravel()
    lo = float(flat.min())
    hi = float(flat.max())
    if not math.isfinite(lo) or not math.isfinite(hi) or hi <= lo:
        return lo if math.isfinite(lo) else 0.0
    counts, edges = np.histogram(flat, bins=bins, range=(lo, hi))
    cumulative = np.cumsum(counts)
    target = (q / 100.0) * cumulative[-1]
    idx = int(np.searchsorted(cumulative, target))
    idx = min(idx, bins - 1)
    return float(edges[idx])


def otsu(values: np.ndarray, bins: int = 256) -> float:
    """Otsu threshold of a [0,1] map, taken at the **middle of the plateau**.

    When the image has two levels (paper vs desk) the between-class variance is equal
    for every threshold between the two modes, so a plain argmax returns the first of
    them -- right against the dark mode, which inflates the mask. Averaging the first
    and last maximising index puts the cut in the valley, which is what a binary
    threshold is supposed to do.
    """
    counts, _ = np.histogram(values, bins=bins, range=(0.0, 1.0))
    total = counts.sum()
    if total == 0:
        return 0.5
    idx = np.arange(bins, dtype=np.float64)
    sum_all = float((counts * idx).sum())
    w0 = 0.0
    sum0 = 0.0
    variance = np.zeros(bins)
    for i in range(bins):
        w0 += counts[i]
        sum0 += counts[i] * i
        w1 = total - w0
        if w0 == 0 or w1 == 0:
            continue
        m0 = sum0 / w0
        m1 = (sum_all - sum0) / w1
        variance[i] = w0 * w1 * (m0 - m1) ** 2
    peak = float(variance.max())
    if peak <= 0:
        return 0.5
    hits = np.nonzero(variance >= peak * (1.0 - 1e-9))[0]
    middle = 0.5 * (hits[0] + hits[-1])
    return float((middle + 0.5) / bins)


def box_sum(image: np.ndarray, radius: int) -> np.ndarray:
    """Sum over a (2r+1)^2 window with edge replication.

    Two 1-D passes (horizontal then vertical) of a contiguous cumulative sum, which
    is what the Kotlin version will do in one loop; a 2-D cumsum is the slow path on
    a C-ordered array because one axis is strided.
    """
    r = int(radius)
    a = image.astype(np.float64)
    if r <= 0:
        return a
    h, w = a.shape
    k = 2 * r + 1
    ap = np.concatenate([np.repeat(a[:, :1], r, axis=1), a, np.repeat(a[:, -1:], r, axis=1)], axis=1)
    c = np.zeros((h, ap.shape[1] + 1))
    c[:, 1:] = ap.cumsum(axis=1)
    horiz = c[:, k:] - c[:, :-k]
    ht = np.ascontiguousarray(horiz.T)
    hp = np.concatenate([np.repeat(ht[:, :1], r, axis=1), ht, np.repeat(ht[:, -1:], r, axis=1)], axis=1)
    c2 = np.zeros((w, hp.shape[1] + 1))
    c2[:, 1:] = hp.cumsum(axis=1)
    vert = c2[:, k:] - c2[:, :-k]
    return np.ascontiguousarray(vert.T)


def box_mean(image: np.ndarray, radius: int) -> np.ndarray:
    r = int(radius)
    if r <= 0:
        return image.astype(np.float64, copy=True)
    k = 2 * r + 1
    return box_sum(image, r) / float(k * k)


def erode(mask: np.ndarray, radius: int) -> np.ndarray:
    if radius <= 0:
        return mask
    k = 2 * radius + 1
    return box_sum(mask, radius) >= (k * k - 0.5)


def dilate(mask: np.ndarray, radius: int) -> np.ndarray:
    if radius <= 0:
        return mask
    return box_sum(mask, radius) > 0.5


# ------------------------------------------------------------------- components


def label_components(mask: np.ndarray) -> Tuple[np.ndarray, List[dict]]:
    """4-connected run-length component labelling (union-find over row runs).

    Returns (labels int32, stats list). ``stats[i]`` belongs to label ``i + 1``.
    """
    h, w = mask.shape
    parent: List[int] = [0]

    def find(a: int) -> int:
        root = a
        while parent[root] != root:
            root = parent[root]
        while parent[a] != root:
            parent[a], a = root, parent[a]
        return root

    def union(a: int, b: int) -> int:
        ra, rb = find(a), find(b)
        if ra == rb:
            return ra
        if ra < rb:
            parent[rb] = ra
            return ra
        parent[ra] = rb
        return rb

    runs: List[Tuple[int, int, int, int]] = []  # (y, start, end_exclusive, label)
    prev: List[Tuple[int, int, int]] = []       # (start, end, label) of the row above
    for y in range(h):
        row = mask[y]
        if not row.any():
            prev = []
            continue
        padded = np.concatenate((np.zeros(1, dtype=np.int8), row.astype(np.int8), np.zeros(1, dtype=np.int8)))
        diff = np.diff(padded)
        starts = np.nonzero(diff == 1)[0]
        ends = np.nonzero(diff == -1)[0]
        current: List[Tuple[int, int, int]] = []
        j = 0
        for s, e in zip(starts.tolist(), ends.tolist()):
            label = 0
            while j < len(prev) and prev[j][1] <= s:
                j += 1
            k = j
            while k < len(prev) and prev[k][0] < e:
                if label == 0:
                    label = prev[k][2]
                else:
                    label = union(label, prev[k][2])
                k += 1
            if label == 0:
                label = len(parent)
                parent.append(label)
            runs.append((y, s, e, label))
            current.append((s, e, label))
        prev = current

    roots = {}
    for index in range(1, len(parent)):
        root = find(index)
        if root not in roots:
            roots[root] = len(roots) + 1
    labels = np.zeros((h, w), dtype=np.int32)
    stats: List[dict] = []
    for y, s, e, label in runs:
        final = roots[find(label)]
        labels[y, s:e] = final
        while len(stats) < final:
            stats.append({"area": 0, "x0": w, "x1": -1, "y0": h, "y1": -1})
        st = stats[final - 1]
        st["area"] += e - s
        st["x0"] = min(st["x0"], s)
        st["x1"] = max(st["x1"], e - 1)
        st["y0"] = min(st["y0"], y)
        st["y1"] = max(st["y1"], y)
    return labels, stats


def border_hug(labels: np.ndarray, label: int) -> float:
    """How much of a component's outline is the frame border itself.

    A page inside the frame: 0. A table or wall filling the frame: near 1. This is
    what stops "the frame is a rectangle" from being a page detection.
    """
    comp = labels == label
    frame = np.zeros_like(comp)
    frame[0, :] = frame[-1, :] = True
    frame[:, 0] = frame[:, -1] = True
    border_count = int((comp & frame).sum())
    if border_count == 0:
        return 0.0
    inner_boundary = comp & ~erode(comp, 1) & ~frame
    count = border_count + int(inner_boundary.sum())
    return border_count / float(max(1, count))


# ----------------------------------------------------------------------- geometry


def convex_hull(points: np.ndarray) -> np.ndarray:
    """Andrew monotone chain over Nx2 float points; returns hull vertices CCW."""
    pts = np.unique(np.round(points, 3), axis=0)
    if len(pts) <= 2:
        return pts
    ordered = pts[np.lexsort((pts[:, 1], pts[:, 0]))]

    def cross(o, a, b) -> float:
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: List[np.ndarray] = []
    for p in ordered:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: List[np.ndarray] = []
    for p in ordered[::-1]:
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    hull = lower[:-1] + upper[:-1]
    return np.array(hull, dtype=np.float64)


def mask_hull(comp: np.ndarray) -> np.ndarray:
    """Convex hull of a binary mask.

    Only row extremes and column extremes are fed to the hull: every hull vertex of a
    pixel set is the extreme point of its own row or column, so the hull is identical
    and the cost is O(rows + columns) instead of O(pixels).
    """
    h, w = comp.shape
    ys = np.arange(h)[:, None]
    xs = np.arange(w)[None, :]
    big = np.float64(1 << 30)
    ys_full = np.broadcast_to(ys, (h, w))
    xs_full = np.broadcast_to(xs, (h, w))

    row_min_x = np.where(comp, xs_full, big).min(axis=1)
    row_max_x = np.where(comp, xs_full, -big).max(axis=1)
    col_min_y = np.where(comp, ys_full, big).min(axis=0)
    col_max_y = np.where(comp, ys_full, -big).max(axis=0)

    points: List[Tuple[float, float]] = []
    rows = np.nonzero(row_max_x > -big)[0]
    for y in rows.tolist():
        points.append((float(row_min_x[y]), float(y)))
        points.append((float(row_max_x[y]), float(y)))
    cols = np.nonzero(col_max_y > -big)[0]
    for x in cols.tolist():
        points.append((float(x), float(col_min_y[x])))
        points.append((float(x), float(col_max_y[x])))
    return convex_hull(np.array(points, dtype=np.float64))


def quad_area(quad: Sequence[Point]) -> float:
    total = 0.0
    n = len(quad)
    for i in range(n):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % n]
        total += x1 * y2 - x2 * y1
    return abs(total) / 2.0


def signed_area(quad: Sequence[Point]) -> float:
    total = 0.0
    n = len(quad)
    for i in range(n):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % n]
        total += x1 * y2 - x2 * y1
    return total / 2.0


def min_area_rect_dirs(hull: np.ndarray) -> Tuple[float, np.ndarray, np.ndarray]:
    """Angle (radians) and unit axes of the minimum-area rectangle around the hull."""
    best_area: Optional[float] = None
    best_angle = 0.0
    for step, half in ((2.0, 90.0), (0.25, 2.5)):
        if best_area is None:
            start, end = 0.0, 90.0
        else:
            start, end = best_angle - half, best_angle + half
        count = int(round((end - start) / step)) + 1
        for k in range(count):
            angle = math.radians(start + k * step)
            u = np.array([math.cos(angle), math.sin(angle)])
            v = np.array([-u[1], u[0]])
            pu = hull @ u
            pv = hull @ v
            area = float((pu.max() - pu.min()) * (pv.max() - pv.min()))
            if best_area is None or area < best_area:
                best_area = area
                best_angle = angle
    u = np.array([math.cos(best_angle), math.sin(best_angle)])
    v = np.array([-u[1], u[0]])
    return best_angle, u, v


def fit_line_tls(points: np.ndarray) -> Optional[Tuple[np.ndarray, np.ndarray]]:
    """Total least squares line: (centroid, unit direction). None if degenerate."""
    if len(points) < 2:
        return None
    centroid = points.mean(axis=0)
    centred = points - centroid
    cov = centred.T @ centred / float(len(points))
    a, b, c = float(cov[0, 0]), float(cov[0, 1]), float(cov[1, 1])
    if abs(b) < 1e-12 and abs(a - c) < 1e-12:
        return None
    angle = 0.5 * math.atan2(2.0 * b, a - c)
    direction = np.array([math.cos(angle), math.sin(angle)])
    return centroid, direction


def line_intersection(line_a, line_b) -> Optional[np.ndarray]:
    (p, d), (q, e) = line_a, line_b
    denom = d[0] * e[1] - d[1] * e[0]
    if abs(denom) < 1e-9:
        return None
    diff = q - p
    t = (diff[0] * e[1] - diff[1] * e[0]) / denom
    return p + t * d


def line_normal(direction: np.ndarray) -> np.ndarray:
    return np.array([-direction[1], direction[0]])


def side_lines_from_hull(hull: np.ndarray, band: float) -> List[Tuple[np.ndarray, np.ndarray]]:
    """Four support lines of a hull: (centroid, direction) for -u, +u, -v, +v sides."""
    _, u, v = min_area_rect_dirs(hull)
    lines: List[Tuple[np.ndarray, np.ndarray]] = []
    for axis in (u, v):
        proj = hull @ axis
        for extreme in (float(proj.min()), float(proj.max())):
            mask = np.abs(proj - extreme) <= band
            selected = hull[mask]
            fit = fit_line_tls(selected) if len(selected) >= 3 else None
            if fit is None:
                # fall back to the axis-aligned support line through the extreme point
                anchor = hull[int(np.argmin(proj))] if extreme == float(proj.min()) else hull[int(np.argmax(proj))]
                fit = (anchor, np.array([axis[1], -axis[0]]))
            lines.append(fit)
    return lines


def quad_from_lines(lines: Sequence[Tuple[np.ndarray, np.ndarray]],
                    pairs=((0, 2), (1, 2), (1, 3), (0, 3))) -> Optional[Quad]:
    """Intersect line pairs into a quad, ordered TL/TR/BR/BL clockwise.

    ``pairs`` must join *adjacent* sides. The default suits the support-line order of
    ``side_lines_from_hull`` (-u, +u, -v, +v); ``refine_quad`` passes sides in walk
    order and therefore uses ((3,0),(0,1),(1,2),(2,3)).
    """
    pts: Quad = []
    for a, b in pairs:
        p = line_intersection(lines[a], lines[b])
        if p is None:
            return None
        pts.append((float(p[0]), float(p[1])))
    cx = sum(p[0] for p in pts) / 4.0
    cy = sum(p[1] for p in pts) / 4.0
    pts.sort(key=lambda p: math.atan2(p[1] - cy, p[0] - cx))
    return normalise_quad(pts)


SIDE_PAIRS = ((3, 0), (0, 1), (1, 2), (2, 3))


def normalise_quad(quad: Quad) -> Quad:
    """Force clockwise screen winding (positive signed area) starting near top-left."""
    pts = list(quad)
    if signed_area(pts) < 0:
        pts = list(reversed(pts))
    # rotate so the first corner is the smallest x+y (top-left-ish)
    scores = [x + y for x, y in pts]
    k = int(np.argmin(scores))
    return pts[k:] + pts[:k]


def quad_is_convex(quad: Quad) -> bool:
    sign = 0
    n = len(quad)
    for i in range(n):
        ax, ay = quad[i]
        bx, by = quad[(i + 1) % n]
        cx, cy = quad[(i + 2) % n]
        cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        if abs(cross) < 1e-9:
            return False
        s = 1 if cross > 0 else -1
        if sign == 0:
            sign = s
        elif s != sign:
            return False
    return True


def scale_quad(quad: Quad, factor: float) -> Quad:
    cx = sum(p[0] for p in quad) / 4.0
    cy = sum(p[1] for p in quad) / 4.0
    return [(cx + (x - cx) * factor, cy + (y - cy) * factor) for x, y in quad]


def polygon_mask(quad: Quad, width: int, height: int) -> np.ndarray:
    """Scanline fill of a convex quad into a boolean mask (portable to Kotlin)."""
    mask = np.zeros((height, width), dtype=bool)
    ys = np.arange(height)
    # each row: intersect the two "active" edges and fill between the extremes
    xs = np.zeros((4, height), dtype=np.float64)
    for i in range(4):
        (x1, y1) = quad[i]
        (x2, y2) = quad[(i + 1) % 4]
        with np.errstate(divide="ignore", invalid="ignore"):
            t = (ys - y1) / (y2 - y1) if abs(y2 - y1) > 1e-9 else np.full(height, np.nan)
        edge_x = x1 + t * (x2 - x1)
        inside = (t >= 0.0) & (t <= 1.0)
        xs[i] = np.where(inside, edge_x, np.nan)
    with np.errstate(invalid="ignore"):
        finite = np.isfinite(xs)
        lo = np.where(finite, xs, np.inf).min(axis=0)
        hi = np.where(finite, xs, -np.inf).max(axis=0)
    valid = np.isfinite(lo) & np.isfinite(hi)
    lo = np.clip(np.where(valid, lo, 0.0), 0, width).astype(np.int32)
    hi = np.clip(np.where(valid, hi, 0.0), 0, width).astype(np.int32)
    for row in np.nonzero(valid & (hi > lo))[0]:
        mask[row, lo[row]:hi[row]] = True
    return mask


# ---------------------------------------------------------------------- refinement


def sample_bilinear(image: np.ndarray, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
    h, w = image.shape
    x = np.clip(xs, 0.0, w - 1.000001)
    y = np.clip(ys, 0.0, h - 1.000001)
    x0 = np.floor(x).astype(np.int32)
    y0 = np.floor(y).astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    dx = x - x0
    dy = y - y0
    return (image[y0, x0] * (1 - dx) * (1 - dy)
            + image[y0, x1] * dx * (1 - dy)
            + image[y1, x0] * (1 - dx) * dy
            + image[y1, x1] * dx * dy)


def gradient_maps(gray: np.ndarray) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Central-difference gradients and their magnitude (Kotlin: two 3-tap passes)."""
    gx = np.zeros_like(gray)
    gy = np.zeros_like(gray)
    gx[:, 1:-1] = 0.5 * (gray[:, 2:] - gray[:, :-2])
    gy[1:-1, :] = 0.5 * (gray[2:, :] - gray[:-2, :])
    mag = np.hypot(gx, gy)
    return gx, gy, mag


def refine_quad(gray_hi: np.ndarray, quad_hi: Quad, diag_hi: float,
                band: float, n_points: int, n_samples: int,
                local_band: float = 0.0, max_shift: float = 0.06,
                prior_weight: float = 0.35,
                outward_bias: float = 0.0) -> Tuple[Quad, Dict[str, float]]:
    """Pull each side onto the strongest intensity step along its normal.

    A page edge is faint and noisy frame to frame (low light, JPEG), so a single
    profile is never enough: the band is averaged across ``n_points`` positions along
    the side (median), which is where the step has to be *consistent* to be found.
    Then each point may move a little (``local_band``) to follow a bowed edge, and a
    robust line fit turns the points into the side.

    ``quad_hi`` and the returned quad are in the high-resolution pixel grid.
    """
    lines: List[Optional[Tuple[np.ndarray, np.ndarray]]] = []
    strengths: List[float] = []
    steps_ok = 0
    total_profiles = 0
    side_info: Dict[str, float] = {}
    cx = sum(p[0] for p in quad_hi) / 4.0
    cy = sum(p[1] for p in quad_hi) / 4.0
    offsets = np.linspace(-band, band, n_samples)
    step_size = offsets[1] - offsets[0]

    for i in range(4):
        (x1, y1) = quad_hi[i]
        (x2, y2) = quad_hi[(i + 1) % 4]
        ex, ey = x2 - x1, y2 - y1
        length = math.hypot(ex, ey)
        if length < 1e-6:
            lines.append(None)
            strengths.append(0.0)
            continue
        tx, ty = ex / length, ey / length
        nx, ny = ty, -tx
        if ((x1 + x2) * 0.5 - cx) * nx + ((y1 + y2) * 0.5 - cy) * ny < 0:
            nx, ny = -nx, -ny
        ts = np.linspace(0.08, 0.92, n_points)
        base_x = x1 + ts * ex
        base_y = y1 + ts * ey
        t_grid, o_grid = np.meshgrid(np.arange(n_points), offsets, indexing="ij")
        profile = sample_bilinear(gray_hi, base_x[t_grid] + nx * o_grid, base_y[t_grid] + ny * o_grid)
        # robust across the side, then smooth along the offset axis by a 3-tap average
        averaged = np.median(profile, axis=0)
        if n_samples > 5:
            smoothed = averaged.copy()
            smoothed[1:-1] = 0.25 * averaged[:-2] + 0.5 * averaged[1:-1] + 0.25 * averaged[2:]
            averaged = smoothed
        grad = averaged[1:] - averaged[:-1]          # positive = brighter going outward
        # we want a *negative* step (paper brighter inside), strongest first
        signs = -grad
        if os.environ.get("SCANNERX_DEBUG"):
            print(f"[prof] side{i} averaged[0:5]={np.round(averaged[:5],4)} averaged[-5:]={np.round(averaged[-5:],4)}", file=sys.stderr)
        mid = 0.5 * (offsets[1:] + offsets[:-1])
        peak = float(np.max(signs))
        # prior: stay near the mask-derived line unless a farther step is clearly stronger
        norm = max(abs(band), 1e-9)
        cost = signs - prior_weight * max(peak, 1e-9) * (np.abs(mid) / norm) \
            + outward_bias * max(peak, 1e-9) * (np.maximum(-mid, 0.0) / norm)
        j = int(np.argmax(cost))
        strength = float(max(signs[j], 0.0))
        # parabolic sub-pixel peak
        delta = 0.0
        if 0 < j < len(signs) - 1:
            denom = signs[j - 1] - 2 * signs[j] + signs[j + 1]
            if abs(denom) > 1e-12:
                delta = 0.5 * (signs[j - 1] - signs[j + 1]) / denom
                delta = max(-0.5, min(0.5, delta))
        shift = offsets[j] + 0.5 * step_size + delta * step_size

        px = base_x + nx * shift
        py = base_y + ny * shift
        # optional per-point correction: small band around the averaged edge
        if local_band > 0:
            loff = np.linspace(-local_band, local_band, max(9, n_samples // 3))
            lgrid = np.repeat(loff[None, :], n_points, axis=0)
            lprof = sample_bilinear(gray_hi, px[:, None] + nx * lgrid, py[:, None] + ny * lgrid)
            lgrad = lprof[:, 2:] - lprof[:, :-2]
            lj = np.argmax(-lgrad, axis=1)
            local_best = -lgrad[np.arange(n_points), lj]
            lshift = loff[lj + 1]
            keep = local_best > 0.4 * max(strength, 1e-6)
            px = px + nx * np.where(keep, lshift, 0.0)
            py = py + ny * np.where(keep, lshift, 0.0)
            steps_ok += int(keep.sum())
        total_profiles += n_points

        pts = np.stack([px, py], axis=1)
        fit = fit_line_tls(pts)
        if fit is not None:
            centroid, direction = fit
            resid = np.abs((pts - centroid) @ line_normal(direction))
            limit = max(1.0, 2.5 * float(np.median(resid)))
            keep_idx = resid <= limit
            if keep_idx.sum() >= max(4, n_points // 2):
                fit = fit_line_tls(pts[keep_idx])
        lines.append(fit)
        strengths.append(strength)
        side_info[f"side{i}_shift"] = float(shift / diag_hi)
        side_info[f"side{i}_strength"] = float(strength)
        if os.environ.get("SCANNERX_DEBUG"):
            print(f"[refine] side{i} n=({nx:.3f},{ny:.3f}) j={j} shift={shift:.1f} strength={strength:.4f}", file=sys.stderr)

    # reject a refined line that jumped far away from the mask-derived quad
    mask_lines = []
    for i in range(4):
        (x1, y1) = quad_hi[i]
        (x2, y2) = quad_hi[(i + 1) % 4]
        d = np.array([x2 - x1, y2 - y1], dtype=np.float64)
        ln = np.linalg.norm(d)
        mask_lines.append((np.array([x1, y1], dtype=np.float64), d / ln if ln > 1e-9 else np.array([1.0, 0.0])))
    for i in range(4):
        if lines[i] is None:
            lines[i] = mask_lines[i]
            continue
        centroid, direction = lines[i]
        base_pt, base_dir = mask_lines[i]
        gap = abs(float((centroid - base_pt) @ line_normal(base_dir)))
        if gap > max_shift * diag_hi:
            lines[i] = mask_lines[i]

    quad = quad_from_lines(lines, pairs=SIDE_PAIRS)
    if quad is None or not quad_is_convex(quad):
        quad = list(quad_hi)
    info = {
        "edge_strength": float(np.mean(strengths)) if strengths else 0.0,
        "edge_hit_rate": steps_ok / float(max(1, total_profiles)),
    }
    info.update(side_info)
    return quad, info


# ---------------------------------------------------------------------- detection


@dataclass
class Detection:
    quad: Optional[Quad] = None            # source pixels, TL/TR/BR/BL
    confidence: float = 0.0
    features: Dict[str, float] = field(default_factory=dict)
    work_quad: Optional[Quad] = None
    mask: Optional[np.ndarray] = None
    work_size: Tuple[int, int] = (0, 0)
    source_size: Tuple[int, int] = (0, 0)
    ms: float = 0.0


def _paper_map(rgb: np.ndarray, cfg: Config):
    """Paper-likelihood map S in [0,1] plus the lightness map it came from."""
    r = rgb[:, :, 0]
    g = rgb[:, :, 1]
    b = rgb[:, :, 2]
    lightness = 0.299 * r + 0.587 * g + 0.114 * b
    maxc = rgb.max(axis=2)
    minc = rgb.min(axis=2)
    chroma = maxc - minc

    lo = hist_percentile(lightness, 2.0)
    hi = hist_percentile(lightness, 98.0)
    if hi - lo < 1e-4:
        ln = np.zeros_like(lightness)
    else:
        ln = np.clip((lightness - lo) / (hi - lo), 0.0, 1.0)
    c90 = hist_percentile(chroma, 90.0)
    cn = np.clip(chroma / (c90 + 1e-3), 0.0, 1.0) if c90 > 1e-4 else np.zeros_like(chroma)
    score = np.clip(ln - cfg.chroma_weight * cn, 0.0, 1.0)
    return score, lightness, {"otsu": 0.0, "ln_lo": float(lo), "ln_hi": float(hi), "chroma_p90": float(c90)}


def _paper_mask(score: np.ndarray, cfg: Config) -> Tuple[np.ndarray, Dict[str, float]]:
    threshold = otsu(score)
    mask = score > threshold
    radius = cfg.morph_radius if cfg.morph_radius > 0 else max(1, int(round(cfg.work_side / 240.0)))
    mask = dilate(erode(mask, radius), radius)  # opening: kill thin noise, holes are irrelevant (hull)
    return mask, {"otsu": float(threshold)}


def quad_sane(quad: Quad, width: int, height: int, slack: float = 0.25) -> bool:
    """Reject nonsense quads: not convex, off the frame, or outside a sane area band."""
    if quad is None or not quad_is_convex(quad):
        return False
    for x, y in quad:
        if not (-slack * width <= x <= (1.0 + slack) * width):
            return False
        if not (-slack * height <= y <= (1.0 + slack) * height):
            return False
    frac = quad_area(quad) / float(width * height)
    return 0.015 <= frac <= 1.25


def detect_quad(rgb_small: np.ndarray, gray_hi: np.ndarray, cfg: Config) -> Detection:
    h, w = rgb_small.shape[:2]
    diag = math.hypot(w, h)
    frame_area = float(w * h)
    score, lightness, base_info = _paper_map(rgb_small, cfg)
    gmag = None
    grad_scale = 1.0
    mask, mask_info = _paper_mask(score, cfg)
    labels, stats = label_components(mask)

    candidates = []
    for index, st in enumerate(stats):
        area_frac = st["area"] / frame_area
        if area_frac < cfg.min_area_frac or area_frac > cfg.max_area_frac:
            continue
        hug = border_hug(labels, index + 1)
        if hug > cfg.max_border_hug:
            continue
        candidates.append((st["area"], index + 1, hug))
    candidates.sort(reverse=True)
    candidates = candidates[: cfg.max_candidates]

    info = dict(base_info)
    info.update(mask_info)
    best: Optional[Detection] = None
    got_acceptable = False
    for _, label, hug in candidates:
        comp = labels == label
        hull = mask_hull(comp)
        if len(hull) < 4:
            continue
        hull_area = quad_area([(float(x), float(y)) for x, y in hull])
        if hull_area <= 1e-6:
            continue
        extent = max(float(hull[:, 0].max() - hull[:, 0].min()),
                     float(hull[:, 1].max() - hull[:, 1].min()))
        lines = side_lines_from_hull(hull, band=cfg.band_frac * extent)
        mask_quad = quad_from_lines(lines)
        if not quad_sane(mask_quad, w, h, slack=0.25):
            continue
        if gmag is None:
            gmag = gradient_maps(gray_hi)[2]
            grad_scale = max(float(np.percentile(gmag, 99.0)), 1e-4)
        variants: List[Tuple[Quad, Dict[str, float]]] = []
        refined, edge_info = _refine(mask_quad, gray_hi, gmag, w, cfg)
        if quad_sane(refined, w, h, slack=0.25):
            variants.append((refined, edge_info))
        else:
            variants.append((mask_quad, {"edge_strength": 0.0, "edge_hit_rate": 0.0}))
        for quad, edge_info in variants:
            # edge support measured on the final quad, whatever produced it
            quad_hi = [(x * (gmag.shape[1] / w), y * (gmag.shape[1] / w)) for x, y in quad]
            supports = measure_quad_edges(gmag, quad_hi, grad_scale)
            edge_info = dict(edge_info)
            edge_info["support_mean"] = float(np.mean(supports))
            edge_info["support_min"] = float(min(supports))
            feats = _features(quad, hull, hull_area, frame_area, diag, edge_info, info, hug,
                              score, lightness)
            conf = _confidence(feats, cfg)
            candidate_det = Detection(confidence=conf, features=feats,
                                      work_quad=list(quad), work_size=(w, h))
            if best is None or conf > best.confidence:
                best = candidate_det
            # the page is the largest paper-like blob: take the first candidate that
            # clears the confidence bar instead of letting a small bright patch win
            if conf >= cfg.accept and not got_acceptable:
                best = candidate_det
                got_acceptable = True
                break
    if best is None:
        return Detection(confidence=0.0, features=info, work_size=(w, h))
    return best


def measure_quad_edges(gmag: np.ndarray, quad_hi: Quad, scale: float) -> List[float]:
    """Per-side median gradient magnitude along a quad's four sides, normalised.

    A page has a visible step on all four sides; a wall, a table or a door frame does
    not. This is the single most useful reject feature and it needs no training.
    """
    out: List[float] = []
    for i in range(4):
        (x1, y1) = quad_hi[i]
        (x2, y2) = quad_hi[(i + 1) % 4]
        length = math.hypot(x2 - x1, y2 - y1)
        n_pts = int(max(8, min(28, round(length / 24.0))))
        if length < 4.0:
            out.append(0.0)
            continue
        ts = np.linspace(0.04, 0.96, n_pts)
        vals = sample_bilinear(gmag, x1 + ts * (x2 - x1), y1 + ts * (y2 - y1))
        out.append(float(np.median(vals)) / max(scale, 1e-6))
    return out


def _refine(quad: Quad, gray_hi: np.ndarray, gmag: np.ndarray, work_w: int,
            cfg: Config) -> Tuple[Quad, Dict[str, float]]:
    hh, ww = gray_hi.shape
    f = ww / float(work_w)
    diag_hi = math.hypot(ww, hh)
    quad_hi = [(x * f, y * f) for x, y in quad]
    if not cfg.refine_enabled:
        return normalise_quad(list(quad)), {"edge_strength": 0.0, "edge_hit_rate": 0.0}
    # pass 1: wide band, one shift per side, median profile across the side
    worst, info1 = refine_quad(gray_hi, quad_hi, diag_hi,
                               band=cfg.refine_wide_frac * diag_hi,
                               n_points=cfg.refine_pass_points,
                               n_samples=81,
                               local_band=0.0,
                               max_shift=cfg.refine_wide_frac + 0.02,
                               prior_weight=cfg.refine_prior,
                               outward_bias=cfg.refine_outward)
    # pass 2: narrow band + per-point freedom, to follow a slightly bowed edge
    best_quad, info2 = refine_quad(gray_hi, worst, diag_hi,
                                   band=cfg.refine_narrow_frac * diag_hi,
                                   n_points=cfg.refine_pass_points,
                                   n_samples=max(21, cfg.refine_samples // 2),
                                   local_band=cfg.refine_local_frac * diag_hi,
                                   max_shift=cfg.refine_narrow_frac + 0.01,
                                   prior_weight=cfg.refine_prior * 0.6,
                                   outward_bias=cfg.refine_outward)
    info = dict(info2 or {})
    info["edge_strength"] = float(info1.get("edge_strength", 0.0))
    info["edge_min_strength"] = float(info1.get("edge_hit_rate", 0.0))
    back = [(x / f, y / f) for x, y in best_quad]
    return normalise_quad(back), info


def _features(quad: Quad, hull: np.ndarray, hull_area: float, frame_area: float,
              diag: float, edge_info: Dict[str, float], mask_info: Dict[str, float],
              hug: float, score: np.ndarray, lightness: np.ndarray) -> Dict[str, float]:
    h, w = score.shape
    area = quad_area(quad)
    feats: Dict[str, float] = {
        "area_frac": area / frame_area,
        "rect": area / hull_area if hull_area > 0 else 0.0,
        "border_hug": hug,
        "edge_strength": edge_info.get("edge_strength", 0.0),
        "edge_hit_rate": edge_info.get("edge_hit_rate", 0.0),
        "support_mean": edge_info.get("support_mean", 0.0),
        "support_min": edge_info.get("support_min", 0.0),
        "otsu": mask_info.get("otsu", 0.0),
    }
    sides = []
    for i in range(4):
        (x1, y1) = quad[i]
        (x2, y2) = quad[(i + 1) % 4]
        sides.append((x2 - x1, y2 - y1))
    lengths = [math.hypot(dx, dy) for dx, dy in sides]
    feats["min_side_frac"] = min(lengths) / max(1e-6, max(lengths))
    max_cos = 0.0
    for i in range(4):
        ax, ay = sides[i]
        bx, by = sides[(i + 1) % 4]
        la, lb = math.hypot(ax, ay), math.hypot(bx, by)
        if la < 1e-6 or lb < 1e-6:
            max_cos = 1.0
            break
        max_cos = max(max_cos, abs((ax * bx + ay * by) / (la * lb)))
    feats["max_cos"] = max_cos

    inside = polygon_mask(quad, w, h)
    outer = polygon_mask(scale_quad(quad, 1.18), w, h)
    ring = outer & ~dilate(inside, 1)
    if inside.any() and ring.any():
        feats["inside_score"] = float(score[inside].mean())
        feats["ring_score"] = float(score[ring].mean())
        feats["contrast"] = feats["inside_score"] - feats["ring_score"]
        feats["inside_std"] = float(lightness[inside].std())
        feats["ring_std"] = float(lightness[ring].std())
        feats["uniformity"] = feats["inside_std"] / max(1e-4, feats["ring_std"] + feats["inside_std"])
    else:
        feats["inside_score"] = feats["ring_score"] = feats["contrast"] = 0.0
        feats["inside_std"] = feats["ring_std"] = 0.0
        feats["uniformity"] = 1.0
    return feats


def _confidence(feats: Dict[str, float], cfg: Config) -> float:
    """Hand-tuned accept score; weights chosen on DEV (see the SCANNERX_PROTO report).

    Deliberately small and interpretable: paper-likeness contrast across the border,
    the strength of the four edge steps, how much the blob hugs the frame, the size,
    and how paper-like the interior is. No learned model, nothing to port beyond
    arithmetic.
    """
    area = feats["area_frac"]
    area_term = min(1.0, max(0.0, (area - 0.10) / 0.50)) * (1.0 - min(1.0, max(0.0, (area - 0.80) / 0.17)))
    hug_term = 1.0 - min(1.0, max(0.0, feats["border_hug"] / 0.35))
    contrast_term = min(1.0, max(0.0, feats["contrast"] / 0.30))
    edge_term = min(1.0, max(0.0, (feats["edge_strength"] - 0.004) / 0.046))
    inside_term = min(1.0, max(0.0, (feats["inside_score"] - 0.15) / 0.60))
    return (0.35 * contrast_term + 0.25 * edge_term + 0.15 * hug_term
            + 0.10 * area_term + 0.15 * inside_term)


# -------------------------------------------------------------------------- entry


def detect_path(path: str, cfg: Optional[Config] = None) -> Detection:
    cfg = cfg or Config()
    start = time.perf_counter()
    with Image.open(path) as img:
        img = img.convert("RGB")
        src_w, src_h = img.size
        f = cfg.work_side / float(max(src_w, src_h))
        work_w = max(8, int(round(src_w * f)))
        work_h = max(8, int(round(src_h * f)))
        small = img.resize((work_w, work_h), Image.BILINEAR)
        rgb = np.asarray(small, dtype=np.float32) / 255.0
        fr = cfg.refine_side / float(max(src_w, src_h))
        ref_w = max(8, int(round(src_w * fr)))
        ref_h = max(8, int(round(src_h * fr)))
        hi = img.resize((ref_w, ref_h), Image.BILINEAR) if (ref_w, ref_h) != (src_w, src_h) else img
        gray_hi = np.asarray(hi.convert("L"), dtype=np.float32) / 255.0
    det = detect_quad(rgb, gray_hi, cfg)
    if det.work_quad is not None:
        sx = src_w / float(work_w)
        sy = src_h / float(work_h)
        det.quad = [(x * sx, y * sy) for x, y in det.work_quad]
        det.source_size = (src_w, src_h)
    det.ms = (time.perf_counter() - start) * 1000.0
    return det


def main(argv: Optional[Sequence[str]] = None) -> int:
    import argparse
    import csv
    import os

    parser = argparse.ArgumentParser(description="Scanner X prototype: predict page corners for recorded frames.")
    parser.add_argument("--frames", required=True, help="frames root (qa/recordings/frames)")
    parser.add_argument("--out", required=True, help="predictions CSV")
    parser.add_argument("--features-out", default="", help="per-frame feature dump CSV (tuning only)")
    parser.add_argument("--clips", default="", help="comma separated clip list; empty = all folders")
    parser.add_argument("--accept", type=float, default=Config.accept, help="confidence threshold")
    args = parser.parse_args(argv)

    cfg = Config(accept=args.accept)
    root = args.frames
    if args.clips:
        clips = [c.strip() for c in args.clips.split(",") if c.strip()]
    else:
        clips = sorted(d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d)))
    rows = []
    feature_rows = []
    times = []
    for clip in clips:
        folder = os.path.join(root, clip)
        files = sorted((f for f in os.listdir(folder) if f.lower().endswith(".jpg")),
                       key=lambda name: int(os.path.splitext(name)[0]))
        for name in files:
            det = detect_path(os.path.join(folder, name), cfg)
            frame = int(os.path.splitext(name)[0])
            times.append(det.ms)
            accepted = det.quad is not None and det.confidence >= cfg.accept
            row = {"clip": clip, "frame": frame, "has_quad": 1 if accepted else 0}
            if accepted:
                for i, (x, y) in enumerate(det.quad):
                    row[f"x{i + 1}"] = int(round(x))
                    row[f"y{i + 1}"] = int(round(y))
            row["ms"] = f"{det.ms:.2f}"
            rows.append(row)
            feats = dict(det.features)
            feats.update({"clip": clip, "frame": frame, "confidence": det.confidence,
                          "has_quad_raw": 1 if det.quad is not None else 0})
            if det.quad is not None:
                for i, (x, y) in enumerate(det.quad):
                    feats[f"x{i + 1}"] = int(round(x))
                    feats[f"y{i + 1}"] = int(round(y))
            feature_rows.append(feats)
    fieldnames = ["clip", "frame", "has_quad", "x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4", "ms"]
    with open(args.out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in fieldnames})
    if args.features_out:
        keys = set()
        for f in feature_rows:
            keys.update(f.keys())
        ordered = ["clip", "frame", "confidence", "has_quad_raw"] + sorted(keys - {"clip", "frame", "confidence", "has_quad_raw"})
        with open(args.features_out, "w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=ordered)
            writer.writeheader()
            for f in feature_rows:
                writer.writerow({k: f.get(k, "") for k in ordered})
    times.sort()
    ms_median = times[len(times) // 2] if times else 0.0
    ms_p90 = times[int(len(times) * 0.9)] if times else 0.0
    print(f"wrote {args.out}: {len(rows)} frames, median {ms_median:.1f} ms, p90 {ms_p90:.1f} ms (PC CPython)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
