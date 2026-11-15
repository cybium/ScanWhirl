# LANE_REPORT_L5 — Edit & sign, Organize draft, Mask Aadhaar

Branch `lane/L5-stamp`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L5`, 2026-11-15.

## Latest batch — phone QA run4a (commit `1f481b6`)

- **Photo stamp/signature save+place**: `StampStore.load` required four manifest lines but the writer
  emits three, so a saved photo stamp never came back; it now reads three. A photo signature/stamp
  saves into its store **and** lands on the page as `Mark.Picture` (test seams
  `placeImportedSignatureForTest` / `placeImportedStampForTest`).
- **Cascade**: every new mark on a page is offset 6% down-right from the previous one (wraps after
  five), so consecutive stamps no longer stack invisibly.
- **Empty Save**: with no marks, `Save copy` is disabled (38% alpha) and says "Add a mark first."
  (`stamp_no_marks`) instead of silently doing nothing.
- **Insert pages position**: `insertFiles(files, position)` honours start / after N / end; the stale
  `insertHere` (always the last selection) no longer forces an append.
- **Aadhaar precision**: outer edge padded by 15% of glyph height (no first-digit leak), inner edge
  stops in the middle of the gap before the 9th digit (never past it), vertical ±10% clipped against
  other OCR lines. **VID**: a 16-digit VID masks its first 12 digits; `mask_opt_vid` toggle defaults
  ON ("Also hide the VID"), `AadhaarMask.findWords(..., maskVid)` / `find(page, maskVid)`.
- **Crop flake**: `PageEditorDraftTest` polls for the crop bitmap before Apply (30 s draft timeout)
  instead of racing the async load.
- **Ink trim**: alpha > 0.1, speck support, 1.5% edge band, 4% margin, and removal of the scanner
  edge rail (±3px isolation, outer 12%).
- **Migration**: 7 host-only classes moved to `ShellActivity` — `SaveTransactionTest` (40),
  `ImportProtectedPdfTest` (1), `OcrGalleryImageFlowTest` (4), `LongImageTest` (4),
  `DirectScanSaveTest` (5), `CategoryPickerSheetTest` (5), `ImageImportPipelineTest` (14):
  **73 tests, ~305 assertion sites kept**; changed expectations carry `// v2 UI: <screen>`.
- Gate: **1367 tests, 1 failure** — only the allowed `TextFitTest > ocr editor text fits`
  (L1-owned; L1 files untouched).

## Parked: MainActivity retirement (post-launch)

Lead decision: park. `MainActivity` is unreachable from the shell and invisible to users; the
remaining classes need shell seams that are not worth building before launch. **Nothing is deleted**
and no legacy test is weakened. Keeping `MainActivity`, `activity_main.xml` and all 18 classes.

Remaining classes still launching `MainActivity` (18 classes in 17 files):

| # | Class | Depends on |
|---|-------|------------|
| 1 | `BrandIdentityTest` | home app bar, brand mark, name text |
| 2 | `ChangePasswordSheetTest` | home overflow → settings/vault entry |
| 3 | `FreeLegacyVaultEntryPointTest` | vault banner/pill entry from home |
| 4 | `HomeCategoryActionsTest` | category chips + card overflow actions |
| 5 | `HomeFontScaleNarrowTest` | full home layout at 320dp |
| 6 | `HomeFontScalePhoneTest` | full home layout at 360dp |
| 7 | `HomeKeyboardFocusTest` | search focus/IME behaviour |
| 8 | `HomeM06Test` | status line + library rows |
| 9 | `HomeScreenTest` | `scanList` adapter rows, category rail |
| 10 | `HomeSlice2Test` | home sections/empty state |
| 11 | `ImportSheetTest` | import sheet launched from home |
| 12 | `LockUiTest` | lock badge/banner on home rows |
| 13 | `QuickSortTest` | quick-sort sheet from Tools |
| 14 | `RetrievalSlice5Test` | search results/highlight in `scanList` |
| 15 | `SelectionBarTest` | selection mode + `btnSel*` action set |
| 16 | `StatusLineScreenTest` | `statusLine` header counts |
| 17 | `TextFitTest` | measures both screens (OcrEditor case is the allowed failure) |
| 18 | `UiSmokeTest` | legacy activity graph walk |

