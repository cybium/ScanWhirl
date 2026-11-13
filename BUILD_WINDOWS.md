# Windows build notes

This clean workspace was copied from WSL and points `local.properties` at the Windows Android SDK:

`sdk.dir=C:/Users/Cybium/AppData/Local/Android/Sdk`

Installed toolchain detected:

- JDK 17
- Android SDK with build-tools 34.0.0, 35.0.0, 36.0.0
- Git for Windows
- Gradle wrapper in the repository

## Commands

Run from this directory:

- Compile main sources: `.\gradlew.bat :app:compileDebugKotlin -PtestBuild --no-daemon`
- Compile tests: `.\gradlew.bat :app:compileDebugUnitTestKotlin -PtestBuild --no-daemon`
- Full unit suite: `.\gradlew.bat test -PtestBuild --no-daemon`
- Lint: `.\gradlew.bat lintDebug -PtestBuild --no-daemon`
- Test APK: `.\gradlew.bat assembleRelease -PtestBuild --no-daemon`

## Important

- The current tree is a snapshot of an uncompiled UX pass. A successful compile is not evidence that tests or device behaviour are green.
- Release test builds use `keystore/scanwhirl.keystore` and `keystore.properties`. Do not print or share their contents.
- Never run `assembleRelease` without `-PtestBuild` unless the owner explicitly asks for a production build.
- Run gates one at a time; the machine has limited memory.
