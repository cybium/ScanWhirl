"""Unit tests for the Scanner X prototype on synthetic images (no private data).

Run: python -m unittest discover -s tools/scanner/proto -p "test_*.py"

The two mandated cases:
  * a white quad on a dark background must be found within 1 % of the diagonal
  * an empty textured image must be rejected
plus the primitives the Kotlin port will need (box filter, Otsu, components, hull).
"""

from __future__ import annotations

import math
import os
import sys
import unittest

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import scannerx as sx  # noqa: E402


def synthetic_quad(w=640, h=480, corners=((80.0, 60.0), (600.0, 90.0), (570.0, 430.0), (60.0, 400.0)),
                   bg=0.16, fg=0.85, noise=0.02, seed=7):
    """Dark noisy background with a bright (slightly perspective) quad and a soft edge."""
    rng = np.random.default_rng(seed)
    img = np.full((h, w, 3), bg, dtype=np.float32) + rng.normal(0.0, noise, (h, w, 3)).astype(np.float32)
    mask = sx.polygon_mask([(x, y) for x, y in corners], w, h)
    # soften the transition over ~3 px so the edge looks like a photograph
    soft = sx.box_mean(mask.astype(np.float32), 2)
    soft = np.clip(soft, 0.0, 1.0)
    img = img * (1.0 - soft[..., None]) + fg * soft[..., None]
    img = np.clip(img, 0.0, 1.0)
    return img


def as_pipeline(img_rgb):
    """Run the same stages as detect_path, on in-memory arrays."""
    from PIL import Image
    small = Image.fromarray((img_rgb * 255).astype(np.uint8))
    hi = small.resize((img_rgb.shape[1] * 2, img_rgb.shape[0] * 2), Image.BILINEAR)
    gray_hi = np.asarray(hi.convert("L"), dtype=np.float32) / 255.0
    cfg = sx.Config()
    return sx.detect_quad(img_rgb, gray_hi, cfg), cfg


class QuadDetectionTest(unittest.TestCase):
    def test_white_quad_on_dark_background(self):
        corners = ((80.0, 60.0), (600.0, 90.0), (570.0, 430.0), (60.0, 400.0))
        img = synthetic_quad(corners=corners)
        det, cfg = as_pipeline(img)
        self.assertIsNotNone(det.work_quad, "the quad must be found")
        self.assertGreaterEqual(det.confidence, cfg.accept, f"confidence {det.confidence:.3f} below accept")
        diag = math.hypot(img.shape[1], img.shape[0])
        pred = list(det.work_quad)
        # best cyclic match, same rule as score.py
        truth = list(corners)
        best = min(
            sum(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(pred[k:] + pred[:k], truth)) / 4.0
            for k in range(4))
        self.assertLess(best, 0.01 * diag, f"mean corner error {best:.1f}px > 1% of {diag:.1f}px")

    def test_empty_textured_image_is_rejected(self):
        rng = np.random.default_rng(3)
        base = rng.normal(0.35, 0.05, (480, 640)).astype(np.float32)
        smooth = sx.box_mean(base, 6)
        img = np.clip(np.stack([smooth * 1.02, smooth, smooth * 0.95], axis=-1), 0.0, 1.0)
        det, cfg = as_pipeline(img)
        accepted = det.quad is not None and det.confidence >= cfg.accept
        self.assertFalse(accepted, f"an empty textured image must not be accepted (conf {det.confidence:.3f})")

    def test_dark_quad_on_light_background_is_not_a_page(self):
        img = synthetic_quad(bg=0.80, fg=0.25)
        det, cfg = as_pipeline(img)
        accepted = det.quad is not None and det.confidence >= cfg.accept
        self.assertFalse(accepted, "paper is brighter than its surroundings in every recorded clip")


class PrimitiveTest(unittest.TestCase):
    def test_box_sum_matches_naive(self):
        rng = np.random.default_rng(1)
        m = rng.random((24, 31))
        r = 2
        got = sx.box_sum(m, r)
        padded = np.pad(m, ((r + 1, r), (r + 1, r)), mode="edge")
        ii = padded.cumsum(0).cumsum(1)
        k = 2 * r + 1
        want = ii[k:, k:] - ii[:-k, k:] - ii[k:, :-k] + ii[:-k, :-k]
        np.testing.assert_allclose(got, want, atol=1e-9)

    def test_otsu_splits_two_modes(self):
        rng = np.random.default_rng(5)
        values = np.concatenate([rng.normal(0.10, 0.03, 500), rng.normal(0.70, 0.03, 500)])
        values = np.clip(values, 0.0, 1.0)
        t = sx.otsu(values)
        self.assertTrue(0.25 < t < 0.55, t)

    def test_label_components_counts_runs(self):
        mask = np.zeros((10, 10), dtype=bool)
        mask[1:3, 1:3] = True
        mask[5:9, 6:9] = True
        labels, stats = sx.label_components(mask)
        self.assertEqual(len(stats), 2)
        areas = sorted(s["area"] for s in stats)
        self.assertEqual(areas, [4, 12])
        self.assertEqual(labels[1, 1], labels[2, 2])
        self.assertNotEqual(labels[1, 1], labels[5, 6])

    def test_hull_of_square(self):
        mask = np.zeros((30, 30), dtype=bool)
        mask[5:20, 7:25] = True
        hull = sx.mask_hull(mask)
        self.assertGreaterEqual(len(hull), 4)
        area = sx.quad_area([tuple(p) for p in hull])
        self.assertAlmostEqual(area, 14 * 17, delta=14 * 17 * 0.06)

    def test_polygon_mask_area(self):
        quad = [(10.0, 10.0), (90.0, 12.0), (88.0, 70.0), (12.0, 68.0)]
        mask = sx.polygon_mask(quad, 100, 80)
        self.assertAlmostEqual(float(mask.sum()), sx.quad_area(quad), delta=0.12 * sx.quad_area(quad))

    def test_normalise_quad_is_clockwise_from_top_left(self):
        quad = [(10.0, 90.0), (90.0, 90.0), (90.0, 10.0), (10.0, 10.0)]
        norm = sx.normalise_quad(quad)
        self.assertGreater(sx.signed_area(norm), 0.0)
        self.assertLess(norm[0][0] + norm[0][1], 25.0)

    def test_line_intersection(self):
        a = (np.array([0.0, 0.0]), np.array([1.0, 0.0]))
        b = (np.array([5.0, -5.0]), np.array([0.0, 1.0]))
        point = sx.line_intersection(a, b)
        self.assertAlmostEqual(point[0], 5.0)
        self.assertAlmostEqual(point[1], 0.0)


if __name__ == "__main__":
    unittest.main()
