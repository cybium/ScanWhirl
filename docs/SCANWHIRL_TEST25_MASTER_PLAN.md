# MASTER BLUEPRINT: ScanWhirl test25 Visual Overhaul & Architecture Handoff

> **TARGET ENVIRONMENT & HANDOFF INFO FOR NEXT ANTIGRAVITY / GEMINI SESSION**
> - **Workspace / Project Root:** `/home/cybium/ScanWhirl`
> - **OS:** Linux (WSL / Ubuntu)
> - **Deliverables Target:** `/mnt/c/Users/Cybium/Downloads/` (`C:\Users\Cybium\Downloads\`)
> - **Package Name:** `com.scanwhirl.app.test` (Test builds ONLY; NEVER touch `com.scanwhirl.app`)
> - **App Label:** `ScanWhirl Test`
> - **Target Version:** `versionCode = 25`, `versionName = "1.1.1-test25"` in `app/build.gradle.kts`
> - **Launcher Activity:** `com.scanwhirl.app.ui.globe.GlobeHomeActivity`
> - **Build & Test Command:**
>   `cd /home/cybium/ScanWhirl && export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" && ./gradlew test assembleRelease -PtestBuild --no-daemon`
> - **Test Verification:** Tests must stay green (180 existing tests + new unit tests, 0 failures, 0 errors). Parse XMLs: `app/build/test-results/testDebugUnitTest/*.xml`.
> - **Verification Command:**
>   `$HOME/android-sdk/build-tools/36.0.0/aapt2 dump badging /mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test25.apk | grep -E "package:|application-label:|launchable-activity"`
> - **Git Remote:** `origin/master` (currently at test24 commit `0e0e17d`).
> - **Android SDK Path:** `/home/cybium/android-sdk`

---

## ✅ PHASE 0 — Foundations & Theme — **COMPLETE**

All items done and verified building.

### What was done
1. **Deleted qualified override files:** `values-night/colors.xml`, `values-night/themes.xml`, `values-v27/themes.xml`, `values-night-v27/themes.xml` — these were silently dropping the dialog theme overlay on API 27+ devices.
2. **`values/themes.xml`** — Configured `Theme.ScanWhirl` with parent `Theme.Material3.Dark.NoActionBar`, all M3 role mappings, dialog overlays (`ThemeOverlay_ScanWhirl_Dialog`, `ThemeOverlay_ScanWhirl_Dialog_Sheet`, `ThemeOverlay_ScanWhirl_Dialog_Danger`), popup style, button styles, switch styles, animation styles (`Animation_ScanWhirl_Sheet`, `Animation_ScanWhirl_Card`).
3. **`values/colors.xml`** — Added D3 approved category colors: Study `#38EF7D`, Receipts `#FF8A4C`, IDs `#5B9DFF`, Forms `#B07CFF`, Other `#94A3B8`. Lightened `text_hologram_dim` to `#7A93B2` (5.4:1 contrast). Fixed PDF Viewer toolbar contrast. Cleaned legacy blue `#1565C0`.
4. **`SpaceBackgroundDrawable.kt`** — Pure Kotlin canvas drawable: void gradient + ~60 deterministic static stars. Wired into `BaseActivity.kt` as window background.
5. **`MainActivity.kt`** — Fixed wallpaper null check in `onResume` so the window space background is preserved when no wallpaper is chosen.
6. **`ScanListAdapter`** — Fixed stroke erasure: now always 1dp idle, 2dp selected (removed the bug that set `strokeWidth=0`).
7. **`activity_main.xml`** — Cleaned duplicate XML attributes, fixed stray text in `proSuiteCard`.
8. **`CategoryStyle.kt`** — Pure-Kotlin category color/tag/ringIndex lookup. No Android imports → fully JVM-testable.
9. **All `res/` resources created:** drawables, selectors, vectors, anim, animator, interpolator.
10. **`res/values/strings.xml`** — Added dialog eyebrow strings, globe HUD plurals (`globe_status_docs`, `globe_status_orbits`) with correct `<item quantity="one/other">` syntax.

---

## ✅ PHASE 1 — Universal Dialog Architecture — **COMPLETE**

All 53 dialog call sites across 7 files migrated. `AlertDialog.Builder` fully replaced with `SwDialog.*`. Tests currently failing to compile due to a stray error (see Current Errors below) — fix errors first, then tests should be green.

### What was done
1. **`SwDialog.kt`** — Created: `card()`, `sheet()`, `danger()`, `progress()`, `input()` builders + `MaterialAlertDialogBuilder.showSw(isSheet)` extension. All return `androidx.appcompat.app.AlertDialog` → Robolectric cast-compatible.
2. **`dialog_pro_panel.xml`** — Created layout for the Pro dossier panel.
3. **Dialog migrations completed in:**
   - `GlobeHomeActivity.kt` — 2 of 2 sites
   - `SettingsActivity.kt` — 5 of 5 sites
   - `PageViewerActivity.kt` — 3 of 3 sites
   - `MergePdfActivity.kt` — 3 of 3 sites
   - `ScanReviewActivity.kt` — 2 of 2 sites
   - `DocumentViewerActivity.kt` — 10 of 10 sites
   - `MainActivity.kt` — all remaining sites including:
     - `startCompress` → `SwDialog.sheet` + `showSw(isSheet=true)`
     - `pickScan` → `SwDialog.sheet` + `showSw(isSheet=true)`
     - `pickLevel` → `SwDialog.card` + `showSw(isSheet=false)`
     - `showProgress` → `SwDialog.progress()`
     - `showAbout` → `SwDialog.card` + `showSw(isSheet=false)`
     - `promptUnlockBeforeOpen` → `SwDialog.card` + `showSw(isSheet=false)`

### ✅ Tests Verified Green

**180 tests, 0 failures, 0 errors** confirmed at session close.

| Suite | Tests | Result |
|-------|-------|--------|
| UiSmokeTest | 78 | ✅ PASS |
| ScanRepositoryTest | 22 | ✅ PASS |
| PdfGeneratorTest | 10 | ✅ PASS |
| GlobeSceneTest | 11 | ✅ PASS |
| ImageToolsTest | 16 | ✅ PASS |
| CategoryRulesTest | 6 | ✅ PASS |
| DownloadSaverTest | 9 | ✅ PASS |
| GreetingTest | 3 | ✅ PASS |
| ScanFilterTest | 5 | ✅ PASS |
| CategoryPersistTest | 4 | ✅ PASS |

**Next session can proceed directly to Phase 2 — no fixes needed.**

---

## ✅ PHASE 2 — Shelf Overhaul — **COMPLETE**

All 7 remaining items done, built, and verified. **180 tests, 0 failures, 0 errors.**

### What was done earlier (previous session)
1. **`item_scan.xml`** — Fully rewritten with:
   - `catRail` (4dp × row height − 20dp, `bg_cat_rail` drawable, tinted at runtime)
   - Portrait thumbnail 60×80dp (was 68×68 square)
   - `bg_chip_void` page badge (replaces legacy blue `bg_page_number`)
   - `catChip` TextView below scanMeta for category label (10sp caps, tinted at runtime)
   - Stacked action column: `btnView` over `btnEdit` (36dp glass circles `bg_action_circle`), freeing ~52dp for text
   - `checkSelect` in same column, `gone` by default
   - `app:strokeColor="@color/sel_stroke_cyan"` (ColorStateList idle/selected)
   - `android:stateListAnimator="@animator/sw_press"` on the card
2. **`bg_cat_rail.xml`** — Created (`res/drawable/`): 2dp corner rectangle, default cyan, tinted at runtime.
3. **`CategoryStyle` import** added to `MainActivity.kt`.
4. **`ProgressBar` import removed** from `MainActivity.kt` (no longer needed after `showProgress` delegates to `SwDialog.progress()`).

### What was done now (items 1–7 of the remaining checklist)

1. **`ScanListAdapter.VH.bind()`** (`MainActivity.kt`) — wired `catRail` + `catChip`:
   ```kotlin
   val catColor = CategoryStyle.color(doc.category.orEmpty())
   binding.catRail.backgroundTintList = ColorStateList.valueOf(catColor)
   binding.catChip.text = "● ${CategoryStyle.shortTag(doc.category.orEmpty())}"
   binding.catChip.setTextColor(catColor)
   ```
   Added `import android.content.res.ColorStateList`. `CategoryStyle` was already imported.
2. **Pro Suite card** (`activity_main.xml`):
   - **Deviation from this plan (deliberate):** `app:cardBackgroundColor="@drawable/bg_pro_banner"`
     is **not** usable and was **not** applied. `cardBackgroundColor` is `format="color"` →
     `ResourcesImpl.loadColorStateList()` → `loadComplexColorForCookie()` only parses root
     tags `<gradient>` / `<selector>`, returns `null` for a `<shape>`, then throws
     `NotFoundException("Can't find ColorStateList from drawable resource ID #0x…")` at
     inflation → **every MainActivity launch crashes**.
   - Instead: `proSuiteCard` keeps `app:cardBackgroundColor="@color/gold_pro_surface"` (the
     1dp ring + fallback under the card stroke), and the **inner vertical `LinearLayout`**
     got `android:background="@drawable/bg_pro_banner"` + `android:layout_margin="1dp"` so
     the card's own `pro_badge_stroke` stays visible above it. The dead drawable is now wired.
   - `toolVault`, `toolOcr`, `toolLockPdf`, `toolLongImage`: `style` →
     `@style/Widget.ScanWhirl.Button.Pro`, `layout_height` `42dp` → **`52dp`**, and the now
     inert `app:strokeColor="@color/pro_badge_stroke"` removed (`bg_pro_tile` supplies the
     1dp gold border + 12dp corners; `MaterialButtonHelper.setBackgroundOverwritten()` makes
     `app:strokeColor` a no-op on a button that carries `android:background`).
     - **Note:** the original plan text named `btnVaultDoc/btnOcr/btnLockPdf/btnLongImage`,
       which live in `activity_document_viewer.xml` → **Phase 5C**. `btnLockPdf` does not
       exist anywhere. The Phase 2 targets are the four `tool*` buttons.
   - `proSuiteChevron`: `android:rotation="0"` added; `toggleProSuite()` now runs an
     `ObjectAnimator` `0° → 180°` (200ms) instead of swapping `ic_chevron_down/up`.
     `updateProBadge()`'s Free branch resets `rotation = 0f` instead of the drawable.
     - **Gotcha:** `android.view.animation.FastOutSlowInInterpolator` does **not** exist in
       the framework `android.jar` (checked android-36) and androidx' copy is not on the
       compile classpath → **compile error**. Use
       `android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)` (the same M3 curve) —
       this applies to Phase 4 too.
3. **Tool cards** `toolResize` / `toolCompress` / `toolEdit` / `toolMerge`:
   `app:strokeColor` `cyan_border_idle` → `@color/sel_stroke_cyan`; added
   `android:stateListAnimator="@animator/sw_press"`; `app:cardCornerRadius` `14dp` → **`16dp`**;
   icon `ImageView` `32dp` → **`40dp`**, `bg_icon_badge` → **`bg_icon_badge_glow`**,
   `padding` `6dp` → **`9dp`** (40 − 22 = 18 / 2 → 22dp glyph).
4. **`btnSort` → pill** (`activity_main.xml`): `ImageButton` replaced by a `LinearLayout`
   (`bg_search_bar` background, `ic_sort` 16dp + new **`sortLabel`** `TextView`, 34dp tall).
   New ID `sortLabel` — **not** on the locked list, so it is additive. `btnSort` itself is
   unchanged as an ID; `binding.btnSort.setOnClickListener` and the `PopupMenu(this,
   binding.btnSort)` anchor still compile and work (both only need `View`).
   `updateSortLabel()` in `MainActivity.kt` maps `Prefs.sortMode()` → `R.string.sort_name`
   / `sort_date` / `sort_modified` and is called from `applyList()` (single source of truth:
   covers onCreate, search, and `showSortMenu()`); it also keeps the pill's
   `contentDescription` in sync for a11y.
5. **Empty state**: `emptyState`'s child `ImageView` `ic_scan_empty` → **`ic_empty_orbit`**
   (given id `emptyOrbit`, additive). `app:tint="@color/cyan_primary"` kept — the vector is
   cyan-only and `src_in` tinting preserves per-path alpha.
6. **FAB host** (`activity_main.xml`): `fabScan` wrapped in a 96dp
   **`FrameLayout fabHost`** (`bottom|end`, 24dp margin) carrying **`fabRing`**
   (`bg_fab_outer_ring`) and **`fabPulse`** (`bg_fab_pulse`) behind it; `fabScan` is now
   `layout_gravity="center"` with no margin, so the ring inherits the old 24dp inset and the
   FAB sits centred inside it.
   - **Kotlin:** new `setFabVisible(visible)` helper sets **both** `fabHost` and `fabScan`;
     called from `setSelectionMode()` and `setVaultMode()`. This matters — `UiSmokeTest`
     asserts `R.id.fabScan` visibility *directly* (lines 326/337/519/1785), so a host-only
     toggle breaks four assertions across three tests.
   - Rings are intentionally **static**: both drawables are byte-identical today, and pulse
     motion is Phase 4 where it must be gated on `SwMotion.ambientAllowed` — Phase 4 landed that
     gated pulse on the globe home's `fabPulse1`/`fabPulse2` (the spec'd pulse); this Shelf FAB
     stays static by design.
