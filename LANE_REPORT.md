# L1 — Shell & look: lane report

Branch `lane/L1-shell`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L1`.

## Done

### Phase A1 — `ui/kit/` (the kit every lane reuses)
- `SwSheet` — bottom sheet with grab handle, title, close button, free content, full-width primary and a quiet secondary.
  `SwSheet.comingSoon(context, tool)` is the honest non-dead-end for tools not built yet.
- `PasswordField` — show/hide eye, error line, 4-segment strength meter (`PasswordStrength`, 0..4).
- `ToolTile` — icon + label + PRO / Soon tag; `asQuick()` for the Home circle row.
- `DocCard` — paper thumbnail with page badge, name + Protected badge, meta, category dot, Share and ⋯.
- `TrustPill` — green "Offline · No ads" / "All offline" pills.
- `ToolRegistry` — all 43 tools of `70_APP_DESIGN.md` §3 with id, group, title, icon, free/pro, status
  (READY/BUILD/SOON), keywords (English + Hindi search) and a launch lambda; 18 Pro.
- Tokens: `res/values/dimens_v2.xml`, v2 colours appended to `colors.xml`, `@drawable/ic_whirl` +
  `ic_whirl_gold` (three open arcs from `sw.css`), 50 `ic_sym_*` vector icons.
- Strings in `res/values/strings_l1.xml` and `res/values-hi/strings_l1.xml` (Hindi values carry `<!-- review -->`).

### Phase A2 — the shell (`ui/shell/`)
- `ShellActivity` — bottom bar Library · Tools · Scan disc · Vault · Me; back returns to Library; the
  legacy `MainActivity` stays in the tree (launcher moved to the shell).
- Library (`01_home_A`): search over names and page text, create row (ID card · Photos · Import PDF),
  6 quick tools, category chips with counts, document cards, empty/filter-empty states.
- Tools (`03_tools_hub`): grouped catalogue, search over titles and keywords, every tile wired.
- Pro (`06_pro`): gold hero, promise pills, 8 feature cells, ₹100 one-time, buy/restore open "Coming soon".
- Me: Pro, Settings, Trash, About (restyled rows).
- Vault: honest "new encrypted Vault is being built" copy + sheet until L2 lands.
- Real actions wired: ML Kit scanner, gallery → Review, PDF import, merge chooser, compress, resize,
  document picker → viewer / OCR text, Settings, Trash, share, move to trash (with confirmation).

## Not done (next session)
- Phase B: reader dock (`07_reader_dock`) and Text screen (`08_text_ocr`).
- Phase B: replace the remaining `SwDialog.card/sheet/input` `AlertDialog`s across the app with `SwSheet`.
- `PdfViewerActivity` dock and `OcrEditorActivity` restyle.
- Manrope `.ttf` files: **not bundled** — this worktree has no network and no TTF on the machine.
  Kit text uses the platform sans family at the mockup's sizes/weights; the font drop-in is a
  one-line change per style once the OFL files are available.
- Screenshots on a device: not taken (no APK build outside the gate; ask lead for a phone run).

## Tests added
- `com.scanwhirl.app.ui.kit.KitComponentsTest` (9 tests) — sheet, coming-soon, password field/toggle/error/meter,
  tile tags, doc card, trust pill.
- `com.scanwhirl.app.ui.kit.ToolRegistryTest` (8 tests) — 43/18 catalogue, titles/icons/groups, Soon notes,
  no dead taps, search by title/keyword/Hindi, group order.
- `com.scanwhirl.app.ui.shell.ShellScreenTest` (8 tests) — home create row + 6 quick tools + doc cards,
  nav switches tabs, 43 wired tiles, Soon sheet, tools search, Pro price/buy, Vault copy, card ⋯ sheet.

## Gate
`GATE PASS L1 tests=6 lint=[lint skipped]` (LongImageRemovalTest, after the v2 rewrite).
Full suite before that rewrite: 858 tests, 1 failure — `LongImageRemovalTest` only; fixed and re-run green.
Full gate with lint re-run after the merge with `integration` (see below).

## Commits
- `c442d47` L1: v2 kit (SwSheet, PasswordField, ToolTile, DocCard, TrustPill), 43-tool registry, tokens and icons
- `b0aed93` L1: v2 shell (Library, Tools hub, Pro, Me, Vault tabs) with bottom nav and scan disc
- `6daea4c` L1: v2 UI test rewrite — Long image is a new catalogue tool, legacy implementation still banned
- `integration` merged into `lane/L1-shell` (L3 Protect/Unlock sheets, L4 tools engine)
