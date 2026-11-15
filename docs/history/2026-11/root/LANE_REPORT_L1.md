# L1 — Shell & look: lane report

Branch `lane/L1-shell`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L1`.

## Latest session (gallery auto-crop, motion kit, QA run3/3b)

### Gallery auto-crop (shared API for L3/L4)
- `launchGalleryCrop(activity: AppCompatActivity, maxPages: Int = 30, onResult: (List<Uri>) -> Unit,
  onFallback: ((List<Uri>) -> Unit)? = null)` in `tools\ScanLauncher.kt`.
  Opens the ML Kit scanner with gallery import enabled (corner detection + perspective crop + rotate), returns the
  cropped page URIs in order. When the scanner module is unavailable it opens the photo picker and returns the raw
  photos to `onFallback` (or `onResult`) for the caller's manual CropView path. BaseActivity carries the result bridge
  so every v2 screen can use the 3-argument form: `launchGalleryCrop(activity, maxPages, onResult)`.
- Home/Tools "Photos" now offers "Auto-crop pages (recommended)" / "Keep photos as they are", remembered in Prefs.
- Photo import now goes through one shell entry (`importPhotos`): 1..N picked photos always open Review with those
  pages (tests with 1 and 3 URIs), an empty pick says "No photos were added". HEIC/HEIF gallery photos are decoded
  and re-encoded by `ImageTools.normalizeGalleryPhoto`, so modern phone photos no longer fail import.

### Motion kit (74_motion)
- `SwMotion` (SHORT/MEDIUM/LONG, animator-scale aware, 0 = no animation, press, tab fade-through, list stagger,
  drill-in transitions, success overlay with rings + check, brand breathe/rotate, placeholder pulse).
- `SwHaptics` (tick/confirm/reject through the view path, no permission).
- `SwProgress` kit progress sheet (whirl mark, determinate "Page k of N", cancel) replaces every `SwDialog.progress`.
- `SwEmptyState` (brand art at 30%, one line, optional action).
- Wired: tab fade + drill-in (BaseActivity), Home brand turn + scan-disc breathe, DocCard/SwChip/ToolTile/SwSheet row
  press + ripples, long-press selection tick, sheet option stagger, list stagger, ToolResult success moment, Vault
  unlock confirm / lock tick, photo copy feedback.
- Tests: `SwMotionTest` (scale 0 -> 0 durations, press animator, immediate tab swap, progress sheet, empty state,
  and a source scan proving no `SwDialog.progress` remains).

### QA run3 / run3b
- Search: a dedicated `swNoMatches` line ("No matches for “x”") now shows whenever a filter/search has no results
  (also when the library is empty); the old empty-state child juggling is gone.
- Search mode collapses the create row, quick tools, folders, chips and headers while the field has focus/text; the
  clear (X) button restores them. Home search no longer restores its text on a cold start.
- Empty library: chrome collapses and the empty art/copy fit above the nav/disc.
- Document chooser wraps its content for 1-4 documents and caps at 80% screen height otherwise; Cancel sits below.
- Text screen: the four chips are on two rows so To Word (+PRO) is always visible; Save .txt always answers (also
  when there is nothing to save); To Word starts the real tool.
- Photos to PDF / Home Photos: one import path, Review always opens (see above).
- Page tab: `ImageTools.decodeOriented` applies EXIF before drawing (landscape photos upright, boxes aligned);
  tested with a 90°-rotated JPEG.

## Gate
- Targeted runs all green: `util.*` 402, `store.*` 289, `ui.shell.*` 37, `ui.tools.*` 32, `ui.qr.*` 35, `ui.vault.*` 19,
  `UiSmokeTest` 77, `PhotoImportTest` 3, `ShellQaFixesTest` 13, `SwMotionTest` 6, `GalleryCropTest` 5.
- **Full unfiltered suite currently fails with `tests: total=0`** (no test XML, no failure lines) while every subset
  above passes on the same commit; this started after the last merge added more tests and looks like the Gradle test
  worker dying (default worker heap) rather than a test failure. Suggest on integration:
  `testOptions.unitTests.all { it.maxHeapSize = "2g" }` (lead owns build.gradle.kts).

## Earlier work (in the same branch)
- v2 kit + shell (Library/Tools/Pro/Me/Vault), SwSheet sweep, Manrope, reader dock, Text screen, Material Symbols,
  brand mark, Pro gating, folders & tags, batch rename, prior QA rounds 1-2.

## Done this session (after the folders/tags pass)

### QA round (build 435e0f3, run2)
1. Empty library: removed the stray full-width cyan "Scan" bar from the empty state (the nav disc is the scan CTA);
   the empty state now ends at its body line, fully above the nav.
2. Tools search: a clear (X) button appears while there is a query; re-entering the Tools tab from the nav clears the
   query and shows the full catalogue.
3. Tools search ranking: title starts-with → title word starts-with → keyword/id word starts-with → substring, ties in
   catalogue order. Search now uses the localised titles (`ToolRegistry.search(context, query)`), so "read" lists
   Read text first, "pdf" lists PDF to images first, "sign" lists Sign, "small" lists Compress.
4. Trash: the "Empty Trash" menu item is hidden (and disabled) whenever the Trash is empty — never a silent no-op.

### Wave 3 preparation: tests still driving the legacy `MainActivity`
QA confirmed the legacy Home is unreachable for users. Tests that still launch/assert `MainActivity` (27 files):
`BrandIdentityTest`, `CategoryPickerSheetTest`, `ChangePasswordSheetTest`, `DirectScanSaveTest`,
`FreeLegacyVaultEntryPointTest`, `HomeCategoryActionsTest`, `HomeFontScaleTest`, `HomeKeyboardFocusTest`,
`HomeM06Test`, `HomeScreenTest`, `HomeSlice2Test`, `ImageImportPipelineTest`, `ImportSheetTest`, `LockUiTest`,
`LongImageTest`, `OcrGalleryImageFlowTest`, `ProtectHostActivity`, `QuickSortTest`, `RetrievalSlice5Test`,
`SaveTransactionTest`, `SelectionBarTest`, `StatusLineScreenTest`, `TextFitTest`, `UiSmokeTest`,
`VaultPasswordsWiringTest` (plus shared helpers used by them).

Migration plan to `ShellActivity`:
- Phase 1 (mechanical): give the shell the seams these tests need — a public `ShellActivity.showTab`, `library`,
  `toolsHub`, `showPro`, `showDocActions`, and stable ids already exist; replace `scanList` → `swDocList`,
  `statusLine`/`headerCount` → the shell equivalents, `dockRoot` → `swShellNav`/`swTabContent`.
- Phase 2 (feature parity): port the behaviours the legacy tests pin that the shell lacks — multi-select bar on Home
  (already in the library selection mode), status header line, greeting, wallpaper, quick-sort entry, and the import
  pipeline state copy. Keep the engine-level assertions (ScanLauncher, OcrIndexer, repository) unchanged.
- Phase 3: rewrite each test class to launch `ShellActivity`, keeping data-safety assertions intact and marking UI
  shape changes with `// v2 UI: <mockup>`.
