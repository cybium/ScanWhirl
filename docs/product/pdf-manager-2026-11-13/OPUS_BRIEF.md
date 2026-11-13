# Opus brief — PDF Manager

Read in this order:

1. `SPEC.md`
2. `TOOL_MATRIX.md`
3. `COMPETITOR_MAP.md`
4. `APK_ARCHITECTURE.md`
5. `docs/audit/2026-11-13/HANDOFF.md` and `DEVICE_FINDINGS.md`

Then answer the product questions below before writing code.

## The goal

Design a **PDF Manager** that makes the existing PDF plumbing feel like a document suite:

- Home → PDF Tools/PDF Manager.
- Recent scanned and imported PDFs.
- View, search, share, edit pages.
- Merge, extract/split, reorder, rotate, delete.
- Compress, resize, PDF-to-images/long image.
- Password protect/unlock, lock badges.
- Local text/Word/TXT where honest.
- Signature/annotation/watermark/redaction later.
- A real one-time PRO Power Pack, not ads/cloud.

## Do not do

- Do not add accounts, cloud, ads, watermarks, analytics, fax, or online OCR.
- Do not copy code/assets from CamScanner, Adobe, Genius or SwiftScan APKs.
- Do not use “PDF/A” or “Word” as a label until output validity/fidelity is verified.
- Do not promise true PDF text-layer editing if the app continues to rasterize imported PDFs.
- Do not add a sixth bottom tab.
- Do not add a new dependency or native SDK without an explicit owner decision.
- Do not hide the document action dock behind Home row overflow.

## Required design output (before implementation)

A one-page decision document plus wireframes:

1. **Entry point:** Home tile vs Tools tile vs both. Recommended: both, same screen.
2. **PDF Manager home:** search/filter, quick actions, recent rows, tool groups. Use the layout in `SPEC.md` as the starting point, not a final.
3. **Tool group:** Organize / Optimize / Convert / Secure / Finish. Which tools are visible in v1?
4. **Picker:** thumbnails, pages, size, date, lock badge, multi-select.
5. **Organizer:** committed page counts, drag reorder, rotate/delete/extract, save-as-new vs overwrite.
6. **Tool dialog:** target naming, options, estimate, keyboard behavior, Cancel/Apply.
7. **Result:** before/after, Open/Share/Show in library/Undo.
8. **Free vs Power Pack:** exact feature boundary and one-time price.
9. **Offline contract:** what remains local; what is deferred.
10. **Acceptance criteria:** visible outcomes for each screen.

## Recommended v1 scope

**Free**
- PDF Manager, viewer, search.
- Import PDF.
- Merge, compress, extract/split, reorder, rotate, delete.
- Protect/unlock; lock badge.
- Basic text/OCR and searchable PDF.
- Naming template, folders/tags.

**PRO Power Pack**
- PDF to images/long image.
- Word/TXT (text-first with fidelity warning).
- PDF/A only after validation.
- Per-export searchable-PDF controls.
- Signature/annotation/watermark.
- Secure redaction.
- Batch operations.
- ID-card/book-spread finishing.
- Metadata/page numbers.
- Price: ₹1,499 founder lifetime; regular ₹1,999; optional ₹599–₹899/year only for continuing language updates/support.

## Non-negotiable UX rules

- Never overwrite an original without naming the target and confirming.
- Every result is a new copy by default.
- Counts and thumbnails must match the saved result.
- Cancel returns the original untouched.
- Errors keep the original and offer Retry/Choose another.
- Action rows stay visible above the keyboard.
- Use numeric keyboards for sizes.
- Locked files show a lock badge and explain the search consequence.
- No dead-end “PRO — not available yet” surfaces.

## Questions the design must answer

1. Is PDF Manager a new activity or a mode of the document library?
2. Does a PDF always become page images on import? If yes, how does the UI state it?
3. Which PDF engine is acceptable: Android renderer + custom generator, or a library?
4. Is Word export a real .docx or text-in-a-doc container? What fidelity is guaranteed?
5. Is PDF/A in scope? What validator?
6. Is signature an image overlay or a PDF annotation layer?
7. Which tools are Free and which are PRO?
8. What is the one-time price and update policy?
9. What is the exact offline caveat for ML Kit OCR model downloads?
10. What is the first 3-screen implementation slice that gives the most value with the least new engine work?

## Source files to study

- PDF/model: `store/ScanRepository.kt`, `store/SaveTransaction.kt`, `store/ExportProvenance.kt`
- PDF generation: `util/PdfGenerator.kt`, `util/PdfTextLayer.kt`, `util/PdfVerifier.kt`
- PDF import/render: `util/PdfImport.kt`, `util/PdfPageRenderer.kt`
- Encryption: `util/PdfEncryptor.kt`
- Viewer/editor: `ui/PdfViewerActivity.kt`, `ui/DocumentViewerActivity.kt`, `ui/PageViewerActivity.kt`, `ui/pagegrid/PageGridView.kt`
- Flow patterns: `tools/MergeFlow.kt`, `tools/CompressFlow.kt`, `tools/ResizeFlow.kt`, `tools/LockFlow.kt`, `tools/OcrFlow.kt`
- Home/Tools: `ui/MainActivity.kt`, `ui/ImportSheet.kt`, `ui/ScanListAdapter.kt`
- OCR: `util/OcrEngine.kt`, `store/OcrStore.kt`, `store/OcrIndexer.kt`, `ui/OcrEditorActivity.kt`

## Deliverable

Do not start by editing code. First produce:

- a screen map,
- three wireframes (Manager home, Organizer, Result),
- a button map,
- a Free/PRO matrix,
- a feasibility decision for Word/PDF-A/vector editing,
- a first slice of 3 screens with acceptance criteria.

The owner wants a **PDF manager that justifies an offline premium**, not a smaller clone of CamScanner’s 41-tool suite.
