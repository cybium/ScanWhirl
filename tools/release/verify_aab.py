#!/usr/bin/env python3
"""Offline AAB inspection. No downloads, signing configuration or private keys."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "http://schemas.android.com/apk/res/android"
# P lane: keep the AAB verifier aligned with the merged-manifest CI gate
# (check_release_manifest.py). INTERNET/ACCESS_NETWORK_STATE are stripped in the
# source manifest; the others are new-SDK risks that must fail closed too.
FORBIDDEN = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.AD_ID",
    "com.google.android.gms.permission.AD_ID",
    "android.permission.QUERY_ALL_PACKAGES",
}
# Play Asset Delivery merges FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC.
# Any permission in this family is forbidden until the owner approves it.
FORBIDDEN_PREFIX = ("android.permission.FOREGROUND_SERVICE",)
PAGE_SIZE_DOC = "https://developer.android.com/guide/practices/page-sizes"
ABIS_64 = {"arm64-v8a", "x86_64"}
ABIS_32 = {"armeabi-v7a", "x86"}


def sha256(path):
    with open(path, "rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def varint(data, pos):
    value = 0
    for shift in range(0, 70, 7):
        if pos >= len(data):
            raise ValueError("truncated protobuf varint")
        byte = data[pos]
        pos += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, pos
    raise ValueError("oversized protobuf varint")


def fields(data):
    """Minimal protobuf wire reader; rejects malformed/truncated records."""
    result = {}
    pos = 0
    while pos < len(data):
        tag, pos = varint(data, pos)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("invalid protobuf field")
        if wire == 0:
            value, pos = varint(data, pos)
        elif wire == 2:
            size, pos = varint(data, pos)
            value = data[pos:pos + size]
            pos += size
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value = data[pos:pos + size]
            pos += size
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")
        if pos > len(data):
            raise ValueError("truncated protobuf field")
        result.setdefault(number, []).append(value)
    return result


def first(message, number, default=b""):
    return message.get(number, [default])[0]


def compiled_value(data):
    item = fields(data)
    for number in (2, 3):
        if number in item:
            return first(fields(first(item, number)), 1).decode("utf-8")
    if 1 in item:
        ref = fields(first(item, 1))
        return "@" + (first(ref, 3).decode() or hex(first(ref, 2, 0)))
    if 7 in item:
        prim = fields(first(item, 7))
        if 8 in prim:
            return "true" if first(prim, 8, 0) else "false"
        for number in (6, 7):
            if number in prim:
                return str(first(prim, number))
    raise ValueError("unsupported compiled attribute")


def proto_xml(data):
    """AAPT2 Resources.proto XmlNode/XmlElement subset, preserving namespaces."""
    node = fields(data)
    if 1 not in node:
        return None
    element = fields(first(node, 1))
    name = first(element, 3).decode("utf-8")
    uri = first(element, 2).decode("utf-8")
    root = ET.Element(f"{{{uri}}}{name}" if uri else name)
    for raw in element.get(4, []):
        attr = fields(raw)
        uri = first(attr, 1).decode("utf-8")
        name = first(attr, 2).decode("utf-8")
        key = f"{{{uri}}}{name}" if uri else name
        if key in root.attrib:
            raise ValueError(f"duplicate XML attribute {key}")
        value = first(attr, 3).decode("utf-8")
        if not value and 6 in attr:
            value = compiled_value(first(attr, 6))
        root.set(key, value)
    for child in element.get(5, []):
        parsed = proto_xml(child)
        if parsed is not None:
            root.append(parsed)
    return root


def manifest_facts(root):
    if root is None or root.tag != "manifest":
        raise ValueError("not an Android manifest")
    def attr(node, name):
        return node.get(f"{{{ANDROID}}}{name}") if node is not None else None
    apps, sdks = root.findall("application"), root.findall("uses-sdk")
    if len(apps) != 1 or len(sdks) != 1:
        raise ValueError("expected one application and uses-sdk")
    permissions = set()
    for node in root:
        if node.tag.startswith("uses-permission"):
            permission = attr(node, "name")
            if not permission:
                raise ValueError("permission missing android:name")
            permissions.add(permission)
    return {
        "package": root.get("package"),
        "versionCode": attr(root, "versionCode"),
        "versionName": attr(root, "versionName"),
        "minSdk": attr(sdks[0], "minSdkVersion"),
        "targetSdk": attr(sdks[0], "targetSdkVersion"),
        "allowBackup": attr(apps[0], "allowBackup"),
        "dataExtractionRules": attr(apps[0], "dataExtractionRules"),
        "debuggable": attr(apps[0], "debuggable"),
        "testOnly": attr(apps[0], "testOnly"),
        "permissions": sorted(permissions),
    }


def check_manifest(facts, code, name, min_sdk=24, target_sdk=36):
    errors = []
    expected = {"package": "com.scanwhirl.app", "versionCode": str(code),
                "versionName": name, "minSdk": str(min_sdk), "targetSdk": str(target_sdk),
                "allowBackup": "false"}
    for key, value in expected.items():
        if facts[key] != value:
            errors.append(f"{key}: expected {value!r}, got {facts[key]!r}")
    if not facts["dataExtractionRules"] or facts["dataExtractionRules"] in ("@0x0", "@null"):
        errors.append("dataExtractionRules missing/null")
    for flag in ("debuggable", "testOnly"):
        if facts[flag] not in (None, "false"):
            errors.append(f"{flag} must be absent or false")
    for permission in sorted(set(facts["permissions"]) & FORBIDDEN):
        errors.append(f"forbidden permission: {permission}")
    for permission in sorted(facts["permissions"]):
        if any(permission.startswith(prefix) for prefix in FORBIDDEN_PREFIX):
            errors.append(f"forbidden permission family (foreground service): {permission}")
    return errors


def elf_load_alignments(data):
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError("not ELF")
    bits, endian = data[4], data[5]
    if bits not in (1, 2) or endian not in (1, 2) or data[6] != 1:
        raise ValueError("unsupported ELF identity")
    order = "<" if endian == 1 else ">"
    header = struct.Struct(order + ("HHIIIIIHHHHHH" if bits == 1 else "HHIQQQIHHHHHH"))
    if len(data) < 16 + header.size:
        raise ValueError("truncated ELF header")
    values = header.unpack_from(data, 16)
    offset, entry_size, count = values[4], values[8], values[9]
    program = struct.Struct(order + ("IIIIIIII" if bits == 1 else "IIQQQQQQ"))
    if count == 0 or count == 65535 or entry_size < program.size:
        raise ValueError("missing/unsupported program header table")
    if offset < 16 + header.size or offset + entry_size * count > len(data):
        raise ValueError("truncated/overlapping program header table")
    alignments = []
    for index in range(count):
        entry = program.unpack_from(data, offset + index * entry_size)
        if entry[0] == 1:
            alignment = entry[-1]
            alignments.append(alignment)
    if not alignments:
        raise ValueError("no ELF PT_LOAD segments")
    return alignments


def java_tool(name):
    found = shutil.which(name)
    if not found and os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / (name + ".exe")
        if candidate.is_file():
            found = str(candidate)
    return found


def signer(path):
    keytool = java_tool("keytool")
    if not keytool:
        return [], "NEEDS keytool: signer fingerprint not verified"
    run = subprocess.run([keytool, "-J-Duser.language=en", "-J-Duser.country=US",
                          "-printcert", "-jarfile", str(path)], capture_output=True,
                         text=True, encoding="utf-8", errors="replace", timeout=120)
    fingerprints = re.findall(r"SHA256:\s*([0-9A-Fa-f:]{95})", run.stdout)
    # keytool may include intermediate certificates. Report all; do not guess a key match.
    if run.returncode or not fingerprints:
        return [], "keytool could not identify signer certificate"
    owners = re.findall(r"Owner: ([^\r\n]+)", run.stdout)
    return list(dict.fromkeys(fingerprints)), "; ".join(owners)


def verify(path, code, name, min_sdk=24, target_sdk=36, bundletool=None):
    path = Path(path)
    lines = [f"AAB: {path}", f"SHA-256: {sha256(path)}", f"Size: {path.stat().st_size} bytes"]
    errors = []
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate ZIP entries")
        manifest_paths = sorted(n for n in names if n.endswith("/manifest/AndroidManifest.xml"))
        if manifest_paths != ["base/manifest/AndroidManifest.xml"]:
            raise ValueError("unsupported module set; expected base-only manifest")
        facts = manifest_facts(proto_xml(archive.read(manifest_paths[0])))
        for key, value in facts.items():
            lines.append(f"{key}: {value}")
        errors.extend(check_manifest(facts, code, name, min_sdk, target_sdk))
        signatures = [n for n in names if re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", n, re.I)]
        lines.append(f"Signature blocks: {signatures}")
        if not signatures:
            errors.append("AAB has no signature block")
        libs = [i for i in archive.infolist() if re.search(r"(^|/)lib/.+\.so$", i.filename)]
        lines.append(f"Native libraries: {len(libs)}")
        lines.append("16 KB PT_LOAD required for arm64-v8a/x86_64; "
                     f"32-bit armeabi-v7a/x86 alignment is informational. Policy: {PAGE_SIZE_DOC}")
        for info in libs:
            try:
                aligns = elf_load_alignments(archive.read(info))
                abi = info.filename.split("/lib/", 1)[1].split("/", 1)[0]
                if abi not in ABIS_64 | ABIS_32:
                    raise ValueError(f"unsupported native ABI {abi}")
                valid = all(a > 0 and a & (a - 1) == 0 for a in aligns)
                good = valid and (abi in ABIS_32 or all(a >= 16384 for a in aligns))
                status = "INFO" if good and abi in ABIS_32 else ("PASS" if good else "FAIL")
                raw.seek(info.header_offset)
                header = raw.read(30)
                if header[:4] != b"PK\x03\x04":
                    raise ValueError("invalid ZIP local header")
                filename_len, extra_len = struct.unpack_from("<HH", header, 26)
                data_offset = info.header_offset + 30 + filename_len + extra_len
                method = "stored" if info.compress_type == 0 else f"compressed(method={info.compress_type})"
                lines.append(f"{status} {info.filename}: PT_LOAD p_align={aligns}; "
                             f"{method}; ZIP data offset={data_offset}; offset%16384={data_offset % 16384}")
                if not good:
                    errors.append(f"{info.filename}: ELF PT_LOAD alignment below 16 KB/invalid")
            except (ValueError, struct.error) as exc:
                errors.append(f"{info.filename}: {exc}")
        lines.append("AAB ZIP offsets are diagnostic only, not installed APK alignment proof.")
    fingerprints, detail = signer(path)
    lines.append(f"Signer: {detail}")
    lines.extend(f"Signer certificate SHA-256: {fingerprint}" for fingerprint in fingerprints)
    if not fingerprints:
        errors.append(detail)
    lines.append("Compare signer fingerprint with Play Console UPLOAD key (not app-signing key).")
    lines.append("Certificate extraction is not cryptographic JAR signature validation.")
    if bundletool:
        java = java_tool("java")
        if not java:
            errors.append("NEEDS java for bundletool")
        else:
            run = subprocess.run([java, "-jar", str(bundletool), "dump", "manifest",
                                  f"--bundle={path}", "--module=base"], capture_output=True,
                                 text=True, encoding="utf-8", errors="replace", timeout=120)
            if run.returncode:
                errors.append("bundletool dump manifest failed: " + run.stderr.strip())
            else:
                dumped = manifest_facts(ET.fromstring(run.stdout))
                # Resource references can be symbolic vs numeric; compare presence for this field.
                for key in facts:
                    same = (bool(facts[key]) == bool(dumped[key]) if key == "dataExtractionRules"
                            else facts[key] == dumped[key])
                    if not same:
                        errors.append(f"bundletool mismatch {key}: {facts[key]!r} vs {dumped[key]!r}")
                lines.append("bundletool manifest comparison completed")
    else:
        lines.append("bundletool: not requested (stdlib AAPT2 protobuf inspection used)")
    lines.extend(f"FAIL: {error}" for error in errors)
    lines.append("VERIFY FAIL" if errors else "VERIFY PASS")
    return not errors, "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aab", type=Path)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--min-sdk", type=int, default=24)
    parser.add_argument("--target-sdk", type=int, default=36)
    parser.add_argument("--bundletool", type=Path)
    args = parser.parse_args()
    try:
        good, report = verify(args.aab, args.version_code, args.version_name,
                              args.min_sdk, args.target_sdk, args.bundletool)
        print(report, end="")
        return 0 if good else 1
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError,
            subprocess.SubprocessError) as exc:
        print(f"VERIFY FAIL: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
