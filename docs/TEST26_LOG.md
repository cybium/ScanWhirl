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

---
