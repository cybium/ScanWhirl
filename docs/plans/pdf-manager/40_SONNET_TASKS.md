# 40 — Task cards for Sonnet

## How to run these cards

- **One card = one change set.** For every card:
  1. Compile: `.\gradlew.bat :app:compileDebugKotlin -PtestBuild --no-daemon`
  2. Run that card's tests.
  3. Run the full unit suite in the environment fixed by PM-0.1.
  4. Lint: `.\gradlew.bat lintDebug -PtestBuild --no-daemon`
- Build a test APK only when the card has device acceptance (`assembleRelease -PtestBuild`).
- **Never** change `versionCode`/`versionName`, the package id, signing, `build.gradle.kts`, or add dependencies or permissions.
- Every user-visible string goes in `values/strings.xml` **and** `values-hi/strings.xml`.
- **Back up device fixtures before destructive device checks.** DocX was already damaged once.
- Line references are to tree `0c283b8`. Re-confirm with search before editing.

**Execution order.**

- **DeepSeek first:** the DeepSeek pack DS-1…DS-4 (`50_OPEN_QUESTIONS.md`). Its report is `docs/audit/2026-11-13/PDF_TOOLS_STATUS.md`.
- **Then Sonnet:** PM-0.1b → 0.2 → 0.3 → 1.1 → 1.2 → 1.3 → 1.4 → 1.5 → 2.1 → 2.2 → 2.3 → 2.5 → 2.4 → 2.6 → 2.7 → 2.8 → 3.1 → 3.2 → 3.3 → (3.4 spike) → 4.1 → 4.2 → 4.3 → 4.4 → 5.1 → 5.2 → 5.3 → 5.4 → 5.5 → (6.1 spike) → 6.2 → (6.3 spike → ship).

Slice 7 has no cards; it is approval-gated.

**Device priority (owner): Android 10+.** Device acceptance is run on API 29–36. The owner's phone is an Infinix X6711, Android 14 / API 34. API 24–28 must not crash or lose data, but may hide a feature (E23). `minSdk` is not changed.

**Owner answers in force:** Play Billing approved; ₹100 one-time; Long image returns; network permissions removed (PM-1.5); Hindi OCR via spike (PM-6.3).

Common definitions: invariants I1–I12 are in `20_ARCHITECTURE_PLAN.md` §4. The operation pipeline is §3.1. Screens are in `10_PRODUCT_SPEC.md` §3.

---

# Slice 0: Test status and current PDF invariants

## PM-0.1 Establish the real test baseline (a: DeepSeek DS-1 · b: Sonnet)

**Split.**
- **Part a** (DeepSeek, before Sonnet) does steps 1, 2 and 5 below and writes `PDF_TOOLS_STATUS.md`.
- **Part b** (Sonnet, "PM-0.1b") reads that report and does steps 3 and 4.
- If the report is missing, Sonnet does all steps.

**User-visible goal.** None. This card makes "green" mean something again.

**Dependencies.** None; part b depends on part a's report.

**Study.**
- `20_ARCHITECTURE_PLAN.md` §1.7
- `util/Durable.kt` (`atomicWrite`, `syncDir`)
- `util/PageReplace.kt` (`commit`)
- `util/PdfGenerator.kt:169–175`
- `app/src/main/res/xml/file_paths.xml`
- the failing classes listed in §1.7

**Change, in product terms.** No product behaviour changes.

1. **Run the full suite under Linux** against this same tree: WSL against `/mnt/c/Users/Cybium/Desktop/ScanWhirl` with a Linux JDK 17 and Android SDK, or any Linux runner. Record the totals.
2. **Classify every failure** into:
   - **A** host-only (passes on Linux, fails on Windows);
   - **C** stale expectation from the committed UX pass `065294b`;
   - **R** real regression;
   - **D** behaviour question.
3. **Fix C-tests only where the UI change was deliberate.** The UX pass is described in `docs/audit/2026-11-13/HANDOFF.md` "What is already changed".
   - Rewrite the assertion to the new intended UI. Never weaken a data-safety assertion.
   - Leave the Tools-sheet tile tests to PM-1.1 if that card follows immediately. Otherwise update them to the 6-tile sheet now.
4. **Do not "fix" bucket A by changing production rename semantics.** Product code targets Android, where `rename(2)` replaces atomically. Instead, either:
   - make `Durable`/`PageReplace`/`PdfGenerator` replacement go through one small internal seam, whose JVM-test implementation deletes the target before renaming **only when `os.name` is Windows and running under Robolectric**; or
   - document that the gate runs on Linux only.

   Choose the second unless the owner asks otherwise. Production code must not change for a test host.
5. **Report `DirectScanSaveTest`** as a behaviour question (OQ-12). Do not change `scanSaved`.

**State model.** None.

**Edge cases.** WSL path performance. Use `--no-daemon` and one gate at a time.

**Test plan.** Full suite on Linux.

**Acceptance criteria.**
- A file `docs/audit/2026-11-13/PDF_TOOLS_STATUS.md` exists. Keep it outside `docs/plans/pdf-manager/`, which stays at seven files. It contains:
  - Linux totals, Windows totals
  - each failing test with its bucket and reason
  - the commit hash
- Bucket R is empty, or each R item has an owner note.
- The full suite on the chosen gate environment is green except D.

**Out of scope.**
- Any product fix.
- Search behaviour (the three earlier search failures already pass: `SearchMatcherTest` 16/16, `LockedDocumentSearchPrivacyTest` 9/9).

**Risk / rollback.** Docs and tests only. Revert the commit.

---

## PM-0.2 PDF invariant test pack

**User-visible goal.** None. It turns the plan's promises into failing or passing tests.

**Dependencies.** PM-0.1.

**Study.**
- `20_ARCHITECTURE_PLAN.md` §4
- `test/.../util/ImportFixtures.kt`
- `SaveFaults` and `ImportStage` seams
- `UiSmokeTest:1493–1614`
- `PdfVerifierTest`

**Change, in product terms.**

1. Add a test-only helper, `PdfInvariants`, that asserts I1–I5 and I10 on a document id, and I6 on the documents, staging and drafts directories.
2. Add tests that state today's behaviour as **desired** behaviour:

   | Test | Expected desired behaviour | Status today |
   |---|---|---|
   | a | `rotatePage`/`deletePage`/`movePage`/`setPageFilter`/`cropPage` on a locked document keeps `/Encrypt` in `scan.pdf`, or is refused | violated today; fixed by PM-0.3 |
   | b | `unlockPdf` cannot be reached from `LockFlow` or the `DocumentViewerActivity` More sheet without the current password (UI test) | violated; PM-0.3 |
   | c | `mergeDocuments`/`extractPages`/`compressScan`: a PDF-generation failure publishes nothing | violated; PM-2.1 |
   | d | the same operations leave `ocr_pending` or copied OCR on the output | violated; PM-2.1 |
   | e | `mergeDocuments` with simulated death after the first page leaves no visible document | violated; PM-2.1 |
   | f | for every operation: source directory digest unchanged (I5) | — |
   | g | 300-page synthetic document: `compressScan` completes without OOM in Robolectric | likely violated; PM-2.5 |

3. Tests that fail today are annotated `@Ignore("PM-x.y: <one-line reason>")`, naming the card that will remove the `@Ignore`.

**State model.** None.

**Edge cases.** Robolectric cannot render foreign PDFs through `PdfRenderer`. Use ScanWhirl-generated documents and the existing import fixtures. A process-death simulation throws an `Error` from a seam; add seams only in test-visible `internal` hooks, following the `importFault` pattern (`ScanRepository.kt:107–111`).

**Test plan.** The new test class `PdfInvariantsTest`, plus the helper.

**Acceptance criteria.**
- The suite stays green.
- Every `@Ignore` names a card.
- `PDF_TOOLS_STATUS.md` lists the ignored tests and their cards.

**Out of scope.** Fixing any of them, except in PM-0.3.

**Risk / rollback.** Tests only.

---

## PM-0.3 Lock integrity guards

**User-visible goal.** A document showing 🔒 is really protected, and nobody can remove the protection without the password.

**Dependencies.** PM-0.2.

**Study.**
- `ScanRepository.kt:1495–1540`, `:1651–1700`
- `tools/LockFlow.kt:36–101`
- `ui/DocumentViewerActivity.kt:573–742`
- `ui/MainActivity.kt:1764–1810`

**Change, in product terms.**

1. **Page edits on a locked document are refused** with "Unlock this document to edit its pages." This covers rotate, delete, move, filter and crop.
   - Implemented as a repository guard: no in-place regeneration of `scan.pdf` when `locked.meta` exists.
   - Organize on locked documents returns properly in PM-3.1 via the draft and re-encryption.
2. **Unlock** (Tools → Lock → saved scan, and More sheet → Unlock) asks for the current password. A wrong password shows an inline error and changes nothing.
3. **Change password** asks for the current password first.

**State model.** locked/unlocked. A password is verified per action and never stored beyond the existing hash.

