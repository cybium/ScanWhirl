# Start here

This is the clean ScanWhirl workspace created on 2026-11-13.

## What changed

- Current source and Git history were copied from the WSL repository.
- The uncommitted M08 UX pass is preserved exactly as it was.
- Candidate APKs, the current handoff, the UX audit, 15 selected M08 screenshots, and the 2026-11-13 device walk were copied into `docs/audit/2026-11-13/` and `builds/2026-11-13/`.
- Build caches, old research screenshots, duplicate ZIPs, and historical plans are excluded.
- The WSL repository and original research folder remain untouched.

## What is current

- Build: `1.1.1-test27`, versionCode 27.
- Source baseline: `d69a304` plus the uncommitted UX pass.
- Latest APK in this workspace: `builds/2026-11-13/ScanWhirl-M08-candidate-D.apk`.
- Candidate D does **not** include the latest uncommitted UX pass.
- The current tree compiles successfully in this Windows workspace. It has not been fully tested. The last recorded targeted run was 101 tests with 3 search-related failures.
- The full suite was last reported green at `d69a304` with 797 tests.
- The installed candidate D was walked on device. It invalidates the video’s stale-grid and universal-B/W findings and adds current failures: page deletion survives force-stop with no discard; PRO/Vault is a dead end; document actions and lock state are hidden.

## Read order

1. `docs/audit/2026-11-13/HANDOFF.md`
2. `docs/audit/2026-11-13/DEVICE_FINDINGS.md` — current installed build
3. `docs/audit/2026-11-13/M08_HANDOFF.md`
4. `docs/audit/2026-11-13/UX_AUDIT.md`
5. `SCANWHIRL_CRITIQUE.md` — only the assigned findings
6. The smallest set of source files that owns those findings

Do not read `docs/history/`; it is deliberately historical. Do not read signing material.

## First action

Do not start by “fixing” the video’s B/W or stale-grid findings. Both failed to reproduce on candidate D. The current device-verified first five are:

1. Page-edit transaction and Save changes discard/provenance.
2. PRO/Vault dead end.
3. Document actions and lock state in the reader/row.
4. Resize keyboard, units and preset precedence.
5. Privacy language around “Google processing” and “no servers.”

The working tree contains post-candidate-D fixes for Vault visibility, palette, Tools, Home and search. Verify those on a fresh build before editing the old surfaces.

Produce a short discrepancy report first: still reproducible, partially fixed, or obsolete because the recording predates the current tree. Then implement only the confirmed top five.
