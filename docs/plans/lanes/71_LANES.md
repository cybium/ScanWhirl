> **STATUS HISTORICAL (banner added 2026-11-19).** Five-worker v2 mechanics; lint now runs on every full lane gate. Current: `docs/ACTIVE_INDEX.md` and `docs/DECISIONS.md`. Kept as evidence; do not follow as instructions.

# 71 — Parallel lanes (wave 1)

Lead engineer: Opus. Design target: `docs/design/2026-11-14/70_APP_DESIGN.md` + `mockups/*.png`.
Five workers (DeepSeek) each own **one lane** in **one git worktree**. They write code in parallel. Builds and the phone
go through **queues** (`tools/gate.ps1`, `tools/phone.ps1`), because this PC has 7.4 GB of RAM and one Gradle build uses about 2 GB.

## 1. Worktrees and branches

| Lane | Folder | Branch | Owns (only this lane edits these) |
|---|---|---|---|
| L1 Shell & look | `C:\Users\Cybium\Desktop\ScanWhirl-HQ\lanes\L1` | `lane/L1-shell` | `ui/kit/**` (new), `ui/shell/**` (new), `ui/MainActivity.kt`, `res/layout/activity_main.xml`, `res/values/colors.xml`, `themes.xml`, `dimens_v2.xml`, `res/font/**`, `res/drawable/ic_sym_*`, `ui/PdfViewerActivity.kt` (dock), `ui/OcrEditorActivity.kt` (restyle), `ui/SettingsActivity.kt` |
| L2 Vault | `...\ScanWhirl-L2` | `lane/L2-vault` | `store/vault/**`, `ui/vault/**` (new); legacy `tools/VaultFlow.kt` (migration only) |
| L3 Protect PDF | `...\ScanWhirl-L3` | `lane/L3-protect` | `util/PdfAes.kt` (new), `util/PdfEncryptor.kt`, `util/PdfProtection.kt`, `util/PdfVerifier.kt`, `ui/protect/**` (new), `tools/LockFlow.kt` |
| L4 Tools engine | `...\ScanWhirl-L4` | `lane/L4-tools` | `store/OpStaging.kt`, `ScanRepository` tool functions (merge/extract/compress/duplicate/create/split/convert), `util/DocxWriter.kt`, `util/LongImage.kt`, `util/PageRanges.kt` (new), `ui/tools/**` (new), `tools/MergeFlow.kt`, `tools/CompressFlow.kt` |
| L5 Edit & sign | `...\ScanWhirl-L5` | `lane/L5-stamp` | `util/PageStamp.kt` (new), `store/SignatureStore.kt` (new), `ui/stamp/**` (new) |

**Shared files.** Allowed, and kept small:
- `AndroidManifest.xml`: add your own `<activity>` lines only. L2 may add `android.permission.USE_BIOMETRIC`. **Never add INTERNET or any other permission.**
- `ScanRepository.kt` outside your functions: add new functions at the end of the class. Don't reformat or move code.
- `ui/kit/ToolRegistry.kt` (L1 creates it): other lanes change only their own tools' `status` and `launch` lines.

**Strings.** Each lane has its own `res/values/strings_<lane>.xml` + `res/values-hi/strings_<lane>.xml` (for example `strings_vault.xml`). Never edit `strings.xml` except to remove a string you replaced (L1 only).

## 2. Rules

1. Read `70_APP_DESIGN.md` §2, §5 and your lane's section below, plus your mockups.
2. **Order inside a lane.** Phase A = engine + unit tests (no UI dependency). Phase B = UI built with L1's kit, after the lead merges L1's kit into `integration` and you run `git merge integration`.
3. **Tests.**
   - Every behaviour gets a Robolectric test written first (red), then the code (green). Use the existing seams (`Faults`, `DocFixtures`, `PdfInvariants`, `OpFaults`).
   - Never `@Ignore`, delete or loosen a data-safety test.
   - UI-shape tests (tile counts, sheet contents) that encode the **old** UI may be rewritten to the new UI. Each rewrite gets a comment `// v2 UI: <mockup>`.