7. **`scanList`**: `android:padding="8dp"` → `paddingStart/Top/End="8dp"` +
   **`paddingBottom="96dp"`**; `clipToPadding="false"` was already present and is kept.

### Phase 2 verification
- `./gradlew test assembleRelease -PtestBuild --no-daemon` → **BUILD SUCCESSFUL**.
- Parsed `app/build/test-results/testDebugUnitTest/*.xml`: **180 tests, 0 failures,
  0 errors, 0 skipped** (UiSmokeTest 78, ScanRepositoryTest 22, GlobeSceneTest 11,
  ImageToolsTest 16, PdfGeneratorTest 10, DownloadSaverTest 9, CategoryRulesTest 6,
  ScanFilterTest 5, GreetingTest 3, CategoryPersistTest 4, GlobeHomeActivityTest 8,
  SearchMatcherTest 4, PageThumbLoaderTest 3, ExternalVerifyExport 1).
- No locked ID removed or renamed; nothing under `com.scanwhirl.app` touched.
- `/tmp/opencode/ui_home.png` (rendered by the suite) confirms: sort pill, 16dp glow-badge
  tool cards, gold `bg_pro_banner` Pro Suite with four 52dp `.Pro` buttons, catChip on rows,
  and the orbital FAB ring.
- APK still reports `versionCode=24` / `1.1.1-test24` — **version bump is Phase 6**, not done.

