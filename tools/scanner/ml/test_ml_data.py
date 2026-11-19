"""Unit tests for the Scanner X data tooling (no GPU, no network, no training).

Run from the repository root:
    python -m unittest discover -s tools/scanner/ml -p "test_*.py"

Data-dependent assertions skip when the private qa workspace is absent, so the
tests stay runnable on a machine that only has the code checkout.
"""

from __future__ import annotations

import csv
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import numpy as np  # noqa: E402

import synth  # noqa: E402
from build_own_dataset import (  # noqa: E402
    TEST_CLIPS,
    VAL_CATEGORIES,
    VAL_CLIPS,
    assign_splits,
    split_counts,
)
from mlcommon import (  # noqa: E402
    is_convex_quad,
    point_in_polygon,
    polygon_area,
    rasterize_mask,
    resize_long_side,
    scale_points,
)

QA_ROOT = Path(r"C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa")
OWN_DIR = QA_ROOT / "scannerx-ml" / "commercial" / "own"


class MaskRasterisationTests(unittest.TestCase):
    def test_axis_aligned_rectangle_covers_expected_pixels(self):
        size = (200, 120)
        corners = [(20, 10), (180, 10), (180, 110), (20, 110)]
        mask = rasterize_mask(corners, size)
        self.assertEqual(mask.shape, (120, 200))
        self.assertEqual(mask.dtype, np.uint8)
        self.assertEqual(set(np.unique(mask)), {0, 255})
        # Inside the rectangle is paper, well outside is not.
        for x, y in [(100, 60), (25, 15), (175, 105)]:
            self.assertEqual(mask[y, x], 255, f"inside point ({x},{y}) not masked")
        for x, y in [(5, 5), (195, 5), (5, 115), (195, 115), (100, 5), (10, 60)]:
            self.assertEqual(mask[y, x], 0, f"outside point ({x},{y}) masked")
        # Area: 160 x 100 = 16000 px.
        covered = int((mask == 255).sum())
        self.assertAlmostEqual(covered, 16000, delta=0.01 * 16000)

    def test_perspective_quad_area_and_boundary(self):
        size = (320, 240)
        corners = [(40.0, 30.0), (280.0, 55.0), (250.0, 210.0), (60.0, 195.0)]
        mask = rasterize_mask(corners, size)
        self.assertTrue(is_convex_quad(corners))
        expected = polygon_area(corners)
        covered = int((mask == 255).sum())
        self.assertAlmostEqual(covered, expected, delta=0.03 * expected)
        # Independent point-in-polygon oracle, sampled away from the boundary.
        rng = np.random.default_rng(7)
        checked_inside = checked_outside = 0
        for _ in range(2000):
            x, y = int(rng.integers(0, size[0])), int(rng.integers(0, size[1]))
            inside = point_in_polygon(corners, x + 0.5, y + 0.5)
            # Skip a 4 px band around every edge where rasterisation is ambiguous.
            near_edge = False
            for i in range(4):
                (ax, ay), (bx, by) = corners[i], corners[(i + 1) % 4]
                dx, dy = bx - ax, by - ay
                length = max(1e-6, (dx * dx + dy * dy) ** 0.5)
                distance = abs(dy * (x - ax) - dx * (y - ay)) / length
                if distance < 4:
                    near_edge = True
                    break
            if near_edge:
                continue
            self.assertEqual(mask[y, x] == 255, inside, f"point ({x},{y}) inside={inside}")
            checked_inside += inside
            checked_outside += not inside
        self.assertGreater(checked_inside, 100)
        self.assertGreater(checked_outside, 100)

    def test_mask_follows_resized_coordinates(self):
        width, height = 1080, 1920
        corners = [(211.0, 168.0), (1069.0, 174.0), (1071.0, 1398.0), (208.0, 1424.0)]
        target_w, target_h = resize_long_side(width, height, 512)
        self.assertEqual((target_w, target_h), (288, 512))
        scaled = scale_points(corners, target_w / width, target_h / height)
        mask = rasterize_mask(scaled, (target_w, target_h))
        covered = int((mask == 255).sum())
        source_area = polygon_area(corners)
        factor = (target_w / width) * (target_h / height)
        self.assertAlmostEqual(covered, source_area * factor, delta=0.03 * source_area * factor)
        # The mask is not accidentally transposed or full-frame.
        self.assertGreater(mask[256, 144], 0)
        self.assertEqual(mask[5, 5], 0)
        self.assertEqual(mask[target_h - 5, target_w - 5], 0)

    def test_degenerate_quad_rejected(self):
        self.assertFalse(is_convex_quad([(0, 0), (10, 0), (20, 0), (30, 0)]))
        self.assertFalse(is_convex_quad([(0, 0), (10, 10), (0, 10), (10, 0)]))  # bow-tie
        self.assertTrue(is_convex_quad([(0, 0), (10, 0), (10, 10), (0, 10)]))


