# LANE fixinsertcrop — Insert pages > Photos crops the photo before the page gets it

Branch `lane/fixinsertcrop`. Not merged, not pushed. Commits `77681c3`, `43692a8`,
`d40d204` (rework after critic), `b718f55` (rework 2 after judge), report commit.

Last full gate after rework 2 (cloud run 44278874665, 7 min):

```
GATE PASS fixinsertcrop tests=1730 failures=0 skipped=0 lint=[skipped]
```

Earlier full gate after the first commit (run 44269348213, 9 min):
`GATE PASS fixinsertcrop tests=1721`. New class after the privacy commit:
`GATE PASS fixinsertcrop tests=7` (run 44271054698).

## The bug (audit A8, High)

`DocumentViewerActivity` > "Add pages from" > "From photos" copied the picked gallery
photo into `cacheDir/insert_<id>` and inserted it as a page unchanged. A photo of a page
lying on a wooden table therefore put the table on the page (the owner saw it on page 2).

## Done

### 1. Every picked photo is auto-cropped and confirmed on the corner screen

- New `ui/tools/PhotoCropFlowActivity.kt` (small, self-contained). For each picked file it
  reads it off the main thread, runs the **existing** `util/PhotoAutoCrop.crop` (i.e.
  `Deskew.detectQuad` + `Deskew.warp`, the same normalisation page straightening uses; no
  new detector), decodes a sampled copy (max 2400 px, never a full 12 MP decode) and shows
  the **same Passport/ID-card corner surface** (`ui/CropView`, view id `toolCropView`, action
  id `toolCropApply`, four corners + four edge handles, 48 dp Back). The frame starts on the
  auto-detected page edges, so confirming without dragging keeps exactly the cut/straightened
  page; the person can still drag any corner.
- Several photos are confirmed **one after another**; the header is `Photo 2 of 3`
  (`fixinsertcrop_crop_title`, `%1$d of %2$d`, only because the total is known). Confirming
  photo N writes `cropped_<N>.jpg` next to the picked copies and opens photo N+1.
- The confirmed crops are encoded (q95, off the main thread) and returned to
  `DocumentViewerActivity`, which inserts them at the position already chosen. The position
  question is asked **before** cropping, in the same order as the existing camera/PDF inserts.
