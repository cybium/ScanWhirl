# Accessibility audit — 2026-11-15

Lane L5 (Edit & sign, Organize, Mask Aadhaar). Companion to `docs/audit/2026-11-15/PERFORMANCE.md`.

## Method

`app/src/test/java/com/scanwhirl/app/ui/a11y/AccessibilityAuditTest.kt` walks every v2 screen that
Robolectric can start, at **360dp width** and font scale **1.3 and 2.0**:

| screen group | screens |
|---|---|
| shell | Library, Library selection, Tools, Me, Pro, Vault tab |
| document | Document viewer (Organize grid), Organize selection, Draft bar |
| editors | Page editor, Stamp editor, Text screen (OCR) |
| tools | Tool setup, Tool result |
| safety | Vault, Protect sheet, Unlock sheet |
| other | Scan review, insert-pages sheet, Find sheet (Redact; Aadhaar preset), Settings |

The checks are plain view-tree walks (no Espresso):

1. **label** — a clickable view with no content description and no visible text (including
   descendants of a clickable group).
2. **touch** — a clickable view smaller than 48×48dp.
3. **contrast** — WCAG relative luminance against the *composited* background: window ink →
   card/glass layers → the view's own fill. Material `backgroundTint` and
   `MaterialShapeDrawable.fillColor` are read; `RippleDrawable` masks are ignored (a ripple's white
   mask is not a surface). Threshold 4.5:1 body, 3:1 large (≥18sp, or ≥14sp bold). Disabled views
   are exempt (inactive components).
4. **clip** — a laid-out line wider than the view's text area, an ellipsized line, text taller than
   its view, an edge outside the screen (scrolling ancestors exempt), or two text siblings
   overlapping by >40% of the smaller one.
5. **image** — an `ImageView` with a drawable, no description and no labelled clickable ancestor.
6. **focus** — a sheet whose decor has no accessibility pane title (TalkBack must start on the
   title, not the first field).

Run it with:

```
powershell -File tools\gate.ps1 -Lane L5 -Tests "com.scanwhirl.app.ui.a11y.AccessibilityAuditTest"
```

The rig is always-on. Justified exceptions live in
`app/src/test/resources/a11y_allowlist.txt` (`screen|rule|key|reason`, 129 lines); every exception
names its owning lane and the finding below. Fixing a finding means deleting its line.

Two focused tests prove the new interaction model:
`StampCanvasAccessibilityTest` (move/resize/rotate/delete actions change the mark) and
`PageGridAccessibilityTest` (tile announces position+selection, Move down reorders and announces).

## Result

The audit reproduces **129 unique findings** (same defect at two font scales counted once):

| severity | rule | count |
|---|---|---|
| **S1 critical** | clickable without a name (`label`) | 4 |
| **S2 high** | touch target <48dp (`touch`) | 47 |
| **S2 high** | contrast below threshold (`contrast`) | 18 |
| **S3 medium** | text clips/ellipsizes/overlaps (`clip`) | 45 |
| **S3 medium** | sheet title is not the pane title (`focus`) | 4 |
| **S4 low** | image with no description (`image`) | 11 |

L5 fixed everything in its own files (below); 129 findings remain for L1, L2 and L4 and are
allow-listed until those lanes ship the fixes.

## Fixed in L5

