# PM-2.1c — Extract, compress, create, duplicate and device-PDF merge on the publish primitive

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decision A6: `61_ENGINE_DECISIONS.md` §2. Needs PM-2.1b.
May run before or after PM-2.2b, but not at the same time in one worktree.

## 1. Goal

Every remaining tool output is built in staging and published by one rename. That removes `finalizeDoc`'s swallowed
PDF failure (F10), the unsynced direct writes (F11) and compress's whole-document memory (F12).

**User-visible effect.**
- Failures that used to leave a document without a PDF, or a half-copied duplicate, now show an error and leave nothing behind.
- Compress of large documents no longer holds every page in memory.
- Names, categories and screens are unchanged.

## 2. Invariants established (extract, compress, createDocument, duplicate, mergePdfUris)

- **I1**, **I4**, **I5**, **I6**, **I10**: quoted in `PM-2.1b_PUBLISH.md` §2.
- **I3** for duplicate: "`locked.meta` exists ⇔ `scan.pdf` contains `/Encrypt`". The copy keeps both.

## 3. Files to touch (all in `store/ScanRepository.kt` unless noted)

| Function | Change |
|---|---|
| `buildAndPublish` (from PM-2.1b) | Add the parameter `copyPdf: Boolean = false` (§5.1). The default keeps merge unchanged. |
| `extractPages` `:1040-1060` | Below the PM-3.1b refusal: `chosen = indices.map { doc.pageFiles[it] }`, then `buildAndPublish(name, doc.category, chosen.sumOf { it.length() }) { s -> chosen.forEachIndexed { i, f -> stagePageCopy(f, s, i) } }`. The name logic at `:1055-1058` is unchanged. |
| `compressScan` `:1349-1359` | Below the refusal: `buildAndPublish("${doc.name} (compressed)", null, doc.pageFiles.sumOf { it.length() }) { s -> doc.pageFiles.forEachIndexed { i, f -> stagePageBytes(ImageTools.recompressJpeg(f.readBytes(), quality, maxDim), s, i) } }`. Only one page's bytes are alive at a time. |
| `createDocument` `:1332-1346` | Keep both `require`s **before** staging. Name: `name?.trim()?.replace('\n',' ')?.takeIf { it.isNotBlank() } ?: nextDocumentName()` (as `finalizeDoc` `:1312-1313`). Then `buildAndPublish(n, null, pageBytes.sumOf { it.size.toLong() }) { s -> pageBytes.forEachIndexed { i, b -> stagePageBytes(b, s, i) } }`. |
| `duplicateDocument` `:1002-1032` | §5.2. |
| `mergePdfUris` `:1110-1128` | `require(uris.size >= 2)`; `name = suggestedName ?: defaultMergedName("Merged PDF")`; then `buildAndPublish(name, null, 0L) { s -> var n = 0; for (uri in uris) n += PdfPageRenderer.withSeekablePdf(context, uri) { fd -> appendPdfPages(fd, s, n, 150f, 2048, 85) }; if (n == 0) throw IllegalStateException("No pages to merge") }`. |
| `appendPdfPages` `:1134-1149` | `:1146` becomes `Durable.writeFile(file, jpeg)`, then `OpFaults.at("op:page:${start + i}")`. |
| `finalizeDoc` `:1306-1322`, `newDocDir` `:1300-1303` | **Delete.** After this card, `Select-String 'finalizeDoc\|newDocDir'` must return nothing. |

UI callers (`CompressFlow.kt:188, 213, 303`, `DocumentViewerActivity.kt:404, 645`, `MergePdfActivity.kt:157`) are **not** edited.

## 4. Red tests first

Class `app/src/test/java/com/scanwhirl/app/store/ToolPublishTest.kt` (Robolectric, sdk 35, `@After Faults.resetAll()`).
Arm faults **after** the fixtures exist.

| # | Test name | Steps and assertions | Must fail today with | Pins |
|---|---|---|---|---|
| 1 | `extract publishes nothing when its PDF cannot be written` | 3-page doc; `failAtFileSync("scan.pdf.tmp")`; `runCatching { extractPages(id, listOf(0, 2)) }` fails; count unchanged; source digest unchanged; `assertClean` after a new process. | `extract must fail when its PDF cannot be written` | `finalizeDoc` swallow removed |
| 2 | `compress publishes nothing when its PDF cannot be written` | Same, with `compressScan(id, 50, null)`. | `compress must fail when its PDF cannot be written` | same |
| 3 | `duplicate death before metadata leaves no visible copy` | `dieAtFileSync("meta.txt.tmp")`; `runCatching { duplicateDocument(id, "Copy") }`; probe fired; new process; `listDocuments().size == 1`; `assertClean`. | `expected:<1> but was:<2>` (today every file, including `meta.txt`, is already copied into a visible folder, `:1008-1010`) | §5.2 |
| 4 | `createDocument death leaves no visible or orphan directory` | `dieAtFileSync("scan.pdf.tmp")`; `runCatching { createDocument("X", listOf(ImportFixtures.image())) }`; new process; `assertClean`; size 0. | `visible document <id> has no scan.pdf` (today `meta.txt` is written before the PDF, `:1314-1317`) | `createDocument` via staging |
| 5 | `extract compress and duplicate outputs satisfy I1 I4 I5 I10` | Run all three on one 3-page source (with `DocFixtures.withOcr` on the source). Each output: `assertI1`, `assertI4`, `assertI10`. The source digest is unchanged after all three. Duplicate: `ocr.txt` is byte-identical to the source's. | `document <id> has neither ocr_pending nor text …` (extract output, F10 `:1321`) | the §5.1 pending step |
| 6 | `three hundred page compress completes` | `DocFixtures.saved(pages = 300)` (small pages); `compressScan(id, 50, null)`; `assertI1(out)`; `out.pageCount == 300`. | green (plan §6 gate; the memory bound is structural, see DoD) | `compressScan` loop |
| 7 | `duplicate never copies hidden scratch files` | Write `.page_000.jpg.abc.swtmp` into the source; `duplicateDocument`; the copy folder has no name starting with `.`. | `duplicate copied scratch files: [.page_000.jpg.abc.swtmp]` | §5.2 filter |
| 8 | `mergePdfUris failure leaves no directory in documents` | Two `Uri.fromFile` of non-PDF bytes; `runCatching { mergePdfUris(uris, null) }` fails; the `documents/` digest is unchanged; `op_staging` empty. | green (characterisation; the happy path is DEVICE-UNPROVEN, F22) | staging use |

