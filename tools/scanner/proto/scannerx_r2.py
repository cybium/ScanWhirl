"""Scanner X round 2: robustness-first single-frame page detector.

Round 1 (``scannerx.py``, v2) overfit DEV: a per-frame Otsu mask plus a gradient
profile refinement scored 33 % recall on DEV and 4 % on the untouched TEST
hold-out. This module implements the four ranked fixes from that report:

  a. **hysteresis paper mask** -- grow from the bright/low-chroma core inside a
     smoothed-gradient barrier, so a page stops at its own edge instead of
     swallowing the floor (or stopping inside the page).
  b. **step-profile side support** -- a page side is a *bright-inside vs darker-
     outside step*, not merely a large gradient. Text columns have large
     gradients but no step.
  c. **explicit rejection features** -- screen (blue-ish emissive + bezel +
     keyboard below), door/wall (large, border-touching, low inner texture),
     with a confidence that is a small sum of bounded terms.
  d. **hybrid candidate** -- the shipped ``Deskew.detectQuad`` heuristic is
     ported in ``deskew_port.py`` and its quad competes as one candidate; the
     mask, step and rejection stages decide whether it is paper.

All primitives are portable to Kotlin (integral-image box sums, histograms,
run-length components, hull, TLS fits, bilinear sampling). No OpenCV, no model.

Coordinates: ``detect_r2`` works in the work image (long side ``work_side``) and
returns a quad in work pixels; ``detect_path_r2`` scales it back to source JPEG
pixels, TL/TR/BR/BL like the labels.
"""

from __future__ import annotations

import math
import os
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image

import deskew_port
import scannerx as sx
from scannerx import (
    Detection,
    Quad,
    Point,
    box_mean,
    border_hug,
    dilate,
    erode,
    fit_line_tls,
    gradient_maps,
    hist_percentile,
    label_components,
    line_normal,
    mask_hull,
    normalise_quad,
    otsu,
    polygon_mask,
    quad_area,
    quad_from_lines,
    quad_is_convex,
    quad_sane,
    sample_bilinear,
    scale_quad,
    side_lines_from_hull,
    SIDE_PAIRS,
)


# --------------------------------------------------------------------------- config


@dataclass
class ConfigR2:
    work_side: int = 640
    refine_side: int = 1280

    # paper map
    chroma_weight: float = 0.35

    # hysteresis mask
    barrier_radius: int = 0          # 0 -> work_side/80 (smoothing for the barrier)
    barrier_strength: float = 0.55   # smoothed-gradient percentile gate (0..1)
    seed_percentiles: tuple = (85.0, 90.0, 95.0)
    hysteresis_deltas: tuple = (0.10, 0.12, 0.18)
    min_seed_frac: float = 0.005     # a component must contain this share of bright core
    min_area_frac: float = 0.03
    max_area_frac: float = 0.96
    max_border_hug: float = 0.55
    max_components: int = 2

    # step-profile refinement
    step_wide_frac: float = 0.10
    step_narrow_frac: float = 0.018
    step_local_frac: float = 0.004
    step_points: int = 15
    step_samples: int = 81
    step_prior: float = 0.35
    step_max_shift: float = 0.12
    step_window_frac: float = 0.10   # inner/outer window, fraction of the band

    # candidate set
    use_mask: bool = True
    use_shipped: bool = True

    # rejection / confidence
    accept: float = 0.55
    w_step: float = 0.30
    w_cons: float = 0.15
    w_contrast: float = 0.25
    w_area: float = 0.10
    w_border: float = 0.10
    w_inside: float = 0.10
    screen_gain: float = 0.55        # subtract this * screen risk
    door_gain: float = 0.45          # subtract this * door/wall risk


# --------------------------------------------------------------- paper map / mask


def paper_map(rgb: np.ndarray, cfg: ConfigR2):
    """S = normalised lightness - chroma_weight * normalised chroma (as v2)."""
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
    return score, lightness, chroma, {
        "ln_lo": float(lo), "ln_hi": float(hi), "chroma_p90": float(c90),
    }


def barrier_gradient(lightness: np.ndarray, cfg: ConfigR2, work_side: int):
    """Large-scale gradient: text strokes average out, the page border does not."""
    radius = cfg.barrier_radius if cfg.barrier_radius > 0 else max(2, int(round(work_side / 80.0)))
    smooth = box_mean(lightness, radius)
    gx, gy, _ = gradient_maps(smooth)
    mag = np.hypot(gx, gy)
    scale = max(float(np.percentile(mag, 99.0)), 1e-6)
    return mag / scale, float(radius)


