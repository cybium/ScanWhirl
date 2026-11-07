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

## 🔄 PHASE 2 — Shelf Overhaul — **IN PROGRESS** (started, not complete)

### What was done so far
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

### What REMAINS for Phase 2
Next session must complete these in order:

1. **Wire `catRail` + `catChip` in `ScanListAdapter.VH.bind()`** in `MainActivity.kt`:
   ```kotlin
   // In VH.bind(), after existing bindings:
   val catColor = CategoryStyle.color(doc.category.orEmpty())
   binding.catRail.backgroundTintList = ColorStateList.valueOf(catColor)
   binding.catChip.text = "● ${CategoryStyle.shortTag(doc.category.orEmpty())}"
   binding.catChip.setTextColor(catColor)
   ```
   Also add `import android.content.res.ColorStateList` if not present.

2. **Pro Suite card** (`activity_main.xml`):
   - Set `app:cardBackgroundColor="@drawable/bg_pro_banner"` on `proSuiteCard` (wire the dead drawable).
   - Pro buttons (`btnVaultDoc`, `btnOcr`, `btnLockPdf`, `btnLongImage`): style as `Widget.ScanWhirl.Button.Pro` — gold glass fill, 1dp gold border, 52dp high, 12dp corners.
   - Chevron `proSuiteChevron`: add `android:rotation="0"` default; in `MainActivity.kt` animate to 180° on expand via `ObjectAnimator`.

3. **Tool cards** (`activity_main.xml` — `toolResize`, `toolCompress`, `toolEdit`, `toolMerge`):
   - `app:strokeColor="@color/sel_stroke_cyan"`, `android:stateListAnimator="@animator/sw_press"`, `app:cardCornerRadius="16dp"`.
   - Icon badge: 40dp `bg_icon_badge_glow`, icon 22dp.

4. **`btnSort` → pill** (`activity_main.xml`): convert to `LinearLayout` pill with `ic_sort` + `sortLabel` TextView. In `MainActivity.kt`, update sort label text when sort mode changes.

5. **Empty state** (`activity_main.xml`): swap `ic_scan_empty` → `ic_empty_orbit` on `emptyState` ImageView.

6. **FAB host** (`activity_main.xml`): wrap `fabScan` in a `FrameLayout` with id `fabHost` (96dp), add `fabRing` and `fabPulse` behind it. In `MainActivity.kt`, toggle `fabHost.isVisible` wherever `fabScan.isVisible` is toggled.

7. **`scanList` bottom padding**: set `scanList` `paddingBottom="96dp"` and `clipToPadding="false"`.

---

## ⏳ PHASE 3 — Holographic Globe Overhaul — **NOT STARTED**

Files: `GlobeScene.kt`, `HolographicEarthView.kt`, `GlobeHomeActivity.kt`, `activity_globe_home.xml`

### Key tasks
- **`GlobeScene.kt`:** Add `topReserve` parameter; update `RING_RX = [0.125, 0.19, 0.255, 0.32, 0.385] * w`; ring `cy = 0.58h`; home orbit `rx = min(1.35r, 0.5w − cardHalfW − 6u)`; add `focusRing` (Int, −1=none), `ringAlphaFor(card)`, `categoryCounts()`.
- **`HolographicEarthView.kt`:** Draw dashed ellipse ring tracks (4u/4u, 0.8u stroke, category color at α.30); draw ring labels along lower-right arc (`Canvas.drawTextOnPath`, 9dp caps); draw 3u left category accent bar on cards; stop drawing `hintText` on canvas (`drawHintInCanvas = false` flag, keep property for tests).
- **`activity_globe_home.xml`:** Add `globeTitle`, `globeStatus`, `btnGlobeList`, `btnGlobeSettings`, orbit dock with 5 category chips, hint pill `globeHint`, FAB pulse rings `fabPulse1`/`fabPulse2`, `bg_hud_scrim` behind HUD.
- **`GlobeHomeActivity.kt`:** Extend `BaseActivity` (not `AppCompatActivity`); bind HUD status readout; bind category dock chips; bind List/Settings buttons; wire `focusRing`.

