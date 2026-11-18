> **STATUS HISTORICAL (banner added 2026-11-19).** Describes the repo on 2026-11-16 (1.2.1 era) and points to the v2 design. Current: `docs/ACTIVE_INDEX.md` and `docs/DECISIONS.md`. Kept as evidence; do not follow as instructions.

# Start here

One page of orientation for ScanWhirl. Everything below describes the repository **today**
(2026-11-16); older handoffs are archived in `docs/history/` and must not be used as instructions.

## Where the project stands

- **Product:** offline Android document scanner, package `com.scanwhirl.app`, English + Hindi,
  no internet permission.
- **Releases:** 1.2.0 (versionCode `26111501`) on Play closed testing; 1.2.1 (`26111601`) in
  preparation. Version codes are date based `YYMMDDnn` and live in `app/build.gradle.kts`.
- **Branches:** `master` is the main branch, `integration` is the working branch that everything
  is merged into. Lanes work on `lane/<name>` branches in their own worktrees and never merge.
- **Referee:** the CI gate — `.github/workflows/gate.yml` runs the full unit suite with
  `-PtestBuild`, lint on `integration`, and a minified release build with `-PciUnsignedRelease`.
  A task is done only when the gate prints `GATE PASS`.
- **Pro:** one-time Play Billing product `scanwhirl_pro` (`util/ProBilling.kt`, `util/ProGate.kt`).
  Test builds unlock Pro so the paid UI can be reviewed.

## Read order (only these, in this order)

1. [`README.md`](README.md) — product, build variants, how to build and test.
2. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — package map and the key classes.
3. [`CLAUDE.md`](CLAUDE.md) — working rules for an implementation session.
4. The doc for the area you are touching:
   - release / store: `docs/release/PLAY_LISTING.md`, `docs/release/DATA_SAFETY.md`,
     `docs/release/RELEASE_NOTES_PLAY.txt`, `docs/release/LAUNCH_CHECKLIST.md` (a 2026-11-15
     pre-launch snapshot — verify anything about console state).
   - UI: `docs/design/2026-11-14/70_APP_DESIGN.md`, then the newest `docs/audit/<date>/` folder.
   - PDF engine / tool behaviour: `docs/plans/pdf-manager/`.
5. The smallest set of source files that owns the problem.

Do **not** read `docs/history/` unless the owner asks for archaeology. Do **not** read
`keystore/`, `keystore.properties` or `local.properties`.

## Where things live

| Need | Location |
| --- | --- |
| App code | `app/src/main/java/com/scanwhirl/app/` (packages explained in `docs/ARCHITECTURE.md`) |
| Strings | `app/src/main/res/values/strings.xml` (English), `app/src/main/res/values-hi/strings.xml` (Hindi) |
| Tests | `app/src/test/java/com/scanwhirl/app/` — same package names as the code |
| Gate, phone and store tooling | `tools/` (`gate.ps1`, `wait-integration.ps1`, `lane-review.ps1`, `phone.ps1`, `store_*.py`, `promo/`) |
| Release copy | `docs/release/` |
| Audits (dated) | `docs/audit/<yyyy-mm-dd>/` — the newest date is the current one |
| Design specs / mockups | `docs/design/2026-11-14/` |
| Older material | `docs/history/` — frozen, provenance only |
| Play icon + feature graphic | `store_assets/` |

## First actions for a new session

1. `git status` and `git log --oneline -10` in your worktree; read the last few commit subjects —
   they are the real changelog.
2. Read your lane brief (if one exists) or ask the lead for the one task you own.
3. Run `powershell -File tools\gate.ps1 -Lane <lane>` on the untouched tree before editing, so you
   know whether the baseline is green. Waiting in the queue is normal; do not cancel it.
