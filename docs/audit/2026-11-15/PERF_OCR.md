# OCR indexing performance (L2-1, L2-2, L2-3)

L4's audit (`PERFORMANCE.md`, findings L2-1..L2-3) found that indexing a textbook is quadratic in
file writes and that the launch path scans every document directory. This note records the fixes
and the measured before/after on L4's harness (`perf/PerfHarnessTest`, 500-page document, EVAL flag).

## Before / after

| | before | after |
|---|---|---|
| OCR index rebuild, 500 pages | **11 007 ms** | **1 211 ms** (9.1x) |
| Whole-file writes for N pages | 2 N (both files per page) | 2 x ceil(N / 16) |
| Launch pending scan | every document directory | one small queue file |

Measured with `PerfHarnessTest.ocr index rebuild` and the lane-specific `L2_EVAL_ON` flag (the run
line is printed as `EVAL RAN: ocr index rebuild pages=500 ... -> <ms>ms`), Robolectric on the gate
host. Phone storage is slower, but the write count is what was quadratic, so the ratio holds.

## What changed

**`store/OcrStore.kt`**

- `putRecognizedPages(id, pages)` commits a whole batch with one geometry write and one text write,
  in the same order as before: geometry first, the text rename is the commit point. Every page is
  rechecked against its source hash under the store lock, exactly like `putRecognizedPage`, so an
  edit, rotation or delete that happened during recognition is still never overwritten. A page that
  is already stored is left alone (resume-safe, never clobbers corrected text).
  `putRecognizedPage` is now a one-page call to the same code, so both paths share one contract.
  A document whose directory vanished mid-pass is not recreated (the existing test pins that).
- `OcrStore.writeCountForTests()` / `resetWriteCountForTests()`: a counter incremented in the one
  atomic write helper, so a test can assert the number of writes instead of timing them.
- **Pending queue**: `filesDir/ocr_pending_queue.txt` lists the documents that carry an
  `ocr_pending` marker. `markPending` appends (queue first, so a crash cannot lose the id),
  `clearPending` removes, and `pendingQueue()` filters out ids whose marker is gone and rewrites the
  file. A missing file (first launch after upgrading from a build without it) is rebuilt once from
  the markers, so existing installs resume correctly.

**`store/OcrIndexer.kt`**

- Recognized pages accumulate in a batch (`batchPages = 16`, or 2 s for one slow page) and flush
  through `putRecognizedPages`. The flush also runs in a `finally`, so a cancelled pass keeps
  everything it recognized up to the in-flight page; those pages stay absent and are recognized
  again on resume. At most one batch (16 pages) is in memory.
- `resumePending()` reads `store.pendingQueue()` instead of listing every document directory.
- The end-of-pass report counts stored pages once: `doc.pageFiles.count { it.name in storedText }`
  instead of scanning every page for every stored key (L2-1).

## Tests

`store/OcrIndexerTest` (+5):

- `a long index writes a bounded number of files, not one per page`: 120 pages, batch 8, asserts
  `writes <= 2 x batches + 2` and `< pages` (a per-page implementation writes 240 times), and that
  text and geometry hold all 120 pages.
- `the report counts only this document's pages`: a stale key for a deleted page does not inflate
  `pagesWithText`.
- `a pass that dies mid-batch leaves a consistent store and resumes`: the pass throws a
  `CancellationException` from the recognizer on page 6 of 10 with batch 4; the committed pages
  survive, text and geometry have identical keys, the document stays pending, and a second pass
  finishes all 10 and clears the marker.
- `the pending queue replaces the directory scan and drops dead entries`.
- `a missing queue file is rebuilt from the markers`.

`OcrPartialEditTest` still passes unchanged (a recognizer can never overwrite a correction or
resurrect a deleted directory).
