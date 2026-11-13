# ScanWhirl — working context

Last updated: 2026-11-13  
Clean workspace: `C:\Users\Cybium\Desktop\ScanWhirl`  
Build workspace: Windows 11, JDK 17, Android SDK 36

## What this project is

A local-first Android document scanner in Kotlin. Google ML Kit supplies scanning and OCR. The app owns the native library, review, reader, editor, search, categories, merge/compress/resize, PDF generation, locking, export provenance, and Trash. Test builds install as `com.scanwhirl.app.test` / “ScanWhirl Test”.

## Current snapshot

- Source basis: `/home/cybium/ScanWhirl`, branch `test26-redesign`, HEAD `d69a304` “M08 edit safety”, plus the uncommitted post-M08 UX fix pass.
- Version: `versionCode 27`, `versionName 1.1.1-test27`.
- This clean copy is frozen on branch `clean-workspace-2026-11-13`. The WSL repository remains untouched as a backup.
- The uncommitted UX pass includes direct save after Google’s scanner, auto-export, whole-word search, hiding dead Vault entry points, one ink palette, tighter Home cards, and shorter Import/Trash copy.
- The UX pass **compiled successfully in this Windows snapshot** on 2026-11-13 (`:app:compileDebugKotlin`, 1m27s). It has not been fully tested. The latest recorded targeted subset was 101 tests with 3 failures in `SearchMatcherTest` and `LockedDocumentSearchPrivacyTest`.
- The full suite was last reported green at `d69a304`: 797 tests, 0 failures. Treat that as historical until rerun.
- The recording `Screen_Recording_20261113_121627.mp4` is test27. It predates the latest UX pass. Do not assume current code matches every frame.
- Candidate D was walked over ADB on 2026-11-13. `DEVICE_FINDINGS.md` is the current-build evidence. It invalidates the video’s universal B/W failure and stale page-grid finding, and adds the page-edit/discard, PRO/Vault and hidden-action failures.

## Read only these first

1. `START_HERE.md`
2. `docs/audit/2026-11-13/HANDOFF.md`
3. `docs/audit/2026-11-13/DEVICE_FINDINGS.md` — current installed build
4. `docs/product/pdf-manager-2026-11-13/SPEC.md` — PDF Manager product spec
5. `docs/product/pdf-manager-2026-11-13/TOOL_MATRIX.md` — Free/PRO and offline feasibility
6. `docs/product/pdf-manager-2026-11-13/APK_ARCHITECTURE.md` — competitor stack/code footprint
7. `docs/competitive/2026-11-13/OPUS_BRIEF.md` — competitor synthesis
8. `docs/competitive/2026-11-13/RANKING.md`, `PRICING.md`, `UI_PATTERNS.md`
9. `docs/audit/2026-11-13/M08_HANDOFF.md`
10. `docs/audit/2026-11-13/UX_AUDIT.md`
11. `SCANWHIRL_CRITIQUE.md` — full video audit; use only the assigned findings
12. `README.md` — build and product rules
13. Relevant source files only

Do **not** load `docs/history/`, old plan/log files, or the entire research archive into context. Do not read `keystore/` or `keystore.properties`.

## Build and test on Windows

Use `gradlew.bat` from this directory. `local.properties` points at the Windows Android SDK.

- Compile: `.\gradlew.bat :app:compileDebugKotlin -PtestBuild --no-daemon`
- Unit tests: `.\gradlew.bat test -PtestBuild --no-daemon`
- Test APK: `.\gradlew.bat assembleRelease -PtestBuild --no-daemon`
- Lint: `.\gradlew.bat lintDebug -PtestBuild --no-daemon`

The build is memory-sensitive. Run one gate at a time in the foreground. Never build a production APK by omitting `-PtestBuild` unless the owner explicitly asks.

## Hard rules

- One focused task at a time; no speculative features while the trust/state findings are open.
- Do not change `versionCode`, `versionName`, Play package ID, signing material, or push to Git.
- No network/analytics/ads/new permissions/new dependencies.
- Every changed user-visible string needs English in `values/strings.xml` and Hindi in `values-hi/strings.xml`.
- Preserve the original file on every save. A destructive action is never the primary visual choice.
- If a state count, thumbnail, and save summary disagree, treat it as a bug even if the arithmetic is explainable.

## Current first-session scope — device-verified

Work only on these unless the owner changes the scope:

1. Resolve the page-edit transaction: deletion survived force-stop, Save changes has no Discard, and the target file is unnamed. Prove whether the page set and PDF can diverge.
2. Fix or remove the PRO/Vault dead end. Do not ship “Not available yet” behind a PRO badge, and do not offer an add-to-Vault path that dies in a toast.
3. Restore visible document actions and lock state. The reader currently offers only Find; Share/Edit/Text/More are hidden behind Home overflow → Edit pages.
4. Fix Resize: keyboard hides actions, 500 KB sits alone, the hint looks like a value, and the keyboard is textual.
5. Make the privacy wording precise: Auto enhance says “Google processing” while About says “no servers.”
6. Verify the current WIP’s Vault, palette, Tools and direct-save changes on device before adding more UI work.

Do not implement the video-only stale page grid or treat B/W as universally broken; both failed to reproduce on candidate D. Do not start signature, redaction, folders/tags, AI, ID mode, or a visual rebrand in the first session.

## Secrets

`keystore/scanwhirl.keystore` and `keystore.properties` are present because release test builds need them. They are ignored by Git. Never print, copy into documentation, commit, or send their contents to a model or another person. The historical `CLAUDE_CONTEXT.md` in the WSL repo contains credentials; it was deliberately excluded from this clean workspace.
