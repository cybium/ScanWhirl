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

- 11-15 00:03 L2 MERGED (vault QA fixes + QR). L2 now: OCR quality (U+FFFD, reading order) + Hindi OCR (lead adds com.google.mlkit:text-recognition-devanagari:16.0.1 bundled after current gate). OCR quality moved from L3's queue to L2.

## Night plan 11-15 01:37 (owner asleep; owner material in C:\Users\Cybium\Desktop\.sw-qa\owner\ + README)
- L1: ScanLauncher.launchGalleryCrop (ML Kit scanner gallery import = auto-crop) + Photos import sheet.
- L2: OCR quality + Hindi (chip + Settings row).
- L3: Book pages crop-first + HORIZONTAL spines (owner shoots books sideways) + owner photo eval + Isartor/broken.pdf parser robustness.
- L4: To Excel, then huge-PDF import (range, estimate, progress/cancel, storage guard), ID card gallery via launchGalleryCrop.
- L5: Insert pages, then signature/stamp from photo (InkExtract), Mask Aadhaar tool.
- QA: run3 on the next build: RUN3_CHECKLIST (42 bugs) + full map + owner material (no ML Kit camera judgments: phone face down; scanner gallery import CAN be tested).
- Lead: review every eval preview and every run3 screenshot; merge after review; keep integration green; release test29 when run3 verdicts are clean.
- 11-15 03:21 QA run3 partial (one session cannot hold the whole app). Lead reviewed all 75 run3 shots. New QA method: fresh small-scope QA sessions in sequence. QA-3b = ses_e23f51416ffe65yaEiFVyfkmTX (19 untested checklist rows). Dispatched: L1 search no-match/search mode/persisted query/empty clip/chooser height; L3 horizontal spine evidence.

- 11-15 04:28 Merged L2 (OCR+Hindi), L4 (Excel, huge import), L5 (Insert, ink, Aadhaar). integration 1304 tests. APK 96c1d56. QA-3b done (12 fixed/2 changed/4 broken), lead reviewed 101 shots. CRITICAL: Photos to PDF dead -> L1. Dispatched L3 (Protect IME on device, vault remember action, result sizes), L4 (result names, merge naming), L5 (Organize READY, drag on device, draft bar, selection menu, chip rails). QA-4a = ses_e23b774acffe9cuZy91VF8vpgL (new features on owner material).

- 11-15 07:38 Merged L4 (gate everywhere), L2 (PDF/A, veraPDF re-verified by lead incl. negative control), L1 (gallery crop, HEIC fix, motion kit), L5 (Organize READY, drag, Aadhaar element boxes). Full gate OOM -> build.gradle forkEvery 40 / 1.5g (testing). QA-4a reviewed (Aadhaar: first 8 hidden on 4/4 but slivers + VID overrun on old build). Dispatched: L5 stamp vanish, insert-at-start, mask padding + VID, mark cascade, save no-op; L4 file-type result cards, Excel no-table flow, Business card gallery, ID card launchGalleryCrop; L1 snackbar anchoring, selection overlay. L2 = security audit. L3 = Book pages crop-first + rotated + Isartor fuzz + Protect IME/vault/result.

