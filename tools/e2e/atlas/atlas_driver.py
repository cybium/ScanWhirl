#!/usr/bin/env python3
"""Tool Atlas: drive every ScanWhirl tool on a disposable CI EMULATOR.

This is an extension of tools/e2e/flow_driver.py, not a replacement. It reuses the
same accessibility-first helpers (view ids / content descriptions, never fixed
coordinates), pushes only fictional fixtures, screenshots EVERY step into
<tool>/NN_<what>.png, pulls the real output and runs verify/verify_result.py with
the matching journey/spec.

Safety: rejects a non-CI host, a real device, or a non-test package before adb use.
A row is PASS only with an output file AND a valid, passing numeric verifier AND all
required interactions succeeding. Any FAIL/BLOCKED/NOT_TESTED/crash reddens the job;
the report is always written so the artifact preserves evidence even on red.
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import traceback

HERE = Path(__file__).resolve().parent
E2E = HERE.parent
if str(E2E) not in sys.path:
    sys.path.insert(0, str(E2E))

from flow_driver import (  # noqa: E402  (path set above)
    adb, hierarchy, find, find_within, tap, tap_node, wait, back, scroll, seek,
    capture as fd_capture, strings, launch, locale,
)
from uitool import dump_xml  # noqa: E402

PKG = "com.scanwhirl.app.test"
FIX = HERE / "fixtures_v2"
VERIFY = HERE / "verify" / "verify_result.py"
OUT = Path(os.environ.get("ATLAS_OUT", E2E.parent.parent / "artifacts" / "atlas"))
FIXTURE_REMOTE = f"/sdcard/Android/data/{PKG}/files/m07_fixtures"
DOWNLOAD = "/sdcard/Download"

# Tool id -> English tile title (from app/src/main/res/values/strings*.xml).
TOOL_TITLES = {
    "form_photo": "Form photo",
    "resize": "Resize",
    "compress": "Compress",
    "id_card": "ID card",
    "merge": "Merge",
    "split": "Split",
    "extract": "Extract",
    "protect": "Protect",
    "ocr": "OCR",
    "ocr_pdf": "OCR PDF",
    "pdf_to_jpg": "PDF to JPG",
    "sign_all": "Sign",
    "watermark": "Watermark",
    "page_numbers": "Page numbers",
    "import_pdf": "Import PDF",
    "trash_restore": "Trash",
    "vault": "Vault",
}
ALL_TOOLS = list(TOOL_TITLES)

CTX = {"tool": "", "step": 0, "shots": [], "rows": [], "notes": []}


# --------------------------------------------------------------- harness guard
def guard():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("CI-only: Tool Atlas refuses a local/physical-device run")
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not re.fullmatch(r"emulator-\d+", serial):
        raise RuntimeError(f"emulator serial required, got {serial!r}")
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Not a QEMU emulator")
    installed = adb("shell", "pm", "list", "packages", PKG)
    if PKG not in installed:
        raise RuntimeError(f"{PKG} is not installed on this emulator")
    if not VERIFY.is_file():
        raise RuntimeError("verify/verify_result.py missing from atlas bundle")
    missing = [p for p in REQUIRED_FIXTURES if not (FIX / p).is_file()]
    if missing:
        raise RuntimeError("fixtures missing: " + ", ".join(missing))


REQUIRED_FIXTURES = [
    "exam_photo.jpg", "signature_on_paper.jpg", "id_front.jpg", "id_back.jpg",
    "marksheet_scan_300dpi.pdf", "rent_agreement_5p.pdf", "electricity_bill_3p.pdf",
    "receipt_aug.jpg", "receipt_sep.jpg", "receipt_oct.jpg",
    "receipt_aug.pdf", "receipt_sep.pdf", "receipt_oct.pdf",
    "ground_truth.json", "manifest.json",
    "references/id_front.png", "references/id_back.png",
    "references/signature_crop.png", "references/portrait_crop.png",
]

# PDFs prepare_library() must actually import into the Library before any tool runs.
STAGING_PDFS = ("marksheet_scan_300dpi.pdf", "rent_agreement_5p.pdf",
                "receipt_aug.pdf", "receipt_sep.pdf", "receipt_oct.pdf")

# --------------------------------------------------- confirmed import selectors
# Every selector below comes from the SHIPPED source / the real emulator hierarchy,
# never from a guess. Evidence:
#   swEmptyImport  - LibraryScreen.kt:459, empty Library "Import photos or a PDF".
#   swNavScan      - ShellActivity.kt:639 and the real 2deddb29 hierarchy; its
#                    content-desc is "Scan. Open scan and import options.".
#   swCreateImport - CreateSheet.kt:69, the Import PDF row (home_create_import).
#   importPdf      - ImportSheet.kt:42 / sheet_import.xml, the PDF row ("PDF file").
#   Show roots     - Android DocumentsUI (SAF) navigation control.
# The legacy `create_disc_desc` ("Scan or import a document") is NOT in the real
# hierarchy, so it is deliberately never used as a fallback.
SAF_ROOT_DESC = "Show roots"
IMPORT_CONSENT_TEXT = "Import all"   # DevicePdfGate decision sheet (explicit consent)
IMPORT_KEEP_TEXT = "Keep"            # import retention sheet
PDF_MARKER = b"%PDF"


# ------------------------------------------------------------------- evidence
def shot(what):
    """Screenshot + XML of the current screen as <tool>/NN_<what>.png/.xml."""
    tool_dir = OUT / CTX["tool"]
    tool_dir.mkdir(parents=True, exist_ok=True)
    CTX["step"] += 1
    tag = f"{CTX['step']:02d}_{what}"
    data = dump_xml()
    if data:
        (tool_dir / f"{tag}.xml").write_bytes(data)
    png = tool_dir / f"{tag}.png"
    png.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    CTX["shots"].append(tag)
    # Blank-frame detection (secure screens are allowed to be black; XML is evidence).
    stats = subprocess.run(
        [sys.executable, str(E2E / "uitool.py"), "image-stats", "--file", str(png),
         "--min-unique", "8", "--min-mean", "2"],
        capture_output=True, text=True,
    )
    return tag, stats.stdout.strip() or stats.stderr.strip()


def note(text):
    CTX["notes"].append(str(text).replace("\n", " ")[:400])


def soft_tap(**want):
    """Tap if the control exists; record the outcome, never throw."""
    try:
        root = hierarchy()
        node = find(root, **want)
        tap_node(root, node)
        time.sleep(0.5)
        return True
    except Exception as exc:  # noqa: BLE001
        note(f"soft_tap {want}: {type(exc).__name__}: {exc}")
        return False


def type_text(text):
    # `adb shell input text` treats a literal space as a key separator; encode it.
    adb("shell", "input", "text", str(text).replace(" ", "%s"))
    time.sleep(0.3)
    back()  # dismiss keyboard only


# ------------------------------------------------------------ fixture staging
def push_fixtures(names):
    adb("shell", "mkdir", "-p", FIXTURE_REMOTE)
    adb("shell", "mkdir", "-p", DOWNLOAD)
    pushed = []
    for name in names:
        src = FIX / name
        if not src.is_file():
            raise RuntimeError(f"fixture not found: {name}")
        for remote in (FIXTURE_REMOTE, DOWNLOAD + "/ScanWhirlQA"):
            adb("shell", "mkdir", "-p", remote)
            adb("push", str(src), f"{remote}/{Path(name).name}")
        pushed.append(name)
    note(f"pushed {len(pushed)} fictional fixtures")
    return pushed


# --------------------------------------------------------------- UI navigation
def open_tool(title):
    """Tools tab -> search -> tile. Always start from a clean Library.

    A previous tool may leave a result sheet, stamp editor or SAF task on top;
    relaunching the shell (app data preserved) is the only reliable way to make
    one tool's failure independent of the next tool's evidence.
    """
    locale("en", "1.0")
    launch()
    tap(desc="Tools")
    wait(rid="swToolsSearch")
    shot("tools_hub")
    tap(cls="EditText")
    type_text(title)
    root = hierarchy()
    try:
        find(root, desc=title)
    except AssertionError:
        note(f"tile {title!r} not in search results; retrying unfiltered")
    if not soft_tap(desc=title):
        if not soft_tap(text=title):
            raise RuntimeError(f"tool tile {title!r} not tappable")
    time.sleep(1.2)
    shot("tool_opened")


def pick_docs(count=1):
    """Tap the first N rows in a ToolSetupActivity document chooser."""
    picked = 0
    for _ in range(count):
        root = hierarchy()
        rows = [n for n in root.iter("node")
                if (n.get("resource-id") or "").endswith("/toolSetupDocRow")]
        if picked >= len(rows):
            break
        tap_node(root, rows[picked])
        picked += 1
        time.sleep(0.5)
    note(f"selected {picked} document row(s)")
    # Selecting at least one source document is required; zero rows must never PASS.
    if picked == 0:
        CTX["required_failed"] = True
    return picked


def run_setup():
    """Pinned Continue on ToolSetupActivity, then wait for a result screen."""
    if not soft_tap(rid="toolSetupRun"):
        # The label is Continue in English; try copy as a last resort.
        if not soft_tap(text=strings("en")["tools_setup_run"]):
            CTX["required_failed"] = True
    for _ in range(60):
        root = hierarchy()
        ids = [n.get("resource-id") or "" for n in root.iter("node")]
        if any(i.endswith("/toolResultName") or i.endswith("/toolResultSummary")
               or i.endswith("/exportResultName") for i in ids):
            return True
        if any((n.get("text") or "").startswith(("Couldn't", "Can't", "Not enough"))
               for n in root.iter("node")):
            return False
        time.sleep(2)
    return False


def doc_ids():
    out = adb("shell", "run-as", PKG, "ls", "files/documents")
    return {line.strip() for line in out.splitlines() if line.strip()}


def pull_new_pdf(before, label="output"):
    """Pull newly created library documents as PDFs; return list of local paths."""
    after = doc_ids()
    new = sorted(after - before)
    paths = []
    for doc_id in new:
        # Preserve the app's own display name: J10 verifies the exact filename.
        app_name = ""
        try:
            meta = adb("shell", "run-as", PKG, "cat",
                       f"files/documents/{doc_id}/meta.txt")
            app_name = meta.splitlines()[0].strip() if meta.splitlines() else ""
        except Exception:  # noqa: BLE001
            pass
        safe = re.sub(r'[\\/:*?"<>|]', "_", app_name).strip() or f"{label}_{doc_id}"
        local = OUT / CTX["tool"] / f"{safe}.pdf"
        local.parent.mkdir(parents=True, exist_ok=True)
        data = adb("exec-out", "run-as", PKG, "cat",
                   f"files/documents/{doc_id}/scan.pdf", binary=True)
        if data:
            local.write_bytes(data)
            paths.append(local)
            note(f"pulled {doc_id}/scan.pdf as {local.name} ({len(data)} bytes)")
    if not new:
        note("no new library document was created by this tool")
    return paths


def pull_new_files(before, suffix=None, label="output"):
    """Pull newly appearing files from /sdcard/Download into the tool folder."""
    listing = adb("shell", "find", DOWNLOAD, "-type", "f")
    now = {line.strip() for line in listing.splitlines() if line.strip()}
    new = sorted(now - before)
    paths = []
    for remote in new:
        if suffix and not remote.lower().endswith(suffix):
            continue
        name = Path(remote).name
        local = OUT / CTX["tool"] / f"{label}_{name}"
        local.parent.mkdir(parents=True, exist_ok=True)
        data = adb("exec-out", "cat", remote, binary=True)
        if data:
            local.write_bytes(data)
            paths.append(local)
            note(f"pulled {remote} ({len(data)} bytes)")
    return paths


# ------------------------------------------------------------------- verification
CHECK_KEYS = ("check", "pass", "measured", "expected")


def checks_from_report(report):
    """Schema-validate a verifier report. Return (checks, error); a valid report has a
    non-empty list of objects with all four keys and an EXACT boolean `pass`."""
    if not isinstance(report, dict):
        return [], "verifier report is not a JSON object"
    raw = report.get("checks")
    if not isinstance(raw, list) or not raw:
        return [], "verifier report has no checks"
    out = []
    for c in raw:
        if not isinstance(c, dict) or any(k not in c for k in CHECK_KEYS):
            return [], "check is not an object with check/pass/measured/expected"
        if not isinstance(c["check"], str) or not c["check"]:
            return [], "check name is not a non-empty string"
        if not isinstance(c["pass"], bool):
            return [], "check pass is not a bool"
        out.append({"check": c["check"], "pass": c["pass"],
                    "measured": c["measured"], "expected": c["expected"]})
    return out, None


def verify(journey, path, extra=None, spec=None, label="verifier"):
    """Run the real verifier. PASS needs exit 0 AND a freshly parsed, schema-valid,
    non-empty check report from THIS invocation whose every `pass` is exactly True.
    A stale JSON file for this output is removed before the child runs."""
    if not path or not has_output(path):
        return {"status": FAIL, "reason": "no output file to verify",
                "checks": [], "parsed": False, "exit": None}
    json_path = Path(path).with_suffix(".json")
    try:
        json_path.unlink()  # only the exact per-output report; never a broad delete
    except FileNotFoundError:
        pass
    except OSError as exc:
        return {"status": FAIL, "reason": f"cannot clear stale verifier JSON: {exc}",
                "checks": [], "parsed": False, "exit": None}
    cmd = [sys.executable, str(VERIFY), "--journey", journey,
           "--file", str(path), "--json", str(json_path)]
    if extra:
        cmd += [str(a) for a in extra]
    if spec:
        spec_file = OUT / CTX["tool"] / "spec.json"
        spec_file.write_text(json.dumps(spec), encoding="utf-8")
        cmd += ["--spec", str(spec_file)]
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=str(HERE))
    (OUT / CTX["tool"] / f"{label}.log").write_text(
        "CMD: " + " ".join(cmd) + "\n\n" + proc.stdout + "\n" + proc.stderr,
        encoding="utf-8",
    )
    parsed, checks = False, []
    reason = f"exit {proc.returncode}"
    try:
        report = json.loads(json_path.read_text(encoding="utf-8"))
        checks, err = checks_from_report(report)
        if err:
            reason = f"exit {proc.returncode}; invalid verifier report: {err}"
            checks = [{"check": "verifier_report", "pass": False,
                       "measured": err, "expected": "schema-valid checks"}]
        else:
            parsed = True
    except Exception as exc:  # noqa: BLE001
        reason = f"exit {proc.returncode}; unreadable verifier report: {type(exc).__name__}"
        checks = [{"check": "verifier_report", "pass": False,
                   "measured": type(exc).__name__, "expected": "fresh readable JSON report"}]
    status = PASS if (proc.returncode == 0 and parsed
                      and all(c["pass"] is True for c in checks)) else FAIL
    return {"status": status, "reason": reason, "checks": checks,
            "parsed": parsed, "exit": proc.returncode}


def result(detail="", verify_result=None, output=None):
    return {"detail": detail, "verifier": verify_result,
            "output": str(output) if output else None}


# ------------------------------------------------------- fail-closed status model
PASS, FAIL, BLOCKED, NOT_TESTED = "PASS", "FAIL", "BLOCKED", "NOT_TESTED"
NON_PASS = {FAIL, BLOCKED, NOT_TESTED}


def verifier_is_valid(ver):
    """Independently re-validate the verifier record; never trust a bare `status`.
    A PASS status is credible only with a freshly parsed report, exit 0 and every
    recorded check passing exactly True. A genuine FAIL (some false checks) stays
    valid evidence -- it reddens the job, it is never promoted to PASS."""
    if not isinstance(ver, dict) or ver.get("status") not in (PASS, FAIL):
        return False
    if ver.get("parsed") is not True:
        return False
    checks = ver.get("checks")
    if not isinstance(checks, list) or not checks:
        return False
    normalised, err = checks_from_report({"checks": checks})
    if err or len(normalised) != len(checks):
        return False
    all_pass = all(c["pass"] is True for c in checks)
    if ver["status"] == PASS and (not all_pass or ver.get("exit") != 0):
        return False
    if ver["status"] == FAIL and all_pass:
        return False
    return True


def has_output(output):
    """An output claim counts only when the pulled artifact is a NON-EMPTY real file."""
    if not output:
        return False
    try:
        p = Path(str(output))
        return p.is_file() and p.stat().st_size > 0
    except OSError:
        return False


def classify(ver, output, required_ok=True):
    """Device-free status decision. PASS requires a non-empty output file AND an
    independently valid verifier whose status is consistent with its checks AND all
    required interactions succeeding. Anything else is NOT_TESTED/FAIL/BLOCKED."""
    if not required_ok:
        return BLOCKED
    if not has_output(output) or not verifier_is_valid(ver):
        return NOT_TESTED
    return ver["status"]


def blocked_row(tool, reason):
    """A requested tool we could not run because of a staging/harness precondition."""
    return {
        "tool": tool, "status": BLOCKED, "steps": 0, "shots": 0, "shot_names": [],
        "verifier": "none", "verifier_reason": reason, "reason": reason,
        "detail": reason, "output": None, "checks": [], "notes": [],
    }


def require_tap(**want):
    """A required interaction: a missing control marks the tool so it cannot PASS."""
    ok = soft_tap(**want)
    if not ok:
        CTX["required_failed"] = True
    return ok


def normalize_fixture_name(value):
    """Compare a Library display name with a fixture filename, ignoring case and
    word separators (the importer may drop the extension or change spacing)."""
    stem = Path(str(value or "")).stem
    return re.sub(r"[\s_\-]+", "", stem.casefold())


def doc_display_name(doc_id):
    try:
        meta = adb("shell", "run-as", PKG, "cat", f"files/documents/{doc_id}/meta.txt")
        return meta.splitlines()[0].strip() if meta.splitlines() else ""
    except Exception:  # noqa: BLE001
        return ""


def doc_pdf_bytes(doc_id):
    return adb("exec-out", "run-as", PKG, "cat",
               f"files/documents/{doc_id}/scan.pdf", binary=True)


# --------------------------------------------------------------- import routing
# Pure hierarchy readers: resource ids are locale-independent and matched by their
# bare name, exactly like flow_driver.match(). No coordinate or copy guessing.
def node_rid(node):
    return (node.get("resource-id") or "").rsplit("/", 1)[-1].rsplit(":id/", 1)[-1]


def node_text(node):
    return node.get("text") or ""


def node_desc(node):
    return node.get("content-desc") or ""


def import_entry_plan(root):
    """Pure: pick the ONE confirmed PDF-import route visible in a UI hierarchy.

    `root` is an xml.etree hierarchy (the same object flow_driver.hierarchy() returns
    and the exact shape captured in the emulator artifact). Returns a plan dict:
      kind = 'saf'             -> the document picker is already in front
      kind = 'import_sheet_pdf'-> tap rid importPdf   (ImportSheet, from swEmptyImport)
      kind = 'create_menu'     -> tap rid swCreateImport (CreateSheet, from swNavScan)
      kind = 'empty_import'    -> tap rid swEmptyImport (empty Library)
      kind = 'nav_scan'        -> tap rid swNavScan      (populated Library)
      kind = 'none'            -> NO confirmed entry: fail closed, never guess
    The Photos routes (swEmptyScan / swCreatePhotos / importPhotos) are deliberately
    absent: a PDF import must never be routed through the picture picker.
    """
    if root is None:
        return {"kind": "none", "by": None, "value": None}
    nodes = list(root.iter("node"))
    rids = {node_rid(n) for n in nodes}
    descs = {node_desc(n) for n in nodes}
    if SAF_ROOT_DESC in descs:
        return {"kind": "saf", "by": None, "value": None}
    if "importPdf" in rids:
        return {"kind": "import_sheet_pdf", "by": "rid", "value": "importPdf"}
    if "swCreateImport" in rids:
        return {"kind": "create_menu", "by": "rid", "value": "swCreateImport"}
    if "swEmptyImport" in rids:
        return {"kind": "empty_import", "by": "rid", "value": "swEmptyImport"}
    if "swNavScan" in rids:
        return {"kind": "nav_scan", "by": "rid", "value": "swNavScan"}
    return {"kind": "none", "by": None, "value": None}


def tap_plan_entry(plan):
    """Tap only the confirmed control a plan names; 'saf' needs no tap."""
    if plan.get("kind") == "saf":
        return True
    by, value = plan.get("by"), plan.get("value")
    if by == "rid":
        return soft_tap(rid=value)
    if by == "desc":
        return soft_tap(desc=value)
    if by == "text":
        return soft_tap(text=value)
    return False


def reach_pdf_picker():
    """Walk the confirmed entry chain to SAF, tapping each control at most once."""
    tapped = set()
    for _ in range(4):
        plan = import_entry_plan(hierarchy())
        kind = plan["kind"]
        if kind == "saf":
            return True
        if kind == "none":
            note("no confirmed PDF import entry on this screen (no guessed fallback)")
            return False
        key = (kind, plan.get("by"), plan.get("value"))
        if key in tapped:
            note(f"import entry {key} did not advance; refusing a second tap")
            return False
        tapped.add(key)
        if not tap_plan_entry(plan):
            note(f"could not tap confirmed import entry {key}")
            return False
        shot(f"import_entry_{kind}")
        time.sleep(0.8)
    note("import entry chain did not reach SAF in bounded steps")
    return False


def validate_one_import(name, info, meta=None, pdf=None):
    """Check EXACTLY one imported fixture: one new id, matching name, PDF marker.
    `meta`/`pdf` are injectable so this is pure in tests. Returns (ids, problems)."""
    meta = meta or doc_display_name
    pdf = pdf or doc_pdf_bytes
    problems, ids = [], []
    if not isinstance(info, dict):
        return [], ["staging did not produce a fixture mapping"]
    new_ids = info.get("ids") or []
    if not info.get("import_ok"):
        problems.append(f"{name}: import did not complete")
    if len(new_ids) != 1:
        problems.append(f"{name}: expected exactly 1 new document, got {len(new_ids)}")
        return ids, problems
    doc_id = new_ids[0]
    ids.append(doc_id)
    display = meta(doc_id)
    if normalize_fixture_name(display) != normalize_fixture_name(name):
        problems.append(f"{name}: library name {display!r} does not match fixture")
    data = pdf(doc_id)
    if not isinstance(data, (bytes, bytearray)) or not data.startswith(PDF_MARKER):
        problems.append(f"{name}: document {doc_id} has no PDF content marker")
    return ids, problems


def validate_staging_map(mapping, meta=None, pdf=None):
    """Check EXPECTED fixture identity, not a bare count. `meta`/`pdf` are injectable
    lookup callables so this is testable without a device. Returns (ids, problems)."""
    if not isinstance(mapping, dict):
        return [], ["staging did not produce a fixture mapping"]
    problems, ids = [], []
    for name in STAGING_PDFS:
        one_ids, one_problems = validate_one_import(name, mapping.get(name), meta, pdf)
        for doc_id in one_ids:
            if doc_id in ids:
                problems.append(f"{name}: document id {doc_id} reused by another fixture")
            ids.append(doc_id)
        problems.extend(one_problems)
    return ids, problems


def assert_library_staged(mapping):
    """Hard precondition: every required fixture is present with the right identity."""
    ids, problems = validate_staging_map(mapping)
    if problems:
        raise RuntimeError("fixture staging incomplete: " + "; ".join(problems) +
                           "; all requested tools are BLOCKED")
    return set(ids)


# --------------------------------------------------------------- tool recipes
def prepare_library():
    """Push fixtures and import each PDF, recording the new document id per fixture."""
    push_fixtures([
        *STAGING_PDFS,
        "exam_photo.jpg", "signature_on_paper.jpg",
        "id_front.jpg", "id_back.jpg", "ground_truth.json",
    ])
    locale("en", "1.0")
    launch()
    shot("library_before_import")
    mapping = {}
    for name in STAGING_PDFS:
        before = doc_ids()
        ok = import_pdf(name, before)
        added = sorted(doc_ids() - before)
        mapping[name] = {"import_ok": bool(ok), "ids": added}
        note(f"staged {name}: import_ok={ok}; new ids={added}")
    return mapping


def choose_fixture_in_saf(name):
    """Find the fixture in the real DocumentsUI picker: provider root, then Download."""
    try:
        wait(timeout=8, desc=SAF_ROOT_DESC)
        soft_tap(desc=SAF_ROOT_DESC)
    except AssertionError:
        note("SAF 'Show roots' not found (picker may already list files)")
    # The test APK ships a DocumentsProvider named exactly this; SAF lists it as a root.
    if soft_tap(text="ScanWhirl test fixtures"):
        note("SAF: using ScanWhirl test fixtures provider")
    else:
        soft_tap(text=adb("shell", "getprop", "ro.product.model"))
        try:
            wait(text="Download")
            soft_tap(text="Download")
            try:
                wait(text="ScanWhirlQA")
                soft_tap(text="ScanWhirlQA")
            except AssertionError:
                pass
        except AssertionError:
            note("SAF: could not reach a files root")
    shot("picker")
    if not soft_tap(text=name):
        note(f"SAF: fixture {name} not listed")
        back()
        return False
    return True


def settle_import(name, before, timeout=120):
    """Resolve the import's settled outcome. A valid document is only a CANDIDATE:
    it exists in the repo while the viewer is still in front. The viewer's
    Back -> Keep retention must actually complete and the SAME one id, name and
    PDF marker must be re-validated afterwards before this returns True. No
    positive result is registered while the viewer has not been resolved."""
    before_ids = set(before)
    deadline = time.monotonic() + timeout
    candidate = None
    while candidate is None and time.monotonic() < deadline:
        texts = {node_text(n) for n in hierarchy().iter("node")}
        if IMPORT_CONSENT_TEXT in texts:            # explicit source consent
            soft_tap(text=IMPORT_CONSENT_TEXT)
            time.sleep(1)
            continue
        added = sorted(set(doc_ids()) - before_ids)
        if len(added) > 1:
            note(f"{name}: {len(added)} documents created; refusing a double import")
            return False
        _, problems = validate_one_import(
            name, {"import_ok": True, "ids": added}, doc_display_name, doc_pdf_bytes)
        if not problems and len(added) == 1:
            candidate = added[0]                    # remember the EXACT new id
            break
        time.sleep(1)
    if candidate is None:
        note(f"{name}: import did not settle within {timeout}s")
        return False
    # Candidate found is NOT success: drive the retention decision. Its result is
    # required, not optional; a failed/unknown return is never counted as kept.
    if not return_to_library():
        note(f"{name}: Back/Keep retention did not reach a settled Library")
        return False
    # Re-validate the SAME id after retention: missing, extra or changed ids are
    # unknown state, never a PASS. Nothing is discarded to make this look clean.
    final_added = sorted(set(doc_ids()) - before_ids)
    if final_added != [candidate]:
        note(f"{name}: post-retention ids {final_added} != candidate {[candidate]}")
        return False
    _, problems = validate_one_import(
        name, {"import_ok": True, "ids": final_added}, doc_display_name, doc_pdf_bytes)
    if problems:
        note(f"{name}: post-retention validation failed: " + "; ".join(problems))
        return False
    shot("import_settled")
    note(f"settled {name}: retained id={candidate}; name+PDF marker verified after retention")
    return True


def return_to_library():
    """Complete the viewer's retention decision and confirm a settled Library.

    The Library stays visible behind the Keep modal, so a visible Keep confirmation
    is handled FIRST: no Library/id-based success may be acknowledged while that
    modal is up. Only an actual Keep tap followed by a Library entry counts. The
    Keep tap is required (failure returns False); Discard is never tapped and no
    import route is taken again.
    """
    kept = False
    for _ in range(6):
        root = hierarchy()
        texts = {node_text(n) for n in root.iter("node")}
        if IMPORT_KEEP_TEXT in texts:                # modal in front: decide first
            if not require_tap(text=IMPORT_KEEP_TEXT):
                note("Keep sheet visible but the required Keep tap failed")
                return False
            kept = True
            time.sleep(0.5)
            continue
        if import_entry_plan(root)["kind"] in ("empty_import", "nav_scan"):
            return kept                              # only after an actual Keep
        back()
        time.sleep(0.5)
    note("viewer did not resolve to a settled Library in bounded steps")
    return False


def import_pdf(name, before=None):
    """Reach the confirmed PDF route, pick the fixture in SAF and wait for a settled,
    validated import. One entry tap per screen, one fixture tap, one new document.

    settle_import() owns the whole settle: it drives the viewer's Back -> Keep
    retention, checks that required result, and re-validates the same id/name/PDF
    after retention. True therefore never means 'a doc appeared before Back/Keep'.
    """
    if before is None:
        before = doc_ids()
    if not reach_pdf_picker():
        note(f"{name}: could not reach the PDF picker through a confirmed entry")
        return False
    if not choose_fixture_in_saf(name):
        return False
    if not settle_import(name, before):
        return False
    shot("library_after_import")
    return True


# --- file-output tools -------------------------------------------------------
def f_form_photo():
    # Snapshot DOWNLOAD BEFORE driving the tool; a snapshot taken at pull time would
    # be empty and could never observe an output (harness false negative).
    before = {str(p) for p in adb("shell", "find", DOWNLOAD, "-type", "f").splitlines()}
    push_fixtures(["exam_photo.jpg", "signature_on_paper.jpg"])
    open_tool(TOOL_TITLES["form_photo"])
    shot("form_setup")
    # The Form-ready activity has its own photo/signature pickers; try the visible
    # primary actions by accessible text, then the SAF/photo picker.
    advanced = 0
    for label in ("Choose photo", "Add photo", "Photo", "Continue", "Next"):
        if soft_tap(text=label):
            advanced += 1
            shot("form_step")
            break
    for label in ("exam_photo.jpg", "signature_on_paper.jpg"):
        if soft_tap(text=label):
            shot("form_picked")
            break
    if require_tap(rid="toolSetupRun"):
        shot("form_run")
    time.sleep(3)
    shot("form_result")
    files = pull_new_files(before, label="form_output")
    # Verifier J1 expects a 200x230 photo and a 140x60 signature.
    photo = next((p for p in files if "photo" in p.name.lower()), None)
    sig = next((p for p in files if "sign" in p.name.lower()), None)
    extra = ["--file2", str(sig)] if sig else []
    return result(
        f"advanced={advanced} steps; pulled={[p.name for p in files]}",
        verify("J1", photo or (files[0] if files else None), extra),
        photo or (files[0] if files else None),
    )


def f_resize():
    before = doc_ids()
    open_tool(TOOL_TITLES["resize"])
    pick_docs(1)
    shot("resize_setup")
    # Exact resize has width/height fields; type the task's 200x230 spec.
    fields = [n for n in hierarchy().iter("node")
              if (n.get("class") or "").endswith("EditText")]
    for field in fields[:2]:
        tap_node(hierarchy(), field)
        adb("shell", "input", "text", "200" if field is fields[0] else "230")
        back()
    shot("resize_values")
    run_setup()
    shot("resize_result")
    paths = pull_new_pdf(before)
    # J1 photo spec is the closest numeric spec for a 200x230 image; report via J1
    # only if an image was actually exported, otherwise record screenshots honestly.
    return result(f"new documents={[p.name for p in paths]}", None, paths[0] if paths else None)


def f_compress():
    before = doc_ids()
    open_tool(TOOL_TITLES["compress"])
    pick_docs(1)
    shot("compress_setup")
    soft_tap(text="Aim for a file size")
    field = None
    try:
        field = seek(cls="EditText")
    except AssertionError:
        note("target size field not found")
    if field is not None:
        tap_node(hierarchy(), field)
        type_text("200KB")
    shot("compress_target")
    run_setup()
    shot("compress_result")
    paths = pull_new_pdf(before, "compressed")
    out = paths[0] if paths else None
    return result(
        f"target=200KB; new documents={[p.name for p in paths]}",
        verify("J2", out, ["--source", str(Path(HERE) / "fixtures_v2/marksheet_scan_300dpi.pdf")]),
        out,
    )


def f_id_card():
    before = doc_ids()
    open_tool(TOOL_TITLES["id_card"])
    shot("id_setup")
    # ID card activity picks two camera photos; try the import path with our fixtures.
    for label in ("Import", "Choose", "Front", "Add front", "Photos"):
        if soft_tap(text=label) or soft_tap(desc=label):
            shot("id_pick")
            break
    for name in ("id_front.jpg", "id_back.jpg"):
        if soft_tap(text=name):
            shot("id_picked")
    require_tap(rid="toolSetupRun")
    time.sleep(3)
    shot("id_result")
    paths = pull_new_pdf(before, "idcard")
    out = paths[0] if paths else None
    return result(
        f"new documents={[p.name for p in paths]}",
        verify("J3", out, ["--password", "Sample-Only-42"]) if out else None,
        out,
    )


def f_merge():
    before = doc_ids()
    open_tool(TOOL_TITLES["merge"])
    n = pick_docs(3)
    shot("merge_setup")
    # Merge requires an explicit output name in the setup field.
    try:
        field = find(hierarchy(), rid="toolSetupName")
        tap_node(hierarchy(), field)
        type_text("Fees receipts")
    except AssertionError:
        note("merge name field missing; keeping prefilled name")
    shot("merge_named")
    run_setup()
    shot("merge_result")
    paths = pull_new_pdf(before, "merged")
    out = paths[0] if paths else None
    extra = ["--expected-name", out.name] if out else []
    return result(
        f"selected={n}; new documents={[p.name for p in paths]}",
        verify("J10", out, extra), out,
    )


def f_split():
    before = doc_ids()
    open_tool(TOOL_TITLES["split"])
    pick_docs(1)
    shot("split_setup")
    try:
        field = find(hierarchy(), rid="toolSetupRanges")
        tap_node(hierarchy(), field)
        type_text("1-2,3-5")
    except AssertionError:
        note("ranges field missing")
    shot("split_ranges")
    run_setup()
    shot("split_result")
    paths = pull_new_pdf(before, "split")
    return result(f"new documents={[p.name for p in paths]}", None, paths[0] if paths else None)


def f_extract():
    before = doc_ids()
    open_tool(TOOL_TITLES["extract"])
    pick_docs(1)
    shot("extract_setup")
    try:
        field = find(hierarchy(), rid="toolSetupRanges")
        tap_node(hierarchy(), field)
        type_text("2")
    except AssertionError:
        note("ranges field missing")
    shot("extract_ranges")
    run_setup()
    shot("extract_result")
    paths = pull_new_pdf(before, "extract")
    return result(f"new documents={[p.name for p in paths]}", None, paths[0] if paths else None)


def f_protect():
    before = doc_ids()
    open_tool(TOOL_TITLES["protect"])
    shot("protect_picker")
    pick_docs(1)
    shot("protect_setup")
    for label in ("Password", "Continue", "Protect"):
        if soft_tap(text=label):
            shot("protect_sheet")
            break
    # Password fields are identified by the kit's PasswordField; type a FICTIONAL value.
    fields = [n for n in hierarchy().iter("node")
              if (n.get("class") or "").endswith("EditText")]
    for field in fields[:2]:
        tap_node(hierarchy(), field)
        type_text("Sample-Only-42")
    shot("protect_password")
    soft_tap(rid="swSheetPrimary")
    soft_tap(text="Protect")
    time.sleep(4)
    shot("protect_result")
    paths = pull_new_pdf(before, "protected")
    out = paths[0] if paths else None
    return result(
        f"new documents={[p.name for p in paths]}",
        verify("J3", out, ["--password", "Sample-Only-42"]) if out else None, out,
    )


def f_ocr():
    before = doc_ids()
    open_tool(TOOL_TITLES["ocr"])
    pick_docs(1)
    shot("ocr_setup")
    run_setup()
    shot("ocr_result")
    # The google_apis emulator has no ML Kit model download; OCR may legitimately fail.
    note("OCR depends on the on-device recogniser; check screenshots for model availability")
    return result("screenshot evidence only", None, None)


def f_ocr_pdf():
    before = doc_ids()
    open_tool(TOOL_TITLES["ocr_pdf"])
    pick_docs(1)
    shot("ocr_pdf_setup")
    run_setup()
    shot("ocr_pdf_result")
    paths = pull_new_pdf(before, "ocrpdf")
    out = paths[0] if paths else None
    return result(
        f"new documents={[p.name for p in paths]}",
        verify("J9", out, ["--ground-truth", str(FIX / "ground_truth.json")]) if out else None,
        out,
    )


def f_pdf_to_jpg():
    before = {str(p) for p in adb("shell", "find", DOWNLOAD, "-type", "f").splitlines()}
    open_tool(TOOL_TITLES["pdf_to_jpg"])
    pick_docs(1)
    shot("pdf_to_jpg_setup")
    soft_tap(rid="toolSetupFormatJpg")
    shot("pdf_to_jpg_jpg")
    run_setup()
    shot("pdf_to_jpg_result")
    files = pull_new_files(before, ".jpg", "jpg")
    return result(f"pulled={[p.name for p in files]}", None, files[0] if files else None)


def f_sign_all():
    before = doc_ids()
    open_tool(TOOL_TITLES["sign_all"])
    shot("sign_picker")
    pick_docs(1)
    shot("sign_editor")
    # The stamp editor is a canvas: no synthetic ink is painted (that would fake a
    # signature). We drive the visible Save/Apply action if present.
    for label in ("Apply", "Save", "Done", "Continue"):
        if soft_tap(text=label):
            shot("sign_apply")
            break
    time.sleep(4)
    shot("sign_result")
    paths = pull_new_pdf(before, "signed")
    out = paths[0] if paths else None
    return result(
        f"new documents={[p.name for p in paths]}",
        verify("J7", out, ["--source", str(FIX / "rent_agreement_5p.pdf")]) if out else None, out,
    )


def f_watermark():
    before = doc_ids()
    open_tool(TOOL_TITLES["watermark"])
    shot("watermark_picker")
    pick_docs(1)
    shot("watermark_editor")
    for label in ("Apply", "Save", "Done", "Continue"):
        if soft_tap(text=label):
            shot("watermark_apply")
            break
    time.sleep(4)
    shot("watermark_result")
    paths = pull_new_pdf(before, "watermarked")
    return result(f"new documents={[p.name for p in paths]}", None, paths[0] if paths else None)


def f_page_numbers():
    before = doc_ids()
    open_tool(TOOL_TITLES["page_numbers"])
    shot("page_numbers_picker")
    pick_docs(1)
    shot("page_numbers_editor")
    for label in ("Apply", "Save", "Done", "Continue"):
        if soft_tap(text=label):
            shot("page_numbers_apply")
            break
    time.sleep(4)
    shot("page_numbers_result")
    paths = pull_new_pdf(before, "numbered")
    return result(f"new documents={[p.name for p in paths]}", None, paths[0] if paths else None)


def f_import_pdf():
    locale("en", "1.0")
    launch()
    shot("import_library_before")
    ok = import_pdf("marksheet_scan_300dpi.pdf")
    shot("import_library_after")
    present = False
    try:
        find(hierarchy(), text="marksheet_scan_300dpi")
        present = True
    except AssertionError:
        pass
    return result(f"import returned {ok}; named document in Library={present}", None, None)


def f_trash_restore():
    locale("en", "1.0")
    launch()
    shot("trash_library")
    # Long-press a named card -> selection -> Delete -> confirm, then Trash -> restore.
    try:
        root = hierarchy()
        node = find(root, regex=r"marksheet|receipt|Fees|scan")
    except AssertionError:
        node = None
    if node is not None:
        from flow_driver import long_press_node
        long_press_node(hierarchy(), node)
        shot("trash_selection")
        soft_tap(rid="swSelDelete")
        soft_tap(rid="swSheetPrimary")
        shot("trash_confirmed")
        launch()
        soft_tap(desc="Me")
        soft_tap(rid="swMeTrash")
        shot("trash_view")
        for label in ("Restore", "Move back", "Undo"):
            if soft_tap(text=label):
                shot("trash_restored")
                break
    else:
        note("no named card available for a Trash journey")
    return result("J5 is a phone-evidence journey; screenshots are the evidence", None, None)


def f_vault():
    locale("en", "1.0")
    launch()
    soft_tap(desc="Vault")
    shot("vault_open")
    # PIN/decryption is Robolectric territory; open the screen and record the state.
    for label in ("Add", "Add to Vault", "Move", "Create PIN", "Continue"):
        if soft_tap(text=label):
            shot("vault_action")
            break
    soft_tap(desc="Library")
    shot("vault_back")
    return result("J8 is a phone-evidence journey; screenshots are the evidence", None, None)


FLOWS = {
    "form_photo": f_form_photo,
    "resize": f_resize,
    "compress": f_compress,
    "id_card": f_id_card,
    "merge": f_merge,
    "split": f_split,
    "extract": f_extract,
    "protect": f_protect,
    "ocr": f_ocr,
    "ocr_pdf": f_ocr_pdf,
    "pdf_to_jpg": f_pdf_to_jpg,
    "sign_all": f_sign_all,
    "watermark": f_watermark,
    "page_numbers": f_page_numbers,
    "import_pdf": f_import_pdf,
    "trash_restore": f_trash_restore,
    "vault": f_vault,
}


# ---------------------------------------------------------------------- report
def write_report(tools, started, verdict=None):
    counts = {}
    for row in CTX["rows"]:
        counts[row["status"]] = counts.get(row["status"], 0) + 1
    summary = ", ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "no rows"
    report = ["# ScanWhirl Tool Atlas", "",
              f"Source: `{os.environ.get('GITHUB_SHA', 'unknown')}`",
              f"Package: `{PKG}` on a throwaway QEMU emulator (API "
              f"{os.environ.get('API_LEVEL', '?')})",
              f"Elapsed: {(time.monotonic()-started)/60:.1f} min", "",
              f"## Verdict: {verdict or 'incomplete'}",
              f"- Row statuses: {summary}",
              "- PASS needs an output file AND a valid, passing numeric verifier AND all "
              "required interactions successful; BLOCKED/NOT_TESTED/FAIL are never acceptance.",
              "",
              "Every tool screen is captured under `<tool>/NN_<what>.png`; the matching "
              ".xml is the accessibility evidence. A FAIL is reported, never suppressed.",
              "", "| tool | steps | screenshots | verifier | failure reason |",
              "| --- | --- | --- | --- | --- |"]
    for row in CTX["rows"]:
        reason = (row.get("reason") or "—").replace("|", "\\|").replace("\n", " ")
        report.append(f"| {row['tool']} | {row['steps']} | {row['shots']} | "
                      f"{row['verifier']} | {reason} |")
    report += ["", "## Per-tool detail", ""]
    for row in CTX["rows"]:
        report += [f"### {row['tool']}", "",
                   f"- status: **{row['status']}**",
                   f"- detail: {row.get('detail', '')}",
                   f"- verifier: {row.get('verifier')} — {row.get('verifier_reason', '')}",
                   f"- output: `{row.get('output') or 'none'}`",
                   f"- screenshots: {', '.join(row.get('shot_names', [])) or 'none'}"]
        for c in row.get("checks", []):
            report.append(f"  - {'PASS' if c['pass'] else 'FAIL'} `{c['check']}`: "
                          f"measured={json.dumps(c['measured'], ensure_ascii=True)} "
                          f"expected={json.dumps(c['expected'], ensure_ascii=True)}")
        if row.get("notes"):
            report.append("- harness notes: " + "; ".join(row["notes"]))
        report.append("")
    (OUT / "ATLAS_REPORT.md").write_text("\n".join(report), encoding="utf-8")


def scan_crashes():
    """Best-effort native crash scan of the throwaway emulator's logcat."""
    found = []
    try:
        logs = adb("logcat", "-d")
        (OUT / "atlas-logcat.txt").write_text(logs, encoding="utf-8")
        if re.search(r"FATAL EXCEPTION.*" + re.escape(PKG), logs):
            found.append("FATAL EXCEPTION")
        if re.search(r"am_anr.*" + re.escape(PKG),
                     adb("logcat", "-d", "-b", "events")):
            found.append("ANR")
    except Exception as exc:  # noqa: BLE001
        found.append(f"logcat unavailable: {type(exc).__name__}")
    return found


