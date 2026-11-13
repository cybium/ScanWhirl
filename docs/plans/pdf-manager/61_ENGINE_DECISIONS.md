# 61 — Engine decisions (Part A: reconciliation and forks)

Architect: Opus, 2026-11-14. Docs only. Tree: `0c283b8` plus the uncommitted test reconciliation in
`docs/audit/2026-11-13/PDF_TOOLS_STATUS.md` §7. Paths are under `app/src/main/java/com/scanwhirl/app/` unless they
start with `app/`. Every line number below was read in this session.
Do not restate `20_ARCHITECTURE_PLAN.md`; this file only records what changed and why.

---

## 0. Code facts the decisions rest on

| ID | Fact | Evidence |
|---|---|---|
| F1 | Visibility rule: a document is visible iff `meta.txt` exists, no `.incomplete` marker, and a PDF or a page exists. | `store/ScanRepository.kt:135-154`, `store/SaveTransaction.kt:12-19` |
| F2 | File contents are fsynced and a failure is fatal; a **directory** fsync is best effort and "must never fail a save". | `util/Durable.kt:13-21, 43-62, 90-107` |
| F3 | The only rename seam is `Durable.renameSeam`, used by page replacement only. | `util/Durable.kt:34-35`, `util/PageReplace.kt:136` |
| F4 | Fault seams: `SaveFaults` (steps), `SimulatedProcessDeath` (an `Error`, so `catch (e: Exception)` cleanup is skipped), `trashFault`, `importFault`, `Durable.beforeFileSync`, `usableSpace`. | `SaveTransaction.kt:182-198`; `ScanRepository.kt:77-111`; `Durable.kt:29` |
| F5 | Import already publishes by **one directory rename from private staging** under `TRASH_LOCK`; `recoverImports` sweeps staging. | `ScanRepository.kt:1399-1440, 1452-1460` |
| F6 | The lock order is `PDF_LOCK` → `OcrStore.lock` → `TRASH_LOCK` ("never acquire the PDF lock while holding the OCR lock"). | `ScanRepository.kt:844-850`; `store/OcrStore.kt:40-43` |
| F7 | `regeneratePdf` ignores `locked.meta`. It is called by delete, rotate, crop, move, filter and unlock. | `ScanRepository.kt:1651-1665`; calls at `:1164, :1182, :1201, :1250, :1295, :1529` |
| F8 | `protectPdf` writes `plain.pdf` **inside the document folder**, overwrites `scan.pdf` with a non-atomic `copyTo`, then writes `locked.meta` with a plain `writeText` (no fsync). | `ScanRepository.kt:1500, 1510, 1511` |
| F9 | `unlockPdf` takes no password and deletes `locked.meta` before regenerating. | `ScanRepository.kt:1524-1532`; callers `tools/LockFlow.kt:50`, `ui/DocumentViewerActivity.kt:614,664`, `ui/MainActivity.kt:1786` |
| F10 | Merge writes `meta.txt` before the PDF and swallows PDF failure; `finalizeDoc` does the same and queues OCR only on the failure path. | `ScanRepository.kt:1093-1100, 1306-1322` |
| F11 | Duplicate, extract, merge, createDocument and mergePdfUris write straight into `documents/<id>` with `copyTo`/`writeBytes` (no fsync). | `:1007-1009, 1048-1054, 1073-1083, 1338-1343, 1146` |
| F12 | Compress holds every recompressed page in memory. | `ScanRepository.kt:1355-1357` |
| F13 | No tool refuses a locked source: merging, extracting or compressing a locked document yields an **unprotected** output. | no `isLocked` in `tools/MergeFlow.kt`, `tools/CompressFlow.kt`; `ScanRepository.kt:1040-1103, 1349-1359` |
| F14 | `PdfEncryptor` is pure-Kotlin RC4 R3/V2. It reads the whole file (`:35`), makes a whole-file `String` (`:188`, `:256`), writes with `writeBytes` (no fsync, `:53`), and encodes the PDF password as ISO-8859-1 (`:37`). | `util/PdfEncryptor.kt:33-55, 58-79, 187-270` |
| F15 | The lock hash is SHA-256 of `"pdf_lock:"+password` in **UTF-8**, so a non-Latin-1 password hashes differently from the PDF password. | `util/Prefs.kt:123-127`; `ScanRepository.kt:1511, 1539` |
| F16 | `PdfVerifier` is already a bounded narrow verifier (64 KiB chunks, no full read). It **rejects** encrypted output: the trailer regex `<<([^>]*)>>` breaks on `/Encrypt << … >>`, and the SOI check sees ciphertext. | `util/PdfVerifier.kt:8-35, 175-189, 294-295` |
| F17 | The generator writes the trailer after the xref, and the encryptor only replaces that trailer, so the effective trailer is always in the file's last few KiB. | `util/PdfGenerator.kt:156-165`; `PdfEncryptor.kt:250-270` |
| F18 | Page files are `page_%03d.jpg` (0-based) with a `.orig` sibling. Sidecars are `.page_NNN.jpg.<hex>.swtmp/.swbak`, and listing filters on the `jpg` extension. | `ScanRepository.kt:1254-1261, 1329`; `PageReplace.kt:45-47` |
| F19 | OCR files are `ocr.txt` (`#scanwhirl-ocr-v2`, key TAB base64) and `ocr_geo.txt` (`#scanwhirl-ocrgeo-v1`, text-digest-bound). A store can be rooted at any directory. | `OcrStore.kt:31-37, 169-187` |
| F20 | The Organize editor edits in place. Its snapshot is written to `filesDir/edit_snapshot_<id>` and restored by delete-then-copy, but the startup sweep looks in **cacheDir**. | `ui/DocumentViewerActivity.kt:165-178, 844-869`; `ui/MainActivity.kt:396-401` |
| F21 | `FreeLegacyVaultEntryPointTest` asserts that More → Unlock completes **without** a password, which encodes the F9 bug. | `app/src/test/java/com/scanwhirl/app/ui/FreeLegacyVaultEntryPointTest.kt:323-339` |
| F22 | Robolectric cannot run `PdfRenderer`. | `app/src/test/java/com/scanwhirl/app/util/PdfImportTest.kt:39` |
| F23 | `android:allowBackup="false"`. | `app/src/main/AndroidManifest.xml:17` |
| F24 | Startup recovery runs in `MainActivity.onCreate`, off the main thread. | `ui/MainActivity.kt:403-411` |