**Next session can proceed directly to Phase 3.**

---

## ✅ PHASE 3 — Holographic Globe Overhaul — **COMPLETE**

Files: `GlobeScene.kt`, `HolographicEarthView.kt`, `GlobeHomeActivity.kt`,
`activity_globe_home.xml`, **new** `res/drawable/sel_chip_orbital.xml`,
`GlobeSceneTest.kt`, `GlobeHomeActivityTest.kt`

### Key tasks (as implemented)
- **`GlobeScene.kt`:** ✅ `topReserve` + `bandY(h, frac) = topReserve + (h − topReserve) · frac`
  (default `0f` reduces to the plan's plain fractions); `RING_RX = [0.125, 0.19, 0.255, 0.32,
  0.385]`; ring `cy = bandY(h, 0.58)`; home orbit `rx = min(1.35r, 0.5w − cardHalfW − 6u)` via
  `homeOrbitRx(w, r, cardHalfW)`; `focusRing` (Int, −1 = none), `ringAlphaFor(card)`,
  `categoryCounts()`, `FOCUS_DIM = 0.25`, `ORBIT_DIM = 0.4`.
- **`HolographicEarthView.kt`:** ✅ dashed ellipse ring tracks (4u/4u, 0.8u stroke, category
  colour α0.30 × stateT); ring labels along the lower arc, bottom-centred **125°→55°
  counter-clockwise** (`drawTextOnPath`, `hOffset` centring via `PathMeasure`, `vOffset = 8.2u`
  to sit just outside the stroke, 9u
  caps, α0.65); 3u left category accent bar on cards (skipped on hidden cards, `tagX` 5u → 7u);
  `drawHintInCanvas = false` flag — **`hintText` property kept** (tests read it); orbit radius
  now clamped through `homeOrbitRx`.
- **`activity_globe_home.xml`:** ✅ `hudScrim`, `globeTitle` + `globeStatus`, `btnGlobeList` /
  `btnGlobeSettings`, `globeHint` pill, `globeDock` with 5 `globeChip*`, `fabPulse1` / `fabPulse2`,
  FAB container → `bg_fab_orbital`, search panel `paddingTop` 64→76dp, empty copy →
  `globe_empty_title` / `globe_empty_sub`.
- **`GlobeHomeActivity.kt`:** ✅ still `BaseActivity`; `updateStatus()` status readout;
  `wireDock()` / `setFocusRing()`; List → Shelf, gear → Settings (D5); `topReserve` posted from
  `globeHud`; `setHint()` writes **both** the view property and the pill; a11y descriptions;
  `runQuery` before `updateStatus` in `loadDocs`.

### Deviations from the plan
1. **D3 category unification** (user-approved): the view's private `categoryColor()` /
   `shortTag()` were replaced by `CategoryStyle`, so Receipts/IDs/Forms card tags and ring labels
   now use the D3 palette (`CategoryRules` import dropped from the view).
2. **`topReserve` is a settable `GlobeScene.topReserve` property**, not a constructor parameter —
   default `0f` keeps the plan's formulas exact; the activity posts the HUD height after measure.
3. **`resources.getQuantityString(id, n, n)`** for the plurals — `getString(R.plurals.…)` throws
   at runtime (repo convention: `MainActivity`, `DocumentViewerActivity`).
4. **`runBlocking<Unit>`** on the screenshot test — an `= runBlocking { … }` expression body
   infers a non-Unit return type and JUnit rejects it ("should be void").
5. **New drawable `sel_chip_orbital.xml`** for the focused chip state (cyan-tinted glass);
   default state matches `bg_chip_void`.
6. **FAB container uses the orphaned `bg_fab_orbital`** (replacing `bg_fab_outer_ring` there);
   `bg_orbit_dot` stays unused.
7. **Chip tap = set `focusRing` + auto-expand to RINGS; second tap clears** (user-approved).
8. **`fabPulse1` / `fabPulse2` are static** (α0.55 / 0.30). Motion was **Phase 4** and must be
   gated on `SwMotion.ambientAllowed` — **done in Phase 4** (gated infinite pulse, see below).

### Render verification — root cause found & fixed
The screenshot tests produced **star-only** PNGs. Root cause: **`GlobeHomeActivityTest` was the
only Robolectric UI test missing `@GraphicsMode(GraphicsMode.Mode.NATIVE)`**, so it ran in
LEGACY graphics where `Canvas`/`Paint`/`Shader`/`Path` are shadow stubs — `drawCircle`/`drawPath`/
`drawPoints` never rasterised, `GradientDrawable` backgrounds never drew, and
`Paint.measureText` returned the *character count* (hence `globeTitle` measuring 9×41 px for
"ScanWhirl"). **Fix = one annotation + one import.** Second half of the fix: the fake document
URI now carries a **real JPEG** (`jpegBytes()`, mirrors `UiSmokeTest`) because `BitmapFactory` is
genuine under NATIVE. Render helpers were also de-silenced — they no longer swallow exceptions,
and pixel assertions now fail loudly on a blank render.

### Phase 3 verification
- `./gradlew test assembleRelease -PtestBuild --no-daemon` → **BUILD SUCCESSFUL**.
- Parsed `app/build/test-results/testDebugUnitTest/*.xml`: **192 tests, 0 failures, 0 errors,
  0 skipped** (UiSmokeTest 78, GlobeHomeActivityTest 15, GlobeSceneTest 16, ScanRepositoryTest 22,
  ImageToolsTest 16, PdfGeneratorTest 10, DownloadSaverTest 9, CategoryRulesTest 6,
  ScanFilterTest 5, GreetingTest 3, CategoryPersistTest 4, SearchMatcherTest 4,
  PageThumbLoaderTest 3, ExternalVerifyExport 1).
  - Plan target was 191; the **+1** is bookkeeping: the temporary `zz debug` test was added
    *after* the 189-test baseline was measured (so it was never part of the 189) and has since
    been deleted → 189 + 3 new = 192.
- New guards: `native graphics is active - text measures to real widths`,
  `globe view paints halo, dots and rim on a transparent canvas`,
  `hud title and status have real text size and paint pixels`, plus bright-pixel assertions on
  both screenshots (threshold 0.05, measured 0.239 home / 0.576 rings; globe-view lit fraction
  1.0 vs 0.8 threshold; HUD title 111 px wide vs 100 threshold).
- `/tmp/opencode/ui_globe.png` confirms: HUD title + `2 DOCUMENTS · 2 ORBITS`, search/List/
  Settings buttons, dotted Earth with rim and latitude lines, two orbit cards with category
  accent bars, hint pill, 5-chip dock, FAB with orbital rings + `TAP TO SCAN`.
- `/tmp/opencode/ui_globe_rings.png` confirms: shrunken Earth, five dashed category ring tracks
  with arc labels, hint pill switched to `Drag rings to spin · Pinch in for List`.
- Sampled pixels: home centre `ff06414f`, rings centre `ff057f8f`, corner `ff060c16`
  (dark and opaque, not transparent).
- `aapt2 dump badging` → `com.scanwhirl.app.test`, label `ScanWhirl Test`, launcher
  `GlobeHomeActivity` — unchanged. Still `versionCode=24` / `1.1.1-test24` (**Phase 6 bump**).
- No locked ID removed or renamed; nothing under `com.scanwhirl.app` touched; no
  `robolectric.properties`; no Gradle changes; no infinite animators.
- **`Invalid ID 0x00000000.`** — emitted by Robolectric's
  `org.robolectric.res.android.CppAssetManager2` (Android's own AssetManager logging a lookup of
  resource id `0`). Reproduced with **`UiSmokeTest` alone (39×) with no Phase 3 code involved** →
  pre-existing / environmental, not a resource bug in these layouts. Counts vary run to run.
  Not a blocker.

### Known visual notes (not spec defects)
- **Ring label orientation — FIXED (post-Phase-3 follow-up):** the original clockwise 20°→80°
  sweep made the tags read rotated/upside-down along the lower arc. The path is now swept
  **counter-clockwise, bottom-centred 125°→55°**, so every tag runs left-to-right and reads
  upright (max tilt ±16° on the 0.42-ratio ellipse; the strict 80°→20° flip would still have
  tilted ~49° near the 3-o'clock end). `vOffset = 8.2u` puts the baseline just outside the
  dashed stroke — balanced in the 31px inter-ring band (caps ≈20px) — and `hOffset` centres each
  tag on the bottom. Verified on the regenerated rings PNG: all five tags upright, unclipped,
  clear of the Earth, cards and neighbouring rings. `drawTextOnPath`, 9u caps and α0.65 kept;
  no new IDs, no animators; GlobeHomeActivityTest 15/15 unchanged.
- Orbit cards show the white test-fixture thumbnail scrimmed to grey — faithful to the
  pre-existing `drawCard` scrim; real scans would show actual page content.

**Next session can proceed directly to Phase 5 (Phase 4 is complete).**

---

## ✅ PHASE 4 — Motion & Transitions — **COMPLETE**

Files: New `SwMotion.kt`, New `SwTransitions.kt`, `GlobeScene.kt`, `HolographicEarthView.kt`,
`GlobeHomeActivity.kt`, `MainActivity.kt`, `activity_globe_home.xml`

### As implemented
- **`util/SwMotion.kt`:** `ambientAllowed(context, fingerprint = Build.FINGERPRINT)` =
  `ANIMATOR_DURATION_SCALE > 0 && isInteractive && fingerprint != "robolectric"` — checks run in
  that order so **all three branches are testable** under Robolectric (a fingerprint short-circuit
  first would make the other two dead code; `fingerprint` is a default-arg injection point only).
- **`ui/SwTransitions.kt`:** `warpToShelf` / `warpBackToGlobe` wrap `overridePendingTransition`
  over the four Phase 0 warp anims (finite → deliberately **not** ambient-gated; the system scales
  window anims itself). `revealScanLaunch` = radial reveal of the additive `scanReveal` view from
  the FAB's centre, 260 ms, `PathInterpolator(0.4f, 0f, 0.2f, 1f)`; when `ambientAllowed` is false
  it runs `onEnd` **synchronously** (same-frame launch — the existing scan test depends on this).
  State machine: `revealSession` (abandoned `doOnNextLayout` listeners can never double-launch),
  `revealPending` + cancelled-flag listener, and `cancelReveal` hides the wash **only** if a
  reveal was actually running/pending — the launch's own `onPause` therefore keeps the globe
  covered while the Shelf's enter warp fades in over it.
- **FAB pulse** (`GlobeHomeActivity`): two code-built `AnimatorSet`s (α 0.55→0.14 / 0.30→0.08,
  scale 1→1.14, 1800/2200 ms, 0/900 ms stagger, `INFINITE`+`REVERSE`, `PathInterpolator(0.4,0,0.2,1)`).
  Started in `onResume` **only after `SwMotion.ambientAllowed`**, cancelled + reset to the static
  XML values in `onPause`. This is the **only `INFINITE` in `app/src/main`** and it is gated
  (grep-audited). `clipChildren="false"` added on the FAB container + its parent so the breathing
  rings aren't clipped.
- **Ambient gating matrix:** gated = FAB pulse, earth `BASE_ROT`, ring drift, star twinkle
  (fixed α0.85 when off), new ring-track shimmer (`sin(time*1.6 + ring*1.1) * 0.07`, flat 0 when
  off — per-ring phase so the five tracks don't pulse in lockstep), scan reveal. **Not** gated =
  the 30fps Choreographer loop (it also drives `stateT` easing, which must keep responding with
  animations disabled), `stateT`, flick inertia, finite `overridePendingTransition` warps.
- **`GlobeScene.ambientMotion: Boolean = true`** — default `true` is load-bearing
  (`GlobeSceneTest` drives `update()` directly and asserts auto-rotation); the activity flips it
  via `HolographicEarthView.syncAmbientMotion()` on every resume (false under Robolectric →
  deterministic screenshots).
- **Return warp:** `MainActivity.finish()` override → `warpBackToGlobe` gated on
  `Prefs.globeHomeEnabled` (globe off → plain system finish; verified no prior `finish()` override
  and no existing test calls it).

### Deviations from the plan
- `fingerprint` became a default parameter of `ambientAllowed` (plan: plain `Context` overload) —
  required so the scale and interactive branches can be *proven* rather than always
  short-circuited by the Robolectric fingerprint.
- Reveal adds a `revealSession` counter + `revealPending` flag beyond the plan sketch (double-tap
  and abandoned-layout-listener guards).
- No new tests for `syncAmbientMotion`/twinkle/shimmer specifically — they're covered indirectly
  (gate off ⇒ flat values) and the render tests lock the output.

### Verification
- Full `./gradlew test assembleRelease -PtestBuild --no-daemon` → **201 tests, 0 failures,
  0 errors** (192 baseline + 9 new: `SwMotionTest` 4, `SwTransitionsTest` 5).
- New tests: all 3 `SwMotion` branches proven green-when-should-be; fab pulse static after
  `idleFor(500ms)` (α 0.55/0.30, scale 1); pinch + List → `shelf_enter_warp`/`globe_exit_warp`;
  scan click → same-frame launch, `scanReveal` stays GONE, scanner flag set;
  `MainActivity.finish()` → `globe_enter_warp`/`shelf_exit_warp` (and **no** warp with the globe
  preference off).
- Audits: badging unchanged (`com.scanwhirl.app.test`, `versionCode 24`, label
  `ScanWhirl Test`, launcher `GlobeHomeActivity`); 83/83 locked IDs present (`snackbar_action` is
  a Material library ID, not app-defined); no `fontFamily` added; `INFINITE` appears exactly once
  in `app/src/main` and is gated; zero new resources (reuses `bg_space`, the 4 Phase 0 warp anims,
  `sw_*` interpolators — `res/anim/` inventory unchanged); no Gradle / `robolectric.properties` /
  `com.scanwhirl.app` config touched; no existing test file modified (the two modified test files
  are Phase 2/3 work from earlier sessions).

---

## ✅ PHASE 5 — Remaining Screens — **COMPLETE**

> Implementation decisions confirmed with the product owner before build: row icons tinted
> **`cyan_primary`**; docks are **inset floating pills** (8dp horizontal margin so
> `bg_dock_glass`'s 24dp corners + cyan hairline read as a distinct bar); `item_merge_pdf`
> rows **are** restyled to glass cards.

### Pre-build findings (verified against the codebase / MDC 1.12 bytecode)
- **`MaterialCardView` silently drops XML `android:background`**: `setBackgroundDrawable`
  early-returns until `isParentCardViewDoneInitializing`, which is set *after* the View ctor
  applies XML attrs → `bg_glass_card_ripple` on `item_page_grid` must be **set in code**
  post-inflate (same trap explains Phase 2's inner-container workaround for `bg_pro_banner`).
- **`app:cardBackgroundColor` cannot take a drawable** (Phase 2-proven crash) →
  `proToolsPanel` gets `bg_pro_banner` via the **Phase 2 inner-container trick** (inner
  `LinearLayout` background + 1dp margin so the gold `pro_badge_stroke` stays visible).
- Once a card's background is overwritten the helper **stops drawing its stroke**
  (`if (!isBackgroundOverwritten) setBackgroundInternal(...)`) → page-grid selection switches
  from `root.strokeWidth` (0/3dp, no longer rendered) to `root.isSelected` →
  `bg_glass_card_ripple`'s `state_selected` branch (2dp cyan stroke + tinted fill).
  **Documented deviation.**
- Theme already defines `Widget.ScanWhirl.Button.{Primary,Secondary,Pro,Dock}`,
  `Widget.ScanWhirl.Switch` (sel_switch tints) and `materialCardViewStyle` = glass card →
  Phase 5 is **wiring only, no new styles**. `thumbTint`/`trackTint` attrs exist (material),
  minSdk 24 → explicit switch tints + `android:foreground` hairline both safe.
- Test locks checked: `subTheme.text.isNotEmpty()` (GONE views still findable), row ID
  `findViewById`s, `btnSavePdf.isEnabled`+click, `btnRotateRight` non-null,
  `btnProtect/btnLongImage` visible, `btnAddPdf/btnMerge` enabled-state, `resultStats`
  content; **no** test greps `›`, page-grid `strokeWidth`, or item_merge_pdf styling.
  `ui_editor.png` (UiSmokeTest render) picks up the new DocumentViewer look automatically.

### 5A — SettingsActivity (`activity_settings.xml`, `SettingsActivity.kt`)
- **Group rows into glass section cards:** one `MaterialCardView` per section instead of one
  per row — Appearance (`rowTheme, rowFont, rowGreeting, globe-home row, rowWallpaper`),
  Language (`rowLanguage`), Viewing (`rowViewer, rowAutoEnhance`), About (`rowAbout,
  rowPrivacy`). Drop per-card overrides (`strokeColor=colorOutlineVariant`, `16dp`) → theme's
  `Widget.ScanWhirl.Card` applies (glass fill + **cyan sel_stroke_cyan hairline** + 14dp).
  1dp cyan hairline dividers (`@color/cyan_border_hair`) between rows, inset ~56dp to align
  under the text column. All locked IDs preserved (inside the grouped cards).
- **D1:** `binding.rowTheme.visibility = View.GONE` in `onCreate` (row sits *inside* the
  grouped Appearance card → no empty card left behind); `subTheme.text = "Orbital Dark"` set
  once in code; **remove the `subTheme` overwrite from `updateSummaries()`**.
  `chooseTheme()` stays as dead-but-harmless code.
- **Row icons:** ID-less `ImageView`s, 24dp, `app:tint="@color/cyan_primary"` —
  theme→`ic_palette`, font→`ic_font`, greeting→`ic_greeting`, globe→`ic_globe`,
  wallpaper→`ic_wallpaper`, language→`ic_language`, viewer→`ic_viewer`,
  auto-enhance→`ic_auto`, about→`ic_info`, privacy→`ic_privacy`.
- **Chevrons:** replace the 6 literal `›` TextViews with `ImageView src=ic_chevron_right`,
  20dp, `tint=?attr/colorOutline` (same colour as today).
- **Switches:** explicit `app:thumbTint="@color/sel_switch_thumb"` +
  `app:trackTint="@color/sel_switch_track"` on all 4 `SwitchMaterial`s (greeting,
  globe-home, wallpaper, auto-enhance) — belt-and-braces over theme `switchStyle`, and
  assertable in a test.

### 5B — ScanReviewActivity / PageViewerActivity
- **`item_page_grid.xml`:** `pageNum` background `bg_page_number` → **`bg_chip_void`**
  (12dp radius, matches `pageBadge` in `item_scan`); `checkSelect`/`dragHandle` keep theirs
  (spec says *page badge* only); card radius → 14dp to match the drawable.
- **`PageGridView.kt` (2 edits in `VH`):** `onCreateViewHolder` →
  `binding.root.setBackgroundResource(R.drawable.bg_glass_card_ripple)` (code-set; XML would
  be dropped — gives idle 1dp cyan border, press ripple, 14dp corners); `bind` → replace
  `root.strokeWidth = …` with `root.isSelected = checkedAt(index)` (selection via the
  drawable's `state_selected` branch).
- **`activity_scan_review.xml`:** bottom button container →
  `android:background="@drawable/bg_dock_glass"` + `layout_marginHorizontal="8dp"` (floating
  pill); `btnSavePdf` → `style="@style/Widget.ScanWhirl.Button.Primary"`, drop redundant
  `backgroundTint/textColor/textStyle/cornerRadius/elevation`.
- **`activity_page_viewer.xml`:** `#99000000` dock → `@drawable/bg_dock_glass` + 8dp
  horizontal margin + breathing-room padding; `btnRotateRight/btnCrop/btnFilter/btnDelete`
  → `style="@style/Widget.ScanWhirl.Button.Dock"` (icon-over-label, cyan icons), removing
  explicit `textColor/textSize/iconTint/iconPadding`. IDs untouched.

### 5C — DocumentViewerActivity (`activity_document_viewer.xml`, `DocumentViewerActivity.kt`)
- **`proToolsPanel`:** inner `LinearLayout` → `android:background="@drawable/bg_pro_banner"`
  + `layout_margin="1dp"` (Phase 2 proSuiteCard trick; card keeps gold stroke).
- **5 `.Pro` buttons** (`btnProtect, btnUnlockPdf, btnOcr, btnLongImage, btnVaultDoc`) →
  `style="@style/Widget.ScanWhirl.Button.Pro"`, remove now-inert `app:strokeColor` +
  redundant `textColor/iconTint/textSize` (same treatment Phase 2 gave the `tool*` buttons).
  Explicit `layout_height=42dp` kept (no metric change).
- **Bottom dock** (`btnProTools/btnDelete/btnShare` container) → `@drawable/bg_dock_glass`
  + 8dp horizontal margin.
- **New `docCategoryChip`** (additive ID — allowed): `TextView` styled like `item_scan`'s
  `catChip` (10sp caps, `bg_chip_void`) in the header row after `pageCount`. Wired in
  `reloadDocument()` — `"● " + CategoryStyle.shortTag(doc.category)` coloured
  `CategoryStyle.color(...)`; `promptCategorize()` already funnels through
  `reloadDocument()`, so recategorising updates it for free.

### 5D — MergePdfActivity / PDFViewerActivity / ExportResultActivity
- **Glass docks:** bottom containers of Merge (`btnAddPdf/btnMerge`) and Export
  (`btnShareResult/btnSaveResult`) → `@drawable/bg_dock_glass` + 8dp horizontal margin.
- **Hairline toolbar (all three):** `android:foreground="@drawable/bg_appbar_hairline"` on
  each `AppBarLayout` — cyan bottom hairline **without changing existing backgrounds**
  (merge = none, export = `sf_appbar`, pdf viewer = `pdf_toolbar`), zero new resources.
  *Fallback if foreground misbehaves: per-screen layer-list drawables (2 new).*
- **Standard styling:** `btnAddPdf`, `btnShareResult` → `Widget.ScanWhirl.Button.Secondary`
  (the only raw `OutlinedButton`s; `btnMerge`/`btnSaveResult` already resolve to theme
  `materialButtonStyle` = Primary). PDFViewer needs no button changes.
- **`item_merge_pdf.xml`:** drop `colorOutlineVariant`/12dp overrides → theme glass card
  (cyan hairline), matching the rest of the system (user-approved).

### New tests — one new file only (as implemented: 8 tests, all green)
`app/src/test/java/com/scanwhirl/app/ui/Phase5ScreensTest.kt` (existing files untouched):
1. Settings: `rowTheme` present **and GONE**, `subTheme == "Orbital Dark"`.
2. Settings: rows carry icons (rendered-pixel match vs `ic_palette`/`ic_font`/…, cyan-tint
   normalised), chevron rows end in a 20dp `ic_chevron_right`, **zero** `›` TextViews.
3. Settings: all 4 switches' `thumbTint`/`trackTint` defaultColor == `sel_switch_*`.
4. Settings: grouping — `rowFont`/`rowGreeting` share one section container inside a
   `MaterialCardView` (Appearance card = 5 rows + 4 one-dp `cyan_border_hair` dividers),
   Viewing/About pairs grouped, Language single-row card, 4 section headers.
5. Page grid: cell `background is RippleDrawable`; `pageNum` corner radius == 12dp;
   `checkedAt=true` → `itemView.isSelected`.
6. DocumentViewer: `docCategoryChip` visible with `● STUDY` after `repo.setCategory(STUDY)`
   (async `reloadDocument()` awaited via `pollUntil`), colour == `CategoryStyle.COLOR_STUDY`;
   bottom dock is `bg_dock_glass`, inset 8dp.
7. ScanReview + PageViewer: dock backgrounds are `bg_dock_glass` (pixel match), inset 8dp;
   `btnRotateRight.textSize == 11sp` (Dock style).
8. Render PNGs `/tmp/opencode/ui_settings.png`, `/tmp/opencode/ui_review.png` (local copy of
   the `renderActivityPng` helper, files deleted first; `ui_editor.png` regenerates from
   UiSmokeTest).

### Verification — as run (2026-11-07)
- `./gradlew test assembleRelease -PtestBuild --no-daemon` → **BUILD SUCCESSFUL;
  209 tests per variant (201 baseline + 8 new), 0 failures / 0 errors / 0 skipped**
  (418 across debug + release; `app/build/test-results/*/*.xml` parsed).
- `aapt2 dump badging` unchanged: `com.scanwhirl.app.test`, `versionCode 24`,
  `versionName 1.1.1-test24`, label `ScanWhirl Test`, launcher `GlobeHomeActivity`
  (the version bump stays Phase 6).
- Audits: 83/83 locked IDs present (82 app-defined + `snackbar_action` = Material library);
  only additive new ID is `docCategoryChip`; no `fontFamily` added (the 3 hits are the
  untouched pre-existing `styles_fonts.xml` system-font styles); `INFINITE` still appears
  exactly once (`GlobeHomeActivity`, pre-existing + gated) — Phase 5 added no animators;
  zero new resource files (no res/values edits, no new drawables — `bg_appbar_hairline`
  already existed); no Gradle / `robolectric.properties` / AndroidManifest /
  `com.scanwhirl.app` app-config changes; existing test files untouched (mtimes pre-date
  this session — only `Phase5ScreensTest.kt` is new); dialogs untouched.
- Renders eyeballed: `ui_settings.png` (grouped glass cards, cyan icons/chevrons, hidden
  theme row), `ui_review.png` (page grid + cyan Save-as-PDF dock), `ui_editor.png`
  (regenerated with the new DocumentViewer look, glass dock present), `ui_home.png`
  (regenerated) — the session's image viewer served stale frames, so content was confirmed
  by PIL pixel signatures (white page rows + cyan dock rows etc.).

### Deviations / as-implemented notes
- 5A–5D shipped exactly as written above; the two **pre-planned** swaps behaved as
  predicted: page-grid selection rides `bg_glass_card_ripple`'s `state_selected` branch
  (the card's own stroke dies once the background is overwritten), and `proToolsPanel` uses
  the Phase 2 inner-container trick (`bg_pro_banner` + 1dp margin keeps the gold stroke).
- `android:foreground` hairline worked on all three AppBars → the 2-new-drawable fallback
  was **not** needed; zero new resources stands.
- Test identity checks use **rendered-pixel comparison** (tint-normalised for icons) instead
  of `Drawable.constantState` equality — ConstantState instances are per-inflation, so
  identity across two separate loads is unreliable.
- Chip test seeds `CategoryRules.STUDY` and asserts `● STUDY` (plan sketch said `● OTHER`);
  this additionally proves the `setCategory → reloadDocument` recategorise path.
- `tools:ignore` on the new chip was dropped before the first build: the layout declares no
  `xmlns:tools`.
- `chooseTheme()` remains dead-but-harmless (D1) — untouched.
- **Next → Phase 6 — ✅ COMPLETE** (versionCode 25 / `1.1.1-test25` shipped, APK + source
  zip deliverables copied to `C:\Users\Cybium\Downloads\`, Phases 2–5 committed as
  `52562d1`, Phase 6 record committed separately — see the Phase 6 as-run section below).

---

## ✅ PHASE 6 — Verification & Deliverables — **COMPLETE**

```bash
# 1. Bump version
# In app/build.gradle.kts:
#   versionCode = 25
#   versionName = "1.1.1-test25"

