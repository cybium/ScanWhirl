#!/usr/bin/env python3
"""Build the human-facing ScanWhirl screen atlas from app/build/atlas/atlas.json.

The JSON and the PNGs are produced by ScreenAtlasTest (Robolectric, on demand) via
tools/atlas/run_atlas.ps1. This script writes:

  * SCREEN_MAP_CURRENT.md  - the same table format as qa/screenmap/SCREEN_MAP.md
  * ATLAS.html             - self-contained page, images relative (shots/...)
  * shots/*.png            - the rendered screens and their box overlays

It also prints/writes a heuristic diff against the old hand-made map, so the owner can see
what the B1-B4 redesign changed and what is new.

Nothing here is a claim about the real phone: the atlas is Robolectric at
w360dp-h800dp-xxhdpi, and system UI (camera, photo picker, SAF, share chooser) is not drawn.
"""

import argparse
import datetime
import difflib
import html
import json
import os
import re
import shutil
import subprocess
import sys

OLD_ROW = re.compile(r"^\s*(\d{2,4})\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*$")
WORD = re.compile(r"[A-Za-z][A-Za-z0-9'\-]{2,}")
STOP = {
    "the", "and", "with", "from", "for", "into", "that", "this", "then", "your", "you",
    "not", "opens", "open", "shows", "shown", "sheet", "dialog", "screen", "no", "any",
}


def normalize_effects(data):
    """Correct the one navigation label the harness cannot read from a view.

    The shell's nav cells do not expose which tab is selected as a view state, so the test
    recorded "already on this tab" for every tab press on a shell screen. The data still says
    which screen it was and which tab the label names, so the truthful effect is deterministic:
    the tab you are on is a no-op, any other tab switches the screen.
    """
    tabs = {"library": "Library", "tools": "Tools", "vault_tab": "Vault", "me": "Me"}
    for s in data.get("screens", []):
        if s.get("scale") != "1.0":
            continue
        current = tabs.get(s.get("key"))
        for b in s.get("buttons", []):
            eff = b.get("effect") or ""
            if "already on this tab" in eff and (b.get("id") or "").startswith("swNav"):
                if current and (b.get("label") or "") == current:
                    b["effect"] = "current tab (no change)"
                else:
                    b["effect"] = "visible screen change"
                b["dead"] = False
    return data


def load_atlas(path):
    with open(path, "r", encoding="utf-8") as f:
        return normalize_effects(json.load(f))


def group_screens(data):
    """key -> {'first': entry, 'byScale': {scale: entry}} preserving atlas index order."""
    groups = {}
    for e in data.get("screens", []):
        g = groups.setdefault(e["key"], {"first": e, "byScale": {}})
        g["byScale"][e["scale"]] = e
        if e["index"] < g["first"]["index"]:
            g["first"] = e
    return dict(sorted(groups.items(), key=lambda kv: kv[1]["first"]["index"]))


def esc_pipe(s):
    return (s or "").replace("|", "/").replace("\n", " ").strip()


def button_line(b):
    name = (b.get("label") or "").strip()
    if not name:
        name = (b.get("id") or "").strip()
    if not name:
        name = f"({b.get('class', 'view')} #{b.get('n')})"
    eff = b.get("effect")
    if eff is None:
        eff = "recorded (not pressed)"
    if b.get("dead"):
        eff = "NO VISIBLE EFFECT - dead candidate"
    return f"{name} -> {eff}"


def screen_row(e):
    buttons = [button_line(b) for b in e.get("buttons", [])]
    return [
        f"{e['index']:03d}",
        esc_pipe(e.get("title") or e["key"]),
        esc_pipe(e.get("path") or ""),
        esc_pipe("; ".join(buttons)) if buttons else "(no visible button)",
    ]


