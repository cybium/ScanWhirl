# ScanWhirl program of record (lead-owned; read after CLAUDE.md; update after every milestone)

Owner order 2026-11-18 ~08:00: the lead decides UI/UX and Pro value from research and psychology, not the owner's
gut. All permissions granted (phone, workers incl. ChatGPT for research/judging, Google docs, context7). The owner
does not need to see screens: designs and code pass a CRITIC and a JUDGE; the lead decides what ships.

## 1. Goal
Turn 1.2.x into an appealing document app worth paying for (Pro one-time ₹499, Play price test incl. ₹299), via the
7 phases in docs/release/UI_PLAN.md, each ending in a test APK installed on the owner's Infinix.

## 2. Pipeline (every design and every code lane)
1. BUILD: DeepSeek lane (exact files, tests first, gate only) or research worker (cited sources).
2. CRITIC: fresh-context DeepSeek, read-only, closed defect list (design: UI_CRITIQUE A-D + research principles;
   code: correctness, data safety, scope, tests, EN+HI, no long dashes, current-docs compliance via context7).
3. JUDGE: GPT-6.1 Sol (`openai/gpt-6.1-sol`, strict reviewer), read-only: PASS / FAIL per item with reasons.
   Use Sol ONLY for judging (ChatGPT allowance is limited; never for long coding).
4. LEAD decides; merges one lane at a time with the cloud gate; device pass on the Infinix (screens captured,
   looked at, EN + HI) before the owner gets an APK.
Limits: max 2 BUILDING lanes at once (7.4 GB PC); read-only research/critic/judge workers may run alongside.

## 3. Current state (update this table, newest first)
| When | State |
|---|---|
| 11-18 09:4x | All 3 critics done (CRITIC_R1_PRO: fake competitor prices, ₹999 decoy hollow, try-once needs up-front disclosure, portal presets free for 1 doc; CRITIC_R2_UX: rows 17/18 misread, Replace stays default; CRITIC_C1: 20 rows, #15 Open-button `<queries>` = HIGH, lead CONFIRMED at SavedSnackbar.kt:191 (precheck with no <queries>; fix = drop precheck, try/catch exists)). JUDGE Sol running over all three (ses_e13363d80ffe4Bmgvwuwg3zi62 -> next\docs\review\JUDGE_RESEARCH.md). Queued lanes after fixcopy/fixidcrop: fixopen, fixime, fixinsertcrop, fixshare, fixbilling, fixpicker. |
| 11-18 09:2x | C1 landed (19 findings: 0 H / 10 M / 9 L; top: photo-picker backport entry, ShellActivity IME inset dropped, reinstall ownership of Documents/ScanWhirl = phase 4 risk, <queries> for SavedSnackbar, billing reconnection) -> CRITIC_C1 running (ses_e133b5bdcffenIjc25IUjnEH7u). R1 landed (₹499 lifetime, free floor incl. clean export, paywall at first export + try-once) -> CRITIC_R1 running (ses_e133af889ffeI67ZdXq3RUdGw3); lead doubts: hollow ₹999 Supporter decoy, try-once-pay-at-save disclosure, "₹1.4 a day" self-contradiction, portal presets in Pro vs free compress. Then ONE Sol judge pass over R1+R2+C1 with their critics (saves Sol allowance). |
| 11-18 08:3x | CRITIC_fixidcrop = FIX FIRST (D2 Back during applyCrop still accepts the side + encodes a recycled bitmap; D5 rotation loses crop). Lead confirmed in code. Sent back to builder (same session ses_e135a77cfffea8ukwEU842674x) with D2, D5, D10 hint, D9 48dp/a11y; D3 (ImageTools unsampled HEIC fallback) DEFERRED to a later lane. After rework: JUDGE (Sol). R2 UX_PRINCIPLES landed (top: SCAN out of the nav bar -> 4 tabs + extended FAB; motion 2-tier not <250 ms; no counting animation; tonal cards not shadows; Hindi type floor). CRITIC_R2 running (ses_e133c773cffeGMJs0KG4w4QO5u -> next\docs\review\CRITIC_R2_UX.md); lead suspects rows 17 (Replace default) and 18 (bilingual) misread the plan. Still running: fixcopy, R1, C1. |
| 11-18 08:1x | fixidcrop DONE (ddf5b34/837304b, gate PASS) -> CRITIC running (ses_e1340f8d5ffeTPMZeN93um32rM -> next\docs\review\CRITIC_fixidcrop.md), then JUDGE (Sol), then merge. fixcopy still building. Research running: R1 PRO_VALUE (ses_e1340cd51ffetsh3Pa5CdXTV5x), R2 UX_PRINCIPLES (ses_e1340ad98ffewWlswnjM5wLqnj), C1 docs audit (ses_e13404439ffe7QjjF987Jf6CsP -> next\docs\review\C1_DOCS_AUDIT.md). Workspace index: ScanWhirl-HQ\README.md. Next building lane after fixidcrop merge: `fixinsertcrop` (Insert pages > Photos through PhotoAutoCrop + corner screen). |
| 11-18 08:0x | Phase 1 running: lanes `fixidcrop` (ses_e135a77cfffea8ukwEU842674x: ID gallery auto-crop + corner crop, page preview backdrop) and `fixcopy` (ses_e134869b9ffewBP8QDcSk1uVg7: S1 recovery code to file, wording C). Merged today: fixui, fixflow, fixtext, fixresult, fixvault, fixhome. Installed on phone: 1.2.3-test (26111801, 4831f20). Next: research R1/R2 + context7 audit C1, then Phase 1 finish (insert-pages photo crop) and Phase 2. |

## 4. Key files
- docs/release/UI_PLAN.md (design + phases), docs/release/1.2.3_PLAN.md (owner decisions),
  docs/audit/2026-11-18/UI_CRITIQUE.md (what is wrong), next/docs/research/ (pricing, online/offline, + R1/R2 output).
- Evidence: ScanWhirl-HQ\qa\screenmap (174 screens + SCREEN_MAP.md), qa\hunt\BUGS_HUNT.md.
- Night log for the owner: ScanWhirl-HQ\MORNING_2026-11-18.md.

## 5. Open research (feeds phases 3-7)
- R1 Pro value + paywall psychology (India, ₹299-499 one-time, ethical: no dark patterns, Play policy).
- R2 UI/UX principles for a document app on budget Android (Material 3 / expressive motion, accessibility, Hindi).
- C1 Code vs current docs (context7 + Google docs): Play Billing, MediaStore/SAF, photo picker, FileProvider.
