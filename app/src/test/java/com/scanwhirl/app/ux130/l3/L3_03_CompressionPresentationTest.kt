package com.scanwhirl.app.ux130.l3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3-03 — one compression vocabulary, no truncation, unchanged engines.
 *
 * RED on base: no shared presentation source and no lane "Aim for a size" /
 * "From phone storage" strings. GREEN once both setup surfaces consume the
 * single ordered label table and rows reflow instead of clipping.
 */
class L3_03_CompressionPresentationTest {

    @Test
    fun `EN and HI carry the two new compression labels with parity`() {
        val en = L3Resources.en()
        val hi = L3Resources.hi()
        assertEquals(en.keys.filter { !it.contains('[') }.toSet(), hi.keys.filter { !it.contains('[') }.toSet())
        assertEquals("Aim for a size", en["ux130_l3_compress_aim_size"])
        assertEquals("From phone storage", en["ux130_l3_compress_from_phone_storage"])
        assertEquals("आकार तय करें", hi["ux130_l3_compress_aim_size"])
        assertEquals("फ़ोन के संग्रहण से", hi["ux130_l3_compress_from_phone_storage"])
    }

    @Test
    fun `a single shared table owns the four ordered compression labels`() {
        val present = java.io.File("src/main/java/com/scanwhirl/app/tools/CompressionPresentation.kt")
        assertTrue("shared compression presentation is missing", present.isFile)
        val table = present.readText()
        for (res in listOf("compress_level_low", "compress_level_medium", "compress_level_high", "compress_level_ultra")) {
            assertTrue("level $res must come from the shared table", table.contains("R.string.$res"))
        }
        assertTrue("the shared table must own the aim label", table.contains("ux130_l3_compress_aim_size"))
        assertTrue("the shared table must own the storage label", table.contains("ux130_l3_compress_from_phone_storage"))
    }

    @Test
    fun `both setup surfaces consume the shared table`() {
        val setup = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt").readText()
        val flow = java.io.File("src/main/java/com/scanwhirl/app/tools/CompressFlow.kt").readText()
        assertTrue("ToolSetupActivity must use the shared labels", setup.contains("CompressionPresentation.levelLabels"))
        assertTrue("CompressFlow must use the shared labels", flow.contains("CompressionPresentation.levelLabels"))
        val levelLabelBlock = setup.substringAfter("text = getString(level.titleRes)")
            .substringBefore("gravity = Gravity.CENTER_VERTICAL")
        assertTrue("the level label must not truncate", !levelLabelBlock.contains("ellipsize"))
        assertTrue("level labels may wrap", levelLabelBlock.contains("maxLines = Int.MAX_VALUE"))
        assertTrue("an explicit Aim for a size label is shown", setup.contains("CompressionPresentation.aimForSize"))
        assertTrue("one combined picker offers phone storage", flow.contains("CompressionPresentation.fromPhoneStorage"))
        assertFalse("the old two-step source sheet is gone", flow.contains("R.string.compress_source_scan"))
        assertTrue("rows keep a 48dp minimum", setup.contains("48f"))
        assertFalse("engine parameters are untouched: quality 80", !flow.contains("80, 3000"))
    }
}
