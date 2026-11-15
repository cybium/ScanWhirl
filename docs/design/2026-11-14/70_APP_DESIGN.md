# 70 — ScanWhirl v2 app design (screens, tools, security)

Lead engineer: Opus, 2026-11-14. **This is the target the lanes build.** Pictures: `mockups/*.png` (rendered from
`src/*.html` by `src/render.ps1`). If code and a mockup disagree, the mockup wins on look and this file wins on behaviour.

Positioning: **private, offline, no ads, no account, the full PDF suite.** App size is not a constraint.

---

## 1. Navigation

Bottom bar: **Library · Tools · (Scan disc) · Vault · Me**. Mockup `01_home_A.png`.

| Tab | Screen | Mockup |
|---|---|---|
| Library | Home: search; create row (ID card, Photos, Import PDF); 6 quick tools; category chips; document cards with Share and ⋯ | 01_home_A |
| Tools | Every tool, grouped, searchable | 03_tools_hub |
| Scan disc | ML Kit scanner (existing `ScanLauncher`) | — |
| Vault | Encrypted private files (fingerprint or passcode) | 04_vault |
| Me | Settings, Pro, About, Trash, Backup | (reuse SettingsActivity, restyled) |

**Rejected: Home option B** (`02_home_B.png`). Its big scan hero shows only 2 documents above the fold and needs a
separate Home vs Library tab.

**Document reader** (`07_reader_dock.png`): bottom dock **Share · Pages · Text · Sign · More**, a page strip, and a lock badge in the title.
**Text screen** (`08_text_ocr.png`): page image with recognised-line boxes; Page/Text switch; editable text; Copy all · Share text · Save .txt · To Word (Pro).

## 2. Look (Android tokens)

| Token | Value | Android |
|---|---|---|
| Background | `#0E1316` | `@color/void_abyss` (exists) |
| Card | `#161D21 → #1C252A` gradient, 1dp border `#2400F0FF` | new `@drawable/bg_glass_card` |
| Accent | `#00F0FF` | `@color/cyan_primary` (exists) |
| Pro | `#FFB800` | `@color/gold_pro_primary` (exists) |
| OK / warn / error | `#38EF7D` / `#FFB800` / `#FF8C82` | new `ok_green`; `laser_danger` exists |
| Text | `#ECEFF1` / `#B3BCC2` / `#8D979E` | `text_hologram_hi/mid/dim` (exist) |
| Radius | small 12 / medium 18 / large 26 / sheet 28 dp | `dimens_v2.xml` |
| Font | Manrope (bundle as `res/font/manrope_*.ttf`, OFL licence) | `@font/manrope` |
| Icons | Material Symbols Rounded, outlined, 24dp, cyan | vector drawables `ic_sym_<name>.xml` (Apache 2.0) |
| Logo | Whirl: three open arcs (svg in `src/sw.css` `.logo`) | `@drawable/ic_whirl` (cyan) and `ic_whirl_gold` |

**Components every lane reuses** (lane L1 builds them first, in `ui/kit/`):

- `SwSheet`: a bottom sheet with a grab handle, title, close button and a full-width primary button. **It replaces every `AlertDialog`.** Mockups `05`, `09`.
- `PasswordField`: show/hide toggle, error line, optional strength meter (4 segments).
- `ToolTile`: icon + label + optional `PRO` or `Soon` tag.
- `DocCard`: thumbnail, name, meta, category dot, Share, ⋯.
- `TrustPill`: a green pill ("Offline · No ads").

## 3. Tool catalogue (44 tools; 19 Pro)

Updated 2026-11-15: To PowerPoint added, Translate removed (see §6). Live status is in `ui/kit/ToolRegistry.kt`.

**Status meanings:**
- **Ready**: the code exists today; restyle and wire it.
- **Build**: a lane builds it now.
- **Soon**: the tile shows a "Coming soon" sheet with one line describing what the tool will do. It is never a dead button.

