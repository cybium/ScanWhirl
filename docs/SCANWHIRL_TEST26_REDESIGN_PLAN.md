# ScanWhirl test26 — "One Home, Real Categories" Redesign Plan

> Written by Claude on 2026-11-07 from: the test25 source zip, the test25 master plan,
> the test25 execution report, and 5 device screenshots from the owner.
> Every root cause below was traced in the actual source, with file/line references.
> Anything I could not verify without a device is marked **(verify on device)**.

> **Rev 2 — 2026-11-07, after G1 (alpha1 APK + 5 device screenshots + APK inspection).**
> T0–T1 are done and verified in the APK. Rev 2 adds only: complaint **#13** (ghost PDFs in Recent files),
> decision **N10**, task **T4.7**, a Settings line in **T5.4**, an OCR "Remove gaps" tool in **T6.1**,
> a larger status-line spec in **T3.2**, test `ExportHygieneTest`, question **Q7**, checklist item **11**.
> Everything else is unchanged. Do not redo T0–T1.

> **Rev 3 — 2026-11-08, after G2 (alpha2 + 6 device screenshots, owner review).** G2 is **NOT passed yet**: the owner
> found real defects that 258 green tests did not catch. Rev 3 adds only: complaints **#14–#18**, decisions **N11–N12**,
> a new **Phase 3b** (T3.10–T3.13, fix-before-Phase-4) with mini-gate **G2b**, **T4.8** (delete also removes the app's own
> exported copies) and a re-ordering of Phase 4 (**T4.7 first**), a rewritten **§4.5 Document screen** and **T5.2**, mini-gate
> **G2c** after Phase 4, tests `StatusLineScreenTest` / `SelectionBarTest` / `DeleteExportsTest`, questions **Q8–Q9**, checklist
> items **12–14**. Everything else is unchanged. Do not redo T0–T3.9; only fix what Phase 3b names.

> **Target:** `versionCode = 26`, `versionName = "1.1.1-test26"`
> **Project root:** `/home/cybium/ScanWhirl` (WSL/Ubuntu) · **Package:** `com.scanwhirl.app.test` only
> **Branch:** `test26-redesign` (from tag `test25-final`)
> **Full build + test:** `cd /home/cybium/ScanWhirl && export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk" && ./gradlew test assembleRelease -PtestBuild --no-daemon`
> **One test class:** `./gradlew :app:testDebugUnitTest --tests "com.scanwhirl.app.ui.TextFitTest"` (plain `test` rejects `--tests`)

> **STATUS (2026-11-08):** T0–T3.13 committed (HEAD `906d158`). Phase-end build was 267 tests / 2 known-red TextFit
> (`home` flips green outside afternoon hours — its greeting string is shorter; see log T3.12). **Phase 3b fixed G2's
> defects #14–#16 + polish.** Next: **STOP at G2b — NO screenshots (owner, 2026-11-08).** Deliverables are in
> `/mnt/c/Users/Cybium/Downloads/`: `ScanWhirl-1.1.1-test26-alpha2b.apk`, the three `ScanWhirl-test26-*.md`, and
> `ScanWhirl-1.1.1-test26-source.zip`. The next session starts by reading the **md copies in Downloads** — the
> reviewer (Claude) edits them after seeing what was built, and **those edits are the gate review** (adopt like Rev 3;
> unchanged = passed) → then plan-mode review of the *revised* Phase 4 (T4.7 → T4.8 → T4.1–T4.6) → **G2c** → Phase 5.
> **How to continue in a new session:** read `docs/TEST26_HANDOFF.md` (or its copy in Downloads — check whether the
> Downloads copy was edited, that is the review), then the section **"PHASE 4"** below (order Rev 3), then §4.5 and
> T4.7/T4.8. As-run record: `docs/TEST26_LOG.md`.

---

## 0. How to use this file (read this first, coding agent)

This plan is written for a fast, lightweight coding model. Follow these rules:

1. **Read sections 1–5 once. Then do tasks in order**, `T<phase>.<n>`. One task = one commit.
2. **Keep every task small.** If a diff goes past ~150 lines, split it. Do not rewrite a whole
   file over 300 lines. Prefer *new files* plus small edits.
3. **Never "improve" things outside the current task.**
4. **Each commit must build.** After every task: `./gradlew :app:compileDebugKotlin -PtestBuild`.
   After every phase: the full build + test command above.
5. **Two failed attempts on the same problem → stop.** Write what you tried into
   `docs/BLOCKERS.md` and wait. Do not thrash.
6. **Phase gates (G1–G5) are real.** At each gate, build the APK, copy it to
   `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test26-alphaN.apk`, and **stop**.
   The owner installs it and sends screenshots. Do not start the next phase until they do.
7. **Green tests are NOT proof the UI is good.** test25 shipped with 209 green tests *and*
   clipped button labels, a truncated search hint and cosmetic rings. Tests asserted that views
   *exist*, not that text is *readable*. Phase 1 adds a test that checks readability.

---

## 1. Why this plan exists (candid post-mortem of test25)

test25 was a **reskin**, not a redesign. The plan treated these as untouchable:
83 locked view IDs, "existing test files are not modified", "dialogs must stay `AlertDialog`",
and "Phase 5 is wiring only, no new styles". With those walls up, the only possible move was
to repaint what already existed. The result:

- **Two unrelated apps in one.** `GlobeHomeActivity` (launcher; 465 lines + `GlobeScene` 393 +
  `HolographicEarthView` 906 = **1,764 lines**) has its own search, its own category chips, its
  own navigation and its own gestures. `MainActivity` (the Shelf; **2,224 lines**) has the real
  features. They share a document list and nothing else. Nothing in the app *needs* an Earth —
  it is a worse way to look at a list of files.
- **Hidden navigation.** The Shelf (where every tool lives) is reached by a pinch gesture or an
  unlabeled icon. There is no visible way from the Shelf back to the Earth.
- **Categories that cannot be used.** See D-table below: the category of a scan is guessed
  from its *name only*, so every "Document N" is "Other". There is no filter on the Shelf.
- **Visible defects that tests did not catch** (clipped labels, truncated hints, emoji).

### Rules of engagement for test26

**Lifted (these caused the timid redesign):**
- No locked view IDs. Rename, delete and add IDs freely.
- No "don't modify existing tests". **Tests follow the product.** If a screen/element is removed,
  delete its tests *in the same commit*. If it changes, rewrite them. Never keep a UI element only
  because a test mentions it.
- Dialogs need not be `AlertDialog`. Bottom sheets (`BottomSheetDialog`, Material 1.12.0 is
  already a dependency) are allowed. Rewrite tests that cast to `AlertDialog`.
- Old decisions **D2** (dialogs stay AlertDialog) and **D5** (gear on globe HUD) are retired.

