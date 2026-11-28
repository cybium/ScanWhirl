# Real-Android E2E smoke (free GitHub runners)

The Robolectric gate proves logic on the JVM. This job proves the *same APK the
phone gets* actually starts and draws on a real Android system image: a missing
view, a crash on launch, an untranslated label or a clipping bottom bar only
shows up here.

| file | role |
| --- | --- |
| `.github/workflows/e2e.yml` | build one test APK, then run the smoke on API 35 and API 36 (matrix) |
| `tools/e2e/smoke.sh` | everything that happens inside the booted emulator (adb only) |
| `tools/e2e/flows.sh` / `flow_driver.py` | F1–F9 device checklist journeys after smoke, text/description driven |
| `tools/e2e/uitool.py` | uiautomator dump / find-by-view-id / tap / screenshot sanity |
| `tools/e2e/make_pdf.py` | writes the two self-authored PDF fixtures at run time |
| `tools/e2e/run_e2e.ps1` | push a worktree snapshot to `ci/e2e-<name>` and print the run URL |
| `tools/e2e/atlas.py` / `atlas.sh` | CI-only screen atlas: walk every screen, screenshot + XML + quality flags |
| `.github/workflows/atlas.yml` | build once, then four legs: light / dark / 200% font / API 24 small phone |

## How to run it

```powershell
# any lane worktree, branch ci/e2e-<lane>
powershell -File tools/e2e/run_e2e.ps1 -Lane e1emulator
# a specific name, e.g. proof on an integration-equal commit
powershell -File tools/e2e/run_e2e.ps1 -Lane e1emulator -Name integration
```

Or by hand: Actions -> **e2e** -> *Run workflow* with a branch/tag/SHA. Pushes to
`ci/e2e-*` start it automatically. `run_e2e.ps1` never writes any ref other than
`ci/e2e-<name>`; integration stays the lead's.

## What one run does

Per API level (35 and 36, `google_apis` x86_64 image):

1. installs `app-debug.apk` built with `-PtestBuild` (`com.scanwhirl.app.test`),
   no runtime permission is granted;
2. pushes two generated PDFs to `/sdcard/Download` (proves the fixture path
   works; never personal or corpus files);
3. for both languages (`en`, `hi`) and both font scales (`1.0`, `2.0`):
   launches the app and walks Library -> Tools -> Vault -> Me -> centre Create
   sheet, screenshotting each screen and dumping the uiautomator XML;
4. rotates once on the Library and asserts the process survives and the UI is
   still there;
5. scans logcat for a `FATAL EXCEPTION` or `am_anr` naming the test package and
   asserts the process is still alive;
6. writes `artifacts/<api>/summary.md` (PASS/FAIL table) and uploads the whole
   folder (screenshots, XML, logcat) for seven days.

Then `flows.sh` runs the migrated device checklist on the **same** emulator/APK:
generated three-page PDF import through the system Downloads picker; complete
Tools catalogue and two recent tools in order; Settings and A/अ language chooser;
EN/HI trust pills at 200%; EN/HI Compress first-frame action bounds (48 dp,
reported in pixels) and a real target-size result; page-delete Undo plus rotation;
encrypted backup creation and a nonzero-size receipt (no restore); EN/HI Vault
copy; and cold start with a verified item in Trash. Each check appends a PASS/FAIL
row to `summary.md` and retains named screenshots/XML (`F1_*` ... `F9_*`).
Secure backup screenshots may be black: their XML is the evidence.

These journeys accept only `com.scanwhirl.app.test`, `GITHUB_ACTIONS=true`, and
one `emulator-*` device with `ro.kernel.qemu=1`. They never use the physical phone.
Only self-generated fictional documents are imported. No app source or app data
is injected. A missing accessible control is a failed check, not a coordinate
fallback or a reason to modify production source.

Harness-only negative tests (no adb):
`python -m unittest discover -s tools/e2e -p test_flow_driver.py -v`.
The runner snapshots the selected lane's harness exactly; it no longer substitutes
workflow/scripts from another checkout.

The job fails on any FAIL row: a crash, an ANR, a screen that never appears, a
blank screenshot, a missing language, or the app process dying.

Emulator noise is handled explicitly and is never treated as evidence: the
script sets `hide_error_dialogs` and taps "Wait" on any system "… isn't
responding" dialog (usually Pixel Launcher on a cold, slow runner) because that
dialog becomes the focused window and would otherwise hide the app from every
uiautomator dump. The number dismissed is reported in `summary.md` as
`system dialogs`, so a noisy runner stays visible. The app's own crashes and
ANRs are still fatal - they come from logcat, not from the dialog.

## Design decisions

- **No fixed coordinates.** Every tap comes from a fresh `uiautomator dump`,
  matched on the view ids the app sets (`swNavLibrary`, `swNavTools`,
  `swNavVault`, `swNavMe`, `swNavScan`, `swToolsSearch`, `swVaultStatus`,
  `swMeList`, `swCreateScan`, `swHomeSearch`). Ids are locale independent, so the
  same walk works in English and Hindi, and a moved button cannot silently make
  the test tap empty space.
- **Language** uses Android's per-app locale:
  `adb shell cmd locale set-app-locales com.scanwhirl.app.test --locales hi`.
  That is the documented path for this app (`util/AppLanguage.kt` reads
  `AppCompatDelegate.getApplicationLocales()` first, and its comment names this
  exact command). The script verifies the locale took effect *and* that the
  Hindi nav label is actually drawn.
- **Font scale** uses `settings put system font_scale 2.0` plus a force-stop, so
  the app restarts with the new density instead of being reconfigured live.
- **API images**: both `system-images;android-35;google_apis;x86_64` and
  `...;android-36;google_apis;x86_64` exist in Google's SDK repository, so both
  legs run. A missing image fails the leg loudly instead of being skipped.
- **No secrets**: the debug/test build needs no keystore, no Play key and no
  network. `-PciUnsignedRelease` is not used here; the smoke installs the debug
  variant only.

## Not covered (by design)

- Play Billing, the ML Kit OCR model download and the document scanner need the
  Play Store, which the `google_apis` image does not ship. Those flows stay on
   outside this emulator checklist; no worker physical-phone pass is authorized.
- The Vault is only opened as a screen; PIN entry and decryption are Robolectric
  and phone-session territory.
- Screenshots prove layout, not correctness of content. Read the artifact when
  a run looks doubtful.

## Screen atlas (separate workflow)

Push any snapshot to `ci/atlas-<name>` (e.g. `ci/atlas-baseline`) or run the
**atlas** workflow with a `workflow_dispatch` ref. Four legs run in parallel:
API 35 light, API 35 dark (`cmd uimode night yes`), API 35 at `font_scale=2.0`,
and API 24 on the small `Nexus 4` profile. Each uploads `atlas-<leg>` with
`index.html` (thumbnail grid + flags), `report.json`, numbered PNG/XML pairs and
a logcat. The walk reuses the same emulator-only guards and ids; it generates
two fictional PNGs and one PDF at run time, stops at the Vault passcode setup,
and never enters a secret. Flags are findings for review, not job failures; a
black FLAG_SECURE screenshot is marked secure rather than failed.
