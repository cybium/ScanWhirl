# PM-2.1b — Publish primitive (`buildAndPublish`) and merge on top of it

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decisions A1 and A6: `61_ENGINE_DECISIONS.md` §2. Needs PM-3.1b
(`OpStaging`, `Durable.copyFile`, the locked-source refusal in `mergeDocuments`).

## 1. Goal

One private function builds a tool's output in `op_staging/<token>` and publishes it with one rename. Merge is the first user.

**User-visible effect.**
- A merge whose PDF cannot be written now **fails with a message**. Today it adds a document without a PDF.
- A crash during merge leaves nothing behind.
- Merge outputs get read by text recognition on the next launch.
- Otherwise nothing changes: same name, same order, same Home row.

## 2. Invariants established (for merge)

- **I1** "count of page files = `/Count` of `scan.pdf`".
- **I4** "No visible document lacks a verified `scan.pdf`. Nothing is published before verification."
- **I5** "every source directory is byte-identical".
- **I6** "Failure, cancel or simulated death at any stage leaves no visible partial document. Startup recovery leaves no staging older than the threshold and never deletes a draft." The threshold is zero for non-live entries (PM-3.1b §5.1).
- **I10** "Every new document is either fully indexed or carries `ocr_pending`."

## 3. Files to touch

| File | Function | Change |
|---|---|---|
| `store/ScanRepository.kt` | new `private suspend fun buildAndPublish(name: String, category: String?, expectedBytes: Long, build: suspend (File) -> Unit): ScanDocument` | §5.1. |
| | new `private suspend fun stagePageCopy(src: File, staging: File, index: Int): File` and `stagePageBytes(bytes: ByteArray, staging: File, index: Int): File` | `ensureActive()`; write `page_%03d.jpg` (PAGE_FORMAT, `:1329`) with `Durable.copyFile` / `Durable.writeFile`; `OpFaults.at("op:page:$index")`. |
| | `writePdf` `:1672-1687` | Split it: `private fun writePdfInto(dir: File, out: File, geo: Map<String, OcrPage>): Boolean` holds today's body, but takes `geo` as a parameter. `writePdf(dir, out)` becomes `writePdfInto(dir, out, if (Prefs.makeSearchable(context)) ocrStore.readGeo(dir.name) else emptyMap())`. The text-layer lookup stays inside `writePdfInto` and is gated on `Prefs.makeSearchable` exactly as at `:1678-1684`. |
| | `mergeDocuments` `:1062-1103` | Below the PM-3.1b refusal, the body becomes §5.2. `writeMeta`-before-PDF and the swallowed `catch` (`:1093-1100`) disappear. |
| `res/values{,-hi}/strings.xml` | 2 keys | §5.3. |

Untouched: `saveScan`, `importPdf`, Trash, `regeneratePdf`, the other tools (PM-2.1c) and every UI file.

## 4. Red tests first

Class `app/src/test/java/com/scanwhirl/app/store/OpPublishTest.kt` (Robolectric, sdk 35, `@After Faults.resetAll()`).

- **Fixtures:** two `DocFixtures.saved` documents (2 and 3 pages). Record `dirDigest` of both.
- **Arm every fault after the fixtures exist.** `saveScan` also syncs `meta.txt.tmp` and `scan.pdf.tmp`.
- **Simulate a new process:** `Faults.resetAll()` (it clears the live set), then `repo.recoverOperations()`.

