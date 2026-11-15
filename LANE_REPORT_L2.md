# LANE_REPORT_L2 - Vault, QR code, OCR/Hindi, PDF/A

Session 2026-11-15 (sixth pass). Branch `lane/L2-vault`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L2`.
This pass: "Save as PDF/A" (Pro, Convert) - PDF/A-2b archives with an embedded sRGB profile, XMP,
OutputIntent and a document ID, validated on the host with veraPDF.

## Save as PDF/A (PDF/A-2b, ISO 19005-2 level B)

- **`util/PdfA.kt` (new)** - pure PDF/A pieces: `Mode` (ICC profile, title, dates, ID, time zone),
  `xmp(...)` (pdfaid:part=2, pdfaid:conformance=B, dc:title, xmp:CreateDate/ModifyDate/MetadataDate,
  pdf:Producer), `infoDict(...)` (`/Title /Producer /Creator /CreationDate /ModDate`, the same
  values the XMP carries), `outputIntentDict(...)` (`/S /GTS_PDFA1 /DestOutputProfile`), PDF and
  XMP date formatting (`D:YYYYMMDDHHmmSS+05'30'` / `2025-10-09T14:23:20+05:30`), document ID
  (32 hex characters), and PDF string escaping (UTF-16BE for Devanagari, `(...)` escapes for Latin).
- **`util/PdfGenerator.imagesToPdf(..., pdfa: PdfA.Mode? = null)`** - with the mode on: `%PDF-1.7`,
  catalog gains `/Metadata` and `/OutputIntents [4 0 R]`, new objects 3 ICC profile stream
  (`/N 3`), 4 OutputIntent, 5 XMP metadata stream, 6 Info dictionary; trailer gets `/Info 6 0 R`
  and `/ID [<id> <id>]`; content streams get the EOL before `endstream` that ISO 19005-2 6.1.7.1
  wants. **With the mode off the output is byte-for-byte what it was** (same header, object
  numbering, trailer; pinned by a test). Encryption + PDF/A is refused (`IllegalArgumentException`).
- **`util/PdfVerifier.verify(..., eolBeforeContentEndstream = ...)`** - one new optional flag so
  the verifier pins the correct stream layout per writer; L3's two "length off by one" tests still
  pass, and a PDF/A file is verified before it is published like any other.
- **sRGB ICC profile** - `res/raw/srgb.icc` (6922 bytes, ICC v2 display RGB/XYZ), from Debian's
  `icc-profiles-free` (LittleCMS/Argyll sRGB), **zlib licence** - redistribution and embedding
  allowed; the licence text is in `docs/licenses/icc-sRGB-zlib.txt`. (The color.org
  `sRGB2014.icc` link no longer serves the file, so the zlib-licensed profile is used: same sRGB
  colorimetry, portable licence.)
- **UI** - `tools/PdfAFlow.kt`: Pro gate -> document chooser (`FlowUtil.pickScan`) -> setup sheet
  ("PDF/A-2b for archiving. Keeps the look, no password.") -> `PdfAExport` -> the shared
  `ToolResultActivity` (Open / Share / Save / Show in library / Undo). A password-protected
  document is refused before anything is written with "This document is password-protected. Save
  as PDF/A needs an unprotected document." The registry entry `pdfa` is READY (Pro, Convert). No
  "Save/Share export sheet" with a toggle exists in this build (the viewer shares directly), so
  there was no toggle to add.
- **`PdfAExport`** reuses the document's existing page JPEGs (never re-encoded), carries the
  invisible OCR text (including Devanagari via `/ActualText`) when searchable PDFs are on, writes
  to app cache, and leaves the source document untouched.

## Host check: veraPDF (the reference validator)

veraPDF 1.30.4 (greenfield, installed in `/home/cybium/tools/verapdf`, no sudo) on samples
written by `PdfASamplesTest` (EVAL_ON-gated, `C:\Users\Cybium\Desktop\.sw-qa\owner\pdfa\`):

```
PASS  pdfa_01_one_page.pdf          2b   isCompliant="true"
PASS  pdfa_02_ten_pages.pdf         2b   isCompliant="true"
PASS  pdfa_03_text_latin.pdf        2b   isCompliant="true"
PASS  pdfa_04_text_devanagari.pdf   2b   isCompliant="true"
```

The two files with an invisible OCR text layer (`Tr 3`, non-embedded Helvetica) and the one with
Devanagari `/ActualText` pass **without embedding a font**: PDF/A-2 exempts text that renders no
glyphs, and veraPDF agrees. The only rule that failed first was 6.1.7.1 (`/Length` vs the EOL
before `endstream`), fixed in the PDF/A writer as described above. Samples live in the owner's QA
folder only (never committed).

## Tests added (always-on, in the gate)

- `PdfATest` - XMP part/conformance/title/Producer, XMP vs Info dates (positive and negative
  time zones), 32-hex document ID, OutputIntent naming, PDF string escaping (Devanagari
  UTF-16BE), XML escaping in the title.
- `PdfAGeneratorTest` - `%PDF-1.7`, `/OutputIntents`, `/Metadata`, ICC stream `/N 3`,
  `/S /GTS_PDFA1`, `/Info 6 0 R`, both `/ID` halves, no `/Encrypt`, well-formed XMP with the
  matching dates, invisible Devanagari in `/ActualText`, **the plain writer unchanged**, and
  encryption+PDF/A refused.
- `PdfAExportTest` - a real document becomes a verified archive named after it (with the text
  layer), searchable off means no text layer, a protected document is refused with the clear
  line and nothing is written, a missing document is refused.
- `PdfASamplesTest` - EVAL_ON-gated sample writer for veraPDF (early return without the sentinel,
  so a normal gate is never "skipped").

## DEVICE-UNPROVEN

- Opening a PDF/A archive in Android viewers (structure is validated by veraPDF; rendering uses
  the same image-only PDF path as every other ScanWhirl file).
- The `PdfAExport` UI flow on the phone (chooser, sheets, result screen) - Robolectric covers it.

## Notes for the lead

- `res/raw/srgb.icc` adds ~7 KB to the APK; it is embedded in every PDF/A output as required.
- I did not touch `PdfAes` or the encryption format; PDF/A simply never combines with it.
- Samples use a fixed timestamp and time zone, so the host check is reproducible.

## Gate

```
tests: total=1324 failures=0 skipped=0
GATE PASS L2 tests=1324 lint=[lint skipped (lead runs it at merge)]
```

(`1272` before merging the latest `integration`; same code plus the merged lanes' tests.)

## Commits

- `2ad6e49` OCR quality + Hindi · `80db83f` merge `integration`
- `28200c7` PDF/A-2b: PdfA engine, pdfa mode in PdfGenerator, verifier layout flag, PdfAFlow +
  export, sRGB ICC profile with its licence, tests, veraPDF proof
- `3d86470` merge `integration` · `7794a40` L1's Soon-tile tests follow the new registry (no
  catalogue tool is Soon any more; the PDF/A tile now opens its picker)
- (this report) L2: report

## Earlier passes (still current)

- **OCR quality + Hindi** (`util/OcrLayout.kt`, `OcrMerge.kt`, `OcrEngine.kt`, `OcrLanguage.kt`,
  `store/OcrReindex.kt`): recursive XY-cut reading order (columns, spreads, rotated pages),
  per-line script merge of the Latin and Devanagari models, U+FFFD-free text, the language
  setting (Text screen chip + Settings row) with re-recognition, the "Hindi text" tool, and the
  `/ActualText` searchable text layer (verified with PyMuPDF).
- **Vault** (`store/vault/**`, `ui/vault/**`): AES-256-GCM files, Keystore + recovery wraps,
  process-wide session with the 2-minute relock, `.swvault` backup v2 (entries + remembered PDF
  passwords), fingerprint unlock through `androidx.biometric` with a CryptoObject, upgrade-safe
  passcode unlock with tries-left errors, documents counted not files, `VaultPasswords` for L3.
- **QR code reader** (`ui/qr/**`, Pro): Google code scanner behind a seam, per-type actions,
  last-20 history with clear, errors as sheets.
