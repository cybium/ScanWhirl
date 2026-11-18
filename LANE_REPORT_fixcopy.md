# LANE_REPORT_fixcopy — S1 recovery code off the clipboard + wording (C)

Lane: `lane/fixcopy` (base integration 084fb79). Unowned files: none touched. Not merged, not pushed.

## Done

### S1 (High, security) — the Vault recovery code never goes to the clipboard
- `VaultActivity.showRecoveryCode`: the "Copy code" action is gone. The one action there is now
  **"Save as file"** (`ACTION_CREATE_DOCUMENT`, `text/plain`, suggested name
  `ScanWhirl Vault recovery code.txt`, via `ActivityResultContracts.CreateDocument`). No storage
  permission, no new library. The file holds **only** the formatted code and one instruction line
  (the existing warning text) plus a final newline.
- `copyRecoveryCode()` and the `ClipboardPrivacy` import are removed from VaultActivity;
  `ClipboardPrivacy` itself is untouched (QR/text/password callers and its own test still use it).
- The warning "Anyone with this code can open your backup. Keep it offline." stays on the sheet and
  is now always visible instead of appearing only after a tap.
- "I wrote it down" (`vault_recovery_saved`, primary) → type 4 characters → finish is unchanged.
- New vault strings: `vault_recovery_save_file`, `vault_recovery_file_name`,
  `vault_recovery_file_done`, `vault_recovery_file_failed` (+ Hindi, `<!-- review -->`).
  Removed the now-unused `vault_recovery_copy` / `vault_copy_done` (EN + HI); no code referenced them.
- The control reuses the existing id `vaultRecoveryCopy` (ids_vault.xml is not a lane-owned file),
  so no id resource was added.

### C — wording (EN + HI, values/strings.xml + values-hi/strings.xml)
- `compress_level_low/medium/high/ultra` = "Best quality" / "Balanced" / "Smaller" / "Smallest".
  Hindi: सबसे अच्छी क्वालिटी / संतुलित / छोटा / सबसे छोटा.
- `compress_target_label` = "Custom size (optional)" (कस्टम आकार (ज़रूरी नहीं)).
- `settings_searchable_pdf_summary` = "Adds invisible text so other apps can search your PDFs."
- `trash_header_note` = "Restore brings back every page."
- `settings_auto_enhance_summary` — **wording deviates from the brief by two words**:
  shipped "Cleans up scans. Off keeps the photo as taken." instead of
  "Cleans up each scan automatically. Off keeps the photo as taken." Reason: the full sentence
  fails the existing readability guard `TextFitTest > settings screen text fits` at w320dp /
  font scale 1.3 (the summary column is 134dp wide; the prescribed sentence's second line measured
  271px against 268px available, because the break lands exactly on the limit and the trailing
  space tips it over). Measured candidates at that rig: prescribed 271px (fail), "Cleans up scans
  automatically. Off keeps the original." 216px, "Cleans up scans. Off keeps the photo as taken."
  258px (pass, chosen). The meaning ("clean up automatically, off keeps the photo as taken") and
  the toggle title "Auto enhance scans" are kept. Hindi mirrors it: "स्कैन साफ़ हो जाते हैं। बंद रखने
  पर फोटो वैसी ही रहती है जैसी खींची गई थी।" The lead may prefer another split, but any longer
  sentence must be re-run against TextFitTest.
- All 8 names are the only entries changed in `values/strings.xml` / `values-hi/strings.xml`.
  Every Hindi value starts with `<!-- review -->`; none of the 8 (EN or HI) contains `—` or `–`.
- Hindi hygiene/parity guard tests pass (`HindiStringsHygieneTest`, `StringsParityTest`).

## Compression mapping confirmed (code first)
`tools/CompressFlow.kt`: `Level(compress_level_low, 80, null, 200f)` →
`Level(low, 80, no max dimension, 200 dpi)` is the **largest file / best quality**; then 65/2000/150,
50/1600/130, 38/1200/110. So "Best quality" = largest file, "Smallest" = smallest file; the order and
logic are unchanged, only labels. `FixCopyTest` now asserts this order and that quality strictly
decreases and that only `low` keeps the full image size, so a future relabel cannot reverse the meaning.

## Tests
New `app/src/test/java/com/scanwhirl/app/FixCopyTest.kt` (4 tests):
1. recovery screen shows "Save as file", has no copy action, leaves a pre-set clipboard sentinel
   untouched, and launches `ACTION_CREATE_DOCUMENT` with `text/plain` + suggested txt name;
2. the chosen file is written as exactly `<code>\n<warning>\n` (delivered through
   `ShadowActivity.receiveResult` + a registered output stream);
3. compression level order/quality mapping + labels (source + strings);
4. the 8 critique wordings in EN and HI (review marker, no long dash, no "Google"/"Hides").
No existing test pinned the old copy behaviour. `TextFitTest` (not owned by this lane) is the only
existing test affected: it went red on the prescribed auto-enhance sentence and is green again with
the shortened one — no test was weakened or ignored.

## Gate
- Red first: `-Tests com.scanwhirl.app.FixCopyTest` → 4 failures (old labels/copy).
- Green: `-Tests FixCopyTest` → `GATE PASS fixcopy tests=4`; then with `VaultActivityTest`,
  `HindiStringsHygieneTest`, `StringsParityTest` → `GATE PASS fixcopy tests=23`;
  then `FixCopyTest + TextFitTest` → `GATE PASS fixcopy tests=11`.
- Full gate: `GATE PASS fixcopy tests=1691 failures=0 skipped=0 lint=[skipped]` (cloud run 44235043564).

## For the lead / device check
- On the phone: open Vault → Recovery code → "Save as file" should open the system picker, write a
  `.txt` with one code line + one warning line, and the keyboard clipboard history should never offer
  the code again. Nothing to clear after 60 s because nothing is copied.
- Optional cosmetic: `ids_vault.xml` still calls the save control `vaultRecoveryCopy`; rename it in a
  lane that owns that file if you want the id to match the action.
