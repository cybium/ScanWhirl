# 62 — Engine card index and runway (Parts B and C)

Card files live in `63_cards/`. Decisions and code facts (F1–F24) are in `61_ENGINE_DECISIONS.md`. Invariants
I1–I12 are in `20_ARCHITECTURE_PLAN.md` §4. These cards **supersede** the engine parts of PM-0.2, PM-0.3, PM-2.1,
PM-2.2, PM-2.3, PM-3.1 (protect/unlock) and PM-3.2 in `40_SONNET_TASKS.md`. The UI and product parts of those cards
(Result screen, picker, PDF Tools home, Password setup screen) still stand and build on these.

---

## 1. Rules for every card (read once)

- One card per session. Read only the card, the F-rows it cites, and the code it names.
- **Red first.** Write the card's red test, then run only that class on Linux and record the failure message.
  It must match the card's stated message (allow minor wording drift; the *reason* must match). Then implement.
- Never `@Ignore`, delete or loosen an existing test. A card that must change an existing test lists it by
  `file:line` and cites the production line that changed. Any other existing test that turns red means the card is wrong. Stop and report.
- Fault seams: `SaveFaults`, `OpFaults` (added in PM-0.2b), `ScanRepository.trashFault/importFault/usableSpace`,
  `Durable.beforeFileSync/dirSyncer/renameSeam`. Simulated death = `throw SimulatedProcessDeath(step)` (an `Error`). So
  production cleanup must stay `catch (e: Exception)`, **never** `catch (t: Throwable)`, in every new publish path.
- Every `@After` calls `Faults.resetAll()` (test helper from PM-0.2b).
- New user-visible strings go in `app/src/main/res/values/strings.xml` and `values-hi/strings.xml`. Each card gives
  the English and a Hindi draft. Prefix the Hindi value with `<!-- review -->`, as existing entries do (`values-hi/strings.xml:165`).
- Do not touch `app/build.gradle.kts`, the manifest, versions, signing or `keystore/`. Commit locally only after Gate G is fully green; never push.

## 2. Gate G (run in this order, one task at a time, foreground)

```powershell
.\gradlew.bat :app:compileDebugKotlin -PtestBuild --no-daemon
.\gradlew.bat :app:compileDebugUnitTestKotlin -PtestBuild --no-daemon
wsl -d Ubuntu bash -lc 'cd /mnt/c/Users/Cybium/Desktop/ScanWhirl && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon --continue'
wsl -d Ubuntu bash -lc 'cd /mnt/c/Users/Cybium/Desktop/ScanWhirl && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon --continue'
.\gradlew.bat lintDebug -PtestBuild --no-daemon
```

- Both Linux runs must report the card's **expected total, 0 failures, 0 skipped**. Count with
  `app/build/test-results/testDebugUnitTest/*.xml` (`tests`, `failures`, `errors`, `skipped` attributes).
- A failure in only one of the two runs is a flake. Record it, rerun once more, and never "fix" it in this card unless it is in this card's tests.
- To run one class on Linux, add `--tests 'com.scanwhirl.app.store.OpPublishTest'` to the same command.
- Lint must report **0 errors**. The baseline after PM-0.2b is 0 errors, 316 warnings, and new warnings must not come from the card's own files. Run lint with `--rerun-tasks`, because a cached lint result can be stale.
- Run the second Linux suite with `--rerun-tasks -x lint`. An identical command is skipped as up-to-date.

**Progress:** PM-0.2b done (`db454bb`, 814 tests). Next: PM-3.1b.

---

## 3. Runway (dependency order)

**Changes from the order requested** (0.2b → 2.1b → 2.2b → 2.3 → 3.1b → 3.2b):

- **3.1b moves to second**, for three reasons:
  1. Plaintext leaks from locked documents exist today (F7, F13).
  2. 2.1b's tool migration needs the locked-source policy, which 3.1b defines.
  3. The `PdfGenerator` choke protects every later card.
- **3.1c is split out** of 3.1b (repair UI).
- **2.1b, 2.2b and 2.3** are each split engine/UI so each fits one session.

