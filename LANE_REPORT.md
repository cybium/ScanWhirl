# LANE_REPORT — L3 Protect PDF (+ Book pages)

Session 2: 2026-11-14. Branch `lane/L3-protect`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L3`.

## Session 2 — done

1. **Reader's Protect now opens the v2 Protect sheet** (`ui/DocumentViewerActivity.kt`).
   - More → Protect PDF (Pro) calls `LockFlow.protectDocument(doc)`; the old one-field
     `promptProtectPdf()` and its `R.string.protect_note` (RC4-128) usage are deleted.
   - Only that function/caller changed in the file. `strings.xml` untouched; `protect_note` is now
     unreferenced and can be removed by the lead/L1.
   - Test: `ui/ViewerProtectSheetTest` (More → Protect opens `protectTitle` + `protectPassword` +
     `protectConfirm`, nothing locked until submit).
2. **`git merge integration`** — clean (picked up the latest kit/TrustPill and the worker brief).
3. **Change password uses the kit, not a dialog** (`tools/LockFlow.kt`).
   - `LockFlow.promptCurrentPassword` is now an `SwSheet` with the kit `PasswordField` (strength
     meter off, show/hide toggle). `promptChangePassword` passes `repo.verifyPdfPassword` and the
     document's `TryCooldown`, so a wrong current password stays in the sheet with the tries-left
     line and the 5-try 30 s pause/doubling — the same pacing as the Unlock sheet.
   - The viewer/repair callers keep the old behaviour (text handed over unchecked) via the default
     `verify = null`; the viewer's own `repo.unlockPdf` still reports wrong passwords.
   - Test: `ui/ChangePasswordSheetTest` (kit field + tries-left, right password opens the
     "Change password" Protect sheet, 5 wrong tries disable Continue with the pause message).
     `FreeLegacyVaultEntryPointTest`'s unlock test was rewritten to the new sheet
     (`// v2 UI: 09_unlock_sheet`), same data-safety assertion.
4. **New tool "Book pages" (Pro, Create)** — `70_APP_DESIGN.md` §3.
   - `util/BookSplit.kt`: gutter scan over the middle 30% of the width. Each sampled column's
     luminance mean/σ on a bounded grid; score = darkness − σ, winner must beat the band average by
     a margin and be uniform, otherwise the exact middle (0.5) is used. Crops two bitmaps whose
     widths add up to the source. `decode()` picks `inSampleSize` so the long side is **at most
     3000 px** (never full-resolution); `splitToJpeg()` decodes once, crops, encodes and recycles.
     Source bytes are never written.
   - `ui/book/BookPagesActivity.kt`: gallery picker uses the same `GetMultipleContents` image
     contract as Photos to PDF; one preview card per picked image, each with a draggable gutter
     line (`BookSpreadView`, fits the bitmap, line can be moved by touch). "Create document" builds
     2 JPEGs per input and calls the existing `ScanRepository.createDocument`, then opens the new
     document in the reader. `ui/book/BookSpreadView.kt` is the preview.
   - `ui/kit/ToolRegistry.kt`: only the `book_pages` entry changed (SOON → READY, `pro = true`,
     launch starts `BookPagesActivity` via the host, which is the shell Activity/Context).
   - `AndroidManifest.xml`: one `<activity>` line for `.ui.book.BookPagesActivity` (required).
   - Strings only in `values/strings_book.xml` + `values-hi/strings_book.xml` (Hindi starts with
     `<!-- review -->`); no ids XML was added (the screen exposes its views for tests).
   - Tests: `util/BookSplitTest` (dark middle band found, uniform → exact middle, two outputs with
     widths adding up, two JPEGs per spread with the source file byte-identical, sampled decode
     capped at 3000), `ui/book/BookPagesActivityTest` (2 spreads → one document with 4 pages and a
     PDF; the registry entry is READY/Pro and launches the screen).

## Not done / carried

- **Book pages is not Pro-gated inside the screen**: the catalogue entry is `pro = true`, but the
  L1 shell currently does not gate any Pro tile before launch. Gating belongs to the shell/Pro
  flow; tell me if the lead wants an in-screen Pro sheet.
- The Book pages screen is code-built (no layout XML) with kit colours/type; no mockup exists for
  it.
- "Remember in Vault" and the Unlock sheet's Vault button remain hidden until L2's API merges.
- `ui/DocumentViewerActivity` still shows the legacy `protect_note` string **in strings.xml**
  (unused now); the lead removes it as agreed.
- `tools/pdfcheck.py` still needs the `(doc.metadata or {})` guard (L3 host check note from
  session 1; the tool crashes on locked PDFs, the files themselves are correct).

## Gate result (last full run, session 2)

```
tests: total=888 failures=0 skipped=0
GATE PASS L3 tests=888 lint=[lint skipped (lead runs it at merge)]
```

## Commits (session 2)

- `9e28617` L3: reader More → Protect opens the v2 Protect sheet (password twice), old RC4 dialog removed
- `a86a2ea` merge `integration` into `lane/L3-protect`
- `094024e` L3: Change password asks the current password in the kit SwSheet + PasswordField with tries-left/cooldown
- `f4a6633` L3: Book pages tool — BookSplit gutter engine, draggable preview, create document (Pro, Create)

## Session 1 (unchanged summary)

AES-256 PDF protection (V5/R6 AESV3, Unicode passwords), Protect/Unlock sheets, ProtectPolicy
(tries/cooldown), `PdfAes`/`PdfEncryptor`/`PdfVerifier` work and the host check with PyMuPDF are
described in the previous report revision; the last session-1 gate was
`GATE PASS L3 tests=853`. That work is merged by the lead.
