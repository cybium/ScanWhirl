# PM-3.1b — Lock guard: no plaintext under a lock, no unlock without the password

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decisions A3 and A5: `61_ENGINE_DECISIONS.md` §2. Needs PM-0.2b.

## 1. Goal

A document marked 🔒 can no longer silently become unprotected. Page edits and tools are refused on locked documents.
Unlock and Change password require the current password. Protect and Unlock swap files by rename, never by an in-place copy.

**User-visible effect.**
- Rotate, crop, filter, delete or move on a locked document shows
  "Page operation failed: Unlock this document to edit its pages."
- Merge, Extract or Compress with a locked input shows "Unlock “‹name›” first…".
- Unlock and Change password ask for the current password; a wrong one shows "Incorrect password".
- Protect refuses passwords with non-Latin characters.

## 2. Invariants established

- **I3** "`locked.meta` exists ⇔ `scan.pdf` contains `/Encrypt`." It holds after every operation, except the one
  designed crash window, which leaves `locked.meta` beside the *previous plain* `scan.pdf`. That state is detected in PM-3.1c (A5).
- **I5** "every source directory is byte-identical": refused operations change no byte.
- **I11** "A locked document's text never reaches search results": unchanged, and kept green.

## 3. Files to touch

| File | Function | Change |
|---|---|---|
| `util/PdfProtection.kt` (new) | `object PdfProtection` | `const val LOCK_FILE = "locked.meta"`, `const val PDF_FILE = "scan.pdf"`, `fun isEncrypted(pdf: File): Boolean?` (same algorithm as `PdfInvariants.trailerEncrypted`, PM-0.2b), `fun isEncodable(pw: String) = pw.all { it in ' '..'~' \|\| it in ' '..'ÿ' }`, plus three `IOException` subclasses: `DocumentLocked(msg)`, `WrongPassword(msg)` and `PlainWriteRefused(file)`. |
| `util/PdfGenerator.kt` | `imagesToPdf` (`:68`) | First statement after `require(images.isNotEmpty())`: if `outFile.name == PdfProtection.PDF_FILE && File(outFile.absoluteFile.parentFile, PdfProtection.LOCK_FILE).exists()`, throw `PdfProtection.PlainWriteRefused(outFile)`. |
| `util/Durable.kt` | new `copyFile(src, dst)` | `FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 64 * 1024); syncStream(o, dst) } }`, then throw `IOException("Copy of ${src.name} is incomplete")` when the lengths differ. |
| `store/OpStaging.kt` (new) | `object OpStaging` | Spec in §5.1. |
| `store/ScanRepository.kt` | `LOCK_FILE` (`:1474`) | `= PdfProtection.LOCK_FILE`. |
| | new `private fun requireUnlocked(dir: File)` and `private fun requireEditable(dir: File) = requireUnlocked(dir)` | `requireUnlocked`: if `File(dir, LOCK_FILE).exists()`, throw `DocumentLocked(context.getString(R.string.locked_edit_refused))`. Two names on purpose: PM-2.2b adds the draft-lease check to `requireEditable` only. A PDF refresh after OCR does not change pages and must stay allowed while a draft is open. |
| | `deletePage` `:1151`, `rotatePage` `:1169`, `cropPage` `:1191`, `movePage` `:1224`, `setPageFilter` `:1268` | First statement inside `withContext`, right after `val dir = …`: `requireEditable(dir)`. |
| | `regeneratePdf` `:1651` | First statement inside `synchronized`: `requireUnlocked(dir)` (backstop). |
| | `mergeDocuments` `:1062`, `extractPages` `:1040`, `compressScan` `:1349` | After the sources load and **before** any `mkdirs`/`newDocDir`: if any source `isLocked`, throw `DocumentLocked(context.getString(R.string.locked_source_refused, src.name))`. In merge this goes before `:1073`. |
| | `protectPdf` `:1495` | Rewritten (§5.2). The signature is unchanged. |
| | `unlockPdf` `:1524` | Becomes `unlockPdf(id: String, password: String)` (§5.3). |
| | new `changePdfPassword(id, current, new)` | §5.4. |
| | new `fun recoverOperations(): Int` | `= OpStaging.sweep(context.filesDir)`. |
| `ui/MainActivity.kt` | `:1786` | `repo.unlockPdf(doc.id, pw)`. |
| | startup `:408-411` | Add `runCatching { repo.recoverOperations() }` after `recoverImports`. |
| `tools/LockFlow.kt` | `promptLockOrUnlockScan` `:36-68`, `promptNewPasswordForScan` `:70-101` | §5.5. |
| | new `companion object { fun promptCurrentPassword(activity: AppCompatActivity, docName: String, onEntered: (String) -> Unit) }` | Built like `:71-76`: title `R.string.unlock_current_pw_title`, message `docName`, positive `android.R.string.ok`. Used by LockFlow and DocumentViewerActivity. |
| `ui/DocumentViewerActivity.kt` | `unlockCurrentPdf` `:659-681` | Prompt via `LockFlow.promptCurrentPassword`, then `repo.unlockPdf(doc.id, pw)`. Catch `PdfProtection.WrongPassword` and toast `R.string.unlock_pdf_wrong`. |
| `res/values/strings.xml`, `res/values-hi/strings.xml` | 5 strings | §5.6. |
| `app/src/test/.../store/Faults.kt` | `resetAll` | Add `OpStaging.resetForTests()`. |

