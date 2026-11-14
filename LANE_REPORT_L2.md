# LANE_REPORT_L2 — Vault

Session 2026-11-14 (second session). Branch `lane/L2-vault`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L2`.
Phase A (engine) was committed by the previous session (`4878349`); this session reviewed and finished
the uncommitted Phase B work, merged `integration` twice, added the process-wide Vault session and the
`VaultPasswords` API for L3.

## Heads-up for the lead (gate)

* **`integration` `426a9cd` is red on two tests that are not mine** (they fail on a clean
  integration too — they came in with L1's "SwSheet sweep" merge `426a9cd`):
  * `StampToolWiringTest > each of the eight entries opens the editor with its own tool preselected` —
    `ClassCastException: BottomSheetDialog cannot be cast to AlertDialog` (the shared chooser now
    opens a `SwSheet`).
  * `StampToolLauncherTest > the shared document chooser opens the editor with the chosen document and tool`
    — same cause ("Merge/Compress' chooser is reused").
  Both are L5 UI-shape tests that encode the old `AlertDialog`; per `71_LANES.md` §2.3 they may be
  rewritten to the new sheet (with `// v2 UI: ...`), which is L1's/L5's call — I did not touch
  another lane's tests. My own last full run of this same tree before the L1 merge was green
  (1009 tests), and the only difference is L1's sweep.
* `VaultScreen`'s button still carries L1's label "Coming soon" (`R.string.vault_tab_open`) and L1's
  "being built" note (`vault_tab_coming`), although it now opens the real Vault. I was told to change
  only one call in that file, so the copy is left for L1.
* The framework `android.hardware.biometrics.BiometricPrompt` **cannot be used from an app**: in the
  compileSdk 36 SDK it has no public constructor (only a package-private one; `PromptInfo`/`Builder`
  are not in the public stubs). The app-facing prompt lives in `androidx.biometric`, a new dependency
  I may not add. See "Phone lock" below for what I built instead.

## Done

### Phase B — UI (mockup `04_vault`)

