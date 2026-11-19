"""Evaluate the trained Scanner X model against the shipped detector and Scanner X r2.

The judge is ``tools/scanner/score.py``, imported/executed **unchanged**; this script only
feeds it predictions in its own CSV format, so every number in the report is the number
the project's agreed instrument produces.

Rules enforced here (lane contract):

* VAL (clips 05, 10, 17, 22, 28) may be used freely for selection.
* TEST (clips 03, 07, 13, 15, 18, 23, 27, 29, EXTRA_04) plus the 9 third-party web stills
  is a **budgeted** hold-out: at most two TEST runs ever, each appended to
  ``runs/test_runs.log`` with the model SHA256. ``--test`` is required to spend one.
* The same frames are given to all three detectors. The recorded clips are scored in the
  stored 512-long-side PNG pixels (what the labels use); the web stills in their stored
  JPEG pixels. Both are PC CPython timings -- no Android/phone number is produced here.

Usage:
    python evaluate_ml.py --run-id ml003_v1 --split val
    python evaluate_ml.py --run-id ml003_v1 --split test --test   # spends one TEST run
    python evaluate_ml.py --run-id ml003_v1 --split web  --test   # same budget
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import data_ml
import infer
import postprocess

SPLIT_CLIPS = {"val": ["05", "10", "17", "22", "28"], "test": None}
R2_ACCEPT = 0.55
PROTO = Path(__file__).resolve().parent.parent / "proto"
TEST_LOG = data_ml.RUNS_DIR / "test_runs.log"


@dataclass
class Frame:
    clip: str
    frame: int
    path: str
    width: int
    height: int
    has_doc: int
    category: str
    confidence: str
    labeler: str
    quad: Optional[list[tuple[float, float]]]


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def load_scorer(repo: Path):
    import importlib.util

    if "score" in sys.modules:
        return sys.modules["score"]
    spec = importlib.util.spec_from_file_location("score", repo / "tools" / "scanner" / "score.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules["score"] = module
    spec.loader.exec_module(module)
    return module


# --------------------------------------------------------------------------- frames
def manifest_frames(split: str) -> list[Frame]:
    frames = []
    for row in data_ml.load_manifest(split=split):
        path = data_ml.OWN_DIR / row.image
        with Image.open(path) as img:
            width, height = img.size
        frames.append(Frame(clip=row.clip, frame=row.frame, path=str(path), width=width,
                            height=height, has_doc=row.has_doc, category=row.category,
                            confidence=row.confidence, labeler="manifest", quad=row.quad()))
    frames.sort(key=lambda f: (f.clip, f.frame))
    return frames


def web_frames() -> list[Frame]:
    frames = []
    with open(data_ml.WEB_LABELS, encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            name = os.path.basename(row["file"])
            path = data_ml.STILLS_DIR / name
            quad = None
            try:
                values = [float(row[k]) for k in ("x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4")]
                quad = [(values[i], values[i + 1]) for i in range(0, 8, 2)]
            except (KeyError, ValueError):
                quad = None
            frames.append(Frame(clip=row["clip"].strip(), frame=int(row["frame"]), path=str(path),
                                width=int(float(row["width"])), height=int(float(row["height"])),
                                has_doc=int(row["has_doc"]), category=row.get("category", "WebStill"),
                                confidence=(row.get("confidence") or "").strip(),
                                labeler=row.get("labeler", ""), quad=quad if int(row["has_doc"]) == 1 else None))
    frames.sort(key=lambda f: f.frame)
    return frames


def write_labels(path: Path, frames: list[Frame]) -> None:
    columns = ["clip", "frame", "file", "width", "height", "has_doc", "category",
               "x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4", "labeler", "confidence", "notes"]
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for frame in frames:
            row = {"clip": frame.clip, "frame": frame.frame, "file": os.path.basename(frame.path),
                   "width": frame.width, "height": frame.height, "has_doc": frame.has_doc,
                   "category": frame.category, "labeler": frame.labeler,
                   "confidence": frame.confidence, "notes": ""}
            if frame.has_doc == 1 and frame.quad:
                for i, (x, y) in enumerate(frame.quad):
                    row[f"x{i + 1}"] = round(float(x), 3)
                    row[f"y{i + 1}"] = round(float(y), 3)
            writer.writerow(row)


def write_predictions(path: Path, rows: list[dict]) -> None:
    columns = ["clip", "frame", "has_quad", "x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4", "ms"]
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in columns})


# --------------------------------------------------------------------------- detectors
def predict_ml(detector: infer.MLDetector, frames: list[Frame], refine: bool = False) -> list[dict]:
    rows = []
    for index, frame in enumerate(frames):
        with Image.open(frame.path) as img:
            rgb = np.asarray(img.convert("RGB"), dtype=np.uint8)
        start = time.perf_counter()
        pred = detector.predict_array(rgb)
        quad = pred.quad
        if refine and quad is not None:
            quad = _refine_quad(frame, quad)
        ms = (time.perf_counter() - start) * 1000.0
        row = {"clip": frame.clip, "frame": frame.frame, "has_quad": 1 if quad else 0,
               "ms": round(ms, 2)}
        if quad:
            for i, (x, y) in enumerate(quad):
                row[f"x{i + 1}"] = round(float(x), 2)
                row[f"y{i + 1}"] = round(float(y), 2)
        rows.append(row)
        if (index + 1) % 40 == 0:
            print(f"  ml: {index + 1}/{len(frames)}", flush=True)
    return rows


def _refine_quad(frame: Frame, quad) -> Optional[list[tuple[float, float]]]:
    """Optional r2 step-profile refinement at 1280 px long side (reported separately)."""
    with Image.open(frame.path) as img:
        gray = img.convert("L")
        src_w, src_h = gray.size
        factor = 1280.0 / max(src_w, src_h)
        if factor > 1.0:
            gray = gray.resize((int(round(src_w * factor)), int(round(src_h * factor))), Image.BILINEAR)
        else:
            factor = 1.0
        arr = np.asarray(gray, dtype=np.float32) / 255.0
    quad_hi = [(x * factor, y * factor) for x, y in quad]
    diag = float(np.hypot(arr.shape[1], arr.shape[0]))
    refined = postprocess.refine_with_steps(arr, quad_hi, diag)
    if refined is None:
        return quad
    return [(x / factor, y / factor) for x, y in refined]


def predict_shipped(frames: list[Frame]) -> list[dict]:
    sys.path.insert(0, str(PROTO))
    import deskew_port  # noqa: PLC0415

    rows = []
    for frame in frames:
        start = time.perf_counter()
        quad = deskew_port.detect_quad_shipped_path(frame.path)
        ms = (time.perf_counter() - start) * 1000.0
        row = {"clip": frame.clip, "frame": frame.frame, "has_quad": 1 if quad else 0,
               "ms": round(ms, 2)}
        if quad:
            for i, (x, y) in enumerate(quad):
                row[f"x{i + 1}"] = round(float(x), 2)
                row[f"y{i + 1}"] = round(float(y), 2)
        rows.append(row)
    return rows


def predict_r2(frames: list[Frame], accept: float = R2_ACCEPT) -> list[dict]:
    sys.path.insert(0, str(PROTO))
    import scannerx_r2  # noqa: PLC0415

    cfg = scannerx_r2.ConfigR2(accept=accept)
    rows = []
    for index, frame in enumerate(frames):
        det = scannerx_r2.detect_path_r2(frame.path, cfg)
        quad = det.quad if (det.quad is not None and det.confidence >= accept) else None
        row = {"clip": frame.clip, "frame": frame.frame, "has_quad": 1 if quad else 0,
               "ms": round(det.ms, 2)}
        if quad:
            for i, (x, y) in enumerate(quad):
                row[f"x{i + 1}"] = round(float(x), 2)
                row[f"y{i + 1}"] = round(float(y), 2)
        rows.append(row)
        if (index + 1) % 40 == 0:
            print(f"  r2: {index + 1}/{len(frames)}", flush=True)
    return rows


# --------------------------------------------------------------------------- scoring
def quad_of(row: dict):
    try:
        values = [float(row[k]) for k in ("x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4")]
    except (KeyError, TypeError, ValueError):
        return None
    return [(values[i], values[i + 1]) for i in range(0, 8, 2)]


def per_frame_iou(frames: list[Frame], rows: list[dict], scorer) -> dict:
    by_key = {(row["clip"], int(row["frame"])): row for row in rows}
    out = {}
    for frame in frames:
        row = by_key.get((frame.clip, frame.frame))
        quad = quad_of(row) if row is not None and str(row.get("has_quad")) == "1" else None
        iou = None
        if quad is not None and frame.quad is not None:
            iou = scorer.polygon_iou(quad, frame.quad, frame.width, frame.height)
        out[(frame.clip, frame.frame)] = iou
    return out


def run_judge(repo: Path, labels_csv: Path, pred_files: list[Path], out_md: Path,
              out_json: Path, title: str) -> None:
    command = [sys.executable, str(repo / "tools" / "scanner" / "score.py"),
               "--labels", str(labels_csv)]
    for path in pred_files:
        command += ["--pred", str(path)]
    command += ["--out-md", str(out_md), "--out-json", str(out_json), "--title", title]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"score.py failed: {result.stderr or result.stdout}")
    print(result.stdout.strip())


def summarise(judge_json: dict, names: list[str]) -> list[dict]:
    rows = []
    for name in names:
        entry = judge_json["predictions"].get(name)
        if entry is None:
            continue
        overall = entry["overall"]
        times = [r["ms"] for r in entry.get("_times", [])] if entry.get("_times") else []
        rows.append({
            "name": name,
            "recall": overall["recall"],
            "matched": overall["matched_iou"],
            "denom": overall["frames_has_doc_1_with_corners"],
            "detection": overall["detection_rate"],
            "fa_any": overall["false_accept_any"],
            "fa_rate": overall["false_accept_any_rate"],
            "fa_nonfit": overall["false_accept_nonfit"],
            "corner_median": overall["corner_median"],
            "corner_p90": overall["corner_p90"],
            "corner_n": overall["corner_frames"],
            "ms_median": float(np.median(times)) if times else None,
        })
    return rows


def timing_stats(rows: list[dict]) -> dict:
    values = sorted(float(r["ms"]) for r in rows if r.get("ms") not in (None, ""))
    if not values:
        return {"median": None, "p90": None, "n": 0}
    return {"median": round(values[len(values) // 2], 1),
            "p90": round(values[min(len(values) - 1, int(len(values) * 0.9))], 1),
            "n": len(values)}


def pct(value) -> str:
    return "n/a" if value is None else f"{value * 100:.1f}%"


# --------------------------------------------------------------------------- sweep
def sweep_thresholds(detector: infer.MLDetector, frames: list[Frame], scorer,
                     t_presence_grid, t_mask_grid) -> list[dict]:
    """Threshold selection on VAL: one forward pass, then every (t_p, t_m) pair.

    Recall/false-accept are counted exactly as score.py counts them (IoU >= 0.8 on
    has_doc=1 frames with corners; any quad on has_doc=0 frames), so the numbers are
    comparable with the final report. Selection is VAL-only by construction.
    """
    cached = []
    for frame in frames:
        with Image.open(frame.path) as img:
            rgb = np.asarray(img.convert("RGB"), dtype=np.uint8)
        pred = detector.predict_array(rgb)
        cached.append((frame, pred.mask_prob, pred.presence, pred.box))

    results = []
    for t_presence in t_presence_grid:
        for t_mask in t_mask_grid:
            doc1 = matched = 0
            doc0 = false_accepts = 0
            ious = []
            for frame, mask_prob, presence, box in cached:
                det = postprocess.detect_from_outputs(mask_prob, presence, box,
                                                      t_presence=t_presence, t_mask=t_mask)
                quad = data_ml.net_quad_to_source(det.quad, box) if det.quad is not None else None
                if frame.has_doc == 0:
                    doc0 += 1
                    false_accepts += 1 if quad is not None else 0
                elif frame.has_doc == 1 and frame.quad is not None:
                    doc1 += 1
                    iou = 0.0 if quad is None else scorer.polygon_iou(quad, frame.quad,
                                                                      frame.width, frame.height)
                    ious.append(iou)
                    matched += 1 if iou >= 0.8 else 0
            recall = matched / doc1 if doc1 else None
            fa = false_accepts / doc0 if doc0 else None
            results.append({
                "t_presence": round(t_presence, 3),
                "t_mask": round(t_mask, 3),
                "recall": recall,
                "matched": matched,
                "doc1": doc1,
                "false_accept": fa,
                "false_accept_n": false_accepts,
                "doc0": doc0,
                "corner_iou_mean": float(np.mean(ious)) if ious else None,
                "score": (recall or 0.0) - 1.5 * (fa or 0.0),
            })
    results.sort(key=lambda r: (-r["score"], -r["recall"], r["false_accept"]))

    presence_pos = [p for f, _m, p, _b in cached if f.has_doc == 1]
    presence_neg = [p for f, _m, p, _b in cached if f.has_doc == 0]
    if presence_pos and presence_neg:
        def quantiles(values, qs=(5, 50, 95)):
            return ", ".join(f"p{q}={float(np.percentile(values, q)):.3f}" for q in qs)
        print("presence probability  positives: " + quantiles(presence_pos))
        print("presence probability  negatives: " + quantiles(presence_neg))
        print("  (negatives are the NoDocument clip(s); a usable t_presence sits above them)")
    return results


def report_table(summary: list[dict], timings: dict, split: str, n_doc1: int, n_doc0: int) -> str:
    lines = [f"### {split} (doc=1 with corners n={n_doc1}, no-doc frames n={n_doc0})", "",
             "| detector | recall@IoU0.80 | returned a quad | false accept (any) | corner median | corner p90 | PC ms/frame (median / p90) |",
             "|---|---|---|---|---|---|---|"]
    for row in summary:
        timing = timings.get(row["name"], {})
        lines.append(
            f"| {row['name']} | {pct(row['recall'])} ({row['matched']}/{row['denom']}) | "
            f"{pct(row['detection'])} | {pct(row['fa_rate'])} ({row['fa_any']}/{n_doc0}) | "
            f"{pct(row['corner_median'])} (n={row['corner_n']}) | {pct(row['corner_p90'])} | "
            f"{timing.get('median', 'n/a')} / {timing.get('p90', 'n/a')} |")
    return "\n".join(lines)


# --------------------------------------------------------------------------- main
def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--split", default="val", choices=["val", "test", "web", "all"])
    parser.add_argument("--weights", default="", help="default runs/<run_id>/best.pt")
    parser.add_argument("--t-presence", type=float, default=0.5)
    parser.add_argument("--t-mask", type=float, default=0.5)
    parser.add_argument("--refine", action="store_true", help="also write the step-refined variant")
    parser.add_argument("--json-summary", default="", help="write a combined summary JSON here")
    parser.add_argument("--test", action="store_true",
                        help="spend one of the two TEST hold-out runs (required for test/web)")
    parser.add_argument("--sweep", action="store_true",
                        help="VAL-only threshold sweep (t_presence x t_mask) instead of a full report")
    parser.add_argument("--tp-grid", default="0.30,0.40,0.50,0.60,0.70,0.80,0.90,0.95")
    parser.add_argument("--tm-grid", default="0.30,0.50,0.70")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args(argv)

    here = Path(__file__).resolve()
    repo = here.parents[3]
    run_dir = data_ml.RUNS_DIR / args.run_id
    weights = Path(args.weights) if args.weights else run_dir / "best.pt"
    if not weights.is_file():
        raise SystemExit(f"weights not found: {weights}")
    weights_sha = sha256_file(weights)

    if args.sweep:
        scorer = load_scorer(repo)
        detector = infer.MLDetector(weights=str(weights), threads=args.threads)
        frames = manifest_frames("val")
        print(f"sweep on VAL: {len(frames)} frames, weights {weights_sha[:16]}...")
        results = sweep_thresholds(detector, frames, scorer,
                                   [float(v) for v in args.tp_grid.split(",") if v.strip()],
                                   [float(v) for v in args.tm_grid.split(",") if v.strip()])
        path = run_dir / "sweep_val.csv"
        with open(path, "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(results[0].keys()))
            writer.writeheader()
            writer.writerows(results)
        print(f"{'t_p':>5} {'t_m':>5} {'recall':>7} {'matched':>8} {'FA':>6} {'FA_n':>5} {'iou_mean':>9} {'score':>7}")
        for row in results:
            print(f"{row['t_presence']:>5.2f} {row['t_mask']:>5.2f} {pct(row['recall']):>7} "
                  f"{row['matched']:>4}/{row['doc1']:<3} {pct(row['false_accept']):>6} "
                  f"{row['false_accept_n']:>5} {row['corner_iou_mean']:>9.3f} {row['score']:>7.3f}")
        print(f"wrote {path}")
        return 0

    splits = ["val", "test", "web"] if args.split == "all" else [args.split]
    needs_budget = any(s in ("test", "web") for s in splits)
    if needs_budget and not args.test:
        raise SystemExit("refusing to score TEST/web without --test: that spends one of two runs")

    scorer = load_scorer(repo)
    detector = infer.MLDetector(weights=str(weights), t_presence=args.t_presence,
                                t_mask=args.t_mask, threads=args.threads)
    print(f"model: {weights} sha256 {weights_sha[:16]}... t_presence {args.t_presence} "
          f"t_mask {args.t_mask}")

    summary_all = {}
    timings_all = {}
    sections = []
    for split in splits:
        frames = manifest_frames(split) if split in ("val", "test") else web_frames()
        if not frames:
            print(f"{split}: no frames, skipped")
            continue
        print(f"\n=== {split}: {len(frames)} frames ===", flush=True)
        stem = f"eval_{split}"
        start = time.perf_counter()
        rows_ml = predict_ml(detector, frames)
        ml_seconds = time.perf_counter() - start
        rows_ml_refined = predict_ml(detector, frames, refine=True) if args.refine else None
        rows_shipped = predict_shipped(frames)
        rows_r2 = predict_r2(frames)

        labels_csv = run_dir / f"labels_{split}.csv"
        write_labels(labels_csv, frames)
        pred_ml = run_dir / f"pred_{split}_ml.csv"
        pred_shipped = run_dir / f"pred_{split}_shipped.csv"
        pred_r2 = run_dir / f"pred_{split}_r2.csv"
        write_predictions(pred_ml, rows_ml)
        write_predictions(pred_shipped, rows_shipped)
        write_predictions(pred_r2, rows_r2)
        pred_files = [pred_shipped, pred_r2, pred_ml]
        if rows_ml_refined is not None:
            pred_refined = run_dir / f"pred_{split}_ml_refined.csv"
            write_predictions(pred_refined, rows_ml_refined)
            pred_files.append(pred_refined)

        out_md = run_dir / f"{stem}.md"
        out_json = run_dir / f"{stem}.json"
        run_judge(repo, labels_csv, pred_files, out_md, out_json,
                  f"Scanner X ML {args.run_id} -- {split}")

        judge_json = json.loads(out_json.read_text(encoding="utf-8"))
        names = [p.stem for p in pred_files]
        timing = {"pred_{}_ml".format(split): timing_stats(rows_ml),
                  f"pred_{split}_shipped": timing_stats(rows_shipped),
                  f"pred_{split}_r2": timing_stats(rows_r2)}
        if rows_ml_refined is not None:
            timing[f"pred_{split}_ml_refined"] = {"median": None, "p90": None, "n": 0}
        overall = {
            "frames": len(frames),
            "doc1_with_corners": judge_json["predictions"][f"pred_{split}_ml"]["overall"]["frames_has_doc_1_with_corners"],
            "doc0": judge_json["predictions"][f"pred_{split}_ml"]["overall"]["frames_has_doc_0"],
            "wall_seconds_ml": round(ml_seconds, 1),
        }
        table = report_table(summarise(judge_json, names), timing, split,
                             overall["doc1_with_corners"], overall["doc0"])
        print(table)
        sections.append(table)

        summary_all[split] = {
            "overall": overall,
            "timing": timing,
            "rows": summarise(judge_json, names),
            "by_category": {name: judge_json["predictions"][name]["by_category"] for name in names},
            "by_clip": {name: judge_json["predictions"][name]["by_clip"] for name in names},
            "checks": {name: judge_json["predictions"][name]["checks"] for name in names},
            "iou_per_frame": {
                name: {f"{clip}/{frame}": iou for (clip, frame), iou in
                       per_frame_iou(frames, rows, scorer).items()}
                for name, rows in [(f"pred_{split}_ml", rows_ml),
                                   (f"pred_{split}_shipped", rows_shipped),
                                   (f"pred_{split}_r2", rows_r2)]
                + ([(f"pred_{split}_ml_refined", rows_ml_refined)] if rows_ml_refined else [])
            },
        }
        timings_all[split] = timing

    if needs_budget:
        entry = {
            "utc": utc_now(),
            "run_id": args.run_id,
            "weights": str(weights),
            "weights_sha256": weights_sha,
            "splits": splits,
            "t_presence": args.t_presence,
            "t_mask": args.t_mask,
            "refine": bool(args.refine),
            "git_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=str(repo),
                                               text=True).strip(),
            # An uncommitted working tree is normal here (code is committed after the run),
            # so the SHA alone does not identify what produced the weights. Record the
            # dirty flag and the per-file hashes of the model stage next to it.
            "git_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"],
                                                      cwd=str(repo), text=True).strip()),
            "code_files_sha256": {
                path.name: sha256_file(path) for path in sorted(
                    (Path(__file__).parent).glob("*.py")) if path.name != "__init__.py"
            },
        }
        with open(TEST_LOG, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry) + "\n")
        print(f"\nTEST budget: appended run to {TEST_LOG}")

    summary_path = Path(args.json_summary) if args.json_summary else run_dir / "eval_summary.json"
    payload = {
        "run_id": args.run_id,
        "weights": str(weights),
        "weights_sha256": weights_sha,
        "t_presence": args.t_presence,
        "t_mask": args.t_mask,
        "splits": summary_all,
        "generated_utc": utc_now(),
    }
    summary_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    (run_dir / "eval_summary.md").write_text(
        f"# Scanner X ML -- {args.run_id}\n\nModel `{weights}` (sha256 `{weights_sha}`).\n\n"
        + "\n\n".join(sections) + "\n", encoding="utf-8")
    print(f"\nwrote {summary_path}")
    print(f"wrote {run_dir / 'eval_summary.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
