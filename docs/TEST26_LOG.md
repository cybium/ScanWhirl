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
  (edge-to-edge + `applySystemBarsPadding` is new in this build). Waiting for
  screenshots/OK before Phase 2.

---
