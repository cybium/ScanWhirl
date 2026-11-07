package com.scanwhirl.app.util

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * test26 T1.5 — StatusLine replaces the emoji Greeting (N7, R8).
 * Phase boundaries: 05–11 morning, 12–16 afternoon, 17–20 evening, else night.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatusLineTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `phases follow the day plan`() {
        assertEquals(R.string.status_night, StatusLine.phase(4))
        assertEquals(R.string.status_morning, StatusLine.phase(5))
        assertEquals(R.string.status_morning, StatusLine.phase(11))
        assertEquals(R.string.status_afternoon, StatusLine.phase(12))
        assertEquals(R.string.status_afternoon, StatusLine.phase(16))
        assertEquals(R.string.status_evening, StatusLine.phase(17))
        assertEquals(R.string.status_evening, StatusLine.phase(20))
        assertEquals(R.string.status_night, StatusLine.phase(21))
        assertEquals(R.string.status_night, StatusLine.phase(23))
        assertEquals(R.string.status_night, StatusLine.phase(0))
    }

    @Test
    fun `format builds the HUD readout`() {
        assertEquals(
            "GOOD AFTERNOON · 2 DOCS · 14.0 MB",
            StatusLine.format(context, 14, 2, 14L * 1_048_576)
        )
        assertEquals(
            "GOOD MORNING · 1 DOC · 0 B",
            StatusLine.format(context, 7, 1, 0)
        )
        assertEquals(
            "GOOD NIGHT · 0 DOCS · 512 KB",
            StatusLine.format(context, 23, 0, 512L * 1024)
        )
    }

    @Test
    fun `every hour renders a line with no emoji`() {
        for (hour in 0..23) {
            val line = StatusLine.format(context, hour, 3, 3L * 1_048_576)
            assertTrue("empty line at $hour", line.isNotBlank())
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                val emoji = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp == 0x2B50
                assertFalse("emoji U+${cp.toString(16)} in \"$line\"", emoji)
                i += Character.charCount(cp)
            }
        }
    }

    @Test
    fun `styled paints separators hairline and numbers monospace`() {
        val span = StatusLine.styled(context, 14, 2, 14L * 1_048_576)
        val text = span.toString()
        assertEquals(StatusLine.format(context, 14, 2, 14L * 1_048_576), text)

        // Every " · " separator carries a ForegroundColorSpan.
        val seps = mutableListOf<Int>()
        var at = text.indexOf(" · ")
        while (at >= 0) {
            seps += at
            at = text.indexOf(" · ", at + 3)
        }
        assertEquals("two separators expected in \"$text\"", 2, seps.size)
        for (s in seps) {
            val spans = span.getSpans(s, s + 3, android.text.style.ForegroundColorSpan::class.java)
            assertEquals("no colour span on separator at $s", 1, spans.size)
        }

        // The digit runs ("2", "14.0") are monospaced; the letters are not.
        val mono = span.getSpans(0, text.length, android.text.style.TypefaceSpan::class.java)
        assertTrue("monospace spans expected", mono.isNotEmpty())
        for (m in mono) {
            val s = span.getSpanStart(m)
            val e = span.getSpanEnd(m)
            assertTrue("empty mono span", e > s)
            val covered = text.substring(s, e)
            assertTrue("mono span must cover digits: \"$covered\"", covered.any { it.isDigit() })
        }
        // The phase word "AFTERNOON" must NOT be inside any mono span.
        val phaseStart = text.indexOf("AFTERNOON")
        val covering = mono.filter {
            span.getSpanStart(it) <= phaseStart && span.getSpanEnd(it) >= phaseStart + 9
        }
        assertEquals("phase word must stay proportional", 0, covering.size)
    }
}
