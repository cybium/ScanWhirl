# LANE_REPORT_L2 - Vault + QR code

Session 2026-11-14 (fourth pass). Branch `lane/L2-vault`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L2`.
This pass: phone-QA fixes on build 435e0f3 (vault passcode, fingerprint offer, counts, sheet
layout, locked placeholder, plurals) on top of the QR code reader.

## Phone QA (run2) - what I changed

1. **Passcode did not unlock after the upgrade (silent close).** The unlock path no longer touches
   the Keystore at all: the `_v2` wrap/repair work moved out of `unlockWithPasscode` /
   `unlockWithRecovery` into a separate, prompt-authorized step. The sheet now
   - sits above the IME (`applySystemBarsPadding` on the sheet root: edge-to-edge ignores
     `adjustResize`, which is how the CTA could sit under the keypad and the tap land on the scrim),
   - unlocks from the IME **Done** key as well as the button,
   - shows **"Wrong passcode. N tries left."** (`vault_unlock_wrong_left` plurals) inside the sheet
     and never dismisses on a failure,
   - and if the sheet is dismissed while still locked, the screen keeps a **"Still locked. Tap
     Unlock Vault and try again."** line, so an unlock can never fail silently.
   - Upgrade test: a vault written by the previous build (old `keystore.bin`, no new Keystore key,
     same `passcode.bin` / `recovery.bin` format) unlocks with its passcode and keeps its entries.
2. **Fingerprint control + honest copy.** When the policy offers the fingerprint, the sheet shows a
   **"Use fingerprint"** button with `ic_sym_fingerprint` and starts the prompt **once** when it
   opens; when it is not offered the sheet says only **"Enter your passcode."** A cancelled
   automatic prompt stays silent; a failed manual one is explained and returns to the passcode.
   **Fingerprint is now actually created after setup/upgrade:** a per-use auth-bound Keystore key
   can only encrypt with a prompt-authorized cipher, so the wrap is created through
   `VaultStore.beginKeystoreWrap()` / `completeKeystoreWrap()` right after the passcode, setup or
   restore succeeds ("Use fingerprint for the Vault?"). The old build could never have had a
   working wrap under the new alias, which is why the option was missing.
3. **Counts documents**: "1 document protected" / "N documents protected" (plurals over entries,
   not files).
4. **Setup sheet**: no auto-focus on the passcode fields, and every sheet gets the IME inset
   padding, so the focused field and the primary button stay above the keyboard.
5. **Locked vault**: "Private files" now has a small **"Unlock to see your files."** card instead
   of empty space. `VaultActivity.intent(context, reason)` accepts the line another screen wants
   shown in the unlock sheet (e.g. "Unlock the Vault to save this password.") - **L3 must pass it**
   (`ui/protect/VaultPasswordsFlow.kt` currently toasts and starts the activity; the toast is L3's
   file).
6. **Plurals**: "1 page" / "N pages", "1 file" / "N files" in the list rows.

## QR code reader (Create group, Pro) - `ui/qr/**`

- `QrContent`: pure parse/action model for link, Wi-Fi (SSID/password/security, `\;` escapes),
  vCard, MeCard, phone, email, SMS, geo and text, with actions per type (Open link, Add to
  contacts, Call, Compose email, Open map, Copy, Share). Wi-Fi Copy copies the password.
- `QrScanner` seam + `GoogleCodeScanner` (`GmsBarcodeScanning`, QR plus the common 1D/2D formats,
  `enableAutoZoom`). No camera permission. **DEVICE-UNPROVEN** (module download / Play services).
- `QrActivity`: kit-style result screen, scan again, local history (last 20, `qr_history.txt`,
  clear), errors as `SwSheet`, cancel saves nothing. "Scan from photo" is **omitted**: image
  decoding needs a barcode-scanning library I may not add; the approved artifact only wraps the
  Google scanner UI.
- `QrToolLauncher` gates the Pro tool with a sheet, like the other Pro tools; the registry entry
  is the only Registry change.
- Tests: `QrContentTest` (all types/actions), `QrHistoryTest` (cap 20, newest first, dedupe,
  clear, damaged file), `QrActivityTest` (result per type, ACTION_VIEW/INSERT/DIAL, copy, cancel
  saves nothing, failure sheet, 20-item history + clear, history row replay, empty code, close).

## Vault copy rules

No em dashes and no line over about 90 characters in `strings_vault.xml` (+hi); sentences use
full stops, commas and colons. (L3's `strings_protect.xml` still has em dashes in the strength
meter; that is L3's file.)

## Tests / gate

```
tests: total=1129 failures=0 skipped=0
GATE PASS L2 tests=1129 lint=[lint skipped (lead runs it at merge)]
```

New/changed tests this pass: `VaultStoreTest` (upgrade from the previous build; wrap through the
prompt; invalidation, passcode, new wrap), `VaultActivityTest` (tries-left error + sheet stays
open + status line; documents count; "1 page"; locked placeholder; reason extra; auto-prompt /
cancelled prompt / manual failure / no-fingerprint copy / invalidation), `QrContentTest`,
`QrHistoryTest`, `QrActivityTest`; `KitComponentsTest` now picks any Soon tool (QR is live).

## DEVICE-UNPROVEN

- Keystore wrap creation through the prompt (`beginKeystoreWrap`) on a real per-use key, and the
  "Use fingerprint for the Vault?" prompt after setup/upgrade.
- The biometric unlock prompt (sensor, cancel, lockout), and the sheet being fully above the
  keyboard (IME insets in an edge-to-edge BottomSheetDialog).
- The Google code scanner (first-use module download, missing Play services).

## Earlier in this lane (still current)

### Fingerprint unlock
`androidx.biometric.BiometricPrompt` with a `CryptoObject` wrapping the auth-bound Keystore
cipher (`BIOMETRIC_STRONG`, title "Unlock Vault", negative "Use passcode"); the decision is
`VaultUnlockPolicy` (API 28 or newer + a valid Keystore wrap + `canAuthenticate == SUCCESS`,
otherwise the passcode only). An invalidated key (KeyPermanentlyInvalidatedException) drops the
wrap and the next passcode unlock offers a new one.

### `.swvault` backup
Format version 2 carries `vault2/passwords.bin` next to the entries (still vault-key encrypted,
verified with the restored key before publishing); version 1 files still restore.

### Files
- `store/vault/VaultStore.kt`: entries, locks, backup/restore, biometric challenge, prompt wrap.
- `store/vault/VaultKeys.kt`: Keystore seam (`AndroidVaultKeys` DEVICE-UNPROVEN, `VaultKeyChallenge`).
- `store/vault/VaultCrypto.kt`: AES-256-GCM chunks, PBKDF2 key wraps.
- `store/vault/VaultSession.kt`: process-wide unlock state + 2-minute grace (`VaultSessionPolicy`).
- `store/vault/VaultBackup.kt`: `.swvault` v2 (v1 readable).
- `store/vault/VaultPasswords.kt`: PDF passwords for L3.
- `ui/vault/VaultActivity.kt`, `VaultCard.kt`, `VaultUnlockPolicy.kt`: mockup `04_vault`.
- `ui/qr/**`: QR code reader.

## API for lane L3 - `VaultPasswords`

```kotlin
val passwords = VaultPasswords.of(context)   // any screen; application context is enough
passwords.save(documentId, password)          // "Remember in Vault"
val pw: String? = passwords.get(documentId)   // "Use password saved in Vault" -> null = none
passwords.has(documentId)                     // show the row/button only when true
passwords.remove(documentId)                  // "Remove protection" / remember unticked
passwords.count()
```

Encrypted with the vault key (AES-256-GCM) in `filesDir/vault2/passwords.bin`, keyed by document
id; every call throws `VaultLockedException` while the Vault is locked (or past the 2-minute
grace) and `VaultAuthException` when the blob was changed. Included in `.swvault` backups.
`VaultActivity.intent(context, reason)` shows a reason line in the unlock sheet (L3 should pass
it instead of toasting).

## Not done / carried

- `VaultScreen`'s tab button still carries L1's "Coming soon" label/note although it opens the
  real Vault (L1's strings; I changed only the one call I was allowed to).
- The "Unlock the Vault to save this password." toast lives in L3's `VaultPasswordsFlow`; my
  activity accepts the reason extra, L3 must pass it.
- A vault document open in `PdfViewerActivity` keeps its decrypted cache until the Vault screen
  relocks; the key itself is dropped process-wide after the grace period.
- Legacy 4-digit `Prefs` PIN entry is only verified; the entry itself is left for L1/lead.

## Commits

- `a162028` v2 Vault UI
- `96e0714` VaultSession + VaultPasswords
- `56fb445` BiometricPrompt + passwords in the backup
- `0edad3a` QR code reader + vault copy rules
- (this pass) vault QA fixes
- (this report) L2: report