4. **Gate:** `powershell -File C:\Users\Cybium\Desktop\ScanWhirl-HQ\current\tools\gate.ps1 -Lane L<n>`. It queues, runs the Linux unit suite and lint for your worktree, and prints `GATE PASS` or `GATE FAIL`. Commit only after `GATE PASS`.
5. **Commit often** on your branch (`git add -A; git commit -m "L<n>: ..."`). **Never push, merge into another lane, rebase, reset or force anything.** The lead merges.
6. **Do not touch** `app/build.gradle.kts`, versions, signing, `keystore/`, `local.properties`, or another lane's files.
7. **Phone** (optional, end of a feature): `powershell -File ...\tools\phone.ps1 -Lane L<n> -Run "<your adb commands>"` holds the phone queue. Install with `adb install -r app\build\outputs\apk\debug\app-debug.apk`. If install fails on signatures, `adb uninstall com.scanwhirl.app.test` is allowed (test data, owner approved).
8. **Stuck for more than 30 minutes on one error:** write `LANE_BLOCKED.md` in your worktree root with the exact error and what you tried, commit it, and stop.
9. **Report** at the end of each session to `LANE_REPORT.md` (overwrite it): done / not done / tests added / gate result / screenshots taken.

## 3. Lane tasks

### L1 Shell & look (builds the kit FIRST; others wait on Phase B for it)
- **Phase A1, the kit** (`ui/kit/`). **Commit within the first session.**
  - Tokens: `colors.xml` additions, `dimens_v2.xml`, `res/font/manrope_*.ttf` (OFL, from Google Fonts), `ic_whirl.xml`, and `ic_whirl_gold.xml` drawn from `src/sw.css` `.logo`.
  - Components: `SwSheet`, `PasswordField` (with strength meter), `ToolTile`, `DocCard`, `TrustPill`, as `70` §2 describes.
  - `ToolRegistry`: every tool in `70` §3 with id, group, title, icon, free/pro, status (`READY/BUILD/SOON`) and a launch lambda. SOON opens `SwSheet` "Coming soon" with a one-line description.
- **Phase A2, the shell.**
  - Bottom bar Library · Tools · Scan disc · Vault · Me.
  - Library = `01_home_A`.
  - Tools tab = `03_tools_hub`, with search over titles and keywords.
  - Pro screen = `06_pro`. The buy button opens "Coming soon" until billing.
  - Me = restyled Settings + Trash + About.
- **Phase B.**
  - Reader dock (`07_reader_dock`) and Text screen (`08_text_ocr`).
  - Replace every `SwDialog.card/sheet/input` `AlertDialog` in the app with `SwSheet` equivalents. Keep behaviour identical; tests find them by view id.
- **Tests:** home shows a create row + 6 quick tools + doc cards; every Tools tile opens something (a loop over `ToolRegistry`: no tile is a no-op); Pro screen copy; nav switches tabs.

### L2 Vault (`70` §4.1)
- **Phase A** (engine, `store/vault/`):
  - `VaultCrypto` (AES-256-GCM file encrypt/decrypt with streaming 64 KiB chunks; Keystore key wrap; PBKDF2 recovery wrap).
  - `VaultStore` (add document: encrypt every file into `filesDir/vault2/<id>/`, verify by decrypting, then move the plain original to Trash; open: decrypt to `cacheDir/vault_open/<id>`, wiped on lock; remove from vault).
  - `VaultBackup` (`.swvault` export and import).
  - Passcode lockout timer.
  - **Tests:**
    - round trip;
    - a wrong recovery code fails;
    - a tampered byte fails (GCM tag);
    - no plaintext left in `filesDir/vault2` (scan for JPEG/PDF magic bytes);
    - backup → wipe → restore with the code gives byte-identical files;
    - process death mid-add (`OpFaults`) leaves the original intact.
  - Robolectric has no hardware Keystore, so put the Keystore behind a seam `VaultKeys` with a test implementation. Mark the real-Keystore path DEVICE-UNPROVEN.
- **Phase B** (UI, `04_vault`):
  - Setup flow: passcode ×2 → recovery code shown → type 4 characters back → done.
  - Unlock with `BiometricPrompt` (API 28+) or the passcode.
  - List, Add files (picker from the library), Backup, Restore, legacy PIN Vault migration.

