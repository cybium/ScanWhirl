package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * FF-P numeric tests (pure, no Android): the Passport output helper must describe a real
 * 630 x 810 mission JPG example and an exact 35 x 45 mm physical print box on A4.
 * Every expected number is taken from FEATURE_FITNESS.md section 4 (FF-P).
 */
class PassportOutputOptionsTest {

    @Test
    fun mission_photo_example_is_630_x_810_jpeg_not_a_document_or_archive() {
        val spec = PassportOutputOptions.MISSION
        assertEquals(630, spec.widthPx)
        assertEquals(810, spec.heightPx)
        assertEquals("image/jpeg", PassportOutputOptions.MIME_JPEG)
        assertNotEquals(PassportOutputOptions.MIME_PDF, PassportOutputOptions.MIME_JPEG)
        assertFalse(PassportOutputOptions.MIME_JPEG.contains("zip"))
        assertTrue("the mission example must carry a dated source", spec.sourceNote.contains("2026"))
    }

    @Test
    fun a4_sheet_measures_595_276_x_841_890_points() {
        val (width, height) = PassportOutputOptions.sheetSizePt(PassportOutputOptions.Sheet.A4)
        assertEquals(595.276f, width, 0.5f)
        assertEquals(841.890f, height, 0.5f)
    }

    @Test
    fun print_box_measures_99_213_x_127_559_points() {
        val (width, height) = PassportOutputOptions.printBoxPt()
        assertEquals(99.213f, width, 0.5f)
        assertEquals(127.559f, height, 0.5f)
    }

    @Test
    fun ten_by_fifteen_centimetre_sheet_is_not_labelled_a4() {
        val (width, height) = PassportOutputOptions.sheetSizePt(PassportOutputOptions.Sheet.CM_10X15)
        assertEquals(283.465f, width, 0.5f)
        assertEquals(425.197f, height, 0.5f)
        assertNotEquals("A4", PassportOutputOptions.sheetName(PassportOutputOptions.Sheet.CM_10X15))
        assertEquals("A4", PassportOutputOptions.sheetName(PassportOutputOptions.Sheet.A4))
    }

    @Test
    fun print_pdf_carries_a4_mediabox_and_exact_box_geometry() {
        val jpeg = ByteArray(64) { 0x42 }
        val pdf = String(
            PassportOutputOptions.buildPrintPdf(jpeg, 630, 810, PassportOutputOptions.Sheet.A4),
            StandardCharsets.ISO_8859_1
        )
        assertTrue("must be a PDF", pdf.startsWith("%PDF-"))
        assertTrue("A4 MediaBox", pdf.contains("/MediaBox [0 0 595.276 841.890]"))
        assertTrue("35 x 45 mm box in points", pdf.contains("99.213 0 0 127.559"))
    }

    @Test
    fun ten_by_fifteen_pdf_does_not_claim_a4_geometry() {
        val jpeg = ByteArray(64) { 0x42 }
        val pdf = String(
            PassportOutputOptions.buildPrintPdf(jpeg, 413, 531, PassportOutputOptions.Sheet.CM_10X15),
            StandardCharsets.ISO_8859_1
        )
        assertTrue("10 x 15 cm MediaBox", pdf.contains("/MediaBox [0 0 283.465 425.197]"))
        assertFalse("must not claim A4", pdf.contains("/MediaBox [0 0 595.276 841.890]"))
        assertTrue("still exact 35 x 45 mm", pdf.contains("99.213 0 0 127.559"))
    }
}
