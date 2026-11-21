package com.scanwhirl.app.ux130.l3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3-04 — accessible external merge and batch options.
 *
 * RED on base: no lane move actions/position string, no lane ids, 40dp merge
 * controls, drag-only reorder. GREEN when 48dp controls and named earlier/later
 * actions exist and the batch summary scrolls inside a bounded height.
 */
class L3_04_MergeBatchAccessibilityTest {

    @Test
    fun `EN and HI carry the merge reorder wording with parity`() {
        val en = L3Resources.en()
        val hi = L3Resources.hi()
        assertEquals(
            "lane string names match",
            en.keys.filter { !it.contains('[') }.toSet(),
            hi.keys.filter { !it.contains('[') }.toSet()
        )
        assertEquals("Move earlier", en["ux130_l3_merge_move_earlier"])
        assertEquals("Move later", en["ux130_l3_merge_move_later"])
        assertEquals("File %1\$d of %2\$d", en["ux130_l3_merge_file_position"])
        assertEquals("पहले ले जाएँ", hi["ux130_l3_merge_move_earlier"])
        assertEquals("बाद में ले जाएँ", hi["ux130_l3_merge_move_later"])
        assertTrue(
            "Hindi keeps both format arguments",
            hi["ux130_l3_merge_file_position"].orEmpty().contains("%1\$d") &&
                hi["ux130_l3_merge_file_position"].orEmpty().contains("%2\$d")
        )
    }

    @Test
    fun `lane ids exist for the two named merge actions`() {
        val ids = L3Resources.ids()
        assertTrue("missing move-earlier id", "ux130_l3_merge_move_earlier" in ids)
        assertTrue("missing move-later id", "ux130_l3_merge_move_later" in ids)
    }

    @Test
    fun `merge item controls are 48dp and reorder has named actions`() {
        val item = java.io.File("src/main/res/layout/item_merge_pdf.xml").readText()
        assertTrue("drag handle must be 48dp", item.contains("android:layout_width=\"48dp\""))
        assertTrue("preview and remove must be 48dp", item.contains("android:layout_height=\"48dp\""))
        assertTrue("move earlier button exists", item.contains("@+id/ux130_l3_merge_move_earlier"))
        assertTrue("move later button exists", item.contains("@+id/ux130_l3_merge_move_later"))
        assertTrue("position label exists", item.contains("@+id/ux130_l3_merge_position"))

        val activity = java.io.File("src/main/res/layout/activity_merge_pdf.xml").readText()
        assertTrue("dock buttons clear 48dp", activity.contains("android:minHeight=\"48dp\""))

        val code = java.io.File("src/main/java/com/scanwhirl/app/ui/MergePdfActivity.kt").readText()
        assertTrue("earlier action is wired", code.contains("ux130_l3_merge_move_earlier"))
        assertTrue("later action is wired", code.contains("ux130_l3_merge_move_later"))
        assertTrue("position is spoken", code.contains("ux130_l3_merge_file_position"))
    }

    @Test
    fun `batch chips wrap at 48dp with selected state and a bounded summary`() {
        val code = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/BatchActivity.kt").readText()
        assertTrue("chips are 48dp", code.contains("SwStyle.dp(this@BatchActivity, 48f)"))
        assertTrue("chips expose checked state", code.contains("AccessibilityNodeInfo"))
        assertTrue("summary scrolls independently", code.contains("NestedScrollView"))
        assertTrue("summary is capped at half the screen", code.contains("SUMMARY_MAX_HEIGHT"))
        assertTrue("Share all stays in the sheet footer", code.contains("R.string.tools_batch_share_all"))
    }
}
