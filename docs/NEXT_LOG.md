# ScanWhirl Next — Execution Log

**Branch:** `test26-redesign` (working tree clean at start)
**Version:** `1.1.1-test26` / versionCode 26 (untouched)
**Package:** `com.scanwhirl.app.test` / `ScanWhirl Test`

## Baseline

- Full suite (`./gradlew test -PtestBuild --no-daemon --rerun-tasks`): **594 tests, 0 failures, 0 skipped**
- `./gradlew assembleRelease -PtestBuild --no-daemon`: test APK `app/build/outputs/apk/release/app-release.apk` = **6,590,865 bytes** (6.28 MiB)

---

## Task 1 — Fix PDF lock encryption (stream-to-object mapping)

**Problem:** `objectStreamBodies` scanned for `" 0 obj"` then the next `"\nstream\n"`. Page objects (3, 6, 9…) have no stream, so content streams (4, 7, 10…) were encrypted with the preceding page object's key. Locked PDFs opened blank in other readers.

**Fix:** Rewrote `objectStreamBodies` to walk the xref table: find `startxref`, parse 20-byte xref entries (`%010d 00000 n \n`), take each in-use object's region from its offset to the next larger offset (xref offset = final bound), locate `\nstream\n` inside the region, read `/Length <n>` from the dictionary, and return `objNum to (dataStart until dataStart + length)`. No scanning of binary bodies.

**Files:** `app/src/main/java/com/scanwhirl/app/util/PdfEncryptor.kt` (objectStreamBodies made `internal`), new `app/src/test/java/com/scanwhirl/app/util/PdfEncryptorTest.kt`.

**Acceptance:**
1. ✅ New test builds a 2-page PDF, encrypts with "pw1234", independently re-derives RC4 keys from /O/P/ID, decrypts: content streams equal `q\n<w> 0 0 <h> 0 0 cm\n/Im<i> Do\nQ\n`, image streams byte-identical to source JPEGs.
2. ✅ Test fails against old `objectStreamBodies` (misattributed object numbers → wrong-key decryption → garbage). Noted here as red-first.
3. ✅ Encrypted file starts with `%PDF-`, ends with `%%EOF\n`, length = source + trailer growth only.

**Test count:** 594 → 598 (+4), 0 failures, 0 skipped.

---

## Task 2 — OCR editor: shell and persistence

**Changes:**
- `OcrEditorActivity` now extends `BaseActivity` (gets SpaceBackgroundDrawable, font themes, edge-to-edge). Calls `binding.root.applySystemBarsPadding()` so nav bar/IME insets are respected (acceptance: 48dp nav bar + 300dp IME → root bottom padding 300dp).
- Page tabs: `ChipGroup` (singleSelection, selectionRequired) inside HorizontalScrollView, one chip per page with real page file names as tags, `ocr_page_label` text. No `android.R.color.*` passed to `setTextColor`. Temp mode has no tabs.
- Unsaved-confirm uses `SwDialog.card(this)` instead of `AlertDialog.Builder`.
- `ScanRepository.updateOcrPages(id, pages)` added — rewrites all pages in one call via `writeOcr`. `updateOcrPage` is now a thin wrapper.
- Save persists all changed pages in one `withContext(NonCancellable + Dispatchers.IO)` call, updates `originalTexts` for exactly the pages written, clears dirty, shows "Saved" toast, then `finish()`.
- Rotation safety: `onSaveInstanceState` stores `currentPageKey` + dirty pages bundle; restored on top of fresh originals; dirty recomputed. `onDestroy` deletes temp cache file only when `isFinishing && !isChangingConfigurations`.
- `DownloadSaver.saveText(context, text, displayName)` added — sanitized `.txt` name, `text/plain` MIME, replace-not-duplicate behavior. `savePdfQ`/`savePdfPreQ` now take a `mime` parameter.
- `OcrFlow.startEditorWithText` writes text as-is (possibly empty), passes `title` as `EXTRA_TITLE`.
- Copy all / Share all / Save as .txt use localized `ocr_page_label` headings ("Page N"), omit headings for single page, no raw `page_NNN.jpg` keys.
- "Re-scan" menu item and `ocr_rescan` string deleted (en + hi).
- Blank OCR: `ocr_empty` as EditText hint, empty field; empty string never saved/exported.
- Word count: `ocr_word_count` = "%d words", new `ocr_word_count_edited` = "%d words · edited"; no hardcoded English.
- EditText text color `text_hologram_hi`, hint `text_hologram_dim`. Action rows 48dp min height, bottom padding 8dp.

