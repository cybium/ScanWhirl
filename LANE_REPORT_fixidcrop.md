# LANE fixidcrop — gallery ID card photos are cropped, paper edges are visible

Branch `lane/fixidcrop`. Not merged, not pushed. Commit `ddf5b34`.

Last full gate after rework 3 (cloud run 44239293607, 6 min):

```
GATE PASS fixidcrop tests=1706 failures=0 skipped=0 lint=[skipped]
```

Earlier: rework 2 `GATE PASS ... tests=1704` (run 44237271752); rework 1 `tests=1699` (run
44233785286); first pass `tests=1695` (run 44231360968).

## Done

### 1. A picked card photo is auto-cropped, then confirmed on the corner crop screen

- `util/PhotoAutoCrop.kt` (new, small): `crop(bytes)` calls the app's **existing** page
  normalisation, `Deskew.detectQuad` + `Deskew.warp` (geometry only, no colour change). No
  detection code was written. A photo without a confident quad is returned untouched
  (`cropped = false`), so nothing is cut wrongly.
- `ui/tools/IdCardActivity.kt`: "Pick from gallery" (the Android photo picker, W4) no longer
  accepts the raw photo. The picked bytes are oriented (`ImageTools.normalizeGalleryPhoto`, so
  EXIF/HEIC photos are not sideways), auto-cropped, and then shown on a **corner crop screen
  that reuses `CropView` exactly as the Passport tool does** (same view id `toolCropView`, same
  four corner / four edge handles, same "Crop and save" action id `toolCropApply`).
- The frame is preset to the detected card: `Deskew.warp` maps the detected quad onto the image
  edges, and `CropView` starts at the full image, so confirming without dragging keeps exactly
  the cut/straightened card; the person can still drag any corner or edge to trim.
- Only the confirmed crop is handed to `acceptCapture` and composed onto the page. Back (system
  Back) or the crop screen's own Back arrow / Cancel closes the crop screen and returns to the
  side sheet it came from (front sheet or "Add the back side?"), with nothing saved and the
  captured sides unchanged.
- The ML Kit camera scan is untouched: Google's scanner already shows the crop, so only gallery
  picks go through the new crop screen.
- `ShellActivity.kt` was **not** changed. Its Photos path ("Auto-crop pages (recommended)") is
  `tools.launchGalleryCrop`, i.e. Google's ML Kit document scanner with gallery import; there is
  no private auto-crop function in ShellActivity to extract, and the app's own detector is
  `Deskew`. So the shared util calls the existing detector and ShellActivity's behaviour stays
  byte-for-byte identical.

### 2. Paper visibility on the ID card result preview

- `ui/tools/ToolResultActivity.kt`, ID card flow only: the preview host is now the ink colour
  (`void_abyss`) with a 1dp `#33FFFFFF` line and 10dp padding, and the page ImageView has no
  white background. The white A4/Letter sheet therefore sits on a dark backdrop with a visible
  border and a little air around it.
- Every other tool result keeps the previous glass box / white preview (covered by a test).
- There is no separate "layout preview" screen in this flow; the result preview is the only
  place the card page is shown. Say if another screen was meant.

## Tests added (new files, `*FixIdCrop*Test.kt`)

- `app/src/test/java/com/scanwhirl/app/util/PhotoAutoCropFixIdCropTest.kt` (4 tests): the card is
  cut out of a table photo and straightened (output is the card, not the 1200x900 photo, corners
  are card not table); a photo without a card is byte-identical; a small bright patch is not
  mistaken for a page; the result is exactly the existing `Deskew` warp (no second detector).
- `app/src/test/java/com/scanwhirl/app/ui/tools/IdCardCropFixIdCropTest.kt` (15 tests: the first 4
  below plus the rework tests described in "Rework after critic", "Rework 2 after judge" and
  "Rework 3 after judge"): picking a
  photo opens the corner screen preset to the auto-cropped card (frame = whole detected card,
  card hint, Crop and save present, side sheet not yet shown); confirming prints no table colour
  anywhere on the page while the card is there; system Back on the crop screen returns to the
  back sheet and saves nothing; the crop screen's own Back returns to the front sheet and saves
  nothing.
- `app/src/test/java/com/scanwhirl/app/ui/tools/IdCardPreviewFixIdCropTest.kt` (2 tests): the ID
  card result preview has padding, an ink backdrop, no white page box, and a rendered check that
  the page is white, the area around it is dark and the edge line is brighter than the backdrop;
  a non-card result keeps the old white preview box.

Red evidence (each test run before the green one):

- `PhotoAutoCropFixIdCropTest` before `PhotoAutoCrop` existed: cloud run 44229753825,
  `GATE FAIL ... tests=0` (compile error).
