package com.scanwhirl.app.ux130.uxg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX lane UXG — source-level contracts for the regrade gaps G4, G5, G6 and G7.
 *
 * Plain JUnit file checks so they compile and fail on the base commit (ed1250a9),
 * matching the style of the existing ux130/l4 source tests.
 */
class UxgSourceTest {

    private fun text(path: String): String {
        val f = File(path)
        return if (f.isFile) f.readText(Charsets.UTF_8) else ""
    }

    private fun check(condition: Boolean, message: String) = assertTrue(message, condition)

    private fun tag(haystack: String, id: String): String {
        val i = haystack.indexOf(id)
        if (i < 0) return ""
        val start = haystack.lastIndexOf('<', i).coerceAtLeast(0)
        val end = haystack.indexOf("/>", i).let { if (it < 0) haystack.length else it + 2 }
        return haystack.substring(start, end)
    }

    /** Text from `start` up to the first `end` after it. */
    private fun between(haystack: String, start: String, end: String): String {
        val a = haystack.indexOf(start)
        if (a < 0) return ""
        val b = haystack.indexOf(end, a)
        return haystack.substring(a, if (b < 0) haystack.length else b)
    }

    // ---------------------------------------------------------------- G4

    @Test
    fun `G4 unsaved discard confirm is danger-styled and separated, Keep editing is safe`() {
        val code = text("src/main/java/com/scanwhirl/app/ui/OcrEditorActivity.kt")
        val confirm = between(code, "ux130_l4_discard_edits_confirm", ".show()")
        check(confirm.isNotEmpty(), "no unsaved-exit discard confirmation sheet found")
        check(confirm.contains("R.color.laser_danger_surface"),
            "destructive Discard button must use the danger surface token, not cyan")
        check(confirm.contains("R.color.laser_danger"),
            "destructive Discard button must use the danger ink token")
        check(confirm.contains("R.color.laser_danger_border"),
            "destructive Discard button must be visually fenced off with the danger border token")
        check(confirm.contains("R.string.ux_uxg_ocr_discard_warning"),
            "confirm sheet must state the irreversible consequence in a lane string")
        check(confirm.contains("R.string.ocr_w5_keep_editing"),
            "Keep editing must remain the safe secondary default")
        check(confirm.contains("R.string.ux130_l4_discard_edits"),
            "the confirm primary must still be the Discard edits action")
    }

    // ---------------------------------------------------------------- G5

    @Test
    fun `G5 Select pages and PDF page indicator expose a Button role`() {
        val doc = text("src/main/res/layout/activity_document_viewer.xml")
        val select = tag(doc, "ux130_l4_select_pages")
        check(select.contains("com.scanwhirl.app.ui.kit.UxgActionTextView"),
            "'Select pages' is still a plain TextView with no Button role")
        check(select.contains("android:minHeight=\"48dp\"") &&
            select.contains("android:minWidth=\"48dp\""),
            "'Select pages' must keep its 48dp target")

        val pdf = text("src/main/res/layout/activity_pdf_viewer.xml")
        val indicator = tag(pdf, "pdfPageIndicator")
        check(indicator.contains("com.scanwhirl.app.ui.kit.UxgActionTextView"),
            "PDF page indicator is still a plain TextView with no Button role")
        check(indicator.contains("android:minHeight=\"48dp\"") &&
            indicator.contains("android:minWidth=\"48dp\""),
            "PDF page indicator must keep its 48dp target")
        check(indicator.contains("android:clickable=\"true\""),
            "PDF page indicator must stay tappable")
        check(indicator.contains("android:contentDescription=\"@string/ux130_l4_go_to_page\""),
            "PDF page indicator must keep its spoken name")

        val role = text("src/main/java/com/scanwhirl/app/ui/kit/UxgActionTextView.kt")
        check(role.contains("Button::class.java.name"),
            "the lane action view must report the Android Button class")
        check(role.contains("onInitializeAccessibilityNodeInfo"),
            "the lane action view must set the Button className on its node info")
    }

    @Test
    fun `G5 stamp Find gets the same Button role as painted actions`() {
        val code = text("src/main/java/com/scanwhirl/app/ui/stamp/StampEditorActivity.kt")
        val find = between(code, "binding.findRedactions.setOnClickListener", "binding.undo.setOnClickListener")
        check(find.contains("info.className = Button::class.java.name"),
            "Find action still has no Button role")
        check(find.contains("isFocusable = true"),
            "Find action is not keyboard focusable")
        check(find.contains("isClickable = true"),
            "Find action is not exposed as clickable")
        check(find.contains("onInitializeAccessibilityNodeInfo"),
            "Find action does not initialise its accessibility node")
    }

    // ---------------------------------------------------------------- G6

    @Test
    fun `G6 viewer metadata is at least 12sp via the lane dimen and wraps`() {
        val xml = text("src/main/res/layout/activity_document_viewer.xml")
        for (id in listOf("docVaultBadge", "docLockBadge", "actionPanelHint")) {
            val block = tag(xml, "@+id/$id")
            check(block.isNotEmpty(), "metadata view $id missing from viewer layout")
            check(block.contains("@dimen/ux_uxg_viewer_meta"),
                "$id must use the lane metadata dimen (>=12sp, 14sp in Hindi), not 11sp (RED on base)")
            assertFalse("$id must wrap, not ellipsize", block.contains("android:ellipsize="))
        }
        val en = text("src/main/res/values/dimens_uxg.xml")
        val hi = text("src/main/res/values-hi/dimens_uxg.xml")
        check(en.contains("<dimen name=\"ux_uxg_viewer_meta\">12sp</dimen>"),
            "EN metadata dimen must be 12sp")
        check(hi.contains("<dimen name=\"ux_uxg_viewer_meta\">14sp</dimen>"),
            "HI metadata dimen must be 14sp")
    }

    // ---------------------------------------------------------------- G7

    @Test
    fun `G7 Discard-unavailable feedback is inline inside the sheet and announced`() {
        val code = text("src/main/java/com/scanwhirl/app/ui/DocumentViewerActivity.kt")
        val branch = between(code, "R.string.ux130_l4_discard_unavailable", "keep.setOnClickListener")
        check(branch.isNotEmpty(), "discard-unavailable branch not found")
        assertFalse("Discard-unavailable must not be a Snackbar behind the modal sheet",
            branch.contains("Snackbar.make"))
        check(branch.contains("discardStatus.visibility = View.VISIBLE"),
            "feedback must reveal the inline status view inside the sheet")
        check(branch.contains("announceForAccessibility") ||
            branch.contains("ACCESSIBILITY_LIVE_REGION"),
            "the inline status must be announced for accessibility")
        // The status view must actually be part of the sheet content, not the activity root.
        val ask = between(code, "private fun askKeepImported()", "private fun discardImportedCopy")
        check(ask.contains("R.id.ux_uxg_discard_status"),
            "the inline status view must be added to the sheet's content container")
    }
}