def render_md(data, groups, old_map, sha, branch):
    out = []
    scales = data.get("scales", [])
    out.append("# ScanWhirl current screen map (generated from the code)")
    out.append("")
    out.append(
        f"Generated {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')} by `tools/atlas/run_atlas.ps1` "
        f"from worktree `{sha}` on branch `{branch}`. Do not hand-edit: re-run the tool."
    )
    out.append("")
    out.append(
        "Fixture: fresh library with 3 fictional documents, **Free** (not Pro), English, "
        f"`{data.get('qualifiers', '')}`, font scale {' and '.join(str(s) + 'x' for s in scales)}. "
        "Robolectric renders at 1080x2400 with a 108 px status bar and 72 px navigation bar "
        "dispatched as insets. System UI (camera, photo picker, SAF, share chooser) is NOT drawn."
    )
    out.append("")
    out.append("Screenshots: `shots/NNN_<screen>_<scale>.png` (and `_boxes.png` with numbered red targets).")
    out.append("Format: `NNN | title as shown | tap path | buttons -> destination or effect`")
    out.append("")
    out.append("| # | Title as shown | Tap path from Home | Buttons / items -> destination or effect |")
    out.append("|---|---|---|---|")
    for g in groups.values():
        e = g["byScale"].get("1.0") or g["first"]
        row = screen_row(e)
        if e.get("kind") == "not-renderable":
            row[3] = "not rendered - see the 'Not renderable' section"
        out.append("| " + " | ".join(row) + " |")
    out.append("")

    out.append("## Screens drawing content under the status bar (top < 108 px)")
    out.append("")
    flagged = []
    for g in groups.values():
        for sc, e in g["byScale"].items():
            if e.get("underStatusBar"):
                flagged.append((e, sc))
    if flagged:
        out.append("The 108 px status-bar inset is dispatched to every screen; these draw their first text above that line, so on a phone the header sits under the clock/icons.")
        out.append("")
        out.append("| Screen | Scale | First text top (px) |")
        out.append("|---|---|---|")
        for e, sc in flagged:
            out.append(f"| {esc_pipe(e.get('title') or e['key'])} | {sc} | {e.get('contentTopPx')} |")
    else:
        out.append("None: every screen pads its first text below the 108 px status bar.")
    out.append("")

    out.append("## Dead buttons (clickable, no visible effect)")
    out.append("")
    out.append(
        "Recorded when a press produced no started screen, no sheet, no toast/snackbar and no "
        "visible change. A few are no-ops by design (pressing the tab you are already on, an "
        "already-active filter chip, a popup menu Robolectric cannot render); each is listed so "
        "the lead can judge. This is a candidate list, not a verdict."
    )
    out.append("")
    dead = []
    for g in groups.values():
        e = g["byScale"].get("1.0") or g["first"]
        for b in e.get("buttons", []):
            if b.get("dead"):
                dead.append((e, b))
    if dead:
        out.append("| Screen | # | id | label | what was seen |")
        out.append("|---|---|---|---|---|")
        for e, b in dead:
            out.append(
                f"| {esc_pipe(e.get('title') or e['key'])} | {b.get('n')} | "
                f"{esc_pipe(b.get('id') or '-')} | {esc_pipe((b.get('label') or '')[:60])} | "
                f"{esc_pipe(b.get('effect') or '')} |"
            )
    else:
        out.append("None recorded.")
    out.append("")

    out.append("## Not renderable (system UI or Pro-gated in the Free fixture)")
    out.append("")
    out.append("| Screen | Kind | Why |")
    out.append("|---|---|---|")
    for n in data.get("notRenderable", []):
        out.append(f"| {esc_pipe(n['screen'])} | {esc_pipe(n['kind'])} | {esc_pipe(n['reason'])} |")
    for g in groups.values():
        e = g["first"]
        if e.get("kind") == "not-renderable":
            why = " ".join(e.get("notes") or [])
            out.append(f"| {esc_pipe(e.get('title') or e['key'])} | in-app screen | {esc_pipe(why)} |")
    out.append("")

    out.append("## Changes against qa/screenmap/SCREEN_MAP.md (18 Nov)")
    out.append("")
    if not old_map:
        out.append("Old map not found; no diff.")
        return "\n".join(out) + "\n"

    old_titles, old_buttons = parse_old_map(old_map)
    new_titles = new_title_set(groups)
    matched, gone, new = match_titles(old_titles, new_titles)
    out.append("Heuristic text match (titles and visible button words), not a semantic diff.")
    out.append("")
    out.append("### Screens in the old map that are gone now")
    out.append("")
    out.append(
        "No current entry matches these titles. Some are system UI (photo picker, SAF); some are "
        "other tools' setup screens this atlas does not render (To Word, Split, PowerPoint, Excel, "
        "PDF to images, Long image); some are the same screen under a new name (Choose a category, "
        "Viewer save snackbar). Review before treating any as removed."
    )
    out.append("")
    if gone:
        for t in gone:
            out.append(f"- {t}")
    else:
        out.append("- none detected")
    out.append("")
    out.append("### Screens new in this atlas")
    out.append("")
    if new:
        for t in new:
            out.append(f"- {t}")
    else:
        out.append("- none detected")
    out.append("")
    out.append("### Buttons: old wording no longer present on a matched screen")
    out.append("")
    changed = 0
    for old_t, new_t in matched:
        missing = old_buttons.get(old_t, set()) - button_words_for_title(groups, new_t)
        missing = {w for w in missing if w not in STOP and len(w) >= 3}
        if missing:
            changed += 1
            out.append(f"- **{old_t}** -> {new_t}: {', '.join(sorted(missing)[:18])}")
    if changed == 0:
        out.append("- none detected")
    out.append("")
    out.append("### Buttons: new wording on a matched screen")
    out.append("")
    added = 0
    for old_t, new_t in matched:
        extra = button_words_for_title(groups, new_t) - old_buttons.get(old_t, set())
        extra = {w for w in extra if w not in STOP and len(w) >= 3}
        if extra:
            added += 1
            out.append(f"- **{new_t}** (was {old_t}): {', '.join(sorted(extra)[:18])}")
    if added == 0:
        out.append("- none detected")
    out.append("")
    out.append(
        "Note: the old map was made on the phone against the pre-redesign app, so most of this "
        "diff is the B1-B4 redesign (Library first screen, Create sheet, named Actions sheet, "
        "grouped Tools hub), not a regression list."
    )
    return "\n".join(out) + "\n"


