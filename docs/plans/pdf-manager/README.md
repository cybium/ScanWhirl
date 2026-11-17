# ScanWhirl PDF Manager: implementation-ready plan

**Author role:** product architect and planner (documentation only; no source, test, build or dependency changes).
**Date:** 2026-11-13. Owner answers applied the same day.
**Tree reviewed:** `clean-workspace-2026-11-13` @ `0c283b8`.

## Executive decision (10 lines)

1. ScanWhirl has a **page-image document engine**, not a PDF engine. The plan sells that honestly and adds no PDF parser or SDK.
2. **PDF Tools** is a new activity behind Home's **Tools** button. It replaces the Tools sheet. Home stays the library.
3. v1 has **12 tools in 5 groups**. Long image returns as a safe export option. The first slice is 3 screens: PDF Tools, Document picker, Reader dock.
4. **Organize uses a persistent draft.** The library is never edited in place. *Save as new copy* is primary; *Replace* goes via Trash; *Discard* always works. This is the D1/D2 fix.
5. **Every tool output** goes through one staging → verify → atomic-publish path. It ends on a Result screen with Undo via Trash.
6. **Imported PDFs keep their original file.** Share sends the original until an edited copy exists.
7. **Locked stays locked.** The badge always matches the file. Tools keep outputs locked. Unlock always needs the password.
8. **Free:** manage, organize, merge, compress, extract, export images and long image, read text, password. **Power Pack:** sign & self-attest, purpose watermark, verified redaction, split, Word (text).
9. **₹100 once,** via Play Billing (owner). The storefront ships after Slice 5. Before that, production shows **no** Power Pack UI, so no dead end can exist.
10. **No internet permission at all** (owner, after the ML Kit review). Hindi OCR is a Free opt-in, gated by a spike. Android 10+ is the device priority.

## Index

| File | What it gives you |
|---|---|
| `00_DECISIONS.md` | Every product, engineering and scope decision: rationale, rejected alternative, risk. The owner-answers table is at the top. |
| `10_PRODUCT_SPEC.md` | IA, screen inventory, ASCII wireframes, button map, states, empty/error cases, accessibility, i18n |
| `20_ARCHITECTURE_PLAN.md` | **§1: verified current state with file:line evidence and today's test run**; data model; pipeline; invariants I1–I12; file ownership; migration; performance; risks |
| `30_FREE_PRO.md` | Exact Free / Power Pack matrix, ₹100 terms, visibility states, upgrade sheet |
| `40_SONNET_TASKS.md` | 32 vertical-slice task cards (PM-0.1 … PM-6.3) plus the approval-gated Slice 7 list |
| `50_OPEN_QUESTIONS.md` | Owner answers, code/device investigations, and the **DeepSeek investigation prompt** |

## Findings that changed the plan (verified on 2026-11-13)

1. **The test baseline is 807 tests, 169 failed** on this Windows workspace, not "101 tests, 3 failures".
   - 111 are Windows file-replace or FileProvider-path errors from the test host.
   - About 28 are stale expectations from the UX pass.
   - The 3 search failures on record pass now.
2. **D1 root cause.**
   - Page edits rewrite the library document immediately.
   - The restore snapshot is taken asynchronously and deleted in `onDestroy`, so a force-stop leaves no restore.
   - "Overwrite" is just `finish()`, and the dialog text is false.
   - Evidence: `DocumentViewerActivity.kt:171–176, 194–204, 796–869`; `strings.xml:119`.
3. **A locked document can lose its encryption on any page edit** (`ScanRepository.kt:1651–1665`). Unlock and Change password skip the password in two places (`LockFlow.kt:46–60`, `DocumentViewerActivity.kt:659–681`). Fixed first, in PM-0.3.
4. **Merge, extract and compress can publish incomplete or never-indexed documents** (`ScanRepository.kt:1093–1100, 1316–1321`).
5. **The old Long image failed by design.** It held every page in memory at once, drew onto a 164 MB ARGB canvas, squashed tall documents to 20,000 px, and stretched mixed-width pages. The new design in E20 / PM-4.4 fixes each problem.
6. **ML Kit models are installed by Google Play services, not the app.** That is why removing `INTERNET` is feasible (PM-1.5). DeepSeek verifies the merged manifest first.

---

## Final handoff

### Exact branch, build and test state

