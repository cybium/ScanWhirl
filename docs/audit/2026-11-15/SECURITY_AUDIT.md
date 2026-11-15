# ScanWhirl security & privacy audit — 2026-11-15

Lane L2 (security). Scope: the merged `integration` tree at the time of the audit (after the L5
merge): manifest, backup surface, exported components, plaintext lifetime, secrets in the UI and
clipboard, crypto parameters, and debug-only paths. Same rules as the lane brief: fixes in my
files or small isolated changes, everything else assigned to its owner with the exact file.

Method: static review of every source file plus the **merged manifest** (read from the test APK by
`ManifestPrivacyTest`), inspection of every dependency AAR's `AndroidManifest.xml`, and the gate
(`GATE PASS L2 tests=1341`) for the fixes and their tests.

## Findings

| # | Sev | Area | Finding (evidence) | Status | Test |
|---|---|---|---|---|---|
| H-1 | **High** | Network | `INTERNET` + `ACCESS_NETWORK_STATE` were declared, and the code-scanner dependency re-adds them through `transport-runtime`, `transport-backend-cct` and `okhttp-android` (all declare `INTERNET`). The app never opens a socket, yet the store listing and the manifest said it could. | **Fixed**: deleted from the manifest and stripped from the merged manifest with `tools:node="remove"` (`AndroidManifest.xml:5-13`) | `ManifestPrivacyTest.the merged manifest requests no network permission` |
| H-2 | **High** | Screenshots | No `FLAG_SECURE` anywhere: the recovery code, passcodes and private file names were screenshot-able, recordable, and visible on the recents card. | **Fixed for the Vault** (`VaultActivity.kt:143` → `SecureView.apply(window)`). **Open for L3/L1** (below) | `VaultActivityTest.the vault window blocks screenshots and blanks the recents card` |
| H-3 | **High** | Plaintext lifetime | Decrypted Vault files (`cacheDir/vault_open/*`), viewer temp PDFs (`cacheDir/documents/*`), PDF/A copies (`cacheDir/pdfa/*`), decrypted imports (`cacheDir/decrypted-*.pdf`) and OCR `.txt` copies survived a process kill; only `lock()` wiped the Vault cache, and only while the app was running. | **Fixed**: `PrivacySweep` runs in `ScanWhirlApp.onCreate` (`ScanWhirlApp.kt:43`) before any screen exists | `PrivacySweepTest` (5 cases: dirs, share copies, library/vault/exports untouched, idempotent) |
| M-1 | Medium | Credentials | The legacy 4-digit PIN was stored as an unsalted bare SHA-256 (`Prefs.kt:121` before the fix): 10,000 guesses from a prefs backup. | **Fixed**: `VaultPinHash` (PBKDF2-HMAC-SHA256, 120k iterations, 16-byte salt, constant-time compare) and an in-place upgrade of old values on the first correct PIN (`Prefs.kt:124-142`) | `VaultPinHashTest` (4 cases incl. legacy upgrade and malformed values) |
| M-2 | Medium | Clipboard | The recovery code (a master key) was copied as a plain clip with no sensitivity flag and no expiry (`VaultActivity.kt:1510` before). | **Fixed**: `ClipboardPrivacy.copy` (Android 13+ `EXTRA_IS_SENSITIVE`, clears after 60 s, only if unchanged) for the recovery code and for Wi-Fi passwords from the QR tool (`QrActivity.kt:492`) | `ClipboardPrivacyTest` (4 cases) |
| M-3 | Medium | Backup | `allowBackup` was already `false` (`AndroidManifest.xml:26`) — no cloud backup, no device-to-device transfer. No rules file was needed; the decision is recorded below. | Verified, no change | `ManifestPrivacyTest.cloud backup and device transfer are off` |
| L-1 | Low | Permissions | `androidx.biometric:1.1.0` merges the deprecated normal permission `USE_FINGERPRINT` in addition to our `USE_BIOMETRIC`. It grants no capability; removing it would change library behaviour on API 24–27. | Accepted, documented | `ManifestPrivacyTest` (allow-list) |
| L-2 | Low | Crypto (L3) | `PdfAes.normalizePassword` returns a byte array that is not zeroed after use (`PdfAes.kt:186`). No leak found; hardening only. | Listed for L3 | — |
| L-3 | Low | Storage | `WRITE_EXTERNAL_STORAGE` (maxSdk 28) remains for the legacy "save to Documents" path below Android 10. Normal permission, version-capped. | Accepted | — |
| L-4 | Low | Components | Only the launcher (`ShellActivity`) is exported; the FileProvider and every other screen are `exported="false"`; no PendingIntents exist in the app. | Verified | `ManifestPrivacyTest` (exported activities and providers) |
| L-5 | Low | Secrets in memory | Vault passcodes and PDF passwords are Kotlin `String`s (EditText text) and cannot be wiped; `PBEKeySpec` is cleared on both wrap paths (`VaultCrypto.kt:87-93`) and in `VaultPinHash`. | Accepted, documented | — |

