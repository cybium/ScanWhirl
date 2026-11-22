#!/usr/bin/env python3
"""Fail-closed privacy gate for the AGP-merged RELEASE manifest.

Parses the plain XML produced by :app:processReleaseManifest and (when supplied) the
generated release BuildConfig.java. Never reads signing files, never uses adb or the
network. Unlike verify_aab.py (which inspects the shipped AAB's compiled manifest),
this runs before packaging, so a library that silently merges a new permission is
caught in every lane's CI gate.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"

# Must never appear in the shipped release manifest. The first two are already
# stripped with tools:node="remove" in app/src/main/AndroidManifest.xml because
# transitive Play/ML Kit libraries declare them; the rest are new-SDK risks.
FORBIDDEN_EXACT = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.AD_ID",
    "com.google.android.gms.permission.AD_ID",
    "android.permission.QUERY_ALL_PACKAGES",
}
# Any permission in this family is forbidden. Play Asset Delivery merges
# FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC; neither is approved in 1.3.x.
FORBIDDEN_PREFIX = ("android.permission.FOREGROUND_SERVICE",)

EXPECTED_PACKAGE = "com.scanwhirl.app"
EXPECTED_LABEL = "ScanWhirl"


def android_attr(node, name):
    return node.get(f"{{{ANDROID}}}{name}")


def review(manifest_path, build_config_path=None):
    """Return (ok, report). Any parse error, missing field or forbidden entry fails closed."""
    lines = [f"Merged manifest: {manifest_path}"]
    try:
        root = ET.parse(manifest_path).getroot()
    except (OSError, ET.ParseError) as exc:
        return False, f"MANIFEST FAIL: cannot parse merged manifest: {exc}\n"

    errors = []
    if root.tag != "manifest":
        errors.append(f"root element is <{root.tag}>, expected <manifest>")

    package = root.get("package")
    lines.append(f"package: {package}")
    if package != EXPECTED_PACKAGE:
        errors.append(
            f"package {package!r} != release applicationId {EXPECTED_PACKAGE!r} "
            "(a -PtestBuild/-PdemoBuild manifest is not the release manifest)"
        )

    apps = root.findall("application")
    if len(apps) != 1:
        errors.append(f"expected one <application>, found {len(apps)}")
    else:
        label = android_attr(apps[0], "label")
        lines.append(f"label: {label}")
        if label != EXPECTED_LABEL:
            errors.append(
                f"label {label!r} != {EXPECTED_LABEL!r} "
                "(a -PtestBuild manifest resolves to 'ScanWhirl Test')"
            )

    permissions = []
    for node in root:
        if node.tag not in ("uses-permission", "uses-permission-sdk-23"):
            continue
        name = android_attr(node, "name")
        if not name:
            errors.append(f"<{node.tag}> without android:name")
            continue
        permissions.append(name)
    lines.append(f"permissions ({len(permissions)}): {', '.join(sorted(permissions))}")
    for name in sorted(set(permissions)):
        if name in FORBIDDEN_EXACT:
            errors.append(f"forbidden permission: {name}")
        elif any(name.startswith(prefix) for prefix in FORBIDDEN_PREFIX):
            errors.append(f"forbidden permission family (foreground service): {name}")

    if build_config_path is None:
        # package + label are both derived from the same -PtestBuild flag that sets
        # BuildConfig.TEST_BUILD, so they already prove a non-test release build.
        lines.append("TEST_BUILD: false (inferred from release applicationId and label)")
    else:
        try:
            with open(build_config_path, encoding="utf-8", errors="replace") as stream:
                text = stream.read()
        except OSError as exc:
            errors.append(f"cannot read BuildConfig: {exc}")
        else:
            match = re.search(
                r"public\s+static\s+final\s+boolean\s+TEST_BUILD\s*=\s*(true|false)\s*;", text
            )
            if not match:
                errors.append("TEST_BUILD not found in generated release BuildConfig.java")
            else:
                value = match.group(1)
                lines.append(f"TEST_BUILD: {value} ({build_config_path})")
                if value != "false":
                    errors.append(f"TEST_BUILD={value} in release BuildConfig (expected false)")

    lines.extend(f"FAIL: {error}" for error in errors)
    lines.append("MANIFEST FAIL" if errors else "MANIFEST PASS")
    return not errors, "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", help="AGP processReleaseManifest AndroidManifest.xml")
    parser.add_argument("--build-config", help="generated release BuildConfig.java (optional)")
    args = parser.parse_args()
    good, report = review(args.manifest, args.build_config)
    sys.stdout.write(report)
    return 0 if good else 1


if __name__ == "__main__":
    sys.exit(main())
