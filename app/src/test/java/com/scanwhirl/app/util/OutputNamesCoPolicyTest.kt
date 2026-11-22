package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Council §5 / lane O acceptance: no verb suffix is generated, the editable field is prefilled
 * with the source stem, names are sanitised and capped, collisions get " (2)", and Hindi is kept.
 * All of this is pure JVM logic, so it runs at 100% font with no Activity involved.
 */
class OutputNamesCoPolicyTest {

    @Test
    fun `prefill is the source stem and never a verb suffix`() {
        assertEquals("Rent receipt", OutputNames.forNewCopy("Rent receipt", emptyList()))
        assertEquals(
            "Rent receipt (2)",
            OutputNames.forNewCopy("Rent receipt", listOf("Rent receipt"))
        )
        assertFalse(OutputNames.forNewCopy("Rent receipt", emptyList()).contains("Compressed"))
        assertFalse(OutputNames.forNewCopy("Rent receipt", emptyList()).contains("Clean"))
    }

    @Test
    fun `a known extension is read-only and left out of the editable name`() {
        assertEquals("Rent receipt", OutputNames.forNewCopy("Rent receipt.pdf", emptyList()))
        assertEquals("photo", OutputNames.forNewCopy("photo.JPG", emptyList()))
        // A date-like dot is NOT an extension and stays part of the name.
        assertEquals("12.05.2025 Rent", OutputNames.forNewCopy("12.05.2025 Rent", emptyList()))
    }

    @Test
    fun `file name characters are sanitised but Hindi survives`() {
        val sanitised = OutputNames.forNewCopy("a/b:c*d?e\"f<g>h|i", emptyList())
        assertEquals("a_b_c_d_e_f_g_h_i", sanitised)
        assertEquals("नई प्रति", OutputNames.forNewCopy("नई प्रति", emptyList()))
        assertFalse(sanitised.any { it in "/\\:*?\"<>|" })
    }

    @Test
    fun `a name is at most 60 code points including its collision number`() {
        val long = "बिल ".repeat(30).trim() // 4 code points each in Hindi, well over 60
        val base = OutputNames.forNewCopy(long, emptyList())
        val result = OutputNames.forNewCopy(long, listOf(base))
        assertTrue("cap: ${result.codePointCount(0, result.length)}", result.codePointCount(0, result.length) <= 60)
        assertTrue("still unique", result.endsWith(" (2)"))
        assertFalse("no verb suffix", result.contains(" - "))
    }

    @Test
    fun `uniqueName sanitises explicit names too`() {
        assertEquals("a_b", OutputNames.uniqueName("a/b", emptyList()))
        assertEquals("a_b (2)", OutputNames.uniqueName("a/b", listOf("a_b")))
    }

    @Test
    fun `split parts keep their meaningful part number and do not stack`() {
        assertEquals("Contract part 1", OutputNames.forSplitPart("Contract", 1, emptyList()))
        assertEquals(
            "Contract part 2",
            OutputNames.forSplitPart("Contract part 1", 2, emptyList())
        )
    }

    @Test
    fun `typed names are normalised before they become a new copy`() {
        assertEquals("Rent", OutputNames.newCopyFromField("  Rent.pdf  ", emptyList()))
        assertEquals("Rent (2)", OutputNames.newCopyFromField("Rent", listOf("Rent")))
        assertEquals("Scan", OutputNames.newCopyFromField("  ", emptyList()))
    }
}
