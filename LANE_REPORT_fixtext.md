# Lane fixtext — report

Device bug fixes from `qa/hunt/BUGS_HUNT.md` (found on a real phone, ScanWhirl 1.2.2).
Branch `lane/fixtext`. Every item has a Robolectric test written first (red), then the fix (green);
each item was committed after its own green run.

## Done

| Item | What changed | Test |
|---|---|---|
| H5 + H6 (High) | `OcrEditorActivity` document mode: "Save .txt" now really writes a UTF-8 `"<doc name> - text.txt"` to Documents/ScanWhirl through the existing export helper `com.scanwhirl.app.util.DownloadSaver.saveText` (MediaStore on API 29+, the existing legacy transaction before that; no library, no new permission). The confirmation/toast names the file. The corrected OCR text is stored on the document first (search keeps working), and the "edited" marker is cleared only after the `.txt` write succeeded; on failure an error is shown and the edits stay in the editor. The Unsaved-changes sheet's "Save .txt" saves first, then closes. Storage permission (pre-Android-10 only) is now requested only when the OS refuses the write, so a permission prompt can never lose an edit. | `OcrTextFileSaveFixTextTest` (3) |
| H3 (Med) | `ProtectSheet`: when the two passwords differ, an inline error "Passwords don't match" appears under the confirm field (`EditText.error`, so no layout change) and nothing is submitted; the error clears as soon as they match. Buttons keep their existing disabled-until-match rule (pinned by `ProtectSheetTest`). | `ProtectSheetFixTextTest` (3) |
| H4 (Low) | `ProtectSheet`: the sheet window now asks for `SOFT_INPUT_ADJUST_RESIZE` (it was `ADJUST_NOTHING`) in addition to the existing IME-inset bottom padding and scroll-to-focus, so the action buttons stay above the keyboard on every API level. | `ProtectSheetFixTextTest` |
| H13 (Low) | Per the lead's correction: the "Choose at least two documents" / "Type page numbers..." messages already existed. Change is only the affordance: Merge's Continue is now visibly disabled (greyed 0.38 alpha, not clickable) until two documents are selected; other tools keep their existing validation. No second message added. | `ToolSetupContinueFixTextTest` (2) |
| H8 (Low) | `LibraryScreen`: tapping the "All" chip while everything is already shown now opens the existing category filter choice (All categories + the five categories with counts, via `SwSheet`), and a pick filters the library; tapping All again with a filter active still clears it. All chip is never a dead button. | `HomeAllChipFixTextTest` |
| H10 (Low) | `VaultScreen`: refreshes its real state on attach and whenever the tab becomes visible again (`onVisibilityAggregated`, `onWindowVisibilityChanged`), so returning from VaultActivity after creating the Vault no longer shows "Not set up yet" until relaunch. Still shows the document count when unlocked. | `VaultScreenFixTextTest` (2) |
| H11 (Low) | `SettingsActivity`: the Accent row was already non-clickable/non-focusable with a muted alpha and no ripple; the remaining tap affordance (chevron) is removed. The "coming soon" subtitle stays. | `SettingsAccentFixTextTest` |
| N1 (Med, lead follow-up) | `OcrEditorActivity`: a page now opens with the caret at the first character (`setSelection(0)` instead of the end), on first open and after switching pages, and the activity asks for no keyboard on launch (`SOFT_INPUT_STATE_HIDDEN`). | `OcrTextTopFixTextTest` (2) |

## Not done / not a bug

- **H9 dropped** per the lead's correction: the page editor already shows "Page deleted · Undo" and keeps the change as a draft (Discard / Replace / Save copy). `PageViewerActivity.kt` was not changed and no test was added.
- H3/H4/H10/H13 partly existed in this tree already (1.2.3 dev is ahead of the 1.2.2 device build); the fixes above are the remaining, provable gaps.

## Notes for the lead

- Reused export helper: `com.scanwhirl.app.util.DownloadSaver.saveText(context, text, displayName)`
  (`util/DownloadSaver.kt`) — it already existed with the M06 transaction, so **no change was needed
  there**; `OcrEditorActivity` now calls it in document mode too (it was only used for temporary text).
- One behaviour consequence, deliberate: when the Unsaved-changes "Save .txt" is used and the `.txt`
  write genuinely fails, the error is shown and the sheet still closes, because the document's OCR
  text is already stored (the existing data-safety test `OcrEditorSaveFailureTest` requires the
  sheet to close after a successful document save). Edits are never lost in that case; the failure
  is reported.
- No layout XML changed: the Protect inline error uses `EditText.error`, and the new strings live in
  `res/values/strings_fixtext.xml` + `res/values-hi/strings_fixtext.xml` (Hindi values start with
  `<!-- review -->`).
- Files touched (all in scope): `ui/OcrEditorActivity.kt`, `ui/protect/ProtectSheet.kt`,
  `ui/tools/ToolSetupActivity.kt`, `ui/shell/LibraryScreen.kt`, `ui/shell/VaultScreen.kt`,
  `ui/SettingsActivity.kt`, the two `strings_fixtext.xml` files, and the new `*FixText*Test.kt` files.
  Nothing else was needed.
- Device checks worth doing: Text > type > Save .txt (file named "<doc> - text.txt" in
  Documents/ScanWhirl, confirmation names it, "edited" gone); Text > Back > Save .txt (file written,
  editor closes); re-open Text (starts at the first line, no keyboard); Protect PDF with two different
  passwords (inline "Passwords don't match"); Protect PDF with the keyboard up (both buttons tappable
  above it); Merge with one document (Continue greyed); Home "All" chip (category sheet); Vault setup >
  Back (tab shows Locked/Unlocked + count); Settings > Accent (no chevron, not tappable).

## Tests added

- `app/src/test/java/com/scanwhirl/app/ui/OcrTextFileSaveFixTextTest.kt` (H5/H6)
- `app/src/test/java/com/scanwhirl/app/ui/OcrTextTopFixTextTest.kt` (N1)
- `app/src/test/java/com/scanwhirl/app/ui/protect/ProtectSheetFixTextTest.kt` (H3/H4)
- `app/src/test/java/com/scanwhirl/app/ui/tools/ToolSetupContinueFixTextTest.kt` (H13)
- `app/src/test/java/com/scanwhirl/app/ui/shell/HomeAllChipFixTextTest.kt` (H8)
- `app/src/test/java/com/scanwhirl/app/ui/shell/VaultScreenFixTextTest.kt` (H10)
- `app/src/test/java/com/scanwhirl/app/ui/SettingsAccentFixTextTest.kt` (H11)

## Gate

- Last full gate (cloud): `GATE PASS fixtext tests=1661 failures=0 skipped=0`
  (`powershell -File C:\Users\Cybium\Desktop\ScanWhirl-HQ\current\tools\gate.ps1 -Lane fixtext`)

## Commits (lane/fixtext)

- `5f60b70` fixtext: Text Save .txt writes the file (H5/H6), page text opens at the top (N1)
- `498e65e` fixtext: Protect sheet explains a password mismatch and resizes for the keyboard (H3/H4)
- `cacecce` fixtext: Merge Continue is visibly disabled until two documents are picked (H13)
- `d249adc` fixtext: Home All chip opens the category filter (H8)
- `6695f48` fixtext: Vault tab re-reads its state when shown again (H10)
- `8b08b20` fixtext: Accent row drops the chevron (not tappable yet) and keeps the note (H11)
- `8bb853f` fixtext: Text save keeps document edits permission-free and reports a failed txt honestly
