#!/usr/bin/env python3
"""Current full-screen map of the app on a disposable CI emulator.

Harness-only: no app source, string, resource or product change. Every capture is
a real screencap + uiautomator dump of the installed com.scanwhirl.app.test APK.
A screen we cannot reach is recorded BLOCKED with the current frame; it is never
silently skipped and never reported as a PASS.

Exit status: 0 only when every planned capture was made with no BLOCKED row and
the harness itself did not break. Any BLOCKED product screen or harness error
returns 1, so an unreachable-tool report can never become a green claim.
"""
import os
import re
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

import flow_driver as fd
from make_pdf import build
from uitool import dump_xml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PKG = "com.scanwhirl.app.test"
OUT = Path(os.environ.get("OUT", ROOT / "artifacts" / "uimap"))
UIMAP = OUT / "uimap"
NAME = fd.NAME  # e2e-three-pages
SOURCE_SHA = os.environ.get("GITHUB_SHA", "unknown")

# fd.capture() writes <tag>.png + <tag>.xml into fd.OUT; point it at our folder.
fd.OUT = UIMAP

ROWS = []
HARNESS_ERRORS = []


# --------------------------------------------------------------------- helpers
def shot(tag, screen, route, status="PASS-captured", note=""):
    """Capture one PNG+XML and append a fail-closed row. A missing file is BLOCKED."""
    png = UIMAP / f"{tag}.png"
    xml = UIMAP / f"{tag}.xml"
    try:
        fd.capture(tag)
    except Exception as exc:  # noqa: BLE001 - reported as a blocked capture, never hidden
        status = "BLOCKED"
        note = (note + " capture-error=" + type(exc).__name__ + ":" + str(exc)).strip()
    if not png.exists() or png.stat().st_size < 1000:
        status = "BLOCKED"
        note = (note + " missing/blank PNG").strip()
    if not xml.exists() or xml.stat().st_size < 50:
        status = "BLOCKED"
        note = (note + " missing UI XML").strip()
    if status == "PASS-captured" and xml.exists():
        note = (note + " || observed: " + first_texts(xml.read_bytes())).strip(" |")
    ROWS.append({"screen": screen, "route": route, "file": tag,
                 "status": status, "note": note})
    print(f"  [{status}] {screen} -> {tag}", flush=True)
    return status


def first_texts(data, limit=7):
    try:
        root = ET.fromstring(data)
    except Exception:  # noqa: BLE001
        return ""
    seen = []
    for node in root.iter("node"):
        value = (node.get("text") or node.get("content-desc") or "").strip()
        if value and value not in seen:
            seen.append(value)
        if len(seen) >= limit:
            break
    return " | ".join(seen)


def wait_any(criteria, timeout=30):
    deadline = time.monotonic() + timeout
    while True:
        root = fd.hierarchy()
        for want in criteria:
            try:
                return want, fd.find(root, **want)
            except AssertionError:
                continue
        if time.monotonic() >= deadline:
            raise AssertionError("none of " + repr(criteria) + " appeared within " + str(timeout) + "s")
        time.sleep(0.4)


def tap_first(candidates, timeout=0.8):
    """Tap the first candidate control that is actually on screen."""
    for want in candidates:
        try:
            fd.find(fd.hierarchy(), **want)
            fd.tap(**want)
            return want
        except AssertionError:
            continue
    return None


def safe_scroll(times):
    done = 0
    for _ in range(times):
        try:
            fd.scroll()
            done += 1
        except Exception:  # noqa: BLE001 - a non-scrollable screen is not a harness crash
            break
    return done


def to_tools():
    """Return to the Tools hub with a clear search field, relaunching if needed."""
    for _ in range(2):
        try:
            fd.find(fd.hierarchy(), rid="swToolsSearch")
            break
        except AssertionError:
            fd.back()
    try:
        fd.find(fd.hierarchy(), rid="swToolsSearch")
    except AssertionError:
        fd.launch()
        fd.tap(desc="Tools")
        fd.wait(rid="swToolsSearch")
    if tap_first([{"rid": "swToolsClear"}, {"desc": "Clear search"}]):
        time.sleep(0.3)


