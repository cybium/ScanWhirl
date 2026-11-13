# 20 — Architecture plan

No code is in this document. File paths are under `app/src/main/java/com/scanwhirl/app/` unless they start with `app/`. Line numbers refer to tree `0c283b8`.

---

## 1. Verified current state

### 1.1 Which PDF operations exist, and are they safe?

| Operation | Where | Safe? | Why |
|---|---|---|---|
| New-scan save | `store/ScanRepository.kt:422–582`, `store/SaveTransaction.kt` | **Yes** | Marker, then pages, PDF, `txn.txt` and `meta.txt`, then verify, then publish by unlinking the marker. Replay-safe, with fault seams. The model to copy. |
| Import PDF | `ScanRepository.kt:1391–1441`, `util/PdfImport.kt` | **Yes** | Read-only source, size and page caps, classified errors, private staging, one atomic rename. |
| Extract pages | `ScanRepository.kt:1040–1060` | Partly | Copies page files without re-encoding. Writes straight into a visible `documents/<id>`. `finalizeDoc` swallows PDF failure (`:1316–1320`) and queues OCR only when it fails (`:1321`). |
| Merge library docs | `ScanRepository.kt:1062–1103` | **No** | `meta.txt` is written before the PDF (`:1093`). PDF failure is swallowed (`:1096–1100`), so a document can be published without a PDF. Not queued for OCR. Picker shows names only (`tools/MergeFlow.kt:55`). |
| Merge device PDFs | `ScanRepository.kt:1110–1128` | Partly | Renders straight into a visible directory and deletes it on failure. A crash leaves a partial document. |
| Compress | `ScanRepository.kt:1349–1359`, `tools/CompressFlow.kt` | Partly | Output is a new document (good). Every page is held in memory (`:1355`; `CompressFlow.kt:294–300` despite its own comment). Not transactional. Exact target silently overrides the chosen preset (`CompressFlow.kt:165–174`). |
| Duplicate | `ScanRepository.kt:1002–1032` | Partly | Copies every file into a visible directory, then writes meta. Not transactional. |
| Page delete / rotate / crop / move / filter | `ScanRepository.kt:1151–1297` | Per page yes; per document **no** | Each page swap is staged and verified (`PageReplace`). But every operation edits the **library document in place** and regenerates `scan.pdf`. `movePage` does an unchecked two-pass rename (`:1235–1246`). |
| Organize session | `ui/DocumentViewerActivity.kt` | **No** | This is the D1/D2 failure: see 1.3. |
| Protect | `ScanRepository.kt:1495–1518` | Partly | Overwrites `scan.pdf` in place and stores an unsalted password hash. Reads the whole PDF into memory (`util/PdfEncryptor.kt:35`). |
| Unlock | `ScanRepository.kt:1524–1532` | **No (UI)** | The repository takes no password. `tools/LockFlow.kt:46–58` and `DocumentViewerActivity.kt:659–681` call it without asking for one. Change password never asks for the old one (`LockFlow.kt:60`). |
| Edit a locked document | `ScanRepository.kt:1651–1665` | **No** | `regeneratePdf` never checks `locked.meta`. Any page edit rewrites `scan.pdf` **unencrypted** while the badge still says Locked. Code-read finding; PM-0.2 makes it a test. |
| Share | `util/ShareUtils.kt:17–26` | Yes | Shares `scan.pdf` under the document's name (encrypted when locked). |
| Save to Documents | `util/DownloadSaver.kt`, `store/ExportProvenance.kt` | **Yes** | Export transaction, fingerprints, HMAC-signed provenance, Undo. |
| Trash | `ScanRepository.kt:816–975` | **Yes** | Whole-directory rename, crash recovery, no auto-purge. |

### 1.2 Page-image versus true-PDF work

**Everything ScanWhirl does is page-image work.**

