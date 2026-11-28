"""Offline tests for the atlas' pure UI-quality helpers; no adb/device is used."""
import struct
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

import atlas


def node(bounds="[0,0][100,100]", **attrs):
    return ET.Element("node", {"bounds": bounds, **attrs})


class GeometryTest(unittest.TestCase):
    def test_parse_bounds(self):
        self.assertEqual(atlas.parse_bounds("[10,20][110,120]"), (10, 20, 110, 120))
        self.assertIsNone(atlas.parse_bounds("[10,20]"))
        self.assertIsNone(atlas.parse_bounds(None))

    def test_px_to_dp_uses_density(self):
        self.assertAlmostEqual(atlas.px_to_dp(96, 2.0), 48.0)
        self.assertAlmostEqual(atlas.px_to_dp(48, 1.0), 48.0)

    def test_intersection_and_area(self):
        a, b = (0, 0, 100, 100), (50, 50, 150, 150)
        self.assertEqual(atlas.intersection(a, b), (50, 50, 100, 100))
        self.assertEqual(atlas.rect_area(atlas.intersection(a, b)), 2500)
        self.assertIsNone(atlas.intersection(a, (200, 200, 300, 300)))
        self.assertEqual(atlas.rect_area(None), 0)

    def test_slugify_is_fit_for_filenames(self):
        self.assertEqual(atlas.slugify("Tools / Search: PDF/A!"), "tools-search-pdf-a")
        self.assertEqual(atlas.slugify("  "), "screen")


class FlagTest(unittest.TestCase):
    def root_with(self, *nodes):
        root = ET.Element("hierarchy")
        for item in nodes:
            root.append(item)
        return root

    def test_small_clickable_targets_are_reported_in_dp(self):
        small = node("[0,0][90,90]", clickable="true", text="Tiny")
        big = node("[100,0][200,100]", clickable="true", text="Fine")
        flags = atlas.analyze(self.root_with(small, big), 2.0, 1080, 2400)
        self.assertEqual(len(flags["small_targets"]), 1)
        self.assertEqual(flags["small_targets"][0]["label"], "Tiny")
        self.assertEqual(flags["small_targets"][0]["dp"], [45.0, 45.0])

    def test_system_controls_are_not_touch_target_findings(self):
        system = node("[0,0][10,10]", clickable="true",
                      **{"resource-id": "com.android.systemui:id/back"})
        flags = atlas.analyze(self.root_with(system), 2.0, 1080, 2400)
        self.assertEqual(flags["small_targets"], [])

    def test_text_clipped_by_screen(self):
        clipped = node("[1000,200][1200,260]", text="Past the edge")
        flags = atlas.analyze(self.root_with(clipped), 2.0, 1080, 2400)
        self.assertEqual(len(flags["clipped_text"]), 1)
        self.assertEqual(flags["clipped_text"][0]["where"], "screen")

    def test_text_clipped_by_non_scrolling_parent(self):
        parent = node("[10,10][110,110]", clickable="false")
        child = node("[20,20][140,60]", text="Overflowing label")
        parent.append(child)
        flags = atlas.analyze(self.root_with(parent), 1.0, 1080, 2400)
        self.assertTrue(any(item["where"] == "parent" for item in flags["clipped_text"]))

    def test_scrolling_parent_does_not_count_as_clip(self):
        scroller = node("[10,10][110,110]", scrollable="true")
        child = node("[20,20][140,60]", text="Long row")
        scroller.append(child)
        flags = atlas.analyze(self.root_with(scroller), 1.0, 1080, 2400)
        self.assertEqual(flags["clipped_text"], [])

    def test_overlapping_siblings_are_flagged_but_nesting_is_not(self):
        a = node("[0,0][100,100]", clickable="true", text="A")
        b = node("[40,40][160,160]", clickable="true", text="B")
        flags = atlas.analyze(self.root_with(a, b), 1.0, 1080, 2400)
        self.assertEqual(len(flags["overlaps"]), 1)
        parent = node("[0,0][200,200]", clickable="true", text="Parent")
        child = node("[10,10][100,100]", clickable="true", text="Child")
        parent.append(child)
        self.assertEqual(atlas.analyze(self.root_with(parent), 1.0, 1080, 2400)["overlaps"], [])

    def test_bottom_sheet_touch_outside_is_not_an_overlap(self):
        outside = node("[0,0][1080,2400]", clickable="true",
                       **{"resource-id": "com.google.android.material:id/touch_outside"})
        close = node("[900,100][1000,200]", clickable="true", text="Close",
                     **{"resource-id": "pkg:id/swSheetClose"})
        flags = atlas.analyze(self.root_with(outside, close), 1.0, 1080, 2400)
        self.assertEqual(flags["overlaps"], [])

    def test_multiple_primary_buttons(self):
        one = node("[0,0][200,100]", clickable="true",
                   **{"resource-id": "pkg:id/swSheetPrimary"})
        two = node("[0,100][200,200]", clickable="true",
                   **{"resource-id": "pkg:id/swSheetPrimary2"})
        three = node("[0,200][200,300]", clickable="true",
                     **{"resource-id": "pkg:id/swSheetPrimary3"})
        flags = atlas.analyze(self.root_with(one, two, three), 1.0, 1080, 2400)
        self.assertEqual(len(flags["multiple_primaries"]), 3)

    def test_verbose_instruction_words(self):
        long_note = " ".join(["Read"] * 30)
        message = node("[0,0][400,400]", text=long_note,
                       **{"resource-id": "pkg:id/swSheetMessage"})
        flags = atlas.analyze(self.root_with(message), 1.0, 1080, 2400)
        self.assertEqual(flags["verbose_instructions"]["words"], 30)
        short = node("[0,0][400,400]", text="Got it",
                     **{"resource-id": "pkg:id/swSheetMessage"})
        self.assertFalse(atlas.analyze(self.root_with(short), 1.0, 1080,
                                       2400)["verbose_instructions"])

    def test_empty_or_broken_hierarchy_has_no_flags(self):
        self.assertTrue(all(not value for value in
                            atlas.analyze(None).values()))