# 2. Full build + test
cd /home/cybium/ScanWhirl
export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"
./gradlew test assembleRelease -PtestBuild --no-daemon

# 3. Verify APK
$HOME/android-sdk/build-tools/36.0.0/aapt2 dump badging \
  app/build/outputs/apk/release/app-release.apk \
  | grep -E "package:|application-label:|launchable-activity"

# 4. Copy to Windows Downloads
cp app/build/outputs/apk/release/app-release.apk \
   /mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test25.apk

cd /home/cybium/ScanWhirl
zip -r /mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test25-source.zip . \
  --exclude ".git/*" --exclude "build/*" --exclude "*/.gradle/*" \
  --exclude "*/build/*"

# 5. Git commit
git add -A
git commit -m "test25: Visual Overhaul & Architecture"
```

Expected: 180 + new unit tests, 0 failures, 0 errors.

### As-run verification (2026-11-07) — all green

1. **Commit #1 (Phases 2–5 work):** `52562d1` — 27 files, +3128/−676, made **before**
   the version bump so Phase 6 ships as its own commit (user-approved two-commit split;
   differs from the single `git add -A` sketch above).
2. **Version bump:** `app/build.gradle.kts` → `versionCode = 25`,
   `versionName = "1.1.1-test25"` — verified as exactly 2 changed lines via `git diff`.
3. **Full build + test:** `./gradlew test assembleRelease -PtestBuild --no-daemon` →
   **BUILD SUCCESSFUL** (2m 33s). Parsed `app/build/test-results/{testDebugUnitTest,
   testReleaseUnitTest}/*.xml` → **209 tests / 0 failures / 0 errors per variant**
   (418 total across debug+release; 201 baseline + 8 `Phase5ScreensTest`).
4. **APK badging** (`aapt2 dump badging`, build-tools 36.0.0):
   - `package: name='com.scanwhirl.app.test' versionCode='25' versionName='1.1.1-test25'`
   - `application-label:'ScanWhirl Test'`
   - `launchable-activity: name='com.scanwhirl.app.ui.globe.GlobeHomeActivity'`
5. **Deliverables:**
   - `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test25.apk` (6,533,325 bytes) ✓
   - `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test25-source.zip` — created **after
     this record** so the shipped source contains the completed MD; verified via
     `unzip -l` (contains `docs/SCANWHIRL_TEST25_MASTER_PLAN.md`, excludes `.git/`,
     `build/`, `*.apk`).
6. **Commit #2:** this Phase 6 record + version bump (hash recorded in git history).

### Deviations from the sketch above

- **Zip exclude list extended.** The `zip -r` command as originally written would have
  shipped gitignored **signing material** (`keystore/`, `keystore.properties`) and build
  junk (top-level `.gradle/`, `.kotlin/`, `dist/`, `captures/`, `local.properties`,
  `*.apk`). The as-run command adds excludes for all of those — no secrets in the
  delivered source archive.
- **Two-commit structure** (see item 1) instead of one trailing `git add -A` commit.
- **No `git push`** — deliverables are local copies only; remote untouched
  (`origin/master` still at `0e0e17d`-era test24 history as before).

---

## Hard Constraints (NEVER violate)

- **Package name:** `com.scanwhirl.app.test` ONLY. NEVER touch `com.scanwhirl.app`.
- **App Label:** `ScanWhirl Test`
- **Launcher:** `com.scanwhirl.app.ui.globe.GlobeHomeActivity`
- **View IDs:** Never remove or rename any of these ~85 IDs:
  `toolResize toolCompress toolEdit toolMerge proSuiteCard proSuiteChevron proSuiteGrid toolVault toolOcr toolLockPdf toolLongImage emptyState emptyTitle emptySubtitle fabScan searchInput btnSort appNameText greetingText proBadge globeView fabGlobeScan btnGlobeSearch globeSearchInput globeMatchCount btnSearchDone globeEmpty scanList toolbar pageList pageGrid cropView btnCrop titleRow toolsRow pager btnFilter btnDelete snackbar_action selectBar searchPanel pageIndicator cropOverlay btnSavePdf btnRotateRight btnClose btnApplyCrop vaultBanner titleInput switchWallpaper switchGreeting subTheme scanName scanMeta rowWallpaper rowViewer rowTheme rowPrivacy rowLanguage rowGreeting rowFont rowAbout resultStats resultPreview proToolsPanel pageCount pageBadge lockBadge docLockBadge checkSelect btnView btnEdit btnVaultDoc btnUnlockPdf btnShareResult btnSaveResult btnProtect btnProTools btnProSuite btnOcr btnMerge btnLongImage btnAddPdf`
- **Test-locked behaviors:**
  - `ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog` — all dialogs MUST remain `AlertDialog`
  - `dialog.listView.performItemClick(...)` — list dialogs MUST use `setItems()` / `setSingleChoiceItems()`
  - Exactly 2 rows in scan chooser dialogs
  - `subTheme.text.isNotEmpty()` must be true (set "Orbital Dark" even if row hidden)
  - Meta format regex: `^N pages · X KB · d MMM$` — do NOT change the format string
  - Stroke: `ScanListAdapter` must set 1dp idle / 2dp selected (never 0dp)
- **`SwMotion.ambientAllowed`:** ALL infinite animators must check this before starting — Robolectric will hang otherwise.
- **System fonts only** (D4) — never set `fontFamily` in text appearances.
- **`showSw()` extension** is on `MaterialAlertDialogBuilder` only — never call it on `AlertDialog.Builder`.

---

## Product Owner Decisions (locked)

| # | Decision |
|---|----------|
| D1 | Orbital-only dark theme. `rowTheme` kept in XML but `visibility=GONE`. `subTheme.text = "Orbital Dark"` in code. |
| D2 | All dialogs stay `AlertDialog` (via `MaterialAlertDialogBuilder`). Sheet look = window gravity + animation only. |
| D3 | Category palette: Study `#38EF7D`, Receipts `#FF8A4C`, IDs `#5B9DFF`, Forms `#B07CFF`, Other `#94A3B8`. |
| D4 | System fonts only. No `fontFamily` in text appearances. |
| D5 | Add gear icon to Globe HUD → opens SettingsActivity. |

---

## Key Bugs Fixed in This Build

| Bug | Fix |
|-----|-----|
| Dialogs rendering stock grey on API 27+ | Deleted `values-v27/` and `values-night*/` qualifier dirs |
| Shelf wallpaper wiped on `onResume` | Fixed null check; window background fallback is SpaceBackground |
| `strokeWidth=0` erasing card borders | ScanListAdapter always sets 1dp; never 0 |
| PDF viewer toolbar invisible text | `navigationIconTint` + `titleTextColor` → `@color/text_hologram_hi` |
| `text_hologram_dim` WCAG fail | Lightened from `#486282` to `#7A93B2` |
| Plurals XML wrong tag names | Changed `<one>/<other>` → `<item quantity="one/other">` |
| `ProgressBar` unused import | Removed from `MainActivity.kt` |
| `showSw` unresolved in GlobeHomeActivity | Added `import com.scanwhirl.app.ui.showSw` |
| `R.string.processing` missing | Changed to `R.string.progress_title` in `SwDialog.kt` |
| `TextView` unresolved in MainActivity | Added `import android.widget.TextView` |

