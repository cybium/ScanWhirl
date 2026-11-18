"""Score a page-corner detector against the private scanner ground truth.

This is the measuring instrument for Scanner X groundwork: it turns the hand/model
labelled frames (see ``qa/recordings/labels/LABEL_SPEC.md``, private, never committed)
plus a per-frame predictions CSV into the metrics and PASS/FAIL verdicts agreed in
``SCANNER_EVIDENCE.md`` section 3.

What it measures, and what it deliberately does not
---------------------------------------------------
* ``returned a quad`` -- the old "95.5%" style number. A frame counts when the
  prediction says ``has_quad=1``. It says nothing about whether the page was found.
* ``doc recall`` -- ``has_doc=1`` frames **that carry corners** where the predicted
  quad and the labelled polygon have a rasterised IoU >= 0.80. This is the number that
  can be called accuracy.
* ``false accept`` -- ``has_doc=0`` frames that got a quad, reported twice: raw (any
  quad) and after the app's own frame-fit rejection (a quad with every corner within
  5% of a frame border is the frame, not a page -- ``PhotoAutoCrop.isFrameFit``).
* ``corner error`` -- per frame, mean distance of the 4 predicted corners to the 4
  labelled corners (best cyclic order) divided by the labelled page diagonal; median
  and p90 over frames with IoU > 0, split by the *label's* confidence (high/med vs
  low), because that is the only confidence either file carries.
* ``jitter`` -- mean corner step between consecutive predicted frames of a clip,
  divided by the frame diagonal: the same definition as ``BENCH_detectQuad.md``.
  Computed from the prediction file alone, so it covers frames without labels too.

Nothing here reads an image, a video or the network. Labels and predictions are local
CSV files; the report contains numbers only.

Usage
-----
    python tools/scanner/score.py \\
        --labels C:/.../qa/recordings/labels/labels_C.csv \\
        --pred C:/.../qa/recordings/labels/pred_detectquad_raw.csv \\
        --pred C:/.../qa/recordings/labels/pred_photoautocrop.csv \\
        --out-md C:/.../SCANNER_BASELINE.md \\
        --out-json C:/.../SCANNER_BASELINE.json

Geometry note: predictions and labels are both in **stored source JPEG pixels**
(top-left origin, TL/TR/BR/BL). Labels may place a corner slightly outside the frame
("clamped"), so masks are rasterised on a full-size coordinate system and clipped by
PIL, never by pre-clamping the numbers (a clamp would fake agreement at the edges).

Stdlib + Pillow only (no numpy needed); tested by ``tools/scanner/test_score.py``.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from PIL import Image, ImageChops, ImageDraw, ImageStat

Quad = List[Tuple[float, float]]

MASK_LONG_SIDE = 512
IOU_THRESHOLD = 0.80
FRAME_FIT_MARGIN = 0.05
RECALL_AGGREGATE_MIN = 0.95
RECALL_PER_CLIP_MIN = 0.90
FALSE_ACCEPT_MAX = 0.05
FALSE_ACCEPT_ZERO_CLIPS = ("26", "30")
CORNER_MEDIAN_MAX = 0.02
CORNER_P90_MAX = 0.05

LABEL_COLUMNS = (
    "clip", "frame", "file", "width", "height", "has_doc", "category",
    "x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4",
    "labeler", "confidence", "notes",
)


# --------------------------------------------------------------------------- model


@dataclass
class Label:
    clip: str
    frame: int
    width: int
    height: int
    has_doc: int
    category: str
    quad: Optional[Quad]
    confidence: str
    labeler: str


@dataclass
class Prediction:
    clip: str
    frame: int
    quad: Optional[Quad]
    ms: Optional[float]


@dataclass
class LoadReport:
    files: List[str] = field(default_factory=list)
    per_file: List[Dict[str, object]] = field(default_factory=list)
    rows: int = 0
    frames: int = 0
    duplicates: int = 0
    malformed: int = 0
    has_doc: Dict[str, int] = field(default_factory=dict)
    labelers: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


# ------------------------------------------------------------------------ parsing


def _norm_clip(value: str) -> str:
    return (value or "").strip()


def _num(text: str) -> Optional[float]:
    text = (text or "").strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def _frame_int(text: str) -> Optional[int]:
    text = (text or "").strip()
    if not text:
        return None
    stem = os.path.splitext(os.path.basename(text))[0]
    try:
        return int(stem)
    except ValueError:
        return None


def _quad_from_row(row: Dict[str, str]) -> Optional[Quad]:
    keys = ("x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4")
    values = [_num(row.get(k, "")) for k in keys]
    if any(v is None for v in values):
        return None
    pts = [(float(values[i]), float(values[i + 1])) for i in range(0, 8, 2)]
    if quad_degenerate(pts):
        return None
    return pts


def load_labels(paths: Sequence[str], report: Optional[LoadReport] = None) -> Dict[Tuple[str, int], Label]:
    """Merge one or more label CSVs, keyed by (clip, frame).

    First file wins on a duplicate so a later, half-written file can never silently
    replace a row that is already on disk. Malformed rows are counted and skipped --
    a broken row must not crash a report that other workers are waiting on.
    """
    report = report if report is not None else LoadReport()
    labels: Dict[Tuple[str, int], Label] = {}
    for path in paths:
        report.files.append(os.path.abspath(path))
        file_rows = 0
        file_frames = 0
        file_has_doc: Dict[str, int] = {}
        file_clips = set()
        labeler = ""
        with open(path, "r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            missing = [c for c in ("clip", "frame", "width", "height", "has_doc") if c not in (reader.fieldnames or [])]
            if missing:
                report.warnings.append(f"{os.path.basename(path)}: missing columns {missing}; file skipped")
                continue
            for row in reader:
                report.rows += 1
                file_rows += 1
                clip = _norm_clip(row.get("clip", ""))
                frame = _frame_int(row.get("frame", ""))
                has_doc_text = (row.get("has_doc") or "").strip()
                if not clip or frame is None or has_doc_text not in ("0", "1", "2"):
                    report.malformed += 1
                    continue
                width = _num(row.get("width", "")) or 0
                height = _num(row.get("height", "")) or 0
                if width <= 0 or height <= 0:
                    report.malformed += 1
                    continue
                has_doc = int(has_doc_text)
                quad = _quad_from_row(row)
                if has_doc == 1 and quad is None:
                    quad = None  # corners deliberately omitted (every-2nd sampling)
                elif has_doc != 1 and quad is not None:
                    report.warnings.append(f"{clip}/{frame}: has_doc={has_doc} but corners present; corners ignored")
                    quad = None
                confidence = (row.get("confidence") or "").strip().lower()
                if confidence in ("medium", "mid"):
                    confidence = "med"
                if confidence not in ("high", "med", "low"):
                    confidence = ""
                row_labeler = (row.get("labeler") or "").strip()
                if row_labeler:
                    labeler = row_labeler
                    if row_labeler not in report.labelers:
                        report.labelers.append(row_labeler)
                key = (clip, frame)
                if key in labels:
                    report.duplicates += 1
                    continue
                labels[key] = Label(
                    clip=clip,
                    frame=frame,
                    width=int(round(width)),
                    height=int(round(height)),
                    has_doc=has_doc,
                    category=(row.get("category") or "").strip() or "Unknown",
                    quad=quad,
                    confidence=confidence,
                    labeler=row_labeler or labeler,
                )
                file_frames += 1
                file_has_doc[str(has_doc)] = file_has_doc.get(str(has_doc), 0) + 1
                file_clips.add(clip)
        report.per_file.append({
            "file": os.path.abspath(path),
            "rows": file_rows,
            "frames": file_frames,
            "has_doc": dict(sorted(file_has_doc.items())),
            "clips": sorted(file_clips, key=_clip_sort_key),
        })
    report.frames = len(labels)
    counts: Dict[str, int] = {}
    for label in labels.values():
        counts[str(label.has_doc)] = counts.get(str(label.has_doc), 0) + 1
    report.has_doc = dict(sorted(counts.items()))
    return labels


def load_predictions(path: str) -> Tuple[Dict[Tuple[str, int], Prediction], int, int]:
    """Read a predictions CSV. Returns (by (clip, frame), malformed rows, duplicate rows)."""
    predictions: Dict[Tuple[str, int], Prediction] = {}
    malformed = 0
    duplicates = 0
    if not os.path.isfile(path):
        raise FileNotFoundError(path)
    with open(path, "r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames or []
        for name in ("clip", "frame", "has_quad"):
            if name not in fields:
                raise ValueError(f"{path}: missing column {name!r} (header: {fields})")
        for row in reader:
            clip = _norm_clip(row.get("clip", ""))
            frame = _frame_int(row.get("frame", ""))
            has_quad = (row.get("has_quad") or "").strip().lower()
            if not clip or frame is None:
                malformed += 1
                continue
            quad = None
            if has_quad in ("1", "true", "yes", "y"):
                quad = _quad_from_row(row)
                if quad is None:
                    malformed += 1  # said yes, gave no usable corners: counted as no quad below
            elif has_quad not in ("0", "false", "no", "n", ""):
                malformed += 1
            ms_text = (row.get("ms") or "").strip()
            ms = _num(ms_text) if ms_text else None
            key = (clip, frame)
            if key in predictions:
                duplicates += 1
                continue
            predictions[key] = Prediction(clip=clip, frame=frame, quad=quad, ms=ms)
    return predictions, malformed, duplicates


# ----------------------------------------------------------------------- geometry


def quad_degenerate(quad: Quad) -> bool:
    if len(quad) != 4:
        return True
    for i in range(4):
        x1, y1 = quad[i]
        x2, y2 = quad[(i + 1) % 4]
        if math.hypot(x2 - x1, y2 - y1) <= 1e-6:
            return True
    return False


def page_diagonal(quad: Quad) -> float:
    """Mean of the quad's two diagonals: the scale a corner error is quoted against."""
    d1 = math.hypot(quad[2][0] - quad[0][0], quad[2][1] - quad[0][1])
    d2 = math.hypot(quad[3][0] - quad[1][0], quad[3][1] - quad[1][1])
    return (d1 + d2) / 2.0