- `util/PdfGenerator.kt:68–182` writes a PDF 1.4 containing only full-page DCT (JPEG) images at an assumed 150 dpi, plus an optional invisible Helvetica/WinAnsi text layer (`util/PdfTextLayer.kt:27–28`).
- Android's `PdfRenderer` (`util/PdfPageRenderer.kt`) reads foreign PDFs only as pixels.
- `PdfEncryptor` parses only ScanWhirl's own xref layout (`:187–248`).
- **Nothing** in the app can parse or modify a foreign PDF's objects.

| Requires a true PDF engine (not in app) | Possible on the page-image engine |
|---|---|
| Lossless merge/extract/rotate of foreign PDFs | Merge, extract/split, reorder, rotate, delete, insert |
| Selectable text from vector PDFs without OCR | Compress, export as images |
| Editing existing text | Sign, watermark, redact (flattened) |
| Form filling | Protect (own PDFs) |
| PDF/A | Text-only Word |
| AES on foreign files | TXT |
| Annotations | AES on own PDFs (spike PM-3.4) |

### 1.3 Current save, overwrite and discard model

| Path | Behaviour |
|---|---|
| New scans | Transactional, with no overwrite concept. |
| Tool outputs (merge, extract, compress, duplicate) | A new document, but **not transactional** (1.1). |
| Page edits in `DocumentViewerActivity` / `PageViewerActivity` | **Destructive and immediate.** |

**What goes wrong with page edits:**

1. The edit is written to `documents/<id>` and `scan.pdf` is regenerated at once (`ScanRepository.kt:1151–1297`).
2. A pre-edit snapshot is copied **asynchronously after the first load** (`DocumentViewerActivity.kt:171–176`). A rotate committed before the copy finishes is captured as "pre-edit".
3. The snapshot is deleted in `onDestroy` (`:194–204`). A force-stop skips `onDestroy`, so the edits stay and nothing restores them. That is D1.
4. The Back dialog (`:796–808`) offers:
   - "Overwrite saved PDF", which is `finish()`: the overwrite already happened.
   - "Save as new document", which duplicates the edited state, then restores the snapshot by **deleting every file and then copying** (`:860–869`). A crash in between destroys the original.
   - "Cancel", which stays on the screen.
   - There is no Discard.
5. The message says "Your edits are not in the original PDF yet" (`res/values/strings.xml:119`). That is false.

**Effect on the PDF Manager.** No tool may reuse this model. Organize moves to a draft (E12). Every tool output goes through one publish path (E2).

### 1.4 Current PDF import

- `PdfImport.withSeekableCopy` copies the source read-only into `cache/pdf_fd` with a 256 MB cap and **deletes the copy afterwards** (`util/PdfImport.kt:105–177`).
- `renderAll` rasterizes **every page** with `RENDER_MODE_FOR_DISPLAY` at 150 dpi, max 2048 px, JPEG quality 85, on a white background (`:184–257`). The maximum is 500 pages (`:38`).
- A foreign password-protected PDF fails with `PASSWORD` (`:198`), because `PdfRenderer` cannot open it on API < 35.

**Lost on import:**
- selectable/vector text
- links and bookmarks
- form fields and annotations
- embedded fonts
- metadata
- attachments
- any resolution above the cap
- the original file itself

Search relies on OCR afterwards (Latin only). The UI copy "Each page becomes an editable page" (`strings.xml:209`) does not say this.

### 1.5 Is OCR truly offline?

**On-device, but not network-free on first use.**

- `play-services-mlkit-text-recognition:18.0.1` (`app/build.gradle.kts:120`) is the unbundled Play services model. It downloads on first use (`util/OcrEngine.kt:18–20`), and `isUnavailable` handles the not-yet-downloaded case (`:35–47`).
- The document scanner is also a Play services module (`:117`).
- Only the Latin recognizer is used (`OcrEngine.kt:7, 71`), so Hindi text is not recognised.
- The app manifest declares `INTERNET` and `ACCESS_NETWORK_STATE` (`app/src/main/AndroidManifest.xml:5–6`).
- No ScanWhirl code makes network calls. Images are not uploaded by ML Kit on-device APIs.

