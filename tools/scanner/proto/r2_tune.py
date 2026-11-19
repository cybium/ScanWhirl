"""DEV-only tuning helper for Scanner X round 2.

Reads the merged private labels and the round-2 feature dump, computes per-frame
correctness with the unchanged scorer, and prints feature distributions. No TEST
clip is ever loaded (the feature dump is DEV-only).

Usage:
    python tools/scanner/proto/r2_tune.py --features <csv>
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import math
import os
import sys
from typing import Dict, List, Tuple

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
DATA = r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\recordings"
AUDIT = r"C:\Users\Cybium\Desktop\ScanWhirl-Workflow-Audit-2026-11-18\automation-run-2026-11-19"
sys.path.insert(0, HERE)

TEST_CLIPS = {"03", "07", "13", "15", "18", "23", "27", "29", "EXTRA_04_Handwritten_Notebook"}


def load_score_module():
    spec = importlib.util.spec_from_file_location("score", os.path.join(REPO, "tools", "scanner", "score.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules["score"] = module
    spec.loader.exec_module(module)
    return module


def merge_labels():
    rows = {}
    for name in ("labels_A.csv", "labels_B.csv", "labels_C.csv"):
        path = os.path.join(DATA, "labels", name)
        with open(path, encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                key = (row["clip"].strip(), int(row["frame"]))
                rows.setdefault(key, row)
    return rows


def quad_of(row) -> List[Tuple[float, float]]:
    try:
        v = [float(row[k]) for k in ("x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4")]
    except (KeyError, TypeError, ValueError):
        return []
    return [(v[i], v[i + 1]) for i in range(0, 8, 2)]


def load(features_path: str):
    score = load_score_module()
    labels = merge_labels()
    rows = []
    with open(features_path, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            row["frame"] = int(row["frame"])
            row["confidence"] = float(row["confidence"])
            for key, value in list(row.items()):
                if key not in ("clip", "frame"):
                    try:
                        row[key] = float(value)
                    except ValueError:
                        pass
            key = (row["clip"], row["frame"])
            lab = labels.get(key)
            if lab is None or lab["has_doc"] == "2":
                continue
            truth = quad_of(lab)
            if int(lab["has_doc"]) == 1 and not truth:
                continue  # has_doc=1 with fewer than 3 labelled corners: not scored
            row["has_doc"] = int(lab["has_doc"])
            row["category"] = lab["category"]
            row["correct"] = 0
            row["iou"] = 0.0
            if row["has_doc"] == 1 and str(row.get("has_quad_raw")) in ("1", "1.0"):
                pred = quad_of(row)
                if pred:
                    iou = score.polygon_iou(pred, truth, int(lab["width"]), int(lab["height"]))
                    row["iou"] = iou
                    row["correct"] = 1 if iou >= 0.8 else 0
            rows.append(row)
    return rows


def summarize(rows, thr: float, key: str = "confidence"):
    pos = [r for r in rows if r["has_doc"] == 1]
    neg = [r for r in rows if r["has_doc"] == 0]
    rec = [r for r in pos if r[key] >= thr and str(r.get("has_quad_raw")) in ("1", "1.0")]
    fa = [r for r in neg if r[key] >= thr and str(r.get("has_quad_raw")) in ("1", "1.0")]
    recall = sum(r["correct"] for r in rec) / max(1, len(pos))
    fa_rate = len(fa) / max(1, len(neg))
    return {"recall": recall, "fa": fa_rate, "recalled": sum(r["correct"] for r in rec),
            "pos": len(pos), "neg": len(neg), "fa_n": len(fa),
            "fa_2630": sum(1 for r in fa if r["clip"] in ("26", "30"))}


def describe(rows):
    groups = {
        "correct": [r for r in rows if r["has_doc"] == 1 and r["correct"] == 1],
        "wrong": [r for r in rows if r["has_doc"] == 1 and str(r.get("has_quad_raw")) in ("1", "1.0") and r["correct"] == 0],
        "missed": [r for r in rows if r["has_doc"] == 1 and str(r.get("has_quad_raw")) not in ("1", "1.0")],
        "neg_quad": [r for r in rows if r["has_doc"] == 0 and str(r.get("has_quad_raw")) in ("1", "1.0")],
    }
    keys = ["confidence", "step_min", "step_mean", "cons_min", "step_min_raw", "contrast",
            "area_frac", "border_hug", "border_touch_n", "hf_inside", "inside_lf_std",
            "inside_chroma", "blue", "bezel", "keyboard", "screen_risk", "door_risk",
            "uniformity", "seed_frac", "candidate_source", "inside_score"]
    for name, group in groups.items():
        print(f"\n[{name}] n={len(group)}")
        for k in keys:
            vals = [r[k] for r in group if isinstance(r.get(k), float)]
            if not vals:
                continue
            arr = np.array(vals)
            print(f"  {k:16s} p10={np.percentile(arr,10):7.3f} p50={np.percentile(arr,50):7.3f} "
                  f"p90={np.percentile(arr,90):7.3f} mean={arr.mean():7.3f}")


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--features", default=os.path.join(AUDIT, "scannerx_features_r2.csv"))
    parser.add_argument("--describe", action="store_true")
    parser.add_argument("--thr", type=float, default=0.53)
    args = parser.parse_args(argv)
    rows = load(args.features)
    print(f"DEV rows {len(rows)}  (pos {sum(r['has_doc']==1 for r in rows)}, "
          f"neg {sum(r['has_doc']==0 for r in rows)})")
    if args.describe:
        describe(rows)
    print("\nthreshold scan (confidence):")
    for t in [i / 40 for i in range(0, 41)]:
        m = summarize(rows, t)
        print(f"  {t:4.3f}  recall {m['recall']*100:5.1f}% ({m['recalled']}/{m['pos']})  "
              f"FA {m['fa']*100:5.1f}% ({m['fa_n']}/{m['neg']})  FA2630 {m['fa_2630']}")
    print("\nper category at thr", args.thr)
    for cat in sorted(set(r["category"] for r in rows)):
        sub = [r for r in rows if r["category"] == cat]
        m = summarize(sub, args.thr)
        print(f"  {cat:16s} pos {m['pos']:3d} recall {m['recall']*100:5.1f}%  "
              f"neg {m['neg']:3d} FA {m['fa']*100:5.1f}%")
    print("\nper clip at thr", args.thr)
    for clip in sorted(set(r["clip"] for r in rows)):
        sub = [r for r in rows if r["clip"] == clip]
        m = summarize(sub, args.thr)
        print(f"  {clip:32s} pos {m['pos']:3d} rec {m['recalled']:3d} ({m['recall']*100:5.1f}%)  "
              f"neg {m['neg']:3d} FA {m['fa_n']:3d}")


if __name__ == "__main__":
    raise SystemExit(main())
