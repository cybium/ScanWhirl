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
2. **Category pill is calm metadata** — sentence case (`● Study`, `● Unsorted`, never caps),
   12 sp, normal weight, `letterSpacing=0`, neutral ink (`text_hologram_mid`); only the dot keeps
   the category colour. It is still the 48 dp touch target that opens the category picker.
3. **Meta line** — `3 pages · 544 KB` (new `plurals viewer_meta`, composed with
   `StatusLine.formatBytes` so sizes agree with Home and the result screens); 13 sp, tabular
   digits, one line.
4. **Title** — the header name wraps to two lines (18 sp bold), then ellipsises; the app bar grows
   only when the second line is needed (`wrap_content` + `minHeight="?attr/actionBarSize"`).
   Styled in `styleDocTitle()` because Toolbar owns its title TextView.
5. **Page grid scrollbar** — `activity_document_viewer.xml` sets `scrollbars="vertical"`,
   `fadeScrollbars="false"`, `scrollbarStyle="insideInset"` on `PageGridView`, so a long document
   always shows that it continues. Page badges were already 12 sp white on `bg_chip_void`; a test
   now locks that in (no change to the shared `item_page_grid.xml`).

## Files

- `app/src/main/res/layout/activity_document_viewer.xml` (the document view's layout)
- `app/src/main/java/com/scanwhirl/app/ui/DocumentViewerActivity.kt` (header/meta/dock-comment code only)
- `app/src/main/res/values/strings_uiviewer.xml` (new: `plurals viewer_meta`)
- `app/src/main/res/values-hi/strings_uiviewer.xml` (new, `<!-- review -->`)
- `app/src/test/java/com/scanwhirl/app/ui/DocumentViewerReaderUiTest.kt` (new, 11 tests)
- `app/src/test/java/com/scanwhirl/app/ui/Phase5ScreensTest.kt` (old dock/chip shape assertions
  rewritten to the new design, each marked `// v2 UI: 07_reader_dock`)
- `app/src/test/java/com/scanwhirl/app/ui/UiSmokeTest.kt` (the `"2 pages"` meta assertion updated to
  `"2 pages · <size>"`, marked `// v2 UI: 07_reader_dock`)

Not touched: `ToolSetupActivity.kt`, `LibraryScreen.kt`, `item_page_grid.xml`, `themes.xml`.

## Tests added (red first — 9 of 11 failed on the old screen)

`DocumentViewerReaderUiTest` (Robolectric, 360 dp wide, sdk 35):
1. dock child order is btnEdit · btnText · btnMore · btnShare, with the right labels;
2. every item weight 1 and the four widths equal within 1 dp;
3. every item >= 56 dp tall and keeps the 56 dp minimum;
4. no dock label has `lineCount > 1`, is ellipsised or maxLines != 1 — EN;
5. the same in HI (`@Config(qualifiers = "hi-w360dp-h640dp-xhdpi")`, "शेयर करें" fits);
6. category pill is `● Study`, not all-caps, 12 sp, neutral ink;
7. Hindi pill is sentence case too (`● ` + the values-hi label);
8. title `maxLines == 2` + ellipsis END;
9. a 90-character name really uses 2 lines and stops;
10. meta line is exactly `3 pages · <formatted size>`;
11. a 12-page grid has a vertical scrollbar that does not fade, and page badge 1 is 12 sp white
    on its chip.

## Gate

Last line: `GATE PASS uiviewer tests=1779 failures=0 skipped=0 lint=[skipped]`
(cloud run 44288330171)

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
- Category pill: tap it — the category picker still opens; the label is now `● Study` style, not CAPS.
