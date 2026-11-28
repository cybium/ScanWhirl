#!/usr/bin/env python3
"""Cloud screen atlas: walk every reachable ScanWhirl screen on ONE CI emulator.

CI-only, emulator-only. No local/physical adb, no Gradle, no app-data injection.
Every tap is derived from a fresh uiautomator dump (stable view ids first), and
every wait is bounded: a dead screen is recorded and the walk continues.

Outputs in OUT (default artifacts/<leg>):
  <NNN>-<slug>.png    screenshot (FLAG_SECURE screens are marked, not failed)
  <NNN>-<slug>.xml    matching uiautomator hierarchy
  report.json         screens, path taken, per-screen flags, totals
  index.html          thumbnail grid grouped by area, with the flags under each
"""
import html
import json
import os
import re
import struct
import subprocess
import sys
import time
import traceback
import zlib
import xml.etree.ElementTree as ET
from pathlib import Path

from make_pdf import build
from uitool import dump_xml, _png_pixels

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
PKG = "com.scanwhirl.app.test"
SHELL = PKG + "/com.scanwhirl.app.ui.shell.ShellActivityCyan"
LEG = os.environ.get("LEG", "unknown")
API = os.environ.get("API_LEVEL", "unknown")
OUT = Path(os.environ.get("OUT", ROOT / "artifacts" / LEG))
_SERIAL_MATCH = re.fullmatch(r"emulator-\d+", os.environ.get("ANDROID_SERIAL", ""))
SERIAL = _SERIAL_MATCH.group(0) if _SERIAL_MATCH else None
FIXTURE = "atlas-qa-fixture"
FIXTURE_PDF = FIXTURE + ".pdf"

MIN_TARGET_DP = 48.0
INSTRUCTION_WORD_LIMIT = 25
MAX_MINUTES = float(os.environ.get("ATLAS_MAX_MINUTES", "34"))

BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
PRIMARY_RE = re.compile(
    r"(swsheetprimary|primarybutton|btnprimary|swprobuy|backupnow|swvaultaction|swcreatescan)",
    re.I,
)
INSTRUCTION_ID_RE = re.compile(
    r"(message|hint|instruction|note|reason|summary|subtitle|body|receipt)", re.I
)
# Bottom-sheet window chrome: a full-screen "touch outside" surface legitimately
# overlaps every control in the sheet, so it is not a UI-quality finding.
NON_CONTENT_IDS = {"touch_outside", "design_bottom_sheet", "swSheetRoot"}

# Every READY tile in ToolRegistry (the Coming-soon tile is never rendered).
TOOLS = [
    ("scan", "Scan"), ("id_card", "ID card"), ("import_pdf", "Import PDF"),
    ("passport", "Passport"), ("qr_code", "QR code"), ("merge", "Merge"),
    ("split", "Split"), ("extract", "Extract"), ("organize_pages", "Reorder"),
    ("insert_pages", "Add pages"), ("folders", "Folders"), ("batch_tools", "Batch"),
    ("compress", "Compress"), ("resize", "Resize"), ("form_ready", "Form photo"),
    ("pdf_to_images", "PDF to JPG"), ("long_image", "Long image"),
    ("to_word", "Word"), ("to_powerpoint", "Slides"), ("to_excel", "Excel"),
    ("zip", "ZIP"), ("searchable_pdf", "OCR PDF"), ("pdfa", "PDF/A"),
    ("sign", "Sign"), ("add_text", "Add text"), ("draw", "Draw"),
    ("highlight", "Highlight"), ("watermark", "Watermark"),
    ("page_numbers", "Page numbers"), ("stamp", "Stamp"), ("redact", "Redact"),
    ("protect", "Protect"), ("unlock", "Unlock"), ("vault", "Vault"),
    ("remove_metadata", "Clear metadata"), ("read_text", "OCR"),
    ("link_index", "Link index"),
]

SCREENS = []
ERRORS = []
COVERAGE = {"tools_total": len(TOOLS), "tools_visited": [], "tools_failed": []}
N = 0
DIALOGS = 0
CURRENT_STEP = "startup"
LAST_SCREEN = None
ANRS = []
CRASHES = []
DENSITY = 1.0
WIDTH = 1080
HEIGHT = 2400
STARTED = time.monotonic()


# ---------------------------------------------------------------- pure helpers
def parse_bounds(text):
    """Parse uiautomator [l,t][r,b] into a tuple, or None for bad/absent bounds."""
    match = BOUNDS_RE.fullmatch((text or "").strip())
    return tuple(int(v) for v in match.groups()) if match else None


def px_to_dp(px, density):
    """Convert device pixels to density-independent pixels."""
    return float(px) / float(density) if density else float(px)


def intersection(a, b):
    """Rectangle intersection; None when the rectangles do not overlap."""
    if not a or not b:
        return None
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None


def rect_area(rect):
    return (rect[2] - rect[0]) * (rect[3] - rect[1]) if rect else 0


def rid_name(node):
    rid = node.get("resource-id") or ""
    return rid.rsplit("/", 1)[-1] if rid else ""


def node_label(node):
    return (node.get("text") or node.get("content-desc") or rid_name(node)
            or node.get("class", "node").rsplit(".", 1)[-1])


def is_system_node(node):
    rid = node.get("resource-id") or ""
    return rid.startswith(("com.android.", "com.google.android.", "android:id/"))


def words(text):
    return re.findall(r"[^\W_]+(?:['’-][^\W_]+)*", text or "", re.UNICODE)


def slugify(text, limit=64):
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", text or "").strip("-").lower()
    return slug[:limit].rstrip("-") or "screen"


def classify_anr(title):
    """Which app owns an 'isn't responding' dialog? System apps are named too."""
    text = title or ""
    if re.search(r"ScanWhirl|" + re.escape(PKG), text, re.I):
        return "ScanWhirl"
    match = re.match(r"(.+?)\s+isn't responding", text, re.I)
    return match.group(1).strip() if match else (text.strip() or "unknown")


def parse_crash_log(raw, screens):
    """FATAL EXCEPTION blocks -> findings, attached to the nearest earlier screen."""
    findings = []
    lines = (raw or "").splitlines()
    for index, line in enumerate(lines):
        if "FATAL EXCEPTION" not in line:
            continue
        block = "\n".join(lines[index:index + 14])
        process = re.search(r"Process:\s*([^\s,]+)", block)
        app = process.group(1) if process else "unknown"
        try:
            epoch = float(line.split()[0])
        except (ValueError, IndexError):
            epoch = None
        screen = ""
        if epoch is not None:
            earlier = [s for s in screens if s.get("captured_at", 0) <= epoch + 2]
            if earlier:
                screen = earlier[-1]["slug"]
        findings.append({
            "app": app,
            "screen": screen,
            "at_epoch": epoch,
            "top": block[:1800],
        })
    return findings


def visible_nodes(root, width, height):
    """Nodes with nonzero bounds that touch the screen at least partly."""
    found = []
    screen = (0, 0, width, height)
    for node in root.iter("node"):
        bounds = parse_bounds(node.get("bounds"))
        if not bounds or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
            continue
        if intersection(bounds, screen) is None:
            continue
        found.append((node, bounds))
    return found


