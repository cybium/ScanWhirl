# Full Backup 2.0

`.swvault` only ever held the Vault. The owner's worry ("if my phone dies I lose all my PDFs") is
now covered by one encrypted file with the whole app.

## What is inside

| Entry | Contents |
|---|---|
| `documents/<id>/…` | pages, `.orig` originals, `scan.pdf`, `meta.txt` (name, date, category, tags), `ocr.txt`, `ocr_geo.txt`, flags |
| `vault2/…` | Vault entries, `recovery.bin`, passcode wrap, remembered PDF passwords. The Keystore wrap is skipped (bound to the phone), entries stay encrypted under the Vault key |
| `signatures/`, `stamps/`, `drafts/` | every file under those folders |
| `settings.json` | every preference except the legacy PIN hash |
| `manifest.json` | format version, creation time, app version, document count |

## Container

```
"SWFULLB1" | version(2) | wrap(1=password, 2=recovery code) | pbkdf2 iterations | salt
           | has-vault-key(1) | AES-GCM body
```

The body is the existing chunked AES-256-GCM format (`VaultCrypto`): one random 96-bit IV and one
128-bit tag per 64 KiB chunk, so writing and restoring stream with 64 KiB of memory regardless of
library size. Entries are `byte 1 | UTF path | long size | bytes`, ending with `byte 0`.

The secret is either a backup password (minimum 10 characters, the kit's strength meter) or the
Vault recovery code. The header records which, so restore asks for the right thing. A recovery-code
backup is only written after the code has been *proven* (`VaultStore.unlockWithRecovery`), so a typo
can never produce a file nobody can open. PBKDF2-HMAC-SHA256 uses 310,000 iterations and a random
16-byte salt for both.

## Restore

1. The file is decrypted to a temporary plaintext archive before anything is published: a wrong
   secret or a single changed byte fails the GCM tag and nothing is touched.
2. Entries are validated (no absolute paths, no `..`, no backslashes) and unpacked into a staging
   folder.
3. Merge, never overwrite:
   - a document whose id already exists is skipped;
   - a document whose *name* already exists is restored under a new id as `<name> (restored)`;
   - **Vault entries merge into an existing Vault by re-encryption.** The archive carries the
     backup Vault's key (`vault-key.bin`, inside the encrypted container; obtained from the
     unlocked session or by unwrapping `recovery.bin` with the recovery code). When this phone
     already has a Vault, the restore asks for it to be unlocked (VaultActivity with a reason), then
     for each entry decrypts every file with the backup key, encrypts it again with the current key
     (fresh IVs), rewrites the manifest, verifies sizes and SHA-256 through the same check
     `addDocument` uses, and only then renames it into place. A plaintext file exists only in
     `cacheDir/vault_verify` for the duration of one file.
     - same id -> skipped; same name -> `<name> (restored)`;
     - a tampered entry fails its digest check and nothing of it is published;
     - if the user does not unlock the Vault, everything else is restored and the result says
       "Vault files were not restored. Unlock the Vault and restore again.";
     - a password backup of a *locked* Vault carries no key, so its entries can only go into an
       empty Vault (the result says so);
   - into an empty Vault the backup's own Vault identity is adopted as-is;
   - signatures, stamps and drafts are added only when the file is missing;
   - settings are applied only for keys this phone does not have.
4. Staging and the temporary archive are deleted, then the OCR indexer resumes for restored
   documents.

## UX

Me tab -> **Backup & restore**.

- **Back up now**: choose the Vault recovery code or a password, then "Save to device" (SAF) or
  "Share". Share opens the system share sheet, which lists Google Drive when it is installed. The
  app has no INTERNET permission: the upload, if any, happens in the Drive app.
- **Restore from backup**: pick any file with SAF; the header decides whether a password or the
  recovery code is asked for.
- **Last backup: today / N days ago**, and a reminder checkbox that drives an in-app note when the
  last backup is a month old. No notification channel is used because the app has no notification
  path.
- "Export all as PDFs" stays the plain, portable option.

## Tests (`FullBackupTest`, 12)

Round trip of library + Vault + signatures + settings; the header records the secret kind and the
iterations; a recovery-code backup restores with the code and refuses a password (and the reverse);
a wrong password changes nothing; restore merges without duplicates and renames a name conflict;
a corrupted byte fails safely with nothing restored; a 120-page document restores with bounded heap;
the last-backup timestamp drives the reminder. Vault merging: entries are re-encrypted into an
existing Vault (both keys), a locked Vault is skipped with the message and merged on a second
restore, a tampered entry fails its digest check without publishing anything, and a 4 MB manifest
restores with bounded heap.

## Device QA script (Backup & restore)

1. Me -> Backup & restore shows "No backup yet", Back up now, Restore from backup, the reminder
   checkbox and the no-internet note.
2. Back up now -> "Use the Vault recovery code" -> type the code -> Continue. Expected: the sheet
   closes, "Encrypting the backup…", then "Backup ready" with a size and Save to device / Share.
3. Save to device: pick a folder in the system picker. Expected: "Backup saved", and the screen says
   "Last backup: today". The file is `ScanWhirl-<date>.swbackup`.
4. Back up now -> "Use a backup password": 9 characters is refused with "Use at least 10
   characters"; 12 characters works and the strength meter moves.
5. Share: the chooser lists Google Drive (when installed). Selecting it opens Drive with the file
   attached; nothing is uploaded by ScanWhirl itself.
6. Restore on the same phone: Restore from backup -> pick the file -> the password/code sheet
   appears (the one used to create it) -> wrong password: "Wrong password or recovery code. Nothing
   was changed."; correct: "Backup restored", with document and Vault counts.
7. Restore onto a phone that already has documents and a Vault (set up): the Vault unlock screen
   appears with the reason; unlock -> Vault documents are re-encrypted into it, duplicates are
   skipped, a same-name document becomes "<name> (restored)". Cancel the unlock -> everything else
   restores and the dialog says the Vault files were not restored.
8. Airplane mode throughout: every step above works (no network permission).
