# LANE_REPORT — L3 Protect PDF

Session: 2026-11-14. Branch `lane/L3-protect`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L3`.

## Done

### Phase A — engine (ISO 32000-2 Standard Security Handler V5 / R6, AESV3)

- **`util/PdfAes.kt` (new)** — AES-256 password protection for new PDFs.
  - Algorithm **2.A** (user and owner file-key retrieval), **2.B** (hash: ≥ 64 rounds, then until
    `E_last <= round - 32`), **8** (`/U`, `/UE`), **9** (`/O`, `/OE`, with the 48-byte `/U` as the
    owner hash's user data), **10** (`/Perms`, AES-256-ECB).
  - Passwords: Unicode **NFKC → UTF-8, cut at 127 bytes**, so Devanagari/emoji/CJK work.
  - Streams: `16-byte random IV + AES-256-CBC/PKCS#7`; the file key is used directly (V5 has no
    per-object keys).
  - `/Encrypt` dictionary (`/V 5 /R 6 /Length 256 /CFM /AESV3`) and a matching `/ID`.
- **`util/PdfGenerator.kt`** — optional `encryption: PdfAes.Encryption?` parameter: each stream body
  is encrypted while writing, `/Length` is the real (larger) byte count, and the trailer gains
  `/Encrypt` + `/ID`. Existing callers are unchanged (default `null`).
- **`util/PdfVerifier.kt`** — optional `decryption` parameter: content `/Im<i> Do`, image SOI and the
  byte-for-byte page comparison run on decrypted data. The trailer parser now accepts a nested
  `/Encrypt << … >>` dictionary.
- **`util/PdfEncryptor.kt`** — RC4 **write removed**; AES-256 now rewrites an existing generator PDF:
  every object is copied, each stream re-encrypted with a new `/Length`, a fresh xref table and a
  trailer with `/Encrypt` + `/ID`; the result is fsynced, verified with the key and published by
  rename. No plaintext temporary.
- **`util/PdfProtection.kt`** — `isEncodable` now accepts any well-formed Unicode (valid surrogate
  pairs) instead of printable Latin-1. The lock hash and the PDF password still agree.
- RC4 **read** is untouched: old RC4 files keep opening in any viewer and the old unlock code path is
  unchanged (ScanWhirl rebuilds unlocked copies from page images).

### Phase B — UI (mockups 05_protect_pdf, 09_unlock_sheet)

The L1 kit is not in `integration` yet (checked: `integration` is still at `7e039c7`), so the two
sheets are self-contained in `ui/protect` and match the mockups' measurements from `sw.css`
(28 dp sheet, 42×5 grab, 56 dp fields, 54 dp primary button, 4-segment meter).

- **`ui/protect/ProtectSheet.kt`** — title + close, note, document chip, password field with
  show/hide, 4-segment strength meter with the check icon, "type it again" with a match check,
  AES-256 info row, Remember-in-Vault row, **Protect & share** and **Save protected copy**.
  Protect stays disabled until the password is ≥ 6 characters and both fields match.
- **`ui/protect/UnlockSheet.kt`** — gold lock badge, `<name> is protected`, password field, the
  tries-left error line, Open, "or", "Use password saved in Vault" (hidden — L2 API not merged),
  Cancel, and "Remove protection…" behind a second confirmation sheet.
- **`ui/protect/ProtectPolicy.kt`** — pure policy: `ProtectPassword` (min 6, Weak < 10, 4 segments,
  code-point length) and `TryCooldown` (5 wrong tries → 30 s pause, doubling, reset on success).
- **`tools/LockFlow.kt`** — both flows now use the sheets: saved-scan Protect / Unlock / Change
  password, device-PDF lock (imports then protects; the sheet does not invent page count or size)
  and device-PDF unlock. "Protect & share" shares the protected PDF, "Save protected copy" opens the
  existing save screen. Cooldowns are per document for the process.
- Strings only in `values/strings_protect.xml` and `values-hi/strings_protect.xml` (Hindi starts with
  `<!-- review -->`); new colours/ids in `values/colors_protect.xml`, `values/ids_protect.xml`;
  layouts `sheet_protect.xml`, `sheet_unlock.xml`; drawables `bg_protect_*`, `ic_protect_visibility_off`.

### Tests added / updated

- **`PdfAesTest` (new)** — right/wrong user and owner passwords; an **independent** implementation of
  Algorithms 2.B/8/9/10 in the test (salts, `/U`, `/UE`, `/O`, `/OE`, `/Perms`) recomputes everything;
  Unicode password (NFKC ligature, decomposed accent, 127-byte cap); generator-time encryption
  decrypts independently and the verifier accepts it; post-hoc encryption keeps the page JPEG
  byte-identical; the `/Encrypt` value is a real `<< … >>` dictionary.
