#!/usr/bin/env python3
"""F1–F9: text/description-driven journeys on a disposable CI emulator.

No private files, app-data injection, fixed tap/swipe coordinates or source edits.
Every action derives bounds from a fresh hierarchy. Failures retain XML + PNG.
Only generated PDFs and a fictional QA backup passphrase are used.
"""
import os
from pathlib import Path
import re
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET

from make_pdf import build
from uitool import dump_xml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PKG = "com.scanwhirl.app.test"
OUT = Path(os.environ.get("OUT", ROOT / "artifacts" / os.environ.get("API_LEVEL", "unknown")))
NAME = "e2e-three-pages"


def adb(*args, binary=False):
    p = subprocess.run(["adb", *map(str, args)], capture_output=True, timeout=90, check=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace").strip()


def bounds(node):
    return tuple(map(int, re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]",
                                      node.get("bounds", "")).groups()))


def match(node, text=None, desc=None, regex=None, cls=None, rid=None):
    # Prefer stable resource-ids over copy: a UX copy rename must not break a flow.
    # uiautomator reports either a fully-qualified "pkg:id/name" or a bare "name".
    resource_id = node.get("resource-id") or ""
    return ((text is None or node.get("text") == text)
            and (desc is None or node.get("content-desc") == desc)
            and (regex is None or re.search(regex, node.get("text", "") + "\n" + node.get("content-desc", "")))
            and (cls is None or node.get("class", "").endswith(cls))
            and (rid is None or resource_id == rid or resource_id.endswith("/" + rid)
                 or resource_id.endswith(":id/" + rid)))


def hierarchy():
    data = dump_xml()
    if not data:
        raise AssertionError("uiautomator hierarchy unavailable")
    return ET.fromstring(data)


def _visible_matches(root, **want):
    return [n for n in root.iter("node") if match(n, **want) and bounds(n)[2] > bounds(n)[0]
            and bounds(n)[3] > bounds(n)[1]]


def find(root, **want):
    nodes = _visible_matches(root, **want)
    if not nodes:
        raise AssertionError(f"Missing text/content-desc/resource-id control: {want}")
    return nodes[0]


def find_within(parent, **want):
    """Find a control inside a specific row/card, so a repeated id stays unambiguous."""
    # element.iter() includes the element itself; restrict to the parent's subtree.
    nodes = [n for n in parent.iter("node") if match(n, **want) and bounds(n)[2] > bounds(n)[0]
             and bounds(n)[3] > bounds(n)[1]]
    if not nodes:
        raise AssertionError(f"Missing control within parent: {want}")
    return nodes[0]


def wait(timeout=35, **want):
    deadline = time.monotonic() + timeout
    while True:
        try:
            return find(hierarchy(), **want)
        except AssertionError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.5)


def require_rotation(root, expected):
    """The UI dump's rotation is observed state, not the requested Settings value.

    Never claim a configuration-change recovery PASS until the emulator truly
    changed its display orientation. A mere settings write is not evidence.
    """
    observed = root.get("rotation")
    assert observed == str(expected), (
        f"Emulator rotation did not change: expected {expected}, observed {observed!r}; "
        "this is an emulator/harness failure, not proof of app data loss"
    )
    return root


def wait_rotation(expected, timeout=12):
    deadline = time.monotonic() + timeout
    last = "no hierarchy yet"
    while True:
        try:
            return require_rotation(hierarchy(), expected)
        except AssertionError as exc:
            last = str(exc)
            if time.monotonic() >= deadline:
                raise AssertionError(last) from exc
            time.sleep(0.3)


def clickable_ancestor(root, node):
    """Climb to the nearest clickable node, or None if there is none in this tree."""
    parents = {child: parent for parent in root.iter() for child in parent}
    while node.get("clickable") != "true" and node in parents and parents[node].tag == "node":
        node = parents[node]
    return node if node.get("clickable") == "true" else None


def tap_node(root, node):
    ancestor = clickable_ancestor(root, node)
    # DocumentsUI ListView rows expose no clickable ancestor: click the matched
    # label itself, not the centre of the entire window reached by climbing.
    target = ancestor if ancestor is not None else node
    x1, y1, x2, y2 = bounds(target)
    adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)


