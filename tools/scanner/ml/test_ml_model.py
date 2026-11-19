"""Unit tests for the Scanner X ML model/data/postprocess pieces.

These are fast (a few seconds, CPU only) and cover the failure modes that would
silently make a training run meaningless:

* shapes, parameter budget and from-scratch determinism of the network,
* letterbox round-trip and that padded pixels can never become "paper",
* augmentation determinism and that the mask stays on the page after a warp,
* postprocess on synthetic masks: a rectangle becomes a rectangle, walls are rejected,
  a blob under 6 % is rejected, and repeated calls give identical corners.

Run:
    <venv>\\Scripts\\python.exe -m unittest discover -s tools\\scanner\\ml -p "test_ml_*.py"
"""

from __future__ import annotations

import math
import unittest

import numpy as np
import torch

import data_ml
import model
import postprocess


def rectangle_prob(size=(128, 160), box=(30.0, 40.0, 120.0, 150.0)) -> np.ndarray:
    """A probability map with a bright filled rectangle (x0, y0, x1, y1)."""
    height, width = size
    x0, y0, x1, y1 = box
    prob = np.zeros((height, width), dtype=np.float32)
    prob[int(y0):int(y1), int(x0):int(x1)] = 0.98
    return prob


def iou_quads(a, b, size=(256, 256)) -> float:
    from PIL import Image, ImageDraw

    def mask(quad):
        image = Image.new("L", size, 0)
        ImageDraw.Draw(image).polygon([tuple(p) for p in quad], fill=255)
        return np.asarray(image) > 127

    ma, mb = mask(a), mask(b)
    union = np.logical_or(ma, mb).sum()
    return float(np.logical_and(ma, mb).sum() / union) if union else 0.0


class TestModel(unittest.TestCase):
    def test_shapes_and_parameter_budget(self):
        net = model.TinyPageNet().eval()
        with torch.no_grad():
            out = net(torch.zeros(3, 3, model.NET_SIZE, model.NET_SIZE))
        self.assertEqual(tuple(out["mask"].shape), (3, 128, 128))
        self.assertEqual(tuple(out["presence"].shape), (3,))
        params = model.count_parameters(net)
        self.assertGreaterEqual(params, 300_000, "agreed floor is ~0.3 M params")
        self.assertLessEqual(params, 1_500_000, "agreed ceiling is ~1.5 M params")

    def test_output_size_helper(self):
        self.assertEqual(model.output_size(256, 2), 128)
        with self.assertRaises(ValueError):
            model.output_size(250, 8)

    def test_seeded_init_and_forward_are_deterministic(self):
        torch.manual_seed(1234)
        a = model.TinyPageNet().eval()
        torch.manual_seed(1234)
        b = model.TinyPageNet().eval()
        x = torch.arange(3 * 256 * 256, dtype=torch.float32).reshape(1, 3, 256, 256) / 1e6
        with torch.no_grad():
            out_a = a(x)
            out_b = b(x)
        self.assertTrue(torch.equal(out_a["mask"], out_b["mask"]))
        self.assertTrue(torch.equal(out_a["presence"], out_b["presence"]))

    def test_from_scratch_only(self):
        # Nothing in this lane may download or load pretrained weights: no torch.hub,
        # no torchvision model zoo, and the trainer must build the net with no argument.
        import inspect

        import train as train_mod

        model_source = inspect.getsource(model)
        train_source = inspect.getsource(train_mod)
        for forbidden in ("torch.hub", "load_state_dict_from_url", "torchvision.models"):
            self.assertNotIn(forbidden, model_source)
            self.assertNotIn(forbidden, train_source)
        self.assertIn("TinyPageNet(", train_source)


class TestLetterbox(unittest.TestCase):
    def test_landscape_and_portrait(self):
        box = data_ml.letterbox(1920, 1080)
        self.assertEqual((box.net_w, box.net_h), (256, 144))
        self.assertAlmostEqual(box.scale, 256 / 1920)
        box = data_ml.letterbox(1080, 1920)
        self.assertEqual((box.net_w, box.net_h), (144, 256))

    def test_quad_round_trip(self):
        box = data_ml.letterbox(1080, 1920)
        quad = [(100.0, 200.0), (900.0, 210.0), (880.0, 1700.0), (110.0, 1690.0)]
        net_quad = data_ml.source_quad_to_net(quad, box)
        back = data_ml.net_quad_to_source(net_quad, box)
        for (ax, ay), (bx, by) in zip(quad, back):
            self.assertAlmostEqual(ax, bx, places=4)
            self.assertAlmostEqual(ay, by, places=4)

    def test_round_trip_is_clamped_to_the_frame(self):
        box = data_ml.letterbox(100, 100)
        back = data_ml.net_quad_to_source([(-5, -5), (300, 0), (300, 300), (0, 300)], box)
        self.assertTrue(all(0 <= x <= 99 and 0 <= y <= 99 for x, y in back))

    def test_bad_size_rejected(self):
        with self.assertRaises(ValueError):
            data_ml.letterbox(0, 100)