---

## 1. Correction table for `60_CORE_ENGINE_GUIDE.md` (untrusted input)

| # | Guide claim (§) | Verdict | Evidence or reason |
|---|---|---|---|
| G1 | No repository files were inspected (header) | VERIFIED | Its own statement. Every symbol it proposes is invented. |
| G2 | Gradle 8.14.3, Robolectric 4.14.1, minSdk 24 (header) | VERIFIED | `gradle/wrapper/gradle-wrapper.properties`; tests use `@Config(sdk = [35])` (e.g. `TrashTest.kt:40`). |
| G3 | Keep the "marker hides, rename moves, unlink publishes" model (§2.2) | VERIFIED | F1, F5; `ScanRepository.kt:589-600`. |
| G4 | `.engine/transactions/<op>/` with request/prepared/intent/rollback/committed/aborted/abandoned records, P0–P10 (§2.2–2.5) | REJECTED | Duplicates `SaveTransaction` (new scans) and the import rename (F5). Tool outputs have no caller-side replay identity, so receipts buy nothing. Only *Replace* has a two-rename gap, and one seal file covers it (A1). |
| G5 | Fsync regular files with `fd.sync()` or `FileChannel.force` (§2.3) | VERIFIED | `Durable.kt:44-62` already does it. |
| G6 | Directory fsync through `Os.open(O_RDONLY\|O_DIRECTORY)` + `Os.fsync` (§2.3) | VERIFIED, with nuance | `Durable.kt:99-107` uses `O_RDONLY` only, which works for directories on Linux. |
| G7 | If directory sync fails, refuse the mutation (§2.3, §6) | REJECTED | Contradicts the documented contract (F2). Under Robolectric `syncDir` can return false, so every save would fail. The worst case is power loss making an *unpublished* output vanish, which is acceptable. |
| G8 | Use `Os.rename` instead of `File.renameTo` (§2.3) | REJECTED | On Android `File.renameTo` is `rename(2)`. Every existing publish uses it with its Boolean checked (`Durable.kt:81`, `PdfGenerator.kt:172`, `ScanRepository.kt:857, 1430`). The Windows difference is a host artefact (`BUILD_LINUX.md:42-52`). |
| G9 | One process-wide monitor for all mutations, OCR and export (§2.1) | REJECTED | Would collide with the existing ordered locks (F6) and with `SAVE_LOCK` (a `Mutex`, `:58`). Long OCR would block saves. Keep F6 and add no new global lock. |
| G10 | Gate every reader on unresolved intent records (§2.4) | REJECTED | Create-only outputs are invisible until one rename (F1, F5). Replace's gap means "document briefly absent", resolved by startup recovery (A1); no reader changes are needed. |
| G11 | Exception messages `Could not publish scan.pdf` and `Could not replace <file>` must survive (§2.5) | VERIFIED | `PdfGenerator.kt:173`, `Durable.kt:81`, `PageReplace.kt:137`. No card changes them. |
| G12 | Two renames cannot atomically swap a populated directory; never delete-then-rename (§2.6) | VERIFIED | That is the real hazard of `restoreSnapshot` (`DocumentViewerActivity.kt:860-869`). |
| G13 | Organize: staging plus replacement beats an in-place journal (§3.1) | VERIFIED as direction | But see G14. |
| G14 | Copy every page into the draft on open (§3.3) | REJECTED | 500 pages of about 400 KB is about 200 MB per open. Chosen instead: a manifest over the immutable base, plus a repository lease (A2). |
| G15 | Page naming, zero- or one-based, is UNKNOWN (§3.2) | RESOLVED | F18. |
| G16 | `ocr_geo.txt` encoding is UNKNOWN (§3.3) | RESOLVED | F19. |
| G17 | Locked page edits regenerate plaintext (§4.1) | VERIFIED | F7. |
| G18 | Do not grep the whole file for `/Encrypt` (§4.1) | VERIFIED | JPEG bytes can contain it. Decision: a bounded parse of the trailer tail (F17), valid because every `scan.pdf` is generator output (import re-generates, `:1409-1411`). |
| G19 | Build a new narrow grammar verifier (§5.4) | REJECTED | `PdfVerifier` already is one (F16). Extend it with an encrypted mode (PM-3.2b). |
| G20 | Use JCA `ARC4/ECB/NoPadding` with a provider probe (§5.2) | REJECTED | Pure-Kotlin RC4 is deliberate (`PdfEncryptor.kt:58`) and deterministic on every device and on Robolectric. |
| G21 | Keep RC4-128 for v1 with honest wording (§5.1) | VERIFIED | Matches the plan (§1.6) and `strings.xml:266`. |
| G22 | Encrypt during generation (§5.2) | REJECTED for v1 | RC4 preserves length, so a streaming post-pass keeps every offset (`PdfEncryptor.kt:9-13`). It also gives a byte-identical oracle against today's encryptor, which generation-time encryption cannot. |
| G23 | In-place encryption of the live `scan.pdf` is unsafe (§5.2) | VERIFIED | F8. Today's `copyTo` can truncate `scan.pdf`. |
| G24 | Encryption must also cover strings and the text layer (§5.2) | VERIFIED, vacuously | The generator emits no strings outside streams (`PdfGenerator.kt:106-165`). The text layer sits inside content streams, which are encrypted (`PdfEncryptorTest.kt:232-278`). |
| G25 | A 2 MiB budget table, 1,000,000-object and 16 GiB ceilings (§5.3) | REJECTED, except the 64 KiB buffer | Buffers match `PdfVerifier.CHUNK` (`:43`). The real page cap is 500 (`PdfImport.kt:38`). Extra ceilings are untestable ceremony. |
| G26 | `locked.meta` v2 with salted PBKDF2 at 600k iterations, plus an API 24/25 SHA-1 fallback (§5.5) | REJECTED for v1 | The hash only gates in-app UI. The page JPEGs and OCR sit **in plaintext** beside it (plan §1.6), and backups are off (F23). PBKDF2 adds an API 24/25 branch and seconds of latency for no gain in confidentiality. Revisit only with encrypted page storage. |
| G27 | Preserve the legacy hash encoding exactly (§5.5) | VERIFIED | F15. Plus a new rule: new passwords must be printable Latin-1, so the hash and the PDF password agree (PM-3.1b). |
| G28 | Legacy mismatch: offer Repair / Remove label / Cancel, user-confirmed (§4.2) | VERIFIED, adopted | Repair cannot be automatic: the RC4 key needs the password, and the hash cannot yield it (A5). |
| G29 | Inherit protection across multi-source tools (§4.3) | DEFERRED | v1 refuses locked sources (F13 fix, PM-3.1b). Inheritance belongs to PM-3.1 v2 in `40_SONNET_TASKS.md`. |
| G30 | Two checks at one engine boundary for protected rewrites (§4.3) | VERIFIED, simplified | A3: a pre-mutation repository guard plus a generator choke. |
| G31 | API 35 `PdfRenderer` password support is absent on the owner's API 34 phone (§5.2) | VERIFIED, with nuance | S-extension 14 makes `PdfRendererPreV` available (`PDF_TOOLS_STATUS.md` §4b). Not used in this pack. |
| G32 | Gradle commands are "not verified for this repository" (§8) | REJECTED | `BUILD_LINUX.md:27-35`, `40_SONNET_TASKS.md:6-9`. |
| G33 | Its card mapping (PM-0.2 = recovery, PM-3.1 = lock, …) (§8) | REJECTED | Real card meanings are in `40_SONNET_TASKS.md`. The re-cut is in `62_ENGINE_CARD_INDEX.md`. |
| G34 | Keep operation tombstones forever for deduplication (§2.2) | REJECTED | No caller retries a tool op by identity. The only replay is new-scan save, which already has a journal (`SaveTransaction.kt:90-112`). |
| G35 | A stale OCR worker may merge into a reordered filename (§7) | VERIFIED as risk; already mitigated | `OcrStore.putRecognizedPage` re-hashes the page under the lock (`OcrStore.kt:246-252`). |
| G36 | `.swtmp` must never be listed as a page (§3.5) | VERIFIED | F18. |
| G37 | Exports remain a separate transaction (§2.6) | VERIFIED | `util/DownloadSaver.kt`, `store/ExportProvenance.kt`; plan §5.3 forbids touching them. |
| G38 | Directory fsync really reaches flash on the owner's device (§2.3) | UNVERIFIABLE here | Needs a device probe (BLOCKED-ON-HUMAN H5). The design does not depend on it (F2). |
| G39 | Per-record trailing SHA-256 to detect torn records (§2.2) | REJECTED | Records are tmp + fsync + rename (`Durable.kt:77-86`), so a torn record is never visible. Headers are already checked (`SaveTransaction.kt:68-75`). |
| G40 | Robolectric API 36 support is UNKNOWN (§6) | RESOLVED | The suite runs at `sdk = [35]` (and `[28]` in `OcrPartialEditTest.kt:23`). Cards use 35. |

