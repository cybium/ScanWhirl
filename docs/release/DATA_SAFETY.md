# Google Play Data safety form - ScanWhirl 1.2.x

Answers derived from the code and the release artifact, not from intent. Evidence is file:line in
this repo; the manifest claims were checked on the built 1.2.0 AAB/APK with `aapt2 dump badging`.

Verdict: **ScanWhirl's own code collects nothing and shares nothing, and has no account, ads or
analytics. But Google's ML Kit libraries used by the app send diagnostics and a device identifier to
Google, and Play counts that as collection "from your app", so the form answer is YES** (section 2).

Correction (2026-11-16): an earlier version of this file answered "No" on the grounds that the APK has
no INTERNET permission. That was wrong. Play's definition covers data "transmitted off device from your
app by libraries and/or SDKs used in your app, irrespective of whether data is transmitted to you or a
third-party server", and the thin ML Kit features hand their work to Google Play services over binder,
which does have network access. The audit facts below are unchanged; only the conclusion changed.

## 1. What the app does, with evidence

| Claim | Evidence |
|---|---|
| No network permission, so the app process cannot transmit anything | `INTERNET` and `ACCESS_NETWORK_STATE` are stripped from the merged manifest (`app/src/main/AndroidManifest.xml:8-13`); pinned by `ManifestPrivacyTest`; confirmed on the release AAB with `aapt2 dump badging`. |
| Every scan, OCR text, draft, signature, QR history and setting stays in the app's private storage | `filesDir/documents` (library), `filesDir/drafts` (`DraftStore.kt`), `filesDir/vault2` (Vault), `filesDir/trash` (`ScanRepository.kt`), `shared_prefs` (settings). None of it is uploaded. |
| Supported code cannot open a socket | no `HttpURLConnection`, `Socket`, `OkHttp` or `URL(...)` call in `app/src/main/java` (the code-scanner dependency's transport libraries are present as unused classes and their permission is removed). |
| No account, no sign-in | no auth, account or login screen in the app; the only way in is the launcher. |
| No ads, no analytics, no crash reporting | `app/build.gradle.kts` dependencies: androidx, Material, coroutines, ML Kit (document scanner, text recognition, code scanner, Devanagari), exifinterface, biometric. No ad, analytics, attribution or crash SDK. |
| Physics of deletion: private storage is deleted on uninstall | Android removes the app's private directories on uninstall. Files the user exported to `Documents/ScanWhirl` are the user's, and stay until the user deletes them (the app says so when exporting). |
| Trash keeps a deleted scan for 30 days, then deletes it | `ScanRepository.TRASH_RETENTION_MS` (30 days) enforced by `recoverTrash()` on every start; the UI says "You can restore it for 30 days". |
| Vault content is encrypted | AES-256-GCM per 64 KiB chunk, fresh random 96-bit IV per chunk, PBKDF2-HMAC-SHA256 with 310,000 iterations for the passcode (`store/vault/VaultCrypto.kt:42-62,157`). The passcode is never stored. |
| Protected PDFs are encrypted with AES-256 | `util/PdfAes.kt` implements ISO 32000-2 AESV3 (R6, AES-256). |
| No cloud backup, no device transfer | `android:allowBackup="false"` (`AndroidManifest.xml:26`); pinned by `ManifestPrivacyTest`. |
| Secrets do not leak through the shell | `FLAG_SECURE` on the Vault window (`util/SecureView.kt`, `VaultActivity`), recovery codes and Wi-Fi passwords marked sensitive and cleared after 60 s (`util/ClipboardPrivacy.kt`). |
| The app cannot read or write another app's data | the only shared surface is the FileProvider (`res/xml/file_paths.xml`), limited to the app's own `files/documents`, `files/exports` and `cache/documents`, and only for a share the user starts. |

Data types the form asks about that the app touches: **Photos and videos** (camera scan, gallery
import), **Files and docs** (imported PDFs, exported PDFs) and **Contacts** (only as text *inside* a
QR code or scanned page, never read from the Contacts provider). All of it is processed on the
device and never sent anywhere, which Google Play excludes from "collection": "User data accessed by
your app that is only processed locally on the user's device and not sent off device does not need
to be disclosed as collected" ([Play Console Help, Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)).

## 2. Recommended answers

| Form section | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (ML Kit uses HTTPS, per Google) |
| Which account creation methods does your app support? | **My app does not allow users to create an account** |
| Do you provide a way for users to request that their data is deleted? | **No** (the app holds no server-side data; Google's diagnostics are under Google's policy). Locally: per item, Trash (30 days), Empty Trash, uninstall. |
| Data types | **App info and performance > Diagnostics**; **Device or other IDs** |
| For each of the two types | Collected: **Yes**. Shared: **No**. Processed ephemerally: **No**. Required or optional: **Required** (users can't turn it off). Purpose: **Analytics** only. |
| NOT selected | Photos and videos, Files and docs, Contacts, Personal info, Financial info, Location, App activity: the document content is processed only on the phone (Play excludes on-device processing). Billing is handled by Google Play itself, outside the app. |
| Security practices | Leave "Committed to follow the Families policy" off (not child-directed) and "Independent security review" off. |

Why "Yes": collection is transmitting data off the device "from your app", **including by libraries
and SDKs, whether to you or to a third party**. ScanWhirl's own process cannot transmit (no network
permission), but the thin ML Kit features (document scanner, text recognition, code scanner) run in
Google Play services, and Google's ML Kit disclosure lists for all features: device information, app
information, a device identifier (thin features), performance metrics, event types and error codes,
used for diagnostics and usage analytics, encrypted in transit, not shared with third parties.

## 3. Third-party code in the APK

| Component | What it is | Where it runs |
|---|---|---|
| `play-services-mlkit-document-scanner:16.0.0` | Google's document scanner UI (edge detection, page capture) | Play services process; the app gets page images back through an activity result |
| `play-services-mlkit-text-recognition:18.0.1` | On-device text recognition, English/Latin | Play services process (model downloaded by Play services) |
| `com.google.mlkit:text-recognition-devanagari:16.0.1` | Hindi/Devanagari model | **Inside the app**, bundled, fully offline |
| `play-services-code-scanner:16.1.0` | Google's QR/barcode scanner UI | Play services process |
| `androidx.biometric:1.1.0` | Fingerprint/face prompt for the Vault | app process, local Keystore |
| `billing-ktx:8.3.0` (since 1.2.0) | Google Play Billing for the one-time Pro purchase (`util/ProBilling.kt`, permission `com.android.vending.BILLING`) | Play Store app; payment details never reach ScanWhirl. Purchase records are Google Play's, so no extra data type is declared for it |

Nothing in the dependency tree collects analytics or advertising identifiers. The `transport-runtime`
and `okhttp` classes that arrive with the code scanner are unused, and their `INTERNET` permission is
removed from the merged manifest.

## 4. What Google says about the Play services components

The two policies that matter, quoted:

1. **ML Kit terms** ([developers.google.com/ml-kit/terms](https://developers.google.com/ml-kit/terms)):
   "processing of the input data (e.g. images, video, text) fully happens on-device, and ML Kit does
   not send that data and the resultant outputs to Google servers." The same page says the APIs
   "may contact Google servers from time to time in order to receive things like bug fixes, updated
   models and hardware accelerator compatibility information", and that "the ML Kit APIs also send
   metrics about the performance and utilization of the APIs in your app to Google", with the
   developer being "responsible for informing users of your app about Google's processing of ML Kit
   metrics data".
2. **ML Kit's Data safety guidance** ([developers.google.com/ml-kit/android-data-disclosure](https://developers.google.com/ml-kit/android-data-disclosure)):
   the SDKs list device information, application information, device or other identifiers
   (including, for "thin" Play services features, a device identifier), performance metrics, API
   configuration, event types and error codes, used for diagnostics and usage analytics, encrypted
   in transit with HTTPS and not shared with third parties. For barcode scanning it adds extra data
   **only if auto zoom is enabled** (session id, zoom changes, predicted box coordinates);
   ScanWhirl's `QrScanner.kt:66` does enable auto zoom.
3. **Data safety rules** ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/10787469)):
   data "transmitted off device by libraries and/or SDKs used in your app" must be declared, and
   on-device processing is out of scope; "You may want to refer to your SDK providers' published
   Data safety information".

How to word this honestly in the two places users look:

- **Data safety form**: Diagnostics + Device or other IDs, collected (by Google's ML Kit), not shared,
  analytics, required, encrypted in transit (section 2). Your documents and their text are not
  collected: they are processed on the phone.
- **Privacy policy** (must say it, and does): scanning, OCR and QR reading are provided by Google
  Play services; the input stays on the phone; Google Play services may download or update models
  and may send Google diagnostic data under Google's privacy policy; the Hindi model is inside the
  app and never contacts anything. Link Google's privacy policy and the ML Kit terms.
- **Store listing**: keep "No data leaves your phone unless you share it" and the note that Google
  Play services provides the scanner and text recognition. Do not claim "no network at all on the
  device"; claim what is true of the app.

### The declaration in one table (same as section 2)

| Field | Value |
|---|---|
| Collected | **App info and performance -> Diagnostics**; **Device or other IDs** |
| Shared | No |
| Encrypted in transit | Yes (HTTPS, per Google) |
| Purpose | Analytics (Google measures API performance and usage) |
| Optional or required | Required (automatic, no user control) |
| Deletion | No server-side data to delete; the app has no account and stores everything locally |

The privacy policy paragraph describes the same thing: what Google Play services / ML Kit sends.

## 5. What would change these answers

Adding any of these requires a new form: an analytics or crash SDK, a cloud sync or account, an ad
SDK, a remote config service, or turning `allowBackup` on. The current answers are pinned by
`ManifestPrivacyTest` (permissions, `allowBackup`) and by the dependency list in
`app/build.gradle.kts`.

## Sources

- Data safety form, definitions and on-device exemption: https://support.google.com/googleplay/android-developer/answer/10787469
- ML Kit terms and privacy: https://developers.google.com/ml-kit/terms
- ML Kit data disclosure (collected data per SDK): https://developers.google.com/ml-kit/android-data-disclosure
- Google privacy policy (Play services): https://policies.google.com/privacy
- Google Play target API requirement: https://support.google.com/googleplay/android-developer/answer/11926878
- Testing requirements for new personal developer accounts: https://support.google.com/googleplay/android-developer/answer/14151465
