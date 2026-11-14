# L1 — Shell & look: lane report

Branch `lane/L1-shell`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L1`.
`integration` merged at the start of this session (L2/L3/L4/L5, Phone-QA build).

## Done this session

### Folders & tags (Pro, now READY)
- Metadata: `ScanDocument.folder: String?` + `tags: List<String>`, stored as two extra `meta.txt` lines
  (`folder=<name>`, `tags=<tag>\u001F…`) by `store/LibraryMeta`. Old documents (three fixed lines) read as no
  folder/no tags; `exports=` and unknown lines survive every write. Tags are trimmed, case-insensitively de-duped,
  comma-safe, ≤24 chars, ≤30 per document.
- Repository: `listFolders` (explicit empty folders survive), `createFolder`, `renameFolder` (renames the folder and
  every document in it), `deleteFolder` (documents move to All, never deleted), `setFolder`, `setTags`, `addTag`,
  `removeTag`, `listTags`.
- Library: a folder chip row above the list (All · folders with counts · + New folder); long-press a folder to
  rename or delete it; selecting a folder filters the list. Search now also matches tag names. Cards show up to two
  tags as `#tag` in the meta line.
- Selection mode: long-press a card → checks appear *only* then (selection badge, per-card Share/⋯ hide), action bar
  with count + Done + Move / Tags / Share / Rename / Delete. Move uses the kit options sheet; Tags is a multi-choice
  sheet with a New tag input; Delete confirms and moves to Trash; Share sends every selected PDF
  (`ACTION_SEND_MULTIPLE`).
- Free gating: New folder and Tags open the 06_pro screen (the shell's one `proGate`/`onProRequired` path).
- `ToolRegistry`: `folders` and `batch_rename` are READY now; the shell's `open()` sends them to the library (folder
  row / selection mode), never to a "Coming soon" sheet.

### Batch rename (Pro, now READY)
- `util/BatchRename`: `{name}`, `{n}` (1-based), `{date}` (yyyy-MM-dd), default `{name} ({n})`, one-line sanitising,
  blank result keeps the old name.
- Selection mode → Rename (2+): SwSheet with a pattern field and a live preview of the first 3 names; Apply renames
  all; a snackbar says "N documents renamed" with **Undo** that restores every old name.

### Phone-QA fixes
1. Search: matches names, OCR text and tags; an empty result shows "No matches for “x”" (and the empty-state
   children are restored for the first-run state).
2. "Newest ▾" is a real sort sheet: Newest / Oldest / Name A-Z / Name Z-A / Size, persisted in Prefs
   (`librarySort`) and reflected in the header label.
3. The shell's root inset listener hides the nav bar + scan disc while the IME is visible (content resizes, no
   mid-screen nav).
4. Searchable PDF is a real tool: kit document chooser → "Make searchable" sheet → `repo.makeSearchable(id)`
   rebuilds that PDF with its stored OCR text layer (or says the text has not been recognised yet). Never Settings.
5. Every document tool now uses the same kit chooser sheet (`showDocPicker` is an `SwSheet` options list).
6. Saved-result snackbar: 3-line message (no ellipsis) and anchored above the dock (`readerDockRoot`/dock parent).
7. SwSheet surface is now fully opaque ink (`#161D21`), no dock showing through.
8. Text screen: Copy all → snackbar "Copied"; Save .txt → snackbar with the full Documents path + Open; To Word
   runs L4's real tool (`ToolLaunch.start(context, "to_word")`), not an info sheet; the tidy actions stay on the
   current tab.
9. ScanReview category rail has 16dp end padding (last chip reachable); the caption has no long dash.
10. Document More sheet rows have 24dp icons (edit / folder / content_copy / compress / encrypted / shield_lock /
    delete in red) and the row is renamed "Protect PDF".

## Not done / next session
- QA11: the "Pick a document" chooser is still a text list (now a consistent kit sheet); compact DocCard rows +
  search for >8 documents is not built yet.
- QA13: the Vault tab now uses the same "Offline · No ads" pill; showing L2's live locked/unlocked + file count on
  the tab is still to do.
- QA14: the shell ScrollView has 112dp bottom padding; confirm on device that the last Library card clears the disc.
- QA15: single-document "Move to Trash" from the card ⋯ already asks first (`confirmTrash`); the Trash "Empty Trash"
  menu item was not re-checked in this pass.
- QA2 sort applies to the shell Library only (the legacy MainActivity list keeps its own sort).

## Tests added
- `LibraryOrganisationTest` (8): old meta round-trip, tag/folder encoding, folder rename/delete keeps documents,
  move-to-new-folder/All, checks only in selection mode after a long-press, tag search, Free → Pro for New folder
  and Tags, batch rename preview/apply/undo.
- `ShellQaFixesTest` (5): search by name/OCR/tag + empty state text, sort sheet + persistence, nav hides for IME,
  Searchable PDF builds the text layer through the chooser, sheet surface is opaque.
- Updated `OcrTextScreenTest` (To Word starts `ToolSetupActivity`).
- Full gate: `GATE PASS L1 tests=1102 failures=0 skipped=0 lint=[lint skipped (lead runs it at merge)]`.

## Commits
- `b475148` phone-review pass (icons, brand, quick/tools layout, Pro gating, Text chips)
- (this session) folders & tags + batch rename + phone-QA fixes
