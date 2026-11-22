#!/usr/bin/env bash
# Tool Atlas entry point. Runs INSIDE the GitHub emulator step of e2e.yml.
# Rejects local hosts and real devices before any adb command.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
    echo "FATAL: CI-only Tool Atlas; refusing local adb access" >&2
    exit 2
fi
mapfile -t emulators < <(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
if [ "${#emulators[@]}" -ne 1 ]; then
    echo "FATAL: atlas requires exactly one booted emulator" >&2
    exit 2
fi
export ANDROID_SERIAL="${emulators[0]}"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
# No binary is committed: build the fictional corpus on the runner. The verifier
# needs numpy/Pillow/pypdf/pypdfium2; the generator needs Pillow/reportlab.
python3 -m pip install --quiet numpy Pillow pypdf pypdfium2 reportlab
python3 "$HERE/fixtures_v2/make_fixtures.py"
export ATLAS_OUT="${ATLAS_OUT:-$(cd "$HERE/../../.." && pwd)/artifacts/atlas}"
# run_e2e.ps1 -Atlas carries -Tools in the snapshot because the push trigger has
# no workflow_dispatch inputs; a manual dispatch passes ATLAS_TOOLS directly.
if [ -z "${ATLAS_TOOLS:-}" ] && [ -f "$HERE/selected_tools.txt" ]; then
    export ATLAS_TOOLS="$(tr -d '\r\n' < "$HERE/selected_tools.txt")"
fi
mkdir -p "$ATLAS_OUT"
python3 "$HERE/atlas_driver.py"
