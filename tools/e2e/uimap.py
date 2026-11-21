#!/usr/bin/env python3
"""uimap: automated INTERFACE MAP of the ScanWhirl demo APK on a CI emulator.

Never a phone. Requires GITHUB_ACTIONS=true, one emulator-* serial and a QEMU
kernel (checked before any adb call). Builds fictional fixtures at run time,
imports them through the app's own Create -> Import PDF / SAF picker, then for
every inventory screen: reach it, screencap + uiautomator dump, and press EVERY
clickable control one at a time (recording the resulting screen/sheet and a
screenshot, then returning with Back and re-navigating when needed).

Skipped with a recorded reason: ML Kit / camera / barcode scanner controls,
Play Billing purchase/restore controls, and whole-library delete (Empty Trash).
PRO controls are reachable because -PtestBuild sets TEST_BUILD=true.

Outputs under OUT: screens/*.png + dumps/*.xml, graph.json, index.md,
index.html, tool_results.json, report.md, logcat.txt, uimap-events.txt.
"""
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET

from make_pdf import build  # noqa: F401 - fixture generation lives in make_fixtures
import make_fixtures
from uitool import dump_xml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PKG = os.environ.get("UIMAP_PKG", "com.scanwhirl.app.demo")
ACT = PKG + "/com.scanwhirl.app.ui.shell.ShellActivityCyan"
OUT = Path(os.environ.get("OUT", ROOT / "artifacts" / "uimap"))
CONTROLS = json.loads((HERE / "uimap_controls.json").read_text(encoding="utf-8"))
TOTAL_SLOTS = int(CONTROLS.get("total_slots", 400))
TEST_PASSWORD = "qa-test-1234"          # fictional, never an owner credential
STARTED = time.monotonic()

# --------------------------------------------------------------------------- state
GRAPH = {"meta": {}, "screens": [], "edges": [], "skipped": [], "tools": [],
         "locales": [], "errors": []}
VISITED = {}
COVERED_SLOTS = set()
FIXTURE_NAMES = []
DEVICE_MODEL = "Pixel 6"


# --------------------------------------------------------------------------- adb
def adb(*args, binary=False, timeout=90, check=True):
    p = subprocess.run(["adb", *map(str, args)], capture_output=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError("adb %s failed: %s" % (" ".join(map(str, args)),
                                                  p.stderr.decode("utf-8", "replace").strip()[:300]))
    return p.stdout if binary else p.stdout.decode("utf-8", "replace").strip()


def safe_adb(*args, binary=False, timeout=90):
    try:
        return adb(*args, binary=binary, timeout=timeout, check=False)
    except Exception:
        return b"" if binary else ""


# ----------------------------------------------------------------------- hierarchy
def bounds(node):
    m = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)


def valid(node):
    x1, y1, x2, y2 = bounds(node)
    return x2 > x1 and y2 > y1


def rid(node):
    return (node.get("resource-id") or "").split("/")[-1]


def label(node):
    return (node.get("text") or node.get("content-desc") or rid(node) or "?").strip()


def node_blob(node):
    return " ".join((rid(node), node.get("content-desc") or "", node.get("text") or "")).lower()


def find(root, **want):
    nodes = [n for n in root.iter("node") if valid(n) and _match(n, want)]
    return nodes[0] if nodes else None


def find_all(root, **want):
    return [n for n in root.iter("node") if valid(n) and _match(n, want)]


def _match(n, want):
    if "id" in want and rid(n) != want["id"]:
        return False
    if "text" in want and (n.get("text") or "") != want["text"]:
        return False
    if "desc" in want and (n.get("content-desc") or "") != want["desc"]:
        return False
    if "cls" in want and want["cls"] not in (n.get("class") or ""):
        return False
    if "regex" in want and not re.search(want["regex"], node_blob(n)):
        return False
    return True


def hierarchy():
    data = dump_xml()
    if not data:
        raise RuntimeError("uiautomator hierarchy unavailable")
    return ET.fromstring(data)


def try_hierarchy():
    try:
        return hierarchy()
    except Exception:
        return None


def has(root=None, **want):
    root = root if root is not None else try_hierarchy()
    return bool(root is not None and find(root, **want))


def tap_node(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(0.35)


def tap(**want):
    root = hierarchy()
    node = find(root, **want)
    if not node:
        raise AssertionError("tap target not found: %s" % want)
    tap_node(node)


def tap_text_any(*texts):
    root = hierarchy()
    for t in texts:
        n = find(root, text=t)
        if n:
            tap_node(n)
            return t
    raise AssertionError("none of the texts on screen: %s" % (texts,))


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.45)


def scroll(up=False):
    root = hierarchy()
    cs = [n for n in root.iter("node") if n.get("scrollable") == "true" and valid(n)]
    if not cs:
        raise AssertionError("no scrollable container")
    node = max(cs, key=lambda n: (bounds(n)[2]-bounds(n)[0]) * (bounds(n)[3]-bounds(n)[1]))
    x1, y1, x2, y2 = bounds(node)
    x, lo, hi = (x1+x2)//2, y1+(y2-y1)//5, y2-(y2-y1)//5
    adb("shell", "input", "swipe", x, hi if up else lo, x, lo if up else hi, 350)
    time.sleep(0.4)


def seek(**want):
    for _ in range(14):
        root = try_hierarchy()
        if root is not None:
            n = find(root, **want)
            if n:
                return n
        try:
            scroll()
        except AssertionError:
            break
    raise AssertionError("not found after bounded scroll: %s" % want)


# ------------------------------------------------------------------------- capture
def screenshot(tag, sub="screens"):
    d = OUT / sub
    d.mkdir(parents=True, exist_ok=True)
    png = d / (tag + ".png")
    xml = OUT / "dumps" / (tag + ".xml")
    xml.parent.mkdir(parents=True, exist_ok=True)
    png.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    root = try_hierarchy()
    if root is not None:
        xml.write_bytes(ET.tostring(root, encoding="utf-8"))
    return str(png.relative_to(OUT)), (str(xml.relative_to(OUT)) if xml.exists() else None)


def current_activity():
    out = safe_adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*?\s([A-Za-z0-9_.]+/[A-Za-z0-9_.]+)", out)
    if m:
        return m.group(1).split("/")[-1]
    m = re.search(r"mFocusedApp[^\n]*?\s([A-Za-z0-9_.]+/[A-Za-z0-9_.]+)", out)
    return m.group(1).split("/")[-1] if m else "unknown"


