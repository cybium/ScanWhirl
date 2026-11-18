# LANE_REPORT — uihome (Home library list, visible UI)

Owner finding: *"document names have no tiles, it's like a page with names, difficult to navigate,
no scroll bar."* Scope: the library LIST only (the tiles / quick tools above it stay for a later
Home change, as instructed).

## Lead visual-check rework (2026-11-18, after the phone pass)

1. **Floating sticky "16 documents" chip removed.** `LibraryViewportChrome` now holds only the
   fast-scroll bar strip (one 24dp plain View at the end edge); nothing is drawn over a row. The
   count stays as normal text in the list header (`swDocCount`, "16 documents").
2. **Rows tightened.** Tile 64×90 dp (page aspect), card vertical padding 12 dp, 12 dp thumb→text
   gap, row 114 dp measured (was ~135) with a two-line title, so ~5 rows fit instead of 4.5.
   Title still 2 lines max; Share/⋯ keep 48 dp targets.
   - Meta is **one line** (`maxLines = 1`, ellipsize end) and, at 100 % font, keeps the owner's
     exact "2 pages · 166 KB · Today" uncut (asserted).
   - Folder dot + name sit after the meta when both fit; a small `MetaLine` ViewGroup drops the
     folder to a second line when the meta needs the width (long meta, Hindi, 200 % font) instead
     of squeezing or overlapping it.
3. **Hindi / large text:** new test at fontScale 1.3 with a long Hindi title asserts no two row
   text boxes overlap and every box stays inside the card (green).

Accessibility audit: a one-line meta necessarily ellipsizes at 200 % font, so the documented
allow-list got **one** narrow entry, `*|clip|swDocMeta|uihome: …`, naming this lane and the lead's
order (the full text stays on the TextView for TalkBack). Nothing else was allow-listed.

Red first for this rework: cloud run **44293545383** (`GATE FAIL tests=11 failures=2` — meta had
`maxLines=3`, two-line row measured 126 px > 120 dp). Green: run **44294718646**
(`GATE PASS tests=11`), a11y class **44295638589**, full suite **44296120420**.

## Done

1. **Real paper thumbnails in every row** (`DocCard.kt`, the row view LibraryScreen uses)
   - Tile 64×90 dp (1:1.414 page aspect, 8 dp radius); page-count badge kept bottom-start (11 sp).
   - Real first page decoded by the existing `PageThumbLoader` (off the main thread, sampled to
     320 px, `fitExactly`, reuses the 24 MB shared LRU). No second cache, no new library.
   - Missing/unreadable/locked page → **placeholder with the document's first letter + page icon**
     (never a blank tile). Decode failure also lands on the placeholder (`onFailed`).
   - Name wraps at 2 lines (autosize kept); meta is one line (see rework above).
   - Third line = **folder dot + folder name as typed (sentence case)**; a document with no folder
     keeps its category label + category colour dot.
   - Row 114 dp (minimum 88 dp); Share and ⋯ keep 48 dp targets.
2. **Fast scroll** (new `LibraryChrome.kt`, wired from `LibraryScreen.kt`)
   - Visible track + draggable thumb at the end edge; drag scrolls the library proportionally
     (fast scroll). Shown only when the list is taller than the screen and
     `documentCount >= 12` (`LibraryFastScrollBar.MIN_DOCUMENTS`). 24 dp-wide touch strip; only the
     thumb (±8 dp) consumes touches, so rows/taps under the rest of the strip still work.
   - **Ownership note:** ShellActivity creates the `ScrollView` and cannot be edited here, so the
     bar is added to that ScrollView's `FrameLayout` host (`swTabContent`) on attach and adopted
     again on re-attach; it leaves with the host on a tab switch and removes its scroll listener on
     detach. The pinned layer paints no text and covers no row.
3. **Calmer labels:** removed the only ALL CAPS label on Home (`"Add tools"` was `.uppercase()`);
   section labels were already sentence case. New test walks every Home TextView and fails on any
   ALL CAPS label longer than 3 letters.