def type_query(title):
    fd.tap(cls="EditText")
    # Android `input text` uses %s for a space; single quotes survive adb's shell
    # re-parsing so '/' and other printable characters reach the widget intact.
    typed = title.replace(" ", "%s")
    fd.adb("shell", "input", "text", "'" + typed + "'")
    fd.back()  # hide keyboard only; query stays
    time.sleep(0.4)


# The 37 READY tools from ToolRegistry.kt at the source snapshot. Titles are the
# exact English R.string values the search box matches (test build = Pro, so no
# tile is gated and the content-desc equals the title).
TOOLS = [
    ("scan", "Scan", True),
    ("id_card", "ID card", False),
    ("import_pdf", "Import PDF", False),
    ("passport", "Passport", False),
    ("qr_code", "QR code", True),
    ("merge", "Merge", False),
    ("split", "Split", False),
    ("extract", "Extract pages", False),
    ("organize_pages", "Reorder", False),
    ("insert_pages", "Add pages", False),
    ("folders", "Folders", False),
    ("batch_tools", "Batch", False),
    ("compress", "Compress", False),
    ("resize", "Resize", False),
    ("form_ready", "Form photo", False),
    ("pdf_to_images", "PDF to JPG", False),
    ("long_image", "Long image", False),
    ("to_word", "Word", False),
    ("to_powerpoint", "Slides", False),
    ("to_excel", "Excel", False),
    ("zip", "ZIP", False),
    ("searchable_pdf", "OCR PDF", False),
    ("pdfa", "PDF/A", False),
    ("sign", "Sign", False),
    ("add_text", "Add text", False),
    ("draw", "Draw", False),
    ("highlight", "Highlight", False),
    ("watermark", "Watermark", False),
    ("page_numbers", "Page numbers", False),
    ("stamp", "Stamp", False),
    ("redact", "Redact", False),
    ("protect", "Protect", False),
    ("unlock", "Unlock", False),
    ("vault", "Vault", False),
    ("remove_metadata", "Clear metadata", False),
    ("read_text", "OCR", False),
    ("link_index", "Link index", False),
]


def source_ready_ids():
    """Derive the READY id set from the registry source, independent of our table."""
    src = (ROOT / "app/src/main/java/com/scanwhirl/app/ui/kit/ToolRegistry.kt").read_text(encoding="utf-8")
    ready = set(re.findall(r'ready\(\s*"([a-z_]+)"', src))
    ctor = set(re.findall(r'SwTool\(\s*(?://[^\n]*\n\s*)*"([a-z_]+)"[\s\S]{0,500}?ToolStatus\.READY', src))
    return ready | ctor