| Step | Card | Size | New tests | Depends on | Touches (shared) | Gate before next | Expected total |
|---|---|---|---|---|---|---|---|
| 1 | `PM-0.2b_FAULTS` | S | 7 | — | new test helpers, `store/OpFaults.kt` | G | **814** |
| 2 | `PM-3.1b_LOCK_GUARD` | L (split A/B) | 11 | 1 | mutators, `regeneratePdf`, protect/unlock, `PdfGenerator`, `Durable.copyFile`, LockFlow, new `OpStaging` | G (+ D1 when H1 allows) | **825** |
| 3 | `PM-3.1c_REPAIR` | M | 6 | 2 | `PdfProtection.state`, reader, share/save sites, AutoExport | G | **831** |
| 4 | `PM-2.1b_PUBLISH` | M | 9 | 2 | `buildAndPublish`, `writePdf` split, merge | G | **840** |
| 5 | `PM-2.1c_TOOLS` | M (split a/b) | 8 | 4 | extract, compress, create, duplicate, mergePdfUris; `buildAndPublish(copyPdf)` | G (+ D3) | **848** |
| 6 | `PM-2.2b_DRAFT` | L (split A/B) | 11 | 4 | `requireEditable`, `protectPdf` head, `moveToTrash`, `recoverOperations`, `buildAndPublish(seal)`, `OcrStore.putAll`, new `DraftStore` | G | **859** |
| 7 | `PM-2.2c_ORGANIZE_UI` | L | 6 | 6 | `DocumentViewerActivity`, `TrashActivity`, `extractedName` | G (+ D2 part 1) | **865** |
| 8 | `PM-2.3b_PAGE_EDITOR` | M | 5 | 7 | `PageViewerActivity`, `draftCrop/draftFilter` | G (+ D2 part 2) | **870** |
| 9 | `PM-3.2b_ENCRYPT` | M | 6 | 3 | `PdfEncryptor`, `PdfVerifier`, `encryptInto` core, `buildAndPublish(copyPdf)` branch | G (+ D4) | **876** |

- **Totals.** They assume this exact order. If a parallel-capable card runs earlier (§4), the expected total is
  807 + the "New tests" of every finished card. Each card also states its alternative total.
- **Release rule.** PM-2.2c and PM-2.3b ship together; never build a release APK between them. In that window the
  full-page viewer still edits in place when no draft exists.

## 4. What may run in parallel, and what must be serial

Parallel means "either order, in separate sessions". It never means two sessions editing one worktree at once.

- **Strictly serial (shared publish primitive or shared functions):** 1 → 2 → 4 → 6 → 7 → 8.
  - 4 creates `buildAndPublish`.
  - 6 extends it (seal, Replace) and `recoverOperations`.
  - 7 and 8 consume the draft API.
- **5 ∥ 6.** Both need 4. Their function sets are disjoint (5: extract/compress/create/duplicate/mergePdfUris;
  6: mutators/moveToTrash/recovery/drafts). Both add lines to `ScanRepository.kt`, so merge carefully.
- **3 ∥ 4.** Both need 2. Disjoint functions; 3 touches `MainActivity.kt:1430`, 4 touches nothing in `MainActivity`.
- **9 ∥ 4–8.** Needs 3 only. It touches `PdfEncryptor`, `PdfVerifier` and the private `encryptInto` core. The only
  2.x overlaps are a one-line draft check at the top of `protectPdf` (2.2b) and the `copyPdf` branch (2.1c), and
  both are independent of 3.2b's edits.
- **Do not start 4 before 2** (it reuses `OpStaging` and the locked-source refusal lines).
  **Do not start 7 before 6, or 8 before 7.**

## 5. Shared-function ledger (same function, more than one card: apply in this order)