class TestAugment(unittest.TestCase):
    def _sample(self):
        grid = np.zeros((256, 256, 3), dtype=np.uint8)
        grid[60:200, 70:190] = 235
        mask = np.zeros((256, 256), dtype=np.uint8)
        mask[60:200, 70:190] = 255
        return grid, mask

    def test_deterministic_with_seed(self):
        grid, mask = self._sample()
        out_a = data_ml.augment_pair(grid, mask, np.random.default_rng(7))
        out_b = data_ml.augment_pair(grid, mask, np.random.default_rng(7))
        self.assertTrue(np.array_equal(out_a[0], out_b[0]))
        self.assertTrue(np.array_equal(out_a[1], out_b[1]))
        out_c = data_ml.augment_pair(grid, mask, np.random.default_rng(8))
        self.assertFalse(np.array_equal(out_a[0], out_c[0]), "a different seed must change the sample")

    def test_mask_stays_on_the_page(self):
        grid, mask = self._sample()
        for seed in range(6):
            aug_img, aug_mask = data_ml.augment_pair(grid, mask, np.random.default_rng(seed))
            ys, xs = np.nonzero(aug_mask)
            self.assertGreater(len(xs), 500, "the page mask must survive the warp")
            mask_centroid = (xs.mean(), ys.mean())
            bright = (aug_img.astype(np.float32).mean(axis=2) > 110)
            bys, bxs = np.nonzero(bright)
            self.assertGreater(len(bxs), 500)
            bright_centroid = (bxs.mean(), bys.mean())
            distance = math.hypot(mask_centroid[0] - bright_centroid[0],
                                  mask_centroid[1] - bright_centroid[1])
            self.assertLess(distance, 6.0, f"mask drifted from the page (seed {seed}, {distance:.1f} px)")

    def test_shapes_and_dtype_preserved(self):
        grid, mask = self._sample()
        aug_img, aug_mask = data_ml.augment_pair(grid, mask, np.random.default_rng(3))
        self.assertEqual(aug_img.shape, grid.shape)
        self.assertEqual(aug_img.dtype, np.uint8)
        self.assertEqual(aug_mask.shape, mask.shape)
        self.assertTrue(set(np.unique(aug_mask)).issubset({0, 255}))

    def test_mask_can_be_none(self):
        grid, _ = self._sample()
        aug_img, aug_mask = data_ml.augment_pair(grid, None, np.random.default_rng(1))
        self.assertIsNone(aug_mask)
        self.assertEqual(aug_img.shape, grid.shape)


