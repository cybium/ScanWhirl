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

## Accessibility audit (commits `2dcf0ec`, `3260726`)

- **Always-on audit**: `ui/a11y/AccessibilityAuditTest` walks 21 v2 screens at 360dp and font
  scale 1.3/2.0: shell tabs, document/Organize/selection/draft bar, page and stamp editors, Text
  screen, Tools setup/result, Vault, Protect/Unlock, Scan review, insert/mask sheets, Settings.
  Rules: label, 48dp touch, WCAG contrast on composited kit surfaces, clip/ellipsis/overlap, image
  description, sheet pane title. 129 findings remain for other lanes, allow-listed with their lane
  in `app/src/test/resources/a11y_allowlist.txt` and listed with file:line + fix in
  `docs/audit/2026-11-15/ACCESSIBILITY.md` (S1 4, S2 65, S3 49, S4 11).
- **Fixed here**: Organize tiles announce “Page 3 of 10[, selected]” and expose **Move up /
  Move down** as actions that run the drag's reorder path; the tick gets a 48dp wrapper. The stamp
  canvas announces its selected mark/page and exposes **Move, bigger/smaller, rotate, delete**.
  Page-viewer images announce “Page 2 of 5”. 48dp targets and 2–3-line wrapping in the stamp tool
  row, page nav, delete/save, document selection bar, draft bar and page-viewer dock.
- `StampCanvasAccessibilityTest` and `PageGridAccessibilityTest` prove the actions and wording.

## Performance — L5-1 / L5-2 (commit `ea7486f`)

Measured with L4's `perf/PerfHarnessTest` in big mode (300 docs, one 500-page document, 6 cells):

| row | before | after |
|---|---:|---:|
| Organize grid first screen | 986 ms | 864 ms |
| open document + draftPages | 9 ms | 9 ms (tmpfs) |

Deterministic (not runner noise): **2 → 0** file stats per grid bind (`PageThumbFastBindTest`), and
**2 → 1** draft manifest parses per viewer open (`DraftStateLoadsTest`). `submit()` hashes the page
paths once; every bind reuses that revision, which is safe because page edits write a new `r_<n>`
file. `ScanRepository.draftState(id)` returns document + pages + draft from one `getDocument` and
one manifest parse and reports a damaged manifest instead of throwing; `DocumentViewerActivity`
uses it instead of `draftPages` + `draftOf`.

## Find and hide numbers — Mask Aadhaar folded into Redact (owner decision)

- Registry: `mask_aadhaar` entry removed (45 → 44 tools); its tests moved onto the Redact path.
  Redact's keywords now include the sensitive kinds so search finds it.
- **Redact editor** shows a **Find** button. Find scans every page, lists checkable groups with
  counts, and turns the tapped groups into ordinary redaction boxes the user can still move/resize;
  saving keeps the existing true-redaction copy (`stampDocument`, pixels + OCR burned, source
  untouched). Output label “Redacted” (the Aadhaar preset keeps “Masked”).
- **Detectors** (`util/SensitiveScan.kt`): Aadhaar (first 8; “whole number” option), Aadhaar VID
  (first 12, keeps last 4), PAN, cards 13–19 digits passing Luhn (keeps last 4), phone numbers
  (Indian and international), emails, IBAN (mod-97), US SSN (range-checked), long account numbers
  9–18. Precision: element boxes, 15% horizontal padding, Aadhaar/card inner edge in the gap before
  the next digit, vertical ±10% clipped against neighbouring lines. Dates, prices, slashes/commas
  and short numbers are ignored.
- **Quick path kept**: the Find sheet carries a **Mask Aadhaar** preset chip that opens the old
  number/VID/QR options.
- Tests: `SensitiveScanTest` (9: true positives with OCR noise, dates/prices/SSN-shape false
  positives, Luhn/IBAN, line bleed), `SensitiveFindUiTest` (4: groups→boxes, preset chip, no-hits
  message, registry), plus the existing `MaskAadhaarFlowTest` (2) unchanged.

## Accessibility follow-up — Text screen and Scan review fixed

Text (`OcrEditorActivity` + `activity_ocr_editor.xml`) and Scan review (`ScanReviewActivity` +
`activity_scan_review.xml`) now pass the audit at 360dp / 1.3× / 2.0×, and their allow-list
entries are **deleted**:

- Text: Page/Text switch row 48dp; language, Tidy, Copy, Share, Save and To Word chips 48dp with
  two-line labels; Undo/Redo 48dp; overflow button 48dp; footer wraps; subtitle uses the short
  `name · 1 / 3` form so it no longer ellipsizes.
- Scan review: title field 48dp with two-line room and the short visual hint “Name” (full
  “Document name” is its content description); overflow button 48dp; mode buttons keep 48dp with
  in-place insets; page status gets its own full-width row; at 200 % the tool buttons put the icon
  beside the label so the rail cannot squeeze the page stage; Save stays on screen.
- Shared helper `ui/A11yTargets.kt` (overflow target, toolbar text, title field, kit chip) is
  new L5 code.