# ------------------------------------------------------------------- sections
def section_shell():
    fd.locale("en", "1.0")
    shot("01_library_empty", "Library (empty / as installed)", "launch -> Library")
    fd.tap(desc="Tools")
    fd.wait(rid="swToolsSearch")
    shot("02_tools_top", "Tools hub (top)", "Library > Tools")
    n = safe_scroll(9)
    shot("03_tools_bottom", "Tools hub (scrolled bottom)", "Library > Tools > scroll x" + str(n))
    fd.tap(desc="Vault")
    fd.wait(rid="swVaultStatus")
    shot("04_vault_tab", "Vault tab (lobby)", "Tools > Vault")
    fd.tap(desc="Me")
    fd.wait(rid="swMeList")
    shot("05_me_tab", "Me tab", "Vault > Me")

    # Create sheet and its import sub-sheets.
    fd.tap(desc=fd.strings("en")["create_disc_desc"])
    fd.wait(rid="swCreateScan")
    shot("06_create_sheet", "Create sheet", "Me > centre Create disc")
    if tap_first([{"text": "Import PDF"}, {"text": "PDF file"}, {"rid": "importPdf"}]):
        time.sleep(0.5)
        shot("07_import_pdf_sheet", "Import PDF sheet", "Create > Import PDF")
        fd.back()
        time.sleep(0.3)
    else:
        shot("07_import_pdf_sheet", "Import PDF sheet", "Create > Import PDF",
             "BLOCKED", "no PDF source row on Create sheet")
    if tap_first([{"text": "Import photos"}, {"text": "Add photos"}]):
        time.sleep(0.5)
        shot("08_add_photos_sheet", "Photos source sheet (Add photos)", "Create > Import photos")
        fd.back()
        time.sleep(0.3)
    else:
        shot("08_add_photos_sheet", "Photos source sheet (Add photos)", "Create > Import photos",
             "BLOCKED", "no photos source row on Create sheet")
    fd.back()
    time.sleep(0.4)

    # Pro screen.
    fd.tap(desc="Me")
    fd.wait(rid="swMeList")
    if tap_first([{"rid": "swMePro"}, {"text": "ScanWhirl Pro"}]):
        time.sleep(0.6)
        shot("09_pro_screen", "ScanWhirl Pro screen", "Me > ScanWhirl Pro")
        fd.back()
        time.sleep(0.4)
    else:
        shot("09_pro_screen", "ScanWhirl Pro screen", "Me > ScanWhirl Pro",
             "BLOCKED", "Pro row not found")

    # Settings top + scrolled.
    fd.tap(text="Settings")
    fd.wait(text="App language")
    shot("10_settings_top", "Settings (top)", "Me > Settings")
    n = safe_scroll(6)
    shot("11_settings_scrolled", "Settings (scrolled)", "Me > Settings > scroll x" + str(n))
    fd.back()
    time.sleep(0.4)

    # Backup entry, Trash entry, About, language chooser.
    fd.tap(text="Backup & restore")
    time.sleep(0.6)
    shot("12_backup_entry", "Backup & restore entry", "Me > Backup & restore")
    fd.back()
    time.sleep(0.3)
    fd.tap(rid="swMeTrash")
    time.sleep(0.6)
    shot("13_trash_entry", "Trash entry", "Me > Trash")
    fd.back()
    time.sleep(0.3)
    if tap_first([{"rid": "swMeAbout"}, {"text": "About ScanWhirl"}]):
        time.sleep(0.5)
        shot("14_about_sheet", "About sheet", "Me > About ScanWhirl")
        fd.back()
        time.sleep(0.3)
    else:
        shot("14_about_sheet", "About sheet", "Me > About ScanWhirl",
             "BLOCKED", "About row not found")
    fd.tap(desc="Library")
    fd.wait(rid="swHomeSearch")
    fd.tap(text="A/अ")
    time.sleep(0.5)
    shot("15_language_chooser", "Language chooser (A/अ)", "Library > A/अ")
    fd.back()
    time.sleep(0.3)


def section_tools():
    """Open every READY tool through Tools search; capture the real entry screen."""
    fd.tap(desc="Tools")
    fd.wait(rid="swToolsSearch")
    for tool_id, title, is_ml in TOOLS:
        try:
            to_tools()
            type_query(title)
            found = False
            for want in ({"desc": title}, {"text": title}, {"regex": re.escape(title)}):
                try:
                    fd.tap(**want)
                    found = True
                    break
                except AssertionError:
                    continue
            if not found:
                shot(f"tool_{tool_id}", "Tool " + title, "Tools search > " + title,
                     "BLOCKED", "search result row not found")
                continue
            if is_ml:
                # An ML Kit / Play-services camera launch is the one allowed skip.
                # Stay on the search-results screen: it still proves the tile exists.
                time.sleep(0.4)
                shot(f"tool_{tool_id}", "Tool " + title, "Tools search > " + title + " (result only)",
                     "SKIP-ML", "camera/Play-services launch - not started per owner rule")
                continue
            time.sleep(1.1)
            shot(f"tool_{tool_id}", "Tool " + title, "Tools search > " + title + " > first result")
            fd.back()
            time.sleep(0.4)
        except Exception as exc:  # noqa: BLE001 - one tool must not abort the map
            HARNESS_ERRORS.append(f"tool {tool_id}: {type(exc).__name__}: {exc}")
            traceback.print_exc()
            try:
                shot_current(f"tool_{tool_id}", "Tool " + title,
                             "Tools search > " + title, "BLOCKED",
                             "harness exception: " + type(exc).__name__)
            except Exception:
                pass