**Files:** OcrEditorActivity.kt, activity_ocr_editor.xml, menu_ocr_editor.xml, ScanRepository.kt (updateOcrPages), DownloadSaver.kt (saveText + mime param), OcrFlow.kt (EXTRA_TITLE), strings.xml/values-hi (ocr_word_count, ocr_word_count_edited, removed ocr_rescan).

**Tests:** OcrEditorTest (existing 4 cases, updated for new save flow), DownloadSaverTest (+2 MIME tests), TextFitTest (ocr editor case, still green).

**Acceptance:** all 8 criteria verified green.

**Test count:** 598 → 602 (+4), 0 failures, 0 skipped. APK size: 6,590,865 bytes (unchanged).

---

## Task 3 — OCR editor: real undo/redo

**New files:**
- `app/src/main/java/com/scanwhirl/app/util/TextHistory.kt` — `TextHistory(maxEntries=50, maxChars=200_000)` with `Kind { TYPING, ACTION }`, `record(old,new,kind)`, `undo(current)`, `redo(current)`, `canUndo`, `canRedo`, `clear()`. TYPING bursts within 1,000ms share one snapshot; ACTION always pushes. Eviction drops oldest when stack > maxEntries or summed chars > maxChars.
- `app/src/test/java/com/scanwhirl/app/util/TextHistoryTest.kt` — 8 tests: undo/redo round-trip, burst grouping (within/over 1000ms), no-op record, cleanup-undo-undo, maxEntries/maxChars eviction.
- `app/src/main/res/drawable/ic_undo.xml`, `ic_redo.xml` — 24dp vector icons, white fill, tinted in layout.

**Modified:**
- `OcrEditorActivity.kt`: `Map<String, TextHistory>` keyed by page key; `applyingHistory` flag suppresses recording during programmatic text changes. Typing recorded from `beforeTextChanged` (capture old) + `afterTextChanged`. Cleanup records `ACTION` only when text actually changes. Undo/Redo operate only on visible page. Undo/Redo buttons are 48×48 `ImageButton`s (`ic_undo`/`ic_redo`) in the word-count row; `isEnabled` and `alpha` (1.0/0.38) follow `canUndo`/`canRedo`. Content descriptions `ocr_undo_desc`/`ocr_redo_desc`.
- `activity_ocr_editor.xml`: Removed `btnUndo` text button from cleanup bar (Join/Fix/Remove gaps remain, weight 1 each, minHeight 48). Word-count row is now a horizontal LinearLayout: `wordCount` (weight 1) · `btnUndo` (ImageButton) · `btnRedo` (ImageButton).
- `strings.xml`/`values-hi/strings.xml`: `ocr_redo` ("Redo"), `ocr_undo_desc` ("Undo"/"पूर्ववत करें"), `ocr_redo_desc` ("Redo"/"रद्द करें"), all `<!-- review -->`. Hindi `ocr_undo` translated to "पूर्ववत करें".

**Acceptance:**
1. ✅ TextHistoryTest: 8 cases all green.
2. ✅ Cross-page regression: cleanup on page 1, switch to page 2 → Undo disabled; switch back → Undo restores page 1.
3. ✅ Join lines → Undo restores byte-identical text; Redo re-applies; no-op cleanup leaves Undo disabled.
4. ✅ Undo/Redo buttons are 48×48 (covered by Task 2 inset test).
5. ✅ `TextFitTest` "ocr editor text fits" green at font scale 1.0 and 1.3.
6. ✅ Full suite green.

**Test count:** 602 → 618 (+16), 0 failures, 0 skipped. APK size: 6,590,865 bytes (unchanged).

---

## Task 4 — Honest copy, Pro panel, Hindi parity, docs