**Existing tests changed, each with its reason:**

- `FreeLegacyVaultEntryPointTest.kt:323-339` asserts that Unlock completes without a password (F21). The production
  line that changes is `DocumentViewerActivity.kt:614` → `unlockCurrentPdf` now prompts.
  - Rewrite the click flow: after `rowMoreLock.performClick()`, idle the looper, then `pinDialog()` must be non-null.
  - Then `submitPin(it, "1234")` (helpers at `:120-133`) and keep the remaining assertions.
  - Keep the test name.
- `UiSmokeTest.kt:1598`: `repo.unlockPdf(id)` → `repo.unlockPdf(id, "4321")` (the password set at `:1579`).
- `UiSmokeTest.kt:1822`: `repo.unlockPdf(id)` → `repo.unlockPdf(id, "open-sesame")` (set at `:1817`).

**Split point if the session runs long:**
- Session A = everything except LockFlow, DocumentViewerActivity, the strings for the prompts, and test 11.
- Session B = the UI.
- Gate G after each, with expected totals 824 and 825.

## 4. Red tests first

Class `app/src/test/java/com/scanwhirl/app/store/LockGuardTest.kt` (Robolectric, sdk 35, `@After Faults.resetAll()`).

**Red** means it fails on today's code with the message shown. **Green** means it passes before and after (characterisation) or needs the new API (written after the red step).