def parent_map(root):
    return {child: parent for parent in root.iter() for child in parent}


def is_descendant(candidate, ancestor, parents):
    current = parents.get(candidate)
    seen = 0
    while current is not None and seen < 40:
        if current is ancestor:
            return True
        current = parents.get(current)
        seen += 1
    return False


def analyze(root, density=DENSITY, width=WIDTH, height=HEIGHT):
    """All automated UI-quality checks for one hierarchy, as plain JSON data."""
    flags = {
        "small_targets": [], "clipped_text": [], "overlaps": [],
        "multiple_primaries": [], "verbose_instructions": [],
    }
    if root is None:
        return flags
    parents = parent_map(root)
    nodes = visible_nodes(root, width, height)
    clickable = [(n, b) for n, b in nodes
                 if n.get("clickable") == "true" and not is_system_node(n)
                 and rid_name(n) not in NON_CONTENT_IDS]

    # 1. Touch targets below 48x48 dp (pixels converted with the display density).
    for node, bounds in clickable:
        w_dp = px_to_dp(bounds[2] - bounds[0], density)
        h_dp = px_to_dp(bounds[3] - bounds[1], density)
        if w_dp < MIN_TARGET_DP - 0.5 or h_dp < MIN_TARGET_DP - 0.5:
            flags["small_targets"].append({
                "label": node_label(node), "id": rid_name(node),
                "bounds": node.get("bounds"),
                "dp": [round(w_dp, 1), round(h_dp, 1)],
            })

    # 2. Text clipped by the screen or by a non-scrolling ancestor.
    for node, bounds in nodes:
        text = (node.get("text") or "").strip()
        if not text:
            continue
        entry_base = {"label": text[:80], "id": rid_name(node),
                      "bounds": node.get("bounds")}
        if (bounds[0] < -2 or bounds[1] < -2 or
                bounds[2] > width + 2 or bounds[3] > height + 2):
            entry = dict(entry_base, where="screen")
            flags["clipped_text"].append(entry)
            continue
        parent = parents.get(node)
        depth = 0
        while parent is not None and parent.tag == "node" and depth < 8:
            if parent.get("scrollable") == "true":
                break  # content larger than a viewport is expected inside a scroller
            parent_bounds = parse_bounds(parent.get("bounds"))
            if parent_bounds and (
                bounds[0] < parent_bounds[0] - 2 or bounds[1] < parent_bounds[1] - 2
                or bounds[2] > parent_bounds[2] + 2 or bounds[3] > parent_bounds[3] + 2
            ):
                entry = dict(entry_base, where="parent",
                             parent=rid_name(parent) or parent.get("class", "node"))
                flags["clipped_text"].append(entry)
                break
            parent = parents.get(parent)
            depth += 1

    # 3. Clickables visually covering each other (nested/ancestor pairs are normal).
    for index, (node_a, bounds_a) in enumerate(clickable):
        for node_b, bounds_b in clickable[index + 1:]:
            if is_descendant(node_a, node_b, parents) or is_descendant(node_b, node_a, parents):
                continue
            overlap = intersection(bounds_a, bounds_b)
            if not overlap:
                continue
            smaller = min(rect_area(bounds_a), rect_area(bounds_b))
            if smaller and rect_area(overlap) / smaller >= 0.25 and rect_area(overlap) >= 16:
                flags["overlaps"].append({
                    "a": node_label(node_a), "b": node_label(node_b),
                    "a_id": rid_name(node_a), "b_id": rid_name(node_b),
                    "bounds": str(overlap),
                })

    # 4. More than one visually primary button (resource-id based, best effort).
    primaries = [n for n, _ in nodes if PRIMARY_RE.search(rid_name(n))]
    if len(primaries) > 1:
        flags["multiple_primaries"] = [
            {"id": rid_name(n), "label": node_label(n)} for n in primaries[:8]
        ]

    # 5. Instruction copy over 25 words (sheet messages/hints/notes/summaries).
    instruction_texts = []
    total_screen_words = 0
    for node, _ in nodes:
        text = (node.get("text") or "").strip()
        if not text:
            continue
        count = len(words(text))
        total_screen_words += count
        in_sheet = any(
            (rid_name(ancestor) == "swSheetRoot")
            for ancestor in [node] + list(iter_parents(node, parents))
        )
        if in_sheet or INSTRUCTION_ID_RE.search(rid_name(node)):
            instruction_texts.append({"label": text[:160], "words": count})
    instruction_words = sum(item["words"] for item in instruction_texts)
    if instruction_words > INSTRUCTION_WORD_LIMIT:
        flags["verbose_instructions"] = {
            "words": instruction_words, "texts": instruction_texts[:10],
        }
    return flags


def iter_parents(node, parents):
    current = parents.get(node)
    seen = 0
    while current is not None and seen < 40:
        yield current
        current = parents.get(current)
        seen += 1


