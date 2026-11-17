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
- Releases: 1.2.1 (`26111601`) uploaded and accepted on Play closed testing (2026-11-16); 1.2.2
  (`26111602`) = trust-pill fix. Version codes are date based `YYMMDDnn` in `app/build.gradle.kts`.
  A code already uploaded to Play can never be reused: every new upload needs the next number.

## LEAD MEMORY (read first; written by the lead after the 2026-11-15/16 sessions, owner-ordered)
The lead (Opus) repeated these mistakes. Do not repeat them after a context compaction.
1. **Read the memory before acting**: this section, `docs/plans/lanes/73_ORCHESTRATION_LOG.md`
   (tail), `docs/plans/lanes/72_WORKER_BRIEF.md`, `docs/release/NEXT_RELEASE.md`. Tools already exist:
   `tools/gate.ps1` (serialized build; full suite runs in the cloud), `tools/lane-watch.ps1`
   (30-min watchdog of every lane), `tools/lane-review.ps1` (pre-merge check), `tools/wait-integration.ps1`,
   `tools/phone.ps1`, `tools/store_frames.py`, `tools/copy_audit.ps1`.
2. **Lead = boss, not worker.** Delegate with EXACT briefs (files, steps, tap paths, acceptance
   tests) and verify the result yourself (diff, screenshots, gate line). Take a worker's job only
   when it is stuck; say so.
3. **Every worker brief cites 72_WORKER_BRIEF.md**: never run gradlew, only `tools\gate.ps1`, gate in
   the FOREGROUND with timeout 5400000, never end the turn while a gate waits. Five local Gradle
   builds at once on this 7.4 GB PC killed a worker.
4. **Models (owner rules)**: opencode-go = ONLY `deepseek-v4.1-flash#high`. **Never use Grok.**
   OpenAI: research the model before launching it, never guess an effort level. GPT-6.1 Sol = strict
   reviewer of risky code (money, data). GPT-6.1 Terra at medium = visual / computer-use / video work.
   Astra burns the ChatGPT allowance; avoid. DeepSeek = volume work, but never merge its
   money/data code without a strong reviewer (it introduced billing regressions all tests missed).
5. **Merge one lane at a time**, wait for the integration cloud gate (lint runs only there) before
   the next merge. Known runner-flaky tests (ScanEnhanceQuality timing, PageNormalization OOM,
   ExportHygiene) re-run automatically once when they are the only failures.
6. **Never claim something is done before it exists and you have looked at it.** Say "not done yet"
   plainly. Check the actual gate line, the actual file, the actual screenshot.
7. **Phone**: one user at a time; lock with `C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\PHONE_BUSY`. Only
   `com.scanwhirl.app.demo` / `.test`; never the owner's `com.scanwhirl.app`; never airplane mode /
   Wi-Fi / data / hotspot; never tap a file-picker preview icon (opens Chrome); never buy.
   Verified paths: nav y=2214 (Library x130, Tools x315, Vault x760, Me x948); doc dock y=2234
   (Share 241, Edit 540 = page editor, Text 738, More 938); Hide private info = Tools > search
   "private" > "Redact" > pick doc > "Hide private info" > Find > Hide selected > Back > Discard;
   Free/Pro test switch = Me > Settings > scroll > id `switchTestFree` (screen reloads after toggle);
   language = Home chip "A/अ". Status bar: `am broadcast -a com.android.systemui.demo` (enter,
   clock 1000, battery 100, network wifi hide + mobile level 4, notifications hidden).
8. **Store screenshots**: demo app only, fictional docs (`tools/store_samples.py`), Free mode except
   Pro-only tools, never edit app content to make a screenshot look better (real OCR, even a misread),
   Pro screenshot = TOP of the Pro screen (logo, pills, 8 tools, "See all N"), NOT the price card.
   Look at every framed image before reporting.
9. **Copy**: no long dashes anywhere users read (StringsNoLongDashTest); no false claims (no family
   sharing; tool counts derived; "Offline" pill wording is the owner's choice); EN + HI always.
10. **Owner style**: speed AND quality; short answers; no defensive narration; when the owner says
   stop, stop; do not start new searches/audits when the owner asks to finish a release. When the
   owner asks for one word, answer in one word. Legal/policy answers: cite the rule, never guess.
11. **"Kill" means kill**: Stop-Process the worker's shell/gate processes immediately, then send the
   session "END NOW, run no tools". Never leave a worker in a wait/poll loop (every check burns
   tokens): end it, and resume the same session ID later when its input is ready.
12. **Roles (owner order 2026-11-17)**: the owner is the GUIDE, the lead is the LEAD. The plan is the
   lead's and its flaws are the lead's. The lead may and must criticise owner decisions when they
   are wrong: say what, why, and what the lead does instead, then go with the best idea.
13. **Direction change (2026-11-17)**: ScanWhirl moves to a NEW test app built one feature at a time,
   each 100% complete before the next. The app WILL use the internet (owner found fatal flaws in
   offline-only: purchases, analytics for a new app). Feature 1 = our own scanner. Process per
   feature: design interface + navigation -> criticise -> rework -> only then code. No phone:
   emulators (Google Play system images), heavy work on GitHub cloud runners. Check current library
   docs (docs MCP) before writing code; never rely on memory for APIs. The scanner must be our own
   code: no copied proprietary code (see the scanner plan for the clean-room rule).
   Plan of record: `docs/plans/NEXT_APP_PLAN.md` (read it first). MCPs: `context7` (library docs),
   `mobile` (emulators; NEVER its paid remote devices), `google-docs` (needs owner's API key).
15. **Workspace (2026-11-17)**: everything lives in `C:\Users\Cybium\Desktop\ScanWhirl-HQ\`:
   `current\` (this repo, 1.2.x), `next\` (new app, private repo `cybium/scanwhirl-next`), `lanes\`
   (future worktrees), `qa\` (was .sw-qa; real-clip benchmark in `qa\recordings\`), `locks\`,
   `assets\`, `research\`, `promo\`, `privacy-site\`. Desktop `Others` + shortcuts are the owner's:
   never touch. Old lane work is on GitHub (`lane/*`, `park/stash-0`, `park/stash-1`). Local MCPs
   must use `cwd: "~"` (a process inside a folder locks it on Windows).
14. **Worker rules (learned 2026-11-17)**: every task ends with a scope block listing the only files
   it may touch; a script checks `git diff --name-only` against that list (no judging by eye);
   DeepSeek gets 1-3 file tasks with the test given; reviewers are fresh-context, read-only, with a
   closed defect list; an ambiguous owner reply is NOT approval; max 2 workers at once.
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
  screenshots and recordings stay outside the repo (`Desktop\ScanWhirl-HQ\qa\shots`).
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