- A photo without a confident quad is shown uncropped with the fallback hint
  `fixinsertcrop_crop_hint_none` ("Couldn't find the page edges. Drag the corners to the
  page."), never silently treated as cropped. Same safest-behaviour rule as fixidcrop.

### 2. Back / cancel inserts nothing and leaves the document unchanged

- System Back and the screen's own Back arrow both cancel the **whole batch**: no page is
  inserted, `DocumentViewerActivity` receives `RESULT_CANCELED`, the draft is not created and
  the document keeps its pages and digest. Confirmed by test.
- The `cacheDir/insert_<id>` batch folder (plaintext picked copies) is deleted when the flow
  is cancelled, when a photo cannot be read, when the position question is cancelled, and
  after the draft has copied the pages (`insertFiles` already deleted `insert_*` folders in
  its `finally`). No picked copy remains after the flow ends — asserted after insert, after
  Back and after a cancelled position sheet.
- A viewer recreated while the crop screen is on top (rotation/process death) restores the
  chosen insert position from saved state and inserts the returned crops there. A viewer
  recreated while the position question was open (question gone with the old screen) deletes
  the batch's picked copies instead of leaving them in the cache.

### 3. Nothing else changed

- Camera/scan insert (`GmsDocumentScanner`) and library-PDF insert still go straight to
  `insertFiles` (the new `askInsertPosition`/`promptInsertAfterPage` take an `onReady` action
  whose default is `insertFiles`, and `onAbandon` is only passed by the photos path).
- `ui/tools/IdCardActivity.kt`, `util/PhotoAutoCrop.kt`, `ui/CropView.kt`, `ScanRepository.kt`
  and `ui/tools/PassportActivity.kt` were **not** touched. No change to `draftInsertPages`.
- Duplicate-selected-pages (`repo.draftInsertPages` directly) is unchanged: only gallery
  photos picked through Insert pages get the new confirmation.

## Tests added — `app/src/test/java/com/scanwhirl/app/ui/InsertPhotosCropFixInsertCropTest.kt`

Robolectric, NATIVE graphics, `sdk = [35]`; the real flow is driven end to end
(viewer → photos picker result → position sheet → `PhotoCropFlowActivity` → result → draft):

1. `a picked photo opens the corner crop screen instead of landing on the page` — the Photos
   pick opens `PhotoCropFlowActivity` and the draft is still untouched; batch folder gone.
2. `a photo of a page on a coloured table is inserted without the table colour` — the inserted
   page has <3 % table-coloured pixels (the uncropped photo is ~70 %) and the page's average
   colour is the paper's, not the table's.
3. `Back on the corner screen inserts nothing and keeps the draft untouched` — page count and
   document unchanged, no draft, no picked copy.
4. `two photos are confirmed one after another and land in order` — `Photo 1 of 2`,
   `Photo 2 of 2`, two crops returned in order (`cropped_000`, `cropped_001`), the first photo
   is the first inserted page (page average colour), neither page shows the table.
5. `rotation keeps the current photo and the adjusted corners` — drag the top-left corner,
   `recreate()`, same photo index, same "Photo 1 of 2" header, same prepared photo (SHA-256)
   and all four normalised crop coordinates within 0.01; then Back clears the cache.
6. `cancelling the insert position keeps no picked copy` — Cancel on the position sheet leaves
   no draft and no `insert_*` folder.
7. `rotation while the insert position is asked keeps no picked copy` — a recreated viewer with
   no chosen position deletes the batch instead of leaking the photos.
8. `a confirm tap that lands while the next photo loads is ignored` (rework D1) — stale Confirm
   during photo 2's load: two unique pages, photo 1 then photo 2.
9. `rotation while the next photo loads does not reuse the previous frame` (rework D2) — photo 2
   opens with its own full auto-crop frame, not photo 1's dragged rectangle.
10. `a viewer recreated while the corner screen is on top inserts at the chosen position`
    (rework D6) — "After page 1" survives the document screen's recreation; the confirmed photo
    is inserted at position 1.
11. `a photo that cannot be read leaves no picked copy behind` (rework 2 R1) — a failing second
    read deletes the half-copied batch, nothing is inserted.
12. `finishing during the picked-photo copy keeps no picked copy` (rework 2 R1) — the screen is
    closed while the gated copy is writing; once the worker finishes, no `insert_*` folder is
    left.
13. `an obsolete writer cannot overwrite the newly confirmed crop` (rework 2 R2) — gated old
    write, rotation, new full-frame confirmation, then the old worker is released: the inserted
    page is the new confirmation's pixels.
14. `a restored photo that is gone says so and keeps no picked copy` (rework 2 R3) — recreated
    screen with the picked file deleted: recovery message, `RESULT_CANCELED`, no batch folder,
    no draft.

Red evidence on the old code (run 44266334779, before `PhotoCropFlowActivity` existed; the
test then referenced the class only by name so it compiled):

```
FAIL InsertPhotosCropFixInsertCropTest > a picked photo opens the corner crop screen instead of landing on the page
  :: java.lang.AssertionError: the picked photo must open the corner crop screen
GATE FAIL fixinsertcrop tests=1 failures=1
```

Green: `GATE PASS fixinsertcrop tests=10` after the rework, then the full suite above (1726 tests).

## Rework after critic (commit `d40d204`)

`next/docs/review/CRITIC_fixinsertcrop.md` raised two Medium defects and a missing test.
Full gate after the rework (cloud run 44275350994, 7 min):
`GATE PASS fixinsertcrop tests=1726 failures=0 skipped=0 lint=[skipped]`.
New class after the rework: `GATE PASS fixinsertcrop tests=10`.

### D1 — a Confirm tap while the next photo loads could confirm it twice (Medium-High, fixed)

- `prepare()` now **tears the previous photo's screen down first** (`clearScreen()` removes the
  overlay, clears the crop view and recycles the old bitmap) and no longer resets `busy`.
  `busy` stays true from the moment a confirm is accepted until `showCropScreen()` has
  installed the next photo's screen and its Confirm button, so a tap that lands during the
  load finds neither a crop view nor an unlocked flow.
