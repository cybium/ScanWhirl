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
- The UX pass was **not compiled or fully tested** before this snapshot. The latest recorded targeted subset was 101 tests with 3 failures in `SearchMatcherTest` and `LockedDocumentSearchPrivacyTest`.
- The full suite was last reported green at `d69a304`: 797 tests, 0 failures. Treat that as historical until rerun.
- The recording `Screen_Recording_20261113_121627.mp4` is test27. It predates the latest UX pass. Do not assume current code matches every frame.

## Read only these first

1. `START_HERE.md`
2. `docs/audit/2026-11-13/HANDOFF.md`
3. `docs/audit/2026-11-13/M08_HANDOFF.md`
4. `docs/audit/2026-11-13/UX_AUDIT.md`
5. `SCANWHIRL_CRITIQUE.md` — full audit, use only the assigned findings
6. `README.md` — build and product rules
7. Relevant source files only

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

## Current first-session scope

Work only on these unless the owner changes the scope:

1. Make the page grid authoritative after edit/delete.
2. Make B/W filtering reviewable and reversible.
3. Make save-as-new the safe default and overwrite explicit.
4. Make Resize’s units, preset/custom precedence, and keyboard action row unambiguous.
5. Verify whether the in-progress Vault entry-point cleanup and search rewrite already close the corresponding audit findings.

Do not start signature, redaction, folders/tags, AI, ID mode, or a visual redesign in the first session.

## Secrets

`keystore/scanwhirl.keystore` and `keystore.properties` are present because release test builds need them. They are ignored by Git. Never print, copy into documentation, commit, or send their contents to a model or another person. The historical `CLAUDE_CONTEXT.md` in the WSL repo contains credentials; it was deliberately excluded from this clean workspace.
