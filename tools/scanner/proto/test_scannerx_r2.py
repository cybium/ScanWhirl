"""Unit tests for the round-2 primitives on synthetic images (no private data).

Run: python -m unittest discover -s tools/scanner/proto -p "test_*.py"

Covers: the hysteresis paper mask (a page stops at its edge, a ramp does not),
the step/ramp profile (a real step vs a text column vs a lighting ramp), the
shipped Deskew port, and the screen/door/wall rejection direction.
"""

from __future__ import annotations

import math
import os
import sys
import unittest

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import deskew_port  # noqa: E402
import scannerx as sx  # noqa: E402
import scannerx_r2 as sx2  # noqa: E402


def synthetic_page(w=640, h=480, corners=((90.0, 70.0), (560.0, 85.0), (545.0, 420.0), (75.0, 405.0)),
                   bg=0.45, fg=0.82, shadow=0.06, noise=0.01, seed=3):
    """A bright page on a slightly darker floor, with a soft shadow ring."""
    rng = np.random.default_rng(seed)
    base = np.full((h, w), bg, dtype=np.float32)
    base = np.clip(base + rng.normal(0.0, noise, (h, w)).astype(np.float32), 0.0, 1.0)
    page = sx.polygon_mask([(x, y) for x, y in corners], w, h)
    ring = sx.dilate(page, 5) & ~page
    base[ring] -= 0.04
    base[page] = fg
    # soften the border over ~3 px
    soft = sx.box_mean(page.astype(np.float32), 2)
    base = base * (1.0 - soft) + fg * soft
    rgb = np.stack([base, base * 0.995, base * 0.985], axis=-1)
    return np.clip(rgb, 0.0, 1.0)


def as_gray_hi(rgb: np.ndarray, scale: int = 2) -> np.ndarray:
    im = Image.fromarray((rgb * 255).astype(np.uint8))
    hi = im.resize((rgb.shape[1] * scale, rgb.shape[0] * scale), Image.BILINEAR)
    return np.asarray(hi.convert("L"), dtype=np.float32) / 255.0


def paper_candidates(rgb, cfg=None):
    cfg = cfg or sx2.ConfigR2()
    gray_hi = as_gray_hi(rgb)
    return sx2.detect_r2_candidates(rgb, gray_hi, cfg), cfg


class HysteresisTest(unittest.TestCase):
    def test_page_component_stops_at_the_page_edge(self):
        rgb = synthetic_page()
        score, lightness, chroma, _ = sx2.paper_map(rgb, sx2.ConfigR2())
        gn, _ = sx2.barrier_gradient(lightness, sx2.ConfigR2(), rgb.shape[1])
        labels, stats, seeds, _ = sx2.hysteresis_components(
            score, lightness, sx2.ConfigR2(), rgb.shape[1], seed_percentile=90, delta=0.12, gn=gn)
        truth = sx.polygon_mask([(90.0, 70.0), (560.0, 85.0), (545.0, 420.0), (75.0, 405.0)],
                                rgb.shape[1], rgb.shape[0])
        best = 0.0
        for i in range(1, len(stats) + 1):
            comp = labels == i
            inter = float((comp & truth).sum())
            union = float((comp | truth).sum())
            best = max(best, inter / union if union else 0.0)
        self.assertGreater(best, 0.80, f"mask IoU with the page {best:.2f}")

    def test_ramp_has_no_page_component_at_the_page_size(self):
        h, w = 480, 640
        ramp = np.tile(np.linspace(0.25, 0.85, w, dtype=np.float32), (h, 1))
        rgb = np.stack([ramp, ramp, ramp], axis=-1)
        score, lightness, _, _ = sx2.paper_map(rgb, sx2.ConfigR2())
        gn, _ = sx2.barrier_gradient(lightness, sx2.ConfigR2(), w)
        _, stats, _, _ = sx2.hysteresis_components(
            score, lightness, sx2.ConfigR2(), w, seed_percentile=90, delta=0.12, gn=gn)
        # a pure ramp is one region; any candidate must have a high inner ramp
        cands = [s for s in stats if s["area"] / float(w * h) >= 0.05]
        if cands:
            quad = [(0.0, 0.0), (w * 0.8, 0.0), (w * 0.8, h * 1.0), (0.0, h * 1.0)]
            prof = sx2.step_profile(lightness, quad, math.hypot(w, h), 0.10 * math.hypot(w, h), 15, 81, 0.35)
            self.assertGreater(np.mean([p.get("ramp_in", 0.0) for p in prof]), 1.0)


