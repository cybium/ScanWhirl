package com.scanwhirl.app.ux130.l4

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-L4 items 1 and 2: visible page selection with a responsive, safe Discard,
 * and one OCR action scope with an honest unsaved exit.
 *
 * Source-level unit checks (no Robolectric needed): they read the exact owned
 * files and assert the contract, so they compile and fail on the base commit.
 */
class L4ViewerEditorTest {

    private fun text(path: String): String {
        val f = File(path)
        return if (f.isFile) f.readText(Charsets.UTF_8) else ""
    }

    private fun check(condition: Boolean, message: String) = assertTrue(message, condition)

    // ---------------------------------------------------------------- L4-01

    @Test
    fun `L4-01 visible Select pages control exists in the viewer layout`() {
        val xml = text("src/main/res/layout/activity_document_viewer.xml")
        check(xml.contains("@id/ux130_l4_select_pages") || xml.contains("@+id/ux130_l4_select_pages"),
            "Document viewer has no visible 'Select pages' control (lane id ux130_l4_select_pages)")
        val block = substringAround(xml, "ux130_l4_select_pages")
        check(block.contains("android:minHeight=\"48dp\""),
            "'Select pages' control must be at least 48dp tall")
        check(block.contains("android:minWidth=\"48dp\""),
            "'Select pages' control must be at least 48dp wide")
        check(block.contains("android:text=\"@string/ux130_l4_select_pages\""),
            "'Select pages' control must show the lane string")
    }

    @Test
    fun `L4-01 per page Select page accessibility action exists`() {
        val grid = text("src/main/java/com/scanwhirl/app/ui/pagegrid/PageGridView.kt")
        check(grid.contains("ux130_l4_a11y_select_page"),
            "PageGridView has no 'Select page' accessibility action id")
        check(grid.contains("R.string.ux130_l4_select_page"),
            "PageGridView 'Select page' action has no spoken label")
        check(grid.contains("AccessibilityActionCompat"),
            "PageGridView must expose accessibility actions")
        // The drag shortcut must remain (the action is an addition, not a replacement).
        check(grid.contains("onLongPress") && grid.contains("ItemTouchHelper"),
            "the long-press drag shortcut was removed")
    }

    @Test
    fun `L4-01 draft targets are 48dp and Discard failure is never silent`() {
        val activity = text("src/main/java/com/scanwhirl/app/ui/DocumentViewerActivity.kt")
        check(activity.contains("view.minHeight = dp(48f)"),
            "styleDraftBar still uses a sub-48dp minimum (expected dp(48f))")
        check(activity.contains("R.string.ux130_l4_discard_unavailable"),
            "a failed safelyPlaced() still ignores the tap with no feedback")
        check(activity.contains("Snackbar.make"),
            "Discard placement failure must be reported in a Snackbar")
    }

    // ---------------------------------------------------------------- L4-02

    @Test
    fun `L4-02 all-pages actions are grouped under one menu and hidden on one page`() {
        val menu = text("src/main/res/menu/menu_ocr_editor.xml")
        check(menu.contains("ux130_l4_all_pages"),
            "OCR menu has no single 'All pages' submenu")
        val copyIds = Regex("android:id=\"@(?:\\+)?id/ocr_copy_all\"").findAll(menu).count()
        check(copyIds == 1, "expected exactly one Copy-all route in the menu, found $copyIds")

        val activity = text("src/main/java/com/scanwhirl/app/ui/OcrEditorActivity.kt")
        check(activity.contains("ux130_l4_all_pages"),
            "OCR activity does not manage the All pages submenu")
        check(activity.contains("findItem"),
            "OCR activity does not hide All pages on a one-page document")
        check(activity.contains("pageTexts.size"),
            "OCR activity does not gate all-pages actions by page count")
    }

    @Test
    fun `L4-02 Finish editing replaces Done and unsaved exit separates Discard edits`() {
        val activity = text("src/main/java/com/scanwhirl/app/ui/OcrEditorActivity.kt")
        check(activity.contains("R.string.ux130_l4_finish_editing"),
            "the edit action still uses the forbidden 'Done' concept")
        check(activity.contains("LegacyTokenText(decided") &&
            activity.contains("override fun toString(): String = token"),
            "the legacy 'Done' token must be a compatibility label, not the visible text")
        check(activity.contains("R.string.ocr_w5_save_txt_and_close") ||
            activity.contains("R.string.ux130_l4_save_as_txt"),
            "unsaved exit has no explicit .txt save route")
        check(activity.contains("R.string.ux130_l4_discard_edits"),
            "unsaved exit has no separated 'Discard edits' route")
        check(activity.contains("R.string.ux130_l4_discard_edits_confirm"),
            "'Discard edits' has no confirmation step")
    }

    @Test
    fun `L4-02 baseline metadata is at least 12sp and reflows`() {
        val xml = text("src/main/res/layout/activity_ocr_editor.xml")
        check(!xml.contains("11.5sp") && !xml.contains("android:textSize=\"11"),
            "OCR layout still has sub-12sp baseline metadata")
        val foot = substringAround(xml, "ocrFootNote")
        check(foot.contains("android:textSize=\"12sp\""),
            "ocrFootNote must be at least 12sp")
        check(!foot.contains("android:ellipsize=\"end\""),
            "ocrFootNote must reflow, not ellipsize")
    }

    private fun substringAround(haystack: String, needle: String): String {
        val i = haystack.indexOf(needle)
        if (i < 0) return ""
        val start = haystack.lastIndexOf('<', i).coerceAtLeast(0)
        val end = haystack.indexOf("/>", i).let { if (it < 0) haystack.length else it + 2 }
        return haystack.substring(start, end)
    }
}
