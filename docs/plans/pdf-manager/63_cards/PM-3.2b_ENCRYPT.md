# PM-3.2b — Streaming RC4 encryption with an encrypted-mode verifier

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Decision A4: `61_ENGINE_DECISIONS.md` §2. Needs PM-3.1c.
May run in parallel with PM-2.1b…2.3b (it touches no function they touch).

## 1. Goal

`PdfEncryptor.encrypt` streams with bounded buffers and produces **byte-identical** output to today's implementation.
Protect, Change password and Repair verify the encrypted file **with the password** before it can replace `scan.pdf`.

**User-visible effect.**
- Protecting large documents no longer risks running out of memory.
- A corrupted encryption result is never published.
- No wording or format changes.

## 2. Invariants established

- **I3** "`locked.meta` exists ⇔ `scan.pdf` contains `/Encrypt`." It is now backed by a cryptographic check: every image decrypts to its page file.
- **I4** "No visible document lacks a verified `scan.pdf`" for locked documents. Previously only a trailer check covered them (PM-3.1b).
- **I1** for locked documents: page-tree `/Count` equals the page files (via `verifyEncrypted`).

## 3. Files to touch

| File | Function | Change |
|---|---|---|
| `app/src/test/java/com/scanwhirl/app/util/LegacyPdfEncryptor.kt` (new, **first step**) | the whole current `PdfEncryptor.kt`, renamed `object LegacyPdfEncryptor` | The frozen oracle. Copy it verbatim **before** any production edit. Add only an `encrypt(src, dst, pw, fileId: ByteArray)` overload in which `newFileId()` is replaced by the parameter. |
| `util/PdfEncryptor.kt` | `encrypt(src, dst, userPassword)` `:33-55` | Becomes `= encrypt(src, dst, userPassword, newFileId())`. New `internal fun encrypt(src, dst, userPassword, fileId: ByteArray)` streams (§5.1). |
| | `rc4` `:61-79`, `arc4Into` `:82-85` | Replaced by `internal class Rc4(key: ByteArray) { fun process(buf: ByteArray, off: Int, len: Int) }`. Same KSA/PRGA as `:62-77`, but the `i`, `j` and `S` state persist across calls. Keep a private one-shot `rc4(key, data) = data.copyOf().also { Rc4(key).process(it, 0, it.size) }` for the `/O` and `/U` computations (`:107-153`). |
| | `objectKey` `:87-97`, `computeFileKey` `:119-139`, `computeUserEntry` `:142-153`, `pad` `:155-159` | Make them `internal` (bodies unchanged), for `PdfVerifier`. |
| | `objectStreamBodies` `:187-248`, `replaceTrailer` `:255-270` | **Delete** from production. Both build whole-file strings (F14). `LegacyPdfEncryptor` keeps them for tests. |
| `util/PdfVerifier.kt` | new `fun verifyEncrypted(file: File, password: String?, expectedPages: Int? = null, expectedImages: List<File>? = null)` | §5.2. `verify` is unchanged. |
| `store/ScanRepository.kt` | `encryptInto` (PM-3.1b §5.2 steps 3–4) | Delete the step-3 line `if (PdfProtection.isEncrypted(enc) != true) throw …`. **Immediately after** `OpFaults.at("protect:encrypted")` (and the `chpw:` equivalent), insert `PdfVerifier.verifyEncrypted(enc, password, expectedImages = doc.pageFiles.takeIf { it.isNotEmpty() }, expectedPages = if (doc.pageFiles.isEmpty()) null else doc.pageFiles.size)`. The position is deliberate: test 4 corrupts the file at that fault point, so verification must come after it and before the lock record is written. This covers protect, change password and repair, since all three call the core. |
| | `buildAndPublish(copyPdf = true)` encrypted branch (PM-2.1c §5.1), **if PM-2.1c has landed** | Add `PdfVerifier.verifyEncrypted(pdf, null, expectedPages = pages.size.takeIf { it > 0 })` after the `locked.meta` check. |
| `app/src/test/java/com/scanwhirl/app/util/PdfEncryptorTest.kt` | `:165`, `:209`, `:263` | `PdfEncryptor.objectStreamBodies(` → `LegacyPdfEncryptor.objectStreamBodies(`. **Reason:** the production function is deleted because it is whole-file (`PdfEncryptor.kt:188`). The identical code lives in the frozen oracle. The tests still decrypt the **production** output, so their meaning is unchanged. |

## 4. Red tests first