- The confirm captures its own photo: `photoIndex = index` at tap time names the output file
  and decides the advance; the post-encode checks require the same token, generation and
  index, so a late result can never be written under a later photo's name.
- `DocumentViewerActivity` now requires `cropped.size == pendingPhotoCount` (the picked count,
  saved/restored with the batch) before `insertFiles`: a short batch inserts nothing.
- Test: `a confirm tap that lands while the next photo loads is ignored` — a new test gate
  (`PhotoCropFlowActivity.prepareGate`) holds photo 2's preparation, the test taps the stale
  photo-1 Confirm while it is blocked, releases it, confirms photo 2 and asserts two unique
  pages in the right order.
- Red evidence with the old `prepare()` (`busy = false`, no teardown):
  `a confirm tap that lands while the next photo loads is ignored :: java.lang.AssertionError:
  photo 1 is the first page: [222, 222, 224] vs [222, 222, 224]` (both pages were photo 1).

### D2 — the saved frame was handed to the next photo (Medium, fixed)

- `onSaveInstanceState` saves the frame together with `KEY_RECT_INDEX`, the photo it was drawn
  on; while the next photo loads there is no crop view/bitmap, so no frame is attributed to it,
  and the restore applies a frame only when `KEY_RECT_INDEX == index`.
- Test: `rotation while the next photo loads does not reuse the previous frame` — drag photo
  1's top-left corner, confirm, hold photo 2's preparation, `recreate()`, release: photo 2
  opens with its own auto-crop frame (0, 0, 1, 1) and its own "Photo 2 of 2" header.
