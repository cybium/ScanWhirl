"""No-device sanity tests for the Tool Atlas harness (run in the build job)."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import atlas_driver as a


class AtlasHarnessTests(unittest.TestCase):
    def test_every_flow_has_a_title_and_unique_id(self):
        self.assertEqual(set(a.TOOL_TITLES), set(a.FLOWS))
        self.assertEqual(len(a.ALL_TOOLS), len(set(a.ALL_TOOLS)))
        self.assertTrue(all(a.TOOL_TITLES[t] for t in a.ALL_TOOLS))

    def test_verifier_journeys_are_file_only(self):
        # J4/J5/J8 must never be sent to the file verifier (they need phone evidence).
        used = set()
        for fn in a.FLOWS.values():
            src = Path(fn.__code__.co_filename).read_text(encoding="utf-8")
            import re
            used.update(re.findall(r'verify\("(J\d+)"', src))
        self.assertTrue(used.issubset({"J1", "J2", "J3", "J7", "J9", "J10"}), used)

    def test_generator_and_ground_truth_are_committed(self):
        # Binaries are generated in CI (pre-push guard rejects committed blobs).
        self.assertTrue((a.FIX / "make_fixtures.py").is_file())
        self.assertTrue((a.FIX / "ground_truth.json").is_file())
        gt = json.loads((a.FIX / "ground_truth.json").read_text(encoding="utf-8"))
        self.assertTrue(gt["fictional"])
        self.assertIn("notice_text", gt)
        self.assertFalse(any((a.FIX / p).is_file() for p in a.REQUIRED_FIXTURES
                             if p.endswith((".jpg", ".pdf"))),
                         "no fixtures should be committed as binaries")

    def test_guard_refuses_a_local_host(self):
        old = os.environ.get("GITHUB_ACTIONS")
        os.environ.pop("GITHUB_ACTIONS", None)
        try:
            with self.assertRaises(RuntimeError):
                a.guard()
        finally:
            if old is not None:
                os.environ["GITHUB_ACTIONS"] = old

    def test_report_is_written_with_tool_rows(self):
        old = a.OUT
        try:
            with tempfile.TemporaryDirectory() as tmp:
                a.OUT = Path(tmp)
                a.CTX["rows"] = [{
                    "tool": "compress", "status": "PASS", "steps": 5, "shots": 4,
                    "shot_names": ["01_setup.png"], "verifier": "PASS",
                    "verifier_reason": "exit 0", "reason": "", "detail": "ok",
                    "output": None, "checks": [], "notes": [],
                }]
                a.write_report(["compress"], 0)
                text = (a.OUT / "ATLAS_REPORT.md").read_text(encoding="utf-8")
                self.assertIn("| tool | steps | screenshots | verifier | failure reason |", text)
                self.assertIn("| compress | 5 | 4 | PASS | — |", text)
        finally:
            a.OUT = old

    # ---- fail-closed regressions (no device, no new dependencies) -------------

    def _pass_verifier(self):
        return {"status": "PASS", "reason": "exit 0",
                "checks": [{"check": "input.exists", "pass": True,
                            "measured": "file", "expected": "file"}]}

    def test_staging_error_blocks_all_rows_writes_report_and_is_red(self):
        with tempfile.TemporaryDirectory() as tmp:
            old_out, old_rows = a.OUT, a.CTX["rows"]
            try:
                a.OUT = Path(tmp)
                a.CTX["rows"] = []
                with patch.dict(os.environ, {"ATLAS_TOOLS": "compress,merge"}), \
                     patch.object(a, "guard"), patch.object(a, "adb", return_value=""), \
                     patch.object(a, "doc_ids", return_value=set()), \
                     patch.object(a, "prepare_library",
                                  side_effect=RuntimeError("import aborted")), \
                     patch.object(a, "scan_crashes", return_value=[]):
                    code = a.main()
                self.assertNotEqual(code, 0)
                self.assertEqual([r["tool"] for r in a.CTX["rows"]], ["compress", "merge"])
                self.assertTrue(all(r["status"] == a.BLOCKED for r in a.CTX["rows"]))
                self.assertIn("BLOCKED", (a.OUT / "ATLAS_REPORT.md").read_text(encoding="utf-8"))
            finally:
                a.OUT, a.CTX["rows"] = old_out, old_rows

    def test_zero_documents_blocks_all_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            old_out, old_rows = a.OUT, a.CTX["rows"]
            try:
                a.OUT = Path(tmp)
                a.CTX["rows"] = []
                with patch.dict(os.environ, {"ATLAS_TOOLS": "resize"}), \
                     patch.object(a, "guard"), patch.object(a, "adb", return_value=""), \
                     patch.object(a, "doc_ids", return_value=set()), \
                     patch.object(a, "prepare_library"), \
                     patch.object(a, "scan_crashes", return_value=[]):
                    code = a.main()
                self.assertNotEqual(code, 0)
                self.assertEqual(len(a.CTX["rows"]), 1)
                self.assertEqual(a.CTX["rows"][0]["status"], a.BLOCKED)
                self.assertIn("staging incomplete", a.CTX["rows"][0]["reason"])
            finally:
                a.OUT, a.CTX["rows"] = old_out, old_rows

    def test_missing_verifier_or_output_is_never_pass(self):
        self.assertEqual(a.classify(None, None), a.NOT_TESTED)
        self.assertEqual(a.classify(self._pass_verifier(), None), a.NOT_TESTED)
        with tempfile.NamedTemporaryFile() as f:
            self.assertEqual(a.classify(None, f.name), a.NOT_TESTED)

    def test_missing_required_tap_blocks_pass(self):
        old = a.CTX.get("required_failed")
        try:
            a.CTX["required_failed"] = False
            with patch.object(a, "soft_tap", return_value=False):
                self.assertFalse(a.require_tap(rid="toolSetupRun"))
            self.assertTrue(a.CTX["required_failed"])
            with tempfile.NamedTemporaryFile() as f:
                self.assertEqual(
                    a.classify(self._pass_verifier(), f.name, required_ok=False), a.BLOCKED)
        finally:
            a.CTX["required_failed"] = bool(old)

    def test_real_verified_success_is_pass(self):
        with tempfile.NamedTemporaryFile() as f:
            self.assertEqual(a.classify(self._pass_verifier(), f.name), a.PASS)

    def test_failing_verifier_is_fail(self):
        ver = {"status": "FAIL", "reason": "exit 1",
               "checks": [{"check": "input.exists", "pass": False,
                           "measured": "none", "expected": "file"}]}
        with tempfile.NamedTemporaryFile() as f:
            self.assertEqual(a.classify(ver, f.name), a.FAIL)

    def test_crash_reddens_job_even_when_tool_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            artifact = out / "output.pdf"
            artifact.write_bytes(b"%PDF-1.4 fictional")
            ok = {"detail": "measured", "verifier": self._pass_verifier(),
                  "output": str(artifact)}
            old_out, old_rows = a.OUT, a.CTX["rows"]
            try:
                a.OUT = out
                a.CTX["rows"] = []
                with patch.dict(os.environ, {"ATLAS_TOOLS": "compress"}), \
                     patch.object(a, "guard"), patch.object(a, "adb", return_value=""), \
                     patch.object(a, "doc_ids", return_value=set()), \
                     patch.object(a, "prepare_library"), \
                     patch.object(a, "assert_library_staged"), \
                     patch.object(a, "FLOWS", {"compress": lambda: ok}), \
                     patch.object(a, "scan_crashes", return_value=["FATAL EXCEPTION"]):
                    code = a.main()
                self.assertNotEqual(code, 0)
                self.assertEqual(a.CTX["rows"][0]["status"], a.PASS)
                report = (out / "ATLAS_REPORT.md").read_text(encoding="utf-8")
                self.assertIn("native crash", report)
            finally:
                a.OUT, a.CTX["rows"] = old_out, old_rows


if __name__ == "__main__":
    unittest.main()
