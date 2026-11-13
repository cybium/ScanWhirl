# PM-2.2c — Organize screen uses the draft (fixes D1/D2)

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decision A2. Needs PM-2.2b. **PM-2.3b must follow before any
release build.** Until then, the full-page viewer still edits in place when no draft exists.

## 1. Goal

`DocumentViewerActivity` page edits (rotate, delete, move, extract-from-selection) write to the draft. The saved document
changes only on **Save as new copy** or a confirmed **Replace original**. **Discard** always works, including after a force-stop.

**User-visible effect.**
- A bar appears after the first edit: "Draft · N pages · K changes" with Discard · Replace original · Save as new copy.
- Back with changes offers Save as new copy / Discard / Keep editing.
- The false message "Your edits are not in the original PDF yet" (`strings.xml:119`) is gone.
- In Trash, a replaced version shows as "‹name› (previous version)".

## 2. Invariants established

- **I7** "While a draft exists, its base document is byte-identical to its state at draft creation." It now holds for the editor's UI path.
- **I8**: via PM-2.2b, now reachable from the UI.
- **I1**: "the Result screen and row count" — after Save or Replace, the Home row count equals the pages in the output.

## 3. Files to touch

| File | Where | Change |
|---|---|---|
| `ui/DocumentViewerActivity.kt` | fields | Remove `snapshotDir`, `snapshotJob`, `snapshotScope`, `startSignature`, `dirty` and the `signature()` use. Add `private var pages: List<File> = emptyList()` and `private var draft: DraftStore.Draft? = null`. |
| | `onCreate` `:164-178` | Keep `reloadDocument()` and `firstLoad = true`; delete the snapshot launch. |
| | `onDestroy` `:194-204` | Keep `commitPendingDelete()`; delete the snapshot lines. |
| | `onResume` `:206-220` | If `document != null && firstLoad`, call `reloadDocument()` (no signature comparison). |
| | `reloadDocument` `:226-251` | After loading `doc`: `pages = withContext(IO) { repo.draftPages(id) }` and `draft = withContext(IO) { repo.draftOf(id) }`, then `updateDraftBar()`. |
| | `refreshGrid` `:256`, `diskIndex` `:278` | Use `pages` instead of `doc.pageFiles`. |
| | `rotateSelected` `:327-351` | Inside IO: `commitPendingDeletesNow()`; `now = repo.draftPages(id)`; `idx = paths.map { p -> now.indexOfFirst { it.path == p } }.filter { it >= 0 }`; `idx.forEach { repo.draftRotate(id, it, clockwise) }`; return `idx`. On main: `reloadDocument()`, then re-select `idx.mapNotNull { pages.getOrNull(it)?.path }`. A rotated page gets a new file, so its selection is re-mapped by index. |
| | `commitPendingDeletes` `:433-437`, `commitPendingDeletesNow` `:457-461` | Replace `repo.getDocument(id)` + `deletePage` with `val now = repo.draftPages(id); val i = now.indexOfFirst { it.path == path }; if (i >= 0 && now.size > 1) repo.draftDelete(id, i)`. |
| | `extractSelected` `:398-404` | Indices come from `repo.draftPages(id)`. If `repo.draftOf(id) == null`, call `repo.extractPages(id, indices)` (unchanged path); otherwise `repo.saveDraftAsNew(id, repo.extractedName(doc.name, indices), indices)`. |
| | `reorderPages` `:477-479` | `val now = repo.draftPages(id); val from = now.indexOfFirst { it.path == baseFile.path }; if (from >= 0 && from != to) repo.draftMove(id, from, to)`. |
| | `handleEditorBack` `:796-808` | If `(draft?.changes ?: 0) == 0`, `finish()`. Otherwise a `SwDialog.card` with title `draft_back_title` and message `draft_back_msg`. Positive `draft_save_new` → `saveDraftFlow()`; neutral `draft_discard` → `repo.discardDraft(id)`, then `finish()`; negative `draft_keep_editing`. |
| | `saveAsNewDocument`, `takeSnapshot`, `restoreSnapshot`, `signature` `:814-~890` | **Delete.** Add `saveDraftFlow()`, `replaceFlow()`, `discardFlow()` and `updateDraftBar()` (§5). |
| `store/ScanRepository.kt` | `extractPages` `:1055-1058` | Move the name formula into `internal fun extractedName(name: String, indices: List<Int>): String`; `extractPages` calls it. |
| `res/layout/<the layout inflated by DocumentViewerActivity>` | above the bottom dock | A `LinearLayout` `@+id/draftBar` (`gone` by default) holding a `TextView` `@+id/draftHeader` and three buttons, `@+id/btnDraftDiscard`, `@+id/btnDraftReplace` and `@+id/btnDraftSave`, in the existing dock button style of that layout. |
| `ui/TrashActivity.kt` | where a row's title is bound from `item.document.name` | If `item.previousToken != null`, use `getString(R.string.trash_previous_version, name)`. |
| strings | 13 keys | §5.4. Remove `save_changes_title`, `save_changes_msg` and `overwrite_pdf` from both locales **only if** lint reports them unused and no test references them. |

