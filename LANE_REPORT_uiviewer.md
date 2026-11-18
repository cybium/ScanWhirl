# LANE REPORT — uiviewer (the saved-document screen)

Owner complaint (2026-11-18): the bottom dock was Share · Edit · Text · More with Share first and
badly spaced; the header showed an ALL-CAPS "UNSORTED"/folder pill; a long document's page grid
gave no sign that it scrolls.

Status: **DONE**, full gate green.

## What changed

1. **Dock order and spacing** — `Edit · Text · More · Share`, left to right: you work on the
   document first and send it away last. Four items, weight 1 each (Share is no longer double
   width), each >= 56 dp tall (`Widget.ScanWhirl.Button.DockItem`), 24 dp icon over one 12 sp
   label, 4 dp dock inset + 4 dp padding so all four labels have room in EN **and** HI.
   Every action, id and click handler is unchanged; only order/margins/weights changed.
2. **Category pill is calm metadata** — sentence case (never caps), 13 sp, normal weight, neutral
   ink (`text_hologram_mid`); the 8 dp dot keeps the category colour. See the rework below for the
   compact shape.
3. **Meta line** — `3 pages · 544 KB` (new `plurals viewer_meta`, composed with
   `StatusLine.formatBytes` so sizes agree with Home and the result screens); 13 sp, tabular
   digits, one line.
4. **Title** — the header name wraps to two lines (18 sp bold), then ellipsises; the app bar grows
   only when the second line is needed (`wrap_content` + `minHeight="?attr/actionBarSize"`).
   Styled in `styleDocTitle()` because Toolbar owns its title TextView.
5. **Page grid scrollbar** — `activity_document_viewer.xml` sets `scrollbars="vertical"`,
   `fadeScrollbars="false"`, `scrollbarStyle="insideInset"` on `PageGridView`, so a long document
   always shows that it continues. Page identity is now a label under the paper (rework below).

## Rework after the lead's phone check (2026-11-18, same day)

Two visual faults the lead saw on the device:

1. **The category pill was a tall outlined 48 dp box.** The 48 dp touch target was the thing being
   drawn (`bg_chip_void`: fill + 1 dp cyan outline, minHeight 48 dp). Now the *visible* chip is a
   compact tonal pill — `bg_category_chip_tonal` (raised glass fill, 16 dp corners, **no
   outline**), 32 dp tall, 12 dp horizontal padding, 13 sp label, 8 dp dot (`bg_category_dot`,
   tinted with the category colour). The 48 dp tap area is a transparent container
   (`docCategoryTouch`) around it: it is the clickable view, it carries the spoken label
   (`doc_category_action` = "Category Study. Change category"), and it is centred on the same row
   as "3 pages · 816 KB".
2. **Page numbers were drawn on top of the page image.** `item_page_grid.xml` now wraps the paper
   (`AspectRatioLayout` + `pageThumb`, ring and selection tick unchanged) in a vertical
   `LinearLayout` with the number **below** it: centred, 12 sp, mid-grey, tabular digits
   (`fontFeatureSettings="tnum"`), no chip background. `PageGridView.VH.bind` paints it mid-grey,
   or accent + bold for Review's current page (the ring still marks the paper). Selection, drag,
   long-press and the a11y reorder actions are untouched.

Red evidence (temporary re-introduction of the tall pill and the on-paper number, ids kept):
`GATE FAIL uiviewer tests=11 failures=2` — "the visible chip is compact (<= 36 dp tall), was 96px"
and "page number '1' is drawn over the page image (number Rect(0, 341 - 316, 394), paper
Rect(0, 0 - 316, 421))".

The first attempt put the click on the pill itself; `AccessibilityAuditTest` rejected it (a
clickable view under 48 dp, and one with no spoken label: 6 problems). The container-as-tap-target
version is the one that ships and the audit is green again.

## Files

