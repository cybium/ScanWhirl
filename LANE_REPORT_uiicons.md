# LANE uiicons — report

Owner critique (2026-11-18): tool icons nearly touched the top edge of their box and read
smaller/lighter than the label under them. One approved geometry now applies to the Tools
tab, Home quick tools and the stamp editor's tool row.

## Done

1. **ToolTile rework** (`ui/kit/ToolTile.kt`, both variants)
   - 48dp tonal rounded-square container, 14dp radius, `glass_surface_high` (#222C32).
   - 26dp accent glyph centred in the container (11dp inset all round).
   - Label below: 12.5sp **semibold**, max 2 lines, `minLines = 2` so rows stay aligned,
     centred horizontally and **top** aligned (Gravity.CENTER had pushed one-line labels
     to the middle of their two-line box).
   - Container top is 12dp + the badge band below the tile top (`>= 12dp` required).
   - PRO badge: 12sp bold gold, the word PRO, on the container's **top-right corner**
     (bottom edge 8dp below the container top, so it never reaches the glyph), inside the
     tile and clear of its edge. The needed band is real layout space measured from the
     badge itself, so system font scale grows the tile instead of clipping the badge.
   - Quick variant (`asQuick`) keeps the same container/glyph/12.5sp cap; it only drops the
     side padding and fits the label to its six-column width in `onMeasure` (never below
     9sp) so "Compress" stays one line. Nothing in `LibraryScreen.kt` was edited.

2. **Tools tab** (`ui/shell/ToolsHubScreen.kt`)
   - Coming-soon (`ToolStatus.SOON`) tools are not rendered at all (no Soon tiles, UI_PLAN
     §0b), including in search results; a group left empty is skipped.
   - Section headings: sentence case, 16sp semibold, count removed (was `sectionHeader`,
     which uppercased to ALL CAPS with an 12sp count).
   - Grid: 5 columns normally, 3 at fontScale >= 1.25, 2 at >= 1.6, so big labels keep two
     lines instead of being clipped on a 360dp phone.
   - (superseded by the density fix below: no fixed tile height, tighter cards.)

3. **Proof renders** (Robolectric NATIVE, temporary shot test, deleted before commit) showed
   the badge clipped by the container in the first attempt (negative offset) and the label
   vertically centred; both were fixed as described. Visual result checked at 1.0x and 1.3x.

## Tests

New `app/src/test/java/com/scanwhirl/app/ui/shell/ToolsLookTest.kt` (English + Hindi
`@Config(qualifiers = "hi-w360dp-h740dp-xhdpi")`, both at 360dp; the label audits run at
1.0x and 1.3x):
- container is 48dp and its top is >= 12dp below the tile top; glyph 26dp (>= 24dp);
  label 12-13sp; label hugs the container (6-12dp); group centred; tile is content-sized
  (12 + 48 + 8 + label + 8) with no fixed minimum height.
- PRO badge: says PRO, >= 12sp, does not intersect the glyph bounds, keeps clear of the
  tile's top edge and stays inside the tile.
- no SOON tile in the hub, every shipped tool has a tile with an action, 5 columns at 1.0x.
- headings equal the group strings (sentence case, not ALL CAPS in EN), 16sp.
- every hub label: <= 2 lines, no ellipsis, no overflow, not clipped, 12-13sp, at 1.0x and
  1.3x, EN and HI; 3 columns at 1.3x.
- quick row: labels one line, no ellipsis, inside their column and not clipped at 1.0x/1.3x.

Existing UI-shape tests updated to the new design (each with a `// v2 UI:` note where the
assertion changed):
- `ShellScreenTest`: tile count = shipped tools (35), search results filtered of SOON.
- `ShellLookTest`: hub count = shipped tools.
- `ShellProGateTest`: only Pro tools that ship are expected as hub tiles.
- `ShellQaFixesTest`: tag-vs-glyph rects compared in `iconSlot` coordinates.

- Test `ToolsDensityTest` (new, 360x780dp, EN, 1.0x): tiles fully visible on the first
  Tools screen >= 13 (was 9), no label over 2 lines, no ellipsis, no clipping; asserts the
  tile is content-sized (12 + 48 + 8 + label + 8) and has no fixed minimum height.

Red evidence (first runs of this work): `ShellLookTest > the six quick tools fit 360dp with
one-line labels :: Compress stays on one line expected:<1> but was:<2>` and `ShellProGateTest
> ... batch_rename tile is missing`; the rendered PNGs showed the PRO badge cut off at the
container's top edge and labels vertically centred in their box. Density round:
`ToolsDensityTest` first run showed 9 tiles visible (the lead's report), then 11 after the
tile fix, 13 after the 5-column/tight-card fix.

## Density fix (lead visual check, 2026-11-18, second round)

Lead: "far too much vertical empty space; each tile row ~125dp, only ~9 tools visible".

- `ToolTile`: no minimum height any more. A tile is exactly its content:
  12dp top space + 48dp square + 8dp + label (1-2 lines) + 8dp = **~93dp one line,
  ~110dp two lines** (was ~125dp for every tile). The PRO badge's band comes out of the
  12dp top space, so the tile does not grow for it at 1.0x.
- Label: 12sp semibold (was 12.5), line spacing 1.0, no side padding so the column's full
  width is available. Rows may differ in height (one-line vs two-line) as the lead allowed.
- `ToolsHubScreen`: grid is now **5 columns** at 1.0x (4 at 1.25-1.6x, 3 at >=1.6x... see
  `columns()`: 5 / 3 / 2), so the five-tool Create group is one row and no longer leaves an
  empty row. Card: 8dp top/bottom, 0 horizontal padding (the 48dp squares own the gutters),
  8dp header-to-grid gap, 6dp between rows, 12dp between cards, 8dp card margins. The Tools
  title bar and search field were tightened by ~16dp (6/2 bar padding, 4/8 search margins).
- Measured at 360x780dp (780 - the 100dp dock = 680dp of tab): **13 tiles** fully visible
  (Create 5, Organize 6, Optimize 2) versus 9-11 before. Tiles are 93dp (one line) and
  109.5dp (two lines); body starts at 102.5dp.

### 14 tiles: not reachable at 360x780, here is the arithmetic

The 14th tile would be the first tile of the *Convert* card. That card's row starts at
816dp: 102.5 (title + search) + Create 139 + 12 + Organize 254.5 + 12 + Optimize 139 + 12 +
16 (card top + header) + 109.5 (row) = 816.5, i.e. 136dp below the 680dp fold.

Rows are all-or-nothing (a row is 93-110dp and its tiles are fully visible or not), so the
count jumps 13 -> 18, never through 14. The smallest possible layout that shows 18 tiles
still needs ~714dp: 5 rows x ~93 + 4 headers (112) + 4 card paddings + gaps, with **no**
title bar and **no** search field. Even the lead's own ~84dp tile and 16dp card padding
gives 676dp for 4 rows = 13 tiles; a 5th row adds ~136dp.

Options that would reach 14+ (each is a product decision, not taken here):
1. drop the "Tools" title bar (saves 44dp) *and* the search field (58dp) — 18 tiles, but
   search must stay per UI_PLAN item 10;
2. move the Optimize (2 tools) group after Convert so the third visible row is Convert's
   (16 tiles at ~666dp) — changes the approved group order;
3. shorten the 100dp bottom dock (ShellActivity, another lane's file) — the dock is a third
   of the missing space;
4. merge the small Optimize group into the Organize card (8 tiles, same 2 rows) — saves its
   card chrome (151dp) and shows 16 tiles, but loses the "Make it smaller" heading.

The test asserts the honest ceiling (>= 13) and still passes on any taller screen or
smaller dock; it is named `the first tools screen is dense enough to show thirteen tiles`.

## Gate

Last line: `GATE PASS uiicons tests=1781 failures=0 skipped=0 lint=[skipped]`
(lint runs in the cloud on integration merges).

Commits:
- `1e9eae3` uiicons: one tool-tile geometry everywhere ... Tools tab drops Soon tiles
- `004adb4` uiicons: lane report
- `0307d2f` uiicons: content-sized tiles, five-column grid, tight cards (13 tiles at 360x780)

## Not done / for the lead

- The Home quick row is six fixed columns on a 360dp phone (LibraryScreen, not this lane).
  At 12sp "Compress" would not fit, so that variant shrinks the label to fit one line
  (floor 9sp, ~10.5-11sp in practice). If the owner wants a true 12sp label there, the row
  needs 5 columns — a LibraryScreen change.
- Stamp editor tiles now use the same geometry, so its tool strip is ~110dp tall instead of
  ~68dp (HorizontalScrollView, scrolls, nothing clipped).
- `SwStyle.sectionHeader` still uppercases for other screens (Me/Vault etc.); only the Tools
  tab was in scope.
