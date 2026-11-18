"""Tests for tools/scanner/score.py -- synthetic fixtures only.

Run: ``python tools/scanner/test_score.py`` from the repository root (or any directory).

No private frame, video or label file is read: every fixture is generated into a
temporary directory, so this test can run in the cloud gate. The point of the fixtures
is the arithmetic and the honesty rules (what is excluded, what counts as a miss), not
the detector.
"""

from __future__ import annotations

import csv
import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import score  # noqa: E402  (path is set above on purpose)

HEADER = ("clip,frame,file,width,height,has_doc,category,"
          "x1,y1,x2,y2,x3,y3,x4,y4,labeler,confidence,notes")
PRED_HEADER = "clip,frame,has_quad,x1,y1,x2,y2,x3,y3,x4,y4,ms"

# A 1000 x 1000 frame keeps the arithmetic readable: diagonal = 1414.2136 px.
SIZE = 1000
DIAGONAL = math.hypot(SIZE, SIZE)
PAGE = (100, 100, 900, 100, 900, 900, 100, 900)       # 800 x 800 page, centred
SHIFTED = (350, 100, 1150, 100, 1150, 900, 350, 900)  # same size, 250 px to the right
FRAME = (0, 0, 1000, 0, 1000, 1000, 0, 1000)          # the frame itself
INNER = (200, 200, 800, 200, 800, 800, 200, 800)


def write_csv(path: str, header: str, rows) -> None:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header.split(","))
        writer.writerows(rows)


def label_row(clip, frame, has_doc, corners=None, confidence="high", category="Normal",
              width=SIZE, height=SIZE, labeler="deepseek-test", notes=""):
    coords = list(corners) if corners else [""] * 8
    return [clip, frame, f"{frame}.jpg", width, height, has_doc, category,
            *coords, labeler, confidence, notes]


def pred_row(clip, frame, corners=None, ms=5.0):
    coords = list(corners) if corners else [""] * 8
    return [clip, frame, 1 if corners else 0, *coords, ms]


def as_quad(values):
    values = list(values)
    return [(float(values[i]), float(values[i + 1])) for i in range(0, 8, 2)]


