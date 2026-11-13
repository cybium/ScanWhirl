# 50 — Open questions: owner answers, investigations, DeepSeek pack

The owner answered on 2026-11-13. **Every question the owner did not answer takes its default.** Nothing below is still open for the owner unless marked **OPEN**.

---

## Owner decisions (resolved)

| ID | Question | Answer | Effect on the plan |
|---|---|---|---|
| OQ-1 | Approve the Play Billing Library for the Power Pack storefront? | **Approved by owner.** | New card **PM-5.5 Storefront**, after Slice 5, so there is something to buy. `FOR_SALE` becomes reachable only when PM-5.5 ships. Until then, production stays `HIDDEN`. Use only `com.android.billingclient:billing-ktx`. Verify the merged manifest (DS-2). |
| OQ-2 | Price | **₹100, one-time** (owner). Reason: brand growth over revenue, and keep mods/sideloading unattractive. | `30_FREE_PRO.md` §3. Single tier, no "regular price", no subscription. Do **not** add anti-tamper or Play Integrity: it is network-based and against the offline promise. Accept that mods exist. |
| OQ-3 | Bring back Long image? | **Yes** (owner), with the old problems fixed. | E20 rewritten. New card **PM-4.4 Long image** (Free, a delivery option of Export as images). `LongImageRemovalTest` is rewritten in that card, not before. |
| OQ-4 | Remove `INTERNET` and `ACCESS_NETWORK_STATE`? | **Yes, after reading how ML Kit works** (owner). Read: the unbundled models (`play-services-mlkit-*`) and the document scanner are downloaded and run by **Google Play services in its own process**; the app needs no network permission for that ([ML Kit text recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [document scanner](https://developers.google.com/ml-kit/vision/doc-scanner/android)). ML Kit offers a manifest declaration that asks Play to download the OCR model **at install time**. | New card **PM-1.5** removes both permissions, plus any library-merged copy (`tools:node="remove"`), and adds the install-time OCR model declaration. Gated on DS-2 (merged manifest) and a device check. About and honesty copy become: "ScanWhirl has no internet access. Google Play services installs the scanner and text-recognition models." |
| OQ-5 | Protect / Unlock in place (P11) | Default: **accepted.** | — |
| OQ-6 | Keep `original.pdf` on import | Default: **keep.** | — |
| OQ-7 | Hindi (Devanagari) text recognition | Left to the architect. **Decision: yes, as a Free opt-in, only after an on-device accuracy spike.** | New card **PM-6.3** (below). |
| OQ-8 | Family Library sharing | Default: **yes.** | PM-5.5 |
| OQ-9 | Keep "previous version" Trash entries indefinitely | Default: **yes.** | — |
| OQ-10 | Generous Free tier | Default: **yes.** | — |
| OQ-12 | Landing after a scan saves | Default: **the Reader.** | PM-0.1b classifies `DirectScanSaveTest`. PM-2.2 wires the landing to the Reader. |
| OQ-13 | Android version focus | **Android 10+ first** (owner). | `minSdk` stays 24, because the build file is not touched. Device acceptance and performance targets are judged on API 29–36; API 24–28 is "must not crash, may degrade". A feature may require API 29+ if it hides cleanly below that. The owner's test phone is API 34. |

### Why Hindi OCR is a "yes, behind a spike" (OQ-7)

- **Audience.** Indian ID cards, government forms, bank letters and school documents mix Hindi and English. Today their Hindi lines are invisible to search.
- **Cost.**
  - The unbundled Devanagari model adds about 260 KB to the APK, and Play services downloads it.
  - It is a new Google Play services dependency of the same family as the existing OCR, approved by the owner's delegation.
  - It needs no server.
- **Risks.**
  - Accuracy on low-quality phone scans is unknown.
  - Running two recognizers doubles indexing time.
  - The PDF text layer is Latin-1 only (`PdfTextLayer.kt:40–80`). So Hindi becomes **searchable inside ScanWhirl and copyable as text, but not searchable inside the exported PDF.** The UI must say so.
- **Gate:**
  - ≥ 90% character accuracy on printed Hindi samples;
  - ≤ 2× indexing time;
  - no regression on English pages.
  - If it fails, nothing ships.

---

## Code and device investigations

| ID | Question | Who | Default if unanswered |
|---|---|---|---|
| INV-1 | Do the 169 Windows test failures pass on Linux? (111 are file-replace or FileProvider-path errors) | DeepSeek, DS-1 | Treat Linux as the gate |
| INV-2 | Does a page edit on a locked document drop `/Encrypt`? (code says yes: `ScanRepository.kt:1651–1665`) | Sonnet, PM-0.2 test a | Guard anyway (PM-0.3) |
| INV-3 | Are merged, extracted and compressed documents never queued for OCR? (`ScanRepository.kt:1321`) | Sonnet, PM-0.2 test d | Fixed by PM-2.1 |
| INV-4 | Which libraries put `INTERNET`, `ACCESS_NETWORK_STATE` or telemetry services into the merged manifest? | DeepSeek, DS-2 | Keep permissions until known |
| INV-5 | Memory and time on a 300-page document (API 34 device) | Sonnet, PM-2.5 / PM-3.2 | 500-page cap |
| INV-6 | Orphaned `files/edit_snapshot_*` directories on the device | DeepSeek, DS-3 | Ship the PM-2.2 notice |
| INV-7 | `PdfVerifier` on encrypted output | Sonnet, PM-3.1 | Add a structure-only mode |
| INV-8 | Is a ScanWhirl searchable PDF searchable in Drive's viewer and Adobe Reader? | Sonnet, PM-3.3 device check | Claim only what is verified |
| INV-9 | Legacy documents with `locked.meta` but a plain `scan.pdf` on the device | DeepSeek, DS-3 | "Protection needs repair" state |
| INV-10 | Does Android 14's photo picker keep selection order? | Sonnet, PM-2.8 | Inserted pages stay selected, so they can be dragged |
| INV-11 | Can the device open foreign password PDFs with `PdfRenderer` `LoadParams` (API 35) or `PdfRendererPreV` (S-extension ≥ 13)? | DeepSeek, DS-3 (extension level only) | Not v1 |
| INV-12 | Row size source (`ScanListAdapter.kt:114`) | Sonnet, PM-2.7 | Share-file rule |
| INV-13 | Old Long image failure modes, reconstructed from `git show c230017^:app/src/main/java/com/scanwhirl/app/util/LongImage.kt` | Architect, done | See E20 |

---

## DeepSeek investigation pack (run **before** Sonnet starts)

**Why DeepSeek goes first.**
- These jobs are long, repetitive and read-only: an 8–10 minute test run, manifest diffs, adb listings.
- Their answers change what Sonnet does in PM-0.1b, PM-1.5 and PM-2.2.
- None of them edits product code.
- Sonnet then starts at **PM-0.1b** with a trustworthy baseline.

**Output.** One file, `docs/audit/2026-11-13/PDF_TOOLS_STATUS.md`. DeepSeek must not change any other file. Paste the prompt below to DeepSeek 4.1 High as-is.

```text
ROLE: You are a careful Android investigator. You only observe, run read-only commands, and write ONE report.
WORKSPACE: C:\Users\Cybium\Desktop\ScanWhirl (Windows 11, PowerShell 5.1, JDK 17, Android SDK at
C:\Users\Cybium\AppData\Local\Android\Sdk). adb = C:\Users\Cybium\AppData\Local\Android\Sdk\platform-tools\adb.exe
(not on PATH). One phone is connected: Infinix X6711, Android 14 / API 34, serial 103223137I112880, test app
package com.scanwhirl.app.test. The phone is a freshly reset test device.

HARD RULES
- Do NOT edit, create, delete or format any file except docs/audit/2026-11-13/PDF_TOOLS_STATUS.md.
- Do NOT git commit/push/checkout/stash. Do NOT change build.gradle.kts, manifests, versions, signing, keystore.
- Never read or print keystore/ or keystore.properties.
- Do NOT uninstall or clear data of any app on the phone. Do NOT tap through the app UI.
- Run Gradle one task at a time in the foreground, always with -PtestBuild --no-daemon. The machine is memory-sensitive.
- When writing the report from PowerShell, write UTF-8 WITHOUT BOM:
  [IO.File]::WriteAllText(path, text, (New-Object System.Text.UTF8Encoding($false))).
  Never use Set-Content or Out-File on existing Markdown: PowerShell 5.1 corrupts non-ASCII.
- If something fails, record the exact command, exit code and the first 30 lines of output, then continue.

READ FIRST (only these): docs/plans/pdf-manager/20_ARCHITECTURE_PLAN.md section 1.7, and
docs/plans/pdf-manager/50_OPEN_QUESTIONS.md (this file).

DS-1  TEST BASELINE (INV-1)
 a) Windows run: .\gradlew.bat :app:testDebugUnitTest -PtestBuild --no-daemon --continue
    Parse app/build/test-results/testDebugUnitTest/*.xml. Record totals: tests, failures, skipped.
 b) Linux run: check `wsl -l -v`. If a distro exists, check inside WSL for JDK 17 (`java -version`) and an
    Android SDK (`echo $ANDROID_HOME`). If both exist, run the same Gradle task from
    /mnt/c/Users/Cybium/Desktop/ScanWhirl using ./gradlew. If either is missing, DO NOT install anything.
    Write "Linux run not possible: <reason>" and give the exact install steps the owner would need.
 c) For EVERY failing test, a table row: class, test name, first line of the failure message, and a bucket:
      A = host file-replace / FileProvider-path error ("Could not replace", "Could not publish",
          "Failed to find configured root");
      B = downstream of A (timeouts or leftover temp files in a test that also does file replaces;
          justify in one phrase);
      C = stale expectation after the committed UX pass 065294b (Tools sheet tiles, nudge, status line,
          import copy, "Search ready" label). Cite the assertion line and the production line that changed;
      D = behaviour question (DirectScanSaveTest);
      R = looks like a real product regression (explain).
    If the Linux run happened, add a column "passes on Linux?".
 d) Totals per bucket.

DS-2  MERGED MANIFEST AND NETWORK (INV-4, OQ-1, OQ-4)
 a) .\gradlew.bat :app:processDebugMainManifest -PtestBuild --no-daemon
    Find the merged manifest under app/build/intermediates (search for AndroidManifest.xml under
    merged_manifest*). List every <uses-permission>, <service>, <provider>, <receiver>, and <meta-data>
    whose name contains mlkit, datatransport, firebase, gms, vending or billing.
 b) For each permission, name the library that contributed it. Use
    app/build/outputs/logs/manifest-merger-debug-report.txt if present; otherwise search the extracted
    AARs in the Gradle cache.
 c) Without changing any build file, report what Play Billing Library would add to the manifest. Do this
    by reading the AndroidManifest.xml inside a cached com.android.billingclient:billing AAR if present
    in %USERPROFILE%\.gradle\caches. If not cached, write "not cached". Do NOT add the dependency.
 d) In the app's Kotlin sources, list every use of java.net, HttpURLConnection, OkHttp, URL(, Socket,
    WebView or ConnectivityManager. Give file:line, or write "none".

DS-3  DEVICE FACTS (INV-6, INV-9, INV-11; Android 10+ priority)
 Read-only adb only:
 a) getprop ro.build.version.sdk, ro.build.version.release, ro.product.model;
    `dumpsys package com.google.android.gms | findstr versionName` (first line only);
    `dumpsys package com.scanwhirl.app.test | findstr versionName`.
 b) SDK extension level: run `adb shell getprop` and keep the lines containing "extension" (record them raw),
    and `adb shell ls /apex` and keep the lines containing "extservices" or "sdkext". Record "unknown" if not readable.
 c) `run-as com.scanwhirl.app.test ls -la files` and `run-as com.scanwhirl.app.test ls files/documents`.
    For each document directory: list its file names and sizes. Flag:
      - any edit_snapshot_* directory under files/ (INV-6);
      - any document with locked.meta: run
        `run-as com.scanwhirl.app.test sh -c "grep -c /Encrypt files/documents/<id>/scan.pdf"`.
        0 means plain = needs repair (INV-9);
      - any document whose page_*.jpg count differs from the "/Count N" value in scan.pdf
        (grep -a -o "/Count [0-9]*" on scan.pdf). For locked PDFs the page tree is not encrypted, so check
        them too and mark the row "locked".
 d) If run-as fails (release-signed test build is not debuggable), say so and stop DS-3c.
    Do not try to work around it.

DS-4  REPORT
 Write docs/audit/2026-11-13/PDF_TOOLS_STATUS.md with these sections:
   1. Summary (max 10 lines: totals, bucket counts, Linux possible?, network-permission sources, device facts);
   2. DS-1 table; 3. DS-2 findings; 4. DS-3 findings;
   5. "Changes the plan should make": max 8 bullets, each citing a PM-x.y card in
      docs/plans/pdf-manager/40_SONNET_TASKS.md;
   6. Commands run, with exit codes.
 End your chat reply with the summary section only.
```

**After DeepSeek finishes, hand Sonnet the README start prompt.** Sonnet begins at PM-0.1b.