| Function (file) | Card order and what each does |
|---|---|
| `requireUnlocked(dir)`, `requireEditable(dir)` (ScanRepository, new) | 3.1b creates both: `requireEditable` = `requireUnlocked`; `regeneratePdf` uses `requireUnlocked`. → 2.2b adds the draft-lease check to `requireEditable` **only**. |
| `deletePage/rotatePage/cropPage/movePage/setPageFilter` (`:1151-1297`) | 3.1b inserts `requireEditable(dir)` as the first statement. No later card edits these bodies. |
| `mergeDocuments` (`:1062-1103`) | 3.1b adds the locked-source refusal at the top → 2.1b replaces the body below that refusal. |
| `extractPages` (`:1040-1060`) | 3.1b refusal → 2.1c body → 2.2c moves the name formula into `extractedName(...)`. |
| `compressScan` (`:1349-1359`) | 3.1b refusal → 2.1c body. |
| `writePdf` (`:1672-1687`) | 2.1b adds `writePdfInto(dir, out, geo)`; `writePdf` delegates. 3.1b's callers are unchanged. |
| `buildAndPublish` (ScanRepository, new) | 2.1b creates it → 2.1c adds `copyPdf` → 2.2b adds `seal` and Replace → 3.2b adds `verifyEncrypted` in the `copyPdf` encrypted branch. 2.1c and 2.2b may land in either order; the parameters are independent. |
| `protectPdf` (`:1495-1518`) and its private core `encryptInto` | 3.1b rewrites → 3.1c's `repairProtection` calls the core → 2.2b adds one draft check at the top of `protectPdf` → 3.2b moves verification inside the core (after the `protect:encrypted` fault point). |
| `unlockPdf` (`:1524-1532`), `changePdfPassword` (new) | 3.1b only. |
| `moveToTrash` (`:847-876`), `TrashedDocument`, `trashedIn` | 2.2b only. It extracts `trashUnderLocks(...)` with identical behaviour (`TrashTest` unedited) and adds `previousToken`. |
| `recoverOperations` (ScanRepository, new) | 3.1b creates it (sweep) → 2.2b adds roll-forward, `.publish` finish and draft cleanup **before** the sweep. |
| `OpStaging` (`store/OpStaging.kt`, new) | 3.1b creates `create/release/forget/sweep/resetForTests` → 2.2b adds the seal read/write. |
| `Durable.copyFile` (new) | 3.1b creates it; 2.1b, 2.1c, 2.2b and 2.3b use it. |
| `OcrStore.putAll` (new) | 2.2b only. |
| `MainActivity` startup block (`:403-411`) | 3.1b adds `repo.recoverOperations()` → 2.2b adds a list refresh when anything rolled forward. |
| `DocumentViewerActivity` | 3.1b (`unlockCurrentPdf` `:659-681`, More row `:612-616`) → 3.1c (share `:135`, save `:503`) → 2.2c (page operations, back, snapshot removal). |
| `PageViewerActivity` | 2.3b only. |
| `PdfVerifier`, `PdfEncryptor` | 3.2b only (`verify` stays untouched). |

---

## 6. BLOCKED-ON-HUMAN: answered by the owner on 2026-11-14

| ID | Owner answer | Lead-engineer decision |
|---|---|---|
| H1 | Full permission (wipe test-app data; emulators allowed) | Use the phone (`103223137I112880`) as the primary device. Install a debug build (uninstall `com.scanwhirl.app.test` first, since a different key blocks an in-place update). Secondary: AVD `sw_api24` for "must not crash" and `Medium_Phone_API_37.0`. The owner taps short plain-language checklists; adb does the file checks. |
| H2 | Approved | PM-1.5 is scheduled in Release 2. The lead engineer writes its card in this pack's format first. |
| H3 | Lead engineer decides | **Dropped.** Wiping test data (H1) erases the folders anyway, and PM-2.2c leaves old snapshots untouched. No notice card. |
| H4 | Sonnet generates the 300-page PDF | It is generated in-app during the D4 run. |
| H5 | Lead engineer decides | **Skipped.** The design does not depend on it (F2). |
| H6 | Locked must mean protected by default | **Blocked** is the default: a document marked locked whose PDF is not protected cannot be shared or saved until it is repaired or unlocked with the password. |

