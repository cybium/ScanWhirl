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

```
tests: total=1341 failures=0 skipped=0
GATE PASS L2 tests=1341 lint=[lint skipped (lead runs it at merge)]
```

## Commits

- `2ad6e49` OCR quality + Hindi · `28200c7` PDF/A-2b · `7794a40` Soon-tile follow-ups
- (this pass) security audit: manifest permission strip, FLAG_SECURE, cold-start sweep, PBKDF2
  legacy PIN, sensitive/auto-clearing clipboard, audit document, tests
- (this report) L2: report
