# Pro package (lead decision 2026-11-18, from R1 -> critic -> judge)

Evidence: next/docs/research/PRO_VALUE.md, next/docs/review/CRITIC_R1_PRO.md, next/docs/review/JUDGE_RESEARCH.md §1.
Status: DECIDED for design; final copy waits for the Pro inventory (below).

## The package
1. **Pro (one-time, one Play non-consumable):** tools that ADD to the free core and already work offline: convert
   (Word/Excel/PowerPoint), sign + edit (text, draw, highlight, stamp, watermark, page numbers), page tools (split etc.),
   batch tools, tags, batch rename, QR, PDF/A, hide private info. No cloud, no promised future features.
   **Corrected by PRO_INVENTORY (next/docs/review/PRO_INVENTORY.md):** Vault, ID card and move-to-folder are FREE
   today and stay free (Pro may never take a free feature). Folder CREATION is Pro today and becomes free.
2. **Free forever:** unlimited scan/import, OCR read/select/copy/search, merge, password protect, clean watermark-free
   export, offline, Hindi, no ads/account, **single-document compress-to-target incl. 20/50/100/200 KB presets**
   (Pro may only sell BATCH compress). **Basic folders are free** (the new Home shows them).
3. **Moment:** a Pro tool tile says "Pro · 1 free use". Before the first run it says, word for word:
   "Your first use includes a full-quality save, free and without a watermark. Further uses of this tool need Pro
   ({Play price}, one payment). No automatic charge." Buttons: **Try free** / **Not now**. The gate shows BEFORE the
   second run, never after work is done. After a successful core export: at most one small dismissible invitation.
4. **Paywall screen:** Pro purchase (Play's localized price), **Continue free**, **Restore purchase**, a short list of
   what stays free. Nothing preselected; no live ratings (offline).
5. **Price framing:** "{Play price} once. No subscription." India control ₹499; Play price experiment ₹299 vs ₹499
   when eligible; judge it on Console revenue + refunds. No ₹/day, no competitor comparison, no anchor tier,
   no crossed-out or "launch" price, no countdown.
6. **Never:** hostage output, trial watermark or lower quality, new caps on core, moving a free capability behind Pro,
   nagging, telemetry/server, auto-charge wording, guarantees we can't keep.

## Inventory result (2026-11-18, PRO_INVENTORY.md)
- 16 Pro tools + tags/batch rename/folder create/batch, all through `ToolRegistry.launchTool` and `ShellActivity.proGate`.
  Sign, QR and PDF/A show a "Got it" sheet instead of the Pro screen: unify in the paywall lane.
- Redaction is SAFE in every export (boxes baked into re-encoded pixels and verified, `.orig` deleted, OCR text dropped
  before the PDF rebuild, no PDF metadata). Gap: the untouched ORIGINAL stays in the library. The result must say so
  and offer "Replace original (moves it to Trash)"; Trash must be emptied to be gone: say that too.
- No trial counter yet; it goes in `scanwhirl_prefs` next to ProGate (7 entry points listed in the inventory).
- Lanes from this: `paywall` (one Pro screen for every gate, "1 free use" + disclosure, Restore, Continue free) and
  `folders-free` (folder creation free). Both after phase 2 kit; copy EN + HI.

## Original task list for the inventory (done)
- Every current Pro gate and free capability, with file:line; what changes under this package (only Pro -> free moves).
- **Redaction check:** does "Hide private info" remove the underlying text/image data and metadata in every export
  format, or only draw a box? Until it truly removes, it is not marketed as private/safe.
- Trial counter: local per tool (resettable, accepted: not fraud-proof).

## Existing tester product
The 1.2.x console product at ₹100 stays for current testers until the price experiment starts.