| # | Test name | Steps and assertions | Must fail today with | Pins |
|---|---|---|---|---|
| 1 | `merge publishes nothing when its PDF cannot be written` | `failAtFileSync("scan.pdf.tmp")`; `r = runCatching { merge }`. Assert `r.isFailure` ("merge must fail when its PDF cannot be written"), `listDocuments().size == 2`, source digests unchanged; after a new process, `assertClean`. | `merge must fail when its PDF cannot be written` | removal of the swallow at `:1096-1100` |
| 2 | `merge death at every stage leaves no visible or orphan directory after recovery` | Loop over the injectors below. For each: inject, `runCatching { merge }`, then `assertTrue("fault $x never fired", probe.fired)`, simulate a new process, `assertClean`, `size == 2`, and source digests unchanged. Injectors: `dieAtFileSync("meta.txt.tmp")`, `dieAtFileSync("scan.pdf.tmp")`, `dieAtOp("op:staged")`, `dieAtOp("op:page:1")`, `dieAtOp("op:pdf")`, `dieAtOp("op:meta")`, `dieAtOp("op:verified")`, `dieAtOp("op:before_rename")`. Final case: `dieAtOp("op:after_rename")` must give `size == 3` and the new document passes `assertI1` + `assertI4`. | `orphan directories under documents: [<id>]` (the first injector: today `meta.txt.tmp` dies after pages land in a visible folder, `:1074-1093`) | the staging build + the single rename |
| 3 | `merged document is queued for text recognition` | `merge`, then `assertI10(newId)`. | `document <id> has neither ocr_pending nor text for [page_000.jpg, …]` | the `ocr_pending` write in §5.1 |
| 4 | `merge keeps sources byte identical and satisfies I1 I4` | `merge`; source digests unchanged; `assertI1(new)`, `assertI4`; page order equals a then b (compare `Sha256` of the files). | green (characterisation, guards the refactor) | `:1076-1084` order semantics |
| 5 | `recovery removes dead staging and keeps a live one` | Create `op_staging/dead/x` by hand; `live = OpStaging.create(filesDir)`; `recoverOperations()`. `dead` is gone, `live` is present; then `OpStaging.release(live)`. | green (pins PM-3.1b sweep) | `OpStaging.sweep` |
| 6 | `recovery never touches documents trash drafts or import staging` | One document, one trashed document, `drafts/x/draft.txt`, `import_staging/y/f`. Digest each folder; `recoverOperations()`; all four digests are equal. | green | sweep scope |
| 7 | `publish rename failure leaves no visible document and cleans staging` | `failAtOp("op:before_rename")`; merge fails; `size == 2`; `op_staging` is empty **without** recovery (released in `catch`). | green (new seam) | the `catch (e: Exception)` cleanup |
| 8 | `low free space refuses before writing anything` | After the fixtures, `ScanRepository.usableSpace = { 0L }`; merge fails with message == `getString(R.string.op_no_space)`; `op_staging` has no entries; `size == 2`. | `merge must refuse when storage is low` (today it ignores space) | the §5.1 precheck |
| 9 | `cancel during merge leaves nothing behind` | `runBlocking { val job = async(Dispatchers.IO) { repo.mergeDocuments(ids, null) }; OpFaults.hook = { if (it == "op:page:0") job.cancel() }; runCatching { job.await() } }`. `size == 2`, `op_staging` empty, `assertClean`. Arm the hook **before** `async` starts, so declare `job` as a `lateinit var`. | green (new seam) | `ensureActive` in `stagePageCopy` |

## 5. Implementation notes

### 5.1 `buildAndPublish`: the protocol

Steps in order; the fault names are part of the contract.

1. **Precheck.** If `expectedBytes > 0 && usableSpace(docsDir) < expectedBytes * 2 + (16L shl 20)`, throw
   `IOException(context.getString(R.string.op_no_space))`. Use the `usableSpace` seam (`:78`).
2. `val staging = OpStaging.create(context.filesDir)`, then `OpFaults.at("op:staged")`.
3. `try {`
   1. `build(staging)`, then `currentCoroutineContext().ensureActive()`.
   2. `pages = pageFilesIn(staging)`. Require it is non-empty, and that every `pages[i].name == String.format(Locale.ROOT, PAGE_FORMAT, i)`; otherwise throw `IOException("Page order check failed")`. This mirrors `verifyPrepared` `:714-716`.
   3. Read OCR: `stageOcr = OcrStore(OpStaging.root(context.filesDir))`; `geo = if (Prefs.makeSearchable(context)) stageOcr.readGeo(staging.name) else emptyMap()`.
   4. `layered = writePdfInto(staging, File(staging, "scan.pdf"), geo)`. It **throws** on failure. Never catch it here.
   5. If `layered && pages.all { geo.containsKey(it.name) }`, then `Durable.writeFile(File(staging, "text_layer.flag"), ByteArray(0))`. Then `OpFaults.at("op:pdf")`.
   6. `writeMeta(staging, name, category)`, then `OpFaults.at("op:meta")`.
   7. `texts = stageOcr.readText(staging.name)`. If `pages.any { it.name !in texts }`, then `Durable.writeFile(File(staging, "ocr_pending"), ByteArray(0))`. Use a direct write: `OcrStore.markPending` refuses non-library folders (`OcrStore.kt:384-390`).
   8. Verify: `PdfVerifier.verify(File(staging, "scan.pdf"), expectedImages = pages)`. Then check `meta.txt` exactly as at `:721-722`. Then `OpFaults.at("op:verified")` and `Durable.syncDir(staging)`.
   9. `id = synchronized(TRASH_LOCK) { val id = freeLibraryId(); OpFaults.at("op:before_rename"); if (!staging.renameTo(File(docsDir, id))) throw IOException(context.getString(R.string.op_publish_failed)); id }`
   10. `OpStaging.forget(staging)`, then `OpFaults.at("op:after_rename")`.
