package com.scanwhirl.app.form1

import com.scanwhirl.app.util.FormPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Lane F (form screen), F1: the picked source is read off the main thread with a hard byte cap.
 * Pure JVM math needs no Android runtime, so these checks stay fast and deterministic.
 */
class FormFLogicTest {

    @Test(timeout = 30_000)
    fun `source read is bounded so one huge pick cannot exhaust memory`() {
        val source = ByteArray(4_096) { (it % 251).toByte() }
        val exact = FormPresets.readBounded(ByteArrayInputStream(source), 4_096L)
        assertTrue("an in-cap source must be returned whole", exact != null)
        assertTrue("bytes must not be reordered", source.contentEquals(exact!!))

        val capped = FormPresets.readBounded(ByteArrayInputStream(source), 1_024L)
        assertNull("an over-cap source must be refused, not partly decoded", capped)

        // The cap is an explicit, documented constant, not an accident of the stream API.
        assertTrue(FormPresets.MAX_SOURCE_BYTES in (8L * 1024 * 1024)..(128L * 1024 * 1024))
    }

    @Test(timeout = 30_000)
    fun `unit conversion keeps the same physical size in both directions`() {
        for (dpi in FormPresets.SUPPORTED_DPI) {
            for (unit in listOf(
                com.scanwhirl.app.util.FormUnit.PX,
                com.scanwhirl.app.util.FormUnit.CM,
                com.scanwhirl.app.util.FormUnit.MM,
                com.scanwhirl.app.util.FormUnit.INCH
            )) {
                val value = FormPresets.fromPixels(413, unit, dpi)
                assertEquals(
                    "413 px must survive a $unit round trip at $dpi DPI",
                    413,
                    FormPresets.toPixels(value, unit, dpi)
                )
            }
        }
        // Changing the DPI changes what a centimetre means in pixels; the helper is the single
        // source of truth the screen uses when it converts the editable fields.
        val cmAt100 = FormPresets.fromPixels(200, com.scanwhirl.app.util.FormUnit.CM, 100)
        val cmAt300 = FormPresets.fromPixels(200, com.scanwhirl.app.util.FormUnit.CM, 300)
        assertTrue(kotlin.math.abs(cmAt100 - cmAt300) > 0.001f)
    }
}
