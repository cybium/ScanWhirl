# ScanWhirl test26 — Execution Report

**Version:** 1.1.1-test26 (versionCode 26)
**Branch:** `test26-redesign`
**Date:** 2026-11-08
**Package:** `com.scanwhirl.app.test` / `ScanWhirl Test`

## Summary

Completed the full test26 redesign: Phase 5 → Phase 6 → Phase 7 → Phase 8 → Phase 9.
All phase-end gate checks passed: **0 failures, 0 skipped** on every full-suite run.

## Phase Results

| Phase | Tests | Failures | Skipped | Status |
|-------|-------|----------|---------|--------|
| 5 (Viewers/editors) | 630 | 0 | 0 | ✅ GREEN |
| 6 (OCR Editor) | 666 | 0 | 0 | ✅ GREEN |
| 7 (Remove Earth) | 594 | 0 | 0 | ✅ GREEN |
| 8 (Futuristic layer) | 594 | 0 | 0 | ✅ GREEN |
| 9 (Release) | 594 | 0 | 0 | ✅ GREEN |

Test count rose in Phase 6 (+36: OcrCleanup 12, OcrEditor 4, TextFitTest OCR 1, plus existing), fell in Phase 7 (−72: deleted globe tests), stable in Phase 8 (UI-only).

## Commits (since `4cc05ab` T5.1)

```
e0bcd2e test26 T5.5: dock buttons paddingHorizontal 2dp (More no longer wraps at scale 1.3)
663eb58 test26 docs: log T5.5 results
09dc530 test26 T6.1: OCR cleanup functions joinLines/fixSpaces/removeGaps + OcrCleanupTest
21ccb52 test26 T6.2: OCR Editor activity + layout + OcrEditorTest
05fd1e2 test26 T6.3: wire OcrEditorActivity into OcrFlow, share_text string, TextFitTest addition
b6f39bc test26 T7.1: delete globe package, globe tests, SwTransitions + manifest entry
df992a2 test26 T7.2: remove Prefs.globeHomeEnabled, Settings globe row + globe strings
8e27065 test26 T7.3: remove unused globe resources (lintDebug confirmed)
a735c0d test26 T7.4: fix settings appearance card count after globe row removal
027f7e5 test26 docs: log Phase 7 results
379b903 test26 T8.1-T8.5: signal dot, viewfinder brackets, dashed Unsorted chip, haptic tick
cd96f27 test26 T8.6: Hindi backlog — 273 legacy strings added to values-hi
2255281 test26 T8.3: scan-line sweep drawable + animator
6f3982d test26 docs: log Phase 8 results
2111e7f test26 T9.1: versionCode 26, versionName 1.1.1-test26
```

Plus docs-only commits for phase log updates.

## Key Changes

**Phase 5 — T5.5 (TextFit sweep):**
- Fixed `btnMore` text-wrap at font scale 1.3 via `paddingHorizontal="2dp"` on all four document-dock buttons (root cause was horizontal text area, not vertical padding)
- Added `onCreateOptionsMenu`/`onOptionsItemSelected` to `DocumentViewerActivity` so `clickMenuItem(action_save_pdf)` reaches `savePdfToDocuments()`
- Shortened settings subtitle to fit within available width at scale 1.0

**Phase 6 — OCR Editor:**
- `OcrCleanup.kt`: `joinLines`, `fixSpaces`, `removeGaps` (12 tests)
- `OcrEditorActivity.kt`: full-screen OCR editor with page tabs, cleanup bar, Copy/Share/Save, dirty-state back confirm (4 tests)
- `OcrFlow.kt`: rewired to launch `OcrEditorActivity` via cache file + `EXTRA_TEXT_PATH` (never Intent extra)
- `share_text` string added (en + hi)

**Phase 7 — Remove the Earth (−2,838 lines):**
- Deleted `ui/globe/` (4 files), globe tests, `SwTransitions.kt` + test, globe manifest entry
- Removed `Prefs.globeHomeEnabled`, Settings globe row, all globe strings/resources
- `SwMotion.kt` + `SwMotionTest` kept (needed by Phase 8)
- Tag `pre-globe-removal` created before deletion

**Phase 8 — Futuristic layer:**
- T8.1: Signal dot next to wordmark, lit during background work, breathing gated on `SwMotion.ambientAllowed`
- T8.2: Viewfinder corner brackets on SCAN button
- T8.3: Scan-line sweep drawable + animator (1.2s, gated)
- T8.4: Unsorted chip dashed outline
- T8.5: Haptic tick on category change
- T8.6: 273 legacy Hindi strings added to values-hi

**Phase 9 — Release:**
- versionCode 26, versionName `1.1.1-test26`
- aapt2 confirms: `com.scanwhirl.app.test`, `ScanWhirl Test`, version 26
- APK + source zip delivered to `/mnt/c/Users/Cybium/Downloads/`

## Deliverables (in Downloads)

- `ScanWhirl-1.1.1-test26.apk` — release APK (test package, versionCode 26)
- `ScanWhirl-1.1.1-test26-source.zip` — full source (excludes keystore, CLAUDE_CONTEXT, build outputs)

## Constraints Honored

- No `git push`
- No network/analytics/ads
- `versionCode` stayed 25 until T9.1, then bumped to 26
- Package `com.scanwhirl.app.test` only, label `ScanWhirl Test`
- Keystore untouched
- No `@Ignore`; every new/changed string has `values-hi` entry in same commit
- Every test run judged by XML parse of `app/build/test-results/*/*.xml`, never exit code