def tap(**want):
    root = hierarchy()
    tap_node(root, find(root, **want))
    time.sleep(0.4)


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)


def scroll(up=False):
    root = hierarchy()
    containers = [n for n in root.iter("node") if n.get("scrollable") == "true"]
    assert containers, "No accessible scroll container"
    node = max(containers, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))
    x1, y1, x2, y2 = bounds(node)
    x, lo, hi = (x1 + x2) // 2, y1 + (y2-y1)//5, y2 - (y2-y1)//5
    adb("shell", "input", "swipe", x, lo if up else hi, x, hi if up else lo, 350)
    time.sleep(0.5)


def seek(**want):
    for _ in range(14):
        try:
            return find(hierarchy(), **want)
        except AssertionError:
            scroll()
    raise AssertionError(f"Not found after bounded scroll: {want}")


def capture(tag):
    # Only synthetic app/system picker screens on a fresh disposable emulator.
    data = dump_xml()
    if data:
        (OUT / f"{tag}.xml").write_bytes(data)
    (OUT / f"{tag}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def locale(lang="en", scale="1.0"):
    adb("shell", "settings", "put", "system", "font_scale", scale)
    adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", lang)
    launch()


def launch():
    adb("shell", "am", "force-stop", PKG)
    # Clear a leftover external SAF activity from a failed prior check's task.
    # App data is preserved. This is not a reset or a production-app command.
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n",
        f"{PKG}/com.scanwhirl.app.ui.shell.ShellActivityCyan")
    wait(desc="Library" if "en" in adb("shell", "cmd", "locale", "get-app-locales", PKG) else "कागज़ात")


def strings(lang):
    values = {}
    for path in (ROOT / "app/src/main/res" / ("values-hi" if lang == "hi" else "values")).glob("*.xml"):
        for n in ET.parse(path).getroot().findall("string"):
            values[n.get("name")] = "".join(n.itertext()).replace("\\'", "'")
    return values


def open_doc():
    locale()
    tap(text=NAME)
    wait(desc="Compress")


def f1():
    locale()
    fixture = OUT / f"{NAME}.pdf"
    fixture.write_bytes(build([[f"Fictional QA document / page {i}", "Self-generated ScanWhirl emulator fixture"]
                               for i in range(1, 4)]))
    adb("push", fixture, f"/sdcard/Download/{NAME}.pdf")
    # Same bounded retry as smoke: the disc's first tap can be swallowed by
    # its first-frame ring animation. Require the sheet, never trust the tap.
    for attempt in range(2):
        tap(desc=strings("en")["create_disc_desc"])
        try:
            wait(timeout=7, text="Import PDF")
            break
        except AssertionError:
            if attempt == 1:
                raise
    tap(text="Import PDF")
    # DocumentsUI, not am start with a URI: proves a real user can find Download.
    wait(desc="Show roots")
    tap(desc="Show roots")
    # The Downloads-provider database need not index adb-pushed files. Navigate
    # the actual internal-storage Download folder, by its displayed labels.
    tap(text=adb("shell", "getprop", "ro.product.model"))
    wait(text="Download")
    tap(text="Download")
    wait(text=f"{NAME}.pdf")
    capture("F1_picker")
    tap(text=f"{NAME}.pdf")
    wait(timeout=100, desc="Compress")
    capture("F1_imported")
    back()
    # Import-retention sheet is allowed, but keeping must be explicit.
    root = hierarchy()
    if any(n.get("text") == "Keep" for n in root.iter("node")):
        tap(text="Keep")
    wait(text=NAME)
    capture("F1_library")
    return "3-page generated PDF selected through Downloads; named document appears in Library"