def shot_current(tag, screen, route, status, note):
    try:
        fd.back()
    except Exception:
        pass
    shot(tag, screen, route, status, note)


def handle_retention(tag, screen):
    """After the SAF pick, explicitly settle any plan/retention sheet."""
    deadline = time.monotonic() + 30
    saw = False
    while time.monotonic() < deadline:
        root = fd.hierarchy()
        texts = [n.get("text", "") for n in root.iter("node")]
        descs = [n.get("content-desc", "") for n in root.iter("node")]
        joined = "\n".join(texts + descs)
        if any(k in joined for k in ("Import all", "Choose pages", "Keep", "Discard")):
            saw = True
            shot(tag, screen, "after selecting PDF in SAF")
            tap_first([{"text": "Import all"}, {"text": "Keep"},
                       {"rid": "swSheetPrimary"}])
            return saw
        time.sleep(0.4)
    if not saw:
        # No retention sheet is a valid product state: record the real frame
        # rather than claiming a missing capture.
        shot("f1_post_pick_screen", "Screen after SAF pick (no retention sheet shown)",
             "after selecting PDF in SAF")
    return saw


def import_flow():
    """Real 3-page PDF import, retention sheet handling, then document screens."""
    fd.locale("en", "1.0")
    fixture = UIMAP / f"{NAME}.pdf"
    fixture.write_bytes(build([["Fictional QA document / page " + str(i),
                                "Self-generated ScanWhirl emulator fixture"]
                               for i in range(1, 4)]))
    fd.adb("shell", "mkdir", "-p", "/sdcard/Download")
    fd.adb("push", fixture, f"/sdcard/Download/{NAME}.pdf")

    # Open the import sheet from whichever entry the current Library offers.
    try:
        fd.tap(rid="swEmptyImport")
    except AssertionError:
        fd.tap(desc=fd.strings("en")["create_disc_desc"])
    opened = False
    for want in ({"text": "Import PDF"}, {"text": "PDF file"}, {"rid": "importPdf"}):
        try:
            fd.wait(timeout=6, **want)
            fd.tap(**want)
            opened = True
            break
        except AssertionError:
            continue
    if not opened:
        shot("f1_import_blocked", "PDF import sheet", "Library > import",
             "BLOCKED", "no PDF source row")
        return

    # Real SAF: Downloads -> internal storage Download folder -> our fixture.
    fd.wait(desc="Show roots")
    fd.tap(desc="Show roots")
    fd.tap(text=fd.adb("shell", "getprop", "ro.product.model"))
    fd.wait(text="Download")
    fd.tap(text="Download")
    fd.wait(text=f"{NAME}.pdf", timeout=60)
    shot("f1_saf_picker", "SAF picker with generated PDF", "Import PDF > Downloads > Download")
    fd.tap(text=f"{NAME}.pdf")

    handle_retention("f1_import_plan_sheet", "PDF import plan / retention sheet")

    # Never trust desc='Compress': wait for the viewer by document name or a
    # stable viewer id, as the current build shows a sheet before the toolbar.
    try:
        wait_any([{"text": NAME}, {"rid": "btnMore"}, {"rid": "pageList"},
                  {"rid": "docCategoryTouch"}, {"regex": r"^1 / 3$"}], 100)
        shot("f1_viewer", "Document viewer (imported PDF)", "after retention sheet")
    except AssertionError as exc:
        shot("f1_viewer", "Document viewer (imported PDF)", "after retention sheet",
             "BLOCKED", "viewer not reached: " + str(exc))
        return

    # Document actions sheet from the viewer's More button.
    try:
        fd.tap(rid="btnMore")
        time.sleep(0.6)
        shot("f1_actions_sheet", "Document actions sheet", "viewer > More")
        tap_first([{"text": "Close"}, {"rid": "swSheetClose"}]) or fd.back()
        time.sleep(0.4)
    except AssertionError as exc:
        shot("f1_actions_sheet", "Document actions sheet", "viewer > More",
             "BLOCKED", str(exc))

    # Compress setup + real result.
    try:
        fd.tap(rid="btnCompress")
        fd.wait(timeout=20, text=fd.strings("en")["compress_level_title"])
        shot("f1_compress_setup", "Compress setup", "viewer > Compress")
        tap_first([{"text": "Aim for a file size"}])
        fd.seek(cls="EditText")
        fd.tap(cls="EditText")
        fd.adb("shell", "input", "text", "100KB")
        fd.back()
        fd.tap(text="Compress")
        fd.wait(timeout=130, text="Compressed")
        shot("f1_compress_result", "Compress result (Before/After)", "Compress setup > Compress")
        fd.back()
        time.sleep(0.4)
    except Exception as exc:  # noqa: BLE001
        HARNESS_ERRORS.append("compress: " + type(exc).__name__ + ": " + str(exc))
        shot("f1_compress_result", "Compress result (Before/After)", "Compress setup > Compress",
             "BLOCKED", "did not complete: " + type(exc).__name__)

    # Back to Library, settling the Keep/Discard import sheet if shown.
    try:
        fd.back()
        time.sleep(0.5)
        root = fd.hierarchy()
        joined = "\n".join([n.get("text", "") for n in root.iter("node")] +
                           [n.get("content-desc", "") for n in root.iter("node")])
        if "Keep" in joined and "Discard" in joined:
            shot("f1_keep_sheet", "Keep imported document sheet", "viewer > Back")
            tap_first([{"text": "Keep"}, {"rid": "swSheetPrimary"}])
            time.sleep(0.5)
        fd.wait(timeout=30, text=NAME)
        shot("f1_library_card", "Library card with imported document name",
             "after import retained")
    except Exception as exc:  # noqa: BLE001
        shot("f1_library_card", "Library card with imported document name",
             "after import retained", "BLOCKED", "card not reached: " + str(exc))

    # Resize and Form photo entry screens, reached the same way a user does.
    for tool_id, title in (("resize", "Resize"), ("form_ready", "Form photo")):
        try:
            to_tools()
            type_query(title)
            fd.tap(desc=title)
            time.sleep(1.0)
            shot(f"f1_{tool_id}_setup", title + " setup", "Tools search > " + title)
            fd.back()
            time.sleep(0.3)
        except Exception as exc:  # noqa: BLE001
            shot(f"f1_{tool_id}_setup", title + " setup", "Tools search > " + title,
                 "BLOCKED", "not reachable: " + str(exc))