## 5. Implementation notes

### 5.1 `buildAndPublish(…, copyPdf = true)`

`build` must place `scan.pdf` in staging itself. In §5.1 of PM-2.1b, **skip** steps 3.3–3.5 (no OCR read, no `writePdfInto`, no `text_layer.flag` write) and replace step 3.8's PDF check with:

- `pdf = File(staging, "scan.pdf")`. Require `pdf.isFile && pdf.length() > 0`, otherwise throw `IOException("Copy of the PDF is missing")`.
- If `PdfProtection.isEncrypted(pdf) == true`: require `File(staging, "locked.meta").exists()`, otherwise throw `IOException("Protection record missing")`.
  PM-3.2b adds `verifyEncrypted` here.
- Else, if pages are non-empty: `PdfVerifier.verify(pdf, expectedPages = pages.size)`.
- The page-order check (3.2) applies only when pages are non-empty. A legacy PDF-only document has none (`:154`).
- Steps 3.6 (`writeMeta`) and 3.7 (`ocr_pending` when text is missing) still run.

### 5.2 `duplicateDocument`

```kotlin
val src = File(docsDir, id)
val doc = loadDocument(src) ?: throw IllegalStateException("Scan not found")
val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: nextDocumentName()
val inherited = File(src, "meta.txt").readLines().getOrNull(2)?.takeIf { CategoryRules.isValid(it) }
val files = src.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty()
buildAndPublish(name, inherited, files.sumOf { it.length() }, copyPdf = true) { s ->
    files.forEachIndexed { i, f ->
        currentCoroutineContext().ensureActive()
        if (f.name == "meta.txt") {
            val kept = f.readLines().filterNot {
                it.startsWith(EXPORTS_KEY) || it.startsWith(EXPORT_SIGS_KEY) || it.startsWith(EXPORT_MADE_KEY)
            }
            Durable.writeFile(File(s, "meta.txt"), (kept.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        } else {
            Durable.copyFile(f, File(s, f.name))
        }
        OpFaults.at("op:page:$i")
    }
}
```

- It copies `locked.meta`, `vault.flag`, `txn.txt`, `ocr*` and `text_layer.flag` exactly as today (`:1008-1010`), minus dot-files.
- `writeMeta` in step 3.6 keeps the extra lines and sets name, createdAt and category (`:188-201`).
- This is today's semantics, with the meta rewrite now atomic.

### 5.3 Ledger

`buildAndPublish` gains `copyPdf` here and `seal` in PM-2.2b. Whichever card runs second rebases onto the other; the parameters are independent.

## 6. Acceptance

1. Gate G. Expected Linux total: **840 + 8 = 848** (if PM-2.2b ran first: 859 + 8 = 867), 0 / 0, on both runs.
2. `ScanRepositoryTest` (`:273-320, :489`), `PageSelectTest`, `CategoryPersistTest`, `UiSmokeTest:1298` and `LockGuardTest#4` pass **unedited**.
3. **DEVICE D3** (needs H1):
   - merge two device PDFs via Tools → Merge → device PDFs and check the page count;
   - compress a 30-page document and force-stop mid-progress (`adb shell am force-stop com.scanwhirl.app.test`);
   - relaunch: no partial document, and the source has the same size and pages.

   Until then: **DEVICE-UNPROVEN**.

## 7. What not to do

- Do not change the UI callers or return types.
- Do not regenerate the PDF for duplicate. Locked copies must stay byte-identical (`LockGuardTest#4`).
- Do not keep `finalizeDoc` "just in case". Its swallow is the defect.
- Do not read all pages before writing any (compress). One `recompressJpeg` result lives at a time.

## 8. Rollback

Revert. On-disk formats are unchanged.

## 9. Size

M. One session. If it runs long, split as (a) extract, compress, createDocument and (b) duplicate, mergePdfUris. Gate G after each; totals are +5 (tests 1, 2, 4, 5, 6) and then +3.

## 10. Definition of done

- [ ] `finalizeDoc` and `newDocDir` no longer exist, and no `writeBytes(` remains in `ScanRepository.kt` tool functions.
- [ ] `compressScan` has no `.map {` over `pageFiles` producing a list of byte arrays (reviewer reads 5 lines).
- [ ] The red output for tests 1, 2, 3, 4, 5 and 7 is pasted.
- [ ] Two Linux runs: 848 (or 867) / 0 / 0. Lint has no new errors. D3 is run or marked DEVICE-UNPROVEN.