class GeometryTests(unittest.TestCase):
    """The measuring instrument itself: masks, IoU, matching, normalisation."""

    def test_identical_quads_have_iou_one(self):
        quad = as_quad(PAGE)
        self.assertAlmostEqual(score.polygon_iou(quad, quad, SIZE, SIZE), 1.0, places=6)

    def test_disjoint_quads_have_iou_zero(self):
        left = as_quad((0, 0, 100, 0, 100, 100, 0, 100))
        right = as_quad((500, 500, 900, 500, 900, 900, 500, 900))
        self.assertEqual(score.polygon_iou(left, right, SIZE, SIZE), 0.0)

    def test_half_overlap_is_about_one_third(self):
        # Two 400x400 squares overlapping in half of their area: 0.5A / 1.5A = 1/3.
        a = as_quad((100, 100, 500, 100, 500, 500, 100, 500))
        b = as_quad((300, 100, 700, 100, 700, 500, 300, 500))
        iou = score.polygon_iou(a, b, SIZE, SIZE)
        self.assertGreater(iou, 0.30)
        self.assertLess(iou, 0.36)

    def test_mask_long_side_is_respected(self):
        mask = score.rasterize_quad(as_quad(PAGE), 1080, 1920, long_side=512)
        self.assertEqual(max(mask.size), 512)
        mask = score.rasterize_quad(as_quad(PAGE), 1920, 1080, long_side=128)
        self.assertEqual(max(mask.size), 128)

    def test_iou_uses_the_same_frame_for_both_quads(self):
        # A shifted quad must not be compared against a differently scaled mask.
        quad = as_quad(PAGE)
        shifted = as_quad(SHIFTED)
        self.assertLess(score.polygon_iou(shifted, quad, SIZE, SIZE), 0.8)
        self.assertGreater(score.polygon_iou(shifted, quad, SIZE, SIZE), 0.0)

    def test_frame_fit_matches_the_app_rule(self):
        self.assertTrue(score.is_frame_fit(as_quad(FRAME), SIZE, SIZE))
        # 3% inset corners: every corner within 5% of a border -> still the frame.
        self.assertTrue(score.is_frame_fit(as_quad((30, 30, 970, 30, 970, 970, 30, 970)), SIZE, SIZE))
        # One corner deeper than 5% -> a real quad, not the frame.
        self.assertFalse(score.is_frame_fit(as_quad((100, 100, 970, 30, 970, 970, 30, 970)), SIZE, SIZE))

    def test_corner_matching_is_rotation_invariant_but_not_mirrored(self):
        quad = as_quad(PAGE)
        rotated = [quad[2], quad[3], quad[0], quad[1]]  # same page, different start corner
        distance, mirror = score.match_corners(rotated, quad)
        self.assertAlmostEqual(distance, 0.0, places=6)
        self.assertFalse(mirror)

        mirrored = list(reversed(quad))
        distance, mirror = score.match_corners(mirrored, quad)
        self.assertGreater(distance, 100.0)  # winding preserved: a mirror is not excused
        self.assertTrue(mirror)

    def test_corner_error_is_normalised_by_page_diagonal(self):
        error, _ = score.corner_error(as_quad(SHIFTED), as_quad(PAGE), SIZE, SIZE)
        self.assertAlmostEqual(error, 250.0 / 1131.3708, places=4)

    def test_degenerate_quads_are_rejected(self):
        self.assertTrue(score.quad_degenerate([(0, 0), (0, 0), (10, 10), (10, 10)]))
        self.assertTrue(score.quad_degenerate([(0, 0), (10, 0), (10, 10)]))
        self.assertFalse(score.quad_degenerate(as_quad(PAGE)))

    def test_percentile_matches_hand_arithmetic(self):
        self.assertEqual(score._percentile([1.0, 2.0, 3.0], 50), 2.0)
        self.assertAlmostEqual(score._percentile([0.0, 1.0], 90), 0.9)
        self.assertIsNone(score._percentile([], 50))


class LoaderTests(unittest.TestCase):
    def test_labels_merge_across_files_and_duplicates_keep_the_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = os.path.join(tmp, "labels_A.csv")
            second = os.path.join(tmp, "labels_B.csv")
            write_csv(first, HEADER, [
                label_row("01", 1, 1, PAGE, "high"),
                label_row("02", 1, 0, None, "med", category="NoDocument"),
            ])
            write_csv(second, HEADER, [
                label_row("01", 1, 1, SHIFTED, "low", labeler="deepseek-other"),  # duplicate, ignored
                label_row("03", 1, 2, None, "low"),                                # has_doc=2 kept
                label_row("bad", "", "1", PAGE),                                   # malformed
            ])
            report = score.LoadReport()
            labels = score.load_labels([first, second], report)
            self.assertEqual(set(labels), {("01", 1), ("02", 1), ("03", 1)})
            self.assertEqual(labels[("01", 1)].quad, as_quad(PAGE))  # first file wins
            self.assertEqual(labels[("03", 1)].has_doc, 2)
            self.assertEqual(report.duplicates, 1)
            self.assertEqual(report.malformed, 1)
            self.assertEqual(report.frames, 3)
            self.assertEqual(report.has_doc, {"0": 1, "1": 1, "2": 1})
            self.assertEqual([entry["frames"] for entry in report.per_file], [2, 1])
            self.assertEqual(report.per_file[0]["clips"], ["01", "02"])
            self.assertEqual(report.per_file[1]["has_doc"], {"2": 1})

    def test_has_doc_0_with_corners_is_warned_and_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "labels_X.csv")
            write_csv(path, HEADER, [label_row("26", 1, 0, PAGE)])
            report = score.LoadReport()
            labels = score.load_labels([path], report)
            self.assertIsNone(labels[("26", 1)].quad)
            self.assertTrue(report.warnings)

    def test_predictions_parse_and_count_bad_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "pred.csv")
            write_csv(path, PRED_HEADER, [
                pred_row("01", 1, PAGE),
                [ "01", 2, 0, "", "", "", "", "", "", "", "", "6.0"],  # explicit miss, ms kept
                [ "01", 3, 1, "", "", "", "", "", "", "", "", ""],      # said yes, no corners
                [ "01", "", 0, "", "", "", "", "", "", "", "", ""],     # malformed
                [ "01", 4, 0, "", "", "", "", "", "", "", "", ""],
                [ "01", 4, 0, "", "", "", "", "", "", "", "", ""],      # duplicate key
            ])
            predictions, malformed, duplicates = score.load_predictions(path)
            self.assertEqual(predictions[("01", 1)].quad, as_quad(PAGE))
            self.assertIsNone(predictions[("01", 2)].quad)
            self.assertEqual(predictions[("01", 2)].ms, 6.0)
            self.assertIsNone(predictions[("01", 3)].quad)  # counted as no quad
            self.assertEqual(malformed, 2)  # missing frame + "yes" without corners
            self.assertEqual(duplicates, 1)