| # | Test name | Fixture and steps | Must fail today with | Pins |
|---|---|---|---|---|
| 1 | `page edits on a locked document are refused and leave every byte unchanged` | For each of rotate, crop `RectF(0,0,50,50)`, filter `BLACKWHITE`, delete(1) and move(0,2): a fresh `DocFixtures.locked`, then `d = dirDigest(dir)`, then `runCatching { op }`. Assert `isFailure` with message `"$op on a locked document must be refused"`. Then `dirDigest == d`, `trailerEncrypted == true`. After the fix, also assert `exceptionOrNull() is PdfProtection.DocumentLocked`. | `rotatePage on a locked document must be refused` | `requireEditable` calls at `:1151-1297` |
| 2 | `the generator refuses to write a plain scan pdf beside locked meta` | Locked doc. `runCatching { PdfGenerator.imagesToPdf(pageFiles, File(dir, "scan.pdf")) }` must fail; `scan.pdf` sha is unchanged; no `scan.pdf.tmp`. | `the generator must refuse a plain scan.pdf beside locked.meta` | the `PdfGenerator.kt` choke |
| 3 | `merge extract and compress refuse a locked source and publish nothing` | One locked and one plain document. Each of `mergeDocuments([plain, locked])`, `extractPages(locked, [0])` and `compressScan(locked, 50, null)` must fail; the `listDocuments()` count is unchanged; `assertNoOrphans`. | `mergeDocuments with a locked source must be refused` | the refusal lines |
| 4 | `duplicate of a locked document stays locked and encrypted` | `duplicateDocument(locked)`, then `assertI3(copy)` and `isLocked`. | green (pins A6 not breaking it) | `:1002-1032` |
| 5 | `unlock with a wrong password changes nothing and the right one unlocks` | Locked with `1234`. `unlockPdf(id, "0000")` throws `WrongPassword` and `dirDigest` is unchanged. `unlockPdf(id, "1234")` → `!isLocked`, `trailerEncrypted == false`, `assertI1`, `assertI3`. | green (new API) | §5.3 |
| 6 | `change password with a wrong current password changes nothing` | `changePdfPassword(id, "0000", "5678")` throws and the digest is unchanged. `changePdfPassword(id, "1234", "5678")`, then `verifyPdfPassword(id, "5678")`, `assertI3`. | green (new API) | §5.4 |
| 7 | `protect keeps plaintext temporaries out of the document folder` | Plain doc. `Faults.dieAtFileSync("plain.pdf.tmp")`, then `runCatching { protectPdf(id, "1234") }`. Assert that the document folder holds no name starting with `plain.pdf`, `protected.pdf` or `enc.pdf`. | `protect left plaintext temporaries in the document folder: [plain.pdf.tmp]` | the protect build happens in `OpStaging` |
| 8 | `death between lock record and pdf swap leaves the previous pdf byte identical` | Plain doc, `before = Sha256.of(scan.pdf)`. `Faults.dieAtOp("protect:lock_written")`, then `runCatching { protectPdf }`. Assert `Sha256.of(scan.pdf) == before`, `locked.meta` exists, and `trailerEncrypted == false` (the designed NEEDS_REPAIR state). Then `Faults.resetAll()`, `recoverOperations()`, and `op_staging` is empty. | green (new seam) | §5.2 ordering |
| 9 | `protect refuses a password the PDF cannot encode` | `protectPdf(id, "पासवर्ड1")` must fail, with no `locked.meta` and an unchanged digest. | `protect must refuse a password outside Latin-1` | `isEncodable` (F15) |
| 10 | `no production code writes scan pdf outside the repository` | Walk `File("src/main/java")` as in `LongImageRemovalTest.kt:218-227`. Offenders: any `.kt` file except `store/ScanRepository.kt` and `util/PdfProtection.kt` that contains the literal `"scan.pdf"` or matches `Regex("pdfFile\\.(writeBytes\|writeText\|appendBytes\|outputStream\|renameTo\|delete)\\(\|(copyTo\|renameTo)\\([^)]*pdfFile")`. | green; mutation proof: temporarily add `doc.pdfFile.writeBytes(ByteArray(0))` in `ShareUtils.kt`, see it fail, revert | A3 point 5 |

Class `app/src/test/java/com/scanwhirl/app/ui/LockUiTest.kt`:

| # | Test name | Steps | Must fail today with |
|---|---|---|---|
| 11 | `Tools Lock unlock asks for the current password` | 1. `ProGate.setTestOverride(app, true)`; a locked doc with `1234`. 2. Launch `MainActivity`, open Tools, tap `tile_lock` (`MainActivity.kt:1194`). 3. Tap item 0 (`source_saved_scan`). Then pick the document; read `FlowUtil.pickScan` to see which dialog it shows. 4. Tap item 0 "Unlock PDF". 5. Assert a dialog with an `EditText` is showing, **and** the document is still locked. 6. Submit `0000`: the toast is `unlock_pdf_wrong`, still locked. 7. Repeat with `1234`: unlocked. | `Unlock must ask for the current password` (today it unlocks at once, `LockFlow.kt:50`) |

**List items.** Click them with `(ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, i, 0)`, then idle the main looper.

## 5. Implementation notes

### 5.1 `OpStaging` (new, `store/OpStaging.kt`)

```kotlin
internal object OpStaging {
    const val DIR = "op_staging"
    private val live: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    fun root(filesDir: File): File = File(filesDir, DIR).apply { mkdirs() }
    /** Exclusive new working folder, registered live BEFORE mkdir. */
    fun create(filesDir: File): File {
        repeat(8) {
            val token = SaveFormat.newToken()
            live.add(token)
            val dir = File(root(filesDir), token)
            if (dir.mkdir()) return dir
            live.remove(token)
        }
        throw IOException("Could not prepare a working folder")
    }
    fun release(dir: File) { runCatching { dir.deleteRecursively() }; live.remove(dir.name) }
    fun forget(dir: File) { live.remove(dir.name) }               // renamed away (PM-2.1b)
    /** Every entry not live in THIS process belongs to a dead process: delete it. */
    fun sweep(filesDir: File): Int = root(filesDir).listFiles().orEmpty()
        .filter { it.name !in live && it.deleteRecursively() }.size
    internal fun resetForTests() = live.clear()
}
```

