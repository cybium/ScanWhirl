# ScanWhirl — working context for AI sessions

Last updated 2026-11-16. This file and `START_HERE.md` are the truth about the repository today;
anything older under `docs/history/` is not instruction.

## The project

- Android app in Kotlin, package `com.scanwhirl.app`, minSdk 24, target/compileSdk 36, JDK 17.
- Local-first document scanner: ML Kit document scanner + on-device text recognition, a library of
  scans, 43 tools (`ui/kit/ToolRegistry.kt`), an encrypted Vault, encrypted full backup, and a
  one-time Pro unlock through Play Billing (`scanwhirl_pro`).
- English and Hindi only. No internet permission: the manifest removes `INTERNET` and
  `ACCESS_NETWORK_STATE`; the app never talks to a server, has no ads and no analytics.
- Current releases: 1.2.0 (`versionCode 26111501`) on Play closed testing, 1.2.1 (`26111601`) being
  prepared. Version codes are date based `YYMMDDnn`, defined in `app/build.gradle.kts`.
- Branches: `master` (main), `integration` (working branch), `lane/<name>` (one task each). Never
  merge a lane into `integration` yourself — the lead merges.

## Read before editing

1. [`START_HERE.md`](START_HERE.md) — orientation and where everything lives.
2. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — package map, key classes.
3. The doc for your area (`docs/release/`, `docs/design/2026-11-14/`, `docs/plans/pdf-manager/`,
   newest `docs/audit/<date>/`).
4. Only the source files that own the task. Do not bulk-read the tree.

Never read, print, copy or move `keystore/`, `keystore.properties` or `local.properties`.

## Build and test (Windows PowerShell, repo root)

```powershell
.\gradlew.bat :app:compileDebugKotlin -PtestBuild --no-daemon   # quick compile
.\gradlew.bat :app:testDebugUnitTest -PtestBuild --no-daemon     # full unit suite (Robolectric)
.\gradlew.bat :app:lintDebug -PtestBuild --no-daemon             # lint (abortOnError = true)
```

- In a lane worktree use `powershell -File tools\gate.ps1 -Lane <lane> [-Tests "Class"]`; it
  serializes builds across lanes and prints `GATE PASS` / `GATE FAIL`. Waiting is normal.
- The authoritative gate is CI: `.github/workflows/gate.yml` runs the full suite with `-PtestBuild`,
  lint on `integration`, and the minified release build with `-PciUnsignedRelease`. Only a
  `GATE PASS` line counts as green.
- Unit tests are Robolectric/JVM tests under `app/src/test/java/com/scanwhirl/app/`, mirroring the
  main package names. Any new behaviour needs a test in the same package.
- Do not run `assembleRelease` without `-PtestBuild` unless the owner asks; that is the Play build.

## Build variants

| Variant | applicationId | Use |
| --- | --- | --- |
| `-PtestBuild` | `com.scanwhirl.app.test` | QA and all agent testing ("ScanWhirl Test", Pro unlocked, no R8, test harness). |
| `-PtestBuild -PdemoBuild` | `com.scanwhirl.app.demo` | Store screenshots only; a clean, empty library. |
| `-PciUnsignedRelease` | `com.scanwhirl.app` | CI-only release build signed with the debug key. Never distribute. |
| (no properties) | `com.scanwhirl.app` | Real Play build signed with the owner's upload key. Owner only. |

## Rules that are true now

- **Strings:** every new or changed user-visible string goes into `values/strings.xml` (English) and
  `values-hi/strings.xml` (Hindi) in the same commit. Hindi entries carry `<!-- review -->`.
  Never hardcode user-visible text in Kotlin.
- **Privacy:** no network code, no ads, no analytics, no crash reporting, no new permission and no
  new dependency without the lead's explicit approval. The app has no `INTERNET` permission.
- **Version:** never change `versionCode`, `versionName`, the applicationId or signing config
  unless the task is explicitly a release task from the owner.
- **Secrets:** never print, commit, copy or send keystore material, passwords, or the contents of
  `keystore.properties` / `local.properties`.
- **Tests:** no `@Ignore`, no deleting or weakening a test to get green. A flaky Robolectric JVM
  abort is retried by the gate; a real failure is fixed.
- **Phone work:** only install and drive `com.scanwhirl.app.test` or `com.scanwhirl.app.demo`.
  Never uninstall, clear data, force-stop or change settings of the `com.scanwhirl.app` production
  install. Never toggle airplane mode or other device-wide settings. Use `tools/phone.ps1`;
  screenshots and recordings stay outside the repo (`Desktop\.sw-qa\shots`).
- **Screenshots for the store:** always the `-PdemoBuild` copy, never the production app and never a
  phone with the owner's real library.
- **Commits:** small and focused, subject `<area>: <what changed>`, one task per commit. Never
  rewrite history, never push to `master` or `integration`, never `git push --force`.
- **Docs:** no new files at the repository root. Dated documents go under
  `docs/<kind>/<yyyy-mm-dd>/`; stale documents move to `docs/history/2026-11/` with `git mv`, never
  deleted.

## Code map in one screen

- `ui/shell/` — `ShellActivity` (launcher screen, bottom tabs Library / Tools / Vault / Me) plus
  `LibraryScreen`, `ToolsHubScreen`, `VaultScreen`, `MeScreen`, `ProScreen`.
- `ui/kit/` — design system (`SwTheme`, `SwStyle`, `SwSheet`, `SwSnackbar`, …) and `ToolRegistry`.
- `ui/tools/`, `ui/stamp/`, `ui/protect/`, `ui/qr/`, `ui/book/`, `ui/pagegrid/`, `ui/vault/`,
  `ui/backup/`, `ui/home/` — feature screens.
- `tools/` — non-UI flows that stitch screens to engines (merge, compress, resize, lock, OCR, …).
- `store/` — `ScanRepository` (documents, pages, exports, trash) and the stores around it;
  `store/vault/` (encrypted Vault) and `store/backup/` (`FullBackup`).
- `util/` — PDF writer/encryption/import, OCR engine, image processing, billing, prefs.
- Details and one line per package: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
