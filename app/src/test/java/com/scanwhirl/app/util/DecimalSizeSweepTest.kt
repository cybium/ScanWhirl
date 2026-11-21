package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP1 re-review P0: the three remaining user-visible binary MB numbers must go through
 * [SizeUnits], while the internal budget constants stay exactly as they are.
 */
class DecimalSizeSweepTest {

    private fun source(path: String) = java.io.File("src/main/java/com/scanwhirl/app", path).readText()

    @Test
    fun `the PDF import no-space sheet formats its MB numbers decimally`() {
        val refuse = source("ui/tools/PdfImportSheets.kt")
            .substringAfter("fun refuse(")
            .substringBefore("fun progressText(")
        assertTrue("the sheet calls the shared formatter: $refuse", refuse.contains("SizeUnits.format"))
        assertFalse("no binary MB arithmetic left: $refuse", refuse.contains("1024 * 1024"))
    }

    @Test
    fun `the repository no-space exception formats its MB numbers decimally`() {
        val block = source("store/ScanRepository.kt")
            .substringAfter("R.string.tools_import_no_space,")
            .substringBefore("pages = totalPages")
        assertTrue("the exception calls the shared formatter: $block", block.contains("SizeUnits.format"))
        assertFalse("no binary MB arithmetic left: $block", block.contains("1024 * 1024"))
    }

    @Test
    fun `both PDF too-large messages use the decimal formatter`() {
        val pdfImport = source("util/PdfImport.kt")
        assertEquals(
            "two user-visible too-large messages",
            2,
            pdfImport.windowed("SizeUnits.format".length) { it == "SizeUnits.format" }.count { it }
        )
        assertFalse(
            "no binary MB arithmetic left in the user-visible messages",
            pdfImport.contains("maxBytes / (1024 * 1024)")
        )
    }

    @Test
    fun `the internal import budget constants are unchanged`() {
        // Guards the "only the displayed numbers change" requirement.
        val pdfImport = source("util/PdfImport.kt")
        assertTrue(pdfImport.contains("256L * 1024 * 1024"))
        assertTrue(pdfImport.contains("16L * 1024 * 1024"))
    }
}
