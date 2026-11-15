# LANE_REPORT_L3 — Protect PDF and Book pages

Session 5: 2026-11-15. Branch `lane/L3-protect`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L3`.

## Gate (last full run, cloud)

```
tests: total=1187 failures=0 skipped=0
GATE PASS L3 tests=1187 lint=[skipped]
```
Cloud snapshot `16e7d76b` (run 43673119996). The local photo/PDF evals run separately with
`-Tests` (the cloud has no access to `.sw-qa`).

## Book pages — crop first, both orientations, honest confidence

- **Entry**: the empty screen (and "Add photos") opens the **"Add a spread" SwSheet** with
  **Scan with camera** / **From photos**. Both start the ML Kit document scanner
  (`SCANNER_MODE_FULL`, gallery import allowed, JPEG, page limit 30), so the book edges are
  detected and the corners adjustable before any split. `ScanLauncher.launchGalleryCrop` was not
  in integration when this was done, so we call `GmsDocumentScanning` ourselves; falling back to
  the system picker + the engine's page-region guard when the scanner is unavailable.
- **Engine** (`util/BookSplit.kt`): page-region search (ink-rich columns/rows expanded over
  paper), a narrow low-ink valley/crease, both sides page-bright within 20%, one-sided blank
  only when the blank side is a real page (extent ≥25%), three-band agreement, confidence < 0.4
  = "Check the line." and a single pulse of the line. **Both axes** are scanned; a horizontal
  spine returns the top and bottom pages rotated **upright and in reading order** (top = page 1,
  each page turns the opposite way because their printed tops face opposite edges).
- **Rotation**: `ui/book/TextRotation` tries ML Kit text recognition on both 90° candidates of a
  downscaled top page (seam `chooser` for tests); offline it uses the margin heuristic
  (`BookSplit.topRotatesClockwise`). The card has a **Rotate pages** button for horizontal
  spreads; keeping one page is still offered only for one-text-block photos.
- **Hint copy** shortened for the phone: "Looks like one page.", "Check the line.",
  "Pages will be turned upright.", "Gutter found. Drag to adjust." (≤2 lines).

### Evaluation (previews in `realworld\out\` and `owner\out\L3\`)

Realworld, raw + hand-cropped (`crops.txt`), 7 photos:

| Photo | RAW | CROP | Expected | Result |
|---|---|---|---|---|
| book_px762687 | 0.500, 0.77, V | 0.475, 0.83, V | spine 0.45-0.55 | **PASS** |
| book_px13752245 | 0.513, 0.85, V | 0.510, 0.85, V | spine 0.45-0.55 | **PASS** |
| book_px23021414 | 0.693, 0.54, V | 0.589, 0.61, V | 0.53-0.59 | **PASS** |
| book_px3303641 | 0.677, 0.49, V | 0.461, 0.35, V | 0.42-0.48 | **PASS** |
| book_px9490246 | 0.378, 0.54, **H** | 0.455, 0.85, **H** | 0.45-0.50 V | **FAIL** (horizontal false positive on an angled page) |
| book_px34042183 | 0.472, 0.47, V | 0.391, 0.81, **H** | 0.51-0.57 V | **FAIL** (horizontal false positive) |
| book_px18632404 | 0.500, 0, V | 0.500, 0, V | one page | **FAIL** (single-page flag lost after the dual-axis change) |

Owner's sideways phone photos (raw + crop), previews show the line and both upright pages:

| Photo | RAW | CROP | Note |
|---|---|---|---|
| IMG_..._005907 | 0.430, 0.71, **H** | 0.422, 0.69, **H** | horizontal spine found; line above the mid (expected 0.45-0.55) |
| IMG_..._005913 | 0.436, 0.71, **H** | 0.431, 0.69, **H** | same |
| IMG_..._005924 | 0.406, 0.69, **H** | 0.423, 0.59, **V** | raw horizontal, crop falls back vertical |
| IMG_..._010022 | 0.500, 0, V | 0.500, 0, V | notebook fold not found (no Keep-as-one) |
| IMG_..._010034 | 0.381, 0.66, V | 0.355, 0.64, V | notebook fold not found |
| IMG_..._010054 | 0.500, 0, V | 0.550, 0.55, V | notebook page |

**Known issues**: the horizontal axis can win on the two angled realworld photos (9490246,
34042183) and the notebooks' folds near an edge are not reliable; the one-page 18632404 lost its
single-page flag. The phone's "Keep as one page ON for a sideways spread" bug is fixed: the
single-page flag now requires that **neither** axis sees two page blocks, so a sideways spread is
never offered as one page. All previews are written for every photo (raw + cropped) for the
lead's inspection.

## Protect sheet / Vault / result screen (phone QA 3b)

- **IME**: `ProtectSheet` sets the sheet expanded and `skipCollapsed`, and consumes
  `WindowInsetsCompat.Type.ime()` as **bottom padding on the scroll container** (edge-to-edge
  means adjustResize is inert); it also scrolls the focused field into view. Test dispatches an
  IME inset and asserts the padding, expanded state and that the primary button is reachable.
- **Save password prompt**: a locked Vault no longer silently fails. After the protect/save
  action completes, an SwSheet asks **"Save the password in Vault?"** with **Unlock** (L2
  `VaultActivity.intent(context, reason)`, then save on return) and **Not now** (only then
  "Password not saved in Vault."). Tests cover Unlock saving and declining.
- **Result screen**: Protect passes a `summary` to `ExportResultActivity`; the screen shows
  "<document>\nProtected with AES-256" instead of "Before 0 B -> After …". Test pins it.
- Page plurals: `protect_doc_meta` and `book_created` are `<plurals>` ("1 page", "6 pages").

## Protected-PDF import (A) and PDF robustness

- `PdfDecryptor` (V5/R6 AESV3 classic-xref) + `ScanRepository.importProtectedPdfAsScan` +
  `ImportUnlockSheet`: the import dead end now asks for the password (tries-left/cooldown),
  decrypts a **temporary** copy, imports the pages and deletes the copy; the source is untouched.
  "Use password saved in Vault" appears when the file matches a locked library doc with `has()`.
- **Hostile PDF sweep** (`PdfRobustnessEval`, 212 PDFs: 204 Isartor + downloads, `broken.pdf`):
  **0 crashes**, `OK=0, ENCRYPTED=3, NOT_A_PDF=1, UNSUPPORTED=208`; results in
  `owner\out\L3\PDF_RESULTS.txt`. The two 53 MB textbooks are classified UNSUPPORTED ("too large
  to inspect offline", the import path handles them separately); 208 valid PDFs are outside the
  generator shape our strict verifier rewrites (honest "not one ScanWhirl rewrites").
- Always-on `MalformedPdfTest` (empty, header-only, truncated xref, huge /Length, bad /Encrypt)
  pins friendly `Unsupported`, no IndexOutOfBounds.
- DEVICE-UNPROVEN: the real `TextRotation` ML Kit path, the real `VaultActivity` return, and the
  real `PdfRenderer` import of a decrypted file (Robolectric has no renderer).

## Commits (this batch)

- `a639e12` Book pages crop-first scanner, both spine orientations/upright rotation, honest
  confidence, real-photo eval
- `e9b22d3` Protect sheet IME inset scroll, save-in-Vault prompt after the save, protected result
  summary, page plurals
- `01b84f5` protected-PDF import Unlock sheet with offline AESV3 decrypt; hostile PDF sweep
- (this report)
