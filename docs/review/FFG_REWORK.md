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

## F3 follow-up (base 4b9c45b3)

- Review found that a crash/write failure after an in-place edit could leave a valid-looking
  geometry entry bound to different pixels. New records bind paper points to ordered decoded
  pixel bounds **and SHA-256**. Ordered bounds encode orientation; the digest also distinguishes
  different pages with identical bounds and square/180-degree rotations.
- Every read rejects mismatched entries per page, derives safe pixel-based dimensions and
  rewrites the sidecar. A failed repair never makes the stale entry usable on that read or
  on a subsequent reopen.
- Delete/reorder write the prospective name-to-content bindings before renaming pages.
  Rotate stages pixels, publishes the staged content binding, then commits the pixels.
  Failure to publish geometry aborts before published image mutation.
- Intentional filters/marks renew the content binding while preserving their paper size.
- Unbound legacy positional/filename-only entries cannot prove which image they describe:
  they now migrate conservatively from pixels. Updated the previous legacy regression's
  expectation explicitly from retained unverified points to derived points; this is required
  by F3, not a weakened identity check.
- Test-first snapshot `dcb8c657` contains only the three new F3 regressions and the temporary
  cloud-only test filter. Run `45020613798`:
  `GATE FAIL ffg tests=14 failures=3 skipped=0 lint=[0 errors, 753 warnings]`.
  All three intended new regressions failed on the old implementation (stale reorder,
  unswapped rotation, and failure to abort when metadata publication fails).
- Additional regressions cover death on both sides of the metadata/image boundary, repair
  failure, intact pages when geometry rename fails, equal-bounds changed pixels and refiltering.
- Copy paths validate only the requested source pages and retain other destination records
  without rebinding them. All records are still validated before use; this avoids hashing an
  entire multi-page document once per copied page.
- Temporary filter removed. Full implementation gate started; a small read/copy I/O optimization
  made after its snapshot will require a final full gate on the final source tree before commit.
- Pre-optimization full snapshot `b65bd40d`, run `45021107111`:
  `GATE FAIL ffg tests=3746 failures=2 skipped=0 lint=[0 errors, 753 warnings]`.
  All geometry tests passed; only permitted flaky `LongImageTest` and `PageViewerFilterTest`
  failed. Final full gate on the read/copy optimization started; outcome pending.
- Final implementation full snapshot `7a079fe1`, run `45022432958`:
  `GATE FAIL ffg tests=3746 failures=2 skipped=0 lint=[0 errors, 753 warnings]`.
  All 18 CompressPageGeometryTest methods passed. Only permitted flaky
  `LongImageTest` and `FormReadyActivityTest` failed.
- Isolated cloud rerun queued for those two classes plus the pre-optimization run's
  permitted flaky `PageViewerFilterTest`. No source/test expectation changes since the
  final full snapshot. The temporary workflow filter will be removed before commit.
- Isolated three-class run `45023800511` (`f67bd688`):
  `GATE FAIL ffg tests=16 failures=1 skipped=0 lint=[0 errors, 753 warnings]`.
  The final full run's two failing classes (`LongImageTest`, `FormReadyActivityTest`)
  passed. `PageViewerFilterTest` failed on a different, permitted flaky assertion.
  Rerunning that class alone, not changing tests or implementation.
