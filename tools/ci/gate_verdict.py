#!/usr/bin/env python3
"""Fail-closed verdicts for .github/workflows/gate.yml.

The Gradle steps run with continue-on-error so the reports are always uploaded;
this script is the ONLY place that turns their results into PASS/FAIL. A PASS
requires the real step outcome AND a complete report: a passing XML left behind
by a failed Gradle run, or a lint step that crashed without writing its report,
is a FAIL. Unknown or missing inputs are a FAIL (fail closed).

Usage (in CI):  python3 tools/ci/gate_verdict.py test     -> gate-result.txt
                python3 tools/ci/gate_verdict.py release  -> release-result.txt
Inputs come from environment variables set by the workflow (see main()).
Self-test:      python3 -m unittest tools/ci/test_gate_verdict.py
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

TEST_XML_GLOB = "app/build/test-results/testDebugUnitTest/*.xml"
LINT_REPORT_GLOBS = (
    "app/build/reports/lint-results-debug.txt",
    "app/build/intermediates/lint_*/**/lint-results-debug.txt",
)


def read_tests(xml_paths):
    """Return (total, failures+errors, skipped, failed_lines, unreadable_files)."""
    total = fail = skip = 0
    failed, unreadable = [], []
    for f in xml_paths:
        try:
            s = ET.parse(f).getroot()
        except (ET.ParseError, OSError):
            unreadable.append(os.path.basename(f))
            continue
        total += int(s.get("tests", 0))
        skip += int(s.get("skipped", 0))
        fail += int(s.get("failures", 0)) + int(s.get("errors", 0))
        for c in s.findall("testcase"):
            e = c.find("failure") if c.find("failure") is not None else c.find("error")
            if e is not None:
                msg = (e.get("message") or "").splitlines()[0][:220] if e.get("message") else ""
                failed.append(f"FAIL {c.get('classname', '').split('.')[-1]} > {c.get('name')} :: {msg}")
    return total, fail, skip, failed, unreadable


def read_lint(report_path):
    """Return (summary_line or None, error_lines)."""
    if not report_path or not os.path.exists(report_path):
        return None, []
    lines = open(report_path, encoding="utf-8", errors="replace").read().strip().splitlines()
    errors = []
    for i, l in enumerate(lines):
        if ": Error:" in l:
            errors.append("LINT " + l.split("/app/src/")[-1][:240])
            if i + 1 < len(lines):
                errors.append("     " + lines[i + 1].strip()[:200])
    return (lines[-1] if lines else "empty report"), errors


def test_verdict(tests_outcome, lint_required, lint_outcome, xml_paths, lint_report, filtered, min_full_tests):
    """Return (ok, lines). lines[-1] is the 'GATE PASS|FAIL ...' verdict (format parsed by tools/gate.ps1)."""
    total, fail, skip, failed, unreadable = read_tests(xml_paths)
    reasons = []
    if tests_outcome != "success":
        reasons.append(f"test step outcome={tests_outcome or 'missing'} (Gradle exit status was not success)")
    if total <= 0:
        reasons.append("no test results")
    if unreadable:
        reasons.append("unreadable test XML: " + ",".join(unreadable[:5]))
    if not filtered and min_full_tests > 0 and total < min_full_tests:
        reasons.append(f"full suite ran only {total} tests (< floor {min_full_tests}): incomplete run")

    if lint_required:
        summary, lint_errors = read_lint(lint_report)
        failed += lint_errors
        lint = summary if summary is not None else "missing report"
        if lint_outcome != "success":
            reasons.append(f"lint step outcome={lint_outcome or 'missing'}")
        if summary is None:
            reasons.append("lint required but no lint report")
        elif not summary.startswith("0 errors"):
            reasons.append("lint errors")
    else:
        lint = "skipped"

    ok = not reasons and fail == 0 and skip == 0
    verdict = f"GATE {'PASS' if ok else 'FAIL'} tests={total} failures={fail} skipped={skip} lint=[{lint}]"
    lines = failed[:60] + [f"REASON {r}" for r in reasons] + [verdict]
    return ok, lines


def release_verdict(assemble_outcome, log_text, missing_rules_text, apks, aabs):
    lines = log_text.splitlines() if log_text else []
    warnings = [l for l in lines if re.search(r"(^|\s)Warning: ", l)]
    errors = [l for l in lines if re.search(r"(^|\s)Error: ", l) or "Missing class" in l]
    missing = [l.strip() for l in (missing_rules_text or "").splitlines()
               if l.strip() and not l.lstrip().startswith("#")]
    apk = apks[0] if apks else ""
    aab = aabs[0] if aabs else ""
    apk_size = os.path.getsize(apk) if apk and os.path.exists(apk) else 0
    aab_size = os.path.getsize(aab) if aab and os.path.exists(aab) else 0
    reasons = []
    if assemble_outcome != "success":
        reasons.append(f"assemble step outcome={assemble_outcome or 'missing'} (Gradle exit status was not success)")
    if not log_text:
        reasons.append("no build log")
    if not apk:
        reasons.append("no release APK")
    if not aab:
        reasons.append("no release AAB")
    ok = not reasons and not missing and not errors
    report = [
        f"warnings={len(warnings)} errors={len(errors)} missing_rules={len(missing)}",
        f"apk={os.path.basename(apk) or '-'} size={apk_size / 1048576:.1f} MiB ({apk_size} bytes)",
        f"aab={os.path.basename(aab) or '-'} size={aab_size / 1048576:.1f} MiB ({aab_size} bytes)",
    ]
    report += [f"R8 {w}" for w in warnings[:15]]
    report += [f"MISSING {m}" for m in missing[:15]]
    report += [f"ERROR {e}" for e in errors[:15]]
    report += [f"REASON {r}" for r in reasons]
    report.append(f"RELEASE {'PASS' if ok else 'FAIL'}")
    return ok, report


def _first(patterns):
    for p in patterns:
        hits = sorted(glob.glob(p, recursive=True))
        if hits:
            return hits[0]
    return None


def _summary(text):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a") as fh:
            fh.write("```\n" + text + "\n```\n")


def main(argv):
    mode = argv[1] if len(argv) > 1 else ""
    if mode == "test":
        try:
            floor = int(os.environ.get("MIN_FULL_TESTS", "0"))
        except ValueError:
            floor = 0
        ok, lines = test_verdict(
            tests_outcome=os.environ.get("TESTS_OUTCOME", ""),
            lint_required=os.environ.get("LINT_REQUIRED", "").lower() == "true",
            lint_outcome=os.environ.get("LINT_OUTCOME", ""),
            xml_paths=glob.glob(TEST_XML_GLOB),
            lint_report=_first(LINT_REPORT_GLOBS),
            filtered=os.path.exists(".ci/test-filter.txt"),
            min_full_tests=floor,
        )
        out = "gate-result.txt"
    elif mode == "release":
        log = "r8-build.log"
        mp = "app/build/outputs/mapping/release/missing_rules.txt"
        ok, lines = release_verdict(
            assemble_outcome=os.environ.get("ASSEMBLE_OUTCOME", ""),
            log_text=open(log, encoding="utf-8", errors="replace").read() if os.path.exists(log) else "",
            missing_rules_text=open(mp, encoding="utf-8", errors="replace").read() if os.path.exists(mp) else "",
            apks=sorted(glob.glob("app/build/outputs/apk/release/*.apk")),
            aabs=sorted(glob.glob("app/build/outputs/bundle/release/*.aab")),
        )
        out = "release-result.txt"
    else:
        print("usage: gate_verdict.py test|release", file=sys.stderr)
        return 2
    text = "\n".join(lines)
    print(text)
    with open(out, "w") as fh:
        fh.write(text + "\n")
    _summary(text)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
