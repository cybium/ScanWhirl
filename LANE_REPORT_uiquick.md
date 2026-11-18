# uiquick — small visible fix: tool-tile labels stay inside their column

Lead's 1.2.3-test3 phone check, two visible defects:

1. **Home → Quick tools in Hindi**: the six labels ran together on one line
   ("फ़ाइल छोटी करें हस्ताक्षर करें सुरक्षित करें Word में बदलें लिखा हुआ पढ़ें").
2. **Tools tab in English**: "Batch tools" spilled to the left of its column.

## What was wrong

`ToolTile`'s label declared `maxLines = 2` but **no ellipsis**, and it wrapped with
`BREAK_STRATEGY_HIGH_QUALITY`. Two consequences, both measured by the new test before the fix:

- With no `ellipsize` set, a label that needed more than two lines still produced a **3- or
  4-line `Layout`** for a 2-line-tall view (measured: Hindi hub titles `lines=3 lh=108` and
  `lines=4 lh=144` in a 72 px box) — the text was painted outside its own box.
- `HIGH_QUALITY` breaking let a Devanagari line come out **wider than the column**
  (`getLineMax(line) > avail`), and a centred label then paints across both neighbours —
  which is exactly the "six labels run together on one line" the lead saw.
- The Home quick row squeezed the label instead: `fitQuickLabel` shrank it to **8.9–10.3 sp**
  (below the brief's 11 sp floor) to force one line in six columns.

## The fix

`app/src/main/java/com/scanwhirl/app/ui/kit/ToolTile.kt` (label layout only)

- Label is still `MATCH_PARENT` of the tile (never wider than its column) with
  `gravity = CENTER`, `maxLines = 2`, and now **`ellipsize = END`** — that clamps the `Layout`
  to two lines and draws "…" instead of a third line outside the tile.
- **`BREAK_STRATEGY_SIMPLE`** (greedy, word boundaries; a word wider than the column is broken
  rather than overhanging). `HIGH_QUALITY` was the overflow source for Devanagari.
- Home quick tiles: `asQuick()` now uses **11 sp fixed** (never smaller) plus a 2 dp inset per
  side so neighbouring labels cannot touch. The old shrink-to-fit-one-line code
  (`onMeasure`/`fitQuickLabel`, 9 sp floor) is deleted.

`app/src/main/java/com/scanwhirl/app/ui/shell/LibraryScreen.kt` (quick tools row only)

- **Five columns per row** (`QUICK_COLUMNS = 5`) instead of six: a 5-column cell on a 360 dp
  phone is 68 dp, which keeps an 11 sp Hindi label on clear word boundaries at fontScale 1.3
  (six columns force mid-word breaks of "हस्ताक्षर"). The row height grows for two-line labels.
- All **six shipped quick tools stay reachable**: five on row one, the sixth starts row two
  (left aligned, not stretched). A third row (`swQuickRow3`, new id in
  `res/values/ids_uiquick.xml`) keeps the 12-tool custom limit reachable at 5 per row.
- The "Add tools" grid uses the same 5 columns.

## Tests

New: `app/src/test/java/com/scanwhirl/app/ui/shell/QuickTileLabelBoundsTest.kt`
(`QuickTileLabelBoundsEnTest`, `QuickTileLabelBoundsHiTest` — 360 dp, fontScale 1.0 and 1.3,
EN and HI). For the Home quick row **and** every Tools-hub tile it asserts:

- no label starts left of, or ends right of, its own tile;
- every drawn line fits the label box (`getLineMax`, trailing wrap space excluded);
- `lineCount <= 2`, and a quick label is never ellipsized (two lines are enough);
- label size >= 11 sp in the quick row;
- **no two quick-label bounds intersect** (pairwise rect check).

Red first (before the fix), same class:
`11 label problem(s)` in Hindi — quick labels at 8.92 / 9.50 / 9.75 / 10.25 / 10.27 sp, hub
titles at 3 and 4 lines; English `2 label problem(s)` ("Compress" 9.12 sp, "Read text" 9.88 sp).
Green after.

Rewritten to the five-column design (each marked `// v2 UI: 01_home_A` /
`// v2 UI: 03_tools_hub`): `ShellLookTest` (six-column one-line test → five-column two-line),
`ShellScreenTest` (row count), `ToolsLookTest` (`getLineMax` instead of `getLineWidth`, END
ellipsis allowed on the last line when two lines are not enough), `QuickToolsTest` (5 per row,
third row for the 12-tool cap).

## Last gate

`GATE FAIL uiquick tests=1822 failures=2 skipped=0 lint=[skipped]` (cloud run 44327400130)

Both failures are **pre-existing at 482b8b3 and owned by the uiviewer lane** — they are not
touched by this diff (`git diff --stat HEAD` before the commit: only ToolTile.kt,
LibraryScreen.kt, four shell tests, the new test and the new ids file):

1. `HindiGlossaryTermsTest > rejected transliterations do not appear in Hindi UI strings`
   `viewer_meta[one]` / `[other]` in `res/values-hi/strings_uiviewer.xml` use **"पेज"**, which
   the hindi lane's judged glossary rejects (commit `39f95cd`, uiviewer). uiquick may not edit
   another lane's strings file.
2. `DocumentViewerReaderUiTest > no dock label clips at 360 dp in Hindi` expects the old
   `share_pdf` value "शेयर करें"; `values-hi` now has "साझा करें" (hindi glossary, commit
   `a0e561e`). The test hardcodes a stale string (same commit `39f95cd`).

Everything uiquick owns is green:
`GATE PASS uiquick tests=35` (QuickTileLabelBoundsEn/Hi, QuickTools, ShellLook, ToolsLook,
ToolsLookHindi, ShellScreen) on the committed tree.

## Commits

- `5d8782b` uiquick: tile labels stay inside their column (5-column quick row, 2-line 11sp,
  END ellipsis)

## For the device check

- Home, Hindi, 100 % and 130 %: five quick tools on row one, "लिखा हुआ पढ़ें" alone on row two;
  every label on one or two whole words, nothing overlapping or clipped.
- Tools tab, English: "Batch tools" (and "Searchable PDF", "To PowerPoint") centred inside
  their tile, two lines, still readable at 130 %.
- Long Hindi hub titles that cannot fit two lines now end in "…" (e.g. "एक साथ कई फ़ाइलों…") —
  that is the brief's END-ellipsis fallback, confirm it reads acceptably.
