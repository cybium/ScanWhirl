# LANE_REPORT_L2 - Vault, QR, OCR/Hindi, PDF/A, security audit

Session 2026-11-15 (seventh pass). Branch `lane/L2-vault`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L2`.
This pass: a privacy & security audit of the merged app, with fixes and tests.

## Security audit - `docs/audit/2026-11-15/SECURITY_AUDIT.md`

Findings table (severity, evidence file:line, fix, test) plus the dependency-manifest survey,
backup decision, plaintext lifetime, clipboard rules, a read-only crypto review and the
debug-path review. Fixed in this lane:

- **H-1 network permission**: `INTERNET`/`ACCESS_NETWORK_STATE` removed and, more importantly,
  **stripped from the merged manifest** (`tools:node="remove"`): the code scanner pulls in Google's
  telemetry transport and okhttp, which declare both. The shipped manifest now has no network
  permission (`ManifestPrivacyTest` reads the merged manifest).
- **H-2 screenshots**: `SecureView.apply(window)` / `FLAG_SECURE` on the Vault screen (recovery
  code, passcodes, file names, recents card). Protect/Unlock hosts listed for L1/L3.
- **H-3 plaintext lifetime**: `PrivacySweep` at cold start deletes `cache/vault_open`,
  `cache/documents` (viewer temp + share copies), `cache/pdfa`, `cache/exports`, `decrypted-*`,
  `preview_*`, `ocr_*`; the library, the Vault and user exports are never touched.
- **M-1 legacy PIN**: bare unsalted SHA-256 -> PBKDF2-HMAC-SHA256 (120k, 16-byte salt,
  constant-time compare) with in-place upgrade of old values (`VaultPinHash`).
- **M-2 clipboard**: recovery codes and QR Wi-Fi passwords are marked `EXTRA_IS_SENSITIVE`
  (Android 13+) and cleared after 60 s if unchanged (`ClipboardPrivacy`).
- Verified unchanged: `allowBackup=false` (no cloud backup / D2D), one exported component (the
  launcher), FileProvider paths without root, no PendingIntents, no secrets in logs, production
  Pro/debug paths inert.

Tests: `ManifestPrivacyTest`, `PrivacySweepTest`, `ClipboardPrivacyTest`, `VaultPinHashTest`,
`VaultActivityTest.the vault window blocks screenshots...`.

## Production build hardening (R8) - `docs/audit/2026-11-15/RELEASE_BUILD_HARDENING.md`

Every phone build until now was `-PtestBuild` (no R8). This pass builds the real minified variant
in CI without the owner's keystore and fixes its keep rules.

- `-PciUnsignedRelease` (new Gradle property): the `release` build type signs with the debug key
  only when it is set, so CI can produce an installable APK. Without the property nothing changes.
- New `release` job in `.github/workflows/gate.yml` (the `test` job untouched): builds
  `:app:assembleRelease -PciUnsignedRelease`, uploads the APK + `mapping.txt` + `missing_rules.txt`
  + `configuration.txt` + the build log, and prints `RELEASE PASS/FAIL`. It fails on a build error,
  an R8 error or any unresolved class.
- `proguard-rules.pro`: the blanket `-keep class com.google.mlkit.**` / `com.google.android.gms.**`
  are replaced by the rules the code actually needs - Firebase component registrars (ML Kit is
  instantiated by name from manifest metadata), the Dynamite module descriptors, the bundled
  Devanagari JNI stack (visionkit pipeline + 1013 protobuf classes), `QrKind` constants (persisted
  by name in QR history), the XML-inflated custom-view constructors, and line numbers for
  readable crash reports.

| | blanket keeps | new rules | test build |
|---|---|---|---|
| APK | 45.2 MiB | **44.9 MiB** | 49.1 MiB |
| dex | 4.74 MB, 7160 classes | **3.73 MB, 5606 classes** | 15.65 MB, 14635 classes |
| R8 warnings / missing classes / errors | 0 / 0 / 0 | **0 / 0 / 0** | - |

Installable artifact for the smoke test:
`C:\Users\Cybium\Desktop\ScanWhirl-L2\app\build\outputs\apk\release\app-release.apk`
(`com.scanwhirl.app`, debug-signed, unsigned-for-distribution; test build id is `.test` so both
coexist). The 41 MB of `libmlkit_google_ocr_pipeline.so` (4 ABIs) dominate the universal APK; a
Play AAB splits it per ABI. Device checks outstanding: English + Hindi OCR, document scanner, code
scanner, Vault unlock, Protect PDF.

## Launch readiness - `docs/release/`, `PRIVACY_POLICY.md`

- **AAB**: the `release` job now builds `:app:bundleRelease` too and reports the real per-device
  download size with `bundletool` (`get-size total --dimensions=ABI`). AAB 23.4 MiB; a phone
  downloads 7.3 to 8.4 MiB (arm64 8.1 to 8.2 MiB); the universal smoke-test APK stays 44.9 MiB.
- **`docs/release/DATA_SAFETY.md`**: the Play form answers derived from the code and the built
  artifact (no collection, no sharing, no account/ads/analytics, encryption of Vault and protected
  PDFs, deletion and the 30-day Trash, backup off), plus a sourced analysis of the Google Play
  services components (document scanner, code scanner, ML Kit model delivery): what Google's ML Kit
  terms and data disclosure say, how to word it honestly, and the conservative fallback answers if
  Play review asks.
- **`PRIVACY_POLICY.md`**: rewritten to match the shipped app (no internet permission, what stays on
  the phone, Vault and PDF encryption, blocked screenshots, clipboard clearing, Play services
  components, exported copies surviving uninstall, 30-day Trash), plain English, dated changelog.
- **`docs/release/LAUNCH_CHECKLIST.md`**: everything left before a Play release with owners, the
  current policy citations (target API 36 from 31 Aug 2026, 12 testers x 14 days for new personal
  accounts, Play App Signing, content rating, pre-launch report) and the compliance facts already
  met (targetSdk 36, 64-bit, AAB, size).
- **Trash now enforces the 30-day window the UI promises**: `recoverTrash()` expires older entries
  on startup (never an interrupted-Replace entry), `TRASH_RETENTION_MS`, strings in English and
  Hindi, two new `TrashTest` cases. Without it the policy and the form would have claimed a
  retention the code did not have.

## OCR performance, Full Backup 2.0, text languages, Hindi on photos - this pass

- **OCR indexing** (L4's L2-1..L2-3): batched store commits (16 pages or 2 s, geometry then text),
  a pending-queue file instead of a directory scan, and an O(pages) report count. 500 pages on L4's
  harness: **11 007 ms -> 1 211 ms**. Details in `docs/audit/2026-11-15/PERF_OCR.md`.
- **Vault buttons**: the recovery/backup buttons stay on one line with their 24dp icons at narrow
  widths and font scale 1.3 (single line, auto-size 10-14sp, tighter padding), with a layout test.
- **Full Backup 2.0** (`docs/release/FULL_BACKUP.md`): one streamed AES-256-GCM file with the
  library, the Vault, signatures/stamps, drafts and settings, opened by a backup password (10+,
  PBKDF2 310k) or the Vault recovery code (proven before writing). Me -> Backup & restore with SAF
  save, share sheet (Drive), restore by merge, last-backup line and reminder. 12 tests. Vault
  entries now merge into an existing Vault: the backup carries the old Vault key, the current Vault
  is unlocked on demand, and every entry is decrypted with the old key and re-encrypted with the
  new one (verified, atomic publish); declining the unlock restores everything else and says so.
- **Text languages**: the separate "Hindi text" tool tile is gone; the language lives in the Text
  screen chip and the Settings row (English / English + Hindi / Hindi). The Chinese/Japanese/Korean
  recognizers were cancelled by the owner and were never added.
- **Hindi on photos**: the merge now detects Latin-model noise (low confidence, punctuation,
  digit-in-word, the "fafI" case, single short tokens on a Devanagari page) and keeps the
  Devanagari reading of the same box; clean English lines and genuinely different lines are
  unchanged. 6 new tests.

## Backup & restore — phone QA script (SAF, share, restore)

Full detail in `docs/release/FULL_BACKUP.md` (§ Device QA script). Short version, expected result in
brackets:

1. Me -> Backup & restore: "No backup yet", both buttons, reminder, no-internet note. [visible]
2. Back up now -> recovery code -> exact code -> Continue. [encrypting…, then "Backup ready" with size]
3. Save to device -> pick a folder. ["Backup saved", "Last backup: today"; `ScanWhirl-<date>.swbackup`]
4. Back up now -> password: 9 chars refused, 12 accepted. [error text / strength meter moves]
5. Share. [chooser lists Drive when installed; the file is attached there]
6. Restore, wrong password. ["Wrong password or recovery code. Nothing was changed."]
7. Restore, correct secret on a phone that already has a Vault. [unlock prompt with the reason;
   entries re-encrypted, duplicates skipped, same-name gets "(restored)"]
8. Cancel the unlock during a restore. [documents restored, dialog says the Vault files were not]
9. Airplane mode for steps 2-8. [everything works; no network permission]

## Accessibility (L2 screens) and the cloud test crash - this pass

- **A11y findings for Vault / Protect / Unlock fixed** (details in `docs/audit/2026-11-15/ACCESSIBILITY.md`,
  "Fixed by L2"): 48dp touch targets, readable label/hint contrast, buttons that wrap instead of
  clipping at font scale 2.0, accessibility pane titles on both sheets, decorative glyphs hidden,
  and the Vault action buttons keep a single line at every font scale (24dp icon above the label,
  text size stepping with the system scale). The corresponding `a11y_allowlist.txt` lines are
  **deleted**, so `AccessibilityAuditTest` now enforces them.
- **Full gates no longer abort at random** with `F JniConstants: Class not found:
  java/nio/DoubleBuffer` (Gradle Test Executor exit 134, tests=0). Cause: Robolectric's native
  runtime was loaded by whichever test class first touched native graphics; on lane/L2's fork
  composition that could be a background decode thread, and the load aborted the JVM. Fix:
  `app/src/test/resources/robolectric.properties` sets `graphicsMode=NATIVE` for the whole suite,
  so the sandbox loads the runtime on the test thread before any test code runs. Evidence: three
  aborted runs before the change, then two consecutive green full cloud gates (43700233080,
  43700703356, 1503 tests each).
- One unrelated flake was seen once between the two green runs (`ScanReviewBackTest` did not delete
  its staging folder); it passes in isolation and in both green runs, so it is recorded here rather
  than changed.

## Save as PDF/A (PDF/A-2b, ISO 19005-2 level B) - previous pass

- `util/PdfA.kt` (XMP with pdfaid part 2 / conformance B, matching Info dates/title/producer,
  `GTS_PDFA1` OutputIntent, document ID) and an optional `pdfa` mode in
  `PdfGenerator.imagesToPdf` (PDF 1.7, ICC/XMP/Info objects, trailer `/ID`, no encryption; the
  plain writer stays byte-identical - pinned by a test).
- Embedded sRGB ICC profile in `res/raw/srgb.icc` (zlib licence, `docs/licenses/icc-sRGB-zlib.txt`).
- UI: Pro gate -> chooser -> setup sheet -> `PdfAExport` -> shared result screen; protected
  documents refused with a clear line.
- **Host check**: veraPDF 1.30.4, `PASS ... 2b isCompliant="true"` on 1 page, 10 pages, Latin
  text and Devanagari text; invisible OCR text needs no embedded font.
- Tests: `PdfATest`, `PdfAGeneratorTest`, `PdfAExportTest`, `PdfASamplesTest` (EVAL_ON-gated).

## Earlier passes (still current)

- **OCR quality + Hindi**: `OcrLayout` (recursive XY-cut reading order: columns, spreads, rotated
  pages), `OcrMerge` (per-line Latin/Devanagari merge, U+FFFD-free text), the language setting
  (Text screen chip + Settings row) with re-recognition, the "Hindi text" tool, and the
  `/ActualText` searchable text layer (verified with PyMuPDF).
- **Vault**: AES-256-GCM files, Keystore + recovery wraps, process-wide session with the 2-minute
  relock, `.swvault` backup v2, fingerprint unlock via `androidx.biometric` with a CryptoObject,
  upgrade-safe passcode unlock with tries-left errors, documents counted not files,
  `VaultPasswords` for L3.
- **QR code reader**: Google code scanner behind a seam, per-type actions, last-20 history.

## DEVICE-UNPROVEN

- Opening a PDF/A archive in Android viewers (veraPDF validates the structure).
- With `INTERNET` stripped, ML Kit's *unbundled* English model can only be updated by Play services
  in its own process: verify OCR on a fresh install on the phone. The bundled Devanagari model and
  the code scanner are unaffected.
- The biometric prompt, the Vault sheet layout above the IME and the QR scanner (all earlier).

## Gate

Full cloud suite on the merged tree (`integration` merged, `app/build.gradle.kts` heap fix from
`1a924ae`/`f42bc1d` included):

```
cloud: run 43673186804 finished in 5 min
tests: total=1447 failures=1 skipped=0
GATE FAIL L2 tests=1447 failures=1 (only TextFitTest, L1's known layout issue)
GATE FAIL L2 tests=1377 failures=1 lint=[skipped]
   FAIL TextFitTest > ocr editor text fits (w320dp, scale 1.3, swChipLabel "To Word · PRO")
```

The only failure is the known L1-owned integration layout issue (`To Word · PRO` chip at font
scale 1.3), which the lead allowed pending L1's fix. No L1 file was edited here; the branch diff
against `integration` is exactly the security files and their tests. All five security tests pass
and every other test in the suite is green.

The earlier `tests=0`/`OutOfMemoryError` failures were the hardcoded 1 GB / forkEvery 120 test
heap, now configurable in `app/build.gradle.kts` (lead's fix).

## Commits

- `2ad6e49` OCR quality + Hindi · `28200c7` PDF/A-2b · `7794a40` Soon-tile follow-ups
- `dd2a62b` security audit: manifest permission strip, FLAG_SECURE, cold-start sweep, PBKDF2
  legacy PIN, sensitive/auto-clearing clipboard, audit document, tests
- `6b22dcd` audit: cloud-gate OOM finding · merge of `integration` (build/heap fix, lint fix)
- `59cf02e` audit + report: final gate numbers, share-copy lifetime, gate credential review
- `eb523a6` build: `-PciUnsignedRelease` · `fcbb183` ci: release job
- `0f6dba6` r8: targeted keep rules · release-build hardening document
- (this commit) report: production build hardening section