**Releases** (each ends with a version bump, a release build, a device checklist, and the owner publishing):

1. **Lock safety:** PM-0.2b, PM-3.1b, PM-3.1c.
2. **Safe tools + offline:** PM-2.1b, PM-2.1c, PM-1.5.
3. **Organize without risk:** PM-2.2b, PM-2.2c, PM-2.3b.
4. **Large locked PDFs:** PM-3.2b.

The original questions are kept below for the record.

| ID | Blocks | Exact question for the owner |
|---|---|---|
| H1 | Every DEVICE step (D1–D4), INV-6, INV-9 | "May Sonnet install a **debuggable** build on the Infinix by wiping `com.scanwhirl.app.test` data (all test documents are lost unless you export them first), or will you provide an API 34 emulator instead?" |
| H2 | PM-1.5 (manifest) | "PM-1.5 removes `INTERNET` and `ACCESS_NETWORK_STATE` with `tools:node="remove"` and adds the install-time OCR model declaration. Do you approve that manifest change now, and will you run the airplane-mode scan + OCR check on the phone afterwards?" |
| H3 | Snapshot-notice follow-up (F20) | "After a debuggable install, can you run `adb shell run-as com.scanwhirl.app.test ls files` and send the list of `edit_snapshot_*` folders, so we know whether a 'restore old edit backup' notice is worth building?" |
| H4 | 300-page device timing (plan §6) | "Please create a 300-page document on the phone (Import a 300-page PDF), then time Protect, Compress and Organize-save with a stopwatch on the D4 build. Do you have such a PDF, or should Sonnet generate one with the app?" |
| H5 | G38 (directory-fsync reality) | "Optional: allow a one-off debug build that logs whether `Os.fsync` on a directory succeeds on the Infinix? The design does not depend on it; it would only tighten the honesty note." |
| H6 | PM-3.1c dialog wording and share block | "If a document says 'Locked' but its PDF is not protected, should Share be **blocked** until you repair it or remove the lock (recommended), or allowed with a warning?" Default if unanswered: blocked. |

**Device checks referenced by cards.** All are DEVICE, need H1, and are never claimed without being measured.

- **D1** (after 3.1b): protect → rotate refused → Unlock asks for the password → wrong password shows an error → Share opens only with the password.
- **D2** (after 2.2c and 2.3b): Organize edit → force-stop → relaunch → draft intact, base unchanged → Discard / Save copy / Replace.
- **D3** (after 2.1c): merge of two device PDFs, and compress of a 30-page document force-stopped mid-way → no partial document.
- **D4** (after 3.2b): protect a 100+ page document in under 10 s; open it in another PDF app with and without the password.

---

## 7. Follow-ups this pack creates (not cards yet)

- OCR carry-over for extract and merge (re-indexing works today; carry-over only saves time).
- Result screen, Undo for Replace, Recent list. These are PM-2.1 (rest) in `40_SONNET_TASKS.md`, and build on `buildAndPublish`.
- Callers of the derived tools calling `indexer.enqueue(id)`. Today `ocr_pending` is only picked up by `resumePending` (`OcrIndexer.kt:83`).
- Locked documents in Organize and in tools (PM-3.1 v2). Needs 3.2b.
- The `edit_snapshot_*` notice (needs H3).
- `README.md` in this folder still says the folder holds seven files. Update it when these docs are adopted.

## 8. Start prompt for Sonnet (paste per card)

```text
You implement exactly one card: docs/plans/pdf-manager/63_cards/<CARD>.md. Read it fully, then only the files it
names (use Select-String to re-confirm every line number before editing; the tree may have moved). Rules:
docs/plans/pdf-manager/62_ENGINE_CARD_INDEX.md §1-2. Write the red test first, run it alone on Linux, paste the
failure message, then implement. Finish with Gate G (both Linux runs) and the card's Definition of Done checklist,
each item answered yes/no with evidence. Do not commit. If anything in the card contradicts the code, stop and
report the contradiction instead of improvising.
```
