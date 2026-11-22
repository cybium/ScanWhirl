package com.scanwhirl.app.form1

import com.scanwhirl.app.util.FormPresets
import com.scanwhirl.app.util.FormUnit
import com.scanwhirl.app.util.SizeUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FORM1: preset math is pure and testable. Form bands use binary KB (the exam portal's unit)
 * while the rest of the app keeps decimal SizeUnits; the editable unit fields convert through one
 * shared helper so the live pixel readout cannot
 * disagree with the encoder.
 */
class FormPresetsTest {

    @Test
    fun `form preset bands are binary KB matching the portal verifier`() {
        // FX2 old->new: form bands were decimal 20,000/50,000 bytes (1 KB = 1000) but the phone
        // portal verifier counts 1024; they are now 20,480/51,200 so "20 KB" really means 20 KiB.
        assertEquals(1_000L, SizeUnits.BYTES_PER_KB) // the rest of the app stays decimal
        assertEquals(1_024L, FormPresets.BYTES_PER_KB)
        assertEquals(20L * 1024, FormPresets.PHOTO.minBytes)
        assertEquals(50L * 1024, FormPresets.PHOTO.maxBytes)
        assertEquals(10L * 1024, FormPresets.SIGNATURE.minBytes)
        assertEquals(20L * 1024, FormPresets.SIGNATURE.maxBytes)
        assertEquals(20L * 1024, FormPresets.THUMB.minBytes)
        assertEquals(50L * 1024, FormPresets.THUMB.maxBytes)
        assertEquals(50L * 1024, FormPresets.DECLARATION.minBytes)
        assertEquals(100L * 1024, FormPresets.DECLARATION.maxBytes)
    }

    @Test
    fun `preset examples carry the researched dimensions and DPI`() {
        assertEquals(200, FormPresets.PHOTO.widthPx)
        assertEquals(230, FormPresets.PHOTO.heightPx)
        assertEquals(140, FormPresets.SIGNATURE.widthPx)
        assertEquals(60, FormPresets.SIGNATURE.heightPx)
        // FX2 old->new: cleanup was ON for the signature preset and destroyed the ink on the
        // phone; it is now an explicit opt-in toggle, OFF by default.
        assertFalse(FormPresets.SIGNATURE.signatureCleanup)
        assertEquals(240, FormPresets.THUMB.widthPx)
        assertEquals(240, FormPresets.THUMB.heightPx)
        assertEquals(200, FormPresets.THUMB.dpi)
        assertEquals(800, FormPresets.DECLARATION.widthPx)
        assertEquals(400, FormPresets.DECLARATION.heightPx)
        assertEquals(200, FormPresets.DECLARATION.dpi)
        assertEquals(200f / 230f, FormPresets.PHOTO.aspect, 0.0001f)
    }

    @Test
    fun `exam presets carry the notice KB bands and suggested pixels`() {
        // FORMPIX: the KB band comes from the public notice; pixels are third-party suggestions.
        val ugPhoto = FormPresets.NEET_UG_PHOTO
        assertEquals("neet_ug_photo", ugPhoto.id)
        assertEquals(350, ugPhoto.widthPx)
        assertEquals(450, ugPhoto.heightPx)
        assertEquals(10, ugPhoto.minKb)
        assertEquals(200, ugPhoto.maxKb)
        assertEquals(10L * 1024, ugPhoto.minBytes)
        assertEquals(200L * 1024, ugPhoto.maxBytes)

        val ugSign = FormPresets.NEET_UG_SIGNATURE
        assertEquals(400, ugSign.widthPx)
        assertEquals(150, ugSign.heightPx)
        assertEquals(4, ugSign.minKb)
        assertEquals(30, ugSign.maxKb)
        assertEquals(400, FormPresets.NEET_UG_THUMB.widthPx)
        assertEquals(150, FormPresets.NEET_UG_THUMB.heightPx)
        assertEquals(4, FormPresets.NEET_UG_THUMB.minKb)
        assertEquals(30, FormPresets.NEET_UG_THUMB.maxKb)

        val pgPhoto = FormPresets.NEET_PG_PHOTO
        assertEquals(350, pgPhoto.widthPx)
        assertEquals(450, pgPhoto.heightPx)
        assertEquals(5, pgPhoto.minKb)
        assertEquals(80, pgPhoto.maxKb)
        assertEquals(276, FormPresets.NEET_PG_SIGNATURE.widthPx)
        assertEquals(118, FormPresets.NEET_PG_SIGNATURE.heightPx)
        assertEquals(20, FormPresets.NEET_PG_SIGNATURE.minKb)
        assertEquals(80, FormPresets.NEET_PG_SIGNATURE.maxKb)
        assertEquals(276, FormPresets.NEET_PG_THUMB.widthPx)
        assertEquals(118, FormPresets.NEET_PG_THUMB.heightPx)
        assertEquals(5, FormPresets.NEET_PG_THUMB.minKb)
        assertEquals(80, FormPresets.NEET_PG_THUMB.maxKb)

        assertEquals(6, FormPresets.EXAM.size)
        for (spec in FormPresets.EXAM) {
            assertTrue("exam preset must be sane: ${spec.id}", FormPresets.isSane(spec))
            assertTrue("exam preset must be labelled exam: ${spec.id}", FormPresets.isExam(spec.id))
            assertEquals(spec.id, FormPresets.byId(spec.id).id)
            assertFalse("cleanup must never be auto-on: ${spec.id}", spec.signatureCleanup)
        }
        assertEquals("neet_ug_photo", FormPresets.outputRole(FormPresets.NEET_UG_PHOTO.id))
        assertEquals("signature", FormPresets.outputRole(FormPresets.NEET_PG_SIGNATURE.id))
        assertEquals("thumb", FormPresets.outputRole(FormPresets.NEET_UG_THUMB.id))
        assertEquals("declaration", FormPresets.outputRole(FormPresets.DECLARATION.id))
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
    fun `form size cap refuses poster sized targets`() {
        assertTrue(FormPresets.fitsFormLimit(200, 230))
        // 4000 x 3000 is exactly 12 MP and is the largest honest form-photo target.
        assertTrue(FormPresets.fitsFormLimit(4000, 3000))
        assertFalse(FormPresets.fitsFormLimit(12000, 12000))
        assertFalse(FormPresets.fitsFormLimit(4001, 100))
        assertFalse(FormPresets.fitsFormLimit(4000, 4000))
        assertFalse(FormPresets.isSane(FormPresets.CUSTOM.copy(widthPx = 12000, heightPx = 12000)))
        assertTrue(FormPresets.isSane(FormPresets.DECLARATION))
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