| Group | Tool | Free/Pro | Status | Lane |
|---|---|---|---|---|
| Create | Scan | Free | Ready | L1 |
| | ID card (front + back on one page) | Free | Build | L4 |
| | Book pages (split a two-page spread) | Pro | Soon | — |
| | Photos to PDF | Free | Ready | L1 |
| | Import PDF | Free | Ready | L1 |
| | Passport (crop preset) | Free | Build | L4 |
| | QR code reader | Pro | Soon | — |
| | Business card | Pro | Soon | — |
| Organize | Merge | Free | Ready → safe (PM-2.1b) | L4 |
| | Split by range | Pro | Build | L4 |
| | Extract pages | Free | Ready → safe | L4 |
| | Organize pages (draft, Discard always works) | Free | Build (PM-2.2b/c, 2.3b) | L6 (wave 2) |
| | Insert pages | Free | Soon (wave 2) | — |
| | Duplicate | Free | Ready → safe | L4 |
| | Folders & tags | Pro | Soon | — |
| | Batch rename | Pro | Soon | — |
| Optimize | Compress (to a target size) | Free | Ready → safe | L4 |
| | Resize | Free | Ready | L1 |
| | Enhance | Free | Ready (filters) | L1 |
| | Black & white | Free | Ready (filters) | L1 |
| Convert | PDF to images (JPG/PNG, ZIP) | Free | Build | L4 |
| | Long image | Free | Build (E20 rules, bounded) | L4 |
| | To Word (.docx, text) | Pro | Build | L4 |
| | To Excel | Pro | Build (wave 2) | L4 |
| | To PowerPoint (pages as slides) | Pro | Ready | L4 |
| | To text (.txt) | Free | Ready (OCR export) | L1 |
| | ZIP export | Free | Ready (archive) | L4 |
| | Searchable PDF | Free | Ready (setting) | L1 |
| | PDF/A | Pro | Soon | — |
| Edit & sign | Sign | Pro | Build | L5 |
| | Add text | Pro | Build | L5 |
| | Draw | Pro | Build | L5 |
| | Highlight | Pro | Build | L5 |
| | Watermark | Pro | Build | L5 |
| | Page numbers | Pro | Build | L5 |
| | Stamp ("Paid", "Approved", date) | Pro | Build | L5 |
| | Redact (true: pixels burned, text removed) | Pro | Build | L5 |
| Secure | Protect PDF (AES-256) | Free | Build | L3 |
| | Unlock PDF | Free | Build | L3 |
| | Vault | Free | Build | L2 |
| | Remove metadata | Free | Build | L4 |
| Text | Read text (OCR) | Free | Ready → Text screen | L1 |
| | Hindi text | Free | Soon (PM-6.3 spike) | — |

**Pro price:** ₹100, one time (owner, OQ-2). **Pro switch:** test builds unlock Pro (`BuildConfig.TEST_BUILD`).
Until billing (PM-5.5) exists, production shows the Pro screen with "Coming soon" on the buy button. It never shows a dead end.

## 4. Security design (replaces the old "Lock")

The old lock mixed two jobs. They are now two features.

### 4.1 Vault: privacy inside the app (lane L2)

- **Unlock:** fingerprint or face (`android.hardware.biometrics.BiometricPrompt`, API 28+, no new library), or a **6+ digit passcode** (all APIs).
  - It relocks when the app goes to the background, after a 2-minute grace period, configurable.
  - Five wrong passcodes give a 30-second pause, doubling each time.
- **Files** are encrypted on disk with **AES-256-GCM**. Each file gets a random 96-bit IV; the 128-bit tag is checked on read.
- **Keys:**
  - A random 256-bit **vault key** encrypts the files.
  - The vault key is stored wrapped twice:
    - (a) by a key in the **Android Keystore** (hardware-backed where available) that requires user authentication;
    - (b) by a key derived from the **recovery code** with PBKDF2-HMAC-SHA256 (API 26+) or HMAC-SHA1 (API 24–25), 310,000 iterations, 16-byte salt.
- **Recovery code:** 24 characters from an unambiguous alphabet, shown **once** at setup in groups of 4. The user must type 4 of the characters back before setup finishes. A "Copy" option shows a warning.
- **Backup file** (`.swvault`): one file containing the encrypted files plus the vault key wrapped by the recovery code (AES-GCM). It is restorable on a new phone with the recovery code alone.
  - This replaces the password-ZIP idea. Standard ZIP passwords use weak ZipCrypto, and AES-ZIP needs a library.
  - The backup can be saved anywhere (Drive, a PC), because without the code it is unreadable.
