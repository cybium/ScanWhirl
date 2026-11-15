# LANE_REPORT_L3 — Protect PDF and Book pages

Session 7: 2026-11-15. Branch `lane/L3-protect`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L3`.

## Gate (last full cloud run, after the final code change)

```
tests: total=1393 failures=1 skipped=0
GATE FAIL L3 · the only failure is TextFitTest > ocr editor text fits (L1's known w320dp issue)
```

## Book pages — orientation is now correct (264 before 265)

The lead's two remaining defects were both direction: the three sideways textbooks came out at
90° (upside down) and the pages were reversed. Fixed in `util/BookSplit.kt`:

1. **Text direction** stays projection-profile first (row vs column autocorrelation in the line
   pitch band, spine-axis cue when ambiguous).
2. **90 vs 270**: the upright candidate is the one whose **quietest band sits low on the page**
   (the blank margin under the last text line; the running head / folio is at the top). The cue
   is found by the lowest ink run on a 500 px copy, so the page's folio side and its blank
   margin decide the turn. The old outer-margin test had the sign inverted — it picked the larger
   *top* blank, i.e. the upside-down half. Owner cue values (0 top … 1 bottom):

   | Photo | p90 | p270 | chosen |
   |---|---|---|---|
   | 005907 | 0.129 | **0.872** | 270 |
   | 005913 | 0.132 | **0.868** | 270 |
   | 005924 | 0.156 | **0.847** | 270 |

3. **0 vs 180**: offline the engine keeps 0 (`uprightFlip`). The ascender asymmetry is only a few
   thousandths on real photos, and books differ in where the folio sits (330 and 340 put it at
   the bottom, 762/137 at the top), so an offline flip would turn upright spreads upside down.
   The device path asks ML Kit for 0/180 and the preview always has **Flip pages**.
4. **ML Kit device path**: `TextRotation.chooseRotation(bitmap, textVertical)` recognises **both**
   candidate turns (90/270 or 0/180) of a downscaled crop and keeps the one with more words.
   `TextRotationTest` uses a fake recognizer that only reads the correct orientation (marker on
   top) and asserts both candidates were scored; ML Kit failure is caught and the engine's
   rotation is kept.
5. **Page order**: the split is taken after the rotation, so the left page (lower page number)
   is output first. The preview below the line shows 264 then 265.
6. **Flip pages** is on every card (not only sideways ones), 180°, and flips the preview plus
   the split ratio; the source rotation used by create follows it.

### Owner eval (raw | cropped; previews in `owner\out\L3\`)

| Photo | Raw | Cropped | Pages |
|---|---|---|---|
| IMG_…_005907 | rot 270, 0.423 | **rot 270, 0.420** | 264 left, 265 right |
| IMG_…_005913 | rot 270, 0.426 | **rot 270, 0.424** | 266 left, 267 right |
| IMG_…_005924 | rot 270, 0.427 | **rot 270, 0.482** | 268 left, 269 right |
| IMG_…_010022 | rot 270 | rot 270 | one page (notebook, honest) |
| IMG_…_010034 | rot 90 | rot 90 | one page (notebook, honest) |
| IMG_…_010054 | rot 90 | rot 90 | one page (notebook, honest) |

### Realworld eval (raw | cropped; previews in `realworld\out\`)

| Photo | Raw | Cropped | Verdict |
|---|---|---|---|
| book_px762687 | 0.522, 1.00 | **0.501**, 1.00, rot 0 | PASS (spine) |
| book_px13752245 | 0.501, 1.00 | **0.495**, 1.00, rot 0 | PASS (spine) |
| book_px23021414 | 0.399, 0.82 | **0.602**, 0.31, rot 0 | PASS (book region; low confidence → check) |
| book_px3303641 | 0.470, 0.33 | **0.474**, 0.34, rot 0 | PASS (no clutter snap) |
| book_px9490246 | 0.376, 0.30 | **0.656**, 0.29, rot 0 | PASS range (near the spine; check) |
| book_px34042183 | one page | **one page** | per the aspect rule (tall crop 0.68) |
| book_px18632404 | one page | **one page** | PASS |

### Freshness

Final eval run **12:58 PM** (local): `owner\out\L3\RESULTS.txt` 12:58:28,
`realworld\out\RESULTS.txt` 12:58:11; all twelve previews rewritten in that run
(005907 12:58:14, 005913 12:58:17, 005924 12:58:20). Every preview is labelled
`line … conf … aspect … text H/V rot … SPREAD/ONE PAGE`, with the rotation applied and the two
resulting pages below; the owner previews show 264/265, 266/267 and 268/269.

## Protect PDF / Unlock PDF tiles (QA bug)

`ToolRegistry`:
- `protect` and `unlock` are **READY**, **free** (no Pro gate) and launch
  `ui/protect/LockToolsActivity`.
- Protect: lists **unprotected** library documents → `ProtectSheet` (`LockFlow.protectDocument`).
- Unlock: lists **protected** documents plus **From a PDF file** → `UnlockSheet`; the file row
  runs the existing device-PDF unlock path (`LockFlow.unlockDevicePdfFile`).
- The `build(...)` helper is gone: no registry entry has `ToolStatus.BUILD`, so no tile can
  silently fall through `host.open` to "Coming soon".
- Tests (`LockToolsWiringTest`): both statuses READY/free; no BUILD entry; every SOON tool calls
  its explicit `comingSoon` and never `open`; the Protect tile starts `LockToolsActivity`; Protect
  chooser → `protectPassword`; Unlock chooser (locked doc + From a PDF file) → `unlockPassword`.

## Flaky Vault test

`VaultPasswordsWiringTest > Remove protection…` awaited only the unlock, then asserted the Vault
forget synchronously. Now the same assertion is awaited:
`pollUntil { !VaultPasswords.of(app).has(id) }`. Two consecutive local runs: 9/9 pass.

## Commits (this batch)

- `e92bddd` Book pages upright direction from the blank-margin/folio cue; ML Kit scores both
  90/270 and 0/180; Flip pages on every card
- `bbdfdf2` Protect PDF / Unlock PDF tiles open a document chooser and the real sheet (READY,
  free); no BUILD fall-through
- `93ae0e8` await the async Vault forget in Remove protection test (deterministic)
- (this commit) ML Kit failure keeps the engine rotation + report

## Device-unproven

- ML Kit's real recognition on the owner's phone (the seam and both candidate lists are tested
  with a fake; the model is unavailable in Robolectric).
- Real scanner perspective crop on the tall `34042183` (still one page per the aspect rule, as
  instructed).