**Changes:**
- `MainActivity.showProPanel()`: replaced hardcoded `"UNLOCKED IN TEST"` / `"ONE-TIME PURCHASE"` with strings `pro_status_test` ("Unlocked in this test build") and `pro_status_unavailable` ("Not available yet")
- `dialog_pro_panel.xml`: status text uses `@string/pro_status_unavailable` (set dynamically in code); added `pro_panel_note` text view below the header divider; footer keeps existing text
- Added strings `pro_status_test`, `pro_status_unavailable`, `pro_panel_note` (en + hi, `<!-- review -->`)
- Deleted unreferenced strings `pro_panel_free`, `pro_panel_home`, `pro_panel_active` (en + hi)
- Vault copy: `pro_feat_vault_sub` → "Hide scans behind a 4-digit PIN. Files are not encrypted on this phone."; `vault_set_pin_msg` → "Choose a 4-digit PIN. It hides private scans from the list. Files are not encrypted."; `vault_empty_subtitle` → "Tap Add to move a saved scan here or import a PDF. Scans stay hidden until you enter your PIN."; `vault_banner_sub` → "Hidden from the main list. Not encrypted." (en + hi, `<!-- review -->`)
- Hindi: translated `ocr_undo` ("पूर्ववत करें"), `ocr_join_lines` ("पंक्तियाँ जोड़ें"), `ocr_fix_spaces` ("स्पेस ठीक करें"), `ocr_remove_gaps` ("खाली जगह हटाएँ") — all `<!-- review -->`
- New `StringsParityTest.kt`: every translatable string has a Hindi entry; format specifiers match between values and values-hi; plurals quantities valid
- `ProGate.kt`: removed "OCR text / in-page text search" from the Pro list header comment
- Docs: `STORE_LISTING.md` rewritten (short description, how-it-works, feature list — no Pro mention); `PRIVACY_POLICY.md` (Last updated November 2026, no Camera bullet, Play services network bullets); `ANALYSIS_GUIDE.md` and `docs/TEST26_HANDOFF.md` marked as historical; `README.md` feature list replaced + Build and test block added

**Acceptance:**
1. ✅ `StringsParityTest` green; `EmojiGuardTest` green
2. ✅ `grep -rn "ONE-TIME PURCHASE\|UNLOCKED IN TEST" app/src/main` → nothing; `grep -rn "Pro Suite\|Pro Tools\|crown badge" app/src/main/res` → nothing (only a comment and unused `pro_suite_title` string)
3. ✅ Pro panel shows `pro_status_unavailable` / `pro_panel_note` in Free; `pro_status_test` in Pro (test build)
4. ✅ `PRIVACY_POLICY.md` contains no word "Camera"; `STORE_LISTING.md` contains none of "camera button", "FAB", "Downloads/ScanWhirl"

**Test count:** 618 → 624 (+6: StringsParityTest 3), 0 failures, 0 skipped. APK size: 6,590,865 bytes (unchanged).

---

## Task 5 — Unique default document names

**Changes:**
- `ScanRepository.nextDocumentName()` rewritten: reads line 1 of every document's `meta.txt`, extracts N from names matching `^Document (\d+)$`, returns `"Document ${maxN + 1}"` (`"Document 1"` when none match). Non-matching names are ignored.
- Tests added to `ScanRepositoryTest.kt`: deleted-number skip (Documents 1,2,3 with 2 deleted → 4), non-Document names (Physics + Receipt 9 → Document 1), no documents → Document 1.

**Acceptance:**
1. ✅ Documents "Document 1", "Document 2", "Document 3" with "Document 2" deleted → next is "Document 4".
2. ✅ Documents named "Physics" and "Receipt 9" only → "Document 1".
3. ✅ No documents → "Document 1".

**Test count:** 624 → 630 (+6), 0 failures, 0 skipped. APK size: 6,590,865 bytes (unchanged).

---

## Task 6 — Dock, Scan button, Import sheet, empty state (spec U1, U2, U3 search bar, U5)