### L3 Protect PDF (`70` §4.2)
- **Phase A:** `util/PdfAes.kt`.
  - Standard Security Handler **V5 / R6, AESV3** (ISO 32000-2 §7.6.4.4, algorithms 2.A, 2.B, 8, 9, 10). Use JCA `Cipher("AES/CBC/NoPadding")`, `AES/ECB/NoPadding`, and SHA-256/384/512.
  - Encrypt each stream during generation: add an optional `encryption` parameter to `PdfGenerator.imagesToPdf` that wraps each stream body (16-byte IV + AES-CBC with PKCS#7 padding) and writes the real `/Length`.
  - Passwords: `java.text.Normalizer` NFKC, UTF-8, truncated to 127 bytes.
  - Keep RC4 **read** (unlock of old files) and remove RC4 **write**.
  - Extend `PdfVerifier` with an AES mode (decrypt image SOI and content `/Im<i> Do`).
  - **Tests:**
    - right and wrong password;
    - an independent decrypt in the test (re-implement algorithm 2.B in the test, as `PdfEncryptorTest` does for RC4);
    - a Unicode password;
    - lock guards from PM-3.1b still green.
  - **Host check:** `wsl -d Ubuntu --cd /tmp -- python3 /mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/current/tools/pdfcheck.py <pdf> <password>` must print `needs_password: True`, `right_password_opens: True`, `wrong_password_opens: False`. Paste the output in `LANE_REPORT.md`.
- **Phase B:** Protect sheet (`05_protect_pdf`), unlock sheet (`09_unlock_sheet`: tries, cooldown, Vault-password button hidden until L2 merges), Change password, Unlock PDF tool for device PDFs (ScanWhirl-made ones).

### L4 Tools engine
- **Phase A, in order:**
  1. `docs/plans/pdf-manager/63_cards/PM-2.1b_PUBLISH.md`.
  2. `PM-2.1c_TOOLS.md`. Follow these cards exactly; their test counts are superseded by "gate passes".
  3. Then:
     - `PageRanges` parser ("1-3, 5, 8-");
     - Split by range (one output per range, via `buildAndPublish`);
     - PDF to images (JPG/PNG, single or ZIP; streamed one page at a time);
     - Long image (bounded canvas, splits instead of squashing, `70` E20 rules: max 20,000 px tall per part);
     - To Word (`DocxWriter`, a minimal valid .docx: `[Content_Types].xml`, `_rels`, `word/document.xml` built from OCR text, one paragraph per OCR line, page breaks; written with `java.util.zip`);
     - Remove metadata;
     - ID card (two captures composed onto one A4 page, front top / back bottom).
  - **Tests:** a happy path + failure (no visible partial output) for each; a .docx opens as a ZIP containing `word/document.xml` with the text.
- **Phase B:** `ui/tools/` setup screens (one shared `ToolSetupActivity` with per-tool option views, matching the kit) and a Result screen (Open, Share, Save, Show in library, Undo).

### L5 Edit & sign
- **Phase A:** `PageStamp` engine. It bakes vector marks into a page bitmap with no full-resolution double copies (decode sampled ≤ 3000 px), writes the new page via `PageReplace` into a **new document copy**, and verifies the result.
  - Mark types: signature (ink path), text, freehand, highlight (multiply blend), watermark (diagonal, opacity), page numbers (position, format), stamp (preset badge + date), redact (solid box burned in).
  - **Redaction** also deletes OCR text for the page and has no `.orig` in the output (I9).
  - `SignatureStore` saves up to 5 signatures as vector paths in app storage.
  - **Tests:**
    - pixels inside a redact box equal the fill (± tolerance);
    - OCR text for a redacted page is gone;
    - the original document is byte-identical;
    - watermark opacity is measurable.
- **Phase B:** Stamp editor screen (page view, a tool row along the bottom, drag/resize/rotate handles, undo/redo, Save as new copy) in the kit style.

## 4. Libraries

Wave 1 needs **no new dependencies**. Everything above uses the Android framework, AndroidX already present, or JCA.
Fonts and icons are assets (OFL / Apache 2.0), not dependencies. Anything else needs the lead's approval in writing in this file.

## 5. Integration (lead only)

- The lead merges lanes into branch `integration` in this order: L1 kit → L4 → L3 → L2 → L5. After each merge the gate must pass.
- Lanes then run `git merge integration` to pick up the kit (Phase B).
- Release builds (version bump, signed APK, device checklist) are cut from `integration` by the lead.