- Crop class with the old picker path (`autoCrop = false`): 4/4 fail, e.g.
  `a picked photo opens the corner crop screen preset to the auto cropped card :: the crop
  screen opens after the pick`, `only the confirmed crop is printed on the page`.
- Preview class with the old white box: `the id card page is shown on a dark bordered backdrop
  with padding :: a little padding shows where the paper ends`.

Green: `GATE PASS fixidcrop tests=4` (util), `tests=4` (crop), `tests=2` (preview); then the full
suite above.

## Files touched

`ui/tools/IdCardActivity.kt`, `ui/tools/ToolResultActivity.kt` (ID card preview only), new
`util/PhotoAutoCrop.kt`, new `res/values/strings_fixidcrop.xml` + `res/values-hi/`
`strings_fixidcrop.xml` (Hindi values start with `<!-- review -->`, no long dashes), the three new
test files. `ShellActivity.kt` is unchanged (no hook was needed). Rework 2 adds one add-only public
method to `ui/CropView.kt` (`setCropRect`, used only by the ID card flow; the Passport preset never
calls it and CropView's existing code is untouched). No build file, manifest, id file, LibraryScreen
or other lane's file touched.

## Strings

- `fixidcrop_id_crop_title` "Crop the card"
- `fixidcrop_id_crop_hint` "Drag the corners to the edges of the card."
- `fixidcrop_id_crop_apply` "Crop and save"
- `fixidcrop_id_crop_failed` "Could not open this photo. Choose it again."
- `fixidcrop_id_crop_hint_none` "Couldn't find the card edges. Drag the corners to the card."
  (rework D10; every string exists in `values-hi` with `<!-- review -->`, no long dashes)
- `fixidcrop_id_photo_gone` "That photo is no longer available. Pick it again." (rework 3 R2-2; EN +
  HI with `<!-- review -->`)

## Rework after critic (commit `c28e25a`)

Full gate after the rework (cloud run 44233785286, 6 min):

```
GATE PASS fixidcrop tests=1699 failures=0 skipped=0 lint=[skipped]
```

### D2 — Back during "Crop and save" could still save the side (Med, fixed)

- `applyCrop` now takes a **token** (`val token = cropOverlay`) before the JPEG encode and, after
  the IO block, bails with `if (token !== cropOverlay) return@launch` before any state, toast or
  `acceptCapture`. Back / Cancel therefore wins: the side is never accepted after the screen closed.
- The encode never reads a bitmap the screen may recycle: the crop region is **always copied into
  a private bitmap before launching** (`Bitmap.createBitmap` returns the source itself for a
  full-frame crop, so that case goes through `source.copy(...)`). The copy is encoded on
  `Dispatchers.IO` and recycled there; the lead's recycled-bitmap exception is gone.
- `dismissCropScreen` now resets `cropBusy`, hides the progress overlay and restores the card
  screen's accessibility, so a cancelled save leaves no busy state behind.
- Test: `IdCardCropFixIdCropTest > Back while the crop is saving leaves the side empty and shows
  no error` (apply and system Back in the same main-thread turn, then wait for the abandoned
  encode; the front sheet must still be the sheet, the error view hidden, nothing saved).
  Red before the fix: the same test failed with `no error after Back` — the recycled-bitmap
  exception the lead predicted; with only the token it would have failed the sheet assertion.

### D5 — Rotation / process death mid-crop lost the photo (Med, fixed)

- The prepared crop bytes are cached as `id_card_state/crop.jpg` next to `front.jpg`/`back.jpg`
  and the saved-state Bundle carries `id_card_crop_pending`, `id_card_crop_side` and
  `id_card_crop_detected` (JPEG bytes never go in the Bundle).
- `onCreate` with a restored state re-opens the corner crop screen for that photo and side
  (`reopenCropScreen`, decoded sampled off the main thread); the frame starts on the full image,
  i.e. the already-detected card. Accepting afterwards works as normal.
- The cache copy is deleted when the crop is accepted, when the crop screen is cancelled, and on
  the next fresh open (`clearCapturedState` deletes the whole state folder).
- Test: `IdCardCropFixIdCropTest > rotation during the crop screen keeps the photo and the side`
  (recreate mid-crop, the same bytes and card hint come back, Crop and save then reaches the
  back step). Red before the fix: `the crop screen is still open after recreation`.

### D10 — No-edge fallback said nothing (Low, fixed)

- `PhotoAutoCrop.Result.cropped` is passed through `beginCrop`/`showCropScreen` and persisted with
  the state. When it is false the crop screen shows the new hint
  `fixidcrop_id_crop_hint_none` ("Couldn't find the card edges. Drag the corners to the card.")
  instead of the confident card hint; nothing is accepted until confirm.
- Test: `a photo without a card edge shows the drag the corners hint` (a flat grey photo shows
  the fallback hint and not the confident one). Red before the fix: `the fallback hint is shown`.

### D9 — Touch target and the screen behind the overlay (Low, fixed)

- The crop screen's Back button is now 48x48 dp (the kit icon button is 40 dp).
- While the crop overlay is shown, `contentScroll` (the whole card screen) is
  `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`; `dismissCropScreen` restores `AUTO`.
- Test: `the crop screen's Back target is 48 dp and the screen behind is hidden` (red before the
  fix: `the crop Back is at least 48 dp: 40x40`; the accessibility half also red).

### Not done (as instructed)

- D3 (unsampled HEIC fallback in `ImageTools.normalizeGalleryPhoto`) was deferred to a later lane;
  `ImageTools.kt` and `CropView.kt` were not touched in this rework.
- D1's optional "use the whole photo" (the warp drops the original photo, so the frame can only
  trim inward) is a feature, not part of the rework; say if it is wanted.

