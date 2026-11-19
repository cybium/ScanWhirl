"""Build the visual report for a trained run: 4-panel galleries + an offline index.html.

For each selected frame the tile is

    original | ground-truth outline | Scanner X r2 (accept 0.55) | trained model

and the caption says what happened, including ``no page`` when the model refused. The
HTML index is self-contained (relative ``<img>`` tags only, no CSS/JS/font from the
network) so it can be opened offline and survives being copied.

Selection is deterministic and deliberate: TEST frames are stratified by category and
**failures come first** (miss below IoU 0.80, a refusal on a labelled page, or a false
accept), because a gallery of wins is not evidence. The 9 third-party web stills are
always included when their predictions exist (lead notice 2026-11-19: they count against
the same evaluation budget as TEST).

Usage:
    python report_gallery.py --run-id ml003_v1 --max 30
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import os
import sys
from collections import defaultdict
from pathlib import Path
from typing import Optional

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import data_ml

PANEL = 300          # panel height in pixels
CAPTION = 34
PAD = 6
COLOR_GT = (60, 220, 90)
COLOR_R2 = (70, 150, 255)
COLOR_ML = (240, 90, 200)
COLOR_MISS = (240, 60, 60)


def quad_of(row: dict) -> Optional[list[tuple[float, float]]]:
    try:
        values = [float(row[k]) for k in ("x1", "y1", "x2", "y2", "x3", "y3", "x4", "y4")]
    except (KeyError, TypeError, ValueError):
        return None
    return [(values[i], values[i + 1]) for i in range(0, 8, 2)]


def read_predictions(path: Path) -> dict:
    out = {}
    if not path.is_file():
        return out
    with open(path, encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            key = (row["clip"].strip(), int(float(row["frame"])))
            quad = quad_of(row) if str(row.get("has_quad")) == "1" else None
            out[key] = quad
    return out


def read_labels(path: Path) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            quad = quad_of(row) if row.get("has_doc") == "1" else None
            rows.append({
                "clip": row["clip"].strip(),
                "frame": int(float(row["frame"])),
                "file": row.get("file", ""),
                "width": float(row["width"]),
                "height": float(row["height"]),
                "has_doc": int(row["has_doc"]),
                "category": row.get("category", "Unknown"),
                "confidence": row.get("confidence", ""),
                "quad": quad,
            })
    return rows


def polygon_iou(a, b, width, height, long_side=512) -> float:
    """Same convention as score.py: rasterise both on a long-side-512 grid."""
    from PIL import ImageChops, ImageDraw, ImageStat

    def raster(quad):
        scale = float(long_side) / float(max(width, height))
        mask = Image.new("L", (max(2, int(round(width * scale))), max(2, int(round(height * scale)))), 0)
        ImageDraw.Draw(mask).polygon([(x * scale, y * scale) for x, y in quad], fill=255)
        return mask

    ma, mb = raster(a), raster(b)
    intersection = ImageStat.Stat(ImageChops.multiply(ma, mb)).sum[0] / 255.0
    union = ImageStat.Stat(ImageChops.lighter(ma, mb)).sum[0] / 255.0
    return intersection / union if union > 0 else 0.0


def draw_quad(draw: ImageDraw.ImageDraw, quad, scale: float, offset, colour, width=3):
    points = [(offset[0] + x * scale, offset[1] + y * scale) for x, y in quad]
    # dark underlay so a light-green outline stays visible on a white page
    draw.line(points + [points[0]], fill=(0, 0, 0), width=width + 3)
    draw.line(points + [points[0]], fill=colour, width=width)
    for x, y in points:
        draw.ellipse([x - 4, y - 4, x + 4, y + 4], fill=(0, 0, 0))
        draw.ellipse([x - 2.5, y - 2.5, x + 2.5, y + 2.5], fill=colour)


def make_tile(entry: dict, out_path: Path) -> None:
    with Image.open(entry["path"]) as img:
        img = img.convert("RGB")
        scale = PANEL / float(img.height)
        panel_w = max(1, int(round(img.width * scale)))
        thumb = img.resize((panel_w, PANEL), Image.BILINEAR)

    panels = []
    for name, colour in (("original", None), ("ground truth", COLOR_GT),
                         ("r2 (accept 0.55)", COLOR_R2), ("trained model", COLOR_ML)):
        panel = Image.new("RGB", (panel_w, PANEL), (18, 18, 20))
        panel.paste(thumb, (0, 0))
        draw = ImageDraw.Draw(panel)
        if name == "ground truth":
            if entry["truth"] is not None:
                draw_quad(draw, entry["truth"], scale, (0, 0), COLOR_GT)
            else:
                draw.text((6, 6), f"no page (has_doc={entry['has_doc']})", fill=(240, 240, 240))
        elif name == "r2 (accept 0.55)":
            if entry["r2"] is not None:
                draw_quad(draw, entry["r2"], scale, (0, 0), COLOR_R2)
            else:
                draw.rectangle([0, 0, 74, 20], fill=(0, 0, 0))
                draw.text((5, 5), "no page", fill=(255, 255, 255))
        elif name == "trained model":
            if entry["ml"] is not None:
                draw_quad(draw, entry["ml"], scale, (0, 0), COLOR_ML)
            else:
                draw.rectangle([0, 0, 74, 20], fill=(0, 0, 0))
                draw.text((5, 5), "no page", fill=(255, 255, 255))
        panel = panel.resize((PANEL, PANEL), Image.BILINEAR)
        panels.append((name, panel, colour))

    width = PANEL * 4 + PAD * 5
    height = PANEL + CAPTION + PAD
    tile = Image.new("RGB", (width, height), (26, 26, 30))
    draw = ImageDraw.Draw(tile)
    for index, (name, panel, colour) in enumerate(panels):
        x = PAD + index * (PANEL + PAD)
        tile.paste(panel, (x, PAD))
        draw.rectangle([x, PAD, x + PANEL - 1, PAD + PANEL - 1], outline=(70, 70, 78))
        draw.rectangle([x + 1, PAD + 1, x + 4 + 6 * len(name), PAD + 17], fill=(10, 10, 12))
        draw.text((x + 4, PAD + 4), name, fill=margin_text(colour), anchor="lt")
        if colour:
            draw.rectangle([x + 2, PAD + 19, x + 12, PAD + 29], outline=colour)

    caption = entry["caption"]
    draw.text((PAD + 2, PANEL + PAD + 6), caption, fill=(235, 235, 235))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tile.save(out_path)


def margin_text(colour) -> tuple[int, int, int]:
    if colour is None:
        return (255, 255, 255)
    return tuple(min(255, c + 80) for c in colour)


def caption_for(entry: dict) -> str:
    pieces = [f"{entry['clip']}/{entry['frame']}", entry["category"], f"has_doc={entry['has_doc']}"]
    if entry["has_doc"] == 2:
        pieces.append("partial page (excluded from recall/false-accept)")
        pieces.append("model: quad" if entry["ml"] is not None else "model: no page")
        pieces.append("r2: quad" if entry["r2"] is not None else "r2: no page")
        return " | ".join(pieces)
    if entry["truth"] is not None:
        pieces.append(f"model IoU {entry['ml_iou']:.3f}" if entry["ml_iou"] is not None
                      else "model: no page")
        pieces.append(f"r2 IoU {entry['r2_iou']:.3f}" if entry["r2_iou"] is not None
                      else "r2: no page")
        if entry["ml_iou"] is not None and entry["ml_iou"] < 0.8:
            pieces.append("MISS")
    elif entry["has_doc"] == 1:
        # presence-only label: the page is there but the geometry was not labelled,
        # so there is nothing to score -- say so instead of calling it a false accept
        pieces.append("geometry not labelled")
        pieces.append("model: quad (unscored)" if entry["ml"] is not None else "model: no page")
        pieces.append("r2: quad" if entry["r2"] is not None else "r2: no page")
    else:
        pieces.append("model FALSE ACCEPT" if entry["ml"] is not None else "model: no page (correct)")
        pieces.append("r2 FALSE ACCEPT" if entry["r2"] is not None else "r2: no page (correct)")
    if entry["confidence"]:
        pieces.append(f"label conf {entry['confidence']}")
    return " | ".join(pieces)


def select(frames: list[dict], targets: dict, quota: int, failure_share: float = 0.65) -> list[dict]:
    """Failures first (up to ``failure_share`` of the quota), then a stratified sample.

    A gallery of failures only is as misleading as a gallery of wins: the point is to show
    both what works and what does not, per category.
    """
    seen = set()
    chosen: list[dict] = []
    failure_quota = max(1, int(round(quota * failure_share)))

    def take(entry):
        key = (entry["clip"], entry["frame"])
        if key in seen:
            return
        seen.add(key)
        chosen.append(entry)

    failures = [f for f in frames if f["is_failure"]]
    by_category: dict[str, list[dict]] = defaultdict(list)
    for frame in failures:
        by_category[frame["category"]].append(frame)
    # round-robin the failures across categories so one bad clip cannot fill the gallery
    queues = {key: list(value) for key, value in by_category.items()}
    while len(chosen) < failure_quota and any(queues.values()):
        for key in sorted(queues):
            if queues[key] and len(chosen) < failure_quota:
                take(queues[key].pop(0))

    # Prefer frames with a ground-truth quad for the "pass" half: a presence-only frame
    # (geometry not labelled) cannot show how good the corner is, so it is a poor tile.
    remaining = [f for f in frames if f["is_failure"] is False and f["truth"] is not None]
    remaining += [f for f in frames if f["is_failure"] is False and f["truth"] is None]
    by_category = defaultdict(list)
    for frame in remaining:
        by_category[frame["category"]].append(frame)
    while len(chosen) < quota and any(by_category.values()):
        for key in sorted(by_category):
            if by_category[key] and len(chosen) < quota:
                take(by_category[key].pop(0))
    return chosen


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--split", default="test")
    parser.add_argument("--max", type=int, default=30)
    parser.add_argument("--include-web", action="store_true", default=True)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args(argv)

    run_dir = data_ml.RUNS_DIR / args.run_id
    gallery = run_dir / "gallery"
    gallery.mkdir(parents=True, exist_ok=True)

    label_sets = [(args.split, run_dir / f"labels_{args.split}.csv")]
    if args.include_web:
        web_labels = run_dir / "labels_web.csv"
        if web_labels.is_file():
            label_sets.append(("web", web_labels))

    entries: list[dict] = []
    for split, labels_path in label_sets:
        if not labels_path.is_file():
            print(f"missing {labels_path}, skipped")
            continue
        pred_ml = read_predictions(run_dir / f"pred_{split}_ml.csv")
        pred_r2 = read_predictions(run_dir / f"pred_{split}_r2.csv")
        pred_shipped = read_predictions(run_dir / f"pred_{split}_shipped.csv")
        for row in read_labels(labels_path):
            key = (row["clip"], row["frame"])
            path = None
            if split == "web":
                path = data_ml.STILLS_DIR / os.path.basename(row["file"])
            else:
                candidate = data_ml.OWN_DIR / "images" / row["clip"] / f"{row['frame']}.png"
                path = candidate
            if path is None or not path.is_file():
                continue
            ml_quad = pred_ml.get(key)
            r2_quad = pred_r2.get(key)
            truth = row["quad"]
            ml_iou = polygon_iou(ml_quad, truth, row["width"], row["height"]) if (ml_quad and truth) else None
            r2_iou = polygon_iou(r2_quad, truth, row["width"], row["height"]) if (r2_quad and truth) else None
            if truth is None:
                # presence-only positive frames carry no trustworthy quad: no IoU, and
                # "returned a quad" is not a failure there. Only has_doc=0 can false-accept.
                is_failure = row["has_doc"] == 0 and (ml_quad is not None or r2_quad is not None)
            else:
                is_failure = ml_quad is None or (ml_iou is not None and ml_iou < 0.8)
            entries.append({
                "split": split, "clip": row["clip"], "frame": row["frame"], "path": str(path),
                "truth": truth, "ml": ml_quad, "r2": r2_quad,
                "shipped": pred_shipped.get(key),
                "ml_iou": ml_iou, "r2_iou": r2_iou,
                "has_doc": row["has_doc"], "category": row["category"],
                "confidence": row["confidence"], "is_failure": is_failure,
            })

    test_entries = [e for e in entries if e["split"] == args.split]
    web_entries = [e for e in entries if e["split"] == "web"]
    selected = select(test_entries, {}, args.max) if test_entries else []
    selected += web_entries  # lead notice: the web stills belong in every TEST gallery
    for entry in selected:
        entry["caption"] = caption_for(entry)

    for index, entry in enumerate(selected):
        name = f"tile_{index:02d}.png"
        entry["tile"] = name
        make_tile(entry, gallery / name)
        print(f"  tile {name}: {entry['caption']}")

    _write_html(gallery / "index.html", args.run_id, selected, test_entries, web_entries)
    _write_csv(gallery / "gallery_index.csv", selected)
    print(f"gallery: {gallery / 'index.html'} ({len(selected)} tiles)")
    return 0


def _esc(text) -> str:
    return html.escape(str(text), quote=True)


def _write_html(path: Path, run_id: str, selected: list[dict], test_entries: list[dict],
                web_entries: list[dict]) -> None:
    rows = []
    for entry in selected:
        iou_ml = "n/a" if entry["ml_iou"] is None else f"{entry['ml_iou']:.3f}"
        iou_r2 = "n/a" if entry["r2_iou"] is None else f"{entry['r2_iou']:.3f}"
        kind = "failure" if entry["is_failure"] else "pass"
        rows.append(
            f'<figure class="{kind}">'
            f'<a href="{_esc(entry["tile"])}"><img src="{_esc(entry["tile"])}" '
            f'alt="{_esc(entry["clip"])}/{_esc(entry["frame"])}" loading="lazy"></a>'
            f'<figcaption><b>{_esc(entry["clip"])}/{_esc(entry["frame"])}</b> '
            f'({_esc(entry["category"])}, has_doc={entry["has_doc"]})<br>'
            f'model IoU <b>{iou_ml}</b> &middot; r2 IoU {iou_r2}'
            f'{" &middot; <span class=miss>FAILURE</span>" if entry["is_failure"] else ""}'
            f'</figcaption></figure>')

    def totals(entries, key):
        scores = [e[key] for e in entries if e.get(key) is not None]
        if not scores:
            return "n/a"
        return f"{sum(1 for s in scores if s >= 0.8)}/{len(scores)} at IoU >= 0.80 (mean {sum(scores) / len(scores):.3f})"

    page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Scanner X ML {_esc(run_id)} -- visual report</title>
<style>
 body {{ background:#141418; color:#e8e8ec; font:14px/1.45 system-ui, sans-serif; margin:0; padding:24px; }}
 h1 {{ font-size:19px; margin:0 0 6px; }}
 p.meta {{ color:#b8b8c4; margin:0 0 18px; max-width:960px; }}
 figure {{ margin:0 0 22px; background:#1d1d23; border:1px solid #2c2c34; border-radius:6px; padding:8px; }}
 figure.failure {{ border-color:#7a2d2d; }}
 img {{ width:100%; height:auto; display:block; border-radius:3px; }}
 figcaption {{ color:#c8c8d2; padding-top:6px; }}
 .miss {{ color:#ff6b6b; font-weight:700; }}
 .legend span {{ display:inline-block; margin-right:16px; }}
 .sw {{ display:inline-block; width:10px; height:10px; margin-right:4px; vertical-align:middle; }}
</style>
</head>
<body>
<h1>Scanner X ML -- {_esc(run_id)}</h1>
<p class="meta">Panels, left to right: the frame as the model sees it &middot; ground-truth outline
(green) &middot; Scanner X r2 at accept 0.55 (blue) &middot; the trained model (magenta, or
"no page" when it refuses). "No page" is a correct answer on has_doc=0 frames.
Failures are listed first in the selection; the nine third-party web stills are appended.</p>
<p class="legend"><span><i class="sw" style="background:#3cdc5a"></i>ground truth</span>
<span><i class="sw" style="background:#4696ff"></i>r2</span>
<span><i class="sw" style="background:#f05ac8"></i>trained model</span></p>
<p class="meta">Trained model: {totals(test_entries, "ml_iou")} on the TEST selection &middot;
{sum(1 for e in test_entries if e["is_failure"])} frames flagged as failures in total.<br>
r2: {totals(test_entries, "r2_iou")}. Web stills: model {totals(web_entries, "ml_iou")},
r2 {totals(web_entries, "r2_iou")}.</p>
{chr(10).join(rows)}
</body>
</html>
"""
    path.write_text(page, encoding="utf-8")


def _write_csv(path: Path, selected: list[dict]) -> None:
    columns = ["split", "clip", "frame", "category", "has_doc", "confidence", "tile",
               "ml_iou", "r2_iou", "is_failure", "caption"]
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        for entry in selected:
            writer.writerow(entry)


if __name__ == "__main__":
    sys.exit(main())
