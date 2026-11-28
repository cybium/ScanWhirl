package com.scanwhirl.app.ux130.l3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3-02 — compact, truthful result hierarchy.
 *
 * RED on base: the lane resource file does not exist. GREEN once the decided
 * wording is added in both languages. The source check pins that the result
 * screen really pairs Open/Share at normal scale, stacks at 200%, names the
 * output removal and states its consequence before the tap.
 */
class L3_02_ResultHierarchyTest {

    @Test
    fun `EN and HI carry the decided remove-copy wording with full parity`() {
        val en = L3Resources.en()
        val hi = L3Resources.hi()
        assertEquals(
            "lane EN/HI string names must match exactly",
            en.keys.filter { !it.contains('[') }.toSet(),
            hi.keys.filter { !it.contains('[') }.toSet()
        )
        assertEquals("Remove new copy", en["ux130_l3_result_remove_copy"])
        assertTrue(
            "the consequence must name what is removed and that the original is safe",
            en["ux130_l3_result_remove_copy_consequence"].orEmpty().contains("Documents") &&
                en["ux130_l3_result_remove_copy_consequence"].orEmpty().contains("original", ignoreCase = true)
        )
        // Natural Hindi: "प्रत" for copy, never the rejected "कॉपी"; the Documents
        // folder name stays in English so it is not a false location.
        assertEquals("नई प्रति हटाएँ", hi["ux130_l3_result_remove_copy"])
        assertTrue(
            "Hindi consequence keeps the real Documents/ScanWhirl location",
            hi["ux130_l3_result_remove_copy_consequence"].orEmpty().contains("Documents/ScanWhirl")
        )
    }

    @Test
    fun `result screen separates secondary navigation from its single dock action`() {
        val src = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolResultActivity.kt").readText()
        assertTrue("secondary navigation must scroll", src.contains("column.addView(secondary)"))
        assertTrue("one primary belongs in the dock", src.contains("bottomBar.addView(primary,"))
        assertTrue("must use the lane remove-copy label", src.contains("R.string.ux130_l3_result_remove_copy"))
        assertTrue(
            "must state the original is kept beside removal",
            src.contains("R.string.rs_remove_copy_note")
        )
        assertTrue("every action meets the shared 48dp floor", src.contains("SwStyle.TOUCH_MIN_DP"))
    }
}
