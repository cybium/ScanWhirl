#!/usr/bin/env bash
# Runs AFTER smoke.sh on the same disposable GitHub emulator; never a phone.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
    echo "FATAL: CI-only emulator flows; refusing local adb access" >&2
    exit 1
fi
# Pin every child adb invocation to the sole emulator, rejecting physical serials.
mapfile -t emulators < <(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
if [ "${#emulators[@]}" -ne 1 ]; then
    echo "FATAL: flows requires exactly one booted emulator" >&2
    exit 1
fi
export ANDROID_SERIAL="${emulators[0]}"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
exec python3 "$HERE/flow_driver.py"
