# FF-G-lite geometry rework

Base: lane/ffg `6354659d` (original lane base `fbfe2d46`).
Rule: compression changes raster resolution, not a page's physical paper size.
Scope: ScanRepository geometry propagation and CompressPageGeometryTest.

## Checkpoint
- Test-first cloud snapshot: `03e1da89` (new regression tests only), run `45017400279`:
  `GATE FAIL ffg tests=3736 failures=4 skipped=0 lint=[0 errors, 753 warnings]`.
  Three intended geometry regressions failed: repeated compression shrank A4/Letter,
  extraction lost Letter geometry, draft publication lost reordered/rotated paper sizes.
  The fourth failure was the permitted known flaky `LongImageTest`.
- Implementation: filename-keyed `page_sizes.txt`, legacy positional input accepted only
  when its count matches; bounded/validated parsing with pixel-derived fallback.
- Direct reorder/delete capture each source identity before renaming; rotate swaps dimensions.
- Staged copies, draft replacements/inserts and draft publication carry each file's size.
- Both compression paths prefer existing validated geometry over downsampled JPEG dimensions.
- Full backup copies the sidecar with the document; a restore/recompress regression verifies it.
- No local Gradle/WSL, phone, browser, secrets or merge operations.
- Phone readiness: Not verified. Only cloud repository tests are in scope.
- First implementation gate was blocked by the pre-push path guard before upload because this
  report was under the private `qa/audit-1.3.0` path. Report moved to `docs/review`; no test
  ran and no source snapshot was uploaded by that blocked attempt.
- Implementation full cloud snapshot `0d957888`, run `45018565997`:
  `GATE FAIL ffg tests=3739 failures=2 skipped=0 lint=[0 errors, 753 warnings]`.
  All geometry tests passed. The only failures are the expressly permitted flaky
  `LongImageTest` and `ScanReviewFocusArrangeTest`.
- Isolated rerun of those two classes used the workflow's existing `.ci/test-filter.txt`
  mechanism with the full gate command (cloud-only even when the local build slot is free).
  Snapshot `1187a822`, run `45019794228`:
  `GATE PASS ffg tests=19 failures=0 skipped=0 lint=[0 errors, 753 warnings]`.
  The temporary filter was removed before committing; no tests or workflow checks were weakened.
- Final verification: full run had only the two owner-permitted flaky classes failing;
  both passed in the isolated rerun. All 11 CompressPageGeometryTest methods and the
  existing PdfPageGeometryTest passed in the full run. No phone verification is claimed.
- Next: independent review and lead integration; never merge from this lane.