def hysteresis_components(score: np.ndarray, lightness: np.ndarray, cfg: ConfigR2,
                          work_side: int, seed_percentile: float = 90.0,
                          delta: float = 0.12, gn: Optional[np.ndarray] = None):
    """Two-threshold growth inside a smoothed-gradient barrier.

    ``allowed`` is the weak region; a component is a candidate only if it owns a
    share of the strong core (the seed). The barrier is what stops a white page
    growing into a white floor: growth crosses weak colour differences but not a
    large-scale brightness edge.
    """
    t_hi = otsu(score)
    if seed_percentile > 0:
        t_hi = max(t_hi, hist_percentile(score, seed_percentile))
    t_lo = max(0.02, t_hi - delta)
    if gn is None:
        gn, radius = barrier_gradient(lightness, cfg, work_side)
    else:
        radius = cfg.barrier_radius if cfg.barrier_radius > 0 else max(2, int(round(work_side / 80.0)))
    allowed = (score > t_lo) & (gn < cfg.barrier_strength)
    seeds = (score > t_hi) & (gn < cfg.barrier_strength * 0.9)
    # the barrier can cut a page at a strong inner shadow; a 1 px close reconnects
    allowed = dilate(erode(allowed, 1), 1)
    labels, stats = label_components(allowed)
    return labels, stats, seeds, {
        "otsu": float(t_hi), "t_lo": float(t_lo), "barrier": float(cfg.barrier_strength),
        "barrier_radius": float(radius),
    }


def min_area_rect_quad(hull: np.ndarray) -> Quad:
    """Stable initial quad: the minimum-area rectangle around the hull.

    The support-line fit in ``side_lines_from_hull`` is ideal for a clean convex
    page but unstable when a shadow adds a thin tail to the component. The
    min-area rectangle is always finite and convex, and the step refinement that
    follows is what moves the sides onto the paper edge.
    """
    _, u, v = sx.min_area_rect_dirs(hull)
    pu = hull @ u
    pv = hull @ v
    umin, umax = float(pu.min()), float(pu.max())
    vmin, vmax = float(pv.min()), float(pv.max())
    corners = [umin * u + vmin * v, umax * u + vmin * v,
               umax * u + vmax * v, umin * u + vmax * v]
    return normalise_quad([(float(x), float(y)) for x, y in corners])


def component_candidates(labels: np.ndarray, stats: List[dict], seeds: np.ndarray,
                         frame_area: float, cfg: ConfigR2, limit: Optional[int] = None):
    h, w = labels.shape
    candidates = []
    for index, st in enumerate(stats):
        area_frac = st["area"] / frame_area
        if area_frac < cfg.min_area_frac or area_frac > cfg.max_area_frac:
            continue
        comp = labels == (index + 1)
        seed_count = int((comp & seeds).sum())
        if seed_count < max(30, int(cfg.min_seed_frac * st["area"])):
            continue
        hug = border_hug(labels, index + 1)
        if hug > cfg.max_border_hug:
            continue
        candidates.append({
            "label": index + 1, "area": st["area"], "area_frac": area_frac,
            "hug": hug, "seed_frac": seed_count / float(max(1, st["area"])),
            "source": "mask",
        })
    candidates.sort(key=lambda c: c["area"], reverse=True)
    take = cfg.max_components if limit is None else limit
    return candidates[:take]


# ---------------------------------------------------------------- step profiles


def _smooth3(values: np.ndarray) -> np.ndarray:
    if len(values) <= 2:
        return values
    out = values.copy()
    out[1:-1] = 0.25 * values[:-2] + 0.5 * values[1:-1] + 0.25 * values[2:]
    return out