### 1.6 Encryption strength and honest claims

- **Standard:** PDF Standard Security Handler R3, V2, RC4 with a 128-bit key (`util/PdfEncryptor.kt:9–55`).
- **Owner password = user password** (`:38`). Permissions `0xFFFFF2C0` are print-only and meaningless when owner equals user.
- **App-side check:** `locked.meta` holds SHA-256("pdf_lock:" + password), unsalted (`ScanRepository.kt:1511`).
- **Not encrypted:** page JPEGs, OCR text and `.orig` files stay unencrypted in app-private storage. Search hides locked documents' text (`util/SearchMatcher.kt:107,129`).

**Honest claim:**
- Other PDF apps will require the password.
- RC4-128 stops casual opening, not a determined attacker.
- Inside the phone, the protection is Android's app sandbox plus an app password prompt.

The current `protect_note` (`strings.xml:266`) is already close to this.

### 1.7 Test coverage and current status (run on 2026-11-13 in this workspace)

**Command:** `.\gradlew.bat :app:testDebugUnitTest -PtestBuild --no-daemon --continue` on Windows 11 / JDK 17, which took 8 min 56 s.

**Result: 807 tests, 169 failed, 0 skipped.** This supersedes the recorded "101 tests, 3 failures".

| Bucket | Count | Evidence | Meaning |
|---|---|---|---|
| A. Host file-replace and FileProvider-path errors | **111** | `IOException: Could not replace meta.txt/ocr.txt/save_txn.txt/ocr_geo.txt/page_000.jpg/.orig/review_state.json`, `Could not publish scan.pdf/src.pdf`, `Failed to find configured root … C:\Users\…\Temp\robolectric-…` | `File.renameTo` cannot replace an existing file on Windows. Android (Linux) `rename(2)` can. These are almost certainly **test-host artefacts, not product regressions**, but unproven until the suite runs on Linux. |
| B. Downstream of A (timeouts, leftover `.swtmp`, OCR counts, crop/reorder) | ≈25 | `OcrIndexerTest`, `OcrStatusTest`, `ScanReview*`, `PageEditSafetyTest` swap tests, `UiSmokeTest` crop/reorder, `ExportHygieneTest` | Same root cause, most likely. |
| C. Stale expectations from the committed UX pass (`065294b`) | ≈28 | Tools sheet tile counts (`HomeScreenTest`, `ImportSheet(Narrow)Test`, `LongImageRemovalTest`, `FreeLegacyVaultEntryPointTest`, `UiSmokeTest`); retired nudge (`QuickSortTest` ×3, `ImportSheet*` ×2); hidden status line (`StatusLineScreenTest` ×4, `UiSmokeTest` ×2); import copy; Home "Search ready" hidden (`HomeSlice2Test`, font-scale tests) | Tests not updated for deliberate UI changes. |
| D. Behaviour question | 1 | `DirectScanSaveTest`: "no second edit screen after the scanner", but `MainActivity.scanSaved` opens `DocumentViewerActivity` (`MainActivity.kt:1715–1721`) | Owner intent needed (OQ-12). |

**The three search failures on record now pass:** `SearchMatcherTest` 16/16 and `LockedDocumentSearchPrivacyTest` 9/9.

**PDF-relevant suites:**

| Suite | Status | Cause |
|---|---|---|
| `PdfGeneratorTest` | 16/16 | — |
| `PdfVerifierTest` | 18/18 | — |
| `PdfImportTest` | 10/10 | — |
| `PdfTextLayerTest` | 14/14 | — |
| `PageSelectTest` (extract) | 3/3 | — |
| `PdfEncryptorTest` | 0/3 | bucket A, `Could not publish src.pdf` |
| `ScanRepositoryTest` | 9 failed | bucket A |
| `TrashTest` | 14 failed | bucket A |

