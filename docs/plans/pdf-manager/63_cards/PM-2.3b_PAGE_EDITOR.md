# PM-2.3b — Full-page editor writes to the draft

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decision A2. Needs PM-2.2c. This card is the engine-precise
version of PM-2.3 in `40_SONNET_TASKS.md`.

## 1. Goal

When `PageViewerActivity` is opened for a saved document (`EXTRA_DOC_ID`), crop, filter, rotate and delete edit the
draft entry. They never touch the library document. Staging mode (`EXTRA_STAGING`, used by scan review) is unchanged.

**User-visible effect.**
- Edits made in the full-page view show up in Organize's draft bar ("K changes").
- The saved document changes only on Save or Replace.
- "Original" filter undoes a filter-only edit.

## 2. Invariants established

- **I7** "While a draft exists, its base document is byte-identical to its state at draft creation": now for every page-edit entry point.
- **I9 groundwork**: filters always apply from the unfiltered version, never stacked (matches `ScanRepository.kt:1263-1267`).

## 3. Files to touch

| File | Function | Change |
|---|---|---|
| `store/ScanRepository.kt` | new `suspend fun draftCrop(id, index, rect: RectF): Draft` | §5.1. Same lock and threading as `draftRotate` (PM-2.2b §5.2). |
| | new `suspend fun draftFilter(id, index, filter: ScanFilter): Draft` | §5.2. |
| `ui/PageViewerActivity.kt` | `reloadSaved` `:161-176` | `pages = repo.draftPages(id).filter { … }`. The filter on `pendingDelete`/`committingDeletes` is unchanged. |
| | `rotateCurrent` `:244` | `repo.draftRotate(id, pos, clockwise)`. |
| | `applyFilter` `:318-319` | `repo.draftFilter(id, pos, filter)`. After `reload()`, set `filterChoice[pages[pos].path] = filter` (the edited page has a new file). |
| | crop commit `:494` | `repo.draftCrop(id, pos, fileRect)`. |
| | `commitPendingDelete` `:523-525`, `commitPendingDeleteNow` `:548-550` | `val now = repo.draftPages(id); val i = now.indexOfFirst { it.path == path }; if (i >= 0 && now.size > 1) repo.draftDelete(id, i)`. |

Staging-mode branches (`dir != null` at `:239-240`, `:490-491`, `:340-347`, `:514-517`) are **not** touched.

**Existing tests that change** (production lines `:244`, `:318`, `:494`, `:525` now write to the draft):

- `UiSmokeTest.kt:502` "page viewer delete is deferred until undo expires": after commit, assert `repo.draftPages(id).size == n - 1` and the base page count is unchanged.
- `UiSmokeTest.kt:855` (the crop-in-saved-mode test): assert the **draft** page 0 has the cropped size and the base page 0 keeps its size.
- Any other red test must be classified in the report. If it asserted an in-place base change through this screen, rewrite it to the draft with a citation; otherwise **stop**. Candidates: `UiSmokeTest:483`, `Phase5ScreensTest:688`.

## 4. Red tests first

Class `app/src/test/java/com/scanwhirl/app/ui/PageEditorDraftTest.kt`. Tests 2, 4 and 5 are repository-level; they start
red against `TODO("PM-2.3b")` stubs for `draftCrop`/`draftFilter`.

| # | Test name | Steps and assertions | Must fail today with |
|---|---|---|---|
| 1 | `crop in the page viewer writes to the draft not the document` | `d0 = dirDigest(docDir)`. Launch `PageViewerActivity.intent(app, id, 0)` and crop using the same mechanism as `UiSmokeTest.kt:855`. Poll until a draft exists **or** the digest changed. Then `assertEquals("the saved document changed before Save", d0, dirDigest(docDir))`, and `PageReplace.bounds(draftPages(id)[0])` is the crop size. | `the saved document changed before Save` (`:494` `cropPage`) |
| 2 | `filter applies from the original without stacking and Original removes the replacement` | `draftFilter(0, BLACKWHITE)`, then `draftFilter(0, ENHANCE)`. The entry's `replacementOrig` file has `Sha256 == entry.sourceSha`; the replacement differs from both the base and the BLACKWHITE result. Then `draftFilter(0, ORIGINAL)`: `replacement == null && replacementOrig == null`. | `NotImplementedError: … PM-2.3b` |
| 3 | `rotate and delete in the page viewer write to the draft` | Click `btnRotateRight`; poll for `draftOf(id)?.changes == 1`; base digest unchanged. Then `btnDelete`, `commitPendingDelete()`: `draftPages` size is n − 1 and the base is still unchanged. | `the saved document changed before Save` (`:244`) |
| 4 | `crop then discard leaves the base byte identical` | `draftCrop(0, RectF(0f, 0f, 100f, 150f))`; `discardDraft`; digest == `d0`; `drafts/<id>` absent. | `NotImplementedError` |
| 5 | `saved copy has the cropped size and the original keeps its own` | First apply `setPageFilter(id, 0, BLACKWHITE)` with **no** draft (in-place filter creates a base `.orig`). Then `draftCrop(0, rect 100×150)`; `saveDraftAsNew`. The new `page_000.jpg` and `page_000.orig` are both 100×150 (±1). Base page 0 keeps its size. | `NotImplementedError` |