**Edge cases.**
- Legacy documents with `locked.meta` but no `/Encrypt` (a result of the old bug): Info shows "Protection needs repair". The user can Unlock with the password (a normal unlock regenerates plain), then Protect again.
- Detection: check `scan.pdf` for `/Encrypt` once, at load time in the reader. Do not scan on every Home render.

**Test plan.**
- Un-ignore PM-0.2 tests a and b.
- New UI tests:
  - wrong password changes nothing (`scan.pdf` digest equal);
  - right password unlocks;
  - Change password with a wrong current password changes nothing.

**Acceptance criteria (device).**
1. Lock a test document with `1234`.
2. Open it, enter `1234`, try to rotate a page. The message says to unlock first, and the page is unchanged.
3. Tools → Lock → choose the document → Unlock. You are asked for the password. Typing `0000` shows an error.
4. Share the document to another PDF app. It asks for the password.

**Out of scope.** The new Password setup screen (PM-3.1), AES, and organizing locked documents.

**Risk / rollback.** Users who used to edit locked documents now must unlock first. This is temporary, until PM-3.1. Revert = remove the guard.

---

# Slice 1: PDF Tools home, picker and reader dock

## PM-1.1 PDF Tools screen and entry points

**User-visible goal.** Tapping **Tools** on Home opens a full PDF Tools screen with quick actions, Recent and grouped tools. The old Tools sheet is gone.

**Dependencies.** PM-0.1.

**Study.**
- `MainActivity.kt:303–340` (dock), `:1160–1273` (`showToolsSheet`, column rule), `:375` (proBadge)
- `res/layout/sheet_tools.xml`, `view_tool_tile.xml`
- `tools/*Flow.kt` entry functions
- `ui/SettingsActivity.kt`
- `10_PRODUCT_SPEC.md` §3.1, P6, P8

**Change, in product terms.**

1. **New `PdfToolsActivity`,** laid out as wireframe 3.1:
   - Quick actions: Import PDF · Merge · Compress · Password.
   - Recent (up to 5; hidden when empty).
   - Groups. Free tiles only in this card; Power Pack tiles come with PM-4.1.
   - "Everything stays on this phone."
2. **Tiles launch the existing flows** for now:
   - Merge → `MergeFlow.showMergeChooser`
   - Compress → `CompressFlow.start`
   - Password → `LockFlow.chooseLockSource`
   - Extract text → the existing OCR source chooser
   - Resize photo → the existing image picker plus `ResizeFlow`
   - Import PDF → the existing import

   Single-document tools switch to the new picker in PM-1.2.
3. **Tiles not built yet are not shown:** Organize pages, Extract & split, Export as images.
4. **Home dock Tools** opens `PdfToolsActivity`. Delete `showToolsSheet` and `sheet_tools.xml` if nothing else uses them.
5. **Relocations:**
   - Quick sort → an item in the Home sort menu.
   - Legacy Vault → a Settings row, shown only when `Prefs.hasVaultPin`.
6. **Empty library** shows the P8 card.
7. **Recent** is maintained by the Reader (PM-1.3) and by tool results (PM-2.1). In this card, only the read side and the Prefs storage.

**State model.** Activity state: none persisted except scroll. Recent is an id list in Prefs.

**Edge cases.**
- 200% font: reuse the measured 4→3 column rule, extracted to a helper.
- Rotation.
- Vault mode open on Home: Tools is disabled in Vault mode, as Merge is today.
- No documents.

**Test plan.** Rewrite the Tools-sheet tests listed in `20_ARCHITECTURE_PLAN.md` §5.4 to assert on `PdfToolsActivity`. Keep `LongImageRemovalTest` semantics: there is no long-image tile anywhere, and the tile count in PDF Tools is exact. Add:
- tile labels do not break mid-word at 1.0 / 1.3 / 2.0 scale;
- empty state shows two buttons;
- Recent hides when empty;
- Quick sort is reachable from the sort menu;
- the Vault row shows only with a legacy PIN.

**Acceptance criteria (device).**
1. Home → Tools opens PDF Tools with 4 quick actions and the groups Organize (Merge), Optimize (Compress, Resize photo), Convert (Extract text) and Secure (Password).
2. At 200% font, no tile label is cut or broken mid-word, and the screen scrolls.
3. With 0 documents: "No documents yet" with Import a PDF and Scan a document.
4. The Home sort menu contains "Quick sort…", which opens the existing sheet.
5. No "PRO", gold band or "Not available yet" anywhere on this screen.

**Out of scope.** The new picker, new setup screens, Power Pack tiles, and Organize.

**Risk / rollback.** About 8 UI test classes change. Rollback: point Tools back at `showToolsSheet` (keep the commit small).

---

## PM-1.2 Document picker

**User-visible goal.** Every tool chooses documents from a list that shows thumbnail, pages, size, date and lock, not from names alone. Merge shows the order before Next.

**Dependencies.** PM-1.1.

**Study.**
- `ui/ScanListAdapter.kt`
- `ui/home/DocThumbBinder.kt`
- `util/SearchMatcher.kt`, `util/HomeFilter.kt`
- `tools/FlowUtil.kt:42–59` (`pickScan`)
- `MergeFlow.kt:48–71`
- `HomeSlice2Test:269` (locked thumbnails)
- wireframe 3.2

**Change, in product terms.**

1. **New `DocPickerActivity`** with modes single, multi-ordered and browse (§3.2). It shows:
   - a search field (names plus text; locked documents by name only);
   - category chips;
   - an Imported chip (shown only when at least one document has `origin=import`, which exists after PM-2.7);
   - a Locked chip.
2. **Rows** follow §3.2: duplicate-name disambiguation, and no thumbnail decode for locked documents.
3. **Multi-ordered mode:**
   - Selections append to the tray. Drag handle ≡ reorders, × removes.
   - Accessibility actions: Move up, Move down.
   - The total page count is shown. Above 500 pages, Next is disabled with a reason.
4. **Locked row** opens the password prompt (§5.4). The verified password returns with the selection, in memory only.
5. **Replace `FlowUtil.pickScan` usages** with the picker in single mode. Merge from library documents uses multi-ordered mode, and the order passes straight to `repo.mergeDocuments(ids…)` (that API already takes an ordered list).
6. **PDF Tools → "All documents"** opens browse mode.

**State model.**

| Field | Persistence |
|---|---|
| selection ids (ordered) | survives rotation and process recreation |
| query | survives |
| chip | survives |
| passwords | **never** persisted; a recreated activity re-prompts |

**Edge cases.**
- A document deleted while the picker is open: drop it from the selection, with a snackbar.
- A vaulted document is never listed.
- 0 eligible documents: §5.2 empty state.
- 500+ documents: RecyclerView, sampled thumbnails.
- A duplicate name with the same date and size: show the `#id4` suffix.