## Rework 2 after judge (commit `4899616`)

Full gate after rework 2 (cloud run 44237271752, 5 min):

```
GATE PASS fixidcrop tests=1704 failures=0 skipped=0 lint=[skipped]
```

### N1 — A picked ID photo could outlive the flow (Med, fixed)

- `onDestroy` deletes the whole `cacheDir/id_card_state` folder when `isFinishing &&
  !isChangingConfigurations`: `crop.jpg`, `front.jpg` and `back.jpg` all go when the flow really
  ends. A configuration change (rotation) or a system-recreated process keeps the cache for the
  restored screen.
- System Back during the asynchronous reopen is no longer a silent finish: `reopenCropScreen`
  enables the crop Back callback (`cancelCrop`) before it decodes, and `cancelCrop` now handles a
  pending crop with no overlay, clearing the cache and returning to the side sheet.
  `dismissCropScreen` is no longer a no-op when the overlay is absent and always resets busy,
  progress and the accessibility flag.
- Tests (each asserts `id_card_state` is empty afterwards):
  `Back during the async reopen closes the crop and removes the cached photo`,
  `finishing while the crop is open removes the cached photo`,
  `finishing an abandoned flow removes the cached front side too`, plus the existing
  `the crop screen's own Back button returns to the front sheet` now recreates first and checks the
  cached photo is removed on Cancel.
- Red evidence with the `onDestroy` cleanup removed: `finishing while the crop is open removes the
  cached photo :: finishing removes every cached file` and `finishing an abandoned flow removes the
  cached front side too :: finishing removes the front and back copies`.

### D5(a) — The adjusted crop rectangle survives recreation (Med, fixed)

- New add-only `CropView.setCropRect(RectF)` (clamped to the image, applied after `setImage`); the
  Passport preset never calls it and no other CropView code changed.
- The ID card screen saves the person's live crop frame normalised (0..1) in the saved-state Bundle
  (`id_card_crop_rect`) and reapplies it when the restored crop screen is shown. Works whichever
  side is being cropped, because the pending crop side is restored with it.
- Tests: `rotation keeps the front photo and the adjusted crop frame` and `rotation keeps the back
  photo and the adjusted crop frame` drag the top-left handle in, recreate, and compare all four
  normalised rect components (not just the image). They also compare SHA-256 of the prepared photo
  so "same photo" is proven by content, not byte length.
- Red evidence with the rect restore disabled: `adjusted crop rect part 0 expected:<0.15>
  but was:<0.0>` for the front and `back crop rect part 0 ...` for the back.

### D5(b) — A pick made while preparation is running survives recreation (Med, fixed)

- The picker callback stores the picked **Uri** and side before reading; `beginCrop` keeps the Uri
  until the prepared photo is ready. `onSaveInstanceState` persists `id_card_crop_source_uri` while
  preparation is in flight; `onCreate` re-reads that Uri and re-runs the preparation
  (`resumeCropPreparation`), then opens the crop screen as normal.
- Test seam `cropPrepareGate` holds preparation so a recreation really happens mid-flight; the
  test `recreation while the photo is being prepared still opens the crop screen` recreates while
  the gate blocks, releases it, and then accepts the front side.
- Red evidence with the `cropPreparing` restore branch removed: `the restored screen prepares the
  same photo again`.

### N2 — Encode bitmap released on cancellation (Low, fixed)

- The private crop bitmap is now released in a `finally` around the cancellable `withContext`, so
  a destroy/recreate during encoding cannot skip `recycle()`.
- The D2 test no longer sleeps: a new `cropEncodeFinishedForTest` seam reports the real encode end
  through a `CountDownLatch`, and the test asserts the encode produced bytes (proving no recycled
  bitmap was read) before checking that Back kept the side empty.

