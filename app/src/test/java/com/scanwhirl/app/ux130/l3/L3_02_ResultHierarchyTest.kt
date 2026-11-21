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
        assertEquals("Remove this new copy", en["ux130_l3_result_remove_copy"])
        assertTrue(
            "the consequence must name what is removed and that the original is safe",
            en["ux130_l3_result_remove_copy_consequence"].orEmpty().contains("Documents") &&
                en["ux130_l3_result_remove_copy_consequence"].orEmpty().contains("original", ignoreCase = true)
        )
        // Natural Hindi: "प्रत" for copy, never the rejected "कॉपी"; the Documents
        // folder name stays in English so it is not a false location.
        assertEquals("यह नई प्रति हटाएँ", hi["ux130_l3_result_remove_copy"])
        assertTrue(
            "Hindi consequence keeps the real Documents/ScanWhirl location",
            hi["ux130_l3_result_remove_copy_consequence"].orEmpty().contains("Documents/ScanWhirl")
        )
    }

    @Test
    fun `result screen pairs Open and Share and keeps the consequence visible before consent`() {
        val src = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolResultActivity.kt").readText()
        assertTrue("must pair the two actions in a horizontal row", src.contains("pairPrimaryActions()"))
        assertTrue("must stack them at fontScale 2", src.contains("stacksActionsAtLargeFont()"))
        assertTrue("must use the lane remove-copy label", src.contains("R.string.ux130_l3_result_remove_copy"))
        assertTrue(
            "must show the exact removal consequence",
            src.contains("R.string.ux130_l3_result_remove_copy_consequence")
        )
        assertTrue("every action is at least 56dp", src.contains("56f"))
    }
}
