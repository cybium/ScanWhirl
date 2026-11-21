"""Offline negatives for the uimap crawler; no adb / device is invoked."""
import os
import re
import struct
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import uimap as u


def node(**attrs):
    return ET.Element("node", {"bounds": "[10,20][110,120]", **attrs})


class SelectorTest(unittest.TestCase):
    def test_class_filter(self):
        root = ET.Element("hierarchy")
        root.append(node(**{"class": "android.widget.EditText", "text": "pw"}))
        self.assertIsNotNone(u.find(root, cls="EditText"))
        self.assertIsNone(u.find(root, cls="Button"))

    def test_control_key_prefers_id_then_desc_then_text(self):
        self.assertEqual(u.control_key(node(**{"resource-id": "pkg:id/swNavTools",
                                                "content-desc": "Tools", "text": "Tools"})), "id:swNavTools")
        self.assertEqual(u.control_key(node(**{"content-desc": "Tools", "text": "Tools"})), "desc:Tools")
        self.assertEqual(u.control_key(node(text="Settings")), "text:Settings")

    def test_zero_area_control_is_not_clickable_candidate(self):
        root = ET.Element("hierarchy")
        root.append(node(clickable="true", bounds="[0,0][0,0]"))
        self.assertEqual(u.clickable_nodes(root), [])


class SkipTest(unittest.TestCase):
    def test_create_disc_is_not_a_scanner_skip(self):
        n = node(**{"resource-id": "pkg:id/swNavScan",
                    "content-desc": "Create: scan or import a document"})
        self.assertIsNone(u.skip_reason(n))

    def test_scanner_tiles_and_empty_state_are_skipped(self):
        for label in ("Scan", "QR code", "Scan with camera", "Barcode scan", "Scan a document"):
            self.assertEqual(u.skip_reason(node(text=label)), "ML Kit scanner", label)

    def test_billing_and_whole_library_delete_are_skipped(self):
        self.assertEqual(u.skip_reason(node(text="Get Pro")), "Play billing purchase")
        self.assertEqual(u.skip_reason(node(text="Restore purchase")), "Play billing purchase")
        self.assertEqual(u.skip_reason(node(text="Empty Trash")), "whole-library delete")

    def test_ordinary_controls_are_visited(self):
        self.assertIsNone(u.skip_reason(node(**{"resource-id": "pkg:id/swNavTools", "text": "Tools"})))


class MappingTest(unittest.TestCase):
    def test_inventory_mapping_has_400_slots_and_nav_ids(self):
        self.assertEqual(u.TOTAL_SLOTS, 400)
        self.assertEqual(u.CONTROLS["by_id"]["swnavlibrary"], ["C001"])
        self.assertEqual(u.CONTROLS["by_id"]["swnavtools"], ["C002"])
        self.assertIn("C005", u.slot_ids(node(**{"resource-id": "pkg:id/swNavScan"})))

    def test_slot_ids_dedupes(self):
        ids = u.slot_ids(node(**{"resource-id": "pkg:id/swNavTools", "text": "Tools",
                                 "content-desc": "Tools"}))
        self.assertEqual(len(ids), len(set(ids)))


class FixtureTest(unittest.TestCase):
    def test_pdf_fixture_is_three_pages_and_fictional(self):
        pdf = u.build(u.make_fixtures.PDFS["fixture-report-3p.pdf"])
        self.assertIn(b"/Count 3", pdf)
        self.assertEqual(pdf.count(b"/Type /Page "), 3)
        self.assertIn(b"Fictional", pdf)

    def test_png_generator_emits_valid_nonblank_image(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "fixture.png"
            n = u.make_fixtures.png(str(path), 40, 40)
            data = path.read_bytes()
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        w, h = struct.unpack(">II", data[16:24])
        self.assertEqual((w, h), (40, 40))
        self.assertGreater(n, 1000)


class SafetyTest(unittest.TestCase):
    def test_local_execution_rejected_before_adb(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}), patch.object(u, "adb") as adb:
            with self.assertRaises(AssertionError):
                u.main()
            adb.assert_not_called()

    def test_physical_serial_rejected_before_adb(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "ANDROID_SERIAL": "physical"}):
            with patch.object(u, "adb") as adb:
                with self.assertRaises(AssertionError):
                    u.main()
                adb.assert_not_called()


if __name__ == "__main__":
    unittest.main()