| file | change |
|---|---|
| `ui/pagegrid/PageGridView.kt` | Tiles announce **“Page 3 of 10”** / **“Page 3 of 10, selected”**; the check button announces **“Select page 3 of 10”**; custom actions **Move up / Move down** reorder through the same `onDropped` path as a drag and announce the new slot. |
| `res/layout/item_page_grid.xml` | The 28dp tick is now a 48dp clickable wrapper (`checkSelectTouch`) around the 28dp circle, so selection meets the 48dp target without changing the visual size. |
| `res/layout/activity_document_viewer.xml` | `docCategoryChip` min 48dp; selection close 48dp; Select all 48dp; draft-bar buttons wrap to 3 lines with 48dp minimum; selection action bar wraps to 3 lines; empty-state Add pages 48dp. |
| `ui/stamp/StampCanvasView.kt` | The canvas is focusable and labelled (“Picture, selected on page 1 of 3” / “Page 1 of 3. No mark selected.”). Custom actions **Move left/right/up/down, Make bigger/smaller, Rotate clockwise/anticlockwise, Delete mark** drive the same editor calls as the drag handles; selection is announced. |
| `res/layout/activity_stamp_editor.xml` | Prev/next page 48×48dp, Delete 48dp high, Save copy wraps with a 48dp minimum. |
| `ui/stamp/StampEditorActivity.kt` | Tool tiles grow to three label lines at font scale 2.0 instead of clipping (“Page numbers”). |
| `ui/PageViewerActivity.kt`, `res/layout/activity_page_viewer.xml` | The page image announces **“Page 2 of 5”**; dock buttons wrap to two lines at 2.0. |
| `res/values/strings_draft.xml`, `values-hi/strings_draft.xml` | New accessibility strings (English + Hindi with `<!-- review -->`). |
| `res/values/ids_a11y.xml` | Stable ids for the custom actions. |

## Findings for other lanes

### L1 — shell, kit, Text screen, Settings, Scan review