def step_profile(gray: np.ndarray, quad_hi: Quad, diag_hi: float,
                 band: float, n_points: int, n_samples: int,
                 prior: float) -> List[Dict[str, float]]:
    """For each side: the strongest bright-inside/darker-outside step.

    A side profile is sampled along the outward normal and median-averaged over
    ``n_points`` positions, then the edge is the offset maximising

        mean(profile[j-w:j]) - mean(profile[j:j+w])          (w = 10 % of band)

    with a mild closeness prior. The window difference is the step, not a
    derivative: a text column edge is a dark line on both sides and therefore
    produces no step, while the page border is bright on one side only.
    ``consistency`` is the share of the individual positions that see the same
    positive step at the chosen offset, which is what separates a real page edge
    from a local feature.
    """
    h, w = gray.shape
    cx = sum(p[0] for p in quad_hi) / 4.0
    cy = sum(p[1] for p in quad_hi) / 4.0
    offsets = np.linspace(-band, band, n_samples)
    if n_samples < 9:
        return [{"step": 0.0, "step_norm": 0.0, "range": 0.0, "shift": 0.0,
                 "consistency": 0.0} for _ in range(4)]
    win = max(2, int(round(len(offsets) * 0.10)))
    span = max(0.02, 2.0 * band)
    results: List[Dict[str, float]] = []
    for i in range(4):
        (x1, y1) = quad_hi[i]
        (x2, y2) = quad_hi[(i + 1) % 4]
        ex, ey = x2 - x1, y2 - y1
        length = math.hypot(ex, ey)
        if length < 1e-6:
            results.append({"step": 0.0, "step_norm": 0.0, "range": 0.0,
                            "shift": 0.0, "consistency": 0.0})
            continue
        tx, ty = ex / length, ey / length
        nx, ny = ty, -tx
        if ((x1 + x2) * 0.5 - cx) * nx + ((y1 + y2) * 0.5 - cy) * ny < 0:
            nx, ny = -nx, -ny
        ts = np.linspace(0.08, 0.92, n_points)
        base_x = x1 + ts * ex
        base_y = y1 + ts * ey
        t_grid, o_grid = np.meshgrid(np.arange(n_points), offsets, indexing="ij")
        prof = sample_bilinear(gray, base_x[t_grid] + nx * o_grid,
                               base_y[t_grid] + ny * o_grid)  # (points, offsets)
        averaged = _smooth3(np.median(prof, axis=0))
        # step at each candidate edge index j (inside = before j). A real page
        # side stays darker outside the step; a text rule is dark for a few
        # pixels and then returns to paper, so its long-range support collapses.
        steps = np.full(n_samples, -1.0)
        quality = np.full(n_samples, -1.0)
        supports = np.zeros(n_samples)
        for j in range(win, n_samples - win):
            inside_m = float(averaged[j - win:j].mean())
            outside_m = float(averaged[j:j + win].mean())
            step_j = inside_m - outside_m
            tail = averaged[j + win:min(n_samples, j + 6 * win)]
            support_j = float(np.mean(tail < inside_m - 0.5 * max(step_j, 0.0))) if len(tail) else 0.0
            steps[j] = step_j
            supports[j] = support_j
            quality[j] = step_j * (0.25 + 0.75 * support_j)
        peak = max(float(quality.max()), 0.0)
        mid = offsets
        cost = quality - prior * max(peak, 1e-9) * (np.abs(mid) / max(band, 1e-9))
        j = int(np.argmax(cost))
        best = float(steps[j])
        support = float(supports[j])
        # per-position consistency at that same offset
        per = prof[:, j - win:j].mean(axis=1) - prof[:, j:j + win].mean(axis=1)
        consistency = float(np.mean(per > max(0.25 * best, 1e-4))) if best > 0 else 0.0
        rng = float(np.percentile(averaged, 95) - np.percentile(averaged, 5))
        shift = 0.5 * (offsets[j - 1] + offsets[j]) if j > 0 else offsets[j]
        # edge sharpness: how concentrated the transition is around the chosen
        # edge. A paper border ramps over 1-3 px; a wall shadow over dozens.
        lo = max(1, j - win)
        hi = min(n_samples - 2, j + win)
        diff = np.abs(np.diff(averaged[lo:hi + 1])) if hi > lo else np.array([0.0])
        sharp = float(diff.max()) / max(best, 1e-6)
        # ramp: a paper edge is flat on both sides of the transition; a lighting
        # gradient keeps sloping. Compare the mean slope of the inner and outer
        # thirds of the profile with the step itself.
        seg = max(3, win)
        inner_lo = max(0, j - 3 * win)
        outer_hi = min(n_samples, j + 3 * win)
        slope_in = 0.0
        slope_out = 0.0
        if j - inner_lo >= 3:
            slope_in = float(np.polyfit(offsets[inner_lo:j - win + 1],
                                        averaged[inner_lo:j - win + 1], 1)[0]) if j - win > inner_lo + 1 else 0.0
        if outer_hi - (j + win) >= 3:
            slope_out = float(np.polyfit(offsets[j + win:outer_hi],
                                         averaged[j + win:outer_hi], 1)[0]) if outer_hi > j + win + 1 else 0.0
        ramp = (abs(slope_in) + abs(slope_out)) * band / max(best, 1e-6)
        ramp_in = abs(slope_in) * band / max(best, 1e-6)
        ramp_out = abs(slope_out) * band / max(best, 1e-6)
        # parabolic sub-sample refinement of the step peak
        if 0 < j < n_samples - 1 and steps[j] > 0:
            denom = steps[j - 1] - 2.0 * steps[j] + steps[j + 1]
            if abs(denom) > 1e-9:
                delta = 0.5 * (steps[j - 1] - steps[j + 1]) / denom
                shift += float(np.clip(delta, -0.5, 0.5)) * (offsets[1] - offsets[0])
        results.append({
            "step": best,
            "step_norm": best / max(rng, 0.02),
            "range": rng,
            "shift": float(shift),
            "consistency": consistency,
            "support": support,
            "sharp": sharp,
            "ramp": ramp,
            "ramp_in": ramp_in,
            "ramp_out": ramp_out,
            "normal": (float(nx), float(ny)),
        })
    return results


