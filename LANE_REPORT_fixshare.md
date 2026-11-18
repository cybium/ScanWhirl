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

## Last gate
Full gate (cloud, run 44269012192): `GATE PASS fixshare tests=1718 failures=0 skipped=0 lint=[skipped]`
(`PageEditorDraftTest` flaked once in an earlier full run, passed standalone and in the rerun).

## Follow-ups for the lead (other lanes' files, NOT edited)
- `ui/tools/BatchActivity.kt:643` — batch "share all" has the identical bug: ClipData carries
  only `uris.first()`. It can now call `ShareUtils.multiShareIntent(this, uris, type, "ScanWhirl batch")`.
- `ui/shell/ShellActivity.kt:756` — sharing several selected documents builds ACTION_SEND_MULTIPLE
  with **no ClipData at all** (same grant-model risk). Same helper applies.
