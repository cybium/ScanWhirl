# RS review rework checkpoint

Base: `79c1626ec4079972342b848e71e9437b9ae30720`, branch `lane/rs`.

Scope: R1-R5 result safety and presentation. Originals remain unchanged. A result screen
is not an authority to delete arbitrary file paths. Size rejection happens before publication.

## Checks

- Tests written first: cloud RED snapshot `2d0d249e`, run `45017461975`.
  Compilation failed on the new repository entry point (and the initially incorrect
  test OpFaults import, subsequently corrected); not a passing behavioral run.
- The first filtered gate unexpectedly selected its legacy local/WSL route.
  Its owned Windows process tree was terminated immediately. No local result is claimed.
  Subsequent gates use the unfiltered cloud route only.
- First full implementation gate: snapshot `d3887718`, run `45018011194`,
  3733 tests / 6 failures, lint 0 errors. Failures exposed stale unmeasured-size and
  identity fixtures, a missing typed-target wire extra on rejection, and Hindi
  expanded-result text over budget. Fixed the fixtures/contracts; More now replaces
  the comparison proof instead of stacking text underneath it.
- No device/ADB/browser checks, merge or release.
- Re-verification `f76da4be`, run `45019289061`: 3737 tests / 2 failures;
  lint 0 errors. One known PageViewerFilterTest flake, plus Hindi source-kept
  expanded result still over budget. Shortened the Hindi saved suffix without
  changing its meaning; final full gate remains pending.
- Run `45020643144`, snapshot `183dece7`: 3737 tests / 1 failure, lint 0 errors.
  The Hindi missing-original test exposed an asynchronous empty-label race.
  Missing-source text is now rendered synchronously before image decoding.
- Run `45021688419`, snapshot `b09076a3`: 3737 tests / 2 failures
  (S1StartupResumeTest and ScanReviewFocusArrangeTest), lint 0 errors.
  No code or test changes followed this run.
- FINAL FULL CLOUD PASS: run `45022831405`, snapshot `eb6c8f43`.
  `GATE PASS rs tests=3737 failures=0 skipped=0 lint=[0 errors, 762 warnings]`
  This reran the entire unchanged suite, including both preceding failures.
  Only this checkpoint's reporting changed after the green snapshot.

## Intentional existing-test changes

- Compare captions: repeated size/pixel facts -> localized Before/After labels.
  The hero retains the measured sizes; tests now assert those facts there.
- Export hero: synthetic size extras -> actual file lengths.
- Legacy larger-output cleanup fixture: supply an explicit full producer identity;
  missing/changed identities are separately required to preserve the current output.
- Streaming source contract: the result producer now invokes the pre-publication
  rejecting repository entry point, not the legacy batch-compatible copying API.

## Next safe action

Commit without merging; independent review and integration/device verification remain
the lead's responsibility.
