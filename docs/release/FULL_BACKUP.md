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
"SWFULLB1" | version(1) | wrap(1=password, 2=recovery code) | pbkdf2 iterations | salt | AES-GCM body
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
   - Vault entries are restored only when this phone's Vault is not set up yet (a different Vault
     key cannot be merged, and the UI says so instead of pretending);
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

## Tests (`FullBackupTest`, 8)

Round trip of library + Vault + signatures + settings; the header records the secret kind and the
iterations; a recovery-code backup restores with the code and refuses a password (and the reverse);
a wrong password changes nothing; restore merges without duplicates and renames a name conflict;
a corrupted byte fails safely with nothing restored; a 120-page document restores with bounded heap;
the last-backup timestamp drives the reminder.
