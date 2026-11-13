# PM-2.2b — Draft engine: manifest over an immutable base, Save as new, Replace with roll-forward

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decisions A1 (Replace) and A2: `61_ENGINE_DECISIONS.md` §2.
Needs PM-2.1b. The UI comes in PM-2.2c; this card changes no screen.

## 1. Goal

A repository-level page draft. Rotate, delete and move are recorded in `filesDir/drafts/<id>/` without touching the
document. **Save as new copy** and **Replace original** are built through `buildAndPublish`, and a crash in Replace's
two-rename gap is finished by startup recovery.

**User-visible effect: none.** No UI calls it yet. PM-2.2c wires it.

## 2. Invariants established

- **I6** "…Startup recovery leaves no staging older than the threshold and **never deletes a draft**." The exception is a draft whose base is gone from both the library and Trash.
- **I7** "While a draft exists, its base document is byte-identical to its state at draft creation."
- **I8** "After Replace, exactly one Trash entry '‹name› (previous version)' exists and restores byte-identically."
- **I1, I4, I5, I10** for Save as new and Replace outputs.

## 3. Files to touch

| File | Function | Change |
|---|---|---|
| `store/DraftStore.kt` (new) | `internal class DraftStore(filesDir: File)` | §5.1. |
| `store/OcrStore.kt` | new `internal fun putAll(id: String, texts: Map<String, String>, geo: Map<String, OcrPage>)` | `lock.withLock { docDir(id).mkdirs(); if (geo.isNotEmpty()) writeGeometry(id, geo.filterKeys { it in texts }, texts); if (texts.isNotEmpty()) writeTexts(id, texts) }`. Geometry goes first; the text rename is the commit point (`OcrStore.kt:154-158`). |
| `store/OpStaging.kt` | new `const val SEAL = ".publish"`, `class Seal(token, draft, draftCreated, replaces, pages, pagesSha256, pdfSha256)`, `fun writeSeal(dir, seal)`, `fun readSeal(dir): Seal?` | Header `scanwhirl-publish-v1`, `key=value` lines, written with `Durable.atomicWriteText`. Parse like `SaveFormat.readTxn` (`SaveTransaction.kt:141-154`): any missing or invalid field returns null. |
| `store/ScanRepository.kt` | `requireEditable` (from 3.1b) | Add, after the lock check: `if (drafts.exists(dir.name)) throw DraftStore.DraftOpen(context.getString(R.string.draft_open_refused))`. `requireUnlocked` is **not** changed. |
| | `protectPdf` (3.1b) | First line after loading: the same draft check. Protect is refused while a draft exists (A2). |
| | `buildAndPublish` | New parameter `seal: SealSpec? = null`, where `private class SealSpec(val draftId: String, val draftCreated: Long, val replaces: String?, val basePages: String?)`. See §5.3. |
| | `moveToTrash` `:847-876` | Extract `private fun trashUnderLocks(src: File, id: String, previousToken: String?): String` = today's `:854-870`. The marker gains a 4th line `previous=$previousToken` only when it is non-null. `moveToTrash` keeps its locks, its `loadDocument` and the vault check, then calls it. `TrashTest` must stay unedited. |
| | `TrashedDocument` `:828-833`, `trashedIn` `:888-898` | Add `val previousToken: String? = null` and parse the marker line starting `previous=`. |
| | draft API (new) | §5.2. |
| | `recoverOperations` | §5.4: roll-forward and draft cleanup, **before** `OpStaging.sweep`. |
| `ui/MainActivity.kt` | startup `:408-411` | `val rolled = runCatching { repo.recoverOperations() }.getOrDefault(0)`, then `if (rolled > 0) withContext(Dispatchers.Main) { refreshList() }`. |
| strings | 4 keys | §5.5. |

## 4. Red tests first

**Stub step.** Add `DraftStore`, the draft API and `putAll` with `TODO("PM-2.2b")` bodies, and run
`DraftEngineTest`. Every test fails with `kotlin.NotImplementedError: An operation is not implemented: PM-2.2b`.
Record that, then implement.

Class `app/src/test/java/com/scanwhirl/app/store/DraftEngineTest.kt` (Robolectric, sdk 35, `@After Faults.resetAll()`).
Fixture: `DocFixtures.saved(pages = 4)`, then `DocFixtures.withOcr`.