class SplitTests(unittest.TestCase):
    def test_contract_split_disjoint_and_complete(self):
        clips = [f"{i:02d}" for i in range(1, 31)] + [
            "EXTRA_02_Second_Book_Angled",
            "EXTRA_03_Desk_Calendar",
            "EXTRA_04_Handwritten_Notebook",
            "EXTRA_05_Spiral_Bound_Open_Pages",
        ]
        splits = assign_splits(clips)
        train, val, test = set(splits["train"]), set(splits["val"]), set(splits["test"])
        # No clip may appear in two splits.
        self.assertFalse(train & val, f"train/val overlap {train & val}")
        self.assertFalse(train & test, f"train/test overlap {train & test}")
        self.assertFalse(val & test, f"val/test overlap {val & test}")
        # Every clip is assigned exactly once.
        self.assertEqual(train | val | test, set(clips))
        self.assertEqual(len(train) + len(val) + len(test), len(clips))
        # Contract sets are exactly as specified.
        self.assertEqual(test, set(TEST_CLIPS))
        self.assertEqual(val, set(VAL_CLIPS))
        self.assertEqual(len(val), 5)
        # Membership by name, not by index arithmetic.
        for clip in ("03", "07", "13", "15", "18", "23", "27", "29"):
            self.assertIn(clip, test)
        for clip in ("05", "10", "17", "22", "28"):
            self.assertIn(clip, val)

    def test_unknown_or_duplicate_clip_fails(self):
        with self.assertRaises(ValueError):
            assign_splits(["01", "02"])  # contract clips missing
        with self.assertRaises(ValueError):
            assign_splits(["01", "01", "05", "10", "17", "22", "28", "03", "07", "13", "15", "18", "23", "27", "29", "EXTRA_04_Handwritten_Notebook"])

    @unittest.skipUnless(OWN_DIR.exists(), "private qa data not present")
    def test_written_splits_json_is_disjoint(self):
        summary = json.loads((OWN_DIR / "splits.json").read_text(encoding="utf-8"))
        train = set(summary["splits"]["train"])
        val = set(summary["splits"]["val"])
        test = set(summary["splits"]["test"])
        self.assertEqual(val, set(VAL_CLIPS))
        self.assertEqual(test, set(TEST_CLIPS))
        self.assertFalse((train & val) | (train & test) | (val & test))
        # Manifest rows must agree with the split lists and never leak a test clip.
        split_of = {clip: name for name, clips in summary["splits"].items() for clip in clips}
        with open(OWN_DIR / "manifest.csv", encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
        self.assertEqual(len(rows), sum(v["frames"] for v in summary["counts"].values()))
        for row in rows:
            self.assertEqual(row["split"], split_of[row["clip"]])
            if row["split"] == "test":
                self.assertIn(row["clip"], TEST_CLIPS)
        val_categories = {row["category"] for row in rows if row["split"] == "val"}
        self.assertTrue(set(VAL_CATEGORIES).issubset(val_categories), val_categories)

    @unittest.skipUnless(OWN_DIR.exists(), "private qa data not present")
    def test_manifest_mask_policy(self):
        with open(OWN_DIR / "manifest.csv", encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
        for row in rows:
            if row["has_doc"] == "2":
                self.assertEqual(row["mask_status"], "ignore_partial_doc")
                self.assertEqual(row["mask"], "", "partial-document frame must not carry a mask")
            if row["mask_status"] == "ok":
                self.assertTrue(row["mask"])
                self.assertTrue((OWN_DIR / row["mask"]).exists())
                self.assertTrue((OWN_DIR / row["image"]).exists())
            if row["mask_status"] in ("negative", "no_geometry"):
                self.assertEqual(row["mask"], "")
            self.assertIn(row["weight"], {"1.0", "0.7", "0.4"})
            self.assertEqual(len(row["src_sha256"]), 64)

    def test_split_counts_classification(self):
        entries = [
            {"split": "train", "clip": "01", "mask_status": "ok", "confidence": "high"},
            {"split": "train", "clip": "01", "mask_status": "no_geometry", "confidence": "low"},
            {"split": "train", "clip": "02", "mask_status": "negative", "confidence": "high"},
            {"split": "train", "clip": "12", "mask_status": "ignore_partial_doc", "confidence": "high"},
        ]
        counts = split_counts(entries)["train"]
        self.assertEqual(counts["frames"], 4)
        self.assertEqual(counts["clips"], 3)
        self.assertEqual(counts["positives_masked"], 1)
        self.assertEqual(counts["positives_unmasked"], 1)
        self.assertEqual(counts["negatives"], 1)
        self.assertEqual(counts["ignored_partial_doc"], 1)
        self.assertEqual(counts["low_confidence"], 1)


class SynthLabelTests(unittest.TestCase):
    """Generated masks must match the corners the generator reports."""

    def _samples(self, count=32, seed=1234):
        rng = np.random.default_rng(seed)
        choices = [(360, 270), (270, 360), (320, 320)]
        return [synth.generate_sample(i, rng, canvas_choices=choices) for i in range(count)]

    def test_mask_matches_corners(self):
        samples = self._samples()
        positives = [s for s in samples if s["has_doc"]]
        negatives = [s for s in samples if not s["has_doc"]]
        self.assertGreater(len(positives), 0, "seeded run produced no positive samples")
        self.assertGreater(len(negatives), 0, "seeded run produced no negative samples")
        for sample in samples:
            width, height = sample["size"]
            mask = sample["mask"]
            self.assertEqual(mask.shape, (height, width))
            if not sample["has_doc"]:
                self.assertNotIn("corners", sample)
                self.assertEqual(int(mask.sum()), 0, "negative sample must have an empty mask")
                continue
            self.assertEqual(set(np.unique(mask)), {0, 255})
            corners = sample["corners"]
            self.assertEqual(len(corners), 4)
            self.assertTrue(is_convex_quad(corners))
            for x, y in corners:
                self.assertTrue(0 <= x <= width - 1 and 0 <= y <= height - 1, "corner outside frame")
            expected = rasterize_mask(corners, (width, height))
            self.assertTrue(np.array_equal(mask, expected), "mask differs from corner polygon")
            # Independent area and interior/exterior checks.
            area = polygon_area(corners)
            covered = int((mask == 255).sum())
            self.assertAlmostEqual(covered, area, delta=0.03 * area)
            self.assertLess(area, 0.92 * width * height, "paper fills almost the whole frame")
            for dx, dy in [(0.3, 0.3), (0.5, 0.5), (0.7, 0.3), (0.4, 0.7)]:
                points = np.array(corners, float)
                inside = points[0] * (1 - dx) * (1 - dy) + points[1] * dx * (1 - dy) + points[2] * dx * dy + points[3] * (1 - dx) * dy
                x, y = int(inside[0]), int(inside[1])
                self.assertEqual(mask[y, x], 255, f"interior sample ({x},{y}) not masked")
            # A point just outside each edge must be background (4 px away).
            for i in range(4):
                (ax, ay), (bx, by) = corners[i], corners[(i + 1) % 4]
                mx, my = (ax + bx) / 2, (ay + by) / 2
                dx, dy = bx - ax, by - ay
                length = max(1e-6, (dx * dx + dy * dy) ** 0.5)
                nx, ny = -dy / length, dx / length
                outside = np.array(corners, float).mean(axis=0)
                sign = 1.0 if ((mx - outside[0]) * nx + (my - outside[1]) * ny) > 0 else -1.0
                px, py = mx + sign * nx * 6, my + sign * ny * 6
                if 0 <= px < width and 0 <= py < height and not point_in_polygon(corners, px, py):
                    self.assertEqual(mask[int(py), int(px)], 0, f"outside point near edge {i} masked")

    def test_hard_negative_screens_have_no_mask(self):
        rng = np.random.default_rng(0)
        found = 0
        for index in range(200):
            sample = synth.generate_sample(index, rng, canvas_choices=[(300, 240)])
            if sample["background"] == "screen":
                found += 1
                if not sample["has_doc"]:
                    self.assertEqual(int(sample["mask"].sum()), 0)
        self.assertGreater(found, 5, "screen hard negatives are not being generated")

    def test_generation_is_deterministic(self):
        first = synth.generate_sample(3, np.random.default_rng(42), canvas_choices=[(240, 320)])
        second = synth.generate_sample(3, np.random.default_rng(42), canvas_choices=[(240, 320)])
        self.assertEqual(first["size"], second["size"])
        self.assertEqual(np.asarray(first["image"]).tobytes(), np.asarray(second["image"]).tobytes())
        self.assertTrue(np.array_equal(first["mask"], second["mask"]))
        self.assertEqual(first.get("corners"), second.get("corners"))
        third = synth.generate_sample(3, np.random.default_rng(43), canvas_choices=[(240, 320)])
        self.assertFalse(np.array_equal(np.asarray(first["image"]), np.asarray(third["image"])))

    def test_backgrounds_and_papers_are_renderable(self):
        rng = np.random.default_rng(99)
        for kind in synth.PAPER_KINDS:
            paper, content = synth.make_paper(kind, rng)
            self.assertGreater(paper.width, 100)
            self.assertGreater(paper.height, 100)
            self.assertTrue(content)
        for kind in synth.BACKGROUND_KINDS:
            background = synth.render_background(kind, rng, 160, 200)
            self.assertEqual(background.size, (200, 160))
            self.assertEqual(background.mode, "RGB")


if __name__ == "__main__":
    unittest.main(verbosity=2)
