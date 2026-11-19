"""Leave-one-clip-out cross-validation for Scanner X round 2 (DEV only).

The round-1 failure was a DEV-tuned threshold that collapsed on TEST. This script
therefore does not report a DEV best point: it holds out one DEV clip at a time,
chooses the accept threshold on the other 24 clips (max recall subject to the
false-accept targets), applies it to the held-out clip, and reports the pooled
out-of-fold metrics. The mean threshold and the per-fold spread are printed so
instability is visible.

Usage:
    python tools/scanner/proto/r2_cv.py --features <dev feature csv> [--variant A]
"""

from __future__ import annotations

import argparse
import os
import sys
from typing import Dict, List, Tuple

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import r2_tune as tune  # noqa: E402

AUDIT = r"C:\Users\Cybium\Desktop\ScanWhirl-Workflow-Audit-2026-11-18\automation-run-2026-11-19"


def clip(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return min(hi, max(lo, x))


def score_row(r: dict, variant: str = "A") -> float:
    """Recompute confidence from the feature dump; mirrors scannerx_r2._confidence."""
    step = r.get("step_min", 0.0)
    cons = r.get("cons_min", 0.0)
    contrast = r.get("contrast", 0.0)
    area = r.get("area_frac", 0.0)
    hug = r.get("border_hug", 0.0)
    inside = r.get("inside_score", 0.0)
    step_term = clip((step - 0.08) / 0.45)
    cons_term = clip((cons - 0.25) / 0.55)
    contrast_term = clip((contrast - 0.01) / 0.22)
    area_term = clip((area - 0.06) / 0.35) * (1.0 - clip((area - 0.82) / 0.16))
    border_term = 1.0 - clip(hug / 0.55)
    inside_term = clip((inside - 0.18) / 0.55)
    base = (0.30 * step_term + 0.15 * cons_term + 0.25 * contrast_term
            + 0.10 * area_term + 0.10 * border_term + 0.10 * inside_term)
    base *= clip((step - 0.04) / 0.12)
    if variant in ("A", "D"):
        ramp = r.get("ramp_in_mean", 0.0)
        base *= 0.50 + 0.50 * (1.0 - clip((ramp - 2.0) / 3.0))
    if variant in ("A", "B", "D", "F"):
        agree = r.get("agree_shipped", 0.0)
        band = (0.20, 0.35) if variant == "F" else (0.10, 0.30)
        corr = clip((agree - band[0]) / band[1])
        strong = clip((step - 0.28) / 0.25)
        base *= 0.30 + 0.70 * max(corr, strong)
    if variant == "C":
        # corroboration replaces the ramp gate
        agree = r.get("agree_shipped", 0.0)
        corr = clip((agree - 0.10) / 0.30)
        strong = clip((step - 0.28) / 0.25)
        base *= 0.30 + 0.70 * max(corr, strong)
    if variant == "E":
        # softer ramp, harder screen/door
        ramp = r.get("ramp_in_mean", 0.0)
        base *= 0.7 + 0.3 * (1.0 - clip((ramp - 2.0) / 3.0))
        agree = r.get("agree_shipped", 0.0)
        corr = clip((agree - 0.10) / 0.30)
        strong = clip((step - 0.28) / 0.25)
        base *= 0.4 + 0.6 * max(corr, strong)
    grain = (clip((r.get("inside_lf_std", 0.0) - 0.055) / 0.06)
             * clip((r.get("hf_inside", 0.0) - 0.008) / 0.015))
    wall = (clip((r.get("inside_lf_std", 0.0) - 0.045) / 0.05)
            * clip((0.006 - r.get("hf_inside", 0.0)) / 0.006)
            * clip((r.get("area_frac", 0.0) - 0.50) / 0.25))
    screen_pen = 0.90 * clip((r.get("screen_risk", 0.0) - 0.40) / 0.45)
    if r.get("screen_risk", 0.0) >= 0.58 and r.get("blue", 0.0) > 0.03:
        screen_pen += 0.35
    door_pen = 0.45 * clip((r.get("door_risk", 0.0) - 0.35) / 0.65)
    return clip(base - 0.22 * grain - 0.30 * wall - screen_pen - door_pen)


def metrics(rows: List[dict], thr: float, variant: str) -> Dict[str, float]:
    pos = [r for r in rows if r["has_doc"] == 1]
    neg = [r for r in rows if r["has_doc"] == 0]
    got = [r for r in pos if score_row(r, variant) >= thr]
    recalled = sum(r["correct"] for r in got)
    fa = [r for r in neg if score_row(r, variant) >= thr]
    return {
        "recall": recalled / max(1, len(pos)),
        "fa": len(fa) / max(1, len(neg)),
        "recalled": recalled, "pos": len(pos),
        "fa_n": len(fa), "neg": len(neg),
        "fa_2630": sum(1 for r in fa if r["clip"] in ("26", "30")),
    }


def choose_threshold(rows: List[dict], variant: str,
                     fa_cap: float = 0.05, forbid_2630: bool = True) -> Tuple[float, Dict[str, float]]:
    best = None
    for i in range(30, 81):
        t = i / 100.0
        m = metrics(rows, t, variant)
        if m["fa"] > fa_cap:
            continue
        if forbid_2630 and m["fa_2630"] > 0:
            continue
        score = (m["recall"], -m["fa"])
        if best is None or score > best[0]:
            best = (score, t, m)
    if best is None:
        return 1.01, metrics(rows, 1.01, variant)
    return best[1], best[2]


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--features", default=os.path.join(AUDIT, "scannerx_features_r2.csv"))
    parser.add_argument("--variant", default="A", choices=list("ABCDEF"))
    parser.add_argument("--fa-cap", type=float, default=0.05)
    args = parser.parse_args(argv)
    rows = tune.load(args.features)
    clips = sorted(set(r["clip"] for r in rows))
    print(f"DEV {len(rows)} scored frames, {len(clips)} clips; variant {args.variant}")

    thresholds = []
    pooled = []
    per_fold = []
    for clip in clips:
        train = [r for r in rows if r["clip"] != clip]
        held = [r for r in rows if r["clip"] == clip]
        t, train_m = choose_threshold(train, args.variant, args.fa_cap)
        thresholds.append(t)
        hm = metrics(held, t, args.variant)
        pooled.extend([(r, t) for r in held])
        per_fold.append((clip, t, hm))
    # pooled out-of-fold predictions, each with its own fold threshold
    pos = [r for r, t in pooled if r["has_doc"] == 1]
    neg = [r for r, t in pooled if r["has_doc"] == 0]
    got = [r for r, t in pooled if r["has_doc"] == 1 and score_row(r, args.variant) >= t]
    fa = [r for r, t in pooled if r["has_doc"] == 0 and score_row(r, args.variant) >= t]
    recall = sum(r["correct"] for r in got) / max(1, len(pos))
    fa_rate = len(fa) / max(1, len(neg))
    print("\nout-of-fold (pooled, each clip judged by the threshold chosen without it):")
    print(f"  recall {recall*100:.1f}% ({sum(r['correct'] for r in got)}/{len(pos)})  "
          f"FA {fa_rate*100:.1f}% ({len(fa)}/{len(neg)})  "
          f"FA(26,30) {sum(1 for r in fa if r['clip'] in ('26','30'))}")
    print(f"  fold thresholds: mean {np.mean(thresholds):.3f} median {np.median(thresholds):.3f} "
          f"min {min(thresholds):.2f} max {max(thresholds):.2f}")
    print("\nper-fold:")
    for clip, t, m in per_fold:
        print(f"  {clip:32s} thr={t:4.2f} pos {m['pos']:3d} rec {m['recalled']:3d} "
              f"({m['recall']*100:5.1f}%)  neg {m['neg']:3d} FA {m['fa_n']:3d}")
    # full-DEV reference at the median CV threshold
    t_med = float(np.median(thresholds))
    full = metrics(rows, t_med, args.variant)
    print(f"\nfull DEV at CV median threshold {t_med:.2f}: recall {full['recall']*100:.1f}% "
          f"FA {full['fa']*100:.1f}% FA(26,30) {full['fa_2630']} "
          f"(cv mean {recall*100:.1f}% / {fa_rate*100:.1f}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
