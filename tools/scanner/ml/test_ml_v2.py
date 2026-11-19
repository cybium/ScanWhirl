"""Tests for the v2 pieces: the second synthetic generator and the hybrid screen veto.

They are deliberately property-based rather than golden-image based: the generator draws
random scenes, so what must hold is that *the label is consistent with the pixels* - a
positive has a page mask that covers a plausible area, a hard negative has an empty mask,
a hand page's mask excludes the fingers, and the same seed reproduces the same sample.

Run:
    <venv>\\Scripts\\python.exe -m unittest test_ml_v2
"""

from __future__ import annotations

import unittest

import numpy as np

import data_ml
import hybrid_gate
import synth2

NEGATIVE_KINDS = ["screen_document", "box_stack", "framed_picture", "tissue", "door_wall",
                  "empty_desk"]
POSITIVE_KINDS = ["hand_page", "page_on_texture"]

_PHOTOS = None


def photos():
    global _PHOTOS
    if _PHOTOS is None:
        _PHOTOS = synth2.load_cc0_backgrounds()
    return _PHOTOS


def polygon_area(points) -> float:
    total = 0.0
    for i in range(len(points)):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % len(points)]
        total += x1 * y2 - x2 * y1
    return abs(total) / 2.0


class TestSynth2(unittest.TestCase):
    def test_positive_has_a_page_mask(self):
        for kind in POSITIVE_KINDS:
            with self.subTest(kind=kind):
                sample = synth2.generate_sample_v2(
                    0, np.random.default_rng(11), photos(), allowed=[kind], mix={kind: 1.0})
                self.assertEqual(sample["has_doc"], 1)
                mask = sample["mask"]
                self.assertEqual(mask.shape, (sample["size"][1], sample["size"][0]))
                self.assertTrue(set(np.unique(mask)).issubset({0, 255}))
                area = float((mask > 127).mean())
                self.assertGreater(area, 0.15, f"{kind} mask covers only {area:.2f}")
                self.assertIsNotNone(sample["corners"])
                self.assertEqual(len(sample["corners"]), 4)

    def test_hard_negative_has_no_page(self):
        for kind in NEGATIVE_KINDS:
            with self.subTest(kind=kind):
                sample = synth2.generate_sample_v2(
                    1, np.random.default_rng(12), photos(), allowed=[kind], mix={kind: 1.0})
                self.assertEqual(sample["has_doc"], 0, f"{kind} must be labelled no-page")
                self.assertEqual(int(sample["mask"].max()), 0)
                self.assertIsNone(sample["corners"])

    def test_hand_page_mask_excludes_the_fingers(self):
        sample = synth2.generate_sample_v2(
            2, np.random.default_rng(13), photos(), allowed=["hand_page"],
            mix={"hand_page": 1.0})
        visible = float((sample["mask"] > 127).mean())
        width, height = sample["size"]
        quad_area = polygon_area(sample["corners"]) / (width * height)
        self.assertLessEqual(visible, quad_area + 1e-6,
                             "the visible mask cannot exceed the page itself")
        self.assertGreater(visible, 0.10)

    def test_generation_is_deterministic(self):
        a = synth2.generate_sample_v2(5, np.random.default_rng(99), photos(),
                                      allowed=["page_on_texture"],
                                      mix={"page_on_texture": 1.0})
        b = synth2.generate_sample_v2(5, np.random.default_rng(99), photos(),
                                      allowed=["page_on_texture"],
                                      mix={"page_on_texture": 1.0})
        self.assertTrue(np.array_equal(np.asarray(a["image"]), np.asarray(b["image"])))
        self.assertTrue(np.array_equal(a["mask"], b["mask"]))

    def test_screen_scene_has_a_bright_panel_and_dark_bezel(self):
        canvas, label, meta = synth2.screen_scene(np.random.default_rng(21), 720, 540, photos())
        self.assertEqual(label, "screen_document")
        self.assertEqual(meta["hard_negative"], 1)
        grey = canvas.mean(axis=2)
        self.assertGreater(float(grey.max()), 0.55, "an emissive panel must be bright")
        self.assertLess(float(grey.min()), 0.25, "a bezel/keyboard must be dark")

    def test_cc0_split_is_disjoint_and_complete(self):
        rows = photos()
        holdout = [rows[0]["id"], rows[3]["id"]]
        train, held = synth2.split_cc0(rows, holdout)
        self.assertEqual(len(train) + len(held), len(rows))
        self.assertEqual({r["id"] for r in held}, set(holdout))
        self.assertFalse({r["id"] for r in train} & {r["id"] for r in held})

    def test_pool_requires_cc0_licence(self):
        rows = photos()
        self.assertTrue(rows)
        self.assertTrue(all(r["category"] for r in rows))


class TestPools(unittest.TestCase):
    def test_synth_pool_flags(self):
        pool = data_ml.SynthPool(data_ml.SYNTH_DIR)
        self.assertGreater(len(pool.negatives), 0)
        self.assertGreater(len(pool.positives), 0)
        self.assertEqual(len(pool.negatives) + len(pool.positives), len(pool))


class TestHybridGate(unittest.TestCase):
    def test_quad_to_work_scales_both_axes(self):
        quad = [(10.0, 20.0), (110.0, 20.0), (110.0, 220.0), (10.0, 220.0)]
        out = hybrid_gate.quad_to_work(quad, 200, 400, 100, 200)
        self.assertEqual(out[0], (5.0, 10.0))
        self.assertEqual(out[2], (55.0, 110.0))

    def test_screen_veto_returns_a_risk_dict(self):
        veto = hybrid_gate.ScreenVeto()
        rgb = (np.random.default_rng(3).random((240, 180, 3)) * 255).astype(np.uint8)
        quad = [(20.0, 30.0), (160.0, 30.0), (160.0, 210.0), (20.0, 210.0)]
        risk = veto.risk(rgb, quad)
        if veto.available:
            self.assertIn("screen_risk", risk)
            self.assertGreaterEqual(risk["screen_risk"], 0.0)
            self.assertLessEqual(risk["screen_risk"], 1.0)
        else:
            self.assertEqual(risk["screen_risk"], 0.0)

    def test_screen_veto_disabled_never_rejects(self):
        veto = hybrid_gate.ScreenVeto()
        veto.available = False
        risk = veto.risk(np.zeros((64, 64, 3), dtype=np.uint8),
                         [(4, 4), (60, 4), (60, 60), (4, 60)])
        self.assertEqual(risk["screen_risk"], 0.0)


if __name__ == "__main__":
    unittest.main()
