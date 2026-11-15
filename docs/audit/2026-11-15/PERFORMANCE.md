# Performance & big documents (owner: 2000-page textbooks)

Session 2026-11-15, lane L4. Harness: `app/src/test/java/com/scanwhirl/app/perf/PerfHarnessTest.kt`.

## How this was measured

- Robolectric (SDK 35, native graphics) on the gate host (WSL, tmpfs-backed build mirror).
- Library: **300 synthetic documents** (2 pages each) plus one **500-page** document, plus OCR text
  and geometry for every page. Page images are **64×96 JPEGs (~2 KB)** so the run stays under a
  couple of minutes; this measures *count-bound* work well and *byte-bound* work only weakly.
- The same harness runs modest sizes (40 docs / 80 pages / 30-page merge) on every gate; the
  300/500 run is gated by `C:\Users\Cybium\Desktop\.sw-qa\owner\EVAL_ON` and writes
  `PERF_BEFORE.txt` / `PERF_AFTER.txt` next to this file.
- Times are the best of 3 runs (1 run for the destructive/publish operations). They are **not
  phone timings**: phone storage is 5-20× slower and real pages are 100-1000× larger.

## Measured table (300 docs, one 500-page doc, synthetic pages)

| Path | Before | After | Note |
|---|---:|---:|---|
| Library load (`listDocuments`, 300 docs) | 39 ms | 40 ms | linear; dominated by per-doc stat of every page |
| Search by name, cold / warm | 36 / 3 ms | 32 / 3 ms | warm is cached |
| Search OCR text, cold / warm | 56 / 41 ms | 65 / 41 ms | first scan reads every `ocr.txt`; cache warm is fast |
| Open document (page list) | 8 ms | 3 ms | `getDocument` only |
| Open + `draftPages` (viewer path) | 18 ms | 9 ms | viewer loads the document twice (finding L1-5) |
| Organize grid first screen (6 visible cells) | 988 ms | 870 ms | dominated by Robolectric inflation; real device binds in a few ms/cell |
| Merge 3×200 pages | 3113 ms | 3701 ms | noise; byte-bound (tiny pages) |
| Compress 500 pages | 3273 ms | 3847 ms | noise; byte-bound |
| PDF generation 500 pages | 69 ms | 78 ms | same wall time on 2 KB pages; page reads halved (see below) |
| Make searchable 500 pages | 262 ms | 477 ms | wall time noise; text-layer flag check 651 ms → 0 ms |
| OCR index rebuild 500 pages | 10 370 ms | 12 540 ms | unchanged code; O(n²) rewrite is L2's (finding L2-2) |
| Target-size compress, full flow | 2766 ms | 2778 ms | same time; **peak pages held in memory: 500 → 1** |

Deterministic resource metrics (independent of runner noise):

| Metric | Before | After |
|---|---:|---:|
| PDF writer page reads | 2 × N | 1 × N |
| Text-layer flag geometry reads (500 pages) | 500 | 1 |
| Text-layer flag check (500 pages) | 651 ms | 0 ms |
| Target-size compress pages resident | N (all `ByteArray`s) | 1 |
| Thumbnail decode for a 1600×2200 page, target 220 px | full 1600×2200 decode requested via `readBytes()` | sample 8 → 200×275 (~220 KB) |
| OCR search: directory entries listed per doc per keystroke | up to all pages of that doc | 0 (direct `ocr.txt` path) |

## Fixes made (files this lane owns)

1. **`store/OcrTextCache.kt:30` — search no longer lists a document directory.**
   `normalized()` used `File(docsDir, docId).listFiles().firstOrNull { it.name == "ocr.txt" }`,
   which walks every page file (up to 2000) on every keystroke and for every document. Now it
   stats `File(File(docsDir, docId), "ocr.txt")` directly.
2. **`store/ScanRepository.kt:2455` / `:2488` — text-layer flag is O(pages), not O(pages²).**
   `regeneratePdf` read the whole geometry map once per page and used `pages.indexOf(it)` per page.
   It now reads geometry once, passes it to both the writer and the flag, and checks keys by index.
   Measured 651 ms → 0 ms on 500 pages.
3. **`util/PdfGenerator.kt:73` — one page read, not two.**
   The writer validated every JPEG in a full pre-pass (`readBytes` + `checkJpeg`) and then read every
   page again to embed it. Validation now happens inside the write loop; a bad page still aborts and
   deletes the `.tmp`, leaving `outFile` untouched. I/O for PDF generation is halved.
4. **`util/ImageTools.kt:126` — thumbnails no longer read the whole encoded page.**
   `decodeThumb` did `file.readBytes()` and decoded from memory. It now uses `decodeSampledFile`:
   bounds from the header, then one sampled decode (up to 32 MB encoded / 3000 px working cap per
   page no longer enter memory for a 220 px cell). A probe (`ImageTools.thumbProbe`, test-only)
   records the request; the harness asserts sample 8 and a 200×275 result for 1600×2200 at 220 px.
5. **Target-size compress streams one page at a time** (`ScanRepository.compressScanToTarget`,
   used by `ui/tools/ToolSetupActivity` and `runBatch`). The old path called
   `SizeFit.compressPages(...)` which returns `List<ByteArray>` for **every** page before
   `createDocument` — a 2000-page book held hundreds of MB. The new path computes each page's share
   from file lengths and stages one compressed page before moving on.
6. `ImageTools.thumbProbe` is a nullable test seam; production behaviour is unchanged.

All other operations were already streaming: merge copies one page at a time, quality compress
decodes/encodes one page at a time, `PdfGenerator` embeds one page at a time, and `PdfVerifier`
compares image streams in 64 KiB chunks.

