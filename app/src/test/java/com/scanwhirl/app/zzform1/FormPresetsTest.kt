package com.scanwhirl.app.zzform1

import com.scanwhirl.app.util.FormPresets
import com.scanwhirl.app.util.FormUnit
import com.scanwhirl.app.util.SizeUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FORM1: preset math is pure and testable. The bands are decimal KB (SizeUnits), and the
 * editable unit fields convert through one shared helper so the live pixel readout cannot
 * disagree with the encoder.
 */
class FormPresetsTest {

    @Test
    fun `preset bands are decimal KB not binary KB`() {
        assertEquals(1_000L, SizeUnits.BYTES_PER_KB)
        assertEquals(20_000L, FormPresets.PHOTO.minBytes)
        assertEquals(50_000L, FormPresets.PHOTO.maxBytes)
        assertEquals(10_000L, FormPresets.SIGNATURE.minBytes)
        assertEquals(20_000L, FormPresets.SIGNATURE.maxBytes)
        assertEquals(20_000L, FormPresets.THUMB.minBytes)
        assertEquals(50_000L, FormPresets.THUMB.maxBytes)
        assertEquals(50_000L, FormPresets.DECLARATION.minBytes)
        assertEquals(100_000L, FormPresets.DECLARATION.maxBytes)
    }

    @Test
    fun `preset examples carry the researched dimensions and DPI`() {
        assertEquals(200, FormPresets.PHOTO.widthPx)
        assertEquals(230, FormPresets.PHOTO.heightPx)
        assertEquals(140, FormPresets.SIGNATURE.widthPx)
        assertEquals(60, FormPresets.SIGNATURE.heightPx)
        assertTrue(FormPresets.SIGNATURE.signatureCleanup)
        assertEquals(240, FormPresets.THUMB.widthPx)
        assertEquals(240, FormPresets.THUMB.heightPx)
        assertEquals(200, FormPresets.THUMB.dpi)
        assertEquals(800, FormPresets.DECLARATION.widthPx)
        assertEquals(400, FormPresets.DECLARATION.heightPx)
        assertEquals(200, FormPresets.DECLARATION.dpi)
        assertEquals(200f / 230f, FormPresets.PHOTO.aspect, 0.0001f)
    }

    @Test
    fun `physical units convert to pixels at 100 200 and 300 dpi`() {
        assertEquals(100, FormPresets.toPixels(1f, FormUnit.INCH, 100))
        assertEquals(200, FormPresets.toPixels(1f, FormUnit.INCH, 200))
        assertEquals(300, FormPresets.toPixels(1f, FormUnit.INCH, 300))
        assertEquals(100, FormPresets.toPixels(2.54f, FormUnit.CM, 100))
        // 25.4 mm is one inch, so it is 100 px at 100 DPI (not 254).
        assertEquals(100, FormPresets.toPixels(25.4f, FormUnit.MM, 100))
        // 3.5 x 4.5 cm at 300 DPI is the frequently quoted 413 x 531 px example.
        assertEquals(413, FormPresets.toPixels(3.5f, FormUnit.CM, 300))
        assertEquals(531, FormPresets.toPixels(4.5f, FormUnit.CM, 300))
        // Pixel entry is unchanged by DPI: the pixels are the ground truth for a form.
        assertEquals(200, FormPresets.toPixels(200f, FormUnit.PX, 300))
    }

    @Test
    fun `pixels round trip through the chosen unit`() {
        for (dpi in FormPresets.SUPPORTED_DPI) {
            for (unit in listOf(FormUnit.PX, FormUnit.CM, FormUnit.MM, FormUnit.INCH)) {
                val px = 413
                val value = FormPresets.fromPixels(px, unit, dpi)
                assertEquals(px.toFloat(), FormPresets.toPixels(value, unit, dpi).toFloat(), 1.5f)
            }
        }
    }

    @Test
    fun `sanity rejects impossible specs and unknown ids fall back to custom`() {
        assertTrue(FormPresets.isSane(FormPresets.CUSTOM))
        assertFalse(FormPresets.isSane(FormPresets.CUSTOM.copy(widthPx = 0)))
        assertFalse(FormPresets.isSane(FormPresets.CUSTOM.copy(dpi = 150)))
        assertFalse(FormPresets.isSane(FormPresets.CUSTOM.copy(minKb = 50, maxKb = 20)))
        assertEquals(FormPresets.CUSTOM.id, FormPresets.byId("not-a-preset").id)
        assertEquals(FormPresets.SIGNATURE.id, FormPresets.byId("signature").id)
    }
}
