# Next release (after 1.2.1): deferred work

Owner rule for 1.2.1 (2026-11-16): fix already-reported bugs only, no new features. Everything below
was found or decided during the 1.2.1 night shift and is deliberately NOT in 1.2.1.
Source reports live in `C:\Users\Cybium\Desktop\.sw-qa\night\` and `.sw-qa\qa_tools\` (private).

## Features shown as "Coming soon" in 1.2.1 (build properly next)
- **Batch rename** (Pro): the tile opened a selection bar with no rename action. `util/BatchRename.kt`
  exists (pattern + preview logic); needs a real flow: pick documents, pattern, preview, apply, undo.
- **Reorder pages**: Organize pages offers rotate/delete/extract only; drag/long-press never reorder.
  Needs drag-to-reorder in the page grid (ui/pagegrid) with the Organize draft commit.
- **Book pages** and the **Gold theme** (greyed since 1.2.0).

## Billing known limit
- R6: the cached Play price is keyed by app language only. A Play country/currency change with the
  same language keeps the old price on the Pro screen until the next successful price query (Play's
  own payment sheet always shows the correct price). Fix: key the cache by Play's billing country
  (BillingClient.getBillingConfigAsync) and refresh on resume.

## Reported findings not fixed in 1.2.1 (if their lanes did not finish before the 10:15 cut-off)
Check the lane reports: CRASH_FIXES.md (MED rows), DATA_FIXES.md (B4-B8, B10), LAYOUT_FIXES.md
(deferred items, e.g. Pro screen trust-pill row at 320dp), qa_tools REPORT.md (MED/LOW), qa_lang
REPORT.md (LOW), UI_CRITIQUE.md (subjective items rejected by the UI lane).

## Store / listing
- Reshoot store screenshots around one finished scan (current 04_text_ocr shows an OCR misread).
- App name changed in PLAY_LISTING.md to "ScanWhirl: PDF Scanner & Tools" (owner applies in Console).
- Add `android { ndk { debugSymbolLevel = "SYMBOL_TABLE" } }` only if the native-symbols warning
  matters (safe to ignore).

## Process lessons
- OWNER: never use Grok again. Research any model before launching it; do not guess effort levels.
- Every worker brief must cite `docs/plans/lanes/72_WORKER_BRIEF.md` (gate only, never gradlew,
  gate in foreground). Five local Gradle builds at once killed a worker.
- Lane gates skip lint; merge one lane at a time and wait for the integration gate (lint runs there).
- Money code: never merge without a strong reviewer (OpenAI caught regressions all tests missed).
- Known flaky on CI: PageNormalizationTest (OOM on the runner), ExportHygieneTest. Re-run once.
