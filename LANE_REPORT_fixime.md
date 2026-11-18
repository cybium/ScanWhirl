# LANE_REPORT_fixime — A6 #17 (Shell drops the IME inset)

## Done
- Judge finding **A6 #17 / Medium** (CRITIC_C1.md §3 #17, JUDGE_RESEARCH #17): `ShellActivity` set a
  **second** `OnApplyWindowInsetsListener` on the shell root after `root.applySystemBarsPadding()`.
  That second listener **replaced** the shared helper's listener, so the root was padded by
  `bars.bottom` only (48 px), the IME (800 px) was dropped, base padding was reset and the cutout was
  recomputed by hand. On Home the search field and the lower library rows sat behind the keyboard.
- Fix (ONE listener, as asked):
  - `ui/Insets.kt`: `applySystemBarsPadding(top, bottom, onInsets)` gained an optional
    `onInsets: ((WindowInsetsCompat) -> Unit)? = null` callback, invoked after the padding is applied.
    The padding maths is unchanged (base rect + top = status bar ∪ cutout, bottom = max(nav bar, IME)
    on API 30+ only, IME skipped below R because `adjustResize` still resizes there) and the insets are
    still returned so children receive them. **Helper change** (the brief allowed it "only if a helper
    change is needed"): the alternative was duplicating the cutout/base-padding logic in
    `ShellActivity`. Default `null` = old behaviour for the other 11 call sites.
  - `ui/shell/ShellActivity.kt` (~183-191): the handler block is gone; the screen now passes its dock
    side effect into the one helper listener:
    `root.applySystemBarsPadding { insets -> navHost.isVisible = insets.getInsets(ime()).bottom == 0 }`.
    Padding, cutout, base padding and dock hiding are all handled by that single listener, which still
    returns the insets.
- Nothing else touched (`LibraryScreen.kt` untouched, no manifest change).

## Tests (tests first, red -> green)
New `app/src/test/java/com/scanwhirl/app/ui/shell/ShellKeyboardInsetFixImeTest.kt` (Robolectric, sdk 35):
1. `ime 800 over a 48 nav bar pads the content by the ime and hides the dock` — systemBars 64/48 +
   displayCutout 90 top + IME 800: root bottom padding **800** (not 48), top 90, dock hidden, returned
   insets still carry IME 800.
2. `without the ime the nav bar pads the content and the dock is visible` — bottom 48, cutout top 90,
   dock visible.
3. `the helper adds insets on top of the view's own padding` — original 3/5/7/9 -> 3 / 5+90 / 7 / 9+800
   (base padding kept, insets added, never stacked).

**RED** (old code): `ime 800 ... expected:<800> but was:<48>` — GATE FAIL fixime tests=3 failures=1.
**GREEN**: GATE PASS fixime tests=3 failures=0.
Full gate: **GATE PASS fixime tests=1690 failures=0 skipped=0** (cloud run 44236512521).

## `windowSoftInputMode` — does it matter?
`AndroidManifest.xml:41` ShellActivity = `stateHidden|adjustResize`.
- **API 30+ (the reported bug):** no effect. `BaseActivity` sets
  `WindowCompat.setDecorFitsSystemWindows(window, false)`, and with decor fitting off S+ forces the soft
  input to `ADJUST_NOTHING`; the IME then only arrives as a `WindowInsetsCompat.Type.ime()` inset. The
  one listener is therefore the only mechanism — this is why the bug reproduced on current phones.
- **API 24-29:** it is the mechanism — `adjustResize` resizes the window, which is exactly why the
  helper keeps its `SDK_INT >= R` guard and does **not** add IME padding below R (double padding
  otherwise). `stateHidden` also keeps the keyboard down on tab entry.
- Verdict: **no manifest change required**; changing it would break 24-29 (and `stateHidden` is still
  wanted). Left untouched.

## Commits
- `0d4c112` fixime: one root inset listener - pad max(nav bar, IME), keep cutout/base padding, dock
  still hides (A6 #17)
- (this report)

## Not done / for the lead
- Device check (lead): on Home, tap the search field — the field and list must stay above the keyboard,
  the dock must hide while it is open and come back when it closes; rotate with the keyboard up; check a
  3-button-nav phone for the nav-bar-only case (padding 48, dock visible).
- `SwSheet`/`SwDialog` inset findings (#18/#19) and `statusBarColor` (#20) are out of this lane.
