"""Offline harness negatives; no adb/device is invoked."""
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import flow_driver as f
from make_pdf import build


class FlowSelectorsTest(unittest.TestCase):
    def node(self, **attrs):
        return ET.Element("node", {"bounds": "[10,20][110,120]", **attrs})

    def test_exact_text_does_not_match_substring(self):
        self.assertTrue(f.match(self.node(text="Merge"), text="Merge"))
        self.assertFalse(f.match(self.node(text="Merge PDF files"), text="Merge"))
        self.assertFalse(f.match(self.node(text="Merge"), desc="Merge"))

    def test_description_and_hindi_match(self):
        self.assertTrue(f.match(self.node(**{"content-desc": "कागज़ात"}), desc="कागज़ात"))

    def test_resource_id_matches_bare_and_qualified(self):
        qualified = self.node(**{"resource-id": "com.scanwhirl.app.test:id/backupNow"})
        self.assertTrue(f.match(qualified, rid="backupNow"))
        self.assertTrue(f.match(self.node(**{"resource-id": "backupNow"}), rid="backupNow"))
        self.assertFalse(f.match(qualified, rid="backupRestore"))

    def test_find_within_parent_scopes_repeated_ids(self):
        root = ET.Element("hierarchy")
        card = self.node(clickable="true")
        more = self.node(**{"resource-id": "com.scanwhirl.app.test:id/swDocMore"})
        card.append(more)
        root.append(card)
        self.assertIs(f.find_within(card, rid="swDocMore"), more)
        with self.assertRaises(AssertionError):
            f.find_within(card, rid="qaDelete")

    def test_clickable_ancestor_returns_none_without_clickable(self):
        root = ET.Element("hierarchy")
        label = self.node()
        root.append(label)
        self.assertIsNone(f.clickable_ancestor(root, label))

    def test_swipe_reveal_below_uses_live_bounds(self):
        node = self.node(bounds="[100,200][500,700]")
        with patch.object(f, "adb") as adb, patch.object(f.time, "sleep"):
            f.swipe_reveal_below(node)
            # x centre 300; span 500 => start 700-62=638, end 200+62=262 (finger up).
            adb.assert_called_once_with("shell", "input", "swipe", 300, 638, 300, 262, 800)

    def test_long_press_node_holds_at_clickable_ancestor(self):
        root = ET.Element("hierarchy")
        parent = self.node(clickable="true", bounds="[100,200][500,400]")
        label = self.node(text="e2e-three-pages")
        root.append(parent)
        parent.append(label)
        with patch.object(f, "adb") as adb, patch.object(f.time, "sleep"):
            f.long_press_node(root, label)
            adb.assert_called_once_with("shell", "input", "swipe", 300, 300, 300, 300, 900)

    def test_swipe_reveal_right_uses_live_bounds(self):
        node = self.node(bounds="[100,200][500,400]")
        with patch.object(f, "adb") as adb, patch.object(f.time, "sleep"):
            f.swipe_reveal_right(node, y_fraction=0.75)
            # span 400 => start 500-66=434, end 100+66=166; y=200+150=350.
            adb.assert_called_once_with("shell", "input", "swipe", 434, 350, 166, 350, 600)

    def test_missing_and_zero_area_controls_fail_closed(self):
        root = ET.Element("hierarchy")
        root.append(self.node(text="Compress", bounds="[0,0][0,0]"))
        with self.assertRaises(AssertionError):
            f.find(root, text="Compress")
        with self.assertRaises(AssertionError):
            f.find(root, text="Cancel")

    def test_visibility_rejects_clipping_and_sub48dp(self):
        with patch.object(f, "adb", return_value="Physical size: 1080x2400"):
            self.assertIn("height=100px", f.visible(self.node(), 100))
            with self.assertRaises(AssertionError):
                f.visible(self.node(bounds="[0,2300][300,2450]"))
            with self.assertRaises(AssertionError):
                f.visible(self.node(), 144)

    def test_tap_uses_current_accessible_ancestor_bounds(self):
        root = ET.Element("hierarchy")
        parent = self.node(clickable="true", bounds="[100,200][500,400]")
        label = self.node(text="Compress")
        root.append(parent)
        parent.append(label)
        with patch.object(f, "adb") as adb:
            f.tap_node(root, label)
            adb.assert_called_once_with("shell", "input", "tap", 300, 300)

    def test_system_list_row_without_clickable_parent_uses_label_not_window(self):
        root = ET.Element("hierarchy")
        window = self.node(clickable="false", bounds="[0,0][1080,2400]")
        label = self.node(text="Downloads", clickable="false", bounds="[168,617][714,668]")
        root.append(window)
        window.append(label)
        with patch.object(f, "adb") as adb:
            f.tap_node(root, label)
            adb.assert_called_once_with("shell", "input", "tap", 441, 642)

    def test_local_execution_is_rejected_before_any_adb(self):
        with patch.dict(f.os.environ, {"GITHUB_ACTIONS": "false"}), patch.object(f, "adb") as adb:
            with self.assertRaises(AssertionError):
                f.main()
            adb.assert_not_called()

    def test_physical_serial_rejected_before_any_adb(self):
        with patch.dict(f.os.environ, {"GITHUB_ACTIONS": "true", "ANDROID_SERIAL": "physical-not-allowed"}):
            with patch.object(f, "adb") as adb:
                with self.assertRaises(AssertionError):
                    f.main()
                adb.assert_not_called()

    def test_rotation_assertion_requires_observed_ui_orientation(self):
        portrait = ET.Element("hierarchy", {"rotation": "0"})
        landscape = ET.Element("hierarchy", {"rotation": "1"})
        self.assertIs(f.require_rotation(landscape, 1), landscape)
        self.assertIs(f.require_rotation(portrait, 0), portrait)
        with self.assertRaisesRegex(AssertionError, "Emulator rotation did not change"):
            f.require_rotation(portrait, 1)
        with self.assertRaisesRegex(AssertionError, "Emulator rotation did not change"):
            f.require_rotation(ET.Element("hierarchy"), 1)

    def test_wait_rotation_fails_when_setting_only_did_not_rotate(self):
        portrait = ET.Element("hierarchy", {"rotation": "0"})
        landscape = ET.Element("hierarchy", {"rotation": "1"})
        with patch.object(f, "hierarchy", return_value=portrait):
            with self.assertRaisesRegex(AssertionError, "emulator/harness failure"):
                f.wait_rotation(1, timeout=0)
        with patch.object(f, "hierarchy", return_value=landscape):
            self.assertIs(f.wait_rotation(1, timeout=0), landscape)

    def test_fixture_is_three_pages(self):
        pdf = build([["Fictional page 1"], ["Fictional page 2"], ["Fictional page 3"]])
        self.assertIn(b"/Count 3", pdf)
        self.assertEqual(pdf.count(b"/Type /Page "), 3)

    def test_source_owned_copy_parity(self):
        for lang in ("en", "hi"):
            s = f.strings(lang)
            for key in ("shell_nav_me", "shell_nav_vault", "vault_tab_relock", "tool_compress",
                        "compress_level_title", "compress_action", "home_search_hint", "tools_search_hint"):
                self.assertTrue(s[key], (lang, key))
            self.assertIn("2", s["vault_tab_relock"])


if __name__ == "__main__":
    unittest.main()
