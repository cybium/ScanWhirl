# 00 — Decisions

Every decision below is final for v1 unless the owner reverses it in `50_OPEN_QUESTIONS.md`.

**Owner answers of 2026-11-13 are applied throughout:**

| Question | Answer |
|---|---|
| Play Billing | approved |
| Price | **₹100 one-time** |
| Long image | **returns** |
| Network permissions | removed after the ML Kit review |
| Hindi OCR | architect's call: opt-in after a spike |
| Device priority | **Android 10+** |
| Everything else | default |
Format: **Decision** → why → rejected alternative → risk.
Source citations refer to the current tree (`clean-workspace-2026-11-13`, `0c283b8`). The evidence for each claim is in `20_ARCHITECTURE_PLAN.md` §1.

---

## The thesis

ScanWhirl does not have a PDF engine. It has a **page-image document engine**: every document is an ordered set of JPEG pages, and `scan.pdf` is generated from them by a small verified writer. That is a strength, not a defect. It makes every operation predictable, cheap, verifiable and offline. The PDF Manager will:

1. **Say what it is.** Outputs are image-based PDFs. Imported PDFs keep their original file and gain editable page images.
2. **Do the page-image jobs better than anyone.** That means ordered merge, exact-size compress, organize with a real Discard, extract and split, verified redaction, signature and self-attest, purpose watermark, and lock badges that never lie.
3. **Charge once for finishing and privacy.** Free users manage, organize, optimize and secure their documents. Power Pack buyers can sign, stamp, redact and split. Nothing is rented, and nothing goes to a server.

---

## Product decisions

### P1. PDF Manager is a new activity, "PDF Tools", opened by the Home dock's **Tools** button

- **Why.** `MainActivity.kt` is 1,707 lines. It already owns search, selection, categories, Quick sort, Vault mode and the header collapse. A new mode inside it would multiply state bugs. A dedicated activity isolates the work and is easy to test with `ActivityScenario`.
- **Rejected: a mode of the Home library.** That means two mental models on one screen and more back-stack edge cases in an overloaded activity.
- **Rejected: a tab-level destination.** The app has a dock, not a tab bar, and the spec forbids a sixth tab.
- **Risk.** Two lists of the same documents could drift apart.
  - **Mitigation:** PDF Tools is tool-first and shows only a short Recent strip. Browsing happens in the Document picker, which reuses Home's row binding (`ScanListAdapter`/`DocThumbBinder`) and `SearchMatcher`. There is one row renderer and one search engine.

### P2. v1 screen set: seven screens and three sheets. The first slice is three screens

| # | Screen | Slice |
|---|---|---|
| 1 | **PDF Tools** (home of the manager) | 1 |
| 2 | **Document picker** (single/multi, ordered tray) | 1 |
| 3 | **Reader with dock** (`PdfViewerActivity` + dock) | 1 |
| 4 | Organize pages (draft-based) | 2 |
| 5 | Tool setup (full screen, sticky action row) | 2 |
| 6 | Result | 2 |
| 7 | Page stamp editor (sign / watermark / redact) | 5 |
| Sheets | Document tools sheet, More sheet, Info sheet | 1–3 |

- **First slice = screens 1–3.** They make every existing tool findable and fix D5/D16 (reader dead end) and D8 (name-only pickers) without new engine work.
- **Rejected:** starting with Organize. It needs the draft model (E12) first, and it is the riskiest data-integrity change. It goes first in Slice 2.

### P3. Five groups, 12 tools (11 visible until Slice 6)

| Group | Tool | Kind | Tier |
|---|---|---|---|
| Organize | Merge | page-image | Free |
| Organize | Organize pages (reorder / rotate / delete / insert) | page-image | Free |
| Organize | Extract & split | page-image | Extract: Free · Split into files: Power Pack |
| Optimize | Compress | page-image | Free |
| Optimize | Resize photo (exact KB / pixels; photo → photo) | image | Free |
| Convert | Export as images (JPEG / ZIP / **one long image**) | page-image | Free |
| Convert | Extract text (OCR → edit / copy / .txt) | OCR | Free |
| Convert | To Word — text only (.docx) | OCR + local writer | Power Pack (Slice 6, only after spike) |
| Secure | Password (protect / unlock / change) | PDF wrapper | Free |
| Secure | Redact (verified) | page-image | Power Pack |
| Finish | Sign & self-attest | page-image | Power Pack |
| Finish | Purpose watermark | page-image | Power Pack |