Missing shell seams (what a post-launch migration must add):

1. **`R.id.scanList` RecyclerView + adapter** — v2 library renders plain cards into a `swDocList`
   `LinearLayout`: no adapter, no `itemCount`, no `findViewHolderForAdapterPosition`; the legacy card
   ids `scanName` / `scanMeta` / `scanSnippet` / `catChip` (`item_scan.xml`) have no v2 equivalent.
2. **Home app bar + status header** — `homeAppBar`, `headerCount`, `statusLine`, `brandMark`,
   `appNameText`, `homeRoot`, `homeContent` exist only in `activity_main.xml`.
3. **Search seams** — v2 has `swHomeSearch` / `swHomeClear` but no `R.id.searchInput` alias and no
   `searchDebounceMs` hook, so debounce and IME tests cannot drive it.
4. **Selection mode** — shell has `swSelectionBar` (Done + Share) only; legacy `selectionBar`,
   `selectionHeader`, `selCount`, `dockRoot`, `btnSelect` and the `btnSel*` set (Select all, Merge,
   Move, Extract, Rotate, More) are absent.
5. **Quick-sort / quick-actions sheet wiring** — `sheet_quick_sort.xml` (`qsChips`, `qsClose`,
   `qsDone`, `qsProgress`) exists but only `MainActivity` opens it; the shell has no entry.
6. **Public seams** — `library`, `toolsHub`, `setVaultMode(vault)`, `showDocActions(doc)` are
   private/absent on `ShellActivity`, so tests cannot drive vault mode or document-level actions.
7. **Home chrome ids** — `nudge`, `btnTools`, `chipRail`, `chipGroup`, `sortSelector`,
   `emptyState` / `btnEmptyScan`, `scanButtonDisc` were not carried over (shell uses `swSort`,
   `swHomeEmpty`, the FAB and `swQuickRow`).
8. **Settings/vault entry points** — legacy home overflow rows (`rowTheme`, `rowPrivacy`, vault
   pills) route through `MainActivity`; the shell routes them via the "Me" tab with different ids.

A naive blanket swap of `MainActivity` → `ShellActivity` produced **140 failures** (adapter/row
counts, id lookups, selection actions); building these seams is a post-launch project.

## Previous batch (phone QA 3b + earlier follow-ups)

- **Organize pages** is READY (Organize group): kit document chooser -> the Organize grid for that
  document (`DocumentViewerActivity.openOrganizePicker`). Insert pages stays READY too.
- **Drag-to-reorder fixed**: the per-cell `OnLongClickListener` (which consumed the gesture) is gone;
  ItemTouchHelper owns the long-press. `onSelectedChanged` enters selection on drag start, so a
  long-press without movement still selects, and moving drags. Tests use a real
  `ItemTouchHelper.startDrag` + adapter move/drop (`simulateDrag`).
- **Draft bar**: `Draft · 2 pages · 1 change` on its own line (plurals for pages and changes); the
  three equal-width one-line buttons are Discard · Replace · Save copy with icons.
- **Selection ⋯**: custom icon rows for Rotate left, Rotate right, Duplicate, Move to… (10+ pages,
  one selected), Insert pages here (inserts after the selection), Extract to new document.
- **Chip rails**: crop preset row (scroll + 24dp end padding on the ChipGroup) and the stamp editor
  tool row (`clipToPadding=false`, 24dp end padding) so the last item scrolls fully into view.
- **OutputNames**: `stampDocument` now names outputs with `OutputNames.forTool(base, label,
  existing)`; Mask Aadhaar uses the same with label "Masked". `PageStamp.outputLabel` added.
- **Ink trim**: bounds from alpha > 0.1, columns/rows need several ink pixels (specks ignored), a
  1.5% edge band ignores a photo frame, plus a 4% margin. Owner eval re-run: the black PNG is now
  254x98 (was 338x446); blue-on-grey and the purple stamp are unchanged.
