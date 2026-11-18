# LANE REPORT — fixvault

## Task
Make `app/src/test/java/com/scanwhirl/app/ui/vault/VaultActivityTest.kt` deterministic in the cloud
(it passed locally, flaked on the CI runner). Test timing only: no assertion was weakened and no app
(main) source file was touched.

## Root cause (test-side, not an app race)
The passcode/recovery derivations run in `withContext(Dispatchers.Default)` inside the activity's
`lifecycleScope`; `render()`, `offerFingerprintSetup()` and the banner text are then drawn by the
resumed main-thread continuation. `VaultSession.isUnlocked()` is `@Volatile`, so the store flips to
"unlocked" on the background thread *before* that continuation is posted to the main looper. The
tests waited only for `store.isUnlocked()` and then read the view in the same tick:

- `the recovery code unlocks...` read `vaultBanner.text` before `render()` had run.
- `an invalidated keystore key ...` read `started` (set by `offerFingerprintSetup`) before the
  continuation had run.
- same latent race in `wrong passcode...` (banner/status after the retry) and
  `the locked screen says what is behind Private files` (placeholder after unlock).

The app behaviour is correct (the continuation always renders); only the tests were reading too early.
A slower cloud runner widened the window.

## Change (one file)
`app/src/test/java/com/scanwhirl/app/ui/vault/VaultActivityTest.kt`
- `await()` now drains the main looper in a bounded loop (5 s, `idle()` + 10 ms) instead of the old
  150 × 20 ms (~3 s) budget.
- Waits after each async action poll the **rendered state the following assertion reads**, not just
  the store flag:
  - recovery unlock → `store.isUnlocked() && banner.isVisible` (then the exact 2-min banner text).
  - invalidated-keystore passcode → `store.isUnlocked() && started` (then `canUseBiometric()`).
  - retry passcode → `store.isUnlocked() && banner.isVisible` (then banner/status assertions).
  - locked-placeholder unlock → `store.isUnlocked() && !placeholder.isVisible`.
- Every assertion is unchanged in strictness (all original `assertEquals`/`assertTrue`/`assertFalse`
  lines, including the exact banner strings, are still there).

No shared idling helper existed in this lane, so the helper stayed local to the test class.

## Verification
- `-Tests "com.scanwhirl.app.ui.vault.VaultActivityTest"` ×3, all green: 17 tests, 0 failures each.
- Full gate (cloud): `GATE PASS fixvault tests=1674 failures=0 skipped=0 lint=[skipped]`
  (run 44210696331).

## Commits
- `a1094f6` fixvault: VaultActivityTest waits for the main-thread render, not just the store flag
- (this report commit follows)

## Not done / notes for the lead
- Nothing merged or pushed from the lane branch; no device testing needed (test-only change).