---

## 2. Decisions

### A1. Publish primitive

- **Options considered.**
  1. In-place marker (the new-scan pattern): `mkdir documents/<id>`, `.incomplete` first, unlink to publish (F1).
  2. A private staged directory plus one rename into `documents/` (the import pattern, F5).
  3. The guide's transaction tree (G4).
  4. An in-place journal.
- **Decision: option 2**, generalised as `ScanRepository.buildAndPublish(...)` with `store/OpStaging.kt`.
  - `filesDir/op_staging/<token>/` is built completely: pages, `scan.pdf`, `meta.txt`, OCR files and `ocr_pending`. Every file is fsynced and the PDF is verified.
  - It then becomes `documents/<newId>` by **one** `File.renameTo` under `TRASH_LOCK`.
  - **Draft commits** (PM-2.2b only, both Save-as-new and Replace) add a seal file `op_staging/<token>/.publish` (`draft=<id>`, an optional `replaces=<id>`, `token`, `pagesSha256`, `pdfSha256`). It is written after verification and before the rename(s), so recovery can finish draft cleanup. Replace then does two renames:
    1. `documents/<id>` → `trash/<key>`, with marker line `previous=<token>`;
    2. staging → `documents/<id>`.
  - Startup recovery **rolls forward** a sealed staging whose `documents/<id>` is absent *and* whose trash entry carries `previous=<token>`. Every other staging directory not in this process's in-memory active set is deleted.