def frame_diagonal(width: int, height: int) -> float:
    return math.hypot(float(width), float(height))


def is_frame_fit(quad: Quad, width: int, height: int, margin: float = FRAME_FIT_MARGIN) -> bool:
    """Same rule as ``PhotoAutoCrop.isFrameFit``: every corner within `margin` of a border."""
    if width <= 0 or height <= 0:
        return False
    for x, y in quad:
        nx, ny = x / width, y / height
        if min(nx, 1.0 - nx, ny, 1.0 - ny) >= margin:
            return False
    return True


def rasterize_quad(quad: Quad, width: int, height: int, long_side: int = MASK_LONG_SIDE) -> Image.Image:
    """Draw the quad onto a 0/255 mask whose long side is `long_side` pixels.

    Mode "L" rather than mode "1" on purpose: Pillow's mode-"1" histograms are not
    consistent between a drawn mask and an ``ImageChops`` result (index 1 vs 255), and
    a silent off-by-255 there would report IoU 0 for identical quads.
    """
    if width <= 0 or height <= 0:
        raise ValueError("frame size must be positive")
    scale = float(long_side) / float(max(width, height))
    mask_w = max(2, int(round(width * scale)))
    mask_h = max(2, int(round(height * scale)))
    mask = Image.new("L", (mask_w, mask_h), 0)
    ImageDraw.Draw(mask).polygon([(x * scale, y * scale) for x, y in quad], fill=255)
    return mask