- **Why these 12.** Each one is either a top job (merge, compress, organize, extract, password, text) or a privacy/finishing job users currently pay CamScanner for, with ads and watermarks (sign, redact, watermark). Every one runs on the current engine. None needs a PDF parser.
- **Rejected.** To Excel, To PPT, PDF/A, page numbers, metadata, free-hand annotation, batch, AI.
- Long image is a delivery option inside Export as images (E20), not a 13th tile.
- **Risk.** "Resize photo" is an image tool inside a PDF surface.
  - **Mitigation:** the label says "photo". The alternative is keeping a second Tools sheet only for it, which is worse.

### P4. Exact Free / Power Pack boundary

See `30_FREE_PRO.md`. The rule: **anything needed to manage, organize, shrink, protect or read your own documents is Free forever.** The Power Pack sells **finishing and privacy outputs**: sign, stamp, redact, split into files and Word.

- **Why.** The free core stays credible. The paid tools have a clear "this is worth paying for" moment: sending an ID copy, signing an agreement, redacting a number.
- **Rejected.** Gating Protect. It is the current D3 dead end, security basics should not be paywalled, and both Adobe and Genius were criticised for doing it.
- **Rejected.** Gating Export as images. A competitor gives it free, and stored pages are already JPEGs, so gating it would be artificial.
- **Risk.** The Free tier may be too generous to convert buyers. Accepted: the trust of a no-ads app depends on it.

### P5. Price: ₹100 one-time (owner decision), single tier, no subscription

- **Owner's reason.** This is the first app. The aim is brand growth, not revenue, and a low price makes modded APKs pointless.
- **Consequences.**
  - No anti-piracy, Play Integrity or license-server work: it is network-based and against the offline promise.
  - Entitlement is Play Billing's local purchase query only.
  - Everything else in this section stands.

- **Lifetime includes.** All Power Pack tools, and every improvement to them, for as long as ScanWhirl is sold on Google Play. It restores on any device with the same Google account.
- **Explicitly excluded from lifetime.**
  1. A future add-on that needs a **separately licensed third-party engine** (for example Office-fidelity conversion or PDF/A, if they ever require a paid SDK). It would be optional, sold separately, and would never remove anything already bought.
  2. Anything server-based. None exists, and none is planned.
  3. Refunds beyond Google Play policy.
- **Rejected: a ₹599–899/year "language updates" subscription.** OCR models come from Google Play services for free. Charging for them would be dishonest.
- **Rejected: founder / regular anchor prices.** A fake anchor price undermines the honesty positioning.
- **Risk.** ₹100 leaves almost no revenue.
  - Accepted by the owner.
  - Raising the price later applies only to new buyers. Existing buyers keep everything.

### P6. PDF Tools replaces the Tools sheet

- The dock's **Tools** button opens PDF Tools. `showToolsSheet()` (`MainActivity.kt:1168–1273`) is retired. Its seven tiles move as follows:

  | Old tile | New home |
  |---|---|
  | OCR | PDF Tools → Convert → Extract text |
  | Merge, Compress | PDF Tools → their groups |
  | Lock | PDF Tools → Secure → Password |
  | Resize | PDF Tools → Optimize → Resize photo |
  | Quick sort | Home sort menu ("Quick sort…") |
  | Vault (legacy PIN only) | Settings row "Hidden Vault (older version)", shown only when a legacy PIN exists |

- **Why.** One tool surface. Two would be the CamScanner clutter the spec rejects.
- **Rejected: keeping the sheet with a "PDF Manager" tile.** It adds a second hop and two homes for Merge.
- **Risk.** About 8 Robolectric tests assert the sheet (`LongImageRemovalTest` 7-tile assertion, `UiSmokeTest`, `HomeSlice2Test`, `TextFitTest`). They are rewritten in PM-1.1 to assert the PDF Tools screen instead. The rewrites are listed in the task card.

### P7. No folders or tags in v1. Categories remain the only grouping

- The picker filters by: All · Category chips (existing five) · Imported · Locked · Image-based copies.
- "Imported" comes from the new provenance origin (E1). Documents created before this change have unknown origin and show **no** source label. Origin is never guessed.
- **Why.** Folders/tags are a library feature with migration and search costs. They are not PDF tools, and CLAUDE.md explicitly defers them.
- **Rejected:** free-form tags now.
- **Risk.** Users with many documents want filing. That is a post-v1 library project.

### P8. First-run and empty state

