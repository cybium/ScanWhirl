# ScanWhirl Next: lead plan (2026-11-17)

Owner = guide, lead = lead (CLAUDE.md rule 12). This plan is the lead's; its flaws are the lead's.
Status: APPROVED by the owner 2026-11-17 (all 4 decisions in section 9: go). Desktop moved, repo
`cybium/scanwhirl-next` created, shot list at `ScanWhirl-HQ\qa\recordings\SHOT_LIST.md`,
Google docs MCP waiting for the owner's API key.

## 1. Goal

A new test app, built one feature at a time, each feature 100% complete (designed, criticised,
reworked, coded, tested on emulators and cloud devices) before the next starts. It uses the internet.
It replaces the 1.2.x app on Play later (same package `com.scanwhirl.app`, same upload key), so
testers and the Play listing carry over. Feature 1 is our own scanner.

## 2. Where the lead disagrees with the owner, and why

| Owner said | Lead's call | Why |
|---|---|---|
| Download scanner apps and reverse engineer them | **Black-box study only**: run them on the emulator, record what they do (when they lock, how they guide, timings, edge cases). No decompiling of their code. | Decompiling and copying their algorithms is exactly what causes the copyright strikes the owner wants to avoid (point 7), and their licences forbid it. Behaviour, ideas and published papers are free to learn from; their code is not. |
| Understand OCR engines, build our own | **Own the scanner, license the OCR.** OCR stays Google ML Kit text recognition (free, on-device, commercial use allowed, Latin + Devanagari). | A competitive OCR engine is a multi-year research project. Users judge us on capture: finding the page fast, a steady outline, a clean result. That's where our own engine pays off. |
| Never use my phone, only emulators | **Emulators for UI and flow; recorded real video for the detector; cloud real devices for sign-off.** | Both earlier scanners passed synthetic tests and failed on the real phone. An emulator camera is not a real camera (no blur, glare, noise, shaky hands). Fix: record ~30 short real videos ONCE (owner, with the phone's normal camera app, not our app), replay them on the PC and on GitHub on every change, and score them. Final check of each feature: Google's Developer Device Platform ("Device Run", successor of Firebase Test Lab, which shuts down 2027-09-30) real devices in the cloud, not the owner's phone. |
| Reverse engineer to fix flicker | Flicker is a **tracking** problem, not an algorithm-secret problem (see section 4). | A steady outline comes from temporal filtering, lock/unlock hysteresis and analysing at a sane frame size. Diagnosis of our old code is in section 4.1. |

## 3. Workspace and tooling

**Desktop**: one folder, `Desktop\ScanWhirl-HQ\`:
- `current\`: today's repo (1.2.x maintenance only)
- `next\`: the new app (new private repo `cybium/scanwhirl-next`)
- `qa\` (was `.sw-qa`), `research\` (Research, Archive, benchmark kit, old assets, reviews, notes zip, a11y log, path backup), `promo\`, `privacy-site\`
- Not touched: `Others`, the shortcuts, `desktop.ini` (the owner's).
- The 12 lane worktrees: every branch is pushed first; the dirty ones (`data-fixes2` 2 files, `layout-robust2` 10 files) and L4's 1 unmerged commit are saved to `park/*` branches. Then the worktrees are removed. Nothing is lost; all of it stays on GitHub.
- Script paths (`tools/*.ps1`, `store_frames.py`) and CLAUDE.md are updated to the new paths. The OpenCode session moves with the repo.

**MCPs (installed 2026-11-17, global config `~/.config/opencode/opencode.jsonc`)**:
- `context7`: connected. Current library docs (CameraX, Compose, Kotlin, OpenCV, Play Billing).
- `mobile`: connected. Drives the emulators (tap, type, screenshot, UI tree). Never use its paid remote cloud devices.
- `google-docs` (Google Developer Knowledge: developer.android.com, Firebase, Play docs): configured, **disabled until the owner creates an API key** (section 9).
- Added later, when its feature starts: Firebase MCP (analytics, crash reports, Test Lab).
- Not needed: a GitHub MCP (the `gh` CLI already does everything).

**Cloud workers on GitHub (Actions)**: every push runs build + unit tests + the detector benchmark
(replays the recorded videos, posts scores) + emulator UI tests (Linux runners with KVM),
uploads screenshots and recordings as artifacts. The PC (7.4 GB RAM) never runs Gradle and an
emulator at the same time. Local emulator = design checks only.

## 4. Feature 1: our scanner

### 4.1 Why the old ones failed (code diagnosis of 750b2eb and 5878cd1, 2026-11-17)
1. **All-or-nothing per frame, no memory**: about 10 hard checks (side support >= 0.40, paper-outside
   test, same contrast on all 4 sides, 1.5% frame margin...), each re-run from scratch every frame.
   On real frames one check fails at random, so the page flips found / lost. The previous outline is
   never used to help find the next one.
2. **Edge threshold re-picked every frame** from the top 2% of gradients: auto-exposure, text and
   clutter change it, so the detected lines change every frame.
3. **Steadiness limit (0.018) smaller than the detector's own jitter (~0.014 from 1-degree Hough
   bins at 213 px)**: the auto-capture timer kept resetting, so it almost never fired.
4. **The overlay jumped** to any new "best" outline (a text block or table edge could win), and its
   colour was re-picked every frame: visible flicker.
5. **Too few pixels**: ~213x160 working image, so soft page edges fell below the vote floor.
6. **Pages near the frame edge were rejected** ("move back"), but that is how people hold a phone.
7. **5878cd1 was worse**: it took the nearest strong edge pixel to each image corner, drew the raw
   outline every frame, ignored rotation, and auto-captured after 4 frames (~130 ms), with no
   one-shot guard.
8. **Tested only on perfect synthetic rectangles**: no blur, exposure change, shadows or curled edges.
   No real recorded frame was ever used. That is why it passed the tests and failed on the phone.

Design rules that follow: score softly instead of gating; search around the last outline first;
keep thresholds stable over time; set the steadiness limit from the detector's measured jitter;
refine corners to sub-pixel level; work at >= 480 px; allow corners near or slightly outside the
frame; one capture per page; and measure everything on real recordings.

### 4.2 Engine design (lead's choice)
- CameraX preview + ImageAnalysis (keep only the latest frame, ~640 px luma), analysis off the UI thread.
- **Detector** produces candidate page outlines with a confidence score.
- **Tracker** (the missing piece last time): matches the outline across frames, smooths each corner
  (One-Euro / Kalman filter), and uses hysteresis: **lock** after N consistent frames, **unlock**
  only after M misses. The screen draws only the tracked outline, animated. Never the raw per-frame result.
- **Auto-capture**: only when locked + still for T ms + sharp enough; takes a full-resolution photo,
  re-detects corners on it, straightens it (perspective warp), then enhancement.
- **Detector choice is decided by the benchmark, not opinion**: (a) classical pipeline on OpenCV
  (Apache-2.0) first; (b) if (a) misses targets, a small page-segmentation model trained by us on our
  own synthetic + recorded data (weights 100% ours, run with LiteRT).
- Only permissive licences (Apache-2.0, MIT, BSD). No GPL/AGPL code, no research-only datasets.

### 4.3 Targets (feature is not done until all pass)
| Metric | Target |
|---|---|
| Page found (benchmark recall) | >= 95% of real clips |
| Corner accuracy | median error < 2% of page diagonal |
| Outline jitter when phone is still | < 1.5 px at 640 px width |
| Time to lock | < 600 ms |
| False auto-capture (clips with no page) | 0 |
| Analysis cost per frame (low-end device class) | <= 40 ms |

### 4.4 ScanWhirl identity (seed idea for the design phase)
"Whirl lock": four corner marks spiral in and snap onto the page corners when it locks (no jumpy
polygon); the shutter ring fills as steadiness grows and fires at full; a soft haptic tick on lock;
the captured page whirls into the page tray. Brand cyan and gold. To be designed, criticised and
reworked before any code (section 5).

## 5. Process per feature (gates; the next stage reads the file, not the chat)

1. **Research**: black-box competitor study on the emulator + papers and permissive open source,
   each with its licence written down.
2. **Interface + navigation design**: screens, states (no permission, no page, searching, locked,
   capturing, error, low light), transitions, copy (EN + HI), as rendered mockups.
3. **Critique**: a fresh-context reviewer with a written defect list (read-only; "no defect" is a
   valid answer) + the lead's own critique.
4. **Rework** -> owner approves the design in words (Status: approved in the design file).
5. **Spec + tasks**: each task lists the exact files it may touch and its acceptance test.
6. **Code**: engine first (benchmark green), then UI.
7. **Verify**: CI green, emulator flows recorded, Developer Device Platform real devices, lead looks at
   every screenshot. Then the definition-of-done checklist.

## 6. What we change in how the lead runs workers (learned from others)

Sources: Abdo & Brom, "A Gated Spec-Driven Workflow" (arXiv 2611.05094, Nov 2026); Qu et al. (scope
blocks cut out-of-scope agent actions from 17.1% to 0.0%); practitioner reports on Opus + DeepSeek
Flash in OpenCode (DeepSeek drifts on long, loose runs and needs tight boundaries and validation).

1. **Every worker task ends with a scope block**: "you may change only these files: ...; everything
   else is out of scope; if in doubt, stop and report". (Our DeepSeek data batch 2 reverted merged fixes
   because nothing stopped it.)
2. **Checks in code, not in prompts**: after each worker run, a script compares `git diff --name-only`
   with the declared file list and rejects anything outside it. The lead does not judge this by eye.
3. **Small tasks for DeepSeek**: 1-3 files, with the test to pass given up front. No more "audit the
   whole app and fix everything" batches.
4. **Reviewer = fresh context + closed defect list + read-only permissions**, per stage (design,
   code, money/data code). It never fixes; the lead decides.
5. **An ambiguous owner reply is not approval**: the paper's orchestrator overrode a gate after an
   ambiguous reply. The lead asks one clear question instead.
6. **Lean**: one lead + at most 2 workers at once (RAM, and elaborate multi-agent setups cost more
   for similar quality). The lead never waits in a loop on a worker.

## 7. Internet: consequences to handle (not features yet)
- The "Offline" trust pill and every "no internet" claim must go; Data safety and the privacy policy
  must be rewritten before the internet build ships.
- Purchases: Play Billing + server-side verification later; decided in its own feature.
- Analytics + crash reports: Firebase, in its own feature, with consent where required.

## 8. Order of work
0. Workspace (desktop, worktrees, new repo, CI skeleton) -> 1. empty app: one Scan button, CI green
-> 2. scanner research -> 3. scanner design + critique + rework + owner approval -> 4. benchmark
recordings + harness -> 5. engine -> 6. scanner UI -> 7. cloud real-device sign-off.

## 11. Google Cloud free-trial credit (~Rs 28.5k, 90 days from 2026-11-17; checked in Google docs 2026-11-18)
- Real-device tests: Developer Device Platform / Test Lab, $5 per physical device-hour, $1 per virtual
  (promo rates until 2027-04-30). The credit covers it: about 60 physical device-hours. Used for each
  feature's sign-off on several low-end phones.
- Purchase verification backend later (Cloud Run + Play Developer API + real-time notifications): small cost.
- NOT possible on the free trial: GPUs (Compute Engine, Vertex AI). A trained scanner model would train on CPU.
- Never upgrade the trial to a paid account without the owner's yes. Set a budget alert first.

## 9. Decisions needed from the owner
1. **Desktop move** (section 3): go?
2. **Real recordings**: ~30 short clips of real paper with the phone's normal camera app
   (shot list provided by the lead). Without them the detector can't be measured honestly.
3. **Google docs MCP key** (optional, 5 min): Google Cloud console -> enable "Developer Knowledge API"
   -> create API key -> Windows user environment variable `GOOGLE_DK_API_KEY` -> OpenCode must
   then be restarted once (environment variables are read at start).
4. **New private GitHub repo** `cybium/scanwhirl-next`: go?

## 10. Old scanner diagnosis
Done: see 4.1 (explore subagent ses_e154dad93ffeqPFSJfERND2fnQ, file:line references in its report).