def mask_iou(a: Image.Image, b: Image.Image) -> float:
    """IoU of two 0/255 masks drawn on the same grid."""
    intersection = ImageStat.Stat(ImageChops.multiply(a, b)).sum[0] / 255.0
    union = ImageStat.Stat(ImageChops.lighter(a, b)).sum[0] / 255.0
    if union <= 0:
        return 0.0
    return intersection / union


def polygon_iou(pred: Quad, truth: Quad, width: int, height: int, long_side: int = MASK_LONG_SIDE) -> float:
    return mask_iou(rasterize_quad(pred, width, height, long_side),
                    rasterize_quad(truth, width, height, long_side))


def _mean_corner_distance(pred: Quad, truth: Quad, undirected: bool = False) -> float:
    order = list(reversed(pred)) if undirected else list(pred)
    return sum(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(order, truth)) / 4.0


def match_corners(pred: Quad, truth: Quad) -> Tuple[float, bool]:
    """Best cyclic alignment of the predicted corners to the labelled ones.

    Returns (mean corner distance in pixels, mirror_suspect). The primary match keeps
    the winding direction: a reversed quad is a mirrored page, and letting the pairing
    flip would hide that. `mirror_suspect` is True when reversing would pair better,
    so a reader can see how often the detector's corner order is wrong.
    """
    forward = min(
        (_mean_corner_distance(pred[k:] + pred[:k], truth), k) for k in range(4)
    )[0]
    backward = min(
        (_mean_corner_distance(list(reversed(pred))[k:] + list(reversed(pred))[:k], truth), k)
        for k in range(4)
    )[0]
    return forward, backward < forward - 1e-9


def corner_error(pred: Quad, truth: Quad, width: int, height: int) -> Tuple[float, bool]:
    """Mean corner distance / labelled page diagonal, plus the mirror diagnostic."""
    diagonal = page_diagonal(truth)
    if diagonal <= 1e-6:
        return float("nan"), False
    distance, mirror = match_corners(pred, truth)
    return distance / diagonal, mirror


