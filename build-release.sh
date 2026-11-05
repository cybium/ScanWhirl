#!/usr/bin/env bash
# Build ScanWhirl release AAB
set -euo pipefail
cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# Signing credentials come from the untracked keystore.properties (or
# SCANWHIRL_* environment variables) — see app/build.gradle.kts.

./gradlew bundleRelease assembleRelease --no-daemon

AAB="app/build/outputs/bundle/release/app-release.aab"
APK="app/build/outputs/apk/release/app-release.apk"
VER=$(grep versionName app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
mkdir -p dist
cp "$AAB" "dist/ScanWhirl-v${VER}.aab"
cp "$APK" "dist/ScanWhirl-v${VER}.apk"
echo "Built: dist/ScanWhirl-v${VER}.aab"
echo "Built: dist/ScanWhirl-v${VER}.apk"
