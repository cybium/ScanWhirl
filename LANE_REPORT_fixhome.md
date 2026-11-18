# LANE_REPORT — fixhome

## Done

Home's cyan-tinted shell background looked "cut" below the header: the Library page
painted an opaque `rv_bg` fill over it, so the document list area was plain black while
Tools, Vault and Me showed the shell gradient all the way down.

- Cause (confirmed): `app/src/main/java/com/scanwhirl/app/ui/shell/LibraryScreen.kt:153`
  `background = SwStyle.rounded(context, SwStyle.color(context, R.color.rv_bg), 0f)`
  on the LibraryScreen root (the page inside the shell's ScrollView). That fill is the
  whole page, not just the cards, so everything from the list down covered the gradient.
  `ToolsHubScreen`, `MeScreen` and `VaultScreen` set no root background — only
  `ShellActivity` paints the tinted gradient (`ShellActivity.kt:171`), unchanged.
- Fix: removed that one background assignment (comment left in place). Nothing else in
  the file changed: padding, cards, chips and the shell/window background are untouched.

## Tests added

`app/src/test/java/com/scanwhirl/app/ui/shell/HomeBackgroundFixHomeTest.kt`

1. `home leaves the shell background visible behind the library` — the Home page
   (LibraryScreen), `swDocList`, `swListHost` and the shell ScrollView must have no
   opaque background (null or fully transparent).
2. `every tab page leaves the shell background visible` — walks Home, Tools, Vault and Me
   and asserts the same, so Home cannot regress back to a filled page alone.

Red first: both failed with `expected:<0> but was:<255>` on the Home page (the other tabs
already passed). Green after the fix.

## Gate

- Red/green: `gate.ps1 -Lane fixhome -Tests "com.scanwhirl.app.ui.shell.HomeBackgroundFixHomeTest"`
  → `GATE PASS fixhome tests=2`
- Full gate (cloud): `GATE PASS fixhome tests=1687 failures=0 skipped=0 lint=[skipped]`
  (run 44230040238)

## Commits

- see `git log --oneline -3` on `lane/fixhome`

## For the lead / device check

- Verify on the phone: the cyan tint now runs evenly from the top of Home down to the nav
  bar, identical to Tools/Vault/Me; cards and chips keep their own colours.
- Only `LibraryScreen.kt` (one line), the new test file and this report were touched.
