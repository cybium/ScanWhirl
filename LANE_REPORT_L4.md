# LANE_REPORT_L4 — Tools engine (Batch tools + phone QA run4a)

Session: 2026-11-15. Branch `lane/L4-tools`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L4`.

## Done this batch

### Batch tools (Pro; Organize)

- **Engine**: `ScanRepository.runBatch(ids, action, onProgress, cancelled)` (at the end of the class).
  - Actions: `Compress(quality, maxDim, targetBytes)`, `ExportPdf` (Documents/ScanWhirl),
    `PdfToImages(format, oneZip)` (ZIP per document or one aggregate ZIP), `ToWord`,
    `RemoveMetadata`, `Protect(password)` (L3's `protectPdf`). Add to Vault is not included.
  - Runs one document at a time through the same publish path as the single tools. One failure is
    recorded (`BatchItem.reason`) and the batch continues. Cancel is cooperative: it stops between
    documents, finished outputs stay, and the publish path leaves nothing partial.
  - `runBatch` signature:
    `suspend fun runBatch(ids: List<String>, action: BatchAction, onProgress: (number: Int, total: Int, name: String) -> Unit = { _,_,_ -> }, cancelled: () -> Boolean = { false }): List<BatchItem>`
- **UI**: `ui/tools/BatchActivity.kt`
  - Kit multi-select chooser (check rows, Select all, search when > 8 docs);
  - action sheet with icons; options for Compress (level / target size), PDF to images
    (JPG/PNG, per-doc or one ZIP), Protect (one password for all), plus one-tap Export PDF,
    To Word and Remove metadata;
  - progress sheet `Document k of N · name` + Cancel ("Finishing the current document…");
  - summary sheet `N done, M failed`, per-document green/red lines, Share all / Show in library.
  - Free users land on `ProScreen`.
- **Entry point for L1's library selection bar**:
  `BatchLauncher.start(activity: Activity, ids: List<String>)` in
  `ui/tools/BatchLauncher.kt`. It opens `BatchActivity` with those ids selected, straight on the
  action sheet.
- **Registry**: one Pro, Ready entry `batch_tools` (Organize, `ic_sym_view_module`). Catalogue is now
  45 tools / 19 Pro; `ToolRegistryTest` and `ShellScreenTest` updated.

### Phone QA run4a

1. **Result screens for .docx / .xlsx / .pptx / .zip** (F06/F07): the empty white card is replaced
   by a file-type card — large tinted type badge (DOCX blue, XLSX green, PPTX orange, ZIP grey),
   file name, size, and a real count (pages from DOCX page breaks, sheets from
   `xl/worksheets/sheetN.xml`, slides from `ppt/slides/slideN.xml`, files from ZIP entries).
   New `util/OfficeFileCounts.kt`; card built only for those four types, images/PDFs unchanged.
2. **To Excel, Tables only, no table on any page** (F06_h): `exportExcel` now returns
   `ExcelExport(file, tablePages, totalPages)`; when Tables only finds no table it **writes
   nothing** (`file == null`). The setup screen then shows an `SwSheet` **"No tables found"** with
   **Export all text instead** / **Cancel**. If some pages have tables, only those pages become
   sheets and the result sheet notes **"Tables found on N of M pages"**.
3. **Business card** (F09_a): the screen now always opens the same first sheet as ID card —
   **Scan with camera / Pick from gallery**. Both business card and ID card (front and back) use
   L1's `ScanLauncher.launchGalleryCrop(...)` so gallery photos are corner-detected and cropped
   before OCR; the old `GetContent` pickers are gone.

### Data-safety regression caught and fixed

An earlier Batch insertion had accidentally replaced the final lines of `stageDraftPages`
(`OcrStore(...).putAll(staging.name, texts, geo)`), which broke OCR carry-over for unchanged draft
pages. Restored; `DraftEngineTest` (15 tests) is green again, including "unchanged pages carry
their OCR text".

### Cloud gate test-infra fix

`app/build.gradle.kts` hard-coded `maxHeapSize = "1g"` / `forkEvery = 120`, so the cloud gate's
`-PtestHeap=4g -PtestForkEvery=200` were ignored and the 16 GB runner died with OOM around test
805. The test options now honor those properties (same defaults locally). Full cloud suite completes
at 1379 tests.

## Tests added / updated

- `store/BatchEngineTest` (7): 3-doc compress → 3 outputs; one fault → 2 done + reason; cancel after
  first → 1 output/no partial; protect-all verifies each; images per-doc and one ZIP; metadata copy.
- `ui/tools/BatchUiTest` (5): registry (Pro/Ready), Free → Pro screen, Select all, launcher ids,
  compress → summary.
- `store/ToolsEngineTest`: Excel tables-only now keeps only table pages; new test proves no table
  writes no workbook.
- `ui/tools/ToolsUiTest`: To Excel no-tables sheet → "Export all text instead" writes one workbook.
- `ui/tools/ResultFileCardTest` (3): DOCX card + page count; XLSX/PPTX/ZIP badges + counts; count
  helper reads real packages.
- `ui/tools/BusinessCardFlowTest` (+1) and `ui/tools/IdCardFlowTest` (+1): the gallery button goes
  through `launchGalleryCrop`.
- Existing `DraftEngineTest` green again after the OCR restore.

## To Excel phone-check steps (QA, updated)

1. Prepare/photograph an invoice with a clear table, e.g. **Item | Qty | Rate | Amount** and rows
   `Pen 2 ₹10 ₹20`, `Book 1 ₹250 ₹250`, `Bag 3 ₹500 ₹1,500`.
2. Library → Import/Scan it and let text recognition finish (open the document → Text once).
3. Tools → Convert → **To Excel** → pick the document → **Tables only** → Continue.
   - Pages with a table become sheets; the result line says **"Tables found on N of M pages"**.
   - `Open`: tap `₹1,500` — it is a real number and displays with ₹; `2`, `1`, `3` are integers.
4. Negative: a text-only letter (no table on any page) must **not** write a workbook. It shows
   **"No tables found"** with **Export all text instead** / **Cancel**. Export all text produces one
   `Page N` sheet per page with plain text.
5. Switch the option to **All text** before running to include non-table lines directly.

## Gate result

Full cloud gate (run 43674257999):

```
1379 tests, 1 failure: TextFitTest > ocr editor text fits (w320dp) — the known L1 integration issue
GATE FAIL L4 tests=1379 failures=1 skipped=0 lint=[skipped]
```

Everything else is green, including all Batch, Excel, result-card, business/ID card and Draft tests.
Local targeted runs: `BatchEngineTest` 7/7, `BatchUiTest` 5/5, `ui.tools.*` 54/54,
`ToolsEngineTest` 32/32, `DraftEngineTest` 15/15.

## Commits

- `21e23e6` L4: Batch tools engine and UI (multi-select, action/options, progress, summary, launcher)
- `71c5c5b` L4: merge integration; keep the Batch entry (45 tools / 19 Pro)
- `0557314` L4: result file-type cards, Excel no-tables/all-text flow, business + ID card gallery
  crop, cloud test heap flags

## Earlier L4 work (kept)

- Shared huge-PDF gate for Vault/Merge/Compress/Protect and device import (see previous report
  sections in history); result screens name the output; Merge names the output after its first
  source.