There is no age threshold. The live set is process-wide, so an in-flight operation is never swept. Recovery runs at process start (F24).

### 5.2 `protectPdf(id, password)`: order is the contract

1. `require(password.isNotBlank())`. If `!PdfProtection.isEncodable(password)`, throw
   `IllegalArgumentException(context.getString(R.string.password_charset))`.
2. `doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")`. If `doc.isLocked`, throw
   `IllegalStateException("Already protected; use Change password")`. This is a programmer error; the UI never offers it.
3. Call the private core `encryptInto(dir, doc, password)`, which runs under `synchronized(PDF_LOCK)`:
   1. `work = OpStaging.create(context.filesDir)`; then `try {`
   2. `plain = File(work, "plain.pdf")`. If `doc.pageFiles` is non-empty, call `writePdf(dir, plain)`; otherwise
      `Durable.copyFile(doc.pdfFile, plain)` (the legacy branch, `:1503-1506`).
   3. `enc = File(work, "enc.pdf")`; `PdfEncryptor.encrypt(plain, enc, password)`; `Durable.syncFile(enc)`.
      If `PdfProtection.isEncrypted(enc) != true`, throw `IOException("Protection could not be verified")`.
   4. `OpFaults.at("protect:encrypted")`.
   5. `Durable.atomicWriteText(File(dir, LOCK_FILE), Prefs.sha256("pdf_lock:$password"))`, then `OpFaults.at("protect:lock_written")`.
   6. If `!enc.renameTo(File(dir, "scan.pdf"))`: delete `locked.meta`, then throw `IOException("Could not protect the PDF")`.
   7. `Durable.syncDir(dir)`; `OpStaging.release(work)`.
   8. `} catch (e: Exception) { OpStaging.release(work); throw e }`. Use **Exception**: death must skip cleanup.
4. `recordLockIndex(File(dir, "scan.pdf"), id)`, then `loadDocument(dir)!!`.

**Why lock-first.** A crash between steps 5 and 6 leaves `locked.meta` beside the old plain PDF. That state is
detectable and honest (PM-3.1c). The opposite order would leave an encrypted PDF the app does not know is locked.

### 5.3 `unlockPdf(id, password)`

1. Load. If not locked, return the document unchanged.
2. If `!verifyPdfPassword(id, password)`, throw `PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))`.
3. If `doc.pageFiles.isEmpty()`, throw `IllegalStateException(context.getString(R.string.locked_no_pages))`. Today this branch leaves an encrypted PDF without a lock (`:1528-1529`).
4. Under `PDF_LOCK`:
   1. `work = create`; `plain = File(work, "plain.pdf")`; `writePdf(dir, plain)`; `OpFaults.at("unlock:built")`.
   2. `plain.renameTo(File(dir, "scan.pdf"))`, or throw.
   3. `OpFaults.at("unlock:swapped")`.
   4. `File(dir, LOCK_FILE).delete()`.
   5. Update `text_layer.flag` exactly as `regeneratePdf` does (`:1656-1664`). Extract that block into
      `private fun updateTextLayerFlag(dir: File, layered: Boolean)` and call it from both places.
   6. `syncDir`; `release(work)`.
5. `pruneLockIndex()`.

### 5.4 `changePdfPassword(id, current, new)`

1. Verify `current`, otherwise throw `WrongPassword`. Check `isEncodable(new)` as in §5.2. Refuse when there are no pages (`locked_no_pages`).
2. Then the §5.2 core with these differences:
   - step 2 always calls `writePdf` (pages exist);
   - fault names are `chpw:lock_written` etc.;
   - step 5 overwrites `locked.meta` with the new hash.
3. A crash between lock and swap leaves the new hash beside the old-password PDF. I3 still holds (both say locked).
   Record this as a known limitation in the KDoc.

### 5.5 UI (`tools/LockFlow.kt`, `ui/DocumentViewerActivity.kt`, `ui/MainActivity.kt`)

- **Unlock** (`LockFlow.kt:46-58`): `promptCurrentPassword(activity, doc.name) { pw -> launch { … repo.unlockPdf(doc.id, pw) … } }`.
  Catch `PdfProtection.WrongPassword` and toast `unlock_pdf_wrong`; other exceptions keep today's `page_op_failed` toast.