---

## Files Modified / Created (cumulative, test25)

### New Kotlin files
- `app/src/main/java/com/scanwhirl/app/ui/SpaceBackgroundDrawable.kt`
- `app/src/main/java/com/scanwhirl/app/ui/SwDialog.kt`
- `app/src/main/java/com/scanwhirl/app/util/CategoryStyle.kt`
- `app/src/main/java/com/scanwhirl/app/ui/SwTransitions.kt` (Phase 4)
- `app/src/main/java/com/scanwhirl/app/util/SwMotion.kt` (Phase 4)

### Modified Kotlin files
- `BaseActivity.kt` — wires SpaceBackgroundDrawable as window background
- `MainActivity.kt` — all dialog migrations + ScanListAdapter stroke fix + catRail wiring (partial)
- `GlobeHomeActivity.kt` — 2 dialog migrations + import showSw
- `SettingsActivity.kt` — 5 dialog migrations + Phase 5 D1 (rowTheme GONE, subTheme pinned)
- `PageViewerActivity.kt` — 3 dialog migrations
- `MergePdfActivity.kt` — 3 dialog migrations
- `ScanReviewActivity.kt` — 2 dialog migrations
- `DocumentViewerActivity.kt` — 10 dialog migrations + Phase 5 `docCategoryChip` wiring
- `GlobeScene.kt`, `HolographicEarthView.kt` — Phase 3 globe / rings overhaul
- `PageGridView.kt` — Phase 5 glass cell background (code-set) + `isSelected` selection

