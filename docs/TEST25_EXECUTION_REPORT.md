# ScanWhirl test25 — Execution Report (Handoff)

> **Purpose:** Standalone summary of everything executed under the test25 master plan, for
> handoff to another agent/reviewer. The authoritative, phase-by-phase detail lives in
> **`docs/SCANWHIRL_TEST25_MASTER_PLAN.md`** (the "plan MD") — this report is the fast-read
> companion to it.
>
> - **Project root:** `/home/cybium/ScanWhirl` (Linux / WSL)
> - **Report date:** 2026-11-07
> - **Overall status:** ✅ **Phases 0–6 ALL COMPLETE — test25 delivered**
> - **Target version:** `versionCode 25` / `1.1.1-test25`
> - **Package (test builds only):** `com.scanwhirl.app.test` · label `ScanWhirl Test` ·
>   launcher `com.scanwhirl.app.ui.globe.GlobeHomeActivity`

---

## 1. Objective

Execute the test25 master plan: a **visual overhaul + architecture** pass over the ScanWhirl
Android app (test flavor), delivered as `test25` on top of `test24`, under a strict set of
hard constraints (locked view IDs, test-locked dialog behaviors, system-fonts-only, ambient-
gated animations). Seven phases: plan → build → verify → mark-done, each gated on a green
full build (`./gradlew test assembleRelease -PtestBuild --no-daemon`).

## 2. Final status

| Phase | Name | Status | Tests at close |
|-------|------|--------|----------------|
| 0 | Foundations & Theme | ✅ COMPLETE | 180 |
| 1 | Universal Dialog Architecture (53 call sites → `SwDialog`) | ✅ COMPLETE | 180 |
| 2 | Shelf Overhaul (`item_scan.xml`, tool cards, FAB ring, sort pill) | ✅ COMPLETE | 180 |
| 3 | Holographic Globe Overhaul (HUD, rings, ring labels, chips) | ✅ COMPLETE | 192 |
| 4 | Motion & Transitions (`SwMotion`, `SwTransitions`, gated pulse) | ✅ COMPLETE | 201 |
| 5 | Remaining Screens (Settings/Review/PageViewer/DocViewer/Merge/Export) | ✅ COMPLETE | 209 |
| 6 | Verification & Deliverables (v25 bump, APK + source zip, commits) | ✅ COMPLETE | 209 |

**Final verification (Phase 6, as run 2026-11-07):**
- Full build → `BUILD SUCCESSFUL` (2m 33s).
- Parsed `app/build/test-results/{testDebugUnitTest,testReleaseUnitTest}/*.xml` →
  **209 tests / 0 failures / 0 errors per variant = 418 total**.
- `aapt2 dump badging` on the release APK:
  - `package: name='com.scanwhirl.app.test' versionCode='25' versionName='1.1.1-test25'`
  - `application-label:'ScanWhirl Test'`
  - `launchable-activity: name='com.scanwhirl.app.ui.globe.GlobeHomeActivity'`
- Audits clean: 83/83 locked IDs present; only additive new ID `docCategoryChip`; no new
  `fontFamily`; exactly one `INFINITE` animator in `app/src/main` (pre-existing, gated on
  `SwMotion.ambientAllowed`); no `robolectric.properties`; no `com.scanwhirl.app` app-config
  changes; existing test files untouched (2 modified test files are Phase 2/3 work from
  earlier sessions, documented in the plan MD).

**Test suite breakdown (209/variant):**

| Suite | Tests | | Suite | Tests |
|-------|------:|-|-------|------:|
| UiSmokeTest | 78 | | GreetingTest | 3 |
| ScanRepositoryTest | 22 | | ScanFilterTest | 5 |
| GlobeSceneTest | 16 | | CategoryPersistTest | 4 |
| ImageToolsTest | 16 | | SearchMatcherTest | 4 |
| GlobeHomeActivityTest | 15 | | PageThumbLoaderTest | 3 |
| PdfGeneratorTest | 10 | | SwMotionTest | 4 |
| DownloadSaverTest | 9 | | SwTransitionsTest | 5 |
| CategoryRulesTest | 6 | | ExternalVerifyExport | 1 |
| Phase5ScreensTest | 8 | | | |

