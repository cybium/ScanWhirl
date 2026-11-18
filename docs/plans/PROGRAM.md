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
