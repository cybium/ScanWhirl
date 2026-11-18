"""T20 evaluation harness for the Scanner X prototype.

Runs the prototype over the labelled clips, writes predictions in the scorer's format,
splits DEV/TEST by the mandatory hold-out rule, and prints the metrics that
``tools/scanner/score.py`` defines (it is imported and used unchanged as the judge).

HOLD-OUT RULE: TEST clips are 03, 07, 13, 15, 18, 23, 27, 29, EXTRA_04. They are never
used for tuning. The TEST column is produced only with ``--test``, and every run is
appended to ``scannerx_test_runs.log`` so the 3-evaluation budget stays auditable.

Usage (local, data outside git):
    python tools/scanner/proto/evaluate.py --version v1 --clips dev        # DEV only
    python tools/scanner/proto/evaluate.py --version v2 --test             # DEV + TEST
    python tools/scanner/proto/evaluate.py --version v2 --sweep            # threshold sweep
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import math
import os
import sys
import time
from typing import Dict, List, Optional, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)

import scannerx as sx  # noqa: E402

DATA_ROOT = r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\recordings"
FRAMES = os.path.join(DATA_ROOT, "frames")
LABELS = os.path.join(DATA_ROOT, "labels")
AUDIT = r"C:\Users\Cybium\Desktop\ScanWhirl-Workflow-Audit-2026-11-18\automation-run-2026-11-19"
LOG = os.path.join(AUDIT, "scannerx_test_runs.log")

LABEL_FILES = ("labels_A.csv", "labels_B.csv", "labels_C.csv")
TEST_CLIPS = {"03", "07", "13", "15", "18", "23", "27", "29", "EXTRA_04_Handwritten_Notebook"}


def load_score_module():
    spec = importlib.util.spec_from_file_location("score", os.path.join(REPO, "tools", "scanner", "score.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules["score"] = module
    spec.loader.exec_module(module)
    return module


def clip_sort_key(clip: str):
    head = clip.split("_")[0]
    try:
        return (0, int(head), clip)
    except ValueError:
        return (1, 0, clip)


def merge_labels() -> Dict[Tuple[str, int], dict]:
    """Merge the labeler CSVs, first file wins (same rule as score.py). Re-read every run."""
    rows: Dict[Tuple[str, int], dict] = {}
    for name in LABEL_FILES:
        path = os.path.join(LABELS, name)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                key = (row["clip"].strip(), int(row["frame"]))
                if key not in rows:
                    rows[key] = row
    return rows


def predict(clips: List[str], cfg: sx.Config, features_out: Optional[str] = None) -> List[dict]:
    rows = []
    feats = []
    for clip in clips:
        folder = os.path.join(FRAMES, clip)
        if not os.path.isdir(folder):
            continue
        names = sorted((n for n in os.listdir(folder) if n.lower().endswith(".jpg")),
                       key=lambda n: int(os.path.splitext(n)[0]))
        for name in names:
            det = sx.detect_path(os.path.join(folder, name), cfg)
            frame = int(os.path.splitext(name)[0])
            accepted = det.quad is not None and det.confidence >= cfg.accept
            row = {"clip": clip, "frame": frame, "has_quad": 1 if accepted else 0, "ms": round(det.ms, 2)}
            if accepted:
                for i, (x, y) in enumerate(det.quad):
                    row[f"x{i + 1}"] = int(round(x))
                    row[f"y{i + 1}"] = int(round(y))
            rows.append(row)
            f = dict(det.features)
            f.update({"clip": clip, "frame": frame, "confidence": round(det.confidence, 4),
                      "has_quad_raw": 1 if det.quad is not None else 0})
            if det.quad is not None:
                for i, (x, y) in enumerate(det.quad):
                    f[f"x{i + 1}"] = round(x, 1)
                    f[f"y{i + 1}"] = round(y, 1)
            feats.append(f)
    if features_out:
        keys = ["clip", "frame", "confidence", "has_quad_raw"]
        for f in feats:
            for k in f:
                if k not in keys:
                    keys.append(k)
        with open(features_out, "w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=keys)
            writer.writeheader()
            for f in feats:
                writer.writerow(f)
    return rows


def write_pred(path: str, rows: List[dict]) -> None:
    fields = ["clip", "frame", "has_quad", "x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4", "ms"]
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for r in rows:
            writer.writerow({k: r.get(k, "") for k in fields})


def write_labels(path: str, labels: Dict[Tuple[str, int], dict], clips: set) -> None:
    cols = ["clip", "frame", "file", "width", "height", "has_doc", "category",
            "x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4", "labeler", "confidence", "notes"]
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=cols)
        writer.writeheader()
        for key in sorted(labels, key=lambda k: (clip_sort_key(k[0]), k[1])):
            if key[0] in clips:
                writer.writerow({c: labels[key].get(c, "") for c in cols})


def quad_of(row: dict):
    try:
        vals = [float(row[k]) for k in ("x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4")]
    except (KeyError, TypeError, ValueError):
        return None
    return [(vals[i], vals[i + 1]) for i in range(0, 8, 2)]


def metrics(score_mod, labels, pred_rows, clips, iou_threshold=0.8):
    """Same definitions as score.py, computed here so a threshold sweep is one pass."""
    by_key = {(r["clip"], int(r["frame"])): r for r in pred_rows}
    doc1 = doc0 = 0
    recalled = 0
    fa = 0
    fa_2630 = 0
    corners: List[float] = []
    per_clip: Dict[str, dict] = {}
    for (clip, frame), lab in labels.items():
        if clip not in clips:
            continue
        st = per_clip.setdefault(clip, {"doc1": 0, "recal": 0, "doc0": 0, "fa": 0, "corners": []})
        pred = by_key.get((clip, frame))
        pred_quad = None
        if pred is not None and str(pred.get("has_quad")) in ("1", "1.0", "true", "True"):
            pred_quad = quad_of(pred)
        has_doc = int(lab["has_doc"])
        if has_doc == 2:
            continue
        if has_doc == 0:
            doc0 += 1
            st["doc0"] += 1
            if pred_quad is not None:
                fa += 1
                st["fa"] += 1
                if clip in ("26", "30"):
                    fa_2630 += 1
            continue
        truth = quad_of(lab)
        if truth is None:
            continue
        doc1 += 1
        st["doc1"] += 1
        if pred_quad is None:
            continue
        iou = score_mod.polygon_iou(pred_quad, truth, int(lab["width"]), int(lab["height"]))
        if iou >= iou_threshold:
            recalled += 1
            st["recal"] += 1
        if iou > 0:
            err, _ = score_mod.corner_error(pred_quad, truth, int(lab["width"]), int(lab["height"]))
            if not math.isnan(err):
                corners.append(err)
                st["corners"].append(err)
    corners.sort()

    def pct(values, q):
        if not values:
            return float("nan")
        return values[min(len(values) - 1, int(round(q / 100.0 * (len(values) - 1))))]

    worst = None
    for clip, st in per_clip.items():
        if st["doc1"] >= 4:
            r = st["recal"] / st["doc1"]
            if worst is None or r < worst[1]:
                worst = (clip, r)
    return {
        "frames": doc0 + doc1,
        "doc1": doc1,
        "doc0": doc0,
        "recall": recalled / doc1 if doc1 else float("nan"),
        "recalled": recalled,
        "false_accept": fa / doc0 if doc0 else float("nan"),
        "fa_count": fa,
        "fa_2630": fa_2630,
        "corner_median": pct(corners, 50),
        "corner_p90": pct(corners, 90),
        "corner_n": len(corners),
        "worst_clip": worst,
        "per_clip": per_clip,
    }


def sweep(score_mod, labels, features_path, clips):
    """Threshold sweep on DEV only, using the feature dump (which holds every quad)."""
    rows = []
    with open(features_path, encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            row["confidence"] = float(row["confidence"])
            row["frame"] = int(row["frame"])
            rows.append(row)
    out = []
    for t in [i / 20.0 for i in range(0, 21)]:
        pred = []
        for row in rows:
            r = dict(row)
            r["has_quad"] = 1 if (row["has_quad_raw"] == 1 and row["confidence"] >= t) else 0
            pred.append(r)
        m = metrics(score_mod, labels, pred, clips)
        out.append((t, m))
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="v1")
    parser.add_argument("--clips", default="all", help="all|dev|comma list")
    parser.add_argument("--accept", type=float, default=sx.Config.accept)
    parser.add_argument("--test", action="store_true", help="also score the TEST hold-out (budget 3)")
    parser.add_argument("--sweep", action="store_true", help="threshold sweep on DEV")
    parser.add_argument("--features", default="", help="write the DEV feature dump here")
    parser.add_argument("--cfg", action="append", default=[], help="Config override key=value")
    parser.add_argument("--pred-csv", action="store_true", help="write the predictions CSV into the labels folder")
    args = parser.parse_args(argv)

    overrides = {}
    for item in args.cfg:
        key, _, value = item.partition("=")
        current = getattr(sx.Config, key, None)
        if isinstance(current, bool):
            overrides[key] = value.lower() in ("1", "true", "yes")
        elif isinstance(current, int):
            overrides[key] = int(value)
        else:
            overrides[key] = float(value)
    cfg = sx.Config(accept=args.accept, **overrides)
    print("config:", {k: v for k, v in cfg.__dict__.items() if k not in ("refine_samples",)})

    score_mod = load_score_module()
    labels = merge_labels()
    labelled_clips = {clip for clip, _ in labels}
    dev_clips = labelled_clips - TEST_CLIPS
    test_clips = labelled_clips & TEST_CLIPS
    print(f"labels: {len(labels)} frames, {len(labelled_clips)} clips "
          f"(dev {len(dev_clips)}, test {len(test_clips)})")

    if args.clips == "all":
        run_clips = sorted(labelled_clips, key=clip_sort_key)
    elif args.clips == "dev":
        run_clips = sorted(dev_clips, key=clip_sort_key)
    else:
        run_clips = [c.strip() for c in args.clips.split(",") if c.strip()]

    t0 = time.perf_counter()
    feats_path = args.features or os.path.join(AUDIT, f"scannerx_features_{args.version}.csv")
    rows = predict(run_clips, cfg, feats_path)
    elapsed = time.perf_counter() - t0
    times = sorted(r["ms"] for r in rows)
    print(f"predicted {len(rows)} frames in {elapsed:.1f}s "
          f"(median {times[len(times) // 2]:.1f} ms, p90 {times[int(len(times) * 0.9)]:.1f} ms, PC CPython)")

    pred_path = os.path.join(LABELS, f"pred_scannerx_{args.version}.csv")
    write_pred(pred_path, rows)
    print("wrote", pred_path)

    run_dev = [c for c in run_clips if c in dev_clips]
    dev_rows = [r for r in rows if r["clip"] in dev_clips]
    dev_set = set(run_dev) or dev_clips
    dev_pred_path = os.path.join(AUDIT, f"pred_scannerx_{args.version}_dev.csv")
    write_pred(dev_pred_path, dev_rows)
    dev_labels_path = os.path.join(AUDIT, f"labels_dev_{args.version}.csv")
    write_labels(dev_labels_path, labels, dev_clips)
    dev_m = metrics(score_mod, labels, dev_rows, dev_set)
    print(f"DEV  recall {dev_m['recall']:.1%} ({dev_m['recalled']}/{dev_m['doc1']})  "
          f"FA {dev_m['false_accept']:.1%} ({dev_m['fa_count']}/{dev_m['doc0']})  "
          f"FA(26,30) {dev_m['fa_2630']}  corner med {dev_m['corner_median']:.1%} "
          f"p90 {dev_m['corner_p90']:.1%}  worst clip {dev_m['worst_clip']}")

    if args.sweep:
        print("\nthreshold sweep (DEV):")
        print("  t     recall   FA     FA26/30  cmed    cp90   worst")
        for t, m in sweep(score_mod, labels, feats_path, dev_clips):
            print(f"  {t:4.2f}  {m['recall']*100:5.1f}%  {m['false_accept']*100:5.1f}%  "
                  f"{m['fa_2630']:5d}    {m['corner_median']*100:4.1f}%  {m['corner_p90']*100:4.1f}%  {m['worst_clip']}")

    if args.test:
        test_rows = [r for r in rows if r["clip"] in test_clips]
        test_pred_path = os.path.join(AUDIT, f"pred_scannerx_{args.version}_test.csv")
        write_pred(test_pred_path, test_rows)
        test_labels_path = os.path.join(AUDIT, f"labels_test_{args.version}.csv")
        write_labels(test_labels_path, labels, test_clips)
        test_m = metrics(score_mod, labels, test_rows, test_clips)
        print(f"TEST recall {test_m['recall']:.1%} ({test_m['recalled']}/{test_m['doc1']})  "
              f"FA {test_m['false_accept']:.1%} ({test_m['fa_count']}/{test_m['doc0']})  "
              f"FA(26,30) {test_m['fa_2630']}  corner med {test_m['corner_median']:.1%} "
              f"p90 {test_m['corner_p90']:.1%}  worst clip {test_m['worst_clip']}")
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        with open(LOG, "a", encoding="utf-8") as handle:
            handle.write(f"{stamp}\t{args.version}\taccept={args.accept}\t"
                         f"recall={test_m['recall']:.4f}\tfa={test_m['false_accept']:.4f}\t"
                         f"cmed={test_m['corner_median']:.4f}\n")
        print("logged TEST evaluation to", LOG)
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