| # | Test name | Assertions | Pins |
|---|---|---|---|
| 1 | `draft edits never change the base document` | `d0 = dirDigest(docDir)`. Then `draftRotate(0, true)`, `draftDelete(1)`, `draftMove(0, 2)`, `draftRotate(1, false)`. `dirDigest == d0`; `draftPages(id).size == 3`; `draftOf(id)!!.changes == 4`. | I7, §5.2 |
| 2 | `a draft survives process death with an equal manifest` | After one rotate, `dieAtOp("draft:saved")` on a second rotate. A new `ScanRepository(app)` gives `draftOf` with `changes == 2`. Then `dieAtOp("draft:replacement_written")` on a third rotate: `draftOf` still has `changes == 2`, and the unreferenced `r_*` file is gone after `draftOf` (pruned). | §5.1 order |
| 3 | `discard deletes only the draft` | Drafts on two documents; `discardDraft(a)`. `drafts/a` is absent, `drafts/b` is untouched (digest), and `a`'s base digest is unchanged. | §5.2 |
| 4 | `save as new builds the draft pages and keeps the base` | rotate(0), delete(3); `saveDraftAsNew(id, null)`. Name == `getString(R.string.draft_edited_name, base.name)`; 3 pages; `assertI1/I4/I10`. New page 0's width == base page 0's height. `ocrDoc(new)["page_001.jpg"] == ocrDoc(base)["page_001.jpg"]`. Base digest unchanged; draft gone. | §5.2, §5.3 |
| 5 | `replace moves the base to Trash as previous version and publishes the draft under the same id` | `before = dirDigest(docDir)`; delete(0); `r = replaceWithDraft(id)`. `r.document.id == id`, 3 pages. `docDir/meta.txt` is byte-identical to the old one. Exactly one `listTrash()` entry has `previousToken != null` and `originalId == id`. `restoreFromTrash(r.trashKey)` gives a new id whose `dirDigest == before`. Draft gone; no `.publish` under `documents/`. | I8, §5.3 |
| 6 | `death between base to Trash and publish is rolled forward by recovery` | (a) `dieAtOp("replace:base_trashed")` → `documents/<id>` absent. New process: `recoverOperations() == 1`, then `docs/<id>` has 3 pages, `assertI1/I4`, previous version in Trash, draft gone, `op_staging` empty. A second `recoverOperations() == 0` with digests unchanged. (b) A fresh draft, then `dieAtOp("op:sealed")`: base digest unchanged; after recovery the draft is intact and `op_staging` is empty. | §5.4 |
| 7 | `in-place page edits and protect are refused while a draft exists` | `draftRotate(0)`; `rotatePage(id, 1)` throws `DraftOpen`; `protectPdf(id, "1234")` throws `DraftOpen`; base digest unchanged. Then `discardDraft`, and `rotatePage` succeeds. | lease |
| 8 | `a changed base page makes save as new and replace fail and keep the draft` | `draftRotate(0)`. Overwrite base `page_002.jpg` with other JPEG bytes directly (this bypasses the lease, as a legacy change would). `saveDraftAsNew` throws `DraftStale`; `replaceWithDraft` throws `DraftStale`. The draft still exists; `op_staging` empty; library count unchanged. | §5.2 hashing |
| 9 | `replace keeps OCR text for unchanged pages and marks the rest pending` | rotate(0); replace. `ocrDoc(id)` has `page_001..003` with the old texts and no `page_000`; `ocr_pending` exists. | §5.3 carry-over |
| 10 | `recovery removes a draft whose base is permanently deleted and keeps one whose base is in Trash` | Drafts on A and B. Trash A; trash B, then `deleteFromTrash`. `recoverOperations()`: draft A kept, draft B gone. | §5.4 |
| 11 | `a locked document cannot start a draft` | `DocFixtures.locked`; `draftRotate(0)` throws `DocumentLocked`; `drafts/<id>` absent. | §5.2 |

**Split point.**
- Session A: §5.1, §5.2, Save as new without the seal, and tests 1, 2, 3, 4, 7, 8, 10, 11. Gate total 856.
- Session B: seal, Replace, roll-forward, the `trashUnderLocks` refactor, and tests 5, 6, 9. Gate total 859.

## 5. Implementation notes

### 5.1 `DraftStore` (file `drafts/<id>/draft.txt`)

```text
scanwhirl-draft-v1
base=<id>
basePages=<64 hex>          # SaveFormat.pagesDigest formula, SaveTransaction.kt:178-179
created=<ms>
updated=<ms>
changes=<n>
next=<n>                    # next r_<n> number; replacement names are never reused
p=<page_NNN.jpg>\t<sha256>\t<r_<n>.jpg or ->\t<r_<n>.orig or ->
```

- `data class Entry(source, sourceSha, replacement: String?, replacementOrig: String?)` and `data class Draft(baseId, basePages, created, updated, changes, next, entries)`.
- **Validation on load:**
  - header exact; `base == folder name`;
  - names match `page_\d{3}\.jpg`, `r_\d+\.jpg`, `r_\d+\.orig`;
  - at least one entry; every referenced `r_*` file exists.
  - Otherwise throw `DraftDamaged(context string draft_damaged)`. Never return a partial draft.
