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
   - Grid: 4 columns normally, 3 at fontScale >= 1.25, 2 at >= 1.6, so big labels keep two
     lines instead of being clipped on a 360dp phone.
   - Tile min height 104dp (was 96dp), spacer cells match.

3. **Proof renders** (Robolectric NATIVE, temporary shot test, deleted before commit) showed
   the badge clipped by the container in the first attempt (negative offset) and the label
   vertically centred; both were fixed as described. Visual result checked at 1.0x and 1.3x.

## Tests

New `app/src/test/java/com/scanwhirl/app/ui/shell/ToolsLookTest.kt` (English + Hindi
`@Config(qualifiers = "hi-w360dp-h740dp-xhdpi")`, both at 360dp; the label audits run at
1.0x and 1.3x):
- container is 48dp and its top is >= 12dp below the tile top; glyph 26dp (>= 24dp);
  label 12-13sp; label hugs the container (6-12dp); group centred; tile >= 104dp.
- PRO badge: says PRO, >= 12sp, does not intersect the glyph bounds, keeps clear of the
  tile's top edge and stays inside the tile.
- no SOON tile in the hub, every shipped tool has a tile with an action, 4 columns at 1.0x.
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

Red evidence (first runs of this work): `ShellLookTest > the six quick tools fit 360dp with
one-line labels :: Compress stays on one line expected:<1> but was:<2>` and `ShellProGateTest
> ... batch_rename tile is missing`; the rendered PNGs showed the PRO badge cut off at the
container's top edge and labels vertically centred in their box. All fixed before the final
green run.

## Gate

Last line: `GATE PASS uiicons tests=1780 failures=0 skipped=0 lint=[skipped]`
(lint runs in the cloud on integration merges).

Commits:
- `1e9eae3` uiicons: one tool-tile geometry everywhere ... Tools tab drops Soon tiles

## Not done / for the lead

- The Home quick row is six fixed columns on a 360dp phone (LibraryScreen, not this lane).
  At 12.5sp "Compress" would not fit, so that variant shrinks the label to fit one line
  (floor 9sp, ~10.5-11sp in practice). If the owner wants a true 12.5sp label there, the row
  needs 5 columns — a LibraryScreen change.
- Stamp editor tiles now use the same geometry, so its tool strip is ~110dp tall instead of
  ~68dp (HorizontalScrollView, scrolls, nothing clipped).
- `SwStyle.sectionHeader` still uppercases for other screens (Me/Vault etc.); only the Tools
  tab was in scope.
