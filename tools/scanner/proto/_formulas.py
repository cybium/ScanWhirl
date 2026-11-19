"""Try simple confidence formulas against the saved DEV feature dump (no re-detection).

Each formula is a weighted sum of clipped terms; we report recall / false accept on
DEV for the threshold that keeps false accepts <= 5 %.
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import evaluate as ev

score = ev.load_score_module()
labels = ev.merge_labels()
dev_clips = {c for c, _ in labels} - ev.TEST_CLIPS
feats = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ev.AUDIT, "scannerx_features_v2.csv")

rows = []
with open(feats, encoding="utf-8") as handle:
    for row in csv.DictReader(handle):
        row["frame"] = int(row["frame"])
        row["raw"] = int(float(row["has_quad_raw"] or 0))
        rows.append(row)


def clip(x, lo, hi):
    return max(0.0, min(1.0, (x - lo) / (hi - lo)))


def terms(r):
    return {
        "contrast": clip(float(r["contrast"] or 0), 0.0, 0.30),
        "edge": clip(float(r["edge_strength"] or 0), 0.004, 0.05),
        "hug": 1.0 - clip(float(r["border_hug"] or 0), 0.0, 0.35),
        "area": clip(float(r["area_frac"] or 0), 0.10, 0.60) * (1.0 - clip(float(r["area_frac"] or 0), 0.80, 0.97)),
        "rect": 1.0 - clip(abs(float(r["rect"] or 0) - 1.0), 0.0, 0.25),
        "angle": 1.0 - clip(float(r["max_cos"] or 0), 0.0, 0.6),
        "smin": clip(float(r["support_min"] or 0), 0.0, 0.5),
        "inside": clip(float(r["inside_score"] or 0), 0.15, 0.75),
    }


FORMULAS = {
    "A": {"contrast": 0.45, "edge": 0.25, "hug": 0.15, "area": 0.15},
    "B": {"contrast": 0.40, "edge": 0.20, "hug": 0.15, "area": 0.10, "rect": 0.15},
    "C": {"contrast": 0.35, "edge": 0.25, "hug": 0.15, "area": 0.10, "inside": 0.15},
    "D": {"contrast": 0.50, "edge": 0.20, "hug": 0.10, "area": 0.10, "angle": 0.10},
    "E": {"contrast": 0.35, "edge": 0.20, "hug": 0.10, "area": 0.10, "smin": 0.10, "rect": 0.15},
}

for name, weights in FORMULAS.items():
    for r in rows:
        t = terms(r)
        r["c"] = sum(w * t[k] for k, w in weights.items())
    best = None
    for i in range(0, 51):
        thr = i / 50.0
        pred = []
        for r in rows:
            rr = dict(r)
            rr["has_quad"] = 1 if (r["raw"] == 1 and r["c"] >= thr) else 0
            pred.append(rr)
        m = ev.metrics(score, labels, pred, dev_clips)
        if m["false_accept"] <= 0.05 and (best is None or m["recall"] > best[1]["recall"]):
            best = (thr, m)
    if best is None:
        print(name, "no threshold meets FA<=5%")
        continue
    thr, m = best
    print(f"{name}  thr={thr:.2f}  recall={m['recall']*100:5.1f}% ({m['recalled']}/{m['doc1']})  "
          f"FA={m['false_accept']*100:4.1f}%  FA26/30={m['fa_2630']}  "
          f"cmed={m['corner_median']*100:4.1f}%  cp90={m['corner_p90']*100:4.1f}%  worst={m['worst_clip']}")