## Cloud gate (11-15 08:22)
- Repo public (owner OK). .github/workflows/gate.yml on ubuntu-latest (16 GB): full suite + lint on integration, ~5.2 min warm. gate.ps1: full suite -> cloud by default (snapshot via temp index -> ci/<lane>, injects lead's gate.yml, gh run list/download with GH_TOKEN from git credential, never printed). -Tests/-Apk local. First cloud runs: 1360 tests (no OOM). Found TextFitTest (L1) + lint StringFormatInvalid draft_replace_msg (lead fixed).
- gh CLI: C:\Program Files\GitHub CLI\gh.exe (no gh auth login; GH_TOKEN per call; token scopes gist, repo, workflow).

- 11-15 09:18 Merged L2 security audit (INTERNET stripped: verified with aapt2 on APK; FLAG_SECURE vault; privacy sweep; PIN PBKDF2; sensitive clipboard). Lead added ML Kit install-time deps meta-data (PM-1.5). Merged L3 (Protect IME/vault/result, protected import, 212-PDF sweep 0 crashes, Book pages partial). Book pages new algorithm sent (text direction -> aspect -> cut). L2 next: release (R8) build in CI + keep rules. L1: + FLAG_SECURE setting. Known allowed failure: TextFitTest (L1 fixing).

- 11-15 10:37 Merged L2 R8 hardening (+CI release job), L3 (FLAG_SECURE sheets, pw zeroing; Book pages UNVERIFIED: previews on disk stale 10:00, L3 regenerating - likely WSL path issue), L4 perf (harness + fixes). L1 hung cloud run: infinite animators leak -> OOM (fix sent); job timeout 20 min. QA-R smoke of release APK = ses_e2287998dffehyK4GGBw4WtlNG. Perf findings queued: L1-1..5, L2-1..3, L5-1..2. L2 now launch readiness (AAB, data safety, privacy policy, checklist). L4 now Scan Enhance 2.0 (eval previews on owner photos). L5 accessibility.
- LESSON: Robolectric runs under WSL: eval outputs must use /mnt/c/... paths; always check preview timestamps before accepting eval numbers.

- 11-15 11:15 RELEASE SMOKE (R8 build) on phone: 9/9 PASS, 0 keep-rule issues (QA-R). Found: Protect/Unlock TILES never wired (BUILD status -> Coming soon) -> L3; flaky VaultPasswordsWiringTest -> L3; Hindi on phone photos falls to Latin -> L2. Lead fixed lint NewApi (SwMotion areAnimatorsEnabled API 26). integration: 1429 tests, lint 0 errors, RELEASE PASS (AAB 23.5 MB). Owner review decisions dispatched: L5 Redact 'Find and hide numbers' (Mask Aadhaar folded), L2 Backup 2.0 + CJK OCR + Hindi tile removal + vault icon overflow, L1 Me cleanup + greyed states + Pro Gold theme/icon, L4 ID card A4/Letter.

- 11-15 12:22 Merged L2: Full Backup 2.0 (.swbackup library+vault+sigs, password or recovery code), OCR index 9x (11.0s->1.2s/500p), Hindi-on-photos merge, vault icon fix, hindi_text tile removed, launch readiness docs (AAB ~8 MB/device). L2 next: vault re-encrypt merge on restore. Gate: -Tests now go to cloud when local slot busy (verified 3.8 min, filter honoured). Owner rejected stock 'aesthetic' photos for scan-quality evals -> L4 re-evaluates on owner photos cropped to the page; Colour boost must differ or go; B&W must keep photos grey.
- 11-15 13:08 Merged L3 (8fe81ed): Book pages upright on owner spreads (lead zoomed all 3: rot 270, 264|265 order OK), Flip pages, Protect/Unlock tiles READY+free, flaky vault test fixed. Lead found: 005924 split ~0.48 is right of spine (cuts text) + glitch line in preview -> L3 next; lock_* strings moved out of strings_l1.
- 11-15 13:26 Merged L5 (8568839): Find and hide numbers (Mask Aadhaar tile -> Redact Find), a11y audit (129 findings: L1 97, L2 24, L4 8) + L5 fixes, grid perf. Lead fixed catalogue 44->43 (L2 + L5 each removed one tile from 45). Find previews use FAKE OCR (boxes miss numbers) -> lead verifies Find on phone with real ML Kit. -Tests now takes comma lists (gradle --tests per pattern). Merged L2 (eb54d02): vault re-encrypt merge on restore. Next: L5 a11y Text+Scan review (L1 files not in L1's set), L2 a11y Vault/Protect/Unlock; L4's 8 a11y later; L4 perf harness still keys on shared EVAL_ON.
- 11-15 13:31 FEATURE FREEZE (owner pushback on scope creep + phone hostage): no new tasks beyond current queues. Scan Enhance capped at this round (Google FULL mode already filters camera scans; ours = gallery/Book pages/auto-enhance off). Phone released to owner until all lanes land; then one phone round + test29.
- 11-15 13:35 Owner: lead must check lanes on its own every 30 min. Lead loop: background `Start-Sleep 1800; tools\lane-watch.ps1` -> on completion act on WARNINGS (stuck = no edit 30 min, gate > 40 min, queue > 20 min, 4 red runs in a row, conflicts, stray files, shared EVAL_ON), then re-arm. Never let the timer lapse while lanes are active.
- 11-15 14:00 Merged L1 (6ed651f): Me cleanup, greyed states, custom quick tools, Pro animation, Gold theme/icon alias, screenshot setting, Batch, library perf. Lead fixes f9658d4: alias swap order (enable new first; never zero icons); kept ImportUnlockSheet (L1 BOM/EOL churn). L1 next: own a11y items only (freeze).
- OWNER OVERNIGHT HANDOFF: owner asleep, full control to lead. Phone connected; lead may screen-record/test everything. Plan: keep 30-min watchdog; land L3/L4/L2/L5/L1 batches; one APK; QA via fresh QA sessions + lead's own phone pass (Find on real ML Kit, Backup QA script, Pro animation + quick tools screenrecord, Book pages, Protect/Unlock, Gold icon swap); fix; then cut test29 (versionCode bump, assembleRelease -PtestBuild, builds/<date>/, tag, push). No Play upload / nothing published beyond the repo.
- 11-15 14:34 integration after L1 merge: run1 3 fails + 15 lint (L1 strings missing hi). Lead fixed lint (722db63) + PerfHarness CME (synchronizedList iterated unlocked). run2 (43696983456): lint 0 errors, RELEASE success, 1 fail PageGridAccessibilityTest 'Page 2 of 3, selected' -> L5. One-off fails (passed on rerun, watch): ScanReviewBackTest discard, ImportProtectedPdfTest sheet closes. L2 branch: DoubleBuffer JNI abort 3/4 runs -> L2 to find trigger.
- 11-15 14:58 LEAD PHONE PASS (build 14:46): quick tools edit/add/remove/drag/persist OK; Pro animation OK (fade, logo turn, cascade ~1 s). UI defects -> L1 (badges cover PRO tag, row-2 tile centred with badges misplaced, circles misaligned by label lines, mid-word breaks, Insert pages blank icon, Unsorted chip 9px, 'ScanWhirlPro', rings clipped, price/Unlock below fold -> sticky buy bar, Pro-state UI ignores isPro, no test 'act as Free' switch in v2). Find and hide numbers with REAL ML Kit on Aadhaar-webp: 1 Aadhaar found, first 8 digits fully hidden, no slivers -> PASS; plural bug 'Found 1 items' -> L5. Me tab: 5 rows, single About/Backup OK. Gate: full -Local disabled for lanes; evals by name only.
- 11-15 15:55 integration GREEN at 4b61dbd (run 43703212470: tests, lint, release). Path: lead found Settings BLANK on phone (L1 hideSection row.parent.parent) -> de7cbd7 + regression test (proven red on old code); DoubleBuffer abort also hit integration -> cherry-picked L2 d1b923b (graphicsMode=NATIVE suite-wide); merged L2 a11y (allow-list union resolved); 3 Settings clips exposed by the fix allow-listed for L1. Flaky (passed on rerun): ScanReviewFocusArrangeTest delete count, ScanReviewBackTest discard, ImportProtectedPdfTest -> L5 investigating ScanReview ones.
- 11-15 16:59 Merged L3 (spine no-ink rule, 005924 0.418 verified by lead), L1 visual batch, L4 (Enhance 2.0 verified on owner photos; ID A4/Letter; a11y). Allow-list resurrection caught 4x on merges (lanes keep stale copies) -> lead rebuilds list = integration minus that lane's fixed entries; now 3 entries left. Lead phone pass 2: quick tools aligned OK; Settings rows 'Block screenshots' + 'act as Free' were inside the HIDDEN About card -> cc16031 'Privacy & security' section + test; lint WrongConstant fixed. Pro gate recorded in Free: bar drawn at top + full-height shine before layout, no cascade, tags not refreshed on Pro change -> L1. Phone handed to L2 for Backup QA script (~60 min). L3 on slanted spine cut.
- 11-15 18:05 LEAD PHONE PASS 3 (a174507, Free via test switch, 15 fps recording): Pro gate PASS - bar pinned at bottom from frame 1, logo turn, title/pills fade-up, rings, shine clipped to Unlock button after content; PRO tags refresh (4 on Home in Free). Incident: L2 enabled airplane mode ~17:23 before the owner's warning reached the lead (phone = PC hotspot; mobile reconnected 17:26:57, ~3 min outage). Lead's fault (step was in the script); rule added to brief. L5 DeadButtonAuditTest merged. L3: slanted cut 15-20 px left of fold, crosses text -> per-band gap method + ink-crossed eval check.
