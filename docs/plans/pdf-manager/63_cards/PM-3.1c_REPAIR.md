# PM-3.1c — "Protection needs repair" (legacy and crash state)

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decision A5: `61_ENGINE_DECISIONS.md` §2. Needs PM-3.1b.

## 0. Carry-over fixes from PM-3.1b (do these FIRST, before §4)

The lead engineer reviewed PM-3.1b (`8bd1d05`) and found three defects. Fix them in this card.

| # | Where (re-confirm with Select-String) | Defect | Fix |
|---|---|---|---|
| C1 | `util/PdfProtection.kt` `isEncodable` (about `:57`) | The second range is `' '..'ÿ'`, which admits the C1 control characters U+007F–U+009F. | `pw.isNotEmpty() && pw.all { it in ' '..'~' \|\| it in ' '..'ÿ' }`. Write the escapes literally as `' '` and `'ÿ'`, not as pasted characters. |
| C2 | `ui/MainActivity.kt`, the `unlock_pdf_remove` button inside `promptUnlockBeforeOpen` (the `lifecycleScope.launch { withContext(Dispatchers.IO) { repo.unlockPdf(doc.id, pw) } … }` block) | An exception from `unlockPdf` (for example `locked_no_pages`) is uncaught inside `launch`, so the app crashes. | Wrap the `launch` body in `try { … } catch (e: Exception) { Toast.makeText(this@MainActivity, getString(R.string.page_op_failed, e.message), Toast.LENGTH_LONG).show() }`. Only the success path calls `refreshList()` and `onUnlocked()`. |
| C3 | `tools/LockFlow.kt` `lockDevicePdf` / `runLockDevicePdf` (about `:173-211`) | A non-Latin password is rejected only **after** `importPdfAsScan`, which leaves an unprotected imported copy in the library. | In `lockDevicePdf`, where the password is read and **before** `runLockDevicePdf` is called: if `!PdfProtection.isEncodable(pw)`, toast `R.string.password_charset` and return. In `runLockDevicePdf`, if `protectPdf` throws, call `repo.moveToTrash(doc.id)` inside its own `runCatching`, then show the error. The user never keeps an unprotected copy they did not ask for. |

**Red test first for C1** (add it to `ProtectionStateTest` as test 0, `password encoding accepts printable Latin-1 only`):

- `isEncodable("abc 123!~")` is true.
- `isEncodable("café")` is true (é = U+00E9).
- `isEncodable("\u0080x")` is false; today's code returns true, so this assertion is the red one. The message is `"C1 control character must be rejected"`.
- `isEncodable("पासवर्ड")` is false.
- `isEncodable("")` is false.

C2 and C3 are UI defensive fixes and get no new test. The reviewer reads the lines. Do not change any existing test for them.

**Test count.** This card adds **7** tests, not 6: the C1 test plus the six in §4. So **832** is the expected total, not 831.

## 1. Goal

Detect `locked.meta` beside a plain `scan.pdf` (left by the old bug, or by a PM-3.1b crash) and an encrypted
`scan.pdf` without `locked.meta`. Never let either be shared silently, and let the user fix it with their password.

**User-visible effect.** For an affected document, Share and Save to Documents open a dialog, "Protection needs
repair", with **Protect again** / **Remove lock** / **Not now**. The reader shows the same dialog when the document opens.
Unaffected documents see no change.

## 2. Invariants established

- **I3** "`locked.meta` exists ⇔ `scan.pdf` contains `/Encrypt`."
  - A violation is now *visible*, never silent.
  - Repair restores the invariant.
  - Export paths refuse while it is violated.
- **I11**: unchanged.

## 3. Files to touch