- **`PdfEncryptorTest`** — rewritten from RC4 to AES-256 (as the lane allows): independent 2.B +
  AES-CBC stream decryption, exact content streams and images, text layer, right/wrong password.
- **`ProtectPolicyTest` (new)** — meter thresholds, 12-char mockup case, code-point counting,
  confirmation match, five-try 30 s pause, doubling, reset.
- **`ProtectSheetTest` (new, v2 UI: 05_protect_pdf)** — fields, disabled state, 3/4 segments at 12
  characters, confirm check, both exits' callbacks, Vault row hidden unless wired.
- **`UnlockSheetTest` (new, v2 UI: 09_unlock_sheet)** — title, tries-left line, right password closes
  and hands over, fifth wrong try pauses Open, Remove protection needs the second confirmation.
- **`LockUiTest`** — rewritten to the v2 sheet (was an AlertDialog) — same data-safety assertions.
- **`LockGuardTest`** — Unicode password now protects/verifies/unlocks; a malformed surrogate is
  refused before anything is written. Every PM-3.1b guard unchanged and green.
- **`ProtectionStateTest`** — `isEncodable` v2 rules; repair with a Unicode password works, malformed
  text is refused without touching bytes.
- **`UiSmokeTest`** — the password-dictionary test now pins `/V 5 /R 6 /Length 256 /CFM /AESV3`.

## Not done / carried

- **"Remember in Vault"** is built but hidden until L2's Vault API merges (`rememberAvailable`), and
  the Unlock sheet's Vault button likewise. No plaintext password is stored anywhere.
- **Change password** still asks the current password with the existing `SwDialog.input` card before
  opening the Protect sheet. L1's "replace every SwDialog with SwSheet" sweep owns that dialog.
- `ui/DocumentViewerActivity.kt` (not in any lane's file list) still shows the old protect dialog and
  the old `protect_note` string in `strings.xml`, which says RC4-128. Both are now false for new
  files. **Needs L1/lead:** point that dialog at `ProtectSheet` and remove/replace `protect_note`.
- `tools/pdfcheck.py` crashes on any locked PDF (`doc.metadata` is `None` before authentication) —
  see the host check. One-line fix for the lead: `(doc.metadata or {}).get("encryption")`.
- Pre-existing: `AndroidManifest.xml` declares `INTERNET` and `ACCESS_NETWORK_STATE` (not added by
  this lane; the design says no INTERNET permission). Not touched — outside L3's file ownership.
- No phone run requested; no screenshots taken (Robolectric UI tests cover the sheets).

## Gate result (last full run)

```
tests: total=853 failures=0 skipped=0
GATE PASS L3 tests=853 lint=[0 errors, 329 warnings]
```

## Host check (independent parser: PyMuPDF 1.27.1, not ScanWhirl code)

Files produced by the committed classes (`L3HostCheck` runs `PdfGenerator.imagesToPdf(..., enc)` and
`PdfEncryptor.encrypt`, no Gradle):

```
$ python3 pdfcheck_l3.py /tmp/l3gen.pdf pw1234      # encrypted while generating
needs_password: True
wrong_password_opens: False
right_password_opens: True
permissions: 6
encryption: Standard V5 R6 256-bit AES
pages: 1
page1_renders: True

$ python3 pdfcheck_l3.py /tmp/l3post.pdf pw1234     # PdfEncryptor rewrite of a plain PDF
needs_password: True
wrong_password_opens: False
right_password_opens: True
permissions: 6
encryption: Standard V5 R6 256-bit AES
pages: 1
page1_renders: True

$ python3 pdfcheck_l3.py /tmp/l3unicode.pdf 'पासवर्ड1'
needs_password: True
wrong_password_opens: False
right_password_opens: True
encryption: Standard V5 R6 256-bit AES
pages: 1
page1_renders: True
```

`pdfcheck_l3.py` is the lane's copy of `tools/pdfcheck.py` with the `None`-metadata line guarded.

The **exact** repo tool was also run:

```
$ wsl -d Ubuntu --cd /tmp -- python3 /mnt/c/Users/Cybium/Desktop/ScanWhirl/tools/pdfcheck.py /tmp/l3post.pdf pw1234
needs_password: True
Traceback (most recent call last):
  ...
  File ".../tools/pdfcheck.py", line 13, in <module>
    print("encryption:", doc.metadata.get("encryption"))
AttributeError: 'NoneType' object has no attribute 'get'
```

i.e. the three required facts hold; the tool's own `doc.metadata` line is what fails, not the file.

The three implemented algorithms were cross-checked against MuPDF's source
(`source/pdf/pdf-crypt.c`, fetched) and against files MuPDF itself generated (`/U` and `/UE`
reproduced round-for-round, 93 and 66 rounds in the two distinguishing samples).

## Commits

- `7eaef61` L3: AES-256 Protect PDF (V5/R6 AESV3), Unicode passwords, Protect/Unlock sheets
- (this report) L3: report
