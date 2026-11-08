package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrCleanupTest {

    @Test
    fun `joinLines joins hard-wrapped paragraph lines`() {
        assertEquals(
            "This is a long paragraph that is wrapped across multiple lines.",
            OcrCleanup.joinLines("This is a long paragraph that\nis wrapped across multiple\nlines.")
        )
    }

    @Test
    fun `joinLines keeps blank-line paragraph breaks`() {
        assertEquals(
            "First paragraph.\n\nSecond paragraph.",
            OcrCleanup.joinLines("First paragraph.\n\nSecond paragraph.")
        )
    }

    @Test
    fun `joinLines de-hyphenates split word`() {
        assertEquals(
            "example",
            OcrCleanup.joinLines("exam-\nple")
        )
    }

    @Test
    fun `joinLines does not de-hyphenate dash followed by uppercase`() {
        assertEquals(
            "pre- View",
            OcrCleanup.joinLines("pre-\nView")
        )
    }

    @Test
    fun `joinLines does not join bullet list lines`() {
        assertEquals(
            "• First item\n• Second item\n• Third item",
            OcrCleanup.joinLines("• First item\n• Second item\n• Third item")
        )
    }

    @Test
    fun `joinLines does not join numbered list lines`() {
        assertEquals(
            "1. One\n2. Two\n3. Three",
            OcrCleanup.joinLines("1. One\n2. Two\n3. Three")
        )
    }

    @Test
    fun `joinLines handles hyphen at paragraph end`() {
        assertEquals(
            "end-\n\nstart",
            OcrCleanup.joinLines("end-\n\nstart")
        )
    }

    @Test
    fun `fixSpaces collapses multiple spaces`() {
        assertEquals(
            "hello world",
            OcrCleanup.fixSpaces("hello   world")
        )
    }

    @Test
    fun `fixSpaces trims line ends`() {
        assertEquals(
            "hello\nworld",
            OcrCleanup.fixSpaces("hello   \nworld  ")
        )
    }

    @Test
    fun `fixSpaces collapses 3+ newlines to 2`() {
        assertEquals(
            "para1\n\npara2",
            OcrCleanup.fixSpaces("para1\n\n\n\npara2")
        )
    }

    @Test
    fun `removeGaps collapses blank lines to single newline`() {
        assertEquals(
            "line1\nline2\nline3",
            OcrCleanup.removeGaps("line1\n\n\nline2\n\nline3")
        )
    }

    @Test
    fun `empty string passes through unchanged`() {
        assertEquals("", OcrCleanup.joinLines(""))
        assertEquals("", OcrCleanup.fixSpaces(""))
        assertEquals("", OcrCleanup.removeGaps(""))
    }

    @Test
    fun `combined cleanup join then fix spaces`() {
        val raw = "This is a\nhard-wrapped paragraph.\n\n  With   extra   spaces  \n\n\nAnd gaps."
        val joined = OcrCleanup.joinLines(raw)
        val fixed = OcrCleanup.fixSpaces(joined)
        assertEquals(
            "This is a hard-wrapped paragraph.\n\nWith extra spaces\n\nAnd gaps.",
            fixed
        )
    }
}
