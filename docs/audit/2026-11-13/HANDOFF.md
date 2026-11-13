# ScanWhirl handoff — 2026-11-13

## The short version

The codebase is modular and test-heavy. The handoff was the problem. This folder is the clean start.

Do not treat the test27 recording, the current working tree, and the historical docs as the same state.

## Four states that must not be conflated

| State | Evidence | What it means |
|---|---|---|
| Recording | `Screen_Recording_20261113_121627.mp4`, 12:16 | Shows test27, before the latest UX pass. Findings must be timestamped against this. |
| Candidate D APK | `builds/2026-11-13/ScanWhirl-M08-candidate-D.apk`, built 03:26 | Version test27. Includes direct save, not the latest UX fix pass. SHA-256 in `ARTIFACTS.md`. |
| Committed source | `d69a304`, 12 Nov | M08 edit safety. Historical full suite reported 797/0. |
| Current working tree | 20 modified files + 3 untracked files frozen in this snapshot | Post-M08 UX pass. Not fully compiled or tested. Latest targeted subset: 101 tests, 3 failures. |

The recording is therefore older than part of the current tree. Re-test findings on a device rather than assuming every frame is still exact.

## What is already changed in the current tree

- Direct save after Google’s scanner; failure keeps pages and opens Review.
- Opt-in Documents copy through `AutoExport`.
- Whole-word search matching; two-term queries must match within name or within text, not split across both.
- Vault entry points hidden when no legacy PIN exists.
- One ink palette; star-field background removed from `BaseActivity`.
- Home cards shortened; “to organize” row retired; sans titles.
- Import and Trash copy shortened.
- Settings status-line and wallpaper rows hidden.

Some of these directly overlap the audit. Check the current tree first.

## Last recorded test state

- Last full-suite claim: **797 tests, 0 failures** at `d69a304`, recorded in the owner’s handoff.
- Current tree: not compiled or fully tested.
- Last targeted subset on disk: **101 tests, 3 failures**:
  - `SearchMatcherTest` — two-term query requires both terms
  - `SearchMatcherTest` — name hit ranks above text-only hit
  - `LockedDocumentSearchPrivacyTest` — name-plus-content privacy case

The three tests were rewritten for the new search rule. Determine whether the failures are stale expectations, red-first tests, or regressions before changing behaviour.

## First session scope

Do not start with the full 30-finding critique. Use these five:

1. **F02 — page grid after deletion.** Editor says 1/1, grid says 2 pages. Make the grid, page count, save summary, and reopened document agree. Likely files: `PageGridView.kt`, `ScanRepository.kt`, `SaveTransaction.kt`, `DocumentViewerActivity.kt`.
2. **F01 — B/W legibility.** Existing-page B/W makes a readable page nearly disappear. Add full-page review, Original/Result comparison, explicit revert, and verify the exported page. Likely: `PageViewerActivity.kt`, `PageGridView.kt`, `ImageTools.kt`, `PageReplace.kt`.
3. **F03 — overwrite emphasis.** “Overwrite saved PDF” is visually primary. Make save-as-new primary, name the affected file, and make overwrite a confirmed destructive secondary action. Likely: `SaveTransaction.kt`, `DocumentViewerActivity.kt`, `ScanReviewActivity.kt`, `DownloadSaver.kt`.
4. **F05/F06 — Resize.** Keyboard hides Cancel/Resize; custom field loses its unit; preset and custom precedence is unexplained. Likely: `ResizeFlow.kt`, `CompressFlow.kt`, resize layout.
5. **F04 — Vault.** The audit saw “unavailable” after tapping a PRO tile. The current tree hides the tile when no legacy PIN exists. First verify that on device; do not rewrite the Vault unless the dead end remains.

Second session, only after those are green: OCR proofreading, password target/search consequence, privacy wording, naming at save, and file-source choosers.

## Out of scope for the first session

- Signature, annotation, secure redaction, ID-card dual-side mode.
- Folders/tags, cloud sync, widgets, inbound share.
- AI summaries or flashcards.
- Visual rebrand, new dependencies, architecture rewrite.
- Version bump or production package changes.

## Source ownership map

| Area | Start here |
|---|---|
| Home/library, search, categories | `ui/MainActivity.kt`, `ui/ScanListAdapter.kt`, `ui/home/`, `util/SearchMatcher.kt`, `util/HomeFilter.kt` |
| Scanner + direct save | `tools/ScanLauncher.kt`, `store/PendingScans.kt`, `store/SaveTransaction.kt` |
| Review and save | `ui/ScanReviewActivity.kt`, `store/ScanRepository.kt`, `store/ExportProvenance.kt`, `util/DownloadSaver.kt` |
| Page grid/editor | `ui/pagegrid/PageGridView.kt`, `ui/DocumentViewerActivity.kt`, `ui/PageViewerActivity.kt` |
| Page image safety | `util/ImageTools.kt`, `util/PageReplace.kt`, `util/PageImageFormat.kt` |
| OCR/read text | `ui/OcrEditorActivity.kt`, `util/OcrCleanup.kt`, `util/TextHistory.kt`, `util/OcrEngine.kt` |
| Compress/resize/merge | `tools/CompressFlow.kt`, `tools/ResizeFlow.kt`, `tools/MergeFlow.kt` |
| Lock/Vault | `tools/LockFlow.kt`, `tools/VaultFlow.kt`, `util/PdfEncryptor.kt`, `util/ProGate.kt` |
| Settings/privacy | `ui/SettingsActivity.kt`, `util/Prefs.kt`, `ui/BaseActivity.kt`, `res/values/colors.xml` |

These are starting points, not proof of ownership. Confirm with search before editing.

## Working rules for the next agent

- Produce a discrepancy report before editing.
- One finding per change set; compile after each batch.
- Run tests in the foreground; the machine is memory-sensitive.
- Do not touch the keystore, version number, package ID, or production build.
- Do not use the historical test26 plans as current instructions.
- Preserve original files on every save.
- For any claim about the recorded UI, cite a timestamp and screen name.

## Evidence

`evidence/` contains 15 current screenshots from the M08 device walk. `EVIDENCE.md` maps them. The full audit is at the workspace root: `SCANWHIRL_CRITIQUE.md`.
