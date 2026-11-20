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

| ABI | Every PT_LOAD p_align | ZIP data offset | Offset mod 16384 | Approved ABI-aware result |
| --- | --- | --- | --- | --- |
| arm64-v8a | 16384, 16384, 16384 | 6759892 | 9684 | PASS |
| armeabi-v7a | 4096, 4096, 4096 | 11165643 | 8139 | INFO |
| x86 | 4096, 4096, 4096 | 14678833 | 15153 | INFO |
| x86_64 | 16384, 16384, 16384 | 19276849 | 9265 | PASS |

The initial all-ABI rule produced `VERIFY FAIL` (exit 1). The lead subsequently
approved the corrected 64-bit-only 16 KB criterion, verified against official
documentation: https://developer.android.com/guide/practices/page-sizes .
That page requires 16 KB support on 64-bit devices and explicitly identifies
arm64-v8a/x86_64 alignment failures as requiring updates. The verifier now requires
16 KB on those ABIs and reports armeabi-v7a/x86 alignment as INFO. Malformed ELF,
invalid non-power-of-two alignment, unknown ABIs and all other checks remain
fail-closed. Output includes the documentation URL.

Rerun on the **same unchanged AAB**: `VERIFY PASS`, exit 0.
AAB ZIP offsets remain diagnostic only, not installed APK/runtime proof.

`assemble_release.py` initially refused the unchanged artifact with no output
folder, as required by the old criterion. After the lead's policy correction it
was rerun against the same real drafts with the explicitly test-only security
verdict: **exit 0**, 14 files created in the TEMP output folder below.
Post-copy checks PASS: all 13 text files are UTF-8 without BOM / LF; the copied AAB
SHA-256 is identical to the source. No patched AAB or bypass was used.
The placeholder E2E ID equals the gate ID in this **test-only** handover and is
not E2E proof. Security is explicitly Not verified; debug AAB never for release.
Python unittest: **15 tests PASS**, including 4096-byte 64-bit FAIL and 4096-byte
32-bit INFO/PASS for both ABIs. Bundletool comparison was not requested.

Local evidence directory:
`C:\Users\Cybium\AppData\Local\Temp\opencode\r1tooling-ci-44805101065`.
Current full verifier output is `verify-report-64bit-policy.txt` (the original
failure is retained as `verify-report.txt`); successful test output is
`release-1.3.0-test/`; input lives under
`app/build/outputs/bundle/release/app-release.aab`.

Lead next action: rerun on the final owner-signed AAB and compare upload signer;
confirm exact-SHA gate/E2E/security approval and review final draft claims.
No app dependencies or signing configuration were changed.