- **Mask Aadhaar device path**: `OcrEngine.recognizeWords` returns ML Kit **element** boxes;
  `AadhaarMask.findWords` groups words into lines and masks exactly the first two 4-digit groups
  (three word boxes -> two whole boxes; a merged element split by character count). Stored line
  geometry is only the host/old fallback.
- **QR toggle**: "Also hide the QR code" in the Mask options (multi-select), using the
  high-contrast square density heuristic (`AadhaarMask.findQr`) because Play code-scanner is UI-only;
  the box is adjustable before saving.
- `LANE_REPORT.md` removed; this file is the report.

## Done earlier (same wave)

- PM-2.2b draft engine (manifest, Save as new, Replace + roll-forward, lease, OCR carry-over),
  PM-2.2c Organize screen, PM-2.3b full-page editor, CamScanner-style grid.
- Crop panel QA: presets reshape, 8 cyan handles, scrollable chips, toolbar hidden in crop mode.
- Insert pages (camera/photos/library) into the draft, start/after N/end, selection ⋯ and empty state.
- Photo ink import for signatures (vector + PNG, 5 total) and stamps (My stamps, 10), recolour
  Original/Black/Blue, `Mark.Picture` placement with move/resize/rotate.
- Back behaviour: stamp editor first Back asks when dirty, closes when clean; page-viewer Back keeps
  the draft edit; Scan Review asks Discard/Keep in staging.

## Tests

- New for run4a: `StampImportTest` (3: photo stamp save+place + cascade, photo signature saves and
  places, empty Save disabled/dimmed with the message and nothing written), `AadhaarMaskTest`
  (9: line/word/merged element/gap-precision/line-clip/VID toggle/QR), `OrganizeDraftUiTest`
  (12, incl. insert at start / after page 2 / end), `InkExtractTest` (6: gradient, blue, tight trim,
  frame, blank, edge rail), `SaveTransactionTest` etc. migrated to the shell.
- Updated for this batch: `PageEditorDraftTest` (crop race ⇒ poll), `DirectScanSaveTest` (v2 library
  refresh instead of a legacy row insert), `Deep*/Import*` host classes.
- Earlier this wave: `MaskAadhaarFlowTest` (2: line and element masks, original untouched, OCR
  gone), `StampEditorActivityTest` (first Back pair, tool-row padding), `PageStampPictureTest`,
  `SignatureStoreBitmapTest`, `SignatureDeleteTest`, `ScanReviewBackTest`, `CropPanelTest`.
- Full suite: **1367 tests, 1 failure, 0 skipped** — the only failure is the allowed
  `TextFitTest > ocr editor text fits` (L1 owns it, L1 files untouched).

## Owner evaluation (private samples, never committed)

`L5OwnerEvalTest` runs only with `…\.sw-qa\owner\EVAL_ON` and writes to `…\out\L5\`; `EVAL_ON` was
created, the class run with `-Tests`, then deleted. Latest results: signature 254x98 black,
459x349 black, 739x404 blue, stamp 360x360 purple, all transparent on a checkerboard. Aadhaar
previews still use the lead-approved FAKE OCR geometry (`OCR=FAKE` in notes.txt): this host has no
ML Kit model; the device path uses element boxes.

## Not done / notes

- QR detection is a heuristic (no barcode-bounds API without a new library); the box is adjustable.
- Mask element grouping assumes words share a line vertically; a badly skewed photo is corrected by
  the user adjusting boxes.
- Hindi copy is marked `<!-- review -->`; no long dashes in user-visible strings.

## Last gate (cloud, full suite)

```
cloud: snapshot a9f5dd55, run 43674046705 (4 min)
tests: total=1367 failures=1 skipped=0
  FAIL TextFitTest > ocr editor text fits :: 1 text-fit problem(s) at w320dp font scale 1.0 and 1.3
GATE FAIL L5 tests=1367 failures=1 lint=[skipped]
```

The single failure is the lead-approved L1 issue; every other test passes. Lint runs at merge.

## Files deleted / APK size

- Files deleted this batch: **none** (`MainActivity`, `activity_main.xml` and all legacy tests stay).
- APK size: not available in this lane (no release build here; the lead measures at merge).
