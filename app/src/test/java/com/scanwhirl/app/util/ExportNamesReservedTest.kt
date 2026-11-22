package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review BLOCK of cc0866d4, P2: Windows device names cannot be exported, because the resulting
 * PDF cannot be copied to a PC. Sanitising appends "_" case-insensitively, including trailing
 * dots/spaces (which trimJunk removes before the check).
 */
class ExportNamesReservedTest {

    @Test
    fun `every Windows reserved base is escaped`() {
        val reserved = listOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )
        reserved.forEach { name ->
            assertEquals("$name must be escaped", "${name}_", ExportNames.sanitizeBase(name))
            assertEquals(
                "$name in lower case must be escaped",
                "${name.lowercase()}_",
                ExportNames.sanitizeBase(name.lowercase())
            )
        }
    }

    @Test
    fun `trailing dots and spaces are handled before the reserved check`() {
        assertEquals("CON_", ExportNames.sanitizeBase("CON.  "))
        assertEquals("con_", ExportNames.sanitizeBase(" con. "))
        assertEquals("AUX_", ExportNames.sanitizeBase("AUX."))
    }

    @Test
    fun `lookalikes and normal names are untouched`() {
        assertEquals("CONSOLE", ExportNames.sanitizeBase("CONSOLE"))
        assertEquals("COM10", ExportNames.sanitizeBase("COM10"))
        assertEquals("NULLED", ExportNames.sanitizeBase("NULLED"))
        assertEquals("Rent receipt", ExportNames.sanitizeBase("Rent receipt"))
        assertEquals("12.05.2025 Rent", ExportNames.sanitizeBase("12.05.2025 Rent"))
    }

    @Test
    fun `the escaped name still fits the 60 code point cap`() {
        val escaped = ExportNames.sanitizeBase("CON")
        assertTrue(escaped.codePointCount(0, escaped.length) <= ExportNames.MAX_BASE_CODE_POINTS)
        assertEquals("CON_", escaped)
    }

    @Test
    fun `a reserved name with an extension is escaped in the base only`() {
        assertEquals("CON_.pdf", ExportNames.sanitizeFileName("CON.pdf"))
    }
}