def main():
    started = time.monotonic()
    OUT.mkdir(parents=True, exist_ok=True)
    raw = os.environ.get("ATLAS_TOOLS", "all").strip()
    tools = ALL_TOOLS if raw in ("", "all") else [t.strip() for t in raw.split(",") if t.strip()]
    blocking_error = None
    report_failed = False
    crashes = []
    verdict = "incomplete"
    try:
        if not tools:
            # An empty/comma-only selection must never be a green 0-row run.
            CTX["rows"].append(blocked_row("ATLAS_TOOLS", "no valid tool id in ATLAS_TOOLS"))
            raise RuntimeError("no tools selected: ATLAS_TOOLS must name at least one tool")
        guard()
        adb("logcat", "-c")
        unknown = [t for t in tools if t not in FLOWS]
        if unknown:
            raise RuntimeError("unknown tool id(s): " + ", ".join(unknown))
        note(f"running tools: {', '.join(tools)}")
        try:
            staging_map = prepare_library()
        except Exception as exc:  # noqa: BLE001
            traceback.print_exc()
            raise RuntimeError(
                f"fixture staging failed: {type(exc).__name__}: {exc}") from exc
        assert_library_staged(staging_map)
        for tool in tools:
            CTX.update({"tool": tool, "step": 0, "shots": [], "notes": [],
                        "required_failed": False})
            print(f"=== {tool} ===", flush=True)
            try:
                res = FLOWS[tool]()
                detail = res.get("detail", "")
                ver = res.get("verifier")
                output = res.get("output")
                required_ok = not CTX.get("required_failed", False)
                status = classify(ver, output, required_ok)
                vreason = ver.get("reason", "") if ver else "no independent verifier ran"
                checks = ver.get("checks", []) if ver else []
                reason = "" if status == PASS else (
                    (ver.get("reason") if ver else None) or detail or "tool did not complete")
            except Exception as exc:  # noqa: BLE001
                traceback.print_exc()
                detail = f"{type(exc).__name__}: {exc}"
                ver, status, vreason, checks = None, FAIL, "", []
                reason = detail
                try:
                    shot("FAIL")
                except Exception:
                    traceback.print_exc()
            CTX["rows"].append({
                "tool": tool, "status": status, "steps": CTX["step"],
                "shots": len(CTX["shots"]), "shot_names": list(CTX["shots"]),
                "verifier": (ver or {}).get("status", "none") if ver else "none",
                "verifier_reason": vreason, "reason": reason, "detail": detail,
                "output": output,
                "checks": checks, "notes": list(CTX["notes"]),
            })
            print(json.dumps(CTX["rows"][-1], ensure_ascii=True), flush=True)
    except Exception as exc:  # noqa: BLE001
        # Staging / harness precondition failure: BLOCK every requested row and stay red.
        traceback.print_exc()
        blocking_error = f"{type(exc).__name__}: {exc}"
        note("harness/precondition error: " + blocking_error)
        done = {r["tool"] for r in CTX["rows"]}
        for tool in tools:
            if tool not in done:
                CTX["rows"].append(blocked_row(tool, blocking_error))
    finally:
        crashes = scan_crashes()
        for c in crashes:
            note("crash scan: " + c)
        non_pass = [r for r in CTX["rows"] if r["status"] in NON_PASS]
        if blocking_error:
            verdict = "BLOCKED (staging/harness precondition failed)"
        elif crashes:
            verdict = "BLOCKED (native crash observed)"
        elif non_pass:
            verdict = "FAIL (" + ", ".join(f"{r['tool']}={r['status']}" for r in non_pass) + ")"
        else:
            verdict = "PASS (fully verified)"
        try:
            write_report(tools, started, verdict)
            print((OUT / "ATLAS_REPORT.md").read_text(encoding="utf-8"), flush=True)
        except Exception:  # noqa: BLE001
            traceback.print_exc()
            report_failed = True
    # Aggregate: any FAIL / BLOCKED / NOT_TESTED / crash / lost report reddens the job.
    if blocking_error or report_failed:
        return 2
    if crashes or any(r["status"] in NON_PASS for r in CTX["rows"]):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
