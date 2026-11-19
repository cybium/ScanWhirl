"""Mask logits -> page quad, for Scanner X ML.

The network only says "paper here" (a 128x128 probability map plus one presence logit).
Turning that into the four corners the app needs is a fixed, testable recipe:

1. ``presence < t_presence`` -> **no page** (this is the only gate that protects the
   NoDocument clips; the mask head is not asked to be empty, because a page that is
   partly cut off still produces paper somewhere in the frame).
2. Threshold the mask, keep the **largest 8-connected component** inside the valid
   (letterboxed) region of the frame.
3. Reject implausible blobs: area < 6 % of the valid region, or a "wall" -- a region
   that touches three or four frame borders while covering > 90 % of the frame.
4. Convex hull -> **maximum-area inscribed quadrilateral** over hull points, which is
   what makes the output a quad rather than a many-sided polygon.
5. Order the corners TL, TR, BR, BL and map back to source pixels.

Optional step 6: :func:`refine_with_steps` re-runs the Scanner X r2 one-dimensional
step-profile refinement at higher resolution on the result. It is reported with and
without so the cost/benefit is visible, and it is never required for a valid quad.

numpy + Pillow only. Pure functions; the same inputs give the same corners.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Optional, Sequence

import numpy as np
from PIL import Image

Quad = list[tuple[float, float]]

DEFAULT_T_PRESENCE = 0.5
DEFAULT_T_MASK = 0.5
MIN_AREA_FRAC = 0.06
WALL_AREA_FRAC = 0.90
WALL_MIN_BORDERS = 3
HULL_MAX_POINTS = 64

__all__ = [
    "PageDetection", "detect_from_outputs", "mask_probability_to_quad",
    "largest_component", "convex_hull", "max_area_quad", "order_quad", "quad_area",
    "refine_with_steps", "DEFAULT_T_PRESENCE", "DEFAULT_T_MASK",
]


# --------------------------------------------------------------------------- helpers
def sigmoid(x) -> np.ndarray:
    x = np.asarray(x, dtype=np.float32)
    return 1.0 / (1.0 + np.exp(-np.clip(x, -40.0, 40.0)))


def quad_area(quad: Sequence[Sequence[float]]) -> float:
    total = 0.0
    n = len(quad)
    for i in range(n):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % n]
        total += x1 * y2 - x2 * y1
    return abs(total) / 2.0


def order_quad(quad: Sequence[Sequence[float]]) -> Quad:
    """Canonical TL, TR, BR, BL order for any convex quad (deterministic on ties)."""
    pts = [(float(x), float(y)) for x, y in quad]
    if len(pts) != 4:
        raise ValueError("order_quad needs exactly 4 points")
    cy = sum(p[1] for p in pts) / 4.0
    cx = sum(p[0] for p in pts) / 4.0
    # Sort by angle around the centroid, then rotate so the top-left is first.
    # atan2 with y growing downwards, taken in ascending order, already yields the
    # TL, TR, BR, BL winding (cross-checked by the unit test on clockwise input).
    pts.sort(key=lambda p: math.atan2(p[1] - cy, p[0] - cx))
    start = min(range(4), key=lambda i: (pts[i][0] + pts[i][1], i))
    pts = pts[start:] + pts[:start]
    return [(float(x), float(y)) for x, y in pts]


def convex_hull(points: np.ndarray) -> np.ndarray:
    """Andrew monotone chain on (N,2) float points -> hull in counter-clockwise order."""
    pts = np.asarray(points, dtype=np.float64)
    if len(pts) < 3:
        return pts
    uniq = np.unique(pts, axis=0)
    if len(uniq) < 3:
        return uniq

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: list[np.ndarray] = []
    for p in uniq:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: list[np.ndarray] = []
    for p in uniq[::-1]:
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return np.asarray(lower[:-1] + upper[:-1], dtype=np.float64)


def _subsample_hull(hull: np.ndarray, max_points: int) -> np.ndarray:
    if len(hull) <= max_points:
        return hull
    idx = np.linspace(0, len(hull) - 1, max_points).round().astype(int)
    return hull[np.unique(idx)]


def max_area_quad(hull: np.ndarray, max_points: int = HULL_MAX_POINTS) -> Optional[Quad]:
    """Maximum-area quadrilateral inscribed in a convex polygon (exhaustive on diagonals).

    For every pair of hull vertices (i, j) used as the quad's diagonal, the best third
    and fourth vertices are the farthest hull points on either side of that diagonal
    (maximum triangle area). That is the classic O(n^3) formulation; the hull is
    subsampled to ``max_points`` first, so an 8-connectivity staircase hull cannot make
    the search slow. A short hill-climb then moves each of the four vertices along the
    full hull to recover what subsampling cost.
    """
    pts = np.asarray(hull, dtype=np.float64)
    n = len(pts)
    if n < 4:
        return None
    small = _subsample_hull(pts, max_points)
    m = len(small)
    best = (-1.0, None)
    for i in range(m):
        for j in range(i + 1, m):
            if j - i < 2 or (m - (j - i)) < 2:
                continue  # need at least one vertex strictly between on each side
            a = small[i]
            b = small[j]
            d = b - a
            cross = d[0] * (small[:, 1] - a[1]) - d[1] * (small[:, 0] - a[0])
            # vertices strictly between i and j in cyclic order, and the rest
            between = np.zeros(m, dtype=bool)
            between[i + 1:j] = True
            outside = ~between
            outside[i] = outside[j] = False
            if not between.any() or not outside.any():
                continue
            # The two chosen vertices must lie on opposite sides of the diagonal i-j;
            # taking |cross| on each arc guarantees that whichever way the hull turns.
            abs_cross = np.abs(cross)
            k1 = int(np.argmax(abs_cross * between))
            k2 = int(np.argmax(abs_cross * outside))
            if abs_cross[k1] <= 0 or abs_cross[k2] <= 0:
                continue
            area = 0.5 * float(abs_cross[k1] + abs_cross[k2])
            if area > best[0]:
                best = (area, (i, k1, j, k2))

    if best[1] is None:
        return None
    quad = _refine_quad_vertices(pts, list(best[1]))
    return [tuple(map(float, p)) for p in quad]


def _quad_area_indices(pts: np.ndarray, idx: Sequence[int]) -> float:
    return quad_area([pts[k] for k in idx])


def _refine_quad_vertices(pts: np.ndarray, idx: list[int], rounds: int = 3) -> list[np.ndarray]:
    """Move each vertex along its arc of hull neighbours while the quad area grows.

    ``idx`` is a quadruple in cyclic order ``(i, k1, j, k2)``; a slot's legal moves are
    the hull vertices strictly between its two neighbours (mod n), which keeps the
    polygon convex and simply-ordered by construction.
    """
    n = len(pts)
    idx = list(idx)
    best = _quad_area_indices(pts, idx)
    for _ in range(rounds):
        for slot in range(4):
            prev_i = idx[(slot - 1) % 4]
            next_i = idx[(slot + 1) % 4]
            steps = (next_i - prev_i) % n
            if steps <= 1:
                continue
            for step in range(1, steps):
                candidate = (prev_i + step) % n
                trial = list(idx)
                trial[slot] = candidate
                area = _quad_area_indices(pts, trial)
                if area > best + 1e-9:
                    best = area
                    idx = trial
    return [pts[k] for k in idx]


# --------------------------------------------------------------------------- component
def _row_runs(binary: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Horizontal runs of True per row, as (row, start, end_inclusive) arrays."""
    height, width = binary.shape
    padded = np.zeros((height, width + 2), dtype=np.int8)
    padded[:, 1:width + 1] = binary.astype(np.int8)
    diff = np.diff(padded, axis=1)
    starts_y, starts_x = np.nonzero(diff == 1)
    ends_y, ends_x = np.nonzero(diff == -1)
    # within each row a start always precedes its end, and rows are visited in order,
    # so pairing by index is exact (both arrays are row-major).
    return starts_y, starts_x, ends_x - 1


