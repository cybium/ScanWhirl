# CR security rework implementation checkpoint

- Base: `cd322fa4d535de5ed75385746270e74d301e9bcd`, branch `lane/cr`.
- Rule: Replace ships OFF. No merge, local Gradle/WSL, ADB, browser, signing/private files.
- Scope: atomic identity/authorization/move, producer-issued authorization, recoverable Undo boundaries, fail-closed Trash markers and expiry cleanup.
- RED: cloud run `44997947062`, snapshot `c896019d`; 12 tests, 3 failures. Reproduced output-at-move race, incomplete Undo recovery and protected output never expiring.
- Existing safety/UI tests keep all their assertions. The removed four-argument bypass is replaced by explicit test-fixture permits; the positive consent fixture now uses an actual compress-produced permit (opening a screen no longer authorizes arbitrary outputs).
- Implementation: verified publication captures permits under PDF -> OCR -> Trash locks; result reopening only loads a permit. Undo records its collision-safe destination before restore and reconciles repository evidence before completion. Both result Undo entry points use the swap, not ordinary permanent removal.
- Infrastructure-only failed attempt: snapshot `44fcaef35e2e207d72e3b638ee8ab38f82baabfc`; gate reported no GitHub run started. No tests or lint evidence from that attempt.
- Full run `44999417300`, snapshot `86e07af5`: 3,653 tests, three failures; lint 0 errors / 717 warnings. Failures were fixture adaptations: a competing fixture had already moved its source, the old UI fixture expected opening to mint authority, and repeated fictional occupant names are uniquified. Fixtures corrected without removing/relaxing old assertions; the new collision test compares the actual allocated occupant name.
- Current verification: final full cloud gate pending after those fixture corrections. Not yet verified green.
- Phone verification: Not verified. Keep the release flag OFF.
- Next safe action: collect the running gate, then gate the final tree and commit only on a full GATE PASS.

This is an implementation checkpoint, not an independent security GO.