def f2():
    locale()
    tap(desc="Tools")
    # Inspect every scroll viewport, not just the initially visible subset.
    previous = None
    for index in range(20):
        root = hierarchy()
        texts = [(n.get("text", ""), n.get("content-desc", "")) for n in root.iter("node")]
        assert not any(re.search(r"All\s+\d+\s+tools", " ".join(t), re.I) for t in texts), "'All N tools' found"
        capture(f"F2_catalogue_{index:02}")
        if texts == previous:
            break
        previous = texts
        scroll()
    else:
        raise AssertionError("Tools catalogue did not reach a stable bottom")
    launch()
    tap(desc="Tools")
    for title in ("Compress", "Merge"):
        tap(cls="EditText")
        adb("shell", "input", "text", title)
        back()  # keyboard only
        tap(desc=title)
        capture(f"F2_open_{title}")
        back()
        wait(desc="Tools")
        # Search is retained on Back.
        root = hierarchy()
        if any(n.get("content-desc") == "Clear search" for n in root.iter("node")):
            tap(desc="Clear search")
    root = hierarchy()
    heading = find(root, text="Recent tools")
    # Read the recent card itself, excluding the duplicated catalogue rows.
    parents = {child: parent for parent in root.iter() for child in parent}
    card = parents[parents[heading]]
    names = [n.get("content-desc") for n in card.iter("node")
             if n.get("clickable") == "true" and n.get("content-desc") in ("Merge", "Compress")]
    assert names == ["Merge", "Compress"], f"Recent card order = {names}"
    capture("F2_recent")
    return "Full catalogue has no All N tools; Recent tools = Merge, Compress (newest first)"


def f3():
    locale()
    tap(desc="Me")
    tap(text="Settings")
    wait(text="App language")
    wait(text="Text reading language")
    capture("F3_settings")
    back()
    tap(desc="Library")
    tap(text="A/अ")
    wait(text="English")
    wait(text="हिन्दी")
    capture("F3_chooser")
    tap(text="हिन्दी")
    wait(desc="कागज़ात")
    capture("F3_hindi")
    tap(text="A/अ")
    tap(text="English")
    wait(desc="Library")
    capture("F3_english")
    return "Settings distinguishes app/text languages; A/अ chooser switches actual nav EN→HI→EN"


def visible(node, min_height=0):
    x1, y1, x2, y2 = bounds(node)
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups())
    assert 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height, f"Off-screen {node.attrib}"
    assert y2-y1 >= min_height, f"Height {y2-y1}px < {min_height}px: {node.attrib}"
    return f"{node.get('bounds')}, height={y2-y1}px"


def f4():
    result = []
    for lang, text in (("en", "Private · No ads"), ("hi", "निजी · विज्ञापन नहीं")):
        locale(lang, "2.0")
        tap(desc="Me" if lang == "en" else strings("hi")["shell_nav_me"])
        node = wait(text=text)
        capture(f"F4_{lang}_200")
        result.append(lang + " " + visible(node))
    return "; ".join(result)


def f5():
    details = []
    layout_errors = []
    density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
    for lang in ("en", "hi"):
        locale(lang, "2.0")
        tap(text=NAME)
        s = strings(lang)
        tap(desc=s["tool_compress"])
        wait(text=s["compress_level_title"])
        # First frame, no scrolling; both action bounds measured before target mode.
        capture(f"F5_{lang}_setup_200")
        root = hierarchy()
        for label, text in (("primary", s["compress_action"]),
                            ("cancel", "Cancel" if lang == "en" else "रद्द करें")):
            try:
                node = find(root, text=text)
                details.append(lang + " " + label + " " + visible(node, round(48*density)))
            except AssertionError as exc:
                layout_errors.append(lang + " " + label + ": " + str(exc))
        back()
    # Actually run target-size mode at 200% after the two first-frame checks.
    locale("en", "2.0")
    tap(text=NAME)
    tap(desc="Compress")
    tap(text="Aim for a file size")
    seek(cls="EditText")
    tap(cls="EditText")
    adb("shell", "input", "text", "100KB")
    back()
    capture("F5_target_200")
    tap(text="Compress")
    wait(timeout=120, text="Compressed")
    root = hierarchy()
    texts = "\n".join(n.get("text", "") for n in root.iter("node"))
    sizes = re.findall(r"\d+(?:[.,]\d+)?\s*(?:KB|MB|kB|B)\b", texts)
    assert len(sizes) >= 2, f"Result must show original/output sizes: {texts}"
    capture("F5_result")
    assert not layout_errors, "; ".join(layout_errors + details) + "; result sizes=" + ", ".join(sizes)
    return "; ".join(details) + "; result sizes=" + ", ".join(sizes)


