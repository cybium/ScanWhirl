#!/usr/bin/env bash
# Runs INSIDE the GitHub emulator step. Never on a phone, never locally.
# Installs the one test APK, applies the leg's display settings, then runs the walk.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
    echo "FATAL: atlas is CI-only; refusing local adb access" >&2
    exit 1
fi
mapfile -t emulators < <(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
if [ "${#emulators[@]}" -ne 1 ]; then
    echo "FATAL: atlas requires exactly one booted emulator" >&2
    exit 1
fi
export ANDROID_SERIAL="${emulators[0]}"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
APK="${APK:-$HERE/../../test-apk/app-debug.apk}"
test -s "$APK"

adb install -r "$APK" >/dev/null
echo "installed $APK on $ANDROID_SERIAL"

# Leg-specific look: dark mode, 200% font, or the plain small-phone leg.
if [ -n "${LEG_SETUP:-}" ]; then
    printf '%s\n' "$LEG_SETUP" | bash
fi

# Deterministic frames and no launcher ANR dialog covering the app.
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell cmd window user-rotation free >/dev/null 2>&1 || true

exec python3 "$HERE/atlas.py"