def screen_title(root):
    act = current_activity()
    sheet = find(root, id="swSheetTitle")
    title = (sheet.get("text") or "") if sheet is not None else ""
    return (act + (" | " + title if title else "")).strip()


# ---------------------------------------------------------------------------- skips
SCANNER_LABELS = frozenset({
    "scan", "scan a document", "scan code", "scan again", "scan card",
    "qr code", "scan with camera", "add a spread", "barcode",
})
SCANNER_RE = re.compile(r"scan with camera|barcode|\bcamera\b|auto-?crop|scan with", re.I)
BILLING_RE = re.compile(r"get pro|\bbuy\b|purchase|restore purchase|subscribe|billing", re.I)
WIPE_RE = re.compile(r"empty trash|delete everything|delete all(?: files| documents)?|clear all", re.I)


def skip_reason(node):
    # The centre disc OPENS the create sheet; it is not a scanner control.
    if rid(node) == "swNavScan":
        return None
    for attr in ("text", "content-desc"):
        v = (node.get(attr) or "").strip().lower()
        if v in SCANNER_LABELS:
            return "ML Kit scanner"
    blob = node_blob(node)
    if SCANNER_RE.search(blob):
        return "ML Kit scanner"
    if BILLING_RE.search(blob):
        return "Play billing purchase"
    if WIPE_RE.search(blob):
        return "whole-library delete"
    return None


def control_key(node):
    i, d, t = rid(node), node.get("content-desc") or "", node.get("text") or ""
    if i:
        return "id:" + i
    if d:
        return "desc:" + d
    return "text:" + (t or "?")


def slot_ids(node):
    result = []
    for k, d in (("by_id", rid(node)), ("by_label", node.get("text") or ""),
                 ("by_label", node.get("content-desc") or "")):
        if not d:
            continue
        result += CONTROLS.get(k, {}).get(d.lower(), [])
        # forgiveness: resource-ids sometimes carry a locale suffix
        if k == "by_id" and d:
            base = re.sub(r"_[a-z]{2}$", "", d)
            result += CONTROLS.get(k, {}).get(base.lower(), [])
    return sorted(set(result))


def clickable_nodes(root):
    out = []
    for n in root.iter("node"):
        if n.get("clickable") != "true" or not valid(n):
            continue
        out.append(n)
    return out


def record_visit(node, screen_id, result_title, screenshot_path, status, reason=""):
    key = control_key(node)
    slots = slot_ids(node)
    COVERED_SLOTS.update(slots)
    VISITED.setdefault(key, {"label": label(node), "screen": screen_id,
                             "slots": slots, "status": status})
    GRAPH["edges"].append({
        "from": screen_id, "control": key, "label": label(node),
        "slots": slots, "status": status, "reason": reason,
        "result_title": result_title, "screenshot": screenshot_path,
    })


# ---------------------------------------------------------------------- device cfg
def configure_device():
    adb("shell", "settings", "put", "global", "window_animation_scale", "0")
    adb("shell", "settings", "put", "global", "transition_animation_scale", "0")
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
    adb("shell", "settings", "put", "global", "hide_error_dialogs", "1")
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", "0")
    safe_adb("logcat", "-c")


def dismiss_dialogs():
    for _ in range(4):
        root = try_hierarchy()
        if root is None or find(root, id="aerr_wait") is None:
            return
        tap(id="aerr_wait")
        time.sleep(2)


def launch():
    adb("shell", "am", "force-stop", PKG)
    dismiss_dialogs()
    out = adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", ACT)
    if "Status: ok" not in out:
        raise RuntimeError("launch failed: " + out[-300:])
    deadline = time.monotonic() + 75
    while time.monotonic() < deadline:
        dismiss_dialogs()
        root = try_hierarchy()
        if root is not None and find(root, id="swHomeSearch"):
            return
        time.sleep(0.7)
    raise RuntimeError("Library (swHomeSearch) did not appear after launch")


def set_locale(lang="en", scale="1.0"):
    adb("shell", "settings", "put", "system", "font_scale", scale)
    adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", lang)
    launch()


# ---------------------------------------------------------------------- fixtures/seed
def make_and_push_fixtures():
    work = OUT / "fixtures"
    work.mkdir(parents=True, exist_ok=True)
    make_fixtures.main(["make_fixtures.py", str(work)])
    adb("shell", "mkdir", "-p", "/sdcard/Download")
    files = sorted(str(p) for p in work.iterdir() if p.suffix in (".pdf", ".png"))
    pushed = []
    for f in files:
        name = Path(f).name
        remote = "/sdcard/Download/" + name
        adb("push", f, remote, check=False)
        verify = adb("shell", "test", "-s", remote, "&&", "echo", "ok", check=False)
        if "ok" in verify or adb("shell", "ls", remote, check=False).strip():
            pushed.append(name)
            FIXTURE_NAMES.append(Path(f).stem)
        else:
            GRAPH["errors"].append({"where": "push/" + name, "error": "fixture not present on device"})
    GRAPH["meta"]["fixtures"] = pushed
    if not pushed:
        raise RuntimeError("no fixtures could be pushed to /sdcard/Download")
    return work


def open_create_sheet():
    for attempt in range(2):
        root = hierarchy()
        n = find(root, id="swNavScan")
        if not n:
            raise AssertionError("create disc (swNavScan) missing")
        tap_node(n)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            root = try_hierarchy()
            if root is not None and find(root, id="swCreateScan"):
                return
            time.sleep(0.4)
        time.sleep(1)
    raise AssertionError("Create sheet (swCreateScan) did not open")


def saf_pick(filename):
    """Walk the real DocumentsUI download folder by its visible labels."""
    deadline = time.monotonic() + 15
    n = None
    while time.monotonic() < deadline:
        root = try_hierarchy()
        if root is not None:
            n = find(root, desc="Show roots") or find(root, text="Show roots")
            if n:
                break
        time.sleep(0.4)
    if n is None:
        raise AssertionError("SAF picker did not load (Show roots missing)")
    if n:
        tap_node(n)
    time.sleep(0.5)
    tap(text=DEVICE_MODEL)
    wait_text("Download", 12)
    tap(text="Download")
    wait_text(filename, 15)
    tap(text=filename)