**Test plan.** Robolectric tests:
- row fields present;
- a locked row shows no decoded thumbnail and prompts for the password;
- the tray order equals the merged document page order (merge two seeded documents, then check the page digests' order);
- the >500 total disables Next;
- rotation keeps the selection;
- TalkBack move actions reorder.

**Acceptance criteria (device).**
1. Merge: tick "Document 3", then "Receipts". The tray shows 1 Document 3, 2 Receipts. Drag Receipts to 1. Next → Merge. The merged document's page 1 is Receipts page 1.
2. Two documents named "M08 Book1" are distinguishable by date and size, or by `#id`.
3. Compress → the picker shows the lock on a locked document. Selecting it asks for the password.

**Out of scope.** New Merge and Compress setup screens (PM-2.4, PM-2.5) and the Imported chip data (PM-2.7).

**Risk / rollback.** Contained in a new activity. Rollback: flows call `FlowUtil.pickScan` again.

---

## PM-1.3 Reader dock and Document tools sheet

**User-visible goal.** Opening a document shows Share · Text · Tools · More, always. The reader is no longer a dead end (D5, D16).

**Dependencies.** PM-1.1.

**Study.**
- `ui/PdfViewerActivity.kt` (toolbar menu `:115–123`; locked preview `:230–236`)
- `res/layout/activity_pdf_viewer.xml`
- `DocumentViewerActivity.kt:135–160, 503–544, 573–637` (dock, save, More sheet to reuse)
- `res/layout/sheet_more_document.xml`
- wireframe 3.3

**Change, in product terms.**

1. **Dock** on the reader, only for library documents (`EXTRA_DOC_ID`). Not shown for path or URI views.
   - Share → `ShareUtils.share`
   - Text → `OcrFlow.runOcrForScan`
   - Tools → the Document tools sheet
   - More → Rename · Save to Documents · Category · Duplicate · Info · Move to Trash
2. **Toolbar:** Find (existing) and Save to Documents (⤓, same export transaction as `DocumentViewerActivity.savePdfToDocuments`).
3. **Lock glyph** in the title row for locked documents.
4. **Persistent page indicator.**
5. **Document tools sheet** (new, shared): Compress · Password · Extract text in this slice, more tiles in later slices. The document is preselected, so there is no picker.
6. **Info sheet:** name, pages, share-file size, created date, category, protection (Locked / Not locked / Needs repair), text state (ready n/N), "Image-based PDF" line.
7. **Recent:** opening a document in the reader records it.
8. **Home row ⋯** gains "Tools…", which opens the same Document tools sheet.

**State model.** The reader keeps its existing state. The dock is stateless. More actions reload the document. Rename updates the title.

**Edge cases.**
- Locked document: the reader is reached only after the password (existing `MainActivity.openViewer`). Share sends the encrypted file.
- Vaulted document: the dock is the same, but Move to Trash reads "Delete permanently" (existing rule).
- Deleted while open: finish, with `doc_not_found`.
- A rotation with Find open keeps both.
- 200% font: dock labels may be hidden, icons have content descriptions, and the 48dp targets remain.

**Test plan.**
- The dock is visible for a document id and absent for a path view.
- Every dock action opens its target.
- Move to Trash goes through `TrashActions`.
- Locked: the title row glyph is visible.
- Recent updated.

**Acceptance criteria (device).**
1. Home → tap DocX. The reader shows Share · Text · Tools · More at the bottom while scrolling through pages.
2. Tools → Compress opens compress for DocX with no picker.
3. More → Info shows "1 page · 1009 KB · Image-based PDF" (or the current values). The size equals the row size.
4. The ⤓ icon saves to Documents and shows the existing Undo snackbar.

**Out of scope.** Edit (PM-2.2), Export as images (PM-4.2) and Power Pack tiles.

**Risk / rollback.** Layout change in one activity; the Find tests (`RetrievalSlice5Test`) must stay green.

---

## PM-1.4 Remove PRO dead ends; make Password Free; fix misleading copy

**User-visible goal.** No screen says "Not available yet", and no tap leads to a pitch with nothing to buy (D3). Password protection works for everyone.

**Dependencies.** PM-1.1.

**Study.**
- `MainActivity.kt:375` (proBadge), `showProPanel`
- `DocumentViewerActivity.kt:550–556, 587–616, 698–701`
- `res/layout/dialog_pro_panel.xml`
- `util/ProGate.kt`
- `strings.xml:119,165,169,209,259`
- `FreeLegacyVaultEntryPointTest:320–357`
- `30_FREE_PRO.md` §4

**Change, in product terms.**

1. **Protect is Free.** Remove `pillLock`, the `ProGate` checks in `promptProtectPdf` and the More-sheet lock row.
2. **proBadge** on Home is hidden unless the user owns the Power Pack (`OWNED`), in which case it shows "Power Pack" as a quiet label.
3. `showProPanel` is unreachable. Delete `pro_status_unavailable` and the panel layout if nothing else uses them.
4. **Rewrite these strings** (English and Hindi):

   | String | New text |
   |---|---|
   | `save_changes_msg` | "Your edits are already saved in this document." (interim truth until PM-2.2 replaces the dialog) |
   | `import_pdf_desc` | "Adds a PDF to your library as page images" (PM-2.7 updates it again) |
   | `tool_compress_sub`, `tool_merge_sub` | delete if unused, else rewrite plainly |

**State model.** None.

**Edge cases.** A test build forced Free (`ProGate` override 1) shows no proBadge and no PRO pills anywhere.

**Test plan.**
- Update `FreeLegacyVaultEntryPointTest:338` to "free user can protect".
- Add a source scan test: the string "Not available yet" exists in no `strings.xml`.
- UI: forced-Free test build, More sheet → Protect opens the password dialog.

**Acceptance criteria (device, test build with override Free).**
1. The Home header has no PRO/FREE tag.
2. Document → More → Protect asks for a password and locks the document.
3. Search all screens: no "Not available yet".

**Out of scope.** The Power Pack gate (PM-4.1) and the storefront.

**Risk / rollback.** Monetisation copy change. Owner-approved by P4.

---

## PM-1.5 Remove network permissions and preinstall the OCR model

**User-visible goal.** ScanWhirl provably cannot use the internet, and text recognition works offline from the first launch on any phone where Google Play services has finished installing the model.

**Dependencies.**
- PM-1.1.
- DeepSeek DS-2 report: which libraries merge `INTERNET`, `ACCESS_NETWORK_STATE` or telemetry services.
- Owner approval OQ-4: given.

**Study.**
- `app/src/main/AndroidManifest.xml:4–6`
- the merged manifest and the DS-2 findings
- `util/OcrEngine.kt:18–47` (`isUnavailable`)
- `OcrStatusTest`
- About string (`strings.xml:48`)
- Settings privacy text (D11)
- ML Kit text recognition v2 Android guide, section "If you choose to use the model in Google Play Services": the install-time download declaration

**Change, in product terms.**

1. **Remove `INTERNET` and `ACCESS_NETWORK_STATE`** from the app manifest. Where DS-2 shows a library merging them in, remove the merged copy with the manifest-merger remove marker.
   - This is a manifest edit, which is app code. It is **not** a `build.gradle.kts` edit, and no dependency is added.
2. **Add the ML Kit install-time model declaration** for the OCR model. Add the Devanagari model only when PM-6.3 ships.
3. **Copy, in English and Hindi:**
   - About: "ScanWhirl has no internet access. Google Play services installs the scanner and text-recognition models on your phone. Your pages never leave it."
   - The Auto-enhance setting's "Google processing" line (D11) becomes: "Runs on your phone through Google Play services."
4. **When OCR is unavailable** because the model is not installed yet, the existing M06 message gains: "Google Play services is still installing text recognition. Connect once to Wi-Fi or mobile data, then try again."

**State model.** None.

**Edge cases.**
- A phone with no Google Play services: the existing scanner and OCR errors still apply. Nothing new breaks.
- A library that crashes when the network permission is missing. Watch the device logcat during step 1 of the device checks below.

**Test plan.**
- A source-scan test: the app manifest declares no `INTERNET` or `ACCESS_NETWORK_STATE`.
- A merged-manifest check, recorded in `PDF_TOOLS_STATUS.md`: run `processDebugMainManifest` and confirm both permissions are absent.
- Invariant I12 (no network code) stays green.

**Acceptance criteria (device, API 34 phone; repeat on any API 29–33 device if available).**
1. Install the test APK. Open the app once while online, and wait until OCR works on one page.
2. Turn on airplane mode. Scan a page with the Google scanner: it works. Read its text: it works.
3. Settings → App info → Permissions shows no network-related entries. No crash appears in logcat during scan, OCR, import or export.

**Out of scope.** Bundled (in-APK) OCR models: they cost about 4 MB per script.

**Risk / rollback.** If ML Kit or the scanner fails without the permission, revert the manifest change. The About copy must then go back to the "before PM-1.5" claim in E15.

---

# Slice 2: Organize, merge, compress, extract, naming

## PM-2.1 Operation pipeline, provenance and Result screen

**User-visible goal.** Every tool output appears as a complete document or not at all. A Result screen proves what happened and offers Open, Share, Save, Show in library and Undo.

**Dependencies.** PM-0.2.

**Study.**
- `ScanRepository.kt:1002–1128` (duplicate, extract, merge), `:1299–1359` (`newDocDir`, `finalizeDoc`, `createDocument`, `compressScan`), `:1391–1460` (import staging pattern to copy)
- `SaveTransaction.kt` (fault seam style)
- `ui/ExportResultActivity.kt`
- `ui/home/HomeReveal.kt`
- `ui/TrashActions.kt`
- `20_ARCHITECTURE_PLAN.md` §2.1, §2.3, §3

**Change, in product terms.**

1. **`OperationRunner`** implements pipeline §3.1: staging, then pages one at a time, then PDF, then verify, then provenance, then meta, then OCR carry-over or pending, then an atomic publish. It has a fault seam for each stage and Cancel between pages.
2. **`Provenance`** read/write. `ScanDocument` gains `origin`, `hasOriginalPdf` and `sourceIds`, all with defaults.
3. **Moved onto the runner:** `extractPages`, `mergeDocuments`, `compressScan`/`createDocument`, `duplicateDocument`. Public signatures stay, so existing callers keep compiling. Behaviour changes only for the better: no publish without a PDF, and OCR carried over or queued.
4. **OCR carry-over:** when an output page's bytes equal a source page's bytes, copy that page's text and geometry under the new key. Otherwise mark pending.
5. **Startup recovery:** sweep `op_staging` entries older than 1 hour. A Replace staging tagged `replaces=<id>`, where `documents/<id>` is missing, is published as `<id>`.
6. **Result screen** (evolved `ExportResultActivity`), per §3.2 and wireframe 3.6:
   - Undo = move the new documents to Trash.
   - Show in library = Home with reveal.
   - Recent records the outputs.
   - The error form shows Retry and Choose another.
7. **The existing Compress and Merge flows** open the new Result screen.

**State model.** Operation: planned → building(page n/N) → verifying → published | cancelled | failed(reason, page). Result: shown → undone.

**Edge cases.**
- ENOSPC mid-page: classify like `PdfImport.classifyWrite`.
- A source changed between plan and build (page digest differs): fail with "‹name› changed while working. Try again."
- A name collision: append " (2)".
- Locked sources: refused until PM-3.1, with "Unlock … first"; the PM-0.3 guard remains.
- Process death after publish but before Result: the document exists, which is fine.

**Test plan.**
- Un-ignore PM-0.2 c, d, e and f.
- For each migrated operation:
  - happy path plus I1, I4, I5 and I10;
  - fault at each stage → I6;
  - cancel → I6;
  - simulated death → recovery → I6.
- Result: Undo moves to Trash, and the Trash restore is byte-identical.
- Existing `ScanRepositoryTest`, `PageSelectTest` and `CategoryPersistTest` pass unchanged.

**Acceptance criteria (device).**
1. Merge two documents. The Result shows "Created “Merged · ‹date›” · 5 pages". Open → 5 pages; the Home row says 5 pages.
2. Tap Undo. The document disappears from Home and is listed in Trash. Restore it, and it opens with 5 pages.
3. Start compressing a 30+ page document, then force-stop during progress. Relaunch: no partial document, and the source is unchanged (same size and pages).

**Out of scope.** Setup screens (PM-2.5), Organize (PM-2.2) and Split.

**Risk / rollback.** Core repository change. Keep the old functions' signatures; rollback = revert the delegation commit.

---

## PM-2.2 Organize pages with a persistent draft (fixes D1/D2)

**User-visible goal.** Rearranging, rotating and deleting pages never changes the saved document until the user chooses **Save as new copy** (primary) or **Replace original** (confirmed). **Discard** always works, including after a force-stop.

**Dependencies.** PM-2.1.

**Study.**
- `DocumentViewerActivity.kt` (whole file; especially `:62–100, 165–220, 326–491, 796–869`)
- `ui/pagegrid/PageGridView.kt`, `PageThumbLoader.kt`
- `ScanRepository.kt:1151–1297`
- `00_DECISIONS.md` E12
- wireframe 3.4
- `20_ARCHITECTURE_PLAN.md` §2.2, §7

**Change, in product terms.**

1. **`DraftStore`** implements manifest §2.2 with atomic rewrites after every change.
2. **Organize screen.** Repurpose `DocumentViewerActivity` into the draft organizer, or create `OrganizeActivity` and retire the old screen's editing role.
   - **Recommendation:** repurpose, to keep `ExportHygieneTest`/`UiSmokeTest` hooks. Remove the old Share/Edit/Text/More dock from this screen; the Reader owns those now.
   - Header: "Draft · N pages · K changes".
   - Grid thumbnails apply quarter-turns as a display transform.
   - Select mode: Rotate · Delete (Undo snackbar edits the draft) · Extract (passes the selected draft pages to Extract, PM-2.6; before PM-2.6, use `extractPages` through the runner).
   - Drag reorder updates the manifest.
3. **Bottom row:** Discard · Replace original · **Save as new copy**.
   - Save: name prompt (default `‹name› (edited)`) → runner → Result. The draft is deleted only after publish.
   - Replace: confirmation names the document → runner builds → current directory to Trash as "‹name› (previous version)" → staged directory renamed to the same id → Result with Undo = restore previous.
   - Discard: confirm → delete the draft.
4. **Back with changes:** Save as new copy / Discard / Keep editing.
5. **Reader dock gains Edit** (between Share and Text). The Home quick action "Edit pages" opens the same screen.
6. **Draft banner** in the reader: "Unsaved page edits · Resume · Discard".
7. **Stale draft** (base page digest differs): only Save as new copy and Discard.
8. **Legacy `edit_snapshot_*` migration** notice (§7).
9. **The UI no longer calls** `ScanRepository.deletePage/rotatePage/movePage` on library documents.

**State model.** `none → draft(open) → draft(persisted, closed) → saved(new | replaced) | discarded`. Stale is a flag. The change count equals the number of manifest mutations since creation (Undo decrements).

**Edge cases.**
- Deleting all pages: refused (last-page guard).
- A 500-page draft: the manifest is about 50 KB, fine.
- The base document trashed while a draft exists: the draft remains; the reader banner is not shown; recovery offers "Save draft as new copy" from Trash restore. Simplest acceptable alternative: delete the draft when the base document is permanently deleted.
- The base document locked: Organize is refused until PM-3.1 ("Unlock to edit pages").
- Low space at Save: Result error. The draft is kept.

**Test plan.**
- I7: the base document is byte-identical after many draft changes.
- Simulated process death mid-edit: the draft resumes with an equal manifest.
- Discard deletes only the draft.
- Save → I1, I5, I10.
- Replace → I8 and Undo.
- Stale detection.
- Grid count equals the draft count after each change.
- Migration notice.

**Acceptance criteria (device, on a copy of DocX or a fresh 3-page fixture).**
1. Open Organize, delete page 2, rotate page 1. The header says "Draft · 2 pages · 2 changes". Home still shows the document with **3** pages.
2. Force-stop and relaunch, then open the document. The banner offers Resume. Resume shows 2 pages and page 1 rotated.
3. Discard. The document opens with 3 pages, byte-identical (same size).
4. Repeat 1, then Save as new copy. Home shows "‹name› (edited)" with 2 pages, and the original still has 3.
5. Repeat 1, then Replace original → confirm. The document has 2 pages, Trash has "‹name› (previous version)" with 3 pages, and Undo on Result restores 3 pages.

**Out of scope.** Crop and filter in the draft (PM-2.3), Insert (PM-2.8) and locked documents (PM-3.1).

**Risk / rollback.** Highest-risk card. Keep it in its own commit. Rollback restores the old dock and in-place behaviour, with a known D1 regression.

---

## PM-2.3 Page editor writes to the draft

**User-visible goal.** Crop, filter, rotate and delete in the full-page editor follow the same Save/Discard rule as Organize.

**Dependencies.** PM-2.2.

**Study.**
- `ui/PageViewerActivity.kt:163–560`
- `ScanRepository.kt:1186–1297`
- `util/ScanFilter.kt`, `util/PageReplace.kt`, `ui/CropView.kt`

**Change, in product terms.**
- Opened from Organize, `PageViewerActivity` edits the draft entry:
  - Crop and filter produce `r_<n>.jpg` in the draft. Filters always apply from the source page or its `.orig`, never stacking.
  - Rotate changes quarter-turns.
  - Delete marks the entry.
- "Original" filter restores the entry to its source page.
- Returning to Organize shows the change count updated.

**State model.** Per draft entry: source | replacement. Quarter-turns.

**Edge cases.**
- A crop on a rotated entry is applied in display orientation.
- An unreadable source page: "This page can't be edited", nothing changes.
- A memory cap: decode sampled to 3000 px (existing `ImageTools.decodeSampled` pattern).

**Test plan.**
- After crop + filter + Discard, the base document is byte-identical.
- After Save, the output page dimensions match the crop.
- Filter Original → the entry's replacement is removed.

**Acceptance criteria (device).**
1. Organize → tap page 1 → crop → Back. Organize says "1 change". Home thumbnail unchanged.
2. Save as new copy. The new document's page 1 is cropped; the original is not.

**Out of scope.** New filters and B/W comparison.

**Risk / rollback.** Contained in `PageViewerActivity` mode handling.

---

## PM-2.5 Tool setup screen, Compress v2 and Resize photo v2

**User-visible goal.** Every tool configures on one full-screen form that names the document, never hides its buttons behind the keyboard, and states the result before Apply (fixes D7, D9).

**Dependencies.** PM-2.1, PM-1.2.

**Study.**
- `tools/CompressFlow.kt` (`levels :47–52`, `pickLevel :146–181`, target helpers `:323–353`)
- `tools/ResizeFlow.kt`
- `res/layout/dialog_compress.xml` and the resize layout
- `ImageTools.parseSizeInput`, `compressToFit`
- wireframe 3.5
- E19

**Change, in product terms.**

1. **`ToolSetupActivity`** has a header (name · pages · size · badges), an options container, an estimate line, a name field, a destination sentence, and a sticky Cancel/Verb row. It uses `adjustResize`.
2. **Compress:**
   - Four presets with estimates (reuse the sampling), **or** Exact size as a numeric field with a KB/MB unit chip.
   - Selecting one disables the other.
   - Streaming build: one page at a time.
   - The original-smaller warning (when `hasOriginalPdf`; until PM-2.7, never).
   - The result goes through the runner. Locked sources follow PM-0.3 or PM-3.1.
3. **Device-PDF compress** (Compress → "From a file"): import first (existing path), then compress the imported document. Remove the in-memory render-all path (`CompressFlow.kt:282–317`).
4. **Resize photo** uses the same pattern:
   - Presets and Exact are mutually exclusive.
   - The numeric keypad has the unit beside the value.
   - The hint is never shown as a value.
   - The action row stays above the keyboard.

**State model.** Setup: editing → validating → running(progress) → result. Option values survive rotation.

**Edge cases.**
- Exact target unreachable: the Result warns "Couldn't reach 100 KB; smallest is 180 KB" (existing `compress_target_missed`).
- 0 or empty: inline error.
- Above the source size: inline "Already smaller than that".
- Cancel during the run: nothing created.

**Test plan.**
- Keyboard: with the IME shown, the Verb button is visible. Follow the `HomeKeyboardFocusTest` approach.
- Numeric `inputType`.
- Mutual exclusion.
- Un-ignore PM-0.2 g (300 pages, no OOM).
- I5.
- Estimates within ±25% of actual on fixtures.

**Acceptance criteria (device).**
1. Compress DocX. The title names DocX. Tap Exact size: a numeric keypad opens, and **Compress** stays visible above it.
2. The presets are disabled while Exact is active.
3. The Result shows before → after, and DocX is unchanged.
4. Resize photo: type 500 with the KB chip. **Resize** is visible above the keyboard, and there is no orphan "500 KB" row.

**Out of scope.** Batch compress and a quality slider.

**Risk / rollback.** Replaces two dialogs. The old dialogs can be restored per tool.

---

## PM-2.4 Merge v2

**User-visible goal.** Choose documents in a visible order, name the result, and protect it if any source was locked.

**Dependencies.** PM-1.2, PM-2.1, PM-2.5.

**Study.**
- `tools/MergeFlow.kt`
- `ui/MergePdfActivity.kt` (device PDFs)
- `ScanRepository.kt:1062–1128`

**Change, in product terms.**
- Merge = picker (multi-ordered) → setup: "17 pages from 2 documents", name `Merged · ‹date›`, destination → runner → Result.
- Device-PDF merge (`MergePdfActivity`): keep its existing builder, but publish through the runner and land on Result.
- **Locked sources:** after PM-3.1, a required password for the result. Before that, refused with a reason.
- The `MergeFlow` source chooser sheet ("saved scans or device PDFs") becomes two rows in the picker header: "From library" (default) and "Add PDF files…".

**State model.** As PM-1.2 plus PM-2.5.

**Edge cases.**
- Over 500 total pages: blocked in the picker.
- A source deleted mid-run: fail with its name.
- The same document added twice: allowed, shown twice in the tray (deliberate duplication).

**Test plan.**
- The merged page digests equal the concatenated source digests, in tray order.
- I1, I5, I10.
- Fault and cancel → I6.

**Acceptance criteria (device).**
1. Merge DocX and a 2-page document, in the order 2-page first. The Result says 3 pages, and the reader shows the 2-page document's pages first.

**Out of scope.** Interleave or alternate merge, and merge bookmarks.

**Risk / rollback.** Low after PM-2.1.

---

## PM-2.6 Extract pages v2

**User-visible goal.** Make a new document from chosen pages or a range ("1-3, 7").

**Dependencies.** PM-2.5. Also PM-2.2, for the entry from Organize.

**Study.**
- `ScanRepository.kt:1040–1060`
- `PageSelectTest`
- E5

**Change, in product terms.**
- `PageRanges` pure parser: 1-based, inclusive; spaces allowed; clear errors such as "Page 14 doesn't exist (12 pages)".
- Extract setup: range field (numeric keyboard plus `-` and `,`) **or** "Choose pages" (a page-chip grid). Output summary "Creates 1 document · 5 pages". Name default `‹name› (pages 3–7)` (en dash, or "pages 1, 3, 7" when not contiguous).
- Entry points: PDF Tools tile (picker → setup) and Organize Select → Extract (preselected pages).
- The Split mode switch is not rendered until PM-4.3.

**State model.** Setup options.

**Edge cases.**
- Reversed ranges ("7-3") are normalized, with a note.
- Duplicates are deduped.
- All pages selected is allowed (equivalent to Duplicate, but named).
- Locked source: same as PM-2.4.

**Test plan.**
- Parser unit tests: 20 cases, including Hindi digits rejected or normalized (Devanagari digits ०-९ map to 0-9).
- Output page digests.
- I5.

**Acceptance criteria (device).**
1. A 12-page document, Extract "2-4, 9". The Result shows "4 pages", the new document's page 4 is the source's page 9, and the source still has 12 pages.

**Out of scope.** Split (PM-4.3).

**Risk / rollback.** Low.

---

## PM-2.7 Import keeps the original PDF

**User-visible goal.** Importing a PDF never makes it worse. Share sends the original file until you make an edited copy.

**Dependencies.** PM-2.1.

**Study.**
- `util/PdfImport.kt:105–177` (the temp copy is deleted at `:175`)
- `ScanRepository.kt:1391–1441`
- `ShareUtils.share`
- `DocumentViewerActivity.savePdfToDocuments`
- `ui/ImportSheet.kt`
- E3

**Change, in product terms.**

1. During import, place the seekable copy into staging as `original.pdf` instead of deleting it. Record its name, bytes and SHA-256 in provenance.
2. **Share file** = `original.pdf` when `hasOriginalPdf`, else `scan.pdf`. This applies to:
   - Share (reader, Home row, Result);
   - Save to Documents;
   - the row and Info size.
3. **Info:** "Original PDF kept · 240 KB · selectable text stays in the original."
4. **Protect on an imported document:** the setup states "Protecting makes the shared PDF image-based." On Protect, remove `original.pdf` (after a successful encrypt), then set `origin` to stay `import` and `hasOriginalPdf=false`.
5. **Import confirmation copy** (E3, English and Hindi). The Imported chip in the picker becomes live.
6. **Duplicate** keeps `original.pdf`. Every other tool output never carries it.

**State model.** `hasOriginalPdf`: true at import → false after Protect or Replace. Derived outputs are always false.

**Edge cases.**
- An original that fails its hash check at load: treat it as absent and log. Never share a mismatching original.
- Storage: the import space check adds the source size.
- Locked flow "lock a device PDF" (`LockFlow.kt:134–201`): imports then protects, so no original survives. That matches the existing copy.

**Test plan.**
- Import fixture: `original.pdf` is byte-equal to the source; share file = original; size label = original length.
- Compress output: no original.
- Protect: original removed, I3 holds.
- I1: original page count = pages.

**Acceptance criteria (device).**
1. Import a small text PDF (for example, a bank statement export).
2. Home shows the original's size.
3. Share it to Drive or Files. The shared file's text is selectable there.
4. Compress it. The new copy is image-based, and the original still shares as the original.

**Out of scope.** Vector-preserving edits, and text extraction from the original.

**Risk / rollback.** Storage growth. The owner may veto (OQ-6); rollback = stop keeping the file, with no format change for existing documents.

---

## PM-2.8 Insert pages into a draft

**User-visible goal.** Add photos or pages from another document into Organize, at a chosen position.

**Dependencies.** PM-2.2, PM-1.2.

**Study.**
- `ui/ImportSheet.kt`
- `ImageTools.normalizePage`, `readPageBytes`
- `DraftStore`

**Change, in product terms.**
- Organize → Insert → Photos (system photo picker) or Another document (picker single → page chips).
- Inserted pages become `r_<n>.jpg` entries **after the last selected page, or at the end**.
- The change count increases by the number of pages inserted.

**State model.** Draft entries.

**Edge cases.**
- An unsupported image is reported with its position; the others are inserted.
- A total over 500 pages is refused.
- Inserting from a locked document requires its password (after PM-3.1; before that, it is refused).

**Test plan.**
- Order after insert.
- The base document is untouched (I7).
- Save → I1.

**Acceptance criteria (device).**
1. Insert 2 photos after page 1 of a 3-page draft. The header says 5 pages.
2. Discard → the original still has 3 pages.

**Out of scope.** Camera insert. That needs `ScanLauncher` integration; next iteration.

**Risk / rollback.** Low.

---

# Slice 3: Secure

## PM-3.1 Password v2 and locked inputs everywhere

**User-visible goal.** Protect, unlock and change a password on one clear screen. See exactly what protection means. Use locked documents in tools without stripping their protection.

**Dependencies.** PM-0.3, PM-2.5, PM-2.2.

**Study.**
- `tools/LockFlow.kt`
- `ScanRepository.kt:1495–1594`
- `MainActivity.kt:1764–1797`
- `SearchMatcher` locked rules
- E13, E14
- `10_PRODUCT_SPEC.md` §3.5 Password row

**Change, in product terms.**

1. **Password setup** (`ToolSetupActivity` kind Password):
   - Protect: new password, confirm, show/hide; 4+ characters; no maximum.
   - Unlock: current password.
   - Change: current, new, confirm.
   - The honest note (E14) and the search-consequence line.
   - The imported-original line (PM-2.7).
   - In-place exception: the destination sentence is "Changes protection of “‹name›”. Pages are not changed."
2. **Locked inputs** across tools (E13):
   - The picker-verified password is passed to the runner.
   - Single-source outputs are encrypted with the same password and say so on the Result.
   - Merge with a locked source adds a required "Password for the merged PDF" field.
   - Export as images and Word from locked documents get a confirm step (wired in PM-4.2 and PM-6.2).
3. **Organize on a locked document:**
   - The password is asked once when opening.
   - Save as new copy is encrypted with the same password.
   - Replace builds and encrypts, and records the lock index (`recordLockIndex`).
   - This removes the PM-0.3 refusal for Organize. The in-place repository guard remains.
4. **Badges:** the lock glyph on the Home row (exists), the picker, the reader title, and Info.
5. **Protect / Unlock / Change** take effect in place (P11).
6. **Remove `LockFlow.chooseLockSource`'s three-way sheet.** The Password tile goes to picker → setup.
   - "Lock a device PDF" becomes Import PDF → Protect.
   - "Unlock a device PDF" keeps the hash-recognition path (`LockFlow.kt:208–296`), reached from the picker header "Unlock a ScanWhirl PDF file…".

**State model.** Protection: none ↔ locked(RC4|AES) ↔ needs-repair. Passwords live in memory per operation.

**Edge cases.**
- Wrong password three times: a 30-second cooldown.
- Non-Latin-1 password characters: `PdfEncryptor` pads ISO-8859-1 bytes (`:37`). Restrict to printable Latin-1 with the message "Use letters, numbers and common symbols", or verify in PM-3.4.
- Change password on a document with exports: existing exported copies keep the old password. Say so.

**Test plan.**
- I3 after every operation.
- A locked source compress gives an encrypted output with the same password, verified by the decrypt helper in `PdfEncryptorTest`.
- Merge with a locked source and no result password is blocked.
- Organize locked, then Save: the output is encrypted and the base is unchanged.
- Unlock or change without the right password changes nothing.

**Acceptance criteria (device).**
1. Protect DocX with `abcd`. The row shows 🔒. Share to a PDF app: it asks for the password and `abcd` opens it.
2. Compress the locked DocX (enter `abcd` in the picker). The Result says "Locked with the same password as DocX"; that file needs `abcd` in another app.
3. Organize the locked DocX, rotate a page, Save as new copy. The new copy is locked; DocX is unchanged and still locked.
4. Search for a word that only appears in DocX's text: no result; searching its name finds it.

**Out of scope.** AES (PM-3.4) and opening foreign password-protected PDFs.

**Risk / rollback.** Touches every tool's input path. Each tool's locked handling is a small isolated branch.

---

## PM-3.2 Streaming encryption with verification

**User-visible goal.** Protecting a 300-page document works and does not crash.

**Dependencies.** PM-3.1.

**Study.**
- `util/PdfEncryptor.kt` (`:33–55`, `:187–270`)
- `PdfEncryptorTest`
- `PdfVerifier`

**Change, in product terms.**
- Encrypt object by object with `RandomAccessFile`: copy unchanged bytes, RC4 each stream body in chunks, then write the patched trailer. Output is byte-identical to today's for the same file ID; assert that in a test with a fixed ID seam.
- After encrypting, verify that the header, `startxref`/`%%EOF`, `/Count` and `/Encrypt` are present (a bounded read).
- Memory is bounded to 64 KB buffers.

**State model.** None.

**Edge cases.** A disk-full during encrypt: temp removed, `scan.pdf` untouched.

**Test plan.**
- Equivalence with the old encryptor on fixtures.
- 300-page Robolectric run with no OOM.
- Decrypt round trip (existing tests).

**Acceptance criteria (device).**
1. Protect a 100+ page document in under 10 s, with no crash.
2. The file opens in another app with the password.

**Out of scope.** Algorithm change.

**Risk / rollback.** Equivalence test guards it.

---

## PM-3.3 Searchable state and "Make searchable"

**User-visible goal.** Know whether the PDF you share is searchable in other apps, and fix it when it is not.

**Dependencies.** PM-1.3.

**Study.**
- `ScanRepository.kt:1650–1700` (`text_layer.flag`, `Prefs.makeSearchable`)
- `OcrIndexer`
- `PdfTextLayer` (Latin-1 limit)
- `OcrStatusTest`

**Change, in product terms.**
- Info gains "Searchable in other apps: Yes / Partly (8 of 12 pages) / No", with a one-line reason: "Text not read yet", "Searchable PDFs are off in Settings", or "Locked: shown after the password".
- An action **Make searchable** runs the indexer now, then regenerates the PDF. For locked documents, it is disabled with an explanation.
- When `original.pdf` is the share file: "Uses your original PDF's own text."
- Add the Latin-script limitation line.

**State model.** Derived from `text_layer.flag`, geometry coverage and the setting.

**Edge cases.**
- Recognition unavailable (model not downloaded): the existing M06 explanation and Retry.

**Test plan.**
- The state for each combination.
- The action marks the document pending and regenerates the PDF.

**Acceptance criteria (device).**
1. A new scan before indexing: Info says "No · Text not read yet". Tap Make searchable. It then says "Yes", and the shared PDF's text is searchable in Drive's viewer.

**Out of scope.** Per-export toggles and Hindi.

**Risk / rollback.** Low.

---

## PM-3.4 AES-256 spike (gated; the default outcome is "keep RC4")

**User-visible goal.** If compatible, stronger protection under the same Free feature.

**Dependencies.** PM-3.2.

**Study.**
- ISO 32000-2 Standard Security R6 (algorithms 2.A, 2.B, 8, 9, 10)
- `PdfGenerator.kt`

**Change, in product terms.**
- **Spike only.** Generate AES-256 (V5/R6, AESV3 crypt filter) PDFs directly from page images in a test-only path, using JDK `Cipher` and `MessageDigest`. Produce 3 sample files.
- The owner opens them in Chrome, Google Drive's viewer, Adobe Acrobat Reader (Android), the Android Files viewer or `PdfRenderer`, and Xodo or WPS.
- **Adopt only if all open with the password and refuse without it.** Adoption is a follow-up card that switches Protect and updates the claim text. Without adoption, nothing ships.

**State model.** None.

**Edge cases.** `PdfRenderer` on API 24–34 with AES-256.

**Test plan.** Unit-level structure tests and a manual compatibility matrix in `PDF_TOOLS_STATUS.md`.

**Acceptance criteria.** A compatibility table filled in by the owner, plus a go/no-go note.

**Out of scope.** Shipping AES without a "go".

**Risk / rollback.** Spike code stays in test sources.

---

# Slice 4: Export as images and Power Pack gating

## PM-4.1 Power Pack gate and visibility

**User-visible goal.** No dead ends. Owners see their tools; everyone else sees a complete Free product.

**Dependencies.** PM-1.4.

**Study.**
- `util/ProGate.kt`
- `ProGateTest`
- `30_FREE_PRO.md` §4

**Change, in product terms.**
- `PowerPack.state()` returns OWNED / FOR_SALE / HIDDEN.
  - FOR_SALE is unreachable until PM-5.5 ships the storefront; until then it returns HIDDEN.
- PDF Tools, the Document tools sheet and setup modes render Power Pack items only in OWNED.
- A Power Pack setup started by intent in HIDDEN finishes immediately and writes nothing.
- Group headings hide when empty.

**State model.** As above.

**Edge cases.**
- The test-build override (1 = Free, 2 = Pro) maps to HIDDEN or OWNED.
- Production with `pro_unlocked` true is OWNED. Production never reads the override (existing rule).

**Test plan.**
- The 3-state matrix across three surfaces, asserting exact tile sets.
- No "Power Pack/Pro/Not available" text in HIDDEN.
- The intent guard.

**Acceptance criteria (device).**
1. Test build, default: Finish and Redact visible.
2. Override Free: Finish group absent, Secure shows only Password, and no Power Pack words anywhere.

**Out of scope.** The storefront, purchase and restore (PM-5.5).

**Risk / rollback.** Low.

---

## PM-4.2 Export as images (Free)

**User-visible goal.** Send pages as images: shared directly, saved to Pictures, or as one ZIP.

**Dependencies.** PM-2.5, PM-3.1.

**Study.**
- `DownloadSaver.saveJpeg(…, subdir)`
- `ShareUtils.sharePages` (`:55–70`)
- `ZipExporter`
- E4

**Change, in product terms.**
- Setup:
  - Pages: All or a range (`PageRanges`).
  - Deliver as: Share / Save to Pictures / One ZIP.
  - Shows "JPEG · 1240 × 1754 px (page 1)" and "Pages are already JPEG; exporting doesn't lower quality."
- Locked source: confirm "Images can't be password-protected…".
- File names: `‹name› - page 01.jpg`.
- Result: in file mode, it lists the destination folder.

**State model.** Setup options.

**Edge cases.**
- 300 pages to Pictures: progress and cancel; partially saved images are removed on cancel (each save is recorded).
- A ZIP over 100 MB: warn before building.

**Test plan.**
- Bytes of the exported images equal the page files.
- Naming and padding.
- Cancel cleanup.
- Locked confirm.

**Acceptance criteria (device).**
1. Export pages 1–2 of a document to Pictures. The Gallery shows `‹name› - page 01.jpg` and `- page 02.jpg` under `Pictures/ScanWhirl/‹name›/`.

**Out of scope.** PNG, re-render at higher DPI, and long image (PM-4.4).

**Risk / rollback.** Low.

---

## PM-4.3 Split into files (Power Pack)

**User-visible goal.** Break one document into several, by every N pages or by ranges.

**Dependencies.** PM-2.6, PM-4.1.

**Change, in product terms.**
- The Extract & split setup shows a mode switch only in OWNED: "Extract one document" / "Split into files".
- Split: every [N] pages, or ranges separated by `;` (for example `1-3; 4-9; 10-12`).
- Preview: "Creates 3 documents: 3, 6 and 3 pages."
- Names: `‹name› (part 1 of 3)`.
- The runner produces N outputs atomically per output. On failure after k outputs, the k outputs stay, and the Result lists them plus the error ("2 of 3 created").
- Undo removes all outputs.

**State model.** Multi-output operation.

**Edge cases.**
- N ≥ the page count: one output, with a note.
- Over 50 outputs: blocked ("Split into at most 50 documents").

**Test plan.**
- Page digests per output.
- Partial failure behaviour.
- Undo all.
- HIDDEN: no Split mode.

**Acceptance criteria (device).**
1. Split a 12-page document every 5 pages. The Result shows 3 documents with 5, 5 and 2 pages; the source still has 12.

**Out of scope.** Split by bookmarks or file size.

**Risk / rollback.** Low.

---

## PM-4.4 Long image (Free, owner reversed O-01)

**User-visible goal.** Send a document as one tall image, or a few, that stays readable in chat apps and never crashes.

**Dependencies.** PM-4.2.

**Study.**
- `00_DECISIONS.md` E20 (the four old failure modes)
- `git show c230017` (the removal commit) and `git show c230017^:app/src/main/java/com/scanwhirl/app/util/LongImage.kt` (the old stitcher; **do not restore it**)
- `ui/LongImageRemovalTest.kt`
- `ImageTools.decodeSampled`

**Change, in product terms.**

1. **New stitcher** under the E20 rules:
   - fixed 1080 px width, aspect ratio kept per page;
   - one page decoded at a time and recycled;
   - an `RGB_565` canvas;
   - at most 16,000 px tall per image;
   - a 12 px grey gap between pages;
   - JPEG quality 88.
2. **Splitting.** When the pages need more than 16,000 px, produce several images named `‹name› - long 1 of 3.jpg`. Never squash.
3. **Export as images setup** gains a delivery option "One long image". Its preview line reads, for example, "Makes 2 images · 1080 px wide".
4. **Locked sources** get the E13 confirm step.
5. **Rewrite `LongImageRemovalTest` as `LongImageTest`.** It must assert:
   - the canvas never exceeds 1080 × 16,000;
   - the split count for 1, 10 and 40 pages;
   - aspect ratio within ±1 px for a landscape page among portrait pages;
   - no `OutOfMemoryError` on 100 synthetic pages;
   - the source pages are untouched.

**State model.** Setup option only.

**Edge cases.**
- One very tall page whose height exceeds 16,000 px at 1080 px width: it gets its own image, scaled to fit, with a note.
- A cancel mid-stitch deletes any partial outputs.

**Test plan.** As in step 5, plus I5.

**Acceptance criteria (device).**
1. On a 12-page document, Export as images → One long image → Share to a chat app. Two images arrive, and every page is readable at full screen width.
2. On a 40-page document, no crash. "long 1 of N" files appear.

**Out of scope.** Custom widths and PNG.

**Risk / rollback.** Isolated. Rollback = hide the option and keep the test.

---

# Slice 5: Sign, watermark and redact (Power Pack)

## PM-5.1 Page stamp engine and Stamp editor shell

**User-visible goal.** Place items on pages precisely and preview the exact result before creating a copy.

**Dependencies.** PM-4.1, PM-2.1.

**Study.**
- `ImageTools` (`decodeSampled`, `encodeJpeg`)
- `ui/ZoomableImageView.kt`, `ui/CropView.kt` (touch handling)
- `PageReplace` (staging style)

**Change, in product terms.**
- `PageStamp` (util) bakes a list of items onto a page bitmap: an image with alpha, text with font/size/colour/angle/opacity, or a solid rectangle. It works in page-pixel coordinates, decodes at full resolution up to 4096 px on the long side, and encodes at quality 92.
- `StampEditorActivity`:
  - a page pager;
  - an item overlay with drag, pinch-scale and rotate handles (48dp);
  - "+ item" per tool;
  - undo last;
  - Next → setup summary → runner (each stamped page is a replacement page; other pages are copied unchanged).
- A shared rule: **stamped pages never carry `.orig`**, and their OCR is re-queued.

**State model.** Editor items per page, held in a `ViewModel`. Saved to the instance state as normalized coordinates.

**Edge cases.**
- Rotated pages (EXIF already normalized at save).
- A very large page: sampled preview, with the full-resolution bake in the runner.
- Out of memory during the bake: fail that operation; nothing published.

**Test plan.**
- Bake determinism: same items, same pixels within tolerance.
- Coordinate mapping between preview and full resolution.
- `.orig` absent; OCR pending.

**Acceptance criteria (device).** Covered by PM-5.2 to PM-5.4. This card alone shows the editor with a test rectangle in a debug test build only. It is not reachable in the UI until a tool uses it.

**Out of scope.** Free-hand drawing.

**Risk / rollback.** Isolated new code.

---

## PM-5.2 Sign & self-attest

**User-visible goal.** Sign a document with your saved signature and an optional "Self-attested" stamp and date, then share a flattened copy.

**Dependencies.** PM-5.1.

**Change, in product terms.**
- **Signature pad:** full-screen, draw with the finger, Clear / Save. Saved as a trimmed transparent PNG in `filesDir/signatures/` (max 3, deletable). It is never exported except as part of a stamped page.
- **Editor items:**
  - Signature (pick saved / draw new);
  - Text presets: "Self-attested", "‹name›" (remembered locally), "Date: 13/11/2026" (locale format), and custom text.
- Default placement: lower right of the current page.
- **Setup summary:** "Adds 2 items to 1 page. Creates “‹name› (signed)”." The disclaimer: "A picture of your signature, not a certified digital signature."

**Edge cases.**
- No saved signature: open the pad first.
- Signing a locked document follows PM-3.1 (the output is locked).

**Test plan.**
- Signature PNG alpha is preserved in the bake.
- Item positions map correctly.
- The source is untouched (I5).

**Acceptance criteria (device).**
1. Sign page 1 of a 2-page document, adding a signature and "Self-attested" with the date. Result: "‹name› (signed)", 2 pages. Page 1 shows the signature exactly where placed, and page 2 is byte-identical to the source page 2.

**Out of scope.** Digital certificates and initials on every page (maybe later as "apply to all pages").

**Risk / rollback.** Low.

---

## PM-5.3 Purpose watermark

**User-visible goal.** Mark every page "For ‹purpose› only · ‹date›" so a copy can't be reused elsewhere.

**Dependencies.** PM-5.1.

**Change, in product terms.**
- Setup only (no placement):
  - text (template "For ‹purpose› only · ‹date›");
  - Diagonal / Across bottom;
  - opacity 15–60%;
  - size S/M/L;
  - live preview of page 1;
  - pages All / range.
- The default output name is `‹name› (watermarked)`.

**Edge cases.**
- Long text wraps or shrinks to fit the page width.
- Devanagari text: render with the system font (bitmap text is fine; no PDF font issue). This is a plus to mention.

**Test plan.**
- Every selected page changed, others byte-identical.
- Text inside page bounds.

**Acceptance criteria (device).**
1. Watermark "For HDFC KYC only" diagonally at 30%. Every page shows it, and the original has none.

**Out of scope.** Image watermarks and tiled patterns.

**Risk / rollback.** Low.

---

## PM-5.4 Verified redaction

**User-visible goal.** Black out numbers and text so that the shared copy truly does not contain them.

**Dependencies.** PM-5.1, PM-3.3.

**Study.**
- `OcrStore` geometry (`readGeo`)
- `model/OcrPage.kt`
- `PdfTextLayer`
- E10, I9

**Change, in product terms.**

1. **Editor:**
   - + Box (drag/resize).
   - **Suggest:** scan the page's OCR lines for 12-digit groups (`\d{4}\s?\d{4}\s?\d{4}`), PAN (`[A-Z]{5}\d{4}[A-Z]`), 10-digit mobiles, and digit runs of 9 or more. Propose a box over each matching line's rectangle, dashed until accepted.
   - Suggest is disabled with the reason "Text not read yet" when there is no geometry.
2. **Bake:** solid black rectangles, expanded by 2% of the page width for JPEG bleed.
3. **Output rules (I9):**
   - no `.orig` for redacted pages;
   - OCR text and geometry for redacted pages are **not** carried over, and the page is re-queued;
   - no `original.pdf`;
   - provenance records box rectangles (no text).
4. **Verification before publish:** decode each redacted output page and check that every pixel inside each box has luminance ≤ 24. On failure, abort with "Redaction couldn't be verified; nothing was created."
5. **Result:** "Redacted copy created and verified. “‹name›” still contains the original content." with an action **Move original to Trash**.

**State model.** Boxes per page. Suggestions are pending until accepted.

**Edge cases.**
- Boxes outside the page are clipped.
- A locked source: the output is locked (PM-3.1).
- A page with no boxes is copied unchanged.

**Test plan.**
- I9 assertions.
- After re-indexing, the redacted document's search does not find the redacted digits. Use a fake recognizer seam that returns text only from unboxed regions; the real check is on device.
- The verification failure path (inject a weak fill) creates nothing.
- Suggestion regex unit tests: Aadhaar with and without spaces, PAN, phone, and non-matches such as dates.

**Acceptance criteria (device).** Use a fixture document with a fake 12-digit number.

1. Suggest → it proposes a box over the number line. Accept → Next → Create.
2. Open the result in another PDF app and search for the digits: not found. Zoom: the area is solid black.
3. Search ScanWhirl for the digits: only the original document matches, not the redacted copy.
4. The Result offers Move original to Trash.

**Out of scope.** Word-level boxes (ML Kit elements are not stored), handwriting detection, and auto-apply without review.

**Risk / rollback.** A privacy-critical feature. Do not ship if any I9 check is skipped.

---

## PM-5.5 Power Pack storefront (Play Billing; owner-approved)

**User-visible goal.** A Free user taps a Power Pack tile, sees exactly what ₹100 buys, pays once with Google Play, and the tool opens. Restore works on a new phone.

**Dependencies.**
- PM-4.1 and PM-5.2–PM-5.4 shipped.
- Owner approval OQ-1: given.

**Study.**
- `30_FREE_PRO.md` §3–§5
- `util/ProGate.kt` (`setPro`, `resolveProState`)
- `ProGateTest`
- the current Play Billing Library integration guide: one-time products, `queryPurchasesAsync`, acknowledgement

**Change, in product terms.**

1. **Add `com.android.billingclient:billing-ktx`.** This is the **only** dependency change the owner approved. Record the version and size delta in `PDF_TOOLS_STATUS.md`.
2. **After adding it,** re-run the merged-manifest check. `INTERNET` must still be absent (PM-1.5). Billing talks to the Play Store app over IPC.
3. **Product:** one non-consumable, id `power_pack`, ₹100. It is created by the owner in Play Console.
4. **Entitlement:**
   - At startup and on resume, query purchases. A purchased, acknowledged `power_pack` sets OWNED through `ProGate.setPro`.
   - Acknowledge new purchases.
   - A pending purchase shows "Payment pending" and does not grant the pack.
   - Production must never grant from the test override (existing rule).
5. **The Upgrade sheet** follows `30_FREE_PRO.md` §5. The price is Play's localized string.
6. **`PowerPack.state` becomes FOR_SALE** when billing connects and the user does not own the pack. It stays HIDDEN when billing is unavailable.
7. **Settings** gains "Restore purchase".

**State model.** Entitlement: unknown → owned | not-owned | pending. It is cached locally, so offline launches keep OWNED.

**Edge cases.**
- Offline at launch: use the cached entitlement and never revoke offline.
- A refund or revocation, seen on the next online query: the state becomes not-owned. Outputs already created are kept.
- Family Library sharing (OQ-8).
- No Play Store installed: HIDDEN.

**Test plan.**
- A fake `BillingClient` seam covering: owned, not-owned, pending, unavailable, a refund, an offline cached state.
- All three `PowerPack.state` surfaces re-asserted.

**Acceptance criteria (device, internal test track with a licence tester).**
1. A Free user taps Redact. The sheet shows ₹100. Buy with a test card. Redact opens.
2. Clear the app's cache, not its data, then relaunch offline. The pack is still owned.
3. Install on a second device with the same account → Restore purchase. The pack is owned.

**Out of scope.** Subscriptions, promo codes UI, server verification, analytics.

**Risk / rollback.** It is a new dependency. Rollback = remove the dependency, and the state returns to HIDDEN everywhere.

---

# Slice 6: Text and Word (only after feasibility)

## PM-6.1 DOCX feasibility spike

**User-visible goal.** None yet. Prove that a text-only Word file opens cleanly.

**Dependencies.** PM-3.3.

**Change, in product terms.**
- A test-only writer produces a minimal valid `.docx` (`[Content_Types].xml`, `_rels/.rels`, `word/document.xml`) from OCR blocks: a paragraph per block, a page break between pages, and UTF-8 text including ₹, curly quotes and one Devanagari line (typed text).
- Three samples. The owner opens them in Microsoft Word (Android), Google Docs (import), WPS, and LibreOffice (desktop).

**Acceptance criteria.** All four open without a repair prompt and show identical paragraphs. Results go in `PDF_TOOLS_STATUS.md`. Go or no-go.

**Out of scope.** Tables, images, styles.

**Risk / rollback.** Test-only code.

---

## PM-6.2 To Word (text only), Power Pack

**User-visible goal.** Get a document's recognised text as an editable Word file.

**Dependencies.** A PM-6.1 "go"; PM-4.1.

**Change, in product terms.**
- The Convert tile "To Word" (OWNED only) → picker → setup:
  - pages;
  - fidelity sentence (E6);
  - "Text not ready on 3 pages: Read now" when needed.
- Output: Save to Documents as `‹name›.docx` (via the existing `DownloadSaver` transaction pattern with the docx MIME type), or Share.
- Locked sources get the confirm step.

**Edge cases.**
- Pages with no text produce an empty paragraph plus the note "[No text found on page 4]".
- Recognition unavailable: the M06 message.

**Test plan.**
- ZIP structure.
- XML escaping.
- Page-break count = pages − 1.
- Export Undo.

**Acceptance criteria (device).**
1. A 3-page scan → To Word → open in Word or Docs. The text matches the OCR editor; there are 2 page breaks.

**Out of scope.** Layout, Excel, PPT.

**Risk / rollback.** Low.

---

## PM-6.3 Hindi text recognition (spike, then a Free opt-in)

**User-visible goal.** Hindi words on scanned documents become searchable and copyable inside ScanWhirl.

**Dependencies.** PM-3.3, PM-1.5.

**Study.**
- `util/OcrEngine.kt`
- `store/OcrIndexer.kt`
- `util/SearchMatcher.kt` normalization (Unicode, Devanagari matras)
- `PdfTextLayer` (Latin-1 only)
- `00_DECISIONS.md` E22
- ML Kit text recognition v2: the Devanagari unbundled artifact

**Change, in product terms.**

**Phase 1: spike, no UI.**
- Add `play-services-mlkit-text-recognition-devanagari`. It is architect-approved under the owner's delegation; record the APK delta.
- Run both recognizers on 15 owner-provided printed samples: Aadhaar front, a Hindi newspaper clip, a government form, a school notice, mixed Hindi/English pages. Put them on the phone through the Photos picker.
- Measure:
  - character accuracy against a hand-typed reference;
  - time per page;
  - whether the Devanagari recognizer also reads the English lines.
- Record the results in `PDF_TOOLS_STATUS.md`.
- **Go only if:** ≥ 90% character accuracy on Hindi, ≤ 2× time, and no English loss on mixed pages (or a merge strategy that achieves this).

**Phase 2, only on "go":**
- Settings toggle "Also read Hindi text" (off by default) with the note "Hindi text is searchable in ScanWhirl. PDFs you share stay searchable in English only."
- The indexer uses the chosen strategy.
- `SearchMatcher` is verified for Devanagari: normalization and word boundaries. Add tests with Hindi queries.
- Add the install-time model declaration for Devanagari (PM-1.5 pattern).

**State model.** A setting. Pages read before the toggle was turned on are re-queued only when the user taps "Re-read all documents" (with a page-count estimate).

**Edge cases.**
- Mixed-script lines.
- Matra and halant normalization in search.
- A 500-page re-read must be cancellable.

**Test plan.**
- `SearchMatcherTest` gains Hindi cases.
- Indexer tests use a fake recognizer returning Devanagari.
- `StringsParityTest` passes.

**Acceptance criteria (device).**
1. With the setting on, scan a Hindi notice. Search for a Hindi word from it: the document is found, with a snippet.
2. Copy the text from the OCR editor: it is Hindi and correct.

**Out of scope.** A Devanagari PDF text layer (Slice 7) and handwriting.

**Risk / rollback.** If the spike fails, remove the dependency. Nothing ships.

---

# Slice 7: Approval-gated (no cards; do not start)

Each item needs an explicit owner decision recorded in `50_OPEN_QUESTIONS.md` first.

| Item | Prerequisite |
|---|---|
| PDF/A-1b | Embedded font for the text layer (or drop it), an ICC OutputIntent, XMP, veraPDF validation in CI; no encryption |
| Office layout conversion, Excel, PPT | A licensed or open engine and a fidelity contract |
| Devanagari PDF text layer | PM-6.3 shipped; embedded font + ToUnicode in `PdfTextLayer` |
| A PDF engine for lossless operations on `original.pdf` (E18) | APK size, licence and offline review |
| Opening foreign password-protected PDFs | Investigation INV-11 |
| Batch apply-to-many, naming templates, folders/tags | A library roadmap, not PDF Tools |