- Phase 4: when no test references `MainActivity`, delete `MainActivity`, `activity_main.xml` and its exclusive
  adapters/drawables, and run the full gate + lint.

## Items 11-15 (previous round)

### Merge
- `git merge integration` clean; L5's `DraftStore`, Organize grid and page-grid draft code kept intact.

### Items 11-15
11. The kit document chooser now uses compact `DocCard` rows (`asCompact()`: 40×52 thumbnail, one-line name and
    "N pages · size · date", lock badge, no category/buttons) inside a scrollable sheet, with a search field when
    there are more than 8 documents. Every document tool uses it.
12. Tools hub tags: the PRO/Soon tag sits in the tile's top-right corner; tiles reserve a 16dp top strip and grew to
    96dp so the tag never intersects the icon and two-line labels stay fully visible. New test walks all 44 tiles and
    asserts `Rect.intersects(tag, icon) == false`.
13. Vault tab: the pill is the same "Offline · No ads" as every other tab; a live status line (`VaultSession` +
    `VaultStore`: "Not set up yet" / "Locked" / "N documents · unlocked") sits under the emblem, plus an AES-256 /
    locks-itself facts card, so the tab is no longer half empty. Refreshed on every open of the tab.
14. Library list has its own 96dp bottom padding (`swDocList`) on top of the shell ScrollView's 112dp, so the last
    card clears the nav bar and scan disc when scrolled to the end (test asserts the padding).
15. Moving a document to Trash now confirms first, from the library (`confirmTrash`) and from the document screen:
    "Move “name” to Trash?" / "You can restore it for 30 days. Nothing is uploaded." (test updated to confirm the
    sheet). Trash's "Empty Trash" already requires the SwSheet confirm ("Delete N documents for good?") and is
    enabled only when the Trash has items.

## Earlier in this lane (still in place)
- Folders & tags (Pro): folder/tag meta lines, folder chips with rename/delete, tag sheets, selection mode, tag
  search, Free → Pro gating.
- Batch rename (Pro): `{name} {n} {date}`, live preview, apply + Undo snackbar.
- Phone QA 1-10: search (name/OCR/tag + empty text), persisted sort, IME hides nav, Searchable PDF tool, one kit
  chooser, saved snackbar above the dock, opaque sheets, Text screen chips/Tidy/undo/To Word, category rail padding,
  More-sheet icons + "Protect PDF".

## Tests
- `ShellQaFixesTest` now also covers: compact chooser + search past 8 docs, tag/icon bounds for every tile, Vault
  live status + shared pill, list bottom padding. `DeleteExportsTest` confirms the new Trash sheet.
- Full gate: `GATE PASS L1 tests=1135 failures=0 skipped=0 lint=[lint skipped (lead runs it at merge)]`.
- QA round: `GATE PASS L1 tests=1178 failures=0 skipped=0 lint=[lint skipped (lead runs it at merge)]`.

## Commits
- `b475148` phone-review pass (icons, brand, quick/tools layout, Pro gating, Text chips)
- `2fd4fbd` folders & tags, batch rename, phone-QA fixes 1-10
- (this session) QA 11-15: compact chooser, tag bounds, Vault status, list padding, Trash confirms
- (latest) QA round 2: empty-state stray bar, tools clear + re-entry reset, title-first search ranking, empty-Trash menu hidden; legacy MainActivity test inventory + migration plan


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
