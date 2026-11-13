# 10 — Product spec: PDF Tools

This spec covers the information architecture, the screens, the button map, states, empty and error cases, accessibility and i18n. Decisions referenced as P#, E# and S# are in `00_DECISIONS.md`.

Scope rules that apply to every screen:

- **Counts.** A count always states its scope. Examples: "12 pages" means pages in this document; "3 selected" means selected pages in this draft; "8 documents" means documents shown by the current filter.
- **Sizes.** A size is always the size of the file **Share would send** (invariant I2).
- **Badges.** A badge is always derived from disk state, never from a cached flag (invariant I3).

---

## 1. Information architecture

```
Home (library: find / open / sort / categorize / Trash)
 ├─ Dock: [Tools]  [ SCAN ]  [Import]
 │          │
 │          └─► PDF Tools  (screen 1)
 │                ├─ Quick actions: Import PDF · Merge · Compress · Password
 │                ├─ Recent (last 5 opened or created; not a library)
 │                └─ Groups: Organize · Optimize · Convert · Secure · Finish
 │                      └─ tool ─► Document picker (2) ─► Tool setup (5) ─► Progress ─► Result (6)
 │                                                       └─ Organize ─► Organize pages (4)
 │                                                       └─ Sign/Watermark/Redact ─► Stamp editor (7)
 ├─ Row tap ─► Reader (3)
 │              ├─ Dock: Share · Edit · Text · Tools · More
 │              ├─ Edit ─► Organize pages (4)               (from PM-2.2)
 │              ├─ Text ─► OCR editor (existing)
 │              ├─ Tools ─► Document tools sheet ─► Tool setup (5) with this document preselected
 │              └─ More ─► Rename · Save to Documents · Category · Duplicate · Info · Move to Trash
 └─ Row ⋯ ─► Quick actions (existing) + "Tools…" (opens the Document tools sheet)
```

**Leaving the old Tools sheet** (P6):

| Old tile | New place |
|---|---|
| Quick sort | Home sort menu |
| Vault | Settings, shown only when a legacy PIN exists |
| Resize | PDF Tools → Optimize → Resize photo |

---

## 2. Screen inventory

| # | Screen | Purpose | Primary action | Secondary actions | Success state | Error state |
|---|---|---|---|---|---|---|
| 1 | PDF Tools | Start any PDF job | Quick action or tool tile | Search in picker, Recent row | Opens the picker or setup | Empty library → empty card (P8) |
| 2 | Document picker | Choose the right document(s) | **Next (n)** | Search, filter chips, reorder tray (multi), Cancel | Returns the selection to the tool | No eligible documents → empty state; locked → password prompt |
| 3 | Reader | Read and act on one document | Dock **Share** | Edit, Text, Tools, More, Find, Save to Documents | — | PDF missing or damaged → message plus **Open page images**, Back |
| 4 | Organize pages | Rearrange, rotate, delete or insert pages safely | **Save as new copy** | Replace original, Discard, Select, Rotate, Delete, Extract, Insert | Result screen | Save failed → draft kept, Retry |
| 5 | Tool setup | Configure one tool on named input | **Apply / Create** (tool verb) | Cancel, options, name field | Progress → Result | Validation inline; failure → Result (error form) |
| 6 | Result | Prove what happened and offer next steps | **Open** | Share, Save to Documents, Show in library, Undo | — | Error form: reason + Retry + Choose another |
| 7 | Stamp editor (Power Pack) | Place a signature, watermark or redaction boxes | **Next** (to setup / Apply) | Page switcher, add/remove item, undo last | Setup → Result | Page decode fail → skip page with message |

---

## 3. Wireframes (ASCII)

### 3.1 PDF Tools (screen 1)

