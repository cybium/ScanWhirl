# R1 tooling real-artifact exercise

Input source: `42bafb0f237f671370ccf0b65ff4c8d4a4483897`.
Gate run: https://github.com/cybium/ScanWhirl/actions/runs/44805101065
Artifact: `release-aab`, downloaded using `gh run download` outside the repository.
The run subsequently concluded **cancelled**: release assemble/report/artifact/
verdict steps succeeded, but the test job was cancelled and lint skipped.
This is an artifact inspection, **not** an integration gate PASS.

File: `app-release.aab`, 25,415,367 bytes.
SHA-256: `841a83099e5a8ee6ee6a8d91718a0e2283155d79d9e5d5a576df4886f6856186`.

Manifest: `com.scanwhirl.app`, versionCode `26112001`, versionName `1.3.0`,
minSdk `24`, targetSdk `36`, allowBackup `false`,
dataExtractionRules `@xml/data_extraction_rules`; debuggable/testOnly absent.
Permissions:

- `android.permission.USE_BIOMETRIC`
- `android.permission.USE_FINGERPRINT`
- `android.permission.WRITE_EXTERNAL_STORAGE`
- `com.android.vending.BILLING`
- `com.scanwhirl.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

No INTERNET or ACCESS_NETWORK_STATE. Manifest checks pass.

Signer: `C=US, O=Android, CN=Android Debug`, as expected for CI.
Certificate SHA-256:
`7C:C2:F6:3E:E2:F5:66:BA:A8:0D:D7:C2:3C:FC:65:3B:28:28:77:43:89:4A:01:B9:03:F9:FC:AB:0C:CB:79:CD`.
Never distribute this CI artifact.

Every library is `libmlkit_google_ocr_pipeline.so`, ZIP deflate method 8:

| ABI | Every PT_LOAD p_align | ZIP data offset | Offset mod 16384 | Strict result |
| --- | --- | --- | --- | --- |
| arm64-v8a | 16384, 16384, 16384 | 6759892 | 9684 | PASS |
| armeabi-v7a | 4096, 4096, 4096 | 11165643 | 8139 | FAIL |
| x86 | 4096, 4096, 4096 | 14678833 | 15153 | FAIL |
| x86_64 | 16384, 16384, 16384 | 19276849 | 9265 | PASS |

The requested rule says **every** `.so` must have p_align >= 16384. The verifier
therefore correctly returns `VERIFY FAIL`, exit 1. Do not confuse this strict
all-ABI criterion with a claim that Play rejects the bundle: no Play/runtime
compatibility judgment was made, and AAB ZIP offsets are diagnostic only.

`assemble_release.py` was also run against this unchanged CI artifact and real
draft folder with a clearly labelled test-only security verdict and temporary
output outside the repo. It returned exit 1, `AAB verification refused packaging`,
and created **no output folder**, as required. Successful packaging of this real
input is blocked by the two 32-bit libraries; no bypass or patched AAB was used.
The success/normalization/no-overwrite path is covered using synthetic inputs in
Python unittest. Bundletool comparison was not requested for this exercise.

Local evidence directory:
`C:\Users\Cybium\AppData\Local\Temp\opencode\r1tooling-ci-44805101065`.
Full verifier output is `verify-report.txt`; input lives under
`app/build/outputs/bundle/release/app-release.aab`.

Lead next decision: explicitly resolve the all-ABI alignment requirement before
packaging; tools deliberately do not invent a 32-bit exemption or change app
dependencies. Then rerun on the final owner-signed AAB and compare upload signer.
