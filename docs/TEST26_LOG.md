# ScanWhirl test26 — Execution Log

As-run record for the test26 redesign. The authoritative plan is
`docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md`.

- **Branch:** `test26-redesign` (from tag `test25-final` @ `1b7113c`)
- **Started:** 2026-11-07

---

## T0.2 — Baseline (before any code change)

Command: `./gradlew test assembleRelease -PtestBuild --no-daemon` → **BUILD SUCCESSFUL**
(23s, tasks mostly up-to-date; test XMLs parsed below).

| Variant | Tests | Failures | Errors | Skipped |
|---------|------:|---------:|-------:|--------:|
| testDebugUnitTest | 209 | 0 | 0 | 0 |
| testReleaseUnitTest | 209 | 0 | 0 | 0 |
| **Total** | **418** | **0** | **0** | **0** |

Baseline = **209 tests per variant (418 total)**. Every phase end must match or exceed
this with 0 failures (phase may add/delete tests with cause, recorded here).

---

## Phase 0 — Safety net

- **T0.1** ✅ `git tag test25-final` @ `1b7113c`; branch `test26-redesign` created.
- **T0.2** ✅ Baseline recorded above.
- **T0.3** ✅ `docs/SCANWHIRL_TEST25_MASTER_PLAN.md` + `docs/TEST25_EXECUTION_REPORT.md`
  → `docs/archive/`; plan copied to `docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md`.

---

## Phase 1 — Foundations

### T1.1 Insets helper ✅

- New `ui/Insets.kt`: `View.applySystemBarsPadding(top, bottom)` — captures original
  padding once (idempotent via weak map), adds `systemBars() | displayCutout()` insets,
  plus `ime()` on Android 11+ (below that `adjustResize` still works; double padding
  avoided). `BaseActivity.onCreate` → `WindowCompat.setDecorFitsSystemWindows(window, false)`.
- Applied after `setContentView` in all 9 activities (incl. `GlobeHomeActivity`, still the
  launcher until T3.9 — its root is the only one needing an import, subpackage).
- Debug suite after the change: **green (1m5s)** — no insets fallout.

### T1.2 TextFitTest — RED PROOF ✅ (written red first, as required)

`app/src/test/java/com/scanwhirl/app/ui/TextFitTest.kt` — `@GraphicsMode(NATIVE)`,
`@Config(sdk=[35], qualifiers="w320dp-h640dp-xhdpi")`, font scale 1.0 + 1.3 via
`RuntimeEnvironment.setFontScale` (exists in Robolectric 4.14.1 ✅), synthetic 48dp
status/nav insets dispatched on the screen root, all problems collected into one message.

**Run against unmodified test25 layouts: 3 of 5 screens FAILED — 21 problems, all real:**

```
### home screen text fits — 7 problems
 - Home @ 1.0: [searchInput "Search name or scanned text"] hint needs 361px but only 272px
   are available                    ← complaint #7 reproduced
 - Home @ 1.0: "Lossless neural packing" line is 143px wide but only 140px fit
 - Home @ 1.0: "Vault · OCR · Lock · Long Image" line is ellipsized (3 chars cut)
 - Home @ 1.3: [searchInput] hint needs 491px but only 228px are available
 - Home @ 1.3: "Scale PDF & canvas" 144px > 140px; "Filter, rotate & crop" 143px > 140px
 - Home @ 1.3: "Vault · OCR · Lock · Long Image" ellipsized (10 chars cut)

### page editor text fits — 8 problems  (complaint #6a reproduced)
 - PageEditor @ 1.0: [btnRotateRight/btnCrop/btnFilter/btnDelete] needs 114px
   (layout 30px + pads 64/20) but the view is 96px tall   ← fixed 48dp @2x = 96px
 - PageEditor @ 1.3: same four buttons need 123px vs 96px

### document screen text fits — 6 problems  (complaint #6b reproduced)
 - Document @ 1.0: [btnShare "Share"] right edge 659px exceeds parent width 608px (LinearLayout)
 - Document @ 1.0: [btnShare "Share"] right edge 675px exceeds screen width 640px
 - Document @ 1.3: [btnShare] right edge 751px > parent 608px; 767px > screen 640px
 - Document @ 1.0/1.3: document title "Text fit document" ellipsized (2/4 chars cut)
```

**Settings + ScanReview pass at both scales.** Robolectric DID reproduce the device
clipping → the plan's XML-dimension fallback is **not needed**.

**Known-red until P5 (deviation, per plan T1.2 "must be green from P5 onward"):** the
plan's §7 rule wants every phase end green, but T1.2 explicitly says this test is only
required green *from P5*. Phase-end full builds for P1–P4 will therefore expect **exactly
these 3 red tests** (documented here); all other tests must be 0-failure. From P5 on:
strict 0 red. P5 (dock rebuilds) + P3 T3.5 (search/sort bar) clear every item above.

### T1.3 Category model ✅
- `CategoryRules`: `UNSORTED` const, `ALL_WITH_UNSORTED`, `isValid` accepts six,
  `label()` = value itself (R9/N4), `classify(name, ocrText)` — name rules unchanged,
  then OCR keyword families (receipts/IDs/forms/study) requiring **≥2 hits and a strict
  winner** (ties → Unsorted), else `UNSORTED`. `loadDocument` fallback still `OTHER`
  (old docs unchanged; `CategoryPersistTest` legacy test untouched and green).
- `CategoryStyle`: `COLOR_UNSORTED` added; `shortTag/tagForRing/colorForRing/ringIndex`
  **deleted**. App callers now `CategoryRules.label(cat).uppercase()` (chip texts —
  `● RECEIPTS` etc.; `● STUDY` assertion in Phase5ScreensTest still green). Globe's two
  index-based lookups live in new `ui/globe/GlobeRingShim.kt` (dies in P7).

### T1.4 Repository API ✅
- `saveScan(..., category: String? = null)` → `writeMeta(dir, name, category)`
  (invalid/null still falls back to `classify(name)`).
- `setCategory(ids: List<String>, category)` bulk overload; missing ids skipped.
- `updateOcrPage(id, pageKey, text)` public — rewrites `ocr.txt` through the same
  lossless `writeOcr`, so `SearchMatcher` (which reads `repo.ocrDoc`) finds edits.
- 4 new `ScanRepositoryTest` tests incl. search-finds-edited-word and old 3-line
  meta-without-trailing-newline load.

### T1.5 Status line + emoji guard ✅
- New `util/StatusLine.kt`: `phase(hour)` → string res id (05–11/12–16/17–20/else),
  `format(...)` → `GOOD AFTERNOON · 2 DOCS · 14.0 MB` (plurals `status_docs`,
  `StatusLine.formatBytes` shared). `Greeting.kt` **deleted** (it was the emoji source).
- Emoji stripped from `pro_panel_home`/`pro_panel_free` (values-hi ships neither string
  and has no emoji — verified).
- `EmojiGuardTest` walks code points (surrogate-safe) over `res/values*/strings.xml` +
  `src/main/**/*.kt` → 0 hits. `GreetingTest` → `StatusLineTest` (git mv, rewritten).
- `UiSmokeTest`: its 2 `Greeting.forHour` tests rewritten same-commit (status-line shape
  + per-hour render).

### G1 gate record — alpha1 (2026-11-07)
- Full build `test assembleRelease -PtestBuild --continue` → APK built
  (2m46s; `--continue` because of the documented known-red TextFitTest).
- Results per variant: **221 tests, 3 failures, 0 errors — the 3 failures are EXACTLY
  the documented TextFitTest screens** (home/page editor/document), 0 any other failure.
  Baseline moved 209 → 221 (+5 TextFit, +1 EmojiGuard, +2 CategoryRules, +4 Repo;
  Greeting→StatusLine net 0).
- `aapt2 dump badging` on alpha1: `com.scanwhirl.app.test` · `ScanWhirl Test` ·
  launcher `GlobeHomeActivity` (moves in T3.9) · versionCode 25/`1.1.1-test25`
  (bump in T9.1) — all as expected for a mid-plan alpha.
- **APK delivered:** `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test26-alpha1.apk`
  (6,537,869 bytes).
- **G1 owner check:** nothing draws under the navigation bar on ANY screen
  (edge-to-edge + `applySystemBarsPadding` is new in this build).

### G1 result — PASSED (2026-11-07, owner) ✅

- Owner installed alpha1, confirmed no drawing under status/nav bars.
- Plan file updated to **Rev 2** by the owner (G1 notes + 5 screenshots + APK
  inspection): adds complaint **#13** (ghost PDFs in Recent), decision **N10**,
  **T4.7** (Phase 4 — export hygiene), a Settings switch line in T5.4, OCR
  `removeGaps` in T6.1, status-line styling spec in T3.2, `ExportHygieneTest`,
  **Q7**, checklist item **11**. **Phase 2 is byte-identical between revs** —
  verified with `diff`; T0–T1 are not to be redone.
- `docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md` synced to Rev 2 (702 → 747 lines).
- T4.7 confirmed deferred to Phase 4 (owner instruction).

---

## Phase 2 — Untangle MainActivity (pure refactor) ✅ complete

**Result: `MainActivity.kt` 2,231 → 890 lines (gate < 900 met), zero visible change.**
Method exactly per plan §P2: one flow per commit, bodies moved verbatim (only receiver
changes), `registerForActivityResult` launchers stay in the Activity and are passed in as
lambdas, full suite after every task.

