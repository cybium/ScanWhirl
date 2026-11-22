"""No-device sanity tests for the Tool Atlas harness (run in the build job)."""
import inspect
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

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
        return {"status": "PASS", "reason": "exit 0", "parsed": True, "exit": 0,
                "checks": [{"check": "input.exists", "pass": True,
                            "measured": "file", "expected": "file"}]}

    def _staging_map(self):
        return {name: {"import_ok": True, "ids": ["doc-" + name]}
                for name in a.STAGING_PDFS}

    def _run_verify(self, report=None, exit_code=0, stale=None, raw_text=None):
        """Run verify() against a NON-EMPTY fake output with a controlled child."""
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out.pdf"
            out.write_bytes(b"%PDF-1.4 fictional output bytes")
            jp = out.with_suffix(".json")
            if stale is not None:
                jp.write_text(json.dumps(stale), encoding="utf-8")
            def fake_run(cmd, **kwargs):
                if raw_text is not None:
                    jp.write_text(raw_text, encoding="utf-8")
                elif report is not None:
                    jp.write_text(json.dumps(report), encoding="utf-8")
                return subprocess.CompletedProcess(cmd, exit_code, "", "")
            old_out, old_tool = a.OUT, a.CTX["tool"]
            try:
                a.OUT = Path(tmp)
                a.CTX["tool"] = ""
                with patch.object(a.subprocess, "run", side_effect=fake_run):
                    ver = a.verify("J2", str(out))
                status = a.classify(ver, str(out))
                return ver, status, jp
            finally:
                a.OUT, a.CTX["tool"] = old_out, old_tool

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
                     patch.object(a, "prepare_library", return_value={}), \
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
            f.write(b"%PDF-1.4 fictional output bytes")
            f.flush()
            self.assertEqual(a.classify(None, f.name), a.NOT_TESTED)

    def test_missing_required_tap_blocks_pass(self):
        old = a.CTX.get("required_failed")
        try:
            a.CTX["required_failed"] = False
            with patch.object(a, "soft_tap", return_value=False):
                self.assertFalse(a.require_tap(rid="toolSetupRun"))
            self.assertTrue(a.CTX["required_failed"])
            with tempfile.NamedTemporaryFile() as f:
                f.write(b"%PDF-1.4 fictional output bytes")
                f.flush()
                self.assertEqual(
                    a.classify(self._pass_verifier(), f.name, required_ok=False), a.BLOCKED)
        finally:
            a.CTX["required_failed"] = bool(old)

    def test_real_verified_success_is_pass(self):
        with tempfile.NamedTemporaryFile() as f:
            f.write(b"%PDF-1.4 fictional output bytes")
            f.flush()
            self.assertEqual(a.classify(self._pass_verifier(), f.name), a.PASS)

    def test_failing_verifier_is_fail(self):
        ver = {"status": "FAIL", "reason": "exit 1", "parsed": True, "exit": 1,
               "checks": [{"check": "input.exists", "pass": False,
                           "measured": "none", "expected": "file"}]}
        with tempfile.NamedTemporaryFile() as f:
            f.write(b"%PDF-1.4 fictional output bytes")
            f.flush()
            self.assertEqual(a.classify(ver, f.name), a.FAIL)

    def test_empty_output_file_is_never_pass(self):
        with tempfile.NamedTemporaryFile() as f:
            self.assertFalse(a.has_output(f.name))
            self.assertEqual(a.classify(self._pass_verifier(), f.name), a.NOT_TESTED)

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

    # ---- B1: verifier trust / staleness ---------------------------------------

    def test_verify_exit0_invalid_json_is_not_pass(self):
        ver, status, _ = self._run_verify(raw_text="{not json")
        self.assertNotEqual(ver["status"], a.PASS)
        self.assertFalse(ver["parsed"])
        self.assertEqual(status, a.NOT_TESTED)

    def test_verify_exit0_all_false_checks_is_not_pass(self):
        report = {"checks": [
            {"check": "a", "pass": False, "measured": "x", "expected": "y"},
            {"check": "b", "pass": False, "measured": "x", "expected": "y"},
        ]}
        ver, status, _ = self._run_verify(report=report, exit_code=0)
        self.assertEqual(ver["status"], a.FAIL)
        self.assertEqual(status, a.FAIL)

    def test_verify_exit0_mixed_checks_is_not_pass(self):
        report = {"checks": [
            {"check": "a", "pass": True, "measured": "x", "expected": "y"},
            {"check": "b", "pass": False, "measured": "x", "expected": "y"},
        ]}
        ver, status, _ = self._run_verify(report=report, exit_code=0)
        self.assertEqual(ver["status"], a.FAIL)
        self.assertEqual(status, a.FAIL)

    def test_verify_exit0_nonbool_pass_is_not_pass(self):
        report = {"checks": [
            {"check": "a", "pass": 1, "measured": "x", "expected": "y"},
        ]}
        ver, status, _ = self._run_verify(report=report, exit_code=0)
        self.assertNotEqual(ver["status"], a.PASS)
        self.assertEqual(status, a.NOT_TESTED)

    def test_verify_exit0_empty_checks_is_not_pass(self):
        ver, status, _ = self._run_verify(report={"checks": []}, exit_code=0)
        self.assertNotEqual(ver["status"], a.PASS)
        self.assertEqual(status, a.NOT_TESTED)

    def test_verify_ignores_stale_pass_json(self):
        stale = {"checks": [{"check": "input.exists", "pass": True,
                             "measured": "old", "expected": "file"}]}
        for code in (0, 1):
            ver, status, jp = self._run_verify(stale=stale, exit_code=code)
            self.assertNotEqual(ver["status"], a.PASS)
            self.assertFalse(jp.exists(), "stale per-output JSON must be removed")
            self.assertNotEqual(status, a.PASS)

    def test_verify_fresh_valid_report_is_pass(self):
        report = {"checks": [{"check": "input.exists", "pass": True,
                              "measured": "fresh", "expected": "file"}]}
        ver, status, _ = self._run_verify(report=report, exit_code=0)
        self.assertEqual(ver["status"], a.PASS)
        self.assertEqual(status, a.PASS)

    def test_classify_rejects_lying_pass_status(self):
        ver = {"status": "PASS", "reason": "exit 0", "parsed": True, "exit": 0,
               "checks": [{"check": "a", "pass": False, "measured": "x", "expected": "y"}]}
        with tempfile.NamedTemporaryFile() as f:
            f.write(b"%PDF-1.4 fictional output bytes")
            f.flush()
            self.assertEqual(a.classify(ver, f.name), a.NOT_TESTED)
        self.assertFalse(a.verifier_is_valid({
            "status": "PASS", "parsed": True, "exit": 0,
            "checks": [{"check": "a", "pass": 1, "measured": "x", "expected": "y"}]}))

    # ---- B2: staged fixture identity ------------------------------------------

    def test_staging_true_map_accepts(self):
        ids, problems = a.validate_staging_map(
            self._staging_map(),
            meta=lambda d: d[len("doc-"):],
            pdf=lambda d: b"%PDF-1.4 fictional",
        )
        self.assertEqual(problems, [])
        self.assertEqual(len(ids), len(a.STAGING_PDFS))

    def test_staging_wrong_ids_reject(self):
        mapping = {name: {"import_ok": True, "ids": ["bogus-" + str(i)]}
                   for i, name in enumerate(a.STAGING_PDFS)}
        _, problems = a.validate_staging_map(
            mapping, meta=lambda d: "", pdf=lambda d: b"%PDF-1.4 fictional")
        self.assertTrue(problems)

    def test_staging_wrong_names_reject(self):
        _, problems = a.validate_staging_map(
            self._staging_map(), meta=lambda d: "unrelated.pdf",
            pdf=lambda d: b"%PDF-1.4 fictional")
        self.assertTrue(problems)

    def test_staging_partial_set_reject(self):
        mapping = self._staging_map()
        mapping.pop(a.STAGING_PDFS[0])
        _, problems = a.validate_staging_map(
            mapping, meta=lambda d: d[len("doc-"):], pdf=lambda d: b"%PDF-1.4 fictional")
        self.assertTrue(problems)
        with patch.object(a, "adb", return_value=""):
            with self.assertRaises(RuntimeError):
                a.assert_library_staged(self._staging_map())

    # ---- B3: empty tool selection ---------------------------------------------

    def test_empty_tool_selection_is_red(self):
        with tempfile.TemporaryDirectory() as tmp:
            old_out, old_rows = a.OUT, a.CTX["rows"]
            try:
                a.OUT = Path(tmp)
                a.CTX["rows"] = []
                with patch.dict(os.environ, {"ATLAS_TOOLS": ", , ,"}), \
                     patch.object(a, "guard"), patch.object(a, "adb", return_value=""), \
                     patch.object(a, "scan_crashes", return_value=[]):
                    code = a.main()
                self.assertNotEqual(code, 0)
                self.assertEqual([r["status"] for r in a.CTX["rows"]], [a.BLOCKED])
                self.assertIn("BLOCKED", (a.OUT / "ATLAS_REPORT.md").read_text(encoding="utf-8"))
            finally:
                a.OUT, a.CTX["rows"] = old_out, old_rows

    # ---- B4: bounded Form-photo before-snapshot timing ------------------------

    def test_form_photo_snapshots_download_before_running(self):
        src = inspect.getsource(a.f_form_photo)
        before_at = src.index("before = ")
        run_at = src.index('require_tap(rid="toolSetupRun")')
        pull_at = src.index("pull_new_files(before")
        self.assertLess(before_at, run_at)
        self.assertLess(before_at, pull_at)
        self.assertLess(run_at, pull_at)


