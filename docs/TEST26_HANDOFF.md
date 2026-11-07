# ScanWhirl test26 — Session Handoff (read this first in a new session)

> Purpose: everything a fresh agent needs to continue the test26 redesign without this
> conversation. Written 2026-11-07 at HEAD `fb96782` + docs commit, **Phase 3 COMPLETE,
> G2 (`alpha2`) delivered → STOPPED for owner screenshots.**
> **Authoritative plan:** `docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md` (Rev 2) — read §0
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

**Current position: STOPPED at G2 — Phase 3 complete, alpha2 APK + all three md files
delivered to `/mnt/c/Users/Cybium/Downloads/`, waiting for the owner's 6 screenshots.**

## 2. Locations

| What | Where |
|---|---|
| Project root (git repo) | `/home/cybium/ScanWhirl` (WSL/Ubuntu) |
| Branch / base | `test26-redesign`, from tag `test25-final` @ `1b7113c`. **Never `git push`.** |
| Plan (authoritative, Rev 2) | `docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md` |
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
- Design: D1 orbital-dark theme, D3 category colors, D4 system fonts only. No network/
  analytics/ads. New strings in `values/strings.xml` only (± `values-hi` where key exists).
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

## 5. Next steps (in order)

1. **G2 is the live stop-gate (this session stopped here).** Owner installs
   `ScanWhirl-1.1.1-test26-alpha2.apk` and sends the 6 screenshots (§3). Review them
   against §4/§5 of the plan: Home 0 docs (frame brackets + "Scan your first document"),
   Home with docs, chip selected (category colour), search focused (clear × + In-text
   chip on Pro), Tools sheet (8 tiles, rows 4/4), selection mode (header row
   `X · N selected · Move · Merge · Export · Delete`, bottom bar hidden, Move dimmed).
2. If G2 passes → **plan-mode review of Phase 4** (T4.1–T4.7: categories usable,
   Move wiring T4.3, nudge T4.4, export hygiene T4.7) → then build mode.
3. If G2 fails → fix in the named task's style, same-commit test updates, re-run
   phase-end build, deliver alpha2 again (never skip the gate).
4. **End-of-session duty (every session):** after any md edit, re-copy all three to
   `/mnt/c/Users/Cybium/Downloads/ScanWhirl-test26-{PLAN,LOG,HANDOFF}.md` so the
   Downloads copies alone tell the next session where to read.

## 6. Key decisions already taken (don't re-ask)

Q1–Q6 defaults, N10, Q7, T4.7 (in P4), T5.4 Settings switch, T6.1 `removeGaps`, T3.2
status-line styling now, `ExportHygieneTest`, checklist item 11. T3.5: `searchInText`
defaults **true** but consumed as `searchInText && ProGate.isPro`; In-text chip visible
only on Pro + search focus; list header hidden at count 0 / selection; sort menu has 4
items (Name/Date/Modified/Category), label composed `"<label> ▾"` in code. T3.6: sheet
built in code (shell XML + `view_tool_tile` inflated into `sheetRow1/2`); tile labels
reuse existing strings; Pro gating at tile level (Free tap → `showProPanel()`); bottom
bar needs no insets call (root has `applySystemBarsPadding()`); `bar_tools` doubles as
sheet title. §5.3's 36dp chip beats R6's 48dp (no test enforces R6 — noted in log);
`bg_chip_unsorted_dashed` belongs to T8.4.

## 7. Gotchas learned this session (see also plan §8)

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