def wait_text(text, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = try_hierarchy()
        if root is not None and find(root, text=text):
            return
        time.sleep(0.4)
    raise AssertionError("text not found: %s" % text)


def wait_any_viewer(timeout=100):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = try_hierarchy()
        if root is not None and (find(root, id="btnMore") or find(root, desc="Compress")):
            return
        time.sleep(0.7)
    raise AssertionError("document viewer did not open")


def seed_library():
    set_locale("en", "1.0")
    seeded = []
    for name in ("fixture-invoice", "fixture-letter", "fixture-report-3p"):
        for attempt in range(2):
            try:
                launch()
                open_create_sheet()
                tap(text="Import PDF")
                saf_pick(name + ".pdf")
                wait_any_viewer()
                root = hierarchy()
                if find(root, text="Keep"):
                    tap(text="Keep")
                back()
                root = hierarchy()
                if not find(root, text=name):
                    # import retention sheet may still be up
                    if find(root, text="Keep"):
                        tap(text="Keep")
                seeded.append(name)
                break
            except Exception as exc:
                GRAPH["errors"].append({"where": "seed/" + name,
                                        "error": str(exc), "attempt": attempt + 1})
                if attempt == 1:
                    safe_adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)
    GRAPH["meta"]["seeded_documents"] = seeded
    return seeded


# ---------------------------------------------------------------------- nav / ready
def ready_library(root):
    return find(root, id="swHomeSearch") is not None


def ready_tools(root):
    return find(root, id="swToolsSearch") is not None


def ready_vault_tab(root):
    return find(root, id="swVaultStatus") is not None


def ready_me(root):
    return find(root, id="swMeList") is not None


def ready_any(root, ids):
    return any(find(root, id=i) for i in ids)


def ready_doc(root):
    return find(root, id="btnMore") is not None or (
        find(root, id="btnEdit") is not None and find(root, id="btnSavePdf") is not None)


def ready_page(root):
    return find(root, id="btnCrop") is not None


def ready_settings(root):
    return find(root, id="rowLanguage") is not None or find(root, text="App language") is not None


def ready_trash(root):
    return ("TrashActivity" in current_activity()) or (
        find(root, text="Empty Trash") is not None or find(root, text="Try again") is not None)


def ready_backup(root):
    return find(root, id="backupNow") is not None or find(root, text="Back up now") is not None


def ready_vault(root):
    return find(root, id="vaultSetupButton") is not None or find(root, id="vaultLock") is not None \
        or find(root, id="vaultLockedPlaceholder") is not None or find(root, id="vaultUnlockButton") is not None


def ready_setup(root):
    return find(root, id="toolSetupRun") is not None


def ready_result(root):
    return find(root, id="toolResultOpen") is not None or find(root, id="btnSaveResult") is not None


def ready_merge(root):
    return find(root, id="btnMerge") is not None or find(root, id="btnAddPdf") is not None


def ready_lock(root):
    return find(root, text="Choose a document") is not None or find(root, id="docPickerRow") is not None


def ready_ocr(root):
    return find(root, id="ocrModePage") is not None or find(root, text="Tidy text") is not None


def ready_review(root):
    return find(root, id="btnSavePdf") is not None and find(root, id="btnToolRotate") is not None


def ready_qr(root):
    return find(root, id="qrClose") is not None or find(root, text="Scan code") is not None


# Nav helpers -----------------------------------------------------------------
def nav_launch():
    return launch()


def nav_tab(view_id, ready):
    launch()
    tap(id=view_id)
    return wait_ready(ready, 25)


def nav_pro():
    launch()
    tap(id="swNavMe")
    tap_text_any("ScanWhirl Pro", "Pro")
    return wait_ready(lambda r: ready_any(r, ("swProBuy", "swProClose", "swProPrice")), 20)


def tap_doc():
    root = hierarchy()
    container = find(root, id="swDocList") or root
    cands = [n for n in container.iter("node") if n.get("clickable") == "true" and valid(n)
             and ((n.get("text") or "") or (n.get("content-desc") or ""))]
    if not cands:
        raise AssertionError("no document card in Library")
    pick = next((n for n in cands if any(f in (node_blob(n)) for f in FIXTURE_NAMES)), cands[0])
    tap_node(pick)


def nav_doc():
    launch()
    tap_doc()
    return wait_ready(ready_doc, 25)


def nav_page():
    if not nav_doc():
        return False
    root = hierarchy()
    n = find(root, id="btnEdit") or find(root, desc="Edit") or find(root, desc="Edit / crop")
    if not n:
        return False
    tap_node(n)
    return wait_ready(ready_page, 20)


def nav_settings():
    launch()
    tap(id="swNavMe")
    tap_text_any("Settings")
    return wait_ready(ready_settings, 20)


def nav_trash():
    launch()
    tap(id="swNavMe")
    tap_text_any("Trash")
    return wait_ready(ready_trash, 20)


def nav_backup():
    launch()
    tap(id="swNavMe")
    tap_text_any("Backup & restore")
    return wait_ready(ready_backup, 20)


def nav_vault():
    launch()
    tap(id="swNavVault")
    wait_ready(ready_vault_tab, 20)
    n = try_hierarchy()
    n = find(n, id="swVaultAction") if n is not None else None
    if n:
        tap_node(n)
    return wait_ready(ready_vault, 25)


def nav_create():
    launch()
    open_create_sheet()
    return wait_ready(lambda r: find(r, id="swCreateScan") is not None, 15)


def nav_create_tool(tile_id, fallback_text):
    launch()
    open_create_sheet()
    root = hierarchy()
    n = find(root, id=tile_id) or find(root, text=fallback_text)
    if not n:
        return False
    tap_node(n)
    return wait_ready(lambda r: current_activity() not in ("ShellActivityCyan", "unknown"), 20)


def nav_idcard():
    return nav_create_tool("swCreateIdCard", "ID card")


def nav_passport():
    return nav_create_tool("swCreatePassport", "Passport")


def nav_tool_setup(tool="Compress"):
    launch()
    tap(id="swNavTools")
    wait_ready(ready_tools, 25)
    try:
        tap(desc=tool)
    except AssertionError:
        seek(desc=tool)
        root = hierarchy()
        tap_node(find(root, desc=tool))
    return wait_ready(ready_setup, 20)


def nav_merge():
    return nav_tool_setup("Merge") and wait_ready(ready_merge, 15)


def nav_lock(tool="Protect PDF"):
    return nav_tool_setup(tool) or _nav_lock_direct()


def _nav_lock_direct():
    launch()
    tap(id="swNavTools")
    wait_ready(ready_tools, 20)
    try:
        tap(text="Choose a document")
    except AssertionError:
        return False
    return wait_ready(ready_lock, 20)