def passport_flow():
    """Push the fictional fixture, scan it, pick it in the app, capture the preview."""
    fixture = ROOT / "app/src/test/resources/formfx/exam_photo.jpg"
    if not fixture.exists():
        shot("f1_passport_preview", "Passport preview after photo selection",
             "Tools > Passport > photo picker", "BLOCKED", "fixture file absent in source tree")
        return
    fd.adb("shell", "mkdir", "-p", "/sdcard/Pictures")
    fd.adb("push", fixture, "/sdcard/Pictures/exam_photo.jpg")
    fd.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
           "-d", "file:///sdcard/Pictures/exam_photo.jpg")
    time.sleep(1.5)
    try:
        to_tools()
        type_query("Passport")
        fd.tap(desc="Passport")
        time.sleep(2.0)
        shot("f1_passport_picker", "Passport system photo picker", "Tools > Passport (auto pick)")
    except Exception as exc:  # noqa: BLE001
        shot("f1_passport_picker", "Passport system photo picker", "Tools > Passport (auto pick)",
             "BLOCKED", "picker not reached: " + str(exc))
        return

    # Navigate the system picker to the fixture. Album names and thumbnails are
    # locale-independent file labels; no fixed coordinates.
    picked = False
    try:
        for album in ("Pictures", "Images", "Photos", "Recent"):
            try:
                fd.tap(text=album)
                time.sleep(0.6)
                break
            except AssertionError:
                continue
        for _ in range(6):
            root = fd.hierarchy()
            for want in ({"text": "exam_photo.jpg"}, {"text": "exam_photo"},
                         {"desc": "exam_photo.jpg"}, {"regex": "exam_photo"}):
                try:
                    fd.tap_node(root, fd.find(root, **want))
                    picked = True
                    break
                except AssertionError:
                    continue
            if picked:
                break
            fd.scroll()
    except Exception as exc:  # noqa: BLE001
        HARNESS_ERRORS.append("passport picker: " + type(exc).__name__ + ": " + str(exc))

    if not picked:
        shot("f1_passport_preview", "Passport preview after photo selection",
             "Tools > Passport > photo picker", "BLOCKED",
             "could not select exam_photo.jpg in the system picker; current screen captured")
        try:
            fd.back()
            fd.back()
        except Exception:
            pass
        return

    try:
        # A nonblank preview shows the crop view with an enabled Apply button.
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            root = fd.hierarchy()
            apply_nodes = [n for n in root.iter("node")
                           if (n.get("resource-id") or "").endswith("toolCropApply")]
            crop_nodes = [n for n in root.iter("node")
                          if (n.get("resource-id") or "").endswith("toolCropView")]
            if apply_nodes and crop_nodes:
                break
            time.sleep(0.5)
        time.sleep(1.0)
        shot("f1_passport_preview", "Passport preview after photo selection",
             "Tools > Passport > pick exam_photo.jpg")
    except Exception as exc:  # noqa: BLE001
        shot("f1_passport_preview", "Passport preview after photo selection",
             "Tools > Passport > pick exam_photo.jpg", "BLOCKED", str(exc))


