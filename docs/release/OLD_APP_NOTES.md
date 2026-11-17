# Old app (1.2.x) working notes

Read only when working on the 1.2.x app in this repo. The new app lives in `ScanWhirl-HQ\next`.

## Tools in this repo
`tools/gate.ps1` (serialized local build; workers use only this, never gradlew), `tools/lane-watch.ps1`,
`tools/lane-review.ps1`, `tools/wait-integration.ps1`, `tools/phone.ps1`, `tools/store_frames.py`,
`tools/copy_audit.ps1`. History of decisions: `docs/plans/lanes/73_ORCHESTRATION_LOG.md`.
Worker brief rules: `docs/plans/lanes/72_WORKER_BRIEF.md`. Deferred work: `docs/release/NEXT_RELEASE.md`.
CI re-runs the known flaky classes (ScanEnhanceQualityTest, PageNormalizationTest, ExportHygieneTest)
once when they are the only failures.

## Phone tap paths (Infinix X6711, demo app `com.scanwhirl.app.demo`)
Nav y=2214 (Library x130, Tools x315, Vault x760, Me x948). Doc dock y=2234 (Share 241, Edit 540,
Text 738, More 938). Hide private info = Tools > search "private" > "Redact" > pick doc > "Hide private
info" > Find > Hide selected > Back > Discard. Free/Pro test switch = Me > Settings > scroll > id
`switchTestFree` (screen reloads after toggle). Language = Home chip "A/अ". Clean status bar:
`am broadcast -a com.android.systemui.demo` (enter; clock 1000; battery 100; network wifi hide +
mobile level 4; notifications hidden); exit afterwards.

## Store screenshots
Demo app, fictional docs (`tools/store_samples.py`), Free mode except Pro-only tools, never edit app
content (real OCR, even a misread), Pro screenshot = TOP of the Pro screen, not the price card. Look at
every framed image before reporting.