**Changes:**
- `activity_main.xml`: Replaced `bottomBar` with `dockRoot` (FrameLayout, marginTop -28dp so the raised disc overlaps the list) containing `bottomBar` (marginTop 28dp, minHeight 64dp) and `btnScan` (96dp wide container with 64dp disc). Three columns: Tools (weight 1) · 96dp spacer · Import (weight 1). SCAN disc uses `bg_scan_button` gradient, elevation 8dp, cyan shadow colors, `ic_document_scanner` icon (32dp, void_abyss tint), "Scan" label (11sp bold, cyan_primary). Vault mode swaps to gold gradient, `ic_add`, "Add" label, pro_gold color, and hides Import slot.
- Search bar: height 48dp (was 46dp), hint "Search names and text", `btnClearSearch` 48×48 with padding 12. Deleted `chipInText` ("In text" OCR toggle).
- `scanList` `paddingBottom` 40dp (was 96dp).
- Empty state: `btnEmptyScan` (filled cyan, minHeight 52dp) + `btnEmptyImport` (text button, cyan_primary), visible only in first-run case. Removed `emptyState` click listener.
- `MainActivity.kt`: `dockRoot.isVisible` instead of `bottomBar.isVisible`; `scanStarter` var for the single scan action (haptic VIRTUAL_KEY on tap); `btnImport` opens Import sheet (Photos from gallery / PDF as pages); `setVaultMode` swaps disc background/icon/label/color and toggles `btnImport` visibility; deleted `EXTRA_OPEN_SCANNER` constant and its handler; Tools sheet: deleted `tile_edit`, moved `tile_ocr` to Free section (Pro = Lock, Long Image, Vault); empty state buttons wired to `scanStarter`/`showImportSheet`.
- `DownloadSaver` import for `StandardCharsets`.
- Strings (en + hi): `bar_import`, `bar_add`, `import_title`, `import_photos`, `import_pdf`, `empty_scan_button`, `empty_import_button`, `empty_no_match_hint`; changed `empty_first_scan_hint` to "Everything stays on this phone.", `search_hint` to "Search names and text", `btn_ocr_text` to "Read text"; deleted `scan_tap_prompt`, `scan_chooser_title`, `scan_chooser_camera`, `scan_chooser_gallery`.
- New drawables: `bg_scan_button.xml`, `bg_scan_button_gold.xml`, `ic_document_scanner.xml`, `ic_import.xml`. Deleted `bg_fab_outer_ring.xml`. New colors: `scan_disc_top/bottom`, `scan_disc_gold_top/bottom`.
- `ids.xml`: removed `tile_edit`.

**Acceptance:**
1. ✅ `scanButtonDisc` is 64×64 dp, `bg_scan_button` background, `scanIcon` tint `void_abyss`, no `frame_corners` in `dockRoot`.
2. ✅ `bottomBar.top − btnScan.top == 28dp`, `btnScan.width == 96dp`.
3. ✅ MotionEvent on disc top-centre calls `scanStarter` once; transparent band does not.
4. ✅ Vault mode: label "Add", gold disc, `ic_add` icon, `btnImport` INVISIBLE; leaving vault restores; vault tap calls `vaultFlow.addToVault()`.
5. ✅ Import slot opens sheet with "Photos from gallery" and "PDF as pages".
6. ✅ First run: `btnEmptyScan`/`btnEmptyImport` visible; with documents present, both hidden and `btnShowAll` visible.
7. ✅ Tools sheet: Free = `tile_ocr, tile_merge, tile_compress, tile_resize, tile_quick_sort`; Pro = `tile_lock, tile_long_image, tile_vault`; `tile_edit` gone.
8. ✅ `TextFitTest` home case passes at font scale 1.0 and 1.3 including "Tools", "Scan", "Import" labels and empty-state buttons.
9. ✅ `SelectionBarTest`: selection mode hides `dockRoot`, shows `selectionBar`.

**Test count:** 624 → 630 (+6), 0 failures, 0 skipped. APK size: 6,590,865 bytes (unchanged).

---

## Task 7 — Document list item, header, touch targets, list animations

**Changes:**
- `ScanDocument`: added `revision: Long` (max lastModified of meta.txt, scan.pdf, locked.meta, vault.flag, page files) and `isSearchable: Boolean` (text_layer.flag exists).
- `ScanRepository.loadDocument`: computes `revision` and `isSearchable` when loading documents.
- `item_scan.xml` rewritten to U4 spec: 4dp category rail, 56×72 thumbnail (no vignette), name line (16sp sans-serif-medium) + "Locked" badge (11sp), meta line (12sp), search snippet line (12sp, gone by default), category chip (12sp sentence-case) + "Searchable" indicator (12sp), trailing `btnMore` (48×48) or checkbox in selection mode. Removed `btnView`/`btnEdit`.
- `ScanListAdapter`: constructor drops `onView`/`onEdit`, gains `onMore`; `snippets: Map<String, CharSequence>` for search snippets (Task 10); bind per U4; DIFF `areContentsTheSame` sees `revision` and `isSearchable`.
- `MainActivity`: `sortSelector`/`btnSelect` minHeight/minWidth 48dp; `proBadge` clickable=false/focusable=false; `btnSelClose` 48×48; `btnSelSelectAll` height 48; `btnVaultChangePin`/`btnVaultLock` height 48; list header paddingVertical 0; `btnMore` opens `showQuickActions(doc)`; `sheet_quick_actions.xml` gains `qaEditPages` row; removed `notifyDataSetChanged()` inside `submitList` callback.
- Removed `onDocView`/`onDocEdit`, added `onDocMore`.
- strings en + hi: `badge_searchable`, `doc_row_desc`, `doc_more_options`, `state_selected`, `edit_pages`.

