# LANE_REPORT_L5 — Edit & sign, Organize draft, Mask Aadhaar

Branch `lane/L5-stamp`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L5`, 2026-11-15.

## Latest batch (phone QA 3b + earlier follow-ups)

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

- New/updated for this batch: `OrganizeDraftUiTest` (11: organize tool chooser, simulated drag,
  selection ⋯ actions + duplicate, insert, discard/save/replace, process death),
  `AadhaarMaskTest` (7: line/word/merged element/QR), `InkExtractTest` (5: gradient, blue, tight trim,
  frame, blank), `MaskAadhaarFlowTest` (2: line and element masks, original untouched, OCR gone),
  `StampEditorActivityTest` (first Back pair, tool-row padding), `PageStampPictureTest`,
  `SignatureStoreBitmapTest`, `SignatureDeleteTest`, `ScanReviewBackTest`, `CropPanelTest`.
- Full suite: **1315 tests, 0 failures, 0 skipped** on the merged tree (final run below).

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

## Last gate

```
tests: total=1315 failures=0 skipped=0
GATE PASS L5 tests=1315 lint=[lint skipped (lead runs it at merge)]
```