class AdbTest(unittest.TestCase):
    def test_serial_is_passed_as_a_string(self):
        with patch.object(atlas, "SERIAL", "emulator-5554"), \
             patch.object(atlas.subprocess, "run") as run:
            run.return_value.stdout = b"ok"
            run.return_value.stderr = b""
            atlas.adb("shell", "id")
        self.assertEqual(run.call_args.args[0][:3], ["adb", "-s", "emulator-5554"])


class IncidentHelperTest(unittest.TestCase):
    def test_anr_owner_is_named(self):
        self.assertEqual(atlas.classify_anr("ScanWhirl isn't responding"), "ScanWhirl")
        self.assertEqual(atlas.classify_anr("Pixel Launcher isn't responding"),
                         "Pixel Launcher")
        self.assertEqual(atlas.classify_anr(""), "unknown")

    def test_crash_log_is_parsed_and_nearest_screen_attached(self):
        raw = "\n".join([
            "100.0 100 100 E AndroidRuntime: FATAL EXCEPTION: main",
            "100.0 100 100 E AndroidRuntime: Process: com.scanwhirl.app.test, PID: 1",
            "100.0 100 100 E AndroidRuntime: java.lang.IllegalStateException: boom",
            "100.0 100 100 E AndroidRuntime: \tat com.scanwhirl.app.ui.ShellActivity.foo(ShellActivity.kt:1)",
        ])
        screens = [{"slug": "before", "captured_at": 90.0},
                   {"slug": "after", "captured_at": 110.0}]
        findings = atlas.parse_crash_log(raw, screens)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["app"], "com.scanwhirl.app.test")
        self.assertEqual(findings[0]["screen"], "before")
        self.assertIn("IllegalStateException: boom", findings[0]["top"])