def f6():
    open_doc()
    require_rotation(hierarchy(), 0)
    # Accessible thumbnail descriptions are authored by the app's page adapter.
    root = hierarchy()
    candidates = [n for n in root.iter("node")
                  if re.search(r"\b[Pp]age 1\b", n.get("content-desc", ""))]
    assert candidates, "Missing stable page-1 thumbnail text/content-desc (no coordinate fallback)"
    tap_node(root, candidates[0])
    wait(regex=r"^1 / 3$")
    capture("F6_before")
    root = hierarchy()
    delete = find(root, text="Delete")
    # Verify the delete really registered, then rotate within the measured Undo
    # interval. A no-op tap followed by an unchanged page count is NOT a pass.
    started = time.monotonic()
    tap_node(root, delete)
    pending = dump_xml()
    assert pending, "No hierarchy during page-delete Undo window"
    pending_root = ET.fromstring(pending)
    find(pending_root, text="Undo")
    find(pending_root, regex=r"^1 / 2$")
    (OUT / "F6_delete_window.xml").write_bytes(pending)
    # Writing Settings.System.USER_ROTATION did not rotate API 35 in the original
    # CI run: XML stayed rotation=0, so Undo merely timed out and F6 falsely
    # blamed PageViewer. WindowManager's explicit lock requests real rotation.
    adb("shell", "cmd", "window", "user-rotation", "lock", "1")
    elapsed = time.monotonic() - started
    (OUT / "F6_timing.txt").write_text(f"Delete-to-rotation command completed in {elapsed:.3f}s", encoding="utf-8")
    assert elapsed < 3.0, f"Harness too slow for Undo window: {elapsed:.3f}s; not an app-defect claim"
    try:
        # Fail as an emulator/harness issue when the system never really
        # rotates; do NOT turn an unchanged page count into a pass.
        rotated = wait_rotation(1)
        (OUT / "F6_actual_rotation.xml").write_bytes(ET.tostring(rotated, encoding="utf-8"))
        # A real rotation recreates the Activity; PageViewer's config-change
        # guard must restore the deferred deletion of page one.
        wait(timeout=18, regex=r"^1 / 3$")
        capture("F6_rotated")
        adb("shell", "cmd", "window", "user-rotation", "lock", "0")
        wait_rotation(0)
        wait(regex=r"^1 / 3$")
        capture("F6_restored")
    finally:
        # CI-only disposable emulator; always undo orientation overrides for
        # F7–F9 even when the assertions above fail.
        adb("shell", "cmd", "window", "user-rotation", "lock", "0")
        adb("shell", "cmd", "window", "user-rotation", "free")
    return f"Viewer 1 / 3 → 1 / 2 + Undo → actual rotation=1 and 1 / 3; delete-to-rotation {elapsed:.3f}s"


def f7():
    locale()
    tap(desc="Me")
    tap(text="Backup & restore")
    # UX-L5 renamed "Back up now" -> "Create backup"; target the stable view id.
    tap(rid="backupNow")
    tap(text=strings("en")["backup_use_password"])
    wait(rid="backupPassword")
    tap(rid="backupPassword")
    # Public fictional fixture, not an owner's password or a signing credential.
    adb("shell", "input", "text", "FictionalE2EBackupOnly2026")
    back()
    tap(text=strings("en")["backup_continue"])
    wait(timeout=120, text="Backup ready")
    capture("F7_receipt")  # secure screens may be black; XML is authoritative
    root = hierarchy()
    text = "\n".join(n.get("text", "") for n in root.iter("node"))
    assert re.search(r"\b[1-9]\d*(?:[.,]\d+)?\s*(?:kB|KB|MB)\b", text), f"No nonzero KB/MB size: {text}"
    assert not re.search(r"\b0(?:[.,]0+)?\s*MB\b", text), text
    # The archive is already created in the app's folder; do not invoke Restore.
    paths = adb("shell", "run-as", PKG, "find", "cache/fullbackup", "-name", "*.swbackup")
    assert ".swbackup" in paths, f"No archive in app folder: {paths}"
    (OUT / "F7_app_folder.txt").write_text(paths, encoding="utf-8")
    return "Archive created in app folder; receipt: " + text.replace("\n", "; ")


