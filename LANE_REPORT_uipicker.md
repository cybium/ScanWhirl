# LANE_REPORT — uipicker (tool setup page, visible UI)

Owner's device screenshot of the shared tool setup page: plain name cards with no page image, an
ALL-CAPS "SOURCE" label, a "Medium" chip clipped into "Mediu / m", and Compress offering
Light / Medium / Strong while the document's compress sheet says Best quality / Balanced /
Smaller / Smallest. This lane makes **every tool that uses `ToolSetupActivity`** (split, extract,
merge, zip, compress, pdf_to_images, long_image, to_word, to_powerpoint, to_excel,
remove_metadata, duplicate) look and read like the document screens.

## Round 2 (lead's phone check)

Lead verdict on round 1: thumbnails, selection, sentence case, unified compress rows and the
scrollbar are good. Four fixes asked for, all done:

1. **The action is pinned.** `Continue` (the tool's action) now lives in a fixed bottom bar
   (`toolSetupBar`) outside `toolSetupScroll`, 56 dp tall, opaque ink background with a 1 dp cyan
   hairline, 18 dp side padding, added *after* the scroll view so it never scrolls. The bar is a
   child of the inset-padded root, so it stays above the navigation bar (and above the keyboard,
   the root already re-pads for the IME). The list keeps exactly the bar's height as bottom
   padding (`bar.addOnLayoutChangeListener` rewrites `column.paddingBottom`, so it also stays
   correct while the error line inside the bar is shown) — the last row or option is never hidden.
   The error message moved into the bar, so a validation failure is visible without scrolling.
   Applies to every tool that uses this page (the bar is built once, before the per-tool options).
2. **New compress help line.** `Choose a level, or type the size you need.` /
   `स्तर चुनें, या जितना आकार चाहिए वह लिखें।` (same string name `tools_setup_compress_note`, so
   the Batch compress sheet shows it too).
3. **Hindi follows `next/docs/review/JUDGE_HINDI.md`.**
   - `tools_pick_custom_size` → **अपनी ज़रूरत का आकार (चाहें तो), जैसे 500 KB**
     (the glossary's own "Custom size" row; "अपना आकार" is rejected there because it can mean a
     person's size, and no वैकल्पिक).
   - `tools_pick_choose_doc` → **दस्तावेज़ चुनें**: the glossary has no singular "document" row;
     कागज़ात is its collective word for the Library, and दस्तावेज़ is the familiar bank/form word
     its "familiar speech and familiar bank/form words win" rule prefers. Kept, review marker
     removed.
   - `tools_setup_run` (the pinned button) → **जारी रखें** (glossary: "Continue | जारी रखें",
     explicitly distinct from आगे). Review marker removed.
   - Review markers removed from both `strings_uipicker.xml` values and from the compress note.
   - **Still not glossary-compliant, owned elsewhere:** the four level labels come from the shared
     `compress_level_*` strings in `res/values-hi/strings.xml` (used by the document's compress
     sheet and `CompressFlow`), which say सबसे अच्छी क्वालिटी / संतुलित. The glossary wants
     **सबसे साफ़ / बीच का** (छोटा / सबसे छोटा already agree). I did not edit another lane's
     `strings.xml`: changing it also changes the document sheet's Hindi, which the owner should
     approve in one place. Proposed follow-up: set `compress_level_low` = सबसे साफ़ and
     `compress_level_medium` = बीच का.
4. **BatchActivity's compress sheet now uses the same four levels** (it was a contained change):
   four full-width 48 dp rows using `compress_level_low/medium/high/ultra`, the custom-size field
   below them with the same hint, and quality/side pairs 80/none, 65/2000, 50/1600, 38/1200
   (the old sheet had three chips and no Smallest). Its chips carry the shared `CHIP_TAG` so the
   test can read them.

Round-2 tests (red first: 6 failures, `tests=13 failures=6`, before the code):

- `the action button is pinned below the list and visible without scrolling` — no `toolSetupRun`
  ancestor is `toolSetupScroll`, the button is ≥ 56 dp, its bottom is inside the 360×640 dp root,
  it sits in the bottom third, the bar is outside the scroll and the list's bottom padding ≥ the
  bar's height.
- `the longest list ends above the pinned bar and the last row stays reachable` — scrolls to the
  end and checks the last source row's bottom is above the bar's top.
- `compress explains the level-or-size choice in plain words` (new EN line, old line gone, custom
  size hint).
- `Hindi labels follow the judged glossary` (custom size, heading, help line, जारी रखें).
- `the custom size hint fits its field at 360 dp` in EN and HI (the long Hindi hint must not clip).
- `batch compress offers the document sheet's four levels too`.
- Also fixed a real bug the red run exposed: a re-render (selection change/onResume) rebuilt the
  level rows without their size labels until a new estimate job ran; `updateEstimateLines()` is
  now called after `startEstimate()`.

Renders checked (Robolectric, 360×640 dp, EN + HI): pinned bar with the list cut off behind it,
"जारी रखें" on the button, the full Hindi custom-size hint inside its field, and the new help line
above the bar.

## Round 1

Lead's phone check was on round 1: thumbnails, selection, headings, four compress rows and the
scrollbar were all accepted; nothing in round 1 was changed by round 2 except the bar, the help
line and the Hindi/glossary items above.


## Done

1. **Real first-page thumbnails in the source list.**
   - Each row: check (22 dp) · page thumb 56 × 79 dp (1:1.414, 8 dp radius, paper background) ·
     name (15.5 sp bold, max 2 lines, ellipsis, hyphenation off) over the meta line
     "3 pages · 544 KB" · lock badge for a protected file. Rows are ≥ 76 dp.
   - The thumb comes from the **library's existing loader and cache**:
     `ui/pagegrid/PageThumbLoader.load(firstPage, targetPx, ScanFilter.ORIGINAL, into,
     fitExactly = true, skipStale = true)`. Decode is off the main thread (the loader's 2-thread
     pool), power-of-two sampled then fitted to ≈ 2× 56 dp, cached in its shared 24 MB LRU, and a
     late result for a re-bound view is dropped by the loader's request-key tag. A locked document
     never reads a page (paper tile instead), exactly as `DocCard` does.
   - Selected row = tinted gradient + cyan 1 dp border **and** the leading check
     (`toolSetupDocCheck`), never colour alone.
2. **Section headings in sentence case.** `Choose a document` / `Options`, 13 sp, no
   `letterSpacing`, no `uppercase()` (the old label shouted and carried Latin tracking).
3. **Chips never clip.** Every chip label is single-line with a 48 dp tap target; the compress
   levels are four full-width rows (one per line), Tables only / All text stack full width, and
   JPG / PNG keep a 2-up row (identical in Hindi). Verified by measurement at **360 dp and
   additionally 320 dp**, EN and HI: `lineCount == 1`, zero ellipsised characters, line width
   inside the available width. The selected chip keeps its check.
4. **Compress = the document sheet's four levels.** `compress_level_low / medium / high / ultra`
   (Best quality / Balanced / Smaller / Smallest), default **Balanced**, each row showing **its own
   estimated size**, using the same qualities/sides as `CompressFlow`
   (80/no cap, 65/2000, 50/1600, 38/1200; `compressScan` gets the same pairs, so the page and the
   sheet now produce the same file), and `Custom size (optional), e.g. 500 KB` last, mirroring
   "Custom size" in the sheet.
5. **Visible scrollbar** on the scrolling container (`toolSetupScroll`):
   `isVerticalScrollBarEnabled = true`, `isScrollbarFadingEnabled = false`,
   `SCROLLBARS_INSIDE_INSET`. The source list is not its own scroller (rows are plain views in the
   page `ScrollView`), so the page's bar is the honest one; it renders in the right gutter.

## Not done / deliberate

- `BatchActivity`'s compress sheet still says Light / Medium / Strong (a different screen, not this
  page). It is a one-line follow-up if the lead wants the same four words there.
- Rows are rebuilt on each selection (no RecyclerView), so there is no true recycling; the
  thumbnails are cached so a rebuild costs no decode. Switching this page to a RecyclerView is a
  larger change and was not needed for the owner's complaint.

## Files

- `app/src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt` — pinned action bar, rows,
  headings, chips, compress levels, scrollbar, estimate plumbing.
- `app/src/main/java/com/scanwhirl/app/ui/tools/BatchActivity.kt` — its compress sheet now uses the
  same four levels and the same custom-size field (round 2).
- `app/src/main/res/values/ids_tools.xml` — new ids only (`toolSetupBar`, the source-row/level ids
  and `batchLevelBest/Balanced/Smaller/Smallest`). Old unused ids are left in place (harmless,
  other screens share the file).
- `app/src/main/res/values/strings_uipicker.xml` + `values-hi/strings_uipicker.xml` — two NEW labels.
- `app/src/main/res/values/strings_tools.xml` + `values-hi/strings_tools.xml` — the compress help
  line and the Hindi `Continue` (जारी रखें).
- Tests: `app/src/test/.../ui/tools/ToolSetupPickerUiTest.kt` (new),
  `ToolsUiTest.kt` and `ToolsPluralsTest.kt` (adjusted to the new row shape, each marked
  `// v2 UI: 03_tools_setup_picker`).

## Hindi

Wording follows `next/docs/review/JUDGE_HINDI.md`. The compress levels reuse the existing
`compress_level_*` Hindi values (see the follow-up in Round 2 about सबसे साफ़ / बीच का).

| key | EN | HI | source |
|---|---|---|---|
| `tools_pick_choose_doc` | Choose a document | दस्तावेज़ चुनें | no glossary row for one document; familiar form word |
| `tools_pick_custom_size` | Custom size (optional), e.g. 500 KB | अपनी ज़रूरत का आकार (चाहें तो), जैसे 500 KB | glossary "Custom size" row |
| `tools_setup_compress_note` | Choose a level, or type the size you need. | स्तर चुनें, या जितना आकार चाहिए वह लिखें। | lead's wording in round 2 |
| `tools_setup_run` | Continue | जारी रखें | glossary "Continue" row |

Renders at 360 dp in Hindi show "सबसे अच्छी क्वालिटी" and the long custom-size hint on one line
inside their rows/field.

## Tests (red first)

Red run (`-Tests com.scanwhirl.app.ui.tools.ToolSetupPickerUiTest`, before the code):
`tests: total=7 failures=7` — real-thumbnail, check, sentence-case heading, four-level compress,
EN/HI clip tests and the scrollbar test all failed (plus one compile fix and the Hindi qualifier
order corrected).

New coverage in `ToolSetupPickerUiTest`:

1. every source row shows the real first page (drawable is a `BitmapDrawable`, not the
   placeholder), thumb ≥ 56 dp and taller than wide, row ≥ 72 dp, name `maxLines == 2`, meta
   matches "N pages · … B";
2. exactly one row checked, and it is the selected one (check exists besides the tint);
3. the two section labels are `Choose a document` / `Options`, not all-caps, zero letter-spacing;
4. the compress chips are exactly the four `compress_level_*` strings, four level rows exist,
   Balanced is default with a visible check, clicking changes the single choice, every level gets
   an estimate, and the size field keeps the "Custom size" wording;
5. no chip clips at 360 dp in English and in Hindi, and again at 320 dp in both (1 line, no
   ellipsis, line width fits, 48 dp tap target);
6. the scroll container has a non-fading vertical scrollbar.

Adjusted: `ToolsUiTest` compress-level test (four rows, per-row size) and `ToolsPluralsTest`
(meta found by id, since the row now leads with a thumbnail). `LockedSourceTest` passes unchanged
(row is still a `LinearLayout`, tag = document id, check is child 0, the lock icon is a direct
child).

## Gate (round 2)

- `GATE PASS uipicker tests=1783 failures=0 skipped=0` (cloud, run 44295154968).
- Red first: `tests=13 failures=6` (pinned-bar, longest-list, help line, Hindi glossary, Batch
  levels; the batch/target tests then green after the fix below).
- Targeted green: ToolSetupPickerUiTest 15/15.
- Round-1 gate: `GATE PASS uipicker tests=1776 failures=0` (run 44289078314).

Commits: `d63b886` (round 2: pinned bar, help line, glossary Hindi, Batch levels),
`e02ea98` (round 1: thumbnails, headings, four levels, chips, scrollbar), `fed03b0`/this file
(report).

## Before → after

| | before | after |
|---|---|---|
| source row | name + right-aligned meta, ~50 dp tall | 56 × 79 page thumb, name (2 lines) over meta, 76+ dp, lock badge |
| heading | `SOURCE` / `OPTIONS` all caps + tracking | `Choose a document` / `Options`, sentence case |
| compress | three half-width chips Light / Medium / Strong, 40 dp, "Medium" clipped | four full-width rows Best quality / Balanced / Smaller / Smallest, each with its size, 48 dp, check + tint |
| Tables only / All text | two half-width chips, 40 dp | two full-width 48 dp rows |
| long list | scrollbar faded away | scrollbar always visible in the right gutter |
| action button | at the end of the list, reached by scrolling | pinned 56 dp bar above the nav area; list ends with bar-height padding |
| help line | "A target size fits every page. Without one, quality is used." | "Choose a level, or type the size you need." |
| Batch compress | Light / Medium / Strong chips | the same four rows |
| Hindi button | आगे | जारी रखें |

## What the lead should check on the phone (Infinix, EN + HI)

1. Compress: Continue is visible on arrival and stays put while the list scrolls underneath; the
   last document row can be scrolled fully above the bar; keyboard up does not hide the bar.
2. Hindi (A/अ): "अपनी ज़रूरत का आकार (चाहें तो), जैसे 500 KB" fits the field, the help line reads
   "स्तर चुनें, या जितना आकार चाहिए वह लिखें।", the button says "जारी रखें".
3. Batch tools > Compress shows the same four levels and the same hint.
4. A locked document: paper tile + gold lock, tapping opens the Unlock sheet as before.
5. Decide the one remaining Hindi mismatch (shared `compress_level_*`): सबसे अच्छी क्वालिटी /
   संतुलित → the glossary's सबसे साफ़ / बीच का. It lives in `strings.xml` + `CompressFlow`, outside
   this lane.
