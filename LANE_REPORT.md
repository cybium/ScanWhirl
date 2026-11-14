# LANE_REPORT — L4 Tools engine

Session: 2026-11-14 (continuation). Branch `lane/L4-tools`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L4`.
The previous session's Phase A step 3 work was reviewed, completed and committed; Phase B was then built
after `git merge integration`.

(The file that was here before this report was L3's lane report, merged in from `integration`. It is kept
unchanged as `LANE_REPORT_L3.md`.)

## Done

### Phase A — tools engine (no UI dependency)

- **`util/PageRanges.kt`** — parses `1-3, 5, 8-` (en/em dashes, stray spaces). Refuses empty input, a
  backwards range, a part that is not a number and any page outside `1..pageCount` before anything is
  written; `label()` names a span for output document names.
- **Split by range** — `ScanRepository.splitByRange(id, spans)`: one published document per span via
  `buildAndPublish`, category kept, source untouched, one output per range (failures publish nothing).
- **PDF to images** — `ScanRepository.pdfToImages(id, format, zip, outFile, quality, pageIndices)`:
  JPG or PNG; a single page becomes one image, more pages one ZIP; entries are streamed one page at a
  time; JPG quality 100 keeps the stored bytes, lower quality re-encodes. The output is written as
  `.part` and renamed, so a failure never leaves a visible broken image/ZIP.
- **Long image** — `util/LongImage.kt` rebuilt under E20: fixed 1080 px width, aspect ratio preserved,
  one page decoded at a time, `RGB_565`, 12 px gap, **20,000 px maximum per part** (the worker brief's
  value; E20's card says 16,000), splits into `name - long 1 of N.jpg`, refuses a page that would not
  fit instead of squashing it. New `stitchEach` renders part by part, and
  `ScanRepository.exportLongImage(id, outDir)` writes each part and deletes all of them if one fails.
- **To Word** — `util/DocxWriter.kt` writes a minimal valid `.docx` with `java.util.zip`
  (`[Content_Types].xml`, `_rels/.rels`, `word/document.xml`, `word/_rels/document.xml.rels`): one
  paragraph per OCR line, an XML page break between pages, XML-escaped text. `exportWord` builds it
  next to the target and renames, so no half-written `.docx` is visible.
- **Remove metadata** — `ScanRepository.removeMetadata(id)`: decodes and re-encodes every page (drops
  EXIF, maker notes, thumbnails) and regenerates the PDF through `buildAndPublish`; the original is
  byte-identical afterwards.
- **ID card** — `util/IdCard.kt` composes two captures onto one A4 page (1240 × 1754), front top /
  back bottom, aspect kept, sampled decode. The setup screen feeds it to `createDocument`.

### Phase B — tools UI (`ui/tools/**`, mockup 03_tools_hub style, built with the kit)

- **`ToolSetupActivity`** — one shared setup screen:
  - source list of saved documents (single choice; multi-select for Merge and ZIP), page/size meta,
    lock-free sources only; a “Merge PDFs from this phone” exit for the legacy device-PDF flow;
  - options per tool: **ranges** with a live label preview and inline error for Split and Extract;
    **JPG/PNG + quality slider** for PDF to images; **target size** (`500 KB`, `2 MB`) plus three
    quality levels for Compress; **name** for Duplicate/Merge; **two image slots** for ID card;
    live “makes N images, 1080 px wide” for Long image; an OCR-missing note for To Word; a clean-copy
    note for Remove metadata;
  - runs the tool in `lifecycleScope` with an in-screen progress overlay and an inline error line,
    then opens the result screen.
- **`ToolResultActivity`** — one result screen for documents and files: preview, summary, and
  **Open · Share · Save · Show in library · Undo**. Open goes to the reader or the system viewer;
  Share uses the existing `ShareUtils` (single or `ACTION_SEND_MULTIPLE`); Save opens the system
  Save-as picker (`ACTION_CREATE_DOCUMENT`, no storage permission); Show in library returns to the
  shell; Undo deletes only the copy this run created (source never touched).
- **`PassportActivity`** — the existing `CropView` with the fixed 35 × 45 mm preset (`aspectRatio`),
  applied to a picked photo; the crop is saved as a normal document.
- **`ToolLaunch`** — one helper the registry launch lambdas call; a host that is not a `Context`
  (registry test host) still gets the honest Coming soon sheet.
- **`ui/kit/ToolRegistry.kt`** — only L4's own lines changed: the 12 tools are now `READY` and launch
  the setup screen (Passport → its crop screen). The `ready`/`build` helpers gained an optional
  `launch` parameter; every other lane's tool keeps the previous behaviour.
- **`tools/CompressFlow.kt`** — its exact-target helpers moved into a public `SizeFit` object so the
  new Compress setup screen uses the same, tested logic. No behaviour change to the legacy flow.
- **`AndroidManifest.xml`** — three new non-exported activities (setup, result, passport).
- Strings only in `values/strings_tools.xml` + `values-hi/strings_tools.xml` (every Hindi value starts
  with `<!-- review -->`); ids in the lane's own `values/ids_tools.xml`.

## Tests added / updated

- **`store/ToolsEngineTest` (new, 23 tests)** — PageRanges happy/refusal; split happy (names, page
  counts, I1/I4/I10, source digest unchanged), split failure publishes nothing, split refuses a locked
  source; PDF to images ZIP, single PNG, selected pages + quality, unreadable-page failure leaves no
  ZIP/`.part`, locked source writes nothing; long-image plan/split/aspect ratio/refusal, `exportLongImage`
  names and bounds, failure deletes every partial part; `.docx` is a ZIP whose `word/document.xml`
  parses as XML with one paragraph per line and one page break, `exportWord` content, failure leaves no
  partial; `removeMetadata` drops EXIF (source built through the real save path so its own I1 stays
  valid) and failure publishes nothing; ID card pixel placement (front top, back bottom, nothing
  squashed) and refusal of a missing/unreadable side.
- **`util/LongImageTest` (new)** — replaces `ui/LongImageRemovalTest`, which encoded the old
  “Long image is removed” UI (`// v2 UI: 03_tools_hub`). It keeps the old data-safety promise (an image
  exported by an earlier version is untouched and still shares as a JPEG) and asserts bounded parts for
  1/10/40/100 pages and refusal of one too-tall page.
- **`ui/tools/ToolsUiTest` (new, 9 tests)** — every one of the 12 L4 registry tools is `READY` and
  launches its own setup screen (Passport → crop preset), and does not fall back to Coming soon; other
  lanes' tiles keep their behaviour; Split ranges validation (valid preview, impossible page error,
  running a bad range creates nothing); PDF to images JPG/PNG/quality controls; Compress target size;
  ID card needs both sides; running Split opens the result screen and publishes the new document;
  result screen has all five buttons and they act (Save → `ACTION_CREATE_DOCUMENT`, Share → chooser,
  Show in library → shell, Undo → the new copy is gone); a file result’s Open (`ACTION_VIEW`), Save and
  Undo also act.

## Not done / notes for the lead

- **Home “ID card” create card** — `ui/shell/ShellActivity.onCreateIdCard()` (L1's file) still calls
  `comingSoon(id_card)` directly, so the home card does not use the new screen even though the registry
  tool and the Tools-hub tile do. Needs a one-line L1/lead change to route it through
  `ToolRegistry.byId("id_card")?.launch(host, tool)`.
- **Pro gating** — To Word and Split are marked Pro in the catalogue, but the v2 shell shows every tile
  (including in forced-Free review) and this lane does not add a purchase sheet; the tools work in the
  test build (Pro by default). Production hiding/upgrade belongs to L1/E21 and PM-5.5.
- **Exported files** are written under `cacheDir/documents/tools/...` (inside the existing
  FileProvider cache root) and deleted by Undo; Save always goes through the system Save-as picker.
- No phone run requested (lead does device testing); no screenshots.

## Gate result (last full run)

```
tests: total=925 failures=0 skipped=0
GATE PASS L4 tests=925 lint=[lint skipped (lead runs it at merge)]
```

## Commits

- `b0273a3` L4: Phase A tools engine — page ranges, split, PDF to images, long image, To Word, remove
  metadata, ID card
- `8acc941` L4: merge integration (v2 kit + shell, AES-256 protect) and retire the old Long image
  removal test
- `77988e4` L4: Phase B tools UI — one setup screen per tool, result screen (Open/Share/Save/Library/
  Undo), passport crop preset, registry wiring
- (this report) L4: report
