package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHistoryTest {

    @Test
    fun `undo returns byte-identical old text after ACTION`() {
        val h = TextHistory()
        h.record("hello", "world", TextHistory.Kind.ACTION)
        assertTrue(h.canUndo)
        assertEquals("hello", h.undo("world"))
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        assertEquals("world", h.redo("hello"))
    }

    @Test
    fun `redo returns new text after undo`() {
        val h = TextHistory()
        h.record("alpha", "beta", TextHistory.Kind.ACTION)
        h.undo("beta")
        assertEquals("beta", h.redo("alpha"))
    }

    @Test
    fun `typing burst separated by time forms separate groups`() {
        val times = longArrayOf(0L, 2000L)
        var t = 0
        val h = TextHistory(clock = { times[t++] })
        h.record("", "x", TextHistory.Kind.TYPING) // t=0, push ""
        h.record("x", "y", TextHistory.Kind.TYPING) // t=2000, gap > 1000ms, push "x"
        assertTrue(h.canUndo)
        assertEquals("x", h.undo("y")) // removes "y" only
        assertTrue(h.canUndo)
        assertEquals("", h.undo("x")) // removes "x"
        assertFalse(h.canUndo)
    }

    @Test
    fun `typing burst within 1000ms forms one group`() {
        val times = longArrayOf(0L, 500L)
        var t = 0
        val h = TextHistory(clock = { times[t++] })
        h.record("", "x", TextHistory.Kind.TYPING) // t=0, push ""
        h.record("x", "y", TextHistory.Kind.TYPING) // t=500, burst, shares snapshot
        assertTrue(h.canUndo)
        assertEquals("", h.undo("y")) // one undo returns to original
        assertFalse(h.canUndo)
    }

    @Test
    fun `no-op record leaves canUndo false`() {
        val h = TextHistory()
        h.record("same", "same", TextHistory.Kind.ACTION)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test
    fun `cleanup then cleanup then undo twice returns to original`() {
        val h = TextHistory()
        h.record("orig", "cleanA", TextHistory.Kind.ACTION)
        h.record("cleanA", "cleanB", TextHistory.Kind.ACTION)
        assertEquals("cleanA", h.undo("cleanB"))
        assertEquals("orig", h.undo("cleanA"))
        assertFalse(h.canUndo)
    }

    @Test
    fun `stack never exceeds maxEntries`() {
        val h = TextHistory(maxEntries = 3)
        for (i in 0 until 10) {
            h.record("$i", "${i + 1}", TextHistory.Kind.ACTION)
        }
        assertTrue(h.undoStackSize <= 3)
    }

    @Test
    fun `stack never exceeds maxChars`() {
        val h = TextHistory(maxEntries = 1000, maxChars = 100)
        h.record("", "a".repeat(60), TextHistory.Kind.ACTION)
        h.record("a".repeat(60), "b".repeat(60), TextHistory.Kind.ACTION)
        assertTrue(h.totalCharsSize <= 100)
    }
}
