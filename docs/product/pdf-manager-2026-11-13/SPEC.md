# ScanWhirl PDF Manager — product specification

**Status:** design brief for Claude Opus. No code changes.  
**Source basis:** current ScanWhirl code capabilities + competitor device walk 2026-11-13.  
**Product constraint:** 100% offline. No accounts, no servers, no ads, no watermark, no cloud processing.

## 1. Goal

Give ScanWhirl a real document-work surface where a PDF is a first-class object:

- find it,
- inspect it,
- organize its pages,
- optimize it,
- secure it,
- convert it locally,
- export or share it.

Today, the app has Merge, Compress, Resize and Lock in a bottom sheet, a page grid hidden behind a row overflow, and a viewer with only Find. The PDF work exists; the product surface does not.

## 2. Jobs to be done

1. “I imported a 12-page PDF and need to remove pages 3 and 9, reorder the rest and save a clean copy.”
2. “I need this file under 500 KB for a portal.”
3. “I need these three scans and this PDF merged in a specific order.”
4. “I need to protect this file with a password and know whether it is still searchable.”
5. “I need the pages as images or one long image for a chat.”
6. “I need the text from this PDF as text/Word, not an image.”
7. “I need to sign/stamp/annotate and flatten the result.”
8. “I need to know which file I am acting on before I overwrite anything.”
9. “I need to recover or undo a bad operation.”
10. “I need all of this without an account or upload.”

## 3. Non-goals

- No cloud storage, sync or login.
- No online OCR, AI summary, translation API, fax or print service.
- No PDF form designer or full vector PDF editor in v1.
- No Office-perfect Word/Excel/PPT conversion promise until fidelity is verified.
- No duplicate of CamScanner’s 41-tool/194 MB suite.
- No ads, watermarks or “remove ads” Pro tier.

## 4. Information architecture

```
Home
├── Scan
├── Import
└── PDF Tools / PDF Manager
    ├── PDF Manager home
    │   ├── Search
    │   ├── Filter: All / Scans / Imported / Locked / Recent
    │   ├── Quick actions: Import PDF / Merge / Compress / Protect
    │   ├── Recent PDFs
    │   └── Tool grid
    │       ├── Organize: Merge, Split/Extract, Reorder, Rotate, Delete
    │       ├── Optimize: Compress, Resize, Images, Long image
    │       ├── Convert: Text, Word, Excel, TXT (only local/verified)
    │       ├── Secure: Password, Unlock, Redact, Metadata
    │       └── Finish: Sign, Annotate, Watermark, Page numbers
    ├── PDF picker (one or multi-select)
    ├── Tool configuration sheet
    ├── Result screen
    └── PDF viewer
        ├── Page stack / thumbnails
        ├── Find
        ├── Share
        ├── Edit → page organizer
        ├── Text → OCR
        ├── Export
        └── More → rename, lock, duplicate, trash, info
```

**Do not add a sixth bottom tab.** Use one visible Home entry and one Tools entry to the same screen.

## 5. Screen 1 — PDF Manager home

```
[←]  PDF Manager                         [Search] [⋯]
──────────────────────────────────────────────────────
[ Import PDF ] [ Merge ] [ Compress ] [ Protect ]
──────────────────────────────────────────────────────
All 12   Scans 8   Imported 4   Locked 2
──────────────────────────────────────────────────────
Recent PDFs                                   [View all]
┌────────────────────────────────────────────────────┐
│ [thumb]  Case notes.pdf                  🔒  [⋯]   │
│          12 pages · 2.4 MB · 13 Nov                │
├────────────────────────────────────────────────────┤
│ [thumb]  Receipts bundle.pdf              [⋯]      │
│          4 pages · 640 KB · 12 Nov                 │
└────────────────────────────────────────────────────┘

Organize
[ Merge ] [ Split / Extract ] [ Reorder ] [ Rotate ]
Optimize
[ Compress ] [ Resize ] [ To images ] [ Long image ]
Convert
[ Extract text ] [ To Word ] [ To Excel ] [ To TXT ]
Secure
[ Password ] [ Unlock ] [ Redact ] [ Metadata ]
Finish
[ Sign ] [ Annotate ] [ Watermark ] [ Page numbers ]
```

### Rules

- The four quick actions are the common jobs; the grid is progressive disclosure.
- Every row has a lock badge, page count, size and source (scan/imported).
- `⋯` contains Rename, Duplicate, Move to folder, Share, Export, Move to Trash, Info.
- `Search` searches names and OCR text with snippets; result rows show a page reference.
- Empty state has Import PDF + Scan a document; no marketing carousel.
- Tool-tile taps open a picker first if the tool needs a document.
- A tool result is a **new copy by default**. Overwrite is explicit.
- If a result is created, the success screen offers Open, Share, Show in library and Undo.