- **Change** (`:59-61`): `promptCurrentPassword { cur -> if (!repo.verifyPdfPassword(doc.id, cur)) toast wrong else promptNewPasswordForScan(doc, cur) }`.
  `promptNewPasswordForScan(doc, current: String? = null)` calls `changePdfPassword(doc.id, current, pw)` when `current != null`, otherwise `protectPdf`.
  Before either call, check `isEncodable(pw)`; on failure toast `password_charset` and keep the dialog closed.
- **`DocumentViewerActivity.unlockCurrentPdf`**: same pattern as Unlock.
- **`MainActivity.kt:1786`**: pass `pw`. It is already verified at `:1784`.

### 5.6 Strings

Add to `values` and `values-hi`; prefix each Hindi value with `<!-- review -->`.

| Key | English | Hindi draft |
|---|---|---|
| `locked_edit_refused` | Unlock this document to edit its pages. | इसके पेज बदलने के लिए पहले यह दस्तावेज़ अनलॉक करें। |
| `locked_source_refused` | Unlock “%1$s” first. Tools can’t use a password-protected document yet. | पहले “%1$s” अनलॉक करें। टूल अभी पासवर्ड वाले दस्तावेज़ का उपयोग नहीं कर सकते। |
| `locked_no_pages` | This document has no page images, so its password can’t be changed or removed. | इस दस्तावेज़ में पेज की तस्वीरें नहीं हैं, इसलिए इसका पासवर्ड बदला या हटाया नहीं जा सकता। |
| `password_charset` | Use letters, numbers and common symbols. Emoji and non-Latin letters can’t be used in a PDF password. | अंग्रेज़ी अक्षर, अंक और सामान्य चिह्न इस्तेमाल करें। PDF पासवर्ड में इमोजी और हिंदी अक्षर नहीं चलते। |
| `unlock_current_pw_title` | Current password | मौजूदा पासवर्ड |

## 6. Acceptance

1. Gate G. Expected Linux total: **814 + 11 = 825**, 0 failures, 0 skipped, on both runs. (If split: 824 after session A, 825 after B.)
2. **DEVICE D1** (needs H1): on a copy of a test document,
   - protect with `1234`, try Rotate: refused, and the page is unchanged;
   - Tools → Lock → that document → Unlock: a password prompt appears and `0000` shows an error;
   - share the document to another PDF app: it asks for the password.

   Until it is run, write "D1: DEVICE-UNPROVEN" in the report.

## 7. What not to do

- Do not check `isLocked` in the UI instead of the repository. The repository guard is the contract (A3).
- Do not keep a no-password `unlockPdf(id)` overload "for compatibility".
- Do not `copyTo` onto `scan.pdf`, and do not write any temporary into `documents/<id>/`.
- Do not use `catch (t: Throwable)` in protect/unlock; it would mask simulated death and real `Error`s.
- Do not change `PdfEncryptor` (PM-3.2b) or the `locked.meta` hash format (G26).

## 8. Rollback

Revert the card's files.
- **Data:** documents protected by the new code are identical in format (same hash and RC4 output), so a rollback reads them.
- **Leftovers:** a leftover `op_staging/` is harmless to older code.

## 9. Size

L. Split point in §3 (engine / UI).

## 10. Definition of done

- [ ] `Select-String -Path app/src/main/java -Pattern 'unlockPdf\(' -SimpleMatch` shows no call with one argument.
- [ ] The 5 mutators call `requireEditable`, and `regeneratePdf` calls `requireUnlocked`, each as the first statement (reviewer reads 6 lines).
- [ ] `PdfGenerator.imagesToPdf` contains the `PlainWriteRefused` check.
- [ ] No `copyTo(doc.pdfFile` and no `"plain.pdf"` under `documents/` remain in `protectPdf`.
- [ ] Red output for tests 1, 2, 3, 7, 9 and 11 is pasted, showing the stated messages.
- [ ] The three existing tests are changed exactly as listed in §3, and no others.
- [ ] Two Linux runs: 825 / 0 / 0. Lint has no new errors.
- [ ] Device D1 is either run (results pasted) or marked DEVICE-UNPROVEN.