def refine_quad_step(gray_hi: np.ndarray, quad_hi: Quad, diag_hi: float,
                     cfg: ConfigR2, pass_wide: bool = True) -> Tuple[Quad, Dict[str, float]]:
    """Two-pass step refinement (one line per side, then per-point bow freedom)."""
    if pass_wide:
        band = cfg.step_wide_frac * diag_hi
        n_samples = cfg.step_samples
        local_band = 0.0
    else:
        band = cfg.step_narrow_frac * diag_hi
        n_samples = max(21, cfg.step_samples // 2)
        local_band = cfg.step_local_frac * diag_hi
    prof = step_profile(gray_hi, quad_hi, diag_hi, band, cfg.step_points, n_samples,
                        cfg.step_prior)
    lines: List[Optional[Tuple[np.ndarray, np.ndarray]]] = []
    strengths: List[float] = []
    total = 0
    hits = 0
    cx = sum(p[0] for p in quad_hi) / 4.0
    cy = sum(p[1] for p in quad_hi) / 4.0
    for i in range(4):
        (x1, y1) = quad_hi[i]
        (x2, y2) = quad_hi[(i + 1) % 4]
        ex, ey = x2 - x1, y2 - y1
        length = math.hypot(ex, ey)
        info = prof[i]
        if length < 1e-6 or "normal" not in info:
            lines.append(None)
            strengths.append(0.0)
            continue
        nx, ny = info["normal"]
        shift = info["shift"]
        ts = np.linspace(0.08, 0.92, cfg.step_points)
        px = x1 + ts * ex + nx * shift
        py = y1 + ts * ey + ny * shift
        if local_band > 0:
            loff = np.linspace(-local_band, local_band, max(9, n_samples // 3))
            lgrid = np.repeat(loff[None, :], cfg.step_points, axis=0)
            lprof = sample_bilinear(gray_hi, px[:, None] + nx * lgrid,
                                    py[:, None] + ny * lgrid)
            w = max(1, lprof.shape[1] // 10)
            lsteps = np.full(lprof.shape, -1.0)
            for j in range(w, lprof.shape[1] - w):
                lsteps[:, j] = (lprof[:, j - w:j].mean(axis=1)
                                - lprof[:, j:j + w].mean(axis=1))
            best_j = np.argmax(lsteps, axis=1)
            best_val = lsteps[np.arange(len(best_j)), best_j]
            keep = best_val > 0.30 * max(info["step"], 1e-6)
            lshift = loff[np.clip(best_j, 0, len(loff) - 1)]
            px = px + nx * np.where(keep, lshift, 0.0)
            py = py + ny * np.where(keep, lshift, 0.0)
            hits += int(keep.sum())
            total += cfg.step_points
        fit = fit_line_tls(np.stack([px, py], axis=1))
        if fit is not None:
            centroid, direction = fit
            resid = np.abs((np.stack([px, py], axis=1) - centroid) @ line_normal(direction))
            limit = max(1.0, 2.5 * float(np.median(resid)))
            keep_idx = resid <= limit
            if keep_idx.sum() >= max(4, cfg.step_points // 2):
                fit = fit_line_tls(np.stack([px, py], axis=1)[keep_idx])
        lines.append(fit)
        strengths.append(info["step"])
    # a side that jumped farther than the search band is not trusted: revert
    mask_lines = []
    for i in range(4):
        (x1, y1) = quad_hi[i]
        (x2, y2) = quad_hi[(i + 1) % 4]
        d = np.array([x2 - x1, y2 - y1], dtype=np.float64)
        ln = float(np.linalg.norm(d))
        mask_lines.append((np.array([x1, y1], dtype=np.float64), d / ln if ln > 1e-9 else np.array([1.0, 0.0])))
    for i in range(4):
        if lines[i] is None:
            lines[i] = mask_lines[i]
            continue
        centroid, direction = lines[i]
        base_pt, base_dir = mask_lines[i]
        gap = abs(float((centroid - base_pt) @ line_normal(base_dir)))
        if gap > cfg.step_max_shift * diag_hi:
            lines[i] = mask_lines[i]
    quad = quad_from_lines(lines, pairs=SIDE_PAIRS)
    if quad is None or not quad_is_convex(quad):
        quad = list(quad_hi)
    del cx, cy
    return quad, {
        "edge_strength": float(np.mean(strengths)) if strengths else 0.0,
        "edge_hit_rate": hits / float(max(1, total)),
    }


def measure_quad_steps(gray_hi: np.ndarray, quad_hi: Quad, diag_hi: float,
                       cfg: ConfigR2) -> Dict[str, float]:
    prof = step_profile(gray_hi, quad_hi, diag_hi, 0.035 * diag_hi,
                        max(11, cfg.step_points), 41, cfg.step_prior)
    out: Dict[str, float] = {}
    step_vals = [p["step_norm"] for p in prof]
    step_raw = [p["step"] for p in prof]
    cons = [p["consistency"] for p in prof]
    for i in range(4):
        out[f"side{i}_step"] = float(step_vals[i])
        out[f"side{i}_step_raw"] = float(step_raw[i])
        out[f"side{i}_cons"] = float(cons[i])
    out["step_min"] = float(min(step_vals))
    out["step_mean"] = float(np.mean(step_vals))
    out["step_min_raw"] = float(min(step_raw))
    out["cons_min"] = float(min(cons))
    out["cons_mean"] = float(np.mean(cons))
    sharps = [p.get("sharp", 0.0) for p in prof]
    out["sharp_min"] = float(min(sharps))
    out["sharp_mean"] = float(np.mean(sharps))
    ramps = [p.get("ramp", 0.0) for p in prof]
    out["ramp_min"] = float(min(ramps))
    out["ramp_max"] = float(max(ramps))
    out["ramp_mean"] = float(np.mean(ramps))
    sup = [p.get("support", 0.0) for p in prof]
    out["support_min"] = float(min(sup))
    out["support_mean"] = float(np.mean(sup))
    rin = [p.get("ramp_in", 0.0) for p in prof]
    out["ramp_in_min"] = float(min(rin))
    out["ramp_in_mean"] = float(np.mean(rin))
    rout = [p.get("ramp_out", 0.0) for p in prof]
    out["ramp_out_min"] = float(min(rout))
    out["ramp_out_mean"] = float(np.mean(rout))
    return out


# --------------------------------------------------------------------- features


def hf_map(lightness: np.ndarray, radius: int = 2) -> np.ndarray:
    return np.abs(lightness - box_mean(lightness, radius))


def region_features(work_l: np.ndarray, work_chroma: np.ndarray, work_score: np.ndarray,
                    hf: np.ndarray, gn: np.ndarray, quad: Quad,
                    cfg: ConfigR2) -> Dict[str, float]:
    h, w = work_l.shape
    inside = polygon_mask(quad, w, h)
    outer = polygon_mask(scale_quad(quad, 1.20), w, h)
    ring = outer & ~dilate(inside, 1)
    outer_n = polygon_mask(scale_quad(quad, 1.06), w, h)
    ring_n = outer_n & ~dilate(inside, 1)
    feats: Dict[str, float] = {}
    if inside.any() and ring.any():
        feats["inside_score"] = float(work_score[inside].mean())
        feats["ring_score"] = float(work_score[ring].mean())
        feats["contrast"] = feats["inside_score"] - feats["ring_score"]
        if ring_n.any():
            feats["contrast_narrow"] = feats["inside_score"] - float(work_score[ring_n].mean())
        else:
            feats["contrast_narrow"] = feats["contrast"]
        feats["inside_L"] = float(work_l[inside].mean())
        feats["ring_L"] = float(work_l[ring].mean())
        feats["inside_chroma"] = float(work_chroma[inside].mean())
        feats["ring_chroma"] = float(work_chroma[ring].mean())
        hf_inside = float(hf[inside].mean())
        feats["hf_inside"] = hf_inside
        feats["hf_ring"] = float(hf[ring].mean())
        lf = box_mean(work_l, 8)
        feats["inside_lf_std"] = float(np.std(lf[inside]))
        feats["inside_std"] = float(np.std(work_l[inside]))
        feats["ring_std"] = float(np.std(work_l[ring]))
        feats["uniformity"] = feats["inside_std"] / max(1e-4, feats["inside_std"] + feats["ring_std"])
        feats["inside_lf_range"] = float(np.percentile(lf[inside], 95) - np.percentile(lf[inside], 5))
    else:
        for key in ("inside_score", "ring_score", "contrast", "contrast_narrow",
                    "inside_L", "ring_L", "inside_chroma", "ring_chroma", "hf_inside",
                    "hf_ring", "inside_lf_std", "inside_std", "ring_std",
                    "inside_lf_range"):
            feats[key] = 0.0
        feats["uniformity"] = 1.0
    # gradient actually on the candidate boundary: a page side sits on a sharp
    # edge; a wall patch is bounded by a smooth lighting gradient.
    side_vals = []
    for i in range(4):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % 4]
        length = math.hypot(x2 - x1, y2 - y1)
        if length < 2.0:
            side_vals.append(0.0)
            continue
        ts = np.linspace(0.1, 0.9, 9)
        vals = []
        for off in (-0.012, 0.0, 0.012):
            nx, ny = (y2 - y1) / length, -(x2 - x1) / length
            vals.append(sample_bilinear(gn, x1 + ts * (x2 - x1) + nx * off * w,
                                        y1 + ts * (y2 - y1) + ny * off * h))
        side_vals.append(float(np.median(np.max(np.stack(vals), axis=0))))
    feats["grad_side"] = min(side_vals) if side_vals else 0.0
    feats["grad_side_mean"] = float(np.mean(side_vals)) if side_vals else 0.0
    feats["global_sharp"] = float(np.percentile(gn, 90))
    feats["area_frac"] = quad_area(quad) / float(w * h)
    # border contact: a side is "on the border" when its midpoint is within 2.5 %
    touch = 0
    for i in range(4):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % 4]
        mx, my = 0.5 * (x1 + x2), 0.5 * (y1 + y2)
        if (mx < 0.025 * w or mx > 0.975 * w or my < 0.025 * h or my > 0.975 * h):
            touch += 1
    feats["border_touch_n"] = float(touch)
    return feats


def screen_features(work_l: np.ndarray, work_rgb: np.ndarray, hf: np.ndarray,
                    quad: Quad, cfg: ConfigR2) -> Dict[str, float]:
    """Emissive-screen cues: blue-white interior, dark bezel, keyboard below.

    None of the three is decisive alone; the risk is a weighted sum, so a paper
    sheet under cool daylight is not rejected by tint alone.
    """
    h, w = work_l.shape
    inside = polygon_mask(quad, w, h)
    if not inside.any():
        return {"blue": 0.0, "bezel": 0.0, "keyboard": 0.0, "screen_risk": 0.0}
    r = work_rgb[:, :, 0]
    g = work_rgb[:, :, 1]
    b = work_rgb[:, :, 2]
    blue = float((b[inside] - 0.5 * (r[inside] + g[inside])).mean())
    # a screen's black bezel is a thin dark band right outside the active area,
    # then the wall/desk is brighter again.
    bezel_vals = []
    hf_below = 0.0
    below_l = 0.0
    # bottom side in quad order is index 2 -> 3 (TL,TR,BR,BL)
    x1, y1 = quad[2]
    x2, y2 = quad[3]
    for i in range(4):
        (ax, ay) = quad[i]
        (bx, by) = quad[(i + 1) % 4]
        ex, ey = bx - ax, by - ay
        length = math.hypot(ex, ey)
        if length < 1e-6:
            continue
        nx, ny = ey / length, -ex / length
        # point the normal away from the centre
        ccx = sum(p[0] for p in quad) / 4.0
        ccy = sum(p[1] for p in quad) / 4.0
        if ((ax + bx) * 0.5 - ccx) * nx + ((ay + by) * 0.5 - ccy) * ny < 0:
            nx, ny = -nx, -ny
        ts = np.linspace(0.15, 0.85, 9)
        near = []
        far = []
        for frac in (0.012, 0.025):
            near.append(sample_bilinear(work_l, ax + ts * ex + nx * frac * w,
                                        ay + ts * ey + ny * frac * h))
        for frac in (0.09, 0.14):
            far.append(sample_bilinear(work_l, ax + ts * ex + nx * frac * w,
                                       ay + ts * ey + ny * frac * h))
        near_v = float(np.mean([v.mean() for v in near]))
        far_v = float(np.mean([v.mean() for v in far]))
        bezel_vals.append(max(0.0, far_v - near_v))
    # keyboard: textured, darker band under the bottom side
    bx1, by1 = quad[2]
    bx2, by2 = quad[3]
    lo_y = int(np.clip(max(by1, by2) + 0.02 * h, 0, h - 2))
    hi_y = int(np.clip(max(by1, by2) + 0.28 * h, 0, h))
    lo_x = int(np.clip(min(bx1, bx2), 0, w - 2))
    hi_x = int(np.clip(max(bx1, bx2), 0, w))
    if hi_y - lo_y >= 4 and hi_x - lo_x >= 4:
        band = np.s_[lo_y:hi_y, lo_x:hi_x]
        hf_below = float(hf[band].mean())
        below_l = float(work_l[band].mean())
    hf_inside = float(hf[inside].mean())
    inside_l = float(work_l[inside].mean())
    keyboard = 0.0
    if hf_inside > 1e-4:
        keyboard = (min(1.0, max(0.0, (hf_below / max(hf_inside, 1e-4) - 0.6) / 0.8))
                    * min(1.0, max(0.0, (inside_l - below_l) / 0.15)))
    bezel = float(np.mean(bezel_vals)) if bezel_vals else 0.0
    blue_n = min(1.0, max(0.0, blue / 0.06))
    bezel_n = min(1.0, max(0.0, (bezel - 0.02) / 0.10))
    risk = 0.45 * blue_n + 0.35 * bezel_n + 0.20 * keyboard
    return {"blue": blue, "bezel": bezel, "keyboard": keyboard,
            "blue_n": blue_n, "bezel_n": bezel_n, "screen_risk": risk}


def door_features(feats: Dict[str, float]) -> Dict[str, float]:
    """Door/wall: large, often border-touching, and low inner text texture.

    A page is bright and *locally uniform* (lighting gradient) while carrying
    high-frequency ink; a wooden door has mid-frequency grain and no ink. The
    risk is gated by size so a small card is never rejected by it.
    """
    area = feats.get("area_frac", 0.0)
    hf = feats.get("hf_inside", 0.0)
    lf = feats.get("inside_lf_std", 0.0)
    touch = feats.get("border_touch_n", 0.0)
    texture = min(1.0, hf / 0.035)          # ink raises hf well above 0.035
    flat = min(1.0, max(0.0, (0.035 - lf) / 0.03))
    large = min(1.0, max(0.0, (area - 0.38) / 0.32))
    touch_n = min(1.0, touch / 2.0)
    risk = large * (0.35 + 0.35 * flat + 0.30 * (1.0 - texture)) * (0.4 + 0.6 * touch_n)
    return {"texture": texture, "flat": flat, "large": large, "door_risk": risk}


# --------------------------------------------------------------------- detection


def _confidence(feats: Dict[str, float], cfg: ConfigR2) -> Tuple[float, Dict[str, float]]:
    step_term = min(1.0, max(0.0, (feats.get("step_min", 0.0) - 0.08) / 0.45))
    cons_term = min(1.0, max(0.0, (feats.get("cons_min", 0.0) - 0.25) / 0.55))
    contrast_term = min(1.0, max(0.0, (feats.get("contrast", 0.0) - 0.01) / 0.22))
    area = feats.get("area_frac", 0.0)
    area_term = (min(1.0, max(0.0, (area - 0.06) / 0.35))
                 * (1.0 - min(1.0, max(0.0, (area - 0.82) / 0.16))))
    border_term = 1.0 - min(1.0, max(0.0, feats.get("border_hug", 0.0) / 0.55))
    inside_term = min(1.0, max(0.0, (feats.get("inside_score", 0.0) - 0.18) / 0.55))
    base = (cfg.w_step * step_term + cfg.w_cons * cons_term
            + cfg.w_contrast * contrast_term + cfg.w_area * area_term
            + cfg.w_border * border_term + cfg.w_inside * inside_term)
    # a page has a step on all four sides; a quad whose weakest side has almost
    # no bright-inside/darker-outside step is a texture region, not a page
    step_gate = min(1.0, max(0.0, (feats.get("step_min", 0.0) - 0.04) / 0.12))
    base *= step_gate
    screen_pen = 0.90 * min(1.0, max(0.0, (feats.get("screen_risk", 0.0) - 0.40) / 0.45))
    if feats.get("screen_risk", 0.0) >= 0.58 and feats.get("blue", 0.0) > 0.03:
        screen_pen += 0.35
    door_pen = cfg.door_gain * min(1.0, max(0.0, (feats.get("door_risk", 0.0) - 0.35) / 0.65))
    # a page edge is flat on the inside of the transition; a lighting gradient
    # keeps sloping. This separates a white page from a brighter wall patch.
    ramp_term = 1.0 - min(1.0, max(0.0, (feats.get("ramp_in_mean", 0.0) - 2.0) / 3.0))
    base *= (0.50 + 0.50 * ramp_term)
    # cross-detector corroboration: either the shipped strong-corner heuristic
    # found the same quad, or the boundary step is strong enough to stand alone.
    agree = feats.get("agree_shipped", 0.0)
    corr = min(1.0, max(0.0, (agree - 0.20) / 0.35))
    strong = min(1.0, max(0.0, (feats.get("step_min", 0.0) - 0.28) / 0.25))
    corroboration = max(corr, strong)
    base *= (0.30 + 0.70 * corroboration)
    grain = (min(1.0, max(0.0, (feats.get("inside_lf_std", 0.0) - 0.055) / 0.06))
             * min(1.0, max(0.0, (feats.get("hf_inside", 0.0) - 0.008) / 0.015)))
    # a large, blank region with a low-frequency lighting gradient is a wall or
    # table patch, not a page: pages are locally flat even when blank.
    wall = (min(1.0, max(0.0, (feats.get("inside_lf_std", 0.0) - 0.045) / 0.05))
            * min(1.0, max(0.0, (0.006 - feats.get("hf_inside", 0.0)) / 0.006))
            * min(1.0, max(0.0, (area - 0.50) / 0.25)))
    conf = base - 0.22 * grain - 0.30 * wall - screen_pen - door_pen
    return float(min(1.0, max(0.0, conf))), {
        "t_step": step_term, "t_cons": cons_term, "t_contrast": contrast_term,
        "t_area": area_term, "t_border": border_term, "t_inside": inside_term,
        "t_step_gate": step_gate, "t_ramp": ramp_term, "t_corr": corroboration,
        "p_grain": 0.22 * grain, "p_wall": 0.30 * wall,
        "p_screen": screen_pen, "p_door": door_pen,
    }


def _refine_all(gray_hi: np.ndarray, quad: Quad, work_w: int, cfg: ConfigR2):
    hh, ww = gray_hi.shape
    f = ww / float(work_w)
    diag_hi = math.hypot(ww, hh)
    q_hi = [(x * f, y * f) for x, y in quad]
    wide, _ = refine_quad_step(gray_hi, q_hi, diag_hi, cfg, pass_wide=True)
    narrow, _ = refine_quad_step(gray_hi, wide, diag_hi, cfg, pass_wide=False)
    back = [(x / f, y / f) for x, y in narrow]
    return normalise_quad(back), diag_hi, f


def detect_r2_candidates(rgb_small: np.ndarray, gray_hi: np.ndarray,
                         cfg: ConfigR2) -> List[Detection]:
    """All candidate detections (mask components + shipped), for tuning/debug."""
    h, w = rgb_small.shape[:2]
    frame_area = float(w * h)
    work_l = (0.299 * rgb_small[:, :, 0] + 0.587 * rgb_small[:, :, 1]
              + 0.114 * rgb_small[:, :, 2])
    score, lightness, chroma, base_info = paper_map(rgb_small, cfg)
    hf = hf_map(work_l)
    gn, barrier_radius = barrier_gradient(lightness, cfg, w)
    base_info["barrier_radius"] = float(barrier_radius)

    info: Dict[str, float] = dict(base_info)
    raw_candidates: List[dict] = []
    if cfg.use_mask:
        kept_masks: List[np.ndarray] = []
        for seed_pct, delta in zip(cfg.seed_percentiles, cfg.hysteresis_deltas):
            labels, stats, seeds, minfo = hysteresis_components(
                score, lightness, cfg, w, seed_percentile=seed_pct, delta=delta, gn=gn)
            info.update(minfo)
            cands = component_candidates(labels, stats, seeds, frame_area, cfg,
                                         limit=1)
            for cand in cands:
                comp = labels == cand["label"]
                duplicate = False
                for old in kept_masks:
                    inter = float((comp & old).sum())
                    union = float((comp | old).sum())
                    if union > 0 and inter / union > 0.85:
                        duplicate = True
                        break
                if duplicate:
                    continue
                kept_masks.append(comp)
                cand["comp_mask"] = comp
                cand["seed_pct"] = float(seed_pct)
                cand["delta"] = float(delta)
                raw_candidates.append(cand)
    if cfg.use_shipped:
        shipped = deskew_port.detect_quad_shipped(rgb_small)
        if shipped is not None and quad_sane(shipped, w, h):
            raw_candidates.append({"source": "shipped", "quad": shipped,
                                   "area": quad_area(shipped),
                                   "area_frac": quad_area(shipped) / frame_area, "hug": 0.0})
    info["n_candidates"] = float(len(raw_candidates))
    shipped_norm = next((normalise_quad(c["quad"]) for c in raw_candidates
                         if c.get("source") == "shipped"), None)
    shipped_mask = polygon_mask(shipped_norm, w, h) if shipped_norm is not None else None
    out: List[Detection] = []
    for cand in raw_candidates:
        if cand.get("source") == "shipped":
            start_quad = normalise_quad(cand["quad"])
        else:
            comp = cand["comp_mask"]
            hull = mask_hull(comp)
            if len(hull) < 4:
                continue
            hull_area = quad_area([(float(x), float(y)) for x, y in hull])
            if hull_area <= 1e-6:
                continue
            extent = max(float(hull[:, 0].max() - hull[:, 0].min()),
                         float(hull[:, 1].max() - hull[:, 1].min()))
            lines = side_lines_from_hull(hull, band=0.06 * extent)
            start_quad = quad_from_lines(lines)
            if not quad_sane(start_quad, w, h):
                start_quad = min_area_rect_quad(hull)
            if not quad_sane(start_quad, w, h):
                continue
        refined, diag_hi, f = _refine_all(gray_hi, start_quad, w, cfg)
        if not quad_sane(refined, w, h, slack=0.30):
            refined = start_quad
        q_hi = [(x * f, y * f) for x, y in refined]
        feats: Dict[str, float] = {}
        feats.update(region_features(work_l, chroma, score, hf, gn, refined, cfg))
        feats.update(measure_quad_steps(gray_hi, q_hi, diag_hi, cfg))
        feats.update(screen_features(work_l, rgb_small, hf, refined, cfg))
        feats.update(door_features(feats))
        feats["border_hug"] = float(cand.get("hug", 0.0))
        feats["candidate_source"] = 1.0 if cand.get("source") == "shipped" else 0.0
        feats["seed_frac"] = float(cand.get("seed_frac", 0.0))
        feats["seed_pct"] = float(cand.get("seed_pct", 0.0))
        feats["seed_delta"] = float(cand.get("delta", 0.0))
        if shipped_mask is not None:
            qm = polygon_mask(refined, w, h)
            inter = float((qm & shipped_mask).sum())
            union = float((qm | shipped_mask).sum())
            feats["agree_shipped"] = inter / union if union > 0 else 0.0
        else:
            feats["agree_shipped"] = 0.0
        # agreement with the paper mask: how much of the candidate sits on the
        # high-score (paper) part of the frame, and how much of the component the
        # candidate covers. A quad that jumps onto the floor explains little.
        inside = polygon_mask(refined, w, h)
        comp = cand.get("comp_mask")
        if comp is not None:
            inter = float((inside & comp).sum())
            feats["mask_iou"] = inter / float(max(1, (inside | comp).sum()))
        else:
            feats["mask_iou"] = 0.0
        conf, terms = _confidence(feats, cfg)
        feats.update(terms)
        det = Detection(confidence=conf, features=feats, work_quad=list(refined),
                        work_size=(w, h))
        out.append(det)
    for det in out:
        det.features.update({k: v for k, v in info.items() if k not in det.features})
    return out


def detect_r2(rgb_small: np.ndarray, gray_hi: np.ndarray, cfg: ConfigR2) -> Detection:
    h, w = rgb_small.shape[:2]
    candidates = detect_r2_candidates(rgb_small, gray_hi, cfg)
    if not candidates:
        return Detection(confidence=0.0, work_size=(w, h))
    best = max(candidates, key=lambda d: d.confidence)
    return best


def detect_path_r2(path: str, cfg: Optional[ConfigR2] = None) -> Detection:
    cfg = cfg or ConfigR2()
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
    det = detect_r2(rgb, gray_hi, cfg)
    if det.work_quad is not None:
        sx_x = src_w / float(work_w)
        sx_y = src_h / float(work_h)
        det.quad = [(x * sx_x, y * sx_y) for x, y in det.work_quad]
        det.source_size = (src_w, src_h)
    det.ms = (time.perf_counter() - start) * 1000.0
    return det
