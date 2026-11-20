package com.scanwhirl.app.ui.stamp

import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * H5-1: the editor is written to and read from a small state file on every recreation. This
 * holds the format itself: every mark kind (text, colour, geometry, ink points, an imported
 * stamp's own file) must come back exactly, and a file for another document must be refused
 * instead of painting marks onto the wrong pages.
 */
class StampEditorStatePersistTest {

    private fun roundTrip(state: StampEditorState): StampEditorState {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { state.writeTo(it) }
        val restored = StampEditorState(state.pageCount)
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { restored.readFrom(it) }
        return restored
    }

    @Test
    fun `every mark kind survives the state file exactly`() {
        val state = StampEditorState(pageCount = 3)
        val signature = PageStamp.Mark.Signature(
            strokes = listOf(
                PageStamp.Stroke(
                    listOf(
                        PageStamp.Point(0.11f, 0.22f),
                        PageStamp.Point(0.33f, 0.44f)
                    )
                )
            ),
            box = PageStamp.Box(0.1f, 0.6f, 0.7f, 0.72f),
            color = 0xFF123456.toInt(),
            width = 0.0041f,
            rotation = -12.5f
        )
        val picture = PageStamp.Mark.Picture(
            file = File("/tmp/f6state-stamp.png"),
            box = PageStamp.Box(0.5f, 0.5f, 0.9f, 0.62f),
            rotation = 7f
        )
        val text = PageStamp.Mark.Text(
            text = "SELF-ATTESTED\nरूप 1",
            box = PageStamp.Box(0.08f, 0.06f, 0.92f, 0.16f),
            color = 0xFF0000FF.toInt(),
            size = 0.052f,
            bold = true,
            rotation = 3f,
            align = PageStamp.TextAlign.CENTER
        )
        val freehand = PageStamp.Mark.Freehand(
            strokes = listOf(
                PageStamp.Stroke(
                    listOf(PageStamp.Point(0f, 0f), PageStamp.Point(1f, 1f))
                )
            ),
            color = 0xFF00FF00.toInt(),
            width = 0.006f
        )
        val highlight = PageStamp.Mark.Highlight(
            PageStamp.Box(0.2f, 0.2f, 0.4f, 0.3f),
            color = 0xFFFFF176.toInt(),
            opacity = 0.41f
        )
        val watermark = PageStamp.Mark.Watermark(
            text = "CONFIDENTIAL",
            color = 0xFF888888.toInt(),
            opacity = 0.22f,
            angle = -38f,
            size = 0.14f
        )
        val pageNumber = PageStamp.Mark.PageNumber(
            position = PageStamp.Position.TOP_RIGHT,
            format = "%d of %d",
            startAt = 4,
            color = 0xFF222222.toInt(),
            size = 0.031f
        )
        val badge = PageStamp.Mark.Badge(
            kind = PageStamp.BadgeKind.PAID,
            box = PageStamp.Box(0.52f, 0.62f, 0.92f, 0.75f),
            date = "20 Nov 2026",
            color = 0xFF1B7F4B.toInt()
        )
        val redact = PageStamp.Mark.Redact(
            PageStamp.Box(0.10f, 0.20f, 0.60f, 0.35f),
            color = 0xFF000000.toInt()
        )
        val all = listOf(signature, picture, text, freehand, highlight, watermark, pageNumber, badge, redact)
        all.forEach { state.add(it) }
        state.add(PageStamp.Mark.Redact(PageStamp.Box(0.7f, 0.7f, 0.9f, 0.8f)))

        val restored = roundTrip(state)

        assertEquals(all + PageStamp.Mark.Redact(PageStamp.Box(0.7f, 0.7f, 0.9f, 0.8f)), restored.marks())
        assertTrue(restored.hasRedaction())
        assertEquals(state.edits(), restored.edits())
        assertFalse("a recreation restores marks, not the undo history", restored.canUndo)
    }

    @Test
    fun `the visible page and the selection survive while other pages stay empty`() {
        val state = StampEditorState(pageCount = 3)
        state.add(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.4f, 0.2f)))
        state.currentPage = 2
        state.add(PageStamp.Mark.Text("page three", PageStamp.Box(0.1f, 0.1f, 0.9f, 0.2f)))
        state.select(0)

        val restored = roundTrip(state)

        assertEquals(2, restored.currentPage)
        assertEquals(1, restored.marks(0).size)
        assertTrue(restored.marks(1).isEmpty())
        assertEquals(1, restored.marks(2).size)
        assertEquals(0, restored.selected)
    }

    @Test
    fun `a state file for another document is refused`() {
        val threePages = StampEditorState(pageCount = 3)
        threePages.add(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.4f, 0.2f)))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { threePages.writeTo(it) }

        val other = StampEditorState(pageCount = 2)
        val refused = runCatching {
            DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { other.readFrom(it) }
        }
        assertTrue("another document's marks must never be painted here", refused.isFailure)
        assertTrue(other.isEmpty())
    }
}
