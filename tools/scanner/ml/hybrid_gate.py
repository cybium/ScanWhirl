"""Hybrid gate: the trained mask decides the quad, Scanner X r2's screen cues can veto it.

v1's failure was not finding pages - it was accepting screens and clutter. The mask head has
no explicit "is this emissive glass" concept, while the r2 prototype has hand-written screen
cues (blue-white interior, dark bezel just outside the quad, keyboard-like structure below).
This module exposes *only* that risk score, evaluated on the trained model's quad, so it can
be used as a veto without importing r2's detector decision:

    final = trained_quad if screen_risk < threshold else no page

The threshold is chosen on VAL + the VAL-NEG proxy (never TEST). The veto can only *remove*
a quad, so it cannot invent a page; the cost of a wrong veto is a missed auto-crop.

Kept dependency-light on purpose: r2 is imported lazily from ``tools/scanner/proto`` and the
function degrades to "no risk" (0.0) if it is unavailable, which leaves the trained model's
behaviour unchanged rather than silently rejecting everything.
"""

from __future__ import annotations

import os
import sys
from typing import Optional, Sequence

import numpy as np
from PIL import Image

WORK_SIDE = 640

__all__ = ["ScreenVeto", "quad_to_work"]


def quad_to_work(quad: Sequence[Sequence[float]], src_w: int, src_h: int,
                 work_w: int, work_h: int) -> list[tuple[float, float]]:
    sx = work_w / float(src_w)
    sy = work_h / float(src_h)
    return [(float(x) * sx, float(y) * sy) for x, y in quad]


class ScreenVeto:
    """Frame-level screen risk evaluated inside a given quad (r2's screen cues only)."""

    def __init__(self, work_side: int = WORK_SIDE):
        self.work_side = int(work_side)
        self._r2 = None
        self._cfg = None
        self.available = self._load()

    def _load(self) -> bool:
        here = os.path.dirname(os.path.abspath(__file__))
        proto = os.path.abspath(os.path.join(here, "..", "proto"))
        if proto not in sys.path:
            sys.path.insert(0, proto)
        try:
            import scannerx_r2 as sx2  # noqa: PLC0415
        except Exception:
            return False
        self._r2 = sx2
        self._cfg = sx2.ConfigR2()
        return True

    def risk(self, rgb: np.ndarray, quad_source: Sequence[Sequence[float]]) -> dict:
        """Return r2's screen risk for ``quad_source`` (source pixels), plus raw cues."""
        if not self.available or quad_source is None:
            return {"screen_risk": 0.0, "available": self.available}
        sx2 = self._r2
        height, width = rgb.shape[:2]
        factor = self.work_side / float(max(width, height))
        work_w = max(8, int(round(width * factor)))
        work_h = max(8, int(round(height * factor)))
        small = Image.fromarray(rgb).resize((work_w, work_h), Image.BILINEAR)
        work_rgb = np.asarray(small, dtype=np.float32) / 255.0
        _score, lightness, _chroma, _info = sx2.paper_map(work_rgb, self._cfg)
        hf = sx2.hf_map(lightness, 2)
        quad_work = quad_to_work(quad_source, width, height, work_w, work_h)
        # keep the quad inside the work grid: r2's polygon_mask clips, but a corner far
        # outside would make the "just outside the paper" ring meaningless
        quad_work = [(min(max(x, 0.0), work_w - 1.0), min(max(y, 0.0), work_h - 1.0))
                     for x, y in quad_work]
        try:
            feats = sx2.screen_features(lightness, work_rgb, hf, quad_work, self._cfg)
        except Exception:
            return {"screen_risk": 0.0, "available": self.available, "error": True}
        out = {key: float(value) for key, value in feats.items()}
        out["available"] = True
        return out