def largest_component(mask: np.ndarray) -> np.ndarray:
    """Boolean mask of the largest 8-connected component (run-based union-find).

    Runs (not pixels) are the union-find elements, so a 256x144 frame costs a few
    hundred unions instead of 36 000 pixel lookups: fast enough to run on every frame of
    every evaluation, while still being plain numpy + Python with no SciPy.

    Deterministic: ties are broken by the smallest run index, which follows raster order,
    so repeated runs cannot flip between two equally large components.
    """
    binary = np.asarray(mask, dtype=bool)
    if not binary.any():
        return np.zeros_like(binary)
    height, width = binary.shape
    rows, starts, ends = _row_runs(binary)
    count = len(rows)
    parent = list(range(count))

    def find(a: int) -> int:
        root = a
        while parent[root] != root:
            root = parent[root]
        while parent[a] != root:
            parent[a], a = root, parent[a]
        return root

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            if ra < rb:
                parent[rb] = ra
            else:
                parent[ra] = rb

    # link runs of adjacent rows that overlap (with the 1 px tolerance that makes the
    # connectivity 8-connected rather than 4-connected)
    row_start = np.searchsorted(rows, np.arange(height + 1), side="left")
    for y in range(height - 1):
        i, i_end = int(row_start[y]), int(row_start[y + 1])
        j, j_end = int(row_start[y + 1]), int(row_start[y + 2])
        while i < i_end and j < j_end:
            s1, e1 = starts[i], ends[i]
            s2, e2 = starts[j], ends[j]
            if s1 - 1 <= e2 and s2 - 1 <= e1:
                union(i, j)
            if e1 < e2:
                i += 1
            else:
                j += 1

    roots = np.array([find(i) for i in range(count)], dtype=np.int64)
    values, counts = np.unique(roots, return_counts=True)
    # np.unique sorts, so the first maximum is the smallest root: raster-order tie-break
    best_root = int(values[int(np.argmax(counts))])
    result = np.zeros_like(binary)
    for index in np.nonzero(roots == best_root)[0]:
        result[rows[index], starts[index]:ends[index] + 1] = True
    return result