- `app/src/main/res/layout/activity_document_viewer.xml` (the document view's layout)
- `app/src/main/java/com/scanwhirl/app/ui/DocumentViewerActivity.kt` (header/meta/dock-comment code only)
- `app/src/main/java/com/scanwhirl/app/ui/pagegrid/PageGridView.kt` (page-number paint only)
- `app/src/main/res/layout/item_page_grid.xml` (rework: number under the paper; shared with Review)
- `app/src/main/res/drawable/bg_category_chip_tonal.xml` (new)
- `app/src/main/res/drawable/bg_category_dot.xml` (new)
- `app/src/main/res/values/strings_uiviewer.xml` (new: `plurals viewer_meta`)
- `app/src/main/res/values-hi/strings_uiviewer.xml` (new, `<!-- review -->`)
- `app/src/test/java/com/scanwhirl/app/ui/DocumentViewerReaderUiTest.kt` (new, 11 tests)
- `app/src/test/java/com/scanwhirl/app/ui/Phase5ScreensTest.kt` (old dock/chip/badge shape assertions
  rewritten to the new design, each marked `// v2 UI: 07_reader_dock`)
- `app/src/test/java/com/scanwhirl/app/ui/UiSmokeTest.kt` (the `"2 pages"` meta assertion updated to
  `"2 pages · <size>"`, marked `// v2 UI: 07_reader_dock`)

Not touched: `ToolSetupActivity.kt`, `LibraryScreen.kt`, `themes.xml`.

## Tests added (red first — 9 of 11 failed on the old screen)

`DocumentViewerReaderUiTest` (Robolectric, 360 dp wide, sdk 35):
1. dock child order is btnEdit · btnText · btnMore · btnShare, with the right labels;
2. every item weight 1 and the four widths equal within 1 dp;
3. every item >= 56 dp tall and keeps the 56 dp minimum;
4. no dock label has `lineCount > 1`, is ellipsised or maxLines != 1 — EN;
5. the same in HI (`@Config(qualifiers = "hi-w360dp-h640dp-xhdpi")`, "शेयर करें" fits);
6. the visible category chip is <= 36 dp tall while its 48 dp container is the clickable, spoken
   tap target; label 13 sp sentence case on the meta line; dot 8 dp;
7. Hindi pill is sentence case too (the values-hi label, not uppercase);
8. title `maxLines == 2` + ellipsis END;
9. a 90-character name really uses 2 lines and stops;
10. meta line is exactly `3 pages · <formatted size>`;
11. a 12-page grid has a vertical scrollbar that does not fade;
12. no page-number view's bounds intersect its page image (below the paper, centred, 12-13 sp,
    mid-grey, no chip).

## Gate

Last line: `GATE PASS uiviewer tests=1779 failures=0 skipped=0 lint=[skipped]`
(cloud run 44294234922, after the rework; red run before it was 44291955724, 2 failures)

## Hindi

Reused existing `values-hi` (`edit`, `btn_text`, `more`, `share_pdf`, `cat_label_*`). One NEW
Hindi string: `plurals viewer_meta` = `%1$d पेज · %2$s` (marked `<!-- review -->`); native review
of "पेज" (uninflected for 1 and many) is pending.

## For the lead to check on the phone

- Open a 3-page document: dock reads Edit · Text · More · Share, evenly spaced, labels not clipped;
  switch to Hindi (A/अ) and check "शेयर करें" and "टेक्स्ट" still fit on one line.
- Rename a document to a long name: the header takes a second line and only then ellipsises;
  the app bar must not clip the second line at 200 % font size.
- Open a 12+ page document: the page grid shows a scrollbar that stays visible while scrolling.
- Category pill: a small tonal pill on the meta line, no outline; tap anywhere in its 48 dp band
  (including just above/below the pill) — the category picker must open; TalkBack reads
  "Category Study. Change category".
- Page grid: every page number now sits under its thumbnail, not on the paper; the tick and drag
  reorder behave exactly as before (Review's focus ring still marks the current page).