**Kept (these protect the owner's data and the live Play app):**
- **Package/label:** test builds are `com.scanwhirl.app.test` / `ScanWhirl Test` only. Never
  touch the Play package config. Never touch `keystore/` or `keystore.properties`.
- **Stored data must keep loading.** Test24/25 users have documents on disk. Keep the on-disk
  format backward compatible: `docs/<id>/meta.txt` lines are `name`, `createdAt`, `category`
  **in that order** (only *append* lines, never reorder); `scan.pdf`, `page_NNN.jpg`,
  `ocr.txt`, lock and vault marker files. Any new category value must be readable by old logic
  as a fallback.
- **D1** Orbital dark-only theme. **D3** category colours (below). **D4** system fonts only
  (generic families `sans-serif-medium` and `monospace` are system fonts and are allowed;
  no bundled font files).
- Any `INFINITE` animator must check `SwMotion.ambientAllowed(context)` first (Robolectric
  hangs otherwise).
- On-device only. No network features, no analytics, no ads (matches `PRIVACY_POLICY.md`).
- No `git push`, no version bump to release, no Play build without explicit owner approval.

---

## 2. Diagnosis — every complaint, traced to a cause

| # | Owner complaint | Evidence | Root cause (verified in source) | Fix |
|---|---|---|---|---|
| 1 | "Ring names look weird" | Screenshot 2: `STUDY / RECEIPT / ID / FORM / OTHER` stacked as tiny overlapping text under the rings, while the chips below say `Study / Receipts / IDs / Forms / Other` | `HolographicEarthView.drawRingTracks` (~L502–545) draws all five labels with `drawTextOnPath` at the *same* bottom arc position on nested ellipses, so they pile up. `CategoryStyle.shortTag()` returns singular caps (`RECEIPT`, `ID`, `FORM`) while the chips use plurals — two naming systems. | Rings deleted (P7). **One** naming source: `CategoryRules.label()` everywhere (T1.3). |
| 2 | "Rotating rings bloat — why would anyone like it" | Screenshot 2 | Rings are decoration: nothing is selected, filtered or learned by rotating them. | Deleted (P7). |
| 3 | "Center empty, ok?" | Screenshot 2: big dead void between shrunken Earth and rings | `GlobeScene` places Earth and rings by fixed fractions of height (`bandY`), leaving unused space. | No Earth/rings. Home is content-first (§4). |
| 4 | "Nobody has time to pinch in to reach the button menu" | Screenshot 1/2 hint: "Pinch in for List"; Screenshot 5 | All tools (Resize, Compress, Edit, Merge, Pro Suite) live only on the Shelf, reached by `onPinchToShelf` or an unlabeled list icon in the HUD. Two steps from the launcher, one of them a hidden gesture. | One Home. Tools = one-tap bottom sheet (P3). Rule: **every gesture needs a visible button.** |
| 5 | "No way to sort categories of docs, then how can you give them labels" | Screenshot 5: both docs say OTHER | `ScanRepository.writeMeta` (L92) sets category = `CategoryRules.classify(name)` — **name keywords only**. "Document 3" can never match. `ScanReviewActivity` (save, L478) has a title field but **no category choice**; `saveScan` has **no category parameter**. The only way to change it is buried in the editor "Categorize" menu (`DocumentViewerActivity` ~L687). The Shelf has **no category filter** (chips exist only on the Globe), and its "Name" sort is by name/date/modified, not category. | Category chosen at save; chip-rail filter on Home; bulk "Move to"; Quick Sort sheet; tappable category chip; "Group by category" sort (P3, P4). |
| 6 | "Editing PDF UI — look at button text" | Screenshot 4: Rotate/Crop/Filter/Delete labels cut off at the bottom. Screenshot 3: "Share" cut off at right edge | **(a) Height:** `Widget.ScanWhirl.Button.Dock` (themes.xml L143) uses `iconGravity=top`; `activity_page_viewer.xml` sets each button to a fixed `layout_height=48dp`. Needed: 6 pad + 24 icon + 4 gap + ~17 text + 6 pad ≈ **57dp > 48dp**, so the label is clipped. **(b) Width:** `activity_document_viewer.xml` (~L237–277) has three `wrap_content` buttons in one row (Pro Tools, Delete, Share), 44dp tall; they overflow 360dp and Share is cut. **(c) Insets:** there is **no window-inset handling anywhere** (no `fitsSystemWindows`, no `WindowInsets` code, no `enableEdgeToEdge`) while `targetSdk = 36`, which forces edge-to-edge on Android 15+. **(verify on device which Android version; (a) and (b) are certain, (c) is a likely extra cause on 15+).** | Anti-clipping rules (§5), insets helper (T1.1), docks rebuilt (P5), automated text-fit test (T1.2). |
| 7 | "Search bar text — look at it" | Screenshot 5: hint reads "Search name or scanned tex" | `search_hint_pro` = "Search name or scanned text" (strings.xml L155) is too long, **and** the sort pill (icon + "Name" label) sits *inside the same bar* (`activity_main.xml` ~L108+), stealing the width. | Hint becomes "Search documents"; sort moves to the list header; OCR search becomes an "In text" chip (T3.5). Test asserts hint fits at 320dp (T1.2). |
| 8 | "Greeting emojis are Android keyboard text, nothing futuristic" | Screenshot 5: 🚀 "Afternoon! Let's organize" | `Greeting.kt` L9–20 hard-codes Unicode emoji (also `pro_panel_home` string has 🛡️). They render with the phone's emoji font, clashing with the cyan line-art style. Also English-only and not in string resources despite a `values-hi`. | Replace with a HUD **status line** `AFTERNOON · 2 DOCS · 14 MB` — no emoji, localizable (T1.5). Emoji guard test (T1.5). |
| 9 | "No way to go back to Earth, only the phone back button" | Screenshot 5 | Two-app split (see §1). `MainActivity.finish()` plays a warp back, but no visible control triggers it. | There is no Earth any more; Home is the root. Rule: every non-root screen has a visible Up arrow. |
| 10 | "Earth + rings cosmetic, useless" | all | True. Nothing functional depends on them. `UiSmokeTest` (78 tests) contains **0** references to the globe, so removal is low-risk. | Delete (P7). Reversible via git tag. |
| 11 | "OCR tool should have had edit option before copy" | — | OCR result is shown as a read-only `setMessage()` dialog with Copy/Share (`DocumentViewerActivity.runAndShowOcr` L504; `MainActivity.showOcrTextDialog` L1053). OCR output has line-break and spacing errors that need fixing before copy. | New full-screen **OCR Editor** with cleanup tools; Copy copies the *edited* text; edits persist for search (P6). |
| 12 | "You feared overhauling, got useless Earth and a separate app inside" | — | See §1: the test25 plan locked everything and defined Phase 5 as "wiring only". | This plan removes the locks and merges the two homes. |
| 13 | "Ghost PDF in Recent files" (found at G1, not in the original plan) | Android Files app > Recent shows `Document 2.pdf`, `Document 2 (1).pdf`, `(2)`, `(3)`, `Document 3 (1).pdf`, `Duo.pdf`. Sizes of `Document 2 (2).pdf` (249 kB) and `Document 3 (1).pdf` (14.78 MB) match the in-app sizes (243 KB and 14.1 MB as the app shows them, KiB/MiB) once units are converted, so they are copies of the current documents. | **(verified in the alpha1 APK bytecode)** `util/DownloadSaver.savePdf` never overwrites: `uniqueName()` appends ` (1)`, ` (2)` and inserts a new MediaStore row into `Documents/ScanWhirl` each time. It is called from: **`ScanReviewActivity.saveAndFinish` for Pro users, with no setting and no confirmation** (coroutine `saveAndFinish$1$1$1` reads `scan.pdf` and calls `savePdf`; the Pro condition is an `isPro` check right before the IO launch, so confirm it when you open the file), `DocumentViewerActivity.savePdfToDocuments` (download button), `ExportResultActivity.saveResult`, and the Compress / Lock / Unlock flows (they build names with `pdfNameFor`; the exact save call in those three was not traced, so read them in T4.7). So every scan saved in the app silently leaves a visible copy in the Files app, and every re-export adds another numbered copy. | Opt-in instead of silent, replace instead of duplicate, visible confirmation with Undo (T4.7, N10). |
| 14 | "Status line says 0 DOCS while one document is listed; says 2 DOCS on an empty Home" (G2, screenshots Home-empty and Home-with-docs; the same line read `1 DOC 4.4 MB` correctly one minute later) | G2 screenshots: `GOOD NIGHT · 0 DOCS · 0 B` above a list with 1 document (4.4 MB), and `2 DOCS · 0 B` above the empty state after both documents were deleted in the app. | **(hypothesis from the screenshots, confirm in code)** The status line is refreshed on some events only (it was right after leaving selection mode, wrong after first load and after delete), i.e. it is computed from a different call / earlier snapshot than the list the adapter shows. `StatusLineTest` only tests the formatter, so nothing tests that the *screen* shows the right numbers. | T3.10: one `renderHome()` path; status line derives from the same unfiltered list. New `StatusLineScreenTest`. |
| 15 | "Selection header reads `1 sel…`" (G2) | Selection mode: `X  1 sel…  Move  Merge  Export  Delete` on one 360dp row; the count is clipped. Merge is bright with 1 item selected. | Four text actions + count on one row (T3.7) — the same width-overflow class as complaint #6. | T3.12: count gets its own row; actions move to a bottom action bar (§4.2). |
| 16 | "Compress is broken into `Compres` / `s`" in the Tools sheet (G2) | Tools sheet, 4th tile of row 1. `Lock / Unlock` and `Long Image` also wrap. | Tile inner width (~58dp after padding) < the width of the word at 12sp, so the line breaks **inside** the word. `TextFitTest` stayed green because it checks ellipsis/height, not "is a word broken". | T3.11: padding/columns fix + a new longest-word-fits assertion. |
| 17 | "Edit screen three-dots list is long and duplicates the Pro Tools bar" (G2, Document screen) | Overflow lists: Save PDF to Documents, OCR Text, Long Image, Lock PDF, Hide in Vault, Categorize, **Rotate left, Rotate right, Delete page, Save this page**. The gold Pro Tools tray below lists Lock PDF / OCR Text / Long Image / Hide in Vault again. | Every new action was added to the overflow menu and to the tray (two entry points for the same thing), and page-level actions (rotate / delete page / save page) sit in a *document* menu where "which page?" is undefined. Also a crown "Pro Tools" button is shown to a user who is already Pro. | §4.5 rewrite + T5.2: one overflow only (the bottom `More`), page actions move to page selection / page editor, N12. |
| 18 | "Deleting inside ScanWhirl does not delete the document in the ScanWhirl folder" (G2, checked in Files app) | `Documents/ScanWhirl/*.pdf` stays after the in-app delete. | Not a regression: those files are the **exported copies** from complaint #13 (silent Pro copy on save, every download/export). In-app documents live in app-private storage; deleting a doc never touches the MediaStore copies, because nothing records which copies belong to which document. T4.7 stops *creating* silent copies but does not remove existing ones. | N11, T4.8: record exported copies per document, offer to delete them together with the document. |

---

## 3. Product decisions (new, numbered N1–N10)

| # | Decision | Notes |
|---|---|---|
| N1 | **One Home.** `MainActivity` becomes the launcher and the only home. The Earth is deleted (P7). | Class name `MainActivity` is kept to avoid churn. |
| N2 | **Categories are first-class.** Chosen at save, filterable on Home, changeable in 1–2 taps from anywhere, bulk-movable, and covered by a Quick Sort flow. | See P3–P4. |
| N3 | **Add `Unsorted`** as a stored category value. New scans with no confident match are `Unsorted` (was `Other`). `Other` stays as a deliberate user choice and as the fallback for old docs. | Old docs currently all `Other` because of the name-only guess; Quick Sort covers both `Unsorted` and `Other`. |
| N4 | **Display label = stored value** (`Study, Receipts, IDs, Forms, Other, Unsorted`). Singular caps tags (`RECEIPT`) are removed. | One naming source: `CategoryRules.label()`. |
| N5 | **Tools = bottom sheet**, one tap from Home. Pro tools sit in the same sheet under a "PRO" divider. The old 2×2 cards + collapsible Pro Suite are removed. | Frees ~40% of the Home screen. |
| N6 | **Bottom bar:** `Tools` · big `SCAN` (centre, thumb zone) · `Vault`. | Open question Q3 below. |
| N7 | **No emoji anywhere**; icons are vectors. Greeting becomes a status line. | Guard test. |
| N8 | **OCR Editor** replaces both OCR dialogs. | P6. |
| N9 | **"Futuristic" comes from the design language, not from a 3-D toy:** starfield window background (already exists), glass cards, neon category colours, HUD micro-typography, viewfinder corner brackets, a scan-line on processing. | P8. |
| N10 | **No silent copies outside the app.** Saving a scan never writes to `Documents/ScanWhirl` unless the owner turned on "Also save a copy to Documents" (default OFF). Every explicit export shows a confirmation with **Open** and **Undo**, and re-exporting the same document **replaces** its earlier copy instead of adding `(1)`, `(2)`. | Complaint #13. T4.7. Open question Q7. |
| N11 | **Delete means delete.** Every copy of a document that ScanWhirl itself wrote to `Documents/ScanWhirl` is recorded in that document's `meta.txt`. Deleting the document (from Home, selection mode or the Document screen) asks once, in the same confirm dialog: **"Also delete N saved PDF copies in Documents/ScanWhirl"** (checkbox, default **ON**, shown only when N > 0). Copies the app cannot delete (created by another install/package) are skipped and reported, never block the in-app delete. | Complaint #18. T4.8. Question Q8. |
| N12 | **Menu discipline.** An action has exactly one home: primary bar, or `More`, or a contextual bar. No action is listed in both a toolbar overflow and a tray. A document-level menu never contains page-level actions. A Pro user never sees a "Pro" button; Free users see a small `PRO` pill on locked rows. | Complaint #17. §4.5, T5.2. |

**Category colours (D3, unchanged) + new:**
Study `#38EF7D` · Receipts `#FF8A4C` · IDs `#5B9DFF` · Forms `#B07CFF` · Other `#94A3B8` ·
**Unsorted `#94A3B8` drawn with a dashed outline** (so "needs sorting" looks different from "Other").

---

## 4. Target architecture and screens

### 4.1 Screen map

```
                         ┌──────────────┐
   launcher ───────────▶ │   HOME       │  (MainActivity)
                         └──┬──┬──┬──┬──┘
        Scan button ───────┘  │  │  └──── Settings (gear)
   Tools sheet (1 tap) ───────┘  └─────── Vault (PIN)
        │                                       │
        ├─ Edit PDF / Merge / Compress / Resize │
        └─ PRO: Text (OCR) / Lock / Long image / Vault
                  │
 card tap ───▶ DOCUMENT (DocumentViewerActivity) ──▶ PAGE EDITOR (PageViewerActivity)
 scan done ──▶ SCAN REVIEW (ScanReviewActivity: name + CATEGORY + pages) ──▶ Home
 OCR       ──▶ OCR EDITOR (new OcrEditorActivity)
 Quick Sort ─▶ bottom sheet over Home
```

Every non-root screen has a visible Up arrow. No screen is reachable *only* by a gesture.

### 4.2 Home wireframe (360 dp wide)

```
┌────────────────────────────────────┐
│ ScanWhirl [PRO]                 ⚙  │  header, 56dp
│ AFTERNOON · 2 DOCS · 14 MB         │  status line, 11sp caps, 0.12 spacing
│ ┌────────────────────────────────┐ │
│ │ (search)  Search documents  (x)│ │  48dp, hint ≤ 20 chars
│ └────────────────────────────────┘ │
│ [All 2][Study 0][Receipts 0][IDs 0]│  chip rail, horizontal scroll
│ [Forms 0][Other 2]  ([Unsorted n]) │  Unsorted chip only when n > 0
│ ┌────────────────────────────────┐ │
│ │ 2 unsorted · Quick sort  ›     │ │  nudge, only when Unsorted/Other > 0
│ └────────────────────────────────┘ │
│ 2 documents        Date ▾    Select│  list header
│ ┌────────────────────────────────┐ │
│ │▌[thumb] Document 3      (view) │ │  category rail ▌ coloured
│ │ 3 pages · 14.1 MB · 7 Nov (edit)│ │
│ │ ● OTHER                        │ │  chip = tappable → category picker
│ └────────────────────────────────┘ │
│              …                     │
├────────────────────────────────────┤
│  [Tools]     (  SCAN  )     [Vault]│  bottom bar, inset-aware
└────────────────────────────────────┘
```

**Selection mode (Rev 3, replaces the one-row header of T3.7):**

```
┌────────────────────────────────────┐
│ [X]  2 selected              Select all │  header row: count never truncated
│ (search + chip rail stay visible)  │
│  list, selected cards outlined     │
├────────────────────────────────────┤
│ [Move] [Merge] [Export] [Delete]   │  contextual bottom action bar (replaces SCAN bar)
└────────────────────────────────────┘
```
Action bar = §5.4 dock-item spec (icon over label, weight 1, minHeight 56dp, inset-aware). Enable rules: `Move` disabled
until T4.3; `Merge` only when N ≥ 2; `Export` and `Delete` when N ≥ 1; `Delete` tinted with the danger colour. Back press /
X leaves selection and restores the SCAN bar.

### 4.3 Tools sheet

```
 Tools
 [Edit PDF] [Merge] [Compress] [Resize]
 ── PRO ──────────────────────────────
 [Text OCR] [Lock PDF] [Long image] [Vault]
```
4-column grid, each tile ≥ 80dp tall, label ≤ 2 lines at 12sp, **and no word may ever be broken across lines**
(Rev 3, complaint #16). If the longest label word does not fit the tile's inner width at the current width/font scale, the
sheet uses **3 columns** instead of 4 (rows are chunked per section: Free tiles, then the PRO divider, then Pro tiles).
Shorten `Lock / Unlock` to `Lock` (the tile already toggles by state). T4.4 adds a 9th tile (`Quick sort`), which also
fits a 3-column layout.

### 4.4 Scan Review (save) — category is chosen here

```
 <-  Review scan                     
 Name  [ Document 3            ]
 Category   [Study][Receipts][IDs][Forms][Other]   ← single-select, one pre-selected
            suggested: Other (from name)            ← updates live as the name is typed,
                                                      until the user picks one manually
 pages grid …
 [        Save as PDF        ]
```

### 4.5 Document screen (rewritten in Rev 3 — complaint #17, N12)

```
 <-  Document 3                           [download]      top bar: Up, title, ONE icon (explicit export)
 3 pages · 14.1 MB · ● OTHER (tap to change)              category chip -> picker (T4.6)
 [Select pages]                                           visible button; page checkboxes appear only after tapping it
 page grid …
 [   Share (weight 2, primary)   ] [ Edit ] [ Text ] [ More ]
```

- **No toolbar overflow (⋮) and no gold "Pro Tools" tray.** Delete the old overflow menu, `proToolsPanel`, the crown
  `Pro Tools` button and the `btnDelete`/`btnShare` row. **Check what the pencil icon in the top bar does:** if it is *Edit
  pages*, delete it (the bottom `Edit` replaces it); if it is *Rename*, remove the icon and make the title tappable, with
  `Rename` also in `More`.
- **`download` icon** = *Save a copy to Documents* (the only way a copy is made, N10). It shows the Snackbar with Open / Undo (T4.7).
- **`More` bottom sheet** (the single home for everything secondary, 3 short groups, same handlers as today):
  - *Organize*: Rename · Move to category · Duplicate
  - *Tools*: Compress · Lock / Unlock PDF *(PRO)* · Long image *(PRO)* · Hide in Vault *(PRO)*
  - *Danger* (visually separated, red): **Delete document** (existing danger confirm, which also carries the T4.8 checkbox)
  - OCR is **not** repeated here: `Text` in the bottom bar is its entry point.
- **Removed from the document level entirely:** `Rotate left`, `Rotate right`, `Delete page`, `Save this page`,
  `Categorize` (chip replaces it), `Save PDF to Documents` (download icon replaces it). Page actions live in two places only:
  1. *Select pages* mode → contextual bar `Rotate · Extract · Delete` acting on the ticked pages;
  2. the Page editor dock (§4.6) for one page.
- Free users see the three Pro rows with a small `PRO` pill (tap → `showProPanel()`); Pro users see no badge at all.
- Page checkboxes and drag handles are **not** shown permanently; checkboxes only in *Select pages* mode; the reorder handle may
  stay if drag-reorder has no other visible entry (R7) — keep it small and low-contrast.

### 4.6 Page editor
Bottom dock: **Rotate · Crop · Filter · Delete**, equal weight, ≥ 64dp tall, inset-aware (spec in §5.4).

### 4.7 OCR Editor

```
 <-  Extracted text                      [⋮ Copy all · Share all · Save .txt · Re-scan]
 [Page 1][Page 2][Page 3]               (only if > 1 page)
 ┌──────────────────────────────────┐
 │ editable text …                  │
 └──────────────────────────────────┘
 312 words · edited
 [Join lines] [Fix spaces] [Undo]
 [   Copy   ] [ Share ] [ Save ]
```
Back with unsaved edits → confirm (Save / Discard / Cancel).

### 4.8 Quick Sort (bottom sheet over Home)

```
 Quick sort            2 of 7
 [ big thumbnail ]
 Document 3 · 3 pages
 [Study][Receipts][IDs][Forms][Other]     [Skip]
```
Picking a category saves instantly and advances. Ends with "All sorted".

---

## 5. Design system v2

### 5.1 Anti-clipping rules (these exist because of screenshots 3, 4 and 5)

- **R1** No control that contains text gets a fixed `layout_height`. Use `wrap_content` +
  `minHeight`.
- **R2** Minimum text size **12sp**. Exception: ALL-CAPS micro labels may be 11sp with
  `letterSpacing 0.12`. (`item_scan.xml` catChip is 10sp today — fix it.)
- **R3** Hints/labels shown in a bar must fit at **320dp width, font scale 1.3**.
- **R4** Rows of buttons use `layout_width=0dp` + `layout_weight`, never `wrap_content`.
- **R5** Every screen root applies system-bar insets (T1.1). Bottom bars sit *above* the
  navigation bar.
- **R6** Touch targets ≥ 48dp.
- **R7** Every gesture has a visible button equivalent.
- **R8** No emoji in strings or code.
- **R9** One naming source for categories (`CategoryRules.label()`).
- **R10** Automated check: `TextFitTest` (T1.2) runs on every screen at `w320dp` and
  font scale 1.0 and 1.3.

### 5.2 Tokens
Spacing 4 / 8 / 12 / 16 / 24 dp · card radius 16dp · chip radius full (18dp) · sheet top radius 24dp ·
hairline stroke 1dp `cyan_border_hair`, selected 2dp · elevation 0 (glass) ·
type: Title 22sp bold · Section 16sp `sans-serif-medium` · Body 14sp · Meta 12sp ·
Micro caps 11sp. Numbers in the status line and counts may use `monospace`.

### 5.3 Components to create (new styles allowed this time)
`Widget.ScanWhirl.Chip` (filter chip: 36dp min height, category colour when checked) ·
`Widget.ScanWhirl.Button.DockItem` (icon over label, see 5.4) · `view_tool_tile.xml` ·
`bg_chip_unsorted_dashed.xml` · `sheet_tools.xml` · `sheet_quick_sort.xml` ·
`sheet_category_picker.xml` · `activity_ocr_editor.xml`.

### 5.4 Dock item spec (fixes screenshot 4)
```
container : LinearLayout horizontal, bg_dock_glass, padding 6dp, margin 8dp horizontal,
            sits above nav bar via insets
item      : MaterialButton, style Button.DockItem (TextButton), width 0dp weight 1,
            height wrap_content, minHeight 56dp, insetTop/insetBottom 0dp,
            paddingTop/Bottom 6dp, icon 24dp, iconGravity textTop, iconPadding 4dp,
            textSize 12sp, maxLines 1
required height = 6 + 24 + 4 + ~17 + 6 = 57dp  → minHeight 56dp + wrap_content is safe
```

---

## 6. Phases and tasks

Legend: **Do** = what to change · **Files** = where · **Done when** = how you know.
Commit message format: `test26 T<n>.<m>: <short summary>`.

---

### PHASE 0 — Safety net (≈15 min)

**T0.1** `git tag test25-final && git switch -c test26-redesign`.
**T0.2** Run the full build + test once. Record the baseline (expected 209 tests per variant,
0 failures) in `docs/TEST26_LOG.md`.
**T0.3** `mkdir docs/archive && git mv docs/SCANWHIRL_TEST25_MASTER_PLAN.md docs/TEST25_EXECUTION_REPORT.md docs/archive/`
and add this file as `docs/SCANWHIRL_TEST26_REDESIGN_PLAN.md`.
**Done when:** branch exists, baseline recorded, plan committed.

---

### PHASE 1 — Foundations (insets, text-fit test, category model, status line)

**T1.1 Insets helper.**
- Do: create `ui/Insets.kt` with `fun View.applySystemBarsPadding(top: Boolean = true, bottom: Boolean = true)`
  using `ViewCompat.setOnApplyWindowInsetsListener`; it adds the system-bar (and IME for bottom)
  insets to the view's *original* padding (store originals once). In `BaseActivity.onCreate`
  call `WindowCompat.setDecorFitsSystemWindows(window, false)` so behaviour is identical on every
  Android version. Because `adjustResize` stops working on some API levels with edge-to-edge,
  include `WindowInsetsCompat.Type.ime()` in the bottom inset.
- Apply to each activity's root view (`binding.root` or its first child) in `onCreate`.
  Docks/bottom bars must be *inside* the padded root so they rise above the nav bar.
- Files: `ui/Insets.kt` (new), `ui/BaseActivity.kt`, every `*Activity.kt` that inflates a layout.
- Done when: app compiles, all screens still render, no screen draws under the nav bar
  **(verify on device at G1)**.

**T1.2 `TextFitTest` — the readability guard (write it RED first).**
- Do: `app/src/test/java/com/scanwhirl/app/ui/TextFitTest.kt`. Use
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)` (otherwise text measures to garbage — see gotchas §8),
  `@Config(qualifiers = "w320dp-h640dp-xhdpi")`, and run each screen twice:
  `RuntimeEnvironment.setFontScale(1.0f)` and `1.3f`. Inflate, `measure/layout` the root, apply
  synthetic system-bar insets (top 48dp, bottom 48dp) via `ViewCompat.dispatchApplyWindowInsets`.
- Helper `assertNoClippedText(root, where)`: walk all visible views; for each `TextView` with text:
  1. `layout != null`;
  2. widest line ≤ `width − totalPaddingLeft − totalPaddingRight`;
  3. `layout.height + totalPaddingTop + totalPaddingBottom ≤ view.height` (`totalPaddingTop`
     already includes a top icon on `MaterialButton`);
  4. for `maxLines == 1` or ellipsized labels: `getEllipsisCount(line) == 0`;
  5. for `EditText`: `paint.measureText(hint) ≤ width − paddings` (this catches the search hint);
  6. the view's bottom/right edge lies inside its parent and inside the root.
  Collect *all* problems into one message so a failure lists every offender.
- Screens covered now: Home (`MainActivity`), `DocumentViewerActivity`, `PageViewerActivity`,
  `ScanReviewActivity`, `SettingsActivity`. Add each new screen in the phase that creates it.
- **Red proof (required):** run it against the unmodified layouts. It *should* flag the
  page-viewer dock labels and the document-viewer Share button. Paste the failure output into
  `docs/TEST26_LOG.md`. If Robolectric does not reproduce the clipping, fall back to a plain
  XML-dimension assertion (dock buttons: `minHeight ≥ 56dp`, no fixed `layout_height` on any
  button) and say so in the log.
- Done when: test exists, fails on test25 layouts for the right reasons, and is added to the
  list of tests that **must be green at every phase end** from P5 onward.

**T1.3 Category model.**
- Do in `util/CategoryRules.kt`:
  - add `const val UNSORTED = "Unsorted"`; keep `ALL` = Study, Receipts, IDs, Forms, Other
    (picker order) and add `val ALL_WITH_UNSORTED`; `isValid()` accepts all six.
  - `fun label(category: String): String` (= the value itself; single source of truth).
  - `fun classify(name: String, ocrText: String? = null): String` — returns `UNSORTED` when nothing
    matches (was `OTHER`). Name rules stay as today. OCR rules: count keyword hits per category
    in the text (receipts: total, subtotal, GST, invoice, amount, qty, paid; IDs: date of birth,
    DOB, Aadhaar, UID, passport, licence/license, valid till, father's name; forms: applicant,
    signature, "please fill", declaration, form no; study: chapter, exercise, theorem, question,
    marks, syllabus, unit). Return a category only with **≥ 2 hits and a clear winner**; else `UNSORTED`.
  - `loadDocument` fallback (missing/invalid meta line) stays `OTHER` so old docs are unchanged.
- In `util/CategoryStyle.kt`: add `COLOR_UNSORTED`, delete `shortTag/tagForRing/colorForRing/ringIndex`
  (ring-only); callers use `CategoryRules.label(cat).uppercase()`.
- Update `CategoryRulesTest` and `CategoryPersistTest` (e.g. `"Document 3"` → `Unsorted`);
  add OCR-sample tests: `"TOTAL 450.00 GST Invoice No 12"` → Receipts; `"Date of Birth Aadhaar"` → IDs;
  `"Application Form applicant signature"` → Forms; `"Chapter 3 Exercise marks"` → Study;
  `"hello world"` → Unsorted.
- Done when: `CategoryRulesTest`, `CategoryPersistTest` green. (Globe code that used the removed
  helpers still compiles via a temporary shim; it is deleted in P7.)

**T1.4 Repository API.**
- Do in `store/ScanRepository.kt`:
  - `saveScan(..., category: String? = null)` → pass to `writeMeta(dir, name, category)`.
    (`writeMeta` already accepts it; `saveScan` just never passed one.)
  - `fun setCategory(ids: List<String>, category: String)` (bulk; one pass per doc, ignore missing).
  - Public `fun updateOcrPage(id: String, pageKey: String, text: String)`: read `ocrDoc(id)` (~L660),
    replace one entry, write via `writeOcr` (L711, currently `internal`). Read both functions first
    to keep the `ocr.txt` format byte-compatible.
  - Check where search reads OCR text (`SearchMatcher` / `ocrDoc` callers) so edits are searchable.
- Tests: extend `ScanRepositoryTest` — save with category; bulk set; `updateOcrPage` round-trip;
  a scan with old 3-line `meta.txt` still loads.
- Done when: those tests green.

**T1.5 Status line + emoji guard.**
- Do: add string resources `status_morning|afternoon|evening|night` ("Good morning", …) in
  `values/strings.xml`; replace `Greeting.forHour(Int)` with
  `StatusLine.phase(hour): Int` (string res id; 05–11 morning, 12–16 afternoon, 17–20 evening, else night)
  and `StatusLine.format(context, hour, docCount, totalBytes)` →
  `"GOOD AFTERNOON · 2 DOCS · 14 MB"` (use a plural resource for DOCS; reuse `formatBytes`).
  Remove emoji from `pro_panel_home` (strings.xml L245) and `pro_panel_free` (L328) — both use
  🛡️ / 📝 bullets — plus the same strings in `values-hi`. A scan of `src/main` found emoji only in
  `Greeting.kt` and those two strings.
- Test: `EmojiGuardTest` scans `res/values*/strings.xml` and `src/main/**/*.kt` for emoji code
  points (ranges `U+1F300–1FAFF`, `U+2600–27BF`, `U+2B50`) and fails on any hit. Rewrite
  `GreetingTest` to cover `StatusLine`.
- Done when: both tests green.

**G1 (gate):** build `alpha1`; owner checks nothing draws under the nav bar on any screen.

**G1 result (recorded by owner + Claude, 2026-11-07): PASSED with notes.** APK confirmed to contain
`StatusLine`, `InsetsKt`, `CategoryRules.classifyOcr`, no `Greeting`; package `com.scanwhirl.app.test`, label
`ScanWhirl Test`, launcher still `GlobeHomeActivity` (correct until T3.9), versionName still `1.1.1-test25` (correct until T9.1).
Globe and Shelf screens show nothing drawn under the status or navigation bar. Ring labels now read plural
(`STUDY / RECEIPTS / IDS / FORMS / OTHER`) = T1.3 `label()` working. Not yet seen on device: Settings, Document screen,
Page editor, Scan Review (they are rebuilt in P5, so they are re-checked at G3). The things the owner still sees
(rings, read-only OCR dialog, truncated search hint, plain status line, FAB over list buttons) are all scheduled below;
the only unscheduled one was #13, now T4.7.

---

### PHASE 2 — Untangle `MainActivity` (pure refactor, zero visible change)

Goal: `MainActivity.kt` from 2,224 lines to **< 900**, so the Home rebuild in P3 is safe for a
small model. Re-run `grep -n "private fun" ui/MainActivity.kt` to get exact current line numbers;
the ranges below are approximate.

**Method for every task here:** create a class in `app/src/main/java/com/scanwhirl/app/tools/`
that takes `(activity: AppCompatActivity, repo: ScanRepository, host: HomeHost)`. Move the
functions **verbatim**; change only what the move forces. `registerForActivityResult` launchers
**must stay registered in `MainActivity`** (they must exist before `STARTED`); pass the
launchers in as lambdas. `HomeHost` is a tiny interface:
`refreshList()`, `showProgress(): AlertDialog`, `toast(...)`. One flow per commit; run the full
test suite after each. **No layout or behaviour changes.**

| Task | New file | Moves (≈ lines in MainActivity) |
|---|---|---|
| T2.1 | `tools/ResizeFlow.kt` | `startResize`, `showResizeDialog`, `doResize`, `derive` (≈1446–1612) |
| T2.2 | `tools/CompressFlow.kt` | `startCompress`, `pickScan`, `estimate*`, `pickLevel`, `runCompress*`, `fit*`, `showCompressResult` (≈1612–1935) |
| T2.3 | `tools/MergeFlow.kt` | `showMergeChooser`, `showMergePicker`, `promptMerge*`, `doMerge` (≈1280–1384, **not** `showSortMenu`) |
| T2.4 | `tools/LockFlow.kt` | `promptLockOrUnlockScan`, `promptNewPasswordForScan`, `chooseLockSource`, `lockDevicePdf`, `runLockDevicePdf`, `unlockDevicePdf`, `promptDeviceUnlockPassword`, `runUnlockDevicePdf` (≈626–692, 799–995) |
| T2.5 | `tools/VaultFlow.kt` | `openVaultWithPin`, `promptSetVaultPin`, `addToVault`, `moveDocToVault`, `importPdfToVault` (≈523–593, 1138–1208) |
| T2.6 | `tools/LongImageFlow.kt` | `runLongImageForScan`, `runLongImageForPdf` (≈693–741, 1078–1137) |
| T2.7 | `tools/OcrFlow.kt` | `runOcrForScan`, `runOcrForPdf`, `runOcrForImage`, `showOcrTextDialog` (≈594–625, 996–1077) — **still shows the old dialog**; replaced in P6 |
| T2.8 | `tools/ScanLauncher.kt` | `launchScanner`, `stageAndReview`, `offerUnfinishedScan` (≈238–256, 1978–2050) |

**Done when (whole phase):** same test count green as baseline, `MainActivity.kt` < 900 lines,
`git diff --stat` is mostly deletions in `MainActivity` and equal additions in `tools/`.

---

### PHASE 3 — The new Home (inside `MainActivity`)

Rebuild `activity_main.xml` and the binding code. IDs are free to change; update
`UiSmokeTest` in the same commit as each change.

**T3.1 Layout skeleton.** Rewrite `activity_main.xml` to the §4.2 structure: `homeRoot`
(insets applied) → header → search → chip rail → nudge → list header → `RecyclerView` → bottom
bar. Empty views for now; app must launch and list docs.

**T3.2 Header + status line.** Wordmark, Pro badge, gear (opens `SettingsActivity`), status line
from `StatusLine.format`. Settings toggle text becomes "Status line"; the toggle still hides it. Style it now, do not wait for P8: 11sp caps, `letterSpacing 0.12`, colour `cyan_dim`, numbers in `monospace`, separators ` · ` in `cyan_border_hair`, and the PRO badge as a small outlined chip. (At G1 the plain line read as lacklustre.) T8.1 then only adds the signal dot.

**T3.3 Filter logic (pure + tested).** New `util/HomeFilter.kt`:
`data class HomeState(query: String, category: String?, sort: String, inText: Boolean)`,
`fun apply(docs, state, ocrLookup): List<ScanDocument>` (reuse `SearchMatcher`), and
`fun counts(docs): Map<String, Int>`. `HomeFilterTest`: each category, search + category
combined, all four sorts (Date, Name, Modified, **Category** — add `Prefs.SORT_CATEGORY`),
counts. This is the only logic the screen depends on; keep the Activity dumb.

**T3.4 Category chip rail.** `ChipGroup` single-selection, `Widget.ScanWhirl.Chip`. Order:
All, (Unsorted only if n>0), Study, Receipts, IDs, Forms, Other. Each chip shows its count and
is tinted with its category colour when checked. Tap = filter. Tap the checked chip = back to All.

**T3.5 Search + list header.** Search field: hint `Search documents` (string `search_hint`, ≤ 20
chars; delete `search_hint_pro`), clear (×) button. **No sort control inside the bar.** An
"In text" chip appears under the field only when Pro and the field has focus (replaces the
long Pro hint). List header: left = `"N documents"` or `"N in Study"`; middle = sort selector
(`PopupMenu` anchored to the label "Date ▾"); right = `Select`. Remove `sortLabel`/`btnSort` pill.

**T3.6 Bottom bar + Tools sheet.** Bottom bar: `Tools` (left), `SCAN` (centre, 64dp, viewfinder
ring), `Vault` (right, lock vector). `Tools` opens `BottomSheetDialog` from `sheet_tools.xml`
(§4.3, 4-column grid of `view_tool_tile`). Tiles call the P2 flows. Pro tiles use the existing
`ProGate`. **Delete** the 4 tool cards, `proSuiteCard`, `proSuiteChevron` and their toggle
code. `Vault` calls `VaultFlow.openWithPin()`.

**T3.7 Selection mode.** Long-press or `Select` enters it. Contextual header:
`X · N selected · Move · Merge · Export · Delete`. `Move` opens the category picker (P4 T4.3).
Hide the bottom bar while selecting. Keep the existing Export-ZIP and delete-confirm logic.

**T3.8 Empty states.** No docs: viewfinder brackets (`frame_corners`) + "Scan your first
document" + the SCAN button. Filter has no results: "No <Category> documents yet" + `Show all`.

**T3.9 Make Home the launcher.** In `AndroidManifest.xml` move the `MAIN/LAUNCHER`
intent-filter from `GlobeHomeActivity` to `MainActivity`. Remove `MainActivity.finish()`'s warp
override and the `Prefs.globeHomeEnabled` branch. `GlobeHomeActivity` stays in the tree
(unreachable) until P7 so P3 can be reverted easily.

**Tests this phase:** `UiSmokeTest` rewritten where it touched removed IDs; new
`HomeScreenTest` (chip tap filters list; count labels; tools sheet opens and lists 8 tiles;
search hint visible; empty state; selection mode shows Move). Add Home + Tools sheet to
`TextFitTest`.

**G2 (gate):** `alpha2`. Owner screenshots: Home with 0 docs, Home with docs, a chip selected,
search focused, Tools sheet, selection mode.

---

### PHASE 3b — G2 fixes (new in Rev 3, do these BEFORE Phase 4)

Source: owner review of alpha2 (screenshots: Home 0 docs, Home with docs, Unsorted chip + Tools sheet, selection mode, Document
screen ×2). Everything else on Home the owner called good — **do not redesign anything not named here.** Each task is one
commit, `UiSmokeTest`/`HomeScreenTest` updated in the same commit. Build mode; no separate plan review needed.

**T3.10 Status line shows the truth (complaint #14).**
First *find the cause*: grep where `StatusLine.format` is called and where the adapter list is submitted. Fix so there is **one**
`renderHome()` (or equivalent) that, from the **unfiltered** document list, updates the status line, chip counts, nudge input, and
from the filtered list updates the adapter + list header. Call it after: first load, `onResume`, delete (single/bulk), import,
save, `setCategory`, leaving selection mode. Size = sum of the PDF/document sizes the card already shows (never `0 B` when a
document has size). Singular/plural already handled by the formatter.
Tests — new `StatusLineScreenTest` (Robolectric NATIVE): launch with 1 stored doc → `statusLine` text contains `1 DOC` and the
same size the card shows; add a doc → `2 DOCS`; delete both via the repo + refresh → `0 DOCS` and the empty state; filtering by
a category does **not** change the status line. Write the red proof first (it must fail on alpha2's code).

**T3.11 Tools sheet: no broken words (complaint #16).**
(a) In `view_tool_tile`: horizontal padding ≤ 2dp, label 12sp `maxLines=2`, `hyphenationFrequency=none`, `breakStrategy=simple`;
sheet side padding 16dp→12dp, tile gap 8dp→6dp. (b) Column rule from §4.3: if the longest label word still does not fit the
tile's inner width (measure with the tile's real `TextPaint`) → 3 columns, chunking each section into rows. (c) Label
`Lock / Unlock` → `Lock` (new/changed string only in `strings.xml`, + `values-hi` where the key exists).
Tests: extend the Tools-sheet case of `TextFitTest` with a **longest-word assertion** for every tile label (`measureText(word) <=
textView.width − paddings`) at `w320dp` × font scale 1.0 and 1.3. **Red-proof it first** by forcing a too-narrow tile (as T1.2 did);
if Robolectric reports zero widths the assertion is vacuous — then add an XML-dimension fallback and say so in the log. `HomeScreenTest`
"tools sheet lists 8 tiles" must stay green (8 tiles, any column count; update the old "rows 4/4" assertion to "sections 4 + 4").

**T3.12 Selection mode: count never clipped, actions in a bottom bar (complaint #15).**
Per §4.2 (Rev 3). Create `Widget.ScanWhirl.Button.DockItem` here (spec §5.4); T5.1 reuses it. Header row = `X` + full `N selected`
label + `Select all` text button. Actions = contextual bottom action bar replacing the SCAN bar while selecting (this *amends* the
approved T3.7 rule "bottom bar hidden while selecting" — same intent, better use of the space; note the amendment in the log).
Enable rules in §4.2. Keep Export-ZIP and delete-confirm logic untouched.
Tests — new `SelectionBarTest`: N=1 → header text is exactly the full string and `Merge` disabled; N=2 → `Merge` enabled; `Select all`
selects every visible card; Back exits selection and the SCAN bar returns; header string passes the text-fit check at 320dp ×1.3 with N=12.

**T3.13 Home polish (tiny, one commit, ≤ 40 lines total).**
(a) Bottom bar: 12dp side margins and 8dp above the navigation bar so its rounded corners no longer touch the screen edges.
(b) Fresh installs default to sort **Date (newest first)** as drawn in §4.2 (existing users keep their stored preference; the
alpha2 screenshots show `Name ▾`, check which default is set). (c) Empty state: add one muted line under "Scan your first
document" — `Tap the camera button to start` (new string `empty_first_scan_hint`, en + hi). Nothing else.

**G2b (mini-gate):** build `ScanWhirl-1.1.1-test26-alpha2b.apk` → Downloads → **STOP**. Owner screenshots (4): Home with docs
(status line right) · Tools sheet · selection mode (1 selected, and 2 selected) · **search focused** (this one was missing at G2:
confirm the clear × and, on Pro, the `In text` chip). Suite expectation: new tests green, only the 3 documented TextFit reds remain
(Tools-sheet TextFit case must be green *and* real).

---

### PHASE 4 — Categories become usable

**Order (Rev 3): T4.7 → T4.8 → T4.1 → T4.2 → T4.3 → T4.4 → (T4.5) → T4.6.** T4.7 goes first because it edits the same
`ScanReviewActivity.saveAndFinish` call site as T4.1 and because the owner is already seeing ghost files in Files > Recent.

**T4.1 Choose category when saving.** `activity_scan_review.xml`: add a `categoryRail`
(single-select chips) under the title field. Pre-select `CategoryRules.classify(title)`; if that is
`UNSORTED`, show **no** chip selected and the caption "Not sorted yet — pick one or skip".
Re-suggest live as the name changes **until the user taps a chip by hand**. Pass the result to
`repo.saveScan(..., category = chosen)` (call site `ScanReviewActivity` ≈ L478).

**T4.2 Category picker sheet.** `sheet_category_picker.xml` + `CategoryPickerSheet.show(activity,
current, onPick)`: five coloured rows (+ Unsorted when current is Unsorted). Used by T4.3, T4.4, T4.6.

**T4.3 Change category from Home.** Card category chip is clickable → picker → `repo.setCategory`.
Long-press quick-actions sheet: Rename, Move to category, Share, Delete. Bulk `Move` in selection mode
→ picker → `repo.setCategory(ids, cat)` → exit selection → toast "Moved N to Study".

**T4.4 Quick Sort.** Pure `util/QuickSortQueue.kt` (`current()`, `pick(category)`, `skip()`,
`progress()`; built from docs whose category is `Unsorted` or `Other`) with a unit test, plus
`sheet_quick_sort.xml` (§4.8). Entry points: the Home nudge ("N unsorted · Quick sort ›") and an
extra tile in the Tools sheet. Picking saves immediately and calls
`view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)`.

**T4.5 (optional, Pro) OCR suggestion.** In Quick Sort, a `Suggest` button runs OCR on page 1 only
(`OcrEngine.recognize`), then `CategoryRules.classify(name, text)`, and highlights the suggested
chip (never auto-applies). Skip if it slips — the rest of P4 stands alone.

**T4.6 Document screen chip.** `docCategoryChip` becomes clickable → picker. Remove the old
"Categorize" list dialog (`DocumentViewerActivity` ≈ L687) once the chip works.

**T4.7 Export hygiene (complaint #13, N10).**
- Do in `util/DownloadSaver.kt` and its callers (read each caller first; keep every change small):
  1. **Stop the silent copy.** In `ScanReviewActivity.saveAndFinish` (same call site T4.1 edits) remove the
     unconditional Pro `DownloadSaver.savePdf` coroutine. Replace it with a check of new `Prefs.autoCopyToDocuments`
     (default **false**). When true, behave exactly as today.
  2. **Replace, don't duplicate.** Add `savePdf(context, bytes, displayName, replaceExisting: Boolean = true)`. On Android 10+ query
     MediaStore for `relative_path='Documents/ScanWhirl/'` and `_display_name=displayName`; if a row exists that the app can
     open for writing, overwrite it with `openOutputStream(uri, "wt")` and return the same name. If it cannot be opened (file
     was created by an older install) fall back to the current `uniqueName` behaviour. Pre-Q: overwrite the `File` if it exists.
  3. **Say what happened.** Replace the plain "Saved to Documents/ScanWhirl: X" toast in `DocumentViewerActivity.savePdfToDocuments`,
     `ExportResultActivity.saveResult` and Compress/Lock/Unlock results with a `Snackbar`: "Saved to Documents/ScanWhirl/X.pdf"
     + **Open** (ACTION_VIEW on the returned Uri) + **Undo** (deletes that MediaStore row). `DownloadSaver.savePdf` must therefore
     return the `Uri` as well as the name (add a small `SavedFile(uri, name)` result type; keep the old String API as a wrapper so
     `DownloadSaverTest` stays green).
  4. **Settings** row for the toggle is in T5.4.
- T4.7 itself does not delete or rename anything already in the owner's `Documents/ScanWhirl` folder. Removing copies on delete is T4.8, and only for copies the app can prove are its own.
- Tests: new `ExportHygieneTest` (Robolectric): saving a scan with the pref OFF does not call the saver; with the pref ON it does;
  exporting the same name twice leaves one row (replace) and returns the same name; Undo removes the row; fallback to ` (1)` when
  the existing row is not writable. Existing `DownloadSaverTest` must stay green untouched.
- Done when: `ExportHygieneTest` and `DownloadSaverTest` green; on device, scanning a doc and saving it leaves **no new file** in
  Files > Recent, and tapping the download button twice leaves **one** file.

**T4.8 Delete removes the app's own exported copies (complaint #18, N11, Q8).**
Depends on T4.7 (`SavedFile(uri, name)`).
1. **Record.** Every successful `DownloadSaver.savePdf` made on behalf of a document (download button, export result, compress/lock/unlock,
   the opt-in auto-copy) appends the returned `uri` to that document's `meta.txt` as one extra line `exports=<uri1>|<uri2>`.
   Read the current `writeMeta`/`setCategory`/reader **first**: old docs have 2–3 lines and no trailing newline (§8), the new key must
   be optional, unknown/missing keys must survive a `setCategory` rewrite, and old builds must ignore the line. Add `ScanRepository`
   helpers `addExport(id, uri)`, `exportsOf(id): List<Uri>`; keep every existing repo test untouched.
2. **Find legacy copies (best effort).** For documents that predate this change, also query MediaStore
   (`relative_path='Documents/ScanWhirl/'`, rows **owned by this package**) for `_display_name` equal to `<docName>.pdf` or
   `<docName> (n).pdf`, and include those in the count. Never match by anything looser.
3. **Delete flow.** In the existing delete confirm dialog (Home single/bulk, selection mode, Document screen `More`), when the
   total number of copies N > 0 add a checkbox row **"Also delete N saved PDF copy/copies in Documents/ScanWhirl"** (plural
   resource, en + hi), default **checked** (Q8). On confirm: delete the in-app document first (never blocked), then try
   `contentResolver.delete(uri, null, null)` for each copy inside try/catch. A copy that throws `SecurityException` /
   `RecoverableSecurityException` (created by another package, e.g. the Play app or an older install) is **skipped**; do not launch
   permission prompts. Show a Snackbar: "Deleted. 2 copies removed." or "Deleted. 1 copy removed, 1 could not be removed (delete it in Files)."
4. **Never** touch a file the app did not write or cannot prove it wrote, and never touch anything when the box is unchecked.
- Tests — new `DeleteExportsTest` (Robolectric, fake MediaStore as `DownloadSaverTest` does): export twice (replace → 1 row) →
  `exportsOf` has the uri; delete with box checked → row gone and doc gone; unchecked → row stays; foreign-owned row → skipped,
  doc still deleted, message reports it; legacy name match only hits `<name>.pdf` / `<name> (n).pdf` owned by the package; old
  2-line `meta.txt` still reads and survives `setCategory`.
- Done when, on the device: scan + save + tap download, then delete the document with the box checked → `Documents/ScanWhirl`
  no longer has it. (Copies made by the earlier alpha installs may be reported as "could not be removed" — **verify on device**
  whether Android lets the reinstalled test package delete them; if not, that is expected and the owner clears them in Files once.)

**Tests:** `ScanReviewCategoryTest` (pick chip → saved doc has that category; typing "Invoice May"
preselects Receipts; manual pick stops live re-suggest), `QuickSortQueueTest`, Home test for bulk
Move. Add `ScanReviewActivity` to `TextFitTest`.

**G2c (mini-gate, new in Rev 3):** after Phase 4 build `ScanWhirl-1.1.1-test26-alpha2c.apk` → Downloads → **STOP**. Phase 4
changes what is written to the phone's storage, so the owner must see it on the device before Phase 5. Owner checks:
(1) scan + save → open Files > Recent: **no new file**; (2) Document screen download twice → **one** file + Snackbar Open/Undo;
(3) delete that document with the new checkbox → the file is gone from `Documents/ScanWhirl`; (4) pick a category on the save
screen → Home chip rail filters by it; (5) Move + Quick sort work. Screenshots: Scan Review with category rail, delete dialog with
the checkbox, Quick sort sheet, Home with the nudge.

---

### PHASE 5 — Viewers/editors with no clipped text

**T5.1 Page editor dock (screenshot 4).** `activity_page_viewer.xml`: rebuild the dock to §5.4.
Reuse `Widget.ScanWhirl.Button.DockItem` (created in T3.12); delete the fixed 48dp heights; weights 1/1/1/1;
inset-aware. Check the top bar and the page counter against the status bar too.

**T5.2 Document screen (screenshots 3 and G2 Document-screen ×2; complaints #6b, #17; N12).** Implement §4.5 exactly:
bottom bar `Share` (weight 2, primary) · `Edit` · `Text` · `More`, built from `DockItem` with `0dp` weights, no fixed heights.
Delete: the toolbar overflow menu (`menu_document*.xml` and its `onOptionsItemSelected` branches that only the removed items used),
`proToolsPanel`, the crown `btnProTools`, `btnDelete`, `btnShare` row and their wiring. `More` = `BottomSheetDialog` with the three groups
in §4.5 calling the **same existing handlers** (Rename, Duplicate, Compress, Lock/Unlock, Long image, Hide in Vault, Delete with
its confirm + T4.8 checkbox). Top bar = Up arrow + title + the single `download` icon (verify what the pencil does, see §4.5).
Add the `Select pages` button and move `Rotate`, `Extract (save selected pages)`, `Delete pages` into its contextual bar; delete the
document-level `Rotate left/right`, `Delete page`, `Save this page`, `Categorize` entries (T4.6 supplies the chip).
Tests: `UiSmokeTest` document cases rewritten; new assertions that the document screen has **no** toolbar overflow, `More` opens a
sheet whose rows == the §4.5 list, no action appears in two places, and `Share/Edit/Text/More` fit at `w320dp` × 1.3 (this turns
`TextFitTest.document` green).

**T5.3 Scan Review dock.** Inset-aware, weights, no fixed heights. (Category rail is T4.1.)

**T5.4 Settings.** Remove nothing yet (globe row is removed in P7). Rename "Greeting" →
"Status line". Fix any label flagged by `TextFitTest`. Add one switch row "Also save a copy to Documents" (default OFF) bound to `Prefs.autoCopyToDocuments` with the subtitle "Off: scans stay inside ScanWhirl until you export them." (en + hi). Add it to `TextFitTest`.

**T5.5** Run `TextFitTest` on all five screens at font scale 1.0 and 1.3. Fix every offender.

**G3 (gate):** `alpha3`. Owner screenshots: Document screen, Page editor, Scan Review (with
category rail), Settings.

---

### PHASE 6 — OCR Editor

**T6.1 Pure cleanup functions.** `util/OcrCleanup.kt` + `OcrCleanupTest`:
- `joinLines(text)`: inside a paragraph (no blank line) replace single `\n` with a space; keep
  blank-line paragraph breaks; de-hyphenate `exam-\nple` → `example` only when the next char is
  lowercase.
- `fixSpaces(text)`: collapse runs of spaces/tabs, trim line ends, collapse 3+ newlines to 2.
- `removeGaps(text)`: collapse **every** run of blank lines to a single newline, then trim. Needed because ML Kit returns each text
  block as its own paragraph (seen at G1: OCR of a phone screenshot came out as one fragment per paragraph), so `joinLines` alone
  does nothing on that kind of input. Add a **[Remove gaps]** button next to Join lines / Fix spaces in §4.7 and the T6.2 `Undo` snapshot.
- Tests with at least 6 samples (hard-wrapped paragraph, hyphenated word, bullet list that must
  *not* be joined — treat lines starting with `-`, `•`, `*` or a digit+`.` as paragraph starts —
  trailing spaces, mixed blank lines, empty string).

**T6.2 Activity.** `OcrEditorActivity` + `activity_ocr_editor.xml` per §4.7. Inputs:
`EXTRA_DOC_ID` (repo-backed, per-page editing; read `repo.ocrDoc(id)`) or `EXTRA_TEXT_PATH`
(device PDF/image: text written to a cache file by the caller; **do not** put large text in an
Intent extra). Keep an in-memory `Map<pageKey, String>`; page chips swap the `EditText` content.
Buttons: **Copy** (copies the *current edited* text; toast "Copied"; for multi-page docs the
overflow has "Copy all pages" joined with blank lines), **Share** (`ACTION_SEND text/plain`),
**Save** (doc-backed → `repo.updateOcrPage`; temp → "Save as .txt" through `DownloadSaver`).
Word count label under the box. `Undo` = one-level snapshot taken before each cleanup action.
Back with unsaved edits → confirm dialog (Save / Discard / Cancel). Empty OCR → inline message
"No text found. Try a sharper, well-lit scan." (not a dialog).

**T6.3 Wire it.** Replace the OCR dialog in `DocumentViewerActivity.runAndShowOcr` and the P2
`OcrFlow` dialog: after OCR finishes (keep the existing progress dialog), start `OcrEditorActivity`.
Delete `showOcrTextDialog` and the `ocr_*` strings that become unused (en + hi).

**T6.4 Tests.** `OcrEditorTest` (Robolectric, NATIVE): edit text then Copy → clipboard equals
the *edited* text; Save → `repo.ocrDoc(id)` returns the edit; Join lines changes the text and
Undo restores it; back with dirty state shows the confirm dialog; empty-state message shows.
Add the editor to `TextFitTest`.

**G4 (gate):** `alpha4`. Owner tests OCR on a real doc: edit, copy, paste into another app.

---

### PHASE 7 — Remove the Earth

**T7.1** `git tag pre-globe-removal` (the undo button).
**T7.2** Delete: `ui/globe/` (3 main files) and `GlobeSceneTest`, `GlobeHomeActivityTest`;
`SwTransitions.kt` + `SwTransitionsTest` (warps and the globe scan-reveal; keep it only if
something else still calls it — grep first); `SwMotion.kt` + its test **only if** no INFINITE
animator remains; the globe manifest entry; `Prefs.globeHomeEnabled` (+ setter) and the Settings
"Globe home" row and `settings_globe_home*` strings (en + hi); `globe_*` strings; resources only
the globe used (`globe_*`/`shelf_*` anims, `bg_fab_orbital`, `bg_fab_pulse`, `bg_orbit_dot`,
`sel_chip_orbital`, `bg_hud_scrim`, `activity_globe_home.xml`) — confirm each with
`./gradlew :app:lintDebug` UnusedResources and `grep` before deleting.
**T7.3** Verify: `grep -ri "globe" app/src` returns nothing; app launches; full tests green;
APK is smaller. Record the new test baseline in `docs/TEST26_LOG.md`.
Expected net change: roughly **−2,500 lines**.

---

### PHASE 8 — The futuristic layer (cheap, functional, optional order)

**T8.1** Status line typography + a 3dp "signal dot" next to the wordmark that is lit while
background work (OCR/merge/compress) runs; breathing only when `SwMotion.ambientAllowed`.
**T8.2** Viewfinder corner brackets (`frame_corners`) on the SCAN button ring, the empty state
and the Quick Sort thumbnail.
**T8.3** Scan-line sweep (finite, 1.2 s, gated) over the thumbnail while OCR runs.
**T8.4** `Unsorted` chip/rail drawn with a dashed outline (`bg_chip_unsorted_dashed`).
**T8.5** Haptic tick on every category change; list items fade-up only on first load.
**T8.6** Hindi: add every new string to `values-hi/strings.xml` (the app ships Hindi; missing
strings silently fall back to English). Mark machine translations `<!-- review -->`.

---

### PHASE 9 — Release

**T9.1** `app/build.gradle.kts`: `versionCode = 26`, `versionName = "1.1.1-test26"` (exactly 2 lines).
**T9.2** Full build + test; parse `app/build/test-results/*/*.xml` and record counts.
**T9.3** `aapt2 dump badging` → package `com.scanwhirl.app.test`, label `ScanWhirl Test`, launcher
`com.scanwhirl.app.ui.MainActivity`, version 26.
**T9.4** Copy APK to `/mnt/c/Users/Cybium/Downloads/ScanWhirl-1.1.1-test26.apk`. Source zip with
the test25 excludes (`.git/`, `.gradle/`, `build/`, `.kotlin/`, `dist/`, `captures/`, `keystore/`,
`keystore.properties`, `local.properties`, `*.apk`).
**T9.5** Update `README.md` feature list; write `docs/TEST26_EXECUTION_REPORT.md`. Commit. **No push.**

**G5 (final):** owner runs §10 on the device.

---

## 7. Test strategy

| Action | Tests |
|---|---|
| **Keep untouched** (the real safety net) | `ScanRepositoryTest`, `PdfGeneratorTest`, `ImageToolsTest`, `DownloadSaverTest`, `ScanFilterTest`, `SearchMatcherTest`, `PageThumbLoaderTest`, `ExternalVerifyExport` (only extended where T1.4 says so) |
| **Rewrite** (same commit as the UI change) | `CategoryRulesTest`, `CategoryPersistTest`, `GreetingTest` → `StatusLineTest`, `UiSmokeTest` (edit what touches changed screens), `Phase5ScreensTest` (keep Settings/Review/Viewer parts that still apply) |
| **Delete** (P7) | `GlobeSceneTest`, `GlobeHomeActivityTest`, `SwTransitionsTest`, and `SwMotionTest` only if `SwMotion` is deleted |
| **New** | `TextFitTest`, `EmojiGuardTest`, `HomeFilterTest`, `HomeScreenTest`, `QuickSortQueueTest`, `ScanReviewCategoryTest`, `OcrCleanupTest`, `OcrEditorTest`, `ExportHygieneTest`, `StatusLineScreenTest`, `SelectionBarTest`, `DeleteExportsTest` |

Rule: a phase is not done while any test is red or skipped. Never `@Ignore` a test to get green —
delete it with a one-line reason in the commit message if its feature is gone.

---

## 8. Gotchas carried over from test25 (still true — do not relearn them)

- Robolectric UI tests need `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, or text measures to the
  character count and renders are blank. Fake document URIs must carry a real JPEG.
- `MaterialCardView` silently drops XML `android:background` (applied before the card finishes
  init). Set glass backgrounds in code after inflate; selection then rides `isSelected`.
- `app:cardBackgroundColor` cannot reference a drawable — it crashes at inflation. Put the
  drawable on an inner container with a 1dp margin.
- `FastOutSlowInInterpolator` is not on the classpath. Use `PathInterpolator(0.4f, 0f, 0.2f, 1f)`.
- Use `resources.getQuantityString(id, n, n)` for plurals, never `getString(R.plurals…)`.
- JUnit methods written as `= runBlocking { … }` must be `runBlocking<Unit>`.
- Any new `INFINITE` animator must be gated on `SwMotion.ambientAllowed(context)`; in Robolectric
  tests use `idleFor(Duration)`, never bare `idle()`.
- `registerForActivityResult` launchers must be created during `onCreate`/field init, before
  `STARTED` — keep them in the Activity when extracting flows (P2).
- `tools:ignore` needs `xmlns:tools` on the layout root.
- `setCategory` currently writes `meta.txt` with no trailing newline; keep reading it with
  `readLines()` and never assume line count (old docs may have only 2 lines).
- The agent's image viewer may show stale PNGs. Check renders with PIL pixel sampling, and
  rely on the owner's device screenshots at gates for the real verdict.
- **Green is not the same as readable (G2 lesson).** At G2 the suite was 258 green yet the owner found: a word broken inside a Tools tile
  (`Compres`/`s`), a clipped `1 sel…`, a status line showing 0 DOCS over a listed document. Every new screen gets (a) a
  longest-word-fits assertion, (b) a test that on-screen *numbers* equal the data, and (c) a red-proof before the fix.
- Exported copies in `Documents/ScanWhirl` are MediaStore rows owned by the **package that wrote them**. The test package
  (`com.scanwhirl.app.test`) can delete only its own; anything from the Play package or from an uninstalled install may be
  undeletable — skip and report, never prompt (T4.8).
- The app ships `values-hi`. New or changed strings need a Hindi entry (T8.6) or Hindi users see English.

---

## 9. Open questions for the owner (defaults are used if unanswered)

| # | Question | Default |
|---|---|---|
| Q1 | Delete the Earth entirely, or keep an opt-in "Orbit view" in Settings? | **Delete** (tag `pre-globe-removal` makes it recoverable). |
| Q2 | Add the `Unsorted` category, or keep five and treat `Other` as unsorted? | **Add `Unsorted`.** It lets the app tell "I haven't looked at this" from "this is genuinely Other". |
| Q3 | Bottom bar right slot: `Vault` or `Import from device`? | **Vault.** |
| Q4 | Move `Delete` out of the Document screen's main bar into `More`? | **Yes** (fewer accidental deletions). |
| Q5 | OCR-based category suggestion (T4.5): include? | **Yes, optional, suggest-only, never auto-applies.** |
| Q6 | Hindi: I machine-translate new strings and flag for review? | **Yes.** |
| Q7 | Should saving a scan still drop a copy into `Documents/ScanWhirl` (today's silent Pro behaviour)? | **No.** Off by default; opt-in switch in Settings (T4.7 / T5.4). |
| Q8 | When deleting a document that has saved PDF copies in `Documents/ScanWhirl`: should the "Also delete copies" checkbox start checked? | **Checked** (owner expects delete to delete; he can untick; shown only when copies exist; only copies the app wrote). |
| Q9 | Default sort for fresh installs? | **Date, newest first** (matches §4.2); existing users keep their choice. |

---

## 10. Definition of done — owner checklist on the phone

Each line maps to a complaint in §2. All must pass on a real device.

1. **No Earth, no rings, no pinch.** The app opens straight to Home. *(#1, 2, 3, 4, 9, 10)*
2. **Tools in one tap.** Home → `Tools` shows Resize, Compress, Edit, Merge and the Pro tools. *(#4)*
3. **Categories work.** Scan something → pick a category on the save screen → Home chip rail
   filters by it; the card shows the same name as the chip. Long-press → Move to category works.
   Quick Sort clears the unsorted pile. *(#5)*
4. **Search bar** shows its full hint ("Search documents") and has no sort control inside. *(#7)*
5. **Page editor and Document screen**: every button label is fully visible, nothing hides
   behind the navigation bar, nothing is cut at the right edge — also with the phone's font size
   set to Large. *(#6)*
6. **No emoji** anywhere. The top of Home shows a status line like `AFTERNOON · 2 DOCS · 14 MB`. *(#8)*
7. **OCR**: run OCR → the Editor opens → fix text → `Copy` → paste elsewhere shows the *edited* text.
   Closing with unsaved edits asks first. Searching for a corrected word finds the document. *(#11)*
8. **Back arrow** is visible on every screen except Home. *(#9)*
9. **Old documents** from test24/test25 still open and keep their categories.
10. Full suite green; `aapt2` reports `com.scanwhirl.app.test`, `1.1.1-test26`, versionCode 26.
11. **No ghost files.** Scan and save a document, then open Files > Recent: **nothing new appears**. Tap the Document screen's download
    button twice: **one** file, and a Snackbar offers Open and Undo. *(#13)*
12. **Numbers are true.** The Home status line always matches the list (`1 DOC` with one document, `0 DOCS` after the last delete) and the Tools sheet shows every label as whole words. *(#14, #16)*
13. **Selection mode** shows the full `N selected` text, Merge is dimmed until 2 are picked, actions sit in a bottom bar. *(#15)*
14. **Delete deletes.** Delete a document with the box ticked: it disappears from the app **and** from `Documents/ScanWhirl`. The Document screen has one overflow only (`More`), with no duplicated or page-level actions. *(#17, #18)*

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| P2 refactor breaks `ActivityResult` launchers or dialogs | One flow per commit, full tests after each, launchers stay in `MainActivity`. |
| `TextFitTest` does not reproduce device clipping in Robolectric | Fallback XML-dimension assertions (T1.2); the gate review (md edits in Downloads; screenshots were used up to G2) is the final check. |
| Edge-to-edge changes layouts on older Android | Inset helper is applied uniformly; verify on the owner's device at G1. |
| A small model rewrites big files badly | §0 rules: split tasks, new files over rewrites, stop after two failed attempts. |
| New `Unsorted` value read by older builds | Old builds treat unknown meta values as `Other` (`isValid` fallback), so downgrade is safe. |
| Deleting exported copies removes something the owner wanted | Only copies the app wrote and recorded (or exact-name, package-owned legacy matches), only when the checkbox is ticked, listed with a count in the dialog; never a bulk "clean folder" action. |
| Removing Earth later regrets | Tag `pre-globe-removal`, deletion is a single phase. |


Owner chose defaults in answers! (Rev 3: the same defaults apply to Q8 and Q9 unless the owner says otherwise.)

*End of plan.*
