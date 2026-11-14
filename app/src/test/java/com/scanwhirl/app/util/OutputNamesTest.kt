package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2 UI: 03_tools_hub — one naming rule for tool outputs: no stacked suffixes and a unique name.
 * `Document 1 (page 1) (compressed) (signed) (watermarked)` becomes `Document 1 - Watermarked`.
 */
class OutputNamesTest {

    @Test
    fun `a plain document gets the tool label`() {
        assertEquals("Document 1 - Compressed", OutputNames.forTool("Document 1", "Compressed", emptyList()))
        assertEquals("Document 1 - Signed", OutputNames.forTool("Document 1", "Signed", emptyList()))
        assertEquals("Document 1 - Pages 2-3", OutputNames.forTool("Document 1", "Pages 2-3", emptyList()))
    }

    @Test
    fun `old suffixes never stack`() {
        assertEquals(
            "Document 1 - Watermarked",
            OutputNames.forTool(
                "Document 1 (page 1) (compressed) (signed)", "Watermarked", emptyList()
            )
        )
        assertEquals(
            "Document 1 - Signed",
            OutputNames.forTool("Document 1 - Compressed", "Signed", emptyList())
        )
        assertEquals(
            "Document 1 - Compressed",
            OutputNames.forTool("Document 1 (pages 2-3)", "Compressed", emptyList())
        )
    }

    @Test
    fun `an existing name gets the next number`() {
        assertEquals(
            "Document 1 - Signed (2)",
            OutputNames.forTool("Document 1", "Signed", listOf("Document 1 - Signed"))
        )
        assertEquals(
            "Document 1 - Signed (4)",
            OutputNames.forTool(
                "Document 1", "Signed",
                listOf("Document 1 - Signed", "Document 1 - Signed (2)", "Document 1 - Signed (3)")
            )
        )
        // Case differences are the same name.
        assertEquals(
            "Document 1 - Signed (2)",
            OutputNames.forTool("Document 1", "Signed", listOf("document 1 - signed"))
        )
    }

    @Test
    fun `hindi names and labels are preserved`() {
        assertEquals(
            "दस्तावेज़ 1 - हस्ताक्षरित",
            OutputNames.forTool("दस्तावेज़ 1", "हस्ताक्षरित", emptyList())
        )
        assertEquals(
            "दस्तावेज़ 1 - हस्ताक्षरित (2)",
            OutputNames.forTool("दस्तावेज़ 1", "हस्ताक्षरित", listOf("दस्तावेज़ 1 - हस्ताक्षरित"))
        )
        // A Hindi suffix from an earlier tool is stripped, not stacked.
        assertEquals(
            "दस्तावेज़ 1 - संपीड़ित",
            OutputNames.forTool("दस्तावेज़ 1 - हस्ताक्षरित", "संपीड़ित", emptyList())
        )
    }

    @Test
    fun `odd input still yields a usable name`() {
        assertEquals("Scan - Copy", OutputNames.forTool("  ", "Copy", emptyList()))
        assertEquals("Document 1 - Copy", OutputNames.forTool("Document 1 - Compressed", "  ", emptyList()))
    }
}