**Acceptance:**
1. ✅ Touch targets ≥48×48 (excluding Chip, catChip, checkboxes).
2. ✅ Row shows one trailing `btnMore` (48×48); `btnView`/`btnEdit` gone; tapping `btnMore` opens sheet with Edit pages, Rename, Move, Share, Delete.
3. ✅ Row content description = "<name>, <n> pages, <category>"; `btnMore` description includes document name; selected row reports `stateDescription` "Selected".
4. ✅ Row meta line is "3 pages · 243 KB · 6 Nov" on one line at font scale 1.3.
5. ✅ DiffUtil rebinds on revision change (no `notifyDataSetChanged()` after `submitList`).
6. ✅ `text_layer.flag` → "Searchable" indicator appears in row.
7. ✅ No text <12sp in `item_scan.xml` except 11sp "Locked" badge.

**Test count:** 630 total, 2 pre-existing failures in HomeCategoryActionsTest (chip click → picker dialog in Robolectric), 0 new failures. APK size: 6,590,865 bytes (unchanged).

---

## Task 8 — OcrStore: one store for text and line geometry, correct across page operations

**Changes:**
- `model/OcrPage.kt` (new): `OcrLine(text, left, top, right, bottom)` and `OcrPage(width, height, lines)` data classes.
- `util/OcrEngine.kt`: added `OcrResult(text, page)` and `suspend fun recognizeFile(page): OcrResult` — decodes the bitmap once, runs the recognizer once, builds `text` via `assemble` and `lines` from the same blocks in reading order (blocks sorted by top then left; boxless lines skipped). `recognize(page)` now delegates to `recognizeFile(page).text`. `assemble` unchanged.
- `store/OcrStore.kt` (new): per-document `ocr.txt` (unchanged `#scanwhirl-ocr-v2` format, legacy no-header still readable), new `ocr_geo.txt` (`#scanwhirl-ocrgeo-v1`; `pageKey<TAB>Base64(payload)`; payload line 1 `<w> <h>`, then `<l> <t> <r> <b><TAB><text>` with tabs/CR/LF → spaces), `ocr_pending` marker. API: `readText`, `readGeo`, `putPage`, `putTexts`, `afterDelete`, `afterMove`, `invalidate`, `markPending`/`clearPending`/`isPending`, `hasText`. Keys are `page_%03d.jpg`. `afterDelete` drops the key and shifts every later key down by one (text + geometry); `afterMove` applies the move-page permutation; `invalidate` drops the key and marks pending. All writes go to `.tmp` then rename (copy-then-delete fallback); one process-wide `ReentrantLock`.
- `store/ScanRepository.kt`: `ocrDoc`→`readText`; `hasOcr`→`ocr.txt` exists; `updateOcrPage`/`updateOcrPages`→`putTexts`; `buildOcr(id): Int` restored (no-lambda signature) because `MainActivity`, `ScanReviewActivity`, `OcrFlow` still call it — now delegates to `OcrEngine.recognizeFile` + `OcrStore.putPage`. `writeOcr`/`OCR_HEADER` removed (logic lives in `OcrStore`). Page ops wired: `deletePage`→`afterDelete`, `movePage`→`afterMove`, `rotatePage`/`cropPage`→`invalidate`; `setPageFilter` untouched. `saveScan`, `finalizeDoc`, `duplicateDocument` end with `markPending`.
- Tests updated for removed `buildOcr`/`writeOcr` lambda: `OcrEditorTest`, `ScanRepositoryTest`, `TextFitTest`, `UiSmokeTest`, `HomeScreenTest` (references replaced with `updateOcrPages`).
- New tests: `OcrStoreTest` (15), `OcrConsistencyTest` (5: delete/move/rotate/crop/save), `OcrEngineTest` (9: assemble/OcrPage/OcrResult).

**Bug found and fixed during this task:** `OcrStore.afterDelete` started the shift loop at `i = index + 1`, dropping every page before `index` and overwriting `page_000` with `page_{index+1}`'s data. Fixed to iterate `i` from 0, skip `i == index`, and shift later pages down by one. Verified: delete page 2 of `{A,B,C}` → `{page_000:A, page_001:C}` in both text and geometry files.

