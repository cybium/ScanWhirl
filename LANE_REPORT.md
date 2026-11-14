# L1 — Shell & look: lane report

Branch `lane/L1-shell`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L1`.
`integration` merged twice (L3 Protect/Unlock, L4 tools engine, lead's lint fixes).

## Done

### Phase A1 — `ui/kit/` (the kit every lane reuses)
- `SwSheet` — bottom sheet with grab handle, title, close button, free content, full-width primary and a quiet
  secondary. `SwSheet.comingSoon(context, tool)` is the honest non-dead-end. Tests find it by view id.
  - v2 additions: `setOptions(labels, checked, multiChecked, onPick)` — the one list in the app (rows in
    `R.id.swSheetItems`, 48dp each, tick on the selected row; single-choice closes, multi-choice stays open);
    `setInput(initial, hint, inputType)` — `R.id.swSheetInput`; `setPrimaryStayOpen` — the sheet stays up while a
    failed save keeps its retry in place.
- `PasswordField` — show/hide eye, error line, 4-segment strength meter (`PasswordStrength`, 0..4).
- `ToolTile` — icon + label + PRO / Soon tag; `asQuick()` for the Home circle row.
- `DocCard` — paper thumbnail with page badge, name + Protected badge, meta, category dot, Share and ⋯.
- `TrustPill` — green "Offline · No ads" / "All offline" pills (extends `AppCompatTextView` after the lead's lint fix).
- `SwType` — Manrope (SIL OFL) with a system-sans fallback; every kit text style asks it for a weight.
- `ToolRegistry` — all 43 tools of `70_APP_DESIGN.md` §3 with id, group, title, icon, free/pro, status
  (READY/BUILD/SOON), keywords (English + Hindi search) and a launch lambda; 18 Pro.
- Tokens: `res/values/dimens_v2.xml`, v2 colours in `colors.xml`, `ic_whirl` / `ic_whirl_gold` (three open arcs),
  50 `ic_sym_*` vector icons.
- Fonts: `res/font/manrope_{regular,medium,semibold,bold,extrabold}.ttf` + `res/font/manrope.xml` (400–800);
  licence at `docs/licenses/manrope-OFL.txt`. Kit text uses `SwType`; v2 XML surfaces carry
  `android:fontFamily="@font/manrope"` and the v2 toolbar styles use it.
  - The app theme does **not** set Manrope globally: doing that widened legacy screens just enough to clip
    `scanLabel` at 200% text and fail HomeFontScale/HomeSlice2/TextFit (those are pinned accessibility tests, not
    shape tests). The v2 look is unaffected — the shell is built by the kit.
- Strings in `res/values/strings_l1.xml` and `res/values-hi/strings_l1.xml` (Hindi values carry `<!-- review -->`).

### Phase A2 — the shell (`ui/shell/`)
- `ShellActivity` — bottom bar Library · Tools · Scan disc · Vault · Me; back returns to Library. Launcher moved
  from the legacy `MainActivity` (kept in the tree for the flows it still owns).
- Library (`01_home_A`): search over names and page text, create row (ID card · Photos · Import PDF), 6 quick
  tools, category chips with counts, document cards, empty/filter-empty states.
- Tools (`03_tools_hub`): grouped catalogue, search over titles and keywords, every tile wired.
- Pro (`06_pro`): gold hero, promise pills, 8 feature cells, ₹100 one-time; buy/restore open "Coming soon".
- Me: Pro, Settings, Trash, About. Vault: honest "new encrypted Vault is being built" copy + sheet until L2.
- Real actions: ML Kit scanner, gallery → Review, PDF import, merge chooser, compress, resize, document picker
  → viewer / OCR text, Settings, Trash, share, move to Trash (confirmed).

### Phase B — reader dock (07) and Text screen (08)
- `PdfViewerActivity` dock: **Share · Pages · Text · Sign · More** in `layout/view_reader_dock.xml`, included as a
  floating overlay at the bottom of the page (it never steals height from the PDF, so Find keeps its room), a real
  page strip (thumbnails rendered from the PDF) and the lock badge in the title for protected documents. Share
  shares the document/PDF, Pages toggles the strip, Text opens the Text screen (or explains why a file-opened PDF
  has none), Sign opens "Coming soon" until L5, More offers Find and Share. `promptProtectPdf()` and its call site
  were not touched (L3 owns them).
- `OcrEditorActivity`: v2 top bar ("Text", document · page n of n, wrap-height toolbar so the subtitle never
  clips), **Page / Text** segmented switch (Page shows the scan; Text stays editable), glass text card, the honest
  footer, and **To Word · PRO** which opens the Coming soon sheet until L4's writer lands. The actions row scrolls
  horizontally at 320dp/200% instead of squeezing four labels. Existing behaviour, ids and data-safety paths are
  unchanged.

### Phase B — the SwSheet sweep
Every `SwDialog.card/sheet/input` (and the `danger` confirmations) is now a `SwSheet`: `SettingsActivity` (4 choice
sheets + About), `TrashActivity` (2 delete confirms), `PageViewerActivity` (2 filter sheets + discard),
`ScanReviewActivity` (filter, Move to…, save-conflict, discard), `OcrEditorActivity` (unsaved confirm),
`MergePdfActivity` (name input, rasterize note), `DocumentViewerActivity` (Pro panel, rename, save changes, vault
delete), `ProtectionDialogs` (repair, unknown password), `MainActivity` (edit-PDF note, Pro panel, source chooser,
rename, import failure, vault delete, unlock-before-open), `FlowUtil.pickScan`, `ResizeFlow`, `CompressFlow`,
`MergeFlow`, `ScanLauncher` (resume prompts), `VaultFlow` (PIN gate/set). Behaviour, copy and view ids are kept;
tests that used `AlertDialog.getButton/listView` now click `swSheetPrimary`/`swSheetSecondary`/`swSheetItems` rows.
`SwDialog.progress` stays an `AlertDialog` (a spinner, not a choice). Untouched by instruction: `tools/LockFlow.kt`,
`ui/protect/**`, `DocumentViewerActivity.promptProtectPdf()` and its caller.

## Not done / next session
- The Text screen's recognised-line boxes over the page image (mockup 08) are not drawn yet; the Page pane shows
  the scan.
- `DocumentViewerActivity` reader dock/restyle is not part of this pass.
- `LockFlow`'s own lists/password prompt are still AlertDialogs (L3 owns the file; its sheet will replace them).
- Device screenshots and a phone run: not taken (no APK built outside the gate). Ask the lead for a run.
- Merging `integration` again will conflict in files L4 is rewriting (`tools/MergeFlow.kt`, `tools/CompressFlow.kt`,
  `tools/ResizeFlow.kt`): keep L4's engine work and take these files' SwSheet calls where they still apply.

## Tests added
- `com.scanwhirl.app.ui.kit.KitComponentsTest` (13) — sheet, coming soon, options (order/check/close), multi-choice,
  input, stay-open primary, password field/toggle/error/meter, tile tags, doc card, trust pill.
- `com.scanwhirl.app.ui.kit.ToolRegistryTest` (8) — 43/18 catalogue, titles/icons/groups, Soon notes, no dead taps,
  search by title/keyword/Hindi, group order.
- `com.scanwhirl.app.ui.shell.ShellScreenTest` (8) — create row + 6 quick tools + doc cards, nav tabs, 43 wired
  tiles, Soon sheet, tools search, Pro price/buy, Vault copy, card ⋯ sheet.
- `com.scanwhirl.app.ui.ReaderDockTest` (3) — five dock actions + page strip, Sign → Coming soon, lock badge.
- `com.scanwhirl.app.ui.OcrTextScreenTest` (3) — Page/Text switch, footer copy, To Word → Coming soon.
- Migrated dialog mechanisms (behaviour unchanged, each with a `// v2 UI:` note): `DeleteExportsTest`,
  `OcrEditorSaveFailureTest`, `SaveReviewIdentityTest`, `SaveTransactionTest`, `ScanReviewFocusArrangeTest`,
  `UiSmokeTest`, `ImportSheetTest`, `HomeCategoryActionsTest`, `FreeLegacyVaultEntryPointTest`,
  `ProtectionStateTest`, `LockUiTest` (scan-picker row only).
- `LongImageRemovalTest` rewritten for v2 (`// v2 UI: 03_tools_hub`): the catalogue mention is allowed, the legacy
  implementation/ids/drawable stay banned.

## Gate
Last: `GATE PASS L1 tests=888 failures=0 skipped=0 lint=[lint skipped (lead runs it at merge)]`

## Commits
- `c442d47` kit: SwSheet, PasswordField, ToolTile, DocCard, TrustPill, 43-tool registry, tokens, icons
- `b0aed93` shell: Library, Tools hub, Pro, Me, Vault tabs with bottom nav and scan disc
- `6daea4c` v2 UI test rewrite — Long image catalogue entry vs legacy ban
- `5897e51` lane report
- `c0e0b09` merge `integration`
- `e8cadd8` Manrope fonts, reader dock (07_reader_dock) and Text screen (08_text_ocr)
- `8da790f` merge `integration` (TrustPill AppCompatTextView, gate queue, worker brief)
- `8fe6399` SwSheet sweep — every card/sheet/input dialog is a sheet (options, input, stay-open primary)
- (next) kit tests for the new sheet APIs + this report