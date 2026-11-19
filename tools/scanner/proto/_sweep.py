"""Threshold sweep over a saved feature dump (DEV only) -- no re-detection."""
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
        row["confidence"] = float(row["confidence"])
        row["frame"] = int(row["frame"])
        row["raw"] = int(float(row["has_quad_raw"] or 0))
        rows.append(row)

print("  t     recall  matched  FA     FA26/30  cmed   cp90")
best = None
for i in range(0, 41):
    t = i / 40.0
    pred = []
    for r in rows:
        rr = dict(r)
        rr["has_quad"] = 1 if (r["raw"] == 1 and r["confidence"] >= t) else 0
        pred.append(rr)
    m = ev.metrics(score, labels, pred, dev_clips)
    print(f"  {t:4.2f}  {m['recall']*100:5.1f}%  {m['recalled']:3d}/{m['doc1']:<3d}  "
          f"{m['false_accept']*100:5.1f}%  {m['fa_2630']:5d}    {m['corner_median']*100:4.1f}%  {m['corner_p90']*100:4.1f}%")
    if best is None or (m["recall"] - m["false_accept"]) > (best[1]["recall"] - best[1]["false_accept"]):
        best = (t, m)
print("best by recall-FA:", best[0], f"recall={best[1]['recall']:.3f} fa={best[1]['false_accept']:.3f}")

# feature separation summary at t=0.55
t = 0.55
for name in ("support_min", "support_mean", "contrast", "rect", "area_frac", "border_hug",
             "inside_std", "uniformity", "edge_strength", "max_cos"):
    vals = {}
    for r in rows:
        key = (r["clip"], r["frame"])
        lab = labels.get(key)
        if lab is None or int(lab["has_doc"]) == 2:
            continue
        group = "doc0" if int(lab["has_doc"]) == 0 else "doc1"
        if r["raw"] != 1:
            continue
        vals.setdefault(group, []).append(float(r[name] or 0))
    out = []
    for group in ("doc1", "doc0"):
        v = sorted(vals.get(group, []))
        if v:
            out.append(f"{group} p10={v[len(v)//10]:.3f} p50={v[len(v)//2]:.3f} p90={v[len(v)*9//10]:.3f}")
    print(f"{name:14s} " + " | ".join(out))
