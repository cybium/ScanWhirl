# ScanWhirl decision ledger

One row per decision. Status words: PROPOSED, ACCEPTED (decided, not built), IMPLEMENTED (in code at a SHA),
CI_VERIFIED, PHONE_VERIFIED, OPEN (needs the owner). A row never upgrades itself because a report says DONE.
Reopen a decision only when its trigger happens; otherwise do not restart the debate. Started 2026-11-19 (lead).

## Product and identity
| ID | Job / problem | Current rule | Evidence | Rejected | Supersedes | Status | Reopen if |
|---|---|---|---|---|---|---|---|
| PRD-001 | What ScanWhirl is | Two jobs: prepare a real file for a form; later find, reuse, recover it. "Scan it. Size it. Keep it." / "Calm. Exact. Yours." | IDENTITY.md, JUDGE_WHY | "the file that gets accepted", "only scanner" | WHY_SCANWHIRL drafts | ACCEPTED (positioning true today) | 5-user task test contradicts it |
| PRD-002 | Rebuild or evolve | Evolve THIS app in place; offline; Next rebuild ON HOLD, its research/design reused | Owner 11-18; PROGRAM | Next rebuild with internet | next/docs/PLAN.md, OLD_APP_NOTES | ACCEPTED | Owner decision only |
| PRD-003 | Camera scanner | Camera scanning stays Google ML Kit. Own detector (`Deskew.detectQuad`) only for gallery-photo auto-crop. Scanner X parked pending a feasibility gate | PROGRAM 11-18 15:1x; BENCH_detectQuad | Own engine now | next/docs/PLAN.md Scanner X | ACCEPTED; feasibility gate 11-19 (SCN-001) | Gate result |

## UX
| ID | Job / problem | Current rule | Evidence | Rejected | Supersedes | Status | Reopen if |
|---|---|---|---|---|---|---|---|
| UX-001 | People cannot find their files | Home documents-first (search + folders + recent); tools under Tools in 7 jobs; centre Scan opens Create | UI_PLAN §0.1/§0b; UI_CRITIQUE; Nov 13 critique | Home quick-tools wall | 70_APP_DESIGN Home | ACCEPTED (design); partly IMPLEMENTED (uihome rows); Tools grouping NOT built | Navigation test fails |
| UX-002 | Tool runs pile up copies | Ask Replace/Keep both; preselect Replace only for 1-in/1-out with tested Trash rollback (incl. process death); otherwise Keep both | UI_PLAN §0.3; JUDGE_RESEARCH; JUDGE_WHY §5A | Universal Keep both; universal Replace | UI_PLAN §3.4, UX_PRINCIPLES | ACCEPTED, NOT implemented | Rollback tests fail |
| UX-003 | Result honesty before real files | Phase 3 keeps "Save to Documents"; no "already a file" wording until phase 4 | UI_PLAN §0.4 | "No Save; already a real file" (§3) | UI_PLAN §3.4 | ACCEPTED | Phase 4 ships |
| UX-004 | v4 concept approval vs judge REVISE | Lead ruling 2026-11-19: v4 is approved as VISUAL direction WITH a binding condition: every Result variant, explicitly including the target-miss result, shows "Also save in Documents" until phase 4 (this is what JUDGE_concepts_v3 R2 asked). Not yet rendered: 200% font, scrolling, every tool page, recovery states = implementation gates per §0b PHASE ACCEPTANCE | JUDGE_concepts_v3 R2 (REVISE); UI_PLAN §0b; §0.4 | Calling v4 "fully approved" | UI_PLAN §0b wording "Approved" | ACCEPTED-WITH-CONDITION (concept); NOT implemented | Phase 3 result lane |
| UX-005 | Motion | No 250 ms cap; short feedback; one whirl after safe capture; all off under Remove animations | UI_PLAN §0.2; IDENTITY | <250 ms universal cap, counters | UI_PLAN §2 | ACCEPTED | Device jank measured |
| UX-006 | Choice count | No numeric "max 5 choices" rule; group by job, max two levels | JUDGE_WHY | PSYCHOLOGY ≤5 cap | PSYCHOLOGY.md | ACCEPTED | Task test |