def parse_old_map(path):
    titles = []
    buttons = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            m = OLD_ROW.match(line.rstrip("\n"))
            if not m:
                continue
            title = m.group(2).strip()
            cell = m.group(4).strip()
            titles.append(title)
            words = {w.lower() for w in WORD.findall(cell)}
            buttons[title] = words
    return titles, buttons


def norm_title(t):
    t = re.sub(r"\(.*?\)", " ", t or "")
    t = re.sub(r"[^a-z0-9]+", " ", t.lower())
    return " ".join(t.split())


def title_variants(t):
    """Variants of an old title: the whole title, each parenthesis, and the base.

    The 18 Nov map named screens after the sample file (SW_TEST_3pages) and the QA folder,
    which no longer exist; strip those before matching so the same screen is not reported as
    "gone" just because the sample was renamed.
    """
    t = re.sub(r"(?i)sw_test_3pages|qa folder(\s*\(\d+\))?", " ", t or "")
    variants = {norm_title(t)}
    for part in re.findall(r"\((.*?)\)", t):
        variants.add(norm_title(part))
    variants.add(norm_title(re.sub(r"\(.*?\)", " ", t)))
    return {v for v in variants if v}


def new_title_set(groups):
    titles = []
    for g in groups.values():
        e = g["byScale"].get("1.0") or g["first"]
        t = e.get("title") or e["key"]
        titles.append(t)
        d = e.get("dialogTitle")
        if d and d not in titles:
            titles.append(d)
    return titles


def match_titles(old_titles, new_titles):
    matched = []
    for o in old_titles:
        ov = title_variants(o)
        if not ov:
            continue
        best_score = 0.0
        best_new = None
        for n in new_titles:
            nv = norm_title(n)
            for variant in ov:
                score = difflib.SequenceMatcher(None, variant, nv).ratio()
                if len(variant) >= 10 and (variant in nv or nv in variant):
                    score = max(score, 0.9)
                if score > best_score:
                    best_score = score
                    best_new = n
        if best_new is not None and best_score >= 0.75:
            matched.append((o, best_new))
    used_new = {n for _o, n in matched}
    gone = [t for t in old_titles if t not in {o for o, _n in matched}]
    new = [t for t in new_titles if t not in used_new and t != "not-renderable"]
    # Several states can share one title (the four Create sheets, three More sheets): list once.
    new = list(dict.fromkeys(new))
    return matched, gone, new


def button_words_for_title(groups, title):
    for g in groups.values():
        e = g["byScale"].get("1.0") or g["first"]
        if (e.get("title") or "") == title:
            words = set()
            for b in e.get("buttons", []):
                words |= {w.lower() for w in WORD.findall((b.get("label") or "") + " " + (b.get("id") or ""))}
            return words
    return set()


