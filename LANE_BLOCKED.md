# LANE_BLOCKED — L5 wave 2 (Organize draft: PM-2.2b, PM-2.2c, PM-2.3b)

Date: 2026-11-14. Branch `lane/L5-stamp`, worktree `C:\Users\Cybium\Desktop\ScanWhirl-L5`.
Tree: `git merge integration` fast-forwarded to `cf67021` (integration). Working tree clean before this file.

## The blocker

All three assigned cards are written against **PM-2.1b's publish primitive**, which is **not in `integration`**.
They explicitly "Need PM-2.1b" (PM-2.2b header) and their implementation edits `buildAndPublish`, `OpStaging`'s seal,
`recoverOperations`, `moveToTrash`/`TrashedDocument`, and `OcrStore`. None of those exist here.

Evidence (all run in this worktree after the merge):

```
$ git log --oneline -1
cf67021 docs: orchestration log - L5 merged, wave 2 assigned

$ Get-ChildItem -Recurse -Filter *.kt app\src\main\java | Select-String -Pattern "buildAndPublish"
(no output)

$ Select-String -Path app\src\main\java\com\scanwhirl\app\store\OpStaging.kt -Pattern "Seal|writeSeal|readSeal|SEAL"
(no output; OpStaging has only DIR/create/release/forget/sweep/resetForTests)

$ Select-String -Path ...\ScanRepository.kt -Pattern "fun recoverOperations" -Context 0,2
1688:    fun recoverOperations(): Int = OpStaging.sweep(context.filesDir)

$ Get-ChildItem -Recurse -Filter *.kt app\src\main\java | Select-String -Pattern "trashUnderLocks|previousToken|fun putAll"
(no output)

$ Get-ChildItem -Recurse -Filter *Test.kt app\src\test | where Name -match "OpPublish|Draft|Organize|PageEditor"
(no output)
```

`git merge-base --is-ancestor e449125 integration` → false. The primitive exists on **`lane/L4-tools`**:

```
e449125 L4: PM-2.1b publish primitive (buildAndPublish, staging merge, op strings)
  store/ScanRepository.kt | 163 ++++---
  store/OpPublishTest.kt  | 208 ++++
  values/strings_tools.xml (+ hi) | 12 +
```

The same is true of L4's later commits (`b0273a3`, `8acc941`): none is in `integration`
(`git merge-base --is-ancestor <c> integration` is false for each). So the tools engine and PM-2.1b are still
only on `lane/L4-tools`, and the 62 index §4 explicitly says **6 must not start before 4**:

> Strictly serial (shared publish primitive or shared functions): 1 → 2 → 4 → 6 → 7 → 8.
> **Do not start 4 before 2 … Do not start 7 before 6, or 8 before 7.**

## What I did

- Read `71_LANES.md`, `72_WORKER_BRIEF.md`, `61_ENGINE_DECISIONS.md` (§2 A1/A2), `62_ENGINE_CARD_INDEX.md` (§1–§4),
  and the three cards PM-2.2b, PM-2.2c, PM-2.3b in full.
- Checked the named production code (`ScanRepository`, `OpStaging`, `OcrStore`, `Durable`, `PageReplace`), the
  current Trash model, and the existing tests.
- Looked for the prerequisite under other names and on every local branch.

## Why I did not improvise

- Writing a second publish primitive inside L5 would duplicate L4's `buildAndPublish`, contradict card §5.3/§5.4
  (seal, roll-forward, lock order, `trashUnderLocks`), and collide with PM-2.1b when it merges.
- The cards' own rule: "If anything in the card contradicts the code, stop and report the contradiction instead
  of improvising." The brief also forbids editing another lane's files/branches; `store/OpStaging.kt` and the
  publish functions are L3/L4 territory.
- The requested invariants (I6, I7, I8, I9) cannot be met without the seal + roll-forward protocol.

## What unblocks it

1. Merge `lane/L4-tools` into `integration` (at least `e449125` PM-2.1b; `b0273a3` + `8acc941` bring the engine
   tools and may be needed for the whole gate), run the gate, then restart this session.
2. Then the assigned order is exactly 6 → 7 → 8 with no code-substitution decision needed.

If the lead prefers an L5-local publish primitive instead, say so explicitly (not recommended: it duplicates
PM-2.1b and will conflict).

## State

- No production or test source was edited in this wave-2 attempt.
- Wave 1 (Edit & sign) is merged; `LANE_REPORT.md` keeps its results.
- Tree clean apart from this file and the report note.
