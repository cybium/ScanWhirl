#!/usr/bin/env bash
# ScanWhirl real-Android smoke test - runs INSIDE the emulator step of
# .github/workflows/e2e.yml, on the CI host, with `adb` pointed at a freshly
# booted throwaway emulator. It never touches the owner's physical phone.
#
# What it proves, per (language x font scale) and per API level:
#   * the debug test build (com.scanwhirl.app.test) installs and starts;
#   * the Library loads and every bottom-bar tab (Library / Tools / Vault / Me)
#     and the centre Create sheet actually render - each tap comes from a fresh
#     `uiautomator dump` (view ids set by the app), never a fixed coordinate;
#   * both UI languages (en, hi) and 1.0x / 2.0x font scale draw real screens;
#   * the app survives one rotation on the Library;
#   * no FATAL EXCEPTION / ANR for the test package appears in logcat and the
#     app process is still alive at the end;
#   * two generated (self-authored) PDFs can be placed in /sdcard/Download.
#
# Writes artifacts/<api>/<lang>_<scale>_<screen>.{png,xml}, logcat.txt and
# summary.md (a PASS/FAIL table). Exit code 0 only if every check passed.
#
# Environment (all optional):
#   PKG=com.scanwhirl.app.test   API_LEVEL=35   APK=/path/app-debug.apk
#   OUT=/path/artifacts/35
set -uo pipefail

PKG="${PKG:-com.scanwhirl.app.test}"
API="${API_LEVEL:-unknown}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${OUT:-$ROOT/artifacts/$API}"
APK="${APK:-$ROOT/test-apk/app-debug.apk}"
WORK="$(mktemp -d)"

