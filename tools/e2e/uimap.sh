#!/usr/bin/env bash
# Current full-screen UI map. Runs AFTER the API 35 smoke job on a fresh,
# disposable GitHub emulator. Never a phone; installs only the provided test APK.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
    echo "FATAL: CI-only emulator UI map; refusing local adb access" >&2
    exit 1
fi
# Pin every adb call to the single booted emulator; physical serials are rejected.
mapfile -t emulators < <(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
if [ "${#emulators[@]}" -ne 1 ]; then
    echo "FATAL: uimap requires exactly one booted emulator" >&2
    exit 1
fi
export ANDROID_SERIAL="${emulators[0]}"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"

PKG="com.scanwhirl.app.test"
API="${API_LEVEL:-35}"
OUT="${OUT:-$HERE/../../artifacts/uimap}"
APK="${APK:-$HERE/../../test-apk/app-debug.apk}"
mkdir -p "$OUT/uimap"

if [ ! -s "$APK" ]; then
    echo "FATAL: test APK not found at $APK" >&2
    exit 2
fi
echo "uimap: installing $(basename "$APK") sha256=$(sha256sum "$APK" | cut -d' ' -f1)"
sha256sum "$APK" >"$OUT/uimap/apk-sha256.txt"
adb install -r "$APK" >"$OUT/uimap/install.txt" 2>&1 || {
    tail -n3 "$OUT/uimap/install.txt" >&2
    exit 2
}

# Deterministic capture: no animations, no system ANR dialog covering the app.
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

export PKG API_LEVEL="$API" OUT APK
exec python3 "$HERE/uimap_driver.py"
