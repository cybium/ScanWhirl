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
