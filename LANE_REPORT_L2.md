# LANE_REPORT_L2 - Vault, QR code, OCR & Hindi

Session 2026-11-15 (fifth pass). Branch `lane/L2-vault`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L2`.
This pass: text-recognition quality (U+FFFD and reading order), Hindi OCR with the lead-approved
bundled Devanagari model, the text-language setting in two places, and Devanagari in the
searchable-PDF text layer.

## OCR pipeline (files I now own)

- `util/OcrLayout.kt` (new) - reading order as a recursive XY-cut over boxes: a clean vertical
  gutter splits columns (finish the left column, then the right one; a two-page spread is the
  same rule), otherwise units are grouped into rows (top to bottom, left to right inside a row).
  `rotationDegrees` maps a sideways page upright first. `sameLine` matches one visual line seen
  by two recognizers.
- `util/OcrMerge.kt` (new) - `OcrRawLine`/`OcrRawPage`, `OcrMerge.merge/pick/ordered/assemble`
  and `OcrText.sanitize`. Merge rule: for one visual line the candidate that actually contains
  Devanagari wins, otherwise the more confident one; lines only one model saw are kept. Text is
  cleaned once at the recognizer boundary, then ordered, then joined (a larger vertical gap
  becomes a paragraph break).
- `util/OcrEngine.kt` - script-aware: `recognizeFile/recognizeBytes/recognizeBitmap(page,
  language)`. Latin uses `TextRecognizerOptions.DEFAULT_OPTIONS`, Devanagari uses
  `DevanagariTextRecognizerOptions.Builder().build()` from the new bundled dependency. EXIF
  orientation of a page file is passed to ML Kit, so a sideways photo is read upright before
  ordering. The recognizers sit behind `rawReader`/`rawBitmapReader` so Robolectric can supply
  synthetic pages. `assemble(blocks)` keeps the old block API (geometry-less blocks keep the
  old top/left order, blocks with geometry go through `OcrLayout`).
- **U+FFFD**: the store (UTF-8 + Base64) and the exports were already lossless; the lossy
  boundary was the PDF text layer, which wrote only WinAnsi and replaced every other character
  with a space, and recognizer output itself. Both are fixed: `OcrText.sanitize` drops U+FFFD
  and unpaired surrogates at the boundary (never splitting a surrogate pair), and the text layer
  carries the exact line (see below).
- `store/OcrReindex.kt` (new) - drops a document's stored text+geometry and reads every page
  again with a given language, storing page by page (`putRecognizedPage` refuses a changed
  image) and regenerating the searchable PDF.
- `store/OcrStore.clearAll(id)` (new).
- `store/OcrIndexer` - the default recognizer now follows the language setting.

## Hindi and the language setting (one source of truth)

- `util/OcrLanguage.kt` - `OcrLanguage` (ENGLISH / ENGLISH_HINDI / HINDI with its scripts) and
  `OcrLanguageSettings` on `Prefs.ocrLanguage` ("en"/"en+hi"/"hi", default "en").
- **Text screen** (`ui/OcrEditorActivity`, L1's file, only the language chip handler changed):
  the chip shows the current language, taps open an SwSheet (English / English + हिंदी / हिंदी);
  picking one saves the setting and, for a document-backed page, re-reads the document
  (`OcrReindex`) and recreates the screen.
- **Settings** (L1's file, `SettingsActivity`, only the new row): "Text language" with the
  current value, right under the app language, same three options and the same setting + a
  note that switching re-reads the document. Built in code so L1's layout is untouched.
- **"Hindi text" tool** (Text group) is READY: pick a document, re-read it with the Devanagari
  model, open the Text screen; it also switches the setting to Hindi. Unreadable pages use the
  existing honest wording ("model still has to download" / "nothing found").
- Strings only in `values/strings_ocr.xml` + `values-hi/strings_ocr.xml`; ids in `values/ids_ocr.xml`.

## Searchable PDF with Devanagari (verified independently)

`util/PdfTextLayer` now wraps every line in PDF 1.5 marked content with
`/Span << /ActualText <FEFF...UTF-16BE...> >> BDC ... EMC`. Extraction tools return the exact
Unicode line (Devanagari, curly quotes, "₹"), while the invisible Helvetica/WinAnsi `Tj` inside
stays a sanitized fallback. Surrogate pairs are never split when a long line is cut. No font is
embedded, so the APK does not grow.
**Host check** (PyMuPDF 1.27, a one-page PDF with the same operators): `get_text()` returned
`Hello world\nकुल राशि 1250\n` and `search_for("कुल राशि 1250")` found it.

## Tests added (all green)

- `OcrLayoutTest` - single column, two columns (left finished before right), two-page spread
  (left page then right page, page numbers last), heading over one column, reversed lines
  (the QA "12..6 before 1..5" case), rotated page (90 degrees), `sameLine` positive/negative.
- `OcrMergeTest` - English+Hindi merge keeps each script, lines only one model saw are kept,
  Hindi-only output never contains U+FFFD, paragraph gap, two-column order, `sanitize`
  (U+FFFD, unpaired surrogates, NBSP, tabs, emoji), script detection, `pick` rules.
- `OcrLanguageSwitchTest` (Robolectric, recognizer seam) - setting persists, switching
  re-reads the document in the new script and replaces the old text, English+Hindi keeps both
  scripts in one pass, geometry is refreshed, a missing document is not an error, and the seam
  drives the real `OcrEngine` end to end.
- `PdfTextLayerTest` - `/ActualText` carries Devanagari exactly, UTF-16BE hex with BOM, no
  replacement characters, surrogate pair never split, fallback stays within the line limit.
- `Phase5ScreensTest` (L1's) - the Settings language card now has two rows (owner-requested
  "Text language" row); rewritten with `// v2 UI: 08_text_ocr`.