mkdir -p "$OUT"
rm -f "$OUT"/*.png "$OUT"/*.xml 2>/dev/null

STARTED=$(date +%s)
rows=()
verdict=0

record() { # name status detail
    local name="$1" status="$2" detail="${3:-}"
    detail="${detail//|/\\|}"
    detail="${detail//$'\n'/ }"
    rows+=("$name|$status|$detail")
    if [ "$status" != "PASS" ]; then verdict=1; fi
    printf '%s  %-34s %s\n' "$status" "$name" "$detail"
}

note() { printf '      %s\n' "$*"; }

shoot() { # tag -> sets SHOT_DETAIL, returns 1 if the frame looks blank
    local tag="$1"
    local png="$OUT/${tag}.png"
    SHOT_DETAIL=""
    adb exec-out screencap -p >"$png" 2>/dev/null
    # Always keep the hierarchy next to the picture: it is the evidence used to
    # decide what the screenshot should have shown, and it survives a failure.
    python3 "$HERE/uitool.py" dump --out "$OUT/${tag}.xml" >/dev/null 2>&1 || true
    if [ ! -s "$png" ]; then
        SHOT_DETAIL="screencap produced no data"
        return 1
    fi
    if SHOT_DETAIL=$(python3 "$HERE/uitool.py" image-stats --file "$png" --min-unique 20 --min-mean 4 2>&1); then
        return 0
    fi
    return 1
}

capture() { # tag label
    local tag="$1" label="$2"
    if shoot "$tag"; then
        record "$label" PASS "$SHOT_DETAIL"
    else
        record "$label" FAIL "blank/unreadable frame: $SHOT_DETAIL"
    fi
}

tap_id() { # view-id
    local out xy
    if ! out=$(python3 "$HERE/uitool.py" find --id "$1" --verbose 2>/dev/null); then
        return 1
    fi
    xy=$(echo "$out" | cut -d' ' -f1,2)
    note "tap $1 at $xy $(echo "$out" | cut -s -d' ' -f3-)"
    # shellcheck disable=SC2086
    adb shell input tap $xy >/dev/null 2>&1
}

open_create_sheet() {
    # A first synthetic tap can be swallowed while the disc's ring animation
    # starts; retry once, then report honestly. The check is still "the sheet is
    # on screen", never "a tap was sent".
    local attempt
    for attempt in 1 2; do
        if ! tap_id swNavScan; then
            note "create disc (swNavScan) not in the hierarchy"
            return 1
        fi
        if wait_id swCreateScan 12; then
            return 0
        fi
        note "create sheet did not open on attempt $attempt"
        sleep 2
    done
    return 1
}

wait_id() { # view-id [seconds]
    python3 "$HERE/uitool.py" wait --id "$1" --timeout "${2:-40}" >/dev/null 2>&1
}

has_desc() { python3 "$HERE/uitool.py" has --desc "$1" >/dev/null 2>&1; }

set_language() { # en|hi
    local tag="$1"
    adb shell cmd locale set-app-locales "$PKG" --locales "$tag" >/dev/null 2>&1
    adb shell cmd locale get-app-locales "$PKG" 2>/dev/null | tr -d '\r'
}

DIALOGS=0
dismiss_system_dialogs() {
    # A system "… isn't responding" dialog (Pixel Launcher, System UI) becomes the
    # focused window, so every uiautomator dump shows only the dialog and the app
    # looks absent. That is emulator noise, never the app's fault: tap Wait and
    # carry on, and report the count so a noisy runner is visible in the summary.
    local xy tries=0
    while [ "$tries" -lt 4 ]; do
        if ! python3 "$HERE/uitool.py" has --id aerr_wait >/dev/null 2>&1; then
            break
        fi
        if ! xy=$(python3 "$HERE/uitool.py" find --id aerr_wait 2>/dev/null); then
            break
        fi
        note "dismissing a system 'isn't responding' dialog (Wait)"
        # shellcheck disable=SC2086
        adb shell input tap $xy >/dev/null 2>&1
        DIALOGS=$((DIALOGS + 1))
        sleep 3
        tries=$((tries + 1))
    done
}

launch_until_library() { # seconds
    local limit="${1:-90}"
    dismiss_system_dialogs
    if launch_app && wait_id swNavLibrary "$limit"; then
        return 0
    fi
    # One retry: a system dialog that appeared mid-launch, or a cold start that
    # lost the first frame. The Library must still be on screen afterwards.
    dismiss_system_dialogs
    launch_app >/dev/null
    wait_id swNavLibrary "$limit"
}

launch_app() {
    adb shell am force-stop "$PKG" >/dev/null 2>&1
    local out
    out=$(adb shell am start -W -n "$PKG/com.scanwhirl.app.ui.shell.ShellActivityCyan" 2>&1 | tr -d '\r')
    note "$(echo "$out" | grep -E 'Status|TotalTime|Error' | tr '\n' ' ')"
    echo "$out" | grep -q "Status: ok"
}

gone() { # view-id -> true when the id is NOT on screen any more
    ! python3 "$HERE/uitool.py" has --id "$1" >/dev/null 2>&1
}

# ---------------------------------------------------------------- device / apk
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
device=$(adb shell getprop ro.product.model | tr -d '\r')
abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
note "emulator: $device / Android API $sdk / $abi"

write_summary() {
    FINISHED=$(date +%s)
    MINUTES=$(((FINISHED - STARTED) / 60))
    {
        echo "# ScanWhirl E2E smoke - API $API"
        echo
        echo "- device: ${device:-?} (Android API ${sdk:-?}, ${abi:-?})"
        echo "- package: $PKG ${version_name:-}"
        echo "- apk: $(basename "$APK") sha256=${apk_sha:-missing}"
        echo "- run: $(date -u +%Y-%m-%dT%H:%M:%SZ), ${MINUTES} min in the emulator step"
        echo
        echo "| check | result | detail |"
        echo "| --- | --- | --- |"
        for row in "${rows[@]}"; do
            IFS='|' read -r name status detail <<<"$row"
            echo "| $name | $status | $detail |"
        done
    } >"$OUT/summary.md"
    cat "$OUT/summary.md"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        cat "$OUT/summary.md" >>"$GITHUB_STEP_SUMMARY"
    fi
}

if [ ! -f "$APK" ]; then
    record "apk present" FAIL "$APK not found"
    write_summary
    echo "E2E FAIL api=$API (no APK)"
    exit 1
fi
apk_sha=$(sha256sum "$APK" | cut -d' ' -f1)
note "apk: $(basename "$APK") sha256=$apk_sha"
if adb install -r "$APK" >"$OUT/install.txt" 2>&1; then
    record "apk installs" PASS "$(tr -d '\r' <"$OUT/install.txt" | tail -n1)"
else
    record "apk installs" FAIL "$(tr -d '\r' <"$OUT/install.txt" | tail -n3 | tr '\n' ' ')"
fi

version_name=$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d '\r' | xargs || true)
note "package $PKG $version_name"

# ------------------------------------------------------------ generated fixtures
# Self-authored content only (public-domain by construction): no personal files,
# no corpus material. The guard forbids committing binaries, so they are built here.
python3 "$HERE/make_pdf.py" "$WORK/sample-invoice.pdf" "ScanWhirl E2E fixture - invoice" >/dev/null 2>&1
python3 "$HERE/make_pdf.py" "$WORK/sample-letter.pdf" "ScanWhirl E2E fixture - letter" >/dev/null 2>&1
adb shell mkdir -p /sdcard/Download >/dev/null 2>&1
if adb push "$WORK/sample-invoice.pdf" "$WORK/sample-letter.pdf" /sdcard/Download/ >"$OUT/push.txt" 2>&1 \
    && adb shell "test -s /sdcard/Download/sample-invoice.pdf && test -s /sdcard/Download/sample-letter.pdf"; then
    record "fixtures pushed" PASS "2 generated PDFs in /sdcard/Download"
else
    record "fixtures pushed" FAIL "$(tr -d '\r' <"$OUT/push.txt" | tail -n2 | tr '\n' ' ')"
fi

# Deterministic timing/capture: no animations, no spell-checker suggestions.
adb shell settings put global window_animation_scale 0 >/dev/null 2>&1
adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1
adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1
adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
adb shell settings put system user_rotation 0 >/dev/null 2>&1
# Emulator noise, never evidence: without this the Pixel Launcher's own ANR
# dialog can cover the app and every dump shows only that dialog.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1

# ------------------------------------------------------------------- UI walk
# Anchor views (ids the app itself sets - locale independent):
#   Library swHomeSearch | Tools swToolsSearch | Vault swVaultStatus | Me swMeList
#   Create sheet swCreateScan
walk() { # prefix
    local prefix="$1"

    if ! launch_until_library 90; then
        record "$prefix launch" FAIL "Library did not appear within 90s"
        shoot "${prefix}_launch_failed" || true
        return 1
    fi
    record "$prefix launch" PASS "ShellActivity started, Library nav visible"
    if python3 "$HERE/uitool.py" has --id swHomeSearch >/dev/null 2>&1; then
        record "$prefix library loads" PASS "Library header (search field) rendered"
    else
        record "$prefix library loads" FAIL "Library header (swHomeSearch) missing"
    fi
    capture "${prefix}_library" "$prefix/library"

    if tap_id swNavTools && wait_id swToolsSearch 40; then
        capture "${prefix}_tools" "$prefix/tools"
    else
        dismiss_system_dialogs
        if tap_id swNavTools && wait_id swToolsSearch 40; then
            capture "${prefix}_tools" "$prefix/tools"
        else
            record "$prefix/tools" FAIL "Tools screen (swToolsSearch) did not appear"
            shoot "${prefix}_tools_stuck" || true
        fi
    fi

    if tap_id swNavVault && wait_id swVaultStatus 40; then
        capture "${prefix}_vault" "$prefix/vault"
    else
        dismiss_system_dialogs
        if tap_id swNavVault && wait_id swVaultStatus 40; then
            capture "${prefix}_vault" "$prefix/vault"
        else
            record "$prefix/vault" FAIL "Vault screen (swVaultStatus) did not appear"
            shoot "${prefix}_vault_stuck" || true
        fi
    fi

    if tap_id swNavMe && wait_id swMeList 40; then
        capture "${prefix}_me" "$prefix/me"
    else
        dismiss_system_dialogs
        if tap_id swNavMe && wait_id swMeList 40; then
            capture "${prefix}_me" "$prefix/me"
        else
            record "$prefix/me" FAIL "Me screen (swMeList) did not appear"
            shoot "${prefix}_me_stuck" || true
        fi
    fi

    if tap_id swNavLibrary && wait_id swHomeSearch 40; then
        : # back on the Library
    else
        record "$prefix/library" FAIL "Library tab did not come back"
    fi

    # Centre Create sheet (scan / import / ID card / passport / QR / photos).
    if open_create_sheet; then
        capture "${prefix}_create" "$prefix/create"
        adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1
        sleep 1
        # Assert the sheet is really gone: the dump still lists the activity behind it.
        if gone swCreateScan && wait_id swNavLibrary 30; then
            note "Create sheet closed with Back"
        else
            record "$prefix/create close" FAIL "Create sheet did not close on Back"
        fi
    else
        record "$prefix/create" FAIL "Create sheet (swCreateScan) did not appear"
        shoot "${prefix}_create_stuck" || true
        adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|Window #" | tr -d '\r' \
            >"$OUT/${prefix}_create_windows.txt"
        grep -o 'text="[^"]*"' "$OUT/${prefix}_create_stuck.xml" 2>/dev/null | head -n 12 \
            >"$OUT/${prefix}_create_texts.txt"
    fi
}

for scale in 1.0 2.0; do
    for lang in en hi; do
        prefix="${lang}_${scale}"
        note "--- $prefix ---"
        adb shell settings put system font_scale "$scale" >/dev/null 2>&1
        locale_now=$(set_language "$lang")
        adb shell settings put global window_animation_scale 0 >/dev/null 2>&1
        case "$locale_now" in
            *"$lang"*) record "$prefix language" PASS "$locale_now" ;;
            *) record "$prefix language" FAIL "per-app locale not applied: '$locale_now'" ;;
        esac
        walk "$prefix"
        # Locale actually drawn: the Library nav label is the first thing a user reads.
        if [ "$lang" = "hi" ]; then
            if has_desc "कागज़ात"; then
                record "$prefix hindi drawn" PASS "nav label कागज़ात present"
            else
                record "$prefix hindi drawn" FAIL "Hindi nav label missing (English fallback?)"
            fi
        else
            if has_desc "Library"; then
                record "$prefix english drawn" PASS "nav label Library present"
            else
                record "$prefix english drawn" FAIL "English nav label missing"
            fi
        fi
    done
done

# ------------------------------------------------------------------- rotation
note "--- rotation ---"
adb shell settings put system font_scale 1.0 >/dev/null 2>&1
set_language en >/dev/null 2>&1
if launch_until_library 90; then
    pid_before=$(adb shell pidof "$PKG" | tr -d '\r')
    adb shell settings put system user_rotation 1 >/dev/null 2>&1
    sleep 4
    pid_after=$(adb shell pidof "$PKG" | tr -d '\r')
    if [ -n "$pid_after" ] && [ "$pid_after" = "$pid_before" ] && wait_id swNavLibrary 45; then
        capture "rotation_landscape" "rotation/library-landscape"
        note "process $pid_before survived the rotation"
    else
        record "rotation/library-landscape" FAIL "app did not survive rotation (pid $pid_before -> ${pid_after:-dead})"
        shoot "rotation_failed" || true
    fi
    adb shell settings put system user_rotation 0 >/dev/null 2>&1
    sleep 3
    if wait_id swNavLibrary 45; then
        capture "rotation_portrait" "rotation/library-back-to-portrait"
    else
        record "rotation/library-back-to-portrait" FAIL "Library did not come back in portrait"
    fi
else
    record "rotation/library-landscape" FAIL "could not relaunch the app for the rotation check"
fi

# ------------------------------------------------------------ crash / ANR scan
# Whatever happened above, one user-visible crash or ANR fails the job.
adb logcat -d >"$OUT/logcat.txt" 2>/dev/null
fatal=$(grep -A6 "FATAL EXCEPTION" "$OUT/logcat.txt" 2>/dev/null | grep -c "Process: $PKG" || true)
anr=$(adb logcat -d -b events 2>/dev/null | grep -c "am_anr.*$PKG" || true)
alive=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
if [ "${fatal:-0}" -eq 0 ]; then
    record "no FATAL EXCEPTION" PASS "no AndroidRuntime crash reported for $PKG"
else
    record "no FATAL EXCEPTION" FAIL "$fatal crash report(s) in logcat.txt"
fi
if [ "${anr:-0}" -eq 0 ]; then
    record "no ANR" PASS "no am_anr event for $PKG"
else
    record "no ANR" FAIL "$anr ANR event(s) in the event log"
fi
if [ -n "$alive" ]; then
    record "app process alive" PASS "pid $alive"
else
    record "app process alive" FAIL "process $PKG is gone"
fi
record "system dialogs" PASS "dismissed $DIALOGS system 'not responding' dialog(s) (emulator noise)"

# --------------------------------------------------------------------- summary
write_summary

rm -rf "$WORK"
if [ "$verdict" -eq 0 ]; then
    echo "E2E PASS api=$API checks=${#rows[@]}"
else
    echo "E2E FAIL api=$API checks=${#rows[@]}"
fi
exit "$verdict"
