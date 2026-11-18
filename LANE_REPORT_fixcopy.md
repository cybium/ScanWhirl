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
4. the 8 critique wordings in EN and HI (review marker, no long dash, no "Google"/"Hides");
5. the save control is 48 dp and focusable; the recovery sheet window is FLAG_SECURE;
6. a rotation while the picker is open still writes the code (retained in-memory holder, never in a Bundle);
7. process death while the picker is open: the fresh activity has no code, so the empty document is
   deleted (`DocumentsContract.deleteDocument` into a recording provider) and the "code was not saved"
   sheet is shown, with nothing written;
8. a cancelled picker writes nothing and shows no error;
9. a failing output stream shows the fixed failure message (no raw `e.message`) and does not crash.
Red proof for the rework (temporary local revert, then restored): tests 5-7 failed with 44 dp/not
focusable, no retained holder, and the silent `return` → `3 failed` before the fix, green after.
No existing test pinned the old copy behaviour. `TextFitTest` (not owned by this lane) is the only
existing test affected: it went red on the prescribed auto-enhance sentence and is green again with
the shortened one — no test was weakened or ignored.

## Rework after critic (CRITIC_fixcopy.md, 2026-11-18)
Defects fixed in `VaultActivity.kt` (+ vault strings):
- **D2 (Medium) — rotation / process death while the picker is open.** The pending code now lives in
  a `PendingRecoveryCode` holder kept only in memory and handed across configuration changes via
  `onRetainCustomNonConfigurationInstance()` (the "retained holder" the lead ruled for). It is still
  never in `savedInstanceState`, an Intent extra, prefs or any file except the one the user picks.
  Cancel (null Uri) stays silent and clears the holder. On a result with a Uri but no code (process
  death), the empty document the picker created is deleted (`DocumentsContract.deleteDocument`,
  `runCatching`) and a clear sheet says "The code was not saved. Tap Save as file again."
  (`vault_recovery_code_lost`, EN + HI).
- **Write failure:** the message is now a fixed string resource
  ("The code was not saved. Tap Save as file and pick another place.", `vault_recovery_file_failed`)
  instead of interpolating `e.message`; the exception goes to logcat (`Log.w`, no code in it), the
  half-written/empty document is deleted, and the app does not crash.
- **D8 — accessibility:** the save control is 48 dp tall and `isFocusable = true`, matching `optionRow()`.
- **Nits:** `vault_recovery_file_name` is `translatable="false"` (Hindi copy removed); the recovery
  sheet now calls `SwSheet.secure()` (verified: a dialog is its own window, the activity's
  FLAG_SECURE does not cover it; now asserted in the test). Note for the lead: the Vault's passcode
  and unlock sheets have the same pre-existing gap and are outside this lane's "recovery-code part"
  scope.
- Tests 5-9 added (see above), red-then-green; no other lane file touched.
- Rework gate lines: `-Tests FixCopyTest` → red `3 failed` on the temporary revert, then
  `GATE PASS fixcopy tests=9`; wider targeted run (FixCopyTest, VaultActivityTest,
  AccessibilityAuditTest, TextFitTest, HindiStringsHygieneTest, StringsParityTest) →
  `GATE PASS fixcopy tests=39`; full gate below.
- Known residual (pre-existing, outside D2): a rotation also dismisses the recovery sheet, so the
  "type 4 characters" confirmation can be skipped; the code is still written to the picked file.

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
