"""No-device sanity tests for the Tool Atlas harness (run in the build job)."""
import json
import os
from pathlib import Path
import tempfile
import unittest

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
        # The pre-push guard, not this no-device test, enforces that generated
        # binaries are never tracked; a local generator run may leave them on disk.

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


if __name__ == "__main__":
    unittest.main()