| Task | Commit | New file | MainActivity | Seams added |
|---|---|---|---|---|
| T2.1 | `65d7b63` | `tools/ResizeFlow.kt` (+`HomeHost`, `FlowUtil`) | 2231 → 2063 | `HomeHost` impl on MainActivity |
| T2.2 | `86c5165` | `tools/CompressFlow.kt` | 2063 → 1749 | `pickPdf`, `docs` lambdas; `pickScan`/`devicePdfName` shared in `FlowUtil`; file-private `formatBytes` absorbed into `StatusLine.formatBytes` |
| T2.3 | `4894cf5` | `tools/MergeFlow.kt` | 1749 → 1679 | `docs`, `onMerged` lambda (the 3 post-merge Home steps stay in one host lambda, order preserved); `showSortMenu` stayed behind |
| T2.4 | `bd2bf56` | `tools/LockFlow.kt` | 1679 → 1424 | `docs`, `pickPdf` (wraps `pendingUriAction` + `pickProPdf`) |
| T2.5 | `982b0f0` | `tools/VaultFlow.kt` | 1424 → 1305 | `pickPdf`, `setVaultMode`, `onProRequired`; **`setVaultMode` stays on the Activity** (tests call it: `UiSmokeTest`) |
| T2.6 | `5ff7a9c` | `tools/LongImageFlow.kt` | 1305 → 1207 | none (source Uri handed in) |
| T2.7 | `8cb7980` | `tools/OcrFlow.kt` | 1207 → 1094 | none; **old OCR dialog moved along untouched — P6 replaces it** |
| T2.8 | `05dc6b5` | `tools/ScanLauncher.kt` | 1094 → 997 | `startScanning` lambda → `scannerLauncher` (registration stays); `showScanChooser`/`chooseProSource` stayed behind |
| T2.9 | `709dd10` | `ui/ScanListAdapter.kt` | 997 → **890** | none — see deviation below |

- **Suite after every task:** 221 tests/variant, **exactly the 3 documented
  `TextFitTest` known-reds** (home / page editor / document), 0 any other failure —
  same state as G1.
- **Deviation T2.9 (documented):** the plan's T2.1–T2.8 table alone left MainActivity
  at 997 — above the phase's `<900` gate. Closed it with pure refactors only:
  `ScanListAdapter` moved to `ui/ScanListAdapter.kt` (same package → zero reference
  changes), deleted `showAbout` (**already dead in `test25-final`** — zero callers;
  `SettingsActivity` has its own), and 9 now-unused imports. No behaviour change.