## DEVICE-UNPROVEN

- Real model accuracy, especially **handwriting** (the owner's notebook photos) and mixed
  Hindi/English print; the merge/pick rules are synthetic-tested but not tuned on real output.
- The bundled Devanagari model on the phone (first use, APK size) and the `ActualText` layer in
  Android viewers (PyMuPDF verified).
- EXIF rotation of camera photos through ML Kit (the gallery path already rotates in
  `OcrImagePrep`).

## Notes for the lead

- Robolectric needs `@GraphicsMode(NATIVE)` for anything that saves a page: `ImageTools` uses
  `ImageDecoder.setTargetSampleSize`, and the legacy shadow throws `UnsupportedOperationException`
  (I hit it in a new test only; existing tests already use NATIVE).
- `LANE_REPORT_L2.md` (this file) replaces the earlier passes' notes; the code from them is
  unchanged unless listed above.

## Gate

```
tests: total=1252 failures=0 skipped=0
GATE PASS L2 tests=1252 lint=[lint skipped (lead runs it at merge)]
```

## Commits

- `649662b` OCR pipeline (wip) · `ac7c8bc` merge `integration` (`88b99a7`)
- (this pass) OCR/Hindi: reading order, script merge, language setting + Settings row, Hindi
  tool, ActualText text layer
- (this report) L2: report


## Earlier in this lane (still current)

- **Vault** (`store/vault/**`, `ui/vault/**`): AES-256-GCM files, Keystore + recovery wraps,
  process-wide `VaultSession` with the 2-minute relock, `.swvault` backup v2 (entries + remembered
  PDF passwords, v1 readable), fingerprint unlock through `androidx.biometric` with a CryptoObject
  and a prompt-authorized wrap, upgrade-safe passcode unlock with tries-left errors, documents
  (not files) counted, `VaultPasswords` for L3.
- **QR code reader** (`ui/qr/**`, Pro): Google code scanner behind a seam, per-type actions,
  last-20 history with clear, errors as sheets.
- **Copy**: no em dashes and no line over ~90 characters in `strings_vault.xml`.
