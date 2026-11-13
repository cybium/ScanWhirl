# PM-0.2b — Fault and invariant harness

Rules and Gate G: `62_ENGINE_CARD_INDEX.md` §1-2. Facts F#: `61_ENGINE_DECISIONS.md` §0.

## 1. Goal

Give every later card one shared set of test helpers. They assert the invariants on disk, inject faults at named
steps, and simulate process death. They also add the production seam object `OpFaults`.
**User-visible effect: none.**

## 2. Invariants it makes checkable (from `20_ARCHITECTURE_PLAN.md` §4)

- **I1** "count of page files = `/Count` of `scan.pdf` … = the Result screen and row count."
- **I3** "`locked.meta` exists ⇔ `scan.pdf` contains `/Encrypt`."
- **I4** "No visible document lacks a verified `scan.pdf`. Nothing is published before verification."
- **I5** "every source directory is byte-identical (recursive digest before and after)."
- **I6** "Failure, cancel or simulated death at any stage leaves no visible partial document."
- **I10** "Every new document is either fully indexed or carries `ocr_pending`."

This card proves only that the helpers detect violations. The owning cards fix the violations.

## 3. Files to touch

| File | What | Why |
|---|---|---|
| `app/src/main/java/com/scanwhirl/app/store/OpFaults.kt` (new) | `internal object OpFaults { @Volatile var hook: (String) -> Unit = {}; fun at(step: String) = hook(step); fun reset() { hook = {} } }` with a KDoc naming PM-3.1b / 2.1b / 2.2b as the callers | The step seam for every tool operation. It mirrors `SaveFaults` (`SaveTransaction.kt:191-195`). |
| `app/src/test/java/com/scanwhirl/app/store/Faults.kt` (new) | `object Faults` (spec below) | One reset and injection point for every seam (F4). |
| `app/src/test/java/com/scanwhirl/app/store/DocFixtures.kt` (new) | `object DocFixtures` (spec below) | Real documents created through the transactional save. |
| `app/src/test/java/com/scanwhirl/app/store/PdfInvariants.kt` (new) | `object PdfInvariants` (spec below) | The assertions every later card uses. |
| `app/src/test/java/com/scanwhirl/app/store/PdfInvariantsTest.kt` (new) | 7 tests (§4) | Proves the helpers detect violations. |

No other file changes.

### `Faults` (test)

- `fun resetAll()` calls `SaveFaults.reset()`, `OpFaults.reset()`, `ScanRepository.resetTestSeams()` and
  `Durable.resetForTests()`. PM-3.1b adds `OpStaging.resetForTests()` here.
- `class Probe { @Volatile var fired = false }`.
- `fun dieAtFileSync(name: String): Probe` sets `Durable.beforeFileSync = { f -> if (f.name == name) { probe.fired = true; throw SimulatedProcessDeath("sync:$name") } }`.
- `fun failAtFileSync(name: String): Probe`: the same, but it throws `java.io.IOException("injected failure at $name")`.
- `fun dieAtOp(step: String): Probe` and `fun failAtOp(step: String): Probe`: the same idea via `OpFaults.hook`, matching `step` exactly.

### `DocFixtures` (test)

- `fun saved(repo: ScanRepository, app: Application, pages: Int = 3, name: String = "Doc"): String`
  1. For each page `i`, write `ImportFixtures.image(width = 200 + 20 * i, height = 300)` to
     `File(app.cacheDir, "fx_${UUID}/p$i.jpg")`. Each page then has a different size, so a page swap is detectable.
  2. Return `runBlocking { repo.saveScan(files.map { Uri.fromFile(it) }, null, name) }.id`.
     A `file:` URI is supported (`ScanRepository.kt:741-745`).
- `fun locked(repo, app, password: String = "1234", pages: Int = 3): String` = `saved(...)`, then
  `runBlocking { repo.protectPdf(id, password) }`.
- `fun withOcr(repo: ScanRepository, id: String)`: for every page file, call `repo.ocrStore.putPage(id, file.name, "text ${file.name}", OcrPage(w, h, listOf(OcrLine("text", 1, 1, 10, 10))))`.
  Get `w,h` from `PageReplace.bounds(file)`. Check the constructor shape in `model/OcrPage.kt` and `OcrLine` before writing; `PdfEncryptorTest.kt:237-241` shows it.

### `PdfInvariants` (test)

- `fun dirDigest(dir: File): String`
  - Walk recursively with `walkTopDown()`. For every entry, take the relative path with `/` separators.
  - Directories contribute `"D <path>"`, files contribute `"F <path> <length> <Sha256.of(file)>"`.
  - Sort the lines and return `Sha256.of(lines.joinToString("\n").toByteArray())`. A missing `dir` returns `"absent"`.
- `fun trailerEncrypted(pdf: File): Boolean?`
  1. Read the last `min(4096, length)` bytes with `RandomAccessFile` as ISO-8859-1.
  2. Find the **last** `"\ntrailer\n<<"` and return null if there is none.
  3. Return whether the text from there to the following `"\nstartxref\n"` contains `"/Encrypt"`. This is valid for generator output (F17).