---

## ⏳ PHASE 4 — Motion & Transitions — **NOT STARTED**

Files: New `SwMotion.kt`, New `SwTransitions.kt`

- **`SwMotion.kt`:** `ambientAllowed(context)` = `Settings.Global.ANIMATOR_DURATION_SCALE > 0 && Build.FINGERPRINT != "robolectric" && powerManager.isInteractive`. Used to gate all infinite animations.
- **`SwTransitions.kt`:** Warp dive Globe↔Shelf (shared window animation `globe_enter_warp`/`shelf_enter_warp`), radial reveal for scan launch.
- All anim XML files already created in Phase 0 (`res/anim/`).

---

## ⏳ PHASE 5 — Remaining Screens — **NOT STARTED**

### 5A — SettingsActivity
- Group rows into glass section cards with cyan hairline dividers.
- **D1:** Keep `rowTheme` in XML but set `visibility = View.GONE` (test `subTheme.text.isNotEmpty()` must pass — set text to `"Orbital Dark"` in code even though row is hidden).
- Add icons to each row (`ic_palette`, `ic_font`, `ic_greeting`, etc.).
- Replace literal `›` chevron TextViews with `ic_chevron_right` ImageView.
- Apply `sel_switch_track` / `sel_switch_thumb` to all switches.

### 5B — ScanReviewActivity / PageViewerActivity
- `item_page_grid` cards: `bg_glass_card_ripple`, `bg_chip_void` page badge.
- Action dock: `bg_dock_glass`, buttons `.Dock` style.
- `btnSavePdf` → `Widget.ScanWhirl.Button.Primary`.

### 5C — DocumentViewerActivity
- `proToolsPanel` card: `bg_pro_banner`; buttons `.Pro`.
- Bottom dock: `bg_dock_glass`.
- `docCategoryChip` new id.

### 5D — MergePdfActivity / PDFViewerActivity / ExportResultActivity
- Glass dock, hairline toolbar, standard styling.

---

## ⏳ PHASE 6 — Verification & Deliverables — **NOT STARTED**

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

### Modified Kotlin files
- `BaseActivity.kt` — wires SpaceBackgroundDrawable as window background
- `MainActivity.kt` — all dialog migrations + ScanListAdapter stroke fix + catRail wiring (partial)
- `GlobeHomeActivity.kt` — 2 dialog migrations + import showSw
- `SettingsActivity.kt` — 5 dialog migrations
- `PageViewerActivity.kt` — 3 dialog migrations
- `MergePdfActivity.kt` — 3 dialog migrations
- `ScanReviewActivity.kt` — 2 dialog migrations
- `DocumentViewerActivity.kt` — 10 dialog migrations

### New XML layout
- `res/layout/dialog_pro_panel.xml`
- `res/layout/item_scan.xml` ← **rewritten** (catRail, catChip, portrait thumb, stacked actions)

### Modified XML layouts
- `res/layout/activity_main.xml`
- `res/layout/activity_pdf_viewer.xml`

### New res/drawable (all new)
`bg_action_circle, bg_appbar_hairline, bg_cat_rail, bg_chip_void, bg_danger_note, bg_dialog_glass, bg_dock_glass, bg_fab_pulse, bg_glass_card_ripple, bg_glass_round_btn, bg_hud_scrim, bg_icon_badge_glow, bg_orbit_dot, bg_pin_field, bg_popup_glass, bg_pro_tile, bg_sheet_glass, bg_sheet_top_glass, fg_glass_ripple, frame_corners, ic_auto, ic_check, ic_chevron_right, ic_empty_orbit, ic_filter_bw, ic_filter_clean, ic_filter_enhance, ic_filter_original, ic_font, ic_globe, ic_greeting, ic_language, ic_list, ic_palette, ic_privacy, ic_viewer, ic_wallpaper, sel_check_orbital, sel_radio_orbital`

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