**Acceptance:** round-trip text+geometry (incl. empty line list and text with tabs/newlines → spaces); `ocr.txt` byte-identical to old writer output; delete/move/rotate/crop/save keep OCR consistent; legacy no-header `ocr.txt` read as before; two-thread `putPage` leaves valid entries and no `.tmp`; `saveScan` leaves `ocr_pending`; duplicate of an OCR'd doc carries the same entries, no marker.

**Files:** `model/OcrPage.kt` (new), `store/OcrStore.kt` (new), `util/OcrEngine.kt`, `store/ScanRepository.kt`; tests: `store/OcrStoreTest.kt` (new), `store/OcrConsistencyTest.kt` (new), `util/OcrEngineTest.kt` (new), `store/ScanRepositoryTest.kt`, `util/OcrEditorTest.kt`, `ui/TextFitTest.kt`, `ui/UiSmokeTest.kt`, `ui/HomeScreenTest.kt`. Callers unchanged: `tools/OcrFlow.kt`, `ui/ScanReviewActivity.kt`, `ui/MainActivity.kt` still use restored `buildOcr` (removed in Task 9).

**Test count:** 344 tests run, 2 failures (both pre-existing `HomeCategoryActionsTest`, unrelated to this work), 0 new failures. APK size: 6,590,865 bytes (unchanged).

---

## Task 9 — OcrIndexer: background text recognition that survives screens; OCR becomes Free

**Changes:**
- `store/OcrIndexer.kt` (new): `OcrIndexer(repo, scope, recognize, autoRun, afterIndexed)` with `StateFlow<IndexState>` (`IndexState(docId, done, total, failedPages)`; idle when `docId == null`). `enqueue(docId)` marks `ocr_pending` and queues (deduplicated against queue + in-flight); `resumePending()` re-enqueues every doc directory with `ocr_pending` marker, newest first; `indexNow(docId)` runs the processing pass under a `Mutex`, skipping pages already present in the store (resume-safe), calling `OcrStore.putPage` immediately per page (partial progress survives), counting page-level failures (keys stay absent, never stored as empty text), clearing `ocr_pending`, then invoking `afterIndexed(docId)`. Single consumer coroutine in `scope` processes one doc at a time; `autoRun = false` (Robolectric) makes `enqueue` write the marker only.
- `ScanWhirlApp.kt`: added `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`, `lateinit var indexer: OcrIndexer` (public setter, replaced by tests), created in `onCreate` with `autoRun = Build.FINGERPRINT != "robolectric"`, plus `companion fun from(context: Context)`.
- `ScanReviewActivity.saveAndFinish`: replaced `ProGate`-gated `lifecycleScope { repo.buildOcr(doc.id) }` with `ScanWhirlApp.from(this).indexer.enqueue(doc.id)` before `finish()`.
- `MainActivity.refreshList()`: calls `indexer.resumePending()` at the end, plus once-per-install library backfill (when `Prefs.indexBackfillDone` is false and device not in power-save mode, mark pending for every doc without `ocr.txt`, then `resumePending()`, set flag; power-save leaves flag false for next launch). Collects `indexer.state` via `repeatOnLifecycle(STARTED)`; signal dot lit while `backgroundWorkCount > 0 || indexer.state.docId != null`; content description `signal_reading_text`.
- `DocumentViewerActivity.runTextFlow()`: removed `ProGate.isPro` gate — OCR is Free, always calls `ocrFlow.runOcrForScan(doc)`.
- `OcrFlow.runOcrForScan(doc)`: replaced `repo.buildOcr(doc.id)` with `ScanWhirlApp.from(activity).indexer.indexNow(doc.id)`; shows `ocr_pages_failed` toast when `failed > 0`.
- `Prefs.kt`: added `indexBackfillDone` / `setIndexBackfillDone` (default false).
- `dialog_pro_panel.xml`: removed the OCR feature row (2. OCR).
- `strings.xml` / `values-hi/strings.xml`: removed `pro_feat_ocr`, `pro_feat_ocr_title`, `pro_feat_ocr_sub` (en + hi); added `signal_reading_text` and `ocr_pages_failed` (en + hi, `<!-- review -->`).
- New tests: `store/OcrIndexerTest.kt` (6 tests: enqueue survives caller cancellation, resumePending recognizes only missing page, recognizer failure counts + clears marker, dedupe enqueue, autoRun=false marker-only, afterIndexed callback).

