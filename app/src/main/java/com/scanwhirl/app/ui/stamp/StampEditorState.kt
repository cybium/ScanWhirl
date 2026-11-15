package com.scanwhirl.app.ui.stamp

import com.scanwhirl.app.util.PageStamp
import kotlin.math.max
import kotlin.math.min

/**
 * L5 Phase B — the state behind the Stamp editor (`70` §3 "Edit & sign", product spec §3.7).
 *
 * It owns the marks per page, the selected mark on the visible page, a bounded undo/redo
 * history and the drag / resize / rotate transforms. It has no Android views, so every editor
 * behaviour is unit-testable; [StampEditorActivity] is a thin shell that draws [marks] and calls
 * these functions from touch gestures.
 *
 * Marks are immutable data objects and the history holds shallow copies of each page's list,
 * so an undo can never mutate a mark the screen is still drawing.
 */
class StampEditorState(val pageCount: Int) {

    init {
        require(pageCount > 0) { "A document needs at least one page" }
    }

    private val pages: List<MutableList<PageStamp.Mark>> = List(pageCount) { mutableListOf() }
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    /** Visible page, 0-based. Changing it clears the selection. */
    var currentPage: Int = 0
        set(value) {
            field = value.coerceIn(0, pageCount - 1)
            selected = null
        }

    /** Index into [marks] of the selected mark, or null. */
    var selected: Int? = null
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Marks on [page] (default: the visible page). */
    fun marks(page: Int = currentPage): List<PageStamp.Mark> =
        pages[page.coerceIn(0, pageCount - 1)].toList()

    fun isEmpty(): Boolean = pages.all { it.isEmpty() }

    /** True when any page carries a redaction: the copy then destroys pixels and OCR text. */
    fun hasRedaction(): Boolean = pages.any { page -> page.any { it is PageStamp.Mark.Redact } }

    /** The visible edit set, ready for `ScanRepository.stampDocument`. */
    fun edits(): List<PageStamp.PageEdits> =
        pages.mapIndexedNotNull { index, list ->
            if (list.isEmpty()) null else PageStamp.PageEdits(index, list.toList())
        }

    /** Adds [mark] to the visible page and selects it. */
    fun add(mark: PageStamp.Mark): Int {
        record()
        val list = pages[currentPage]
        list += mark
        selected = list.lastIndex
        return list.lastIndex
    }

    /** Adds several marks to the visible page as ONE undo step (Redact suggestions). */
    fun addAll(marks: List<PageStamp.Mark>): Int? {
        if (marks.isEmpty()) return null
        record()
        val list = pages[currentPage]
        list.addAll(marks)
        selected = list.lastIndex
        return selected
    }

    /** Adds marks to a specific page (Find-and-hide applies across pages in one pass). */
    fun addAllAt(page: Int, marks: List<PageStamp.Mark>): Int? {
        if (marks.isEmpty()) return null
        val index = page.coerceIn(0, pageCount - 1)
        record()
        val list = pages[index]
        list.addAll(marks)
        if (index == currentPage) selected = list.lastIndex
        return list.lastIndex
    }

    /** Adds [mark] to every page as ONE undo step (watermark, page numbers). */
    fun addToAll(mark: PageStamp.Mark) {
        record()
        pages.forEach { it += mark }
        selected = pages[currentPage].lastIndex
    }

    /** Selects a mark on the visible page; anything else clears the selection. */
    fun select(index: Int?) {
        selected = index?.takeIf { it in pages[currentPage].indices }
    }

    /** Removes the selected mark. */
    fun removeSelected(): Boolean {
        val index = selected ?: return false
        if (index !in pages[currentPage].indices) return false
        record()
        pages[currentPage].removeAt(index)
        selected = null
        return true
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(snapshot())
        restore(redoStack.removeLast())
        return true
    }

    /**
     * Drag: moves a boxed mark, or every point of free drawing, by page-normalised [dx], [dy].
     * The mark keeps its size and cannot leave the page. Watermarks and page numbers are
     * placement-free, so dragging them does nothing.
     */
    fun moveBy(index: Int, dx: Float, dy: Float): Boolean {
        val mark = pages[currentPage].getOrNull(index) ?: return false
        val moved = when (mark) {
            is PageStamp.Mark.Text -> mark.copy(box = shifted(mark.box, dx, dy))
            is PageStamp.Mark.Signature -> mark.copy(box = shifted(mark.box, dx, dy))
            is PageStamp.Mark.Picture -> mark.copy(box = shifted(mark.box, dx, dy))
            is PageStamp.Mark.Highlight -> mark.copy(box = shifted(mark.box, dx, dy))
            is PageStamp.Mark.Badge -> mark.copy(box = shifted(mark.box, dx, dy))
            is PageStamp.Mark.Redact -> mark.copy(box = shifted(mark.box, dx, dy))
            is PageStamp.Mark.Freehand -> mark.copy(strokes = shiftedStrokes(mark.strokes, dx, dy))
            is PageStamp.Mark.Watermark, is PageStamp.Mark.PageNumber -> return false
        }
        if (moved == mark) return false
        record()
        pages[currentPage][index] = moved
        return true
    }

