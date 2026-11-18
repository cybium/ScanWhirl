# LANE REPORT — fixshare

JUDGE_RESEARCH §3 #14 (Medium): a multi-file share carried only the first Uri in ClipData, so
receivers under the Android 10+ grant model could read the first file and the others failed
silently (a batch/split result shared to WhatsApp arrived as one file).

## Done
- `ui/tools/ToolResultActivity.kt` (`shareUris` only): the intent is now built by
  `ShareUtils.multiShareIntent`; ClipData contains **every** Uri (`ClipData.newUri` for the
  first, `addItem` for the rest), `EXTRA_STREAM` keeps the same ArrayList in the same order,
  `FLAG_GRANT_READ_URI_PERMISSION` only (no write), chooser title/flow unchanged.
- `util/ShareUtils.kt`: no public multi-share helper existed (shareImages/sharePages each had a
  private inline copy of the "all URIs in ClipData" logic). Added one public
  `multiShareIntent(context, uris, type, label)` and reused it from `shareImages`,
  `sharePages` and `ToolResultActivity.shareUris`, so all three multi-share sites share one
  contract. Behaviour of the two ShareUtils paths is unchanged (same TYPE, chooser title and
  single-file path untouched).
- Single-file share (`ShareUtils.shareFile` / `share`) is untouched: plain `ACTION_SEND`, one
  `EXTRA_STREAM` Uri, one-item ClipData, read grant.

## Tests added (red first)
`app/src/test/java/com/scanwhirl/app/ui/tools/ToolResultShareFixShareTest.kt` (Robolectric, API 35)
- shares 3 image files through the real Share button, unwraps the chooser's `EXTRA_INTENT`, and
  asserts ACTION_SEND_MULTIPLE, 3 EXTRA_STREAM Uris in order, ClipData itemCount 3 with the same
  Uris in order, read flag and no write flag (target and chooser), and that each Uri resolves
  to that file's own bytes.
- single-file share is still ACTION_SEND with one Uri, ClipData itemCount 1, read grant only.
- Red on the old code: `ClipData must carry every URI, not only the first expected:<3> but was:<1>`.
  Green after the fix.

## Extension (lead-requested): the other two multi-share sites
Both now use `ShareUtils.multiShareIntent`; only the share-intent lines changed.
- `ui/tools/BatchActivity.kt` (`shareAll`, ~631): multi path fixed (ClipData had only
  `uris.first()`); the one-file path stays a plain `ACTION_SEND` with its old ClipData.
- `ui/shell/ShellActivity.kt` (`shareDocuments`, ~756): previously had **no ClipData at all**
  (so not even the chooser carried the read grant); now `multiShareIntent(this, uris, "application/pdf")`.
- Tests: `app/src/test/java/com/scanwhirl/app/ui/MultiShareSitesFixShareTest.kt` (Robolectric)
  drives both through their real UI (Batch compress run -> summary "Share all"; library
  long-press + select 2 -> `swSelShare`) and unwraps the chooser's `EXTRA_INTENT`:
  ACTION_SEND_MULTIPLE, all Uris in EXTRA_STREAM and ClipData in order, read grant only.
  Red before: "ClipData must carry every URI ... expected:<2> but was:<1>" (batch) and
  "read grant on android.intent.action.CHOOSER" (shell — no ClipData to migrate).

## Last gate
Full gate (cloud, run 44270648951): `GATE PASS fixshare tests=1720 failures=0 skipped=0 lint=[skipped]`
(earlier full run, pre-extension: run 44269012192, same verdict; `PageEditorDraftTest` flaked once
in a still earlier run and passed standalone and on rerun).

## No remaining follow-ups
The other share sites (`ExportResultActivity`, `BackupActivity`) are single-file `ACTION_SEND`
paths and were not part of this bug.
