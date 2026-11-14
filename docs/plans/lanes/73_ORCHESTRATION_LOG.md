# 73 — Orchestration log (lead's record of running workers)

The lead (Opus) runs the lane workers itself as background subagents (model `opencode-go/deepseek-v4.1-flash#high`,
agent `general`). Each worker's final reply comes back to the lead automatically. Follow-ups go to the same session ID.
The owner does not relay messages. If the lead is out of usage, finished reports wait; the owner types "continue".

Brief every worker follows: `72_WORKER_BRIEF.md`. Gate: `tools/gate.ps1` (WSL mirror build, FIFO queue, lint only with -Lint).

## Lesson
- OWNER RULE (2026-11-14): never review only what the owner points at. Every screen, sheet and tool in docs/audit/2026-11-14/SCREEN_MAP.md (regenerate with tools/screen_map.ps1) needs a screenshot the lead has personally looked at and a written verdict before any release. Review screenshots in contact sheets if needed, but look at every one.
- Before assigning a card, check its prerequisites are in integration (L5 was blocked on PM-2.1b living only on L4). Merge a lane's committed, gated work early when another lane needs it.

## Lead loop (on every worker report)
1. Read the report; `git -C ..\ScanWhirl-Ln log/status`; review the diff (manifest, build files, @Ignore, deleted tests,
   ownership).
2. In the main checkout (branch `integration`): `git merge --no-ff lane/<branch>`; `tools\gate.ps1 -Lane main -Lint`.
3. Fix tiny issues on integration myself; send bigger ones back to the worker (same session ID).
4. Push integration. Device-test merged features on the phone (adb serial 103223137I112880).
5. Give the worker its next task by session ID (or a new wave task). Update this log.

## Workers (wave 1, started 2026-11-14 ~15:50)

| Lane | Session ID | Task given | Status |
|---|---|---|---|
| L1 shell & look | ses_e267412b0ffeZ84ZxoKo9gr68N | finish uncommitted Phase B; merge integration (TrustPill conflict); Manrope; reader dock 07, Text 08, SwSheet sweep | running |
| L2 vault | ses_e2673f395ffesg3lAiuldphAaI | finish Phase B vault UI; biometric/passcode; backup/restore; migration; VaultPasswords API for L3 | running |
| L3 protect | ses_e2673ceb8ffe1MYGfhQWbVUcue | MERGED 57fcdb3 (1038 tests). Idle; next: Remember-in-Vault wiring after L2 BiometricPrompt lands; real-photo Book pages tuning after QA run 2 | idle |
| L4 tools | ses_e2673ad8bffe8h4gyE8XU1TUWK | Phase A (2.1b, 2.1c, step 3) MERGED partially at 4f10678 (967 tests, lint 0); still running Phase B ToolSetup + Result; wire 12 tools | running |
| L5 edit & sign | ses_e2673902dffe4adGDrfK9NBXAw | wave 1 MERGED (16:3x, 929 tests, lint 0). Now wave 2: PM-2.2b -> 2.2c -> 2.3b Organize draft | running |

## Extra workers
- QA (phone tester, no code edits): ses_e25ac0c75ffeEtMUJGpbRCXJyq - full tap-through of debug build from integration ac87cba; output C:\Users\Cybium\Desktop\.sw-qa\run1\REPORT.md + screenshots. Lead reviews every screenshot visually and writes the critique.

## Lead to-do
- QA run1 (old build) findings sent: L1 #1-9 (search broken CRITICAL, sort dead, IME nav, Searchable PDF->Settings, launch consistency, snackbar, translucent sheet, Text screen actions, review rail); L5 crop presets/handles; L4 plurals. PENDING for L3 after Book pages: OCR quality - U+FFFD chars and wrong block reading order (.sw-qa/run1/doc2_ocr.txt).
- Verdicts in SCREEN_MAP are written from run2 (build 435e0f3) screenshots, not run1.
- Real-world test photos ready: C:\Users\Cybium\Desktop\.sw-qa\realworld (7 book photos from Pexels + README with EXPECTED results; ID specimens still blocked by Wikimedia 429, only id_lux_back; retry tools/fetch_realworld.ps1 later). Use for QA run 2 and L3 Book pages tuning (incl. new requirement: 'Not a two-page photo? Keep as one page').
- Copy pass: no long dashes, short text (tools/copy_audit.ps1). L1 does strings.xml + strings_l1; other lanes' files after their current tasks (protect 3 dashes, vault 2 dashes + 4 long, stamp 2 long, tools 1 long).
- Assets prepared for L1: .sw-assets/symbols (official Material Symbols, 83 icons), .sw-assets/brand (ic_brand_mark from launcher icon).
- Tell L1 (on its report): ToolRegistry icons swapped - Draw uses ic_sym_highlight, Highlight uses format_list_numbered; fix (draw = ic_sym_draw/gesture, highlight = ic_sym_highlight). Also kit-style the FlowUtil.pickScan chooser in the sweep.
- Device test batch (one APK build through the queue) once L1 + L4 land: L5 phone checks = drag/resize/rotate, signature draw, redaction, Save-as-new-copy keeps original, Free/Pro.
- After L3 lands: remove unused `protect_note` from strings.xml / values-hi (L1-owned file; lead does it on integration).
- After L2 lands: give L3 the "Remember in Vault" / "Use password saved in Vault" wiring (uses VaultPasswords).
- Release test29 from integration once L1+L4 Phase B are merged and device-checked.
- Wave 2 candidates: Organize draft (PM-2.2b/c, 2.3b), Insert pages, QR (needs ML Kit barcode: library approval),
  Folders & tags, Batch rename, To Excel, PDF/A, Hindi OCR, Translate, PM-1.5 manifest.

## Status 2026-11-14 ~21:00
- integration has L1 (icons, brand, layout, Pro gating, Text screen), L2 (BiometricPrompt, backup v2), L3 (Remember in Vault, Book pages), L4 (PowerPoint, Word pictures, ID card flow), L5 wave 1. 1080 tests, lint 0 errors.
- Running: L1 Folders & tags + Batch rename; L2 QR code + vault copy; L3 Book pages real-photo tuning + Keep as one page; L4 Business card + tools copy; L5 Organize draft (PM-2.2b/c, 2.3b, CamScanner-style grid); QA coverage run.
- Next lead step: APK of integration -> phone -> QA continues coverage on the new build -> lead reviews every screenshot (SCREEN_MAP verdicts).

- 23:05 L5 wave 2 MERGED (draft engine, grid, crop): integration 1c13819, 1118 tests, lint 0 errors. L5 now: Insert pages + carried items + copy.