- **Thumbnails, OCR text and drafts** of vault files are encrypted the same way. Search inside the vault works only while it is unlocked.
- **Honest copy:** "Encrypted on this phone. If you lose the recovery code *and* this phone, nobody can open these files — not even us."
- **The legacy PIN Vault** migrates in one confirmed step on first open. The old 4-digit PIN unlocks it once, then the user sets up the new vault.

### 4.2 Protect PDF: protection for files you send (lane L3)

- **AES-256**, PDF security handler V5/R6 (ISO 32000-2). It opens with the password in Adobe, Chrome, Drive, PyMuPDF and Android `PdfRenderer` 35+.
  - **RC4 is no longer offered for new files.** Old RC4 files still unlock (read support kept).
- **UI** (`05_protect_pdf.png`):
  - password, typed twice, with a show toggle;
  - a strength meter: minimum 6 characters, a "Weak" warning under 10;
  - the AES-256 line, saying plainly that strength depends on the password;
  - "Remember in Vault", on by default, which stores the password in the Vault (L2 API) so a forgotten password is recoverable;
  - **Protect & share**, or **Save protected copy**.
- **Unlock sheet** (`09_unlock_sheet.png`) replaces `Open / Cancel / Unlock permanently`:
  - a password field and the tries-left error;
  - after 5 wrong tries, a 30 s pause;
  - "Use password saved in Vault" (fingerprint);
  - Cancel, and "Remove protection…" behind a second confirmation.
- **Passwords:** any Unicode is allowed. R6 uses SASLprep'd UTF-8, which fixes the Latin-1 limit. Maximum 127 bytes, per the spec.
- **Locked library documents** keep the PM-3.1b guards: no plaintext under a lock, and no unlock without the password.

## 5. Behaviour rules every lane follows

1. Every button does something real, or opens the "Coming soon" sheet. No dead taps, no toast-only stubs.
2. Every new screen matches its mockup's layout, colours and spacing. Screens without a mockup reuse the kit components.
3. Every user-visible string lives in that lane's own file, `values/strings_<lane>.xml`, **and** `values-hi/strings_<lane>.xml`. Hindi values start with `<!-- review -->`.
4. No network permission, no ads, no analytics, no account. New libraries are allowed only from the approved list in `71_LANES.md` §4.
5. Data safety: outputs publish through `buildAndPublish` (PM-2.1b) once L4 lands it. Nothing overwrites a user's file in place.
6. Copy: no long dash in user-visible text; one short plain line where possible (`tools/copy_audit.ps1`).

## 6. Decisions log

- 2026-11-15: **Translate removed.** On-device translation needs language models downloaded with the app's own network access; ScanWhirl has no INTERNET permission. Not offered, not shown as Soon.
- 2026-11-15: Approved libraries: `androidx.biometric:biometric:1.1.0` (Vault unlock), `play-services-code-scanner:16.1.0` (QR), `com.google.mlkit:text-recognition-devanagari:16.0.1` (bundled Hindi OCR).
- 2026-11-14: Header logo = the launcher brand mark (two rings + page), not the three-arc whirl. Icons = official Material Symbols Rounded (Apache 2.0).
- 2026-11-14: Long image part limit 16,000 px (E20).

- 2026-11-15 (owner review): global-first. 'Hindi text' tile removed (language = Read text setting; + Chinese/Japanese/Korean bundled). 'Mask Aadhaar' tile removed -> Redact 'Find and hide numbers' (Aadhaar/VID/PAN/cards/phone/email/IBAN/SSN, Aadhaar preset chip). ID card paper A4/Letter by locale. Full Backup 2.0 (library + Vault, recovery code or backup password, share to Drive; no password ZIP: ZipCrypto weak, AES-ZIP not openable in Windows Explorer). Me tab: one About, no nested Settings. Pro perk: Gold theme + gold launcher icon (activity-alias), Cyan keeps Pro.
- 2026-11-15 (owner): audience = English + Hindi; NO Chinese/Japanese/Korean OCR. Home quick tools customisable (pencil edit, add/remove/reorder, up to 2 rows / 12). Pro screen + Pro gate sheet animated (gold ring burst, cascade, shine sweep, one pulse; off when animations are off).