- `fun pageTreeCount(pdf: File): Int`
  - Read the tail for `startxref\n(\d+)`. At that offset, parse `xref\n0 N\n`, then the 20-byte entry for **object 2** (the page tree, `PdfGenerator.kt:108-109`).
  - Read up to 1 MiB from that object's offset, then `Regex("/Count (\\d+)")`.
  - Page-tree dictionaries are never encrypted, so this works for plain and locked files.
- `fun assertI1(repo, id)`: `pageFiles.size == pageTreeCount(pdf)`. When `trailerEncrypted == false`, also run `PdfVerifier.verify(pdf, expectedImages = pageFiles)`.
- `fun assertI3(repo, id)`: `File(dir, "locked.meta").exists() == (trailerEncrypted(pdf) == true)`, with a message naming both values.
- `fun assertI4(repo)`: for every `repo.listDocuments(includeVaulted = true)`, the PDF is a file with length > 0, then `assertI1`.
- `fun assertI10(repo, id)`: `ocr_pending` exists in the folder, **or** every page name is a key of `repo.ocrDoc(id)`.
- `fun assertNoOrphans(app, repo)`: every directory in `filesDir/documents` either loads (`repo.getDocument(name) != null`)
  or is save-owned (`SaveFormat.ownerOf(dir) != null`). Also, `filesDir/op_staging` is absent or empty.
  On failure, list the offending names.
- `fun assertClean(app, repo)` = `assertI4(repo)` + `assertNoOrphans(app, repo)`.

## 4. Red test first

This card adds infrastructure, so the "red" is a mutation proof.

1. Write the four detection tests (2, 3, 4, 6 below) **first**, against helper stubs whose bodies are empty.
2. Run `PdfInvariantsTest` on Linux. Tests 2, 3, 4 and 6 must fail with `java.lang.AssertionError: expected an invariant violation to be reported`. Use `assertThrows(AssertionError::class.java) { … }`; JUnit's message reads "expected java.lang.AssertionError to be thrown, but nothing was thrown".
3. Implement the helpers. All 7 tests pass.

Tests in `PdfInvariantsTest`: `@RunWith(RobolectricTestRunner::class) @GraphicsMode(NATIVE) @Config(sdk = [35])`, with `@After Faults.resetAll()`.

1. `new scan satisfies I1 I3 I4 and I10`: `saved(3)`, then `assertI1`, `assertI3`, `assertI4`, `assertI10` (green today; it pins `ScanRepository.kt:551-581, 732-738`).
2. `I4 check rejects a visible document without scan pdf`: delete `scan.pdf`. The document stays visible (`:154`), so `assertI4` must throw.
3. `I1 check rejects a PDF whose Count differs from the page files`: delete `page_002.jpg`, then `assertI1` must throw.
4. `I3 check rejects locked meta beside a plain PDF`: write `locked.meta` with text `"x"` into an unlocked document and `assertI3` must throw. Then take a `locked(...)` document: `assertI3` passes and `trailerEncrypted == true`. This pins the F7 failure shape.
5. `dir digest changes on one flipped byte and on an added hidden file`: flip byte 100 of `page_000.jpg` and the digest changes. Restore it and the digest is equal again. Add `.x.swtmp` and the digest changes.
6. `orphan check flags a metadata-less directory under documents`:
   - `documents/deadbeef/page_000.jpg` must make `assertNoOrphans` throw.
   - A directory carrying a `SaveFormat.createMarker(dir, SaveFormat.newToken(), now)` marker must be accepted.
7. `faults fire once and resetAll restores every seam`:
   - `dieAtFileSync("meta.txt.tmp")`, then `repo.renameDocument(id, "x")` throws `SimulatedProcessDeath`, and `probe.fired` is true.
   - `Faults.resetAll()`, then the rename succeeds.
   - Also: `OpFaults.at("anything")` does not throw after a reset.

## 5. Implementation notes

- **Robolectric heap.** Keep fixtures small (≤ 400 px).
- **Stub idiom.** Use only `org.junit.Assert`; no new test libraries.
- **Shape tolerance.** `pageTreeCount` must throw `AssertionError` with a clear message when it cannot find the shape. It must not return 0.
- **No production calls to `OpFaults` in this card.** It is only referenced from `Faults`.

## 6. Acceptance

Gate G (`62` §2), HOST only. Expected Linux total: **807 + 7 = 814**, 0 failures, 0 skipped, on both runs. No DEVICE step.

## 7. What not to do

- Do not wire `OpFaults.at(...)` into production code here.
- Do not "fix" any invariant violation the helpers reveal; report it to the owning card.
- Do not add test dependencies, a `TestRule` class hierarchy, or a base test class.

## 8. Rollback

Delete the five new files. Nothing else changed.

## 9. Size

S. One session.

## 10. Definition of done (reviewer checks without the diff)

- [ ] `store/OpFaults.kt` exists in main and has **no** production callers (`Select-String -Path app/src/main -Pattern 'OpFaults.at'` returns nothing).
- [ ] The test sources contain `Faults.kt`, `DocFixtures.kt`, `PdfInvariants.kt` and `PdfInvariantsTest.kt`.
- [ ] The red run's output shows tests 2, 3, 4 and 6 failing against stubs, pasted in the session report.
- [ ] Two Linux runs: 814 tests, 0 failures, 0 skipped.
- [ ] Lint has no new errors.