**Existing tests that change.** In each, the production line that changed is the in-place call replaced above.

- `UiSmokeTest.kt:1108` "page delete is deferred until undo expires" (production `:436`):
  - `:1117` stays (no draft before commit);
  - `:1125-1128` polls `repo.draftPages(id).size == 1` **and** asserts `repo.getDocument(id)!!.pageFiles.size == 2` (base untouched).
- `UiSmokeTest.kt:1133` "undo keeps the page on disk": `:1153-1156` additionally asserts `repo.draftOf(id) == null`. Undo means no edit was recorded.
- `UiSmokeTest.kt:1161` "drag reorder persists the new page order" (production `:479`):
  - poll on `repo.draftPages(doc.id)` colours (green first, red last);
  - assert the base still starts with red;
  - then `runBlocking { repo.saveDraftAsNew(doc.id, null) }` and assert the new document's colours. This is the plan §5.4 rename: "persists in the draft and in the saved copy".
- Any other test that turns red must be classified in the report. A test that asserted an in-place base change through this screen is rewritten the same way, with the citation. Anything else means **stop**. Candidates: `ExportHygieneTest:176`, `Phase5ScreensTest`.

## 4. Red tests first

Class `app/src/test/java/com/scanwhirl/app/ui/OrganizeDraftUiTest.kt`. Use the `UiSmokeTest` patterns: `seedDoc`, `pollUntil`, `ActivityScenario`, `ShadowDialog`.

