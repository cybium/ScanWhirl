# LANE_REPORT — L5 Edit & sign

## Wave 2 (Organize draft: PM-2.2b/c, PM-2.3b) — BLOCKED

The three cards need PM-2.1b (`buildAndPublish` + `OpStaging.Seal`), which is not in `integration`
(only on `lane/L4-tools`, `e449125`); see `LANE_BLOCKED.md` for the exact evidence. No source was
edited. Waiting for L4 to be merged, then the cards run in order 6 → 7 → 8.

Session 2026-11-14. Branch `lane/L5-stamp`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L5`.

## Done

### Phase A — engine (earlier session, `dc72a5a`)

- **`util/PageStamp.kt`** — bakes vector marks into page pixels: signature ink path, text, freehand,
  highlight (multiply), watermark (diagonal, opacity), page numbers (position/format), preset badges
  (Paid/Approved/Draft/Received/Self-attested + date) and redaction boxes. Pages are decoded once at
  ≤ 3000 px, written through `PageReplace`, then verified (box pixels solid, JPEG read-back).
  `suggestRedactions()` finds Aadhaar/PAN/phone/account numbers in OCR geometry.
- **`store/SignatureStore.kt`** — up to 5 signatures as normalised vector paths in app storage
  (line protocol, atomic write, damaged files skipped, `delete(id)`).
- **`ScanRepository.stampDocument`** — works on a duplicate only. A page with a redaction loses its
  OCR text/geometry and its `.orig`; the PDF is regenerated and verified; any failure deletes the
  unfinished copy and leaves the source byte-identical.

### Phase B — editor (earlier session `660f679`, restyled this session `9759931`)

- `ui/stamp`: `StampEditorActivity` (page surface, bottom tool row, undo/redo, delete, Suggest,
  Save as new copy), `StampCanvasView` (tap-select, drag, corner resize, rotate knob, one-gesture
  Draw/Highlight/Redact), `SignaturePadView`, `StampEditorState` (marks per page, 50-step history,
  clamped transforms) and `StampPrompts`.
- **Every sheet is the kit's `SwSheet`**: text entry, watermark text, saved signatures, signature pad
  and the badge picker. The four bespoke `sheet_stamp_*.xml` layouts are deleted and no reference to
  them remains. Rows/fields use `SwStyle` colours and radii; ids moved to `values/ids_stamp.xml`.
- The discard-edits prompt is a `SwSheet` too (was `SwDialog.card`).

### Tools hub wiring (`2ca6231`)

- All eight Edit & sign entries (`sign`, `add_text`, `draw`, `highlight`, `watermark`,
  `page_numbers`, `stamp`, `redact`) are `READY` and Pro.
- `StampToolLauncher`: Pro gate (`ProGate.isPro`, test builds unlock Pro) → the **existing**
  Merge/Compress library chooser (`FlowUtil.pickScan`, locked/vaulted documents filtered out) →
  `StampEditorActivity` with `EXTRA_TOOL` armed. No documents = one honest line, no empty picker.
  Free build gets the Pro sheet (`pro_suite_title` + `stamp_pro_message`), never a dead tap.

### Polish (`2ca6231`)

- Activity layout on v2 tokens: `void_abyss` background, `text_hologram_*` text, `sw_cyan_hair`
  divider, `sw_space_*` paddings, save button height `sw_btn_height`.
- The activity paints its own chrome from `SwStyle` + `dimens_v2`: cyan 54dp Save (same as SwSheet's
  primary), glass Undo/Redo, danger Delete, round page navigator and hint chip; disabled controls dim.
- Tool row is built from kit `ToolTile`s; `StampCanvasView` selection/handles now use
  `cyan_primary`/`void_abyss` and the handle radius from `dimens_v2` (no hex colours left).
- **Redaction warning before saving**: if any page carries a redaction, Save opens a `SwSheet`
  ("Redaction is permanent" / "Hidden text and pixels are removed for good in the new copy…") with
  "Save redacted copy" and "Keep editing". Nothing is written until the user confirms.

## Tests added / updated

- `StampToolLauncherTest` (rewritten) — id→tool map; the shared Merge/Compress chooser lists the
  library and opens the editor with doc + tool extras; empty library; locked document not offered;
  **Free build shows the Pro sheet and starts nothing**; test build is Pro.
- `StampToolWiringTest` (rewritten) — loops **all eight** entries: `READY` + Pro, chooser opens, and
  the started intent carries the matching `EXTRA_TOOL` for that tool; non-Activity host still gets
  the coming-soon sheet; empty library message.
- `StampEditorActivityTest` — **redaction warns before saving** and writes nothing until confirmed;
  existing page-strip, tool row, undo/redo, page numbers, text sheet, Suggest, requested-tool and
  locked-document tests kept.
- `StampEditorStateTest` — `hasRedaction()` across pages (ordinary marks are not redactions).
- Existing engine tests unchanged and green: `PageStampTest` (pixels in a redact box, OCR removed,
  original byte-identical, watermark opacity), `PageStampSuggestTest`, `SignatureStoreTest`.

## Not done / carried

- **Signature management**: the store can hold 5 and has `delete(id)`, but the sheets have no
  delete-a-signature or "clear pad" affordance, so the "delete one to save another" line has no
  button yet. Needs a lead decision (small follow-up).
- The reader dock's **Sign** button (`07_reader_dock`, L1) does not open `StampEditorActivity`; the
  Tools-hub entries are the only entry point today. The Pro gate lives in `StampToolLauncher`, so any
  future direct caller must check `ProGate` itself.
- The document chooser is intentionally `FlowUtil.pickScan` (per the lead's instruction to reuse the
  Merge/Compress chooser). It is still an old-style `SwDialog`; L1's "replace every SwDialog with
  SwSheet" sweep will restyle it. If the lead wants the kit look now, point `FlowUtil.pickScan` at
  `SwSheet`.
- Saved signatures are vector paths; no bitmap is stored. `SignatureStore`'s 5-slot limit and
  corruption handling are unit-tested but the real finger drawing was not run (no phone in this lane).
- Strings only in `values/strings_stamp.xml` + `values-hi/strings_stamp.xml` (Hindi values start
  with `<!-- review -->`). No new libraries, no permissions.

## Gate result (last full run)

```
tests: total=929 failures=0 skipped=0
GATE PASS L5 tests=929 lint=[lint skipped (lead runs it at merge)]
```

## Commits

- `dc72a5a` L5: PageStamp engine + SignatureStore; redaction drops OCR and `.orig` in a new copy
- `660f679` L5: stamp editor UI (page surface, drag/resize/rotate, tool row, undo/redo, Save as new copy)
- `faba66d` merge `integration` (earlier session)
- `9759931` L5: stamp editor sheets on the kit — `SwSheet` for text/badges/signatures/pad, ToolTile row
- `e1ee14f` merge `integration` (this session)
- `2ca6231` L5: 8 Edit & sign tools wired through the shared chooser + ProGate; kit chrome; redaction warning
- (this report) L5: report

## What to check on the phone (lead)

1. **Touch drag / resize / rotate** on text, signature, badge and redaction boxes; rotate handle only
   on text/signature; a freehand mark only drags. Handles must be easy to hit with a thumb.
2. **Signature drawing**: draw with one finger in the white pad, save, then place the signature and
   drag/rotate it. Also the 5-signature limit message (no delete UI yet).
3. **Draw / Highlight / Redact**: one-gesture ink and boxes; a redaction box burns solid black and the
   saved copy reads as solid after JPEG.
4. **Save as new copy**: the warning sheet appears only when a redaction exists; the original opens
   unchanged; the copy is named `(signed)`/`(watermarked)`/`(redacted)`; OCR text of a redacted page
   is gone (Text tab empty/queued).
5. **Tools hub**: each of the 8 tiles → the library chooser → the editor already armed for that tool.
6. **Free/Pro**: force Free (`ProGate.setTestOverride(context, false)`) and confirm the Pro sheet, then
   reset; test builds are Pro by default.
7. **Look**: tool row, Save button, page navigator, hint chip and the sheets against the kit on a real
   density; system-bar insets at the top and the keyboard over the text sheet.