### New XML layout
- `res/layout/dialog_pro_panel.xml`
- `res/layout/item_scan.xml` ← **rewritten** (catRail, catChip, portrait thumb, stacked actions)

### Modified XML layouts (Phases 2–5)
- `res/layout/activity_main.xml`
- `res/layout/activity_pdf_viewer.xml`
- `res/layout/activity_globe_home.xml` (Phase 3)
- `res/layout/activity_settings.xml` ← **rewritten** (Phase 5A: grouped glass section cards)
- `res/layout/activity_scan_review.xml`, `activity_page_viewer.xml` (Phase 5B docks)
- `res/layout/activity_document_viewer.xml` (Phase 5C: chip, Pro grid, dock)
- `res/layout/activity_merge_pdf.xml`, `activity_export_result.xml` (Phase 5D docks/hairlines)
- `res/layout/item_page_grid.xml`, `item_merge_pdf.xml` (Phase 5 glass cards)

### New res/drawable (all new)
`bg_action_circle, bg_appbar_hairline, bg_cat_rail, bg_chip_void, bg_danger_note, bg_dialog_glass, bg_dock_glass, bg_fab_pulse, bg_glass_card_ripple, bg_glass_round_btn, bg_hud_scrim, bg_icon_badge_glow, bg_orbit_dot, bg_pin_field, bg_popup_glass, bg_pro_tile, bg_sheet_glass, bg_sheet_top_glass, fg_glass_ripple, frame_corners, ic_auto, ic_check, ic_chevron_right, ic_empty_orbit, ic_filter_bw, ic_filter_clean, ic_filter_enhance, ic_filter_original, ic_font, ic_globe, ic_greeting, ic_language, ic_list, ic_palette, ic_privacy, ic_viewer, ic_wallpaper, sel_check_orbital, sel_chip_orbital, sel_radio_orbital`

