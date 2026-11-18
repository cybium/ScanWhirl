# LANE_REPORT_fixflake

Task: fix the intermittent cloud failure
`IdCardCropFixIdCropTest > "Back while the crop is saving leaves the side empty and shows no error" :: the abandoned save is not accepted expected:<false> but was:<true>`
(production: `app/src/main/java/com/scanwhirl/app/ui/tools/IdCardActivity.kt` `applyCrop` / `cancelCrop` / `dismissCropScreen`, critic D2).

Status: DONE. One commit (`be93307`), last gate PASS (1822 tests, lint 0 errors).

## Root cause: test artifact (a timing assumption), not a save accepted after Back

The test pressed "Crop and save" and then dispatched Back **inside the same `onActivity` block**, i.e. on the same
main-thread turn. Its assertion then depended on which of two main-thread events runs first:

* the save's continuation — `lifecycleScope.launch` resumed from `Dispatchers.IO` onto `Dispatchers.Main`, i.e. **posted
  to the main looper**;
* the Back dispatch (`onBackPressedDispatcher.onBackPressed()` → `cropBack` → `cancelCrop`).

Nothing in the test guaranteed that Back was processed while the save was still in flight; the queue position of the
posted continuation decides it. When the continuation is dispatched first, the save *legitimately* settles before Back
(probe B below) and the old assertion fails with exactly the CI message.

Code reading + probes show there is **no production path where a Back that reaches the crop screen still accepts the
side**:

* `IdCardActivity` registers exactly one back callback (`cropBack`, `onCreate`); activity 1.10.1's
  `OnBackPressedDispatcher.onBackPressed()` is synchronous, so a Back with the crop screen open always calls `cancelCrop`.
* `cancelCrop` has no busy/overlay early return while a crop screen is open (`cropOverlay != null`), and it calls
  `dismissCropScreen()`, which nulls `cropOverlay` *before* the save's continuation can run. `applyCrop` compares the
  token captured at click time (`token !== cropOverlay`) and the activity flags, so the result is discarded and
  `cropSaveSettledForTest(false)` is reported — the D2 behaviour is unchanged.
* Probe A (25 iterations of the old ungated sequence, half of them with a 300 ms busy main thread before Back):
  `settled=false` 25/25, `onBackPressedDispatcher.hasEnabledCallbacks()=true` every time, and the decision always ran on
  the main looper (`SDK 35 Main Thread`), never before Back.
* Probe B (Back only *after* the decision settled): `settled=true`, crop screen gone, sheet = "Add the back side?",
  no document saved. That is the signature of "the save finished before Back", which is what the ungated test could hit
  on the cloud (0.203 s test, 1822 tests in one JVM); it is not a side accepted after Back.

## Fix (test-only hook, no behaviour change)

`IdCardActivity` gets `internal var cropEncodeGate: (() -> Unit)? = null` (nullable, `@Volatile`, never set in
production — same pattern as the existing `cropPrepareGate` / `cropReopenGate`). It is invoked inside the
`Dispatchers.IO` block right after the JPEG is encoded, so a test can hold the finished encode while it drives Back.

The test now:
1. installs the gate + the existing encode/settle observers,
2. clicks "Crop and save", waits for the gate (`the save really is encoding`) and asserts **nothing is decided yet**,
3. dispatches Back, asserts the crop screen closes and still **nothing is decided** (the in-flight save was not settled),
4. releases the gate, waits for the real decision and asserts `settled == false`, the front sheet, no error, the flow
   still open, the sheet usable, no document and no cached photo.

Order is now guaranteed, so the assertion can no longer lose a race in either direction.

## D2 guard proof (red -> restore -> green)

With the token check in `applyCrop` temporarily replaced by a bare `isDestroyed` check, the **gated** test failed with
exactly the CI message:

```
FAIL IdCardCropFixIdCropTest > Back while the crop is saving leaves the side empty and shows no error
     :: java.lang.AssertionError: the abandoned save is not accepted expected:<false> but was:<true>
tests: total=15 failures=1
```

The check was then restored unchanged (`token == null || token !== cropOverlay || isFinishing || isDestroyed`); the test
is therefore still a real D2 guard, not a tautology.

## Runs

* Single class, gated test, 10 consecutive `gate.ps1 -Tests` runs: **PASS 10/10**
  (`tests=15 failures=0`, 0.6-1.1 min each).
* Full suite (`gate.ps1 -Lane fixflake`): snapshot `ddfc23fa`, cloud run 44349089693 ->
  `GATE PASS fixflake tests=1822 failures=0 skipped=0 lint=[skipped]`.
* Lint: `gate.ps1 -Lint` prints `lint=[skipped]` for a lane (the cloud workflow lints only `integration` or when
  dispatched with `lint=true`; the local path would need the full suite in the only build slot, which lanes must not
  hold). To get a real lint verdict on the **same snapshot** I dispatched `gate.yml` on `ci/fixflake` (head
  `ddfc23fa`) with `lint=true`, run 44350942083:
  `GATE PASS tests=1822 failures=0 skipped=0 lint=[0 errors, 528 warnings]`.
  The lead runs lint again at merge (integration runs it).

## Files

* `app/src/main/java/com/scanwhirl/app/ui/tools/IdCardActivity.kt` — `cropEncodeGate` test hook (null in production).
* `app/src/test/java/com/scanwhirl/app/ui/tools/IdCardCropFixIdCropTest.kt` — the D2 test made deterministic.
* No strings, no new libraries, no permission, no behaviour change.

## For the lead

* Nothing to check on the device; the D2 path is unchanged. Device check optional: press Crop and save, then Back while
  the spinner shows -> the front/back sheet returns, nothing is accepted.
* If the same message ever appears again it means the token guard in `applyCrop` was removed or bypassed — the gated test
  fails inside the same second, so it will not be mistaken for a timing flake.