def render_html(data, groups, old_map, sha, branch, scale_list):
    esc = html.escape
    rows = []
    for g in groups.values():
        e = g["byScale"].get("1.0") or g["first"]
        if e.get("kind") == "not-renderable":
            continue
        title = esc(e.get("title") or e["key"])
        path = esc(e.get("path") or "")
        imgs = []
        for sc in scale_list:
            s = g["byScale"].get(sc)
            if not s or not s.get("png"):
                continue
            imgs.append(
                f'<figure><a href="shots/{esc(s["png"])}"><img src="shots/{esc(s["png"])}" alt="{title} at {sc}x"></a>'
                f'<figcaption>{sc}x &middot; <a href="shots/{esc(s["overlay"])}">targets</a></figcaption></figure>'
            )
        btns = []
        for b in e.get("buttons", []):
            cls = ' class="dead"' if b.get("dead") else ""
            label = esc((b.get("label") or b.get("id") or f"#{b.get('n')}"))
            btns.append(
                f"<tr{cls}><td>{b.get('n')}</td><td><code>{esc(b.get('id') or '-')}</code></td>"
                f"<td>{label}</td><td>{esc(b.get('effect') or 'recorded (not pressed)')}</td></tr>"
            )
        notes = "".join(f"<li>{esc(n)}</li>" for n in (e.get("notes") or []))
        warn = '<p class="warn">First text sits under the 108 px status bar (top ' + str(e.get("contentTopPx")) + ' px).</p>' if e.get("underStatusBar") else ""
        rows.append(
            f'<section id="s{e["index"]}"><h2>{e["index"]:03d} {title}</h2>'
            f'<p class="path">{path}</p>{warn}<div class="shots">{"".join(imgs)}</div>'
            f'{("<ul class=note>" + notes + "</ul>") if notes else ""}'
            f'<table><thead><tr><th>#</th><th>id</th><th>label</th><th>press result</th></tr></thead>'
            f'<tbody>{"".join(btns)}</tbody></table></section>'
        )
    dead = []
    for g in groups.values():
        e = g["byScale"].get("1.0") or g["first"]
        for b in e.get("buttons", []):
            if b.get("dead"):
                dead.append(
                    f"<li><b>{esc(e.get('title') or e['key'])}</b> #{b.get('n')} "
                    f"<code>{esc(b.get('id') or '-')}</code> {esc((b.get('label') or '')[:60])} "
                    f"&mdash; {esc(b.get('effect') or '')}</li>"
                )
    nr = "".join(
        f"<li><b>{esc(n['screen'])}</b> <span class=kind>{esc(n['kind'])}</span><br>{esc(n['reason'])}</li>"
        for n in data.get("notRenderable", [])
    )
    nr_extra = "".join(
        f"<li><b>{esc(g['first'].get('title') or g['first']['key'])}</b> <span class=kind>in-app screen</span><br>"
        f"{esc(' '.join(g['first'].get('notes') or []))}</li>"
        for g in groups.values() if g["first"].get("kind") == "not-renderable"
    )
    diff_html = ""
    if old_map:
        old_titles, old_buttons = parse_old_map(old_map)
        new_titles = new_title_set(groups)
        matched, gone, new = match_titles(old_titles, new_titles)
        diff_html = (
            "<h3>Old map screens gone now</h3><ul>"
            + "".join(f"<li>{esc(t)}</li>" for t in gone)
            + "</ul><h3>New screens</h3><ul>"
            + "".join(f"<li>{esc(t)}</li>" for t in new)
            + "</ul>"
        )
    notes = "".join(f"<li>{esc(n)}</li>" for n in (data.get("notes") or []))
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ScanWhirl screen atlas</title>
<style>
:root{{color-scheme:dark}}body{{background:#0a1016;color:#e8f6f8;font:15px/1.5 system-ui,Segoe UI,sans-serif;margin:0;padding:24px}}
h1,h2{{font-weight:800;letter-spacing:.2px}}h1{{font-size:28px;margin:0 0 6px}}h2{{font-size:20px;margin:28px 0 4px;color:#7ff2ff}}
a{{color:#7ff2ff}}code{{color:#ffd66b;font-size:13px}}.path{{color:#9fb4bd;margin:0 0 10px}}
.facts{{background:#101b21;border:1px solid #1d3b44;border-radius:14px;padding:14px 18px;max-width:1100px}}
.facts li{{margin:2px 0}}.shots{{display:flex;gap:14px;flex-wrap:wrap;margin:8px 0 14px}}
figure{{margin:0}}figure img{{width:270px;border:1px solid #1d3b44;border-radius:12px;display:block}}
figcaption{{color:#9fb4bd;font-size:13px;padding-top:4px}}
table{{border-collapse:collapse;width:100%;max-width:1100px;font-size:13px}}
th,td{{border-bottom:1px solid #163039;padding:6px 8px;text-align:left;vertical-align:top}}
th{{color:#7ff2ff;font-weight:600}}tr.dead td{{background:#2a1512}}tr.dead td:last-child{{color:#ff9b8a}}
section{{border-top:1px solid #163039;padding-top:10px}}ul.note{{color:#ffd66b}}span.kind{{color:#9fb4bd;font-size:13px}}
p.warn{{background:#3a1310;border:1px solid #ff7a63;color:#ffc9bf;padding:8px 12px;border-radius:10px;max-width:700px}}
ul li{{margin:4px 0}}
</style></head><body>
<h1>ScanWhirl screen atlas</h1>
<div class=facts><ul>
<li>Generated {esc(datetime.datetime.now().strftime('%Y-%m-%d %H:%M'))} from <code>{esc(sha)}</code> on <code>{esc(branch)}</code></li>
<li>Fixture: 3 fictional documents, <b>Free</b> (not Pro), English, <code>{esc(data.get('qualifiers',''))}</code>, scales {esc(', '.join(scale_list))}</li>
<li>Every screenshot is Robolectric at 1080x2400 with a 108 px status bar and 72 px navigation inset dispatched. <b>Not a phone photo.</b></li>
<li>Red boxes are numbered targets on the <code>_boxes.png</code> copies. The dashed cyan line is the status-bar boundary: text above it is under the bar.</li>
<li>Dead-button rows are shaded red: the press produced no visible effect. Some are no-ops by design; the list is for the lead to judge.</li>
</ul></div>
{'<div class=facts><b>Run notes</b><ul>' + notes + '</ul></div>' if notes else ''}
<h2>Dead buttons</h2><ul>{''.join(dead) or '<li>none recorded</li>'}</ul>
<h2>Not renderable</h2><ul>{nr}{nr_extra}</ul>
<h2>Changes against the 18 Nov map</h2>{diff_html or '<p>old map not found</p>'}
{''.join(rows)}
</body></html>
"""


def copy_shots(atlas_dir, out_dir, scales):
    shots_dir = os.path.join(out_dir, "shots")
    os.makedirs(shots_dir, exist_ok=True)
    for name in os.listdir(atlas_dir):
        if name.endswith(".png"):
            shutil.copy2(os.path.join(atlas_dir, name), os.path.join(shots_dir, name))


def git(repo, *args):
    try:
        return subprocess.check_output(["git", "-C", repo, *args], text=True).strip()
    except Exception:
        return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--atlas-dir", default=os.path.join("app", "build", "atlas"))
    ap.add_argument("--out-dir", default=None)
    ap.add_argument("--old-map", default=None)
    ap.add_argument("--repo", default=None)
    args = ap.parse_args()

    repo = os.path.abspath(args.repo or os.path.join(os.path.dirname(__file__), "..", ".."))
    atlas_dir = os.path.abspath(args.atlas_dir)
    if not os.path.isdir(atlas_dir):
        sys.exit(f"atlas: no folder at {atlas_dir} (run run_atlas.ps1 first)")
    data = load_atlas(os.path.join(atlas_dir, "atlas.json"))
    out_dir = os.path.abspath(args.out_dir or os.path.join(repo, "docs", "audit", "atlas-current"))
    old_map = os.path.abspath(args.old_map) if args.old_map else None
    if old_map and not os.path.isfile(old_map):
        old_map = None

    os.makedirs(out_dir, exist_ok=True)
    copy_shots(atlas_dir, out_dir, data.get("scales", []))
    groups = group_screens(data)
    sha = git(repo, "rev-parse", "HEAD") or "unknown"
    branch = git(repo, "rev-parse", "--abbrev-ref", "HEAD") or "unknown"
    scales = [str(s) for s in data.get("scales", [])]

    md = render_md(data, groups, old_map, sha, branch)
    html_text = render_html(data, groups, old_map, sha, branch, scales)
    with open(os.path.join(out_dir, "SCREEN_MAP_CURRENT.md"), "w", encoding="utf-8") as f:
        f.write(md)
    with open(os.path.join(out_dir, "ATLAS.html"), "w", encoding="utf-8") as f:
        f.write(html_text)
    # Write the normalised data (same effects the HTML/MD show), not a raw copy.
    with open(os.path.join(out_dir, "atlas.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)

    dead = sum(1 for g in groups.values()
               for e in [g["byScale"].get("1.0") or g["first"]]
               for b in e.get("buttons", []) if b.get("dead"))
    rendered = {sc: sum(1 for g in groups.values() if sc in g["byScale"] and (g["byScale"][sc].get("png")))
                for sc in scales}
    print(f"atlas: {len(groups)} screen keys, rendered {rendered}, dead-button candidates {dead}")
    print(f"atlas: written {out_dir}")
    if data.get("navTruncated"):
        print("atlas: WARNING navigation was truncated by the press budget")


if __name__ == "__main__":
    main()