### Modified res/drawable
`bg_page_number, ic_launcher_foreground, ic_scan_empty`

### New res/color
`sel_stroke_cyan, sel_switch_thumb, sel_switch_track`

### New res/anim
`anim_none, dialog_card_enter, dialog_card_exit, globe_enter_warp, globe_exit_warp, item_fade_up, layout_list_enter, screen_rise_enter, screen_rise_exit, screen_rise_hold, sheet_enter, sheet_exit, shelf_enter_warp, shelf_exit_warp`

### New res/animator
`sw_press`

### New res/interpolator
`sw_accelerate, sw_decelerate, sw_standard`

### Modified res/values
`colors.xml, strings.xml, styles_fonts.xml, themes.xml`

### Deleted res/values
`values-night/colors.xml, values-night/themes.xml, values-v27/themes.xml, values-night-v27/themes.xml`

### Test files (test25)
- New: `SwMotionTest.kt` (Phase 4), `SwTransitionsTest.kt` (Phase 4),
  `Phase5ScreensTest.kt` (Phase 5 — 8 tests)
- Modified (Phase 2/3 work from earlier sessions): `globe/GlobeHomeActivityTest.kt`,
  `globe/GlobeSceneTest.kt`
- Every other test file untouched (verified by mtime before Phase 5 completion)

### Build / deliverables (Phase 6)
- `app/build.gradle.kts` — version bump only (`versionCode = 25`, `versionName = "1.1.1-test25"`)
- `docs/SCANWHIRL_TEST25_MASTER_PLAN.md` — Phase 6 ✅ COMPLETE as-run record (this file)
- Delivered (not in repo): `ScanWhirl-1.1.1-test25.apk` + `ScanWhirl-1.1.1-test25-source.zip`
  → `/mnt/c/Users/Cybium/Downloads/`
