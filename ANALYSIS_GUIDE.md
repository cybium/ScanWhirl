# ScanWhirl — Source Analysis Guide (test22)

Read this first. This bundle is the **full readable source** of ScanWhirl, an
Android scan-to-PDF app for students/teachers. The matching installable build
is `ScanWhirl-1.1.1-test22.apk` (package `com.scanwhirl.app.test`,
versionCode 22, built **without R8 minification/obfuscation** — class and
method names in the APK are the real names from this source).

## Product facts

- One-time-purchase "Pro" extras; the free app stays free. No accounts, no
  server, no ads. Everything runs on-device.
- Scanner is Google ML Kit document scanner (`SCANNER_MODE_BASE` = fast,
  original-looking; auto-enhance is a Settings opt-in, default OFF).
- Page filter default is **Original (as scanned)** — no fake DPI, no fake
  enhancement; before/after sizes in the UI are real bytes.
- APK target ~3 MB: ML Kit artifacts are **unbundled** (Play services delivers
  them; the bundled artifact ballooned to 45 MB and is deliberately not used).

## Build & test

```bash
export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
./gradlew test                      # 147 unit tests (Robolectric, sdk 35)
./gradlew assembleRelease -PtestBuild   # test APK: com.scanwhirl.app.test
./gradlew assembleRelease               # Play build: com.scanwhirl.app (minified)
```

- Test builds flip `BuildConfig.TEST_BUILD` → every Pro extra unlocked for free.
- Release signing comes from untracked `keystore.properties` / env vars
  (not in this bundle).
- Test results: `app/build/test-results/testDebugUnitTest/*.xml`.

## Module map (where to look)

| Area | File(s) |
|---|---|
| Home: list, search, sort, selection, scan/gallery chooser, Pro Suite, Vault, compress/resize/merge, source choosers for OCR/Lock/Long Image | `app/src/main/java/com/scanwhirl/app/ui/MainActivity.kt` |
| Page editor (reorder/rotate/crop/delete/filter), Pro Tools drawer | `ui/DocumentViewerActivity.kt` |
| Full-screen page view, per-page filter preview, crop + ID/passport presets | `ui/PageViewerActivity.kt` |
| Post-scan staging (review, add pages, per-page filters) | `ui/ScanReviewActivity.kt` |
| Read-only PDF viewer, merge builder, result/export screen, settings | `ui/PdfViewerActivity.kt`, `ui/MergePdfActivity.kt`, `ui/ExportResultActivity.kt`, `ui/SettingsActivity.kt` |
| Storage: documents, PDF generation/import/merge, OCR store, password-lock index, vault flags | `store/ScanRepository.kt` |
| PDF password protection (pure-Kotlin RC4-128 standard security handler) | `util/PdfEncryptor.kt` |
| OCR: ML Kit wrapper + reading-order assembly | `util/OcrEngine.kt` |
| Seekable-PDF cache copy (fixes SAF pipe failures) | `util/PdfPageRenderer.kt` |
| Pro gate + test Free/Pro preview override | `util/ProGate.kt` |
| EXIF-only orientation, atomic crop/rotate, bounded decodes | `util/ImageTools.kt` |
| Long-image stitch, ZIP export, greetings, prefs | `util/LongImage.kt`, `ZipExporter.kt`, `Greeting.kt`, `Prefs.kt`, `Sha256.kt` |
| Layouts / strings / drawables | `app/src/main/res/` |
| Test suite (UI smoke + store + utils) | `app/src/test/` |

## Deliberate limitations (not oversights)

- **Play Billing is not wired.** `ProGate` is a stub: `TEST_BUILD || pref`,
  plus `setTestOverride()` used by the test-only "Preview Free/Pro UI" toggle.
  Real `BillingClient` + `pro_unlock` SKU is future work.
- **Play upload must use versionCode 11 / package `com.scanwhirl.app`**;
  test versionCodes 1–22 are test-only and never ship to Play.
- `PdfEncryptor` only parses PDFs ScanWhirl generated itself; device PDFs are
  rasterized (pages become images) before locking — the UI says so honestly.
- The lock index (`locked_pdfs.txt`, SHA-256 of the encrypted file) exists so
  a shared locked copy can be unlocked later by this app; foreign locks are
  refused with an explanation rather than attacked.
- OCR model downloads on first use through Play services; handwriting is
  honestly labeled unreliable.

## Suggested review angles

1. **Honesty**: any UI text or code path that fakes quality/DPI/enhancement?
2. **Security**: vault PIN hashing, lock-index privacy, no plaintext secrets
   written to shared storage.
3. **Robustness**: memory bounds on PDF/image paths, atomic file writes,
   seekable-FD handling for exotic providers.
4. **UX consistency**: Free vs Pro visibility (Pro extras hidden in Free),
   single-line scan meta, chooser flows.
5. **Tests**: 147 tests — coverage gaps or assertions that don't assert much?

## Recent history

`git log --oneline` — test16→test22 each add features/fixes; test21 fixed
OCR data loss, SAF seekable FDs, gallery tilt, and Free/Pro visibility.