| File | Function | Change |
|---|---|---|
| `util/PdfProtection.kt` | new `enum class State { NONE, LOCKED, NEEDS_REPAIR, UNKNOWN_PASSWORD, UNREADABLE }`, `fun state(dir: File): State` | `locked = File(dir, LOCK_FILE).exists()`; `enc = isEncrypted(File(dir, PDF_FILE))`, where a missing PDF or null gives `UNREADABLE`. Map: (locked, enc) → (T,T) `LOCKED`, (T,F) `NEEDS_REPAIR`, (F,T) `UNKNOWN_PASSWORD`, (F,F) `NONE`. It reads ≤ 4 KiB. |
| `store/ScanRepository.kt` | new `fun protectionState(id: String): PdfProtection.State` | `PdfProtection.state(File(docsDir, id))`. |
| | new `suspend fun repairProtection(id, password): ScanDocument` | Require `NEEDS_REPAIR`, otherwise throw `IllegalStateException`. Then `verifyPdfPassword`, otherwise throw `WrongPassword`. Then the PM-3.1b private core `encryptInto(dir, doc, password)`. It rewrites the same hash, encrypts, and renames over. |
| | new `suspend fun removeProtectionLabel(id, password): ScanDocument` | Require `NEEDS_REPAIR`; verify the password; then `File(dir, LOCK_FILE).delete()`, `Durable.syncDir(dir)`, `pruneLockIndex()`. |
| | new `suspend fun rebuildPlainPdf(id): ScanDocument` | Require `UNKNOWN_PASSWORD` and non-empty pages; then `regeneratePdf(dir)`. This is allowed because there is no `locked.meta`; `PdfGenerator` replaces `scan.pdf` atomically (`PdfGenerator.kt:172`). |
| `ui/ProtectionDialogs.kt` (new) | `object ProtectionDialogs` with `fun guardExport(activity: AppCompatActivity, repo: ScanRepository, doc: ScanDocument, proceed: () -> Unit)` and `fun showFor(activity, repo, doc, onResolved: () -> Unit)` | `guardExport`: `NONE`/`LOCKED` → `proceed()`; `NEEDS_REPAIR`/`UNKNOWN_PASSWORD` → `showFor(...)` and **do not** proceed; `UNREADABLE` → proceed (today's behaviour, and Share already handles a missing PDF, `ShareUtils.kt:18-25`). |
| `ui/MainActivity.kt` | `:1430` | `ProtectionDialogs.guardExport(this, repo, doc) { ShareUtils.share(this, doc) }`. |
| `ui/DocumentViewerActivity.kt` | `:135` share and `savePdfToDocuments` `:503` | Wrap both in `guardExport`. For `savePdfToDocuments`, wrap its whole body. |
| `ui/PdfViewerActivity.kt` | `onCreate`, after `openPdf()` succeeds for an `EXTRA_DOC_ID` | `ProtectionDialogs.showFor(...)` when the state is `NEEDS_REPAIR`. For `UNKNOWN_PASSWORD` with pages: in `openPdf`'s doc branch (`:230-236`), treat it like the locked branch (build `preview_<id>.pdf` from pages), then show the dialog. |
| `store/AutoExport.kt` | `:24` | Skip the document (no copy) when `PdfProtection.state(doc.pdfFile.parentFile!!) == NEEDS_REPAIR`. |
| strings | 8 keys | §5.3. |

## 4. Red tests first

Class `app/src/test/java/com/scanwhirl/app/store/ProtectionStateTest.kt` (Robolectric, sdk 35, `@After Faults.resetAll()`).

**Stub step.** Add `State` and `fun state(dir: File): State = State.NONE`, plus repository functions whose body is
`TODO("PM-3.1c")`. Run the tests and record the red messages. Then implement.

**Fixtures.**
- *repairable*: `DocFixtures.saved(...)`, then write `locked.meta` = `Prefs.sha256("pdf_lock:1234")` with `Durable.atomicWriteText`.
- *unknown*: `DocFixtures.locked(..., "1234")`, then delete `locked.meta`.

| # | Test name | Assertions | Red message (stub) |
|---|---|---|---|
| 1 | `classifies plain locked needs repair and encrypted without lock` | Four fixtures give `NONE`, `LOCKED`, `NEEDS_REPAIR`, `UNKNOWN_PASSWORD`. | `expected:<LOCKED> but was:<NONE>` |
| 2 | `a JPEG containing the bytes /Encrypt does not make a plain PDF look encrypted` | Build a JPEG: `ImportFixtures.image()` with a COM segment (`FF FE`, length, `"/Encrypt << >>"`) inserted after SOI. `PdfGenerator.imagesToPdf(listOf(it), out)`. First assert the fixture is adversarial: `String(out.readBytes(), ISO_8859_1).contains("/Encrypt")`. Then `PdfProtection.isEncrypted(out) == false`. | green (pins G18); if `PageImageFormat.checkJpeg` rejects a COM segment, record that and use an APP15 segment instead |
| 3 | `repair with the right password encrypts and with a wrong one changes nothing` | *repairable*: `repairProtection(id, "0000")` throws `WrongPassword` and `dirDigest` is unchanged. `repairProtection(id, "1234")` gives `LOCKED`, `assertI3`, `assertI1`, `verifyPdfPassword(id, "1234")`. | `NotImplementedError: … PM-3.1c` |
| 4 | `remove lock with the right password deletes only locked meta` | Digest every file except `locked.meta` before and after `removeProtectionLabel(id, "1234")`: equal. State `NONE`. | `NotImplementedError` |
| 5 | `share of a document that needs repair shows the dialog and shares nothing` | *repairable*. Launch `DocumentViewerActivity.intent(app, id)`, click `R.id.btnShare`, idle the looper. The latest dialog's title equals `protection_repair_title`, and `shadowOf(activity).nextStartedActivity == null`. | `expected dialog 'Protection needs repair'` (today the chooser starts, `ShareUtils.kt:21`) |
| 6 | `save to documents of a document that needs repair writes nothing` | *repairable*. DocumentViewerActivity → `onOptionsItemSelected(action_save_pdf)` (as `ExportHygieneTest` does). The dialog is shown and `repo.exportsOf(id).isEmpty()`. | `expected dialog 'Protection needs repair'` |

## 5. Implementation notes

### 5.1 `ProtectionDialogs.showFor`

- **`NEEDS_REPAIR`**: `SwDialog.card(activity)` with title `protection_repair_title` and message `protection_repair_msg` (doc name).
  - Positive `btn_protect_again`: `LockFlow.promptCurrentPassword` → `repo.repairProtection`. Toast `protection_repaired`, then `onResolved()`.
  - Neutral `btn_remove_lock`: prompt → `repo.removeProtectionLabel` → `onResolved()`.
  - Negative `protection_not_now`.
  - `WrongPassword` → toast `unlock_pdf_wrong` and nothing else.
  - Repository calls run with `lifecycleScope.launch` + `withContext(Dispatchers.IO)`.
- **`UNKNOWN_PASSWORD`**: message `protection_unknown_msg`.
  - Positive `btn_rebuild_pdf` → `repo.rebuildPlainPdf` → `onResolved()`.
  - Negative `protection_not_now`.

### 5.2 Why the user must confirm

The RC4 key needs the password, and the stored hash cannot yield it (F14, F15). So automatic repair is impossible, and silently dropping the label would downgrade the user's intent (A5).

### 5.3 Strings

Add to `values` and `values-hi`; prefix each Hindi value with `<!-- review -->`.

| Key | English | Hindi draft |
|---|---|---|
| `protection_repair_title` | Protection needs repair | सुरक्षा ठीक करनी है |
| `protection_repair_msg` | “%1$s” is marked as locked, but its PDF is not password-protected. Copies you already shared or saved are not changed by this. Enter your password to protect it again. | “%1$s” लॉक दिखता है, पर इसकी PDF पर पासवर्ड नहीं है। जो कॉपी आप पहले शेयर या सेव कर चुके हैं, वे इससे नहीं बदलेंगी। इसे फिर से सुरक्षित करने के लिए अपना पासवर्ड डालें। |
| `btn_protect_again` | Protect again | फिर से सुरक्षित करें |
| `btn_remove_lock` | Remove lock | लॉक हटाएँ |
| `protection_not_now` | Not now | अभी नहीं |
| `protection_unknown_msg` | This PDF has a password ScanWhirl doesn’t know. Rebuild it from your pages without a password? | इस PDF पर ऐसा पासवर्ड है जो ScanWhirl नहीं जानता। क्या इसे आपके पेजों से बिना पासवर्ड के दोबारा बनाएँ? |
| `btn_rebuild_pdf` | Rebuild PDF | PDF दोबारा बनाएँ |
| `protection_repaired` | Protected again | फिर से सुरक्षित किया गया |

## 6. Acceptance

1. Gate G. Expected Linux total: **825 + 7 = 832**, 0 / 0, on both runs.
2. **DEVICE** (needs H1; part of D1). On a test document:
   - protect with `1234`;
   - with run-as, overwrite `files/documents/<id>/scan.pdf` with an unprotected copy;
   - open the document: the dialog appears;
   - Protect again with `1234`: a share to another PDF app asks for the password.

   The reader dialog is **DEVICE-UNPROVEN** until then (PdfRenderer does not run under Robolectric, F22).
3. INV-9 (how many documents on the phone are affected) stays UNKNOWN until H1. It is settled by
   `adb shell run-as com.scanwhirl.app.test sh -c 'for d in files/documents/*; do [ -f $d/locked.meta ] && tail -c 4096 $d/scan.pdf | grep -c /Encrypt; done'`.

## 7. What not to do

- Do not scan every document at startup or on Home renders. State is computed only at reader open, Share and Save.
- Do not auto-repair, and do not auto-delete `locked.meta`.
- Do not grep the whole PDF for `/Encrypt` (G18).
- Do not block Share for `UNREADABLE`. That is a different, pre-existing failure with its own message.

## 8. Rollback

Revert. On-disk formats are unchanged.

## 9. Size

M. One session.

## 10. Definition of done

- [ ] `ProtectionDialogs.guardExport` wraps exactly the three export call sites, and AutoExport skips `NEEDS_REPAIR`.
- [ ] Red output for tests 1, 3, 4, 5 and 6 is pasted.
- [ ] C1 is fixed and its red output pasted. C2 and C3 are fixed (reviewer reads the lines).
- [ ] Two Linux runs: 832 / 0 / 0. Lint has 0 errors.
- [ ] The device step is run or marked DEVICE-UNPROVEN. INV-9 is recorded as UNKNOWN with the settling command.
