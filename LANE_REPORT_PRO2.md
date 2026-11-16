# Lane report — pro2 (lane/pro-fixes, round 2)

Status: **done**. Merge-review fixes for `.sw-qa/night/REVIEW_PRO_FIXES.md`.

- `9b9cfdd` pro: keep billing callbacks on main, one purchase at a time, versioned ownership, locale-scoped price cache
- `542a5ab` pro: gate the OCR To Word action and add a registry backstop in the tool setup
- `9f15073` pro: send a Free Batch intent to the shell Pro offer instead of a local dead Buy
- `dfc1e74` pro: real editor entitlement tests, refreshed More-sheet test and Find-sheet audit

Last gate line (cloud, full suite): `GATE PASS pro2 tests=1583 failures=0 skipped=0 lint=[skipped]`
Targeted cloud gate for the rewritten audit: `GATE PASS pro2 tests=4 failures=0 skipped=0`.

Tests added: worker-thread/double-tap/cancel/launch-exception/locale/adversarial-order tests in
`ProBillingTest`; startup-crash and reversed-price-order tests in `ProPriceTest`; real editor creation,
recreation, default TEST_BUILD and release-Free tests in `PdfViewerProGateTest`; `ToolSetupProGateTest`
(4); Free To Word test in `OcrTextScreenTest`; Batch paywall test in `BatchUiTest`; refreshed
`Phase5ScreensTest` and `DeadButtonAuditTest` Find-sheet expectations.

Left as tracked, not fixed: selling Soon Book pages, obsolete sales promises, Gold appearance.
Windows-only `Durable`/`renameTo` failures remain local-only and unrelated.
