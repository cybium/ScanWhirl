"""Join the per-slice atlas JSON files into one atlas.json.

Why this exists: ScreenAtlasTest renders 136 screens in a single JVM, which grows to ~3 GB of RSS.
The 3.5 GB WSL VM (2 GB swap, Windows host 7.4 GB) kernel-OOM-killed that run twice on 2026-11-20
(shot 78 at 03:56, shot 93 at 04:52), so tools/atlas/run_atlas.ps1 now renders the atlas in slices
(ATLAS_SLICE=from:to) and this step stitches the results back into the one atlas.json that
make_atlas.py reads. Nothing here invents data: a missing slice stays missing and is reported.

Usage: python tools/atlas/merge_atlas.py --atlas-dir app/build/atlas [--slices 3]
"""
import argparse
import glob
import json
import os
import sys


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--atlas-dir", required=True)
    ap.add_argument("--slices", type=int, default=0, help="expected slice count; a shortfall is reported")
    args = ap.parse_args()

    paths = glob.glob(os.path.join(args.atlas_dir, "atlas_slice_*.json"))
    if not paths:
        print("merge: no atlas_slice_*.json found - nothing to merge")
        return 2

    def start_of(p):
        name = os.path.basename(p)                      # atlas_slice_<from>.json
        return int(name.rsplit("_", 1)[-1].split(".")[0])

    paths.sort(key=start_of)
    if args.slices and len(paths) != args.slices:
        print(f"merge: WARNING {len(paths)} of {args.slices} slices present "
              f"({', '.join(os.path.basename(p) for p in paths)}) - the atlas is INCOMPLETE")

    merged = None
    screens = {}
    not_renderable = {}
    started = []
    notes = []
    nav_truncated = False
    for path in paths:
        d = load(path)
        if merged is None:
            merged = {k: v for k, v in d.items()
                      if k not in ("screens", "notRenderable", "notes", "startedFrom", "navTruncated")}
        for s in d.get("screens", []):
            # one entry per screen per scale; a later slice never overwrites a different screen
            screens[(s.get("key"), s.get("scale"))] = s
        for n in d.get("notRenderable", []):
            not_renderable[n.get("screen")] = n
        started.extend(d.get("startedFrom", []))
        for n in d.get("notes", []):
            if n not in notes:
                notes.append(n)
        nav_truncated = nav_truncated or bool(d.get("navTruncated"))

    merged["screens"] = sorted(screens.values(), key=lambda s: (s.get("index", 0), str(s.get("scale"))))
    merged["notRenderable"] = [not_renderable[k] for k in sorted(not_renderable)]
    merged["startedFrom"] = started
    merged["notes"] = notes
    merged["navTruncated"] = nav_truncated
    merged["slices"] = [os.path.basename(p) for p in paths]

    out = os.path.join(args.atlas_dir, "atlas.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(merged, fh, indent=2)
    print(f"merge: {len(paths)} slices -> {len(merged['screens'])} screens in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