class TestPostprocess(unittest.TestCase):
    def test_rectangle_becomes_a_rectangle(self):
        prob = rectangle_prob()
        det = postprocess.mask_probability_to_quad(prob, t_mask=0.5)
        self.assertIsNotNone(det.quad, det.reason)
        truth = [(30, 40), (120, 40), (120, 150), (30, 150)]
        self.assertGreater(iou_quads(det.quad, truth, size=(160, 128)), 0.95)
        self.assertEqual(len(det.quad), 4)

    def test_quad_is_ordered_tl_tr_br_bl(self):
        det = postprocess.mask_probability_to_quad(rectangle_prob(), t_mask=0.5)
        tl, tr, br, bl = det.quad
        self.assertLess(tl[0] + tl[1], tr[0] + tr[1] + 1e-6)
        self.assertGreater(br[1], tl[1])
        self.assertLess(bl[0], br[0])

    def test_rotated_quad_is_recovered(self):
        # A rotated square rasterised directly as a probability map.
        size = 160
        prob = np.zeros((size, size), dtype=np.float32)
        yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
        cx = cy = size / 2.0
        u = (xx - cx) * 0.7071 + (yy - cy) * 0.7071
        v = -(xx - cx) * 0.7071 + (yy - cy) * 0.7071
        prob[(np.abs(u) < 40) & (np.abs(v) < 40)] = 0.95
        det = postprocess.mask_probability_to_quad(prob, t_mask=0.5)
        self.assertIsNotNone(det.quad, det.reason)
        truth = [(cx - 56.6, cy), (cx, cy - 56.6), (cx + 56.6, cy), (cx, cy + 56.6)]
        self.assertGreater(iou_quads(det.quad, truth, size=(size, size)), 0.9)

    def test_wall_is_rejected(self):
        prob = np.ones((128, 160), dtype=np.float32) * 0.99
        det = postprocess.mask_probability_to_quad(prob)
        self.assertIsNone(det.quad)
        self.assertIn("wall", det.reason)
        self.assertEqual(det.borders, 4)

    def test_page_touching_one_border_is_kept(self):
        prob = rectangle_prob(box=(40.0, 0.0, 150.0, 120.0))
        det = postprocess.mask_probability_to_quad(prob)
        self.assertIsNotNone(det.quad, det.reason)
        self.assertEqual(det.borders, 1)

    def test_small_blob_is_rejected(self):
        prob = np.zeros((128, 160), dtype=np.float32)
        prob[60:64, 80:84] = 0.99  # 16 px of 20480 = 0.08 %
        det = postprocess.mask_probability_to_quad(prob)
        self.assertIsNone(det.quad)
        self.assertIn("area", det.reason)

    def test_empty_mask_is_no_page(self):
        det = postprocess.mask_probability_to_quad(np.zeros((128, 160), np.float32))
        self.assertIsNone(det.quad)

    def test_two_components_takes_the_largest(self):
        prob = rectangle_prob(box=(10.0, 10.0, 40.0, 40.0))
        prob[30:110, 60:150] = 0.99
        det = postprocess.mask_probability_to_quad(prob)
        self.assertIsNotNone(det.quad)
        xs = [p[0] for p in det.quad]
        self.assertGreater(min(xs), 50.0, "the small blob must not win")

    def test_presence_gate(self):
        prob = rectangle_prob()
        box = data_ml.letterbox(512, 512)
        det = postprocess.detect_from_outputs(prob, presence=0.20, box=box, t_presence=0.5)
        self.assertIsNone(det.quad)
        self.assertIn("presence", det.reason)
        det = postprocess.detect_from_outputs(prob, presence=0.80, box=box, t_presence=0.5)
        self.assertIsNotNone(det.quad)

    def test_deterministic(self):
        prob = rectangle_prob()
        a = postprocess.mask_probability_to_quad(prob)
        b = postprocess.mask_probability_to_quad(prob)
        self.assertEqual(a.quad, b.quad)

    def test_padding_is_never_paper(self):
        # A letterboxed 256x144 frame: everything below y=144 is padding. A model that
        # confidently marks the padding must not produce a page from it.
        box = data_ml.letterbox(1920, 1080)
        prob = np.zeros((128, 128), dtype=np.float32)
        prob[72:, :] = 0.99  # rows 144..256 of the grid = padding only
        det = postprocess.detect_from_outputs(prob, presence=0.99, box=box)
        self.assertIsNone(det.quad)

    def test_letterbox_quad_maps_back_inside_the_frame(self):
        box = data_ml.letterbox(1080, 1920)
        prob = np.zeros((128, 128), dtype=np.float32)
        prob[10:60, 10:60] = 0.99
        det = postprocess.detect_from_outputs(prob, presence=0.99, box=box)
        self.assertIsNotNone(det.quad)
        source = data_ml.net_quad_to_source(det.quad, box)
        self.assertTrue(all(0 <= x <= 1079 and 0 <= y <= 1919 for x, y in source))

    def test_hull_and_area_helpers(self):
        square = np.array([[0, 0], [10, 0], [10, 10], [0, 10], [5, 5]], dtype=np.float64)
        hull = postprocess.convex_hull(square)
        self.assertEqual(len(hull), 4)
        self.assertAlmostEqual(postprocess.quad_area(list(map(tuple, hull))), 100.0)
        quad = postprocess.max_area_quad(hull)
        self.assertAlmostEqual(postprocess.quad_area(quad), 100.0, places=6)

    def test_largest_component_is_8_connected(self):
        mask = np.zeros((10, 10), dtype=bool)
        mask[1, 1] = mask[2, 2] = True  # diagonal neighbours
        self.assertEqual(postprocess.largest_component(mask).sum(), 2)

    def test_order_quad_handles_clockwise_input(self):
        cw = [(0, 0), (0, 10), (10, 10), (10, 0)]
        ordered = postprocess.order_quad(cw)
        tl, tr, br, bl = ordered
        self.assertAlmostEqual(tl[0] + tl[1], 0.0)
        self.assertAlmostEqual(tr[0] - tr[1], 10.0)
        self.assertAlmostEqual(br[0] + br[1], 20.0)
        self.assertAlmostEqual(bl[1] - bl[0], 10.0)


if __name__ == "__main__":
    unittest.main()