Class `app/src/test/java/com/scanwhirl/app/util/EncryptStreamTest.kt` (Robolectric, sdk 35, `@After Faults.resetAll()`).
**Step 0** adds `LegacyPdfEncryptor` and the `fileId` overload to the *current* `PdfEncryptor` (a pure refactor). Only then are the tests written.

| # | Test name | Steps and assertions | Must fail before the rewrite with |
|---|---|---|---|
| 1 | `encryptor never loads a whole file` | Read `File("src/main/java/com/scanwhirl/app/util/PdfEncryptor.kt")`. Offenders: occurrences of `readBytes()`, `writeBytes(`, `String(raw`, `ByteArrayOutputStream`. | `PdfEncryptor loads a whole file: [readBytes(), writeBytes(, String(raw]` (`:35`, `:53`, `:188`) |
| 2 | `streaming output is byte identical to the frozen legacy encryptor for a fixed file id` | `id = ByteArray(16) { it.toByte() }`. Fixtures: 1 page; 3 pages with a text layer (`PdfEncryptorTest.kt:237-243` style); 30 pages. For each: `LegacyPdfEncryptor.encrypt(src, a, "pw1234", id)` and `PdfEncryptor.encrypt(src, b, "pw1234", id)`, then `assertArrayEquals(a.readBytes(), b.readBytes())`. Reading whole files is fine in **test** code. | green before and after (the oracle); it must stay green through the rewrite |
| 3 | `encrypted verify accepts our output and rejects a corrupted stream or a wrong password` | `verifyEncrypted(enc, "pw1234", expectedImages = pages)` passes. A copy with one byte flipped in the middle of image 0's body throws `InvalidPdfException`. A wrong password throws `InvalidPdfException("Wrong password")`. `PdfVerifier.verify(enc)` still throws (unchanged). | stub `verifyEncrypted { }` → `a corrupted encrypted stream must be rejected` |
| 4 | `protect never publishes a corrupted encrypted PDF` | Plain doc, `before = Sha256(scan.pdf)`. `OpFaults.hook = { if (it == "protect:encrypted") flipMiddleByte(the single op_staging/*/enc.pdf) }`. `runCatching { protectPdf(id, "1234") }` must fail; `Sha256(scan.pdf) == before`; no `locked.meta`; after a new process, `op_staging` is empty. | `a corrupted encrypted PDF was published` (PM-3.1b checks only the trailer) |
| 5 | `three hundred page protect completes and verifies` | `DocFixtures.saved(pages = 300)`; `protectPdf(id, "1234")`; `assertI3`; `verifyEncrypted(scan.pdf, "1234", expectedImages = pageFiles)`. | green (completion; the bound is proven by test 1 plus a device run) |
| 6 | `disk full during encrypt leaves scan pdf untouched and no temp` | `failAtFileSync("enc.pdf")`; protect fails; the `scan.pdf` sha is unchanged; no `locked.meta`; `op_staging` is empty without recovery. | green (characterisation) |

## 5. Implementation notes

### 5.1 Streaming `encrypt(src, dst, pw, fileId)`

Use one `RandomAccessFile(src, "r")` and one `BufferedOutputStream(FileOutputStream(dst), 64 * 1024)`. `buf = ByteArray(64 * 1024)`.

1. **Tail.** Read the last `min(size, 64)` bytes; `Regex("startxref\\n(\\d{1,15})\\n%%EOF\\n\\z")` gives `xrefOffset`. Otherwise throw `IOException("Not a ScanWhirl PDF")`.
2. **Xref.**
   - Read ≤ 64 bytes at `xrefOffset` and match `^xref\n0 (\d{1,9})\n`. Refuse `count > 200_000`, since the 500-page cap gives 1,503 (`PdfImport.kt:38`).
   - Read `count * 20` bytes and parse offsets exactly as legacy (`:211-222`: flag at index 17, `'n'` only, skip object 0) into a `LongArray`.
   - `trailerStart = xrefOffset + header.length + count * 20`.
3. **Streams.**
   - Sort `(objNum, offset)` by offset; each region ends at the next offset, and the last at `xrefOffset` (legacy `:224-231`).
   - For each region, read `head = min(2048, regionLen)` bytes as ISO-8859-1 and find `"\nstream\n"`. Skip the region if it is absent.
   - `/Length` comes from `Regex("/Length\\s*(\\d+)")` on the text before it (legacy `:239-241`).
   - `dataStart = regionStart + idx + 8`. Collect `(objNum, dataStart, length)`.
