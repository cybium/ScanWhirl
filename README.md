# ScanWhirl

Private, offline document scanner for Android. Scan, clean up, search, protect and export PDFs on
the phone — no account, no ads, no analytics, no server, no internet permission.

- Google Play: <https://play.google.com/store/apps/details?id=com.scanwhirl.app>
- Privacy policy: [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) · <https://cybium.github.io/scanwhirl-privacy/>
- Support: cybiumsupport@gmail.com

## What the app does

- Scan with Google's on-device document scanner, review pages, save a PDF (text is read in the
  background so the PDF is searchable).
- Library: categories, folders, tags, search across names and OCR text, Quick Sort, Trash.
- 43 tools in one catalogue (`ui/kit/ToolRegistry.kt`): merge, split, compress, resize, rotate,
  reorder, page editor, text/OCR editor, protect (password) PDF, PDF/A, import, QR, ID card,
  passport, business card, stamps and signatures, Aadhaar masking, Word/Excel/PowerPoint export.
  Tools marked "Coming soon" say so honestly; none dead-end silently.
- Vault: documents encrypted on the phone (AES-256-GCM) behind passcode, recovery code or
  fingerprint/face; optional encrypted full backup of library + Vault + settings.
- ScanWhirl Pro: one-time purchase, product id `scanwhirl_pro` (Play Billing). Test builds unlock
  Pro automatically so the Pro UI can be reviewed.
- English and Hindi only.

## Current state

- Release 1.2.0 (versionCode `26111501`) is on Play **closed testing**; 1.2.1 (`26111601`) is being
  prepared. Version codes are date based: `YYMMDDnn`, read from `app/build.gradle.kts`.
- Working branch: `integration`. `master` is the main branch. Everything lands in `integration`
  through a lane branch and a merge the lead performs.
- The referee for "done" is the CI gate (`.github/workflows/gate.yml`): every unit test with
  `-PtestBuild`, lint on `integration`, and a minified release build with `-PciUnsignedRelease`.
  The gate summary ends with `GATE PASS` or `GATE FAIL`.

## Build and test

Requirements: JDK 17, Android SDK 36 (compileSdk 36). `local.properties` is untracked and points
at the SDK. Windows PowerShell, from the repository root:

```powershell
.\gradlew.bat :app:compileDebugKotlin -PtestBuild --no-daemon   # fast compile check
.\gradlew.bat :app:testDebugUnitTest -PtestBuild --no-daemon     # full unit suite (Robolectric)
.\gradlew.bat :app:lintDebug -PtestBuild --no-daemon             # Android lint
```

The suite is memory-hungry; run one build at a time. In a lane worktree use the serialized gate
instead of raw Gradle:

```powershell
powershell -File tools\gate.ps1 -Lane L3                      # full suite, prints GATE PASS/FAIL
powershell -File tools\gate.ps1 -Lane L3 -Tests "com.scanwhirl.app.util.PdfAesTest"
```

`tools/gate.ps1` queues behind other lanes, runs the full suite in the cloud when the PC is busy
(`-Local` forces it locally), and is the supported way to claim a green gate. CI also runs on every
push to `integration`.

## Build variants

| Command | applicationId | What it is |
| --- | --- | --- |
| `gradlew.bat assembleRelease` | `com.scanwhirl.app` | Production build, signed with the owner's upload key. Owner only. |
| `-PtestBuild` | `com.scanwhirl.app.test` | "ScanWhirl Test": side-by-side QA build, Pro unlocked, no R8, test harness included. |
| `-PtestBuild -PdemoBuild` | `com.scanwhirl.app.demo` | Clean third copy for Play screenshots. Never touch the installed app for screenshots. |
| `-PciUnsignedRelease` | `com.scanwhirl.app` | Release variant signed with the debug key, for CI smoke tests. Never distribute. |

## Repository layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/scanwhirl/app/` | App code — see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). |
| `app/src/main/res/values*/` | Strings: `values` (English), `values-hi` (Hindi). Both are required. |
| `app/src/test/` | Robolectric/JVM tests, mirroring the package layout (`eval/`, `perf/`, `store/`, `tools/`, `ui/`, `util/`). |
| `docs/ARCHITECTURE.md` | Package map and the key classes. |
| `docs/release/` | Play listing, data safety, release notes, launch checklist. |
| `docs/audit/`, `docs/design/`, `docs/plans/`, `docs/competitive/` | Dated audits and designs; newest date wins. |
| `docs/history/` | Frozen older plans, handoffs and audits — provenance only. |
| `tools/` | Gate/wait/lane scripts, phone screenshot helper (`phone.ps1`), PDF checks, store and promo asset scripts. |
| `store_assets/` | Play icon and feature graphic. |

## Rules in one line

Every user-visible string ships in English **and** Hindi (Hindi entries carry `<!-- review -->`);
no network, ads, analytics, new permissions or new dependencies; no secrets (`keystore/`,
`keystore.properties`, `local.properties`) are ever printed, copied or committed. Details:
[`CLAUDE.md`](CLAUDE.md).