- **Near-miss caught pre-compile:** the `showAbout` deletion range also swallowed the
  `scannerLauncher` registration (the `}` asserted as its close was actually
  `scannerLauncher`'s). Caught by auditing `git diff` before building; restored
  byte-identical from git (shows as untouched context in the diff), recompiled and
  re-ran the suite green. Recorded here because it is exactly the class of error the
  "verify before commit" discipline exists for.
- `tools/` after the phase: **10 files, 1,609 lines** (`HomeHost` 23, `FlowUtil` 60,
  `MergeFlow` 110, `LongImageFlow` 126, `ScanLauncher` 140, `OcrFlow` 141, `VaultFlow` 164,
  `ResizeFlow` 204, `LockFlow` 295, `CompressFlow` 346).
- **Phase-end full build** `test assembleRelease -PtestBuild --no-daemon --continue`:
  BUILD FAILED *only* on the 3 known-red TextFit tests (expected with `--continue`);
  **both variants 221 tests / 3 failures / 0 errors**, APK produced
  (`app-release.apk`, 6,544,641 bytes — not delivered; next delivery is **alpha2 at G2**).
- Gate check: same test count as G1 (221) ✅ · MainActivity < 900 (890) ✅ ·
  diff mostly deletions in `MainActivity`, additions in `tools/` (+`ui/ScanListAdapter.kt`) ✅
- Nothing pushed; 18 commits on `test26-redesign` through `709dd10`.

**Next:** plan-mode review of Phase 3 (the new Home) with the owner → build mode →
**G2 (`alpha2`)**.

---

## Phase 3 plan review — APPROVED (2026-11-07, owner + Claude)

Plan-mode walkthrough of §PHASE 3 + §4.2/§4.3 + §5 rules against the current
`activity_main.xml` (757 lines, 34 IDs), binding code, and all test ID references.
Five decisions taken, all **owner-approved via question round**:

1. **T2.9 deviation (P2) APPROVED as done** — adapter file move + dead `showAbout`
   + 9 unused imports; pure refactors, gate `<900` met. Signed off at this review.
2. **FAB dies in T3.6** (not named in the plan's Delete list) — §4.2 has no FAB;
   bottom-bar SCAN inherits its click behavior incl. vault-mode add; `fabPulse`
   ambient animator goes with it.
3. **Selection `Move` button: visible but disabled in P3**, wired to the category
   picker in T4.3 (HomeScreenTest asserts it shows; no dead-geometry surprises).
4. **Nudge: view created in T3.1 but kept `GONE` until T4.4** (Quick Sort) — no
   inert control ships in alpha2.
5. **Tools-sheet `TextFitTest` added this phase**; if it fails for genuine fit
   reasons it is documented as a 4th known-red until P5 — never `@Ignore`.

**Execution strategy (T3.1 crux):** T3.1 builds the full §4.2 skeleton while the
old toolbar / tools row / Pro suite / FAB stay parked in place, so every commit
compiles and the suite stays green; T3.2–T3.6 each fill one region and delete only
its own predecessor; the Delete list lands in T3.6 exactly where the plan says.
Intermediate states are never delivered (first delivery = alpha2 at G2).

**Defaults:** rename `greetingText` → `statusLine`, keep `proBadge`/`appNameText`
IDs; Settings globe row stays until P7; new strings in `values/strings.xml` only
(± `values-hi` where the key already exists), matching the T1.5 `status_*` pattern.

**Tests contract:** counts grow (new `HomeFilterTest`, `HomeScreenTest`, TextFit
Tools sheet) — exact numbers recorded at phase end; known-red set stays exactly
the 3 documented TextFit screens ± the documented Tools-sheet one.

**Next:** T3.1 layout skeleton → … → T3.9 launcher → **G2 (`alpha2`)**.

---

## Phase 3 — The new Home ✅ complete (G2 stop-gate reached)

Strategy as approved: skeleton first (T3.1), each task fills one region and deletes
only its predecessor; nothing ships until G2. `UiSmokeTest` updated in the same commit
as every layout/ID change; full suite after every task.

| Task | Commit | Suite after | Notes |
|---|---|---|---|
| T3.1 | `e962bc6` | 221/3 | skeleton only, +49 lines layout, no test change |
| T3.2 | `e1e3965` | 222/3 | +1 `StatusLineTest.styled` case |
| T3.3 | `eef53c1` | 239/3 | +17 `HomeFilterTest` |
| T3.4 | `d793de1` | 246/3 | +7 `HomeScreenTest` (first run 246/7 → fix) |
| T3.5 | `c508baa` | 253/3 | +7 `HomeScreenTest` (first run 253/4 → fix) |
| T3.6 | `4759972` | 255/3 | +1 HomeScreenTest +1 TextFit (first run 255/6 → fix) |
| T3.7 | `e83ec2d` | 256/3 | selection header; 2 tests rewritten + 1 new (first run 256/4 → fix) |
| T3.8 | `4405904` | 259/3 | +3 HomeScreenTest empty-state cases, first run green |
| T3.9 | `fb96782` | 258/3 | launcher moves; −1 dead warp test, launcher case rewritten |

The "3" in every row is exactly the documented `TextFitTest` known-red set
(home / page editor / document) — 0 any other failure at every task.

### T3.1 Layout skeleton ✅ (`e962bc6`)

- `activity_main.xml` +49 lines only: `homeRoot` id on the CoordinatorLayout root, then
  the four §4.2 regions as GONE placeholders in wireframe order — chip rail, Quick Sort
  nudge (GONE until T4.4 per owner decision 4), list header, bottom bar. Legacy header /
  tools row / Pro suite / FAB stayed parked in place untouched, so every intermediate
  commit compiles and lists docs.

### T3.2 Header + status line ✅ (`e1e3965`)

- `StatusLine.styled()` (+42 lines): hairline-coloured ` · ` separators + monospace
  digits over `format()`'s plain string (R2 spec), new `cyan_dim` `#4A8299` token.
- `greetingText` → `statusLine` id rename; 11sp / letterSpacing 0.12 / `cyan_dim`
  (micro-caps exception). Settings toggle text → "Status line"
  (`settings_greeting`/`_summary` relabelled, key kept).
- `UiSmokeTest` rewritten in-commit for the id; `StatusLineTest` +1 `styled()` case
  (222 tests). **TextFit caught a settings-string regression immediately** (the plan's
  §7 screenshot-7 class) and it was fixed in the same task — proof the rig works.

### T3.3 HomeFilter pure logic ✅ (`eef53c1`)

- New `util/HomeFilter.kt` (97 lines): `HomeState(query, category, sort, inText,
  includeVaulted)` + pure `apply()`/`sort()`/`counts()`; `MainActivity.applyList()`
  now delegates (the old `sortDocs` block and the redundant `ocrMatches` leg removed,
  ~41 lines out of MainActivity).
- `Prefs.SORT_CATEGORY` added; `sort_category` = "Category" string.
- `HomeFilterTest` 17 cases (223 lines) → 239/3.

### T3.4 Category chip rail ✅ (`d793de1`)

- `Widget.ScanWhirl.Chip` style + `ThemeOverlay.ScanWhirl.ChipRail` (`parent=""` —
  Material's overlay styles need an explicit empty parent, first attempt failed
  without it); `buildChipRail`/`paintChip`/`selectCategory` in MainActivity;
  `chipGroup` single-selection; category colour when checked (D3).
- New `HomeScreenTest` (7 cases, 271 lines). **First run 246/7:** 4 reds, all the same
  cause — `ScanListAdapter` is a `ListAdapter`, so post-tap `itemCount` assertions read
  the pre-tap list until the background diff lands. Fixed by adding `pollUntil`/
  `forceLayout` helpers (same contract `UiSmokeTest` already used) and making the
  selection test a real long-press → **246/3**.

### T3.5 Search + list header ✅ (`c508baa`)

- Hint → "Search documents" (values + values-hi), `search_hint_pro` **deleted**;
  `btnClearSearch` (×) appears while there is text; `chipInText` "In text" chip shows
  only for Pro **and** only while the field has focus (checked-state colour selectors
  `chip_in_text_text/stroke`); `searchInText` default **true** but consumed as
  `searchInText && ProGate.isPro` (owner-approved: preserves Pro OCR-on behaviour).
- List header: `headerCount` / `sortSelector` / `btnSelect`; `btnSort`/`sortLabel`
  pill deleted from the search bar; `updateListHeader()` (hidden at count 0 or in
  selection; category shows "N in <label>"); `showSortMenu()` anchored to the selector
  with **4** items incl. Category, label composed `"<label> ▾"` in code.
- UiSmokeTest hint tests rewritten to chip-on-focus asserts; `HomeScreenTest` +7
  (253). **First run 253/4:** the clear (×) never flipped visible — visibility was not
  wired into `afterTextChanged`; fixed there → **253/3**.

### T3.6 Bottom bar + Tools sheet ✅ (`4759972`)

- **Bottom bar** (`bg_dock_glass`, inset-aware via the root's existing
  `applySystemBarsPadding`): `btnTools` (`ic_tools` new grid glyph + "Tools"),
  `btnScan` 64dp `bg_fab_outer_ring` viewfinder (vault mode swaps to `ic_add` +
  `vault_add_title` description), `btnVault` (→ `vaultFlow.openVaultWithPin()`).
- **Deleted with it (owner decision 2):** `fabHost`/`fabRing`/`fabPulse`/`fabScan`
  (the FAB block) and the whole `toolsRow` + Pro Suite card (`toolEdit/Merge/Compress/
  Resize/ProSuite*/toolOcr/Vault/LockPdf/LongImage`) — `activity_main.xml` −561/+~100.
  `setFabVisible`/`toggleProSuite` removed from MainActivity (890-line file stays put).
  Selection mode hides the bar; `setVaultMode` re-shows it with SCAN→Add.
- **Tools sheet** (`sheet_tools.xml` + `view_tool_tile.xml`, tiles built in code into
  `sheetRow1/2` via ids in new `values/ids.xml`): row 1 Edit/Merge/Compress/Resize
  (core), PRO divider (reuses `badge_pro`), row 2 OCR/Lock/Long/Vault — Pro tiles
  gated **at tile level**: Free tap dismisses the sheet and opens `showProPanel()`,
  Pro tap runs the P2 flow. `showToolsSheet()` dismisses the sheet before launching a
  flow so `ShadowDialog.getLatestDialog()` returns the flow dialog.
- **Tests:** UiSmokeTest 8 cases rewritten (bottom bar, tile paths, selection hides
  bar, tools-row test now asserts the ids are *gone* via `getIdentifier`); new
  `HomeScreenTest` "tools sheet opens and lists eight tiles in two rows" (rows 4/4);
  `TextFitTest` refactored `checkScreen` → `checkView(activity, root, …)` so a second
  window can be walked, + `tools sheet text fits` case — **it passed green**, so the
  owner-approved contingency 4th known-red was NOT needed (known-red set stays 3).
- **First run 255/6:** 3 NPEs — tile lookups went through `activity.findViewById`,
  but tiles live in the dialog's window, not the activity; fixed to
  `ShadowDialog.getLatestDialog()!!.findViewById` → **255/3**.

### T3.7 Selection contextual header ✅ (`e83ec2d`)

- New `selectionHeader` row in `activity_main.xml` (sibling of `toolbar` inside
  `AppBarLayout`, GONE by default): `btnSelClose (X) · selCount · Move · Merge ·
  Export · Delete` with `·` separators (`selSep1-3`). While selecting the toolbar
  row (wordmark + gear) hides and the header takes the row.
- `menu_main.xml` keeps only `action_settings`; the 4 selection menu items +
  `onPrepareOptionsMenu` deleted; `applyToolbarTitle`/`selectionTitle` merged into
  `updateSelectionHeader()` — **no `invalidateOptionsMenu` anywhere** (static menu).
  `exportSelectedZip()`/`confirmDeleteSelected()`/`mergeFlow` logic verbatim.
- Gating: Move visible but **disabled** (T4.3 wires it — owner decision 3);
  Merge ≥ 2; Delete ≥ 1; Export Pro-only ≥ 1 — Free hides Export + separators.
- New strings `move`, `export`. Tests: UiSmokeTest long-press rewritten
  (header/toolbar/selCount), zip-export test → `btnSelExport` + menu id asserted
  gone via `getIdentifier==0`; HomeScreenTest Select rewritten + new gating case.
- **First run 256/4:** (1) my gating case asserted Free — test builds are Pro by
  default (`isPro = TEST_BUILD || …`), fixed with `setTestOverride(false)` and the
  Pro half with `setTestOverride(true)`; (2) async `ListAdapter` row-1 NPE — fixed
  with `pollUntil { findViewHolderForAdapterPosition(1) != null }` and re-`forceLayout`
  + poll after leaving selection → **256/3**.

### T3.8 Empty states ✅ (`4405904`)

- `updateEmptyState()` called from `applyList()` when `sorted.isEmpty()` — three
  states: **fresh library** → `frame_corners` brackets + `empty_first_scan`
  ("Scan your first document", subtitle GONE, SCAN button is the CTA);
  **filter-empty** (category *or* search) → `empty_category` "No <label> documents
  yet" / `empty_no_match` + `btnShowAll` chip (clears query + category in one tap);
  **vault-empty** → copy set inside `updateEmptyState` (`setVaultMode` no longer
  sets it — its two lines died with the string).
- `emptyState` click = scan chooser **only when `allDocs.isEmpty()`**; a
  filtered-out library must not launch the scanner.
- Deleted: `emptyOrbit` view, `ic_empty_orbit` drawable, `empty_subtitle` (values +
  values-hi). `empty_title` kept — FlowUtil:49 / VaultFlow:111 toasts use it.
  New strings `empty_first_scan`, `empty_category`, `empty_no_match`, `show_all`.
- Smart-cast fix: `val cat = selectedCategory` (mutable property).
- Tests: 3 new `HomeScreenTest` cases (fresh library incl. `emptyOrbit`
  `getIdentifier==0`, dead category → Show all → doc back + All checked, dead
  query → Show all → query cleared). **First run green → 259/3.** The known-red
  `home screen text fits` stays red on its own `statusLine` ellipsis (its
  `emptySubtitle` complaints naturally disappear with the view).

### T3.9 Home is the launcher ✅ (`fb96782`)

- Manifest: `MAIN/LAUNCHER` intent-filter moved `GlobeHomeActivity` →
  `MainActivity`; the globe activity keeps its declaration (no filter) — in the
  tree until P7.
- `MainActivity.finish()` warp override deleted (`globeHomeEnabled` branch gone;
  `SwTransitions.warpBackToGlobe` keeps zero callers, removed with the globe in P7).
- Same-commit test fixes: `GlobeHomeActivityTest` launcher case **rewritten** →
  `MainActivity` is the launcher; `SwTransitionsTest.finishing the shelf warps
  back to the globe` **deleted** (feature gone — reason in commit); the no-warp
  case kept and still passes. **First run 258/3.**

### Phase-end full build — P3 (2026-11-07)

Command: `./gradlew test assembleRelease -PtestBuild --no-daemon --continue` →
BUILD FAILED *only* on the 3 known-red TextFit tests (expected with `--continue`,
82 tasks); APK produced.

| Variant | Tests | Failures | Errors | Red = the 3 documented TextFit? |
|---------|------:|---------:|-------:|---|
| testDebugUnitTest | 258 | 3 | 0 | ✅ home / page editor / document |
| testReleaseUnitTest | 258 | 3 | 0 | ✅ home / page editor / document |

- `aapt2 dump badging` on alpha2: `com.scanwhirl.app.test` · `ScanWhirl Test` ·
  launcher **`com.scanwhirl.app.ui.MainActivity`** (T3.9 landed ✅) ·
  versionCode 25 / `1.1.1-test25` (bump in T9.1 — unchanged by design).
- Gate checks: both variants **258/3**, 0 any other failure ✅ · known-red set
  exactly the documented 3 ✅ · baseline (209) exceeded ✅ · MainActivity < 900 ✅.
- **APK delivered:** `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test26-alpha2.apk`.
- **G2 owner check (6 screenshots required):** Home with 0 docs · Home with docs ·
  chip selected · search focused · Tools sheet · selection mode.
- Nothing pushed; commits T3.7 `e83ec2d` → T3.8 `4405904` → T3.9 `fb96782`.

**Next:** **STOP at G2** — wait for owner screenshots → then plan-mode review of
Phase 4 (T4.1–T4.7; Move wiring T4.3, nudge T4.4, export hygiene T4.7).

---
## G2 review → plan Rev 3 (2026-11-08)

Owner reviewed alpha2 (6 screenshots) together with a reviewer session → **G2 NOT
passed.** Plan + handoff came back as **Rev 3** (adopted into `docs/` this session);
the `search focused` screenshot was never sent (re-requested at G2b).

| # | Defect | Task |
|---|---|---|
| 14 | Status line stale: `0 DOCS · 0 B` over a listed doc; `2 DOCS · 0 B` over empty Home; right only after leaving selection | T3.10 |
| 15 | Selection header clips to `1 sel…`; Merge looks bright at 1 | T3.12 |
| 16 | Tools sheet breaks `Compres`/`s`; `Lock / Unlock` wraps | T3.11 |
| 17 | Document ⋮ menu long, duplicates Pro tray, holds page actions | §4.5 + T5.2 (P5) |
| 18 | In-app delete leaves `Documents/ScanWhirl/*.pdf` | T4.7 first, then new T4.8 (P4) |
| — | Polish: bottom bar touches edges; sort shows `Name`; empty state lacks a hint | T3.13 |

**Root causes verified in source before coding (all three Home defects confirmed):**

- **#14:** `updateStatusLine()` (MainActivity:325) reads `adapter.currentList`
  (filtered **and** possibly stale) and is called only from `onResume` (:311 —
  right after async `refreshList()` launches, so it sees the pre-load list),
  `updateSelectionHeader` (:575) and `setSelectionMode` (:718). **Never from
  `applyList()`** → first load renders `0 DOCS · 0 B` and never refreshes;
  post-delete `onResume` beats the async diff → `2 DOCS · 0 B` (files gone);
  leaving selection happens to call it → correct. Matches all 3 readings.
- **#16:** `view_tool_tile.xml` = 8dp padding + 4dp margin → ~58dp inner width
  at 4 columns on 360dp; no `hyphenationFrequency`/`breakStrategy` → word
  broken across lines. Sheet rows are fixed (`sheetRow1`/`sheetRow2`).
- **#15:** `selCount` (weight 1, ellipsize) shares one row with X + 4 actions +
  3 separators → truncates. Merge enable-logic (`n >= 2`) is correct but plain
  TextViews have no visual disabled state.
- **T3.13(b):** `Prefs.sortMode` already defaults to `SORT_DATE` — the
  `Name ▾` in screenshots is the owner's **stored** preference surviving the
  upgrade (same package). Verify only; existing users keep their pref.

**Phase 3b order:** docs adopt Rev 3 → T3.10 → T3.11 → T3.12 → T3.13 →
phase-end full build → **G2b** (`alpha2b`, STOP for 4 screenshots — *superseded same day:
no screenshots, review = md edits in Downloads, see the P3b delivery note*). Then
plan-mode review of the revised Phase 4 (T4.7 → T4.8 → T4.1–T4.6) → G2c.

---

---
## Phase 3b — fix-before-Phase-4 ✅ complete (G2b stop-gate reached)

### T3.10 Status line tells the truth (complaint #14) ✅ (`49b1576`)

- **Red proof:** new `StatusLineScreenTest` (4 cases) run on T3.9's code → **4/4 red**,
  reproducing all three owner readings (stale `0 DOCS · 0 B` over a listed doc, `2 DOCS ·
  0 B` over empty Home, correct only after leaving selection).
- **Fix:** `updateStatusLine()` now reads the unfiltered `allDocs` snapshot (never
  `adapter.currentList`) and is called from `applyList()` — the single render path —
  so chips/adapter/status can never disagree (T1.2's "one renderHome" rule applied).
- **4/4 green → suite 262/3** (258 + 4 new, known-reds intact).

### T3.11 Tools sheet: no broken words (complaint #16) ✅ (`0172940`)

- **Red proof:** `collectTileWordProblems()` added to `TextFitTest.tools sheet text
  fits`, first made to fire for real by forcing a narrow tile: 40dp padding → zero-width
  reports (and exposed a vacuous id-match bug — `tile_` prefix vs the real label id
  `tileLabel` — plus a latent `describe()` ClassCastException, both fixed); 20dp →
  `Compress needs 109px but only 48px fit` — the device defect verbatim.
- **Fix:** tile `paddingHorizontal 2dp` / margin `3dp` / `hyphenationFrequency=none` /
  `breakStrategy=simple`; sheet side padding 16→12dp; **runtime column rule** (probe the
  tile's real `TextPaint`: longest label word vs inner width → 4 columns, else 3, rows
  chunked per section); rows → `sheetFree`/`sheetPro` sections; new string
  `tool_lock`="Lock"/"लॉक" (LockFlow titles keep `btn_lock_unlock`); sheet content
  wrapped in `NestedScrollView`.
- **Scale-1.3 height red found & root-caused in Material source:** the sheet rested in
  `STATE_COLLAPSED` (a `BottomSheetDialog` never sets `STATE_EXPANDED` — verified in
  `BottomSheetDialog.java`); `calculateCollapsedOffset()` = `max(parentH − peek,
  fitToContentsOffset)` with `peek = min(16:9-keyline, childH) + insetBottom` → content
  taller than the keyline deliberately hangs its excess below the screen (observed
  bottom 1305 > 1280). **Fix: open the sheet `STATE_EXPANDED`** → `fitToContentsOffset`
  puts it flush with the screen bottom at every scale; @1.0 unchanged (collapsed ==
  expanded for short content); still draggable + scrollable. **Suite 262/3.**

### T3.12 Selection: count never clipped, actions in a bottom bar (complaint #15) ✅ (`ac8d267`)

- **Red proof:** new `SelectionBarTest` (5 cases) written first → **4/5 red** on the
  shell-only code (bar swap ×2, Select all ×2); `HomeScreenTest` + `UiSmokeTest`
  same-commit updates added 2 more reds. All green after the logic landed.
- **Header** = `X · full "N selected" (own weight, no ellipsize) · Select all`.
  **Actions** = new `selectionBar` (sibling of `bottomBar`): four
  `Widget.ScanWhirl.Button.DockItem` buttons (§5.4 spec: icon over label, weight 1,
  minHeight 56dp, inset-aware) — Move (new `ic_move`, dead until T4.3) · Merge (≥ 2) ·
  Export (≥ 1, Pro-only) · Delete (≥ 1, `laser_danger` tint). `Select all` picks
  `adapter.currentList` — the *visible* cards only (pinned by test with a category
  filter: 2 of 3 selected).
- **Rule amendment (as the plan directs):** T3.7's "bottom bar hidden while selecting"
  → the SCAN bar still leaves, but the contextual action bar now takes its place
  (§4.2 Rev 3 — same intent, the space works). Noted here per plan T3.12.
- **Suite 267/2** — 262 + 5 new. The `home` known-red was **green at 17:18 PST**: its
  status line reads `GOOD EVENING` (7) instead of `GOOD AFTERNOON` (9) and fits at 1.3.
  The red is **time-of-day dependent** (worst case = afternoon); it will be red again in
  afternoon runs. Not a regression, not a new red — documented so the G2b count (2 or 3)
  is not misread.

### T3.13 Home polish ✅ (`c9e329c`)

- (a) Bottom bar: `layout_marginHorizontal 12dp` + `layout_marginBottom 8dp` — the
  rounded corners no longer touch the screen edges / nav bar.
- (b) **Verified only (no code):** fresh installs already default to `SORT_DATE` and
  `HomeFilter` sorts it `sortedByDescending { createdAt }` = newest first (Q9 ✓). The
  `Name ▾` in the alpha2 shots is the owner's **stored** preference surviving the
  upgrade (same package) — existing users keep their pref, by design.
- (c) Empty state: new string `empty_first_scan_hint` = "Tap the camera button to
  start" / "कैमरा बटन दबाकर शुरू करें" shown in the muted `emptySubtitle` under the
  fresh-library title (vault/filter states unchanged). **Red proof: 1 red first**
  (subtitle hidden on T3.12 code) → green.
- **Suite 267/2** (same time-of-day note as T3.12).

### Phase-end full build — P3b (2026-11-08)

`./gradlew test assembleRelease -PtestBuild --no-daemon --continue` (82 tasks; APK produced).

| Variant | Tests | Failures | Red = subset of the documented TextFit 3? |
|---------|------:|---------:|---|
| testDebugUnitTest | 267 | 2 | ✅ page editor / document (`home` green — evening greeting, see T3.12 note) |
| testReleaseUnitTest | 267 | 2 | ✅ same two |

- `aapt2 dump badging` on alpha2b: `com.scanwhirl.app.test` · **`ScanWhirl Test`** ·
  launcher **`com.scanwhirl.app.ui.MainActivity`** ✓ · versionCode 25 /
  `1.1.1-test25` (bump in T9.1 — unchanged by design) ✓.
- Gate checks: both variants **267**, failures ⊆ the documented 3 ✅ · no new reds ✅ ·
  baseline (209) exceeded ✅ · every P3b test red-proofed before its fix ✅.
- `MainActivity.kt` = 1,132 lines (the <900 gate belonged to **P2**, met there at 890;
  P3 + 3b legitimately grew it — not a G2b item).
- **APK delivered:** `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test26-alpha2b.apk`.
- **G2b content checklist (what the review must confirm):** Home with docs (status line
  right) · Tools sheet · selection mode (1 selected, and 2 selected) · **search focused**
  (missing at G2 — the clear × and, on Pro, the `In text` chip).
- **Process change (owner, 2026-11-08): G2b needs NO screenshots.** Deliverables in
  Downloads = the APK + the three `ScanWhirl-test26-*.md` + **`ScanWhirl-1.1.1-test26-source.zip`**
  (261 files, tracked tree at `906d158`). The next session starts from the **md copies in
  Downloads**: the reviewer (Claude) edits them after seeing what was built and **the edits
  are the gate review** (adopt like Rev 3; unchanged = passed). The checklist above is what
  those edits should address.
- Nothing pushed; commits T3.10 `49b1576` → T3.11 `0172940` → T3.12 `ac8d267` →
  T3.13 `c9e329c`.

**Next:** **STOP at G2b — no screenshots (owner, 2026-11-08):** APK + three mds + source
zip are in `/mnt/c/Users/Cybium/Downloads/` → the next session starts from the **md copies
in Downloads** (the reviewer edits them; the edits are the gate review) → then plan-mode review of
the *revised* Phase 4 (T4.7 → T4.8 → T4.1–T4.6) → G2c (`alpha2c`) → Phase 5.

---

## G2b review → plan Rev 4 adopted (2026-11-08)

The reviewer wrote **`/mnt/c/Users/Cybium/Downloads/ScanWhirl-G2b-AuditAndNextSteps.md`**
(a fourth md — the G2b review itself, updated once with Q&A). Adopted exactly like Rev 3.

### Verdict (audit §5)

| Area | Status |
|---|---|
| Home screen (P3 + P3b) | ✅ **PASSED** — #14/#15/#16 fixed, polish done |
| Tools sheet | ✅ **PASSED** — no broken words, opens expanded |
| Selection mode | ✅ **PASSED** — count unclipped, action bar correct |
| Search focused (missing at G2) | ⚠️ source correct (`afterTextChanged`, T3.5) — no blocker |
| Document screen | ⚠️ **DEFERRED to T5.2** — defects **D-A…D-H** catalogued in §2 |
| OCR tool | ⚠️ **DEFERRED to P6** — spec block appended to §4.7 (O-1…O-7) |

**G2b PASSED (sub-checks Home/Tools/Selection).** → Rev 4 applied → Phase 4 (order re-confirmed:
T4.7 → T4.8 → T4.1 → T4.2 → T4.3 → T4.4 → (T4.5 optional) → T4.6).

### The two Q&A decisions (asked in plan mode, answered inside the audit)

- **Q1 — T5.3 clash:** the audit's "new T5.3 page-editor task" would collide with the existing
  T5.3 (= Scan Review dock). **Decision: merge into existing T5.1**, no renumbering. T5.1 sharpened:
  single row (not 2×2), `minHeight 64dp`, `TextFitTest.page editor` must go green in T5.1.
  *(Note: audit §2C's line "fix is in Phase 5 (T5.3, new task)" is superseded by this.)*
- **Q2 — G2c process:** **md-review only, no screenshots** — same as G2b. The plan's old G2c
  screenshot list is deleted and replaced by the md-review process + the audit's 9-point
  device checklist (deduped — the audit file has points 1–4 duplicated, and two `## 6` headings;
  adopt one clean 1–9 list).

### Verification against the tree (3 corrections to the audit, baked into the plan)

1. **§3A "Duplicate has zero hits" is wrong** — `ScanRepository.duplicateDocument(id, suggestedName)`
   already exists (`ScanRepository.kt:219`), is called from `DocumentViewerActivity:811`, and is
   covered by `CategoryPersistTest`. T5.2's Duplicate row now says *wire the existing method*, not
   *write a new one*. **Extract**, however, is genuinely new: today's `saveSelectedPages()` →
   `downloadPages()` **exports** to Documents; audit's "new doc from ticked pages" needs a new repo
   method. Audit correct there.
2. **§3C hi gap is bigger than stated** — `move`/`export` are indeed missing from `values-hi`, but
   **23 of the 27** test26 strings lack hi entries (only `tool_lock`, `empty_first_scan_hint`,
   `search_hint`, `select_all` have them), and the legacy backlog is **280** (en 356 / hi 53).
   → **new task T3.14** (add the 23, one commit), the §6 legend **same-commit rule**, T8.6 rescoped
   to the 280 legacy strings.
3. **Menu facts check out** — `menu_viewer.xml` is inflated only by `DocumentViewerActivity`
   (13 item IDs) → "delete the entire file" in T5.2 is safe.

### What changed in the plan (Rev 4, one docs commit)

Rev 4 header note + STATUS · §2 **D-A…D-H** rows · §4.5 rewritten (Rev 4 wireframe; title tappable
→ Rename; explicit removed-ID list; `Depends on: T4.6 + T4.8`; R7 drag-handle bullet kept) · §4.6
single-row 64dp page-editor dock · §4.7 OCR spec-additions block · §5.4 minHeight split (56dp
Home/Document, 64dp page editor) · §6 legend hi-rule + **T3.14** + T4.4 X-close (§3D) + T5.1
sharpened + T5.2 rewritten (incl. §3A duplicate/extract, §3E depends-on) + T6.2 manifest/keyboard/
onDestroy (§3B) + T6.3 cache-file-always + `share_text` (§3B/§3F) + T8.6 rescoped · §8 gotchas
T4.7→T4.1 collision (§3G) · §10 checklist items 15/16/17 (O-IDs folded into item 7 — audit's item 17
would have duplicated it; new item 17 = Duplicate/Extract) · §11 write-on-PDF backlog row (§2E) ·
G2c block rewritten (md-review + 9-point checklist).

### T3.14 Hindi gap found at G2b ✅ (`abdbabd`)

G2b §3C's "immediate check" found `move`/`export` missing from `values-hi` — and the sweep found
**23 of the 27** test26 strings without hi entries (only `tool_lock`, `empty_first_scan_hint`,
`search_hint`, `select_all` had them). Added all 23 (machine-translated, `<!-- review -->` comment
in the file; en 356 / hi 76 now → legacy backlog for T8.6 = 280). No code. Suite after: **267 / 2**
(only the two documented TextFit reds). This closed the audit's immediate §3C check; the going-forward
"same commit" rule is now in plan §6 legend + §8 gotchas + handoff §3.

### T4.7 Export hygiene ✅ (`e518c75`)

Complaint #13 / N10. **Red-proofed first:** `ExportHygieneTest` was written against the old API and
run before any production change — both new tests failed exactly as designed:
`scan save does not copy into Documents when auto copy is off` → row `Hygiene doc.pdf` existed
(unconditional copy), and `downloading twice keeps a single row` → `Hygiene.pdf, Hygiene (2).pdf`.

Built:
- **`Prefs.autoCopyToDocuments`** (default **false**) gates the copy in
  `ScanReviewActivity.saveAndFinish` (the same call site T4.1 edits — §8 gotcha: after T4.1 grep it
  still there + rerun `ExportHygieneTest`). Off → toast is the new `scan_saved`; `save_inside_app`
  deleted (en only, had no hi). en 357 / hi 78 now.
- **`DownloadSaver.savePdfFile(...): SavedFile(uri, name)` replaces** an existing row of the same
  name: `_ID` query by `relative_path + _display_name` → `openOutputStream(uri, "wt")`; if that row
  cannot be written it falls back to the `uniqueName` insert (`Doc (2).pdf`). Pre-Q overwrites the
  `File`. `savePdf(...)` stays the name-only wrapper (`DownloadSaverTest` untouched, green).
  `deleteSaved(context, uri)` = Undo (content → `resolver.delete`, file → `File.delete`).
- **Open/Undo Snackbar:** `DocumentViewerActivity.savePdfToDocuments` and
  `ExportResultActivity.saveResult` (isPdf only — jpeg keeps its toast) show `SavedSnackbar` — a
  Material Snackbar carrying a **custom view** `snackbar_saved.xml` (message + Open + Undo), because
  Material allows only one action button. Open → `PdfOpener` (ACTION_VIEW; file:// via the existing
  FileProvider) → toast `no_pdf_app` if nothing can open it; Undo → `deleteSaved`. Compress / Lock /
  Unlock all funnel through `ExportResultActivity.saveResult`, so this one change covers all three.
- **Strings** `open` (Open / खोलें) and `scan_saved` (Scan saved / स्कैन सहेजा गया) added en+hi in the
  same commit (Rev 4 §3C rule).

**Robolectric stream-routing outcome (the documented risk): worked on attempt 1.**
`query/insert/update/delete` delegate to a registered provider; `openOutputStream(uri, "wt")` falls
through to the real ContentResolver → shadowed `acquireUnstableProvider` →
`ContentProvider.getIContentProvider()` → Transport → our `openFile`. One requirement discovered:
the fake provider **must** call `attachInfo(context, ProviderInfo(authority = "media"))` — without it
Transport's `enforceFilePermission` NPEs on the null context (and `mMyUid = 0` defeats the same-uid
grant). With `attachInfo` the same-uid check grants immediately.

Suite after: **272 tests / 2 failures = only the documented TextFit reds** (267 + 5 new: pref OFF →
no row + default-off asserted · pref ON → exactly one row · download twice → one row and the
snackbar's Open+Undo present · Undo click → row gone · unwritable row → `Doc (2).pdf` fallback with
the old row's bytes untouched).

**Next:** **T4.8** (record `exports=` in meta.txt, delete checkbox, `DeleteExportsTest`) → T4.1 →
T4.2 → T4.3 → T4.4 → (T4.5) → T4.6 → phase-end full build → **G2c**: `alpha2c.apk` + source zip +
3 mds → Downloads → **STOP** (md-review, no screenshots).

### T4.8 Delete removes exported copies ✅ (`2685ff5`)

Complaint #18 / N11 / Q8. **Red-proofed first** (test-only changes: extracted `FakeMediaStore` from
`ExportHygieneTest` into its own shared file, extended with `owner` + owner-scoped query + foreign-row
`SecurityException`; new `DeleteExportsTest` written against the old API): the core test failed
exactly as designed — `T4.8: deleting the document must also remove its recorded export — got
DelEx.pdf` (the dialog deleted the doc and left the row), while the `old 2-line meta … survives
setCategory` guard was green from the start, and all 5 `ExportHygieneTest` stayed green after the
extraction.

Built:
- **`meta.txt` line 4 = `exports=<uri1>|<uri2>`** (optional, append-only): `ScanRepository.addExport`
  (dedupes; pads old 2-line metas like `setCategory` does) + `exportsOf(id)`. `writeMeta` now
  **preserves** an existing `exports=` line when it rewrites; `setCategory`/`renameDocument` already
  survived via `joinToString("\n")` (§8: old metas are 2–3 lines, no trailing newline — the new key is
  ignored by `loadDocument` and by old builds). `duplicateDocument` **strips** exports from the copy —
  exported copies belong to the original.
- **Recording at every `savePdfFile` call site on behalf of a document:** DocumentViewer download
  button, ScanReview opt-in auto-copy (switched from `savePdf` → `savePdfFile` to get the uri), and
  `ExportResultActivity` via new optional `EXTRA_DOC_ID` (`docId: String? = null`, default null so the
  7 existing callers compile unchanged) passed by CompressFlow (`out.id`), LockFlow lock (`locked.id`)
  and unlock (`doc.id`). ZIP/Resize/LongImage correctly pass nothing.
- **`DownloadSaver.findCopies(context, docName)`** — legacy best effort, Q+ only: MediaStore query
  `relative_path='Documents/ScanWhirl/' AND owner_package_name=<ours>`, then **exact** display-name
  regex `^<sanitize(name)>( \(\d+\))?\.pdf$` — never looser; wrapped in try/catch (no provider /
  no permission → empty). Pre-Q returns nothing rather than guessing.
- **Delete flow (both dialogs, `MainActivity.confirmDeleteSelected` + `DocumentViewerActivity.
  confirmDelete`):** copies are collected **before** the confirm (meta must still exist), N > 0 →
  `DeleteExports.checkboxRow` (id `deleteCopiesBox` in `ids.xml`, **default checked** per Q8) via
  `builder.setView` (SwDialog.danger is a plain `MaterialAlertDialogBuilder`). On confirm: document
  delete first and **never blocked**, then `removeCopies` per uri in try/catch — `SecurityException`
  (foreign/older-install row) counts as "could not be removed", no permission prompts, unchecked box
  → nothing touched. Snackbar `deleted_copies_ok` / `deleted_copies_partial` plurals; the **viewer
  defers `finish()` until the snackbar is dismissed** so the message is actually readable (bulk delete
  on Home just shows it — Home stays open). Pre-Q `file://` copies are removed with `File.delete`.
- **Strings:** 3 new plurals `delete_copies`, `deleted_copies_ok`, `deleted_copies_partial` en+hi in
  the same commit (hi had no plurals before — these are its first; `<!-- review -->` comment).

`DeleteExportsTest` 6 tests (plan §6): delete removes the recorded copy (+ message says "1 copy
removed") · unchecked box keeps the row · foreign row skipped, doc still deleted, message says
"could not be removed" · export twice → 1 row, same uri, `exportsOf` lists it once · legacy match
hits only `Doc.pdf`/`Doc (2).pdf` owned by this package (not `Doc (3).pdf` foreign, not
`Doc extra.pdf`, not `Other.pdf`) · old 2-line meta reads + survives `setCategory`.

Suite after: **278 tests / 2 failures = only the documented TextFit reds** (272 + 6 new).

**Next:** **T4.1** (then §8 gotcha: grep `Prefs.autoCopyToDocuments` in `saveAndFinish` + rerun
`ExportHygieneTest`) → T4.2 → T4.3 → T4.4 → (T4.5) → T4.6 → phase-end full build → **G2c**:
`alpha2c.apk` + source zip + 3 mds → Downloads → **STOP** (md-review, no screenshots).

### T4.1 Category rail on Scan Review ✅ (`b8e40c1`)

Additive feature — nothing goes red before it exists (the new UI cannot compile pre-fix, and the
default save path already classified the name the same way), so there is no runnable red-proof here;
the guards are the existing suite (notably `scan review text fits`, already in `TextFitTest` — it must
stay green with the new rail) plus the new `ScanReviewCategoryTest`.

Built:
- **Layout** `activity_scan_review.xml`: `categoryRail` (`ChipGroup`, `singleSelection`) in a
  `HorizontalScrollView` between the app bar and the page grid, plus `categoryCaption` (starts GONE).
  TextFitTest skips anything under a "Scroll" ancestor, so the scrolling chip row is safe; the caption
  is short and wraps within the width.
- **`ScanReviewActivity`**: five `Chip`s built in code under `ContextThemeWrapper(
  ThemeOverlay_ScanWhirl_ChipRail)` (same pattern as Home's filter rail, same `Widget.ScanWhirl.Chip`
  style, labels via `CategoryRules.label`). `setupCategoryRail()` pre-selects
  `CategoryRules.classify(currentTitle())`; a `TextWatcher` re-suggests on every name change **until
  `pickCategory` sets `categoryPickedByHand`**; `UNSORTED` → no chip checked + the caption
  "Not sorted yet — pick one or skip". `saveAndFinish` now passes `category = chosenCategory` to
  `repo.saveScan` (line≈ L478 — the call site T4.7 also edits).
- **§8 gotcha checked:** `Prefs.autoCopyToDocuments` gate still present in `saveAndFinish`
  (line 557) and `ExportHygieneTest` re-run green (5/5) after this task.
- **Strings:** `category_unsorted_hint` en + hi in the same commit (hi review comment).
- **Tests** `ScanReviewCategoryTest` (3): unsorted name → hint visible, exact caption text, zero
  chips checked · typing "Invoice May" → Receipts preselected + hint hidden · tap Study by hand →
  rename to "Invoice May" does not override → save → the opened document's `category == Study`.
  (The plan's "Add ScanReviewActivity to TextFitTest" item was already done — `scan review text fits`
  exists and passes with the rail.)

Suite after: **281 tests / 2 failures = only the documented TextFit reds** (278 + 3 new).

**Next:** **T4.2** (CategoryPickerSheet) → T4.3 → T4.4 → (T4.5) → T4.6 → phase-end full build →
**G2c**: `alpha2c.apk` + source zip + 3 mds → Downloads → **STOP** (md-review, no screenshots).

### T4.2 Category picker sheet ✅ (`bb70f81`)

Additive (the sheet cannot compile pre-fix) → no runnable red-proof; guard = new
`CategoryPickerSheetTest` + existing suite.

Built:
- **`sheet_category_picker.xml`**: title (`pickerTitle` → `category_picker_title`) + empty
  `pickerRows` container on `bg_sheet_glass` (same shape as `sheet_tools`).
- **`CategoryPickerSheet.show(activity, current, onPick)`**: a `BottomSheetDialog` whose rows are
  built in code (the Unsorted row is conditional): **five coloured rows** from `CategoryRules.ALL`
  (dot = `GradientDrawable` oval tinted `CategoryStyle.color`, label = `CategoryRules.label` = the
  value itself per R9, `ic_check` visible only on the `current` row), plus **Unsorted first when
  `current == UNSORTED`** (same order as Home's filter rail). Row `tag` = the stored value; tap →
  `onPick(cat)` → `dismiss()`. No new behaviour wired yet — T4.3/T4.4/T4.6 are the consumers.
- **Strings:** `category_picker_title` en + hi in the same commit (hi review comment).
- **Tests** `CategoryPickerSheetTest` (5): current=Study → exactly `ALL` (no Unsorted) · current=
  Unsorted → `[Unsorted] + ALL`, and `current = null` → no Unsorted row · check visible only on the
  current row (all 6/5 rows) · tap Receipts → `onPick` reports it and the sheet closes · labels ==
  `CategoryRules.label(tag)` and dot colour == `CategoryStyle.color(tag)` + title string matches.
  (One iteration: the title assertion originally looked in the activity, not the dialog — fixed to
  `dialog.findViewById`.)

Suite after: **286 tests / 2 failures = only the documented TextFit reds** (281 + 5 new).

**Next:** **T4.3** (Home chip → picker → `setCategory`; long-press quick-actions; bulk Move +
"Moved N to Study" toast + Home test) → T4.4 → (T4.5) → T4.6 → phase-end full build → **G2c**:
`alpha2c.apk` + source zip + 3 mds → Downloads → **STOP** (md-review, no screenshots).

---

### T4.3 Change category from Home ✅ (`9e531ab`)

**Plan conflict found & resolved (owner decision):** T3.7 says "Long-press or `Select` enters
selection mode" (built, 8 green assertions) while T4.3 says "Long-press quick-actions sheet: Rename,
Move to category, Share, Delete" — one gesture, two behaviors. Asked the owner mid-build: **the
quick-actions sheet wins**; selection mode is entered via the `Select` button only. The 8 long-press
assertions were updated in this same commit to `Select` + tap (plus `UiSmokeTest`'s test renamed to
`select button enters selection mode…`, and the two "Move is disabled until T4.3" assertions flipped
to enabled — §4.2's "Move disabled until T4.3" rule is now discharged).

**Red-proof first** (test-only, existing symbols only so it compiled pre-fix): all 5 new
`HomeCategoryActionsTest` cases failed exactly as designed — `T4.3: long-press must open the
quick-actions sheet (not selection mode)`, `the category chip must open the picker`, NPE/no-dialog
on `Move must open the category picker`, and the two full-flow cases died at the first missing
dialog; `SelectionBarTest` baseline stayed 5/5 green.

Built:
- **Card chip → picker** (`ScanListAdapter` gets a 5th `onCategory` callback; `catChip` gets a click
  listener that forwards to `onClick` while selecting so no dead zone appears) →
  `CategoryPickerSheet.show(this, doc.category) { repo.setCategory(id, cat); refreshList() }`.
- **Long-press → `sheet_quick_actions.xml`** (`BottomSheetDialog`, title = doc name, four static
  rows: Rename / Move to category / Share in `text_hologram_hi`, Delete in `laser_danger`; each
  dismisses the sheet first). Rename = `SwDialog.input` prompt → `repo.renameDocument` → refresh;
  Share = `ShareUtils.share`; Delete = `confirmDelete` (the T4.8 copy-aware confirm, refactored from
  `confirmDeleteSelected` to take an id list — single-doc confirm gets `delete_title` +
  `delete_message`, bulk keeps the plurals); Move = `moveCategory`.
- **Bulk Move** (`btnSelMove` wiring + `isEnabled = n > 0` per §4.2): picker (current = null, no
  check row) → `repo.setCategory(ids, cat)` → exit selection → toast `moved_n_to_cat`
  ("Moved 1 to Study"). Single-card moves skip the toast (the chip visibly changes).
- **Strings:** `move_to_category` + `moved_n_to_cat` en+hi in the same commit; also filled the
  pre-existing English-only hi gaps for `rename`, `rename_title`, `share_pdf` because the new sheet
  shows them (hi review comment).

**Test-run gotcha (§8-style):** entering selection via `btnSelect` re-lays out the list, so
`list.findViewHolderForAdapterPosition(0)` right after the click returns **null** — every migrated
site now captures the card view *before* clicking `Select` (the pattern the already-passing
`UiSmokeTest.select button…` used). One iteration needed for HomeScreenTest's second entry point in
the same test.

**Tests:** `HomeCategoryActionsTest` (8): 5 red-proofed then green (long-press sheet · chip → picker ·
Move → picker · bulk move = category Study + selection exited + toast "Moved 1 to Study" · chip move
= Receipts) + 3 guards added with the fix (sheet rows carry `rename`/`move_to_category`/`share_pdf`/
`delete` labels + Move row opens the picker · quick-action rename saves "Physics notes" ·
quick-action delete removes the doc after confirm).

Suite after: **294 tests / 2 failures = only the documented TextFit reds** (286 + 8 new).

**Next:** **T4.4** (QuickSortQueue + sheet + X-close, `QuickSortQueueTest`: skip n → close →
`remaining == total − n`) → (T4.5 optional) → T4.6 → phase-end full build → **G2c**:
`alpha2c.apk` + source zip + 3 mds → Downloads → **STOP** (md-review, no screenshots).

---

### T4.4 Quick Sort ✅ (`246a5ca`)

**Red-proof first** (existing symbols only: `R.id.nudge`, `R.id.btnTools`, the wireframe's literal
labels, so it compiled pre-fix): both new-behavior cases failed exactly as designed — `T4.4: the
nudge must be visible while an unsorted doc exists` (T3.1 left the container GONE by owner
decision) and `T4.4: the Tools sheet must carry the Quick sort tile` (only 8 tiles existed); the
absence-guard (`nudge stays hidden…`) passed pre-fix and still guards post-fix.

Built:
- **`util/QuickSortQueue.kt`** (pure): `from(docs)` keeps Unsorted + Other in library order;
  `current()`, `pick(category)` (requires ∈ `CategoryRules.ALL`, returns the doc so the caller
  saves it immediately), `skip()`, `remaining()`, `done()`, `progress()` → `Progress(done, total)`
  with 1-based `position` ("2 of 7" in §4.8). The queue never writes storage.
- **`sheet_quick_sort.xml`** (§4.8): header = `Quick sort` + `qsProgress` ("N of M") + **X close
  button top-right** (48dp, `@string/close`; Rev 4 §3D — closing mid-queue just closes, no
  confirmation), 180dp thumbnail (`PageThumbLoader`, 640px, `centerCrop`), doc line
  "name · N pages", `qsChips` ChipGroup holding the five category chips + Skip (built in code with
  `ThemeOverlay_ScanWhirl_ChipRail`, tag = stored value, skip chip tagged `"skip"`), and a `qsDone`
  "All sorted" banner that replaces the queue UI when the queue empties. Pick → haptic
  `CLOCK_TICK` (plan) + `repo.setCategory` + `render()` + `refreshList()`; Skip only advances; X
  only dismisses.
- **Home nudge** — T3.1's `R.id.nudge` container finally fills: a `bg_glass_card` label built in
  onCreate, `updateNudge()` hooked into `applyList()` (the T3.10 renderHome path, unfiltered
  snapshot) — visible only while Unsorted + Other > 0, text `quick_sort_nudge` =
  "N unsorted · Quick sort ›". The click listener sits on the **whole card** (label inert so taps
  bubble) — that is also the R6-sized target.
- **Tools sheet 9th tile**: `tile_quick_sort` (ids.xml) + `Tile(..., R.drawable.ic_sort, free)` in
  `showToolsSheet` — §4.3 "T4.4 adds a 9th tile"; the longest-word column probe sees its label
  automatically.
- **Strings:** `quick_sort`, `quick_sort_nudge`, `qs_progress`, `all_sorted`, `skip` en + hi in the
  same commit (hi review comment).

**Iterations (2, both caught before the commit):**
1. `processDebugResources` — this Material version's ChipGroup wants
   `app:chipSpacingHorizontal`/`chipSpacingVertical`, not `spacingHorizontal`/`spacingVertical`.
2. Red → green run exposed the real design slip: the listener was on the nudge's *label*, so a tap
   on the card (what the test drives via `nudge.performClick()`) did nothing — moved to the
   container. Plus 3 self-inflicted pure-test bugs (message passed as `expected`, a "none
   categorized" assertion that also counted the never-queued Study doc, `position` asserted on an
   empty queue where it is undefined by design).

**Tests:** `QuickSortQueueTest` (6, pure JUnit): only Unsorted/Other join · pick hands back +
advances, empty = no-op · `pick` refuses non-categories · **plan case: skip n× → close →
`remaining == total − n`, none categorized** · progress 1→2→3 of 3 · empty queue = "All sorted"
state. `QuickSortTest` (5): 3 red-proofed (nudge count + opens the sheet · Tools sheet carries
"Quick sort" by label · nudge hidden with nothing to sort) + 2 guards added with the fix (pick
Receipts → saved instantly → advances "1 of 2" → "2 of 2" → Skip → "All sorted" banner + chips
gone · X closes mid-queue → nudge back to "1 unsorted", exactly one doc still Unsorted, closed
sheet not showing). `HomeScreenTest`'s tools-sheet test updated 8 → 9 tiles in the same commit.

Suite after: **305 tests / 2 failures = only the documented TextFit reds** (294 + 11 new).

**Next:** **T4.6** (doc chip → picker, remove the old "Categorize" dialog) → phase-end full build →
**G2c**: `alpha2c.apk` + source zip + 3 mds → Downloads → **STOP** (md-review, no screenshots).

---

### T4.6 Document screen chip ✅ (`99fa701`)

**Red-proof first** (existing symbols only: `R.id.docCategoryChip`, `R.id.pickerRows`): both new
cases failed exactly as designed — `T4.6: the chip must open the category picker` (the chip had no
click listener) and `the picker must be open` on the full recategorise flow; the other 8
`Phase5ScreensTest` cases stayed green throughout.

Built:
- `binding.docCategoryChip.setOnClickListener { promptCategorize() }` in `onCreate` (the chip has
  been display-only since test25 Phase 5C).
- `promptCategorize()` reimplemented on `CategoryPickerSheet.show(this, doc.category) { … }` — the
  old `SwDialog.sheet(…).setSingleChoiceItems` list dialog is **gone**; a pick saves on IO +
  `reloadDocument()` (the chip's repaint is the confirmation — no toast, same decision as T4.3's
  single-card moves). Orphaned string `categorize_title` removed from `values` (it never had a hi
  entry, so nothing to mirror).
- **D-F respected:** the overflow `Categorize` item (`action_categorize`) *stays* and now opens the
  picker too — a deliberate duplicate of the chip until **T5.2** removes the overflow entry (plan
  D-F + T5.2; the More sheet keeps `promptCategorize` as its handler).

**Tests:** `Phase5ScreensTest` +2 → 10 in the class: `document category chip opens the picker`
(chip → dialog carrying `pickerRows`) · `picker choice recategorises the document and repaints the
chip` (RECEIPTS pick persists on the doc + chip becomes "● RECEIPTS"). Both red first, green after.

Suite after: **307 tests / 2 failures = only the documented TextFit reds** (305 + 2 new).

**Next:** **Phase 4 complete** (T4.5 skipped as optional) → phase-end full build
(`test assembleRelease -PtestBuild --continue`, both variants) → **G2c**: `alpha2c.apk` + refreshed
source zip + 3 mds → Downloads → **STOP** (md-review only, no screenshots — the reviewer's md edits
against the 9-point G2c checklist are the review).

---

### Phase-end build + G2c delivered ✅ · Rev 5 adopted (`docs commit`)

**Phase-end full build** (`test assembleRelease -PtestBuild --no-daemon --continue`, both
variants): **307 tests / 2 failures = only the known-red TextFit pair** (`page editor`,
`document screen`) on debug *and* release; `assembleRelease` green. One restart interruption
re-ran cleanly (tree unchanged). Release APK: `app/build/outputs/apk/release/app-release.apk`
(6.6 MB) → copied to `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test26-alpha2c.apk`.

**G2c deliverables complete:** `alpha2c.apk` + refreshed source zip (278 files @ `ce54f5a`) +
3 md copies in sync → Downloads → **STOP for md-review** (9-point checklist in the plan's G2c
block; NO screenshots — reviewer's md edits against the checklist *are* the review).

**Rev 5 (owner decision, same day): testing strategy changed to phase-end runs.**
- Per task: one commit, **compile** (`:app:compileDebugKotlin`) + **tests written** in the same
  commit — but **not executed**. Red-proof-first suspended (impossible without running).
- Full suite runs **once at the end of each phase** (+ T9.2), judged by XML parse.
- 2-failed-attempts → `BLOCKERS.md` + halt now applies **at run points** (triage reds against the
  phase's expected scope first).
- §0 rules 4–5 rewritten in the plan; STATUS/handoff updated.

**Next:** Phase 5 under Rev 5 — **T5.1** (page-editor dock §5.4) → T5.2 (document screen rebuild,
deps T4.6+T4.8 ✅) → T5.3 → T5.4 → T5.5 (TextFit sweep, the phase's test run).

---

### T5.5 — Phase-5 TextFit sweep ✅ (`docs commit`)

**Root cause of `btnMore` red:** label wrapped mid-word at font scale 1.3 — horizontal text area too narrow (~69px vs ~72px needed for "More" at 15.6sp), not a vertical padding issue. Fix: `android:paddingHorizontal="2dp"` on all four document-dock buttons (`btnShare` w2, `btnEdit`, `btnText`, `btnMore`), mirroring the page editor dock (T5.1, already green). Dock `paddingVertical`, `minHeight 56dp`, and weights 2/1/1/1 unchanged.

**Toolbar menu wiring:** added `onCreateOptionsMenu` (inflates `menu_document`) + `onOptionsItemSelected` (routes `action_save_pdf` → `savePdfToDocuments()`) to `DocumentViewerActivity` so Robolectric's `clickMenuItem` used by `ExportHygieneTest` reaches the save handler.

**Settings subtitle:** shortened `settings_auto_copy_to_documents_summary` to "Copies saved to Documents/ScanWhirl." (en + hi, marked `<!-- review -->`) because the previous text measured 327px vs 324px available at scale 1.0. Viewing card now 5 children (3 rows + 2 dividers, incl. hairline divider after `switchAutoEnhance`).

**Verification:** TextFitTest all 6 green (document + settings at scales 1.0 + 1.3), Phase5ScreensTest green, ExportHygieneTest green. Full phase-end run: **630 tests / 0 failures / 0 skipped** (debug + release), `assembleRelease` green → Phase 5 checkpoint GREEN.

---

### T6.1 — OCR Editor pure cleanup functions ✅

**New files:** `app/src/main/java/com/scanwhirl/app/util/OcrCleanup.kt` + `app/src/test/java/com/scanwhirl/app/util/OcrCleanupTest.kt`.

**Functions:**
- `joinLines(text)`: joins single newlines within a paragraph to spaces, keeps blank-line paragraph breaks, de-hyphenates `exam-\nple` → `example` when next char is lowercase, never joins bullet/list lines (`-`, `•`, `*`, `N.`).
- `fixSpaces(text)`: collapses space/tab runs to one, trims line ends, collapses 3+ newlines to 2.
- `removeGaps(text)`: collapses every blank-line run to a single newline, trims.

**Tests:** 12 cases covering hard-wrapped paragraphs, hyphenated words, dash-vs-hyphen distinction, bullet lists, numbered lists, paragraph-end hyphens, trailing spaces, multi-blank-line collapse, empty strings, and combined join→fix pipeline. All green.

**Suite impact:** +12 tests (test count rises in Phase 6 per amendment).

---

### T6.2 — OCR Editor activity + layout + OcrEditorTest ✅

**New files:**
- `app/src/main/java/com/scanwhirl/app/ui/OcrEditorActivity.kt` — full-screen OCR editor per §4.7. Doc-backed (EXTRA_DOC_ID → repo.ocrDoc) or temp (EXTRA_TEXT_PATH → cache file). Page tabs, editable EditText (14sp, multiline), word-count label, cleanup bar (Join lines/Fix spaces/Undo/Remove gaps via OcrCleanup), action bar (Copy/Share/Save). Dirty-state back confirm (Save/Discard/Cancel). `windowSoftInputMode="adjustResize"` in manifest. Cache file deleted in `onDestroy`.
- `app/src/main/res/layout/activity_ocr_editor.xml` — toolbar, page tabs, EditText, word count, cleanup bar, action bar.
- `app/src/main/res/menu/menu_ocr_editor.xml` — overflow (Copy all/Share all/Save as .txt/Re-scan).
- `app/src/test/java/com/scanwhirl/app/util/OcrEditorTest.kt` — 4 tests: edit→Save→SearchMatcher finds corrected word; Back while dirty shows confirm dialog; empty OCR state; temp mode cache file read+cleanup.

**Modified:** strings.xml/values-hi (OCR strings, `share_text` marked `<!-- review -->`), AndroidManifest.xml (OcrEditorActivity + adjustResize).

**Suite impact:** +4 tests.

---

### T6.3 — Wire OcrEditorActivity into OcrFlow + TextFitTest ✅

**OcrFlow.kt:** `runOcrForScan` now launches `OcrEditorActivity` with `EXTRA_DOC_ID` instead of `showOcrTextDialog`. `runOcrForPdf`/`runOcrForImage` write OCR text to `cacheDir/ocr_<ts>.txt` and launch with `EXTRA_TEXT_PATH` (never in an Intent extra). `showOcrTextDialog` deleted.

**TextFitTest.kt:** added `ocr editor text fits` case (7 total, all green at scales 1.0 + 1.3).

**Strings:** added `share_text` = "Share text" (en + hi, `<!-- review -->`), used in `shareAllPages()` chooser title.

**Phase-end full run:** **666 tests / 0 failures / 0 skipped** (debug + release), `assembleRelease` green → Phase 6 checkpoint GREEN. Test count rose from 630 (P5) to 666 (P6).

---

### Phase 7 — Remove the Earth ✅

**Tag `pre-globe-removal` created before any deletion.**

**T7.1:** Deleted `ui/globe/` (4 files: GlobeHomeActivity, GlobeScene, HolographicEarthView, GlobeRingShim), globe tests (GlobeSceneTest, GlobeHomeActivityTest), `SwTransitions.kt` + `SwTransitionsTest.kt`, GlobeHomeActivity manifest entry. −2,838 lines.

**T7.2:** Removed `Prefs.globeHomeEnabled`/`setGlobeHomeEnabled` + `KEY_GLOBE_HOME`, Settings globe home row + switch, all `globe_*` and `settings_globe_home*` strings (en + hi). Also deleted globe-only resources that referenced deleted strings: `activity_globe_home.xml`, `globe_enter_warp.xml`, `globe_exit_warp.xml`, `ic_globe.xml`, `bg_hud_scrim.xml`, `sel_check_orbital.xml`, `bg_fab_orbital.xml`, `sel_radio_orbital.xml`, `sel_chip_orbital.xml`, `bg_orbit_dot.xml`. Replaced `sel_radio_orbital`/`sel_check_orbital` theme references. Replaced `bg_hud_scrim` in `item_scan.xml` with `@color/sf_appbar`. Removed `sel_radio_orbital`/`sel_check_orbital` from `Dialog` theme.

**T7.3:** Removed `bg_fab_pulse.xml` (unused globe resource, lintDebug confirmed).

**SwMotion.kt + SwMotionTest kept** (Phase 8 T8.1/T8.3 need `SwMotion.ambientAllowed`). `SwMotionTest`'s globe-dependent `fab pulse` test removed; remaining 3 ambient-gate tests kept.

**Phase-end full run:** **594 tests / 0 failures / 0 skipped** (debug + release), `assembleRelease` green → Phase 7 checkpoint GREEN. Test count fell from 666 (P6) to 594 (P7) — the drop is exactly the deleted globe tests.