```
[←]  PDF Tools                                    [⋯]
──────────────────────────────────────────────────────
[ Import PDF ] [ Merge ] [ Compress ] [ Password ]
──────────────────────────────────────────────────────
Recent                                     [All documents]
[thumb] Case notes (compressed)   12 pages · 612 KB   🔒
[thumb] Receipts bundle           4 pages · 640 KB
[thumb] Rent agreement (signed)   3 pages · 1.1 MB
──────────────────────────────────────────────────────
ORGANIZE
[ Merge ]  [ Organize pages ]  [ Extract & split ]
OPTIMIZE
[ Compress ]  [ Resize photo ]
CONVERT
[ Export as images ]  [ Extract text ]  ( [ To Word ] Slice 6 )
SECURE
[ Password ]  [ Redact ◆ ]
FINISH
[ Sign & self-attest ◆ ]  [ Purpose watermark ◆ ]
──────────────────────────────────────────────────────
Everything stays on this phone.
```

- `◆` marks a Power Pack tile. It appears only under the rules in `30_FREE_PRO.md` §4. When it appears, it is a small "Power Pack" label, never "PRO", never gold, and never "Not available yet".
- **`[All documents]`** opens the picker in browse mode. Tapping a row there opens the Reader.
- **`[⋯]`** contains one item: "About PDF Tools". It opens the honesty sheet (§7).
- **Recent:**
  - Shows the last 5 documents opened in the Reader or created by a tool. Scope: this phone, this library.
  - Is stored as an id list in Prefs. A Trashed id is dropped.
  - Hidden when the list is empty.

### 3.2 Document picker (screen 2)

```
[×]  Choose documents to merge                       
[ Search names and text…                        ]
All · Study · Receipts · IDs · Forms · Other · Imported · Locked
──────────────────────────────────────────────────────
☐ [thumb] Case notes          12 pages · 2.4 MB · 13 Nov  🔒
☑ [thumb] Receipts            4 pages · 640 KB · 12 Nov   Imported
☑ [thumb] Document 3          1 page · 210 KB · 12 Nov
──────────────────────────────────────────────────────
Order (drag ≡ to change)                 17 pages total
 1 ≡ Receipts      4 pages   [×]
 2 ≡ Document 3    1 page    [×]
──────────────────────────────────────────────────────
[ Cancel ]                                  [ Next (2) ]
```

**Modes:**

| Mode | Used by | Behaviour |
|---|---|---|
| single | most tools | Tapping a row selects it; Next enabled. No tray. |
| multi-ordered | Merge | Order tray shown; Next requires at least 2. |
| browse | All documents | No checkboxes; a tap opens the Reader. |

**Rows:**
- Every row shows a thumbnail, name, `N pages`, size, date and badges (🔒 Locked, "Imported").
- Locked rows show **no thumbnail image** (existing privacy rule, `HomeSlice2Test` "locked … never decode a page image"). They show the lock glyph instead.
- **Duplicate names** are disambiguated by date and size. If those are also equal, append `· #id4`, the first 4 characters of the id.

**Search:** reuses `SearchMatcher`. Locked documents match by name only (existing privacy rule).

**Selecting a locked row** prompts for the password, as specified in §5.4.

**Totals:** the tray shows the total page count of the selection. Above 500 total pages, Next is disabled with "Merged documents can have up to 500 pages."

### 3.3 Reader with dock (screen 3)

```
[←]  Case notes                     🔒   [Find] [⤓]
──────────────────────────────────────────────────────
              (continuous page stack)
                     page 3 / 12
──────────────────────────────────────────────────────
[ Share ]  [ Edit ]  [ Text ]  [ Tools ]  [ More ]
```

- `⤓` = Save to Documents. Same action as the existing toolbar icon in `DocumentViewerActivity`.
- **Page indicator:** always visible. Its scope is the document's page count, which equals `/Count` (I1).
- **Dock:**
  - Always visible; it never scrolls away.
  - Order: Share · Edit · Text · Tools · More.
  - **Edit** appears only from PM-2.2 onward. Before that the dock has four buttons.