class CaptureVerdictTest(unittest.TestCase):
    PNG = b"\x89PNG\r\n\x1a\n" + b"x" * 40

    def test_secure_blank_capture_is_by_design_and_never_a_defect(self):
        for payload, stats in ((b"", {"mean": None, "unique": 0}),
                               (self.PNG, {"mean": 0.1, "unique": 2})):
            verdict = atlas.classify_capture(payload, stats, secure=True, attempts=1)
            self.assertEqual(verdict["status"], "secure-by-design")
            self.assertEqual(verdict["label"], "secure (by design)")
            self.assertIsNone(verdict["failure"])

    def test_empty_non_secure_capture_fails_the_leg(self):
        verdict = atlas.classify_capture(b"", {"mean": None, "unique": 0}, False, 3)
        self.assertEqual(verdict["status"], "capture-failed")
        self.assertEqual(verdict["failure"]["kind"], "empty")
        self.assertEqual(verdict["failure"]["attempts"], 3)

    def test_all_black_non_secure_capture_fails_the_leg(self):
        verdict = atlas.classify_capture(self.PNG, {"mean": 0.5, "unique": 3}, False)
        self.assertEqual(verdict["failure"]["kind"], "black")

    def test_broken_png_is_not_a_false_green(self):
        stats = atlas.png_stats(Path("/no/such/atlas-frame.png"))
        self.assertIsNone(stats["mean"])
        verdict = atlas.classify_capture(self.PNG, stats, False)
        self.assertEqual(verdict["status"], "capture-failed")

    def test_ordinary_frame_is_ok(self):
        verdict = atlas.classify_capture(
            self.PNG, {"mean": 120.0, "unique": 60}, False)
        self.assertEqual(verdict["status"], "ok")
        self.assertIsNone(verdict["failure"])

    def test_screencap_retries_a_transient_race(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "frame.png"
            with patch.object(atlas, "adb", return_value=b"") as adb:
                adb.side_effect = [b"", b"", self.PNG]
                payload, attempts = atlas.read_screencap(path)
            self.assertEqual(payload, self.PNG)
            self.assertEqual(attempts, 3)
            self.assertEqual(path.read_bytes(), self.PNG)

    def test_secure_window_takes_a_single_frame(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "frame.png"
            with patch.object(atlas, "adb", return_value=b"") as adb:
                _, attempts = atlas.read_screencap(path, secure=True)
            self.assertEqual(attempts, 1)
            self.assertEqual(adb.call_count, 1)

    def test_always_secure_activities_are_recognised(self):
        self.assertEqual(atlas.secure_activity(
            "mResumedActivity: ... com.scanwhirl.app.test/"
            "com.scanwhirl.app.ui.vault.VaultActivity t51"), "VaultActivity")
        self.assertEqual(atlas.secure_activity(
            "ActivityRecord{... com.scanwhirl.app.ui.backup.BackupActivity t53}"),
            "BackupActivity")
        self.assertIsNone(atlas.secure_activity(
            "ActivityRecord{... com.scanwhirl.app.ui.shell.ShellActivityCyan t1}"))

    def test_landscape_dimensions_are_swapped(self):
        with patch.object(atlas, "WIDTH", 1080), patch.object(atlas, "HEIGHT", 2400):
            self.assertEqual(atlas.display_dimensions(1), (2400, 1080))
            self.assertEqual(atlas.display_dimensions("3"), (2400, 1080))
            self.assertEqual(atlas.display_dimensions(0), (1080, 2400))

    def test_secure_flag_is_scoped_to_our_windows(self):
        other = "Window #1 com.android.systemui:\n  FLAG_SECURE\n"
        mine = "Window #2 com.scanwhirl.app.test:\n  FLAG_SECURE\n"
        with patch.object(atlas, "adb", return_value=other):
            self.assertFalse(atlas.has_secure_flag())
        with patch.object(atlas, "adb", return_value=mine + other):
            self.assertTrue(atlas.has_secure_flag())


class DeepCoverageTest(unittest.TestCase):
    def test_every_deep_journey_has_a_locale_independent_search_alias(self):
        for tool_id in atlas.DEEP_TOOLS:
            self.assertIn(tool_id, atlas.TOOL_ALIASES)
            self.assertTrue(atlas.TOOL_ALIASES[tool_id].isascii())

    def test_confirm_picker_prefers_add_over_selected_checkbox(self):
        # Harm prevented: the picker's "Selected" tick was tapped instead of
        # "Add (2)", so neither fictional photo was ever imported.
        root = ET.fromstring(
            '<hierarchy><node clickable="true" text="Selected" '
            'resource-id="pkg:id/preview_selected_check_button" bounds="[0,0][10,10]"/>'
            '<node clickable="true" text="Add (2)" '
            'resource-id="pkg:id/preview_add_button" bounds="[0,0][10,10]"/></hierarchy>')
        with patch.object(atlas, "hierarchy", return_value=root), \
             patch.object(atlas, "tap_node", return_value=True) as tap:
            atlas.confirm_picker()
        self.assertEqual(tap.call_args.args[1].get("text"), "Add (2)")

    def test_a_lone_language_chip_is_not_a_hindi_ui(self):
        chip = ET.fromstring(
            '<hierarchy><node resource-id="pkg:id/swLanguageChip" text="A/अ"/>'
            '</hierarchy>')
        self.assertEqual(atlas.hindi_node_count(chip), 0)
        self.assertFalse(atlas.hindi_active(chip))
        real = ET.fromstring(
            '<hierarchy><node resource-id="pkg:id/swLanguageChip" text="A/अ"/>'
            + ''.join('<node text="हिन्दी %d लाइब्रेरी" />' % i for i in range(6))
            + '</hierarchy>')
        self.assertTrue(atlas.hindi_active(real))

    def test_system_permission_prompt_only_finds_deny(self):
        root = ET.fromstring(
            '<hierarchy><node clickable="true" text="ALLOW" package="com.android.packageinstaller"/>'
            '<node clickable="true" text="DENY" package="com.android.packageinstaller"/>'
            '<node clickable="true" text="Deny" package="com.scanwhirl.app.test"/>'
            '</hierarchy>')
        node = atlas.system_deny_node(root)
        self.assertIsNotNone(node)
        self.assertEqual(node.get("text"), "DENY")

    def test_tool_capture_with_focused_search_is_detected(self):
        focused = ET.fromstring(
            '<hierarchy><node resource-id="pkg:id/swToolsSearch" focused="true" '
            'bounds="[0,0][10,10]"/></hierarchy>')
        self.assertTrue(atlas.tool_search_focused(focused))
        opened = ET.fromstring(
            '<hierarchy><node resource-id="pkg:id/toolSetupRun" focused="false" '
            'bounds="[0,0][10,10]"/></hierarchy>')
        self.assertFalse(atlas.tool_search_focused(opened))

    def test_redact_alias_does_not_open_id_card(self):
        # "aadhaar" is also an ID-card keyword; "confidential" is unique.
        self.assertEqual(atlas.TOOL_ALIASES["redact"], "confidential")
        self.assertNotIn("aadhaar", atlas.TOOL_ALIASES["redact"])

    def test_picker_items_finds_media_thumbs_and_fixture_files(self):
        root = ET.fromstring(
            '<hierarchy><node clickable="true" bounds="[0,0][10,10]">'
            '<node resource-id="pkg:id/icon_thumbnail" bounds="[0,0][10,10]"/>'
            '</node><node clickable="true" text="atlas-photo-2.png" '
            'bounds="[0,0][10,10]"/><node clickable="false" text="Recent" '
            'bounds="[0,0][10,10]"/></hierarchy>')
        self.assertEqual(len(atlas.picker_items(root)), 2)


class ScreenshotTest(unittest.TestCase):
    def test_black_frame_is_measurable(self):
        width, height = 32, 32
        rows = bytearray()
        for _ in range(height):
            rows.append(0)
            rows += bytes(width * 3)
        header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)

        def chunk(kind, payload):
            return (struct.pack(">I", len(payload)) + kind + payload
                    + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

        data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                + chunk(b"IDAT", zlib.compress(bytes(rows)))
                + chunk(b"IEND", b""))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "black.png"
            path.write_bytes(data)
            stats = atlas.png_stats(path)
        self.assertEqual(stats["mean"], 0.0)
        self.assertEqual(stats["unique"], 1)


if __name__ == "__main__":
    unittest.main()
