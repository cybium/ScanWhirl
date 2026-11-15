# ScanWhirl release-build (R8) hardening — 2026-11-15

Lane L2. Until now every APK on a phone was `-PtestBuild`: no R8, no resource shrinking, class
names readable. The store build minifies, and R8 breaks reflection users in ways unit tests never
show. This pass builds the real variant in CI without the owner's keystore, fixes the keep rules,
and produces an installable APK for a device smoke test.

## What changed

| File | Change |
|---|---|
| `app/build.gradle.kts` | `-PciUnsignedRelease` makes the `release` build type sign with the **debug** key (only when the property is set). Without the property nothing changes: release still uses `signingConfigs.release` (the owner's keystore). |
| `.github/workflows/gate.yml` | New `release` job (the `test` job is untouched): builds `:app:assembleRelease :app:bundleRelease -PciUnsignedRelease`, then a report + artifact upload + a `RELEASE PASS/FAIL` verdict. Fails on a failed build, on R8 errors or on any unresolved (missing) class. Also turns the AAB into the exact per-device APK set Play would serve with `bundletool get-size total` (artifacts `release-apk`, `release-aab`, `aab-size.txt`). |
| `app/proguard-rules.pro` | The two blanket keeps (`-keep class com.google.mlkit.** { *; }`, `-keep class com.google.android.gms.** { *; }`) are replaced by the rules below. |

The CI APK is signed with the Android debug key: installable for a smoke test, **never
distributable**. The application id is `com.scanwhirl.app` (the test build is
`com.scanwhirl.app.test`), so both can coexist on a phone.

## Rules, and why each one exists

Everything the libraries ship as consumer rules is left alone (`androidx.biometric`,
`play-services-basement`, `play-services-base`, `firebase-components`, `material`, `androidx.core`).
The file only adds what R8 cannot see — names reached by string:

| Rule | Why |
|---|---|
| `-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }` | ML Kit registers `com.google.mlkit.vision.text.internal.TextRegistrar` through the manifest metadata of `play-services-mlkit-text-recognition-common`; Firebase instantiates it by name. The consumer rule keeps the class name, not the constructor. |
| `-keep class com.google.android.gms.dynamite.descriptors.** { MODULE_ID, MODULE_VERSION }` | `DynamiteModule` loads the descriptor class by name and reads both fields reflectively; it selects the native module version. |
| `-keep class com.google.android.libraries.vision.visionkit.** { *; }` | The bundled Devanagari recognizer runs in-process; its native vision pipeline calls these classes from C++ by the names compiled into `libmlkit_google_ocr_pipeline.so`. |
| `-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }` | Same JNI boundary: 1013 protobuf/model classes used by the bundled Devanagari model. The AAR's own rule only keeps the `zbuf` field access. |
| `-keep class com.google.mlkit.vision.text.pipeline.** { *; }`, `…text.bundled.** { *; }` | The in-process bundled recognizer and its pipeline entry points. |
| `-keepclassmembers enum com.scanwhirl.app.** { *; }` | `QrKind.name()` is persisted in `qr_history.txt` and passed in an Intent extra; R8 renames enum constants, which would make `QrKind.valueOf(<stored name>)` throw after an app update. |
| `-keepclassmembers class com.scanwhirl.app.** extends android.view.View { public <init>(Context, AttributeSet); }` | Custom views are inflated from layouts **by class name** (`PageGridView`, `AdaptiveHeaderRow`, `CropView`, `ZoomableImageView`, `StampCanvasView`, `AspectRatioLayout`, `CappedScrollView`); the two-argument constructor is only called by the inflater. |
| `-keepattributes SourceFile,LineNumberTable` | `mapping.txt` is uploaded with every build, so this is what makes a de-obfuscated crash report readable. |
| `-dontwarn com.google.mlkit.**`, `-dontwarn com.google.android.gms.**` | Optional modules those libraries mention but this app does not ship (analytics, auth, protobuf, conscrypt). R8 full mode fails on them otherwise; nothing calls them. |

Manifest-declared components (`ShellActivity`, every screen, the ML Kit discovery service, the
document/code-scanner delegate activities, the FileProvider) are kept automatically through
`aapt_rules.txt`, so no rule repeats them.

## Results (same code, same CI job)

| | Original rules (blanket keeps) | New rules | Test build (`-PtestBuild`, no R8) |
|---|---|---|---|
| APK | 47,423,264 B (45.2 MiB) | **47,128,292 B (44.9 MiB)** | 51,505,822 B (49.1 MiB) |
| classes.dex | 4,741,472 B / 1 dex | **3,726,288 B / 1 dex** | 15,649,760 B / 2 dex |
| classes in dex | 7,160 | **5,606** | 14,635 |
| `com.google.android.gms.*` | 4,277 | **1,029** | 4,276 |
| `com.google.mlkit.*` | 276 | **50** | 276 |
| R8 warnings | 0 | **0** | — |
| R8 missing classes (`missing_rules.txt`) | 0 | **0** | — |
| R8 errors | 0 | **0** | — |

The dex shrinks by 21% against the blanket rules and by 76% against the unminified test build.
The remaining 41.0 MB of the APK is the four-ABI `libmlkit_google_ocr_pipeline.so` (11.1 MB
arm64-v8a, 6.8 MB armeabi-v7a, 11.6 MB x86, 11.6 MB x86_64) — this is a universal APK for the
smoke test; a Play AAB splits it per ABI, so a phone downloads one slice (~12 MB of native code).

CI runs: 43675804772 (original rules), 43674515074 (new rules, `release` job **success**),
43675126893 (unminified comparison). The `test` job in those runs fails only on the known L1
`TextFitTest > ocr editor text fits` layout issue.

## AAB and the size a phone actually downloads

The release job builds the App Bundle as well (`:app:bundleRelease -PciUnsignedRelease`) and turns it
into the per-device APKs with `bundletool 1.18.3` (`build-apks --mode=default`, throwaway signing
key, then `get-size total`). Run 43679221596, `release` job success:

| Artifact | Size |
|---|---|
| `app-release.aab` (upload artifact) | 24,586,131 B (23.4 MiB) |
| Universal APK (smoke test, 4 ABIs) | 47,128,332 B (44.9 MiB) |
| Download, all devices (min / max) | 7,637,370 / 8,793,675 B (7.3 / 8.4 MiB) |
| arm64-v8a | 8,529,915 to 8,601,504 B (8.1 to 8.2 MiB) |
| armeabi-v7a | 7,637,370 to 7,708,959 B (7.3 to 7.4 MiB) |
| x86 | 8,722,086 to 8,793,675 B |
| x86_64 | 8,594,308 to 8,665,897 B |

So a phone downloads about **8 MiB** although the universal APK is 45 MiB: Play splits by ABI,
density and language, and the 41 MB of native OCR pipelines collapse to the one the device needs.
The AAB is built with the debug key in CI for size validation only, the Play upload must be built
and signed with the owner's upload key (`docs/release/LAUNCH_CHECKLIST.md`).

## Verified in the artifact

- `aapt2 dump badging`: `package=com.scanwhirl.app`, no `INTERNET`, no `ACCESS_NETWORK_STATE`;
  permissions are `USE_BIOMETRIC`, `USE_FINGERPRINT` (library alias), version-capped storage.
- `apksigner verify --print-certs`: signed, `CN=Android Debug` (as intended for CI).
- One exported component (the launcher); every screen and the FileProvider are `exported=false`.
- `TextRegistrar`, `MlKitComponentDiscoveryService`, both scanner delegate activities, the two
  `ModuleDescriptor` classes, the visionkit pipeline and all 1013 bundled-common classes keep
  their names; `QrKind` is renamed as a class but its constants keep `CONTACT`, `WIFI`, … .
- Every XML-inflated custom view keeps `void <init>(Context, AttributeSet)` in `mapping.txt`.
- All 5606 classes are in a single dex (arm64 phone installs fine).

## Install

```
adb install -r C:\Users\Cybium\Desktop\ScanWhirl-L2\app\build\outputs\apk\release\app-release.apk
```

(the artifact of run 43674515074 is also downloadable as CI artifact `release-apk`).

## Not verifiable here (needs the phone)

- **English OCR** (unbundled model through Play services) and **Hindi/Devanagari OCR** (bundled
  model — the JNI keeps above are the risky part of this pass).
- **Document scanner** and **code scanner** (both start a Play-services activity and return a
  Parcelable result).
- **Vault unlock** (biometric prompt + Keystore) and **Protect PDF** (PdfAes).
- Anything that restores state after process death (Fragment/Activity re-creation by name).

A crash in any of these is a missing keep: the rule to add follows from the `ClassNotFoundException`
/ `NoSuchMethodError` in logcat and the class name in `mapping.txt`.
