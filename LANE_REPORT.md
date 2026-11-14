# LANE_REPORT — L5 Edit & sign / Organize draft

Wave 2 session 2026-11-14. Branch `lane/L5-stamp`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L5`.

## Done — PM-2.2b draft engine

- **`store/DraftStore.kt` (new)** — `drafts/<id>/draft.txt` manifest over the immutable base:
  header, base id + page digest, created/updated/counters and one `p=<source>\t<sha>\t<r_n.jpg|->\t<r_n.orig|->`
  line per page. Strict validation (never a partial draft), unreferenced `r_*` pruned on load,
  atomic manifest replace, discard renames the folder aside first.
- **Draft API at the END of `ScanRepository.kt`** — `draftOf`, `draftPages`, `draftRotate`,
  `draftDelete`, `draftMove`, `draftCrop`, `draftFilter`, `discardDraft`, `saveDraftAsNew`
  (full copy or extract subset) and `replaceWithDraft`, all under one `DRAFT_LOCK`.
- **Lease (I7)**: `requireEditable` refuses in-place page edits while a draft exists;
  `protectPdf` refuses too. `requireUnlocked` is unchanged, so OCR PDF refresh stays allowed.
- **`buildAndPublish(seal)`** — seal file (`OpStaging.SEAL`) written after verification, before the
  rename. Replace = two renames under `PDF_LOCK → OcrStore → TRASH_LOCK`, with
  `previous=<token>` in the Trash marker. A death between them raises `PublishPending` and leaves the
  sealed staging; recovery rolls it forward.
- **`trashUnderLocks`** extracted (behaviour identical; `TrashTest` unedited) and
  `TrashedDocument.previousToken` parsed. `OcrStore.putAll` carries base OCR for untouched pages
  (I9: replaced pages lose text and get `ocr_pending`).
- **`recoverOperations`** (order): roll forward sealed Replace → finish `.publish` in documents
  (discard the matching draft, delete the seal) → drop `.discard-*` and drafts whose base is gone
  from docs **and** Trash (only manifests we can read) → sweep staging. Returns roll-forward + sweep
  count, so MainActivity refreshes when a Replace is finished.
- MainActivity startup refreshes the list when recovery rolled a Replace forward.
- Strings in `values/strings_draft.xml` + `values-hi/strings_draft.xml` (Hindi starts
  `<!-- review -->`).

## Done — PM-2.2c Organize screen

- Page edits (rotate/delete/move/reorder/extract-from-selection) write to the draft; opening creates
  nothing. `draftBar` shows `Draft · N pages · K changes` with Discard / Replace / Save as new.
- Back prompt is a kit `SwSheet`: Save as new copy · Discard (X = keep editing). Replace confirmation
  and Discard confirmation are `SwSheet`s. Save-as-new name uses `SwSheet.setInput`.
- Damaged/stale drafts show their message with a single Discard action.
- Trash rows show `‹name› (previous version)` for a Replace entry.
- **Owner revision of the Organize grid**:
  - clean tiles (page number only); no drag-handle box; tap opens the page;
  - long-press enters selection mode and selects that tile; round check (kit cyan) only in selection
    mode; top bar `N selected` with close (X) and Select all;
  - reorder is `ItemTouchHelper` long-press drag in normal and selection mode, one `onDropped`;
  - bottom selection bar, kit style: Rotate · Delete · Extract · Share · ⋯. Delete asks in a
    `SwSheet`; ⋯ has Rotate left and, for 10+ pages with one selected, Move to… (typed page number);
  - Share exports the selected draft pages as images (`ShareUtils.shareImages`).
- `extractedName()` extracted for both the plain extract path and draft extract.

## Done — PM-2.3b full-page editor

- `PageViewerActivity` crop/filter/rotate/delete write to the draft; staging mode is untouched.
- Filters always start from the unfiltered version; `Original` returns a pure base reference, so OCR
  carry-over works again.

## Done — crop panel phone QA (run 1, 022/023)

- **Presets reshape the frame**: `CropView.aspectRatio` setter fits the largest rect of that ratio
  inside the image, centred on the current frame and clamped (Free = no reshape).
- **Eight handles**: 4 corner dots + 4 edge bars, kit cyan, 24dp touch radius; corners and edges drag
  in image pixels; a preset keeps its ratio while dragging.
- **Preset row**: `clipToPadding=false` + 28dp end padding, so `Visa 5:7` is no longer clipped.
- **Crop mode chrome**: the page dock, close button and page indicator are hidden while cropping;
  only Cancel / Apply plus the preset row remain. Restored on cancel/apply.

## Tests added / updated

- `DraftEngineTest` (11) — base untouched; crash at `draft:saved`/`draft:replacement_written` and
  pruning; discard isolation; Save as new (rotated size, OCR carry, I1/I4/I10); Replace (same id,
  byte-identical meta, one previous version in Trash, restore byte-identical, I8); roll-forward after
  `replace:base_trashed` and sealed death; lease (rotate + protect refused); stale base keeps the
  draft; OCR carry-over/pending after Replace; recovery keeps a draft whose base is in Trash and drops
  one whose base is gone; locked document cannot start a draft.
- `OrganizeDraftUiTest` (7) — rotate leaves the base byte-identical; Back sheet has Save/Discard;
  Discard restores; Save as new; Replace + previous-version Trash label; long-press/selection bar
  rotate+delete act on the draft; reopening after process death shows the draft.
- `PageEditorDraftTest` (5) — crop in the viewer writes the draft; filters never stack, Original
  returns to the base; rotate/delete write the draft; crop+discard; saved copy cropped with its
  original.
- `CropPanelTest` (6) — preset aspect + clamping; corner and edge handles move the frame; aspect kept
  while dragging; 8 handles in view; crop mode hides the toolbar; preset chip reshapes the frame and
  the row has end padding.
- Rewritten old UI-shape tests with `// v2 UI:` notes: `Phase5ScreensTest` long-press selection,
  `UiSmokeTest` grid drag + viewer toolbar test. Card-mandated rewrites: `UiSmokeTest` page-viewer
  delete/crop, viewer delete/undo/reorder.
