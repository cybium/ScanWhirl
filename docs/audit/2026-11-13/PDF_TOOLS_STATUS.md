# PDF tools status — DS-1 to DS-4

Investigation date: 2026-11-13. Tree: `0c283b8` (working tree clean apart from untracked `docs/plans/`).
Device: Infinix X6711, Android 14 / API 34, serial `103223137I112880`.
Read-only investigation: no product file, build file, manifest or device state was changed.

---

## 1. Summary

- Windows baseline: **807 tests, 170 failed, 0 skipped** (run 1, 6 m 30 s). A confirmation run gave **807 / 167**, so the baseline is 170 ± 3.
- Linux (WSL Ubuntu, OpenJDK 17, SDK `android-36`, 11 m 25 s): **807 tests, 28 failed, 0 skipped**. 142 of the 170 Windows failures are host artefacts.
- Buckets: **A 116, B 26, C 27, D 1, R 0.** All of A and B pass on Linux; none of C or D do.
- Linux run possible: **yes** (Ubuntu WSL2, JDK 17 at `/usr/lib/jvm/java-17-openjdk-amd64`, SDK at `/home/cybium/android-sdk`; `ANDROID_HOME` was unset and passed inline).
- Merged manifest: `INTERNET` and `ACCESS_NETWORK_STATE` are declared by the app **and re-added by `com.google.android.datatransport` 2.2.6/2.3.3**. ML Kit adds 1 service, 1 provider and 3 Firebase component `meta-data`; datatransport adds 2 services, 1 receiver, 1 `meta-data`.
- No `tools:node="remove"` exists anywhere, so PM-1.5 must remove the permissions at merge level.
- Play Billing AAR: **not cached** in either Gradle home, so its manifest contribution is unverified.
- App Kotlin sources make **no** network calls (`java.net`, HTTP, OkHttp, Socket, WebView, ConnectivityManager: none).
- Device: app `1.1.1-test27`, GMS `26.43.30`, extensions R/S/T/U = **14** (S ≥ 13, so `PdfRendererPreV` is available). `run-as` is blocked: the installed test APK is **not debuggable**, so INV-6 and INV-9 stay unverified.

---

## 2. DS-1 — test baseline (INV-1)

### 2.1 Totals

| Environment | Command | Tests | Failures | Skipped | Duration | Exit |
|---|---|---|---|---|---|---|
| Windows 11, JDK 17 | `.\gradlew.bat :app:testDebugUnitTest -PtestBuild --no-daemon --continue` | 807 | **170** | 0 | 6 m 30 s | 1 |
| Windows re-run (stability check) | same | 807 | **167** | 0 | 6 m 26 s | 1 |
| WSL Ubuntu, OpenJDK 17 | `./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon --continue` | 807 | **28** | 0 | 11 m 25 s | 1 |

Parsed from 77 `app/build/test-results/testDebugUnitTest/*.xml` files; `errors=0` in both environments.

**Stability.** The three tests that failed in run 1 but passed in the re-run are all timing-sensitive:
`DirectScanSaveTest`, `SaveTransactionTest > committed but unacknowledged review ...`, and
`ScanReviewFocusArrangeTest > Later Earlier and Move ...`. Both runs agree on every A and C row.
The table below is run 1 (the superset).

### 2.2 Failing tests

