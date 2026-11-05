# ScanWhirl

Private document scanner: camera → auto edge detect / crop → multi-page batch → PDF. Built to help restore your Google Play developer account (inactive, no apps published).

## What it does
- Launches Google ML Kit document scanner (on-device): auto edge detection, manual crop corners, filters, batch pages, gallery import
- Look filters on the review screen: Original / Enhance (auto levels) / Clean (whitens paper, removes stains and shadows)
- Dark look by default
- Saves scans locally (private storage); exported PDFs also land in Documents/ScanWhirl, page images in Downloads/ScanWhirl
- Merge multiple scans into one PDF (long-press list → select → Merge)
- Per-page editing in viewer: rotate 90°, move earlier/later, delete page
- Rename scans; multi-select for merge or batch delete
- Tools row on main screen:
  - **Resize** — gallery image to exact px or auto aspect (width/height "auto" fields), DPI, keep-original button, keep-aspect lock, optional target size (e.g. 500 KB) that compresses to fit
  - **Compress** — saved scans or any PDF file, Low / Medium / High / Ultra levels with estimated result sizes shown, creates "(compressed)" copy
  - After scanning: review screen with all pages, editable default title ("Document 1", …), **Save PDF** / **Save & share PDF**
  - Save any page image to **Downloads/ScanWhirl/** (viewer menu + page actions + per-page button on review screen)
- Share PDF / pages via system share sheet
- No accounts, no ads, no data collection

## Tests
```bash
./gradlew testReleaseUnitTest
```
65 unit tests: repository (save/load/merge/rotate/delete/move/compress/create, sequential Document N naming, filter application), pure-Kotlin PDF writer (DCTDecode embedding, xref validation), image tools (resize, target-size compression, JPEG/PNG DPI patches, stain whitening), scan filters (enhance range stretch, clean background), download and PDF file naming, UI smoke tests (main, viewer, review screen, dark default, scanner button). PDF output is additionally validated with PyMuPDF/pypdf (page count, dimensions).

## Build
```bash
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew bundleRelease
```
Or: `./build-release.sh`

Output: `app/build/outputs/bundle/release/app-release.aab` (copy also in `dist/`)

## Keystore
`keystore/scanwhirl.keystore` — alias and passwords are in the untracked `keystore.properties` (or `SCANWHIRL_*` env vars). Never commit either.
**Back this up.** If you enable Play App Signing (recommended), this is your upload key; Google holds the app signing key.

## Next steps for Play
1. Upload AAB in Play Console → Closed testing
2. Complete data safety (no data collected), privacy policy, content rating
3. Get 12 testers opted into closed testing for 14 continuous days
4. Apply for production access
5. The "account at risk / inactivity" warning clears once app activity exists

See `STORE_LISTING.md` and `PRIVACY_POLICY.md`.