- **Write order (the contract):**
  1. Write the new `r_<n>` file(s) fully and fsync (`Durable.copyFile` + `PageReplace.commit` inside the draft folder).
  2. `OpFaults.at("draft:replacement_written")`.
  3. `Durable.atomicWriteText(draft.txt, …)`, then `Durable.syncDir(draftDir)`.
  4. `OpFaults.at("draft:saved")`.
- **`load`** also deletes `r_*` files not referenced (pruning). All `DraftStore` use happens under `DRAFT_LOCK` (a new `private val DRAFT_LOCK = Any()` in the companion), so pruning never races a write.
- **`discard(id)`:** rename the folder to `drafts/.discard-<id>-<nonce>`, then `deleteRecursively`. Recovery also deletes any `.discard-*`.
- **Exceptions** (all `IOException`): `DraftOpen`, `DraftStale`, `DraftDamaged`, nested in `DraftStore`.

### 5.2 Draft API in `ScanRepository`

Every function is `withContext(Dispatchers.IO)` plus `synchronized(DRAFT_LOCK)`. No suspension happens inside `synchronized`.

- `fun draftOf(id): Draft?` and `fun draftPages(id): List<File>`. The second resolves entries to `drafts/<id>/r_n.jpg` or `documents/<id>/<source>`; with no draft it returns `getDocument(id)?.pageFiles.orEmpty()`.
- **Fresh draft** (private, on the first mutation only):
  - `doc` must load. If `doc.isLocked`, throw `DocumentLocked(locked_edit_refused)`.
  - `shas = doc.pageFiles.map { Sha256.of(it) }`; `basePages = Sha256.of(shas.joinToString("\n").toByteArray())`.
  - `changes = 0`, `next = 0`.
- `draftRotate(id, index, clockwise)`:
  - The source is the replacement if present, else the base page.
  - The orig is `replacementOrig` if present; else the base `page_NNN.orig` when it exists and the entry has no replacement; else none.
  - Copy to `r_<next>.jpg` (and `.orig`). Rotate each copy in place with `PageReplace.commit(ImageTools.stageRotate90(copy, clockwise))`; the copy is draft-owned and not yet referenced.
  - Save with `changes + 1`, `next + 1`.
- `draftDelete(id, index)`: `require(entries.size > 1) { "Can't delete the last page" }`, matching `:1157`.
- `draftMove(id, from, to)`: the same permutation as `:1232-1234`. If `from == to`, return without saving.
- `discardDraft(id)`.
- `saveDraftAsNew(id, name: String?, indices: List<Int>? = null)`:
  - `indices != null` means "extract these draft pages". It leaves the draft alone and writes no seal.
  - Otherwise `seal = SealSpec(id, draft.created, null, null)`.
  - Name: `name ?: getString(R.string.draft_edited_name, doc.name)`. Category = `doc.category`.
  - Locked base → `DocumentLocked`. A missing base → `DraftStale`.
  - Build: §5.3 materialise + OCR carry-over.
  - After publish, when `indices == null`: `drafts.discard(id)`, then delete `docs/<new>/.publish`.
- `replaceWithDraft(id): ReplaceResult(document, trashKey)`:
  - `require(changes > 0)`.
  - `pagesDigest(doc.pageFiles) == basePages`, else `DraftStale`.
  - `seal = SealSpec(id, created, replaces = id, basePages = draft.basePages)`.
  - Build: materialise, plus `Durable.copyFile` of base `meta.txt`, `vault.flag` and `txn.txt` when present.
  - After success: `drafts.discard(id)`; delete `docs/<id>/.publish`; `dropDerivedCaches(id)`.

### 5.3 Materialise, seal, and the Replace protocol (inside `buildAndPublish`)

**Materialise entry `e` at output index `i`.**
- If `e.replacement != null`: copy it to `page_%03d.jpg`, and `e.replacementOrig` to `page_%03d.orig` if present.
- Otherwise: copy `documents/<id>/<e.source>`, then `Sha256.of(out) == e.sourceSha` or throw `DraftStale(draft_stale)`; copy its `.orig` if it exists.
- Then `OpFaults.at("op:page:$i")`.

**OCR carry-over.** For entries without a replacement, read the base `ocrStore.readText(id)` and `readGeo(id)` once. Map `source → page_%03d(i)` and call `OcrStore(OpStaging.root(filesDir)).putAll(staging.name, texts, geo)` once, at the end of `build`.

