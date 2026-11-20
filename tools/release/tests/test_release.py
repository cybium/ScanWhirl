import argparse
import contextlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import xml.etree.ElementTree as ET

from tools.release import assemble_release as package
from tools.release.verify_aab import (
    ANDROID, check_manifest, elf_load_alignments, fields, manifest_facts, proto_xml, verify,
)


def v(number):
    result = bytearray()
    while number > 127:
        result.append((number & 127) | 128)
        number >>= 7
    return bytes(result) + bytes([number])


def pb(number, value):
    if isinstance(value, str):
        value = value.encode()
    return v(number << 3 | 2) + v(len(value)) + value


def node(name, attrs=(), children=()):
    element = pb(3, name)
    for uri, key, value in attrs:
        element += pb(4, pb(1, uri) + pb(2, key) + pb(3, value))
    for child in children:
        element += pb(5, child)
    return pb(1, element)


def elf(bits=2, endian=1, alignment=16384):
    order = "<" if endian == 1 else ">"
    identity = b"\x7fELF" + bytes([bits, endian, 1]) + b"\0" * 9
    header_format = order + ("HHIIIIIHHHHHH" if bits == 1 else "HHIQQQIHHHHHH")
    ph_format = order + ("IIIIIIII" if bits == 1 else "IIQQQQQQ")
    offset = 16 + struct.calcsize(header_format)
    header = struct.pack(header_format, 3, 183, 1, 0, offset, 0, 0, offset,
                         struct.calcsize(ph_format), 1, 0, 0, 0)
    return identity + header + struct.pack(ph_format, 1, 0, 0, 0, 0, 0, 0, alignment)


class ElfTests(unittest.TestCase):
    def test_classes_and_endianness(self):
        for bits in (1, 2):
            for endian in (1, 2):
                self.assertEqual([16384], elf_load_alignments(elf(bits, endian)))

    def test_small_alignment_is_exposed(self):
        self.assertEqual([4096], elf_load_alignments(elf(alignment=4096)))

    def test_malformed(self):
        for data in (b"", b"not ELF", elf()[:50], elf()[:-1]):
            with self.assertRaises(ValueError):
                elf_load_alignments(data)

    def test_no_load_segments(self):
        data = bytearray(elf())
        struct.pack_into("<I", data, 64, 2)
        with self.assertRaisesRegex(ValueError, "no ELF PT_LOAD"):
            elf_load_alignments(data)


class ManifestTests(unittest.TestCase):
    def manifest(self, permissions=()):
        return node("manifest", [("", "package", "com.scanwhirl.app"),
                    (ANDROID, "versionCode", "26112001"), (ANDROID, "versionName", "1.3.0")],
                    [node("uses-sdk", [(ANDROID, "minSdkVersion", "24"), (ANDROID, "targetSdkVersion", "36")]),
                     node("application", [(ANDROID, "allowBackup", "false"),
                                          (ANDROID, "dataExtractionRules", "@0x7f120001")])] +
                    [node(tag, [(ANDROID, "name", name)]) for tag, name in permissions])

    def test_proto_permission_namespaces(self):
        facts = manifest_facts(proto_xml(self.manifest([("uses-permission", "android.permission.CAMERA")])))
        self.assertEqual(["android.permission.CAMERA"], facts["permissions"])
        self.assertEqual([], check_manifest(facts, 26112001, "1.3.0"))

    def test_network_sdk_variant_is_forbidden(self):
        for name in ("INTERNET", "ACCESS_NETWORK_STATE"):
            facts = manifest_facts(proto_xml(self.manifest([("uses-permission-sdk-23", "android.permission." + name)])))
            self.assertTrue(any("forbidden" in e for e in check_manifest(facts, 26112001, "1.3.0")))

    def test_wrong_namespace_does_not_hide_permission(self):
        root = proto_xml(self.manifest())
        ET.SubElement(root, "uses-permission", {"name": "android.permission.INTERNET"})
        with self.assertRaises(ValueError):
            manifest_facts(root)

    def test_bad_flags_and_missing_backup_rules(self):
        facts = manifest_facts(proto_xml(self.manifest()))
        for key, value in (("debuggable", "true"), ("testOnly", "true"),
                           ("allowBackup", None), ("dataExtractionRules", None),
                           ("versionCode", "1"), ("package", "other"), ("targetSdk", "35")):
            modified = dict(facts, **{key: value})
            self.assertTrue(check_manifest(modified, 26112001, "1.3.0"), key)

    def test_compiled_boolean(self):
        attr = pb(1, ANDROID) + pb(2, "allowBackup") + pb(6, pb(7, v(8 << 3) + v(0)))
        root = proto_xml(pb(1, pb(3, "application") + pb(4, attr)))
        self.assertEqual("false", root.get(f"{{{ANDROID}}}allowBackup"))

    def test_truncated_proto(self):
        for data in (b"\x0a\x05x", b"\x80", b"\x00"):
            with self.assertRaises(ValueError):
                fields(data)


class PackagingTests(unittest.TestCase):
    def setup_args(self, root):
        drafts = root / "drafts"
        drafts.mkdir()
        for name in package.REQUIRED:
            (drafts / name).write_bytes(b"\xef\xbb\xbfexample\r\nline\r\n")
        security = root / "verdict.txt"
        security.write_text("TEST ONLY: security not verified", encoding="utf-8")
        aab = root / "test.aab"
        aab.write_bytes(b"synthetic")
        return argparse.Namespace(sha="a" * 40, aab=aab, drafts=drafts, out=root / "out",
                                  gate_run="123", e2e_run="456", security=security, bundletool=None)

    def test_normalization_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            args = self.setup_args(Path(temp))
            with patch.object(package, "verify", return_value=(True, "VERIFY PASS\n")), contextlib.redirect_stdout(io.StringIO()):
                package.assemble(args)
                self.assertEqual(b"example\nline\n", (args.out / "CONSOLE_STEPS.txt").read_bytes())
                with self.assertRaisesRegex(ValueError, "already exists"):
                    package.assemble(args)

    def test_failed_verification_leaves_no_output(self):
        with tempfile.TemporaryDirectory() as temp:
            args = self.setup_args(Path(temp))
            with patch.object(package, "verify", return_value=(False, "VERIFY FAIL\n")):
                with self.assertRaisesRegex(ValueError, "refused packaging"):
                    package.assemble(args)
            self.assertFalse(args.out.exists())


class VerifierTests(unittest.TestCase):
    def test_complete_synthetic_archive_and_32bit_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "fixture.aab"
            for alignment, expected in ((16384, True), (4096, False), (24576, False)):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("base/manifest/AndroidManifest.xml", ManifestTests().manifest())
                    archive.writestr("META-INF/FIXTURE.RSA", b"not a real certificate")
                    archive.writestr("base/lib/fixture/libtest.so", elf(alignment=alignment))
                with patch("tools.release.verify_aab.signer", return_value=(["TEST-FINGERPRINT"], "Synthetic")):
                    good, report = verify(path, 26112001, "1.3.0")
                self.assertEqual(expected, good, report)
                self.assertIn(f"p_align=[{alignment}]", report)
                self.assertIn("ZIP data offset=", report)

    def test_missing_certificate_tool_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "fixture.aab"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("base/manifest/AndroidManifest.xml", ManifestTests().manifest())
                archive.writestr("META-INF/FIXTURE.RSA", b"not a real certificate")
            with patch("tools.release.verify_aab.java_tool", return_value=None):
                good, report = verify(path, 26112001, "1.3.0")
            self.assertFalse(good)
            self.assertIn("NEEDS keytool", report)


if __name__ == "__main__":
    unittest.main()