## 1. Manifest and dependency contributions

Merged-manifest check = `ManifestPrivacyTest` reading `PackageManager` (test APK, so every library
manifest is included). After the fix the merged permissions are exactly:
`USE_BIOMETRIC`, `USE_FINGERPRINT` (from androidx.biometric), `WRITE_EXTERNAL_STORAGE` (maxSdk 28)
and the test build's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

Per-dependency survey (AAR `AndroidManifest.xml`):

| Dependency | Contributes |
|---|---|
| `play-services-mlkit-text-recognition` (+ `-common`, `-devanagari`), `com.google.mlkit:text-recognition-*` | no permission; one **non-exported** `MlKitComponentDiscoveryService` |
| `play-services-code-scanner` | no permission; one **non-exported** `GmsBarcodeScanningDelegateActivity` (invisible delegate) |
| `transport-runtime` 2.2.6, `transport-backend-cct` 2.3.3 (via the code scanner) | **`INTERNET` + `ACCESS_NETWORK_STATE`** → stripped |
| `okhttp-android` 5.3.2 (via the code scanner / ML Kit) | **`INTERNET`** → stripped |
| `play-services-base` 18.5.0 | one **non-exported** `GoogleApiActivity` |
| `androidx.biometric` 1.1.0 | `USE_BIOMETRIC` + `USE_FINGERPRINT` |
| `material`, `appcompat`, `core`, `recyclerview` | nothing |

**Decision — backup.** `allowBackup="false"` is kept: every artifact in the app's storage is
private content (scans, OCR text, drafts, signatures, the Vault, QR history including Wi-Fi
passwords, the legacy PIN hash in prefs), and the product's promise is "private, offline". Android
cloud backup and device-to-device transfer are therefore both off; the explicit paths users have
are Save/Share exports, the PDF/A copy, and the Vault's own `.swvault` (recovery-code) backup.
While `allowBackup` is false, `dataExtractionRules`/`fullBackupContent` are ignored, so no rules
file is shipped (adding one would only invite someone to flip `allowBackup` on later).

**PendingIntents.** None in the app (grep over `app/src/main`): no mutability flags to set.
**FileProvider.** `res/xml/file_paths.xml` exposes only `files/documents`, `files/exports` and
`cache/documents` — no `root-path`, no `files-path` root. A granted URI reaches exactly one file
tree that the app already shares on purpose.

## 2. Plaintext lifetime

Before the audit only `VaultStore.lock()` wiped `cacheDir/vault_open`, and only from the running
session. Cold start (process killed while the Vault was open, or while a viewer/share held a temp
file) left plaintext behind. `PrivacySweep` now runs first in `ScanWhirlApp.onCreate` and deletes:

- `cache/vault_open/` (decrypted Vault documents / page JPEGs / PDF),
- `cache/vault_verify/` (Vault verification scratch),
- `cache/documents/` (viewer temp PDFs and `documents/share/…` share copies),
- `cache/pdfa/` (PDF/A export copies),
- `cache/exports/`,
- `cache/decrypted-*.pdf`, `cache/preview_*.pdf`, `cache/ocr_*.txt`.

The library (`files/documents`), the Vault (`files/vault2`) and user exports (`files/exports`) are
never touched — the sweep test proves it, including the `.swvault` and manifest files. Vault OCR
text and thumbnails are never written in the clear: adding a document encrypts every file of the
directory and removes the library copy, and the OCR indexer only sees library documents.

## 3. Screenshots (FLAG_SECURE)

`util/SecureView.apply(window)` sets `FLAG_SECURE` (black screenshots, blank recents card, no
virtual-display capture). Applied to `VaultActivity` (recovery code, passcodes, file list).

**Needs other lanes** (one line each, `SecureView.apply(window)` in `onCreate`):

| Screen | Owner | Why |
|---|---|---|
| `ui/protect/UnlockSheet.kt` + `tools/LockFlow.kt` | **L3** | shows the PDF password field and the "Use password saved in Vault" flow |
| `ui/protect/ProtectSheet.kt` | **L3** | shows a password twice with a show toggle |
| `ui/DocumentViewerActivity.kt` | **L1** | hosts the Protect/Unlock sheets and the "locked" badge |
| `ui/PdfViewerActivity.kt` | **L1** | hosts the unlock sheet |
| `ui/ImportSheet.kt` | **L1** | hosts `ImportUnlockSheet` for protected PDFs |

## 4. Clipboard

`ClipboardPrivacy.copy(context, label, text, sensitive)`:

- Android 13+: `ClipDescription.EXTRA_IS_SENSITIVE = true`, so the system hides the content in the
  clipboard preview and does not offer it in suggestions.
- Clears the clip after 60 s **only if it still holds the same text**, so a user's later copy is
  never destroyed.