- **Text** opens the OCR editor. If text is not ready, the editor already explains why (M06).
- **Tools** opens the Document tools sheet: the tools that accept one document, with this document preselected.
- **More:** Rename · Save to Documents · Category · Duplicate · Info · **Move to Trash** (red, last, separated).
- **Locked document:** the lock glyph appears in the title row. Tapping it opens Info → Protection.

### 3.4 Organize pages (screen 4)

```
[×]  Case notes                               [Select]
Draft · 11 pages · 3 changes            (not saved yet)
┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐
│  1   │ │  2 ↻ │ │  3   │ │  4   │
└──────┘ └──────┘ └──────┘ └──────┘
...
──────────────────────────────────────────────────────
Select mode: [ Rotate ] [ Delete ] [ Extract ] [ Insert ]
──────────────────────────────────────────────────────
[ Discard ]      [ Replace original ]   [ Save as new copy ]
```

- **Header.** Always states "Draft", the page count of the draft and the number of changes. The count updates on every change, before any save (old F02 requirement).
- **Primary:** **Save as new copy**, with an editable name defaulting to `‹name› (edited)`.
- **Replace original.** Opens a confirmation:
  - Title: "Replace “Case notes”?"
  - Body: "The current version moves to Trash as “Case notes (previous version)” so you can restore it."
  - Buttons: Cancel · **Replace**. Replace is shown with neutral emphasis, not accent.
- **Discard.** Opens a confirmation: "Discard 3 changes to “Case notes”? The saved document stays as it is." Buttons: Keep editing · **Discard**.
- **Back or ×** with changes: a three-option dialog. Save as new copy (primary) · Discard · Keep editing. The draft is **never** auto-discarded.
- **Rotate** shows ↻ on the thumbnail and rotates the thumbnail itself.
- **Delete** hides the page from the draft, with an Undo snackbar.
- **Extract** sends the selected pages to the Extract setup.
- **Insert** opens a chooser: Photos · Another document. New pages appear after the selection, or at the end.

### 3.5 Tool setup (screen 5): the shared pattern

```
[×]  Compress
Case notes · 12 pages · 2.4 MB · original kept
──────────────────────────────────────────────────────
(•) Balanced      about 900 KB
( ) Small         about 610 KB
( ) Smallest      about 380 KB
( ) Exact size    [  500  ] KB ▾        (numeric keypad)
    Pages are re-saved as JPEG; text in them stays readable at Balanced.
──────────────────────────────────────────────────────
Save as  [ Case notes (compressed)              ]
Saved in your ScanWhirl library. The original is not changed.
──────────────────────────────────────────────────────
[ Cancel ]                                  [ Compress ]   ← sticky, above keyboard
```

**The pattern every tool follows:**
1. **Header:** input summary, with `N pages · size · badges`.
2. **Options:** radio presets are mutually exclusive with "Exact …". Choosing Exact disables the presets and the reverse; there is no hidden precedence (fixes D9).
3. **Estimate:** a line where one is meaningful.
4. **Name field.**
5. **Destination sentence.**
6. **Sticky row:** Cancel · Verb.

**Tool-specific setup content:**

| Tool | Options | Notes / warnings |
|---|---|---|
| Merge | (order comes from the picker tray) | "17 pages from 2 documents". If a source is locked: Password for the result (required). |
| Extract & split | Mode: Extract one document (Free) · Split into files (Power Pack). Range field "1-3, 7" or page chips. Split: every [N] pages or ranges. | Output count: "Creates 3 documents". |
| Compress | as wireframe | If the source has an original kept and the estimate exceeds the original's size: "Your original (240 KB) is already smaller. Compressing makes an image-based copy." |
| Resize photo | Existing resize options, rebuilt in this pattern. Presets XOR exact; unit chip; numeric keypad. | Fixes D7. |
| Export as images | Pages (all / range); Deliver as: Share · Save to Pictures · One ZIP · **One long image** (PM-4.4) | Shows pixel size of page 1. Long image: "Makes 2 images · 1080 px wide". Locked source: warning (E13). |
| Extract text | (none) | Opens the OCR editor. Pages without text show "Text not ready", never empty. |
| To Word (text only) | Pages | Fidelity sentence (E6). |
| Password | Protect: new password, confirm, show/hide. Unlock: current password. Change: current, new, confirm. | Honest note (E14). Search consequence: "While locked, ScanWhirl search finds this document by its name only." Imported with original: "Protecting makes the shared PDF image-based." |
| Redact / Sign / Watermark | Opens the stamp editor first; setup shows the summary "3 boxes on 2 pages". | Redact: "The original still contains the hidden content." |