### Why this layout

ACE proved that a Home tool grid works. Adobe proved that a document row with quick actions works. CamScanner proved that grouping tools into Convert/Import/Edit/AI scales, but also that 41 entries become clutter. ScanWhirl should use five groups and a small set of tools.

## 6. Screen 2 — PDF picker

```
Choose a PDF
[Search]                     [Select all]
┌────────────────────────────────────────────────────┐
│ ☐ [thumb] Case notes.pdf     12 pages · 2.4 MB 🔒   │
│ ☐ [thumb] Receipts.pdf        4 pages · 640 KB      │
│ ☐ [thumb] Imported PDF        8 pages · 3.1 MB      │
└────────────────────────────────────────────────────┘
                         [Cancel]  [Next (1)]
```

- Thumbnail, pages, size, date and lock status are mandatory.
- Locked PDFs show a lock; tapping prompts for the password.
- Multi-select for Merge; single-select for Split/Compress/Protect.
- Never show names alone, as the current merge/compress/lock pickers do.

## 7. Screen 3 — Organize pages

```
[←]  Case notes.pdf                      [Select] [Done]
12 pages · edited
┌──────────┐ ┌──────────┐ ┌──────────┐
│ 1        │ │ 2        │ │ 3        │
│ thumb    │ │ thumb    │ │ thumb    │
└──────────┘ └──────────┘ └──────────┘
[ + Insert pages ] [ Extract ] [ Rotate ] [ Delete ]

[ Cancel ]                         [ Save as new copy ]
```

- Drag to reorder; long-press for multi-select.
- Rotate/delete apply to selected pages.
- Insert pages from camera, images or another PDF.
- Extract selected pages into a new PDF.
- Save as new is primary; overwrite is secondary and confirms.
- The header count and thumbnails must update **before** the save screen. This is the old F02 requirement, now explicitly part of the PDF manager.

## 8. Screen 4 — Tool configuration and result

Use one consistent pattern for every tool:

```
Tool title
Document name · pages · size
[ options relevant to this tool ]
Estimated result: 630 KB
[ Cancel ]                         [ Apply ]
```

Examples:

- **Compress:** preset radio + custom size mode + estimate + quality/privacy note.
- **Resize:** preset vs exact target; unit attached; numeric keyboard; action row above keyboard.
- **Protect:** target name, password + confirm/reveal, note on encryption strength, search-consequence warning.
- **Split:** range/page selection, “new PDF” destination, page count.
- **Images:** format PNG/JPEG, quality, ZIP or separate files.
- **Word/TXT:** format, OCR language, layout-fidelity warning.
- **Redact:** draw areas, verify flattening, warning that cover is not redaction.

Result screen:

```
Done — Case notes (compressed)
Before 2.4 MB → After 612 KB
[ preview ]
[ Open ] [ Share ] [ Show in library ] [ Undo ]
```

If the operation fails: keep the original untouched, name the failed tool, show the reason and a Retry/Choose another file path.

## 9. Screen 5 — PDF viewer

```
[←]  Case notes.pdf            🔒  [Find] [Share] [⋯]
──────────────────────────────────────────────────────
                 page stack / full page
──────────────────────────────────────────────────────
[ Share ] [ Edit ] [ Text ] [ Export ] [ More ]
```

- The dock is always visible.
- `Edit` opens Organize.
- `Text` opens OCR/text.
- `Export` opens the local format/destination sheet.
- `More` includes Rename, Lock/Unlock, Duplicate, Folders, Trash, Info.
- Page count is persistent.
- Search results show a page and a snippet; if the image cannot be highlighted, say so honestly.

## 10. Button map

