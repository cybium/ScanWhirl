package com.scanwhirl.app.ui.tools

import com.scanwhirl.app.tools.CompressTarget
import com.scanwhirl.app.util.ImageTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2 (winner spec lane 2): the "Aim for a file size" field must never turn a bad or overflowing
 * number into a real target. The shared `ImageTools.parseSizeInput` rounds through Int, so
 * "999999999999999999999 MB" became a 1-byte target there; the Compress sheet parses its own
 * input and rejects blank, zero, unreadable and overflowing sizes by name.
 */
class CompressTargetParseTest {

    @Test
    fun `blank input asks for a size`() {
        assertEquals(CompressTarget.Result.Blank, CompressTarget.parse(null))
        assertEquals(CompressTarget.Result.Blank, CompressTarget.parse(""))
        assertEquals(CompressTarget.Result.Blank, CompressTarget.parse("   "))
    }

    @Test
    fun `zero is rejected, not silently accepted`() {
        assertEquals(CompressTarget.Result.Zero, CompressTarget.parse("0"))
        assertEquals(CompressTarget.Result.Zero, CompressTarget.parse("0 KB"))
        assertEquals(CompressTarget.Result.Zero, CompressTarget.parse("0.0001 KB"))
    }

    @Test
    fun `unreadable text is rejected`() {
        assertEquals(CompressTarget.Result.Invalid, CompressTarget.parse("abc"))
        assertEquals(CompressTarget.Result.Invalid, CompressTarget.parse("20KBg"))
        assertEquals(CompressTarget.Result.Invalid, CompressTarget.parse("-5"))
        assertEquals(CompressTarget.Result.Invalid, CompressTarget.parse("1,5 MB"))
        assertEquals(CompressTarget.Result.Invalid, CompressTarget.parse("KB"))
    }

    @Test
    fun `a size that is too large for the job is rejected, never wrapped`() {
        assertEquals(
            CompressTarget.Result.TooLarge,
            CompressTarget.parse("999999999999999999999 MB")
        )
        assertEquals(
            CompressTarget.Result.TooLarge,
            CompressTarget.parse("999999999999999999999999999999999999 KB")
        )
        assertEquals("above the 2 GB ceiling", CompressTarget.Result.TooLarge, CompressTarget.parse("3000 MB"))
    }

    @Test
    fun `a real size is the exact number of bytes (decimal KB and MB)`() {
        // CP1: KB = 1000 bytes and MB = 1,000,000. "Under 500 KB" is <= 500,000 bytes,
        // so it also passes a portal that counts KB as 1024. The label stays "KB".
        assertEquals(CompressTarget.Result.Bytes(7_000L), CompressTarget.parse("7 KB"))
        assertEquals(CompressTarget.Result.Bytes(700_000L), CompressTarget.parse("700 KB"))
        assertEquals(CompressTarget.Result.Bytes(700_000L), CompressTarget.parse(" 700kb "))
        assertEquals(CompressTarget.Result.Bytes(3_000_000L), CompressTarget.parse("3 MB"))
        assertEquals("a bare number means KB, as the hint says", CompressTarget.Result.Bytes(500_000L), CompressTarget.parse("500"))
        assertEquals(CompressTarget.Result.Bytes(1_500L), CompressTarget.parse("1.5"))
        assertEquals(CompressTarget.Result.Bytes(2L), CompressTarget.parse("2 b"))
        assertEquals(
            "2048 MB is a size below the shared 2 GiB ceiling",
            CompressTarget.Result.Bytes(2_048_000_000L),
            CompressTarget.parse("2048 MB")
        )
        val parsed = CompressTarget.parse("7 KB") as CompressTarget.Result.Bytes
        assertTrue("the parsed target is a positive byte count", parsed.value > 0L)
    }

    @Test
    fun `the tools hub and the Compress sheet agree on huge targets`() {
        // H3-7: the hub and Batch used ImageTools.parseSizeInput, which saturated through Int.
        // Both now name the same 2 GB ceiling as the sheet instead of accepting "5000 MB".
        assertNull("5000 MB must not become 2 GiB", ImageTools.parseSizeInput("5000 MB"))
        assertEquals(
            "both parsers now read 2048 MB as the same decimal byte count",
            2_048_000_000L,
            ImageTools.parseSizeInput("2048 MB")
        )
    }
}
