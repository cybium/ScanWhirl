# LANE_REPORT — fixopen (judge C1 #15)

## Status: DONE

Bug (Medium, confirmed): `app/src/main/java/com/scanwhirl/app/util/SavedSnackbar.kt:191` called
`packageManager.queryIntentActivities(view, 0)` before opening a saved export. The manifest has no
`<queries>` element, so Android 11+ (API 30) package visibility filtering hides most installed
viewers (e.g. a Play PDF viewer) and the query returns empty -> the snackbar's **Open** showed
`fixresult_open_no_app` ("No app on this phone can open this file") although a viewer exists.

## Fix (only `util/SavedSnackbar.kt`)

- Removed the `queryIntentActivities` precheck. No `<queries>` and no QUERY_ALL_PACKAGES added.
- The `ACTION_VIEW` intent is unchanged: `setDataAndType(target, mime)`,
  `FLAG_GRANT_READ_URI_PERMISSION`, `clipData = ClipData.newUri(contentResolver, name, target)`.
- The existing `try { startActivity(view) } catch (ActivityNotFoundException) / (SecurityException)
  -> cannotOpen(activity)` is now the only check, i.e. the platform's own refusal.
- The Android 9- `file://` -> FileProvider path above it is untouched.
- KDoc of `openExported` now says why the query must never come back (package visibility;
  `startActivity` itself is not filtered).

## Tests (Robolectric, red -> green)

New `app/src/test/java/com/scanwhirl/app/util/SavedSnackbarFixOpenTest.kt` (both `@Config(sdk = [35])`,
host activity records the handed intent and can refuse with `ActivityNotFoundException`):

1. `open is attempted with no viewer and says so when the platform refuses` — asserts the shadow
   PackageManager has no resolver, that the intent is still handed over (ACTION_VIEW + the export
   URI), and that on `ActivityNotFoundException` the `fixresult_open_no_app` toast appears.
   **Verified red on the old code**: `GATE FAIL fixopen tests=2 failures=1` with
   "fixopen: Open must hand the ACTION_VIEW to the platform instead of stopping at a
   package-visibility-filtered query".
2. `open hands the viewer the exported file, its type and read permission` — ACTION_VIEW, data ==
   export URI, type == `application/pdf`, `FLAG_GRANT_READ_URI_PERMISSION`, clip-data URI, no toast.

Existing test updated (it pinned the precheck, per brief): `ExportActionsFixResultTest`
`open says so when no app can open the file` -> `open asks the platform even when no viewer
resolves` (`// fixopen: judge C1 #15` in its KDoc): it now asserts the ACTION_VIEW with the exported
row URI is handed to the platform and the save is not re-run. The toast-on-refusal half moved to the
new test, where a host can actually throw. Removed the then-unused `assertNull` import.

## Other prechecks searched in `app/src/main` (NOT changed — lead decides)

`git grep -n -E "resolveActivity|queryIntentActivities|resolveService|queryIntentServices|canResolve|getLaunchIntentForPackage" -- app/src/main`
returns **only the two new fixopen comments** in `SavedSnackbar.kt`. There is no other
`resolveActivity`/`queryIntentActivities` precheck before a `startActivity` in the app.

Observation only (no visibility filtering involved, nothing changed): `ExportResultActivity:192`
and `OcrEditorActivity:808/1051` use `Intent.createChooser` for Share; that is a share sheet, not a
"can anything handle it" gate.

## Gate lines

- Red: `GATE FAIL fixopen tests=2 failures=1 skipped=0 lint=[skipped]` (new test 1 fails on old code)
- Green (new + updated class): `GATE PASS fixopen tests=7 lint=[lint skipped (lead runs it at merge)]`
- Full: `GATE PASS fixopen tests=1689 failures=0 skipped=0 lint=[skipped]`

## Commits

- `6d0df86` fixopen: Open a saved export without the package-visibility-filtered query (judge C1 #15)
  (3 files: `util/SavedSnackbar.kt`, new `SavedSnackbarFixOpenTest.kt`, updated
  `ExportActionsFixResultTest.kt`)

No merge, no push. Device check for the lead: on Android 11+ with only a Play PDF viewer installed,
Save to Documents -> Open must launch that viewer (previously the "no app" toast).