### 3.6 Result (screen 6)

```
[×]  Done
✓ Created “Case notes (compressed)”
12 pages · 2.4 MB → 612 KB  (−75%)
Saved in your ScanWhirl library. “Case notes” was not changed.
[ preview of page 1 ]
──────────────────────────────────────────────────────
[ Open ]   [ Share ]   [ Save to Documents ]
[ Show in library ]                           [ Undo ]
```

**Rules:**
- The **page count on the Result screen = the page count of the new document = `/Count`** (I1). A test asserts all three.
- **Undo** follows P9. After Undo, the screen shows "Removed. You can restore it from Trash." and disables Open, Share and Save.
- **Multi-output (Split).** The screen lists every output with its own Open action. Undo removes all outputs.
- **Locked output:** the screen adds "🔒 Locked with the same password as ‹source›".
- **Larger result:** "This copy is larger than the original. The original was kept." (Reuses `compress_grew_warning`.)

**Error form:**

```
✕ Compress didn't finish
Couldn't read page 7 of 12. Nothing was created; “Case notes” is unchanged.
[ Retry ]                                  [ Choose another ]
```

### 3.7 Stamp editor (screen 7, Power Pack)

```
[×]  Redact · Aadhaar card                   [Suggest]
──────────────────────────────────────────────────────
        page image with ▇▇▇ boxes (drag / resize)
                  ◀  page 1 / 2  ▶
──────────────────────────────────────────────────────
[ + Box ]   [ Undo ]                         [ Next ]
```

| Tool | Behaviour |
|---|---|
| Redact | **Suggest** proposes boxes over OCR lines matching 12-digit Aadhaar patterns, PAN (`AAAAA9999A`), 10-digit phone numbers and long digit runs. Suggestions are dashed until the user accepts them. |
| Sign | **+ Signature** (pick saved / draw new), **+ Text** ("Self-attested", name, date presets). |
| Watermark | No placement. The text, size, opacity and angle preview applies to all pages. |

---

## 4. Button map