### Overruled / deferred (as instructed)

- D9's accessible corner handles are phase 2 and were not implemented.
- D3 stays deferred; `ImageTools.kt` was not touched.

## Rework 3 after judge (commit `ea39ee3`)

Full gate after rework 3 (cloud run 44239293607, 6 min):

```
GATE PASS fixidcrop tests=1706 failures=0 skipped=0 lint=[skipped]
```

### R2-1 — The adjusted frame survived only one recreation (Med, fixed)

- `keepCapturedState` now falls back to the pending `cropSavedRect` when `cropBytes` is set but the
  fresh `CropView`/preview bitmap has not been reinstalled yet (the async reopen window), instead
  of saving no rectangle. The next instance restores the same adjusted frame.
- Test: `a second recreation during the reopen keeps the adjusted frame` drags a corner, recreates,
  holds the reopen decode with a gate, recreates again before that decode finishes, then compares
  all four normalised coordinates on the second restored crop screen. Red before the fix:
  `adjusted frame part 0 after two recreations expected:<0.15> but was:<0.0>`.

### R2-2 — Unreadable restored pick said nothing visible (Low, fixed)

- When the restored pick can no longer be read (SecurityException / null stream, e.g. a photo
  picker grant that is gone), the recovery message is now the **active side sheet's message**
  (`fixidcrop_id_photo_gone`), with the normal Scan / Pick actions as the retry; progress is
  hidden, crop Back is disabled, no crop file exists and no side is accepted. The Activity's
  `errorView` (behind the modal sheet) is no longer used for this case.
- Test: `a restored pick that can no longer be read says so in the side sheet` registers a
  content provider whose `openFile` starts throwing `SecurityException` after the first read,
  holds preparation, recreates, and asserts the front sheet title, the recovery message inside
  `swSheetMessage`, the normal primary/secondary actions, no crop screen, no stuck progress and no
  `crop.jpg`. Red before the fix: `expected:<That photo is no longer available. Pick it again.>
  but was:<Scan the front, or pick a photo from the gallery.>`.

### Required test determinism (3a / 3b)

- 3a: `Back before the reopened crop screen appears returns to the sheet and removes the photo`
  now holds the reopened decode with a new static test gate (`IdCardActivity.cropReopenGate`) and
  asserts there is no crop view before pressing Back, so Back definitely precedes overlay
  installation. Red with the early-Back enable removed: `Back returns to the front sheet
  expected:<Front side> but was:<null>` (the activity finished instead of cancelling).
- 3b: `Back while the crop is saving leaves the side empty and shows no error` keeps the worker
  encode latch and adds `cropSaveSettledForTest`, invoked **on the main thread after the token
  decision**; the test waits for that decision (`waitUntil`) and asserts it was "not accepted".
  Red with the token check removed: `the abandoned save is not accepted expected:<false>
  but was:<true>`.
- Both gates are test-only seams; the static reopen gate is reset in `@After`.

### Overruled / deferred (rework 3)

- The destroy-during-encode bitmap-release test is not added (GC-reclaimable, no user impact).
- D9 and D3 remain deferred by the lead.

## For the lead to check on the phone

1. ID card → Pick from gallery → a photo of a card lying on a table/desk: the crop screen should
   show the card only, straight; drag a corner and "Crop and save"; the page must not show the
   table. Try a photo taken at an angle (perspective) and one taken flat.
2. Back and the crop screen's Back arrow: both should return to the Front/Back or "Add the back
   side?" sheet with no card saved, and the already-captured side must stay.
3. If the auto-detector is not confident (busy background, card fills the frame oddly) the photo
   is shown uncropped for a manual drag; confirm that this fallback is usable, since it is the
   safest behaviour rather than a wrong cut.
4. Result screen: the white page should be clearly visible on the dark backdrop with a thin line
   and padding; Keep / Discard and the Back question from fixflow must be unchanged.
5. Camera scan for the ID card is unchanged (Google shows its own crop); only the gallery pick
   gets this new crop screen.
6. Rework: tap "Crop and save" and immediately use the system Back gesture - the side sheet must
   come back and no card side may appear (no error flash). Rotate while the crop screen is open:
   the same photo and side must still be there, and "Crop and save" must then work.
7. Rework: a photo where no card edge is found must show "Couldn't find the card edges. Drag the
   corners to the card."; TalkBack should read the crop Back button as a 48dp target and must not
   reach the card screen hidden underneath.
8. Rework 2: drag a corner into place, then rotate - the frame must be exactly where it was, for
   the front and for the back side. Rotate immediately after picking (while the progress is still
   up) - the crop screen must still open with the same photo. Backing out of the tool completely
   must leave no picked photo behind (not visible on the phone; the tests assert the cache).