| Surface | Button | Result |
|---|---|---|
| Home | PDF Tools / PDF Manager | Opens PDF Manager home |
| Tools | PDF Manager | Opens the same screen |
| PDF Manager | Import PDF | Android file picker; rasterize with confirmation |
| PDF Manager | Merge | Multi-select picker → ordered list → name → new PDF |
| PDF Manager | Compress | Single PDF → preset/target → result |
| PDF Manager | Protect | Single PDF → password → locked copy |
| PDF Manager | Search | Name/OCR search, snippets, page refs |
| PDF Manager | Filter chips | All / Scans / Imported / Locked / Recent |
| PDF Manager | Row `⋯` | Rename, Duplicate, Move, Share, Export, Trash, Info |
| PDF Manager | Tool tile | Picker if needed → configuration |
| Picker | Next | Validate selection and open tool |
| Organize | Drag | Reorders committed page list |
| Organize | Rotate | Rotates selected pages |
| Organize | Delete | Deletes selected pages with Undo |
| Organize | Extract | Creates a new PDF from selected pages |
| Organize | Insert pages | Camera/images/PDF into current document |
| Organize | Save as new | New copy; Overwrite is secondary |
| Viewer | Find | Page/text search |
| Viewer | Share | Android share sheet |
| Viewer | Edit | Organize |
| Viewer | Text | OCR editor |
| Viewer | Export | Local format/destination |
| Viewer | More | Rename/lock/duplicate/folder/trash/info |
| Result | Open | Opens the result |
| Result | Share | Share sheet |
| Result | Show in library | Highlight the new item |
| Result | Undo | Reverses a reversible operation or removes the new copy |
| Lock | Password | Confirm/reveal, strength note, lock badge |
| Unlock | Password | Open/unlock a supported PDF |
| Convert | Word/TXT/Excel | Local generation only; show fidelity limits |
| Redact | Draw/Apply | Flatten and verify; never call a cover “redaction” |

## 11. State model

Every PDF has:

- identity: library ID, display name, source (scan/import/merge/result), original path/URI if external.
- page state: page count, page files, page order, edited/unsaved.
- security: locked/unlocked, password state, searchable-text state.
- output state: last export destination, last result size, undo availability.
- indexing: OCR state, language, searchable PDF flag.

Every tool result is a **new document/copy** by default. The original stays untouched unless the user selects an explicit overwrite.

## 12. Empty, error and edge states

- No PDFs: Import PDF + Scan a document.
- Locked PDF in picker: password prompt, clear failure without corrupting the file.
- Damaged/encrypted/unsupported PDF: name the reason, keep the original, offer another file.
- Too many pages / too large: explain limits and offer Compress or Split first.
- OCR missing: show “Text not ready” and a Start OCR action, not a false no-results state.
- No OCR text in a scanned PDF: do not imply the page is empty.
- Export failure: retain the result and allow Retry/Share/Save again.
- Cancel from tool: return to the previous screen with the original untouched.
- Low storage: check before creating a large output; show required/available space.
- Back/rotation: preserve the selection and pending tool state.

## 13. Accessibility and i18n

- Every icon has an accessible name; no unlabeled OCR icons.
- 48 × 48 dp minimum hit areas.
- Font scaling to 200%; no clipped tile labels or dialog actions.
- RTL for Arabic/Urdu; document imagery is never mirrored.
- Numeric inputs use numeric keyboards.
- Dialogs remain above the keyboard; action rows never hide.
- Hindi and English strings at parity; error/empty states included.

## 14. Acceptance criteria for the first release

1. A user can open PDF Manager from Home and Tools, see all PDFs with pages/size/lock badge, and search names/text.
2. A user can import a PDF, see the rasterization warning, and find it in the manager.
3. A user can merge in a visible order, name the result, and see it in the manager.
4. A user can compress with a clear before/after and keep the original.
5. A user can open Organize, reorder/rotate/delete/extract pages, and the count/thumbnails match the saved result.
6. A user can protect and unlock a supported PDF, see a lock badge, and understand the search consequence.
7. Every result is a new copy by default; overwrite is explicit and confirms the target.
8. No action silently hides a file; the success state offers Open/Share/Show in library/Undo.
9. Back, cancel, keyboard, font scale and RTL do not trap or hide the primary action.
10. No network is required for any flow in the manager.

## 15. Open decisions for Opus

1. Is PDF Manager a separate activity or a mode of the document library?
2. Does a PDF always become page images on import, or can vector PDFs remain vector? Current app rasterizes.
3. Which local PDF engine is acceptable? Android PdfRenderer can render; the custom `PdfGenerator` writes images. Vector/text editing needs more.
4. Is Word/Excel generation in scope? If yes, define minimum fidelity and whether the output is .docx/.xlsx or text/HTML.
5. Is PDF/A in scope? If yes, define the exact profile and validator.
6. Is signature/redaction an image overlay or a true PDF annotation layer?
7. Is “searchable PDF” a global setting or per-export?
8. Which tools are free and which Power Pack?
9. What is the exact one-time price and update policy?
10. Does the current `PdfEncryptor` RC4-128 meet the product claim, or should the UI continue to call it “casual protection” only?