4. **Copy and encrypt.**
   - `pos = 0`. For each stream in `dataStart` order: copy `[pos, dataStart)` verbatim; then RC4 `[dataStart, dataStart + length)` chunk by chunk with **one** `Rc4(objectKey(fileKey, objNum))` per stream; then `pos = dataStart + length`.
   - Finally copy `[pos, trailerStart)` verbatim.
5. **Trailer.**
   - Read `[trailerStart, size)` (≤ 1024 bytes, per `PdfVerifier.kt:177`) as text.
   - Apply legacy `replaceTrailer`'s transformation to that text alone: `kept` = the text between the first `<<` and the following `>>`, then append the identical `/Encrypt … /ID …` string.
   - Write it as ISO-8859-1.
6. **Finish.** `Durable.syncStream(fileOut, dst)` (the inner `FileOutputStream`, after flushing the buffered stream), then close. On any exception, delete `dst` and rethrow.

`/O`, `/U` and the file key are computed exactly as legacy `:37-43`.
**Memory:** `buf` (64 KiB), the head (2 KiB), the `LongArray` (8 × objects), and the trailer (≤ 1 KiB). There is no whole-file allocation.

### 5.2 `PdfVerifier.verifyEncrypted`

Add a nullable `crypt: Crypt?` (file key) parameter to the private `Checker`. `verify` passes `null` and keeps today's behaviour.

- **Trailer.** In encrypted mode, `readTrailer` (`:175-189`) accepts `^trailer\n<<(.*)>>\nstartxref\n(\d+)\n%%EOF\n\z` (DOTALL, ≤ 1024 bytes).
  - It requires `/Encrypt << /Filter /Standard /V 2 /R 3 /Length 128 /O <([0-9A-F]{64})> /U <([0-9A-F]{64})> /P (-?\d+) >>` and `/ID [<([0-9A-F]{32})> <\1>]`.
  - `/Size` and `/Root` are checked as today.
- **Password** (when non-null): `fileKey = computeFileKey(pad(pw ISO-8859-1), O, P, ID)`. Then `computeUserEntry(fileKey, ID)` must equal `/U` in its first 16 bytes, otherwise `fail("Wrong password")`. When the password is null: structure only. Skip the content, SOI and image checks.
- **Per page** (with a key):
  - decrypt the content stream (≤ `MAX_CONTENT_STREAM`) with `Rc4(objectKey(key, contentNum))` and require `/Im$index Do`;
  - decrypt image bytes 0–1 with a fresh `Rc4(objectKey(key, imageNum))` and require `FF D8`;
  - when `expectedImages` is given, `compareImage` decrypts each 64 KiB chunk with **one** `Rc4` per image (state carried across chunks) before comparing.

## 6. Acceptance

1. Gate G. Expected Linux total: **870 + 6 = 876** in the index order (if it runs right after PM-3.1c: 831 + 6 = 837), 0 / 0, on both runs.
2. `PdfEncryptorTest` passes with only the three listed line edits. `LockGuardTest`, `ProtectionStateTest` and `UiSmokeTest:1493-1614` pass unedited.
3. **DEVICE D4** (needs H1, H4):
   - protect a 100+ page document and time it (target < 10 s);
   - share it to two other PDF apps (Drive viewer, plus Adobe or the Files viewer): the password `1234` opens it, and cancelling the prompt does not;
   - record the timings and app names in `docs/audit/…/PDF_TOOLS_STATUS.md` under a new dated section.

   Until then: **DEVICE-UNPROVEN**.

## 7. What not to do

- Do not change the algorithm, permissions, padding, file-ID length or trailer text. Test 2 must stay byte-identical.
- Do not use JCA `ARC4` (G20), and do not encrypt during generation (G22).
- Do not restart RC4 per chunk. One cipher state per stream object is the PDF rule.
- Do not edit `LegacyPdfEncryptor` after step 0. It is the oracle.

## 8. Rollback

Revert. Output is byte-identical in format, so documents protected under either version read the same.

## 9. Size

M. One session.

## 10. Definition of done

- [ ] `LegacyPdfEncryptor.kt` (test) equals the pre-card `PdfEncryptor.kt` apart from its name and the `fileId` overload. The reviewer diffs it against `git show HEAD:app/src/main/java/com/scanwhirl/app/util/PdfEncryptor.kt`.
- [ ] Test 1's offender list is empty, and its red output (`:35`, `:53`, `:188`) is pasted.
- [ ] Test 2 was green before **and** after the rewrite (both outputs pasted).
- [ ] Two Linux runs: 876 (or 837) / 0 / 0. Lint has no new errors. D4 is run or marked DEVICE-UNPROVEN.