| Surface | Control | Result | Free/PP | Slice |
|---|---|---|---|---|
| Home dock | Tools | Opens PDF Tools | Free | 1 |
| Home sort menu | Quick sort… | Existing Quick sort sheet | Free | 1 |
| Home row ⋯ | Tools… | Document tools sheet for this document | Free | 1 |
| PDF Tools | Import PDF | SAF picker → existing import flow with new copy (E3) | Free | 1 (copy), 2 (original kept) |
| PDF Tools | Merge | Picker (multi-ordered) → Merge setup | Free | 1 (old flow), 2 (new) |
| PDF Tools | Compress | Picker (single) → Compress setup | Free | 1 (old dialog), 2 (new) |
| PDF Tools | Password | Picker (single) → Password setup | Free | 1 (old), 3 (new) |
| PDF Tools | Organize pages | Picker → Organize | Free | 2 |
| PDF Tools | Extract & split | Picker → Extract setup | Extract Free / Split PP | 2 / 4 |
| PDF Tools | Resize photo | Photo picker → Resize setup | Free | 1 (old), 2 (setup pattern) |
| PDF Tools | Export as images | Picker → Images setup | Free | 4 |
| PDF Tools | Extract text | Source chooser (Library / PDF file / Photo) → OCR editor | Free | 1 |
| PDF Tools | To Word | Picker → Word setup | PP | 6 |
| PDF Tools | Redact | Picker → Stamp editor → setup | PP | 5 |
| PDF Tools | Sign & self-attest | Picker → Stamp editor → setup | PP | 5 |
| PDF Tools | Purpose watermark | Picker → Watermark setup | PP | 5 |
| PDF Tools | Recent row | Reader | Free | 1 |
| PDF Tools | All documents | Picker (browse) | Free | 1 |
| Picker | Row | Select / toggle / open (by mode) | — | 1 |
| Picker | Locked row | Password prompt → selected | — | 1 |
| Picker | Tray ≡ drag | Reorders the merge order | — | 1 |
| Picker | Next (n) | Returns the selection | — | 1 |
| Reader | Share | Shares the file Share sends (original if unchanged import) | Free | 1 |
| Reader | Edit | Organize (draft) | Free | 2 |
| Reader | Text | OCR editor | Free | 1 |
| Reader | Tools | Document tools sheet | Free | 1 |
| Reader | More | Rename / Save to Documents / Category / Duplicate / Info / Trash | Free | 1 |
| Reader | Find | Existing `ReaderFind` | Free | — |
| Organize | Save as new copy | Publish path → Result | Free | 2 |
| Organize | Replace original | Confirm → publish + previous version to Trash → Result | Free | 2 |
| Organize | Discard | Confirm → delete the draft → back | Free | 2 |
| Organize | Rotate / Delete / Extract / Insert | Draft change | Free | 2 |
| Setup | Verb | Run with progress → Result | — | 2+ |
| Setup | Cancel | Back; nothing written | — | 2+ |
| Progress | Cancel | Stops between pages; staging removed; "Nothing was created" | — | 2+ |
| Result | Open | Reader on the new document | — | 2 |
| Result | Share | Share sheet | — | 2 |
| Result | Save to Documents | Existing export transaction | — | 2 |
| Result | Show in library | Home with the new document revealed | — | 2 |
| Result | Undo | P9 | — | 2 |

---

## 5. States, empty and error cases

### 5.1 Document-level states shown in the UI

| State | Shown as | Source of truth |
|---|---|---|
| Locked | 🔒 on row, picker and reader; Info → "Protected with a password (RC4-128)" | `locked.meta` exists **and** `scan.pdf` has `/Encrypt` (I3). A mismatch shows "Protection needs repair" in Info, with Unlock (password) and Protect again. |
| Imported (original kept) | "Imported" chip in the picker; Info → "Original PDF kept (240 KB, selectable text)" | `provenance.origin=import` + `original.pdf` hash matches |
| Image-based copy | Info → "Image-based PDF: text can't be selected; search uses recognised text" | Every ScanWhirl-generated `scan.pdf` |
| Searchable in other apps | Info → Yes / Partly (n of N pages) / No | `text_layer.flag`, plus geometry coverage |
| Text ready in ScanWhirl | Existing Home status, shown only when not ready (D14) | `OcrStore` |
| Draft pending | Reader banner: "Unsaved page edits · Resume · Discard" | `drafts/‹id›/` exists |

### 5.2 Empty cases

| Case | What the user sees |
|---|---|
| No documents at all | P8 card |
| No documents in the filter | "No documents match “Locked”." + **Show all** |
| Merge with fewer than 2 documents | Picker empty state: "Merge needs at least 2 documents." + Import PDF · Scan |
| Text not ready | "Text not ready yet: page 3 is still being read." + **Read now**. Never "No results". |
| A page with no recognised text | "No text found on this page." Only after a successful read; never implies the page is blank. |

### 5.3 Error cases (each names the reason and keeps the original)