- Red evidence with the old save/restore:
  `rotation while the next photo loads does not reuse the previous frame :: java.lang.AssertionError:
  left edge of photo 2's frame expected:<0.0> but was:<0.15>` (photo 1's dragged frame).

### D6 — the reported viewer-recreation position restore is now tested (fixed)

- Test: `a viewer recreated while the corner screen is on top inserts at the chosen position`
  — choose "After page 1", recreate the document screen while the crop screen is on top, then
  confirm the photo and deliver its result to the recreated viewer (through the platform
  `onActivityResult`, i.e. the real `ActivityResult` registry restore): exactly three pages,
  the confirmed photo at position 1 and the two base pages around it. The claim in the report
  is therefore backed by a test, not prose.
- New test hook: `PhotoCropFlowActivity.prepareGate` (static, test-only, reset in `@After`).

### D3 (optional) — the private crop copy is off the main thread

- The ~2400 px `Bitmap.createBitmap`/`copy` for an undragged confirm now happens inside the
  `Dispatchers.IO` block (recycled in its `finally`, also on cancellation), so the default
  confirm path allocates nothing large on the main thread.

## Rework 2 after judge (commit `b718f55`)

`next/docs/review/JUDGE_fixinsertcrop.md` verdict FAIL with three accepted must-fix items.
Full gate after the rework (cloud run 44278874665, 7 min):
`GATE PASS fixinsertcrop tests=1730 failures=0 skipped=0 lint=[skipped]`.
New class after the rework: `GATE PASS fixinsertcrop tests=14`.

### R1 — the picked batch is owned before the copy and never leaks (privacy, fixed)

- `DocumentViewerActivity` now creates the `cacheDir/insert_<id>` folder and takes ownership
  of it (`InsertPhotoBatch`) **before** the first URI is read. The batch carries two flags:
  `handedOff` (the corner crop flow owns it; this screen must not delete it) and `abandoned`
  (the screen went away while the blocking copy was still writing).
- Cleanup is guaranteed on every exit from the copy: a failed read deletes the half-filled
  folder; a cancelled lifecycle continuation leaves a `finally` in the copy worker itself that
  deletes the folder after the last write; `onDestroy` (including a rotation) marks a
  not-yet-handed-off batch abandoned and deletes it; only a batch the crop flow already owns
  is left alone (it has its own cancel/cleanup path).
- Tests: `a photo that cannot be read leaves no picked copy behind` (second pick points at a
  missing file) and `finishing during the picked-photo copy keeps no picked copy` (a new
  `photoCopyGateForTest` holds the copy, the screen is closed, the worker is released, then no
  `insert_*` folder remains).

### R2 — an obsolete instance can no longer overwrite the confirmed crop (data, fixed)

- Every confirm writes to a worker-unique file `cropped_<index>_<instanceTag>_<generation>.tmp`
  in the same folder, and only the **live** generation publishes it with
  `File.renameTo(cropped_<index>.jpg)` (atomic replace on the same directory). An abandoned
  worker (destroyed instance whose blocking write outlives a rotation) deletes only its own
  temporary file: in the cancellation path (`catch CancellationException`) and after the
  token/index checks; it never touches the final crop or another instance's temp.
- Test: `an obsolete writer cannot overwrite the newly confirmed crop` — the old instance
  confirms a dragged (smaller) crop and is suspended inside the write (`beforeWriteForTest`
  gate); the screen is recreated, the new instance confirms the full auto-crop, its write and
  rename complete, then the old worker is released (`afterWriteForTest`); the inserted page has
  exactly the new confirmation's pixel dimensions. Under the old direct-write code the old
  instance's late write deleted/overwrote the new crop and nothing was inserted.

### R3 — a restored photo that is gone says so (recovery, fixed)

- `PhotoCropFlowActivity.onCreate` no longer cancels silently when the restored cache files
  are missing: it shows the existing `fixinsertcrop_crop_failed` message ("Could not open this
  photo. Pick the photos again.") as a toast (safe before any view exists, same as the
  existing failure path), then cancels and deletes the whole batch.
- Test: `a restored photo that is gone says so and keeps no picked copy` — the picked file is
  deleted, the screen is recreated: the message is the latest toast, the result is
  `RESULT_CANCELED`, no `insert_*` folder remains and no draft is created.

### Red evidence for rework 2

Before the fixes (cleanup removed, direct final-file write, silent early cancel), the same
class failed 5 of 14:

```
FAIL ... a photo that cannot be read leaves no picked copy behind :: the half-copied batch is deleted
FAIL ... finishing during the picked-photo copy keeps no picked copy :: the abandoned copy deletes what it wrote
FAIL ... an obsolete writer cannot overwrite the newly confirmed crop :: the confirmed crop lands in the draft
FAIL ... a restored photo that is gone says so and keeps no picked copy :: the recovery message is shown expected:<Could not open this photo. Pick the photos again.> but was:<null>
FAIL ... rotation while the insert position is asked keeps no picked copy :: the question is gone with the old screen, so are its copies
```

Green after restoring the fixes: `GATE PASS fixinsertcrop tests=14`, then the full suite
(1730 tests).

### New test-only seams (no production behaviour)

- `DocumentViewerActivity.photoCopyGateForTest` — instance hook, runs before each picked file is
  copied.
- `PhotoCropFlowActivity.beforeWriteForTest` / `afterWriteForTest` — hook before the crop write
  and after the write attempt (worker/main completion). Both null in production and reset in
  `@After`.

## Files touched

- `app/src/main/java/com/scanwhirl/app/ui/DocumentViewerActivity.kt` — insert-photos path only:
  the photos picker hands the copied batch to the crop flow after the position question, a new
  `insertPhotoCrop` result launcher inserts the confirmed crops (or cleans up on
  `RESULT_CANCELED`), `askInsertPosition`/`promptInsertAfterPage` gained an `onReady` action
  (default = old behaviour) plus an `onAbandon` cleanup, saved-state keys for the pending batch,
  and `onDestroy` cleanup of a pending batch.
- `app/src/main/java/com/scanwhirl/app/ui/tools/PhotoCropFlowActivity.kt` — new (crop screen,
  per-photo progress, sampled decode, crop on rotation, cache cleanup, no main-thread decoding).
- `app/src/main/AndroidManifest.xml` — one `<activity>` line (`exported=false`,
  parent = ShellActivity).
- `app/src/main/res/values/strings_fixinsertcrop.xml` + `values-hi/` (Hindi values start with
  `<!-- review -->`, no long dashes), the new test file.
- No build file, no permission, no new library, no other lane's file.

## Strings

- `fixinsertcrop_crop_title` "Photo %1$d of %2$d"
- `fixinsertcrop_crop_hint` "Drag the corners to the edges of the page."
- `fixinsertcrop_crop_hint_none` "Couldn't find the page edges. Drag the corners to the page."
- `fixinsertcrop_crop_apply` "Crop and add"
- `fixinsertcrop_crop_failed` "Could not open this photo. Pick the photos again."

## Notes / not done

- The crop is taken from the sampled preview copy (max 2400 px), not a second full-size
  decode; that matches the ID-card flow and the app's 3000 px page cap.
- The position question still comes before the crop screen (same order as the camera and PDF
  inserts). Nothing is inserted until every photo is confirmed.
- A process kill (not a rotation) in the middle of the flow can leave the `insert_<id>` folder
  in the cache until the app's existing privacy sweep; there is no place to run cleanup after
  the process is gone.
- The crop screen offers Back (arrow and system); there is no second "save" action, so no path
  inserts a photo without the confirmation.

## For the lead to check on the phone

1. Document > Add pages > From photos > a photo of a page on a table: the corner screen must
   show the page, straight, with "Photo 1 of N"; "Crop and add" must put the page (no table)
   at the chosen position. Try a flat photo and one taken at an angle.
2. Pick 3 photos: three confirmations in order with "Photo 1 of 3", "Photo 2 of 3",
   "Photo 3 of 3"; the pages must land in that order.
3. Back (gesture and arrow) on the second photo: no page may be inserted at all, the document
   must look unchanged (no draft bar).
4. Rotate while a photo is being confirmed: same photo, same dragged corners; "Crop and add"
   must then work.
5. Cancel the insert-position sheet: nothing inserted.
6. Camera insert and library-PDF insert must behave exactly as before (no crop screen).
7. A photo with no clear page edge: the hint must say the edges were not found, and the manual
   drag must still work.
8. Rework: tap "Crop and add" twice quickly on photo 1 of a 2-photo batch — photo 2 must still
   appear once, and the two pages must be photo 1 then photo 2 (never the same page twice).
9. Rework: confirm photo 1 and rotate while photo 2 is still loading — photo 2 must open with
   its own frame (the whole auto-cropped page), not photo 1's dragged corners.
10. Rework: choose "After page 1", rotate the document screen while the crop screen is on top,
    then confirm — the page must land after page 1, not at the end or nowhere.
11. Rework 2: cancel the photo picker after choosing photos, or leave the document screen while
    the "Preparing" is still running — no copy of the picked photos may be left in the app cache
    (not visible on the phone; the tests assert the folder).
12. Rework 2: confirm a photo, rotate at the same moment, then confirm the rotated screen's
    frame — the page must be the frame you confirmed last, never the earlier one. Delete the
    picked file before a rotation (or clear the app cache) — the screen must say "Could not open
    this photo. Pick the photos again." rather than closing silently.
