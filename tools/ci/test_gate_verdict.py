"""Control cases for tools/ci/gate_verdict.py: every known false-green path must FAIL.

Run: python3 -m unittest tools/ci/test_gate_verdict.py   (also runs as the first CI step)
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gate_verdict as gv  # noqa: E402

PASS_XML = '<testsuite name="com.x.ATest" tests="3" skipped="0" failures="0" errors="0"><testcase classname="com.x.ATest" name="a"/></testsuite>'
FAIL_XML = ('<testsuite name="com.x.BTest" tests="2" skipped="0" failures="1" errors="0">'
            '<testcase classname="com.x.BTest" name="b"><failure message="boom">x</failure></testcase></testsuite>')
SKIP_XML = '<testsuite name="com.x.CTest" tests="1" skipped="1" failures="0" errors="0"/>'


class Base(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def file(self, name, text):
        p = os.path.join(self.dir.name, name)
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(text)
        return p

    def verdict(self, **kw):
        args = dict(tests_outcome="success", lint_required=False, lint_outcome="skipped",
                    xml_paths=[self.file("a.xml", PASS_XML)], lint_report=None,
                    filtered=False, min_full_tests=0)
        args.update(kw)
        return gv.test_verdict(**args)


class TestGate(Base):
    def test_clean_pass_without_lint(self):
        ok, lines = self.verdict()
        self.assertTrue(ok, lines)
        self.assertTrue(lines[-1].startswith("GATE PASS tests=3 failures=0 skipped=0 lint=[skipped]"))

    def test_clean_pass_with_required_lint(self):
        rep = self.file("lint.txt", "No issues\n0 errors, 528 warnings")
        ok, lines = self.verdict(lint_required=True, lint_outcome="success", lint_report=rep)
        self.assertTrue(ok, lines)

    # --- control cases: each must FAIL ---
    def test_passing_xml_but_gradle_failed(self):
        ok, lines = self.verdict(tests_outcome="failure")
        self.assertFalse(ok)
        self.assertTrue(lines[-1].startswith("GATE FAIL"))

    def test_tests_cancelled_or_missing_outcome(self):
        for outcome in ("cancelled", "", "skipped"):
            ok, _ = self.verdict(tests_outcome=outcome)
            self.assertFalse(ok, outcome)

    def test_no_xml(self):
        ok, _ = self.verdict(xml_paths=[])
        self.assertFalse(ok)

    def test_failed_test(self):
        ok, lines = self.verdict(xml_paths=[self.file("a.xml", PASS_XML), self.file("b.xml", FAIL_XML)])
        self.assertFalse(ok)
        self.assertTrue(any(l.startswith("FAIL BTest > b") for l in lines))

    def test_skipped_test(self):
        ok, _ = self.verdict(xml_paths=[self.file("c.xml", SKIP_XML)])
        self.assertFalse(ok)

    def test_unreadable_xml(self):
        ok, _ = self.verdict(xml_paths=[self.file("a.xml", PASS_XML), self.file("t.xml", "<testsuite tests=")])
        self.assertFalse(ok)

    def test_lint_required_but_report_missing(self):
        ok, lines = self.verdict(lint_required=True, lint_outcome="success", lint_report=None)
        self.assertFalse(ok)
        self.assertIn("lint=[missing report]", lines[-1])

    def test_lint_step_failed_with_clean_old_report(self):
        rep = self.file("lint.txt", "0 errors, 1 warnings")
        ok, _ = self.verdict(lint_required=True, lint_outcome="failure", lint_report=rep)
        self.assertFalse(ok)

    def test_lint_errors(self):
        rep = self.file("lint.txt", "/w/app/src/main/A.kt:3: Error: WrongConstant [WrongConstant]\n  x\n1 errors, 0 warnings")
        ok, lines = self.verdict(lint_required=True, lint_outcome="success", lint_report=rep)
        self.assertFalse(ok)
        self.assertTrue(any(l.startswith("LINT main/A.kt") for l in lines))

    def test_full_suite_below_floor(self):
        ok, _ = self.verdict(min_full_tests=1800)
        self.assertFalse(ok)

    def test_filtered_run_ignores_floor(self):
        ok, lines = self.verdict(min_full_tests=1800, filtered=True)
        self.assertTrue(ok, lines)


class TestRelease(Base):
    def outputs(self):
        return [self.file("app-release.apk", "x")], [self.file("app-release.aab", "y")]

    def test_clean_release(self):
        apks, aabs = self.outputs()
        ok, lines = gv.release_verdict("success", "BUILD SUCCESSFUL", "", apks, aabs)
        self.assertTrue(ok, lines)
        self.assertEqual(lines[-1], "RELEASE PASS")

    def test_gradle_failed_but_outputs_exist(self):
        apks, aabs = self.outputs()
        ok, lines = gv.release_verdict("failure", "BUILD FAILED", "", apks, aabs)
        self.assertFalse(ok)
        self.assertEqual(lines[-1], "RELEASE FAIL")

    def test_missing_aab(self):
        apks, _ = self.outputs()
        ok, _ = gv.release_verdict("success", "BUILD SUCCESSFUL", "", apks, [])
        self.assertFalse(ok)

    def test_missing_rules(self):
        apks, aabs = self.outputs()
        ok, _ = gv.release_verdict("success", "BUILD SUCCESSFUL", "-dontwarn com.foo.Bar\n", apks, aabs)
        self.assertFalse(ok)

    def test_r8_error_line(self):
        apks, aabs = self.outputs()
        ok, _ = gv.release_verdict("success", "ERROR: R8: Missing class com.foo.Bar", "", apks, aabs)
        self.assertFalse(ok)

    def test_no_log(self):
        apks, aabs = self.outputs()
        ok, _ = gv.release_verdict("success", "", "", apks, aabs)
        self.assertFalse(ok)


if __name__ == "__main__":
    unittest.main()
