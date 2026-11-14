# LANE_REPORT_L2 — Vault

Session 2026-11-14 (third pass). Branch `lane/L2-vault`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L2`.
Phase A (engine) `4878349`, Phase B UI `a162028`/`82926f6`; this pass replaces the temporary
phone-lock unlock with the approved `androidx.biometric` prompt and carries remembered PDF
passwords inside the backup.

## Done this pass

### 1. Fingerprint/face unlock through `androidx.biometric.BiometricPrompt`

- **`ui/vault/VaultActivity`** — "Use fingerprint" on the unlock sheet starts
  `BiometricPrompt` (AndroidX 1.1.0, added by the lead) with:
  - `PromptInfo`: title **"Unlock Vault"** (`vault_unlock_sheet_title`), the unlock note as the
    subtitle, `setAllowedAuthenticators(BIOMETRIC_STRONG)`, negative button **"Use passcode"**
    (`vault_use_passcode`, which leaves the passcode sheet in place), no confirmation step;
  - a **`CryptoObject`** wrapping the auth-bound Keystore decrypt cipher, so the vault key is
    unwrapped **only after** a successful BIOMETRIC_STRONG authentication
    (`onAuthenticationSucceeded` → `store.completeBiometricUnlock(challenge)`).
- **Decision logic** (`ui/vault/VaultUnlockPolicy`, pure): the fingerprint is offered only when
  API ≥ 28 **and** the Vault has a valid Keystore wrap **and**
  `BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS`. Every other status
  (none enrolled, no hardware, hardware unavailable, security update, unsupported, unknown),
  a missing key, or API < 28 goes straight to the passcode. The passcode is always available;
  the five-try lockout and the 2-minute relock are unchanged.
- **Keystore seam (`VaultKeys`)** — same seam as before, now CryptoObject-shaped:
  - `challenge(blob)` returns a`VaultKeyChallenge(cipher, complete)`; the key is **per-use
    auth-bound** (`setUserAuthenticationRequired(true)`, no validity duration), so the cipher is
    unusable until the prompt authorizes it;
  - `hasKey()` lets the screen know whether a fingerprint key exists;
  - alias bumped to `scanwhirl_vault_key_v2` (a wrap made under the old time-based alias fails its
    GCM tag, falls back to the passcode and is re-wrapped).
- **Invalidated key** (new fingerprint/face enrolled → `KeyPermanentlyInvalidatedException`):
  `challenge()` deletes the dead key and throws `VaultKeyInvalidatedException`; the store drops
  `keystore.bin`, the screen explains it (`vault_fingerprint_reset`) and asks for the passcode.
  **After any successful passcode or recovery unlock `repairKeystoreWrap()` rebuilds a fresh
  Keystore key and wrap** (`VaultStore.unlockWithPasscode` / `unlockWithRecovery`).
  The same path repairs a wrap from an older install (`completeBiometricUnlock` drops the file on
  an auth failure).
- Prompt errors: "Use passcode"/cancel leave the passcode sheet as it is; lockout shows
  `vault_fingerprint_locked`; any other error is shown by its system text. Nothing is a dead end.
- `VaultActivity` test seams: `biometricStatusOverride` (instead of `canAuthenticate`) and
  `fingerprintStarter` (instead of the real prompt); the real path is DEVICE-UNPROVEN.

### 2. `.swvault` backup now carries `passwords.bin`

- **`VaultBackup` format version 2**: after the entries, `int extraCount | { UTF name | long len |
  bytes }`. **Version 1 files still restore** (the reader stops after the entries).
- **`VaultStore.exportBackup`** adds `vault2/passwords.bin` as an extra; **`importBackup`**
  verifies it with the restored key (`VaultPasswords.verifyFile`, GCM + header) *before*
  publishing, then moves it next to the entries. Still encrypted under the vault key; only the
  recovery code unwraps the key, so a backup saved on Drive/a PC stays unreadable without it.
- Round-trip covered by a test: save password → backup → `wipeAll` → restore → `get` returns the
  same password; and a v1 container restores its documents.

## Which file is what

- `store/vault/VaultStore.kt` — entries, locks, backup/restore, biometric challenge, Keystore repair.
- `store/vault/VaultKeys.kt` — Keystore seam; `AndroidVaultKeys` (DEVICE-UNPROVEN) + `VaultKeyChallenge`.
- `store/vault/VaultCrypto.kt` — AES-256-GCM chunks, PBKDF2 key wraps (`VaultAuthException` now `open`).
- `store/vault/VaultSession.kt` — process-wide unlock state + 2-minute grace (`VaultSessionPolicy`).
- `store/vault/VaultBackup.kt` — `.swvault` v2 container (v1 readable).
- `store/vault/VaultPasswords.kt` — PDF passwords for L3 (API below).
- `ui/vault/VaultActivity.kt`, `VaultCard.kt`, `VaultUnlockPolicy.kt` — mockup `04_vault`.

## API for lane L3 — `VaultPasswords` (unchanged surface, now backed up)

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
background grace) and `VaultAuthException` when the blob was changed. Included in `.swvault`
backups. L3 wiring: `rememberAvailable = true`; hide "Use password saved in Vault" unless
`has(id)` succeeds, and fall back to the passcode when the Vault is locked.

## DEVICE-UNPROVEN (lead tests on the phone)

- `androidx.biometric.BiometricPrompt` with the CryptoObject cipher (real sensor, cancel path,
  lockout, prompt over the sheet, rotation).
- Android Keystore wrap/challenge/unwrap with a **per-use auth-bound** key, and
  `KeyPermanentlyInvalidatedException` after enrolling a new fingerprint (fallback + re-wrap).
- Whether the device's face/fingerprint is reported as `BIOMETRIC_STRONG` (a weak-only sensor is
  deliberately not offered).
- **On a phone set up with the previous build**: the old `keystore.bin` (time-based key) will not
  open; the first passcode unlock re-wraps it, then the fingerprint works. Worth one check.

## Not done / carried

- `VaultScreen`'s tab button still carries L1's "Coming soon" label/note although it opens the
  real Vault (L1's strings; I changed only the one call I was allowed to).
- A vault document open in `PdfViewerActivity` keeps its decrypted cache until the Vault screen
  relocks; the key itself is dropped process-wide after the grace period.
- Legacy 4-digit `Prefs` PIN entry is only verified; the entry itself is left for L1/lead.

## Tests added / updated (all green)

- `VaultUnlockPolicyTest` (new, 4): offered only for API ≥ 28 + key + `BIOMETRIC_SUCCESS`; every
  `canAuthenticate` failure; API 27/24; no Keystore key.
- `VaultStoreTest` (+2): the challenge unlocks only when it completes; an invalidated key throws,
  disappears from `canUseBiometric()`, and a passcode unlock re-wraps so the next challenge works
  and the entries stay readable.
- `VaultPasswordsTest` (+2): backup → wipe → restore returns the same password and document;
  a version 1 backup restores documents and carries no passwords.
- `VaultActivityTest` (rewritten unlock part): fingerprint prompt opened through the seam (cipher
  non-null, banner "Unlocked with fingerprint · relocks in 2 min"); a failed fingerprint shows the
  error then returns to the passcode sheet and stays locked; no fingerprint offer for
  none-enrolled/no-hardware/unavailable/unsupported; an invalidated key offers only the passcode
  and re-wraps after the passcode; the 2-minute relock, setup, recovery, add and backup tests as
  before.
- `FakeVaultKeys` — `challenge()`, `hasKey()`, `invalidate()` (stands for a new enrolment).

## Gate

```
tests: total=1048 failures=0 skipped=0
GATE PASS L2 tests=1048 lint=[lint skipped (lead runs it at merge)]
```

(Also green at `1038` before the last `integration` merge.)

## Commits

- `4878349` L2: Vault Phase A engine (previous session)
- `a162028` L2: v2 Vault UI (04_vault) — setup, unlock, library add, backup/restore, migration
- `96e0714` L2: process-wide VaultSession + VaultPasswords API for L3
- `bcd3ec8`/`ac87cba` merges of `integration`
- `56fb445` L2: fingerprint unlock via androidx BiometricPrompt (BIOMETRIC_STRONG CryptoObject,
  Keystore invalidation repair) + remembered PDF passwords in the `.swvault` backup (v2, v1 readable)
- `24da48f` merge `integration` (`3882fc3`)
- (this report) L2: report