| Error | Message | Actions |
|---|---|---|
| Damaged or unreadable PDF (import) | Existing `import_failed_*` strings, reused | Choose another |
| Foreign password-protected PDF (import) | Existing `import_failed_password` | Choose another |
| Over 500 pages or 256 MB | Existing `import_failed_too_many/too_large`, plus "Split it first" | Choose another |
| Not enough space | "This needs about 48 MB free; 12 MB is free." | Retry · Choose another |
| Page decode failure during a tool | "Couldn't read page 7 of 12. Nothing was created." | Retry · Choose another |
| Wrong password | Inline under the field: "That password doesn't match." Field keeps focus; no toast. | — |
| Export to Documents failed | Result stays; "Couldn't save to Documents: ‹reason›." | Retry · Share |
| Process killed during a tool | On next launch, nothing partial is visible. No message needed: the original was never touched. | — |
| Process killed in Organize | On next open: "Unsaved edits to ‹name› · Resume · Discard" | Resume · Discard |
| Cancel during progress | "Cancelled. Nothing was created." | Back to setup |

### 5.4 Locked input flow

The prompt reads: "“Case notes” is locked. Enter its password to use it in Compress." It shows a field, Show/Hide, Cancel and Use.

- **Wrong password:** inline error; three attempts, then a 30-second cooldown.
- **Correct password:** the row is selected and the password is held in memory for this operation only.

---

## 6. Accessibility

- Every icon button and tile has a content description equal to its visible label. The lock glyph reads "Locked". Power Pack tiles read "‹tool›, Power Pack".
- Every touch target is at least 48 × 48 dp (existing `ImportSheetTest` pattern). This includes the tray drag handle, the tray remove button and the stamp editor handles.
- **At 200% font scale:**
  - Tile labels never break inside a word: reuse the measured 4→3 column rule from `MainActivity.kt:1214–1227`, extracted to a helper.
  - The setup sticky row stays on screen.
  - The reader dock labels may hide; icons and content descriptions remain.
- **Drag alternatives** (TalkBack):
  - The tray has "Move up" and "Move down" accessibility actions.
  - Organize has the same actions on each page.
- **Keyboard:** numeric fields use `inputType=number|numberDecimal`. The action row is never covered (E19). Tested with `HomeKeyboardFocusTest`-style assertions.
- **Contrast:** AA on the ink sheet. Reuse the `ImportSheetTest` contrast assertion pattern.
- **Announcements:** Result and errors are announced with a live region.

## 7. i18n and copy

- Every new or changed string exists in `values/strings.xml` **and** `values-hi/strings.xml` (`StringsParityTest` enforces this).
- **RTL:** layouts use start/end. Page images and thumbnails are never mirrored, and the page order tray reads in layout direction.
- **Numbers and dates:** use `StatusLine.formatBytes`, locale date formats and plurals for every count ("1 page" / "2 pages").
- **Delete these misleading strings or rewrite them** (PM-1.4):

  | String | Location | Current text |
  |---|---|---|
  | `tool_compress_sub` | `strings.xml:165` | "Lossless neural packing" |
  | `tool_merge_sub` | `:169` | "Combine orbital records" |
  | `import_pdf_desc` | `:209` | "Each page becomes an editable page" |
  | `save_changes_msg` | `:119` | "Your edits are not in the original PDF yet" (false) |
  | `pro_status_unavailable` | `:259` | "Not available yet" |

- **About PDF Tools** (the honesty sheet behind `[⋯]`) has five short lines:
  1. Everything runs on this phone. No account, no upload. After PM-1.5, this line becomes: "ScanWhirl has no internet access."
  2. ScanWhirl PDFs are made from page images. Text you see is recognised text.
  3. Imported PDFs keep their original file until you make an edited copy.
  4. Password protection uses the RC4-128 PDF standard: it stops casual opening.
  5. Text recognition reads English and other Latin-script text. Hindi is added in Settings once PM-6.3 ships, and is searchable in ScanWhirl only. Google Play services installs the on-device model.
