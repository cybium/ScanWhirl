# APK architecture comparison

Pulled from the installed split APKs on 2026-11-13. The APK artifacts are in `C:\Users\Cybium\Desktop\ScanWhirl-HQ\research\Research_2026-11-13\apk-analysis\`, outside the clean code workspace. This is manifest/dex/native/resource analysis, not source decompilation. Do not copy competitor code or assets; use it only to understand the cost of features.

## Installed sizes

| App | Base APK | Native split | Combined | Classes | Methods | Packages | Activities | Services | Providers | Receivers | Permissions |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| CamScanner 7.28.5 | 194.5 MB | 0 (base) | 194.5 MB | 84,681 | 522,684 | 4,921 | 340 | 41 | 24 | 38 | 46 |
| Adobe Scan 26.10.29 | 44.0 MB | 113.7 MB | 157.7 MB | 49,626 | 169,867 | 876 | 85 | 20 | 8 | 12 | 33 |
| Genius Scan 7.44.1 | 27.3 MB | 15.8 MB | 43.1 MB | 22,521 | 112,596 | 597 | 60 | 16 | 8 | 6 | 21 |
| SwiftScan 11.0.1 | 66.4 MB | 22.6 MB | 89.0 MB | 65,554 | 353,396 | 2,535 | 161 | 26 | 19 | 15 | 27 |
| ScanWhirl test27 | 6.95 MB | — | 6.95 MB | current source | — | — | — | — | — | — | minimal |

CamScanner’s base is 194.5 MB and includes 14 dex files. Adobe’s native split is dominated by large PDF/scanning libraries. Genius is the leanest of the four, using its own GSSDK plus TFLite. SwiftScan uses Scanbot plus Tesseract and DynaPDF.

## Native libraries that explain feature depth

**Adobe**
- `libDCMScanPDFEdit.so` — 27.5 MB
- `libMagicClean.so` — 43.9 MB
- `libPDFLOCRLib.so` — PDF/OCR-related
- `libScan2PDF.so`
- `libACE.so`, `libAGM.so`, PDF viewer classes
- Adobe PDF libraries, XMP, JP2K, CoolType, LiteRT

**CamScanner**
- `libOCREngine.so`
- `libpagescanner.so` — 8.9 MB
- `libmagicenhancer.so` — 5.4 MB
- `liboctopus_classifier.so` — 8.9 MB
- `libopencv.so`, `libMNN.so`, `libncnn.so`
- `libpdfium.so`, `libpdfengine.so`, `libdigital_ink.so`
- `libencryptfile.so`, `libjbig2enc.so`, `libQREngine.so`
- 14 dex files; 41-entry remote tool catalogue

**Genius**
- `libgssdk-core.so` — 8.5 MB
- `libgssdk-ocr.so` — 2.8 MB
- `libtensorflowlite_jni.so`
- RevenueCat, Sentry

**SwiftScan**
- `libscanbotsdk.so` — 22.6 MB
- `libscanbotsdk_tesseract.so` — 3.7 MB
- `libdynapdf.so` — 6.4 MB
- AppLovin/Fyber/Smaato/Moloco/PubNative/Unity ad SDKs

## What this means for ScanWhirl

ScanWhirl’s 6.95 MB APK is small because:

- It uses Google ML Kit through Play Services, not bundled native OCR models.
- It has a custom page-image PDF generator, not a full PDF editing SDK.
- It has no cloud/ads/account/analytics stack.
- It uses Android’s PDF rendering/generation surfaces and its own encryption.

The existing PDF-relevant code is substantial but page-image oriented:

| Area | Current code |
|---|---|
| Page model / operations | `ScanRepository` (merge, extract, reorder, rotate, crop, delete) |
| Page rendering | `PdfPageRenderer` |
| PDF generation | `PdfGenerator` |
| PDF import/rasterization | `PdfImport` |
| PDF text layer | `PdfTextLayer` |
| PDF verification | `PdfVerifier` |
| Encryption | `PdfEncryptor` (RC4-128) |
| OCR | `OcrEngine`, `OcrStore`, `OcrIndexer`, `OcrTextCache` |
| Viewer/editor | `PdfViewerActivity`, `DocumentViewerActivity`, `PageGridView`, `PageViewerActivity` |
| Flows | `MergeFlow`, `CompressFlow`, `ResizeFlow`, `LockFlow`, `VaultFlow`, `OcrFlow` |

This is a good base for a **page-image PDF manager**. It is not a base for Adobe-style vector text editing, full PDF forms, or Acrobat-level Office conversion.

## What is cheap to add

These fit the current architecture:

- A PDF Manager hub over existing `ScanDocument` data.
- PDF row metadata, thumbnails, lock badges, search.
- Merge with ordered thumbnails.
- Compress with better input/estimate/result.
- Organize pages with counts and save-as-new.
- Extract/split by page ranges.
- PDF-to-images and long image via existing renderer.
- Rename/duplicate/move/folders/tags.
- Watermark/text stamp/annotation on page images.
- Signature saved as a local image overlay.
- Redaction as a flattened page-image operation (with verification).
- Page numbers and basic metadata.
- Searchable PDF controls around the existing text layer.
- Naming templates.

## What is medium

- PDF-to-TXT and text-first Word export.
- Batch operations.
- ID-card front/back composition.
- Book-spread split and dewarp.
- Per-export compression quality with a real quality/size slider.
- PDF search across imported PDFs.

## What is hard or should be deferred

- True PDF text-layer editing.
- PDF/A-1b certification.
- Excel/PPT reconstruction from OCR.
- Table preservation.
- Form filling.
- Acrobat-level compare/redaction verification.
- Any AI summary/translation (server, violates offline).
- Full PDF creation from vector data.

## Architectural direction

1. **Use the existing `ScanDocument` as the canonical PDF item.** A PDF is pages + metadata + PDF file + OCR + provenance + lock state.
2. **Separate “reader” and “organizer.”** The reader shows a page stack and a visible dock. The organizer is a page grid with committed edits.
3. **Every operation creates a derived document with provenance.** Do not overwrite unless the user chooses it. Record source document, tool, timestamp and output.
4. **One result model for all tools.** A result screen needs `before/after size`, output path, Open, Share, Show in library, Undo.
5. **One picker component.** Thumbnail, page count, size, date, lock badge, source.
6. **One tool configuration component.** Title, target, options, estimate, Cancel/Apply.
7. **No silent data loss.** On Cancel, the original is untouched. On failure, the original and temp output are cleaned safely.
8. **No new dependency without an explicit owner decision.** A PDF/Office library would change APK size and offline guarantees.
9. **State the rendering model honestly.** Imported PDFs become page images; text and vector detail are flattened. Word/PDF-A claims need a fidelity contract.
10. **Keep the Free core.** PDF Manager, viewer, merge, compress, extract, organize and basic lock should be free. Sell advanced local finishing.

## Comparison to the current app

Current ScanWhirl has the plumbing; it lacks the manager surface. The top competitors have managers because their tools need a home. If ScanWhirl adds the manager and moves existing tools into it, it can look and feel much larger than its 6.95 MB APK while staying offline.
