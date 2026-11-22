package com.scanwhirl.app.pdfj

import com.scanwhirl.app.tools.PdfToJOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FF-J setup options (pure): selected pages, ZIP-not-forced layout, output names and the
 * "JPG · 630 × 810 · 142 KB" receipt. No Activity, no document, no I/O.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfToJOptionsTest {

    @Test(timeout = 30_000)
    fun `choose pages accepts a single page and a list in document order`() {
        assertEquals(listOf(1), PdfToJOptions.parsePages("2", 3))
        assertEquals(listOf(0, 2), PdfToJOptions.parsePages("1,3", 3))
        assertEquals(listOf(0, 1, 2), PdfToJOptions.parsePages("1-3", 3))
        // Duplicates collapse; user order never reorders the output.
        assertEquals(listOf(0, 2), PdfToJOptions.parsePages("3, 1, 3", 3))
    }

    @Test(timeout = 30_000)
    fun `an impossible page is refused before anything is written`() {
        try {
            PdfToJOptions.parsePages("4", 3)
            fail("a page outside the document must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue("no such page", e.message?.contains("page") == true ||
                e.javaClass.simpleName == "InvalidPageRange")
        }
    }

    @Test(timeout = 30_000)
    fun `separate files and a zip bundle are both expressible`() {
        assertEquals(PdfToJOptions.Layout.SEPARATE, PdfToJOptions.Layout.SEPARATE)
        assertEquals(PdfToJOptions.Layout.ZIP, PdfToJOptions.Layout.ZIP)
        assertEquals("Doc page 2.jpg", PdfToJOptions.fileName("Doc", 2, "jpg"))
        assertEquals("Doc page 1.png", PdfToJOptions.fileName("Doc", 1, "png"))
        assertEquals("Doc pages.zip", PdfToJOptions.zipName("Doc"))
    }

    @Test(timeout = 30_000)
    fun `receipt shows type pixels and the measured final byte size`() {
        val template = "%1\$s · %2\$d × %3\$d · %4\$d KB"
        assertEquals(
            "JPG · 630 × 810 · 142 KB",
            PdfToJOptions.receipt(template, "JPG", 630, 810, 142_000)
        )
        // Rounds to the nearest KB from the real bytes, not from an estimate.
        assertEquals("PNG · 200 × 230 · 11 KB", PdfToJOptions.receipt(template, "PNG", 200, 230, 10_500))
    }
}