- Used by the recovery code (`VaultActivity`) and QR Wi-Fi passwords (`QrActivity`).

## 5. Crypto review (read-only where it is another lane's file)

**Vault (`store/vault/**`, mine)** — good:

- AES-256-GCM per 64 KiB chunk, **fresh random 96-bit IV per chunk** from `SecureRandom`
  (`VaultCrypto.kt:157`), 128-bit tag checked on read; a changed byte raises `VaultAuthException`
  and no partial plaintext is published.
- PBKDF2-HMAC-SHA256, **310,000 iterations**, 16-byte random salt (`VaultCrypto.kt:47,62`); the
  wrapped-key blob carries its own iteration count, bounded to 1..10,000,000 on read, so a hostile
  file cannot force a 2^31 loop.
- The vault key is 32 random bytes; wrapped by the passcode, by the recovery code (24 characters,
  120 bits) and by the auth-bound Keystore key. `lock()` overwrites the key and deletes decrypted
  copies; `VaultSession` expires it after the 2-minute background grace.
- Keystore: `AES/GCM/NoPadding`, per-use user authentication, `setInvalidatedByBiometricEnrollment`,
  the IV comes from the cipher, not from a counter. No nonce reuse path.

**PDF AES (`util/PdfAes.kt`, L3)** — the R6 implementation reviewed as read-only: `SecureRandom`
for salts/key/file ID, random per-stream 16-byte IV, `MessageDigest.isEqual` for all comparisons,
NFKC + UTF-8 + 127-byte cap, `/Perms` randomised beyond the permission bits. No issue found.
Hardening only: zero the `normalizePassword` byte array after `create`/`key` use (L-2).

**Legacy PIN (mine, fixed)** — was an unsalted SHA-256 of a 4-digit PIN; now PBKDF2 with a fresh
salt per write, constant-time comparison, and old values are upgraded on the first successful
verification (M-1).

## 6. Debug-only paths

| Path | Production behaviour | Verdict |
|---|---|---|
| `ProGate.resolveProState` (`ProGate.kt:24-56`) | production ignores the review override; only `purchased` counts; the override integer is read but never used when `BuildConfig.TEST_BUILD` is false | safe |
| `MainActivity.kt:1052` ("Preview Free/Pro UI") | inside `if (BuildConfig.TEST_BUILD)`; not compiled into a release APK | safe |
| `DownloadSaver.armDeviceFault` (`DownloadSaver.kt:101`) | returns immediately unless `BuildConfig.TEST_BUILD` | safe |
| Fault seams `Faults`, `OpFaults`, `SaveFaults`, `Durable.beforeFileSync` | `internal` (module-scoped), only tests arm them; `OpFaults.at(...)` is a no-op with nothing armed, and they never read external files or intents | safe (no external trigger) |
| `android:debuggable` | not set; release builds are not debuggable | safe |
| R8 (`proguard-rules.pro`) | keeps ML Kit/Play services; no rule enables a test path | safe |

## 7. What the lead still needs to decide / assign

1. **L3**: `FLAG_SECURE` on the Protect/Unlock sheets' host windows (table in §3) and L-2
   (zero the PDF-password byte array).
2. **L1**: `FLAG_SECURE` on `DocumentViewerActivity`, `PdfViewerActivity`, `ImportSheet` (§3).
   `VaultScreen`'s tab copy is unrelated but still stale.
3. **Lead**: if `allowBackup` is ever turned on, a `dataExtractionRules` + `fullBackupContent`
   pair must exclude `vault2/`, `documents/*/ocr*`, `signatures`, `qr_history.txt` and
   `shared_prefs/scanwhirl_prefs.xml` — I recommend leaving it off.
4. **Device check (unproven on hardware)**: with `INTERNET` stripped, ML Kit's *unbundled* English
   text model can only be updated by Play services in its own process — verify OCR on a fresh
   install on the phone; the bundled Devanagari model and the code scanner are unaffected.

## 8. Tests added

- `util/ManifestPrivacyTest` — merged manifest: no `INTERNET`/`ACCESS_NETWORK_STATE`, only the
  expected normal permissions, `allowBackup=false`, exactly one exported activity (the launcher),
  the FileProvider not exported.
- `util/PrivacySweepTest` — cache dirs/files removed, share copies removed, library/Vault/exports
  untouched, second run is a no-op.
- `util/ClipboardPrivacyTest` — sensitive flag on Android 13+, non-sensitive copies untouched,
  clear-only-if-unchanged, 60 s constant.
- `util/VaultPinHashTest` — salted PBKDF2 storage (PIN absent from prefs, fresh salt), legacy
  SHA-256 verified and upgraded, malformed values rejected, only 4-digit PINs accepted.
- `ui/vault/VaultActivityTest` — the Vault window carries `FLAG_SECURE`.

## 9. Gate

```
tests: total=1341 failures=0 skipped=0
GATE PASS L2 tests=1341 lint=[lint skipped (lead runs it at merge)]
```