PDF Tools with zero documents shows everything except the Recent strip. In its place is one card: "No documents yet" · **[Import a PDF]** (primary) · **[Scan a document]** (secondary) · "Everything stays on this phone."

- A tool tapped with no eligible documents opens the picker's empty state with the same two buttons. No carousel, no tutorial, no PRO pitch.

### P9. Result and undo model: every tool ends on one Result screen

- **New copy.** **Undo** moves the new copy to Trash. Nothing is deleted, the original was never touched, and Trash has no auto-purge (`ScanRepository.kt:816–975`).
- **Replace original** (explicit, Organize only). The previous version moves to Trash as "‹name› (previous version)". **Undo** swaps it back.
- **Saved to Documents.** Undo uses the existing `DownloadSaver.undoSave`, which removes only a copy this save created.
- **Protect / Unlock** (in place, see P11). There is no Undo button. The state is visible and reversible: Unlock with the password, or Protect again.
- **Failure.** The original stays untouched. The screen names the tool and reason and offers **Retry** and **Choose another**.

### P10. Coexistence with the scan library

- **Home** remains the library: find, open, sort, categorize and Trash.
- **PDF Tools** is the work surface. Every tool output is a normal library document. It appears at the top of Home with the existing reveal, in PDF Tools → Recent, and on the Result screen.
- There is no second document store, and no "PDF" vs "scan" split. Every ScanWhirl document is a PDF.

### P11. Exception: Protect / Unlock change the document in place

- They change only the PDF wrapper (page images untouched; `ScanRepository.kt:1495–1532`). A "protected copy" next to an unprotected original would defeat the purpose of protecting.
- The dialog names the document. Unlock and Change password always ask for the current password.
- **Rejected:** copy-by-default for Protect.
- **Risk.** It breaks the literal "every operation creates a copy" rule. The rule exists to prevent content loss, and Protect loses no content. Recorded as a deliberate exception.

### P12. Naming: every output is named before Apply, and every save names its destination

- **Default names** (editable on the setup screen):

  | Tool | Default name |
  |---|---|
  | Compress | `‹source› (compressed)` |
  | Extract | `‹source› (pages 3–9)` |
  | Merge | `Merged · 13 Nov 2026` |
  | Organize | `‹source› (edited)` |
  | Sign | `‹source› (signed)` |
  | Watermark | `‹source› (watermarked)` |
  | Redact | `‹source› (redacted)` |
  | Split | `‹source› (part 1 of 3)` |

- An exact name collision appends ` (2)`.
- **Destination text** is always one of:
  - "Saved in your ScanWhirl library"
  - "Saved to Documents/ScanWhirl/‹file›.pdf"
  - "Saved to Pictures/ScanWhirl/‹folder›/"
- **Rejected:** naming templates with tokens. Deferred; smart defaults cover v1.

---

## Engineering decisions

### E1. `ScanDocument` stays the canonical item. Add provenance, not a wrapper store