**Coverage that exists:**
- merge order and sources intact (`ScanRepositoryTest:141–175`)
- compress copy and target (`ScanRepositoryTest:288–313`, `UiSmokeTest:1298`)
- extract (`PageSelectTest`)
- import failure classes, cancellation and staging recovery (`PdfImportTest`)
- encrypt dictionary, protect, unlock and badge (`UiSmokeTest:1493–1614`)
- lock index (`UiSmokeTest:1823`)
- locked-search privacy
- page swap safety (`PageEditSafetyTest`)
- Trash crash safety

**Coverage missing (added in PM-0.2):**
- editing a locked document keeps `/Encrypt`
- Unlock requires a password
- tools on a locked input
- crash or PDF failure during merge, extract or compress
- derived documents queued for OCR
- Organize discard and force-stop
- page count equal across thumbnails, files, `/Count` and the Result screen
- 300-page memory runs

---

## 2. Data model

### 2.1 Document directory (`filesDir/documents/<id>/`)

| File | Status | Meaning |
|---|---|---|
| `page_NNN.jpg` | existing | **Source of truth for pages and order.** |
| `page_NNN.orig` | existing | Kept original for filter re-apply. Never copied into a redacted output. |
| `scan.pdf` | existing | Generated, verified. Encrypted when locked. |
| `meta.txt` | existing | Name, createdAt, category, export records. |
| `ocr.txt`, `ocr_geo.txt`, `ocr_pending`, `text_layer.flag` | existing | OCR store. |
| `locked.meta`, `vault.flag`, `txn.txt` | existing | Lock hash, legacy Vault flag, new-scan provenance. |
| **`provenance.txt`** | new (PM-2.1) | Versioned key=value: `origin`, `sources` (id + page digest), `tool`, `params`, `createdAt`, `original` (name, bytes, sha256). Written atomically before publish. |
| **`original.pdf`** | new (PM-2.7) | Byte-exact imported source. Present only while the document is unchanged since import. |

`ScanDocument` gains `origin` (default `legacy`), `hasOriginalPdf` (default false) and `sourceIds` (default empty). The loader reads `provenance.txt` if present. The **share file** is `original.pdf` when `hasOriginalPdf`, otherwise `scan.pdf`. The size label uses the share file (I2).

### 2.2 Drafts (`filesDir/drafts/<docId>/`)

- `draft.txt`: versioned manifest with these fields:
  - base document id
  - base page digest (`SaveFormat.pagesDigest`)
  - createdAt and updatedAt
  - ordered entries, each: source page name + its SHA-256, **or** `replacement=<file>`; quarter-turns 0–3
  - change count
- `r_<n>.jpg`: replacement or inserted pages (crop, filter, photo insert, page from another document).
- **One draft per document.** Rewritten with `Durable.atomicWriteText` after each change.
- **Never** swept by startup cleanup. A draft is removed only by Discard, a successful Save, or the base document being permanently deleted from Trash.

### 2.3 Staging (`filesDir/op_staging/<token>/`)

Holds a tool's output while it is being built. It becomes `documents/<newId>` by one rename. Startup sweeps entries older than 1 hour, on the same pattern as `recoverImports`.

### 2.4 Recent list

Prefs key `pdf_tools_recent`: up to 5 ids, most recent first. Ids of documents that no longer exist or are vaulted are dropped on read. Locked documents stay listed but show no thumbnail.

---

## 3. Operation and result model

### 3.1 One operation pipeline

