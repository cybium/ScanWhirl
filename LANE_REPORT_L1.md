# L1 — Shell & look: lane report

Branch `lane/L1-shell`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L1`.
`integration` merged at the start of this session (L2 Vault, L4 tools UI, L5 Edit & sign, lead's phone-review fixes).

## Done this session

### Merge
- `git merge integration` clean (no conflicts). Read `LANE_REPORT.md` rename: this file is the L1 report now.

### Tool catalogue icons (task 2)
- Added the official Material Symbols Rounded set (83 vectors, white-filled, 960 viewport) plus `docs/licenses/material-symbols-APACHE-2.0.txt`.
- Fixes: **Draw** -> `ic_sym_draw` (pen), **Highlight** -> `ic_sym_ink_highlighter`, **Page numbers** ->
  `format_list_numbered`, **Read text** -> `notes_lines`, **Hindi text** -> `language`, **Remove metadata** ->
  `visibility_off` (was the Vault shield), **Folders & tags** -> `folder_open`, **Insert pages** -> `note_add`.
  Every other registry icon was checked against its tool's meaning; no other mismatch.

### Vault tab (task 3)
- `VaultScreen` is the 04_vault entry state: shield emblem on the glass card, one line "Encrypted on this phone",
  **Open Vault** button that keeps L2's single `VaultActivity.intent(...)` launch. The "being built / Coming soon"
  copy is gone (`vault_tab_note` / `vault_tab_coming` removed, `vault_tab_encrypted` + new `vault_tab_open` added).

### Pro gating in the shell (task 4)
- One dispatch point: `ToolHost.launchTool(tool)` in `ui/kit/ToolRegistry.kt` — a Pro tool in a Free build calls
  `ToolHost.proGate` (default false; the shell's override opens the 06_pro screen). `ToolTile` (Tools hub + Home
  quick row) and the Library ID card all go through it. Test build default is Pro, so existing tests are unchanged.
- New `ShellProGateTest`: forced Free -> all 18 Pro tiles open Pro, the two Pro quick tools open Pro, a Free tool
  still runs; forced Pro -> `To Word` starts `ToolSetupActivity` and a Soon Pro tool keeps its Coming soon sheet.

### Text screen (08_text_ocr) phone pass (H1-H7)
- **H1** Action row is four kit **chips** (`SwChip`: 38dp, 12dp radius, glass + cyan hairline, 18dp cyan glyph,
  13sp bold): Copy all / Share text / Save .txt / To Word with a gold PRO pill, in one horizontally scrolling row.
- **H2** One **Tidy text** chip (`ic_sym_auto_fix_high`) opens an SwSheet with Join lines / Fix spaces / Remove
  gaps (icon + one short line each, ids `ocrTidyJoin/Spaces/Gaps`); the old three wide buttons are gone, spacing is
  12dp between blocks.
- **H3** Undo/Redo use the official `ic_sym_undo` / `ic_sym_redo`, sit on the word-count line at the left (Undo
  first), disabled at 38% alpha, and loading a page no longer pollutes the undo history.
- **H4** Language chip ("English", `ic_sym_language`) beside the Page/Text switch (a label: the recogniser is Latin
  only; there is no language setting to open yet).
- **H5** Footer is one line: "Read on this phone. Searchable." (`maxLines=1`).
- **H6** `OcrLineOverlay` draws the recognised line boxes (22% cyan fill, 1.5dp outline) from `repo.ocrGeometry`
  (`OcrStore.readGeo`), inside the fitCenter image rect; a tap highlights the box and jumps the Text tab to that
  line's text; missing geometry draws the image only.
- **H7** The Page tab now decodes with EXIF orientation (`ImageTools.decodeOriented`): a sideways landscape photo
  shows upright, fit to width; pages the user rotated are already re-encoded and unaffected.

### Phone review A-G
- **A** Copied all 83 official vectors over `ic_sym_*`; Me rows now Pro=`star_filled`, Settings=`settings`,
  Trash=`delete`, About=`info`, arrow `chevron_right`; nav is `folder_open/folder_filled`, `apps/apps_filled`,
  `shield_lock/shield_lock_filled`, `person/person_filled` (filled for the open tab, outline otherwise);
  search fields `search`; Protect `encrypted`; Draw/Highlight as above.
- **B** Home quick row: six equal columns that fit 360dp (8dp side padding, 52dp circle, zero tile side padding),
  labels 11sp one line with short copy (Merge, Compress, Sign, Protect, To Word, Read text), PRO pill moved to the
  circle's top-right edge with no translation.
- **C** Tools hub: tiles are 84dp tall so two-line labels are fully visible; no ellipsis. One tag at most (Soon wins
  over PRO); the tag keeps full opacity while only the icon+label dim on a Soon tile; tags sit inside the tile's
  top-right corner.
- **D** Every shell tab's ScrollView has 112dp bottom padding (`clipToPadding=false`) = nav bar + scan-disc overhang.
- **E** New `ShellLookTest` (360dp): quick row fits with one-line labels; every tool label (43) has <=2 lines, no
  ellipsis and its full height; a Pro+Soon tile shows only Soon, tile not dimmed; Me row drawables; filled/outline
  nav glyphs; scroll padding clears the nav.
- **F** Header uses `ic_brand_mark` (32dp, id `swBrandMark`), Pro hero `ic_brand_mark_gold` (72dp); `ic_whirl` /
  `ic_whirl_gold` deleted (no references).
- **G** Copy: no long dash in any user-visible string in `strings_l1.xml`, `strings.xml` or the Hindi twins. Long
  descriptions shortened to <=90 chars (import failures, resume, save-conflict, OCR status, trash, reader Find,
  password charset, empty Home, etc.). Text asserted by tests was updated where the wording changed.
  `R.string.protect_note` is still referenced by `DocumentViewerActivity.promptProtectPdf()` (L3), so it was left
  in place as instructed.

## Tests added / updated
- Added: `ShellProGateTest` (4), `ShellLookTest` (6), `OcrTextScreenTest` +4 (chips/icons, Tidy sheet, undo order
  and state, footer/chip), plus `OcrStatusTest`, `ImportSheetTest`, `DirectScanSaveTest`, `OcrEditorTest`
  mechanism/copy updates.
- Full gate last line: `GATE PASS L1 tests=1042 failures=0 skipped=0 lint=[lint skipped (lead runs it at merge)]`.

## Not done / notes
- The language chip is a label (no OCR language setting exists yet).
- Line boxes only appear for documents whose OCR geometry was stored; older OCR text has none (image only).
- `R.string.protect_note` left for L3's removal.
- Device re-check list for the lead: Home quick row at 360dp and 200% text; Tools hub labels; nav filled glyphs;
  Me rows; Vault entry; page/tab bottom edges; a Pro tile in forced Free; the Text screen chips/Tidy sheet/undo
  icons; a landscape imported image's Page tab.

## Commits
- `e8cadd8` Manrope fonts, reader dock, Text screen
- `8fe6399` SwSheet sweep
- `0eb2cb8` kit sheet-API tests + report
- (this session) phone-review pass: icons, brand, quick/tools layout, Pro gating, Text screen chips