- Each document directory gains `provenance.txt`: versioned header, written atomically, and moved whole by Trash. Fields:
  - `origin` = scan | import | merge | extract | split | compress | organize | sign | watermark | redact | legacy
  - `sources` (source document ids, plus each source's page digest at operation time)
  - `tool` and a short parameter summary
  - `createdAt`
  - `original` (only for imports: file name, byte length, SHA-256)
- `ScanDocument` gains optional fields with defaults: `origin`, `hasOriginalPdf`, `sourceIds`. A missing file means `origin = legacy`.
- UI-only row models (size label, badges) are presentation, not storage.
- **Why.** `ScanDocument` already carries pages, PDF, lock, Vault, category and searchable state, and Home, Trash, search and the indexer all read it. A parallel wrapper store would create two sources of truth.
- **Rejected:** a `PdfItem` table or JSON index. Rejected: putting provenance into `meta.txt`, which is rewritten by rename and category changes under `META_LOCK`, and which duplicate copies.
- **Risk.** `duplicateDocument` copies every file (`ScanRepository.kt:1002–1032`). It must rewrite `provenance.txt` to `origin=duplicate`, `sources=[id]`.

### E2. One derived-document publish path for every tool

- **Steps:** build in `filesDir/op_staging/‹token›` → write pages → write `scan.pdf` with `PdfGenerator` (verifier runs inside) → write `provenance.txt` → write `meta.txt` last → queue OCR → one atomic `rename` into `documents/‹newId›`. Staging older than 1 hour is swept at startup, as `recoverImports` already does (`ScanRepository.kt:1452–1460`).
- `extractPages`, `mergeDocuments`, `compressScan`/`createDocument` and `duplicateDocument` move onto this path.
- **Why.** Today these write directly into `documents/‹id›`:
  - `mergeDocuments` writes `meta.txt` before the PDF and **swallows PDF failure** (`ScanRepository.kt:1093–1100`).
  - `finalizeDoc` swallows PDF failure (`:1316–1320`) and queues OCR **only on its failure path** (`:1321`).
  - A crash mid-merge can therefore publish a partial, unindexed document.
  - The import path (`:1391–1441`) already solves this correctly. Copy that pattern instead of inventing a new one.
- **Rejected: reusing the new-scan `saveScan` transaction.** It is built around review-staging identity and replay (`SaveTransaction.kt`), which derived documents do not need.
- **Risk.** Behaviour change in four repository functions with existing tests (`ScanRepositoryTest`, `PageSelectTest`, `CategoryPersistTest`). Those tests must stay green unchanged, except where they asserted the swallow.

### E3. Imported PDFs become page images, **and the original file is kept**

- Import already rasterizes every page at 150 dpi, max 2048 px, JPEG quality 85 (`PdfImport.kt:184–257`) and deletes the source copy (`:175`).
- **New rule:** the source bytes are kept as `original.pdf` in the document directory. Its SHA-256 and length are recorded in provenance.
- While the document is unchanged, **Share and Save to Documents send the original**, and the row shows the original's size.
- Every tool output is a new image-based document. Protecting an imported document encrypts its image-based `scan.pdf`, and the Protect screen says so.
- **UI copy at import:** "ScanWhirl keeps your original PDF and also makes page images so you can view, search and edit. Edited copies are image-based: text in them can't be selected." This replaces `import_pdf_desc` "Each page becomes an editable page" (`strings.xml:209`), which is misleading.
- **Why.** Without it, importing a 200 KB bank statement and sharing it sends a 3 MB picture-PDF with no selectable text. A PDF manager must not degrade files the user only wanted to file, rename, find or share. It also gives a future engine (E18) a lossless source.
- **Rejected:** vector pass-through for edits. That needs a PDF parser and writer (see E8).
- **Risk.**
  - Storage roughly doubles for imported documents. Owner question OQ-6.
  - Two PDFs per imported document. The invariant "page count = original.pdf page count = scan.pdf /Count" is enforced at import.

### E4. Export as images copies the stored page JPEGs. No PNG, no re-render in v1

- Pages are already JPEG. Output: share (multiple), save to `Pictures/ScanWhirl/‹document›/` via `DownloadSaver.saveJpeg(subdir)`, or one ZIP.
- **Rejected: PNG.** It is bigger and no sharper than the JPEG source. Saying so is honest.
- **Rejected: a 300 dpi re-render from `original.pdf`.** Possible later via `PdfPageRenderer`. Not v1.
- **Risk.** For imported documents, images are 150 dpi renders. The setup screen shows each page's pixel size before export.

### E5. Extract and split run on page indices through the publish path

- Extract = one output from selected pages or a range string ("1-3, 7, 9-12"). Split = N outputs (every N pages, or ranges).
- The range parser lives in a pure function with unit tests.
- Pages are copied, not re-encoded. That is lossless relative to the stored pages, as `extractPages` already is (`ScanRepository.kt:1040–1060`).

### E6. Text and Word

- **TXT is Free and already exists.** The OCR editor saves `.txt` (`DownloadSaver.saveText`, `TextExportTest`). Extract text routes to it.
- **Word = a text-only `.docx`**, written locally with `java.util.zip` (a `.docx` is a ZIP of XML parts). No dependency. One paragraph per OCR block, a page break between pages, no images, tables, columns or fonts.
- Fidelity label on the setup screen: "Word (text only): your text and paragraphs, not the page layout. Recognises English and other Latin-script text."
- Ships only after spike PM-6.1 proves it opens cleanly in Microsoft Word (Android), Google Docs and WPS.
- **Rejected:** layout-preserving Word, Excel, PPT. They need table/layout reconstruction, which is a different product.
- **Risk.** Users expect CamScanner-like layout. The label and a sample preview set that expectation.

### E7. PDF/A is out of v1. Deferred to Slice 7, owner approval only

PDF/A-1b requires all of the following, and none exist today:

- Embedded fonts. The invisible text layer uses non-embedded Helvetica (`PdfTextLayer.kt:27–28`), which PDF/A forbids even in render mode 3.
- An embedded ICC OutputIntent.
- XMP metadata.
- No encryption.
- An external validator (veraPDF). It runs on desktop, not on the phone.

**No "PDF/A" label appears anywhere in v1.**

### E8. True PDF text-layer editing is **rejected**, not postponed

- It needs a content-stream parser, font subsetting, re-layout and a PDF writer for foreign files. That is a PDFium/PDFBox-class engine of 10–30 MB, which contradicts the 7 MB, no-dependency, honest-offline product.
- "Edit text" in ScanWhirl means **correcting recognised text** (OCR editor). The UI calls it "Correct text", never "Edit PDF text".

### E9. Signature is an image overlay baked into the page image

- A saved signature is a transparent PNG in app-private storage (max 3). It is placed, scaled and rotated on a page, then flattened into that page's JPEG in a **new copy**.
- It is not a PDF annotation layer and not a cryptographic digital signature. The UI says: "Adds a picture of your signature. It is not a certified digital signature."
- **Rejected: a PDF `/Annot` layer.** The generator has no annotation support. Annotations can be removed by any editor, and on a page-image document they add nothing.

### E10. Redaction is a flattened page-image operation with three verifications

- Black boxes are painted into the decoded page bitmap, which is re-encoded to a new JPEG in a **new copy**.
- **The output must not carry:**
  1. the page's kept original (`page_NNN.orig`, which still holds the unredacted pixels: `ScanRepository.kt:1260–1261`);
  2. OCR text or geometry for that page. The redacted page is re-queued for OCR, and its text layer is rebuilt only from the new pixels;
  3. `original.pdf`.
- **Verification before publish:**
  - Every pixel inside each box decodes within a tolerance of the fill colour.
  - No `.orig` exists for redacted pages.
  - Provenance records the boxes.
- The success screen says that the original still contains the hidden content and offers **Move original to Trash**.
- **Rejected:** "cover" annotations. They are not redaction.

### E11. App lock and Vault are separate from PDF Tools, and not in the Power Pack

- Vault is a legacy PIN-hidden flag with no encryption (`strings.xml:462`). It stays legacy-only (P6) until a real encrypted Private space is designed.
- **Rejected:** selling Vault, or putting it in PDF Tools.

### E12. Organize uses a persistent **draft**. The library document is never edited in place

This is the D1/D2 fix, and the core data-integrity decision.

- **What happens today.**
  - Every page operation (`deletePage`, `rotatePage`, `cropPage`, `movePage`, `setPageFilter`) rewrites the library directory and regenerates `scan.pdf` immediately (`ScanRepository.kt:1151–1297`).
  - `DocumentViewerActivity` takes its pre-edit snapshot **asynchronously after first load** (`:171–176`), so an edit can land before the snapshot. It deletes the snapshot in `onDestroy` (`:194–204`), so a force-stop leaves the edits with no restore.
  - "Overwrite saved PDF" is only `finish()` (`:804`).
  - The dialog claims "Your edits are not in the original PDF yet" (`strings.xml:119`), which is false.
  - `restoreSnapshot` deletes every file and then copies (`:860–869`). A crash in between destroys the original.
- **The new model.**
  - Opening Organize creates or resumes `filesDir/drafts/‹docId›/`: a manifest plus edited page files.
  - The manifest is an ordered list of entries. Each entry has a source page file name and digest, quarter-turns, an optional replacement page file (crop, filter or insert), and the base document's page digest.
  - The manifest is rewritten atomically (`Durable.atomicWriteText`) after every change.
  - Thumbnails show rotation as a display transform. Nothing is baked until Save.
- **Save as new copy (primary).** Builds through the publish path (E2). The original stays byte-identical.
- **Replace original (secondary, confirm names the document).**
  1. Build the new version in staging.
  2. Move the current directory to Trash as "‹name› (previous version)".
  3. Rename the staged directory into the same id.

  Each step is a single `rename`. A crash between steps 2 and 3 leaves the previous version restorable from Trash and a staged directory that startup recovery publishes.
- **Discard.** Deletes the draft. The library was never touched.
- **Force-stop.** The draft survives. Reopening the document shows "Unsaved edits to ‹name› · 3 changes · Resume / Discard".
- **Stale draft.** If the base document changed (for example, the page digest differs), only "Save draft as new copy" and "Discard" are offered.
- **Rejected: immediate commit with per-operation Undo (Google Photos style).** It violates copy-by-default and has already failed on device (D1).
- **Rejected: fixing the snapshot race.** That still edits in place and still depends on `onDestroy`.
- **Risk.**
  - This is the largest change in the plan. The page editor (`PageViewerActivity`) must also write into the draft (PM-2.3).
  - The existing `PageEditSafetyTest` tests the in-place operations. They stay, because the repository operations remain for internal use, but no UI calls them on library documents after PM-2.3.

### E13. Locked documents as tool input

- A locked document can be chosen in any tool. The picker asks for the password once per operation, and the password is held in memory only for that operation.
- **Single-source tools** (compress, extract, split, organize, sign, watermark, redact). The output is **locked with the same password**. The Result screen says "Locked with the same password as ‹source›".
- **Merge with any locked source.** The user must set a password for the result. It is a required step, with no default.
- **Export as images or Word from a locked document.** A confirm step: "Images can't be password-protected. Anyone with the files can open them."
- **In-place PDF regeneration of a locked document is forbidden.** The app stores only a password hash (`ScanRepository.kt:1511`), so it cannot re-encrypt. Today `regeneratePdf` (`:1651–1665`) rewrites `scan.pdf` **unencrypted** after any page edit on a locked document, while `locked.meta` keeps the badge on. PM-0.3 closes this.
- **Unlock and Change password always require the current password.**
  - Today `LockFlow.kt:46–58` and `DocumentViewerActivity.kt:659–681` remove protection without one.
  - Change password never asks for the old one (`LockFlow.kt:60`).
- **Rejected:** forbidding locked inputs ("unlock first"). It encourages users to strip protection permanently.

### E14. Protection: honest claims, streaming, and an AES spike

- **Current state.** PDF Standard Security R3, RC4-128; owner password equals user password; permission bits are print-only (`PdfEncryptor.kt:9–55`). The encryptor reads the whole PDF into memory and a Latin-1 copy (`:35`, `:188`), which risks running out of memory on large documents. The app-side check is an unsalted SHA-256 (`ScanRepository.kt:1511`). Page JPEGs stay unencrypted in app-private storage.
- **Claim allowed in v1.** "Other apps will ask for this password before opening the PDF. Uses the older RC4-128 PDF standard: it stops casual opening, not a determined attacker. Inside ScanWhirl, pages are kept in the app's private storage and the app asks for the password before opening."
- **PM-3.2** makes encryption streaming (it is length-preserving, so it can be done object by object) and verifies the encrypted file's page count.
- **PM-3.4 (spike, gated).** Generate AES-256 (PDF Standard Security R6) directly in `PdfGenerator` using JDK crypto, with no dependency. Adopt it only if the output opens in Chrome, Google Drive's viewer, Adobe Reader, Android `PdfRenderer`, and one of Xodo or WPS. If it passes, the claim becomes "AES-256". If not, RC4 stays and the claim does not change.

### E15. OCR is on-device but not "never online"

- `play-services-mlkit-text-recognition` (`build.gradle.kts:120`) uses Google Play services. The **model downloads on first use** (`OcrEngine.kt:18–20`). The Latin recognizer is the only one (`:71`), and the text layer is Latin-1 only (`PdfTextLayer.kt:40–80`).
- **Owner decision OQ-4: remove `INTERNET` and `ACCESS_NETWORK_STATE`** (`AndroidManifest.xml:5–6`).
  - The ML Kit unbundled models and the document scanner are downloaded and run by Google Play services in its own process, so the app needs no network permission.
  - PM-1.5 removes both permissions, including any copy merged in by a library, and adds ML Kit's install-time OCR model download declaration.
  - It is gated on DeepSeek DS-2 (merged manifest) and a device check: airplane mode after install → scan and OCR still work.
  - A side benefit: any in-process library telemetry cannot leave the phone.
- **Claim after PM-1.5:** "ScanWhirl has no internet access. Google Play services installs the scanner and text-recognition models on your phone. Your pages never leave it. Reads English and other Latin-script text." Hindi is added only if PM-6.3 ships.
- **Claim before PM-1.5:** "Text recognition runs on your phone. The first time, Google Play services may download the recognition model. Your pages are never uploaded."

### E16. Limits for large documents

| Limit | Value | Source |
|---|---|---|
| Pages per document (import, merge result, organize result) | 500 | `PdfImport.MAX_PAGES` (`PdfImport.kt:38`) |
| Import source size | 256 MB | `PdfImport.MAX_BYTES` (`:35`) |
| Free space required before any tool | 2 × (sum of source page bytes) + 16 MB | consistent with `PdfImport.SPACE_MARGIN` |
| Bitmaps in memory per operation | at most one full page, plus at most 4 cached reader pages | `PdfViewerActivity.kt:60` |
| Progress | "Page n of N" for any operation over 10 pages; Cancel between pages | — |

- **Streaming is required for:** compress (today it holds all pages: `ScanRepository.kt:1355`, `CompressFlow.kt:294–300`) and encrypt (E14).
- The picker and organizer reuse `PageThumbLoader` and `DocThumbBinder` sampling. No full-resolution decode in a grid.
- **Acceptance fixture:** a synthetic 300-page document (`ImportFixtures`). Merge, compress, protect, organize-save and export-ZIP must complete under Robolectric without `OutOfMemoryError`, and on device within one minute.

### E17. Test strategy

- **Every operation has five tests:**
  1. Happy path.
  2. Failure injection at each stage (fault seam, like `ImportStage`/`SaveFaults`).
  3. Cancel.
  4. Simulated process death (throw `SimulatedProcessDeath` from the seam, then run startup recovery).
  5. Invariant check after the operation.
- **Invariants I1–I12** (`20_ARCHITECTURE_PLAN.md` §4) are assertion helpers in one test utility, used by every operation test.
- **Fixtures** for corrupt, encrypted-foreign, huge and zero-page inputs already exist in `PdfImportTest` and `ImportFixtures`. Reuse them.
- **The device checklist** for each task is in the task card. Robolectric cannot render real PDFs through `PdfRenderer`, so rendering acceptance is a device check.

### E18. Migration path if a PDF engine is added later

- **The seam.** A tool is "ordered page list plus per-page operations → new document through the publish path".
- **What a future engine could do.**
  - Handle imported documents that still have `original.pdf` (provenance proves which) losslessly: page-tree merge, extract and rotate without rasterizing.
  - Export text from `original.pdf` without OCR.
- **What it would not change.** Nothing in the library format, Trash, search or the Result model. Provenance records the operations, so an engine could even re-run an image-based copy losslessly from the kept original.
- **Prerequisite:** an owner decision on APK size, licence and the offline guarantee. The likely candidates are PDFium-based (Apache-2) or PdfBox-Android (Apache-2).

### E19. Tool setup is a full-screen activity with a sticky action row

- `windowSoftInputMode=adjustResize`. The action row sits outside the scroll view, so it is always above the keyboard. Sizes use a numeric keyboard with the unit shown beside the field.
- **Why.** D7 and D9 come from `AlertDialog` setups whose buttons hide under the keyboard (`CompressFlow.kt:146–181`).
- **Rejected:** fixing dialogs one by one.

### E20. Long image returns, rebuilt so the old failures cannot recur (owner reversed O-01)

The old stitcher (`git show c230017^:app/src/main/java/com/scanwhirl/app/util/LongImage.kt`) failed by design in four ways:

1. It decoded **every page into memory at once** (up to 2048 px each, ARGB). Twenty pages is about 240 MB, so it ran out of memory.
2. It drew onto **one ARGB canvas** up to 2048 × 20,000 px, about 164 MB on its own.
3. It **squashed** tall documents to fit 20,000 px. A 30-page document gave unreadable pages about 670 px tall.
4. It **stretched** every page to the widest page's width, distorting mixed portrait/landscape pages.

**v1 rules** (card PM-4.4):

| Rule | Value |
|---|---|
| Output width | fixed 1080 px (chat-friendly) |
| Page scaling | each page scaled to that width, **aspect ratio preserved** |
| Decoding | one page at a time, sampled, recycled before the next |
| Canvas | `RGB_565`; no alpha needed |
| Maximum height per image | 16,000 px (under common GPU texture and viewer limits) |
| Longer documents | **split into several long images ("part 1 of 3")**, never squashed |
| Page gap | 12 px light-grey separator |
| Encoding | JPEG quality 88 |
| Memory guard | refuse with "Too large for one image" if a single page would exceed the cap after scaling. Peak about 1080 × 16,000 × 2 B ≈ 35 MB, plus one page. |

- **Free**, a delivery option of Export as images. Locked sources get the E13 confirm step.
- `LongImageRemovalTest` is rewritten in PM-4.4 to assert the new safety rules: no unbounded canvas, split, and aspect ratio kept.

### E21. The test build keeps Pro on by default. Production hides Power Pack tools until a storefront exists

- `ProGate.resolveProState` returns Pro in test builds by default (`ProGate.kt:24–34`).
- **The production rule.** A Power Pack tool is **visible only if the user owns it, or a real purchase flow exists**. With no storefront, production users never see a PRO tile, pill or panel. **No dead end can exist.**
- **Play Billing Library (`billing-ktx` only) is approved by the owner (OQ-1).** The storefront is card **PM-5.5**, scheduled after Slice 5, so the first thing a buyer sees is a complete pack.
- Before PM-5.5, production stays `HIDDEN`. After PM-5.5, Free users see Power Pack tiles in `FOR_SALE` with the ₹100 upgrade sheet.

### E22. Hindi text recognition: Free opt-in, gated by an on-device spike (architect decision for OQ-7)

- Adds `play-services-mlkit-text-recognition-devanagari`: unbundled, about 260 KB, model installed by Play services. The owner delegated the decision.
- **Setting:** "Also read Hindi text" (off by default).
- When on, pages are recognised with the Devanagari recognizer. If the spike shows it does not also read Latin well, pages are recognised with both, and the result with higher confidence and coverage is kept.
- Hindi text becomes searchable and copyable **in ScanWhirl**. It is **not** in the exported PDF's text layer, which stays Latin-1. Info says so.
- **Gate (PM-6.3 spike):**
  - ≥ 90% character accuracy on printed Hindi;
  - indexing time ≤ 2×;
  - no English regression.
  - Fail means it does not ship.
- **Rejected:** a Devanagari PDF text layer (needs an embedded font and ToUnicode; Slice 7).

### E23. Android 10+ first

- `minSdk` stays 24, because the build file is not touched.
- Acceptance, performance targets and device checks are judged on API 29–36. API 24–28 must not crash or lose data, but may hide a feature.
- New code may use API 29+ behaviour (scoped storage, MediaStore without legacy permissions) behind a version check.

---

## Scope decisions

### S1. Not built in v1

- PDF/A
- Word with layout, Excel, PPT
- True PDF text editing
- Form filling
- PDF compare
- Page numbers and metadata editor
- Free-hand annotation (brush, sticky notes)
- Batch apply-to-many
- Folders and tags
- Naming templates
- Hindi in the PDF text layer (in-app Hindi OCR is PM-6.3)
- AES (unless the PM-3.4 spike passes)
- Opening foreign password-protected PDFs (see OQ-11)
- Cloud, sync, accounts, analytics, ads, watermarks
- Widgets
- ID-card or book modes (not PDF tools)
- AI anything

### S2. Competitor features deliberately rejected

| Feature | Seen in | Why rejected |
|---|---|---|
| Cloud storage, sync, sign-in, 2FA account | Adobe, CamScanner, Genius | Accounts and servers |
| Fax, email-to-myself, print services | SwiftScan, CamScanner | Server relay |
| AI summary, Ask AI, Solver, translate | Adobe, CamScanner | Server processing |
| Remote-configured tool catalogue (41 entries, HOT/VIP/EDU badges) | CamScanner | Remote config, upsell clutter |
| Watermark on free output, "remove watermark" upsell | SwiftScan | Hostile to users |
| Ads and reward points | CamScanner, SwiftScan | No ads |
| Smart Erase (generative fill) | CamScanner | Needs large native ML models (APK cost) |
| Magic eraser | Adobe | Same reason |
| Full-screen paywall before the document | Swift, Genius | Paywall before the user's own document |

### S3. Three features that make the Power Pack worth buying (now ₹100)

1. **Verified redaction with ID-number suggestions.** Black out Aadhaar, PAN, account and phone numbers. OCR geometry suggests the lines that contain them. The output is proven to contain no hidden pixels, kept originals or text. A cover-up box in another app does not do that, and there it is the user's liability.
2. **Sign & self-attest.** A saved signature plus a "Self-attested" stamp and date, placed on any page and flattened. This is the everyday Indian workflow for KYC, admission and job applications. Competitors gate it behind subscriptions with watermarks.
3. **Purpose watermark.** "Submitted to ‹bank› for KYC only · 13 Nov 2026" across every page, so a leaked ID copy is useless elsewhere.

**Together they make one story: share an ID or agreement safely, signed and stamped, and nothing leaves the phone.** Split into files and Word (text) are supporting value, not the headline.