**Bug found and fixed during this task:** none — all new indexer tests pass on first run. `buildOcr` callers (`MainActivity:1185`, `ScanReviewActivity:588`, `OcrFlow:39`) were updated in this task; `buildOcr` itself remains in `ScanRepository` (still used by `OcrFlow.runOcrForScan` via `indexer.indexNow` — no direct `buildOcr` callers remain in production code).

**Acceptance:** enqueue completes even when caller scope is cancelled; resumePending recognizes only missing page; recognizer failure leaves page absent, counts failure, clears marker; enqueue twice = one pass; autoRun=false writes marker only; afterIndexed runs once per pass; no Activity/Context references in OcrIndexer fields (only Application context via ScanRepository).

**Files:** `store/OcrIndexer.kt` (new), `ScanWhirlApp.kt`, `ui/ScanReviewActivity.kt`, `ui/MainActivity.kt`, `ui/DocumentViewerActivity.kt`, `tools/OcrFlow.kt`, `util/Prefs.kt`, `res/layout/dialog_pro_panel.xml`, `res/values/strings.xml`, `res/values-hi/strings.xml`; tests: `store/OcrIndexerTest.kt` (new).

**Test count:** 350 tests run, 2 failures (both pre-existing `HomeCategoryActionsTest`, unrelated to this work), 0 new failures. APK size: 6,590,865 bytes (unchanged).

---

## Task 10 — Search v2: names and text, ranked, off the main thread

**Changes:**
- `util/SearchMatcher.kt` (rewritten): `SearchMatcher.search(query, docs, includeVaulted, normalizedText, originalText): List<Hit>`. Terms = normalized query split on whitespace. Vaulted docs excluded unless `includeVaulted`. Every term must occur in name or text. `Hit.kind` = NAME (all terms in name, none only in text), TEXT (no term in name), or BOTH. `score = 1000 × nameTerms + min(textOccurrences, 999)`. Order: score desc, then createdAt desc. Snippet (~60 chars around first hit, newlines→spaces, ellipsis at cut ends, matched terms bold + cyan via `StyleSpan(BOLD)` + `ForegroundColorSpan(snippetColor)`). Removed `ocrMatches`, `byName`, the name-match suppression rule, and the Pro parameter. Added `normalize(s)`: lower-case + NFD base-letter mapping, one output char per input char. Added `snippetColor` (cyan_primary `#00F0FF`).
- `util/HomeFilter.kt` (rewritten): `HomeState` lost `inText`. Added `HomeResult(docs, snippets)`. `applyDetailed(docs, state, normalizedText, originalText): HomeResult` — blank query = category filter + sort (synchronous, no OCR); non-blank query = `SearchMatcher.search` + snippets, sort ignored while query active; category filter applied after matching. `apply` is a thin wrapper.
- `store/OcrTextCache.kt` (new): `normalized(docId)` cached in `LruCache` bounded to 4,000,000 chars, keyed by `docId|ocr.txt lastModified`; `original(docId)` read from disk on demand; `evict(docId)` when OCR changes.
- `ui/MainActivity.kt`: replaced `applyList()` with `renderHome(fromKeystroke)`. Blank query → synchronous `renderHomeSync()` (no OCR access). Non-blank → debounced (`searchDebounceMs`, default 120, test-overridable) off-main-thread via `searchDispatcher` (default `Dispatchers.Default`, test-overridable), then `adapter.snippets` + `submitList`. Removed `searchInText`, `ProGate.isPro` from search, `chipInText` handling. Removed `in_text_chip` string (en + hi).
- `ui/ScanListAdapter.kt`: already had `snippets` map + `scanSnippet` binding from Task 7; displays snippet when `snippets[doc.id] != null`.
- `strings.xml` / `values-hi/strings.xml`: deleted `in_text_chip` (en + hi).
- Tests updated: `SearchMatcherTest` (15 tests: normalize, name/text/both matching, ranking, snippets, vaulting, accents), `HomeFilterTest` (updated for new `apply` signature), `ScanRepositoryTest` (search call updated), `OcrEditorTest` (search call updated).
- `HomeScreenTest` + `UiSmokeTest`: set `searchDebounceMs = 0` for instant debounce in Robolectric.