Remaining allow-list: **43 entries** — L2: Protect sheet 10, Unlock sheet 7, Vault 7; L1:
Settings 8, Shell vault 2, Tools count 1; L4: Tool setup 5, Tool result 3. No Text, Scan review,
Library, Tools, Me or Pro entries remain (L1's own batch fixed those, or this batch did).

Move-down behaviour: a reorder must not change selection. L1's selection model keys by page path,
so the moved tile stays selected at its new slot — the **app was right**; the test had raced the
host reload and expected the old cleared state. It now waits for selection, moves, and polls for
“Page 2 of 3, selected”.

Scan review delete: delete is refused while a Focus decode is running (`pageOpBusy`, with a Toast),
so the delete test could click during the decode and never see a change. The test now waits for
`!pageOpBusy` and then polls the real completion (“Page 2 of 2”) before asserting. No assertion was
weakened.

Find plurals: the sheet says **Found 1 item / N items** and the note **Hid 1 item / N items**
(English + Hindi); no other count noun in the Find/stamp code was singular-wrong.

## Dead-button audit — owner rule “no dead buttons”

`ui/a11y/DeadButtonAuditTest` (commit `85bcc17`) walks the same 21 v2 screens in **Free and Pro**
state. For each visible, enabled, clickable view it requires a listener (or an intrinsic control:
checkbox/radio, EditText, adapter item, toggle-group child, custom canvas) and an **observable
effect** within 1.2 s of advanced looper time: an activity/result start, a sheet/dialog change
(including dismissal), a toast/snackbar, a view-state change (text/checked/selected/focus/
visibility/enabled/inputType) or the screen closing. Disabled views must be alpha ≤ 0.5. It also
declares the ids each screen's own builders must show, so a row added into a hidden container
(Settings' old About-card bug) fails as `hidden`.

Result: **no dead button in an L5-owned screen**. The audit itself first exposed three false
positives which are now understood: a snackbar is a sibling of the content root (hashed the whole
`android.R.id.content`), toggle-group buttons dispatch through the group, and a custom canvas
handles touches in `onTouchEvent`.

Remaining allow-list (`app/src/test/resources/dead_buttons_allowlist.txt`), all explained:

| screen | control | owner | why |
|---|---|---|---|
| `*` | `swNavScan` | L1 shell | Play Services scanner hand-off; nothing observable in Robolectric |
| `*` | `swNavTools` | L1 shell | already-selected tab is a deliberate no-op |
| `*` | `swToolsGrid` | L1 shell | grid/list toggle redraws the list without a view-state change |
| Tool setup | `toolSetupDocRow` | L4 | system document picker via ActivityResult |
| Vault | `vaultLock` | L2 | relock prompt is already shown on open |
| Protect sheet | `protectShare` | **L2 bug** | disabled but alpha 1.0 — owner rule says it must look greyed |
| Protect sheet | `protectSave` | **L2 bug** | disabled but alpha 1.0 |

The only real app defect found outside L5 is L2's Protect sheet: the two action buttons disable
themselves until the two passwords match, but keep full opacity, so they look tappable. Dispatched
to L2 (allow-listed with “dispatched” so the gate stays green).

Settings runtime rows: `rowOcrLanguage`, `rowBlockScreenshots`, `rowAccent` are asserted `isShown`
and pass after integration's hidden-card fix.



## Hide private info (release job — 2026-11-15)

Owner decision: drop the “Mask Aadhaar” name. Redact's Find is now **Hide private info** (sheet
title, editor button, search keywords; Hindi “निजी जानकारी छिपाएँ”). The “Mask Aadhaar” preset
chip is gone; Aadhaar appears only as an example inside the ID choice.

- The sheet first shows **choices**, all on: Phone numbers · Email addresses · ID numbers
  (Aadhaar, PAN, driving licence, passport, VID) · Card & bank numbers (cards, IBAN, account
  numbers) · Other numbers (US SSN). Find scans only the chosen kinds and lists the groups with
  counts. “Keep last 4 digits” is a separate option, **off** by default.
- ID numbers now hide the **whole** number by default; with “Keep last 4 digits” Aadhaar masks
  its first 8 and VID its first 12. Cards still keep the last 4.
- New safe patterns: Indian driving licence (`MH12 20110012345` variants) and passport
  (`A1234567`), detected as ID numbers.
- Saved copy: **“<name> - Private info hidden”**; toast “Saved a copy with private info hidden”.
  The old Mask option strings/methods and `tool_mask_aadhaar` are removed.
- Tests: `SensitiveScanTest` (whole-ID default, keep-last-4, DL/passport, kind filtering, all
  earlier positives/false positives), `SensitiveFindUiTest` (choices on by default, chosen kinds
  only, grouped counts, no-hits), plus the unchanged detector-level `MaskAadhaarFlowTest` and
  `AadhaarMaskTest`.

## Last gate (cloud)

Release job targeted run (`Hide private info`):
```
cloud: run 43733797503 (5 min)
tests: total=94 failures=0 skipped=0
GATE PASS L5 tests=94 lint=[skipped]
```
It covers SensitiveScanTest, AadhaarMaskTest, MaskAadhaarFlowTest, the whole stamp package,
AccessibilityAuditTest, DeadButtonAuditTest, ToolRegistryTest and ShellScreenTest.

Last full suite before this job: run `43712328849` — 1530 tests, 0 failures.

## Owner evaluation

`eval/L5OwnerEvalTest` now keys on the lane's own
`…\.sw-qa\owner\L5_EVAL_ON` (never the shared `EVAL_ON`, which other lanes delete mid-run) and
prints `EVAL RAN: <n> files -> <dir>` on success or `EVAL SKIPPED: L5_EVAL_ON missing` on a skip.
Latest run: **EVAL RAN: 13 files**, Aadhaar cards `find=AADHAAR:1` (this host has no ML Kit model,
so the preview geometry is the lead-approved fake line). **These previews are not evidence of
detector accuracy**: on the owner's phone with real ML Kit the Aadhaar case hid exactly the first
8 digits with no slivers and the last 4 readable. Private samples were read in place and never
committed; the flag was deleted afterwards.

## Files deleted / APK size

- Files deleted this batch: **none** (`MainActivity`, `activity_main.xml` and all legacy tests stay).
- APK size: not available in this lane (no release build here; the lead measures at merge).
