# LANE_REPORT — uipicker (tool setup page, visible UI)

Owner's device screenshot of the shared tool setup page: plain name cards with no page image, an
ALL-CAPS "SOURCE" label, a "Medium" chip clipped into "Mediu / m", and Compress offering
Light / Medium / Strong while the document's compress sheet says Best quality / Balanced /
Smaller / Smallest. This lane makes **every tool that uses `ToolSetupActivity`** (split, extract,
merge, zip, compress, pdf_to_images, long_image, to_word, to_powerpoint, to_excel,
remove_metadata, duplicate) look and read like the document screens.

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

- `app/src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt` — rows, headings, chips,
  compress levels, scrollbar, estimate plumbing.
- `app/src/main/res/values/ids_tools.xml` — new ids only (`toolSetupSection`, `toolSetupDocCheck`,
  `toolSetupDocThumb`, `toolSetupDocName`, `toolSetupDocMeta`, `toolSetupLevels`,
  `toolSetupLevelLabel`, `toolSetupLevelSize`, `toolSetupLevelCheck`, `toolSetupLevelBest`,
  `toolSetupLevelBalanced`, `toolSetupLevelSmaller`, `toolSetupLevelSmallest`). Old unused level
  ids are left in place (harmless, other screens share the file).
- `app/src/main/res/values/strings_uipicker.xml` + `values-hi/strings_uipicker.xml` — two NEW labels.
- Tests: `app/src/test/.../ui/tools/ToolSetupPickerUiTest.kt` (new),
  `ToolsUiTest.kt` and `ToolsPluralsTest.kt` (adjusted to the new row shape, each marked
  `// v2 UI: 03_tools_setup_picker`).

## Hindi

No invented vocabulary: the compress levels reuse the existing `compress_level_*` Hindi values.
Two new labels were added EN + HI with `<!-- review -->` (owner approves wording separately):

| key | EN | HI (reused words) |
|---|---|---|
| `tools_pick_choose_doc` | Choose a document | दस्तावेज़ चुनें |
| `tools_pick_custom_size` | Custom size (optional), e.g. 500 KB | लक्ष्य आकार (वैकल्पिक), जैसे 500 KB |

Hindi renders were inspected: "सबसे अच्छी क्वालिटी" and "सिर्फ़ तालिकाएँ" sit on one line with the
size still readable on the right.

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

## Gate

- `GATE PASS uipicker tests=1776 failures=0 skipped=0` (cloud, run 44289078314).
- Targeted green runs before it: ToolSetupPickerUiTest 8/8, ToolsUiTest 13/13,
  ToolsPluralsTest 3/3, LockedSourceTest 2/2.

Commit: `e02ea98 uipicker: tool setup page shows real page thumbnails, sentence-case headings, the
compress sheet's four levels and chips that never clip` (+ this report).

## Before → after

| | before | after |
|---|---|---|
| source row | name + right-aligned meta, ~50 dp tall | 56 × 79 page thumb, name (2 lines) over meta, 76+ dp, lock badge |
| heading | `SOURCE` / `OPTIONS` all caps + tracking | `Choose a document` / `Options`, sentence case |
| compress | three half-width chips Light / Medium / Strong, 40 dp, "Medium" clipped | four full-width rows Best quality / Balanced / Smaller / Smallest, each with its size, 48 dp, check + tint |
| Tables only / All text | two half-width chips, 40 dp | two full-width 48 dp rows |
| long list | scrollbar faded away | scrollbar always visible in the right gutter |

## What the lead should check on the phone (Infinix, EN + HI)

1. Compress: thumbnails visibly different per document and matching the library cards; tap rows to
   see the tint + check move; the four level rows' sizes change when another document is picked;
   no clipped label at the default and largest font size.
2. Hindi (A/अ): "सबसे अच्छी क्वालिटी", "संतुलित", "सिर्फ़ तालिकाएँ", "लक्ष्य आकार (वैकल्पिक), जैसे
   500 KB" all on one line.
3. Merge (two checks, order visible) and a long library (scrollbar thumb stays visible while
   scrolling; thumbnails load without a stutter on a 30+ document library).
4. A locked document: paper tile + gold lock, tapping opens the Unlock sheet as before.
5. Wording approval for the two new Hindi values above.