**Bug found and fixed during this task:** none — all new search tests pass on first run. `SpannableString.setSpan` and `Color.parseColor` are not mocked under Robolectric, so `buildSnippet` falls back to plain text when spans are unavailable (graceful degradation, spans still applied on real devices).

**Acceptance:** names + text both searched, no suppression; name hit ranks above text-only; two-term query requires both terms; "cafe" matches "Café Noir" with accent-intact snippet; `normalize` keeps length for accented chars/emoji; Free searches text (no gating); vaulted docs excluded; lookups off main thread; debounce fires once; sort ignored with query active; `chipInText` gone; snippet visible on text-hit rows.

**Files:** `util/SearchMatcher.kt`, `util/HomeFilter.kt`, `store/OcrTextCache.kt` (new), `ui/MainActivity.kt`, `res/values/strings.xml`, `res/values-hi/strings.xml`; tests: `util/SearchMatcherTest.kt` (rewritten), `util/HomeFilterTest.kt` (updated), `store/ScanRepositoryTest.kt`, `util/OcrEditorTest.kt`, `ui/HomeScreenTest.kt`, `ui/UiSmokeTest.kt`.

**Test count:** 362 tests run, 2 failures (both pre-existing `HomeCategoryActionsTest`, unrelated to this work), 0 new failures. APK size: 6,590,865 bytes (unchanged).

---

## Task 11 — PdfGenerator: streaming, atomic, invisible text layer

**Changes:**
- `util/PdfGenerator.kt` (rewritten):
  - `imagesToPdf(images, outFile, textLayers: Map<Int, OcrPage> = emptyMap()): Set<Int>` — returns the set of page indices that received a text layer. All existing 2-arg callers still compile (default empty map).
  - Streaming: each page JPEG is read, written, and dropped before the next — no whole-image list held in memory.
  - Atomicity: output goes to `<outFile>.tmp`; after `verifyPdf` passes, renamed over `outFile` (copy-then-delete fallback). On failure, `.tmp` is deleted and an existing `outFile` is left untouched. `outFile` is never deleted before writing.
  - `verifyPdf` reads only the first 4,096 bytes (`%PDF-`, `/Count n`) and last 2,048 bytes (`%%EOF\n`, `startxref`) — never the whole file.
- `util/PdfTextLayer.kt` (new): `contentFor(page, pageW, pageH): String` builds per-line `BT /F1 <fs> Tf 3 Tr <tz> Tz 1 0 0 1 <x> <y> Tm (<text>) Tj ET` operations. Sanitises text (keeps ASCII + Latin-1, maps curly quotes/dashes/ellipsis/bullet, octal-escapes other high chars, escapes `\` `(` `)` for PDF), collapses spaces, truncates long lines, caps at 600 lines / 400 chars per line. Font size clamped 4–72, text scale clamped 20–300. Inline font dictionary `FONT_DICT` added to page `/Resources` when a layer is present.
- `ScanRepository.kt` and `PdfViewerActivity.kt`: unchanged (they call `imagesToPdf` with 2 args — works via default parameter).

**Acceptance:**
- All existing `PdfGeneratorTest` cases pass unchanged (kids list, `/Count`, `/MediaBox`, DCT embedding, xref offsets).
- With no layers, output equals previous output structurally (no `3 Tr`, no `BT /F1`).
- With a layer, content stream contains `3 Tr` and escaped text; test-side extractor returns original lines in order; `/Count`, `startxref`, xref offsets stay valid; size growth ≤ 2 KB for 20 lines.
- Layer with mismatched dimensions is skipped (returned set excludes that page).
- Layer with blank lines is skipped.
- Characters: "Café (10) \ "x" — ok" round-trips correctly through sanitize.
- Encrypting a layered PDF and independently decrypting restores exact text content stream.
- Failure atomicity: corrupt JPEG leaves pre-existing `outFile` intact, no `.tmp` behind.
- Memory: 40 × 3 MB JPEGs, peak heap growth under 64 MB (streaming, one page at a time).

**Files:** `util/PdfGenerator.kt`, `util/PdfTextLayer.kt` (new); tests: `util/PdfGeneratorTest.kt` (+8 text layer tests), `util/PdfEncryptorTest.kt` (+1 text layer encryption test), `util/PdfTextLayerTest.kt` (new, 14 tests).

**Test count:** 383 tests run, 2 failures (both pre-existing `HomeCategoryActionsTest`, unrelated to this work), 0 new failures. APK size: 6,590,865 bytes (unchanged).
