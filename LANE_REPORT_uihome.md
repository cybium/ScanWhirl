# LANE_REPORT — uihome (Home library list, visible UI)

Owner finding: *"document names have no tiles, it's like a page with names, difficult to navigate,
no scroll bar."* Scope: the library LIST only (the tiles / quick tools above it stay for a later
Home change, as instructed).

## Done

1. **Real paper thumbnails in every row** (`DocCard.kt`, the row view LibraryScreen uses)
   - Tile 72×102 dp (1:1.414 page aspect, 8 dp radius) instead of 54×70 dp; page-count badge kept
     bottom-start (11 sp for legibility).
   - Real first page decoded by the existing `PageThumbLoader` (off the main thread, sampled to
     320 px, `fitExactly`, reuses the 24 MB shared LRU). No second cache, no new library.
   - Missing/unreadable/locked page → **placeholder with the document's first letter + page icon**
     (never a blank tile). Decode failure also lands on the placeholder (`onFailed`).
   - Name still wraps at 2 lines (autosize kept); meta `"3 pages · 544 KB · Today"` now reflows to
     3 lines at 200 % font instead of ellipsizing (the a11y audit rejects a cut line).
   - Third line = **folder dot + folder name as typed (sentence case)**; a document with no folder
     keeps its category label + category colour dot.
   - Row minimum height 88 dp; Share and ⋯ keep 48 dp targets.
2. **Fast scroll + sticky count** (new `LibraryChrome.kt`, wired from `LibraryScreen.kt`)
   - Visible track + draggable thumb at the end edge; drag scrolls the library proportionally
     (fast scroll). Shown only when the list is taller than the screen and
     `documentCount >= 12` (`LibraryFastScrollBar.MIN_DOCUMENTS`). 24 dp-wide touch strip; only the
     thumb (±8 dp) consumes touches, so rows/taps under the rest of the strip still work.
   - Sticky `"16 documents"` chip that appears once the count header scrolls off (hidden again when
     the header is back / search hides it). The header itself keeps reading the same plurals.
   - **Ownership note:** ShellActivity creates the `ScrollView` and cannot be edited here, so the
     pinned layer is added to that ScrollView's `FrameLayout` host (`swTabContent`) on attach and
     adopted again on re-attach; it leaves with the host on a tab switch and removes its scroll
     listener on detach.
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

`app/src/test/java/com/scanwhirl/app/ui/shell/LibraryListCraftTest.kt` — 8 tests:
row thumbnail ≥ 72 dp + page aspect + decoded bitmap + row ≥ 88 dp + name maxLines 2; locked
document shows letter "D" + page icon; thumbnail reuses the shared cache (same bitmap instance);
16 documents get a shown/enabled draggable fast-scroll bar that moves `scrollY`; a 2-document list
has no bar; count reads "16 documents" and the sticky chip appears after scrolling; no ALL CAPS
Home label; row names its folder "Holidays".

Red evidence: cloud run **44286178396** (`GATE FAIL uihome tests=8 failures=3`): blank-thumbnail
assertion, cache-reuse assertion, bar width assertion. Green: run **44286931682**
(`GATE PASS uihome tests=8 failures=0`).

Accessibility regression caught and fixed: full run **44287434808** failed
`AccessibilityAuditTest` with `Library|clip|swDocMeta :: @2.0x line 1 is ellipsized`; fixed by
letting the meta reflow to 3 lines (green in run **44288974337**).

## Last gate

`GATE PASS uihome tests=1776 failures=0 skipped=0 lint=[skipped]` — cloud run **44291165596**
(full suite; lint runs on integration). Note: `PageEditorDraftTest` failed once under full-run load
in run **44289414972**, then passed the same class alone (**44290613594**) and in the final full run;
it is a timing flake in the page editor, not touched by this lane.

## Commits

- `3c10f24` uihome: Home list is navigable — 72dp real page tiles, letter placeholder, folder
  names, fast scroll, sticky count, no ALL CAPS

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