def _percentile(values: Sequence[float], q: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * q / 100.0
    low = int(math.floor(position))
    high = min(low + 1, len(ordered) - 1)
    frac = position - low
    return ordered[low] * (1.0 - frac) + ordered[high] * frac


# ------------------------------------------------------------------------- metrics


@dataclass
class Group:
    """Everything a metric block needs for one slice (overall, a category, a clip)."""

    labels: List[Label] = field(default_factory=list)
    quads: List[Optional[Quad]] = field(default_factory=list)

    @property
    def n_labelled(self) -> int:
        return len(self.labels)

    def _count(self, has_doc: int) -> int:
        return sum(1 for label in self.labels if label.has_doc == has_doc)


def build_metrics(labels: Dict[Tuple[str, int], Label],
                  predictions: Dict[Tuple[str, int], Prediction],
                  keys: Optional[Iterable[Tuple[str, int]]] = None,
                  long_side: int = MASK_LONG_SIDE,
                  iou_threshold: float = IOU_THRESHOLD) -> Dict[str, object]:
    used = list(keys) if keys is not None else list(labels.keys())
    doc1 = doc0 = doc2 = 0
    doc1_with_corners = 0
    doc1_corners_omitted = 0
    returned_quad = 0
    matched = 0
    false_any = 0
    false_nonfit = 0
    corner_values: List[float] = []
    corner_values_hi_med: List[float] = []
    corner_values_low: List[float] = []
    mirror_suspects = 0
    missing_predictions = 0

    for key in used:
        label = labels[key]
        prediction = predictions.get(key)
        if prediction is None:
            missing_predictions += 1
        quad = prediction.quad if prediction is not None else None

        if label.has_doc == 2:
            doc2 += 1
            continue
        if label.has_doc == 0:
            doc0 += 1
            if quad is not None:
                false_any += 1
                if not is_frame_fit(quad, label.width, label.height):
                    false_nonfit += 1
            continue

        doc1 += 1
        if label.quad is None:
            doc1_corners_omitted += 1
            if quad is not None:
                returned_quad += 1
            continue
        doc1_with_corners += 1
        if quad is None:
            continue
        returned_quad += 1
        iou = polygon_iou(quad, label.quad, label.width, label.height, long_side)
        if iou >= iou_threshold:
            matched += 1
        if iou > 0.0:
            error, mirror = corner_error(quad, label.quad, label.width, label.height)
            if not math.isnan(error):
                corner_values.append(error)
                if label.confidence == "low":
                    corner_values_low.append(error)
                else:  # high, med, or unlabelled -> the "trusted" bucket
                    corner_values_hi_med.append(error)
                if mirror:
                    mirror_suspects += 1

    def rate(numerator: int, denominator: int) -> Optional[float]:
        return None if denominator == 0 else numerator / denominator

    return {
        "labelers": sorted({labels[key].labeler for key in used if labels[key].labeler}),
        "frames_labelled": len(used),
        "frames_has_doc_1": doc1,
        "frames_has_doc_1_with_corners": doc1_with_corners,
        "frames_has_doc_1_corners_omitted": doc1_corners_omitted,
        "frames_has_doc_0": doc0,
        "frames_has_doc_2_excluded": doc2,
        "frames_missing_prediction": missing_predictions,
        "returned_quad_doc1": returned_quad,
        "detection_rate": rate(returned_quad, doc1),
        "matched_iou": matched,
        "recall": rate(matched, doc1_with_corners),
        "false_accept_any": false_any,
        "false_accept_any_rate": rate(false_any, doc0),
        "false_accept_nonfit": false_nonfit,
        "false_accept_nonfit_rate": rate(false_nonfit, doc0),
        "corner_frames": len(corner_values),
        "corner_median": _percentile(corner_values, 50),
        "corner_p90": _percentile(corner_values, 90),
        "corner_frames_conf_high_med": len(corner_values_hi_med),
        "corner_median_conf_high_med": _percentile(corner_values_hi_med, 50),
        "corner_p90_conf_high_med": _percentile(corner_values_hi_med, 90),
        "corner_frames_conf_low": len(corner_values_low),
        "corner_median_conf_low": _percentile(corner_values_low, 50),
        "corner_p90_conf_low": _percentile(corner_values_low, 90),
        "mirror_suspects": mirror_suspects,
    }


def jitter_by_clip(predictions: Dict[Tuple[str, int], Prediction],
                   labels: Dict[Tuple[str, int], Label]) -> Dict[str, Optional[float]]:
    """Mean corner step between consecutive *predicted* frames of a clip / frame diagonal.

    Same definition as BENCH_detectQuad.md: frames are walked in order, a frame without
    a quad is skipped (the step bridges the gap), each step is the mean of the four
    corner distances divided by that frame's diagonal.
    """
    by_clip: Dict[str, List[Tuple[int, Prediction]]] = {}
    for (clip, frame), prediction in predictions.items():
        by_clip.setdefault(clip, []).append((frame, prediction))
    result: Dict[str, Optional[float]] = {}
    for clip, rows in by_clip.items():
        rows.sort(key=lambda item: item[0])
        steps: List[float] = []
        previous: Optional[Prediction] = None
        for frame, prediction in rows:
            if prediction.quad is None:
                continue
            if previous is not None:
                label = labels.get((clip, frame))
                if label is not None and label.width > 0 and label.height > 0:
                    diagonal = frame_diagonal(label.width, label.height)
                else:
                    diagonal = 1.0
                step = sum(
                    math.hypot(a[0] - b[0], a[1] - b[1])
                    for a, b in zip(previous.quad, prediction.quad)
                ) / 4.0
                if diagonal > 0:
                    steps.append(step / diagonal)
            previous = prediction
        result[clip] = (sum(steps) / len(steps)) if steps else None
    return result


# --------------------------------------------------------------------------- checks


def _pct(value: Optional[float]) -> str:
    return "n/a" if value is None else f"{value * 100.0:.1f}%"


def _ratio(value: Optional[float]) -> str:
    return "n/a" if value is None else f"{value:.4f}"


def _verdict(passed: Optional[bool]) -> str:
    if passed is None:
        return "n/a"
    return "PASS" if passed else "FAIL"


def evaluate_checks(overall: Dict[str, object], by_clip: Dict[str, Dict[str, object]]) -> Tuple[List[Dict[str, object]], str]:
    """PASS/FAIL for the thresholds agreed in SCANNER_EVIDENCE.md section 3."""
    checks: List[Dict[str, object]] = []

    recall = overall.get("recall")
    checks.append({
        "metric": "doc recall (IoU >= 0.80), aggregate",
        "value": _pct(recall),
        "threshold": f">= {RECALL_AGGREGATE_MIN * 100:.0f}%",
        "verdict": _verdict(None if recall is None else recall >= RECALL_AGGREGATE_MIN),
    })

    per_clip = [
        (clip, metrics) for clip, metrics in by_clip.items()
        if metrics.get("frames_has_doc_1_with_corners", 0) > 0
    ]
    if per_clip:
        worst_clip, worst = min(per_clip, key=lambda item: item[1]["recall"])
        checks.append({
            "metric": "doc recall (IoU >= 0.80), worst positive clip",
            "value": f"{_pct(worst['recall'])} (clip {worst_clip})",
            "threshold": f">= {RECALL_PER_CLIP_MIN * 100:.0f}% per clip",
            "verdict": _verdict(worst["recall"] >= RECALL_PER_CLIP_MIN),
        })
    else:
        checks.append({
            "metric": "doc recall (IoU >= 0.80), worst positive clip",
            "value": "n/a (no corner-labelled frames)",
            "threshold": f">= {RECALL_PER_CLIP_MIN * 100:.0f}% per clip",
            "verdict": "n/a",
        })

    fa_any = overall.get("false_accept_any_rate")
    fa_nonfit = overall.get("false_accept_nonfit_rate")
    checks.append({
        "metric": "false accept, any quad",
        "value": _pct(fa_any),
        "threshold": f"<= {FALSE_ACCEPT_MAX * 100:.0f}%",
        "verdict": _verdict(None if fa_any is None else fa_any <= FALSE_ACCEPT_MAX),
    })
    checks.append({
        "metric": "false accept, after frame-fit rejection",
        "value": _pct(fa_nonfit),
        "threshold": f"<= {FALSE_ACCEPT_MAX * 100:.0f}%",
        "verdict": _verdict(None if fa_nonfit is None else fa_nonfit <= FALSE_ACCEPT_MAX),
    })

    zero_raw = []
    zero_nonfit = []
    for clip in FALSE_ACCEPT_ZERO_CLIPS:
        metrics = by_clip.get(clip)
        if metrics is None or metrics.get("frames_has_doc_0", 0) == 0:
            continue
        zero_raw.append(metrics["false_accept_any"])
        zero_nonfit.append(metrics["false_accept_nonfit"])
    if zero_raw:
        raw_ok = all(v == 0 for v in zero_raw)
        nonfit_ok = all(v == 0 for v in zero_nonfit)
        checks.append({
            "metric": f"false accept on clips {'/'.join(FALSE_ACCEPT_ZERO_CLIPS)}, any quad",
            "value": ", ".join(f"{clip}={by_clip[clip]['false_accept_any']} frames" for clip in FALSE_ACCEPT_ZERO_CLIPS if clip in by_clip),
            "threshold": "0 frames",
            "verdict": _verdict(raw_ok),
        })
        checks.append({
            "metric": f"false accept on clips {'/'.join(FALSE_ACCEPT_ZERO_CLIPS)}, after frame-fit rejection",
            "value": ", ".join(f"{clip}={by_clip[clip]['false_accept_nonfit']} frames" for clip in FALSE_ACCEPT_ZERO_CLIPS if clip in by_clip),
            "threshold": "0 frames",
            "verdict": _verdict(nonfit_ok),
        })
    else:
        checks.append({
            "metric": f"false accept on clips {'/'.join(FALSE_ACCEPT_ZERO_CLIPS)}",
            "value": "n/a (clips not labelled)",
            "threshold": "0 frames",
            "verdict": "n/a",
        })

    median = overall.get("corner_median")
    p90 = overall.get("corner_p90")
    checks.append({
        "metric": "corner error, median",
        "value": _pct(median),
        "threshold": f"< {CORNER_MEDIAN_MAX * 100:.0f}% of page diagonal",
        "verdict": _verdict(None if median is None else median < CORNER_MEDIAN_MAX),
    })
    checks.append({
        "metric": "corner error, p90",
        "value": _pct(p90),
        "threshold": f"< {CORNER_P90_MAX * 100:.0f}% of page diagonal",
        "verdict": _verdict(None if p90 is None else p90 < CORNER_P90_MAX),
    })

    verdicts = {check["verdict"] for check in checks}
    if "FAIL" in verdicts:
        overall_verdict = "FAIL"
    elif verdicts == {"n/a"}:
        overall_verdict = "n/a"
    elif "n/a" in verdicts:
        overall_verdict = "PASS (partial)"
    else:
        overall_verdict = "PASS"
    return checks, overall_verdict


# --------------------------------------------------------------------------- report


def _clip_sort_key(clip: str) -> Tuple[int, int, str]:
    if clip.isdigit():
        return (0, int(clip), clip)
    return (1, 0, clip)


def score_prediction(labels: Dict[Tuple[str, int], Label],
                     predictions: Dict[Tuple[str, int], Prediction],
                     prediction_name: str,
                     prediction_path: str,
                     malformed: int,
                     duplicates: int,
                     long_side: int,
                     iou_threshold: float) -> Dict[str, object]:
    # Every labelled frame is scored. A labelled frame with no prediction row is a miss
    # (counted per group), never silently dropped: dropping it would flatter recall.
    labelled = list(labels.keys())
    keys_by_clip: Dict[str, List[Tuple[str, int]]] = {}
    keys_by_category: Dict[str, List[Tuple[str, int]]] = {}
    for key in labelled:
        label = labels[key]
        keys_by_clip.setdefault(label.clip, []).append(key)
        keys_by_category.setdefault(label.category, []).append(key)

    overall = build_metrics(labels, predictions, labelled, long_side, iou_threshold)
    by_clip = {
        clip: build_metrics(labels, predictions, keys, long_side, iou_threshold)
        for clip, keys in sorted(keys_by_clip.items(), key=lambda item: _clip_sort_key(item[0]))
    }
    by_category = {
        category: build_metrics(labels, predictions, keys, long_side, iou_threshold)
        for category, keys in sorted(keys_by_category.items())
    }
    checks, verdict = evaluate_checks(overall, by_clip)

    return {
        "name": prediction_name,
        "file": os.path.abspath(prediction_path),
        "rows": len(predictions),
        "rows_malformed": malformed,
        "rows_duplicate": duplicates,
        "frames_scored": len(labelled),
        "frames_predicted_but_unlabelled": sum(1 for key in predictions if key not in labels),
        "jitter_by_clip": jitter_by_clip(predictions, labels),
        "overall": overall,
        "by_category": by_category,
        "by_clip": by_clip,
        "checks": checks,
        "verdict": verdict,
    }


def render_markdown(label_report: LoadReport, scored: List[Dict[str, object]],
                    long_side: int, iou_threshold: int, title: str,
                    notes: Sequence[str] = ()) -> str:
    lines: List[str] = []
    add = lines.append
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    add(f"# {title}")
    add("")
    add(f"Generated by `tools/scanner/score.py` on {generated}.")
    add("")
    if notes:
        add("Snapshot notes:")
        add("")
        for note in notes:
            add(f"* {note}")
        add("")
    add("## Inputs and what they mean")
    add("")
    add("Label files included (private, outside git, one CSV per labeller):")
    add("")
    add("| file | rows read | frames used | has_doc=1 | has_doc=0 | has_doc=2 | clips |")
    add("|---|---:|---:|---:|---:|---:|---|")
    for entry in label_report.per_file:
        clips = entry["clips"]
        shown = ", ".join(clips[:8]) + (f", ... (+{len(clips) - 8})" if len(clips) > 8 else "")
        add(f"| `{os.path.basename(entry['file'])}` | {entry['rows']} | {entry['frames']} | "
            f"{entry['has_doc'].get('1', 0)} | {entry['has_doc'].get('0', 0)} | {entry['has_doc'].get('2', 0)} | "
            f"{shown or '-'} |")
    add("")
    add(f"All files together: {label_report.frames} frames "
        f"(has_doc=1 {label_report.has_doc.get('1', 0)}, has_doc=0 {label_report.has_doc.get('0', 0)}, "
        f"has_doc=2 {label_report.has_doc.get('2', 0)}).")
    add("")
    labelers = ", ".join(label_report.labelers) if label_report.labelers else "unknown"
    add(f"Labellers: {labelers}. **The labels are model-made (vision-assisted), not human-certified:** "
        "the label spec quotes roughly +/-1% of the frame diagonal on well-contrasted frames and worse on the "
        "`confidence=low` ones, so every corner number below inherits about 1% label noise. Treat a corner "
        "error within ~1% of the thresholds as undecided rather than as a pass.")
    add("")
    if label_report.duplicates or label_report.malformed or label_report.warnings:
        add("Load notes: " + "; ".join(
            [f"{label_report.duplicates} duplicate frame(s) ignored (first file wins)" if label_report.duplicates else "",
             f"{label_report.malformed} malformed row(s) skipped" if label_report.malformed else ""]
            + label_report.warnings[:5]
        ).strip("; "))
        add("")
    add("Metric definitions used here:")
    add("")
    add(f"* `returned a quad` = `has_doc=1` frames whose prediction says `has_quad=1`. The old 95.5%-style "
        "number; it does not mean the page was found.")
    add(f"* `doc recall` = `has_doc=1` frames **with corners** whose predicted quad has a rasterised polygon "
        f"IoU >= {iou_threshold / 100:.2f} with the labelled page (masks at {long_side} px on the long side).")
    add("* `false accept` = `has_doc=0` frames that got a quad, counted twice: any quad, and after the app's "
    "frame-fit rejection (every corner within 5% of a border = the frame, not a page).")
    add("* `corner error` = mean distance of the 4 predicted corners to the 4 labelled corners (best cyclic "
        "order, winding preserved) / the labelled page diagonal (mean of the two diagonals); median and p90 "
        "over frames with IoU > 0, split by the **label's** confidence.")
    add("* `jitter` = mean corner step between consecutive predicted frames of a clip / frame diagonal, the "
        "same definition as `BENCH_detectQuad.md`; computed from the prediction file, so it covers unlabelled frames.")
    add(f"* `has_doc=2` (document present, fewer than 3 corners visible) is excluded from recall and false "
        "accept and counted in its own column.")
    add("")

    for entry in scored:
        overall = entry["overall"]
        add(f"## {entry['name']}")
        add("")
        add(f"Predictions: `{os.path.basename(entry['file'])}` -- {entry['rows']} rows, "
            f"{entry['frames_scored']} scored against labels, "
            f"{entry['frames_predicted_but_unlabelled']} predicted frames without a label, "
            f"{entry['rows_malformed']} malformed row(s).")
        add("")
        add(f"**Verdict: {entry['verdict']}**")
        add("")
        add("| check | value | threshold | verdict |")
        add("|---|---|---|---|")
        for check in entry["checks"]:
            add(f"| {check['metric']} | {check['value']} | {check['threshold']} | **{check['verdict']}** |")
        add("")
        add("### Overall")
        add("")
        add("| metric | value |")
        add("|---|---:|")
        add(f"| labelled frames scored | {overall['frames_labelled']} |")
        add(f"| has_doc=1 frames | {overall['frames_has_doc_1']} |")
        add(f"| ... of which with corners (recall denominator) | {overall['frames_has_doc_1_with_corners']} |")
        add(f"| ... of which corners omitted by the labeller | {overall['frames_has_doc_1_corners_omitted']} |")
        add(f"| has_doc=0 frames | {overall['frames_has_doc_0']} |")
        add(f"| has_doc=2 frames (excluded) | {overall['frames_has_doc_2_excluded']} |")
        add(f"| returned a quad | {_pct(overall['detection_rate'])} ({overall['returned_quad_doc1']}/{overall['frames_has_doc_1']}) |")
        add(f"| doc recall (IoU >= {iou_threshold / 100:.2f}) | {_pct(overall['recall'])} ({overall['matched_iou']}/{overall['frames_has_doc_1_with_corners']}) |")
        add(f"| false accept, any quad | {_pct(overall['false_accept_any_rate'])} ({overall['false_accept_any']}/{overall['frames_has_doc_0']}) |")
        add(f"| false accept, after frame-fit rejection | {_pct(overall['false_accept_nonfit_rate'])} ({overall['false_accept_nonfit']}/{overall['frames_has_doc_0']}) |")
        add(f"| corner error median | {_pct(overall['corner_median'])} of page diagonal (n={overall['corner_frames']}) |")
        add(f"| corner error p90 | {_pct(overall['corner_p90'])} of page diagonal |")
        add(f"| mirror-suspect quads (reversed order matched better) | {overall['mirror_suspects']} |")
        if overall.get("frames_missing_prediction"):
            add(f"| labelled frames with no prediction row (counted as no quad) | {overall['frames_missing_prediction']} |")
        add("")

        add("### Corner error by label confidence")
        add("")
        add("| label confidence | frames | median (% diag) | p90 (% diag) |")
        add("|---|---:|---:|---:|")
        add(f"| high/med | {overall['corner_frames_conf_high_med']} | {_pct(overall['corner_median_conf_high_med'])} | {_pct(overall['corner_p90_conf_high_med'])} |")
        add(f"| low | {overall['corner_frames_conf_low']} | {_pct(overall['corner_median_conf_low'])} | {_pct(overall['corner_p90_conf_low'])} |")
        add("")

        add("### Per category")
        add("")
        add("| category | frames | doc=1 frames | doc=2 (excluded) | returned a quad | doc recall | false accept (any) | false accept (non-frame-fit) | corner median | corner p90 |")
        add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for category, metrics in entry["by_category"].items():
            add(f"| {category} | {metrics['frames_labelled']} | {metrics['frames_has_doc_1']} | {metrics['frames_has_doc_2_excluded']} | "
                f"{_pct(metrics['detection_rate'])} | {_pct(metrics['recall'])} | "
                f"{_pct(metrics['false_accept_any_rate'])} ({metrics['false_accept_any']}) | "
                f"{_pct(metrics['false_accept_nonfit_rate'])} ({metrics['false_accept_nonfit']}) | "
                f"{_pct(metrics['corner_median'])} | {_pct(metrics['corner_p90'])} |")
        add("")

        add("### Per clip")
        add("")
        add("| clip | labeler | frames | doc=1 (w/ corners) | doc=2 (excluded) | no-doc frames | returned a quad | doc recall | false accept (any) | false accept (non-frame-fit) | corner median | corner p90 | jitter (diag) |")
        add("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        jitter = entry["jitter_by_clip"]
        for clip, metrics in entry["by_clip"].items():
            jitter_text = _ratio(jitter.get(clip))
            add(f"| {clip} | {', '.join(metrics.get('labelers') or []) or '-'} | {metrics['frames_labelled']} | {metrics['frames_has_doc_1']} "
                f"({metrics['frames_has_doc_1_with_corners']}) | {metrics['frames_has_doc_2_excluded']} | {metrics['frames_has_doc_0']} | "
                f"{_pct(metrics['detection_rate'])} | {_pct(metrics['recall'])} | "
                f"{_pct(metrics['false_accept_any_rate'])} ({metrics['false_accept_any']}) | "
                f"{_pct(metrics['false_accept_nonfit_rate'])} ({metrics['false_accept_nonfit']}) | "
                f"{_pct(metrics['corner_median'])} | {_pct(metrics['corner_p90'])} | {jitter_text} |")
        add("")
        add("Jitter has no pass threshold in `SCANNER_EVIDENCE.md` for this label set (the reference "
            "figure is < 0.01 of the diagonal on the still clips 02/05/07/17), so it is reported, not judged.")
        add("")

    return "\n".join(lines) + "\n"


def build_json(label_report: LoadReport, scored: List[Dict[str, object]],
               long_side: int, iou_threshold: float, title: str,
               notes: Sequence[str] = ()) -> Dict[str, object]:
    return {
        "title": title,
        "notes": list(notes),
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "settings": {
            "mask_long_side": long_side,
            "iou_threshold": iou_threshold,
            "frame_fit_margin": FRAME_FIT_MARGIN,
            "recall_aggregate_min": RECALL_AGGREGATE_MIN,
            "recall_per_clip_min": RECALL_PER_CLIP_MIN,
            "false_accept_max": FALSE_ACCEPT_MAX,
            "false_accept_zero_clips": list(FALSE_ACCEPT_ZERO_CLIPS),
            "corner_median_max": CORNER_MEDIAN_MAX,
            "corner_p90_max": CORNER_P90_MAX,
        },
        "labels": {
            "files": label_report.files,
            "per_file": label_report.per_file,
            "rows": label_report.rows,
            "frames": label_report.frames,
            "has_doc": label_report.has_doc,
            "labelers": label_report.labelers,
            "duplicates": label_report.duplicates,
            "malformed": label_report.malformed,
            "warnings": label_report.warnings,
        },
        "predictions": {entry["name"]: entry for entry in scored},
    }


# ------------------------------------------------------------------------------ cli


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Score page-corner predictions against the scanner ground truth.")
    parser.add_argument("--labels", action="append", required=True,
                        help="label CSV (repeatable; later files do not overwrite earlier rows)")
    parser.add_argument("--pred", action="append", required=True, dest="predictions",
                        help="predictions CSV (repeatable; the report gets one section per file)")
    parser.add_argument("--out-md", default="", help="markdown report path")
    parser.add_argument("--out-json", default="", help="JSON report path")
    parser.add_argument("--mask-long-side", type=int, default=MASK_LONG_SIDE)
    parser.add_argument("--iou-threshold", type=float, default=IOU_THRESHOLD)
    parser.add_argument("--title", default="Scanner baseline: corner detector vs labelled frames")
    parser.add_argument("--note", action="append", default=[],
                        help="snapshot caveat rendered under the title (repeatable)")
    args = parser.parse_args(argv)

    if args.mask_long_side < 64:
        print("score: --mask-long-side must be >= 64", file=sys.stderr)
        return 2
    if not 0.0 < args.iou_threshold <= 1.0:
        print("score: --iou-threshold must be in (0, 1]", file=sys.stderr)
        return 2

    label_report = LoadReport()
    labels = load_labels(args.labels, label_report)
    if not labels:
        print("score: no usable label rows; nothing to score", file=sys.stderr)
        return 2

    scored: List[Dict[str, object]] = []
    for path in args.predictions:
        if not os.path.isfile(path):
            print(f"score: predictions file not found: {path}", file=sys.stderr)
            return 2
        predictions, malformed, duplicates = load_predictions(path)
        name = os.path.splitext(os.path.basename(path))[0]
        scored.append(score_prediction(
            labels, predictions, name, path, malformed, duplicates,
            args.mask_long_side, args.iou_threshold,
        ))

    markdown = render_markdown(label_report, scored, args.mask_long_side, int(args.iou_threshold * 100),
                               args.title, args.note)
    payload = build_json(label_report, scored, args.mask_long_side, args.iou_threshold, args.title, args.note)

    if args.out_md:
        os.makedirs(os.path.dirname(os.path.abspath(args.out_md)), exist_ok=True)
        with open(args.out_md, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(markdown)
        print(f"wrote {args.out_md}")
    if args.out_json:
        os.makedirs(os.path.dirname(os.path.abspath(args.out_json)), exist_ok=True)
        with open(args.out_json, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, indent=2, sort_keys=False)
            handle.write("\n")
        print(f"wrote {args.out_json}")
    if not args.out_md and not args.out_json:
        print(markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
