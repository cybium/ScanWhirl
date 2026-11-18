# LANE fixinsertcrop — Insert pages > Photos crops the photo before the page gets it

Branch `lane/fixinsertcrop`. Not merged, not pushed. Commits `77681c3`, `43692a8`.

Last full gate (cloud run 44271471275, 5 min):

```
GATE PASS fixinsertcrop tests=1723 failures=0 skipped=0 lint=[skipped]
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

Red evidence on the old code (run 44266334779, before `PhotoCropFlowActivity` existed; the
test then referenced the class only by name so it compiled):

```
FAIL InsertPhotosCropFixInsertCropTest > a picked photo opens the corner crop screen instead of landing on the page
  :: java.lang.AssertionError: the picked photo must open the corner crop screen
GATE FAIL fixinsertcrop tests=1 failures=1
```

Green: `GATE PASS fixinsertcrop tests=7`, then the full suite above (1723 tests).

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
