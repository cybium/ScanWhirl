#!/usr/bin/env python3
"""uiautomator helpers for the real-Android smoke test (tools/e2e/smoke.sh).

The smoke test must never click a fixed coordinate: every tap is derived from a
fresh `uiautomator dump` of the screen, matched by the view id the app sets
(R.id.swNavLibrary, R.id.swToolsSearch, ...). Those ids are locale-independent,
so the same script walks the UI in English and in Hindi.

Commands (all talk to `adb` on PATH, i.e. the throwaway emulator only):

  dump  --out FILE                 dump the hierarchy to FILE (and /sdcard/e2e_ui.xml)
  find  --id swNavTools [...]      print "X Y" of the node centre (exit 3 if absent)
  wait  --id swNavTools           poll until the node exists, print "X Y"
  has   --id X | --desc D | --text T
                                   exit 0 when present, 1 when not
  image-stats --file F            decode a screencap PNG and print mean/unique colours

Filters combine: --id swNavLibrary --desc Library requires both on one node.
--text matches a node's text attribute exactly; --text-contains is a substring
match and is only used for human-readable assertions.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zlib

DUMP_PATH = "/sdcard/e2e_ui.xml"

# uiautomator sometimes refuses the first dump right after a launch ("could not
# get idle state"); a short retry is cheaper than a flaky failure.
DUMP_ATTEMPTS = 5


def adb(*args, binary=False):
    result = subprocess.run(
        ["adb"] + list(args),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if binary:
        return result.returncode, result.stdout
    return result.returncode, result.stdout.decode("utf-8", "replace")


def dump_xml():
    """Return the current hierarchy XML as bytes, or None."""
    last = None
    for _ in range(DUMP_ATTEMPTS):
        code, out = adb("shell", "uiautomator", "dump", DUMP_PATH)
        if code == 0 and "dumped to" in out:
            code, data = adb("exec-out", "cat", DUMP_PATH, binary=True)
            if code == 0 and data.strip().startswith(b"<?xml"):
                return data
            last = "cat failed"
        else:
            last = out.strip()
        time.sleep(0.5)
    sys.stderr.write("uitool: uiautomator dump failed (%s)\n" % (last or "no output"))
    return None


def _matches(node, want):
    rid = node.get("resource-id") or ""
    if want["id"]:
        # "com.scanwhirl.app.test:id/swNavTools" and a bare "swNavTools" both match.
        if not (rid == want["id"] or rid.endswith("/" + want["id"]) or rid.endswith(":id/" + want["id"])):
            return False
    if want["desc"] is not None:
        if (node.get("content-desc") or "") != want["desc"]:
            return False
    if want["text"] is not None:
        if (node.get("text") or "") != want["text"]:
            return False
    if want["text_contains"] is not None:
        if want["text_contains"] not in (node.get("text") or ""):
            return False
    return True


def _centre(bounds):
    match = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds or "")
    if not match:
        return None
    x1, y1, x2, y2 = (int(v) for v in match.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def find_node(data, want):
    root = ET.fromstring(data)
    for node in root.iter("node"):
        if _matches(node, want):
            centre = _centre(node.get("bounds"))
            if centre:
                return centre, node
    return None, None


def want_from_args(args):
    return {
        "id": args.id,
        "desc": args.desc,
        "text": args.text,
        "text_contains": args.text_contains,
    }


def cmd_dump(args):
    data = dump_xml()
    if data is None:
        return 1
    with open(args.out, "wb") as handle:
        handle.write(data)
    print("dumped %d bytes to %s" % (len(data), args.out))
    return 0


def cmd_find(args):
    data = None
    if args.file:
        with open(args.file, "rb") as handle:
            data = handle.read()
    else:
        data = dump_xml()
    if data is None:
        return 1
    centre, node = find_node(data, want_from_args(args))
    if not centre:
        sys.stderr.write("uitool: not found (id=%s desc=%s text=%s)\n" % (args.id, args.desc, args.text))
        return 3
    if args.verbose and node is not None:
        print("%d %d bounds=%s class=%s" % (centre[0], centre[1], node.get("bounds"), node.get("class")))
    else:
        print("%d %d" % centre)
    return 0


def cmd_wait(args):
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        data = dump_xml()
        if data is not None:
            centre, _ = find_node(data, want_from_args(args))
            if centre:
                print("%d %d" % centre)
                return 0
        time.sleep(1.0)
    sys.stderr.write(
        "uitool: timed out after %ss waiting for id=%s desc=%s text=%s\n"
        % (args.timeout, args.id, args.desc, args.text)
    )
    return 3


def cmd_has(args):
    data = dump_xml()
    if data is None:
        return 2
    centre, _ = find_node(data, want_from_args(args))
    return 0 if centre else 1


def _png_pixels(path):
    """Decode a non-interlaced 8-bit RGB/RGBA PNG into (width, height, rows)."""
    with open(path, "rb") as handle:
        raw = handle.read()
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    width = height = None
    bit_depth = colour = None
    interlace = 0
    idat = bytearray()
    while pos < len(raw):
        length = int.from_bytes(raw[pos:pos + 4], "big")
        kind = raw[pos + 4:pos + 8]
        body = raw[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width = int.from_bytes(body[0:4], "big")
            height = int.from_bytes(body[4:8], "big")
            bit_depth = body[8]
            colour = body[9]
            interlace = body[12]
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if bit_depth != 8 or colour not in (2, 6) or interlace != 0:
        raise ValueError("unsupported PNG (depth=%s colour=%s interlace=%s)" % (bit_depth, colour, interlace))
    channels = 3 if colour == 2 else 4
    stride = width * channels
    data = zlib.decompress(bytes(idat))
    rows = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = data[offset]
        offset += 1
        line = bytearray(data[offset:offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = previous[i]
                c = previous[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        elif filter_type != 0:
            raise ValueError("bad PNG filter %s" % filter_type)
        rows.append(line)
        previous = line
    return width, height, rows, channels


def cmd_image_stats(args):
    """Cheap sanity check that a screenshot is a real screen, not a blank frame."""
    width, height, rows, channels = _png_pixels(args.file)
    total = 0
    count = 0
    colours = set()
    step_x = max(1, width // 60)
    step_y = max(1, height // 60)
    for y in range(0, height, step_y):
        row = rows[y]
        for x in range(0, width, step_x):
            base = x * channels
            r, g, b = row[base], row[base + 1], row[base + 2]
            total += (r + g + b) // 3
            count += 1
            colours.add((r >> 3, g >> 3, b >> 3))
    mean = total / max(1, count)
    print("size=%dx%d mean=%.1f unique=%d" % (width, height, mean, len(colours)))
    ok = mean >= args.min_mean and len(colours) >= args.min_unique
    if not ok:
        sys.stderr.write(
            "uitool: screenshot looks blank (need mean>=%s unique>=%s)\n"
            % (args.min_mean, args.min_unique)
        )
    return 0 if ok else 1


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def add_filters(p):
        p.add_argument("--id", default=None)
        p.add_argument("--desc", default=None)
        p.add_argument("--text", default=None)
        p.add_argument("--text-contains", dest="text_contains", default=None)

    p = sub.add_parser("dump")
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_dump)

    p = sub.add_parser("find")
    add_filters(p)
    p.add_argument("--file", default=None)
    p.add_argument("--verbose", action="store_true")
    p.set_defaults(func=cmd_find)

    p = sub.add_parser("wait")
    add_filters(p)
    p.add_argument("--timeout", type=int, default=60)
    p.set_defaults(func=cmd_wait)

    p = sub.add_parser("has")
    add_filters(p)
    p.set_defaults(func=cmd_has)

    p = sub.add_parser("image-stats")
    p.add_argument("--file", required=True)
    p.add_argument("--min-mean", dest="min_mean", type=float, default=4.0)
    p.add_argument("--min-unique", dest="min_unique", type=int, default=20)
    p.set_defaults(func=cmd_image_stats)

    args = parser.parse_args(argv[1:])
    try:
        return args.func(args)
    except Exception as exc:  # noqa: BLE001 - the smoke script reports the message
        sys.stderr.write("uitool: %s: %s\n" % (type(exc).__name__, exc))
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
