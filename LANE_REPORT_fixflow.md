# LANE fixflow — owner bugs F1, F5, F22, X1, X2, W2, W3, W4, W6, W7

Branch `lane/fixflow`. Not merged, not pushed.

Commit: `5809658` fixflow: ID card Keep/Discard + Back prompts, photo picker, front-only layout,
rotation state, import Back prompt, ML Kit direct save.

Last full gate line:

```
GATE PASS fixflow tests=1641 failures=0 skipped=0 lint=[skipped]
```

(cloud run 44191555120, 8 min; lint is the lead's at merge)

## Done (all 7 items)

### 1. F1 + W3 — ID card result is Keep / Discard, Back asks first
- `ToolResultActivity`: the `id_card` result shows exactly two actions, **Keep** (primary, closes
  with the card saved) and **Discard** (deletes that one card through
  `ScanRepository.deleteDocument(id)`, which also drops its preview cache). The "Save" (system
  Save-as) and "Undo" buttons are gone for this flow only; every other tool result is unchanged.
  Buttons are found in tests by view tag (`ToolResultActivity.TAG_KEEP/TAG_DISCARD`) because
  `res/values/ids_tools.xml` is outside this lane's file list.
- Back (system Back and the header arrow) asks **"Discard this ID card?"** with Discard / Keep.
  Keep only closes the question; nothing else in the library is touched.
- The ID card flow still publishes at the layout choice (the brief asks for Keep/Discard on the
  result screen, not a draft); the misleading Save/Undo are what is fixed.

### 2. W7 + X1 — one Back to Home, a cancelled capture no longer re-opens the sheet
- `IdCardActivity`: every sheet is one step. Closing a sheet with Back / close icon / outside tap
  now finishes the screen (`finish()`), so one Back from any step before the result reaches Home
  with nothing saved; there is no intermediate "ID card" screen needing a second Back.
  Steps that move on set an explicit `movingOn` flag first.
- `onCaptureCancelled()` no longer re-opens the capture sheet: a cancelled camera/picker lands on
  the ID card screen (its Front/Back slots are the retry), and one Back from there leaves.
  A new `internal captureOverride` seam lets the Robolectric test drive the camera launch.

### 3. W4 — "Pick from gallery" is the Android photo picker
- `IdCardActivity` uses `ActivityResultContracts.PickVisualMedia` (ImageOnly, single image)
  instead of `launchGalleryCrop` (Google's camera scanner). No second "Import from gallery" tap,
  no multi-select, no "Done".

### 4. W6 — front only offers only the front layout
- `showLayoutSheet()` branches on the captured sides: with no back the sheet offers one layout,
  "Front on one page" (new string), and "Both sides on one page" is not rendered at all.
  A front-only card does not overwrite the remembered both-sides layout preference (it does
  remember the paper size).

### 5. X2 — captured sides survive rotation and process death
- `IdCardActivity.onSaveInstanceState` writes the captured sides to
  `cacheDir/id_card_state/` and the state Bundle carries only a flag plus `pendingSide` (a 12 MP
  JPEG does not belong in a Bundle). On recreate the sides are read back, previews repaint and
  the flow continues at the right step (back sheet, or Layout when both sides are in). The cache
  copy is deleted when the card is created and on the next fresh open.

### 6. F5 + W2 — first Back after an import asks "Keep imported document?"
- `ShellActivity.importPdf` opens the viewer with an explicit
  `DocumentViewerActivity.intent(..., freshImport = true)` extra; the viewer is otherwise the same
  as before. The first Back (system Back or header arrow) asks **Keep imported document?** with
  Keep (primary) / Discard. Discard calls `repo.deleteDocument(id)` for that document only, which
  also deletes its `preview_<id>.pdf` cache copy, then closes. Other viewer entries keep the plain
  Back; the question is asked once per visit.

### 7. F22 — ML Kit Done saves and opens the document, no second review screen
- `ShellActivity`'s scanner result handler now calls the existing
  `ScanLauncher.saveScanned(uris, openAfterSave = true)` instead of `stageAndReview(uris)`;
  `saveScanned` keeps everything it did (durable staging, verified save transaction, OCR index,
  AutoExport, Home reveal, toast) and then opens the saved document. The app's
  `ScanReviewActivity` is no longer part of the camera scan. The photos/gallery import still uses
  `saveScanned(uris)` with the default (Home shows the new card, no jump) and `stageAndReview` is
  untouched for gallery/photo flows. A save failure still opens Review on the kept pages (M08
  fallback, unchanged).

## Tests added (all new files named `*FixFlow*Test.kt`)

- `app/src/test/java/com/scanwhirl/app/ui/tools/IdCardFixFlowTest.kt` (9 tests): W6 single layout,
  W7 Back at Layout, X1 cancelled capture, W4 picker, X2 front-only and both-sides recreation,
  F1/W3 Keep / Discard / Back question.
- `app/src/test/java/com/scanwhirl/app/ui/ImportPdfFixFlowTest.kt` (3 tests): Discard deletes the
  imported document + its two cache previews and keeps another document; Keep keeps it; an
  ordinary viewer entry keeps the plain Back.
- `app/src/test/java/com/scanwhirl/app/ui/ScanDirectFixFlowTest.kt` (1 test): scanner pages are
  saved, the new document opens, `ScanReviewActivity` is never started, nothing stays staged.

Red evidence (tests detect the old behaviour): one temporary run with the three old lines back
(`idCardFlow = false`, `freshImport = false`, `stageAndReview(uris)`) — cloud run 44189499272:
6 failures, e.g. `Keep is offered: [Done, ID card, 1 page, , Open, Share, Save, Show in library,
Undo]`, `no Keep question`, `Timed out waiting for the saved document`. Reverted immediately; the
same 21 tests are green in run 44190808735.

Existing tests updated (old behaviour encoded, each marked `// fixflow: owner bug ...`):
`IdCardFlowTest` — `front only creates one page with the front on its own page` (was: front in the
both-sides slot) and `the id card gallery opens the android photo picker` (was: goes through
`launchGalleryCrop`).

## Files touched (all inside the allowed list)

`ui/tools/IdCardActivity.kt`, `ui/tools/ToolResultActivity.kt` (ID-card actions only),
`ui/shell/ShellActivity.kt` (scanner result handler + import-PDF launch),
`tools/ScanLauncher.kt`, `ui/DocumentViewerActivity.kt` (fresh-import Back prompt),
new `res/values/strings_fixflow.xml` + `res/values-hi/strings_fixflow.xml` (Hindi values carry
`<!-- review -->`, no long dashes), plus the three new test files and the two updated
`IdCardFlowTest` tests. No build file, manifest, repository, id or other string file changed.

## For the lead to check on the device / decide

1. **The generic ID card path also changes.** `ToolSetupActivity`'s `id_card` branch opens the same
   `ToolResultActivity` with tool id `id_card`, so it now shows Keep/Discard too (consistent, and
   the misleading Save/Undo were wrong there as well). `ToolSetupActivity` itself is out of this
   lane's scope and not edited (its X3 front/back requirement is untouched).
2. **Result screen scope for the card.** Only Keep and Discard are shown for `id_card`; Open, Share
   and "Show in library" are not on that screen (per the brief's "two clear actions"). Say if you
   want Share back as a third quiet action.
3. **Header text.** The result header still says "Done" for the card; Keep is the action.
4. **Cancel lands on the card screen, not on the capture sheet** (X1). That is the "previous step"
   reading that removes the loop; the Front/Back slots are the retry. One Back from there leaves.
5. **Import PDF only.** The fresh-import Back question is passed by ShellActivity's Import PDF
   path; a protected PDF imported through `LockFlow.importProtectedPdf` keeps the plain Back
   (LockFlow is not in this lane's file list).
6. **Saved state location.** The captured card sides are kept in `cacheDir/id_card_state` while
   the flow is open (deleted after creation or on the next open), not in the Bundle, to avoid
   `TransactionTooLargeException` with phone-camera JPEGs.
7. **F22 + Home.** The camera scan opens the viewer after saving and Home still lists the new
   document with its Undo; the photos import keeps the previous "no jump" behaviour.