- **Branch:** `clean-workspace-2026-11-13`. **HEAD:** `0c283b8`. The only uncommitted additions are these plan files under `docs/plans/pdf-manager/`.
- **Build:** `versionCode 27`, `1.1.1-test27`, `com.scanwhirl.app.test` with `-PtestBuild`. `minSdk` 24, `targetSdk` 36. Kotlin compiled on 2026-11-13.
- **Tests:** `:app:testDebugUnitTest -PtestBuild` on Windows gives **807 tests, 169 failed**. Classification is in `20_ARCHITECTURE_PLAN.md` §1.7. The last green was at `d69a304` (797 tests, on Linux).
- **Device:** Infinix X6711, **Android 14 / API 34**, adb serial `103223137I112880`. adb is at `C:\Users\Cybium\AppData\Local\Android\Sdk\platform-tools\adb.exe` (not on PATH). Only `com.scanwhirl.app.test` is installed. The device was reset; the old DocX fixture is gone.

### Who goes first: DeepSeek, then Sonnet

1. **DeepSeek 4.1 High** runs the investigation pack DS-1…DS-4 in `50_OPEN_QUESTIONS.md`: the test baseline and classification, the merged-manifest and network audit, and read-only device facts. The prompt is in that file, ready to paste. Output: `docs/audit/2026-11-13/PDF_TOOLS_STATUS.md`.
2. **Sonnet 5.5 High** starts at **PM-0.1b** with that report.

### Sonnet: read these three files first

1. `docs/plans/pdf-manager/00_DECISIONS.md`
2. `docs/plans/pdf-manager/40_SONNET_TASKS.md`
3. `docs/plans/pdf-manager/20_ARCHITECTURE_PLAN.md` (§1, §3–§5)

### First task for Sonnet

**PM-0.1b,** then PM-0.2 and PM-0.3 (lock guards), before any UI work.

### Five decisions not to re-litigate

1. **Page-image engine only.** No PDF parser or SDK. The only new dependencies allowed are `billing-ktx` (PM-5.5) and the Devanagari OCR model (PM-6.3).
2. **Organize is a persistent draft.** Never edit a library document in place (E12).
3. **One publish path** for every output, with Undo via Trash (E2, P9).
4. **The Free/Power Pack boundary as written. Protect is Free. ₹100 once.** No Power Pack UI before PM-5.5 (P4, P5, E21).
5. **Imported PDFs keep `original.pdf`** (E3).

### Three things not to build

1. PDF/A, Office layout conversion (layout Word, Excel, PPT), true PDF text editing, or any PDF engine.
2. Anything that uses the network: cloud, accounts, analytics, ads, Play Integrity, anti-piracy or server verification.
3. Any upgrade or Power Pack UI before PM-5.5, and any dependency or permission beyond the two approved ones.

### Start-here prompt for Sonnet 5.5 High (10 lines)

```
You are implementing ScanWhirl's PDF Tools in C:\Users\Cybium\Desktop\ScanWhirl-HQ\current (branch clean-workspace-2026-11-13).
Read docs/plans/pdf-manager/00_DECISIONS.md, 40_SONNET_TASKS.md, 20_ARCHITECTURE_PLAN.md §1,§3-§5, and docs/audit/2026-11-13/PDF_TOOLS_STATUS.md.
Implement one task card at a time in the card order given, starting with PM-0.1b; do not skip Slice 0.
Each card: re-confirm cited lines with search, change only what the card says, add its tests, keep invariants I1–I12 green.
Gate per card: compileDebugKotlin, the card's tests, the full unit suite on the gate environment from PM-0.1, lintDebug — one at a time, foreground.
Device priority is Android 10+ (owner phone: API 34, adb at C:\Users\Cybium\AppData\Local\Android\Sdk\platform-tools\adb.exe).
No new dependencies or permissions except billing-ktx (PM-5.5) and mlkit devanagari (PM-6.3); strings go in values and values-hi.
Never edit a library document in place, never publish without staging+verify, never show Power Pack UI before PM-5.5.
In PowerShell, never rewrite non-ASCII files with Set-Content/Out-File; use the editor tool. If a card conflicts with code, log it in PDF_TOOLS_STATUS.md and stop.
After each card report files changed, tests added/updated with counts, gate results, and the device acceptance steps for the owner.
```
