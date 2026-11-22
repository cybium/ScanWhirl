package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Council §5 (lane O): a tool output is a new copy under the SOURCE STEM, never a forced
 * "- Compressed" / "- Clean" / "- Signed" suffix. The user edits the name in the setup field;
 * the engine default is just the stem plus " (2)" when that stem is already taken.
 *
 * Old suffixes written by earlier versions are still recognised and stripped before the new
 * name is made, so re-running a tool on an old output never stacks suffixes.
 */
class OutputNamesTest {

    @Test
    fun `a plain document keeps its stem with no verb suffix`() {
        assertEquals("Document 1", OutputNames.forTool("Document 1", "Compressed", emptyList()))
        assertEquals("Document 1", OutputNames.forTool("Document 1", "Signed", emptyList()))
        assertEquals("Document 1", OutputNames.forTool("Document 1", "Pages 2-3", emptyList()))
    }

    @Test
    fun `old suffixes are read and stripped, never stacked`() {
        assertEquals(
            "Document 1",
            OutputNames.forTool(
                "Document 1 (page 1) (compressed) (signed)", "Watermarked", emptyList()
            )
        )
        assertEquals(
            "Document 1",
            OutputNames.forTool("Document 1 - Compressed", "Signed", emptyList())
        )
        assertEquals(
            "Document 1",
            OutputNames.forTool("Document 1 (pages 2-3)", "Compressed", emptyList())
        )
    }

    @Test
    fun `comma page lists are stripped like page ranges`() {
        assertEquals(
            "Document 1",
            OutputNames.forTool("Document 1 - Pages 1, 10", "Compressed", emptyList())
        )
        assertEquals(
            "Document 1",
            OutputNames.forTool("Document 1 (pages 1, 3-5, 9)", "Signed", emptyList())
        )
    }

    @Test
    fun `a taken stem gets the next collision number`() {
        assertEquals(
            "Document 1 (2)",
            OutputNames.forTool("Document 1", "Signed", listOf("Document 1"))
        )
        assertEquals(
            "Document 1 (4)",
            OutputNames.forTool(
                "Document 1", "Signed",
                listOf("Document 1", "Document 1 (2)", "Document 1 (3)")
            )
        )
        // Case differences are the same name.
        assertEquals(
            "Document 1 (2)",
            OutputNames.forTool("Document 1", "Signed", listOf("document 1"))
        )
    }

    @Test
    fun `hindi stems survive and old hindi suffixes are stripped`() {
        assertEquals(
            "दस्तावेज़ 1",
            OutputNames.forTool("दस्तावेज़ 1", "हस्ताक्षरित", emptyList())
        )
        assertEquals(
            "दस्तावेज़ 1 (2)",
            OutputNames.forTool("दस्तावेज़ 1", "हस्ताक्षरित", listOf("दस्तावेज़ 1"))
        )
        assertEquals(
            "दस्तावेज़ 1",
            OutputNames.forTool("दस्तावेज़ 1 - हस्ताक्षरित", "संपीड़ित", emptyList())
        )
    }

    @Test
    fun `odd input still yields a usable name`() {
        assertEquals("Scan", OutputNames.forTool("  ", "Copy", emptyList()))
        assertEquals("Document 1", OutputNames.forTool("Document 1 - Compressed", "  ", emptyList()))
    }
}