## 5. Implementation notes

Entry `e` at `index`. `base(e)` is `documents/<id>/<e.source>`, and `baseOrig(e)` is its `.orig` when it exists.
`n = draft.next`. Every new file follows the PM-2.2b §5.1 write order: write and fsync, then `draft:replacement_written`,
then the manifest, then `draft:saved`. Every successful edit does `changes + 1`.

### 5.1 `draftCrop`

1. `cur = e.replacement?.let { draftFile(it) } ?: base(e)` and `orig = e.replacementOrig?.let { draftFile(it) } ?: (if (e.replacement == null) baseOrig(e) else null)`.
2. `Durable.copyFile(cur, r_n.jpg)`, then `PageReplace.commit(ImageTools.stageCrop(r_n.jpg, rect))`.
3. If `orig != null`: `Durable.copyFile(orig, r_n.orig)`, then `PageReplace.commit(ImageTools.stageCrop(r_n.orig, rect))`.
   This mirrors `editPageAndOriginal` `:1209-1222`.
4. The new entry is `(source, sourceSha, "r_n.jpg", if (orig != null) "r_n.orig" else null)`, and `next = n + 1`.

### 5.2 `draftFilter` (mirrors `setPageFilter` `:1268-1297`)

- **Unfiltered version:** `u = e.replacementOrig?.let { draftFile(it) } ?: (if (e.replacement == null) baseOrig(e) else null)`.
- **`filter == ORIGINAL`:**
  - If `u == null`: no-op. Return the draft unchanged, without saving or counting.
  - Else, if `e.replacementOrig != null && Sha256.of(u) == e.sourceSha`: the entry becomes a pure base reference, `(source, sourceSha, null, null)`.
  - Else: `Durable.copyFile(u, r_n.jpg)`, and the entry becomes `(source, sourceSha, "r_n.jpg", null)`.
- **Other filters:**
  1. `src = u ?: (e.replacement?.let { draftFile(it) } ?: base(e))`.
  2. `newOrig` = `e.replacementOrig` if it is non-null (immutable, keep the name). Otherwise copy `src` to `r_n.orig` and use that.
  3. `bmp = BitmapFactory.decodeFile(src.path) ?: throw IllegalStateException("This page's kept original can't be read, so nothing was changed.")`
  4. `out = filter.applyTo(bmp)`, then `PageReplace.commit(PageReplace.stageJpeg(r_n.jpg, out, 92))`. Recycle both bitmaps in `finally`, as at `:1288-1293`.
  5. The entry becomes `(source, sourceSha, "r_n.jpg", newOrig)`.
- Rotation of an entry that has `replacementOrig` is already handled by PM-2.2b.

### 5.3 OCR

OCR carry-over at Save happens only for entries without a replacement (PM-2.2b §5.3). The ORIGINAL → pure-reference
rule above therefore restores carry-over for filter-only edits.

## 6. Acceptance

1. Gate G. Expected Linux total: **865 + 5 = 870**, 0 / 0, on both runs.
2. **DEVICE D2, part 2** (needs H1):
   - Organize → tap page 1 → crop → Back. The bar shows "1 change", and the Home thumbnail is unchanged.
   - Save as new copy: the new document's page 1 is cropped; the original is not.

   Until run: **DEVICE-UNPROVEN**.

## 7. What not to do

- Do not touch staging-mode code paths (scan review depends on them, `ScanReviewFocusArrangeTest`).
- Do not overwrite an existing `r_*` file. Every edit uses `next`.
- Do not apply a filter to an already filtered image (stacking). Always start from `u`.
- Do not decode at full resolution where the existing code samples (`ImageTools.stageCrop` already samples to `WORK_DIM`).

## 8. Rollback

Revert. Drafts created by this card remain readable by PM-2.2b code.

## 9. Size

M. One session.

## 10. Definition of done

- [ ] `Select-String -Path app/src/main/java/com/scanwhirl/app/ui/PageViewerActivity.kt -Pattern 'repo\.(rotatePage|setPageFilter|cropPage|deletePage)'` returns nothing.
- [ ] Staging-mode lines are byte-identical to before (the reviewer diffs `:239-240`, `:340-347`, `:490-491`, `:514-517`).
- [ ] Red output for tests 1–5 is pasted.
- [ ] Two Linux runs: 870 / 0 / 0. Lint has no new errors. D2 part 2 is run or marked DEVICE-UNPROVEN.