def nav_ocr():
    launch()
    tap(id="swNavTools")
    wait_ready(ready_tools, 20)
    tap_text_any("Read text", "To text")
    # a DocumentPicker opens; pick a doc
    root = hierarchy()
    row = find(root, id="docPickerRow")
    if row:
        tap_node(row)
    return wait_ready(ready_ocr, 30)


def nav_review():
    """Gallery import route (camera/ML gallery crop is skipped)."""
    launch()
    open_create_sheet()
    tap_text_any("Import photos")
    tap_text_any("Keep photos as they are")
    root = hierarchy()
    n = find(root, desc="Show roots") or find(root, text="Show roots")
    if n:
        tap_node(n)
    tap(text=DEVICE_MODEL)
    wait_text("Download", 12)
    tap(text="Download")
    wait_text("fixture-photo.png", 15)
    tap(text="fixture-photo.png")
    return wait_ready(ready_review, 40)


SCREENS = [
    {"id": "S01", "name": "Library tab", "nav": nav_launch, "ready": ready_library, "repeat": True,
     "activity": "ShellActivityCyan"},
    {"id": "S02", "name": "Tools tab", "nav": lambda: nav_tab("swNavTools", ready_tools),
     "ready": ready_tools, "repeat": True, "activity": "ShellActivityCyan"},
    {"id": "S03", "name": "Vault tab (lobby)", "nav": lambda: nav_tab("swNavVault", ready_vault_tab),
     "ready": ready_vault_tab, "repeat": True, "activity": "ShellActivityCyan"},
    {"id": "S04", "name": "Me tab", "nav": lambda: nav_tab("swNavMe", ready_me),
     "ready": ready_me, "repeat": True, "activity": "ShellActivityCyan"},
    {"id": "S05", "name": "Pro screen", "nav": nav_pro,
     "ready": lambda r: ready_any(r, ("swProBuy", "swProClose", "swProPrice")),
     "repeat": False, "activity": "ShellActivityCyan"},
    {"id": "S06", "name": "DocumentViewer", "nav": nav_doc, "ready": ready_doc,
     "repeat": True, "activity": "DocumentViewerActivity"},
    {"id": "S07", "name": "PdfViewer", "skip": "external/legacy PDF viewer route; not reachable from launcher"},
    {"id": "S08", "name": "PageViewer", "nav": nav_page, "ready": ready_page,
     "repeat": False, "activity": "PageViewerActivity"},
    {"id": "S09", "name": "Settings", "nav": nav_settings, "ready": ready_settings,
     "repeat": False, "activity": "SettingsActivity"},
    {"id": "S10", "name": "Trash", "nav": nav_trash, "ready": ready_trash,
     "repeat": False, "activity": "TrashActivity"},
    {"id": "S11", "name": "VaultActivity", "nav": nav_vault, "ready": ready_vault,
     "repeat": False, "activity": "VaultActivity"},
    {"id": "S12", "name": "Backup", "nav": nav_backup, "ready": ready_backup,
     "repeat": False, "activity": "BackupActivity"},
    {"id": "S13", "name": "QR", "skip": "barcode scanner is the only input (ML Kit); no file-import route"},
    {"id": "S14", "name": "OcrEditor / Text", "nav": nav_ocr, "ready": ready_ocr,
     "repeat": False, "activity": "OcrEditorActivity"},
    {"id": "S15", "name": "ScanReview (gallery route)", "nav": nav_review, "ready": ready_review,
     "repeat": False, "activity": "ScanReviewActivity"},
    {"id": "S16", "name": "ToolSetup (Compress)", "nav": lambda: nav_tool_setup("Compress"),
     "ready": ready_setup, "repeat": False, "activity": "ToolSetupActivity"},
    {"id": "S17", "name": "ToolResult", "skip": "captured during tool exercise (see tool_results.json)"},
    {"id": "S18", "name": "MergePdf", "nav": nav_merge, "ready": ready_merge,
     "repeat": False, "activity": "MergePdfActivity"},
    {"id": "S19", "name": "ExportResult", "skip": "legacy export route; no launcher path"},
    {"id": "S20", "name": "BookPages", "skip": "Coming-soon / direct test route only"},
    {"id": "S21", "name": "LockTools (Protect)", "nav": lambda: nav_lock("Protect PDF"),
     "ready": ready_lock, "repeat": False, "activity": "LockToolsActivity"},
    {"id": "S22", "name": "Stamp editor", "skip": "Pro editor reached only with an armed Sign/stamp tool; covered via result screens"},
    {"id": "S23", "name": "IdCard", "nav": nav_idcard,
     "ready": lambda r: current_activity() not in ("ShellActivityCyan", "unknown"),
     "repeat": False, "activity": "IdCardActivity"},
    {"id": "S24", "name": "Passport", "nav": nav_passport,
     "ready": lambda r: current_activity() not in ("ShellActivityCyan", "unknown"),
     "repeat": False, "activity": "PassportActivity"},
    {"id": "S25", "name": "BusinessCard", "skip": "registry entry not READY; direct/test route only"},
    {"id": "S26", "name": "Batch", "nav": lambda: nav_tool_setup("Batch tools"),
     "ready": ready_setup, "repeat": False, "activity": "BatchActivity"},
    {"id": "S27", "name": "FormReady", "skip": "form-preset deep link; captured via tool exercise"},
    {"id": "S28", "name": "PhotoCrop", "skip": "gallery crop route only"},
    {"id": "S29", "name": "MainActivity (legacy)", "skip": "not reachable from launcher (exported=false)"},
]


