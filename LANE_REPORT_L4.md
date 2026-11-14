# LANE_REPORT_L4 — Tools engine (To PowerPoint, Word pictures, ID card flow)

Session: 2026-11-14 (second continuation). Branch `lane/L4-tools`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L4`.
Merged `integration` first (fast-forward); the lead's changes are kept: long-image part limit **16,000 px**
(E20) and the split test's 7,000 px pages, home "ID card" routed through the registry.

## Done

### To PowerPoint (Pro, Convert) — `util/PptxWriter.kt` (new)

A minimal, complete OOXML presentation with `java.util.zip` only:

- `[Content_Types].xml`, `_rels/.rels`, `ppt/presentation.xml` + rels;
- one slide master (with its layout list, colour map, text styles), one blank slide layout, one theme;
- one slide per page whose only shape is a picture of the page, fitted to the slide keeping aspect;
- slide size follows the first page's aspect: A4 portrait **7,560,000 × 10,692,000 EMU**, landscape swapped;
- the page's OCR text goes in the **speaker notes** when the page has text; that adds the notes
  master and one notes slide per page (the same package stays valid without notes);
- pages are encoded by a callback **one at a time** (sampled to ≤ 2000 px long side, JPEG 90), so a
  long deck never holds every picture; the file is written as `.part` and renamed, failure deletes it.

Registered as `to_powerpoint` (Convert, Pro, READY, my setup screen) with the "one slide per page,
text in speaker notes" note. Strings in `strings_tools.xml` + Hindi.

> Icon: the tree has no Material "slideshow" drawable and `res/drawable/ic_sym_*` is L1's; the tool
> uses the existing `ic_sym_table_chart` until L1 adds a dedicated icon.

### To Word — "Include page pictures"

`exportWord(id, outFile, includePictures = true)` embeds each page's image (sampled ≤ 1600 px,
JPEG 90) as an inline `w:drawing` fitted to the text column (9,638 twips = 6,120,130 EMU), followed
by that page's OCR text. Pictures live in `word/media/page_NNN.jpg` with real document
relationships. Off = the previous text-only export (no `word/media`, no jpg content type needed in
practice). Both paths are written atomically; the setup screen has a kit-style toggle for it.

### ID card — the real flow (`ui/tools/IdCardActivity.kt`, new)

1. Opening the tool starts Google's ML Kit document scanner (same integration as Scan; page limit 1,
   JPEG, auto-crop) for the **front**. The scanner integration lives in `ui/tools` because
   `tools/ScanLauncher.kt` is not this lane's file; it uses the identical `GmsDocumentScanning` client.
2. After the front an `SwSheet` asks **"Add the back side?"** with *Scan back side* / *Front only*,
   plus *Pick back side from gallery*; a cancelled scanner lands on a *Front side* sheet with
   *Scan front side* / *Pick from gallery*, so the tool works without a camera.
3. An `SwSheet` **Layout**: *Both sides on one page* (A4, real ID-1 85.6 × 54 mm boxes, front top,
   back bottom, 24 px gap) or *Each side on its own page*. The choice is remembered in the tool's
   own preference file (`IdCardPrefs`).
4. The document is created through `ScanRepository.createDocument` (the publish path) and the
   existing Result screen opens (Open · Share · Save · Show in library · Undo).
5. `util/IdCard.kt` now draws into fixed **ID-1-proportioned** boxes, aspect preserved, never
   stretched: `compose(front, back?)` (one A4 page; front only = front in the top slot) and
   `composeSingle(side)` (one A4 page per side). The old one-page pixel test still passes.

Cancelling the scanner, a gallery picker, a sheet or leaving the screen creates nothing.

## Tests added / updated

- **`util/PptxWriterTest` (6)** — required parts, one slide per page with a picture referencing
  `ppt/media/imageN.jpeg` (JPEG magic checked), **strict OPC validation** (every content-type
  override names a real part, every relationship target resolves, unique ids), master→layout and
  layout→master and master→theme relationships, landscape size, notes master + notes text (escaped),
  failure leaves no visible file/`.part`, a non-image page is refused, deterministic parts.
- **`store/ToolsEngineTest` (+4)** — Word with pictures: 2 media JPEGs, drawings + rels, text kept,
  text-only export has no media, picture failure leaves no `.docx`; PowerPoint via the repository:
  one slide per page, media, master/layout/theme, notes carry OCR text, locked source refused.
- **`ui/tools/IdCardFlowTest` (6)** — front only → one A4 page (front colour on top, white bottom);
  front+back one page → one page with both halves at the ID-1 boxes; "own pages" → two pages, colour
  on each, layout remembered; leaving before Layout creates nothing; the front and back sheets carry
  the expected scanner/gallery/front-only actions.
- **`ui/tools/ToolsUiTest`** — To PowerPoint is READY and opens the setup screen; To Word's pictures
  toggle exists and re-renders; ID card is expected to open the new capture flow.
- **`store/HostSampleExportTest`** — builds `sample.pptx` and `sample.docx` from real fixtures (and
  copies them to the Windows temp folder under WSL for the independent check).
- Updated the L1 catalogue counts for the new tool: **44 tools, 19 Pro** (`ToolRegistryTest`,
  `ShellScreenTest`).

## Gate result (last full run)

```
tests: total=1046 failures=0 skipped=0
GATE PASS L4 tests=1046 lint=[lint skipped (lead runs it at merge)]
```

## Host check (independent parsers, outside our code)

Samples produced by `HostSampleExportTest` on this run:
`C:\Users\Cybium\AppData\Local\Temp\scanwhirl-l4-hostcheck\{sample.pptx,sample.docx}`.

```
$ python3 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); print(z.testzip(), len(z.namelist()))" sample.pptx
None 21
$ python3 -c "... sample.docx"
None 6

$ python3 -c "from pptx import Presentation; ..."
slides: 2
size: 7560000 10692000
shapes: [1, 1]
pictures: [<MSO_SHAPE_TYPE.PICTURE: 13>, <MSO_SHAPE_TYPE.PICTURE: 13>]
notes: ['text page_000.jpg', 'text page_001.jpg']

$ python3 -c "from docx import Document; ..."
paragraphs: 5
inline shapes: 2
text: ['', 'text page_000.jpg', '', '', 'text page_001.jpg']
```

python-pptx 1.0.2 and python-docx 1.2.0 are installed and open both files without repair prompts.

## Notes for the lead

- Phone checks worth doing: scan a real specimen ID card front/back; verify the auto-cropped cards
  sit in the ID-1 boxes and that "Each side on its own page" plus the remembered layout feel right.
- PowerPoint icon: reuse `ic_sym_table_chart` (no L1 slideshow icon exists).
- Home quick row "To Word" now opens the setup screen with the pictures toggle; the Text screen's
  "To Word" (L1) is unchanged and remains text-only.
- `LANE_REPORT.md` no longer exists on the branch; this is `LANE_REPORT_L4.md` as requested.

## Commits

- `32e4ff7` L4: To PowerPoint (minimal OOXML deck), Word page pictures, real ID card capture flow
- (this report) L4: report