class EndToEndTests(unittest.TestCase):
    """One fixture with a known answer for every metric, including the exclusions."""

    def build(self, tmp):
        labels_path = os.path.join(tmp, "labels_T.csv")
        predictions_path = os.path.join(tmp, "pred_test.csv")
        write_csv(labels_path, HEADER, [
            label_row("01", 1, 1, PAGE, "high"),
            label_row("01", 2, 1, PAGE, "low"),
            label_row("01", 3, 1, None, "high", notes="corners omitted (every 2nd)"),
            label_row("01", 4, 2, None, "low", notes="document present, <3 corners visible"),
            label_row("26", 1, 0, None, "med", category="NoDocument"),
            label_row("26", 2, 0, None, "med", category="NoDocument"),
            label_row("26", 3, 0, None, "med", category="NoDocument"),
            label_row("26", 4, 0, None, "med", category="NoDocument"),
        ])
        write_csv(predictions_path, PRED_HEADER, [
            pred_row("01", 1, PAGE),
            pred_row("01", 2, SHIFTED),
            pred_row("01", 3, PAGE),
            pred_row("01", 4, PAGE),      # has_doc=2: must not count anywhere
            pred_row("26", 1, FRAME),     # frame-fit: false accept "any" only
            pred_row("26", 2, INNER),     # a real false accept
            # 26/4 has no prediction row at all: must count as a miss, not vanish.
        ])
        report = score.LoadReport()
        labels = score.load_labels([labels_path], report)
        predictions, malformed, duplicates = score.load_predictions(predictions_path)
        scored = score.score_prediction(labels, predictions, "pred_test", predictions_path,
                                        malformed, duplicates, 512, 0.8)
        return labels, predictions, scored

    def test_overall_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, _, scored = self.build(tmp)
        overall = scored["overall"]
        self.assertEqual(overall["frames_labelled"], 8)
        self.assertEqual(overall["frames_has_doc_1"], 3)
        self.assertEqual(overall["frames_has_doc_1_with_corners"], 2)
        self.assertEqual(overall["frames_has_doc_1_corners_omitted"], 1)
        self.assertEqual(overall["frames_has_doc_2_excluded"], 1)
        self.assertEqual(overall["frames_has_doc_0"], 4)
        self.assertEqual(overall["frames_missing_prediction"], 2)  # 26/3 and 26/4
        # 01/1, 01/2, 01/3 got quads; 01/4 (has_doc=2) must not inflate this.
        self.assertEqual(overall["returned_quad_doc1"], 3)
        self.assertAlmostEqual(overall["detection_rate"], 1.0)
        # 01/1 IoU 1.0 matches; 01/2 is shifted (IoU ~0.52) and must not.
        self.assertEqual(overall["matched_iou"], 1)
        self.assertAlmostEqual(overall["recall"], 0.5)
        # 26/1 (frame-fit) + 26/2 count as false accepts; only 26/2 survives the filter.
        self.assertEqual(overall["false_accept_any"], 2)
        self.assertEqual(overall["false_accept_nonfit"], 1)
        self.assertAlmostEqual(overall["false_accept_any_rate"], 0.5)
        self.assertAlmostEqual(overall["false_accept_nonfit_rate"], 0.25)

    def test_corner_error_buckets(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, _, scored = self.build(tmp)
        overall = scored["overall"]
        self.assertEqual(overall["corner_frames"], 2)
        expected = 250.0 / 1131.370849898476  # mean corner distance / page diagonal
        self.assertAlmostEqual(overall["corner_median"], expected / 2, places=6)
        self.assertAlmostEqual(overall["corner_median_conf_high_med"], 0.0, places=6)
        self.assertAlmostEqual(overall["corner_median_conf_low"], expected, places=4)
        self.assertEqual(overall["corner_frames_conf_high_med"], 1)
        self.assertEqual(overall["corner_frames_conf_low"], 1)

    def test_jitter_uses_consecutive_predicted_frames(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, _, scored = self.build(tmp)
        jitter = scored["jitter_by_clip"]
        # Steps 01/1->2 and 01/2->3 are 250 px, 01/3->4 is 0 (both PAGE); the has_doc=2
        # frame still contributes a step: jitter is a detector property, not a label one.
        self.assertAlmostEqual(jitter["01"], (250.0 + 250.0 + 0.0) / 3.0 / DIAGONAL, places=6)
        step = math.hypot(200, 200) / DIAGONAL  # frame corner -> inner corner
        self.assertAlmostEqual(jitter["26"], step, places=6)

    def test_per_clip_and_category_slices(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, _, scored = self.build(tmp)
        clip = scored["by_clip"]["01"]
        self.assertEqual(clip["frames_has_doc_1_with_corners"], 2)
        self.assertAlmostEqual(clip["recall"], 0.5)
        self.assertEqual(clip["frames_has_doc_0"], 0)
        negative = scored["by_clip"]["26"]
        self.assertEqual(negative["false_accept_any"], 2)
        self.assertEqual(negative["false_accept_nonfit"], 1)
        self.assertEqual(negative["frames_has_doc_1"], 0)
        self.assertIn("NoDocument", scored["by_category"])
        self.assertIn("Normal", scored["by_category"])

    def test_verdict_is_fail_and_names_the_reason(self):
        with tempfile.TemporaryDirectory() as tmp:
            _, _, scored = self.build(tmp)
        self.assertEqual(scored["verdict"], "FAIL")
        checks = {check["metric"]: check for check in scored["checks"]}
        self.assertEqual(checks["doc recall (IoU >= 0.80), aggregate"]["verdict"], "FAIL")
        self.assertEqual(checks["false accept, any quad"]["verdict"], "FAIL")
        self.assertEqual(checks["false accept, after frame-fit rejection"]["verdict"], "FAIL")
        self.assertEqual(checks["corner error, median"]["verdict"], "FAIL")
        self.assertEqual(checks["false accept on clips 26/30, any quad"]["verdict"], "FAIL")

    def test_cli_writes_markdown_and_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            labels_path = os.path.join(tmp, "labels_T.csv")
            predictions_path = os.path.join(tmp, "pred_test.csv")
            write_csv(labels_path, HEADER, [
                label_row("01", 1, 1, PAGE, "high"),
                label_row("26", 1, 0, None, "med", category="NoDocument"),
            ])
            write_csv(predictions_path, PRED_HEADER, [pred_row("01", 1, PAGE)])
            out_md = os.path.join(tmp, "out", "report.md")
            out_json = os.path.join(tmp, "out", "report.json")
            code = score.main([
                "--labels", labels_path,
                "--pred", predictions_path,
                "--out-md", out_md,
                "--out-json", out_json,
                "--title", "fixture",
            ])
            self.assertEqual(code, 0)
            with open(out_md, encoding="utf-8") as handle:
                markdown = handle.read()
            self.assertIn("# fixture", markdown)
            self.assertIn("Verdict: PASS", markdown)
            self.assertIn("model-made", markdown)
            with open(out_json, encoding="utf-8") as handle:
                payload = json.load(handle)
            self.assertEqual(payload["labels"]["frames"], 2)
            self.assertEqual(payload["predictions"]["pred_test"]["overall"]["matched_iou"], 1)

    def test_missing_label_rows_do_not_crash_the_cli(self):
        with tempfile.TemporaryDirectory() as tmp:
            labels_path = os.path.join(tmp, "empty.csv")
            predictions_path = os.path.join(tmp, "pred.csv")
            write_csv(labels_path, HEADER, [])
            write_csv(predictions_path, PRED_HEADER, [pred_row("01", 1, PAGE)])
            self.assertEqual(score.main(["--labels", labels_path, "--pred", predictions_path]), 2)


class VerdictTests(unittest.TestCase):
    """The thresholds themselves, without needing a fixture that happens to hit them."""

    @staticmethod
    def metrics(**overrides):
        base = {
            "frames_labelled": 10,
            "frames_has_doc_1": 6,
            "frames_has_doc_1_with_corners": 6,
            "frames_has_doc_1_corners_omitted": 0,
            "frames_has_doc_0": 4,
            "frames_has_doc_2_excluded": 0,
            "frames_missing_prediction": 0,
            "returned_quad_doc1": 6,
            "detection_rate": 1.0,
            "matched_iou": 6,
            "recall": 1.0,
            "false_accept_any": 0,
            "false_accept_any_rate": 0.0,
            "false_accept_nonfit": 0,
            "false_accept_nonfit_rate": 0.0,
            "corner_frames": 6,
            "corner_median": 0.01,
            "corner_p90": 0.03,
            "corner_frames_conf_high_med": 6,
            "corner_median_conf_high_med": 0.01,
            "corner_p90_conf_high_med": 0.03,
            "corner_frames_conf_low": 0,
            "corner_median_conf_low": None,
            "corner_p90_conf_low": None,
            "mirror_suspects": 0,
        }
        base.update(overrides)
        return base

    def test_everything_green_is_pass(self):
        overall = self.metrics()
        by_clip = {"01": self.metrics(), "26": self.metrics()}
        checks, verdict = score.evaluate_checks(overall, by_clip)
        self.assertEqual(verdict, "PASS")
        self.assertTrue(all(check["verdict"] == "PASS" for check in checks))

    def test_zero_tolerance_clips(self):
        overall = self.metrics(false_accept_any=1, false_accept_any_rate=0.25)
        by_clip = {"01": self.metrics(), "26": self.metrics(false_accept_any=1, false_accept_any_rate=0.25)}
        checks = {check["metric"]: check for check in score.evaluate_checks(overall, by_clip)[0]}
        self.assertEqual(checks["false accept on clips 26/30, any quad"]["verdict"], "FAIL")

    def test_corner_thresholds_are_strict(self):
        overall = self.metrics(corner_median=0.02, corner_p90=0.049)
        by_clip = {"01": self.metrics()}
        checks = {check["metric"]: check for check in score.evaluate_checks(overall, by_clip)[0]}
        self.assertEqual(checks["corner error, median"]["verdict"], "FAIL")  # 2% is not < 2%
        self.assertEqual(checks["corner error, p90"]["verdict"], "PASS")

    def test_no_corner_labels_is_na_not_pass(self):
        overall = self.metrics(frames_has_doc_1_with_corners=0, matched_iou=0, recall=None,
                               corner_frames=0, corner_median=None, corner_p90=None)
        by_clip = {"01": self.metrics(frames_has_doc_1_with_corners=0, matched_iou=0, recall=None,
                                      corner_frames=0, corner_median=None, corner_p90=None)}
        checks, verdict = score.evaluate_checks(overall, by_clip)
        self.assertEqual(verdict, "PASS (partial)")
        self.assertIn("n/a", {check["verdict"] for check in checks})


if __name__ == "__main__":
    unittest.main(verbosity=2)
