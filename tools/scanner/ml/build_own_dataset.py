"""Build the owner-owned (commercially usable) training pool for Scanner X.

Input  (private, outside Git): qa/recordings/labels/labels_{A,B,C}.csv
                               qa/recordings/frames/<clip>/<frame>.jpg
        labels_corrected.csv overrides rows of the same (clip, frame) when present.
Output (private, outside Git): qa/scannerx-ml/commercial/own/
                               images/<clip>/<frame>.jpg  long side 512
                               masks/<clip>/<frame>.png   rasterised 4-corner quad
                               manifest.csv               one row per frame, source SHA256
                               splits.json               scene-level split rule + clip lists

Label semantics (qa/recordings/labels/LABEL_SPEC.md)
- has_doc=1 and four corners -> paper mask.
- has_doc=1 without corners -> paper present, geometry unlabelled ("no_geometry"):
  usable as a presence sample only; it can never contribute to a mask loss.
- has_doc=0 -> genuine no-paper negative, empty mask.
- has_doc=2 -> a document exists but fewer than three corners are visible. These
  frames are marked "ignore": they are neither a usable positive (no reliable
  quad, and forcing a mask loss would teach wrong geometry) nor an honest
  negative (a page *is* on screen, so penalising "paper present" would teach the
  model to reject real documents). The trainer must exclude them from loss.

Confidence is copied into the manifest with a numeric weight (high 1.0, med 0.7,
low/blank 0.4) so the trainer can down-weight uncertain geometry. Low-confidence
frames are kept, never silently dropped: a wrong mask is a data problem, and
hiding it would make the dataset look cleaner than it is.

Never commit this output: it is private owner media, not training weights either.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, OrderedDict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image

from mlcommon import (
    parse_quad,
    rasterize_mask,
    resize_long_side,
    scale_points,
    sha256_file,
)

LABEL_FILES = ("labels_A.csv", "labels_B.csv", "labels_C.csv")
CORRECTED_FILE = "labels_corrected.csv"

# Fixed by the task contract: never trained on, never used for model selection.
TEST_CLIPS = (
    "03",
    "07",
    "13",
    "15",
    "18",
    "23",
    "27",
    "29",
    "EXTRA_04_Handwritten_Notebook",
)
# One whole clip per category, chosen before any training run, so validation
# cannot be tuned to a lucky split. Clips 01-08 Normal, 09-15 Movement,
# 16-20 Lighting, 21-25 DifficultPaper, 26-30 NoDocument.
VAL_CLIPS = ("05", "10", "17", "22", "28")
VAL_CATEGORIES = ("Normal", "Movement", "Lighting", "DifficultPaper", "NoDocument")

SPLIT_RULE = (
    "Split by capture scene (clip = one capture session / one scene). TEST is the "
    "fixed contract hold-out (clips 03,07,13,15,18,23,27,29,EXTRA_04): never trained "
    "on and never used for model selection. VAL is five whole clips, one per category "
    "(05 Normal, 10 Movement, 17 Lighting, 22 DifficultPaper, 28 NoDocument), fixed "
    "before training. TRAIN is every remaining clip. Web stills in "
    "stills/web-2026-11-19 are third-party images used only as an untrained FIELD-TEST "
    "and never enter train/val/test scoring of model selection."
)

WEIGHTS = {"high": 1.0, "med": 0.7, "low": 0.4, "": 0.4}


def read_rows(labels_dir: Path, recordings_dir: Path, log) -> list[dict]:
    """Read all label CSVs, apply the corrected override, resolve frame paths."""
    rows: OrderedDict[tuple[str, str], dict] = OrderedDict()
    for name in LABEL_FILES:
        path = labels_dir / name
        if not path.exists():
            log(f"labels: {name} MISSING (skipped)")
            continue
        with open(path, encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                key = (str(row["clip"]).strip(), str(row["frame"]).strip())
                rows[key] = dict(row)
        log(f"labels: {name}")

    corrected = labels_dir / CORRECTED_FILE
    if corrected.exists():
        overrides = 0
        with open(corrected, encoding="utf-8-sig", newline="") as handle:
            for row in csv.DictReader(handle):
                key = (str(row["clip"]).strip(), str(row["frame"]).strip())
                rows[key] = dict(row)
                overrides += 1
        log(f"labels: {CORRECTED_FILE} overrides {overrides} rows")
    else:
        log(f"labels: {CORRECTED_FILE} absent (no overrides)")

    missing = []
    for row in rows.values():
        raw = str(row["file"]).strip()
        candidates = [
            recordings_dir / raw,
            recordings_dir / "frames" / str(row["clip"]).strip() / raw,
        ]
        resolved = next((c for c in candidates if c.exists()), None)
        if resolved is None:
            missing.append(raw)
        row["_path"] = resolved
    if missing:
        raise FileNotFoundError(f"{len(missing)} labelled frames not found, e.g. {missing[:5]}")
    return list(rows.values())


def assign_splits(clips) -> dict[str, list[str]]:
    """Assign whole clips to train/val/test; raises on unknown or duplicated clips."""
    clips = [str(c) for c in clips]
    if len(set(clips)) != len(clips):
        raise ValueError("duplicate clip ids in input")
    known = set(clips)
    unknown_test = [c for c in TEST_CLIPS if c not in known]
    unknown_val = [c for c in VAL_CLIPS if c not in known]
    if unknown_test or unknown_val:
        raise ValueError(f"split clips missing from data: test={unknown_test} val={unknown_val}")
    overlap = set(TEST_CLIPS) & set(VAL_CLIPS)
    if overlap:
        raise ValueError(f"clip in both TEST and VAL: {sorted(overlap)}")
    val = [c for c in VAL_CLIPS]
    test = [c for c in TEST_CLIPS]
    train = sorted(c for c in clips if c not in set(val) | set(test))
    return {"train": train, "val": val, "test": test}


def split_counts(entries) -> dict:
    """Frame-level counts per split, split by supervision type."""
    out: dict[str, Counter] = {}
    for entry in entries:
        bucket = out.setdefault(entry["split"], Counter())
        bucket["frames"] += 1
        status = entry["mask_status"]
        if status == "ok":
            bucket["positives_masked"] += 1
        elif status == "no_geometry":
            bucket["positives_unmasked"] += 1
        elif status == "ignore_partial_doc":
            bucket["ignored_partial_doc"] += 1
        elif status == "negative":
            bucket["negatives"] += 1
        if entry["confidence"] == "low" or entry["confidence"] == "":
            bucket["low_confidence"] += 1
    counts = {}
    for name, counter in sorted(out.items()):
        clips = sorted({e["clip"] for e in entries if e["split"] == name})
        counts[name] = {
            "clips": len(clips),
            "clip_list": clips,
            "frames": counter["frames"],
            "positives_masked": counter["positives_masked"],
            "positives_unmasked": counter["positives_unmasked"],
            "negatives": counter["negatives"],
            "ignored_partial_doc": counter["ignored_partial_doc"],
            "low_confidence": counter["low_confidence"],
        }
    return counts


def build(recordings_dir: Path, out_dir: Path, long_side: int = 512, log=print) -> dict:
    labels_dir = recordings_dir / "labels"
    rows = read_rows(labels_dir, recordings_dir, log)
    log(f"labels: {len(rows)} unique (clip, frame) rows")

    splits = assign_splits(sorted({str(r["clip"]).strip() for r in rows}))
    clip_to_split = {clip: name for name, clips in splits.items() for clip in clips}

    images_dir = out_dir / "images"
    masks_dir = out_dir / "masks"
    images_dir.mkdir(parents=True, exist_ok=True)
    masks_dir.mkdir(parents=True, exist_ok=True)

    entries = []
    for row in sorted(rows, key=lambda r: (str(r["clip"]), int(r["frame"]) if str(r["frame"]).isdigit() else 0, str(r["frame"]))):
        clip = str(row["clip"]).strip()
        frame = str(row["frame"]).strip()
        has_doc = str(row["has_doc"]).strip()
        category = str(row["category"]).strip()
        confidence = str(row["confidence"]).strip().lower()
        src = row["_path"]

        with Image.open(src) as probe:
            width, height = probe.size
            src_mode = probe.mode
            image = probe.convert("RGB")
        target_w, target_h = resize_long_side(width, height, long_side)
        sx, sy = target_w / width, target_h / height

        mask_status = "negative"
        quad_src = None
        quad_dst = None
        if has_doc == "2":
            mask_status = "ignore_partial_doc"
        elif has_doc == "1":
            quad = parse_quad(row)
            if quad is None:
                mask_status = "no_geometry"
            else:
                quad = [(min(max(x, 0.0), width - 1.0), min(max(y, 0.0), height - 1.0)) for x, y in quad]
                quad_src = quad
                quad_dst = scale_points(quad, sx, sy)
                mask_status = "ok"
        elif has_doc != "0":
            raise ValueError(f"unexpected has_doc={has_doc!r} at {clip}/{frame}")

        # has_doc=2 and no_geometry frames stay unflagged from the negative pool;
        # they are written so a reviewer can see them, with no mask file at all.
        image_out = images_dir / clip / f"{frame}.png"
        image_out.parent.mkdir(parents=True, exist_ok=True)
        image.resize((target_w, target_h), Image.LANCZOS).save(image_out)

        mask_out = None
        if mask_status == "ok":
            mask = rasterize_mask(quad_dst, (target_w, target_h))
            mask_out = masks_dir / clip / f"{frame}.png"
            mask_out.parent.mkdir(parents=True, exist_ok=True)
            Image.fromarray(mask).save(mask_out)

        entries.append(
            {
                "split": clip_to_split[clip],
                "clip": clip,
                "frame": frame,
                "has_doc": has_doc,
                "category": category,
                "confidence": confidence,
                "labeler": str(row.get("labeler", "")).strip(),
                "weight": WEIGHTS.get(confidence, 0.4),
                "mask_status": mask_status,
                "image": image_out.relative_to(out_dir).as_posix(),
                "mask": mask_out.relative_to(out_dir).as_posix() if mask_out else "",
                "corners": json.dumps([[round(x, 1), round(y, 1)] for x, y in quad_dst]) if quad_dst else "",
                "src_file": str(src.relative_to(recordings_dir)).replace("\\", "/"),
                "src_w": width,
                "src_h": height,
                "src_mode": src_mode,
                "src_sha256": sha256_file(src),
                "notes": str(row.get("notes", "")).replace("\n", " ")[:200],
            }
        )

    columns = [
        "split", "clip", "frame", "has_doc", "category", "confidence", "labeler", "weight",
        "mask_status", "image", "mask", "corners", "src_file", "src_w", "src_h", "src_mode",
        "src_sha256", "notes",
    ]
    manifest = out_dir / "manifest.csv"
    with open(manifest, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        writer.writerows(entries)

    # Field-test stills: third-party images, evaluation only, listed but never copied.
    stills_dir = recordings_dir / "stills" / "web-2026-11-19"
    stills = sorted(p.name for p in stills_dir.glob("*.jpg")) if stills_dir.exists() else []
    still_labels = labels_dir / "labels_web.csv"

    counts = split_counts(entries)
    summary = {
        "version": 1,
        "created_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "unit": "clip (one capture session / one scene); consecutive frames never cross splits",
        "rule": SPLIT_RULE,
        "long_side": long_side,
        "splits": {name: splits[name] for name in ("train", "val", "test")},
        "val_category_coverage": dict(zip(VAL_CLIPS, VAL_CATEGORIES)),
        "field_test": {
            "clips": [
                {
                    "name": "web-2026-11-19",
                    "frames": stills,
                    "labels": "labels_web.csv" if still_labels.exists() else None,
                    "note": (
                        "Third-party web images, LOCAL EVALUATION ONLY (never commit/upload/ship). "
                        "Not in train/val/test. "
                        + ("" if still_labels.exists() else "labels_web.csv absent: unlabelled until the label task delivers it.")
                    ),
                }
            ]
        },
        "counts": counts,
        "source_labels": list(LABEL_FILES) + ([CORRECTED_FILE] if (labels_dir / CORRECTED_FILE).exists() else []),
        "manifest_sha256": sha256_file(manifest),
    }
    splits_path = out_dir / "splits.json"
    with open(splits_path, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2)
        handle.write("\n")

    log(f"wrote {len(entries)} frames -> {out_dir}")
    for name, info in counts.items():
        log(
            f"  {name}: {info['clips']} clips, {info['frames']} frames, "
            f"{info['positives_masked']} masked positives, {info['positives_unmasked']} unmasked, "
            f"{info['negatives']} negatives, {info['ignored_partial_doc']} ignored, "
            f"{info['low_confidence']} low-confidence"
        )
    return summary


def main(argv=None) -> int:
    default_recordings = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\recordings")
    default_out = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\scannerx-ml\commercial\own")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--recordings", type=Path, default=default_recordings)
    parser.add_argument("--out", type=Path, default=default_out)
    parser.add_argument("--long-side", type=int, default=512)
    args = parser.parse_args(argv)
    if "lanes" in str(args.out) or "current" in str(args.out):
        raise SystemExit("refusing to write private media inside the Git checkout")
    summary = build(args.recordings, args.out, args.long_side)
    print(json.dumps(summary["counts"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