def _boundary_points(component: np.ndarray) -> np.ndarray:
    """Component pixels that touch the outside (4-neighbourhood). Same hull, ~50x cheaper."""
    height, width = component.shape
    eroded = component.copy()
    eroded[1:, :] &= component[:-1, :]
    eroded[:-1, :] &= component[1:, :]
    eroded[:, 1:] &= component[:, :-1]
    eroded[:, :-1] &= component[:, 1:]
    return component & ~eroded


def _border_hits(component: np.ndarray) -> int:
    hits = 0
    if component[0, :].any():
        hits += 1
    if component[-1, :].any():
        hits += 1
    if component[:, 0].any():
        hits += 1
    if component[:, -1].any():
        hits += 1
    return hits


# --------------------------------------------------------------------------- detect
@dataclass
class PageDetection:
    quad: Optional[Quad] = None          # in the grid the mask was given in
    presence: float = 0.0
    reason: str = ""
    area_frac: float = 0.0
    borders: int = 0
    features: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.quad is not None


def mask_probability_to_quad(prob: np.ndarray, t_mask: float = DEFAULT_T_MASK,
                             min_area_frac: float = MIN_AREA_FRAC,
                             wall_area_frac: float = WALL_AREA_FRAC,
                             wall_borders: int = WALL_MIN_BORDERS) -> PageDetection:
    """Threshold -> largest component -> hull -> max-area quad (grid coordinates)."""
    prob = np.asarray(prob, dtype=np.float32)
    if prob.ndim != 2 or prob.size == 0:
        return PageDetection(reason="empty mask")
    total_area = float(prob.shape[0] * prob.shape[1])
    component = largest_component(prob >= t_mask)
    area = int(component.sum())
    if area == 0:
        return PageDetection(reason="no component above threshold")
    area_frac = area / total_area
    borders = _border_hits(component)
    features = {"area_px": area, "borders": borders}
    if area_frac < min_area_frac:
        return PageDetection(reason="area below minimum", area_frac=area_frac,
                             borders=borders, features=features)
    if borders >= wall_borders and area_frac > wall_area_frac:
        return PageDetection(reason="wall (borders + area)", area_frac=area_frac,
                             borders=borders, features=features)
    ys, xs = np.nonzero(_boundary_points(component))
    if len(xs) < 4:
        ys, xs = np.nonzero(component)
    hull = convex_hull(np.stack([xs, ys], axis=1).astype(np.float64))
    quad = max_area_quad(hull)
    if quad is None:
        return PageDetection(reason="hull too small for a quad", area_frac=area_frac,
                             borders=borders, features=features)
    quad = order_quad(quad)
    if quad_area(quad) <= 1e-6:
        return PageDetection(reason="degenerate quad", area_frac=area_frac,
                             borders=borders, features=features)
    return PageDetection(quad=quad, reason="ok", area_frac=area_frac, borders=borders,
                         features=features)


