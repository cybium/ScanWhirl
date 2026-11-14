# 73 — Orchestration log (lead's record of running workers)

The lead (Opus) runs the lane workers itself as background subagents (model `opencode-go/deepseek-v4.1-flash#high`,
agent `general`). Each worker's final reply comes back to the lead automatically. Follow-ups go to the same session ID.
The owner does not relay messages. If the lead is out of usage, finished reports wait; the owner types "continue".

Brief every worker follows: `72_WORKER_BRIEF.md`. Gate: `tools/gate.ps1` (WSL mirror build, FIFO queue, lint only with -Lint).

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
| L3 protect | ses_e2673ceb8ffe1MYGfhQWbVUcue | viewer Protect -> ProtectSheet; Change password sheet; new tool Book pages | running |
| L4 tools | ses_e2673ad8bffe8h4gyE8XU1TUWK | finish Phase A step 3; merge integration; Phase B ToolSetup + Result; wire 12 tools | running |
| L5 edit & sign | ses_e2673902dffe4adGDrfK9NBXAw | wave 1 MERGED (16:3x, 929 tests, lint 0). Now wave 2: PM-2.2b -> 2.2c -> 2.3b Organize draft | running |

## Lead to-do
- Tell L1 (on its report): ToolRegistry icons swapped - Draw uses ic_sym_highlight, Highlight uses format_list_numbered; fix (draw = ic_sym_draw/gesture, highlight = ic_sym_highlight). Also kit-style the FlowUtil.pickScan chooser in the sweep.
- Device test batch (one APK build through the queue) once L1 + L4 land: L5 phone checks = drag/resize/rotate, signature draw, redaction, Save-as-new-copy keeps original, Free/Pro.
- After L3 lands: remove unused `protect_note` from strings.xml / values-hi (L1-owned file; lead does it on integration).
- After L2 lands: give L3 the "Remember in Vault" / "Use password saved in Vault" wiring (uses VaultPasswords).
- Release test29 from integration once L1+L4 Phase B are merged and device-checked.
- Wave 2 candidates: Organize draft (PM-2.2b/c, 2.3b), Insert pages, QR (needs ML Kit barcode: library approval),
  Folders & tags, Batch rename, To Excel, PDF/A, Hindi OCR, Translate, PM-1.5 manifest.
