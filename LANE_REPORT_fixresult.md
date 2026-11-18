# LANE_REPORT_fixresult

Lane `lane/fixresult`. Bugs H1, H2, H7 (qa/hunt/BUGS_HUNT.md, found on the phone with ScanWhirl
1.2.2) and N2 (found by the lead on the screen map). Tests first (red, then green) for every item.

## Done

### H1 (Med) Open after "Save to Documents" did nothing
- Cause: `SavedSnackbar.showExport` opened the export through `PdfOpener.open`, which builds an
  ACTION_VIEW and then wraps it in `Intent.createChooser`. A chooser resolves even when no app can
  handle the type, so nothing happened (no ACTION_VIEW in logcat, only ACTION_CHOOSER); the
  tester's next tap landed on the screen's own Save button, which re-ran the save and produced the
  second "Already saved, unchanged" bar (screen map 013 -> 014).
- Fix (`app/src/main/java/com/scanwhirl/app/util/SavedSnackbar.kt`): new `openExported` asks
  `PackageManager.queryIntentActivities` first, then starts the viewer directly with the file's own
  content/MediaStore URI (or a FileProvider URI for a `file://` export), `FLAG_GRANT_READ_URI_PERMISSION`
  and the URI in the clip data. No viewer -> toast "No app on this phone can open this file".
- Also: the bar's stock (hidden) message row is now GONE and the custom action row keeps the bottom
  system-bar inset, so Open/Undo sit above the navigation/gesture area instead of in the screen
  corner over the Save button.

### H2 (Med) Undo after "Save to Documents" did not delete the export
- Fix: Undo still deletes exactly the file this save created, by its own URI (`DownloadSaver.undoSave`
  rechecks the fingerprint before deleting), and now confirms in words: "Removed from Documents: <name>";
  "nothing was created" and "kept, it changed" have their own honest messages. The snackbar row is
  also clear of the navigation inset (above), which is what made the phone tap miss the button.
- Files: `SavedSnackbar.kt`, strings in `values/strings_fixresult.xml` + `values-hi/strings_fixresult.xml`.
- `store/AutoExport.kt` was NOT changed: it returns a message string to callers in files this lane
  does not own (ScanReviewActivity, MainActivity), and the copy is already recorded through
  `repo.addExport`, so `undo`-by-file needs no change there.

### H7 (Med) "Show in library" after an export
- Choice: **hide** the button for export results (not "Open folder"). Reason: those results live in
  the app's own output folder (`cache/documents/tools/...`) and only reach Documents/ScanWhirl when
  the user taps Save, so "Open folder" would open a folder where the file is not either. A dead
  button is exactly the bug class we are fixing, so the smaller honest change is to drop it.
- File: `app/src/main/java/com/scanwhirl/app/ui/tools/ToolResultActivity.kt` (one condition:
  `docIds.isEmpty() && files.isNotEmpty()` -> `GONE`). Document results (split, compress, merge, ...)
  keep the button and its reveal-the-document behaviour. The ID-card Keep/Discard part is untouched.

### N2 (Med) To Word failed when the document had no OCR text
- Fix: `ScanRepository.exportWord` (the To Word conversion engine,
  `app/src/main/java/com/scanwhirl/app/store/ScanRepository.kt`) now reads the pages that have no
  stored text with the app's existing on-device recognition
  (`OcrEngine.recognizeFile(page, OcrLanguageSettings.get(context))`, the same call the Text screen
  and `OcrIndexer` use), stores each result through `OcrStore.putPage` (so the Text screen shows it
  and no second pass is needed) and then converts. Pages the recognizer cannot read stay "not read"
  and the export still fails with the same honest message when nothing can be read at all.
- Progress: the run happens inside `ToolSetupActivity.execute`, whose "Working." overlay stays up
  for the whole conversion including the recognition (no ToolSetupActivity change needed or made).
- Follow-up for the tools lane (NOT edited, out of scope): the To Word options note says
  "No recognised text yet. Open the document and read it first." when the selected document has no
  text (`ToolSetupActivity.buildWordOption` + `strings_tools.xml:tools_setup_no_text`). It is now
  misleading and should say the pages are read on this phone when you continue (To Excel already
  recognises on the fly).

### Not changed / decided by others
- `OcrEditorActivity.kt`, `ToolSetupActivity.kt`, `PageViewerActivity.kt`, `ProtectSheet.kt`,
  `LibraryScreen.kt`, `VaultScreen.kt`, `SettingsActivity.kt`, manifests, gradle: untouched.
- Android 9 and older: an export is a `file://` file in public Documents and the app's FileProvider
  paths (`res/xml/file_paths.xml`) do not cover that folder, so another app cannot be handed the
  file. Open says "No app on this phone can open this file" there. A real fix needs
  `<external-path ... path="Documents/ScanWhirl/"/>` in `file_paths.xml` (a resource owned by the
  release lane, hence not touched here).
- Batch results (`BatchActivity`) also show "Show in library" for a mixed run; not in this lane's
  file scope.

## Tests added (all new files, `*FixResult*Test.kt`; each was red before its fix)
- `app/src/test/java/com/scanwhirl/app/ui/ExportActionsFixResultTest.kt` (H1/H2, 5 tests): Open hands
  the exported row URI to a viewer with read permission; Open with no installed viewer starts
  nothing and says "No app on this phone can open this file"; Undo deletes exactly the exported row
  (an unrelated Documents file survives) and confirms "Removed from Documents"; the action row keeps
  a 96 px bottom inset and the empty stock row is GONE; end-to-end on the Compress/Protect result
  screen (Undo deletes, the next save's Open opens and does not save again).
- `app/src/test/java/com/scanwhirl/app/ui/tools/ExportResultLibraryFixResultTest.kt` (H7, 3 tests):
  a .docx/ZIP result has no Show in library; Open/Share/Save/Undo stay visible; a document result
  keeps Show in library and still opens the shell.
- `app/src/test/java/com/scanwhirl/app/store/WordNeedsTextFixResultTest.kt` (N2, 3 tests): a document
  with no text is recognised and converted (both pages, text stored for the Text screen); a page the
  user already has is never re-read and is not overwritten; when nothing can be read the export
  fails with the honest message and writes nothing.
- No existing test was weakened or removed.

## Gate
- Red runs seen before each fix (e.g. `FAIL ... H1: Open must be an ACTION_VIEW expected VIEW but was
  CHOOSER`, `H2: Undo must delete the exported row`, `H7 ... Show in library must be hidden`,
  `N2 ... Read the text first`).
- Last full gate: `GATE PASS fixresult tests=1671 failures=0 skipped=0 lint=[skipped]`
  (`powershell -File C:\Users\Cybium\Desktop\ScanWhirl-HQ\current\tools\gate.ps1 -Lane fixresult`,
  cloud run 44207295423).

## Commits (lane/fixresult)
- 9582ce0 fixresult: save snackbar Open/Undo (H1/H2), hide Show in library for exports (H7), To Word reads pages first (N2)
- e04bc93 fixresult: result-screen Open/Undo test (H1/H2), toast race in undo test

## For the lead to check on the device
1. Viewer and Compress/Protect result: Save to Documents, then Open (a PDF viewer must open the file
   in Documents/ScanWhirl) and Undo (the file must disappear from Files, with "Removed from
   Documents").
2. To Word on a document that was never read: Continue should show "Working." a bit longer, then
   the .docx must contain the recognised text, and Text must show the same text afterwards.
3. To Word options note (see N2 follow-up) still says "Open the document and read it first".