4. **Optional list/grid toggle: NOT done** (optional in the brief; skipped rather than risk the
   required work — a 2-up grid needs its own card layout and a prefs migration).

## Not done / follow-ups

- Grid toggle (see above).
- The Home band above the list (create row, quick tools, chips) is unchanged by design; the next
  owner-visible Home pass can remove it.

## Tests added (Robolectric, red first, then green)

`app/src/test/java/com/scanwhirl/app/ui/shell/LibraryListCraftTest.kt` — 11 tests:
row thumbnail ≥ 64 dp + page aspect + decoded bitmap + row ≥ 88 dp + name maxLines 2 + meta
maxLines 1; a two-line title keeps the row ≤ 120 dp and the exact "2 pages · 166 KB · Today" meta
stays uncut; a long Hindi title at 1.3× has no overlapping or out-of-card row text; locked document
shows letter "D" + page icon; thumbnail reuses the shared cache (same bitmap instance); 16 documents
get a shown/enabled draggable fast-scroll bar that moves `scrollY`; a 2-document list has no bar;
**nothing is drawn over the list content** (no TextView in any scroll-host sibling, count stays in
the header); the bar is the only pinned layer and is ≤ 32 dp wide; no ALL CAPS Home label; row names
its folder "Holidays".

Red evidence (first version): cloud run **44286178396** (`GATE FAIL tests=8 failures=3`): blank
thumbnail, cache reuse, bar width. Red evidence (lead rework): **44293545383**
(`GATE FAIL tests=11 failures=2`): meta `maxLines` was 3, two-line row measured 126 px > 120 dp.
Green: **44286931682** (8/8), **44294718646** (11/11), a11y **44295638589**, full suite
**44296120420**.

Accessibility regression caught and fixed in the first version: run **44287434808** failed
`AccessibilityAuditTest` with `Library|clip|swDocMeta :: @2.0x line 1 is ellipsized`; the rework
makes the meta one line on purpose, so it is one narrow allow-list line (see rework note).

## Last gate

`GATE PASS uihome tests=1779 failures=0 skipped=0 lint=[skipped]` — cloud run **44296120420**
(full suite; lint runs on integration). Note: `PageEditorDraftTest` failed once under full-run load
in run **44289414972**, then passed the same class alone (**44290613594**) and in the final runs;
it is a timing flake in the page editor, not touched by this lane.

## Commits

- `3c10f24` uihome: Home list is navigable — real page tiles, letter placeholder, folder names,
  fast scroll, no ALL CAPS
- `168bae6` uihome rework per lead phone check: no floating count overlay, rows 114 dp (64×90
  tile), one-line meta with folder wrapping below, Hindi 1.3 check

## New strings (EN + HI, Hindi marked for native review)

| key | EN (`values/strings_uihome.xml`) | HI (`values-hi/strings_uihome.xml`) |
|---|---|---|
| `home_fast_scroll` | Scroll quickly through documents | `<!-- review -->` दस्तावेज़ों में तेज़ी से स्क्रॉल करें |

Everything else reuses existing strings (`plurals/list_count`, `home_recent`, `home_quick_tools`,
`share_pdf`, `more`, …). No new colours, no new libraries, no permission changes.

## Files

- `app/src/main/java/com/scanwhirl/app/ui/shell/LibraryScreen.kt` (list wiring, chrome install,
  sentence-case fix)
- `app/src/main/java/com/scanwhirl/app/ui/shell/LibraryChrome.kt` (new: `LibraryFastScrollBar`,
  `LibraryViewportChrome`)
- `app/src/main/java/com/scanwhirl/app/ui/kit/DocCard.kt` (row view used by the list)
- `app/src/main/res/values/ids_uihome.xml`, `values/strings_uihome.xml`,
  `values-hi/strings_uihome.xml`, `drawable/sw_thumb_blank.xml`
- `app/src/test/java/com/scanwhirl/app/ui/shell/LibraryListCraftTest.kt`