# --------------------------------------------------------------------- writing
def write_map():
    UIMAP.mkdir(parents=True, exist_ok=True)
    source = source_ready_ids()
    attempts = {rid for rid, _, _ in TOOLS}
    captured_tools = {r["file"][5:] for r in ROWS if r["file"].startswith("tool_")}
    blocked = [r for r in ROWS if r["status"] == "BLOCKED"]
    skipped = [r for r in ROWS if r["status"] == "SKIP-ML"]
    not_reached = sorted(attempts - captured_tools)

    lines = []
    lines.append("# ScanWhirl — current full-screen map (GitHub emulator)\n")
    lines.append(f"- Source snapshot: `{SOURCE_SHA}`")
    lines.append("- Package: `com.scanwhirl.app.test` (normal `-PtestBuild` APK)")
    lines.append("- Method: real `screencap` PNG + `uiautomator dump` XML on API 35; no faked frames")
    lines.append(f"- Captures attempted: {len(ROWS)}; PASS-captured: "
                 f"{sum(1 for r in ROWS if r['status'] == 'PASS-captured')}; "
                 f"BLOCKED: {len(blocked)}; SKIP-ML: {len(skipped)}")
    lines.append(f"- ToolRegistry READY tools (parsed from source): {len(source)}; "
                 f"tools attempted: {len(TOOLS)}; tool screens captured: {len(captured_tools)}")
    if source != attempts:
        lines.append(f"- **Harness mismatch**: registry-only={sorted(source - attempts)}, "
                     f"table-only={sorted(attempts - source)}")
    lines.append("")
    lines.append("A missing capture is BLOCKED, not silently skipped. SKIP-ML is the only ")
    lines.append("allowed skip (ML Kit / Play-services camera launch). BLOCKED means a real ")
    lines.append("product screen was not reachable; it is a finding, never a green claim.\n")
    lines.append("| screen | route / taps | file | status | note |")
    lines.append("| --- | --- | --- | --- | --- |")
    for r in ROWS:
        note = r["note"].replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {r['screen']} | {r['route']} | {r['file']} | {r['status']} | {note} |")
    lines.append("")
    lines.append("## Screens not reached\n")
    if not_reached:
        for rid in not_reached:
            lines.append(f"- {rid}")
    else:
        lines.append("- none")
    lines.append("")
    lines.append("## Harness errors (distinct from product findings)\n")
    if HARNESS_ERRORS:
        for err in HARNESS_ERRORS:
            lines.append("- " + err.replace("\n", " "))
    else:
        lines.append("- none")
    lines.append("")
    lines.append("## Owner-reported defects to inspect in these captures\n")
    lines.append("- Home card width / name breaks: `01_library_empty`, `f1_library_card`")
    lines.append("- Passport preview blank after selection: `f1_passport_picker`, `f1_passport_preview`")
    lines.append("- Result Before/After white or clipped: `f1_compress_result`")
    lines.append("- Duplicate Text size row: `10_settings_top`, `11_settings_scrolled`")
    lines.append("- Tools missing import-from-phone: `02_tools_top`, `03_tools_bottom`")
    lines.append("- Retention sheet before viewer: `f1_import_plan_sheet`, `f1_keep_sheet`, `f1_viewer`")
    (UIMAP / "MAP.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    summary = ("\n## Current UI map (API 35)\n\n"
               f"- source `{SOURCE_SHA}`; captures={len(ROWS)}, "
               f"PASS={sum(1 for r in ROWS if r['status'] == 'PASS-captured')}, "
               f"BLOCKED={len(blocked)}, SKIP-ML={len(skipped)}, "
               f"README: `uimap/MAP.md`\n")
    with (OUT / "summary.md").open("a", encoding="utf-8") as handle:
        handle.write(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(summary)
    print(summary, flush=True)
    return len(blocked), len(HARNESS_ERRORS), len(ROWS)


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true", "CI-only: no local/physical device runs"
    assert re.fullmatch(r"emulator-\d+", os.environ.get("ANDROID_SERIAL", "")), "Emulator serial required"
    assert fd.adb("shell", "getprop", "ro.kernel.qemu") == "1", "Not an emulator"
    UIMAP.mkdir(parents=True, exist_ok=True)
    parsed = source_ready_ids()
    if parsed != {tool_id for tool_id, _, _ in TOOLS}:
        HARNESS_ERRORS.append("ToolRegistry READY set differs from map tool table")
    fd.adb("logcat", "-c")
    try:
        for section in (section_shell, section_tools, import_flow, passport_flow):
            try:
                section()
            except Exception as exc:  # noqa: BLE001 - a section failure is reported, not hidden
                HARNESS_ERRORS.append(section.__name__ + ": " + type(exc).__name__ + ": " + str(exc))
                traceback.print_exc()
                try:
                    shot("section_failed_" + section.__name__, "Section " + section.__name__,
                         "current screen at failure", "BLOCKED", type(exc).__name__ + ": " + str(exc))
                except Exception:
                    pass
    finally:
        blocked, errors, total = write_map()
    # Fail closed: a missing product screen or a broken harness is never green.
    return 1 if (blocked or errors or total == 0) else 0


if __name__ == "__main__":
    raise SystemExit(main())