**`buildAndPublish` with `seal != null`.**
- When `seal.replaces != null`, skip `writeMeta` (step 3.6): `meta.txt` was copied byte for byte.
- After step 3.8, call `OpStaging.writeSeal(staging, Seal(token = staging.name, draft = seal.draftId, draftCreated = seal.draftCreated, replaces = seal.replaces, pages, pagesSha256 = SaveFormat.pagesDigest(pages), pdfSha256 = Sha256.of(pdf)))`, then `OpFaults.at("op:sealed")`.
- Create-only (`replaces == null`): step 3.9 is unchanged.
- **Replace (`replaces = X`), step 3.9 becomes** `synchronized(PDF_LOCK) { OcrStore.locked { synchronized(TRASH_LOCK) { … } } }` (lock order F6):
  1. `base = File(docsDir, X)`. If it does not load, or `pagesDigest(pageFilesIn(base)) != seal.basePages`, throw `DraftStale`. If it is locked, throw `DocumentLocked`.
  2. `key = trashUnderLocks(base, X, staging.name)`, then `OpFaults.at("replace:base_trashed")`.
  3. If `!staging.renameTo(base)`:
     - try `File(trashDir, key).renameTo(base)` and delete its `trashed.txt`; if that succeeds, throw `IOException(op_publish_failed)`, and the normal `catch` releases staging;
     - otherwise call `OpStaging.forget(staging)` and throw `PublishPending(op_publish_failed)`, an `IOException` subclass. The `catch` must **not** release staging for `PublishPending`; recovery rolls forward.
  4. Return `X`. The rest of step 3.10 and onward is unchanged.

### 5.4 `recoverOperations()`: order matters

1. **Roll forward.** For each non-live `op_staging/<t>` whose `readSeal` is non-null with `replaces = X`, roll forward when all hold:
   - `!File(docsDir, X).exists()`;
   - a Trash entry has `originalId == X && previousToken == t`;
   - the staging pages and `pagesDigest` match the seal, and `Sha256(scan.pdf)` matches.

   Then under `TRASH_LOCK`: rename staging → `documents/X`, `OpStaging.forget`, and `count++`.
2. **Finish.** For each `documents/*/.publish`: when its `draft` exists, discard it only if `draft.created == seal.draftCreated`. Then delete `.publish`.
3. **Drafts.** Delete `drafts/.discard-*`. For each `drafts/<D>`: if `documents/D` is absent **and** no `listTrash()` entry has `originalId == D`, call `drafts.discard(D)`.
4. `OpStaging.sweep(filesDir)`: everything else that is not live, including sealed staging that failed a check.
5. Return `count`.

### 5.5 Strings

Add to `values` and `values-hi`; prefix each Hindi value with `<!-- review -->`.

| Key | English | Hindi draft |
|---|---|---|
| `draft_open_refused` | Finish or discard the unsaved page edits first. | पहले बिना सहेजे पेज बदलावों को सहेजें या हटाएँ। |
| `draft_stale` | This document changed after these edits started. They can only be discarded. | ये बदलाव शुरू होने के बाद दस्तावेज़ बदल गया। इन्हें केवल हटाया जा सकता है। |
| `draft_damaged` | These unsaved edits are damaged and can only be discarded. | ये बिना सहेजे बदलाव खराब हो गए हैं और केवल हटाए जा सकते हैं। |
| `draft_edited_name` | %1$s (edited) | %1$s (बदला गया) |

## 6. Acceptance

Gate G. Expected Linux total: **848 + 11 = 859** (if PM-2.1c has not run: 840 + 11 = 851), 0 / 0, on both runs.
`TrashTest`, `ScanRepositoryTest`, `OcrConsistencyTest` and `PageEditSafetyTest` pass **unedited**. No DEVICE step; D2 comes after PM-2.2c.

## 7. What not to do

- Do not copy all pages when a draft opens (G14), and do not create a draft on read (`draftOf` never writes).
- Do not apply the lease to `regeneratePdf` or `refreshPdfAfterIndex` (only `requireEditable`).
- Do not overwrite an `r_*` file. Every edit gets `next`.
- Do not "restore" a Replace by delete-then-copy (F20, G12). Two renames under the locks, roll forward on a crash.
- Do not roll forward without the Trash entry carrying `previous=<token>`. That check is what prevents resurrecting a document the user deleted.
- Do not add an Undo for Replace here (follow-up in `62` §7).

## 8. Rollback

Revert.
- Drafts on disk are ignored by older code.
- A sealed staging folder left behind is deleted by PM-3.1b's sweep, which runs in older code too. Only revert after recovery has run once, so no Replace stays half-done.

## 9. Size

L. Split point in §4.

## 10. Definition of done

- [ ] `requireEditable` has the lease check; `requireUnlocked` does not.
- [ ] `TrashTest` is unedited and green, proving `trashUnderLocks` changed no behaviour.
- [ ] The stub-red output is pasted, then the green run.
- [ ] Two Linux runs: 859 (or 851) / 0 / 0. Lint has no new errors.
