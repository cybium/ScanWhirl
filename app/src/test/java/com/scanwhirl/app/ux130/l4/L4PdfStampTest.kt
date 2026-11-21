package com.scanwhirl.app.ux130.l4

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-L4 items 3 and 4: reach any PDF page with named zoom, and give the stamp
 * editor real Button roles with readable reader metadata.
 *
 * Source-level unit checks so they compile and fail on the base commit.
 */
class L4PdfStampTest {

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

    // ---------------------------------------------------------------- L4-03

    @Test
    fun `L4-03 page indicator is a named 48dp Go to page control`() {
        val xml = text("src/main/res/layout/activity_pdf_viewer.xml")
        val indicator = tag(xml, "pdfPageIndicator")
        check(indicator.contains("android:minWidth=\"48dp\""),
            "PDF page indicator must be at least 48dp wide")
        check(indicator.contains("android:minHeight=\"48dp\""),
            "PDF page indicator must be at least 48dp tall")
        check(indicator.contains("android:clickable=\"true\""),
            "PDF page indicator must be tappable")
        check(indicator.contains("android:contentDescription=\"@string/ux130_l4_go_to_page\""),
            "PDF page indicator must announce 'Go to page'")
        val close = tag(xml, "btnZoomClose")
        check(close.contains("@string/ux130_l4_close_zoom"),
            "zoom close button must announce 'Close zoom'")
    }

    @Test
    fun `L4-03 page preview and zoom actions are spoken`() {
        val item = text("src/main/res/layout/item_pdf_page.xml")
        check(item.contains("@string/ux130_l4_zoom_page"),
            "tappable PDF page preview has no spoken label (still @null)")
        val activity = text("src/main/java/com/scanwhirl/app/ui/PdfViewerActivity.kt")
        check(activity.contains("R.string.ux130_l4_page_zoom"),
            "page preview is not labelled 'Page X of Y. Zoom page.'")
        check(activity.contains("R.string.ux130_l4_zoom_page"),
            "no named 'Zoom page' accessibility action")
        check(activity.contains("promptGoToPage"),
            "no 'Go to page' dialog implementation")
        check(activity.contains("R.string.ux130_l4_go_to_page_invalid"),
            "Go to page input has no localized validation")
    }

    @Test
    fun `L4-03 reader More holds nonduplicated reader actions`() {
        val activity = text("src/main/java/com/scanwhirl/app/ui/PdfViewerActivity.kt")
        val more = activity.substring(
            activity.indexOf("private fun showReaderMore()"),
            (activity.indexOf("private fun toggleStrip()").let { if (it < 0) activity.length else it })
        )
        check(more.contains("ux130_l4_go_to_page"),
            "reader More does not offer 'Go to page'")
        check(!more.contains("R.string.reader_find"),
            "reader More still duplicates the toolbar Find action")
        check(more.contains("vaultMode") && more.contains("secure()"),
            "Vault secure restrictions must be preserved in reader More")
    }

    // ---------------------------------------------------------------- L4-04

    @Test
    fun `L4-04 stamp actions have Button role and focus`() {
        val activity = text("src/main/java/com/scanwhirl/app/ui/stamp/StampEditorActivity.kt")
        check(activity.contains("info.className = Button::class.java.name"),
            "stamp actions are still plain TextViews to accessibility")
        check(activity.contains("view.isFocusable = true"),
            "stamp actions are not focusable")
        val xml = text("src/main/res/layout/activity_stamp_editor.xml")
        for (id in listOf("deleteMark", "findRedactions", "undo", "redo", "saveCopy")) {
            val t = tag(xml, "@+id/$id")
            check(t.contains("android:minHeight="),
                "stamp action $id has no >=48dp minimum height")
        }
    }

    @Test
    fun `L4-04 reader dock metadata is at least 12sp`() {
        val xml = text("src/main/res/layout/view_reader_dock.xml")
        check(!xml.contains("11.5sp") && !xml.contains("android:textSize=\"11"),
            "reader dock metadata is still below 12sp")
        check(xml.contains("FlowRowLayout"),
            "reader dock must keep its wrapping FlowRowLayout")
    }
}
