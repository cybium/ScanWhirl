# ScanWhirl launch checklist (Google Play)

> **Snapshot, 15 November 2026.** This checklist was written before 1.2.0 went to closed testing:
> its version numbers ("1.1.1", "currently 28") and "nothing has been uploaded yet" are no longer
> true. The console steps and the launch requirements below are still the useful part — verify any
> statement about the current build or console state.
>
> Current store copy lives in `docs/release/PLAY_LISTING.md`; data safety answers in
> `docs/release/DATA_SAFETY.md`; release notes in `docs/release/RELEASE_NOTES_PLAY.txt`.

State of the repo on 15 November 2026. Owners: **lead** = repository owner and Play Console
account, **L2** = this lane.

## 1. Build and signing (owner: lead)

- [ ] **Version name for the store.** `versionName = "1.1.1-test28"` (`app/build.gradle.kts:21`) is
      the test-build label. Make it `1.1.1` for the production build (e.g. append `-test28` only
      when `-PtestBuild` is set), and set `versionCode` above anything ever uploaded for
      `com.scanwhirl.app` (currently 28; nothing has been uploaded yet, so 28 is valid but a clean
      30 is tidier).
- [ ] **Upload artifact.** Build and sign the AAB with the real upload key, never the CI one:
      `gradlew :app:bundleRelease` with `keystore.properties` present (the file is untracked).
      Output: `app/build/outputs/bundle/release/app-release.aab`. The CI `release-aab` artifact is
      **debug-signed for validation only** and must not be uploaded.
- [ ] **Play App Signing.** Enrol on the first upload (Google holds the app signing key, this
      keystore becomes the upload key). https://support.google.com/googleplay/android-developer/answer/9842756
- [ ] Check the AAB on the Play Console: package `com.scanwhirl.app`, one base module, arm64-v8a
      present (64-bit requirement met).

## 2. Play Console app content (owner: lead)

- [ ] **Privacy policy URL** hosted publicly (GitHub Pages or any static host; the file to publish
      is `PRIVACY_POLICY.md`), pasted into App content. Required even with no data collection.
- [ ] **Data safety**: follow `docs/release/DATA_SAFETY.md`. Recommended: no data collected, no data
      shared. It documents the Google Play services nuance and the conservative fallback answers.
- [ ] **Content rating questionnaire** (IARC). Category: Productivity / utility, no violence, no
      user-generated content shared with others, no ads. https://support.google.com/googleplay/android-developer/answer/9859655
- [ ] **Ads**: "No ads".
- [ ] **App access**: no login, every feature works without credentials. Say so explicitly, or the
      reviewer will ask.
- [ ] **Target audience**: 13+ and not child-directed. Do **not** opt into the Families policy.
- [ ] **News app**: no. **COVID-19**: no. **Financial features**: no. **Health**: no.
- [ ] **Permissions**: none of the sensitive permission declarations (no location, camera, mic,
      contacts, SMS, call log, all-files). The camera is used inside Google Play services; the app
      only asks for biometrics and legacy storage on Android 9 and older. Nothing to declare.
- [ ] **Government apps**: no.

## 3. Store listing (owner: lead, assets need a designer or a phone)

Text: `docs/release/PLAY_LISTING.md` holds the current copy; the two stale lines this checklist
originally called out (a `ScanWhirl-v1.3.0.aab` build name and an older checklist) should be
re-checked there. Text fields:

- [ ] App name (30), short description (80), full description (4000), English.
- [ ] Optional Hindi listing (the app ships Hindi strings; a localized listing lifts installs in IN).
- [ ] App icon 512x512 PNG (32-bit, no alpha).
- [ ] Feature graphic 1024x500 PNG.
- [ ] At least 2 phone screenshots (1080x1920 or larger, portrait). The eight shot ideas are in
      `docs/release/PLAY_LISTING.md`; take them on the `-PdemoBuild` copy so the UI matches.
- [ ] Category: Productivity. Tags: document scanner, scan to pdf, pdf scanner, receipt scanner.
- [ ] Contact email: the address shown in the app's About screen (`cybiumsupport@gmail.com`).
- [ ] Countries: lead's decision (India first is the obvious start given the Hindi support).