`ui/vault/VaultActivity.kt` (one screen, all sheets through the kit's `SwSheet`/`PasswordField`):

- **Setup:** passcode ×2 (6+ digits, digits only) → the 24-character recovery code shown **once in
  groups of four**, monospace, with the "nobody can open these files — not even us" copy and a Copy
  button whose warning appears only after copying → **type the first 4 characters back** → done.
- **Unlock:** the phone's own lock (fingerprint / face / PIN) or the passcode. Five wrong passcodes
  give a 30-second pause that doubles (shown as "Too many tries. Try again in N s."), plus the
  recovery-code path for a forgotten passcode.
- **Phone lock (design deviation, explained above):** `KeyguardManager.createConfirmDeviceCredentialIntent`
  (public API since 21, no permission, no library) authenticates the user to the device; that
  authorizes the auth-bound Keystore key (`setUserAuthenticationValidityDurationSeconds(30)`), and
  then `VaultStore.unlockWithBiometric()` unwraps the vault key. The option only appears when a
  Keystore wrap exists **and** the device is secure. **DEVICE-UNPROVEN** (see below).
- **Relock:** 2-minute background grace (`VaultSessionPolicy`), now applied by the process-wide
  `VaultSession` so the key expires for every screen at once.
- **List:** stats card ("N files protected"), green unlocked banner that names how it was unlocked,
  private-file cards (page badge, name, meta, ⋯ → Open / Remove from Vault with a confirmation).
- **Add files:** a library picker (`repo.listDocuments()`); the document is encrypted, verified and
  its library copy is purged by `VaultStore.addDocument` (Phase A).
- **Backup:** `CreateDocument` SAF picker, suggested name `ScanWhirl-vault-YYYYMMDD.swvault`,
  written through `VaultStore.exportBackup`; errors as sheets.
- **Restore:** `OpenDocument` picker → recovery-code sheet → `VaultStore.importBackup` → a new
  passcode is asked for (the old passcode/Keystore wrap do not travel with the backup).
- **Legacy PIN Vault:** on first open, the old 4-digit PIN (verified by `Prefs.verifyVaultPin`) is
  asked once, then the new setup runs and `migrateLegacyVault()` moves the old vaulted documents;
  it is also available later from the ⋯ sheet.
- **Shell wiring:** `ui/shell/VaultScreen.kt` — **one call changed** (the tab button starts
  `VaultActivity`). `ui/kit/ToolRegistry.kt` — the `vault` entry is `READY` and launches
  `VaultActivity` from a real host (a non-Context host still gets `host.open`), so the Tools tile is
  not a dead tap.
- Strings only in `values/strings_vault.xml` + `values-hi/strings_vault.xml` (Hindi `<!-- review -->`),
  ids in `values/ids_vault.xml`, no new layouts (programmatic kit style). Two names were renamed to
  `vault_screen_title` / `vault_no_files_title` because `strings.xml` already owns `vault_title` /
  `vault_empty_title`.

### VaultSession (new, `store/vault/VaultSession.kt`)

The vault key is held **once per process**, not per `VaultStore` instance. `VaultStore.setup/unlock*`
store it, `requireKey()` and `isUnlocked()` read it, `lock()/wipeAll()` clear it. This is what makes
the API below usable from any screen and makes the 2-minute grace apply app-wide
(`onBackground(now)` / `onForeground()` / lazy expiry in `keyOrNull(now)`). `VaultSessionPolicy`
moved from `ui.vault` to `store.vault` (its test moved with it and still passes).

### API for lane L3 — `store/vault/VaultPasswords.kt` (PDF passwords)

```kotlin
// Any screen, no VaultStore instance needed:
val passwords = VaultPasswords.of(context)          // application context is enough

passwords.save(documentId, password)                // "Remember in Vault"
val pw: String? = passwords.get(documentId)         // "Use password saved in Vault" -> null = none
passwords.has(documentId)                           // show the row/button only when true
passwords.remove(documentId)                        // "Remove protection" / user unticks remember
passwords.count()
```

* Stored **encrypted with the vault key** (AES-256-GCM, the same container as every Vault file) in
  `filesDir/vault2/passwords.bin`, keyed by document id, Base64 inside the encrypted blob; atomic
  write + directory fsync; no plaintext of the id, the password or the header on disk.
* **Readable only while the Vault is unlocked**: every method throws `VaultLockedException` once the
  key is gone (locked, or 2 minutes in the background). `VaultAuthException` means the blob was
  changed or belongs to another key — never return a wrong password silently.
* L3 wiring suggestion: `ProtectSheet(rememberAvailable = true)` and, in the unlock sheet,
  `vaultAvailable = runCatching { VaultPasswords.of(ctx).has(id) }.getOrDefault(false)`. A locked
  Vault should fall back to the passcode path (or offer "Unlock the Vault").
* **Not in the `.swvault` backup** (the backup container only carries vault entries + the recovery
  wrap). After a restore, remembered PDF passwords are gone; documents are intact. Extending the
  backup format is left to the lead if wanted.

## Not done / carried

- **Biometrics on real hardware** — DEVICE-UNPROVEN: Keystore `wrap`/`unwrap`,
  `setUserAuthenticationValidityDurationSeconds(30)`, key invalidation on biometric enrolment, the
  keyguard confirmation and the phone-lock → Keystore authorization window. Robolectric has no
  hardware Keystore; tests use the `FakeVaultKeys` seam and a fake credential intent
  (`VaultActivity.credentialIntentFactory`).
- **A vault document open in `PdfViewerActivity`** keeps its decrypted cache file until the Vault
  screen relocks (the viewer is L1's file). The key itself is dropped by the session after 2 minutes.
- `VaultScreen`'s stale "Coming soon" copy is L1's (see heads-up).
- The legacy 4-digit PIN is only *verified*; its docs are re-encrypted under the new vault key.
  `Prefs` keeps the old PIN entry (legacy flows still use it) — removing it is L1/lead's call.
- Merge carried two integration-side failing UI-shape tests (L5's stamp chooser after L1's SwSheet
  sweep), see heads-up.

## Tests added / updated

- `VaultActivityTest` (new, `// v2 UI: 04_vault`): setup → one-time code → type 4 characters back;
  wrong passcode then right one; recovery code after a forgotten passcode; **phone lock** (system
  confirmation started, RESULT_OK opens the vault with the Keystore wrap, banner text) and a
  cancelled confirmation leaving it locked; add a library document (encrypted, shown in the list,
  library copy gone); `.swvault` backup picker; **relock after the 2-minute grace**; legacy PIN asked
  once before setup.
- `VaultSessionTest` (new): one state for all handles (second `VaultStore` sees/locks it); key
  dropped at exactly 2 minutes in the background; foreground cancels the grace; locked → no key and
  `VaultPasswords` throws.
- `VaultPasswordsTest` (new): save/get/remove/count with two documents; Unicode password round trip;
  the file holds no readable id/password/header and needs the key; locked Vault refuses every
  operation; survives lock → passcode unlock; readable from another screen's handle; tampered blob
  fails the GCM tag; `wipeAll` removes it.
- `VaultSessionPolicyTest` — moved to `store.vault`, unchanged.
- `VaultStoreTest` — `VaultSession.close()` in setUp/tearDown only.
- `ShellScreenTest` (L1's, one test rewritten with `// v2 UI: 04_vault`): the Vault tab now starts
  `VaultActivity` instead of opening a "coming soon" sheet.
- Phase A engine tests (`VaultCryptoTest`, `VaultStoreTest`) still green and unchanged in intent.

## Gate

Last full run of the tree with all of this lane's work in it (and `integration` before L1's sweep):

```
tests: total=1009 failures=0 skipped=0
GATE PASS L2 tests=1009 lint=[lint skipped (lead runs it at merge)]
```

Full run after merging `integration` `426a9cd` (L1 Phase B + L4 tools + the long-image test fix);
only inherited failures:

```
tests: total=1028 failures=2 skipped=0
GATE FAIL L2 tests=1028 failures=2 skipped=0 lint=[lint skipped ...]
  FAIL StampToolLauncherTest > the shared document chooser opens the editor ... :: Merge/Compress' chooser is reused
  FAIL StampToolWiringTest > each of the eight entries opens the editor ... :: ClassCastException: BottomSheetDialog cannot be cast to AlertDialog
```

## Commits (this session)

- `a162028` L2: v2 Vault UI (04_vault) — setup with one-time recovery code, phone-lock/passcode
  unlock, library add, backup/restore, legacy PIN migration, shell wiring
- `7da098a` L2: remove a stray empty artifact file
- `1c128b3` merge `integration` (L4 partial, L5 stamp)
- `96e0714` L2: process-wide VaultSession (2 min background relock) + VaultPasswords API for L3
- `82926f6` merge `integration` (`6fa93f8`, L4 tools UI + long-image 16,000 px)
- `bcd3ec8` merge `integration` (`426a9cd`, L1 Phase B: reader dock, Text screen, Manrope, SwSheet sweep)
- (this report) L2: report
