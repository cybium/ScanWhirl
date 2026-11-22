# Release handover tools

Python 3.10+; standard library only. Neither script builds, signs, downloads, uses
adb, contacts Play, or reads signing configuration. `keytool` (JDK 17, on PATH or
JAVA_HOME) is required to obtain the public AAB certificate; unavailable means
`NEEDS keytool` and a nonzero verifier exit. Certificate extraction is not JAR
signature-integrity validation. Compare the fingerprint with Play Console's
**upload key** certificate; a CI debug signer is never release authorization.

```powershell
python -m unittest discover -s tools/release/tests -v
# Equivalent: python -m unittest tools.release.tests.test_release -v
python tools/release/check_release_manifest.py <merged-release-manifest.xml> --build-config <BuildConfig.java>
python tools/release/verify_aab.py <signed.aab> --version-code 26112001 --version-name 1.3.0
python tools/release/assemble_release.py --sha <full-frozen-SHA> --aab <signed.aab> --drafts <reviewed-drafts-dir> --out builds/2026-11-21/release-1.3.0 --gate-run <id> --e2e-run <id> --security <security-verdict.txt>
```

`check_release_manifest.py` is the P-lane CI gate. It parses the plain XML from
`:app:processReleaseManifest` (built without `-PtestBuild`) and the generated release
`BuildConfig.java`. It fails closed on `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID`
(both namespaces), `QUERY_ALL_PACKAGES`, any `FOREGROUND_SERVICE*` permission (the
family Play Asset Delivery merges), a non-release `package`/`label`, or
`TEST_BUILD=true`. When `--build-config` is omitted, the release `package`/`label`
alone prove `TEST_BUILD=false` because both come from the same `-PtestBuild` flag.
The gate.yml step also runs this module's unittest suite, so a regression in either
checker fails the lane.

Verifier checks package, expected version, minSdk 24 / targetSdk 36 (overridable
with `--min-sdk` / `--target-sdk`), permissions, backup/debug/test flags, and every
native library's PT_LOAD alignment. The forbidden-permission set (including the
`FOREGROUND_SERVICE` family) is identical to `check_release_manifest.py`. 16 KB is
required for arm64-v8a/x86_64;
armeabi-v7a/x86 values are INFO, per the lead-approved
[64-bit page-size requirement](https://developer.android.com/guide/practices/page-sizes).
Malformed ELF and unknown ABIs still fail closed. AAPT2 protobuf manifest parsing follows
[AOSP Resources.proto](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/tools/aapt2/Resources.proto).
The supported module set is base-only, failing closed on additional modules.
Malformed or missing evidence is a failure. ZIP compression and actual local
data offsets are diagnostic: AAB offsets are **not** installed APK zipalign
proof, and this is not a runtime 16 KB page-size compatibility test.

Optional independent manifest comparison: add `--bundletool <jar>` to either
command. Obtain version **1.18.2** manually from the official
https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar
(documentation: https://developer.android.com/tools/bundletool).
Keep it outside the repo, for example
`C:\Users\Cybium\Desktop\ScanWhirl-HQ\tools-cache\bundletool-all-1.18.2.jar`.
No automatic download. Only `dump manifest` is invoked, never build/install APKs.

Packaging copies `.txt` and `.md` draft evidence, preserves locale filenames and
wording, and normalizes UTF-8 without BOM / LF. The 1.2.3 folder was UTF-8 without
BOM but mixed CRLF (Console/listing) and LF (notes); LF is the consistent choice.
The output must not exist. Failed verification creates no folder; failures
during copying retain an explicitly incomplete folder for investigation.

Provenance contains supplied source SHA, versionCode, AAB hash/size, run URLs,
security verdict and the complete verifier report. Supplied SHA/run/verdict are
**not independently authenticated**: the lead must confirm both run results and
exact SHA, review draft claims at that SHA, and compare the upload fingerprint.
Debug-signed CI AABs can exercise both scripts, but are never for distribution.
Draft text is deliberately not silently rewritten into release approval.