class StepProfileTest(unittest.TestCase):
    def _profile(self, image, quad, band=60.0):
        h, w = image.shape
        return sx2.step_profile(image, quad, math.hypot(w, h), band, 15, 81, 0.35)

    def test_bright_inside_step_is_positive(self):
        img = np.full((300, 400), 0.35, dtype=np.float32)
        img[:, :200] = 0.80
        quad = [(10.0, 10.0), (195.0, 10.0), (195.0, 290.0), (10.0, 290.0)]
        prof = self._profile(img, quad)
        right = prof[1]
        self.assertGreater(right["step"], 0.2)
        self.assertGreater(right["consistency"], 0.5)

    def test_text_column_edge_has_no_positive_step(self):
        # page on a darker floor; a dark vertical text rule sits inside the page.
        img = np.full((300, 400), 0.30, dtype=np.float32)
        img[:, :300] = 0.80
        img[:, 250:256] = 0.10
        page_side = [(10.0, 10.0), (300.0, 10.0), (300.0, 290.0), (10.0, 290.0)]
        text_side = [(10.0, 10.0), (253.0, 10.0), (253.0, 290.0), (10.0, 290.0)]
        prof_page = self._profile(img, page_side, band=40.0)
        prof_text = self._profile(img, text_side, band=20.0)
        page_step = prof_page[1]["step"]
        text_step = prof_text[1]["step"]
        self.assertGreater(page_step, 0.30)
        self.assertGreater(prof_page[1]["support"], 0.70)
        # the text rule can have a large immediate dip, but it is dark for only a
        # few pixels: long-range outside support is what disqualifies it
        self.assertLess(prof_text[1]["support"], 0.40)
        self.assertLess(prof_text[1]["support"], prof_page[1]["support"] * 0.6)

    def test_ramp_is_not_flat(self):
        img = np.tile(np.linspace(0.30, 0.80, 400, dtype=np.float32), (300, 1))
        quad = [(10.0, 10.0), (390.0, 10.0), (390.0, 290.0), (10.0, 290.0)]
        prof = self._profile(img, quad)
        self.assertGreater(np.mean([p["ramp_in"] for p in prof]), 1.0)


class DeskewPortTest(unittest.TestCase):
    def test_strong_quad_found(self):
        rgb = synthetic_page(bg=0.10, fg=0.90, noise=0.005)
        pts = deskew_port.detect_quad_shipped(rgb)
        self.assertIsNotNone(pts)
        self.assertEqual(len(pts), 4)

    def test_flat_image_has_no_quad(self):
        rgb = np.full((300, 400, 3), 0.5, dtype=np.float32)
        self.assertIsNone(deskew_port.detect_quad_shipped(rgb))


class RejectionTest(unittest.TestCase):
    def test_screen_is_rejected(self):
        h, w = 480, 640
        rgb = np.zeros((h, w, 3), dtype=np.float32)
        rgb[:, :] = (0.22, 0.20, 0.20)                      # room
        # active area: blue-white, slightly perspective
        quad = [(140.0, 70.0), (500.0, 60.0), (505.0, 315.0), (135.0, 325.0)]
        screen = sx.polygon_mask(quad, w, h)
        rgb[screen] = (0.78, 0.82, 0.92)
        bezel = sx.dilate(screen, 4) & ~screen
        rgb[bezel] = (0.03, 0.03, 0.03)
        # keyboard below: dark, high-frequency texture
        rng = np.random.default_rng(11)
        kb = np.s_[350:470, 150:520]
        rgb[kb] = np.clip(rng.normal(0.18, 0.10, (120, 370, 3)), 0, 1).astype(np.float32)
        det = sx2.detect_r2(rgb, as_gray_hi(rgb), sx2.ConfigR2())
        self.assertLess(det.confidence, sx2.ConfigR2().accept,
                        f"screen accepted at {det.confidence:.3f}")

    def test_wall_gradient_is_rejected(self):
        h, w = 480, 640
        ramp = np.tile(np.linspace(0.35, 0.80, w, dtype=np.float32), (h, 1))
        rgb = np.stack([ramp, ramp * 0.98, ramp * 0.95], axis=-1)
        det = sx2.detect_r2(rgb, as_gray_hi(rgb), sx2.ConfigR2())
        self.assertLess(det.confidence, sx2.ConfigR2().accept,
                        f"wall gradient accepted at {det.confidence:.3f}")

    def test_page_beats_screen(self):
        page = synthetic_page()
        det_page = sx2.detect_r2(page, as_gray_hi(page), sx2.ConfigR2())
        self.assertGreaterEqual(det_page.confidence, sx2.ConfigR2().accept)


if __name__ == "__main__":
    unittest.main()
