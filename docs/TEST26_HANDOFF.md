# ScanWhirl test26 — Session Handoff (read this first in a new session)

> Purpose: everything a fresh agent needs to continue the test26 redesign without this
> conversation. Written 2026-11-07 at HEAD `fb96782`; **revised 2026-11-08 after the owner's G2 review (plan Rev 3)**;
> **G2b reviewed 2026-11-08 → PASSED (Home/Tools/Selection sub-checks) → plan Rev 4 adopted** (the review was the
> audit file `ScanWhirl-G2b-AuditAndNextSteps.md` in Downloads; no screenshots — see §3/§5).
> **Authoritative plan:** `docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md` (Rev 4) — read §0
> (rules), §4–§5 (target UI), §6 (tasks), §7 (tests), §8 (gotchas) before coding.
> **As-run record:** `docs/TEST26_LOG.md` — per-task entries + phase-end tables here.
> Owner reference copies (same content): `/mnt/c/Users/Cybium/Downloads/
> ScanWhirl-test26-{PLAN,LOG,HANDOFF}.md` — re-copy all three after every md edit.

---

## 1. Objective

Execute the test26 redesign "One Home, Real Categories" phase-by-phase (P0–P9, tasks
`T<phase>.<n>`) with strict stop-gates G1–G5: rebuild Home (one screen, first-class
categories, zero clipped text, OCR editor, Earth removed in P7), ship
`versionCode 26` / `1.1.1-test26`. Owner installs each gate APK and sends screenshots
before the next phase starts.

**Current position (2026-11-08): G2b PASSED → plan Rev 4 adopted → build mode underway: T3.14 (`abdbabd`), T4.7 (`e518c75`), T4.8 (`2685ff5`), T4.1 (`b8e40c1`), T4.2 (`bb70f81`) and T4.3 (`9e531ab`, category from Home: card chip → picker, long-press quick-actions sheet [owner decision: replaces T3.7's long-press selection entry — `Select` now enters selection], bulk Move + "Moved N to Study" toast, `HomeCategoryActionsTest` 8 tests red-proofed then green) are committed; suite 294 tests / 2 known-red TextFit. Next work = T4.4 (QuickSortQueue + sheet + X-close) → (T4.5 optional) → T4.6 → phase-end full build → G2c (`alpha2c` + source zip + 3 mds → Downloads → STOP, md-review no screenshots).**

## 2. Locations

| What | Where |
|---|---|
| Project root (git repo) | `/home/cybium/ScanWhirl` (WSL/Ubuntu) |
| Branch / base | `test26-redesign`, from tag `test25-final` @ `1b7113c`. **Never `git push`.** |
| Plan (authoritative, Rev 4) | `docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md` |
| As-run log | `docs/TEST26_LOG.md` |
| This handoff | `docs/TEST26_HANDOFF.md` |
| Blockers file (2 failed attempts → write here + halt) | `docs/BLOCKERS.md` |
| Windows Downloads (deliver APKs here) | `/mnt/c/Users/Cybium/Downloads/` |
| Handoff copies for owner | `/mnt/c/Users/Cybium/Downloads/ScanWhirl-test26-*.md` |
| Main P3 surface | `app/src/main/java/com/scanwhirl/app/ui/MainActivity.kt` (~1,030 lines) |
| Home layout | `app/src/main/res/layout/activity_main.xml` |
| Tools sheet | `app/src/main/res/layout/sheet_tools.xml`, `view_tool_tile.xml`, `res/values/ids.xml` |
| Pure home logic | `app/src/main/java/com/scanwhirl/app/util/HomeFilter.kt` |
| P2 flows (untouched this phase) | `app/src/main/java/com/scanwhirl/app/tools/` (10 files) |
| Tests | `app/src/test/java/com/scanwhirl/app/{ui,util}/` — `UiSmokeTest`, `HomeScreenTest`, `TextFitTest`, `HomeFilterTest`, `StatusLineTest`, `EmojiGuardTest` |

**Build commands** (always export the SDK first):

```bash
cd /home/cybium/ScanWhirl && export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
./gradlew :app:compileDebugKotlin -PtestBuild --no-daemon          # after every task
./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon           # full suite after every task
./gradlew test assembleRelease -PtestBuild --no-daemon --continue  # phase-end full build (--continue: known-reds)
```

Parse results:

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=0; bad=[]
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(p).getroot(); t+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0))
    for tc in r.iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None: bad.append(tc.get('classname').split('.')[-1]+'.'+tc.get('name'))