## 4. Testing and rollout (owner: lead)

- [ ] **Internal testing**: upload the signed AAB, install on the owner's phone, run the device
      checklist below.
- [ ] **Pre-launch report**: Play Console runs it automatically on the first track build; read the
      crashes, ANRs, accessibility and screenshot tabs.
      https://support.google.com/googleplay/android-developer/answer/9844487
- [ ] **Closed test with 12 testers for 14 continuous days** if the account is a **personal**
      account created after 13 November 2023. This is mandatory before production access and the
      production track stays disabled until it is met. Internal testing does not count.
      https://support.google.com/googleplay/android-developer/answer/14151465
- [ ] **Apply for production access** on the Dashboard (three question sections). Review usually
      takes seven days or less.
- [ ] **Staged rollout**: 20% first, watch Android vitals for 48 hours, then 100%.

## 5. Device checks on the release build (owner: lead, L2 for any fix)

The AAB is minified; the QA session already covers launch, scan import, OCR, PDF export, Vault
unlock and Protect PDF. Still worth a pass on:

- [ ] First run after install: discoverable camera scan, no crash on a device with no Play services
      download yet (English OCR model is fetched by Play services on first use).
- [ ] Hindi OCR on a page with Devanagari text (bundled model, fully offline; airplane mode).
- [ ] Vault: set passcode, fingerprint unlock, add and open a file, lock, recovery code, restore
      from `.swvault`.
- [ ] Protect a PDF with a password and open it in another PDF app.
- [ ] Share a PDF and a page image to another app.
- [ ] Rotate and background the app during a scan and during OCR.
- [ ] Delete a scan, restore it from Trash, empty Trash.

## 6. Compliance facts already satisfied

| Requirement | Status | Evidence |
|---|---|---|
| Target Android 16 (API 36) for new apps from 31 Aug 2026 | **met** (`targetSdk = 36`) | `app/build.gradle.kts:19`; https://support.google.com/googleplay/android-developer/answer/11926878 |
| 64-bit native code | met (arm64-v8a in the AAB) | bundletool ABI output in `docs/audit/2026-11-15/RELEASE_BUILD_HARDENING.md` |
| App bundle (AAB) upload | met | `release-aab` CI artifact |
| Size limits | far below (AAB 23.4 MiB, 7.3 to 8.4 MiB per device) | CI run 43679221596 |
| No ads / no tracking | met | dependency list in `app/build.gradle.kts`, `DATA_SAFETY.md` |
| Backup and data extraction off | met (`allowBackup=false`) | `ManifestPrivacyTest` |
| No sensitive permissions | met | merged manifest test |

## 7. Open items

| # | Item | Owner | Blocks |
|---|---|---|---|
| 1 | Store `versionName` (`1.1.1`, no `-test28`) and confirm the version code | lead | upload |
| 2 | Signed AAB from the owner's keystore; Play App Signing enrolment | lead | upload |
| 3 | Privacy policy at a public URL | lead | App content |
| 4 | Data safety form submission | lead | review |
| 5 | Content rating questionnaire | lead | review |
| 6 | Icon, feature graphic, screenshots | lead / design | listing |
| 7 | Closed test 12 testers x 14 days (if a new personal account) | lead | production access |
| 8 | Pre-launch report read and crashes triaged | lead | rollout |
| 9 | `docs/release/PLAY_LISTING.md`: confirm every copy line against the current build | lead | listing |
| 10 | Any missing R8 keep rule found by the QA session | L2 | release build |

Nothing in this list requires a code change except items 1 (build config) and 10 (only if QA finds
one).

## Sources

- Target API level: https://support.google.com/googleplay/android-developer/answer/11926878
- App testing for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Play App Signing: https://support.google.com/googleplay/android-developer/answer/9842756
- Content rating: https://support.google.com/googleplay/android-developer/answer/9859655
- Pre-launch report: https://support.google.com/googleplay/android-developer/answer/9844487
- App size limits: https://support.google.com/googleplay/android-developer/answer/9859372
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
