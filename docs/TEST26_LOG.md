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

**Next:** build mode → **T3.14** (values-hi) → Phase 4 (T4.7 first) → phase-end full build →
**G2c**: `alpha2c.apk` + source zip + 3 mds → Downloads → **STOP** (md-review, no screenshots).

---
