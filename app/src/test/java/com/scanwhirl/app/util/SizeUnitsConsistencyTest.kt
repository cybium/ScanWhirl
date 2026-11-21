package com.scanwhirl.app.util

import com.scanwhirl.app.ui.tools.PdfImportSheets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP1 review P0: every size field and readout must use ONE decimal unit helper. Written before the
 * refactor: on the base SHA the parser and the PDF-import readout still used 1024, so these are
 * named failing assertions rather than a compile error.
 */
class SizeUnitsConsistencyTest {

    @Test
    fun `the hub parser uses decimal KB and MB like the Compress target`() {
        assertEquals(500_000L, ImageTools.parseSizeInput("500 KB"))
        assertEquals(3_000_000L, ImageTools.parseSizeInput("3MB"))
        assertEquals(500_000L, ImageTools.parseSizeInput("500")) // bare number = KB
        assertEquals(2_500_000L, ImageTools.parseSizeInput("2.5 mb"))
        assertEquals(1_000_000L, ImageTools.parseSizeInput("1M"))
    }

    @Test
    fun `the PDF import sheet formats sizes with the same decimal units`() {
        assertEquals("1.0 MB", PdfImportSheets.formatSize(1_000_000L))
        assertEquals("500 KB", PdfImportSheets.formatSize(500_000L))
        assertEquals("1.0 GB", PdfImportSheets.formatSize(1_000_000_000L))
    }

    @Test
    fun `all size call sites delegate to the one shared helper`() {
        fun source(path: String) = java.io.File("src/main/java/com/scanwhirl/app", path).readText()
        assertTrue("ImageTools parser delegates", source("util/ImageTools.kt").contains("SizeUnits.parse"))
        assertTrue("StatusLine formatter delegates", source("util/StatusLine.kt").contains("SizeUnits.format"))
        assertTrue(
            "ExportResultActivity formatter delegates",
            source("ui/ExportResultActivity.kt").contains("SizeUnits.format")
        )
        assertTrue(
            "ResizeFlow parses and displays through the helper",
            source("tools/ResizeFlow.kt").contains("SizeUnits.parse") &&
                source("tools/ResizeFlow.kt").contains("SizeUnits.format")
        )
        assertTrue(
            "PdfImportSheets formatter delegates",
            source("ui/tools/PdfImportSheets.kt").contains("SizeUnits.format")
        )
        assertTrue(
            "ToolSetupActivity uses the shared estimate and the about label",
            source("ui/tools/ToolSetupActivity.kt").contains("SizeUnits.estimateJpegPages") &&
                source("ui/tools/ToolSetupActivity.kt").contains("cp1_est_only")
        )
    }
}
