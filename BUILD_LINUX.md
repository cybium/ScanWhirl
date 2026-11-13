# Linux (WSL) build notes — canonical unit-test runner

The unit suite is **gated on Linux**. Windows is useful for compiling and for lint, but 142 of the
807 unit tests fail there for a host reason, not a product reason (see below).

Measured on 2026-11-13 in this workspace:

| Host | Tests | Failures | Notes |
|---|---|---|---|
| Windows 11, JDK 17 | 807 | 170 | 142 are host artefacts |
| WSL Ubuntu, OpenJDK 17 | 807 | 28 | the 28 are real expectations |

## Setup already present

- Distro: Ubuntu (WSL 2).
- JDK 17: `/usr/lib/jvm/java-17-openjdk-amd64` (the default `java` on PATH is 21 — always pass `JAVA_HOME`).
- Android SDK: `/home/cybium/android-sdk` with platforms `android-34/35/36` and build-tools `34.0.0/35.0.0/36.0.0`.
- Gradle 8.14.3 is already in `~/.gradle/wrapper/dists`, and `~/.gradle/caches` is warm.

`ANDROID_HOME` is **not** exported in the WSL shell, and `local.properties` points at the Windows SDK path.
Pass both variables inline; AGP prefers them over `local.properties` in this setup.

## Commands

From PowerShell, in the repository root:

```powershell
wsl -d Ubuntu bash -lc 'cd /mnt/c/Users/Cybium/Desktop/ScanWhirl && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon --continue'
```

Other gates, same prefix:

- Compile main: `:app:compileDebugKotlin`
- Compile tests: `:app:compileDebugUnitTestKotlin`
- Lint: `lintDebug`

Keep the wrapper executable bit: if `./gradlew` reports "Permission denied", `git update-index --chmod=+x gradlew`
is not needed here — the file is already executable; a fresh clone from Windows may need `chmod +x gradlew` inside WSL.

Run one task at a time. A full suite takes about 11 min on `/mnt/c` (first run) and less once the build cache is warm.

## Why Windows shows extra failures

`java.io.File.renameTo` cannot replace an existing file on Windows; `rename(2)` on Android and Linux can.
The app saves safely by renaming over the previous file, so every test that exercises a save, a meta write,
an OCR write or a page swap fails on Windows with:

- `IOException: Could not replace meta.txt / ocr.txt / ocr_geo.txt / save_txn.txt / review_state.json / page_000.jpg / page_000.orig`
- `IOException: Could not publish scan.pdf / src.pdf`
- `IllegalArgumentException: Failed to find configured root that contains C:\Users\...\Temp\robolectric-...` (FileProvider test root)

These are **not** product defects. Treat a Windows-only failure in those three shapes as expected.
A test that fails on both hosts is a real expectation to look at.

## Reading results

Results land in `app/build/test-results/testDebugUnitTest/*.xml` (one file per class) and
`app/build/reports/tests/testDebugUnitTest/index.html`. The Windows and Linux runs write to the same
directory, so the last run wins; re-run before quoting numbers.