def png_stats(path):
    """Mean brightness and sampled unique colours (secure/blank screenshot hint)."""
    try:
        width, height, rows, channels = _png_pixels(path)
        total = count = 0
        colours = set()
        step_x, step_y = max(1, width // 60), max(1, height // 60)
        for y in range(0, height, step_y):
            row = rows[y]
            for x in range(0, width, step_x):
                base = x * channels
                r, g, b = row[base], row[base + 1], row[base + 2]
                total += (r + g + b) // 3
                count += 1
                colours.add((r >> 3, g >> 3, b >> 3))
        return {"mean": round(total / max(1, count), 1),
                "unique": len(colours), "size": "%dx%d" % (width, height)}
    except Exception as exc:  # noqa: BLE001 - a broken PNG must not stop the walk
        return {"mean": 255.0, "unique": 999, "error": str(exc)}


def write_fictional_png(path, width, height, accent):
    """A tiny self-authored PNG fixture (pure zlib, no PIL/Gradle/adb needed)."""
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # PNG filter: none
        for x in range(width):
            band = 1 if (x // 40 + y // 40) % 2 == 0 else 0
            r = (accent[0] + 40 * band) % 256
            g = (accent[1] + y // 12) % 256
            b = (accent[2] + x // 12) % 256
            rows += bytes((r, g, b))

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 6))
            + chunk(b"IEND", b""))
    Path(path).write_bytes(data)
    return data


# ------------------------------------------------------------------- adb layer
def adb(*args, binary=False, timeout=45):
    command = ["adb"] + (["-s", SERIAL] if SERIAL else []) + [str(a) for a in args]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()


def adb_ok(*args, timeout=30):
    try:
        adb(*args, timeout=timeout)
        return True
    except Exception:  # noqa: BLE001 - every adb call is best effort in a walk
        return False


def device_size_and_density():
    global WIDTH, HEIGHT, DENSITY
    size = adb("shell", "wm", "size", timeout=20)
    match = re.search(r"(\d+)x(\d+)", size)
    if match:
        WIDTH, HEIGHT = int(match.group(1)), int(match.group(2))
    density = re.findall(r"\d+", adb("shell", "wm", "density", timeout=20))
    if density:
        DENSITY = max(1, int(density[-1])) / 160.0


def current_window():
    try:
        output = adb("shell", "dumpsys", "activity", "activities", timeout=20)
    except Exception:  # noqa: BLE001
        return "unknown"
    for line in output.splitlines():
        if any(key in line for key in ("mResumedActivity", "topResumedActivity",
                                       "ResumedActivity:")):
            return line.strip()[:200]
    return "unknown"


def has_secure_flag():
    try:
        output = adb("shell", "dumpsys", "window", "windows", timeout=20)
    except Exception:  # noqa: BLE001
        return False
    return "FLAG_SECURE" in output


def collect_anr_evidence(title, step, screen):
    """Pull whatever ANR evidence this disposable CI emulator exposes."""
    chunks = ["ANR dialog: %s" % title, "leg: %s api: %s" % (LEG, API),
              "step: %s" % step, "screen: %s" % screen]
    for label, command in (
        ("dumpsys activity anr", ("shell", "dumpsys", "activity", "anr")),
        ("logcat -b events (am_anr)", ("shell", "logcat", "-d", "-b", "events")),
    ):
        try:
            output = adb(*command, timeout=30)
            chunks.append("--- %s ---\n%s" % (label, output[:12000]))
        except Exception as exc:  # noqa: BLE001 - evidence is best effort
            chunks.append("--- %s unavailable: %s ---" % (label, exc))
    try:
        listing = adb("shell", "ls", "-t", "/data/anr", timeout=20)
        for name in [n for n in listing.split() if n.endswith(".txt")][:2]:
            try:
                chunks.append("--- /data/anr/%s ---\n%s" % (
                    name, adb("shell", "cat", "/data/anr/" + name, timeout=30)[:12000]))
            except Exception as exc:  # noqa: BLE001
                chunks.append("--- /data/anr/%s unavailable: %s ---" % (name, exc))
    except Exception as exc:  # noqa: BLE001
        chunks.append("--- /data/anr listing unavailable: %s ---" % exc)
    path = OUT / ("anr-%02d.txt" % (len(ANRS) + 1))
    path.write_text("\n".join(chunks), encoding="utf-8", errors="replace")
    return path.name


# -------------------------------------------------------- uiautomator navigation
def hierarchy():
    """Fresh hierarchy; tap 'Wait' on a system ANR dialog that stole focus.

    The Pixel Launcher routinely ANRs on a cold runner and its dialog becomes the
    focused window, hiding the app from every dump. It is emulator noise, not an
    app finding; smoke.sh handles it the same way. Never tap 'Close app'.
    """
    global DIALOGS
    data = dump_xml()
    if not data:
        return None
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        return None
    for _ in range(4):
        dialog = find(root, rid="aerr_wait")
        if dialog is None:
            break
        title_node = find(root, rid="alertTitle")
        title = (title_node.get("text") if title_node is not None else "") or "isn't responding"
        recent = ANRS[-1] if ANRS else None
        if not (recent and recent["title"] == title and time.time() - recent["_t"] < 8):
            evidence = collect_anr_evidence(title, CURRENT_STEP,
                                            LAST_SCREEN["slug"] if LAST_SCREEN else "startup")
            ANRS.append({
                "leg": LEG, "api": API, "app": classify_anr(title), "title": title,
                "step": CURRENT_STEP,
                "screen": LAST_SCREEN["slug"] if LAST_SCREEN else "startup",
                "area": LAST_SCREEN["area"] if LAST_SCREEN else "startup",
                "evidence": evidence, "_t": time.time(),
            })
            print("ANR finding: %s at %s (%s)" % (title, CURRENT_STEP, evidence), flush=True)
        tap_node(root, dialog)
        DIALOGS += 1
        print("dismissed system 'not responding' dialog (#%d)" % DIALOGS, flush=True)
        time.sleep(1.2)
        data = dump_xml()
        if not data:
            return None
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            return None
    return root


def match(node, rid=None, desc=None, text=None, regex=None, cls=None,
          text_contains=None, pkg=None):
    resource_id = node.get("resource-id") or ""
    return (
        (pkg is None or (node.get("package") or "") == pkg)
        and (rid is None or rid_name(node) == rid or resource_id.endswith("/" + rid))
        and (desc is None or (node.get("content-desc") or "") == desc)
        and (text is None or (node.get("text") or "") == text)
        and (text_contains is None or text_contains in (node.get("text") or ""))
        and (regex is None or re.search(
            regex, (node.get("text") or "") + "\n" + (node.get("content-desc") or ""),
            re.I | re.S))
        and (cls is None or node.get("class", "").endswith(cls))
    )


def find(root, **want):
    if root is None:
        return None
    for node in root.iter("node"):
        bounds = parse_bounds(node.get("bounds"))
        if match(node, **want) and bounds and rect_area(bounds) > 0:
            return node
    return None


def wait_for(want, timeout=8.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        node = find(hierarchy(), **want)
        if node is not None:
            return node
        time.sleep(0.35)
    return None


def clickable_ancestor(root, node):
    parents = parent_map(root)
    current = node
    seen = 0
    while current.get("clickable") != "true" and seen < 30:
        current = parents.get(current)
        if current is None or current.tag != "node":
            return None
        seen += 1
    return current if current.get("clickable") == "true" else None


def tap_node(root, node):
    if root is None or node is None:
        return False
    target = clickable_ancestor(root, node) or node
    bounds = parse_bounds(target.get("bounds"))
    if not bounds:
        return False
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    adb("shell", "input", "tap", x, y)
    time.sleep(0.4)
    return True


def tap(want, timeout=8.0):
    root = hierarchy()
    node = find(root, **want) if root is not None else None
    if node is None and timeout > 0:
        node = wait_for(want, timeout)
        root = hierarchy()
    return tap_node(root, node)


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.45)


def type_text(text):
    escaped = str(text).replace("\\", "\\\\").replace(" ", "%s")
    for char in "()<>|&;\"'*?":
        escaped = escaped.replace(char, "\\" + char)
    adb("shell", "input", "text", escaped)
    time.sleep(0.3)


def scroll_node(node, up=False, horizontal=False, duration=420):
    bounds = parse_bounds(node.get("bounds")) if node is not None else None
    if not bounds:
        return False
    if horizontal:
        x1, x2 = bounds[0], bounds[2]
        y = (bounds[1] + bounds[3]) // 2
        pad = max(24, (x2 - x1) // 6)
        adb("shell", "input", "swipe", x1 + pad, y, x2 - pad, y, duration)
    else:
        x = (bounds[0] + bounds[2]) // 2
        y1, y2 = bounds[1], bounds[3]
        pad = max(24, (y2 - y1) // 6)
        adb("shell", "input", "swipe", x, y2 - pad, x, y1 + pad, duration)
    time.sleep(0.5)
    return True


def scroll_page(up=False):
    root = hierarchy()
    if root is None:
        return False
    containers = [n for n in root.iter("node") if n.get("scrollable") == "true"]
    if not containers:
        return False
    node = max(containers, key=lambda n: rect_area(parse_bounds(n.get("bounds")) or (0, 0, 0, 0)))
    return scroll_node(node, up=up)


def dismiss_sheet():
    root = hierarchy()
    if root is None or find(root, rid="swSheetRoot") is None:
        return False
    if not tap({"rid": "swSheetClose"}, timeout=2):
        back()
    for _ in range(3):
        time.sleep(0.35)
        root = hierarchy()
        if root is None or find(root, rid="swSheetRoot") is None:
            return True
    back()
    return False


def launch():
    adb("shell", "am", "force-stop", PKG, timeout=25)
    adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", SHELL, timeout=40)
    return wait_for({"rid": "swNavLibrary"}, timeout=40) is not None


def open_create_sheet():
    """The disc's first tap can be swallowed by its ring animation; retry once."""
    for _ in range(2):
        if tap({"rid": "swNavScan"}, timeout=8):
            if wait_for({"rid": "swCreateScan"}, timeout=10) is not None:
                return True
        time.sleep(1.0)
    return False


def recover(reason):
    """Never leave the walk lost: try Back, then a cold relaunch of the shell."""
    print("recover: %s" % reason, flush=True)
    for _ in range(2):
        root = hierarchy()
        if root is not None and find(root, rid="swNavLibrary") is not None:
            return True
        back()
    root = hierarchy()
    if root is not None and find(root, rid="swNavLibrary") is not None:
        return True
    launch()
    return True


# ----------------------------------------------------------------- capture step
def capture(area, slug, path, note=None):
    global N, LAST_SCREEN
    N += 1
    name = "%03d-%s" % (N, slugify(slug))
    png_path = OUT / (name + ".png")
    xml_path = OUT / (name + ".xml")
    hierarchy()  # dismiss a launcher ANR dialog before the evidence is taken
    data = dump_xml()
    if data:
        xml_path.write_bytes(data)
    else:
        xml_path.write_text("<!-- uiautomator dump unavailable -->\n", encoding="utf-8")
    try:
        png_path.write_bytes(adb("exec-out", "screencap", "-p", binary=True, timeout=30))
    except Exception as exc:  # noqa: BLE001
        print("screencap failed for %s: %s" % (name, exc), flush=True)
        png_path.write_bytes(b"")
    stats = png_stats(png_path) if png_path.stat().st_size else {"mean": -1, "unique": 0}
    secure = (0 <= stats.get("mean", 255) <= 8 and stats.get("unique", 99) <= 12)
    if secure and has_secure_flag():
        note = ((note + "; ") if note else "") + "FLAG_SECURE confirmed"
    root = None
    if data:
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            pass
    flags = analyze(root, DENSITY, WIDTH, HEIGHT)
    score = sum(len(v) if isinstance(v, list) else 1 for v in flags.values() if v)
    record = {
        "n": N, "slug": slug, "area": area,
        "path": " > ".join(path), "png": png_path.name, "xml": xml_path.name,
        "window": current_window(), "secure": secure, "screenshot": stats,
        "flags": flags, "score": score, "note": note or "",
        "step": CURRENT_STEP, "captured_at": time.time(),
    }
    SCREENS.append(record)
    LAST_SCREEN = record
    print("screen %s [%s] secure=%s score=%d flags=%s" % (
        name, area, secure, score,
        {k: len(v) if isinstance(v, list) else v.get("words", 0)
         for k, v in flags.items() if v}), flush=True)
    return record


def step(area, slug, path, action):
    """Run one navigation action; a failure is evidence, never a full-stop."""
    global CURRENT_STEP
    CURRENT_STEP = slug
    if (time.monotonic() - STARTED) / 60 > MAX_MINUTES:
        print("atlas deadline reached, skipping %s" % slug, flush=True)
        return None
    try:
        result = action()
        print("step ok: %s %s" % (slug, result or ""), flush=True)
        return result
    except Exception as exc:  # noqa: BLE001 - one bad screen must not stop the walk
        detail = "%s: %s" % (type(exc).__name__, exc)
        ERRORS.append({"slug": slug, "error": detail, "trace": traceback.format_exc()[-1200:]})
        print("step FAILED: %s %s" % (slug, detail), flush=True)
        try:
            capture(area, slug + "-error", path + ["ERROR"], note=detail)
        except Exception:  # noqa: BLE001
            traceback.print_exc()
        recover(detail)
        return None


# --------------------------------------------------------------------- fixtures
def seed():
    fixture = OUT / FIXTURE_PDF
    fixture.write_bytes(build([
        [FIXTURE + " / fictional page %d" % i,
         "Self-authored ScanWhirl atlas fixture, no real content"]
        for i in range(1, 4)
    ]))
    adb("shell", "mkdir", "-p", "/sdcard/Download")
    adb("shell", "mkdir", "-p", "/sdcard/Pictures")
    adb("push", fixture, "/sdcard/Download/" + FIXTURE_PDF, timeout=40)
    adb_ok("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
           "-d", "file:///sdcard/Download/" + FIXTURE_PDF)
    for index, accent in enumerate(((40, 120, 200), (200, 90, 60)), 1):
        photo = OUT / ("atlas-photo-%d.png" % index)
        write_fictional_png(photo, 640, 480, accent)
        adb("push", photo, "/sdcard/Pictures/atlas-photo-%d.png" % index, timeout=40)
        adb_ok("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
               "-d", "file:///sdcard/Pictures/atlas-photo-%d.png" % index)
    # API 24's DocumentsUI lists only media-scanned files; the broadcasts above are
    # asynchronous, so give it time before the picker opens.
    time.sleep(3)
    ok = adb_ok("shell", "test", "-s", "/sdcard/Download/" + FIXTURE_PDF)
    print("fixtures seeded: pdf=%s" % ok, flush=True)
    return ok


def import_fixture():
    """Import the generated PDF through DocumentsUI, as a person would."""
    launch()
    if not open_create_sheet():
        return False
    capture("create", "create-sheet", ["Library", "Create"], "Create sheet before import")
    if not tap({"rid": "swCreateImport"}, timeout=6):
        return False
    wait_for({"desc": "Show roots"}, timeout=15)
    capture("import", "picker-roots", ["Library", "Create", "Import PDF"])
    # The seeded PDF appears in DocumentsUI "Recent files". Take it directly when it is
    # offered; navigating roots first used to leave the picker and lose the fixture.
    if tap({"regex": r"atlas-qa-fixture"}, timeout=5):
        opened = (wait_for({"rid": "ux130_l4_select_pages"}, timeout=70) is not None
                  or wait_for({"rid": "btnMore"}, timeout=8) is not None)
        if opened:
            capture("viewer", "imported-document", ["Library", "Create", "Import PDF", "Viewer"],
                    "Fictional PDF opened straight from Recent files")
            dismiss_sheet()
            launch()
            return True
    tap({"desc": "Show roots"}, timeout=4)
    # API 24 hides the filesystem root until this menu option is enabled.
    if tap({"desc": "More options"}, timeout=1):
        if not tap({"regex": r"Show (internal storage|SD card)"}, timeout=1):
            back()
        tap({"desc": "Show roots"}, timeout=2)
    model = adb("shell", "getprop", "ro.product.model")
    root_node = None
    for label in (model, "Internal storage", "Downloads", "This device"):
        root_node = wait_for({"text": label}, timeout=4) if label else None
        if root_node is not None:
            tap({"text": label}, timeout=4)
            break
    fixture_node = wait_for({"regex": r"atlas-qa-fixture"}, timeout=8)
    selected_by_key = False
    if fixture_node is None and API == "24":
        # API 24 DocumentsUI does not always expose a clickable row to uiautomator,
        # but TAB focus + ENTER still selects the file. Stop when the focused node IS
        # the fixture, never blind-press ENTER on an arbitrary row.
        for _ in range(10):
            adb("shell", "input", "keyevent", "KEYCODE_TAB")
            root = hierarchy()
            if root is None:
                continue
            for node in root.iter("node"):
                if node.get("focused") == "true" and re.search(
                        r"atlas-qa-fixture",
                        (node.get("text") or "") + "\n" + (node.get("content-desc") or ""), re.I):
                    fixture_node = node
                    break
            if fixture_node is not None:
                break
        if fixture_node is not None:
            adb("shell", "input", "keyevent", "KEYCODE_ENTER")
            selected_by_key = True
            time.sleep(0.6)
    if fixture_node is None:
        capture("import", "picker-no-fixture", ["Library", "Create", "Import PDF"],
                "Generated PDF not listed by DocumentsUI")
        return False
    capture("import", "picker-fixture", ["Library", "Create", "Import PDF", FIXTURE_PDF])
    if not selected_by_key and not tap({"regex": r"atlas-qa-fixture"}, timeout=6):
        tap_node(hierarchy(), fixture_node)
    opened = (wait_for({"rid": "ux130_l4_select_pages"}, timeout=70) is not None
              or wait_for({"rid": "btnMore"}, timeout=8) is not None)
    if not opened:
        capture("import", "import-stuck", ["Library", "Create", "Import PDF", "open"],
                "Viewer did not appear after import")
        return False
    capture("viewer", "imported-document", ["Library", "Create", "Import PDF", "Viewer"],
            "Fictional PDF opened after import")
    dismiss_sheet()
    launch()
    return True


# ------------------------------------------------------------------------ walks
def tap_reachable(want, swipes=8):
    """Scroll actual visible containers, never guess coordinates of an offscreen control."""
    for _ in range(swipes + 1):
        if tap(want, timeout=1):
            return True
        if not scroll_page():
            return False
    return False


def open_tool(title):
    launch()
    if not tap({"rid": "swNavTools"}, timeout=8):
        return False
    tap({"rid": "swToolsSearch"}, timeout=8)
    type_text(title)
    back()
    # A tool row is a custom TextView, not a Button. Match the app's own node only:
    # the IME suggestion strip repeats the typed word and swallowed the old tap.
    # The search EditText's own text is the query, so match the row's content-desc.
    return tap_reachable({"desc": title, "pkg": PKG}, swipes=2)


def capture_result(kind):
    if wait_for({"rid": "toolResultName"}, timeout=70) is None:
        capture("result", kind + "-not-reached", ["Tools", kind],
                "Result was not reached; this is a coverage failure, not result evidence")
        return False
    # Wait for the real output image decoder, not an artificial product delay.
    time.sleep(1)
    capture("result", kind + "-preview", ["Tools", kind, "Result", "Preview"])
    scroll_page()
    capture("result", kind + "-where", ["Tools", kind, "Result", "Facts and location"])
    return True


def walk_results():
    """W5b: reach both kinds through their real picker and processing UI."""
    def compress():
        if not open_tool("Compress"):
            return "Compress tile unavailable"
        if wait_for({"rid": "toolSetupDocName"}, timeout=12) is None:
            capture("result", "compress-no-input", ["Tools", "Compress"],
                    "No imported document; result coverage unavailable")
            return "no input"
        if not tap_reachable({"rid": "toolSetupRun"}):
            return "Compress action unavailable"
        if capture_result("compress"):
            tap({"text": "Done"}, timeout=3)
            wait_for({"rid": "swHomeSearch"}, timeout=12)
            capture("library", "result-new-copy", ["Compress", "Result", "Done", "Library"])
        return "compression attempted"

    step("result", "compress", ["Tools", "Compress"], compress)

    def form():
        if not open_tool("Form photo"):
            return "Form photo tile unavailable"
        if wait_for({"rid": "formReadyPick"}, timeout=12) is None:
            capture("result", "form-photo-no-pick", ["Tools", "Form photo"],
                    "Setup did not expose Choose photo")
            return "Choose photo unavailable"
        if not tap_reachable({"rid": "formReadyPick"}):
            return "Choose photo unavailable"
        # The Android Photo Picker labels tiles "Photo taken on ...", not by filename;
        # older SAF pickers show the file name. Accept either before browsing roots.
        photo_tile = {"regex": r"Photo taken on|atlas-photo-1(?:\.png)?"}
        picked_by_key = False
        if find(hierarchy(), **photo_tile) is None and API == "24":
            # API 24 SAF rows are not always clickable to uiautomator; TAB to focus the
            # seeded photo and press ENTER, never an unexamined row.
            photo_re = re.compile(r"Photo taken on|atlas-photo-1", re.I)
            for _ in range(10):
                adb("shell", "input", "keyevent", "KEYCODE_TAB")
                root = hierarchy()
                if root is None:
                    continue
                focused = [n for n in root.iter("node")
                           if n.get("focused") == "true" and photo_re.search(
                               (n.get("text") or "") + "\n" + (n.get("content-desc") or ""))]
                if focused:
                    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
                    picked_by_key = True
                    time.sleep(0.6)
                    break
        if not picked_by_key and not tap(photo_tile, timeout=6):
            tap({"text": "Files"}, timeout=1)
            if tap({"desc": "Show roots"}, timeout=5):
                if not tap({"text": "Images"}, timeout=3):
                    tap({"text": "Pictures"}, timeout=2)
            tap({"text": "Pictures"}, timeout=2)
            if not tap(photo_tile, timeout=8):
                capture("result", "form-photo-picker-no-input", ["Tools", "Form photo", "Picker"],
                        "Seeded fictional photo not listed")
                back()
                return "photo unavailable"
        if not tap_reachable({"rid": "formReadyApply"}):
            capture("result", "form-photo-no-apply", ["Tools", "Form photo", "Setup"],
                    "Processing action was not reachable")
            return "Make photo unavailable"
        if capture_result("form-photo"):
            # Capture Adjust and the unchanged workspace as well as the unsaved receipt.
            if tap_reachable({"text": "Adjust"}, swipes=3):
                capture("result", "form-photo-adjust", ["Form photo", "Result", "Adjust"],
                        "Original workspace retained; no new selection")
        return "form photo attempted"

    step("result", "form-photo", ["Tools", "Form photo"], form)
    recover("after shared result walk")


def walk_library():
    launch()

    def search():
        tap({"rid": "swHomeSearch"}, timeout=8)
        type_text("atlas")
        time.sleep(0.6)
        capture("library", "search", ["Library", "Search: atlas"])
        tap({"rid": "swHomeClear"}, timeout=3)
        back()
        return "searched"

    step("library", "search", ["Library"], search)

    def sort_sheet():
        if tap({"rid": "swSort"}, timeout=6):
            wait_for({"rid": "swSheetRoot"}, timeout=6)
            capture("library", "sort-sheet", ["Library", "Sort sheet"])
            dismiss_sheet()
            return "sort sheet captured and dismissed"
        return "no sort control on screen"

    step("library", "sort-sheet", ["Library"], sort_sheet)

    def folder_sheet():
        if tap({"rid": "swAllFolders"}, timeout=5):
            wait_for({"rid": "swSheetRoot"}, timeout=6)
            capture("library", "folders-sheet", ["Library", "Folders sheet"])
            dismiss_sheet()
            return "folders sheet captured and dismissed"
        return "no folders control on screen"

    step("library", "folders-sheet", ["Library"], folder_sheet)

    def doc_actions():
        if not tap({"rid": "swDocMore"}, timeout=6):
            return "no document action control"
        wait_for({"rid": "swSheetRoot"}, timeout=6)
        capture("library", "document-actions-sheet", ["Library", "Document actions"])
        dismiss_sheet()
        return "document actions sheet captured and dismissed"

    step("library", "document-actions-sheet", ["Library"], doc_actions)

    def selection():
        root = hierarchy()
        node = find(root, regex=FIXTURE) if root else None
        if node is None:
            node = find(root, cls="TextView", text_contains="atlas") if root else None
        if node is None:
            return "no imported document to select"
        # Selection is entered by a long press (bounded swipe with no movement).
        bounds = parse_bounds(node.get("bounds"))
        x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        adb("shell", "input", "swipe", x, y, x, y, 900)
        time.sleep(0.6)
        if wait_for({"rid": "swSelectionBar"}, timeout=8) is None:
            return "selection bar did not appear"
        capture("library", "selection", ["Library", "Select document"])
        # V018/D2: the shipped chip is cbSelMore; the old swSelMore id tapped nothing.
        tap({"rid": "cbSelMore"}, timeout=4)
        wait_for({"rid": "swSheetRoot"}, timeout=5)
        capture("library", "selection-overflow-sheet", ["Library", "Selection", "More"])
        dismiss_sheet()
        tap({"rid": "swSelDone"}, timeout=4)
        return "selection and overflow sheet captured"

    step("library", "selection", ["Library"], selection)


def walk_tabs():
    for name, rid, anchor in (
        ("tools", "swNavTools", "swToolsSearch"),
        ("vault", "swNavVault", "swVaultStatus"),
        ("me", "swNavMe", "swMeList"),
        ("library", "swNavLibrary", "swHomeSearch"),
    ):
        def action(rid=rid, anchor=anchor, name=name):
            if not tap({"rid": rid}, timeout=8):
                return "tab %s missing" % name
            wait_for({"rid": anchor}, timeout=12)
            capture("shell", "tab-" + name, ["Library", "Tab: " + name.title()])
            return name
        step("shell", "tab-" + name, ["Library"], action)


def walk_tools():
    for tool_id, title in TOOLS:
        def action(tool_id=tool_id, title=title):
            launch()
            if not tap({"rid": "swNavTools"}, timeout=10):
                return "Tools tab missing"
            wait_for({"rid": "swToolsSearch"}, timeout=12)
            tap({"rid": "swToolsSearch"}, timeout=4)
            type_text(title)
            back()  # keyboard only: the results stay, as in flow_driver F2
            time.sleep(0.5)
            root = hierarchy()
            node = None
            if root:
                for candidate in root.iter("node"):
                    if match(candidate, regex=re.escape(title)) and candidate.get("clickable") == "true":
                        node = candidate
                        break
            if node is None:
                node = wait_for({"regex": re.escape(title), "cls": "Button"}, timeout=5)
            if node is None:
                COVERAGE["tools_failed"].append(tool_id + " (no search result)")
                capture("tools", "tool-" + tool_id + "-missing",
                        ["Library", "Tools", "Search: " + title],
                        "Search result tile not found")
                return "no result for %s" % title
            root = hierarchy()  # fresh tree for the clickable ancestor lookup
            tap_node(root, node)
            time.sleep(1.4)
            capture("tools", "tool-" + tool_id, ["Library", "Tools", "Search: " + title, title],
                    "Tile opened; first screen after launch")
            COVERAGE["tools_visited"].append(tool_id)
            dismiss_sheet()
            back()
            return title
        step("tools", "tool-" + tool_id, ["Library", "Tools"], action)


def open_viewer():
    """Reach the fictional document from a known Library state, every time."""
    launch()
    root = hierarchy()
    node = find(root, regex=FIXTURE) if root else None
    if node is None and root:
        node = find(root, text_contains="atlas")
    if node is None:
        return False
    root = hierarchy()
    tap_node(root, find(root, regex=FIXTURE) or find(root, text_contains="atlas"))
    return (wait_for({"rid": "ux130_l4_select_pages"}, timeout=25) is not None
            or wait_for({"rid": "btnMore"}, timeout=8) is not None)


def walk_viewer():
    def open():
        if not open_viewer():
            return "no imported document available"
        capture("viewer", "main", ["Library", FIXTURE, "Viewer"])
        return "viewer opened"

    step("viewer", "main", ["Library"], open)

    def dock_action(rid, slug):
        if not open_viewer():
            return "viewer unavailable for %s" % slug
        if not tap({"rid": rid}, timeout=7):
            return "%s not on screen" % rid
        time.sleep(1.0)
        capture("viewer", slug, ["Library", FIXTURE, "Viewer", slug])
        dismiss_sheet()
        back()
        return slug

    for rid, slug in (
        ("btnMore", "actions-sheet"),
        ("btnEdit", "editor"),
        ("btnText", "ocr"),
        ("btnShare", "share-sheet"),
    ):
        step("viewer", slug, ["Library", FIXTURE, "Viewer"],
             lambda rid=rid, slug=slug: dock_action(rid, slug))

    def editor_row():
        if not open_viewer():
            return "viewer unavailable"
        if not tap({"rid": "btnEdit"}, timeout=7):
            return "editor unavailable"
        if wait_for({"rid": "pageDock"}, timeout=12) is None:
            return "editor tools row missing"
        capture("viewer", "editor-tools", ["Library", FIXTURE, "Viewer", "Edit & crop"])
        presets = wait_for({"rid": "cropPresets"}, timeout=5)
        if presets is not None:
            scroll_node(presets, horizontal=True)
            capture("viewer", "editor-tools-scrolled",
                    ["Library", FIXTURE, "Viewer", "Edit & crop", "Presets scrolled"])
        back()
        return "editor tools row captured"

    step("viewer", "editor-tools", ["Library", FIXTURE, "Viewer"], editor_row)

    def selection():
        if not open_viewer():
            return "viewer unavailable"
        if not tap({"rid": "ux130_l4_select_pages"}, timeout=7):
            return "Select pages control missing"
        time.sleep(0.8)
        capture("viewer", "select-pages", ["Library", FIXTURE, "Viewer", "Select pages"])
        tap({"rid": "btnSelAll"}, timeout=4)
        time.sleep(0.4)
        capture("viewer", "select-pages-actions",
                ["Library", FIXTURE, "Viewer", "Select pages", "Actions"])
        tap({"rid": "btnSelClose"}, timeout=4)
        return "selection captured"

    step("viewer", "select-pages", ["Library", FIXTURE, "Viewer"], selection)
    recover("after viewer walk")


def walk_vault():
    def action():
        launch()
        tap({"rid": "swNavVault"}, timeout=8)
        wait_for({"rid": "swVaultStatus"}, timeout=12)
        if not tap({"rid": "swVaultAction"}, timeout=7):
            return "vault entry button missing"
        time.sleep(1.0)
        capture("vault", "entry", ["Library", "Vault tab", "Open Vault"])
        if tap({"rid": "vaultSetupButton"}, timeout=6):
            time.sleep(0.8)
            capture("vault", "passcode-setup",
                    ["Library", "Vault tab", "Open Vault", "Set up passcode"],
                    "Stopped at passcode setup; no secret entered")
            dismiss_sheet()
        back()
        return "vault entry captured, stopped before any secret"
    step("vault", "entry", ["Library", "Vault tab"], action)


def walk_me():
    def pro():
        launch()
        tap({"rid": "swNavMe"}, timeout=8)
        wait_for({"rid": "swMeList"}, timeout=12)
        if tap({"rid": "swMePro"}, timeout=6):
            wait_for({"rid": "swProScreen"}, timeout=10)
            capture("me", "pro", ["Library", "Me", "Pro"])
            tap({"rid": "swProClose"}, timeout=4)
        return "pro screen"

    step("me", "pro", ["Library", "Me"], pro)

    def backup():
        launch()
        tap({"rid": "swNavMe"}, timeout=8)
        if not tap({"rid": "swMeBackup"}, timeout=7):
            return "Backup row missing"
        time.sleep(1.0)
        capture("backup", "screen", ["Library", "Me", "Backup & restore"])
        if tap({"rid": "backupNow"}, timeout=6):
            wait_for({"rid": "swSheetRoot"}, timeout=6)
            capture("backup", "create-choice-sheet",
                    ["Library", "Me", "Backup & restore", "Create backup"],
                    "Choice sheet only; no passphrase entered")
            dismiss_sheet()
        back()
        return "backup screen captured"

    step("backup", "screen", ["Library", "Me"], backup)

    def trash():
        launch()
        tap({"rid": "swNavMe"}, timeout=8)
        if tap({"rid": "swMeTrash"}, timeout=6):
            time.sleep(0.9)
            capture("me", "trash", ["Library", "Me", "Trash"])
            back()
        return "trash"

    step("me", "trash", ["Library", "Me"], trash)

    def about():
        launch()
        tap({"rid": "swNavMe"}, timeout=8)
        if tap({"rid": "swMeAbout"}, timeout=6):
            wait_for({"rid": "swSheetRoot"}, timeout=6)
            capture("me", "about-sheet", ["Library", "Me", "About"])
            dismiss_sheet()
        return "about sheet"

    step("me", "about-sheet", ["Library", "Me"], about)

    settings_rows = (
        ("rowTheme", "theme-sheet"), ("rowFont", "font-sheet"),
        ("rowTextSize", "text-size-sheet"), ("rowLanguage", "language-sheet"),
        ("rowViewer", "viewer-sheet"), ("rowPrivacy", "privacy-sheet"),
        ("rowAbout", "about-row-sheet"),
    )
    for rid, slug in settings_rows:
        def action(rid=rid, slug=slug):
            launch()
            tap({"rid": "swNavMe"}, timeout=8)
            if not tap({"rid": "swMeSettings"}, timeout=7):
                return "Settings row missing"
            wait_for({"rid": "rowLanguage"}, timeout=12)
            capture("settings", "screen", ["Library", "Me", "Settings"])
            if not tap({"rid": rid}, timeout=6):
                return "%s missing" % rid
            time.sleep(0.9)
            capture("settings", slug, ["Library", "Me", "Settings", slug])
            dismiss_sheet()
            back()
            return slug
        step("settings", slug, ["Library", "Me", "Settings"], action)


def walk_create():
    def action():
        launch()
        if not open_create_sheet():
            return "Create disc missing"
        capture("create", "sheet", ["Library", "Create"])
        dismiss_sheet()
        return "create sheet"

    step("create", "sheet", ["Library"], action)

    for rid, slug in (("swCreatePhotos", "photo-picker"),
                      ("swCreateImport", "import-picker")):
        def picker(rid=rid, slug=slug):
            launch()
            if not open_create_sheet():
                return "Create sheet missing"
            if not tap({"rid": rid}, timeout=6):
                return "%s missing" % rid
            time.sleep(1.4)
            capture("create", slug, ["Library", "Create", slug],
                    "System picker; fictional fixtures were seeded")
            back()
            return slug
        step("create", slug, ["Library", "Create"], picker)


# ---------------------------------------------------------------- report output
def collect_crashes():
    """Save the crash buffer and parse FATAL EXCEPTION blocks into findings."""
    raw = ""
    for args in (("logcat", "-d", "-b", "crash", "-v", "epoch"),
                 ("logcat", "-d", "-v", "epoch")):
        try:
            raw = adb(*args, timeout=35)
            if raw and "FATAL EXCEPTION" in raw:
                break
        except Exception:  # noqa: BLE001 - a missing buffer is not an app finding
            raw = ""
    (OUT / "atlas-crash-log.txt").write_text(raw, encoding="utf-8", errors="replace")
    seen = set()
    for finding in parse_crash_log(raw, SCREENS):
        key = finding["top"].splitlines()[0][:180]
        if key not in seen:
            seen.add(key)
            CRASHES.append(finding)
    print("crash findings: %d" % len(CRASHES), flush=True)
    return len(CRASHES)


def write_outputs():
    by_flag = {
        key: sum(len(s["flags"][key]) if isinstance(s["flags"][key], list)
                 else (1 if s["flags"][key] else 0) for s in SCREENS)
        for key in ("small_targets", "clipped_text", "overlaps",
                    "multiple_primaries", "verbose_instructions")
    }
    worst = [{"slug": s["slug"], "area": s["area"], "score": s["score"],
              "flags": {k: (len(v) if isinstance(v, list) else v.get("words", 0))
                        for k, v in s["flags"].items() if v}}
             for s in sorted(SCREENS, key=lambda s: -s["score"])[:10]]
    report = {
        "leg": LEG, "api": API, "sha": os.environ.get("GITHUB_SHA", "unknown"),
        "device": (adb("shell", "getprop", "ro.product.model") if SERIAL else "unknown"),
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "density": round(DENSITY, 3), "screen_size": [WIDTH, HEIGHT],
        "elapsed_minutes": round((time.monotonic() - STARTED) / 60, 1),
        "screens": SCREENS, "coverage": COVERAGE, "navigation_errors": ERRORS,
        "anrs": [{k: v for k, v in item.items() if k != "_t"} for item in ANRS],
        "crashes": CRASHES,
        "totals": {"screens": len(SCREENS), "secure": sum(1 for s in SCREENS if s["secure"]),
                   "system_dialogs_dismissed": DIALOGS,
                   "anrs": len(ANRS), "crashes": len(CRASHES),
                   "by_flag": by_flag, "worst": worst},
    }
    (OUT / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False),
                                     encoding="utf-8")

    doc = ["<!doctype html><html><head><meta charset='utf-8'>",
           "<meta name='viewport' content='width=device-width,initial-scale=1'>",
           "<title>ScanWhirl screen atlas - %s</title>" % html.escape(LEG),
           "<style>body{font:14px system-ui,sans-serif;background:#0b1116;color:#e7f2f7;"
           "margin:20px}h1,h2{color:#5ff}a{color:#5ff}section{margin:24px 0}"
           ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:14px}"
           ".card{background:#14202a;border:1px solid #27404d;border-radius:10px;padding:10px}"
           ".card img{width:100%;border-radius:6px;background:#000}.card b{display:block;margin:6px 0}"
           ".path{color:#9fb3bf;font-size:12px;word-break:break-word}.flag{color:#ffb86b;font-size:12px}"
           ".secure{color:#8fd3ff}table{border-collapse:collapse}td,th{padding:4px 10px;border:1px solid #27404d}"
           "</style></head><body>",
           "<h1>ScanWhirl screen atlas - %s (API %s)</h1>" % (html.escape(LEG), html.escape(str(API))),
           "<p>%d screens, %d secure, %d system dialogs dismissed, %.1f min. "
           "Density %.2f, %dx%d. Commit %s.</p>" % (
               len(SCREENS), report["totals"]["secure"], DIALOGS,
               report["elapsed_minutes"], DENSITY, WIDTH, HEIGHT,
               html.escape(str(report["sha"])[:12])),
           "<h2>Flags by type</h2><table><tr><th>check</th><th>hits</th></tr>"]
    for key, count in by_flag.items():
        doc.append("<tr><td>%s</td><td>%d</td></tr>" % (html.escape(key), count))
    doc.append("</table><h2>Worst 10 screens</h2><ol>")
    for item in worst:
        doc.append("<li>%s (%s) - score %d: %s</li>" % (
            html.escape(item["slug"]), html.escape(item["area"]), item["score"],
            html.escape(json.dumps(item["flags"]))))
    doc.append("</ol>")
    doc.append("<h2>ANRs (%d) - recovered, reported separately from layout flags</h2><ul>" % len(ANRS))
    for finding in ANRS:
        doc.append("<li>%s: <b>%s</b> in %s screen / %s step - <a href='%s'>evidence</a></li>" % (
            html.escape(finding["app"]), html.escape(finding["title"]),
            html.escape(finding["screen"]), html.escape(finding["step"]),
            html.escape(finding["evidence"])))
    doc.append("</ul><h2>App crashes (%d)</h2><ul>" % len(CRASHES))
    for finding in CRASHES:
        doc.append("<li>%s near %s screen%s</li>" % (
            html.escape(finding["app"]), html.escape(finding["screen"]),
            ": %s" % html.escape(finding["top"].splitlines()[0][:160])
            if finding["top"] else ""))
    doc.append("</ul>")
    areas = {}
    for screen in SCREENS:
        areas.setdefault(screen["area"], []).append(screen)
    for area in sorted(areas):
        doc.append("<section><h2>%s</h2><div class='grid'>" % html.escape(area))
        for screen in areas[area]:
            doc.append("<div class='card'><a href='%s'><img src='%s' loading='lazy' alt='%s'></a>"
                       "<b>%03d %s</b><div class='path'>%s</div>" % (
                           html.escape(screen["xml"]), html.escape(screen["png"]),
                           html.escape(screen["slug"]), screen["n"],
                           html.escape(screen["slug"]), html.escape(screen["path"])))
            if screen["secure"]:
                doc.append("<div class='secure'>SECURE (black screenshot expected)</div>")
            for key, value in screen["flags"].items():
                if not value:
                    continue
                count = len(value) if isinstance(value, list) else value.get("words", 0)
                doc.append("<div class='flag'>%s (%s): %s</div>" % (
                    html.escape(key), count,
                    html.escape(json.dumps(value[:3] if isinstance(value, list) else value))))
            if screen["note"]:
                doc.append("<div class='path'>%s</div>" % html.escape(screen["note"]))
            doc.append("</div>")
        doc.append("</div></section>")
    doc.append("</body></html>")
    (OUT / "index.html").write_text("\n".join(doc), encoding="utf-8")

    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write("\n### Atlas %s\n\n- screens: %d\n- secure: %d\n- flags: %s\n"
                         "- tools visited: %d/%d\n- navigation errors: %d\n"
                         "- ANRs (recovered): %d\n- crashes: %d\n" % (
                             LEG, len(SCREENS), report["totals"]["secure"], by_flag,
                             len(COVERAGE["tools_visited"]), COVERAGE["tools_total"],
                             len(ERRORS), len(ANRS), len(CRASHES)))
    print(json.dumps({"screens": len(SCREENS), "by_flag": by_flag,
                      "tools": len(COVERAGE["tools_visited"]),
                      "errors": len(ERRORS)}, sort_keys=True), flush=True)


# ------------------------------------------------------------------------- main
def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise AssertionError("CI-only: atlas never runs on a local/physical device")
    if not SERIAL:
        raise AssertionError("Emulator serial required (ANDROID_SERIAL=emulator-N)")
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise AssertionError("Not an emulator")
    devices = adb("devices")
    if len(re.findall(r"^emulator-\d+\s+device\r?$", devices, re.M)) != 1:
        raise AssertionError("Atlas requires exactly one booted emulator")
    OUT.mkdir(parents=True, exist_ok=True)
    adb_ok("logcat", "-c")
    device_size_and_density()
    print("atlas leg=%s api=%s device=%s %dx%d density=%.2f" % (
        LEG, API, adb("shell", "getprop", "ro.product.model"),
        WIDTH, HEIGHT, DENSITY), flush=True)

    try:
        step("fixtures", "seed", [], lambda: seed())
        step("import", "fixture", ["Library", "Create", "Import PDF"], import_fixture)
        walk_library()
        walk_tabs()
        walk_results()
        walk_tools()
        walk_viewer()
        walk_vault()
        walk_me()
        walk_create()
        capture("shell", "final", ["Library"], "Final state after the full walk")
    finally:
        try:
            logs = adb("logcat", "-d", "-v", "brief", timeout=30)
            (OUT / "atlas-logcat.txt").write_text(logs, encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001 - the report must survive a logcat failure
            traceback.print_exc()
        try:
            collect_crashes()
        except Exception:  # noqa: BLE001
            traceback.print_exc()
        write_outputs()
    return 0 if len(SCREENS) >= 10 else 1


if __name__ == "__main__":
    raise SystemExit(main())
