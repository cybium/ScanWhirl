# LANE_REPORT_L4 — Tools engine (QA batch: ID card choice, names, library, locks)

Session: 2026-11-15. Branch `lane/L4-tools`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L4`.
Merged `integration` (fast-forward) and kept the lead's changes (slideshow icon, `SW_HOSTCHECK_DIR`,
L1 count tests from the catalogue).

## Done this batch (phone-QA feedback)

### 1. ID card starts with a choice, not the camera

- Opening the tool now always shows the `SwSheet` **"Front side"** with **Scan with camera** and
  **Pick from gallery**; the camera opens only when asked. The back step has the same two choices
  plus a quiet **Front only** action. Works without a camera angle.

### 2. Stacking-free output names — `util/OutputNames.kt` (new)

- `OutputNames.forTool(baseName, toolLabel, existingNames)` strips any suffix a previous tool added
  (`- Compressed`, `(pages 2-3)`, `(signed)`, Hindi labels too), then appends ` - <label>`:
  `Document 1 (page 1) (compressed) (signed) (watermarked)` becomes `Document 1 - Watermarked`.
  A taken name gets ` (2)`, ` (3)` (case-insensitive).
- Used by the repository's tool outputs: **Split** (`Contract - Pages 1-2` / `Contract - Page 4`),
  **Extract**, **Compress** (both levels and the target-size path), **Remove metadata**, and the
  default **Duplicate** name. The API is pure and ready for L5's stamp tools.
- Old name assertions in `PageSelectTest`, `ScanRepositoryTest`, `ToolsEngineTest` were updated to
  the new rule.

### 3. "Show in library" opens the Library tab and reveals the document

- `ToolResultActivity` sends the new document id to `ShellActivity`; the shell switches to the
  Library tab and `LibraryScreen.revealDocument` highlights that card (cyan border), requests focus
  and scrolls it into view. The highlight survives the shell's `onResume` reload.
- Small L1-file edits were required: `ShellActivity` (intent extra + `onNewIntent`) and
  `LibraryScreen` (reveal/highlight). No other shell behaviour changed.

### 4. Locked documents are no longer a dead end

- A locked row in the shared setup chooser shows a gold lock and its "N pages" line; tapping it opens
  **L3's `UnlockSheet`**. A correct password unlocks the document in place (`repo.unlockPdf`), the
  list refreshes and that document becomes the selection, so the tool continues. A wrong password
  shows the tries-left line and changes nothing.

### 5. Count plurals (kept from the previous message)

- `<plurals>` for every page/file/image/document count in setup lists, split/extract previews, the
  long-image note and the result summary: **1 page** / **2 pages**, **1 file** / **2 files**,
  **1 image** / **2 images**. `ToolsPluralsTest` pins the strings and the setup source list.

### Earlier this session (already committed)

- **Business card** (Pro, Create, READY): ML Kit single-page capture + gallery, on-device OCR, pure
  `ContactParser` (name, company, job, Indian/international phones, emails, website, address, OCR
  noise, Hindi), editable kit form; **Save to contacts** (`ACTION_INSERT`, no permission),
  **Share as vCard** (escaped vCard 3.0 via FileProvider), **Save card image** (document + Result).
  Cancel creates nothing.
- **Copy pass**: no long dash in user-visible strings; English copy <= 90 characters.

## Tests added this batch

- **`util/OutputNamesTest` (5)** — labels, no stacking (English and Hindi suffixes), uniqueness
  `(2)`/`(4)`, case-insensitive, Hindi names/labels, odd input.
- **`ui/tools/ShowInLibraryTest` (3)** — result sends the document id to the shell; the Library tab
  shows the card highlighted (and not the Tools tab); the highlight survives a reload.
- **`ui/tools/LockedSourceTest` (2)** — locked row has a lock icon, the unlock sheet opens, the right
  password unlocks and selects the document; a wrong password shows the error and stays locked.
- **`ToolsEngineTest` (+1)** — `compress` twice gives `Contract - Compressed` and
  `Contract - Compressed (2)`; splitting the compressed copy gives `Contract - Pages 1-2` and
  `... (2)` — no stacking.
- `IdCardFlowTest` updated for the choice sheet (camera/gallery, Front only, layout).

## Gate result (last full run)

```
tests: total=1126 failures=0 skipped=0
GATE PASS L4 tests=1126 lint=[lint skipped (lead runs it at merge)]
```

## Notes for the lead

- `ShellActivity` and `LibraryScreen` (L1 files) carry the small reveal change: one extra constant,
  `onNewIntent`, and a `revealDocument` highlight. Please keep both sides on the next merge.
- Locked sources still show the old "unlock first" message only if a tool is run against a document
  that became locked elsewhere; the chooser itself now unlocks in place.
- Phone checks worth doing: the ID card choice sheet on a dark card, a locked Split run, "Show in
  library" from Merge, and a second Compress to see ` (2)`.
- Business card still waits for the phone QA after this batch (as instructed).

## Commits

- `0808c52` L4: Business card (OCR form, contacts, vCard), count plurals, shorter tool copy
- `e270c63` L4: ID card camera choice, stacking-free output names, library reveal, unlock locked sources
- (this report) L4: report
