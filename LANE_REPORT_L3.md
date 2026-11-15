# LANE_REPORT_L3 — Protect PDF and Book pages

Session 6: 2026-11-15. Branch `lane/L3-protect`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L3`.

## Gate (last full cloud run)

```
tests: total=1385 failures=1 skipped=0
GATE FAIL L3 · the only failure is TextFitTest > ocr editor text fits (L1's known w320dp issue)
```
(An earlier run also showed `ScanReviewBackTest` once; it did not reproduce. The photo/PDF evals
are local `-Tests` runs; the cloud has no `.sw-qa` and they return early there.)

## Book pages — final structure (per the lead's decision tree)

1. **Text direction first** on the scanner-cropped image: autcorrelation of the downscaled
   row/column projection profiles (line pitch 8-60 px). When the profiles are ambiguous (dense,
   curved pages), a strong spine in one axis decides: a horizontal spine means the photograph is
   sideways. Vertical text is turned 90° (ML Kit rotation choice in the UI seam; the offline
   margin heuristic as fallback; the **Rotate pages** button flips the other candidate).
2. **Shape** on the upright image: aspect ≥1.15 = spread, ≤0.95 = **one page** (Keep-as-one ON),
   between = a confident gutter decides. A two-column portrait page is one page, never split at
   its column gap.
3. **Cut** only in the middle 30% of the width: dark narrow valley / brightness dip between text
   blocks; no confident valley → exact middle + "Check the line." (pulse once).
4. **Entry**: "Add a spread" → [Scan with camera] (our ML Kit scanner call) and [From photos]
   (L1's `ScanLauncher.launchGalleryCrop`, with its manual fallback). Both perspective-crop first.

### Realworld eval (raw | cropped; previews in `realworld\out\`)

| Photo | Raw | Cropped | Verdict |
|---|---|---|---|
| book_px762687 | 0.522, 1.00 | **0.501**, 1.00, rot 0 | **PASS** (spine) |
| book_px13752245 | 0.501, 1.00 | **0.495**, 1.00, rot 0 | **PASS** (spine, not a column gap) |
| book_px23021414 | 0.399, 0.82 | **0.602**, 0.31 | **PASS** (spine in the book region) |
| book_px3303641 | 0.470, 0.33 | **0.474**, 0.34 | **PASS** (no clutter snap) |
| book_px9490246 | 0.376, 0.30 | **0.656**, 0.29 | **PASS range** (near the spine; low confidence → check) |
| book_px34042183 | one page | one page | **per the aspect rule** (tall crop 1632×2400 = 0.68; the scanner's warp may differ) |
| book_px18632404 | one page | one page | **PASS** (Keep-as-one) |

### Owner eval (raw | cropped; previews in `owner\out\L3\`)

| Photo | Raw | Cropped | Verdict |
|---|---|---|---|
| IMG_…_005907 | rot 90, 0.595 | **rot 90, 0.599** | sideways textbook: horizontal spine turned upright, split left/right |
| IMG_…_005913 | rot 90, 0.586 | **rot 90, 0.592** | same |
| IMG_…_005924 | rot 90, 0.585 | **rot 90, 0.599** | same |
| IMG_…_010022 | one page | one page | notebook: honest one-page result |
| IMG_…_010034 | one page | one page | notebook: honest one-page result |
| IMG_…_010054 | one page | one page | notebook: honest one-page result |

The phone bug ("sideways spread → Keep as one page ON") is fixed: the three sideways textbooks
are recognised as spreads and rotated; the notebooks are honestly one page under the aspect rule.

## Security audit fixes (L2)

- `SecureView.apply(window)` on the **Protect PDF**, **Unlock**, **import Unlock**, and the
  **current-password** sheet windows (plus `SwSheet.secure()` for the kit sheet). Tests assert
  `FLAG_SECURE` on all three password surfaces.
- `PdfAes` zeroes the prepared password byte array in `create`, `userKey` and `ownerKey`
  (`try/finally { pw.fill(0) }`); the String itself is Java-immutable and out of our control.

## Earlier work carried in this branch (accepted by the lead)

- Protect sheet IME inset scroll; save-in-Vault prompt after the save (Unlock/Not now); Protect
  result summary ("Protected with AES-256" instead of Before/After); page plurals.
- Protected-PDF import Unlock sheet with offline AESV3 V5/R6 decrypt; hostile PDF sweep 212 files,
  **0 crashes** (`ENCRYPTED=3`, `NOT_A_PDF=1`, `UNSUPPORTED=208`; two 53 MB books "too large to
  inspect offline"); `MalformedPdfTest`.
- Vault reason lines via `VaultActivity.intent(context, reason)`; real-photo Book pages eval
  harness.

## Commits (this batch)

- `44f4b34` Book pages text-direction-first structure (upright rotation, aspect shape, middle-30%
  spine) + both evals
- `86994e1` FLAG_SECURE on password sheets (L2 `SecureView`), zero prepared password bytes in
  `PdfAes`
- (this commit) "From photos" via L1's `launchGalleryCrop` + report

## Known / carried

- The tall `book_px34042183` crop is one page under the aspect rule; the scanner's perspective
  warp may make the real device crop landscape. Lead's call if it should be forced to a spread.
- Notebooks are one page (honest); their fold near an edge is not trusted as a spread.
- DEVICE-UNPROVEN: ML Kit text-rotation choice, real VaultActivity return, real PdfRenderer import
  of a decrypted file.