    /** Resize handle: replaces the box of a boxed mark, keeping it on the page and usable. */
    fun resizeTo(index: Int, box: PageStamp.Box): Boolean {
        val mark = pages[currentPage].getOrNull(index) ?: return false
        val fitted = fitted(box)
        val resized = when (mark) {
            is PageStamp.Mark.Text -> mark.copy(box = fitted)
            is PageStamp.Mark.Signature -> mark.copy(box = fitted)
            is PageStamp.Mark.Picture -> mark.copy(box = fitted)
            is PageStamp.Mark.Highlight -> mark.copy(box = fitted)
            is PageStamp.Mark.Badge -> mark.copy(box = fitted)
            is PageStamp.Mark.Redact -> mark.copy(box = fitted)
            else -> return false
        }
        if (resized == mark) return false
        record()
        pages[currentPage][index] = resized
        return true
    }

    /** Rotation handle: signature ink and text can rotate; other marks have a designed angle. */
    fun rotateBy(index: Int, degrees: Float): Boolean {
        val mark = pages[currentPage].getOrNull(index) ?: return false
        val rotated = when (mark) {
            is PageStamp.Mark.Text -> mark.copy(rotation = normaliseAngle(mark.rotation + degrees))
            is PageStamp.Mark.Signature -> mark.copy(rotation = normaliseAngle(mark.rotation + degrees))
            is PageStamp.Mark.Picture -> mark.copy(rotation = normaliseAngle(mark.rotation + degrees))
            else -> return false
        }
        if (rotated == mark) return false
        record()
        pages[currentPage][index] = rotated
        return true
    }

    // ------------------------------------------------------------------

    private data class Snapshot(
        val pages: List<List<PageStamp.Mark>>,
        val page: Int,
        val selected: Int?
    )

    private fun snapshot() = Snapshot(pages.map { it.toList() }, currentPage, selected)

    private fun record() {
        undoStack.addLast(snapshot())
        redoStack.clear()
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
    }

    private fun restore(snapshot: Snapshot) {
        pages.forEachIndexed { index, list ->
            list.clear()
            list.addAll(snapshot.pages[index])
        }
        currentPage = snapshot.page
        selected = snapshot.selected?.takeIf { it in pages[currentPage].indices }
    }

    private fun shifted(box: PageStamp.Box, dx: Float, dy: Float): PageStamp.Box {
        val width = (box.right - box.left).coerceAtLeast(MIN_SIZE)
        val height = (box.bottom - box.top).coerceAtLeast(MIN_SIZE)
        val left = (min(box.left, box.right) + dx).coerceIn(0f, 1f - width)
        val top = (min(box.top, box.bottom) + dy).coerceIn(0f, 1f - height)
        return PageStamp.Box(left, top, left + width, top + height)
    }

    private fun fitted(box: PageStamp.Box): PageStamp.Box {
        var left = min(box.left, box.right).coerceIn(0f, 1f)
        var top = min(box.top, box.bottom).coerceIn(0f, 1f)
        var right = max(box.left, box.right).coerceIn(0f, 1f)
        var bottom = max(box.top, box.bottom).coerceIn(0f, 1f)
        // The epsilon keeps the width genuinely ≥ MIN_SIZE after float rounding.
        if (right - left < MIN_SIZE) right = (left + MIN_SIZE + EPSILON).coerceAtMost(1f)
        if (bottom - top < MIN_SIZE) bottom = (top + MIN_SIZE + EPSILON).coerceAtMost(1f)
        // A corner drag can pin a box to the far edge; keep the minimum by moving the near edge.
        if (right - left < MIN_SIZE) left = (right - MIN_SIZE - EPSILON).coerceAtLeast(0f)
        if (bottom - top < MIN_SIZE) top = (bottom - MIN_SIZE - EPSILON).coerceAtLeast(0f)
        return PageStamp.Box(left, top, right, bottom)
    }

    private fun shiftedStrokes(
        strokes: List<PageStamp.Stroke>,
        dx: Float,
        dy: Float
    ): List<PageStamp.Stroke> = strokes.map { stroke ->
        PageStamp.Stroke(
            stroke.points.map { point ->
                PageStamp.Point(
                    (point.x + dx).coerceIn(0f, 1f),
                    (point.y + dy).coerceIn(0f, 1f)
                )
            }
        )
    }

    private fun normaliseAngle(degrees: Float): Float {
        var value = degrees % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }

    private companion object {
        const val MIN_SIZE = 0.02f
        const val EPSILON = 0.00001f
        const val MAX_HISTORY = 50
    }
}
