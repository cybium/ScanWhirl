package com.scanwhirl.app.ui.stamp

import com.scanwhirl.app.util.PageStamp
import java.io.DataInputStream
import java.io.DataOutputStream
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

    /**
     * E1: a drag / resize / rotate is one gesture, not one undo step per ACTION_MOVE.
     * [beginGesture] keeps the pre-gesture snapshot; every transform in the gesture simply
     * mutates the marks and flags the change. [endGesture] pushes that single snapshot, so a
     * 60-event drag creates exactly one undo step and never evicts the earlier "add mark" step.
     */
    private var gestureActive = false
    private var gestureChanged = false
    private var gestureSnapshot: Snapshot? = null
    /** Monotonic: even undoing back to the same geometry requires a fresh privacy review. */
    var revision: Long = 0
        private set

    fun cancelGesture() {
        gestureSnapshot?.let(::restore)
        gestureActive = false
        gestureChanged = false
        gestureSnapshot = null
    }

    /** Opens one coalesced undo step. Safe to call again while a gesture is already open. */
    fun beginGesture() {
        if (gestureActive) return
        gestureActive = true
        gestureChanged = false
        gestureSnapshot = snapshot()
    }

    /** Commits the gesture's one undo step (only when something actually moved). */
    fun endGesture() {
        if (!gestureActive) return
        gestureActive = false
        if (gestureChanged) gestureSnapshot?.let(::commit)
        gestureSnapshot = null
        gestureChanged = false
    }

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
        revision++
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        revision++
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
    fun resizeTo(index: Int, box: PageStamp.Box, precise: Boolean = false): Boolean {
        val mark = pages[currentPage].getOrNull(index) ?: return false
        val requested = fitted(box, if (precise) EPSILON else MIN_SIZE)
        val old = when (mark) {
            is PageStamp.Mark.Signature -> mark.box
            is PageStamp.Mark.Picture -> mark.box
            else -> null
        }
        val fitted = if (old == null) requested else {
            val ratio = (old.right - old.left) / (old.bottom - old.top)
            val w = min(requested.right - requested.left, (requested.bottom - requested.top) * ratio)
            PageStamp.Box(requested.left, requested.top, requested.left + w, requested.top + w / ratio)
        }
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

    /**
     * H5-1: writes every mark, the visible page and the selection, so a recreation (rotation,
     * dark mode, a font-size change, a language change, a low-memory kill) rebuilds exactly the
     * editor that was on screen instead of an empty one. The format is the app's own binary
     * state file — the same build writes and reads it, and [readFrom] refuses anything else.
     */
    fun writeTo(out: DataOutputStream) {
        out.writeInt(VERSION)
        out.writeInt(pageCount)
        out.writeInt(currentPage)
        out.writeInt(selected ?: -1)
        for (list in pages) {
            out.writeInt(list.size)
            for (mark in list) writeMark(out, mark)
        }
    }

    /** Restores what [writeTo] wrote; throws when the bytes are not a matching editor state. */
    fun readFrom(input: DataInputStream) {
        check(input.readInt() == VERSION) { "Unknown stamp editor state" }
        check(input.readInt() == pageCount) { "The stamp editor state belongs to another document" }
        val page = input.readInt().coerceIn(0, pageCount - 1)
        val selectedIndex = input.readInt()
        for (index in pages.indices) {
            val count = input.readInt()
            pages[index].clear()
            repeat(count) { pages[index] += readMark(input) }
        }
        currentPage = page
        selected = selectedIndex.takeIf { it in pages[currentPage].indices }
        // The undo history is per-screen work; a recreation restores the marks, not the steps.
        undoStack.clear()
        redoStack.clear()
    }

    private fun writeMark(out: DataOutputStream, mark: PageStamp.Mark) {
        when (mark) {
            is PageStamp.Mark.Signature -> {
                out.writeUTF(TAG_SIGNATURE)
                writeStrokes(out, mark.strokes)
                writeBox(out, mark.box)
                out.writeInt(mark.color)
                out.writeFloat(mark.width)
                out.writeFloat(mark.rotation)
            }
            is PageStamp.Mark.Picture -> {
                out.writeUTF(TAG_PICTURE)
                out.writeUTF(mark.file.path)
                writeBox(out, mark.box)
                out.writeFloat(mark.rotation)
            }
            is PageStamp.Mark.Text -> {
                out.writeUTF(TAG_TEXT)
                out.writeUTF(mark.text)
                writeBox(out, mark.box)
                out.writeInt(mark.color)
                out.writeFloat(mark.size)
                out.writeBoolean(mark.bold)
                out.writeFloat(mark.rotation)
                out.writeUTF(mark.align.name)
            }
            is PageStamp.Mark.Freehand -> {
                out.writeUTF(TAG_FREEHAND)
                writeStrokes(out, mark.strokes)
                out.writeInt(mark.color)
                out.writeFloat(mark.width)
            }
            is PageStamp.Mark.Highlight -> {
                out.writeUTF(TAG_HIGHLIGHT)
                writeBox(out, mark.box)
                out.writeInt(mark.color)
                out.writeFloat(mark.opacity)
            }
            is PageStamp.Mark.Watermark -> {
                out.writeUTF(TAG_WATERMARK)
                out.writeUTF(mark.text)
                out.writeInt(mark.color)
                out.writeFloat(mark.opacity)
                out.writeFloat(mark.angle)
                out.writeFloat(mark.size)
            }
            is PageStamp.Mark.PageNumber -> {
                out.writeUTF(TAG_PAGE_NUMBER)
                out.writeUTF(mark.position.name)
                out.writeUTF(mark.format)
                out.writeInt(mark.startAt)
                out.writeInt(mark.color)
                out.writeFloat(mark.size)
            }
            is PageStamp.Mark.Badge -> {
                out.writeUTF(TAG_BADGE)
                out.writeUTF(mark.kind.name)
                writeBox(out, mark.box)
                out.writeBoolean(mark.date != null)
                mark.date?.let { out.writeUTF(it) }
                out.writeBoolean(mark.color != null)
                mark.color?.let { out.writeInt(it) }
            }
            is PageStamp.Mark.Redact -> {
                out.writeUTF(TAG_REDACT)
                writeBox(out, mark.box)
                out.writeInt(mark.color)
            }
        }
    }

    private fun readMark(input: DataInputStream): PageStamp.Mark =
        when (val tag = input.readUTF()) {
            TAG_SIGNATURE -> PageStamp.Mark.Signature(
                strokes = readStrokes(input),
                box = readBox(input),
                color = input.readInt(),
                width = input.readFloat(),
                rotation = input.readFloat()
            )
            TAG_PICTURE -> PageStamp.Mark.Picture(
                file = java.io.File(input.readUTF()),
                box = readBox(input),
                rotation = input.readFloat()
            )
            TAG_TEXT -> PageStamp.Mark.Text(
                text = input.readUTF(),
                box = readBox(input),
                color = input.readInt(),
                size = input.readFloat(),
                bold = input.readBoolean(),
                rotation = input.readFloat(),
                align = PageStamp.TextAlign.valueOf(input.readUTF())
            )
            TAG_FREEHAND -> PageStamp.Mark.Freehand(
                strokes = readStrokes(input),
                color = input.readInt(),
                width = input.readFloat()
            )
            TAG_HIGHLIGHT -> PageStamp.Mark.Highlight(
                box = readBox(input),
                color = input.readInt(),
                opacity = input.readFloat()
            )
            TAG_WATERMARK -> PageStamp.Mark.Watermark(
                text = input.readUTF(),
                color = input.readInt(),
                opacity = input.readFloat(),
                angle = input.readFloat(),
                size = input.readFloat()
            )
            TAG_PAGE_NUMBER -> PageStamp.Mark.PageNumber(
                position = PageStamp.Position.valueOf(input.readUTF()),
                format = input.readUTF(),
                startAt = input.readInt(),
                color = input.readInt(),
                size = input.readFloat()
            )
            TAG_BADGE -> PageStamp.Mark.Badge(
                kind = PageStamp.BadgeKind.valueOf(input.readUTF()),
                box = readBox(input),
                date = if (input.readBoolean()) input.readUTF() else null,
                color = if (input.readBoolean()) input.readInt() else null
            )
            TAG_REDACT -> PageStamp.Mark.Redact(box = readBox(input), color = input.readInt())
            else -> throw IllegalStateException("Unknown mark in the stamp editor state: $tag")
        }

    private fun writeBox(out: DataOutputStream, box: PageStamp.Box) {
        out.writeFloat(box.left)
        out.writeFloat(box.top)
        out.writeFloat(box.right)
        out.writeFloat(box.bottom)
    }

    private fun readBox(input: DataInputStream) = PageStamp.Box(
        input.readFloat(),
        input.readFloat(),
        input.readFloat(),
        input.readFloat()
    )

    private fun writeStrokes(out: DataOutputStream, strokes: List<PageStamp.Stroke>) {
        out.writeInt(strokes.size)
        for (stroke in strokes) {
            out.writeInt(stroke.points.size)
            for (point in stroke.points) {
                out.writeFloat(point.x)
                out.writeFloat(point.y)
            }
        }
    }

    private fun readStrokes(input: DataInputStream): List<PageStamp.Stroke> =
        List(input.readInt()) {
            PageStamp.Stroke(
                List(input.readInt()) {
                    PageStamp.Point(input.readFloat(), input.readFloat())
                }
            )
        }

    // ------------------------------------------------------------------

    private data class Snapshot(
        val pages: List<List<PageStamp.Mark>>,
        val page: Int,
        val selected: Int?
    )

    private fun snapshot() = Snapshot(pages.map { it.toList() }, currentPage, selected)

    private fun record() {
        revision++
        if (gestureActive) {
            // A transform already opened a gesture: do not add a step for every move. The
            // pre-gesture snapshot kept by beginGesture is committed once by endGesture.
            gestureChanged = true
            return
        }
        commit(snapshot())
    }

    private fun commit(snap: Snapshot) {
        undoStack.addLast(snap)
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

    private fun fitted(box: PageStamp.Box, minimum: Float = MIN_SIZE): PageStamp.Box {
        var left = min(box.left, box.right).coerceIn(0f, 1f)
        var top = min(box.top, box.bottom).coerceIn(0f, 1f)
        var right = max(box.left, box.right).coerceIn(0f, 1f)
        var bottom = max(box.top, box.bottom).coerceIn(0f, 1f)
        // The epsilon keeps the width genuinely ≥ MIN_SIZE after float rounding.
        if (right - left < minimum) right = (left + minimum + EPSILON).coerceAtMost(1f)
        if (bottom - top < minimum) bottom = (top + minimum + EPSILON).coerceAtMost(1f)
        // A corner drag can pin a box to the far edge; keep the minimum by moving the near edge.
        if (right - left < minimum) left = (right - minimum - EPSILON).coerceAtLeast(0f)
        if (bottom - top < minimum) top = (bottom - minimum - EPSILON).coerceAtLeast(0f)
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

        /** Bumped only when [writeTo] / [readFrom] change layout. */
        const val VERSION = 1

        const val TAG_SIGNATURE = "signature"
        const val TAG_PICTURE = "picture"
        const val TAG_TEXT = "text"
        const val TAG_FREEHAND = "freehand"
        const val TAG_HIGHLIGHT = "highlight"
        const val TAG_WATERMARK = "watermark"
        const val TAG_PAGE_NUMBER = "pageNumber"
        const val TAG_BADGE = "badge"
        const val TAG_REDACT = "redact"
    }
}