def detect_from_outputs(mask_prob: np.ndarray, presence: float, box,
                        t_presence: float = DEFAULT_T_PRESENCE,
                        t_mask: float = DEFAULT_T_MASK,
                        crop_valid: bool = True,
                        upsample: bool = True) -> PageDetection:
    """Full decision for one frame, quad returned in **network-grid** pixels.

    ``mask_prob`` is the sigmoid of the 128x128 mask logits; ``box`` is the
    :class:`data_ml.Letterbox` used for this frame, so the padding is cropped away
    before anything is measured (padding must never be mistaken for paper or for a
    frame border). With ``upsample`` the probability crop is bilinearly resized to the
    full 256-grid valid region before thresholding: the hull then has sub-pixel
    precision at half-pixel steps instead of 2-pixel steps, which matters for corners.
    """
    prob = np.asarray(mask_prob, dtype=np.float32)
    if crop_valid:
        valid_w = max(1, int(round(box.net_w / 2)))
        valid_h = max(1, int(round(box.net_h / 2)))
        prob = prob[:valid_h, :valid_w]
    if presence < t_presence:
        return PageDetection(quad=None, presence=float(presence),
                             reason=f"presence {presence:.3f} < {t_presence:.2f}")
    if upsample:
        prob = _resize_prob(prob, (int(box.net_w), int(box.net_h)))
    det = mask_probability_to_quad(prob, t_mask=t_mask)
    det.presence = float(presence)
    if det.quad is not None:
        scale_x = box.net_w / float(prob.shape[1])
        scale_y = box.net_h / float(prob.shape[0])
        det.quad = [(float(x) * scale_x, float(y) * scale_y) for x, y in det.quad]
    return det


# --------------------------------------------------------------------------- refinement
def refine_with_steps(gray_hi: np.ndarray, quad_hi: Sequence[Sequence[float]],
                      diag_hi: float, cfg=None) -> Optional[Quad]:
    """Optional r2 step-profile refinement of a quad, at a higher-resolution gray image.

    Returns the refined quad, or ``None`` when the refinement is unavailable or makes
    the quad worse (measured by area change beyond a sane band or a non-convex result).
    Imported lazily so this module keeps working if the r2 prototype moves.
    """
    try:
        sys_path_add = __import__("sys").path
        proto = __import__("os").path.abspath(
            __import__("os").path.join(__import__("os").path.dirname(__file__), "..", "proto"))
        if proto not in sys_path_add:
            sys_path_add.insert(0, proto)
        import scannerx_r2 as sx2  # noqa: PLC0415
    except Exception:
        return None
    if cfg is None:
        cfg = sx2.ConfigR2()
    try:
        refined, _info = sx2.refine_quad_step(np.asarray(gray_hi, dtype=np.float32),
                                              [tuple(map(float, p)) for p in quad_hi],
                                              float(diag_hi), cfg)
    except Exception:
        return None
    if refined is None or len(refined) != 4:
        return None
    if not all(math.isfinite(v) for p in refined for v in p):
        return None
    return [(float(x), float(y)) for x, y in refined]


def _resize_prob(prob: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    """Bilinear upsample of a float probability map (helper kept for callers/tests)."""
    width, height = size
    image = Image.fromarray(np.asarray(prob, dtype=np.float32))
    return np.asarray(image.resize((width, height), Image.BILINEAR), dtype=np.float32)