## Pro, price, marketing
| ID | Job / problem | Current rule | Evidence | Rejected | Supersedes | Status | Reopen if |
|---|---|---|---|---|---|---|---|
| PRO-001 | Free floor | Free forever: scan/import, OCR + search, merge, password, clean export, single-document target compression, basic folders, Vault, ID card | PRO_PLAN; PRO_INVENTORY | Moving any free feature behind Pro | Nov 10 pack | ACCEPTED; folder creation still Pro in code | Owner decision only |
| PRO-002 | Entitlement | Pro only on PURCHASED; failed query never revokes; successful empty query does; ack once with retry | PRO_PLAN entitlement ruling | Grant on PENDING/ALREADY_OWNED | none | IMPLEMENTED (fixbilling); license-tester buy worked 11-18 | Billing bug |
| PRC-001 | Price | DECIDED 2026-11-19 (owner delegated to lead; owner preference ₹499 regular / ₹299 at launch; ₹100 rejected as signalling low quality). Production launch Play price ₹299 one-time; ₹499 is the planned regular price once there is purchase + refund data (Play price experiment). In-app and listing copy say only "{Play price} once. No subscription." (no "launch price", crossed-out price or countdown; PRO_PLAN §5). Discounts only as real Play sales. ₹100 tester product stays until production. The owner changes Console prices by hand; agents never do | PRO_PLAN; JUDGE_RESEARCH; owner 11-19 | ₹100 (cheap signal), ₹1,499, fake anchors | PRO_PLAN §5 "control ₹499" at launch | ACCEPTED, NOT in Console | Refunds/conversion data after launch |
| MKT-001 | Credible claims | Never: accepted by portals, guaranteed KB, perfect OCR, "nothing leaked", "files never leave your phone", Trash = gone, unbuilt features | IDENTITY never-claim list | WHY_SCANWHIRL claims | WHY_SCANWHIRL | ACCEPTED | none |
| MKT-002 | Play listing truth | PLAY_LISTING.md conflicts with MKT-001 ("nothing you scan is uploaded", "Your files never leave your phone") and lists features to re-verify (one long image, business card scan). Corrected DRAFT to be prepared; Console untouched until owner publishes | PLAY_LISTING.md lines 43,56,66,70,84 | Absolute privacy wording | PLAY_LISTING.md | PROPOSED (draft pending) | Owner review |

## Policy drift that needs the OWNER (do not change code until decided)
| ID | Question | Facts | Status |
|---|---|---|---|
| POL-001 | Long Image: removed (Nov 10 owner decision) or kept (Nov 13 plan, current app and listing)? | DECIDED 2026-11-19 by lead (owner delegated): KEEP what ships; removing a working tester feature has no evidence of benefit. It is advertised only after it is verified in code + on device. Reopen if it causes bugs/support cost or a task test finds it confusing | ACCEPTED (no code change) |
| POL-002 | Vault: mandatory Android device lock (Nov 10) or the current passcode Vault? | DECIDED 2026-11-19 by lead (owner delegated): KEEP the current passcode Vault; no forced device lock (would lock out users without a screen lock and needs a risky migration). Finish the fixvault2 security follow-ups (secure sheets, rotation bypass, exception logging) first; never market Vault as unbreakable. Reopen after an independent security review | ACCEPTED (no behaviour change); fixvault2 OPEN |
| POL-003 | Folders free vs Pro tags | PRO_PLAN: folder creation becomes free, tags stay Pro; code still gates folder creation | ACCEPTED in plan; NOT implemented |

## Engineering
| ID | Problem | Current rule | Evidence | Status |
|---|---|---|---|---|
| ENG-001 | CI could report PASS after a failed Gradle step | `tools/ci/gate_verdict.py`: PASS needs real step outcomes, complete XML, lint report where lint is required, full-suite floor; gate.ps1 requires run conclusion success | audit P0-01; 19 control cases + mutation check | IMPLEMENTED (lane/cifail e942646); CI proof pending |
| ENG-002 | Public ci/<lane> pushes not screened | Pre-push guard on the exact commit: deny secret paths/content, binaries outside allowlist, >2 MiB | audit P0-02 | IN PROGRESS (lane/prepush) |

## Scanner
| ID | Question | Rule | Status |
|---|---|---|---|
| SCN-001 | Scanner X vs ML Kit | Decide among KEEP ML KIT / KEEP ML KIT + INVESTIGATE / PROTOTYPE IN PARALLEL on labelled evidence; never compare our gallery detector's numbers as if they were ML Kit's | OPEN (sprint 11-19 task T06) |