- **Rationale.**
  - The rename is the commit point, exactly as import already proves on device (F5). No visibility-rule change is needed: a staged directory is not under `documents/` (F1).
  - Option 1 needs a marker plus a reaper that may keep prepared output forever (`:771-785`). That is right for the review journal and pointless for tool outputs, which have no replay.
  - Java can fsync file descriptors reliably; directory entries only best effort (F2). The protocol fsyncs every file before the rename and calls `syncDir` after it. Power loss before a directory sync can only lose an output the user never saw confirmed; it can never expose a partial one.
  - Option 3 is rejected by G4, G7 and G9.
- **Blast radius.**
  - `ScanRepository.kt`: merge, extract, compress, createDocument, duplicate, mergePdfUris; `finalizeDoc` and `newDocDir` are deleted.
  - New `store/OpStaging.kt`, a startup call in `MainActivity.kt:408-411`, and `Durable.copyFile`.
  - New-scan save, import, Trash and Export are untouched.
- **Regression test.** `OpPublishTest` "merge death at every stage leaves no visible or orphan directory after recovery" (PM-2.1b).

### A2. Organize draft

- **Options considered.**
  1. Copy everything into a staging folder on open (guide).
  2. A journal plus undo log editing the base in place (today's model, F20).
  3. A manifest draft over the base pages (plan §2.2), plus a **repository lease** that keeps the base pages immutable while a draft exists.
- **Decision: option 3.**
  - **Storage.** `filesDir/drafts/<docId>/draft.txt` is a versioned manifest. Each entry is the source page name and its SHA-256, plus an optional `r_<n>.jpg`/`r_<n>.orig` replacement.
    - A rotate writes a rotated replacement file, so the grid keeps showing plain `File`s (`ui/pagegrid/PageGridView.kt:108`).
    - Replacements are written and fsynced before the manifest is rewritten atomically. A replacement file is never overwritten; each edit uses a fresh name.
    - A draft is created lazily, on the first edit. Opening the screen writes nothing.
  - **Lease.** While a draft exists, `rotatePage`, `cropPage`, `deletePage`, `movePage` and `setPageFilter` refuse the in-place edit. They share one guard, `requireEditable(dir)`. This gives invariant I7 by construction:
    - pages change only through those five functions (F7, F18);
    - OCR, meta, protect and unlock never touch `page_*.jpg`.
  - **Save as new copy.** Built through A1 from the manifest. Every referenced base page is re-hashed while it is copied; a mismatch fails as stale and keeps the draft.
  - **Replace original.** Requires the base page digest to equal the draft's `basePages`, then runs A1-Replace.
  - **Force-stop.** The draft is durable after every acknowledged edit. Recovery leaves drafts alone unless the base is gone from both `documents/` and `trash/`.
  - **Base edited by a tool while a draft is open.**
    - Tools that create new documents read the base and do not conflict.
    - Protect and Unlock change only `scan.pdf`/`locked.meta`. Drafts are refused on locked documents, and Protect is refused while a draft exists.
    - Rename and category changes are adopted at Replace, because Replace copies the base `meta.txt` byte for byte.
- **Rationale.** Opening is O(1) and the base is never written (I7). Commit reuses A1, so there is no second engine. Option 1 costs a full copy per open (G14). Option 2 is the current D1/D2 failure (F20).
- **Blast radius.**
  - New `store/DraftStore.kt`; draft functions and `requireEditable` in `ScanRepository.kt`.
  - `ui/DocumentViewerActivity.kt` (PM-2.2c), `ui/PageViewerActivity.kt` (PM-2.3b), and the Trash label in `ui/TrashActivity.kt`.
- **Regression test.** `DraftEngineTest` "draft edits never change the base document" (PM-2.2b).

### A3. Lock-guard placement

- **Options considered.**
  1. Per-call-site UI checks. These are the reason for F7 and F9.
  2. A repository-level guard only.
  3. A repository pre-mutation guard **plus** a choke in the only plaintext PDF writer.
- **Decision: option 3.** It has five parts.
  1. `requireEditable(dir)` runs at the top of the five in-place mutators, and `requireUnlocked(dir)` at the top of `regeneratePdf`. Each throws `PdfProtection.DocumentLocked` before any page file is touched. They are two functions because PM-2.2b adds the draft lease to `requireEditable` only, so a PDF refresh after OCR stays allowed while a draft is open.
  2. `PdfGenerator.imagesToPdf` throws `PdfProtection.PlainWriteRefused` when `outFile.name == "scan.pdf"` and `locked.meta` exists beside it. `PdfGenerator` is the **only** code that writes plaintext PDF bytes; the other writer, `PdfEncryptor`, writes ciphertext.
  3. `mergeDocuments`, `extractPages` and `compressScan` refuse a locked source (F13).
  4. `protectPdf`, `unlockPdf(id, password)` and `changePdfPassword` build in `op_staging` and swap with a rename, never `copyTo`.
  5. A source-scan test fails if the literal `"scan.pdf"`, or a write through `.pdfFile`, appears in production code outside `store/ScanRepository.kt`.
- **Rationale.**
  - The generator choke makes it impossible for any future tool to *write* a plaintext `scan.pdf` beside `locked.meta`, whatever function it calls.
  - The pre-mutation guard stops a page from changing first and the PDF being discovered unencryptable afterwards.
  - The source scan closes the remaining "write elsewhere, rename in" route. It is a review aid, not a sandbox (Kotlin cannot sandbox one module), and is labelled as such.
- **Blast radius.**
  - `PdfGenerator.kt` (one check at the top of `imagesToPdf`); new `util/PdfProtection.kt`.
  - `ScanRepository.kt` lock functions; `LockFlow.kt:36-101`; `DocumentViewerActivity.kt:612-616, 659-681`; `MainActivity.kt:1782-1794`.
  - Rewritten tests: F21, `UiSmokeTest.kt:1598, 1822`.
- **Regression test.** `LockGuardTest` "the generator refuses to write a plain scan pdf beside locked meta".

### A4. Encryption scope for v1

- **Options considered.**
  1. RC4-128, made streaming and honest.
  2. AES-128/256 (V4/V5) with a streaming writer.
- **Decision: option 1** (PM-3.2b). AES stays the gated spike PM-3.4.
  - `PdfEncryptor.encrypt(src, dst, password)` becomes a `RandomAccessFile` pass. It reads the xref table (20 bytes × objects), copies non-stream bytes verbatim, RC4s each stream body in 64 KiB chunks with a stateful cipher per object, replaces the trailer at the end, and fsyncs.
  - Output must be **byte-identical** to the frozen legacy implementation for a fixed file ID. The legacy code is copied into test sources as the oracle.
  - `PdfVerifier.verifyEncrypted(file, expectedPages, password)` checks the structure. It also decrypts and checks each image's first two bytes (SOI) and each content stream's `/Im<i> Do`, which is a cheap per-object proof that the key is right.
- **Rationale.**
  - AES-CBC adds an IV and padding per stream. That changes lengths, so offsets and the xref must be regenerated, which means generation-time encryption and a compatibility matrix the owner must run (PM-3.4).
  - RC4 preserves length (`PdfEncryptor.kt:9-13`), so a streaming pass is small, bounded and verifiable against today's bytes.
  - The memory ceiling is met: 64 KiB buffers plus an offsets array of about 8 bytes × objects, with no whole-file arrays (F14).
- **Blast radius.** `PdfEncryptor.kt` (internals; public signature kept), `PdfVerifier.kt` (new function; `verify` untouched), and three call sites in `ScanRepository` (protect, change password, repair).
- **Regression test.** `EncryptStreamTest` "streaming output is byte identical to the frozen legacy encryptor for a fixed file id".

### A5. Legacy repair (`locked.meta` beside a plain `scan.pdf`)

- **Options considered.**
  1. Automatic repair at startup.
  2. Silently drop the lock label.
  3. A user-confirmed repair state.
- **Decision: option 3** (PM-3.1c).
  - **Detection.** `PdfProtection.state(dir)` returns `NONE`, `LOCKED`, `NEEDS_REPAIR` or `UNKNOWN_PASSWORD` (encrypted without `locked.meta`). It reads only the last ≤ 4 KiB of `scan.pdf` and parses the trailer dictionary (F17, G18). Nothing is scanned on Home renders.
  - **Checked at:** reader open (`PdfViewerActivity.openPdf`), Share (`MainActivity.kt:1430`, `DocumentViewerActivity.kt:135`), and Save to Documents (`DocumentViewerActivity.kt:503`).
  - **What the user sees.** The dialog is titled "Protection needs repair". It names the document, says the PDF is not password-protected, and says copies already shared cannot be changed. Its actions:
    - **Protect again**: asks for the password, verifies it against the legacy hash, then re-encrypts.
    - **Remove lock**: asks for the password, then deletes `locked.meta`.
    - **Not now**.
  - Share and Save are blocked until the document is resolved; Auto-export skips it.
  - The same state is the **designed crash state** of Protect, Unlock and Change password in PM-3.1b. Lock-record-first ordering means a crash can leave "label without encryption", and never "encryption without a known password".
- **Rationale.**
  - Automatic repair is impossible, because the RC4 key needs the password and the stored hash cannot yield it (F14, F15).
  - Dropping the label would silently downgrade what the user asked for.
  - INV-9 (how many such documents the phone holds) is unmeasured, so the flow must be cheap and correct for zero or many.
- **Blast radius.** `util/PdfProtection.kt`, `ScanRepository` (repair and remove-label functions), `PdfViewerActivity.kt:224-239`, three share/save call sites, `store/AutoExport.kt:24`, and strings.
- **Regression test.** `ProtectionStateTest` "classifies plain locked needs repair and encrypted without lock".

### A6. Tool-output transactionality

- **Decision.** `mergeDocuments`, `extractPages`, `compressScan`, `createDocument`, `duplicateDocument` and `mergePdfUris` keep their **public signatures and return values**. Each body becomes one `buildAndPublish(name, category, expectedBytes) { staging -> … }` call.
  - **Names and categories** are unchanged (`:1012-1016, 1057-1059, 1090-1093, 1123, 1358`).
  - **Behaviour changes, all for the better:**
    - no publish without a verified PDF (F10);
    - an `ocr_pending` marker on every output that lacks text (I10);
    - compress encodes one page at a time (F12);
    - duplicate skips dot-files (`.swtmp`/`.swbak`/`.publish`) and copies durably.
  - **OCR.** Duplicate copies OCR files byte for byte, since the keys are unchanged. Other tools mark pending; carry-over is a later optimisation and is not needed for I10.
  - **UI flows** (`MergeFlow.kt:103`, `CompressFlow.kt:188, 213, 303`, `DocumentViewerActivity.kt:404, 645`, `MergePdfActivity.kt:157`) are not edited.
- **Rationale.** One primitive, one recovery path, and an unchanged API, so `ScanRepositoryTest`, `PageSelectTest` and `CategoryPersistTest` keep passing unedited. That is the proof that user-visible flows did not move.
- **Blast radius.** ScanRepository tool functions only. `mergePdfUris` happy paths are **DEVICE-UNPROVEN** (F22).
- **Regression test.** `ToolPublishTest` "extract publishes nothing when its PDF cannot be written".

---

## 3. What this pack deliberately does not do

- No `provenance.txt`, Result screen, Recent list or picker. Those are PM-2.1 (rest), PM-1.x and PM-2.5 in `40_SONNET_TASKS.md`; they build on A1 unchanged.
- No AES, no PBKDF2, no foreign-password PDFs.
- No manifest change. PM-1.5 is BLOCKED-ON-HUMAN.
- No migration of `filesDir/edit_snapshot_*` (F20). PM-2.2c stops creating them and leaves existing ones untouched. A follow-up notice card waits on INV-6 (H3).
- No change to new-scan save, import, Trash semantics or Export.