class AtlasImportEntryTests(unittest.TestCase):
    """Pure XML / mock coverage of the PDF-import route. No device, ADB or network.

    The hierarchies below use only selectors confirmed in the shipped Kotlin/XML
    and in the real 2deddb29 emulator artifact; no guessed locator fallback.
    """

    @staticmethod
    def _hierarchy(ids=(), descs=(), texts=()):
        parts = ['<?xml version="1.0"?><hierarchy rotation="0">']
        i = 0
        for rid in ids:
            parts.append(
                f'<node index="{i}" text="" '
                f'resource-id="com.scanwhirl.app.test:id/{rid}" '
                f'class="android.widget.FrameLayout" content-desc="" '
                f'clickable="true" bounds="[0,0][100,100]"/>')
            i += 1
        for text in texts:
            parts.append(
                f'<node index="{i}" text="{text}" resource-id="" '
                f'class="android.widget.TextView" content-desc="" '
                f'clickable="true" bounds="[0,0][100,100]"/>')
            i += 1
        for desc in descs:
            parts.append(
                f'<node index="{i}" text="" resource-id="" '
                f'class="android.widget.TextView" content-desc="{desc}" '
                f'clickable="true" bounds="[0,0][100,100]"/>')
            i += 1
        parts.append("</hierarchy>")
        return ET.fromstring("".join(parts))

    # ---- entry variants -------------------------------------------------------

    def test_real_artifact_shape_empty_library_uses_empty_import(self):
        # swEmptyImport AND swNavScan are both present in the real 2deddb29 dump.
        # The dedicated empty-Library import tile must win over the nav disc.
        root = self._hierarchy(
            ids=("swEmptyImport", "swEmptyScan", "swNavScan", "swNavLibrary"),
            descs=("Scan. Open scan and import options.", "Library"))
        self.assertEqual(a.import_entry_plan(root),
                         {"kind": "empty_import", "by": "rid", "value": "swEmptyImport"})

    def test_populated_library_uses_nav_scan(self):
        root = self._hierarchy(ids=("swNavScan", "swNavLibrary", "swDocList"))
        self.assertEqual(a.import_entry_plan(root),
                         {"kind": "nav_scan", "by": "rid", "value": "swNavScan"})

    def test_create_menu_uses_import_pdf_row(self):
        root = self._hierarchy(ids=("swCreateImport", "swCreatePhotos", "swCreateScan"))
        self.assertEqual(a.import_entry_plan(root),
                         {"kind": "create_menu", "by": "rid", "value": "swCreateImport"})

    def test_import_sheet_uses_pdf_row_not_photos(self):
        root = self._hierarchy(ids=("importPdf", "importPhotos"))
        self.assertEqual(a.import_entry_plan(root),
                         {"kind": "import_sheet_pdf", "by": "rid", "value": "importPdf"})

    def test_direct_saf_detected_by_show_roots(self):
        self.assertEqual(a.import_entry_plan(self._hierarchy(descs=("Show roots",))),
                         {"kind": "saf", "by": None, "value": None})

    def test_missing_entry_is_none_without_guessing(self):
        # Loose "Import PDF" copy (the legacy create_disc_desc-style guess) is not
        # a confirmed control: fail closed instead of inventing a selector.
        root = self._hierarchy(ids=("swHomeSearch",), texts=("Import PDF",))
        self.assertEqual(a.import_entry_plan(root), {"kind": "none", "by": None, "value": None})

    def test_photo_routes_are_never_a_pdf_entry(self):
        root = self._hierarchy(ids=("swEmptyScan", "swCreatePhotos", "importPhotos"))
        self.assertEqual(a.import_entry_plan(root)["kind"], "none")

    # ---- bounded, single-tap routing -----------------------------------------

    def test_reach_pdf_picker_taps_each_entry_once(self):
        empty = self._hierarchy(ids=("swEmptyImport", "swNavScan"))
        sheet = self._hierarchy(ids=("importPdf", "importPhotos"))
        saf = self._hierarchy(descs=("Show roots",))
        tapped = []
        with patch.object(a, "hierarchy", side_effect=[empty, sheet, saf]), \
             patch.object(a, "soft_tap",
                          side_effect=lambda **w: tapped.append(w) or True), \
             patch.object(a, "shot"), patch.object(a, "note"), \
             patch.object(a.time, "sleep"):
            self.assertTrue(a.reach_pdf_picker())
        self.assertEqual(tapped, [{"rid": "swEmptyImport"}, {"rid": "importPdf"}])

    def test_reach_pdf_picker_refuses_second_tap_on_stale_screen(self):
        stuck = self._hierarchy(ids=("swEmptyImport",))
        tapped = []
        with patch.object(a, "hierarchy", side_effect=[stuck, stuck, stuck]), \
             patch.object(a, "soft_tap",
                          side_effect=lambda **w: tapped.append(w) or True), \
             patch.object(a, "shot"), patch.object(a, "note"), \
             patch.object(a.time, "sleep"):
            self.assertFalse(a.reach_pdf_picker())
        self.assertEqual(len(tapped), 1, "a stale screen must not be tapped twice")

    def test_provider_root_selected_by_app_label(self):
        # Provider has no android:label; SAF shows the shipped application label.
        app_label = a.strings("en")["app_name"]
        roots = self._hierarchy(texts=("Downloads", app_label), descs=(a.SAF_ROOT_DESC,))
        tapped = []
        with patch.object(a, "hierarchy", return_value=roots), \
             patch.object(a, "tap_node",
                          side_effect=lambda r, n: tapped.append(a.node_text(n))), \
             patch.object(a, "note"):
            self.assertTrue(a.select_provider_root())
        self.assertEqual(tapped, [app_label])

    def test_provider_root_ambiguity_and_generic_roots_refused(self):
        app_label = a.strings("en")["app_name"]
        roots = self._hierarchy(texts=("Downloads", "Recent", app_label, app_label),
                                descs=(a.SAF_ROOT_DESC,))
        with patch.object(a, "hierarchy", return_value=roots), \
             patch.object(a, "tap_node") as do_tap, patch.object(a, "note"):
            self.assertFalse(a.select_provider_root())
        do_tap.assert_not_called()
        # No 'Show roots' control means we are not in the roots list.
        bare = self._hierarchy(texts=(app_label,))
        with patch.object(a, "hierarchy", return_value=bare), \
             patch.object(a, "tap_node") as do_tap, patch.object(a, "note"):
            self.assertFalse(a.select_provider_root())
        do_tap.assert_not_called()

    def test_fixture_stem_match_grid(self):
        grid = self._hierarchy(texts=("receipt_aug",))
        rows = a.fixture_row_matches(grid, "receipt_aug.pdf")
        self.assertEqual(len(rows), 1)
        self.assertEqual(a.node_text(rows[0]), "receipt_aug")
        with patch.object(a, "hierarchy", return_value=grid), \
             patch.object(a, "note"), patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 0]):
            self.assertEqual(a.node_text(a.await_fixture_row("receipt_aug.pdf")),
                             "receipt_aug")

    def test_fixture_stem_does_not_match_suffixed_name(self):
        grid = self._hierarchy(texts=("receipt_aug_2", "receipt_aug_2.pdf"))
        self.assertEqual(a.fixture_row_matches(grid, "receipt_aug.pdf"), [])

    def test_fixture_ambiguity_fails_closed(self):
        grid = self._hierarchy(texts=("receipt_aug.pdf", "receipt_aug"))
        self.assertEqual(len(a.fixture_row_matches(grid, "receipt_aug.pdf")), 2)
        with patch.object(a, "hierarchy", return_value=grid), \
             patch.object(a, "note") as noted, patch.object(a.time, "sleep"):
            self.assertIsNone(a.await_fixture_row("receipt_aug.pdf"))
        self.assertTrue(any("ambiguous" in str(c) for c in noted.call_args_list))

    def test_choose_fixture_in_saf_taps_fixture_once(self):
        row = list(self._hierarchy(texts=("receipt_sep.pdf",)).iter("node"))[0]
        fixture_taps = []
        with patch.object(a, "wait"), \
             patch.object(a, "soft_tap", return_value=False), \
             patch.object(a, "select_provider_root", return_value=False), \
             patch.object(a, "await_fixture_row", return_value=row), \
             patch.object(a, "tap", side_effect=lambda **w: fixture_taps.append(w)), \
             patch.object(a, "shot"), patch.object(a, "note"), \
             patch.object(a, "adb", return_value="Pixel"), \
             patch.object(a, "back") as do_back:
            self.assertTrue(a.choose_fixture_in_saf("receipt_sep.pdf"))
        self.assertEqual(fixture_taps, [{"text": "receipt_sep.pdf"}])
        do_back.assert_not_called()

    def test_choose_fixture_in_saf_polls_until_file_then_taps_once(self):
        # Real PILOT 45058426629 shape: the dump right after the ScanWhirlQA tap
        # still shows the parent/folder row; the file only appears after a poll.
        stale = self._hierarchy(texts=("ScanWhirlQA",))
        fresh = self._hierarchy(texts=("marksheet_scan_300dpi.pdf",))
        seq = [stale, stale, fresh]
        fixture_taps = []

        with patch.object(a, "wait"), \
             patch.object(a, "soft_tap", return_value=False), \
             patch.object(a, "select_provider_root", return_value=False), \
             patch.object(a, "shot"), patch.object(a, "note"), \
             patch.object(a, "adb", return_value="Pixel"), \
             patch.object(a, "back") as do_back, \
             patch.object(a, "hierarchy",
                          side_effect=lambda: seq.pop(0) if len(seq) > 1 else seq[0]), \
             patch.object(a, "tap", side_effect=lambda **w: fixture_taps.append(w)), \
             patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 0, 0.5, 1.0]):
            self.assertTrue(a.choose_fixture_in_saf("marksheet_scan_300dpi.pdf"))
        do_back.assert_not_called()
        self.assertEqual(fixture_taps, [{"text": "marksheet_scan_300dpi.pdf"}],
                         "the located fixture must be tapped exactly once")

    def test_choose_fixture_in_saf_grid_stem_taps_once(self):
        grid = self._hierarchy(texts=("receipt_aug",))
        tapped = []
        with patch.object(a, "wait"), \
             patch.object(a, "soft_tap", return_value=False), \
             patch.object(a, "select_provider_root", return_value=True), \
             patch.object(a, "shot"), patch.object(a, "note"), \
             patch.object(a, "adb", return_value="Pixel"), \
             patch.object(a, "back") as do_back, \
             patch.object(a, "hierarchy", return_value=grid), \
             patch.object(a, "tap", side_effect=lambda **w: tapped.append(w)), \
             patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 0]):
            self.assertTrue(a.choose_fixture_in_saf("receipt_aug.pdf"))
        self.assertEqual(tapped, [{"text": "receipt_aug"}])
        do_back.assert_not_called()

    def test_choose_fixture_in_saf_missing_file_is_false(self):
        with patch.object(a, "wait"), \
             patch.object(a, "soft_tap", return_value=False), \
             patch.object(a, "select_provider_root", return_value=False), \
             patch.object(a, "await_fixture_row", return_value=None), \
             patch.object(a, "shot"), patch.object(a, "note"), \
             patch.object(a, "adb", return_value="Pixel"), \
             patch.object(a, "back") as do_back, \
             patch.object(a, "tap") as do_tap:
            self.assertFalse(a.choose_fixture_in_saf("marksheet_scan_300dpi.pdf"))
        do_tap.assert_not_called()
        do_back.assert_called_once_with()

    # ---- explicit settled result ---------------------------------------------

    def test_settle_import_valid_candidate_retained_then_revalidated(self):
        # A valid doc while the viewer is in front is only a candidate: retention
        # must run and the same id must re-validate before True.
        viewer = self._hierarchy(texts=("Compress",))
        seq = [set(), {"doc-1"}, {"doc-1"}]
        rtl_calls = []

        def fake_doc_ids():
            return seq.pop(0) if len(seq) > 1 else {"doc-1"}

        with patch.object(a, "hierarchy", return_value=viewer), \
             patch.object(a, "doc_ids", side_effect=fake_doc_ids), \
             patch.object(a, "doc_display_name", return_value="marksheet_scan_300dpi"), \
             patch.object(a, "doc_pdf_bytes", return_value=b"%PDF-1.4 fictional"), \
             patch.object(a, "return_to_library",
                          side_effect=lambda: rtl_calls.append(True) or True), \
             patch.object(a, "soft_tap"), patch.object(a, "shot"), \
             patch.object(a, "note"), patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 1, 2, 3]):
            self.assertTrue(a.settle_import("marksheet_scan_300dpi.pdf", set(), timeout=10))
        self.assertEqual(len(rtl_calls), 1, "retention must be attempted exactly once")

    def test_settle_import_valid_doc_without_retention_is_false(self):
        viewer = self._hierarchy(texts=("Compress",))
        with patch.object(a, "hierarchy", return_value=viewer), \
             patch.object(a, "doc_ids", return_value={"doc-1"}), \
             patch.object(a, "doc_display_name", return_value="x"), \
             patch.object(a, "doc_pdf_bytes", return_value=b"%PDF"), \
             patch.object(a, "return_to_library", return_value=False), \
             patch.object(a, "soft_tap"), patch.object(a, "shot"), \
             patch.object(a, "note"), patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 1]):
            self.assertFalse(a.settle_import("x.pdf", set(), timeout=10))

    def test_settle_import_post_retention_missing_id_reject(self):
        seq = [set(), {"doc-1"}, set()]

        def fake_doc_ids():
            return seq.pop(0) if len(seq) > 1 else set()

        with patch.object(a, "hierarchy", return_value=self._hierarchy()), \
             patch.object(a, "doc_ids", side_effect=fake_doc_ids), \
             patch.object(a, "doc_display_name", return_value="x"), \
             patch.object(a, "doc_pdf_bytes", return_value=b"%PDF"), \
             patch.object(a, "return_to_library", return_value=True), \
             patch.object(a, "soft_tap"), patch.object(a, "shot"), \
             patch.object(a, "note"), patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 1, 2]):
            self.assertFalse(a.settle_import("x.pdf", set(), timeout=10))

    def test_settle_import_post_retention_changed_id_reject(self):
        seq = [set(), {"doc-1"}, {"doc-2"}]

        def fake_doc_ids():
            return seq.pop(0) if len(seq) > 1 else {"doc-2"}

        with patch.object(a, "hierarchy", return_value=self._hierarchy()), \
             patch.object(a, "doc_ids", side_effect=fake_doc_ids), \
             patch.object(a, "doc_display_name", return_value="x"), \
             patch.object(a, "doc_pdf_bytes", return_value=b"%PDF"), \
             patch.object(a, "return_to_library", return_value=True), \
             patch.object(a, "soft_tap"), patch.object(a, "shot"), \
             patch.object(a, "note"), patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 1, 2]):
            self.assertFalse(a.settle_import("x.pdf", set(), timeout=10))

    def test_return_to_library_viewer_back_keep_library(self):
        viewer = self._hierarchy(texts=("Compress",))
        keep_sheet = self._hierarchy(texts=("Keep",))
        library = self._hierarchy(ids=("swNavScan",))
        seq = [viewer, keep_sheet, library]
        events = []
        with patch.object(a, "hierarchy",
                          side_effect=lambda: seq.pop(0) if len(seq) > 1 else seq[0]), \
             patch.object(a, "soft_tap",
                          side_effect=lambda **w: events.append(("tap", w)) or True), \
             patch.object(a, "back", side_effect=lambda: events.append(("back",))), \
             patch.object(a, "note"), patch.object(a.time, "sleep"):
            self.assertTrue(a.return_to_library())
        self.assertEqual(events, [("back",), ("tap", {"text": "Keep"})])

    def test_return_to_library_background_nav_under_keep_not_early_success(self):
        # The Library id is still visible behind the modal; it must not count until
        # Keep has actually been tapped.
        keep_with_nav = self._hierarchy(ids=("swNavScan", "swNavLibrary"), texts=("Keep",))
        library = self._hierarchy(ids=("swNavScan",))
        seq = [keep_with_nav, library]
        events = []
        with patch.object(a, "hierarchy",
                          side_effect=lambda: seq.pop(0) if len(seq) > 1 else seq[0]), \
             patch.object(a, "soft_tap",
                          side_effect=lambda **w: events.append(("tap", w)) or True), \
             patch.object(a, "back", side_effect=lambda: events.append(("back",))), \
             patch.object(a, "note"), patch.object(a.time, "sleep"):
            self.assertTrue(a.return_to_library())
        self.assertEqual(events, [("tap", {"text": "Keep"})])
        self.assertNotIn(("back",), events)

    def test_return_to_library_keep_tap_failure_is_false(self):
        keep_sheet = self._hierarchy(texts=("Keep",))
        old = a.CTX.get("required_failed")
        try:
            a.CTX["required_failed"] = False
            with patch.object(a, "hierarchy", return_value=keep_sheet), \
                 patch.object(a, "soft_tap", return_value=False), \
                 patch.object(a, "note"), patch.object(a.time, "sleep"):
                self.assertFalse(a.return_to_library())
            self.assertTrue(a.CTX["required_failed"], "failed Keep is a required-tap failure")
        finally:
            a.CTX["required_failed"] = bool(old)

    def test_return_to_library_missing_keep_is_false(self):
        viewer = self._hierarchy(texts=("Compress",))
        library = self._hierarchy(ids=("swNavScan",))
        seq = [viewer, library]
        with patch.object(a, "hierarchy",
                          side_effect=lambda: seq.pop(0) if len(seq) > 1 else seq[0]), \
             patch.object(a, "soft_tap", return_value=True), \
             patch.object(a, "back"), patch.object(a, "note"), \
             patch.object(a.time, "sleep"):
            self.assertFalse(a.return_to_library())

    def test_settle_import_refuses_double_import(self):
        library = self._hierarchy(ids=("swNavScan",))
        with patch.object(a, "hierarchy", return_value=library), \
             patch.object(a, "doc_ids", return_value={"a", "b"}), \
             patch.object(a, "soft_tap"), patch.object(a, "shot"), \
             patch.object(a, "note"):
            self.assertFalse(a.settle_import("x.pdf", set()))

    def test_settle_import_taps_consent_explicitly(self):
        consent = self._hierarchy(texts=("Import all",))
        library = self._hierarchy(ids=("swNavScan",))
        seq = [consent, library]
        tapped = []
        with patch.object(a, "hierarchy",
                          side_effect=lambda: seq.pop(0) if len(seq) > 1 else seq[0]), \
             patch.object(a, "soft_tap",
                          side_effect=lambda **w: tapped.append(w) or True), \
             patch.object(a, "doc_ids", return_value={"doc-1"}), \
             patch.object(a, "doc_display_name", return_value="x"), \
             patch.object(a, "doc_pdf_bytes", return_value=b"%PDF"), \
             patch.object(a, "return_to_library", return_value=True), \
             patch.object(a, "shot"), patch.object(a, "note"), \
             patch.object(a.time, "sleep"), \
             patch.object(a.time, "monotonic", side_effect=[0, 1, 2, 3]):
            self.assertTrue(a.settle_import("x.pdf", set(), timeout=10))
        self.assertIn({"text": "Import all"}, tapped)

    # ---- one-import-per-fixture orchestration --------------------------------

    def test_import_pdf_missing_entry_is_false_no_taps(self):
        stuck = self._hierarchy(ids=("swHomeSearch",))
        tapped = []
        with patch.object(a, "hierarchy", return_value=stuck), \
             patch.object(a, "doc_ids", return_value=set()), \
             patch.object(a, "soft_tap",
                          side_effect=lambda **w: tapped.append(w) or True), \
             patch.object(a, "note"), patch.object(a, "shot"), \
             patch.object(a.time, "sleep"):
            self.assertFalse(a.import_pdf("marksheet_scan_300dpi.pdf"))
        self.assertEqual(tapped, [])

    def test_import_pdf_saf_failure_is_false_without_settle(self):
        with patch.object(a, "reach_pdf_picker", return_value=True), \
             patch.object(a, "choose_fixture_in_saf", return_value=False), \
             patch.object(a, "settle_import") as settle, \
             patch.object(a, "return_to_library"), patch.object(a, "shot"), \
             patch.object(a, "note"), patch.object(a, "doc_ids", return_value=set()):
            self.assertFalse(a.import_pdf("marksheet_scan_300dpi.pdf"))
        settle.assert_not_called()

    def test_import_pdf_unsettled_is_false(self):
        before = {"existing"}
        with patch.object(a, "reach_pdf_picker", return_value=True), \
             patch.object(a, "choose_fixture_in_saf", return_value=True), \
             patch.object(a, "settle_import", return_value=False), \
             patch.object(a, "return_to_library"), patch.object(a, "shot"), \
             patch.object(a, "note"), patch.object(a, "doc_ids", return_value=before):
            self.assertFalse(a.import_pdf("rent_agreement_5p.pdf", before))

    def test_import_pdf_settled_is_true_and_reports_library(self):
        before = {"existing"}
        with patch.object(a, "reach_pdf_picker", return_value=True), \
             patch.object(a, "choose_fixture_in_saf", return_value=True), \
             patch.object(a, "settle_import", return_value=True) as settle, \
             patch.object(a, "return_to_library") as rtl, \
             patch.object(a, "shot"), patch.object(a, "doc_ids", return_value=before):
            self.assertTrue(a.import_pdf("receipt_aug.pdf", before))
        settle.assert_called_once_with("receipt_aug.pdf", before)
        # Retention belongs to settle_import, whose result import_pdf checks; the
        # orchestration layer must never call it again unchecked.
        rtl.assert_not_called()

    # ---- preserved strict guards / per-import validation ---------------------

    def test_validate_one_import_accepts_exact_fixture(self):
        ids, problems = a.validate_one_import(
            "marksheet_scan_300dpi.pdf", {"import_ok": True, "ids": ["doc-1"]},
            meta=lambda d: "marksheet_scan_300dpi",
            pdf=lambda d: b"%PDF-1.4 fictional")
        self.assertEqual((ids, problems), (["doc-1"], []))

    def test_validate_one_import_rejects_wrong_name_and_missing_marker(self):
        _, problems = a.validate_one_import(
            "marksheet_scan_300dpi.pdf", {"import_ok": True, "ids": ["doc-1"]},
            meta=lambda d: "unrelated-scan", pdf=lambda d: b"not a pdf")
        self.assertTrue(any("name" in p for p in problems))
        self.assertTrue(any("PDF" in p for p in problems))

    def test_validate_one_import_rejects_zero_and_multiple_ids(self):
        for new_ids in ([], ["a", "b"]):
            _, problems = a.validate_one_import(
                "x.pdf", {"import_ok": True, "ids": new_ids},
                meta=lambda d: "x", pdf=lambda d: b"%PDF")
            self.assertTrue(problems)

    def test_validate_one_import_rejects_import_not_ok(self):
        _, problems = a.validate_one_import(
            "x.pdf", {"import_ok": False, "ids": ["a"]},
            meta=lambda d: "x", pdf=lambda d: b"%PDF")
        self.assertTrue(any("did not complete" in p for p in problems))

    def test_strict_status_helpers_still_present(self):
        for name in ("classify", "verifier_is_valid", "has_output",
                     "assert_library_staged", "validate_staging_map"):
            self.assertTrue(callable(getattr(a, name, None)), name)
        self.assertNotIn("ok", inspect.signature(a.result).parameters)
        self.assertEqual(a.classify(None, None), a.NOT_TESTED)

    def test_binary_self_test_not_weakened(self):
        src = inspect.getsource(
            AtlasHarnessTests.test_generator_and_ground_truth_are_committed)
        self.assertIn("no fixtures should be committed as binaries", src)


if __name__ == "__main__":
    unittest.main()
