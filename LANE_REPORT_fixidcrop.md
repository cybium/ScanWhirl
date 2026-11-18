# LANE fixidcrop — gallery ID card photos are cropped, paper edges are visible

Branch `lane/fixidcrop`. Not merged, not pushed. Commit `ddf5b34`.

Last full gate (cloud run 44231360968, 6 min):

```
GATE PASS fixidcrop tests=1695 failures=0 skipped=0 lint=[skipped]
```

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
- `app/src/test/java/com/scanwhirl/app/ui/tools/IdCardCropFixIdCropTest.kt` (4 tests): picking a
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
test files. `CropView.kt` and `ShellActivity.kt` are unchanged (no hook was needed). No build file,
manifest, id file, LibraryScreen or other lane's file touched.

## Strings

- `fixidcrop_id_crop_title` "Crop the card"
- `fixidcrop_id_crop_hint` "Drag the corners to the edges of the card."
- `fixidcrop_id_crop_apply` "Crop and save"
- `fixidcrop_id_crop_failed` "Could not open this photo. Choose it again."

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
