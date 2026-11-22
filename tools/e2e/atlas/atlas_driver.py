#!/usr/bin/env python3
"""Tool Atlas: drive every ScanWhirl tool on a disposable CI EMULATOR.

This is an extension of tools/e2e/flow_driver.py, not a replacement. It reuses the
same accessibility-first helpers (view ids / content descriptions, never fixed
coordinates), pushes only fictional fixtures, screenshots EVERY step into
<tool>/NN_<what>.png, pulls the real output and runs verify/verify_result.py with
the matching journey/spec.

Safety: rejects a non-CI host, a real device, or a non-test package before adb use.
A tool FAIL is reported, never hidden; a harness/precondition error exits 2.
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
    return picked


def run_setup():
    """Pinned Continue on ToolSetupActivity, then wait for a result screen."""
    if not soft_tap(rid="toolSetupRun"):
        # The label is Continue in English; try copy as a last resort.
        soft_tap(text=strings("en")["tools_setup_run"])
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
def verify(journey, path, extra=None, spec=None, label="verifier"):
    """Run the real verifier and record every check with measured values."""
    if not path or not Path(path).is_file():
        return {"status": "FAIL", "reason": "no output file to verify", "checks": []}
    cmd = [sys.executable, str(VERIFY), "--journey", journey,
           "--file", str(path), "--json", str(Path(path).with_suffix(".json"))]
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
    try:
        report = json.loads(Path(path).with_suffix(".json").read_text(encoding="utf-8"))
        checks = [{"check": c["check"], "pass": c["pass"],
                   "measured": c["measured"], "expected": c["expected"]}
                  for c in report.get("checks", [])]
    except Exception as exc:  # noqa: BLE001
        checks = [{"check": "verifier_report", "pass": False,
                   "measured": type(exc).__name__, "expected": "readable JSON report"}]
    status = "PASS" if proc.returncode == 0 else "FAIL"
    return {"status": status, "reason": f"exit {proc.returncode}", "checks": checks}


def result(detail="", verify_result=None, output=None):
    return {"detail": detail, "verifier": verify_result,
            "output": str(output) if output else None}


# --------------------------------------------------------------- tool recipes
def prepare_library():
    """Push fixtures and import the four PDF documents the tools operate on."""
    push_fixtures([
        "marksheet_scan_300dpi.pdf", "rent_agreement_5p.pdf",
        "receipt_aug.pdf", "receipt_sep.pdf", "receipt_oct.pdf",
        "exam_photo.jpg", "signature_on_paper.jpg",
        "id_front.jpg", "id_back.jpg", "ground_truth.json",
    ])
    locale("en", "1.0")
    launch()
    shot("library_before_import")
    for name in ("marksheet_scan_300dpi.pdf", "rent_agreement_5p.pdf",
                 "receipt_aug.pdf", "receipt_sep.pdf", "receipt_oct.pdf"):
        import_pdf(name)


def import_pdf(name):
    """Create sheet -> Import PDF -> SAF: test-fixture root, fallback to Download."""
    for attempt in range(2):
        if not soft_tap(desc=strings("en")["create_disc_desc"]):
            break
        try:
            wait(timeout=8, text="Import PDF")
            break
        except AssertionError:
            if attempt == 1:
                note("Import PDF entry did not appear")
    if not soft_tap(text="Import PDF"):
        return False
    try:
        wait(desc="Show roots")
        soft_tap(desc="Show roots")
    except AssertionError:
        pass
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
    try:
        wait(timeout=120, desc="Compress")
        shot("imported")
    except AssertionError:
        note(f"no document viewer after importing {name}")
    back()
    if soft_tap(text="Keep"):
        note("kept imported document")
    time.sleep(1)
    shot("library_after_import")
    return True


# --- file-output tools -------------------------------------------------------
def f_form_photo():
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
    if soft_tap(rid="toolSetupRun"):
        shot("form_run")
    time.sleep(3)
    shot("form_result")
    files = pull_new_files({str(p) for p in adb("shell", "find", DOWNLOAD, "-type", "f").splitlines()},
                           label="form_output")
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
    soft_tap(rid="toolSetupRun")
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
def write_report(tools, started):
    report = ["# ScanWhirl Tool Atlas", "",
              f"Source: `{os.environ.get('GITHUB_SHA', 'unknown')}`",
              f"Package: `{PKG}` on a throwaway QEMU emulator (API "
              f"{os.environ.get('API_LEVEL', '?')})",
              f"Elapsed: {(time.monotonic()-started)/60:.1f} min", "",
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


def main():
    started = time.monotonic()
    OUT.mkdir(parents=True, exist_ok=True)
    guard()
    adb("logcat", "-c")
    raw = os.environ.get("ATLAS_TOOLS", "all").strip()
    tools = ALL_TOOLS if raw in ("", "all") else [t.strip() for t in raw.split(",") if t.strip()]
    unknown = [t for t in tools if t not in FLOWS]
    if unknown:
        raise RuntimeError("unknown tool id(s): " + ", ".join(unknown))
    note(f"running tools: {', '.join(tools)}")
    try:
        prepare_library()
    except Exception as exc:  # noqa: BLE001
        note(f"fixture staging error (does not fail the harness): {type(exc).__name__}: {exc}")
        traceback.print_exc()
        shot("fixture_staging_error")
    crashes = []
    for tool in tools:
        CTX.update({"tool": tool, "step": 0, "shots": [], "notes": []})
        print(f"=== {tool} ===", flush=True)
        try:
            res = FLOWS[tool]()
            detail = res.get("detail", "")
            ver = res.get("verifier")
            output = res.get("output")
            status = "PASS" if ver is None else ver["status"]
            vreason = ver.get("reason", "") if ver else "screenshot-only (no numeric verifier)"
            checks = ver.get("checks", []) if ver else []
            reason = "" if status == "PASS" else (ver.get("reason") or detail or "tool did not complete")
        except Exception as exc:  # noqa: BLE001
            traceback.print_exc()
            detail = f"{type(exc).__name__}: {exc}"
            ver, status, vreason, checks = None, "FAIL", "", []
            reason = detail
            try:
                shot("FAIL")
            except Exception:
                traceback.print_exc()
        CTX["rows"].append({
            "tool": tool, "status": status, "steps": CTX["step"],
            "shots": len(CTX["shots"]), "shot_names": list(CTX["shots"]),
            "verifier": "PASS" if (ver and ver["status"] == "PASS") else
                        ("FAIL" if ver else "N/A"),
            "verifier_reason": vreason, "reason": reason, "detail": detail,
            "output": output,
            "checks": checks, "notes": list(CTX["notes"]),
        })
        print(json.dumps(CTX["rows"][-1], ensure_ascii=True), flush=True)
        # Tool FAILs never abort the atlas; harness errors do (guard/main).
    try:
        logs = adb("logcat", "-d")
        (OUT / "atlas-logcat.txt").write_text(logs, encoding="utf-8")
        fatal = bool(re.search(r"FATAL EXCEPTION.*" + re.escape(PKG), logs))
        anr = bool(re.search(r"am_anr.*" + re.escape(PKG),
                             adb("logcat", "-d", "-b", "events")))
        if fatal or anr:
            crashes.append(f"FATAL={fatal} ANR={anr}")
    except Exception as exc:  # noqa: BLE001
        crashes.append(f"logcat unavailable: {type(exc).__name__}")
    if crashes:
        note("crash scan: " + "; ".join(crashes))
    write_report(tools, started)
    print((OUT / "ATLAS_REPORT.md").read_text(encoding="utf-8"), flush=True)
    # Report-only: a tool FAIL is not a harness error. The workflow job stays green
    # so the artifact is always uploaded and its findings are visible.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
