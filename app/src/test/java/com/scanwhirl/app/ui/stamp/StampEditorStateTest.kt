package com.scanwhirl.app.ui.stamp

import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L5 Phase B: the stamp editor's model — marks per page, selection, undo/redo and the drag,
 * resize and rotate transforms. The screen is a thin shell over this class, so every editor
 * behaviour is testable without inflating views.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampEditorStateTest {

    private val textBox = PageStamp.Box(0.1f, 0.1f, 0.5f, 0.2f)

    private fun text(value: String = "APPROVED") =
        PageStamp.Mark.Text(value, textBox)

    private fun signature() = PageStamp.Mark.Signature(
        strokes = listOf(
            PageStamp.Stroke(listOf(PageStamp.Point(0f, 0f), PageStamp.Point(1f, 1f)))
        ),
        box = PageStamp.Box(0.2f, 0.2f, 0.5f, 0.4f)
    )

    @Test
    fun `marks are grouped per page and exported as page edits`() {
        val state = StampEditorState(pageCount = 3)
        state.add(text())
        state.currentPage = 2
        state.add(PageStamp.Mark.Watermark("COPY"))

        assertTrue(state.edits().any { it.pageIndex == 0 && it.marks.size == 1 })
        assertTrue(state.edits().any { it.pageIndex == 2 && it.marks.size == 1 })
        assertEquals(2, state.edits().size)
        assertEquals(1, state.marks(0).size)
        assertEquals(1, state.marks(2).size)
    }

    @Test
    fun `undo and redo step through every add and remove`() {
        val state = StampEditorState(pageCount = 1)
        state.add(text())
        state.add(PageStamp.Mark.Watermark("COPY"))
        assertEquals(2, state.marks().size)

        assertTrue(state.undo())
        assertEquals(1, state.marks().size)
        assertTrue(state.undo())
        assertEquals(0, state.marks().size)
        assertFalse("nothing left to undo", state.undo())

        assertTrue(state.redo())
        assertEquals(1, state.marks().size)
        assertTrue(state.redo())
        assertEquals(2, state.marks().size)
        assertFalse("nothing left to redo", state.redo())
    }

    @Test
    fun `a fresh edit after undo clears the redo history`() {
        val state = StampEditorState(pageCount = 1)
        state.add(text("A"))
        state.undo()
        assertTrue(state.canRedo)
        state.add(text("B"))
        assertFalse(state.canRedo)
        assertEquals(1, state.marks().size)
        assertEquals("B", (state.marks().single() as PageStamp.Mark.Text).text)
    }

    @Test
    fun `dragging moves a box mark and clamps it inside the page`() {
        val state = StampEditorState(pageCount = 1)
        val index = state.add(text())
        state.moveBy(index, 0.2f, 0.1f)
        val moved = state.marks().single() as PageStamp.Mark.Text
        assertEquals(0.3f, moved.box.left, 0.0001f)
        assertEquals(0.2f, moved.box.top, 0.0001f)

        state.moveBy(index, 5f, 5f)
        val clamped = (state.marks().single() as PageStamp.Mark.Text).box
        assertTrue("box stays on the page", clamped.right <= 1f && clamped.bottom <= 1f)
        assertTrue(clamped.left >= 0f && clamped.top >= 0f)
        assertEquals("size is kept", 0.4f, clamped.right - clamped.left, 0.0001f)
    }

    @Test
    fun `resizing keeps a usable minimum`() {
        val state = StampEditorState(pageCount = 1)
        val index = state.add(text())
        state.resizeTo(index, PageStamp.Box(0.2f, 0.2f, 0.2f, 0.2f))
        val box = (state.marks().single() as PageStamp.Mark.Text).box
        assertTrue("a zero-size box is refused (got $box)", box.right - box.left >= 0.02f)
        assertEquals(0.02f, box.right - box.left, 0.0001f)
    }

    @Test
    fun `rotation applies to signature and text only`() {
        val state = StampEditorState(pageCount = 1)
        val sig = state.add(signature())
        val txt = state.add(text())
        val mark = state.add(PageStamp.Mark.Watermark("COPY"))

        state.rotateBy(sig, 15f)
        state.rotateBy(txt, -10f)
        state.rotateBy(mark, 40f)

        assertEquals(15f, (state.marks()[sig] as PageStamp.Mark.Signature).rotation, 0.0001f)
        assertEquals(-10f, (state.marks()[txt] as PageStamp.Mark.Text).rotation, 0.0001f)
        assertEquals(
            "a watermark keeps its designed angle",
            -40f,
            (state.marks()[mark] as PageStamp.Mark.Watermark).angle,
            0.0001f
        )
    }

    @Test
    fun `hasRedaction spots a redaction on any page`() {
        val state = StampEditorState(pageCount = 2)
        assertFalse(state.hasRedaction())
        state.add(text())
        assertFalse("ordinary marks are not redactions", state.hasRedaction())

        state.currentPage = 1
        state.add(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.5f, 0.2f)))
        assertTrue("a redaction on another page still counts", state.hasRedaction())
    }

    @Test
    fun `selection is cleared when the selected mark is undone`() {
        val state = StampEditorState(pageCount = 1)
        val index = state.add(text())
        state.select(index)
        assertEquals(index, state.selected)
        state.undo()
        assertNull(state.selected)
    }

    @Test
    fun `undo restores marks on the page they were added to`() {
        val state = StampEditorState(pageCount = 2)
        state.currentPage = 1
        state.add(text())
        state.undo()
        assertTrue(state.marks(0).isEmpty())
        assertTrue(state.marks(1).isEmpty())
        state.redo()
        assertTrue(state.marks(0).isEmpty())
        assertEquals(1, state.marks(1).size)
    }

    @Test
    fun `a watermark on every page is one undo step`() {
        val state = StampEditorState(pageCount = 3)
        state.addToAll(PageStamp.Mark.Watermark("COPY"))
        for (page in 0 until 3) assertEquals(1, state.marks(page).size)
        assertTrue(state.undo())
        for (page in 0 until 3) assertTrue(state.marks(page).isEmpty())
        assertTrue(state.redo())
        for (page in 0 until 3) assertEquals(1, state.marks(page).size)
    }

    @Test
    fun `suggested redactions are added as one undo step`() {
        val state = StampEditorState(pageCount = 1)
        val boxes = listOf(
            PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.8f, 0.2f)),
            PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.3f, 0.8f, 0.4f))
        )
        state.addAll(boxes)
        assertEquals(2, state.marks().size)
        assertTrue(state.undo())
        assertTrue(state.marks().isEmpty())
    }
}
