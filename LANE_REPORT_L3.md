# LANE_REPORT_L3 — Protect PDF and Book pages

Session 3: 2026-11-14 (evening). Branch `lane/L3-protect`, worktree
`C:\Users\Cybium\Desktop\ScanWhirl-L3`.

## Done this session

1. **`git merge integration`** — fast-forward to `7d673f2` (no conflicts; L2's Vault, L1's kit
   sweep, L4/L5 UI all present).
2. **Remembered PDF passwords wired to L2's `VaultPasswords`** (design §4.2).
   - New `ui/protect/VaultPasswordsFlow.kt` (internal helper):
     - `has(ctx, id)` — true only when an entry is saved and readable; a locked or damaged Vault
       answers false, so the Unlock button is not drawn (L2's stated contract).
     - `remember(activity, id, password)` — saves at once when the Vault is unlocked; when it is
       locked it shows "Unlock the Vault to save this password", opens **L2's `VaultActivity`**
       (its unlock sheet), and retries once the calling activity resumes. `onResult(false)` means
       the owner backed out; protection is never blocked.
     - `readForUnlock(activity, id)` — same unlock-then-retry for the Unlock sheet; null = missing
       or unreadable, so the sheet falls back to typing.
     - `forget(ctx, id)` — removes the entry (best effort; a locked Vault cannot be cleaned
       without its key).
     - The real launcher waits for the caller's next `onResume` through
       `Application.ActivityLifecycleCallbacks` (VaultActivity does not `setResult`; the user
       unlocks, then goes Back). `unlockLauncher` is the test seam, mirroring L2's store seams.
   - **Protect sheet** (`tools/LockFlow.kt`): `rememberAvailable` = "a Vault is set up", so the
     row is visible and ON by default only then. After the PDF is protected the password is saved
     (or updated) in the Vault; if the owner cancels the Vault unlock the note
     **"Password not saved in Vault."** is shown. Same wiring for the device-PDF lock path.
   - **Unlock sheet**: "Use password saved in Vault" shows only when `has(id)` is true; tapping it
     fills the password, verifies it, and unlocks the document. A Vault that relocked after the
     sheet opened is unlocked first. A missing or stale entry shows a short note and leaves the
     sheet open, so typing still works.
   - **Remove protection / change password**: removing protection (or unlocking from the sheet)
     deletes the saved entry; changing the password updates it (or removes it when "Remember in
     Vault" is turned off).
   - No new permissions, no libraries, no plaintext: `VaultPasswords` keeps the entries
     AES-256-GCM under the Vault key.
3. **Copy cleanup** in `strings_protect.xml`, `strings_book.xml` and their `values-hi` files:
   every long dash removed (full stop/comma/colon instead), new lines short and plain.
   `tools/copy_audit.ps1`: `strings_protect.xml` and `strings_book.xml` now report **0 long
   dashes / 0 strings over 90 chars** (English). New Vault notes:
   "Password not saved in Vault.", "No saved password. Type it instead.",
   "The saved password did not work. Type it instead.",
   "Unlock the Vault to save this password."

## Tests added

`ui/protect/VaultPasswordsWiringTest` (9, Robolectric, L2's `FakeVaultKeys` + 2,000 PBKDF2 rounds):

- Remember row hidden without a Vault, visible and ON after setup.
- Protect with Remember saves the exact password; **scan of `filesDir`, `shared_prefs` and
  `cacheDir` finds no plaintext copy**.
- Locked Vault: the PDF is still protected; the seam unlock then stores the password
  (one unlock request).
- Cancelled Vault unlock: protected anyway + "Password not saved in Vault." + no entry.
- Vault button GONE without a saved entry, VISIBLE with one (`has`).
- Saved password opens the document even when the Vault relocked after the sheet opened; the
  entry is dropped after unlocking.
- A stale saved password: note shown, document stays locked, sheet stays open for typing.
- Remove protection (with the second confirmation) unlocks and forgets the entry.
- Change password updates the Vault entry to the new password.

## Not done / carried

- The **reader's own** More → Unlock (`DocumentViewerActivity.unlockCurrentPdf`) still unlocks
  through `LockFlow.promptCurrentPassword` and does not call `forget`; a stale entry there is
  harmless (it is never offered on an unlocked document, and a later Remember overwrites it).
  Cleaning it would need a small edit in that Activity, outside this lane's file list.
- The Vault button is hidden while the Vault is locked (per L2's contract). A user who wants a
  saved password on a locked Vault must open the Vault once first; the sheet's lock path only
  covers a relock after it was opened.
- Book pages still has no in-screen Pro gate (L1 is adding central Pro gating for all Pro tiles).
- DEVICE-UNPROVEN: the real `VaultActivity` round trip through `ActivityLifecycleCallbacks`
  (Robolectric uses the launcher seam). Worth one phone check: protect with the Vault locked,
  unlock, come back, see the password offer on the Unlock sheet.

## Gate (last full run, this session)

```
tests: total=1047 failures=0 skipped=0
GATE PASS L3 tests=1047 lint=[lint skipped (lead runs it at merge)]
```

## Commits

- `7d673f2` merge `integration` (fast-forward)
- `65efc00` L3: Remember in Vault for Protect PDF and Use saved password in the Unlock sheet
  (L2 `VaultPasswords`), copy cleanup
- (this report)
