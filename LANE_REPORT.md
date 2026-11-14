# LANE_REPORT — L5 Edit & sign / Organize / Mask Aadhaar

Wave 2 sessions 2026-11-14/15. Branch `lane/L5-stamp`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L5`.

## Done — Organize draft (PM-2.2b / 2.2c / 2.3b)

- `store/DraftStore.kt`: manifest over the immutable base (`drafts/<id>/draft.txt`), strict
  validation, atomic replace, pruning, discard; `ScanRepository` draft API at the end of the class
  (`draftOf/Pages/Rotate/Delete/Move/Crop/Filter/InsertPages`, `discardDraft`, `saveDraftAsNew`,
  `replaceWithDraft`) under one `DRAFT_LOCK`.
- Lease (I7): `requireEditable` and `protectPdf` refuse while a draft exists. Save/Replace use
  `buildAndPublish(seal)`; Replace is two renames with `previous=<token>` in Trash, rolled forward by
  `recoverOperations`. OCR carry-over for untouched pages; replaced pages lose text and get
  `ocr_pending` (I9). `TrashTest` unedited.
- Organize screen: draft bar, kit Back/Replace/Discard sheets, previous-version Trash label; owner
  revision of the grid (clean tiles, long-press selection, ItemTouchHelper drag, top/bottom kit bars,
  SwSheet Delete, share/extract/rotate on the draft).
- Full-page editor: crop/filter/rotate/delete on the draft; filters never stack; Original restores the
  base reference.
- Crop QA: presets reshape the frame (centred/clamped), 4 corner + 4 edge cyan handles (24dp),
  scrollable chip row with end padding, page toolbar hidden in crop mode.
- Insert pages (Free, Organize): camera / photos / another library document → position sheet →
  copied into the draft at the start / after page N / end; also in the selection ⋯ and the empty
  state. Inserted pages have no base source; the base is never touched.

## Done — Import signature / stamp from a photo

- `util/InkExtract.kt`: sampled decode (≤ 2000 px), local 80th-percentile paper estimate + closing,
  smooth alpha, ink hue (black/blue/other), Original/Black/Blue recolour, trimmed transparent PNG.
- `SignatureStore` stores bitmap signatures (v2 manifest + PNG) beside vector ones, 5 total.
- `store/StampStore.kt`: "My stamps", up to 10 transparent PNGs, colour and round shape kept.
- `PageStamp.Mark.Picture`: imported ink renders, moves, resizes and rotates like a signature;
  `outputName` calls it signed.
- UI: signatures sheet has "From photo" (gallery/camera) plus Draw; badges sheet has My stamps and
  Import a stamp from a photo; checkerboard preview with recolour options.

## Done — Mask Aadhaar (Free, Edit & sign)

- `util/AadhaarMask.kt`: OCR noise (O/o→0, l/I/|→1), 12-digit Aadhaar and 16-digit VID, spaces or
  not; boxes cover exactly the first 8 digits.
- New tool `mask_aadhaar`: pick a document → stamp editor seeded with redaction boxes on every page
  (stored OCR, or ML Kit recognition when missing) → adjust/add boxes → save through true redaction
  into a new copy named `<name> - Masked`; the copy's OCR text for those pages is gone and re-queued.
- Optional QR masking was left out (lead marked it optional); the user can add a box.

## Done — QA run 2 Back behaviour

- Stamp editor: first Back with unsaved marks shows the kit discard sheet; no marks closes at once.
  The text sheet now hides the IME on save so a stale keyboard cannot swallow the first Back.
- Page viewer: Back keeps the draft rotate and Organize shows `Draft · 2 pages · 1 changes`; staging
  Back keeps the staged rotate; Scan Review's Back asks "Discard this scan?" with Keep editing.

## Tests

- `DraftEngineTest` (15) — manifest/crash/prune/discard/Save/Replace/lease/stale/OCR/recovery/insert.
- `OrganizeDraftUiTest` (8) — base untouched, Back sheet, discard, save, replace, selection bar,
  insert pages, process death.
- `PageEditorDraftTest` (7) — crop/filter/rotate/delete on the draft; crop discard; saved copy;
  page-viewer Back keeps edits; staging Back keeps the rotate.
- `CropPanelTest` (6), `InkExtractTest` (3), `SignatureStoreBitmapTest` (2),
  `PageStampPictureTest` (2), `AadhaarMaskTest` (4), `MaskAadhaarFlowTest` (1),
  `SignatureDeleteTest` (1), `ScanReviewBackTest` (1).
- Rewritten old UI-shape tests with `// v2 UI:` notes (Phase5 grid, UiSmoke grid/toolbar, tool counts
  for the 44-tool catalogue).
- All owner "normal always-on" cases are covered; the masked-copy test proves the original is
  byte-identical with `dirDigest`.

## Evaluation (owner material, never committed)

`L5OwnerEvalTest` runs only when `…\.sw-qa\owner\EVAL_ON` exists and writes previews to
`…\.sw-qa\owner\out\L5\`. `EVAL_ON` was created, the class run with `-Tests`, then deleted.

- Signatures: black (338x446), black JPG (456x354), blue-on-grey (618x279, hue BLUE) — transparent
  background, blue kept.
- Stamp `images(3).jpg`: round purple seal, 363x364, background removed.
- Aadhaar: this host has no ML Kit text model, so the four previews use a FAKE OCR geometry (a
  plausible lower-middle line) as allowed; `notes.txt` says `OCR=FAKE`. On `1(1).jpg` the box covers
  `9233 6926` and leaves `4947` visible. The device path uses ML Kit boxes.

## Not done / notes

- `util/OutputNames.forTool` is not in integration; `ScanRepository.stampDocument` keeps a
  `TODO(OutputNames)` line next to `PageStamp.outputName`.
- Mask boxes are estimated inside OCR line boxes (character fraction), not per glyph; adjustable.
- Stamps have no delete UI yet (store API exists).
- Copy: no long dash in user-visible strings; English values short, Hindi values stay long.

## Device checks (lead)

1. Signature From photo (gallery + camera), recolour, place, resize/rotate.
2. Stamp import: colour kept, round, listed under My stamps.
3. Mask Aadhaar on the owner cards: first 8 digits boxed, last 4 visible; adjust; save → "… - Masked",
   solid black, OCR text gone.
4. Back behaviour (stamp editor, page viewer, Scan Review) from QA run 2.
5. Insert pages: position sheet, draft bar, Discard/Save/Replace.
6. Crop presets and all 8 handles; no toolbar behind the crop panel.

## Commits (latest batch)

- `acf1e2d` import signature/stamp ink from photos, Mask Aadhaar, owner eval, tests
- `833d510` merge integration (44-tool catalogue)
- (report) L5: report

## Last gate

```
tests: total=1250 failures=0 skipped=0
GATE PASS L5 tests=1250 lint=[lint skipped (lead runs it at merge)]
```
