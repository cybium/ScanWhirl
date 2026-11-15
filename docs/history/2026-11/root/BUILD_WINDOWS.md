# Windows build notes

This clean workspace was copied from WSL and points `local.properties` at the Windows Android SDK:

`sdk.dir=C:/Users/Cybium/AppData/Local/Android/Sdk`

Installed toolchain detected:

- JDK 17
- Android SDK with build-tools 34.0.0, 35.0.0, 36.0.0
- Git for Windows
- Gradle wrapper in the repository

## Unit tests are gated on Linux

This host compiles and lints fine, but **do not use it to judge the unit suite**. 142 of 807 tests fail only
because `File.renameTo` cannot replace an existing file on Windows. The canonical runner is WSL — see
`BUILD_LINUX.md` for the exact command and the list of expected host-only failures.

## Commands

Run from this directory:

- Compile main sources: `.\gradlew.bat :app:compileDebugKotlin -PtestBuild --no-daemon`
- Compile tests: `.\gradlew.bat :app:compileDebugUnitTestKotlin -PtestBuild --no-daemon`
- Full unit suite: `.\gradlew.bat test -PtestBuild --no-daemon` (Windows-only failures expected — use `BUILD_LINUX.md` for the real gate)
- Lint: `.\gradlew.bat lintDebug -PtestBuild --no-daemon`
- Test APK: `.\gradlew.bat assembleRelease -PtestBuild --no-daemon`

## Important

- The current tree compiled successfully in this Windows workspace on 2026-11-13. A successful compile is not evidence that tests or device behaviour are green.
- Release test builds use `keystore/scanwhirl.keystore` and `keystore.properties`. Do not print or share their contents.
- Never run `assembleRelease` without `-PtestBuild` unless the owner explicitly asks for a production build.
- Run gates one at a time; the machine has limited memory.
