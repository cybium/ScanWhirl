# Scanner competitive teardown — 2026-11-13

## Scope

This is a device-based teardown of the scanner apps installed from Google Play on the Infinix X6711 (Android 14, 1080 × 2460). It covers UI, navigation, the actual tool model, free/paid boundaries and localized prices observed on 2026-11-13. It is not a decompilation and it does not assume every hidden feature is absent.

**Region:** India. Prices are shown in ₹ as displayed by Google Play/in-app paywalls. They may be regional, A/B-tested or stale.

## Method

- ADB screenshots and UIAutomator hierarchy.
- Permissions accepted on the reset test phone.
- Camera used where possible, with the phone held over a real page. Hardware flicker lines are ignored.
- Account gates skipped where a skip existed. Adobe was already signed in with Google. TapScanner was continued without an account. CamScanner’s official listing was not available; only a sponsored “OKEN — CamScanner” clone appeared and was not installed. OneDrive was skipped because it requires a Microsoft account. Google Drive’s package had no launchable activity on this device.
- Each app was opened, its onboarding skipped where possible, and its Home/library, main actions, Pro/paywall and at least one document flow inspected. Depth varies by app and is recorded in `APP_NOTES.md`.

## Apps observed

| App | Package | Version | Rank | Deep walk |
|---|---|---|---|---|
| Adobe Scan | `com.adobe.scan.android` | 26.10.29 | 2 | Home, Files, scan sheet, import from photos, crop/edit review, saved card, viewer, Preferences, plan screen |
| Genius Scan | `com.thegrizzlylabs.geniusscan.free` | 7.44.1 | 1 | Onboarding, paywall, plan comparison, import, document detail, OCR gate |
| SwiftScan | `net.doo.snap` | 11.0.1(721) | 3 | Terms, onboarding, VIP paywall, Home, camera, review, save, ad, library, export |
| vFlat Scan | `com.voyagerx.scanner` | 1.16.10 | 4 | Permission explainer, terms, Premium, camera, library |
| TapScanner | `pdf.tap.scanner` | 3.1.24 | 5 | Onboarding, optional account, Home tools, premium entry |
| ACE Scanner | `pdfscanner.scan.pdf.scanner.free` | 1.5.2 | 6 | Launch ad, language, onboarding, tool picker, Home tools |
| Notebloc | `com.notebloc.app` | 6.0.4 | 7 | Home, drawer, ad-removal Pro paywall |
| Lufick Doc Scanner | `com.cv.docscanner` | 6.9.9 | 8 | Tour, Home folders/tools, Premium |
| Tiny Scanner | `com.appxy.tinyscanner` | 8.16.7 | 9 | Marketing onboarding, Home |
| iScanner | `com.bpmobile.iscanner.free` | 6.11.3 | 10 | WebView onboarding, account/terms, subscription/legal funnel |

**Excluded:** Microsoft Lens (retired), Microsoft OneDrive (login wall), Google Drive scanner (no launchable activity on this test device), Apple Notes/Files (iOS), official CamScanner (not available; clone not installed).

## Evidence map

Screenshots are in `screenshots/` and are ignored by Git so the repository stays small. The key ones are named by app and surface:

- Adobe: `01_adobe_filebrowser.png`, `01_adobe_camera4.png`, `01_adobe_import_review.png`, `01_adobe_home_saved.png`, `01_adobe_document.png`, `01_adobe_modify.png`, `01_adobe_preferences.png`, `01_adobe_filename.png`, `01_adobe_premium.png`
- Genius: `02_genius_welcome.png`, `02_genius_editor.png`, `02_genius_ultra_paywall.png`, `02_genius_compare_plans.png`, `02_genius_ocr_gate.png`
- SwiftScan: `03_swiftscan_home.png`, `03_swiftscan_camera.png`, `03_swiftscan_review.png`, `03_swiftscan_paywall.png`, `03_swiftscan_library4.png`, `03_swiftscan_library.png`
- vFlat: `04_vflat_onboarding2.png`, `04_vflat_camera.png`, `04_vflat_home4.png`, `04_vflat_library.png`
- TapScanner: `11_tapscanner_onboarding.png`, `11_tapscanner_account3.png`, `11_tapscanner_premium.png`
- ACE: `09_ace_onboarding.png`, `09_ace_after_tools.png`, `09_ace_home3.png`
- Notebloc: `05_notebloc_onboarding2.png`, `05_notebloc_menu.png`, `05_notebloc_pro.png`
- Lufick: `08_lufick_home.png`, `08_lufick_pro.png`
- Tiny Scanner: `06_tinyscanner_home.png`
- iScanner: `07_iscanner_onboarding.png`, `07_iscanner_onboarding3.png`

## Files

- `RANKING.md` — top 10 and where ScanWhirl sits.
- `PRICING.md` — observed prices and benefits.
- `FEATURE_MATRIX.md` — what each app visibly offers.
- `UI_PATTERNS.md` — UI and navigation patterns with evidence.
- `APP_NOTES.md` — per-app walk notes.
- `SCANWHIRL_STRATEGY.md` — what to borrow, what not to copy, offline Pro packaging and pricing.
- `OPUS_BRIEF.md` — one-page brief for the next implementation session.
- `apps.csv` — machine-readable summary.

## Hard limits

- Prices are localized and can A/B test. Do not treat every number as a permanent list price.
- Some paywalls require accounts, web views or further steps; those are marked “not captured.”
- A missing UI control is not proof that the capability is absent from the binary.
- We did not decompile or inspect competitor source code.
- Advertising and full-screen interstitials were observed in several apps; their presence is not proof that every session shows one.