```
Input (doc ids + optional verified passwords, held in memory)
  → Plan (pure): list of output documents; per output an ordered list of
               page sources {doc id, page file | draft replacement, quarter-turns, stamp ops}
  → Precheck: page cap 500 per output, free space (2 × source page bytes + 16 MB),
              sources unchanged since planning (page digest)
  → Build in op_staging/<token> (one page at a time; ensureActive between pages;
              progress "Page n of N"; fault seam per stage)
  → PdfGenerator.imagesToPdf (verifier inside) → encrypt if required (E13) → verify /Count
  → provenance.txt → meta.txt (name, inherited category) → ocr_pending
  → publish: rename staging → documents/<newId> (TRASH_LOCK, same as import)
  → OCR: re-use source OCR when a page's bytes are unchanged (copy text and geometry
         under the new page key); otherwise enqueue the indexer
  → Result
```

- **Replace original** (Organize only) adds one step after build: move `documents/<id>` to Trash as "‹name› (previous version)", then rename staging to `documents/<id>`. Startup recovery handles the gap: a staging directory tagged `replaces=<id>` with no `documents/<id>` is published under `<id>`.
- **Cancel or failure** before publish deletes staging only. After publish nothing is rolled back; Undo is explicit.

### 3.2 Result payload (what the Result screen receives)

- tool
- outputs (ids, names, page counts, share-file sizes)
- inputs (ids, names, share-file sizes)
- destination text
- warnings (grew, locked-like-source, images-unprotected, original-still-contains-content)
- undo kind (`trash-new`, `restore-previous`, `undo-export`, `none`)
- failure (reason enum, page index)

**Evolve `ui/ExportResultActivity.kt` rather than adding a sixth result screen.** It already shows a preview, before/after sizes and Save/Share, and carries `docId`. It gains: Open, Show in library, Undo, multi-output list, error form. Resize-photo results keep using it in file mode.

---

## 4. Invariants (asserted by a shared test helper, used by every operation test)

| ID | Invariant |
|---|---|
| I1 | For every visible document: count of page files = `/Count` of `scan.pdf` (when unencrypted, `PdfVerifier.verify(expectedImages=…)`; when encrypted, trailer and page tree `/Count`) = `original.pdf` page count when present = the Result screen and row count. |
| I2 | The displayed size = the byte length of the share file. |
| I3 | `locked.meta` exists ⇔ `scan.pdf` contains `/Encrypt`. |
| I4 | No visible document lacks a verified `scan.pdf`. Nothing is published before verification. |
| I5 | After any tool except Protect/Unlock and an explicit Replace, every source directory is byte-identical (recursive digest before and after). |
| I6 | Failure, cancel or simulated death at any stage leaves no visible partial document. Startup recovery leaves no staging older than the threshold and never deletes a draft. |
| I7 | While a draft exists, its base document is byte-identical to its state at draft creation. |
| I8 | After Replace, exactly one Trash entry "‹name› (previous version)" exists and restores byte-identically. |
| I9 | A redacted output has no `.orig` for redacted pages, no OCR text from before redaction for those pages, no `original.pdf`, and box pixels within tolerance of the fill. |
| I10 | Every new document is either fully indexed or carries `ocr_pending`. |
| I11 | A locked document's text never reaches search results, snippets or page hits (existing tests). |
| I12 | No class in `ui/pdftools/` or the operation pipeline opens a network connection. Checked by a source scan test, in the same style as `LongImageRemovalTest:215–231`. |

---

## 5. Navigation and file ownership

### 5.1 New screens (new files; manifest entries are app code, not build files)

| File | Responsibility |
|---|---|
| `ui/pdftools/PdfToolsActivity.kt` | Screen 1. Quick actions, Recent, groups, entry gating. |
| `ui/pdftools/DocPickerActivity.kt` | Screen 2. Single, multi-ordered and browse modes; returns ids through an activity result. Reuses `ScanListAdapter` row binding or a slim variant bound by `DocThumbBinder`, plus `SearchMatcher`. |
| `ui/pdftools/ToolSetupActivity.kt` | Screen 5. Hosts one tool's options view per tool kind; sticky footer; `adjustResize`. |
| `ui/pdftools/OrganizeActivity.kt` | Screen 4. Either a new activity or `DocumentViewerActivity` repurposed; see PM-2.2. |
| `ui/pdftools/StampEditorActivity.kt` | Screen 7 (Slice 5). |
| `ui/pdftools/DocumentToolsSheet.kt` | Document-scoped tools sheet, used by Reader and Home row ⋯. |