def f8():
    details = []
    for lang in ("en", "hi"):
        locale(lang)
        s = strings(lang)
        tap(desc="Vault" if lang == "en" else s["shell_nav_vault"])
        expected = s["vault_tab_relock"]
        wait(text=expected)
        capture(f"F8_{lang}_vault")
        # Exact source-owned EN/HI phrase; also reject an accidentally weakened source string.
        assert "2" in expected, expected
        assert ("Fingerprint" in expected and "passcode" in expected if lang == "en"
                else "उँगली का निशान" in expected and "गुप्त नंबर" in expected and "मिनट" in expected), expected
        details.append(lang + ": " + expected)
    return "; ".join(details)


def f9():
    locale()
    # UX-L1 moved the single-document Trash action into the per-card quick-actions
    # sheet (the card's swDocMore overflow -> qaDelete), not the viewer's scrolling
    # Actions sheet. Target stable resource-ids: no English copy, no bounded scroll.
    root = hierarchy()
    label = find(root, text=NAME)
    card = clickable_ancestor(root, label)
    assert card is not None, "Named document card has no clickable ancestor"
    tap_node(root, find_within(card, rid="swDocMore"))
    wait(rid="qaDelete")
    capture("F9_quick_actions")
    # qaDelete -> confirmDelete(): the document moves to Trash immediately and an
    # Undo snackbar is shown. There is no second confirmation sheet to tap.
    tap(rid="qaDelete")
    time.sleep(1)
    launch()
    tap(desc="Me")
    tap(rid="swMeTrash")
    wait(text=NAME)
    capture("F9_trash")
    launch()
    wait(desc="Library")
    wait(text=strings("en")["home_search_hint"])
    capture("F9_cold_library")
    return "Named imported item moved to Trash via per-card quick actions; verified in Trash; force-stop/cold start opens Library"


def main():
    # Reject local use and any real device even if a caller bypasses flows.sh.
    assert os.environ.get("GITHUB_ACTIONS") == "true", "CI-only: no local/physical device runs"
    assert re.fullmatch(r"emulator-\d+", os.environ.get("ANDROID_SERIAL", "")), "Emulator serial required"
    assert adb("shell", "getprop", "ro.kernel.qemu") == "1", "Not an emulator"
    OUT.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    rows = []
    adb("logcat", "-c")
    try:
        for index, flow in enumerate((f1, f2, f3, f4, f5, f6, f7, f8, f9), 1):
            try:
                detail = flow()
                rows.append((f"F{index}", "PASS", detail))
            except Exception as exc:
                traceback.print_exc()
                rows.append((f"F{index}", "FAIL", str(exc)))
                try:
                    capture(f"F{index}_FAIL")
                except Exception:
                    traceback.print_exc()
            print(rows[-1], flush=True)
    finally:
        adb("shell", "cmd", "window", "user-rotation", "lock", "0")
        adb("shell", "cmd", "window", "user-rotation", "free")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
        adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", "en")
        logs = adb("logcat", "-d")
        events = adb("logcat", "-d", "-b", "events")
        (OUT / "flows-logcat.txt").write_text(logs, encoding="utf-8")
        (OUT / "flows-events.txt").write_text(events, encoding="utf-8")
        fatal = any(re.search(r"Process: " + re.escape(PKG) + r"(?:,|\s|$)", block[:1200])
                    for block in logs.split("FATAL EXCEPTION")[1:])
        anr = bool(re.search(r"am_anr.*" + re.escape(PKG), events))
        rows.append(("flows crash/ANR", "FAIL" if fatal or anr else "PASS", f"FATAL={fatal}, ANR={anr}"))
        summary = "\n## Device checklist flows\n\n"
        summary += f"Source `{os.environ.get('GITHUB_SHA', 'unknown')}`; {(time.monotonic()-start)/60:.1f} emulator minutes.\n\n"
        summary += "| check | result | detail |\n| --- | --- | --- |\n"
        for check, status, detail in rows:
            detail = detail.replace("|", "\\|").replace("\n", " ")
            summary += f"| {check} | {status} | {detail} |\n"
        with (OUT / "summary.md").open("a", encoding="utf-8") as handle:
            handle.write(summary)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
                handle.write(summary)
        print(summary, flush=True)
    return int(any(row[1] != "PASS" for row in rows) or len(rows) != 10)


if __name__ == "__main__":
    raise SystemExit(main())
