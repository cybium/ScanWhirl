# L1 — Shell & look: lane report

Branch `lane/L1-shell`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L1`.
`integration` merged into this branch (L3 Protect/Unlock, L4 tools engine).

## Done

### Phase A1 — `ui/kit/` (the kit every lane reuses)
- `SwSheet` — bottom sheet with grab handle, title, close button, free content, full-width primary and a quiet
  secondary. `SwSheet.comingSoon(context, tool)` is the honest non-dead-end. Robolectric tests find it by view id.
- `PasswordField` — show/hide eye, error line, 4-segment strength meter (`PasswordStrength`, 0..4).
- `ToolTile` — icon + label + PRO / Soon tag; `asQuick()` for the Home circle row.
- `DocCard` — paper thumbnail with page badge, name + Protected badge, meta, category dot, Share and ⋯.
- `TrustPill` — green "Offline · No ads" / "All offline" pills.
- `SwType` — Manrope (SIL OFL) with a system-sans fallback; the whole app theme now uses `@font/manrope`.
- `ToolRegistry` — all 43 tools of `70_APP_DESIGN.md` §3 with id, group, title, icon, free/pro, status
  (READY/BUILD/SOON), keywords (English + Hindi search) and a launch lambda; 18 Pro.
- Tokens: `res/values/dimens_v2.xml`, v2 colours in `colors.xml`, `ic_whirl` / `ic_whirl_gold` (three open arcs),
  50 `ic_sym_*` vector icons.
- Fonts: `res/font/manrope_{regular,medium,semibold,bold,extrabold}.ttf` + `res/font/manrope.xml`;
  licence at `docs/licenses/manrope-OFL.txt`.
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
- `PdfViewerActivity` dock: **Share · Pages · Text · Sign · More**, a real page strip (thumbnails rendered from the
  PDF), and the lock badge in the title for protected documents. Share shares the document/PDF, Pages toggles the
  strip, Text opens the Text screen (or explains why a file-opened PDF has none), Sign opens "Coming soon" until
  L5, More offers Find and Share. `promptProtectPdf()` and its call site were not touched (L3 owns them).
- `OcrEditorActivity`: v2 top bar ("Text", document · page n of m), **Page / Text** segmented switch (Page shows
  the scan; Text stays editable), glass text card, the honest footer, and **To Word · PRO** which opens the
  Coming soon sheet until L4's writer lands. Existing behaviour, ids and data-safety paths are unchanged.

## Not done / next session
- **SwSheet sweep** of the remaining legacy `SwDialog.card/sheet/input` `AlertDialog`s. This is a behaviour-preserving
  migration of many dialogs with pinned tests (items, multi-choice, inputs); it needs its own session. New L1 code
  already uses `SwSheet` only.
- The Text screen's recognised-line boxes over the page image (mockup 08) are not drawn yet; the Page pane shows
  the scan.
- `DocumentViewerActivity` reader dock/restyle is not part of this pass.
- Device screenshots and a phone run: not taken (no APK built outside the gate). Ask the lead for a run if needed.

## Tests added
- `com.scanwhirl.app.ui.kit.KitComponentsTest` (9) — sheet, coming-soon, password field/toggle/error/meter,
  tile tags, doc card, trust pill.
- `com.scanwhirl.app.ui.kit.ToolRegistryTest` (8) — 43/18 catalogue, titles/icons/groups, Soon notes, no dead taps,
  search by title/keyword/Hindi, group order.
- `com.scanwhirl.app.ui.shell.ShellScreenTest` (8) — create row + 6 quick tools + doc cards, nav tabs, 43 wired
  tiles, Soon sheet, tools search, Pro price/buy, Vault copy, card ⋯ sheet.
- `com.scanwhirl.app.ui.ReaderDockTest` (3) — five dock actions + page strip, Sign → Coming soon, lock badge.
- `com.scanwhirl.app.ui.OcrTextScreenTest` (3) — Page/Text switch, footer copy, To Word → Coming soon.
- `LongImageRemovalTest` rewritten for v2 (`// v2 UI: 03_tools_hub`): the catalogue mention is allowed, the legacy
  implementation/ids/drawable stay banned.

## Gate
<!-- GATE_LINE -->

## Commits
- `c442d47` kit: SwSheet, PasswordField, ToolTile, DocCard, TrustPill, 43-tool registry, tokens, icons
- `b0aed93` shell: Library, Tools hub, Pro, Me, Vault tabs with bottom nav and scan disc
- `6daea4c` v2 UI test rewrite — Long image catalogue entry vs legacy ban
- `5897e51` lane report
- `c0e0b09` merge `integration` into `lane/L1-shell`
- (next) fonts + reader dock + Text screen + lane report