### 5.2 New store and util code

| File | Responsibility |
|---|---|
| `store/OperationRunner.kt` | Pipeline 3.1, fault seam, staging and recovery. |
| `store/Provenance.kt` | Read/write `provenance.txt`. |
| `store/DraftStore.kt` | Manifest read/write, stale detection. |
| `util/PageRanges.kt` | Pure range parsing and formatting. |
| `util/PageStamp.kt` | Slice 5 bake-into-bitmap for sign, watermark and redact, with verification. |
| `util/DocxWriter.kt` | Slice 6, only if the spike passes. |
| `util/LongImage.kt` | PM-4.4. A **new** bounded stitcher (E20). Never restore the old one. |
| `store/PowerPackBilling.kt` | PM-5.5. The Play Billing wrapper behind a fake-able seam. The only approved new dependency. |
| `util/PowerPack.kt` or an extension of `util/ProGate.kt` | Entitlement plus visibility (`30_FREE_PRO.md` §4). |

### 5.3 Changed files

| File | Change | Task |
|---|---|---|
| `ui/MainActivity.kt` | Tools button → PdfTools; remove `showToolsSheet`; Quick sort into the sort menu; proBadge hidden without a storefront; row ⋯ gains "Tools…" | PM-1.1, PM-1.4 |
| `ui/PdfViewerActivity.kt` | Dock, lock glyph, Save to Documents, draft banner | PM-1.3, PM-2.2 |
| `ui/DocumentViewerActivity.kt` | Becomes the draft organizer, or is retired into it | PM-2.2 |
| `ui/PageViewerActivity.kt` | Crop, filter, rotate and delete write to the draft when opened from Organize | PM-2.3 |
| `store/ScanRepository.kt` | Lock guard in `regeneratePdf`; merge/extract/compress/duplicate delegate to `OperationRunner`; import keeps the original; `loadDocument` reads provenance; password-checked unlock | PM-0.3, PM-2.x, PM-3.1 |
| `util/PdfEncryptor.kt` | Streaming | PM-3.2 |
| `tools/MergeFlow.kt`, `tools/CompressFlow.kt`, `tools/LockFlow.kt` | Shrink to launchers of the picker and setup, or are deleted | PM-2.4, PM-2.5, PM-3.1 |
| `tools/ResizeFlow.kt` | Setup pattern | PM-2.5 |
| `ui/ExportResultActivity.kt` | Result model 3.2 | PM-2.1 |
| `ui/SettingsActivity.kt` | Legacy Vault row | PM-1.1 |
| `res/values{,-hi}/strings.xml`, layouts | New activities and copy | all |
| `AndroidManifest.xml` | New activities. **Remove** `INTERNET`/`ACCESS_NETWORK_STATE` and add the ML Kit install-time model declaration (PM-1.5). No permission is ever added. | all, PM-1.5 |
| `app/build.gradle.kts` | **Owner-approved exceptions only:** `billing-ktx` (PM-5.5) and `play-services-mlkit-text-recognition-devanagari` (PM-6.3, removed again if the spike fails) | PM-5.5, PM-6.3 |

**Not touched:** any other build setting, the version, signing, the package id, `SaveTransaction.kt` semantics, `ExportProvenance.kt`, `DownloadSaver` transaction logic.

### 5.4 Tests that will change deliberately

