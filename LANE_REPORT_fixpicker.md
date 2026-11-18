# LANE_REPORT_fixpicker

## Task
C1 #10 (judge: Low) — the Android photo picker backport for API 19-29 needs the Google Play services
`ModuleDependencies` service in the manifest; without it `PickVisualMedia` (only user today:
`ui/tools/IdCardActivity.kt`, not touched — another lane owns it) falls back to `ACTION_OPEN_DOCUMENT`
on those devices.

## Done
- `app/src/main/AndroidManifest.xml`: added the entry **exactly** as documented in
  https://developer.android.com/training/data-storage/shared/photopicker ("Device availability" /
  backported photo picker section, snippet verified 2026-11-18):
  `<service android:name="com.google.android.gms.metadata.ModuleDependencies" android:enabled="false"
  android:exported="false" tools:ignore="MissingClass">` with the
  `com.google.android.gms.metadata.MODULE_DEPENDENCIES` intent-filter action and the
  `<meta-data android:name="photopicker_activity:0:required" android:value="" />` (empty value, as in
  the docs). Placed inside `<application>`, next to the ML Kit `DEPENDENCIES` meta-data, with a comment
  pointing at the doc and at the no-permission rule.
- No other file changed; no permission added anywhere. `IdCardActivity.kt` untouched.
- **INTERNET still absent from the merged manifest**: confirmed by the merged-manifest test (below)
  and by `ManifestPrivacyTest` (unchanged, in the full gate). The source keeps only
  `<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />`; ACCESS_NETWORK_STATE
  likewise still removed.

## Tests added (red -> green, TDD)
`app/src/test/java/com/scanwhirl/app/util/FixPickerManifestTest.kt` (3 tests, Robolectric sdk 35):
1. merged manifest: the `ModuleDependencies` service is present, `enabled=false`, `exported=false` and
   carries the `photopicker_activity:0:required` meta-data. Note: `PackageManager.MATCH_DISABLED_COMPONENTS`
   is required — the service is intentionally disabled, so PackageManager hides it otherwise (this cost
   one red cycle).
2. source manifest (`File("src/main/AndroidManifest.xml")`, same pattern as `HindiStringsHygieneTest` /
   `EmojiGuardTest`, with `app/src/main/...` fallback): parses the XML and asserts the exact documented
   shape — name, enabled/exported false, `tools:ignore="MissingClass"`, the MODULE_DEPENDENCIES action,
   the meta-data name and its empty `android:value`.
3. no permission regression: merged `requestedPermissions` contains neither INTERNET nor
   ACCESS_NETWORK_STATE, and every source INTERNET declaration is a `tools:node="remove"`.

Red evidence (old manifest): `GATE FAIL fixpicker tests=3 failures=2` (run 44235669424) — both service
tests failed, the INTERNET test already passed. Then one intermediate red (run 44236076529) for the
disabled-component flag. Green: run 44236444113.

## Last gate
`GATE PASS fixpicker tests=1690 failures=0 skipped=0 lint=[skipped]` (run 44236787263, cloud, 8 min).

## Commit
- `0e350f5` fixpicker: ModuleDependencies entry so Play services installs the backported photo picker (C1 #10)

## For the lead / device check
- Verify on an API 28/29 device (if one is available): Tools > ID card > pick photo should open the
  system photo picker instead of the document picker after Play services installs the module (may need
  the app installed once and a Play services update/refresh; not verifiable on API 30+).
- Lint was skipped in this gate (cloud lint runs on integration merges); the entry carries
  `tools:ignore="MissingClass"` as documented, so a lint run should be clean for the missing GMS class.
