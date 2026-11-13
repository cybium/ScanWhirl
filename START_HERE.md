# Start here

This is the clean ScanWhirl workspace created on 2026-11-13.

## What changed

- Current source and Git history were copied from the WSL repository.
- The uncommitted M08 UX pass is preserved exactly as it was.
- Candidate APKs, the current handoff, the UX audit, and 15 evidence screenshots were copied into `docs/audit/2026-11-13/` and `builds/2026-11-13/`.
- Build caches, old research screenshots, duplicate ZIPs, and historical plans are excluded.
- The WSL repository and original research folder remain untouched.

## What is current

- Build: `1.1.1-test27`, versionCode 27.
- Source baseline: `d69a304` plus the uncommitted UX pass.
- Latest APK in this workspace: `builds/2026-11-13/ScanWhirl-M08-candidate-D.apk`.
- Candidate D does **not** include the latest uncommitted UX pass.
- The current tree has not been fully tested. The last recorded targeted run was 101 tests with 3 search-related failures.
- The full suite was last reported green at `d69a304` with 797 tests.

## Read order

1. `docs/audit/2026-11-13/HANDOFF.md`
2. `docs/audit/2026-11-13/M08_HANDOFF.md`
3. `docs/audit/2026-11-13/UX_AUDIT.md`
4. `SCANWHIRL_CRITIQUE.md` — only the assigned findings
5. The smallest set of source files that owns those findings

Do not read `docs/history/`; it is deliberately historical. Do not read signing material.

## First action

Before editing, reconcile the critique against the current UX pass. Some findings may already be partially fixed:

- Vault dead entry points: fix is present in the working tree.
- Search matching: rewrite is present, but tests are red.
- Palette/starfield: one-palette work is present.
- Direct save: present, but the exact landing behaviour is still being changed.

Produce a short discrepancy report first: still reproducible, partially fixed, or obsolete because the recording predates the current tree. Then implement only the confirmed top five.
