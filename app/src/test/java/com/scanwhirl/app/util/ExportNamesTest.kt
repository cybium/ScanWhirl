package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5-FILENAMES: the one naming helper behind every share, ZIP and Documents copy. */
class ExportNamesTest {

    private fun cps(s: String) = s.codePointCount(0, s.length)

    /** A string with a lone surrogate does not survive a UTF-8 round trip. */
    private fun assertWellFormed(s: String) =
        assertEquals("no lone surrogate in: $s", s, String(s.toByteArray(Charsets.UTF_8), Charsets.UTF_8))

    // ---- H2-7: only a known extension is an extension ----------------------

    @Test fun `a dotted date is part of the name`() {
        assertEquals("12.05.2025 Rent receipt", ExportNames.baseName("12.05.2025 Rent receipt"))
        assertEquals("3.5 inch scan", ExportNames.baseName("3.5 inch scan"))
        assertEquals("Q3 v2.1 notes", ExportNames.baseName("Q3 v2.1 notes"))
    }

    @Test fun `a known extension is removed once`() {
        assertEquals("Rent receipt", ExportNames.baseName("Rent receipt.pdf"))
        assertEquals("Rent receipt", ExportNames.baseName("Rent receipt.PDF"))
        for (ext in listOf("pdf", "jpg", "jpeg", "png", "docx", "xlsx", "pptx", "txt", "zip")) {
            assertEquals("a.$ext", "a", ExportNames.baseName("a.$ext"))
        }
        assertEquals("only one extension is removed", "scan.pdf", ExportNames.baseName("scan.pdf.pdf"))
    }

    @Test fun `file name builds base plus the requested extension`() {
        assertEquals("12.05.2025 Rent receipt.docx", ExportNames.fileName("12.05.2025 Rent receipt", "docx"))
        assertEquals("Invoice.docx", ExportNames.fileName("Invoice.pdf", "docx"))
        assertEquals("Scan.xlsx", ExportNames.fileName("...", "xlsx"))
        assertEquals("Scan.xlsx", ExportNames.fileName("   ", "xlsx"))
    }

    @Test fun `names that are only dots or start with a dot never become empty or hidden`() {
        assertEquals("Scan", ExportNames.baseName(".."))
        assertEquals("Scan", ExportNames.baseName("."))
        assertEquals("hidden", ExportNames.baseName(".hidden"))
        assertEquals("pdf", ExportNames.baseName(".pdf"))
        assertEquals("Q3", ExportNames.baseName("Q3."))
    }

    // ---- illegal characters ------------------------------------------------

