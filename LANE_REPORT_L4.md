# LANE_REPORT_L4 — Tools engine (shared PDF gate, result names, merge naming)

Session: 2026-11-15. Branch `lane/L4-tools`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L4`.
Merged `integration` (L3's protected-PDF import + L1/L2 work).

## Done this batch

### Protected PDFs still reach the Unlock sheet through the planned import

- The planned path reads the page count with `PdfImport.pageCount(fd)` **before** any budget sheet;
  `PdfRenderer`'s `SecurityException` is classified as `PdfImport.Reason.PASSWORD`, and the shell's
  catch (kept from L3) calls `LockFlow.importProtectedPdf`, which opens `ImportUnlockSheet`.
- New `ui/tools/ProtectedPdfPlannedTest` drives a real `ShellActivity` import with the page-count
  seam throwing `PASSWORD` and asserts the Unlock sheet opens with nothing imported. **Passing.**

### One shared huge-PDF gate on every path that renders a device PDF

- **`ScanRepository.withPlannedPdfPages(uris, ..., preset, onCopied, onPlan, block)`** is the single
  primitive: copies each PDF once (bounded), counts pages, applies `PdfImportBudget` (question when
  >50 pages or >25% free, refuse when leaving <500 MB), asks once, then runs the block with every
  source open and the selected page indices. `inspectDevicePdf` exposes the same budget for callers
  with their own renderer.
- **`ui/tools/DevicePdfGate`** is the single UI helper: refusal sheet, decision sheet
  (Import all / Choose pages / Cancel) and the "Page k of N" progress sheet with Cancel.
- Wired paths:
  - **Import PDF** (shell, the ImportSheet path) — unchanged behaviour, now uses the shared helper;
  - **Vault add-from-device** — `importPdfIntoVaultPlanned` + gate + progress/cancel;
  - **Device Merge** (`MergePdfActivity`) — `mergePdfUrisPlanned`, one aggregate plan, pages rendered
    one at a time, progress/cancel, cancel publishes nothing;
  - **Compress on a device PDF** — `inspectDevicePdf` gate + chosen pages; the quality path imports
    only those pages (`importPdfAsScanSelected`), the target-size path renders only those pages, both
    with the progress sheet and Cancel;
  - **Protect a device PDF** — gated planned import before protecting.
  - **ID card / Passport** accept images only (`image/*` pickers); no PDF path to gate.
- Page cap raised to 10 000; range imports are renumbered from page 0; page rendering is one at a
  time at ≤ import resolution (2048 px), so memory stays flat.

### Result screens name the output (QA run3b 14/17)

- Under the thumbnail there is now a one-line, ellipsized **output name** (`toolResultName`); the
  summary below shows the page count ("3 pages"), or the file count for exports. A size line
  ("· 12 KB") is shown **only for Compress/Resize**, never for Merge/ID card/etc.

### Merge names the output after a source (QA run3b 14f)

- Library Merge with no typed name now uses `OutputNames.forTool(first source name, "Merged",
  existing)`: `isz14 - Merged`, then `isz14 - Merged (2)`. The setup screen no longer prefills the
  name field with a source name, so this rule applies. Test added (two sources `isz14`/`isz12`).

## Tests added / updated

- `ui/tools/ProtectedPdfPlannedTest` (1): protected PDF → Unlock sheet via the planned import.
- `store/ToolsEngineTest` (+1): merge default name and uniqueness.
- `ui/tools/ResultNameTest` (3): output name + page count, no size line for Split, size kept for
  Compress, exported file name.
- `PdfImportTest` still green after the refactor (COPIED fault hook preserved).
- Updated `ScanRepositoryTest` merge-default expectation.

## To Excel phone-check steps (QA)

1. Prepare/photograph an invoice with a clear table, e.g. columns **Item | Qty | Rate | Amount** and
   rows `Pen 2 ₹10 ₹20`, `Book 1 ₹250 ₹250`, `Bag 3 ₹500 ₹1,500` (a printed invoice or a scan).
2. Library → Import/Scan it and let text recognition finish (open the document → Text once if needed).
3. Tools → Convert → **To Excel** → pick the document → **Tables only** → Continue.
4. Result → **Open**: sheet `Page 1` should hold one cell per column; tap `₹1,500`: it is a real
   number (not text) and displays with ₹; `2`, `1`, `3` are integers. Change **All text** to include
   non-table lines. Each page gets its own `Page N` sheet.
5. Optional negative: a text-only letter page in “Tables only” shows one clear “No table found on
   this page.” line.

## Gallery crop (B)

`ScanLauncher.launchGalleryCrop(...)` is still **not present** in `integration` after the merge
(checked the whole tree), so the ID card front/back gallery steps keep the current `GetContent` path
as instructed; nothing was switched.

## Gate result (last full run)

```
tests: total=1285 failures=0 skipped=0
GATE PASS L4 tests=1285 lint=[lint skipped (lead runs it at merge)]
```

## Notes for the lead

- `ImportSheet`-based import, Vault, Merge, Compress and Protect device PDFs now share the gate; the
  legacy `MainActivity` import still uses the automatic path (launcher is the shell).
- `DevicePdfGate.decide` always runs the plan callback now, so even small imports get the progress
  sheet and Cancel.
- Protect's own ExportResult "Before → After" line is L3's; this lane only stopped showing sizes in
  the shared tool result outside Compress/Resize.

## Commits

- `508ea23` merge L4: To Excel, compression chips, huge-PDF import guard, Translate removed
- `295cca6` L4: shared huge-PDF gate for Vault/Merge/Compress/Protect, result names, merge naming
- (report) L4: report