| Class | Test | First line of the failure message | Bucket | Passes on Linux? |
|---|---|---|---|---|
| CategoryPersistTest | duplicates inherit the category even under a new name | java.io.IOException: Could not replace meta.txt | A | yes |
| CategoryPersistTest | setCategory overrides the guess and survives a reload | java.io.IOException: Could not replace meta.txt | A | yes |
| DeleteExportsTest | a foreign-owned copy is skipped but the document is still deleted | java.io.IOException: Could not replace meta.txt | A | yes |
| DeleteExportsTest | an unsigned older record is not offered and the dialog says older copies stay | java.io.IOException: Could not replace meta.txt | A | yes |
| DeleteExportsTest | export twice records identical once and a changed copy separately | java.io.IOException: Could not replace meta.txt | A | yes |
| DeleteExportsTest | leaving the box unchecked keeps the exported copy | java.io.IOException: Could not replace meta.txt | A | yes |
| DeleteExportsTest | move to Trash from the document screen keeps the exported copy | java.io.IOException: Could not replace meta.txt | A | yes |
| DeleteExportsTest | old two-line meta reads and survives setCategory | java.io.IOException: Could not replace meta.txt | A | yes |
| DeleteExportsTest | permanent delete from Trash removes the proven copy | java.io.IOException: Could not replace meta.txt | A | yes |
| DirectScanSaveTest | Done saves straight to Home in scan order with nothing left staged | java.lang.AssertionError: no second edit screen after the scanner expected null, but was:<Intent { cmp=com.scanwhirl.app.test/com.scanwhirl.app.ui.DocumentViewerActivity (has extras) }> | D | no |
| ExportHygieneTest | undo after a changed re-save keeps the earlier export | java.lang.AssertionError: only the earlier copy stays recorded - got [] | B | yes |
| ExportOwnershipTest | a copy also recorded by a trashed document is protected | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | a missing install key leaves every record unproven | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | a proof copied into another document does not verify | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | a proven copy edited elsewhere is kept on permanent delete | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | a renamed predecessor's retained copy is not claimed by a later document with that name | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | an identical re-save that adopts a retained copy is recorded but never proven | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | deleting a never-exported namesake does not offer the retained copy of a deleted document | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | pre-Q filesystem - a namesake never claims a deleted document's retained file | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportOwnershipTest | the same journey through Trash keeps the retained copy | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportTransactionLegacyTest | pre-Q delete-with-document removes only fingerprinted unchanged copies | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportTransactionTest | a file recorded by another document is kept when one is deleted | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportTransactionTest | a recorded copy edited after saving is kept on delete | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportTransactionTest | export records survive remove and keep fingerprints separate | java.io.IOException: Could not replace meta.txt | A | yes |
| ExportTransactionTest | same title on two documents never shares or deletes the other's export | java.io.IOException: Could not replace meta.txt | A | yes |
| FreeLegacyVaultEntryPointTest | free vault tile without a PIN is blocked with truthful copy and creates nothing | java.lang.AssertionError: tile present | C | no |
| FreeLegacyVaultEntryPointTest | pro vault tile without a PIN is also blocked - setup disabled for everyone | java.lang.AssertionError: tile present | C | no |
| HomeCategoryActionsTest | bulk move recategorizes the selection and exits it | java.io.IOException: Could not replace meta.txt | A | yes |
| HomeCategoryActionsTest | chip move recategorizes the document | java.io.IOException: Could not replace meta.txt | A | yes |
| HomeCategoryActionsTest | quick action rename saves the new name | java.io.IOException: Could not replace meta.txt | A | yes |
| HomeFontScaleNarrowTest | home status header cards and controls stay readable at 100 130 and 200 percent | java.lang.AssertionError | C | no |
| HomeFontScalePhoneTest | home status header cards and controls stay readable at 100 130 and 200 percent | java.lang.AssertionError | C | no |
| HomeScreenTest | tools sheet opens and holds seven tiles in two sections | java.lang.AssertionError: tile tile_vault present | C | no |
| HomeSlice2LargeTextTest | rows status and dock fit at 200 percent on 320dp | java.lang.AssertionError: catChip: no layout | C | no |
| HomeSlice2Test | scan import tools settings pro private select more and long-press all still work | java.lang.AssertionError | C | no |
| HomeSlice2Test | text status comes from the pending marker and stored page text only | java.lang.AssertionError: expected:<Search ready> but was:<null> | C | no |
| HomeSlice2Test | the indexer finishing a document re-reads its status | java.io.IOException: Could not publish scan.pdf | A | yes |
| ImageImportPipelineTest | direct 1 page save reopen share and PDF streams | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ImageImportPipelineTest | direct 30 pages save reopen share and PDF streams | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ImageImportPipelineTest | direct 5 pages save reopen share and PDF streams | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ImageImportPipelineTest | staged 1 page save reopen share and PDF streams | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ImageImportPipelineTest | staged 30 pages with append save reopen share and PDF streams | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ImageImportPipelineTest | staged 5 pages with append save reopen share and PDF streams | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ImportSheetNarrowTest | import chooser names Photos and PDF with distinct vector icons and honest copy | java.lang.AssertionError: Photos copy says Review | C | no |
| ImportSheetNarrowTest | nudge counts Unsorted plus Other as to organize and never calls Other unsorted | java.lang.AssertionError | C | no |
| ImportSheetNarrowTest | quick sort sheet scrolls and its X and Skip stay | java.lang.AssertionError | C | no |
| ImportSheetNarrowTest | tools sheet keeps every tile and the Free and Pro split | java.lang.AssertionError: tile 2131231491 | C | no |
| ImportSheetTest | import chooser names Photos and PDF with distinct vector icons and honest copy | java.lang.AssertionError: Photos copy says Review | C | no |
| ImportSheetTest | nudge counts Unsorted plus Other as to organize and never calls Other unsorted | java.lang.AssertionError | C | no |
| ImportSheetTest | quick sort sheet scrolls and its X and Skip stay | java.lang.AssertionError | C | no |
| ImportSheetTest | tools sheet keeps every tile and the Free and Pro split | java.lang.AssertionError: tile 2131231491 | C | no |
| LongImageRemovalTest | existing exported long image stays an ordinary shareable image and pages are untouched | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| LongImageRemovalTest | tools sheet has no long image tile in Free or Pro | java.lang.AssertionError: exactly the 7 remaining tiles: [tile_ocr, tile_merge, tile_compress, ... | C | no |
| OcrConsistencyTest | cropPage marks document pending (OCR invalidated) | java.io.IOException: Could not replace page_000.jpg. The page was left unchanged. | A | yes |
| OcrConsistencyTest | deletePage removes OCR for deleted page and shifts remaining | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrConsistencyTest | movePage reorders OCR text to match file order | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrConsistencyTest | rotatePage marks document pending (OCR invalidated) | java.io.IOException: Could not replace page_000.jpg. The page was left unchanged. | A | yes |
| OcrEditorSaveFailureTest | Save failure retains edits and back-dialog Save stays open until successful retry | org.junit.ComparisonFailure: expected:<[corrected]> but was:<[original]> | B | yes |
| OcrIndexerTest | enqueue completes even when the caller scope is cancelled | java.lang.AssertionError: expected:<3> but was:<1> | B | yes |
| OcrIndexerTest | enqueue twice for same id produces one pass | java.lang.AssertionError: expected:<0> but was:<1> | B | yes |
| OcrIndexerTest | recognizer failure leaves page absent, counts failure, clears marker | java.lang.AssertionError: expected:<1> but was:<2> | B | yes |
| OcrIndexerTest | resumePending recognizes only the missing page | java.lang.AssertionError: expected:<0> but was:<1> | B | yes |
| OcrPartialEditTest | failed index text commit retains old text and geometry pair | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | failed sync torn temp and cancellation retain earlier readable text and geometry | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | geometry write failure and blocked temp do not replace text | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | independent stores concurrently merge edits and indexed pages without losing keys | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | legacy geometry binds before edit and only edited page becomes stale | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | rename failure never falls back to truncating the readable target | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | repeated empty Unicode and batch edits preserve exact logical text | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | repository middle edit preserves other text keys and geometry and pending | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| OcrPartialEditTest | sparse OCR survives delete and move remapping | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrStatusTest | document text export is named after the document with honest 1-based headings | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrStatusTest | manual text for an unread page is saved as that page's text | java.lang.AssertionError | B | yes |
| OcrStatusTest | partial reading names the missing pages and Retry never overwrites corrected text | java.lang.AssertionError | B | yes |
| OcrStatusTest | unavailable recognition is explained, never a blank 0 words page, and Retry works | java.lang.AssertionError | B | yes |
| OcrStatusTest | unread pages are never written back as empty text | java.lang.AssertionError | B | yes |
| OcrStoreTest | afterDelete shifts remaining keys down | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrStoreTest | afterDelete then readText is consistent with putTexts | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrStoreTest | afterMove reorders keys moving down | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrStoreTest | afterMove reorders keys moving up | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrStoreTest | invalidate removes key and marks pending | java.io.IOException: Could not replace ocr.txt | A | yes |
| OcrStoreTest | putPage with null page removes geometry but keeps text | java.io.IOException: Could not replace ocr_geo.txt | A | yes |
| PageEditSafetyTest | crop whose swap rename fails keeps the only page byte-identical | java.lang.AssertionError: expected:<[]> but was:<[.page_002.jpg.1994c25e735a9e30.swtmp]> | B | yes |
| PageEditSafetyTest | document crop with an unreadable kept original changes nothing | java.io.IOException: Could not replace page_000.orig. The page was left unchanged. | A | yes |
| PageEditSafetyTest | document filter whose page swap fails keeps page and original | java.io.IOException: Could not replace page_000.orig. The page was left unchanged. | A | yes |
| PageEditSafetyTest | document rotate and crop still succeed and keep page and original aligned | java.io.IOException: Could not replace page_000.orig. The page was left unchanged. | A | yes |
| PageEditSafetyTest | document rotate whose original swap fails rolls the page back | java.io.IOException: Could not replace page_000.orig. The page was left unchanged. | A | yes |
| PageEditSafetyTest | rotate whose swap rename fails keeps the only page byte-identical | java.lang.AssertionError: expected:<[]> but was:<[.page_001.jpg.4d2e2076f12b1179.swtmp]> | B | yes |
| PageEditSafetyTest | successful rotate and crop leave no staging files and a decodable page | java.io.IOException: Could not replace page_004.jpg. The page was left unchanged. | A | yes |
| PdfEncryptorTest | encrypted pdf streams decrypt to original content | java.io.IOException: Could not publish src.pdf | A | yes |
| PdfEncryptorTest | encrypting a PDF with a text layer and decrypting restores the exact text | java.io.IOException: Could not publish src.pdf | A | yes |
| PdfEncryptorTest | verifyPdfPassword accepts right and rejects wrong | java.io.IOException: Could not publish src.pdf | A | yes |
| PendingReviewStateTest | queued older snapshots cannot overwrite newer state across activity instances | java.util.concurrent.ExecutionException: java.io.IOException: Could not replace review_state.js... | A | yes |
| PendingReviewStateTest | review state leaves identity filters and journal intact and resumed save replays once | java.io.IOException: Could not replace review_state.json | A | yes |
| PendingReviewStateTest | simulated process interruption ignores uncommitted temp record | java.io.IOException: Could not replace review_state.json | A | yes |
| Phase5ScreensTest | document category chip opens the picker | java.io.IOException: Could not replace meta.txt | A | yes |
| Phase5ScreensTest | document viewer shows the category chip and glass dock | java.io.IOException: Could not replace meta.txt | A | yes |
| Phase5ScreensTest | picker choice recategorises the document and repaints the chip | java.io.IOException: Could not replace meta.txt | A | yes |
| QuickSortTest | closing mid queue leaves the rest unsorted and the nudge keeps counting | java.lang.AssertionError: the nudge must be visible to open the sheet | C | no |
| QuickSortTest | nudge shows the unsorted count and opens quick sort | java.lang.AssertionError: T4.4: the nudge must be visible while an unsorted doc exists | C | no |
| QuickSortTest | picking saves instantly and advances to the next document | java.lang.AssertionError: the nudge must be visible to open the sheet | C | no |
| RetrievalSlice5Test | external viewer preference keeps ordinary opens external and page links say they open here | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| RetrievalSlice5Test | Find re-reads edited text and never shows stale results | java.io.IOException: Could not replace ocr.txt | A | yes |
| RetrievalSlice5Test | page matches follow the current order after a real page move | java.io.IOException: Could not replace ocr.txt | A | yes |
| SaveReviewIdentityTest | R1 crash while building the explicit new document leaves the earlier one intact and replays safely | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveReviewIdentityTest | R1 review screen refuses a changed resumed review and lets the owner choose a new document | java.lang.AssertionError: Timed out | B | yes |
| SaveReviewIdentityTest | R1 SAVE_AS_NEW is an explicit choice with a new persisted identity and the earlier document untouched | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveReviewIdentityTest | R1 title and category never rewrite or duplicate the committed document | java.io.IOException: Could not replace meta.txt | A | yes |
| SaveReviewIdentityTest | R2 a complete prepared document of a superseded review is preserved and the change saves to a new target | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveReviewIdentityTest | R2 foreign and unverifiable directories are never touched by the reaper or by replay | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveTransactionTest | a document with a different token is never returned as a successful save | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveTransactionTest | collision fallback is itself replay safe | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveTransactionTest | committed but unacknowledged review offers to open the saved document and cleans up only after opening | java.lang.AssertionError: leftover staging removed after the verified document was opened | B | yes |
| SaveTransactionTest | existing EMPTY target directory is never replaced or deleted | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveTransactionTest | existing populated legacy target directory stays byte-identical | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveTransactionTest | metadata mutators are atomic and preserve createdAt category unknown lines and exports | java.io.IOException: Could not replace meta.txt | A | yes |
| SaveTransactionTest | our token with tampered page content is not accepted as the committed document | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveTransactionTest | process death at any step never shows a document and replay yields exactly one | java.io.IOException: Could not replace save_txn.txt | A | yes |
| SaveTransactionTest | review screen keeps every staged page and shows the page error when the save fails | java.lang.AssertionError: Timed out | B | yes |
| ScanRepositoryTest | bulk setCategory moves every doc and ignores missing ids | java.io.IOException: Could not replace meta.txt | A | yes |
| ScanRepositoryTest | cropPage crops the page and its kept original | java.io.IOException: Could not replace page_000.orig. The page was left unchanged. | A | yes |
| ScanRepositoryTest | delete page reduces count and renumbers | java.io.IOException: Could not publish scan.pdf | A | yes |
| ScanRepositoryTest | duplicateDocument makes an independent copy | java.io.IOException: Could not replace meta.txt | A | yes |
| ScanRepositoryTest | move page reorders and rebuilds pdf | java.io.IOException: Could not publish scan.pdf | A | yes |
| ScanRepositoryTest | rename updates name in list | java.io.IOException: Could not replace meta.txt | A | yes |
| ScanRepositoryTest | rotate left also swaps dimensions | java.io.IOException: Could not replace page_000.jpg. The page was left unchanged. | A | yes |
| ScanRepositoryTest | rotate page swaps dimensions and keeps page count | java.io.IOException: Could not replace page_000.jpg. The page was left unchanged. | A | yes |
| ScanRepositoryTest | updateOcrPage round-trips and the edit is what search finds | java.io.IOException: Could not replace ocr.txt | A | yes |
| ScanReviewFocusArrangeTest | back from the viewer Focus lands on the page shown there with its edited file | java.io.IOException: Could not replace page_002.jpg. The page was left unchanged. | A | yes |
| ScanReviewFocusArrangeTest | Delete in Focus keeps the position and Undo restores the page | java.lang.AssertionError | B | yes |
| ScanReviewFocusArrangeTest | Later Earlier and Move to rename the staged files and the selection follows the page | java.lang.AssertionError: Timed out | B | yes |
| ScanReviewFocusArrangeTest | Rotate in Focus turns the real file and Undo turns it back | java.lang.AssertionError: Timed out | B | yes |
| ScanReviewFocusArrangeTest | Save from Arrange after a move saves exactly one document in the shown order | java.lang.AssertionError: Timed out | B | yes |
| ScanReviewRecoveryTest | automatic category keeps suggesting after restart and empty title retains original default | java.lang.AssertionError: Timed out waiting for review state | B | yes |
| ScanReviewRecoveryTest | debounce persists while review stays open and rotation reorder filters survive cold resume | java.lang.AssertionError: Timed out waiting for review state | B | yes |
| ScanReviewRecoveryTest | one and five page reviews restore title and manual choice after recreation and cold launch | java.lang.AssertionError: Timed out waiting for review state | B | yes |
| ScanReviewRecoveryTest | open review debounces title writes off main and flushes latest state before Save | java.lang.AssertionError: Timed out waiting for review state | B | yes |
| ShareUtilsTest | multi page share keeps ClipData for every page | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ShareUtilsTest | share of a document with a PDF goes through the single-file path | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ShareUtilsTest | single image share has ClipData uri mime and read grant | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ShareUtilsTest | single PDF share has ClipData uri mime and read grant | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| ShareUtilsTest | single ZIP share has ClipData uri mime and read grant | java.lang.IllegalArgumentException: Failed to find configured root that contains C:\Users\Cybiu... | A | yes |
| StatusLineScreenTest | category filter never changes the status line | java.lang.AssertionError: says 1 DOC | C | no |
| StatusLineScreenTest | deleting every doc shows 0 DOCS and the empty state | java.lang.AssertionError: says 2 DOCS first | C | no |
| StatusLineScreenTest | status line counts the second doc after it is added | java.lang.AssertionError: says 1 DOC first | C | no |
| StatusLineScreenTest | status line reads one doc with the card's size after first load | java.lang.AssertionError: says 1 DOC, was: | C | no |
| TrashTest | a trashed document is invisible to OCR resume and its pending marker survives | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | a vault-flagged folder in Trash is never listed | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | an interrupted permanent delete is never shown and is finished on restart | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | crash after restore move leaves no stale marker after recovery | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | crash before a move or a restore changes nothing | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | crash right after the move leaves a complete restorable Trash entry | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | legacy Vault items never enter Trash | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | low space for the Trash marker still moves the document whole | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | permanent delete removes only that entry | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | repeated cycles and a restart never lose or duplicate anything | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | restore never overwrites an item that took the id meanwhile | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | trash and restore keep every file byte-identical and the same id | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | two trash entries from the same id stay separate | java.io.IOException: Could not replace meta.txt | A | yes |
| TrashTest | unlocked previews do not outlive a trashed document | java.io.IOException: Could not replace meta.txt | A | yes |
| UiSmokeTest | drag reorder persists the new page order | java.lang.AssertionError: order not persisted: red, blue, yellow, green | B | yes |
| UiSmokeTest | home shows a status line and settings can turn it off | java.lang.AssertionError: status line should be shown under the name | C | no |
| UiSmokeTest | lock index finds and forgets its own locked pdfs | java.io.IOException: Could not publish scan.pdf | A | yes |
| UiSmokeTest | page op after a full-rect crop keeps the page intact | java.io.IOException: Could not replace page_000.jpg. The page was left unchanged. | A | yes |
| UiSmokeTest | page viewer crop shrinks the saved page | java.lang.AssertionError: page should be cropped to ~(248, 368) | B | yes |
| UiSmokeTest | protect and unlock pdf toggles isLocked and lock badge on scan card | java.io.IOException: Could not publish scan.pdf | A | yes |
| UiSmokeTest | staging crop writes the staged page | java.lang.AssertionError: staged page should be cropped in place | B | yes |
| UiSmokeTest | status line renders under the app name, and the badge is clickable | java.lang.AssertionError: status line leads with the phase: "" | C | no |
| UiSmokeTest | tools sheet reveals all pro actions and editor pro tools render screenshots | java.lang.AssertionError | C | no |
| UiSmokeTest | viewer tap opens the full-screen page view | java.io.IOException: Could not replace page_000.jpg. The page was left unchanged. | A | yes |

### 2.3 Totals per bucket

| Bucket | Windows | On Linux | Meaning |
|---|---|---|---|
| A — host file replace / FileProvider root | **116** | 0 fail | Test-host artefact. `File.renameTo` cannot replace an existing file on Windows; Android `rename(2)` can. |
| B — downstream of A | **26** | 0 fail | Timeouts, leftover `.swtmp`, OCR counts and export records in tests that also do file replaces. |
| C — stale expectation after `065294b` | **27** | 27 fail | Deliberate UX changes the tests were never updated for. |
| D — behaviour question | **1** | 1 fail | `DirectScanSaveTest` (OQ-12). |
| R — product regression | **0** | 0 fail | Nothing in the 170 looks like a product regression. |
| **Total** | **170** | **28** | |

### 2.4 Bucket C evidence (assertion line to production line)

| Test(s) | Assertion | Production line that changed |
|---|---|---|
| `FreeLegacyVaultEntryPointTest` x2 | `FreeLegacyVaultEntryPointTest.kt:110` `assertNotNull("tile present", tile)` | `MainActivity.kt:1201-1207` — `tile_vault` is added only `.takeIf { Prefs.hasVaultPin(this) }` |
| `HomeScreenTest`:657, `HomeSlice2Test`:458, `UiSmokeTest`:1658, `ImportSheetTest`:336, `ImportSheetNarrowTest`:336, `LongImageRemovalTest`:145 | `findViewById(tile_vault)` / "exactly the 7 remaining tiles" | `MainActivity.kt:1209` `val proTiles = emptyList<Tile>()` and the conditional vault tile at `:1206` — the sheet now holds 6 tiles, not 7 |
| `HomeFontScaleNarrowTest` / `HomeFontScalePhoneTest` | `HomeFontScaleTest.kt:151` `assertTrue(status.isShown)` | `Prefs.kt:65` `fun showGreeting(c: Context): Boolean = false` (status line retired in M08) + guard at `MainActivity.kt:453` |
| `StatusLineScreenTest` x4 | `:107`, `:121`, `:142`, `:170` poll for "1 DOC"/"2 DOCS"/"0 DOCS" | same `Prefs.kt:65`; `MainActivity.kt:452-464` never sets the text |
| `UiSmokeTest`:305, `:1358` | `assertTrue(... line.text.isNotEmpty() && line.isVisible)` / "status line leads with the phase" | same `Prefs.kt:65` |
| `QuickSortTest`:65, `:242`, `ImportSheetTest`/`ImportSheetNarrowTest`:251, `:278` | nudge `visibility == View.VISIBLE` | `MainActivity.kt:479` `binding.nudge.isVisible = false` — the nudge is retired |
| `ImportSheetTest` / `ImportSheetNarrowTest`:81 | `assertTrue("Photos copy says Review", photosDesc.contains("Review"))` | `strings.xml:208` `import_photos_desc = "From your gallery"`; `sheet_import.xml:46` and `:189` set subtitle and order note `gone` |
| `HomeSlice2Test`:396 | `assertEquals("Search ready", status("Ready"))` | `ScanListAdapter.kt:196-199` — "Search ready" is spoken, not printed (M08) |
| `HomeSlice2LargeTextTest`:637 | `assertTrue(problems.joinToString("\n"), problems.isEmpty())` ("catChip: no layout") | `ScanListAdapter.kt:130` — `catChip` is hidden while the category is Unsorted/empty |

`DirectScanSaveTest` (bucket D) asserts no second screen (`DirectScanSaveTest.kt:71`), but `MainActivity.kt:1720`
starts `DocumentViewerActivity` from `scanSaved`. OQ-12 says the landing is the Reader; the owner's answer, not a defect.

---

## 3. DS-2 findings — merged manifest and network (INV-4, OQ-1, OQ-4)

Built with `:app:processDebugMainManifest -PtestBuild --no-daemon` (exit 0).
Merged file: `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`.
Attribution: `app/build/outputs/logs/manifest-merger-debug-report.txt`.

### 3.1 Permissions

| Permission | Declared by |
|---|---|
| `android.permission.INTERNET` | app manifest `:6` **and** `com.google.android.datatransport:transport-backend-cct:2.3.3` (`:26`) + `transport-runtime:2.2.6` (`:23`) |
| `android.permission.ACCESS_NETWORK_STATE` | app manifest `:5` **and** `transport-backend-cct:2.3.3` (`:25`) + `transport-runtime:2.2.6` (`:22`) |
| `android.permission.WRITE_EXTERNAL_STORAGE` (maxSdk 28) | app manifest `:4` only |
| `com.scanwhirl.app.test.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core:core:1.16.0` (not on the keyword list; signature-level, self-scoped) |

**Consequence for PM-1.5:** deleting the two lines from the app manifest is not enough — datatransport re-adds both.
They need `tools:node="remove"` on the `<uses-permission>` elements. There is no removal marker anywhere today.

### 3.2 Services, providers, receivers, meta-data (keyword list)

| Kind | Name | Contributing library |
|---|---|---|
| service | `com.google.mlkit.common.internal.MlKitComponentDiscoveryService` | `play-services-mlkit-text-recognition-common:17.1.0` (merged with `vision-common:17.2.0`, `mlkit:common:18.11.0`) |
| service | `com.google.android.datatransport.runtime.backends.TransportBackendDiscovery` | `transport-backend-cct:2.3.3` (merged with `transport-runtime:2.2.6`) |
| service | `com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService` | `transport-runtime:2.2.6` |
| provider | `com.google.mlkit.common.internal.MlKitInitProvider` (authority `<pkg>.mlkitinitprovider`) | `com.google.mlkit:common:18.11.0` |
| receiver | `com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver` | `transport-runtime:2.2.6` |
| meta-data | `com.google.firebase.components:com.google.mlkit.vision.text.internal.TextRegistrar` | `play-services-mlkit-text-recognition-common:17.1.0` |
| meta-data | `com.google.firebase.components:com.google.mlkit.vision.common.internal.VisionCommonRegistrar` | `com.google.mlkit:vision-common:17.2.0` |
| meta-data | `com.google.firebase.components:com.google.mlkit.common.internal.CommonComponentRegistrar` | `com.google.mlkit:common:18.11.0` |
| meta-data | `backend:com.google.android.datatransport.cct.CctBackendFactory` | `transport-backend-cct:2.3.3` |
| meta-data | `com.google.android.gms.version` | `play-services-basement:18.4.0` |

Related, outside the keyword list: activity `com.google.mlkit.vision.documentscanner.internal.GmsDocumentScanningDelegateActivity`
(`play-services-mlkit-document-scanner:16.0.0`) and activity `com.google.android.gms.common.api.GoogleApiActivity`
(`play-services-base:18.5.0`).

No `com.google.mlkit.vision.DEPENDENCIES` meta-data is present, so PM-1.5's install-time OCR model declaration is
still missing.

### 3.3 Play Billing

**not cached.** No `com.android.billingclient` directory exists in `%USERPROFILE%\.gradle\caches\modules-2\files-2.1`
or in WSL `~/.gradle/caches/modules-2/files-2.1`, and `billing-ktx` is not in `app/build.gradle.kts`. Nothing was
added (forbidden), so its manifest contribution (`com.android.vending.BILLING`, the proxy activity) remains unverified.

### 3.4 Network APIs in the app's Kotlin sources

**none.** No match in `app/src` for `java.net`, `HttpURLConnection`, `OkHttp`, `URL(`, `Socket`, `WebView`,
`android.webkit`, `ConnectivityManager`, `HttpClient`, `URLConnection` or `InetAddress`.

---

## 4. DS-3 findings — device facts (INV-6, INV-9, INV-11)

### 4a. Versions

| Fact | Value |
|---|---|
| `ro.build.version.sdk` | 34 |
| `ro.build.version.release` | 14 |
| `ro.product.model` | Infinix X6711 |
| GMS `versionName` (first line) | `26.43.30 (190400-1006944126)` |
| App `versionName` | `1.1.1-test27` (versionCode 27, minSdk 24, targetSdk 36) |

### 4b. SDK extension level

`adb shell getprop`, lines containing "extension" (raw):

```
[build.version.extensions.ad_services]: [14]
[build.version.extensions.r]: [14]
[build.version.extensions.s]: [14]
[build.version.extensions.t]: [14]
[build.version.extensions.u]: [14]
```

`adb shell ls /apex` -> `ls: /apex: Permission denied` (exit 1). No `extservices`/`sdkext` lines are readable,
so the APEX side is **unknown**.

**For INV-11:** S-extension 14 is well above the 13 that `PdfRendererPreV` needs, so the API-34 phone can open
password-protected foreign PDFs through `PdfRendererPreV`. This is a capability fact only; no in-app test was run.

### 4c. App data (INV-6, INV-9)

**Not executed — `run-as` is blocked.** Both commands returned:

```
run-as: package not debuggable: com.scanwhirl.app.test     (exit 1)
```

`dumpsys package com.scanwhirl.app.test` agrees: `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` (no DEBUGGABLE),
dataDir `/data/user/0/com.scanwhirl.app.test`. The installed APK is the release-signed test build. Per the brief,
DS-3c stopped here and no workaround was attempted.

Consequences: no listing of `files/`, no `edit_snapshot_*` count (INV-6), no `locked.meta` versus `/Encrypt` check
(INV-9), no page-count versus `/Count` comparison. All three stay unverified until a debuggable build is installed.

---

## 5. Changes the plan should make

- **PM-0.1b** — make Linux the gate for the unit suite. Windows is 170 red / 142 host artefacts; WSL Ubuntu with OpenJDK 17 and `ANDROID_HOME=/home/cybium/android-sdk` runs the same task green apart from the 28 real failures.
- **PM-0.1b** — rewrite the 27 bucket-C expectations to M08: 6 Tools tiles (Vault only with a legacy PIN, no PRO band), retired nudge, retired status line, "From your gallery" import copy, and "Search ready" spoken rather than printed.
- **PM-0.1b** — classify `DirectScanSaveTest` as bucket D and hand it to **PM-2.2**, which changes the post-save landing to the Reader (OQ-12); the test must assert that landing, not a null intent.
- **PM-1.5** — remove `INTERNET` and `ACCESS_NETWORK_STATE` with `tools:node="remove"` at merge level; datatransport 2.2.6/2.3.3 re-declares both, so a plain deletion would silently keep them. Re-run `:app:processDebugMainManifest` and assert neither survives.
- **PM-1.5** — add the install-time OCR model declaration; the merged manifest currently has no `com.google.mlkit.vision.DEPENDENCIES` meta-data, so first use would still download the model.
- **PM-0.2** — any test that relies on replacing an existing file cannot pass on Windows (`File.renameTo`); either move those tests to the Linux gate or give the card a replace-safe helper so a Windows run is meaningful.
- **PM-3.1** — record that the owner's phone reports S-extension 14, so `PdfRendererPreV` is available for INV-11; scope password-protected foreign PDFs against that.
- **PM-2.2** — INV-6 and INV-9 are still unverified because the installed test APK is not debuggable; schedule the device check with a debuggable build before shipping the orphan-snapshot notice.

---

## 6. Commands run

| # | Command | Exit | Note |
|---|---|---|---|
| 1 | `git status --short` | 0 | only `docs/plans/` untracked |
| 2 | `.\gradlew.bat :app:testDebugUnitTest -PtestBuild --no-daemon --continue` (attempt 1, piped to `$env:TEMP\ds1-windows-test.log`) | aborted | interrupted at the owner's request (phone USB); no result used |
| 3 | same Gradle test task (run 1) | 1 | 807 tests, 170 failed, 6 m 30 s |
| 4 | PowerShell XML parse of `app/build/test-results/testDebugUnitTest/*.xml` | 0 | 77 files, 807/170/0 |
| 5 | `wsl -l -v` | 0 | Ubuntu, version 2, stopped |
| 6 | `wsl -d Ubuntu -- bash -lc "java -version"` (and `ls /usr/lib/jvm`) | 0 | default java is 21; JDK 17 present |
| 7 | `wsl -d Ubuntu bash -lc "printenv ANDROID_HOME; which sdkmanager adb java"` | 0 | `ANDROID_HOME` empty; `adb`, `java` present |
| 8 | `wsl -d Ubuntu bash -lc "ls /home/cybium/android-sdk; ls .../platforms; ls .../build-tools"` | 0 | android-34/35/36, build-tools 34/35/36 |
| 9 | `wsl ... ./gradlew :app:compileDebugKotlin -PtestBuild --no-daemon --dry-run` | 0 | BUILD SUCCESSFUL, 26 s |
| 10 | `wsl ... JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon --continue` (stdout to `/tmp/core.txt`) | 1 | 807 tests, 28 failed, 11 m 25 s |
| 11 | PowerShell XML parse (Linux results) | 0 | 807/28/0 |
| 12 | `git log -1 065294b`, `git log --oneline -8` | 0 | UX pass present |
| 13 | `Select-String` over layouts, strings, `MainActivity.kt`, `Prefs.kt`, `ScanListAdapter.kt`, tests | 0 | bucket-C citations |
| 14 | `.\gradlew.bat :app:processDebugMainManifest -PtestBuild --no-daemon` | 0 | UP-TO-DATE, 15 s |
| 15 | search for `manifest-merger-debug-report.txt` | 0 | found under `app/build/outputs/logs` |
| 16 | `Get-ChildItem ...\.gradle\caches\modules-2\files-2.1` filtered for billing (Windows and WSL) | 0 | no billingclient |
| 17 | `adb -s 103223137I112880 shell getprop ro.build.version.sdk` / `.release` / `ro.product.model` | 0 | 34 / 14 / Infinix X6711 |
| 18 | `adb ... shell "dumpsys package com.google.android.gms | grep versionName"` | 0 | 26.43.30 (first line) |
| 19 | `adb ... shell "dumpsys package com.scanwhirl.app.test | grep versionName"` | 0 | 1.1.1-test27 |
| 20 | `adb ... shell getprop` filtered on "extension" | 0 | R/S/T/U = 14 |
| 21 | `adb ... shell ls /apex` | 1 | `ls: /apex: Permission denied` |
| 22 | `adb ... shell "dumpsys package com.scanwhirl.app.test | grep -E 'versionCode|flags=|dataDir|codePath'"` | 0 | no DEBUGGABLE flag |
| 23 | `adb ... shell "run-as com.scanwhirl.app.test ls -la files"` | 1 | `run-as: package not debuggable` |
| 24 | `adb ... shell "run-as com.scanwhirl.app.test ls files/documents"` | 1 | same |
| 25 | `.\gradlew.bat :app:testDebugUnitTest -PtestBuild --no-daemon --continue` (run 2, stability) | 1 | 807 tests, 167 failed, 6 m 26 s |

---

## 7. Postscript — 2026-11-14 (what was applied)

Items 1-3 of section 5 are done; 4 and 5 are deliberately not. No product code, manifest, build file, version
or signing was touched. Only test sources and build notes changed (11 modified files, 1 new).

**1. Linux is the documented gate.** New `BUILD_LINUX.md` (exact WSL command, JDK/SDK paths, the three
Windows-only failure shapes). `BUILD_WINDOWS.md` now points at it and says not to judge the suite there.

**2. The 27 bucket-C tests now assert the M08 contract:**
- Tools sheet: six tiles in one Free section, no PRO band, no Vault tile without a legacy PIN
  (`HomeScreenTest`, `ImportSheetTest`/`Narrow`, `LongImageRemovalTest`, `HomeSlice2Test`, `UiSmokeTest`,
  `FreeLegacyVaultEntryPointTest` x2).
- Nudge retired: the tests open Quick sort from the Tools tile and assert the nudge stays hidden
  (`QuickSortTest` x3, `ImportSheetTest`/`Narrow` x2).
- Status line retired: the tests assert it stays hidden even with a stored "on"
  (`StatusLineScreenTest` x4, `UiSmokeTest` x2, `HomeFontScaleTest` x2).
- Import copy: "From your gallery", order note hidden (`ImportSheetTest`/`Narrow`).
- "Search ready" is spoken, not printed (`HomeSlice2Test`).
- Category chip hidden on Unsorted rows (`HomeSlice2LargeTextTest`).

**3. Scan landing.** `DirectScanSaveTest` now asserts that the scanner opens the saved document's screen,
with a comment recording that OQ-12's Reader landing belongs to PM-2.2 (which will change the assertion
again). The test is no longer bucket D.

**Result.** Canonical run on WSL Ubuntu: **807 tests, 0 failed, 0 skipped** (6 m 33 s). The Windows suite was
not re-run; its remaining failures should be the A/B host artefacts only.

**Not done, on purpose.**
- Manifest removal (section 5, item 4): needs the install-time OCR model declaration first and a device
  verification, so it stays a PM-1.5 change with its own ordering.
- Debuggable build (item 5): a debug APK cannot update the release-signed `com.scanwhirl.app.test` in place
  (same package id, different signing key). It needs either an emulator or permission to wipe the app's data.

**Two things noticed while rewriting the tests.**
- `import_pdf_desc` ("Each page becomes an editable page") no longer admits that a PDF is rasterized. That is
  the copy problem `20_ARCHITECTURE_PLAN.md` §1.4 flags; PM-1.4 owns the wording.
- Immediately after closing Quick sort mid-queue, the Unsorted chip still read "Unsorted 2" while the library
  held one unsorted doc. The test now asserts the library state instead of the chip; worth a look when the
  chip rail is next touched.
