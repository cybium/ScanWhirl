# LANE_REPORT_L4 — Tools engine (To Excel, huge PDF import, QA fixes)

Session: 2026-11-15. Branch `lane/L4-tools`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L4`.
Merged `integration` (kept both sides of the ShellActivity/LibraryScreen reveal change); removed the
"Translate" catalogue entry (no INTERNET permission by design); the Devanagari OCR dependency and
L1/L2 work are in.

## Done this batch

### To Excel (Pro, Convert) — READY

- **`util/TableDetect.kt`**: from one page's stored OCR geometry, rows are grouped by vertical centre
  (baseline tolerance from the median line height), columns are clustered by horizontal gaps
  (gap-based), and a page is a table only with **>= 2 columns and >= 3 rows** (and at least two filled
  cells in most rows). Everything else is one-cell-per-line text.
- **`util/XlsxWriter.kt`**: minimal valid `.xlsx` with `java.util.zip` only — `[Content_Types].xml`,
  `_rels/.rels`, `xl/workbook.xml` (+ rels), `xl/worksheets/sheetN.xml` (one sheet per page, named
  "Page N"), `xl/styles.xml` (number formats), inline strings. Atomic `.part` + rename.
- **Numbers**: `₹4,560`, `Rs. 4,999`, `1,234.50`, `12%`, `1,23,456.00`, `-1,000` become real numeric
  cells with number formats; a currency cell keeps its symbol in the display format (`"₹"#,##0.00`);
  long plain digit runs (phones) and leading-zero references stay text.
- **`ScanRepository.exportExcel(id, out, tablesOnly, noTableNote)`**: uses stored OCR; pages without
  geometry are recognised first with the existing on-device OCR. Setup option **Tables only / All
  text**, wired to the setup screen and result screen (mime + icon). 1225-test gate passed before the
  later batch.

### Compression level chips (QA run2 193/194)

- Light / Medium / Strong are now a single-choice kit chip group: **Medium by default**, selected
  chip = cyan border + soft cyan fill + check, and an **"Estimated size: …"** line under the chips
  (same two-page sampling/extrapolation as CompressFlow, recomputed per selected document).
  `ToolsUiTest` asserts exactly one selected chip and the estimate.

### Huge PDF import (owner's 56 MB textbooks)

- **Pre-check** (`util/PdfImportBudget.kt`): page count is read before rendering; the estimate is
  pages × a typical page (600 KB at 2048 px, 450 KB at 1600, 320 KB at 1024); free storage is read
  from the documents volume. A PDF needs confirmation when **pages > 50 or estimate > 25% of free**.
- **Refusal**: if the import would leave **< 500 MB** free, it refuses before starting with a clear
  message (needed MB / free MB).
- **Sheet** (`ui/tools/PdfImportSheets.kt`): "This PDF has N pages" with **Import all**, **Choose
  pages** (the PageRanges field, e.g. "1-20, 45", invalid input stays open with a line) and **Cancel**
  (also closing the sheet = Cancel).
- **Progress sheet**: "Importing page k of N" updated per page, **Cancel** cancels the coroutine; the
  seekable copy and the private staging folder are deleted in `finally`, and the document is published
  only after every chosen page is written, so a cancel leaves **no partial document**.
- **Pages one at a time**, max long side = import resolution (`maxDim`, default 2048); the page cap is
  raised to 10 000 (up from 500) so a chosen range works for the textbooks; range imports are
  renumbered from page 0.
- Memory: `PdfImport.renderPages` holds one rendered JPEG and writes it before the next page. Test
  with a **fake 2000-page source**: all 2000 files written sequentially, cancellation stops before a
  page, a mid-render failure leaves staging to be discarded.

#### Host check (EVAL_ON is present; private data not committed)

PyMuPDF in WSL, reading the owner PDFs only for this eval:

```
Harrison's ... Part-1.pdf   pages: 2223   open+count: 171 ms
27th-Bailey ... Surgery.pdf pages: 1633   open+count: 2261 ms
```

The app pre-check copies the PDF once to a seekable cache file (bounded at 256 MB) and then reads
`PdfRenderer.pageCount`; the copy dominates, so both books are checked in seconds, not minutes.
Estimates: 2223 pages ≈ 1.27 GB, 1633 pages ≈ 0.94 GB; both trigger the page-count sheet and the
free-space rule (they are refused if leaving < 500 MB).

### ID card gallery crop (B)

`ScanLauncher.launchGalleryCrop(...)` is **not in integration yet**, so the ID card gallery steps keep
the current path (`GetContent` → `readImage` → the ID-1 compositor) as instructed; switch them to the
auto-crop route when L1 lands it.

### Translate removal

`translate` removed from `ToolRegistry` and its `tool_translate` / `soon_translate` strings (en + hi);
catalogue is 43 tools / 18 Pro and the tests counts were updated accordingly.

## Tests added / updated

- `TableDetectTest` (5): 4-column invoice, 2-column list, paragraphs, too few rows/one column, single-
  cell rows.
- `XlsxWriterTest` (4): parts + rels/content types resolve, sheet names/count, numeric cells with
  styles, numeric parsing (currency, Indian grouping, percent, phone/leading zero as text), atomic
  failure, escaping.
- `ToolsEngineTest` (+2): repository export one sheet per page with a detected table and numbers,
  tables-only page note, locked source refused.
- `ToolsUiTest` (+2): To Excel chips; compression chips single selection + estimate.
- `PdfImportBudgetTest` (5): decision thresholds, 25% rule, 500 MB refusal, resolution estimate,
  range parsing.
- `PdfImportRenderTest` (5): 2000-page fake source, selected range, failure, cancellation, bad page.
- `PdfImportSheetsTest` (5): Import all / Cancel / close = Cancel, range decision, invalid range,
  progress text + cancel.
- Updated L1 tests that assumed `to_excel` was Soon (now uses `pdfa`).

## Gate result (last full run)

```
tests: total=1240 failures=0 skipped=0
GATE PASS L4 tests=1240 lint=[lint skipped (lead runs it at merge)]
```

## To Excel host check (sample produced by the test, env only for this run)

```
testzip: None
parts: 7
sheets: ['Page 1', 'Page 2']
-- Page 1
   [('A1', 'text', '')]
-- Page 2
   [('A1', 'text', '')]
```

(The sample uses the DocFixtures OCR text; `XlsxWriterTest` covers numeric/currency cells and a
4-column grid from synthetic geometry. openpyxl is not installed in WSL; the grid was read with
`zipfile` + XML, independent of our code.)

## Notes for the lead

- `SwSheet.setOnDismissListener` was added to the kit (additive) so closing the import sheet counts as
  Cancel; keep both sides on merge.
- Legacy/other import entry points (`MainActivity`, Vault import, Compress/Lock device PDF) still use
  the automatic import path; the ImportSheet path in the shell uses the new pre-check. Say if you
  want the same gate on Vault import.
- The owner PDFs were read only because `EVAL_ON` is present; nothing from `.sw-qa` is committed.

## Commits

- `165f8f2` L4: To Excel (table detection, numbers, minimal xlsx) and selected compression levels
- `668d16c` L4: huge PDF import pre-check, page range, progress and cancel; 2000-page render test
- (report) L4: report