def wait_ready(ready, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = try_hierarchy()
        if root is not None:
            try:
                if ready(root):
                    return True
            except Exception:
                pass
        time.sleep(0.5)
    return False


def go_to(screen):
    for attempt in range(2):
        try:
            screen["nav"]()
            if wait_ready(screen["ready"], 12):
                return True
        except Exception as exc:
            GRAPH["errors"].append({"where": "nav/" + screen["id"], "error": str(exc),
                                    "attempt": attempt + 1})
    return False


def identify(root):
    for s in SCREENS:
        if s.get("nav") and s["ready"](root):
            return s["id"] + " " + s["name"]
    return "unknown/" + current_activity()


def overlay_open(root):
    """A sheet/picker still covers the screen even when the anchor is in the dump."""
    return any(find(root, id=i) for i in ("swSheetRoot", "swSheetClose",
                                          "docPickerSheetRoot", "aerr_wait"))


# ------------------------------------------------------------------------ crawl
def crawl_screen(screen):
    sid = screen["id"]
    if screen.get("skip"):
        GRAPH["skipped"].append({"screen": sid, "name": screen["name"],
                                 "reason": screen["skip"]})
        return
    if not go_to(screen):
        GRAPH["errors"].append({"where": "crawl/" + sid, "error": "navigation failed"})
        return
    png, xml = screenshot(sid + "_base")
    root = hierarchy()
    GRAPH["screens"].append({"id": sid, "name": screen["name"],
                             "activity": current_activity(), "title": screen_title(root),
                             "screenshot": png, "dump": xml,
                             "clickable_controls": len(clickable_nodes(root))})
    print("CRAWL", sid, screen["name"], "controls=", len(clickable_nodes(root)), flush=True)

    # Snapshot the control identities; after each tap we re-find by key so a
    # changed scroll position or sheet cannot leave us tapping stale bounds.
    keys = []
    for n in clickable_nodes(root):
        k = control_key(n)
        if k not in keys:
            keys.append(k)
    for index, key in enumerate(keys):
        root = try_hierarchy()
        if root is None or not screen["ready"](root):
            if not go_to(screen):
                break
            root = hierarchy()
        node = next((n for n in clickable_nodes(root) if control_key(n) == key), None)
        if node is None:
            # try a bounded scroll to find a control that moved out of view
            try:
                node = seek(**({"id": key[3:]} if key.startswith("id:") else
                               {"text": key[5:]} if key.startswith("text:") else
                               {"desc": key[5:]}))
            except AssertionError:
                record_visit({"resource-id": "", "content-desc": "", "text": key[5:] if ":" in key else key},
                             sid, "not present on re-entry", None, "ERROR", "control vanished")
                continue
        reason = skip_reason(node)
        if reason:
            GRAPH["skipped"].append({"screen": sid, "control": control_key(node),
                                     "label": label(node), "reason": reason})
            record_visit(node, sid, None, None, "SKIPPED", reason)
            continue
        tag = "%s_%02d" % (sid, index)
        try:
            tap_node(node)
            time.sleep(0.7)
            after = try_hierarchy()
            title = screen_title(after) if after is not None else "no hierarchy"
            res_screen = identify(after) if after is not None else "unknown"
            png2, _ = screenshot(tag)
            record_visit(node, sid, title + " -> " + res_screen, png2, "VISITED")
        except Exception as exc:
            GRAPH["errors"].append({"where": "tap/" + sid + "/" + key, "error": str(exc)})
            record_visit(node, sid, None, None, "ERROR", str(exc)[:160])
        # return to the anchor so the next control starts from the same screen;
        # a sheet can remain in the dump over the anchor, so close overlays too.
        for _ in range(6):
            r = try_hierarchy()
            if r is not None and screen["ready"](r) and not overlay_open(r):
                break
            back()
        else:
            go_to(screen)
        time.sleep(0.2)


def locale_pass():
    for lang, scale in (("hi", "1.0"), ("en", "2.0"), ("hi", "2.0")):
        tag = "loc_%s_%s" % (lang, scale.replace(".", ""))
        set_locale(lang, scale)
        for s in SCREENS:
            if not s.get("repeat") or s.get("skip"):
                continue
            try:
                if go_to(s):
                    png, xml = screenshot("%s_%s" % (tag, s["id"]))
                    GRAPH["locales"].append({"locale": lang, "scale": scale,
                                             "screen": s["id"], "screenshot": png, "dump": xml})
            except Exception as exc:
                GRAPH["errors"].append({"where": "locale/" + tag + "/" + s["id"], "error": str(exc)})
    set_locale("en", "1.0")


# ------------------------------------------------------------------ tool exercise
def type_text(text):
    adb("shell", "input", "text", text)
    time.sleep(0.25)
    back()  # hide keyboard only


def pick_doc(prefer=None):
    root = hierarchy()
    rows = find_all(root, id="docPickerRow")
    if not rows:
        raise AssertionError("no documents in picker")
    if prefer:
        rows.sort(key=lambda n: 0 if prefer in node_blob(n) else 1)
    tap_node(rows[0])
    time.sleep(0.5)


def pull_outputs(tag, also_save=True):
    local = OUT / "tool_outputs" / tag
    local.mkdir(parents=True, exist_ok=True)
    info = {"files": [], "note": ""}
    if also_save:
        root = try_hierarchy()
        n = find(root, text="Also save in Documents") if root is not None else None
        if n:
            tap_node(n)
            time.sleep(0.6)
    safe_adb("pull", "/sdcard/Documents/ScanWhirl", str(local))
    for p in sorted(local.rglob("*")):
        if not p.is_file():
            continue
        rec = {"name": p.name, "bytes": p.stat().st_size}
        if p.suffix.lower() == ".pdf":
            data = p.read_bytes()
            m = re.search(rb"/Count\s+(\d+)", data)
            rec["pages"] = int(m.group(1)) if m else data.count(b"/Type /Page ")
        info["files"].append(rec)
    if not info["files"]:
        info["note"] = "no pulled output file (result screen/dump is the evidence)"
    return info


def finish_tool(tool, tag):
    """Wait for a result screen, capture it, pull output, record."""
    deadline = time.monotonic() + 130
    while time.monotonic() < deadline:
        root = try_hierarchy()
        if root is not None and (find(root, id="toolResultOpen") or find(root, id="toolResultKeep")
                                 or find(root, text="Compressed") or find(root, id="btnSaveResult")):
            break
        time.sleep(1.0)
    root = try_hierarchy()
    if root is None:
        raise AssertionError("no hierarchy after tool run")
    png, xml = screenshot("T_" + tag)
    out = pull_outputs(tag)
    GRAPH["screens"].append({"id": "T_" + tag, "name": tool + " result",
                             "activity": current_activity(), "title": screen_title(root),
                             "screenshot": png, "dump": xml})
    return {"title": screen_title(root), "screenshot": png, **out}


def generic_setup(tool, tag, fields=(), choices=(), pre=(), prefer_pdf=None, timeout=130):
    if not nav_tool_setup(tool):
        raise AssertionError("could not open ToolSetup for " + tool)
    root = hierarchy()
    row = find(root, id="toolSetupDocRow")
    if row:
        tap_node(row)
        pick_doc(prefer_pdf)
    for want in pre:
        try:
            tap_text_any(*want) if isinstance(want, (tuple, list)) else tap_text_any(want)
        except AssertionError:
            pass
    for spec in fields:
        try:
            target = find(hierarchy(), **spec["target"])
            if not target:
                target = seek(**spec["target"])
            tap_node(target)
            type_text(spec["text"])
        except Exception as exc:
            GRAPH["errors"].append({"where": "field/" + tag, "error": str(exc)})
    for text in choices:
        try:
            tap(text=text)
        except AssertionError:
            try:
                tap_node(seek(text=text))
            except AssertionError:
                pass
    n = find(hierarchy(), id="toolSetupRun") or find(hierarchy(), text="Continue")
    if not n:
        raise AssertionError("no Continue button for " + tool)
    tap_node(n)
    return finish_tool(tool, tag)


def run_tool(spec):
    name, tag = spec["name"], spec["tag"]
    try:
        kind = spec.get("kind", "generic")
        if kind == "generic":
            res = generic_setup(name, tag, spec.get("fields", ()), spec.get("choices", ()),
                                spec.get("pre", ()), spec.get("prefer"))
        elif kind == "skip":
            GRAPH["skipped"].append({"tool": name, "reason": spec["reason"]})
            return {"status": "SKIPPED", "reason": spec["reason"]}
        elif kind == "compress":
            res = generic_setup("Compress", tag,
                                fields=[{"target": {"id": "toolSetupTarget"}, "text": "100KB"}],
                                pre=[("Aim for a file size",)], prefer_pdf="fixture-report")
        elif kind == "merge":
            res = run_merge()
        elif kind == "protect":
            res = run_protect()
        elif kind == "unlock":
            res = run_unlock()
        elif kind == "backup":
            res = run_backup()
        elif kind == "vault":
            res = run_vault()
        elif kind == "trash":
            res = run_trash()
        elif kind == "idcard":
            res = run_gallery_tool("ID card", tag, "fixture-photo.png")
        elif kind == "passport":
            res = run_gallery_tool("Passport", tag, "fixture-photo.png")
        elif kind == "form":
            res = run_gallery_tool("Photo", tag, "fixture-photo.png")
        else:
            res = generic_setup(name, tag)
        res["status"] = "PASS"
        return res
    except Exception as exc:
        traceback.print_exc()
        safe_adb("shell", "input", "keyevent", "KEYCODE_BACK")
        png, _ = screenshot("T_" + tag + "_FAIL")
        return {"status": "FAIL", "error": str(exc), "screenshot": png}


def run_merge():
    if not nav_tool_setup("Merge"):
        raise AssertionError("Merge ToolSetup not reached")
    root = hierarchy()
    link = find(root, text="Merge PDFs from this phone") or find(root, regex="merge pdfs from this phone")
    if link:
        tap_node(link)
    wait_ready(ready_merge, 15)
    tap(id="btnAddPdf")
    pick_doc("fixture-invoice")
    time.sleep(0.6)
    # add a second PDF through the same picker if it is still open
    root = try_hierarchy()
    if root is not None and find(root, id="docPickerRow"):
        pick_doc("fixture-letter")
    tap(id="btnMerge")
    time.sleep(0.6)
    root = hierarchy()
    if find(root, id="swSheetInput"):
        tap(id="swSheetInput")
        type_text("MergedQA")
    primary = find(root, id="swSheetPrimary") or find(root, text="Merge")
    if primary:
        tap_node(primary)
    return finish_tool("Merge", "merge")


def run_protect():
    if not nav_tool_setup("Protect PDF"):
        # LockTools entry
        if not _nav_lock_direct():
            raise AssertionError("Protect tool not reached")
    root = hierarchy()
    row = find(root, id="toolSetupDocRow") or find(root, id="docPickerRow")
    if row:
        tap_node(row)
        pick_doc("fixture-report")
    root = hierarchy()
    fields = find_all(root, cls="EditText")
    for i, f in enumerate(fields[:2]):
        tap_node(f)
        type_text(TEST_PASSWORD)
    primary = find(hierarchy(), id="swSheetPrimary") or find(hierarchy(), text="Apply password")
    if primary:
        tap_node(primary)
    return finish_tool("Protect", "protect")


def run_unlock():
    if not nav_tool_setup("Unlock PDF"):
        if not _nav_lock_direct():
            raise AssertionError("Unlock tool not reached")
    root = hierarchy()
    row = find(root, id="docPickerRow") or find(root, id="toolSetupDocRow")
    if row:
        tap_node(row)
    root = hierarchy()
    fields = find_all(root, cls="EditText")
    for f in fields[:1]:
        tap_node(f)
        type_text(TEST_PASSWORD)
    primary = find(hierarchy(), id="swSheetPrimary") or find(hierarchy(), text="Unlock permanently")
    if primary:
        tap_node(primary)
    return finish_tool("Unlock", "unlock")


def run_backup():
    if not nav_backup():
        raise AssertionError("Backup screen not reached")
    tap(id="backupNow")
    time.sleep(0.5)
    tap_text_any("Use a backup password")
    root = hierarchy()
    f = find(root, cls="EditText") or find(root, id="swSheetInput")
    if f:
        tap_node(f)
        type_text(TEST_PASSWORD)
    tap_text_any("Continue")
    deadline = time.monotonic() + 130
    while time.monotonic() < deadline:
        root = try_hierarchy()
        if root is not None and find(root, text="Backup ready"):
            break
        time.sleep(1)
    png, xml = screenshot("T_backup")
    paths = safe_adb("shell", "run-as", PKG, "find", "cache/fullbackup", "-name", "*.swbackup")
    info = pull_outputs("backup", also_save=False)
    info["status"] = "PASS" if ".swbackup" in paths else "FAIL"
    info["archive"] = paths.strip()[:300]
    info["title"] = "Backup ready"; info["screenshot"] = png; info["dump"] = xml
    return info


def run_vault():
    if not nav_vault():
        raise AssertionError("Vault screen not reached")
    root = hierarchy()
    if find(root, id="vaultSetupButton"):
        tap(id="vaultSetupButton")
        time.sleep(0.5)
        root = hierarchy()
        a = find(root, id="vaultPasscodeInput")
        b = find(root, id="vaultPasscodeConfirm")
        for f in (a, b):
            if f:
                tap_node(f); type_text(TEST_PASSWORD)
        primary = find(hierarchy(), id="swSheetPrimary") or find(hierarchy(), text="Create Vault")
        if primary:
            tap_node(primary)
        time.sleep(1)
        # recovery code is visible in the hierarchy even though FLAG_SECURE blackens the PNG
        root = hierarchy()
        code = find(root, id="vaultRecoveryCode")
        proof = ""
        if code is not None and code.get("text"):
            proof = re.sub(r"\s+", "", code.get("text"))[:4]
        saved = find(root, text="I saved it")
        if saved:
            tap_node(saved)
        if proof:
            root = hierarchy()
            f = find(root, id="vaultRecoveryInput")
            if f:
                tap_node(f); type_text(proof)
        primary = find(hierarchy(), id="swSheetPrimary") or find(hierarchy(), text="Finish setup")
        if primary:
            tap_node(primary)
    root = hierarchy()
    if find(root, id="vaultAddFiles"):
        tap(id="vaultAddFiles")
        root = hierarchy()
        n = find(root, text="Move a saved scan here") or find(root, id="vaultAddRow")
        if n:
            tap_node(n)
        root = try_hierarchy()
        if root is not None and find(root, id="docPickerRow"):
            pick_doc("fixture-letter")
    png, xml = screenshot("T_vault")
    return {"title": screen_title(hierarchy()), "screenshot": png, "dump": xml, "note": "FLAG_SECURE: XML authoritative"}


def run_trash():
    # Move a seeded doc to Trash, then restore it.
    launch()
    tap_doc()
    wait_ready(ready_doc, 20)
    root = hierarchy()
    more = find(root, id="btnMore")
    if more:
        tap_node(more)
        n = seek(text="Move to Trash")
        tap_node(n)
    time.sleep(0.8)
    root = try_hierarchy()
    if root is not None and find(root, text="Move to Trash"):
        tap(text="Move to Trash")
    if not nav_trash():
        raise AssertionError("Trash not reached")
    root = hierarchy()
    restore = find(root, id="btnTrashRestore") or find(root, text="Restore")
    if restore:
        tap_node(restore)
        time.sleep(0.5)
    png, xml = screenshot("T_trash")
    return {"title": screen_title(hierarchy()), "screenshot": png, "dump": xml}


def run_gallery_tool(tool, tag, filename):
    launch()
    open_create_sheet()
    tap_text_any(tool)
    time.sleep(0.6)
    # pick from gallery if a choice sheet appears
    root = hierarchy()
    n = find(root, text="Pick from gallery") or find(root, text="Pick a photo") or find(root, text="Choose a photo")
    if n:
        tap_node(n)
    root = hierarchy()
    s = find(root, desc="Show roots") or find(root, text="Show roots")
    if s:
        tap_node(s)
    tap(text=DEVICE_MODEL)
    wait_text("Download", 12)
    tap(text="Download")
    wait_text(filename, 15)
    tap(text=filename)
    time.sleep(1)
    root = hierarchy()
    apply = find(root, id="btnApplyCrop") or find(root, id="btnApply") or find(root, text="Crop and save")
    if apply:
        tap_node(apply)
    time.sleep(0.8)
    root = hierarchy()
    cont = find(root, id="toolSetupRun") or find(root, text="Continue") or find(root, text="Make file")
    if cont:
        tap_node(cont)
    return finish_tool(tool, tag)


TOOLS = [
    {"name": "Compress", "tag": "compress", "kind": "compress"},
    {"name": "Merge", "tag": "merge", "kind": "merge"},
    {"name": "Split", "tag": "split", "kind": "generic", "fields": [{"target": {"id": "toolSetupRanges"}, "text": "1"}]},
    {"name": "Extract pages", "tag": "extract", "kind": "generic", "fields": [{"target": {"id": "toolSetupRanges"}, "text": "1"}]},
    {"name": "Insert pages", "tag": "insert", "kind": "generic"},
    {"name": "Resize", "tag": "resize", "kind": "generic"},
    {"name": "PDF to images", "tag": "pdf_to_images", "kind": "generic", "choices": ["JPG"]},
    {"name": "Long image", "tag": "long_image", "kind": "generic"},
    {"name": "To Word", "tag": "to_word", "kind": "generic"},
    {"name": "To PowerPoint", "tag": "to_powerpoint", "kind": "generic"},
    {"name": "To Excel", "tag": "to_excel", "kind": "generic", "choices": ["Tables only"]},
    {"name": "To text", "tag": "to_text", "kind": "generic"},
    {"name": "ZIP export", "tag": "zip", "kind": "generic"},
    {"name": "Searchable PDF", "tag": "searchable_pdf", "kind": "generic"},
    {"name": "PDF/A", "tag": "pdfa", "kind": "generic"},
    {"name": "Remove metadata", "tag": "remove_metadata", "kind": "generic"},
    {"name": "Protect PDF", "tag": "protect", "kind": "protect"},
    {"name": "Unlock PDF", "tag": "unlock", "kind": "unlock"},
    {"name": "ID card", "tag": "idcard", "kind": "idcard"},
    {"name": "Passport", "tag": "passport", "kind": "passport"},
    {"name": "Form-ready photo", "tag": "form", "kind": "form"},
    {"name": "QR code", "tag": "qr", "kind": "skip", "reason": "barcode scanner is the only input (ML Kit)"},
    {"name": "Backup create", "tag": "backup", "kind": "backup"},
    {"name": "Vault add/open", "tag": "vault", "kind": "vault"},
    {"name": "Trash restore", "tag": "trash", "kind": "trash"},
]


def tool_exercise():
    for spec in TOOLS:
        res = run_tool(spec)
        GRAPH["tools"].append({"tool": spec["name"], **res})
        print("TOOL", spec["name"], res.get("status"), str(res.get("error", ""))[:140], flush=True)


# ----------------------------------------------------------------------- reports
def write_reports(fatal, anr, alive):
    covered = len(COVERED_SLOTS)
    pct = round(100.0 * covered / TOTAL_SLOTS, 1)
    meta = GRAPH["meta"]
    meta.update({
        "package": PKG, "source_sha": os.environ.get("GITHUB_SHA", "unknown"),
        "generated_utc": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "emulator_minutes": round((time.monotonic() - STARTED) / 60, 1),
        "total_checklist_slots": TOTAL_SLOTS, "checklist_slots_covered": covered,
        "coverage_pct": pct, "distinct_controls_visited": len(VISITED),
        "screens_mapped": sum(1 for s in SCREENS if not s.get("skip")),
        "screens_skipped": sum(1 for s in SCREENS if s.get("skip")),
        "crash": fatal, "anr": anr, "app_alive_at_end": bool(alive),
    })
    (OUT / "graph.json").write_text(json.dumps(GRAPH, indent=2, ensure_ascii=False), encoding="utf-8")
    (OUT / "tool_results.json").write_text(
        json.dumps(GRAPH["tools"], indent=2, ensure_ascii=False), encoding="utf-8")

    status = {}
    for e in GRAPH["edges"]:
        status.setdefault(e["status"], 0)
        status[e["status"]] += 1
    md = ["# ScanWhirl automated interface map", "",
          "- package: `%s`" % PKG,
          "- source: `%s`" % meta["source_sha"],
          "- generated: %s (%s emulator minutes)" % (meta["generated_utc"], meta["emulator_minutes"]),
          "- **coverage: %s%% — %d / %d checklist slots**" % (pct, covered, TOTAL_SLOTS),
          "- distinct controls visited: %d" % len(VISITED),
          "- screens mapped: %d / 29; skipped: %d" % (meta["screens_mapped"], meta["screens_skipped"]),
          "- edge outcomes: %s" % ", ".join("%s=%d" % kv for kv in sorted(status.items())),
          "- crash: %s; ANR: %s; app alive at end: %s" % (fatal, anr, bool(alive)), "",
          "## Screens", "", "| screen | name | controls | screenshot |", "| --- | --- | --- | --- |"]
    for s in GRAPH["screens"]:
        md.append("| %s | %s | %s | [%s](%s) |" % (
            s["id"], s.get("name", ""), s.get("clickable_controls", "-"),
            Path(s["screenshot"]).name, s["screenshot"]))
    md += ["", "## Control index (control id · label → result screenshot)", "",
           "| screen | control | label | checklist | result | status | screenshot |",
           "| --- | --- | --- | --- | --- | --- | --- |"]
    for e in GRAPH["edges"]:
        md.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            e["from"], e["control"], str(e["label"])[:48], ",".join(e["slots"]) or "-",
            str(e.get("result_title") or e.get("reason") or "")[:48], e["status"],
            "[png](%s)" % e["screenshot"] if e["screenshot"] else "-"))
    md += ["", "## Skipped (with reason)", ""]
    for s in GRAPH["skipped"]:
        md.append("- %s %s: %s" % (s.get("screen") or s.get("tool", ""),
                                   s.get("name") or s.get("control") or "", s["reason"]))
    md += ["", "## Tools", "", "| tool | status | detail | screenshot |",
           "| --- | --- | --- | --- |"]
    for t in GRAPH["tools"]:
        detail = t.get("error") or t.get("reason") or t.get("title") or ""
        sizes = ", ".join("%s:%dB%s" % (f["name"], f["bytes"],
                                        ("/%dp" % f["pages"]) if "pages" in f else "")
                          for f in t.get("files", []))
        md.append("| %s | %s | %s %s | %s |" % (
            t["tool"], t.get("status", "?"), str(detail)[:60], sizes[:120],
            "[png](%s)" % t["screenshot"] if t.get("screenshot") else "-"))
    if GRAPH["errors"]:
        md += ["", "## Harness errors", ""]
        for e in GRAPH["errors"][:80]:
            md.append("- %s: %s" % (e["where"], str(e["error"])[:160]))
    body = "\n".join(md)
    (OUT / "index.md").write_text(body, encoding="utf-8")
    html = ("<!doctype html><meta charset='utf-8'><title>ScanWhirl interface map</title>"
            "<style>body{font:14px system-ui;margin:2em;max-width:1100px}table{border-collapse:collapse}"
            "td,th{border:1px solid #ccc;padding:4px 6px;font-size:12px}img{max-width:180px}"
            "h1{color:#185a9d}</style><h1>ScanWhirl interface map</h1>"
            "<p><b>%s%% coverage — %d / %d checklist slots.</b> Source %s. crash=%s anr=%s</p>" %
            (pct, covered, TOTAL_SLOTS, meta["source_sha"], fatal, anr))
    for line in md[8:]:
        if line.startswith("|"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            html += "<tr>" + "".join("<td>%s</td>" % c for c in cells) + "</tr>"
        elif line.startswith("##"):
            html += "<h2>%s</h2>" % line.lstrip("# ")
    html += "</table>"
    (OUT / "index.html").write_text(html, encoding="utf-8")

    summary = "\n".join(md[:12])
    (OUT / "report.md").write_text(summary + "\n", encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as h:
            h.write("\n" + summary + "\n")
    print(summary, flush=True)


# -------------------------------------------------------------------------- main
def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true", "uimap is CI-only: no local/physical device runs"
    assert re.fullmatch(r"emulator-\d+", os.environ.get("ANDROID_SERIAL", "")), "emulator serial required"
    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "not an emulator"
    OUT.mkdir(parents=True, exist_ok=True)
    configure_device()
    global DEVICE_MODEL
    DEVICE_MODEL = adb("shell", "getprop", "ro.product.model") or "Pixel 6"
    GRAPH["meta"]["device"] = {"model": DEVICE_MODEL, "sdk": adb("shell", "getprop", "ro.build.version.sdk")}

    try:
        make_and_push_fixtures()
        seed_library()

        for screen in SCREENS:
            try:
                crawl_screen(screen)
            except Exception as exc:
                traceback.print_exc()
                GRAPH["errors"].append({"where": "crawl/" + screen["id"], "error": str(exc)})

        locale_pass()
        tool_exercise()
    except Exception as exc:
        traceback.print_exc()
        GRAPH["errors"].append({"where": "main", "error": str(exc)})
    finally:
        # Even an early harness failure must leave a readable map for the lead.
        logs = safe_adb("logcat", "-d")
        events = safe_adb("logcat", "-d", "-b", "events")
        (OUT / "logcat.txt").write_text(logs, encoding="utf-8")
        (OUT / "uimap-events.txt").write_text(events, encoding="utf-8")
        fatal = any(re.search(r"Process: " + re.escape(PKG) + r"(?:,|\s|$)", b[:1200])
                    for b in logs.split("FATAL EXCEPTION")[1:])
        anr = bool(re.search(r"am_anr.*" + re.escape(PKG), events))
        alive = adb("shell", "pidof", PKG, check=False)
        write_reports(fatal, anr, alive)
    return 0  # the map is an artifact set, not a gate; failures are reported as bug leads


if __name__ == "__main__":
    raise SystemExit(main())