- Full suite green: **1118 tests, 0 failures, 0 skipped** (final merged tree, two consecutive runs).

## Not done / notes for the lead

- I edited `ui/MainActivity.kt` (startup recovery refresh) and `util/ShareUtils.kt`
  (`shareImages` for the selection Share) beyond the stated owner list; both are small and required by
  PM-2.2b §5.4 / the owner's Share action. No protect/RC4 path was touched in DocumentViewer.
- `moveToTrash` writes the `previous=` marker best-effort, like the rest of the marker.
- Damaged drafts are never deleted by recovery (only parsed drafts whose base is gone); the user gets
  the Discard sheet in the editor.
- Card Gate G's old totals are obsolete; use the gate line below.

## Device checks (lead)

1. Organize: long-press a page → check + top bar; tap tiles to toggle; Select all; X leaves.
2. Drag a tile (normal and selection mode) → drop reorders the draft; Home order unchanged until
   Save/Replace.
3. Selection bar Rotate/Delete (SwSheet)/Extract/Share/⋯; Share opens the sheet with the selected
   images; Move to… on a 10+ page document.
4. Draft bar: force-stop and reopen → draft intact; Discard; Save as new; Replace → Trash shows
   “(previous version)” and restores.
5. Crop: pick ID card/Passport/Visa → frame jumps to the ratio; drag corners and edge bars; Visa chip
   fully scrolls into view; no page dock/close behind the crop panel; Cancel/Apply only.
6. Full-page editor: crop/filter/rotate/delete → Organize shows K changes; Original filter restores.

## Commits

- `e542bd0` PM-2.2b draft engine (manifest, Save as new, Replace, roll-forward, lease)
- `7da3b77` PM-2.2c Organize screen on the draft (bar, sheets, previous-version label)
- `0e41523` PM-2.3b full-page editor on the draft (crop/filter/rotate/delete)
- `41ec1fb` merge integration; `529f965` Organize grid owner revision
- `976557c` merge integration; `2f980b3` crop panel QA fixes; `e260dc1` merge integration
- (this report) L5: report

## Last gate

```
tests: total=1118 failures=0 skipped=0
GATE PASS L5 tests=1118 lint=[lint skipped (lead runs it at merge)]
```