| # | Test name | Steps and assertions | Must fail today with |
|---|---|---|---|
| 1 | `rotating in the editor leaves the saved document byte identical until Save` | 3-page doc; `d0 = dirDigest(docDir)`; `toggleSelected(0)`; `rotateSelected(true)`; `pollUntil { repo.draftOf(id) != null \|\| dirDigest(docDir) != d0 }`; `assertEquals("the saved document changed before Save", d0, dirDigest(docDir))`. | `the saved document changed before Save` (today `rotatePage` at `:338`) |
| 2 | `back with changes offers Save as new copy Discard and Keep editing` | After a rotate, `onBackPressedDispatcher.onBackPressed()`. The latest `AlertDialog` has positive / neutral / negative texts equal to `draft_save_new` / `draft_discard` / `draft_keep_editing`. | `expected:<Save as new copy> but was:<…>` (today's `overwrite_pdf`, `:804`) |
| 3 | `discard restores the original page list` | Rotate + delete (commit); click `btnDraftDiscard` → confirm. The grid paths equal the base page paths, `draftOf == null`, base digest is `d0`. | `btnDraftDiscard not found` |
| 4 | `Save as new copy creates the edited copy and keeps the original` | Delete page 0 (commit); `btnDraftSave` → the name dialog is prefilled with `draft_edited_name` → OK. A new document has 2 pages, base digest `d0`, the activity is finishing. | `btnDraftSave not found` |
| 5 | `Replace original asks to confirm and puts the previous version in Trash` | Delete page 0; `btnDraftReplace`; the dialog title equals `getString(draft_replace_title, name)`; OK. The base id has 2 pages, Trash has one entry with 3 pages. `TrashActivity` shows text `getString(trash_previous_version, name)`. | `btnDraftReplace not found` |
| 6 | `reopening after process death shows the draft` | Rotate; close the scenario; a new `ScanRepository(app)`; relaunch. `draftBar` is visible, `draftHeader` text equals `getString(R.string.draft_header, 3, 1)`, and page 0's path is under `drafts/`. | `draftBar not found` |

**Keeping the red step compilable.** Before the views, strings and draft API exist, look views up with
`activity.resources.getIdentifier("btnDraftDiscard", "id", activity.packageName)` and strings by name the same way.
Assert `assertNotNull("btnDraftDiscard not found", view)`. Switch to `R.id` / `R.string` after the green step. The draft
API exists from PM-2.2b, so `draftOf` and `draftPages` compile.

## 5. Implementation notes

### 5.1 `updateDraftBar()`

`binding.draftBar.isVisible = (draft?.changes ?: 0) > 0`, and `draftHeader.text = getString(R.string.draft_header, pages.size, draft?.changes ?: 0)`. It is called at the end of `reloadDocument`.

### 5.2 Flows (all repository calls in `withContext(Dispatchers.IO)`, with errors toasted as `page_op_failed`)

- **`saveDraftFlow()`:**
  1. `commitPendingDeletesNow()`.
  2. `SwDialog.input(this, hint = getString(R.string.draft_save_new), initial = getString(R.string.draft_edited_name, doc.name))`.
  3. OK → `repo.saveDraftAsNew(id, text)` → toast `draft_saved_new` → `finish()`.
- **`replaceFlow()`:**
  1. `commitPendingDeletesNow()`.
  2. Confirm with title `draft_replace_title` (name) and message `draft_replace_msg` (name).
  3. OK → `repo.replaceWithDraft(id)` → toast `draft_replaced` → `reloadDocument()`.
- **`discardFlow()`:** confirm `draft_discard_msg` (changes count) → `repo.discardDraft(id)` → clear `pendingDeletes` → `reloadDocument()`.

**Errors.** `DraftStore.DraftStale` and `DraftDamaged` show their message in a `SwDialog.card` with a single **Discard** action. That is the only safe choice (PM-2.2b §5.2).

### 5.3 Behaviours to keep

- Locked documents: `draftRotate` throws `DocumentLocked`, which shows as today's toast.
- Share, Text, More and Save to Documents keep working on the **saved** document. The dock is unchanged until PM-1.3.
- `DirectScanSaveTest` and the post-scan landing (`MainActivity.kt:1720`) are unchanged. Opening the screen creates no draft.

### 5.4 Strings

Add to `values` and `values-hi`; prefix each Hindi value with `<!-- review -->`.

| Key | English | Hindi draft |
|---|---|---|
| `draft_header` | Draft · %1$d pages · %2$d changes | ड्राफ़्ट · %1$d पेज · %2$d बदलाव |
| `draft_save_new` | Save as new copy | नई कॉपी के रूप में सहेजें |
| `draft_replace` | Replace original | मूल को बदलें |
| `draft_discard` | Discard | हटाएँ |
| `draft_keep_editing` | Keep editing | बदलाव जारी रखें |
| `draft_back_title` | Unsaved page edits | बिना सहेजे पेज बदलाव |
| `draft_back_msg` | Your saved document hasn’t changed. Save these edits as a new copy, or discard them. | आपका सहेजा दस्तावेज़ नहीं बदला है। इन बदलावों को नई कॉपी के रूप में सहेजें या हटाएँ। |
| `draft_replace_title` | Replace “%1$s”? | “%1$s” को बदलें? |
| `draft_replace_msg` | The current version moves to Trash as “%1$s (previous version)”. You can restore it from there. | मौजूदा संस्करण ट्रैश में “%1$s (पिछला संस्करण)” के रूप में चला जाएगा। आप उसे वहाँ से वापस ला सकते हैं। |
| `draft_discard_msg` | Discard %1$d page edits? The saved document stays as it is. | %1$d पेज बदलाव हटाएँ? सहेजा दस्तावेज़ जैसा है वैसा रहेगा। |
| `draft_saved_new` | Saved as “%1$s” | “%1$s” के रूप में सहेजा गया |
| `draft_replaced` | Replaced. The previous version is in Trash. | बदल दिया गया। पिछला संस्करण ट्रैश में है। |
| `trash_previous_version` | %1$s (previous version) | %1$s (पिछला संस्करण) |

## 6. Acceptance

1. Gate G. Expected Linux total: **859 + 6 = 865**, 0 / 0, on both runs.
2. **DEVICE D2, part 1** (needs H1), on a fresh 3-page document:
   1. Delete page 2 and rotate page 1. The header says 2 pages and 2 changes; Home still shows 3 pages.
   2. Force-stop, relaunch, open the document. The draft is intact.
   3. Discard: 3 pages, and the same size in Info.
   4. Repeat the edits, then Save as new copy: Home shows "‹name› (edited)" with 2 pages, and the original keeps 3.
   5. Repeat, then Replace: 2 pages, and Trash shows "‹name› (previous version)" with 3 pages.

   Until run: **DEVICE-UNPROVEN**.

## 7. What not to do

- Do not keep the snapshot copy "as a backup". It is the D1/D2 mechanism (F20).
- Do not delete or migrate existing `filesDir/edit_snapshot_*` folders (H3).
- Do not call `rotatePage/deletePage/movePage` from this screen any more.
- Do not create a draft in `onCreate` or on reload.
- Do not change `PageViewerActivity` (PM-2.3b).

## 8. Rollback

Revert. Drafts on disk become inert; the lease would then refuse in-place edits on those documents.
Run `repo.discardDraft` for each `drafts/<id>`, or delete `filesDir/drafts`, as part of a rollback.

## 9. Size

L. One focused session. It cannot be split cleanly: the UI and the test rewrites must land together.

## 10. Definition of done

- [ ] `Select-String -Path app/src/main/java/com/scanwhirl/app/ui/DocumentViewerActivity.kt -Pattern 'rotatePage|deletePage|movePage|edit_snapshot|restoreSnapshot'` returns nothing.
- [ ] The three listed `UiSmokeTest` rewrites are present, each with a comment citing the production line, and no other existing test changed without a classification note.
- [ ] Red output for tests 1, 2 and 6 is pasted.
- [ ] Two Linux runs: 865 / 0 / 0. Lint has no new errors. D2 part 1 is run or marked DEVICE-UNPROVEN.