4. `} catch (e: Exception) { OpStaging.release(staging); throw e }`. Use **Exception, never Throwable** (F4).
5. `Durable.syncDir(docsDir)`; `Durable.syncDir(OpStaging.root(context.filesDir))`. Both are best effort (F2).
6. `return loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Published document could not be opened")`.

**Rules that go with the protocol.**
- **No lock is held during step 3.1.** `TRASH_LOCK` is held only for the rename, as import does at `:1428-1434`.
- **Fsync points:** every page (`Durable.copyFile`/`writeFile`), `scan.pdf` (inside the generator, `PdfGenerator.kt:166`), `meta.txt` (`Durable.atomicWrite`), and the markers. Directory syncs are best effort.
- **Rollback:** before the rename, everything lives in staging and is released on any `Exception`. After the rename there is nothing to roll back.

### 5.2 `mergeDocuments` body

Below the PM-3.1b refusal line:

```kotlin
val pages = sources.flatMap { d -> d.pageFiles.filter { it.exists() && it.length() > 0 } }
if (pages.isEmpty()) throw IllegalStateException("No pages to merge")
val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
    ?: "Merged scan ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"
buildAndPublish(name, null, pages.sumOf { it.length() }) { staging ->
    pages.forEachIndexed { i, p -> stagePageCopy(p, staging, i) }
}
```

- `require(sourceIds.size >= 2)` stays first (`ScanRepositoryTest.kt:179`).
- `category = null` keeps today's classify-by-name (`:1093` → `writeMeta`).
- `.orig` is not copied today, and still is not.

### 5.3 Strings

Add to `values` and `values-hi`; prefix each Hindi value with `<!-- review -->`.

| Key | English | Hindi draft |
|---|---|---|
| `op_no_space` | Not enough storage space. Free some space and try again. | पर्याप्त जगह नहीं है। कुछ जगह खाली करके फिर कोशिश करें। |
| `op_publish_failed` | Could not add the new document. Try again. | नया दस्तावेज़ नहीं जोड़ा जा सका। फिर कोशिश करें। |

## 6. Acceptance

1. Gate G. Expected Linux total: **831 + 9 = 840**, 0 / 0, on both runs.
2. `ScanRepositoryTest`, `PageSelectTest` and `CategoryPersistTest` pass **unedited**. Any edit to them voids the card.
3. DEVICE: none for this card; D3 comes after PM-2.1c.

## 7. What not to do

- Do not keep a `try { … } catch (e: Exception) { pdfFile.delete() }` anywhere in the merge path.
- Do not build inside `documents/` or reuse `newDocDir()` (`:1300`).
- Do not hold `TRASH_LOCK`, `PDF_LOCK` or the OCR lock across `build(staging)`. `writePdfInto` takes `PDF_LOCK` itself, briefly, as today.
- Do not add a seal file or a Replace mode (PM-2.2b), and do not move import or new-scan save onto this function.
- Do not call `ocrStore.markPending(id)` on the staging id; it is a no-op there by design.

## 8. Rollback

Revert. Formats are unchanged. A leftover `op_staging/` is swept by PM-3.1b's recovery or ignored by older code.

## 9. Size

M. One session.

## 10. Definition of done

- [ ] `mergeDocuments` contains no `writeMeta`, `mkdirs` or `catch`.
- [ ] `buildAndPublish` cleanup is `catch (e: Exception)`, and the only rename sits inside `synchronized(TRASH_LOCK)`.
- [ ] The red output for tests 1, 2, 3 and 8 shows the stated messages.
- [ ] Two Linux runs: 840 / 0 / 0. Lint has no new errors.
