# ScanWhirl: what is current (read this first)

Created 2026-11-19 by the lead. If a document below disagrees with an older one, this list wins.
A document saying DONE / APPROVED is not proof; proof is an exact commit SHA plus its CI run, and an APK hash
plus a device check for phone claims.

## Order of authority
1. Recorded owner decisions and the decision ledger: `docs/DECISIONS.md`.
   For what the app DOES today, the code and its CI run win over any prose (tool counts: `ui/kit/ToolRegistry.kt`).
2. Product invariants (offline, no INTERNET permission, no account/ads/analytics/own cloud, EN + HI parity):
   `AGENTS.md` (OpenCode) and `CLAUDE.md` § The project.
3. Current product decisions: `docs/release/IDENTITY.md`, `docs/release/PRO_PLAN.md`,
   `docs/release/UI_PLAN.md` §0 and §0b (only these two sections are binding; §2-§6 are earlier proposals).
4. Visual direction only: `next/design/*_v4.html` concepts. Concept approval is NOT implementation approval;
   see DECISIONS UX-004 for the one open judge objection.
5. Evidence and rationale, not instructions: research, critic and judge reports in `next/docs/research/`,
   `next/docs/review/`, `docs/audit/`, `research/app-benchmark-starter-kit/`.
6. Historical: everything listed under "Historical" below.

## Current working files
| Need | File |
|---|---|
| Program state and running lanes | `docs/plans/PROGRAM.md` (a manual log: check CI/APK before trusting a row) |
| Identity, voice, never-claim list | `docs/release/IDENTITY.md` |
| Free vs Pro, entitlement policy | `docs/release/PRO_PLAN.md` |
| UI rulings and approved direction | `docs/release/UI_PLAN.md` §0, §0b |
| Decisions, owner questions, supersessions | `docs/DECISIONS.md` |
| Code map | `docs/ARCHITECTURE.md` (tool counts there are stale; use ToolRegistry) |
| Gates | `tools/gate.ps1`, `.github/workflows/gate.yml`, `tools/ci/gate_verdict.py` |
| Known UI problems (baseline, not spec) | `docs/audit/2026-11-18/UI_CRITIQUE.md` |

## Historical (keep for evidence; never follow as instructions)
| File | Why historical | Superseded by |
|---|---|---|
| `START_HERE.md` (2026-11-16 state) | 1.2.1 era, points to the v2 design | this file + PROGRAM |
| `docs/design/2026-11-14/70_APP_DESIGN.md` | v2 UI (Home quick tools, neon, 44 tools) | UI_PLAN §0/§0b, IDENTITY |
| `docs/release/UI_PLAN.md` §2-§6 | earlier proposals partly overruled by §0 | UI_PLAN §0/§0b |
| `docs/release/PLAY_LISTING.md` | ₹100 setup, absolute privacy wording, unverified features | DECISIONS MKT-002 (draft fix pending) |
| `docs/release/LAUNCH_CHECKLIST.md` | 2026-11-15 snapshot | template only |
| `docs/release/OLD_APP_NOTES.md` | says "new app lives in next" (reversed 11-18) | DECISIONS PRD-002 |
| `docs/release/NEXT_RELEASE.md`, `docs/release/1.2.3_PLAN.md` | past task registers | PROGRAM |
| `docs/plans/lanes/71_LANES.md`, `73_ORCHESTRATION_LOG.md` | five-worker v2 mechanics, "lint only on integration" | PROGRAM §2 |
| `docs/plans/lanes/72_WORKER_BRIEF.md` | old worker brief | AGENTS.md + task contract |
| `next/README.md`, `next/docs/PLAN.md` | Next rebuild, ON HOLD 11-18; PLAN §1 says "uses the internet" | DECISIONS PRD-002 |
| `next/docs/research/WHY_SCANWHIRL.md`, `PSYCHOLOGY.md`, `UX_PRINCIPLES.md` | research drafts; key claims rejected by judges | IDENTITY, UI_PLAN §0 |
| Nov 10 benchmark owner decisions (`research/app-benchmark-starter-kit/.../14_FINAL_OWNER_DECISIONS.md`, `21_PRODUCT_OWNER_DECISIONS.md`) | ₹100, Long Image removal, device-lock Vault | DECISIONS POL-001/002, PRC-001 (open) |
| `LANE_REPORT_*.md`, `MORNING_*.md` | each true only for its own commit | CI run for the exact SHA |