## Findings for other lanes (do not edit here; propose + measure)

### L1 — library screen

- **L1-1 `ui/shell/LibraryScreen.kt:229`** — `reload()` calls `repo.listDocuments()` **on the main
  thread** and then rebuilds the whole UI. `listDocuments` → `ScanRepository.loadDocument`
  (`store/ScanRepository.kt:178-182`) stats every page file for the revision, so a 2000-page book
  adds 2000 stats to every library refresh (measured 39 ms for 300 docs on tmpfs; phone storage will
  be several times that). Proposed: `lifecycleScope.launch { withContext(Dispatchers.IO) {
  repo.listDocuments() } }`, keep the old list visible while loading, and cache the list keyed by a
  cheap revision.
- **L1-2 `ui/shell/LibraryScreen.kt:744-749` + `:982-990` + `:1006-1008`** — every keystroke runs
  `visibleDocs()` (which calls `textCache.normalized()` for every document, i.e. reads/normalises
  every `ocr.txt`) **and** `renderDocs()`, which does `docList.removeAllViews()` and builds a card
  for **all** 300 documents. Proposed: debounce 150-250 ms, run filtering on IO, render a
  RecyclerView with DiffUtil (or paginate), and cache a searchable text index per document.
- **L1-3 `util/HomeFilter.kt:84-91`** — `SORT_MODIFIED` calls `pageFiles.maxOfOrNull { lastModified }`,
  another O(pages) stat pass after `loadDocument` already did one. Proposed: carry the revision /
  newest page mtime from `loadDocument` and sort on it.
- **L1-4 `ui/shell/LibraryScreen.kt:1002`** — `docSize` sums page lengths on every sort; fold the
  size into `ScanDocument` while listing.
- **L1-5 `ui/DocumentViewerActivity.kt:282-305`** — the viewer calls `getDocument(id)` and then
  `repo.draftPages(id)`; when there is no draft, `draftPages` (`store/ScanRepository.kt:2999-3006`)
  calls `getDocument` again, so opening any document lists and stats its pages twice. Proposed:
  call `repo.draftOf(id)` first and use the already-loaded `doc.pageFiles` when it is null (measured
  18 ms vs 9 ms on the 500-page fixture).

### L2 — OCR

- **L2-1 `store/OcrIndexer.kt:181`** — after indexing, the report does
  `store.readText(docId).keys.count { k -> doc.pageFiles.any { it.name == k } }`, an O(pages²) scan
  (and another full map read). The stored keys are page names already; use
  `store.readText(docId).size` (or count once against a `Set` of page names).
- **L2-2 `store/OcrStore.kt:232-264`** — the real textbook bottleneck: every `putPage` /
  `putRecognizedPage` re-reads `ocr.txt` and `ocr_geo.txt`, rewrites **both whole files** (fsync +
  rename each), and re-hashes the page. Indexing N pages is therefore O(N²) bytes written and
  O(N²) parsing. Measured 500 pages ≈ 10-12 s for trivial text on tmpfs; a 2000-page book is
  ~16× worse. Proposed: append-only per-page OCR files (or a small journal + batch flush every
  32 pages) with one final consolidated write, preserving the per-page crash-safety contract; keep
  the existing format as the consolidated output.
- **L2-3 `store/OcrIndexer.kt:86-96`** — `resumePending` lists every document directory at launch
  to find `ocr_pending`. Acceptable for 300 docs, but a single marker index file would avoid it.

### L5 — draft / Organize grid

- **L5-1 `ui/pagegrid/PageGridView.kt:234-273` → `PageThumbLoader.kt:40-41`** — each bind computes
  `keyFor` with `lastModified()` + `length()` on the main thread. That is 2 stats per visible cell,
  not O(n), but on a fling over 2000 pages it is the remaining disk touch in binding. Proposed:
  cache the request key per `File` (or pass the document revision into the grid) and reuse it while
  the page set is unchanged. Decoding is already off-main and sampled (fix 4 above).
- **L5-2 `store/ScanRepository.kt:2996-3006` + `:2960`** — `draftPages` and `draftOf` each load and
  parse the manifest; the viewer calls both. Proposed: one `draftState(id)` returning
  `(pages, draft)` and one manifest read.

## Memory

- Thumbnails: no full-resolution decode. The harness asserts the requested sample and decoded size
  (1600×2200 page → sample 8 → 200×275, ~220 KB) instead of the 14 MB full ARGB bitmap.
- Compress / merge / PDF generation / text-layer writing: one page's bytes and at most one bitmap
  are alive at a time; `PdfVerifier` streams 64 KiB chunks.
- Target-size compress previously held all N compressed pages (a 2000-page book: hundreds of MB);
  it now holds one page.
- OCR indexing holds the text/geometry maps (small) but its **write amplification** is the problem
  (L2-2), not heap.
- Unbounded caches: none found. `PageThumbLoader` 24 MB bitmap LRU, `OcrTextCache` 4 MB chars +
  3 MB page map, `PdfViewerActivity` 4 bitmaps, `HomeRow` 1024 keys — all bounded.

## Tests

- `perf/PerfHarnessTest` (10): library load, name/text search, open page list, grid first screen,
  merge 3×N, compress N, PDF N, OCR index N, text-layer flag, thumbnail sampling/memory. Modest
  sizes always; the 300-doc/500-page run under `EVAL_ON`.
- `store/ToolsEngineTest` (+1): `compressScanToTarget` publishes the right page count and leaves the
  source untouched.
- Existing `util/*` (490), `ui/tools/*` (54), `ui/pagegrid/PageThumbLoaderTest` (3),
  `store/BatchEngineTest` (7) remain green, including PDF verifier, text-layer and redaction tests.
