"""Render one frame with the prototype's mask and quad, for private visual checking.

Writes PNG/JPG overlays into a local folder only (never into git): the recorded frames
are private. Usage:

    python tools/scanner/proto/debug_overlay.py --frame <jpg> --out <png> [--label x1,y1,...]
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scannerx as sx  # noqa: E402


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--frame", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--label", default="", help="8 comma separated numbers x1,y1,...,x4,y4")
    parser.add_argument("--width", type=int, default=640)
    args = parser.parse_args(argv)

    det = sx.detect_path(args.frame)
    with Image.open(args.frame) as img:
        img = img.convert("RGB")
        src_w, src_h = img.size
        f = args.width / float(max(src_w, src_h))
        canvas = img.resize((int(src_w * f), int(src_h * f)), Image.LANCZOS)
    draw = ImageDraw.Draw(canvas)
    if det.quad:
        pts = [(x * f, y * f) for x, y in det.quad]
        pts.append(pts[0])
        draw.line(pts, fill=(255, 0, 0), width=3)
        for i, (x, y) in enumerate(pts[:-1]):
            draw.text((x + 4, y + 4), str(i + 1), fill=(255, 0, 0))
    if args.label:
        vals = [float(v) for v in args.label.split(",")]
        pts = [(vals[i] * f, vals[i + 1] * f) for i in range(0, 8, 2)]
        pts.append(pts[0])
        draw.line(pts, fill=(0, 220, 0), width=2)
        draw.text((8, 8), f"conf={det.confidence:.3f}", fill=(255, 255, 0))
    canvas.save(args.out, quality=88)
    print(os.path.abspath(args.out), "conf=%.3f" % det.confidence)
    print({k: round(v, 3) for k, v in det.features.items()})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