**Shell: Library / Library selection** (`ui/shell/LibraryScreen.kt`, `ui/kit/DocCard.kt`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | `swDocShare`, `swDocMore` are 36×36dp | `ui/kit/DocCard.kt:187,194` | 48dp touch bounds (keep the 36dp glyph) |
| S2 | Sort `swSort` 32dp; category chips “All”/“All 1” 34dp; “+ New folder” 34dp | `ui/shell/LibraryScreen.kt:871` and chip row below it | minHeight 48dp on every chip/button |
| S2 | Selection actions `swSelDone` 32dp, `swSelMove/Tags/Share/Rename/Delete` 34dp | `ui/shell/LibraryScreen.kt:450,476-488` | minHeight 48dp on the bar and its chips |
| S3 | Search hint “Search names and words in pages” is 595px at 1.3× but 516px fit | `ui/shell/LibraryScreen.kt:729` | shorter hint or let the hint wrap/ellipsize by design |
| S3 | Quick-tool labels (`swToolLabel` “Compress”, “Read text”) clip at 1.3× | `ui/kit/ToolTile.kt:67` + quick-row sizing | allow 2 lines or reduce label |
| S3 | `Library`, `Import PDF`, `swDocMeta` clip at 2.0× | `ui/shell/LibraryScreen.kt` card/row layout | wrap instead of fixed single line |
| S4 | `swBrandMark` and card glyphs are announced although decorative | `ui/shell/LibraryScreen.kt:685`, `DocCard` icon | `importantForAccessibility="no"` |

**Tools hub** (`ui/shell/ToolsHubScreen.kt`, `ui/kit/ToolTile.kt`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | Section headers + counts (`CREATE`, `ORGANIZE`, `OPTIMIZE`, `CONVERT`, `EDIT & SIGN`, `SECURE`, `TEXT`, `2/4/8/9`) are 4.06:1 | `ToolsHubScreen.kt:176,205` using `cyan_dim` on `#151c20` | use `text_hologram_mid` or brighten `cyan_dim` |
| S3 | `swToolLabel` clips (“Book pages”, “Business card”, “To PowerPoint”, “Searchable PDF”, “Watermark”, “Page numbers”) at 1.3–2.0× | `ui/kit/ToolTile.kt:67` (maxLines 2) | 3 lines or a wider tile |
| S3 | Tools search hint clips at 1.3× | `ToolsHubScreen.kt:78` | shorter hint |
| S4 | A decorative `ImageView` is announced | `ToolsHubScreen` search glyph | mark decorative |

**Me / Pro / Vault tab** (`ui/shell/MeScreen.kt`, `ProScreen.kt`, `VaultScreen.kt`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | `swProClose` 40dp; “See all 18 Pro tools” 36dp; “Restore purchase” 36dp | `ProScreen.kt:51,160` | 48dp |
| S3 | Pro feature labels (`Sign & stamp`, `Word & Excel`, `Split by range`, …) clip at 1.3–2.0× | `ProScreen.kt:131-145` | wrap to 2 lines |
| S3 | `Library` label clips in Me and the Vault tab | `MeScreen.kt`, `VaultScreen.kt` | wrap |
| S2 | `swVaultStatus` contrast ~3.9:1 | `VaultScreen.kt:93` | `text_hologram_mid` |
| S4 | Pro/Me decorative glyphs announced | `ProScreen.kt` feature icons | mark decorative |

**Kit `SwSheet`** (every sheet: Insert pages, Mask Aadhaar, Protect, Unlock, More, Delete…)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | Close `swSheetClose` is 40dp; secondary `swSheetSecondary` is 44dp | `ui/kit/SwSheet.kt:82,128` | 48dp (54dp primary already) |
| S3 | No accessibility pane title, so TalkBack starts on the first control instead of the sheet title | `ui/kit/SwSheet.kt:339` (`show()`) | `ViewCompat.setAccessibilityPaneTitle(dialog.window.decorView, titleView.text)` on show |

**Text screen** (`ui/OcrEditorActivity.kt`, `res/layout/activity_ocr_editor.xml`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | Page/Text mode toggles 30dp; language chip 38dp; Tidy/Copy/Share/Save/To Word chips 38dp; Undo/Redo 40dp; overflow 40dp | `activity_ocr_editor.xml:93,107,203,212`; `OcrEditorActivity.kt:237,466-472` | 48dp minimum on chips and icon buttons |
| S3 | `swChipLabel` clips (“To Word · PRO”, “Share text”) at 1.3–2.0×; mode labels clip at 2.0× | `ui/kit/SwChip.kt:64` (fixed 38dp, maxLines 1) | wrap/minHeight in `SwChip` |
| S3 | Page title ellipsized at 2.0×; `ocrFootNote` ellipsized | `activity_ocr_editor.xml` title/footnote | allow two lines |

**Settings** (`ui/SettingsActivity.kt`, `res/layout/activity_settings.xml`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S1 | Switches `switchAutoEnhance`, `switchAutoCopyToDocuments`, `switchSearchablePdf` are clickable with no name | `activity_settings.xml:530,582,634` | `contentDescription` from the row title (or labelFor) |
| S1 | Toolbar back button has no name | `activity_settings.xml` toolbar | `navigationContentDescription` already used elsewhere; set it here |
| S3 | Three descriptions clip at 1.3–2.0× (“Google processing is on…”, “Hides OCR text…”, “Also save a copy…”) | `activity_settings.xml` 404px column | reduce padding or allow wrap |
| S4 | Decorative row icons are announced | `activity_settings.xml` row icons | mark decorative |

**Scan review** (`ui/ScanReviewActivity.kt`, `res/layout/activity_scan_review.xml`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | Overflow menu 40dp; title field 45dp high | `activity_scan_review.xml:40`, toolbar | 48dp |
| S3 | `pageStatus` clips at 2.0×; title hint is 497px vs 464px | `activity_scan_review.xml:127-144` | wrap/ellipsize by design |
| S2 | `btnModeFocus`/`btnSavePdf` contrast appeared low in an earlier run; with proper selector/tint resolution both pass | — | none (harness fix) |

### L2 - Vault, Protect, Unlock

**Fixed by L2** (allow-list lines removed, the audit now enforces them):

| file | change |
|---|---|
| `res/layout/sheet_protect.xml` | close 40->48dp, show-password 44->48dp, Protect & share and Save wrap with a 48dp+ minimum and two text lines at 2.0; field labels and hints moved from `rv_ink_3` to `rv_ink_2` (contrast); the AES sentence gets end padding so its second line fits; decorative glyphs and the confirm tick are `importantForAccessibility="no"`. |
| `ui/protect/ProtectSheet.kt` | the sheet sets the accessibility pane title to its own title. |
| `res/layout/sheet_unlock.xml` | show-password 44->48dp, Cancel 46->48dp, Open and Cancel wrap with a 48dp+ minimum; "or" moved to `rv_ink_2`; lock/eye glyphs are decorative. |
| `ui/protect/UnlockSheet.kt` | pane title = the unlock title. |
| `ui/vault/VaultActivity.kt` | lock and more buttons 40->48dp; Recovery code / Backup file buttons 64dp tall with the 24dp icon above the label (the label keeps the full width; the size steps down with the system font scale so it stays on one line at 2.0); the shield, empty-state and locked-state glyphs are decorative. |

**Original findings** (kept for the record):

**Protect sheet** (`ui/protect/ProtectSheet.kt`, `res/layout/sheet_protect.xml`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | Close 40dp; show-password 44dp; `protectSave` 44dp high | `ProtectSheet.kt:116,120,130` | 48dp |
| S2 | “PASSWORD”, “TYPE IT AGAIN”, password hints are 3.7:1 on `#363c49` | `sheet_protect.xml:114` label + field hints | use `text_hologram_mid` for the field background |
| S3 | `protectShare`/`protectSave` clip at 2.0×; strength sentence line 2 overflows | `sheet_protect.xml` buttons/strength note | wrap to 2 lines |
| S3 | No pane title | `ProtectSheet.show` | set pane title to `protectTitle` |
| S4 | Lock/eye glyphs announced without description | `sheet_protect.xml` icons | mark decorative or describe |

**Unlock sheet** (`ui/protect/UnlockSheet.kt`, `res/layout/sheet_unlock.xml`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | Show-password 44dp; Cancel 46dp | `UnlockSheet.kt:49,53` | 48dp |
| S2 | “or” 3.7:1 on the field surface | `sheet_unlock.xml` | `text_hologram_mid` |
| S3 | `unlockOpen`/`unlockCancel` clip at 2.0× | `sheet_unlock.xml` | wrap |
| S3 | No pane title | `UnlockSheet.show` | pane title = `unlockTitle` |
| S4 | Eye/lock glyphs announced without description | `sheet_unlock.xml` | mark decorative |

**Vault** (`ui/vault/VaultActivity.kt`, `res/layout/activity_vault.xml`)

| sev | finding | file:line | fix |
|---|---|---|---|
| S2 | `vaultLock`, `vaultMore` 40dp; Recovery code / Backup file 46dp high | `VaultActivity.kt:232,251,352,356` | 48dp |
| S3 | Recovery/backup labels clip at 2.0× | same rows | wrap |
| S4 | A decorative image is announced | vault header | mark decorative |

### L4 — Tools setup and result (fixed)

| sev | finding | fix |
|---|---|---|
| S2 | Back `ImageButton` 40dp (setup and result) | 48×48 dp touch target |
| S2 | `SOURCE`/`OPTIONS` headers at 4.40:1 | label colour brightened to `text_hologram_mid` |
| S3 | `toolSetupRanges` / source document name clipped at 2.0× | field wraps with a 52dp minimum; document name allows two lines |
| S3 | `toolResultLibrary` clipped at 2.0× | result buttons wrap (2 lines, 50dp minimum) |
| S4 | `toolResultPreview` had no description | announces “Result preview” (EN + HI) |

The eight L4 allow-list lines were removed; `AccessibilityAuditTest` passes with no L4
exceptions.

## Notes

- Contrast is measured against the composited kit colours, so a finding is only reported when the
  text colour itself fails — not when the harness cannot resolve a custom drawable. Two earlier
  Scan-review/Unlock false positives disappeared when `backgroundTint` and text-selector defaults
  were resolved; they are not in the list.
- Text in scrolling content is exempt from “edge outside the screen” (below-the-fold rows are not
  defects) but not from line/height clipping.
- Hindi strings for the new announcements carry `<!-- review -->` for a native speaker.
- `MainActivity` is unreachable legacy UI (parked), so it is not part of the audit.
