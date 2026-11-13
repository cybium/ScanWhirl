# ScanWhirl handoff — 2026-11-13

## The short version

The codebase is modular and test-heavy. The handoff was the problem. This folder is the clean start.

Do not treat the test27 recording, the current working tree, and the historical docs as the same state.

**A device walk on candidate D was completed on 2026-11-13.** Read `DEVICE_FINDINGS.md` immediately after this file. It supersedes two video findings and adds new current failures.

**A ten-app competitor teardown is in `../../competitive/2026-11-13/`.** Read `OPUS_BRIEF.md` before proposing a new Home, document action model or Pro offer. It is based on actual Play Store installs, in-app paywalls and device screenshots, not launch-page marketing.

**A PDF Manager product spec is in `../../product/pdf-manager-2026-11-13/`.** It defines the PDF hub, page organizer, tool matrix, Free/PRO split, pricing, and offline feasibility. Read it before designing the PDF manager or new PDF tools.

## Four states that must not be conflated

| State | Evidence | What it means |
|---|---|---|
| Recording | `Screen_Recording_20261113_121627.mp4`, 12:16 | Shows test27, before the latest UX pass. Findings must be timestamped against this. |
| Candidate D APK | `builds/2026-11-13/ScanWhirl-M08-candidate-D.apk`, built 03:26 | Version test27. Includes direct save, not the latest UX fix pass. SHA-256 in `ARTIFACTS.md`. |
| Committed source | `d69a304`, 12 Nov | M08 edit safety. Historical full suite reported 797/0. |
| Current working tree | 20 modified files + 3 untracked files frozen in this snapshot | Post-M08 UX pass. Not fully compiled or tested. Latest targeted subset: 101 tests, 3 failures. |

The recording is therefore older than part of the current tree. Re-test findings on a device rather than assuming every frame is still exact.

**Two video findings did not survive device testing:**

- The stale page grid (F02) did not reproduce: deleting page 2 produced a 1-page grid.
- The universal B/W failure (F01) did not reproduce on the DocX contents page: B/W was legible. Treat it as image-dependent.

**Two worse current failures emerged:**

- Page deletion persisted through a force-stop, while the Save dialog offered no discard.
- PRO/Vault is a “Not available yet” dead end with no purchase or benefit list.

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
- Current tree: **compiled successfully on Windows on 2026-11-13** (`:app:compileDebugKotlin`, 1m27s); not fully tested.
- Last targeted subset on disk: **101 tests, 3 failures**:
  - `SearchMatcherTest` — two-term query requires both terms
  - `SearchMatcherTest` — name hit ranks above text-only hit
  - `LockedDocumentSearchPrivacyTest` — name-plus-content privacy case

The three tests were rewritten for the new search rule. Determine whether the failures are stale expectations, red-first tests, or regressions before changing behaviour.

**Device test-document note:** the candidate-D walk changed the fixture `DocX` from 2 pages / 1.5 MB to 1 page / 1009 KB. Page 2 (contents) was deleted and did not return after force-stop. This is recorded as D1; do not run further destructive edits on that fixture without a backup.

## First session scope — device-verified

Do not start with the full 30-finding critique. Use these five:

1. **D1/D2 — page-edit transaction and Save changes.** Page deletion survived force-stop; the Save dialog offers Overwrite / Cancel / Save as new, with no Discard and no filename. Prove whether the page set and PDF can diverge. Make the transaction explicit, name the file, and add a real Discard/Keep-editing path. Likely: `SaveTransaction.kt`, `ScanRepository.kt`, `DocumentViewerActivity.kt`, `SaveReviewIdentityTest.kt`.
2. **D3/D4 — PRO and Vault.** PRO says “Not available yet” with no price, benefits or purchase; Vault dies in a toast. The current WIP hides Vault and removes the PRO band; first verify those changes, then decide the honest production surface. Do not ship “Not available yet” behind a PRO badge.
3. **D5/D6 — document actions and lock state.** The saved reader offers only Find; Share/Edit/Text/More are reachable only via Home row overflow → Edit pages. Lock is in the page-grid More sheet, with no row badge. Restore a visible document action dock and a lock state.
4. **D7 — Resize.** Keyboard hides Cancel/Resize; 500 KB is orphaned on its own row; the hint looks like a value; the field uses a text keyboard. Likely: `ResizeFlow.kt`, resize layout.
5. **D11 — privacy wording.** Auto enhance is ON and says “Google processing”, while About says “no servers.” State exactly what runs on-device and what leaves it.

Second session, only after those are green: B/W comparison/revert (not a universal failure), OCR proofreading, password target/search consequence, naming at save, and source choosers.

Do not implement F02 (stale grid) or claim universal F01 (B/W failure) without reproducing them on the current build.

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