| Task | Tests |
|---|---|
| PM-1.1 | Tools sheet → PDF Tools assertions in `HomeScreenTest`, `ImportSheetTest`, `ImportSheetNarrowTest`, `LongImageRemovalTest` (keep "no long image" but assert on PDF Tools), `FreeLegacyVaultEntryPointTest`, `UiSmokeTest`, `HomeSlice2Test`, `TextFitTest` |
| PM-1.4 | `FreeLegacyVaultEntryPointTest:338` (Protect no longer gated) |
| PM-2.2 | `UiSmokeTest` "drag reorder persists the new page order" becomes "drag reorder persists in the draft and in the saved copy" |

---

## 6. Performance and memory (100+ pages)

| Concern | Today | Plan |
|---|---|---|
| Compress holds all pages | `ScanRepository.kt:1355` | Encode one page into staging at a time (PM-2.5) |
| Device-PDF compress-to-target holds all renders | `CompressFlow.kt:294–300` | Removed: device PDFs are imported first, then compressed |
| Encrypt holds the PDF twice and more | `PdfEncryptor.kt:35,188` | Stream object by object with `RandomAccessFile` (PM-3.2) |
| Generator reads each page twice | `PdfGenerator.kt:76–82, 111–112` | Acceptable (bounded by one page). Keep. |
| Thumbnails | `PageThumbLoader`, `DocThumbBinder` sampled decode | Reuse; no full decode in grids or picker |
| Reader | LRU of 4 bitmaps, max 1600 px | Keep |
| Merge 500 pages | file copies | Free-space precheck; progress; Cancel between pages |

**Gate:** a 300-page synthetic document in Robolectric must pass merge, compress, protect, organize-save and export-ZIP without OOM.

**Device timing targets on the reference device (Infinix X6711):**
- 100-page compress under 60 s
- protect under 10 s
- organize-save of 100 pages under 30 s

---

## 7. Migration

- **Existing documents** keep working unchanged. A missing `provenance.txt` means `origin=legacy`, with no "Imported" label and no original. No rewrite on upgrade.
- **Existing `edit_snapshot_<id>`** directories (left by force-stops, `DocumentViewerActivity.kt:848`):
  - PM-2.2 adds a one-time startup check. If any exist, Home shows one notice per affected document: "An earlier edit of ‹name› left a backup. Restore it as a copy / Delete backup."
  - **Restore as a copy** publishes the snapshot as a new document "‹name› (before edit)" through the publish path. It never overwrites.
- **Legacy Vault** is unchanged.
- **Lock index** (`locked_pdfs.txt`) is unchanged. Re-encryption on Replace calls `recordLockIndex` again.
- **Future PDF engine:** E18.

---

## 8. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Draft model regresses the page editor | Medium | High | PM-2.2 and PM-2.3 split; I7 test; device force-stop checklist |
| Windows test host hides real regressions | High | Medium | PM-0.1 reruns on Linux (WSL against the same tree) before any feature work |
| Locked-document edits leak plaintext (current) | Present today | High | PM-0.3 guard first |
| Storage doubling from `original.pdf` | Certain | Low/medium | Info shows size; OQ-6 |
| Power Pack visible without a storefront → dead end | Medium | High (D3 repeat) | §4 visibility rule plus a test that production-mode Free sees zero PP tiles |
| `.docx` opens badly | Medium | Medium | Spike gate PM-6.1 |
| AES output incompatible | Medium | Medium | Spike gate PM-3.4; RC4 stays |
| Redaction leaks via `.orig`, OCR or original | Medium without I9 | Critical | I9; device check opening the output in another app and searching for the hidden digits |
| Tools sheet removal breaks many UI tests | Certain | Low | Listed rewrites in PM-1.1 |
| Removing network permissions breaks ML Kit or the scanner | Low | High | DS-2 merged-manifest audit first; PM-1.5 airplane-mode device check; one-line revert |
| Long image repeats its old out-of-memory and squash failures | Low with E20 | Medium | Bounded canvas, split instead of squash, a 100-page test |
| Power Pack pirated at ₹100 | Certain | Low (owner accepts) | No anti-tamper work |
