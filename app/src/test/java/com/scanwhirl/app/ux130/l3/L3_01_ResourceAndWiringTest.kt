package com.scanwhirl.app.ux130.l3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3-01 — real Replace/Keep with durable provenance: resource and wiring contract.
 * RED on base: no lane strings, no journal store, no provenance on the result screen.
 */
class L3_01_ResourceAndWiringTest {

    @Test
    fun `EN and HI carry the Replace Keep and Restore decision wording with parity`() {
        val en = L3Resources.en()
        val hi = L3Resources.hi()
        assertEquals(
            "lane string names match",
            en.keys.filter { !it.contains('[') }.toSet(),
            hi.keys.filter { !it.contains('[') }.toSet()
        )
        assertEquals("Keep the original?", en["ux130_l3_keep_original_title"])
        assertEquals("Keep both", en["ux130_l3_keep_both"])
        assertEquals("Replace original", en["ux130_l3_replace_original"])
        assertEquals(
            "Original moves to Trash. Restore it for 30 days.",
            en["ux130_l3_replace_consequence"]
        )
        assertEquals("Restore original", en["ux130_l3_restore_original"])
        assertEquals("मूल फ़ाइल रखें?", hi["ux130_l3_keep_original_title"])
        assertEquals("दोनों रखें", hi["ux130_l3_keep_both"])
        assertEquals("मूल को बदलें", hi["ux130_l3_replace_original"])
        assertTrue(
            "Hindi consequence must state the 30 day window without the loanword Trash",
            hi["ux130_l3_replace_consequence"].orEmpty().contains("30 दिन") &&
                !hi["ux130_l3_replace_consequence"].orEmpty().contains("ट्रैश")
        )
        assertEquals("मूल फ़ाइल वापस लाएँ", hi["ux130_l3_restore_original"])
    }

    @Test
    fun `result screen wires a durable decision store and a persistent restore route`() {
        val result = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolResultActivity.kt").readText()
        assertTrue("journal store is used", result.contains("ToolResultChoiceStore"))
        assertTrue("decision card title is used", result.contains("R.string.ux130_l3_keep_original_title"))
        assertTrue("Keep both action is used", result.contains("R.string.ux130_l3_keep_both"))
        assertTrue("Replace action is used", result.contains("R.string.ux130_l3_replace_original"))
        assertTrue("consequence is shown", result.contains("R.string.ux130_l3_replace_consequence"))
        assertTrue("persistent restore route exists", result.contains("R.string.ux130_l3_restore_original"))
        assertTrue("output copy removal stays distinct", result.contains("R.id.toolResultUndo"))
    }

    @Test
    fun `only proven one-in one-out transforms carry provenance`() {
        val flow = java.io.File("src/main/java/com/scanwhirl/app/tools/CompressFlow.kt").readText()
        val setup = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt").readText()
        val result = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolResultActivity.kt").readText()
        assertTrue("CompressFlow supplies source id", flow.contains("sourceId ="))
        assertTrue("ToolSetupActivity supplies source id", setup.contains("sourceId ="))
        assertTrue("the result reads source provenance", result.contains("EXTRA_SOURCE_ID"))
        assertTrue("the result reads output provenance", result.contains("EXTRA_OUTPUT_ID"))
        assertTrue("the result reads operation identity", result.contains("EXTRA_OPERATION_ID"))
        assertFalse("external results do not claim provenance", setup.contains("sourceId = docIds"))
    }
}