print(t, f); [print(' RED:', x) for x in sorted(bad)]"
```

## 3. Hard rules (from plan §0 + owner gates — non-negotiable)

- Package `com.scanwhirl.app.test` only, label `ScanWhirl Test`; never touch the Play
  package/keystore/`keystore.properties`. No version bump (25 → 26 happens in T9.1) and
  no `git push`.
- One task = one commit, message `test26 T<n>.<m>: <summary>`; compile after each task,
  full suite after each task, full build after each phase. **`UiSmokeTest` updated in the
  same commit** as any layout/ID change. Two failed attempts → `docs/BLOCKERS.md` + halt.
- **Gates are real stop-points:** G2 (end of P3) = build `ScanWhirl-1.1.1-test26-alpha2.apk`
  → copy to `/mnt/c/Users/Cybium/Downloads/` → **STOP**, wait for owner screenshots.
  G2 screenshots required: Home 0 docs, Home with docs, chip selected, search focused,
  Tools sheet, selection mode.
  **G2b (end of P3b) — DONE, PASSED 2026-11-08 (sub-checks Home/Tools/Selection):** deliverables were
  `alpha2b.apk` + the three `ScanWhirl-test26-*.md` + source zip in `/mnt/c/Users/Cybium/Downloads/`,
  no screenshots. The reviewer's edits arrived as a **fourth file**, `ScanWhirl-G2b-AuditAndNextSteps.md`
  (Rev 4) — **adopted** (log entry "G2b review → plan Rev 4 adopted"). Document screen (D-A…D-H) and OCR
  (O-1…O-7) are deferred with exact specs in §4.5/§4.7/T5.2.
  **G2c (end of P4) — md-review only, NO screenshots (Rev 4 Q2):** deliverables = `alpha2c.apk` + refreshed
  source zip + the three md copies → Downloads → **STOP**; the reviewer's md edits against the **9-point
  checklist embedded in the plan's G2c block** are the review.
- Design: D1 orbital-dark theme, D3 category colors, D4 system fonts only. No network/
  analytics/ads. **New/changed strings get their `values-hi` entry in the same commit**
  (Rev 4 §3C rule; T8.6 only sweeps the 280-string legacy backlog).
  No emoji anywhere (`EmojiGuardTest` enforces). Every `INFINITE` animator gated on
  `SwMotion.ambientAllowed`; interpolator `PathInterpolator(0.4f, 0f, 0.2f, 1f)`.
- Tests follow the product (rewrite in the same commit; **never `@Ignore`**).
  `TextFitTest.{home,page editor,document}` stays **expected-red until P5** — those 3 are
  the ONLY permitted failures; anything else = regression, fix before committing.
- Backward-compatible on-disk data (meta.txt name/createdAt/category, append-only).
- md-file edits are allowed in build mode; use plan mode for the phase review before
  starting each next phase (P3 review already APPROVED).

## 4. Done so far

- **P0** (`…1b7113c` → baseline): tag `test25-final`, branch, docs archived, plan+log created.
- **P1 + G1 PASSED ✅**: `Insets.kt` (`applySystemBarsPadding`), `TextFitTest` red-proof,
  `CategoryRules`/`CategoryStyle`, repo API (`saveScan(…, category)`, `setCategory`,
  `updateOcrPage`), `StatusLine` + `EmojiGuardTest`. alpha1 delivered, owner confirmed.
  Plan updated to **Rev 2** (complaint #13, N10, T4.7, T5.4 switch, T6.1 `removeGaps`,
  `ExportHygieneTest`, Q7, checklist 11).
- **P2 ✅**: MainActivity 2,231 → **890** lines (gate <900), `tools/` = 10 files/1,609 lines,
  9 commits, suite 221/3 throughout. T2.9 deviation approved at the P3 review.
- **P3 review APPROVED** (5 owner answers, see log): T2.9 done; FAB dies in T3.6; Move
  button visible-but-**disabled** until T4.3; nudge created T3.1 but GONE until T4.4;
  Tools-sheet TextFit case allowed as 4th known-red *if genuinely red* (it came back
  **GREEN** — no 4th red needed).
- **P3 tasks committed** (one commit each): T3.1 `e962bc6` skeleton → T3.2 `e1e3965`
  status line → T3.3 `eef53c1` HomeFilter → T3.4 `d793de1` chip rail → T3.5 `c508baa`
  search+list header → T3.6 `4759972` bottom bar+Tools sheet (FAB and toolsRow/ProSuite
  **deleted**) → log `bbf52a4` → docs `557d11e` → T3.7 `e83ec2d` selection contextual
  header (menu reduced to the gear; `updateSelectionHeader()`; Move visible-but-disabled
  until T4.3) → T3.8 `4405904` empty states (frame_corners + first-scan; category/search
  empty + Show all; `emptyOrbit`/`ic_empty_orbit`/`empty_subtitle` deleted) →
  T3.9 `fb96782` **Home is the launcher** (MAIN/LAUNCHER → `MainActivity`, `finish()`
  warp override deleted; launcher test rewritten, dead warp test deleted).
- **Suite now: 258 tests / 3 failures = exactly the documented TextFit known-reds.**
- **Phase-end full build PASSED the gate check:** both variants **258/3/0**, red = exactly
  the 3 documented TextFit screens, APK built (6,561,168 bytes); aapt2: `com.scanwhirl.app.test`
  · `ScanWhirl Test` · launcher `MainActivity` · versionCode 25/`1.1.1-test25` (T9.1 bumps).
- **G2 delivered:** `ScanWhirl-1.1.1-test26-alpha2.apk` + the three md copies are in
  `/mnt/c/Users/Cybium/Downloads/` — **session stopped here for owner screenshots.**
- **G2 reviewed → NOT passed (defects #14–#18, plan Rev 3 adopted).**
- **Phase 3b BUILT** (one commit each, every new test red-proofed first): T3.10 `49b1576`
  status line from the unfiltered list (`StatusLineScreenTest` 4/4 red → green) →
  T3.11 `0172940` Tools-sheet words (forced-narrow red proof `Compress needs 109px but
  only 48px fit`; runtime 4/3-column rule; sections `sheetFree`/`sheetPro`;
  `tool_lock`; sheet `NestedScrollView`; **opens `STATE_EXPANDED`** — Material's
  collapsed rest state hangs tall content below the screen, source-verified) →
  T3.12 `ac8d267` selection bar (`SelectionBarTest` 5 cases, 4 red first; header =
  X · full count · Select all; `selectionBar` + `Widget.ScanWhirl.Button.DockItem`;
  **amends the T3.7 rule** — SCAN bar replaced by the contextual action bar) →
  T3.13 `c9e329c` polish (dock margins12/8dp; sort default *verified* `SORT_DATE` +
  newest-first — the shots' `Name ▾` was a stored pref; `empty_first_scan_hint` en+hi).
- **Phase-end full build (P3b): both variants 267 tests / 2 failures** — the two are
  documented TextFit known-reds; `home` flips **green outside afternoon hours** (shorter
  greeting string — time-of-day dependent, see log T3.12). aapt2 unchanged: test package ·
  `ScanWhirl Test` · launcher `MainActivity` · versionCode 25.
- **G2b delivered:** `ScanWhirl-1.1.1-test26-alpha2b.apk` + the three md copies +
  `ScanWhirl-1.1.1-test26-source.zip` (tracked tree at `906d158`) are in
  `/mnt/c/Users/Cybium/Downloads/`.
- **G2b reviewed → PASSED (sub-checks) → plan Rev 4 adopted (2026-11-08):** review arrived as
  `ScanWhirl-G2b-AuditAndNextSteps.md` in Downloads (Rev 4 + Q&A). Verdict: Home/Tools/Selection
  **PASSED**; Document screen → D-A…D-H deferred to T5.2; OCR → O-1…O-7 spec'd into §4.7.
  Decisions **Q1** (page-editor dock = sharpen **T5.1**, no new T5.3) and **Q2** (G2c = md-review,
  no screenshots). Three audit claims corrected against the tree: `duplicateDocument` already exists
  (§3A), Extract is genuinely new, and **23 of 27** test26 strings lack `values-hi` → new **T3.14**.

## 5. Next steps (in order)

**G2 owner review result (2026-11-08): Home looks good to the owner; these are real defects found on the device that 258 green tests missed:**

| # | Defect | Fix task (plan Rev 3) |
|---|---|---|
| 14 | Status line wrong: `0 DOCS · 0 B` over a listed 4.4 MB doc; `2 DOCS` over the empty state; right (`1 DOC 4.4 MB`) only after leaving selection mode | **T3.10** one `renderHome()` for status/counts/list + `StatusLineScreenTest` (red-proof first) |
| 15 | Selection header clipped to `1 sel…`; Merge enabled with 1 item | **T3.12** count on its own row, `Select all`, actions in a bottom bar (DockItem), `SelectionBarTest` |
| 16 | Tools sheet breaks the word `Compres`/`s`; `Lock / Unlock` wraps | **T3.11** tile padding / 3-column fallback, label `Lock`, longest-word assertion in `TextFitTest` |
| 17 | Document-screen ⋮ menu is long, duplicates the gold Pro Tools tray, and holds page actions (Rotate, Delete page, Save this page) | **T5.2 + §4.5 rewrite** (one overflow = bottom `More`; page actions → Select pages / Page editor; no Pro button for Pro users). Phase 5 work, spec fixed now. |
| 18 | Deleting a doc in-app leaves `Documents/ScanWhirl/*.pdf` | **T4.7 first, then new T4.8** (record exported copies in `meta.txt`, delete checkbox in the confirm dialog, skip copies the app cannot delete) |
| — | Home polish: bottom bar touches screen edges; default sort shows `Name`; empty state has no hint line | **T3.13** |
| — | `search focused` screenshot was not sent | requested again at **G2b** |

**Order now (Phase 3b is DONE — 2026-11-08):**

1. ~~**Phase 3b in build mode**~~ ✅ T3.10 `49b1576` → T3.11 `0172940` → T3.12 `ac8d267` → T3.13 `c9e329c`;
   every new test red-proofed first; the T3.7 amendment is noted in the log; phase-end build **267/2 per variant**
   (the 2 = documented TextFit reds; `home` is green outside afternoon hours — time-of-day dependent).
2. ~~**G2b**~~ ✅ DONE — reviewed via `ScanWhirl-G2b-AuditAndNextSteps.md`, PASSED (sub-checks),
   **Rev 4 adopted** into the plan/log/handoff (one docs commit).
3. **Phase 4 — plan review DONE (order re-confirmed Rev 4), now build mode:**
   **T3.14** ✅ (`abdbabd`), **T4.7** ✅ (`e518c75`), **T4.8** ✅ (`2685ff5`), **T4.1** ✅ (`b8e40c1`), **T4.2** ✅ (`bb70f81`) and **T4.3** ✅ (`9e531ab`) are done; then **T4.4 → (T4.5 optional) → T4.6**.
   After T4.1: grep `Prefs.autoCopyToDocuments` in `saveAndFinish` + rerun `ExportHygieneTest` (§8 gotcha).
   Any new string → `values-hi` in the same commit.
4. **G2c (md-review, NO screenshots):** deliverables = `alpha2c.apk` + refreshed source zip + the three
   md copies → `/mnt/c/Users/Cybium/Downloads/` → **STOP**. The reviewer's md edits against the
   **9-point checklist in the plan's G2c block** are the review (Phase 4 changes what is written to the
   phone's storage, so this gate matters).
5. Then Phase 5 (documents/editors; T5.2 per §4.5) → G3, and so on as in the plan.
6. **End-of-session duty (every session):** after any md edit re-copy all three to
   `/mnt/c/Users/Cybium/Downloads/ScanWhirl-test26-{PLAN,LOG,HANDOFF}.md`.

Things the G2b reviewer could NOT verify without a device (they had the source this time): whether the
reinstalled test package can delete copies written by earlier installs (verify on device at G2c), and
the real font-scale rendering of the Document dock (TextFit covers it; red-proof before T5.2).

## 6. Key decisions already taken (don't re-ask)

G2b review (Rev 4): **Q1** = page-editor dock fix lives in **T5.1** (sharpened: single row, `minHeight 64dp`,
TextFit green there) — no new T5.3; **Q2** = **G2c is md-review only, no screenshots**; T4.5 stays optional;
T5.2 `Depends on: T4.6 + T4.8`; **Duplicate** wires the existing `repo.duplicateDocument`, **Extract** is a new
repo method; strings rule = `values-hi` in the same commit (T3.14 fixes the 23 already missing).

Rev 3 additions: N11 (delete also removes the app's own exported copies, checkbox default ON, only copies the app wrote), N12 (one home per action; no page-level actions in document menus; no Pro button for Pro users), Q8 = checked by default, Q9 = Date default for fresh installs, mini-gates G2b and G2c.

Q1–Q6 defaults, N10, Q7, T4.7 (in P4), T5.4 Settings switch, T6.1 `removeGaps`, T3.2
status-line styling now, `ExportHygieneTest`, checklist item 11. T3.5: `searchInText`
defaults **true** but consumed as `searchInText && ProGate.isPro`; In-text chip visible
only on Pro + search focus; list header hidden at count 0 / selection; sort menu has 4
items (Name/Date/Modified/Category), label composed `"<label> ▾"` in code. T3.6: sheet
built in code (shell XML + `view_tool_tile` inflated into section rows — T3.11 renamed
the rows `sheetFree`/`sheetPro`, chunked at runtime into 4 columns or 3 when the longest
label word does not fit; the sheet opens `STATE_EXPANDED` because Material's collapsed
rest state hangs tall content below the screen); tile labels
reuse existing strings; Pro gating at tile level (Free tap → `showProPanel()`); bottom
bar needs no insets call (root has `applySystemBarsPadding()`); `bar_tools` doubles as
sheet title. T3.12: `selectionBar` swaps with `bottomBar` inside `setSelectionMode`;
`Select all` adds only `adapter.currentList` (visible) ids. §5.3's 36dp chip beats R6's 48dp (no test enforces R6 — noted in log);
`bg_chip_unsorted_dashed` belongs to T8.4.

## 7. Gotchas learned this session (see also plan §8)

- **Green is not readable (G2).** Robolectric can report a Tools tile as fine while a real phone breaks the word. New UI assertions must (a) check the longest word fits, (b) check on-screen numbers equal the data, and (c) be red-proofed before the fix.
- Exported copies are MediaStore rows owned by the writing package; the test package can delete only its own. Skip and report, never prompt.

- `ScanListAdapter` is a `ListAdapter`: list diffs land async — **`pollUntil { }` after
  every list-changing interaction**; initial state after launch is sync; `forceLayout(rv)`
  first (`HomeScreenTest`/`UiSmokeTest` both have the helpers).
- Tools-sheet tiles live in the **dialog window**, not the activity: look them up via
  `ShadowDialog.getLatestDialog()!!.findViewById(...)`; `sheet.dismiss()` before launching
  a flow dialog so `getLatestDialog()` returns the flow's dialog.
- TextWatcher must drive `btnClearSearch` visibility (not just the click handler).
- Multi-line import edits can silently drop lines — re-grep imports after any block edit
  (bit me in T3.6: lost `Intent`/`ActivityNotFoundException`/`ColorStateList`).
- `edit` tool may fuzzy-match beyond the intended span; verify with `git diff` before build.
- `ThemeOverlay.X.Y` styles need `parent=""`. `TextFitTest` catches clipped strings
  immediately (settings regression in T3.2).
- Repo API shape: `saveScan(uris, pdfUri, suggestedName, filter, pageFilters, category)`
  (category is 6th), `runBlocking<Unit>`, `idleFor(Duration)`, `renderActivityPng`,
  `ProGate.setTestOverride(app, …)` must be reset per test.
- **Test builds are Pro by default** (`ProGate.isPro = BuildConfig.TEST_BUILD || …`):
  to see or assert the Free UI, call `ProGate.setTestOverride(app, false)` FIRST
  (`@Before` resets it to `null` = follow test-build default = Pro).
- Leaving selection mode (toolbar row returns) can leave
  `findViewHolderForAdapterPosition(n)` null until re-layout: `forceLayout(rv)` +
  `pollUntil { findViewHolderForAdapterPosition(n) != null }` before clicking a row.
- aapt2 reports launcher `MainActivity` after T3.9; versionCode stays 25 until T9.1.
