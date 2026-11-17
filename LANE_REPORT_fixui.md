# LANE_REPORT_fixui — owner fixes F6, F7, F8, F9, F14, F20, X3

Branch `lane/fixui`. Status: all six items done, full gate PASS, committed. Not merged, not pushed.

## Done (each with a new `*FixUiTest` written red first, then green)

### 1. F6 + F20 + F7 — dead tiles removed from `ToolRegistry`
Removed entirely (not "Coming soon"): `book_pages`, `photos_pdf` (Photos to PDF),
`business_card`, `duplicate`, `enhance`, `bw`. Every other tool is unchanged.
`ToolsHubScreen` needed no change (it renders the registry); `util/Prefs.kt` needed no change
(it already drops ids whose `ToolRegistry.byId` is null, and `DEFAULT_QUICK_TOOLS` holds only
live READY ids). Tests prove old saved quick lists skip the removed ids, a list of only removed
ids falls back to the shipped six, and `setQuickTools` drops unknown ids.
Code that only launched those tiles (ShellActivity's `photos_pdf`/`enhance`/`bw` branches,
`BusinessCardActivity`, `BookPagesActivity`, the `duplicate` setup branch) is now unreachable
and was left in place, as instructed (ShellActivity is another lane's file).

### 2. Counts (store text must be updated by the lead)
Derived from `ToolRegistry.all` after the removal:

| number | value |
| --- | --- |
| total tools | **37** (was 43) |
| READY | **35** |
| Coming soon | **2** (`organize_pages`, `batch_rename`) |
| `ToolRegistry.proCount()` (Pro tools on sale) | **16** (was 17) |
| tiles carrying the Pro flag | **17** (16 on sale + `batch_rename`, Pro but Soon) |
| per group | Create 5, Organize 8, Optimize 2, Convert 9, Edit 8, Secure 4, Text 1 |

`ToolRegistryTest` now asserts 37 / 35 / 2 / 16 / 17 via `ToolRegistry` (comment
`// fixui: tiles removed (owner F6/F7/F20)`), and `ShellScreenTest` expects 37 tiles.

### 3. F8 — Text (OCR) action chips breathe
`OcrEditorActivity.setupActionBar`: 8dp between chips (no trailing gap after the last chip),
12dp between the two rows, every chip keeps the 48dp minimum touch height (kit `fixChip`).
`activity_ocr_editor.xml`: the actions container keeps its original 2/4dp padding — 8/8dp made
the text pane 2px too short in `TextFitTest` at w320dp, font 1.3 (the pane has no spare height);
the spacing the owner asked for is the chip/row gaps, not outer padding.

### 4. F9 — Split output names
`ScanRepository.splitByRange` now names parts `<document name> part 1`, `part 2`, ... in page
order (ranges are sorted by start page, so typing "4, 1-2" still numbers page 1-2 as part 1).
Uniqueness keeps the existing " (2)" rule. Hindi says `<name> भाग 1` via
`R.string.split_part_word` (`values/strings_fixui.xml` = "part",
`values-hi/strings_fixui.xml` = `<!-- review -->भाग`).
`OutputNames.forSplitPart` also strips an earlier "part N" / "भाग N" so re-splitting a part never
stacks suffixes. Extract naming (`<name> - Pages 1-3`) is unchanged: only split was in scope.

### 5. F14 — Signature pad sheet cannot be dragged away
`SwSheet.lockDrag()` (new): `behavior.isDraggable = false`, `isHideable = false`,
`setCancelable(false)` (Back and outside tap no longer close it) and the grab bar is hidden.
Applied only to the signature pad sheet in `StampPrompts.pad`; it closes through its X or its
Save (Done) button. `SignaturePadView` keeps claim-the-gesture behaviour. Other sheets stay
draggable.

### 6. X3 — stale ID-card branch in ToolSetupActivity removed
Deleted the whole second implementation: the front/back pickers (`toolSetupFront`,
`toolSetupBack`), `idCardSource`/`sideView`, the paper-size option (`buildIdCardOption`,
`IdCardPrefs`/`IdCard.compose` run branch) and the `fail(need_back)` path. ToolSetupActivity for
`id_card` now falls through to the generic "nothing here" message; no tile routes there —
`ToolLaunch` sends `id_card` to `IdCardActivity` (test asserts that). `IdCardActivity` is
untouched (another lane's file).

## Tests

New: `app/src/test/java/com/scanwhirl/app/...`
- `ui/kit/TilesFixUiTest.kt` — removed ids absent, kept ids present, groups non-empty, counts.
- `ui/shell/QuickToolsFixUiTest.kt` — saved quick lists skip removed ids, defaults valid.
- `ui/OcrChipSpacingFixUiTest.kt` — 8dp chip gaps, 12dp row gap, 48dp targets, row membership.
- `store/SplitNamingFixUiTest.kt` — part numbering, unsorted input, no suffix stacking, Hindi "भाग".
- `ui/stamp/SignatureSheetFixUiTest.kt` — not draggable/hideable/cancelable, grab hidden, X and
  Done both work (Done saves the drawn stroke).
- `ui/tools/IdCardSetupFixUiTest.kt` — no front/back pickers, no back-image demand, tile opens
  `IdCardActivity` only.

Updated (count/expectation lines only, comment `// fixui: tiles removed (owner F6/F7/F20)`):
`ui/kit/ToolRegistryTest.kt`, `ui/shell/ShellScreenTest.kt`.

## Out-of-scope files that had to change (flag for the lead)
These are tests outside my file list that hard-coded the removed tiles or the old split names;
without them the gate could not pass. Each change is minimal and marked
`// fixui: tiles removed (owner F6/F7/F20)`:
- `app/src/test/java/com/scanwhirl/app/store/ToolsEngineTest.kt` — old split names
  ("Contract - Pages 1-2") → "Contract part 1/2".
- `app/src/test/java/com/scanwhirl/app/ui/book/BookPagesActivityTest.kt` — "Book pages is Coming
  soon" → "Book pages is gone from the catalogue" (the activity itself still has its tests).
- `app/src/test/java/com/scanwhirl/app/ui/shell/ShellQaFixesTest.kt` — used the removed
  `enhance` tile to open the document chooser; now uses `to_text` (same chooser, still free).
- `app/src/test/java/com/scanwhirl/app/ui/tools/ToolsUiTest.kt` — dropped `business_card` and
  `duplicate` from its tile-launch list (their activities keep their own flow tests).
No data-safety test was deleted or weakened.

## Left in the code on purpose
Removed tiles' strings (`tool_book_pages`, `tool_photos_pdf`, `tool_business_card`,
`tool_duplicate`, `tool_enhance`, `tool_bw`, `soon_book_pages`) and the activities they opened
stay in the project (unused, harmless; deleting them is another lane's call).

## Gate
- Full gate: `GATE PASS fixui tests=1647 failures=0 skipped=0 lint=[skipped]`
  (cloud run 44192938436, snapshot 85f54dd9).
- Earlier red/green runs: 19 tests / 13 failures seen red for the six new classes, then green.

## Commits
- `0344013` fixui: remove dead tiles, part names, OCR chip spacing, signature sheet lock,
  ID card setup (F6/F7/F8/F9/F14/F20/X3)

## Lead / device checks
- Store text: "37 tools", "16 Pro tools" (was 43 / 17).
- Device: draw a signature starting at the very top of the pad (must keep drawing, sheet must not
  move) and confirm X + Save still close it; glance at the OCR chip rows; split a 5-page doc and
  check the file names.