    @Test fun `every illegal file name character is replaced`() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", ExportNames.baseName("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("a_b_c", ExportNames.baseName("a\nb\rc"))
        assertEquals("a_b", ExportNames.baseName("a\u0000b"))
        assertEquals("Scan", ExportNames.baseName("///"))
    }

    @Test fun `sanitizeFileName keeps the known extension chain`() {
        assertEquals("a_b_c.pdf", ExportNames.sanitizeFileName("a/b:c.pdf"))
        assertEquals("Doc.jpg.zip", ExportNames.sanitizeFileName("Doc.jpg.zip"))
        assertEquals("12.05.2025 Rent receipt", ExportNames.sanitizeFileName("12.05.2025 Rent receipt"))
        assertEquals("Document.pdf", ExportNames.sanitizeFileName("////.pdf", "Document"))
    }

    // ---- H2-3: the limit applies to the base only --------------------------

    @Test fun `a 200 character name is cut to 60 code points and keeps its extension`() {
        val name = "x".repeat(200) + ".pdf"
        val out = ExportNames.sanitizeFileName(name)
        assertEquals("x".repeat(60) + ".pdf", out)
        assertEquals(60, cps(ExportNames.baseName("y".repeat(200))))
    }

    @Test fun `a 58 character name with its extension is untouched`() {
        val name = "a".repeat(58) + ".pdf"
        assertEquals(name, ExportNames.sanitizeFileName(name))
    }

    @Test fun `a double extension survives a long name`() {
        assertEquals("z".repeat(60) + ".jpg.zip", ExportNames.sanitizeFileName("z".repeat(90) + ".jpg.zip"))
    }

    @Test fun `trailing space or underscore left by the cut is trimmed`() {
        val name = "a".repeat(59) + " b" // 60th code point is a space
        assertEquals("a".repeat(59), ExportNames.baseName(name))
    }

    // ---- Hindi and emoji: never split a cluster ----------------------------

    @Test fun `a Hindi name is never cut between a letter and its matra`() {
        // 60 letters, then a vowel sign that belongs to the 60th... cut would fall before it.
        val hindi = "क".repeat(60) + "ि" + "क".repeat(10)
        val out = ExportNames.baseName(hindi)
        assertEquals("क".repeat(59), out)
        assertWellFormed(out)
    }

    @Test fun `a Hindi conjunct is not torn at the virama`() {
        // ... क ् ष : cutting after the virama would leave a dangling half letter.
        val hindi = "अ".repeat(58) + "क्ष" + "त".repeat(5) // cps: 58, then क(59) ्(60) ष(61)
        val out = ExportNames.baseName(hindi)
        assertEquals("the whole conjunct is dropped", "अ".repeat(58), out)
        assertWellFormed(out)
    }

    @Test fun `a realistic long Hindi title keeps whole syllables and its extension`() {
        val title = "किराये की रसीद और बिजली का बिल जनवरी दो हज़ार पच्चीस " + "नमस्ते ".repeat(20)
        val out = ExportNames.sanitizeFileName("$title.pdf")
        assertTrue(out.endsWith(".pdf"))
        val base = out.removeSuffix(".pdf")
        assertTrue(cps(base) <= 60)
        assertTrue(title.startsWith(base))
        assertWellFormed(out)
        assertCleanCut(title, base)
    }

    /** The cut must not fall before a combining mark, nor right after a virama / joiner. */
    private fun assertCleanCut(original: String, base: String) {
        val last = base.codePointBefore(base.length)
        assertFalse("ends on a virama or joiner", last == 0x094D || last == 0x200D)
        if (base.length < original.length) {
            val next = original.codePointAt(base.length)
            val type = Character.getType(next).toByte()
            assertFalse(
                "the next character is a combining mark, the cut tore a syllable",
                type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
            )
        }
    }

    @Test fun `an emoji surrogate pair is never split`() {
        val smile = "😀" // one code point, two chars
        val name = "a".repeat(59) + smile + smile + "z"
        val out = ExportNames.baseName(name)
        assertEquals(60, cps(out))
        assertWellFormed(out)
        assertTrue(out.endsWith(smile))
    }

    @Test fun `an emoji ZWJ family sequence is dropped whole when it does not fit`() {
        val family = "👨‍👩‍👧" // man ZWJ woman ZWJ girl
        val out = ExportNames.baseName("a".repeat(59) + family + "tail")
        assertEquals("a".repeat(59), out)
        assertWellFormed(out)
    }

    @Test fun `a flag pair is not split`() {
        val flag = "🇮🇳" // India
        val out = ExportNames.baseName("a".repeat(59) + flag + "x")
        assertWellFormed(out)
        assertTrue("flag kept whole or dropped, never half", out == "a".repeat(59) + flag || out == "a".repeat(59))
    }

    @Test fun `one unbreakable cluster falls back to a hard cut instead of an empty name`() {
        val out = ExportNames.baseName("क" + "ि".repeat(100))
        assertTrue(out.isNotEmpty())
        assertTrue(cps(out) <= 60)
    }

    // ---- duplicates --------------------------------------------------------

    @Test fun `duplicates in one share get numbered before the extension`() {
        val alloc = ExportNames.Allocator()
        assertEquals("Rent.pdf", alloc.file("Rent.pdf"))
        assertEquals("Rent (2).pdf", alloc.file("Rent.pdf"))
        assertEquals("Rent (3).pdf", alloc.file("Rent.pdf"))
        assertEquals("Gas.pdf", alloc.file("Gas.pdf"))
    }

    @Test fun `duplicates are found case-insensitively`() {
        val alloc = ExportNames.Allocator()
        assertEquals("Rent.pdf", alloc.file("Rent.pdf"))
        assertEquals("rent (2).pdf", alloc.file("rent.pdf"))
    }

    @Test fun `numbering never takes a name that was already numbered`() {
        val alloc = ExportNames.Allocator()
        assertEquals("A (2).pdf", alloc.file("A (2).pdf"))
        assertEquals("A.pdf", alloc.file("A.pdf"))
        assertEquals("A (3).pdf", alloc.file("A.pdf"))
    }

    @Test fun `numbering a name without an extension keeps a dotted date whole`() {
        val taken = setOf("12.05.2025 Rent receipt")
        assertEquals(
            "12.05.2025 Rent receipt (2)",
            ExportNames.uniqueName("12.05.2025 Rent receipt") { it in taken }
        )
    }

    @Test fun `folders get their own numbering`() {
        val alloc = ExportNames.Allocator()
        assertEquals("Invoice", alloc.folder("Invoice"))
        assertEquals("Invoice (2)", alloc.folder("Invoice"))
        assertEquals("invoice (3)", alloc.folder("invoice"))
        assertEquals("12.05.2025 Rent", alloc.folder("12.05.2025 Rent"))
        assertEquals("12.05.2025 Rent (2)", alloc.folder("12.05.2025 Rent"))
    }

    @Test fun `known extension helper`() {
        assertTrue(ExportNames.isKnownExtension("PDF"))
        assertFalse(ExportNames.isKnownExtension("2025"))
        assertFalse(ExportNames.isKnownExtension("1"))
        assertTrue(ExportNames.hasKnownExtension("a.pdf"))
        assertFalse(ExportNames.hasKnownExtension("12.05.2025 Rent receipt"))
    }
}