## 3. Phase-by-phase summary

### Phase 0 — Foundations & Theme
Deleted the qualified override dirs that silently broke dialog theming on API 27+
(`values-night/*`, `values-v27/*`); configured `Theme.ScanWhirl` (Material3 Dark + dialog
overlays + sheet/danger/popup/animation styles); added D3 category colors; lightened
`text_hologram_dim` for WCAG; created `SpaceBackgroundDrawable.kt` (void gradient + static
stars, wired as window background in `BaseActivity`); fixed `ScanListAdapter` stroke erasure
(1dp idle / 2dp selected, never 0); created `CategoryStyle.kt` (pure-Kotlin, JVM-testable).

### Phase 1 — Universal Dialog Architecture
Created `SwDialog.kt` (`card()` / `sheet()` / `danger()` / `progress()` / `input()` +
`MaterialAlertDialogBuilder.showSw()` extension — all return `androidx.appcompat.app.AlertDialog`
so Robolectric's `ShadowDialog.getLatestDialog() as AlertDialog` cast keeps working).
Migrated **all 53 dialog call sites** across 7 files off `AlertDialog.Builder`. Added
`dialog_pro_panel.xml` (Pro dossier panel layout).

### Phase 2 — Shelf Overhaul
Rewrote `item_scan.xml`: category rail (`catRail`, runtime-tinted), portrait thumb 60×80dp,
`bg_chip_void` page badge, `catChip` label, stacked `btnView`/`btnEdit` action column,
`sel_stroke_cyan` stroke, `sw_press` state-list animator. Pro Suite card got the gold
`bg_pro_banner` via the **inner-container trick** (cardBackgroundColor can't take a drawable
— XML shape crashes inflation); the four `tool*` buttons moved to `Widget.ScanWhirl.Button.Pro`
(52dp, `bg_pro_tile`); `btnSort` became a pill with new `sortLabel` (additive ID); empty state
swapped to `ic_empty_orbit`; FAB wrapped in a 96dp `fabHost` FrameLayout with static orbital
rings (`setFabVisible()` helper keeps `UiSmokeTest`'s direct `fabScan` visibility assertions
green); `scanList` got 96dp bottom padding.

### Phase 3 — Holographic Globe Overhaul
`GlobeScene.kt`: `topReserve` + `bandY()` layout band, five fixed `RING_RX` radii, compact
home-orbit radius, `focusRing` state, `ringAlphaFor()`, `categoryCounts()` with dim factors.
`HolographicEarthView.kt`: dashed ellipse ring tracks, **counter-clockwise bottom-centred
ring labels** (125°→55°, upright reading — fixed a post-Phase-3 upside-down-label bug),
category accent bars, `drawHintInCanvas=false` while keeping the `hintText` property (tests
read it). `activity_globe_home.xml`: HUD scrim, title/status, list/settings buttons, hint
pill, 5-chip dock, `fabPulse1/2`, `bg_fab_orbital` FAB container. `GlobeHomeActivity.kt`:
status readout, dock wiring, List→Shelf + gear→Settings (D5), topReserve posted from HUD.
**Key fix:** `GlobeHomeActivityTest` was the only Robolectric UI test missing
`@GraphicsMode(MODE.NATIVE)` → screenshots were star-only; fixed with the annotation + real
JPEG fixture. Ring-label geometry locked by tests (`GlobeSceneTest`).

### Phase 4 — Motion & Transitions
New `util/SwMotion.kt`: `ambientAllowed(context, fingerprint = Build.FINGERPRINT)` =
`ANIMATOR_DURATION_SCALE > 0 && isInteractive && fingerprint != "robolectric"` (check order
chosen so all three branches are testable). New `ui/SwTransitions.kt`: `warpToShelf` /
`warpBackToGlobe` (finite `overridePendingTransition` warps, not ambient-gated), and
`revealScanLaunch()` — FAB radial reveal, 260ms, `PathInterpolator(0.4f, 0f, 0.2f, 1f)`,
runs `onEnd` **synchronously** when ambient is off (the scan test depends on same-frame
launch), guarded by `revealSession`/`revealPending` state machine. FAB pulse: the **only
`INFINITE` animator in `app/src/main`**, started in `onResume` only after
`SwMotion.ambientAllowed`, cancelled/reset in `onPause`. Gated: FAB pulse, earth rotation,
ring drift, star twinkle, ring shimmer, scan reveal. Not gated: 30fps Choreographer loop,
`stateT`, flick inertia, finite warps. `GlobeScene.ambientMotion` default `true` is
load-bearing (tests drive `update()` directly).

### Phase 5 — Remaining Screens
Pre-build findings drove the implementation: `MaterialCardView` silently drops XML
`android:background` (→ page-grid glass card set **in code**), and a card stops drawing its
stroke once its background is overwritten (→ selection rides `root.isSelected` →
`bg_glass_card_ripple`'s `state_selected` branch — documented deviation).
- **5A Settings:** rows grouped into glass section cards (Appearance / Language / Viewing /
  About) with 1dp cyan hairline dividers; D1 → `rowTheme` GONE + `subTheme = "Orbital Dark"`
  in code (`chooseTheme()` left dead-but-harmless); cyan-tinted row icons (`ic_palette`,
  `ic_font`, …), `ic_chevron_right` replacing literal `›`, explicit `thumbTint`/`trackTint`
  on all 4 switches.
- **5B Review/PageViewer:** page-grid cells get code-set `bg_glass_card_ripple`, `pageNum`
  badge → `bg_chip_void`; review + viewer docks → `bg_dock_glass` floating pills (8dp
  horizontal margin), buttons → `Widget.ScanWhirl.Button.{Primary,Dock}`.
- **5C DocumentViewer:** `proToolsPanel` via Phase 2 inner-container trick; 5 `.Pro` buttons
  restyled; bottom dock → glass pill; **new additive ID `docCategoryChip`** wired in
  `reloadDocument()` (`● <TAG>` colored by `CategoryStyle`).
- **5D Merge/PDFViewer/Export:** glass docks; AppBar **hairline via `android:foreground`**
  (`bg_appbar_hairline` — worked on all three, the 2-drawable fallback was not needed);
  `item_merge_pdf` rows restyled to theme glass cards.
- New tests: `Phase5ScreensTest.kt` (8 tests, new file only — existing test files untouched).

### Phase 6 — Verification & Deliverables (this session)
1. Pre-commit audit (constraints re-checked) → **commit #1 `52562d1`** "Phases 2–5".
2. Version bump: `app/build.gradle.kts` → `versionCode = 25`, `versionName = "1.1.1-test25"`
   (exactly 2 changed lines).
3. Full build + test → `BUILD SUCCESSFUL`, 209/209 per variant (418 total), 0 failures/errors.
4. `aapt2 dump badging` → package/label/launcher/version all correct (see §2).
5. APK copied → `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test25.apk` (6,533,325 bytes).
6. Plan MD updated → `✅ PHASE 6 — COMPLETE` with as-run record + deviations.
7. Source zip → `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test25-source.zip`
   (451,607 bytes, 277 files). **Deviation:** the plan's original `zip` command would have
   shipped gitignored signing material (`keystore/`, `keystore.properties`) and build junk —
   the as-run command adds excludes for those plus `.gradle/`, `.kotlin/`, `dist/`,
   `captures/`, `local.properties`, `*.apk`. Verified: zero forbidden entries, final MD
   included.
8. **Commit #2 `49488cf`** "Phase 6 — versionCode 25, verification record, deliverables".
   Working tree clean. **No push** — remote (`origin/master`) untouched.

## 4. Locked product decisions (do not change)

| # | Decision |
|---|----------|
| D1 | Orbital-only dark theme. `rowTheme` kept in XML but `visibility=GONE`; `subTheme.text = "Orbital Dark"` set in code. |
| D2 | All dialogs stay `AlertDialog` (built via `MaterialAlertDialogBuilder` + `showSw()`). Sheet look = window gravity + animation only. |
| D3 | Category palette: Study `#38EF7D`, Receipts `#FF8A4C`, IDs `#5B9DFF`, Forms `#B07CFF`, Other `#94A3B8` (via `CategoryStyle`). |
| D4 | System fonts only — never set `fontFamily` in text appearances. |
| D5 | Globe HUD gear icon opens SettingsActivity. |

## 5. Hard constraints (NEVER violate)

- **Package:** `com.scanwhirl.app.test` ONLY. Never touch `com.scanwhirl.app` app config.
- **Label:** `ScanWhirl Test`. **Launcher:** `com.scanwhirl.app.ui.globe.GlobeHomeActivity`.
- **83 locked view IDs** (82 app-defined + `snackbar_action` = Material library) must never be
  removed or renamed — full list in the plan MD (`toolResize toolCompress toolEdit toolMerge
  proSuiteCard proSuiteChevron proSuiteGrid toolVault toolOcr toolLockPdf toolLongImage
  emptyState emptyTitle emptySubtitle fabScan searchInput btnSort appNameText greetingText
  proBadge globeView fabGlobeScan btnGlobeSearch globeSearchInput globeMatchCount btnSearchDone
  globeEmpty scanList toolbar pageList pageGrid cropView btnCrop titleRow toolsRow pager
  btnFilter btnDelete snackbar_action selectBar searchPanel pageIndicator cropOverlay btnSavePdf
  btnRotateRight btnClose btnApplyCrop vaultBanner titleInput switchWallpaper switchGreeting
  subTheme scanName scanMeta rowWallpaper rowViewer rowTheme rowPrivacy rowLanguage rowGreeting
  rowFont rowAbout resultStats resultPreview proToolsPanel pageCount pageBadge lockBadge
  docLockBadge checkSelect btnView btnEdit btnVaultDoc btnUnlockPdf btnShareResult btnSaveResult
  btnProtect btnProTools btnProSuite btnOcr btnMerge btnLongImage btnAddPdf`). New IDs are
  additive only (`docCategoryChip` is the only Phase 5 addition; `sortLabel`, `emptyOrbit`,
  `fabHost`, `fabRing`, `fabPulse*` etc. are additive from earlier phases).
- **Test-locked behaviors:** all dialogs remain `androidx.appcompat.app.AlertDialog` (cast in
  tests); list dialogs use `setItems()`/`setSingleChoiceItems()` (tests call
  `dialog.listView.performItemClick`); exactly 2 rows in scan chooser dialogs;
  `subTheme.text.isNotEmpty()`; meta format regex `^N pages · X KB · d MMM$`;
  `ScanListAdapter` stroke 1dp idle / 2dp selected (never 0dp).
- **`SwMotion.ambientAllowed`:** every `INFINITE` animator must check it before starting —
  Robolectric hangs otherwise. (Exactly one exists: the globe FAB pulse.)
- **No `fontFamily`, no `robolectric.properties`, no Gradle edits** (beyond the Phase 6
  version bump), system fonts only.
- **Never `FastOutSlowInInterpolator`** — not on the classpath. Use
  `PathInterpolator(0.4f, 0f, 0.2f, 1f)` (same M3 curve).
- **Existing test files are not modified** — new tests go in new files only.

## 6. Repo gotchas / conventions (learned the hard way)

- `app:cardBackgroundColor` **cannot** reference a drawable → inflation crash. Use the
  inner-container trick (background + 1dp margin under the card stroke).
- `MaterialCardView` **drops XML `android:background`** (applied before card init completes)
  → set glass backgrounds in code post-inflate; selection then rides `isSelected` +
  `state_selected`, not `strokeWidth`.
- Robolectric UI screenshot tests need `@GraphicsMode(GraphicsMode.Mode.NATIVE)` or renders
  are star-only/blank; fake document URIs must carry a real JPEG for `BitmapFactory`.
- Robolectric tests using infinite-anim potential must use `idleFor(Duration)`, never bare
  `idle()`.
- `resources.getQuantityString(id, n, n)` for plurals (`getString(R.plurals.…)` throws).
- JUnit test methods returning `= runBlocking { … }` must be `runBlocking<Unit>` ("should be
  void").
- Targeted test runs: `./gradlew :app:testDebugUnitTest --tests "…"` — the plain `test` task
  rejects `--tests`.
- `tools:ignore` requires `xmlns:tools` in the layout (dropped where absent).
- The session image viewer can serve **stale PNG frames** — verify renders with PIL pixel
  sampling instead of eyeballing.

## 7. Git history (test25)

```
49488cf  test25: Phase 6 — versionCode 25, verification record, deliverables
52562d1  test25: Phases 2-5 — glass visual overhaul, holographic globe, SwMotion
         transitions, settings/review/editor rework
58c1258  test25: Phase 0 complete (theme/foundations) + Phase 1 complete (dialog
         migration) + Phase 2 partial (item_scan.xml overhaul)
0e0e17d  test24: Orbital Archive & Neural Vault visual design system (previous release)
```

All test25 work is **local only** — nothing pushed to `origin`.

## 8. Deliverables

| File | Location | Size |
|------|----------|------|
| `ScanWhirl-1.1.1-test25.apk` | `/mnt/c/Users/Cybium/Downloads/` | 6,533,325 B |
| `ScanWhirl-1.1.1-test25-source.zip` | `/mnt/c/Users/Cybium/Downloads/` | 451,607 B (277 files) |

Source zip excludes: `.git/`, `.gradle/`, `build/`, `.kotlin/`, `dist/`, `captures/`,
`keystore/`, `keystore.properties`, `local.properties`, `*.apk`.

## 9. Key file map

**New Kotlin:** `ui/SpaceBackgroundDrawable.kt`, `ui/SwDialog.kt`, `util/CategoryStyle.kt`,
`ui/SwTransitions.kt`, `util/SwMotion.kt`
**Modified Kotlin:** `MainActivity.kt` (dialogs, adapter stroke, catRail, FAB host, finish
warp), `SettingsActivity.kt` (dialogs, D1), `DocumentViewerActivity.kt` (dialogs, chip),
`GlobeHomeActivity.kt` (dialogs, HUD/dock, gated pulse), `GlobeScene.kt`,
`HolographicEarthView.kt`, `PageViewerActivity.kt`, `MergePdfActivity.kt`,
`ScanReviewActivity.kt`, `pagegrid/PageGridView.kt`, `BaseActivity.kt`
**New layouts:** `dialog_pro_panel.xml`, `item_scan.xml` (rewritten)
**Modified layouts:** `activity_main`, `activity_settings` (rewritten), `activity_globe_home`,
`activity_scan_review`, `activity_page_viewer`, `activity_document_viewer`,
`activity_merge_pdf`, `activity_export_result`, `activity_pdf_viewer`, `item_page_grid`,
`item_merge_pdf`
**New tests:** `SwMotionTest.kt`, `SwTransitionsTest.kt`, `Phase5ScreensTest.kt`
**Modified tests:** `globe/GlobeHomeActivityTest.kt`, `globe/GlobeSceneTest.kt` (Phase 2/3
work from earlier sessions; every other test file untouched)
**Docs:** `docs/SCANWHIRL_TEST25_MASTER_PLAN.md` (authoritative plan + as-run records),
this report.
**Render verification PNGs** (regenerated by the suites): `/tmp/opencode/ui_home.png`,
`ui_globe.png`, `ui_globe_rings.png`, `ui_editor.png`, `ui_settings.png`, `ui_review.png`.

## 10. If continuing from here

- The tree is clean; baseline for any full run is **209 tests/variant (418 total)**.
- Phase 6 was the final phase — test25 is done. Any further work = a new plan (test26?)
  against the same hard constraints (§5) and gotchas (§6).
- Do not push, bump the version, or produce deliverables without explicit user approval.
