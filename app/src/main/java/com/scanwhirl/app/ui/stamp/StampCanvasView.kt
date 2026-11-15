package com.scanwhirl.app.ui.stamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.util.PageStamp
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * L5 Phase B — the page surface of the stamp editor.
 *
 * It shows the current page with every placed mark, the selection box and its handles, and
 * turns touches into editor calls: tap selects, drag moves, a corner resizes, the knob rotates.
 * Draw / Highlight / Redact are armed modes that collect one gesture and report the finished
 * mark (or box) to [listener]; the editor then returns to Select.
 *
 * Marks are normalised to the page, so the preview and the saved page always agree. Rendering
 * reuses [PageStamp.render] on a display-sized copy (≤ 1400 px), never the output page.
 */
class StampCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { SELECT, DRAW, HIGHLIGHT, REDACT }

    interface Listener {
        fun onSelect(index: Int?)
        fun onMove(index: Int, dx: Float, dy: Float)
        fun onResize(index: Int, box: PageStamp.Box)
        fun onRotate(index: Int, degrees: Float)
        fun onFreehand(strokes: List<PageStamp.Stroke>)
        fun onBox(tool: Tool, box: PageStamp.Box)

        /** Accessibility "Delete mark"; hosts without a delete path ignore it. */
        fun onDelete(index: Int) {}
    }

    var listener: Listener? = null

    var pageBitmap: Bitmap? = null
        set(value) {
            if (field === value) return
            if (field?.isRecycled == false) field?.recycle()
            field = value
            rebuild()
        }

    var marks: List<PageStamp.Mark> = emptyList()
        set(value) {
            field = value
            contentDescription = describeState()
            rebuild()
        }

    var selectedIndex: Int? = null
        set(value) {
            if (field == value) return
            field = value
            contentDescription = describeState()
            if (value != null) announceForAccessibility(describeState())
            invalidate()
        }

    var pageNumber: Int = 1
        set(value) {
            field = value
            contentDescription = describeState()
        }
    var pageCount: Int = 1
        set(value) {
            field = value
            contentDescription = describeState()
        }

    var tool: Tool = Tool.SELECT
        set(value) {
            if (field == value) return
            field = value
            previewStroke.clear()
            previewBox = null
            invalidate()
        }

    private var composite: Bitmap? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    /** v2 kit: the selection chrome is cyan_primary on void_abyss, handles sized by dimens_v2. */
    private val cyan = ContextCompat.getColor(context, R.color.cyan_primary)
    private val voidInk = ContextCompat.getColor(context, R.color.void_abyss)
    private val handleRadius = resources.getDimension(R.dimen.sw_space_2)

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = cyan
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = cyan
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = voidInk
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = cyan
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
    }
    private val inkPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = cyan
    }
    private val dimPaint = Paint().apply { color = (voidInk and 0x00FFFFFF) or 0x66000000 }

    init {
        // A TalkBack user reaches the marks through the local context menu: move, resize,
        // rotate and delete are custom accessibility actions, never a precision drag.
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = describeState()
        ViewCompat.setAccessibilityDelegate(this, CanvasAccessibility())
    }

    private fun markLabel(mark: PageStamp.Mark): String = context.getString(
        when (mark) {
            is PageStamp.Mark.Text -> R.string.a11y_mark_text
            is PageStamp.Mark.Signature -> R.string.a11y_mark_signature
            is PageStamp.Mark.Picture -> R.string.a11y_mark_picture
            is PageStamp.Mark.Highlight -> R.string.a11y_mark_highlight
            is PageStamp.Mark.Badge -> R.string.a11y_mark_badge
            is PageStamp.Mark.Redact -> R.string.a11y_mark_redact
            is PageStamp.Mark.Freehand -> R.string.a11y_mark_ink
            is PageStamp.Mark.Watermark -> R.string.a11y_mark_watermark
            is PageStamp.Mark.PageNumber -> R.string.a11y_mark_page_number
        }
    )

    private fun describeState(): String {
        val mark = marks.getOrNull(selectedIndex ?: -1)
        return if (mark == null) {
            context.getString(R.string.a11y_no_mark_selected, pageNumber, pageCount)
        } else {
            context.getString(
                R.string.a11y_mark_selected, markLabel(mark), pageNumber, pageCount
            )
        }
    }

    private fun resizeBy(index: Int, mark: PageStamp.Mark, delta: Float): Boolean {
        val bounds = normalizedBounds(mark) ?: return false
        val hw = (bounds.width() / 2f + delta).coerceAtLeast(0.01f)
        val hh = (bounds.height() / 2f + delta).coerceAtLeast(0.01f)
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val left = (cx - hw).coerceIn(0f, 1f)
        val top = (cy - hh).coerceIn(0f, 1f)
        val right = (cx + hw).coerceIn(0f, 1f)
        val bottom = (cy + hh).coerceIn(0f, 1f)
        listener?.onResize(index, PageStamp.Box(left, top, right, bottom))
        return true
    }

    private inner class CanvasAccessibility : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.contentDescription = describeState()
            info.isFocusable = true
            val index = selectedIndex ?: return
            val mark = marks.getOrNull(index) ?: return
            fun action(id: Int, label: Int) {
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        id, context.getString(label)
                    )
                )
            }
            if (tool == Tool.SELECT) {
                action(R.id.a11y_move_left, R.string.a11y_move_left)
                action(R.id.a11y_move_right, R.string.a11y_move_right)
                action(R.id.a11y_move_up, R.string.a11y_move_up)
                action(R.id.a11y_move_down, R.string.a11y_move_down)
                if (mark !is PageStamp.Mark.Freehand) {
                    action(R.id.a11y_grow, R.string.a11y_grow)
                    action(R.id.a11y_shrink, R.string.a11y_shrink)
                }
                if (canRotate(mark)) {
                    action(R.id.a11y_rotate_cw, R.string.a11y_rotate_cw)
                    action(R.id.a11y_rotate_ccw, R.string.a11y_rotate_ccw)
                }
            }
            action(R.id.a11y_delete_mark, R.string.a11y_delete_mark)
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            val index = selectedIndex
            val mark = marks.getOrNull(index ?: -1)
            if (index != null && mark != null) {
                when (action) {
                    R.id.a11y_move_left -> {
                        listener?.onMove(index, -0.02f, 0f)
                        contentDescription = describeState()
                        return true
                    }
                    R.id.a11y_move_right -> {
                        listener?.onMove(index, 0.02f, 0f)
                        contentDescription = describeState()
                        return true
                    }
                    R.id.a11y_move_up -> {
                        listener?.onMove(index, 0f, -0.02f)
                        contentDescription = describeState()
                        return true
                    }
                    R.id.a11y_move_down -> {
                        listener?.onMove(index, 0f, 0.02f)
                        contentDescription = describeState()
                        return true
                    }
                    R.id.a11y_grow -> return resizeBy(index, mark, 0.02f)
                    R.id.a11y_shrink -> return resizeBy(index, mark, -0.02f)
                    R.id.a11y_rotate_cw -> {
                        listener?.onRotate(index, 5f)
                        return true
                    }
                    R.id.a11y_rotate_ccw -> {
                        listener?.onRotate(index, -5f)
                        return true
                    }
                    R.id.a11y_delete_mark -> {
                        listener?.onDelete(index)
                        return true
                    }
                }
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }

    private var mode = Mode.NONE
    private var moveIndex = -1
    private var lastNorm = PointF()

    private var resizeIndex = -1
    private var resizeAnchor = PointF()

    private var rotateIndex = -1
    private var lastAngle = 0f

    private val previewStroke = ArrayList<PointF>()
    private var previewBox: RectF? = null

    private enum class Mode { NONE, MOVE, RESIZE, ROTATE, DRAWING, BOXING }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = pageRect()
        val shown = composite
        if (shown != null && !shown.isRecycled) {
            canvas.drawBitmap(shown, null, page, null)
        } else {
            canvas.drawRect(page, dimPaint)
        }
        if (page.isEmpty) return
        drawPreview(canvas, page)
        if (tool == Tool.SELECT) drawSelection(canvas, page)
    }

    private fun drawPreview(canvas: Canvas, page: RectF) {
        if (previewStroke.isNotEmpty()) {
            val path = Path()
            previewStroke.forEachIndexed { index, point ->
                val x = page.left + point.x * page.width()
                val y = page.top + point.y * page.height()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, inkPreviewPaint)
        }
        previewBox?.let { box ->
            canvas.drawRect(toView(box, page), previewPaint)
        }
    }

    private fun drawSelection(canvas: Canvas, page: RectF) {
        val mark = marks.getOrNull(selectedIndex ?: return) ?: return
        val bounds = normalizedBounds(mark) ?: return
        val rect = toView(bounds, page)
        canvas.drawRect(rect, selectionPaint)
        if (tool != Tool.SELECT) return

        // Corner handles: every boxed mark can be resized. A freehand mark only moves.
        if (mark is PageStamp.Mark.Freehand) return
        for (corner in corners(rect)) {
            canvas.drawCircle(corner.x, corner.y, handleRadius, handlePaint)
            canvas.drawCircle(corner.x, corner.y, handleRadius, handleRing)
        }
        if (canRotate(mark)) {
            val knob = PointF(rect.centerX(), rect.top - dp(30f))
            canvas.drawLine(rect.centerX(), rect.top, knob.x, knob.y, selectionPaint)
            canvas.drawCircle(knob.x, knob.y, handleRadius, handlePaint)
            canvas.drawCircle(knob.x, knob.y, handleRadius, handleRing)
        }
    }

    // ------------------------------------------------------------------ geometry

    private fun pageRect(): RectF {
        val bmp = pageBitmap ?: return RectF()
        if (width == 0 || height == 0) return RectF()
        val pad = dp(10f)
        val availableW = max(1f, width - 2 * pad)
        val availableH = max(1f, height - 2 * pad)
        val scale = min(availableW / bmp.width, availableH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    private fun toView(bounds: RectF, page: RectF): RectF = RectF(
        page.left + bounds.left * page.width(),
        page.top + bounds.top * page.height(),
        page.left + bounds.right * page.width(),
        page.top + bounds.bottom * page.height()
    )

    private fun toNormalised(x: Float, y: Float, page: RectF): PointF {
        if (page.width() <= 0f || page.height() <= 0f) return PointF()
        return PointF(
            ((x - page.left) / page.width()).coerceIn(0f, 1f),
            ((y - page.top) / page.height()).coerceIn(0f, 1f)
        )
    }

    private fun corners(rect: RectF) = listOf(
        PointF(rect.left, rect.top),
        PointF(rect.right, rect.top),
        PointF(rect.right, rect.bottom),
        PointF(rect.left, rect.bottom)
    )

    private fun normalizedBounds(mark: PageStamp.Mark): RectF? = when (mark) {
        is PageStamp.Mark.Text -> box(mark.box)
        is PageStamp.Mark.Signature -> box(mark.box)
        is PageStamp.Mark.Picture -> box(mark.box)
        is PageStamp.Mark.Highlight -> box(mark.box)
        is PageStamp.Mark.Badge -> box(mark.box)
        is PageStamp.Mark.Redact -> box(mark.box)
        is PageStamp.Mark.Freehand -> freehandBounds(mark.strokes)
        is PageStamp.Mark.Watermark, is PageStamp.Mark.PageNumber -> null
    }

    private fun box(box: PageStamp.Box): RectF = RectF(
        min(box.left, box.right).coerceIn(0f, 1f),
        min(box.top, box.bottom).coerceIn(0f, 1f),
        max(box.left, box.right).coerceIn(0f, 1f),
        max(box.top, box.bottom).coerceIn(0f, 1f)
    )

    private fun freehandBounds(strokes: List<PageStamp.Stroke>): RectF? {
        var l = 1f
        var t = 1f
        var r = 0f
        var b = 0f
        var any = false
        strokes.forEach { stroke ->
            stroke.points.forEach { p ->
                any = true
                l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            }
        }
        if (!any) return null
        val pad = 0.012f
        return RectF(
            (l - pad).coerceIn(0f, 1f),
            (t - pad).coerceIn(0f, 1f),
            (r + pad).coerceIn(0f, 1f),
            (b + pad).coerceIn(0f, 1f)
        )
    }

    private fun canRotate(mark: PageStamp.Mark): Boolean =
        mark is PageStamp.Mark.Text ||
            mark is PageStamp.Mark.Signature ||
            mark is PageStamp.Mark.Picture

    private fun hitTest(norm: PointF): Int {
        for (i in marks.indices.reversed()) {
            val bounds = normalizedBounds(marks[i]) ?: continue
            if (bounds.contains(norm.x, norm.y)) return i
        }
        return -1
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val page = pageRect()
        if (page.isEmpty) return false
        val norm = toNormalised(event.x, event.y, page)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastNorm.set(norm)
                when (tool) {
                    Tool.DRAW -> {
                        previewStroke.clear()
                        previewStroke += PointF(norm.x, norm.y)
                        mode = Mode.DRAWING
                        invalidate()
                    }
                    Tool.HIGHLIGHT, Tool.REDACT -> {
                        previewBox = RectF(norm.x, norm.y, norm.x, norm.y)
                        mode = Mode.BOXING
                        invalidate()
                    }
                    Tool.SELECT -> startSelect(event, page, norm)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAWING -> {
                        val last = previewStroke.lastOrNull()
                        if (last == null || distance(last, norm) > 0.002f) {
                            previewStroke += PointF(norm.x, norm.y)
                            invalidate()
                        }
                    }
                    Mode.BOXING -> {
                        val origin = previewBox ?: return true
                        previewBox = RectF(
                            min(origin.left, norm.x), min(origin.top, norm.y),
                            max(origin.left, norm.x), max(origin.top, norm.y)
                        )
                        invalidate()
                    }
                    Mode.MOVE -> {
                        val dx = norm.x - lastNorm.x
                        val dy = norm.y - lastNorm.y
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 0.001f) {
                            listener?.onMove(moveIndex, dx, dy)
                            lastNorm.set(norm)
                        }
                    }
                    Mode.RESIZE -> {
                        val box = PageStamp.Box(
                            min(resizeAnchor.x, norm.x), min(resizeAnchor.y, norm.y),
                            max(resizeAnchor.x, norm.x), max(resizeAnchor.y, norm.y)
                        )
                        listener?.onResize(resizeIndex, box)
                    }
                    Mode.ROTATE -> {
                        val mark = marks.getOrNull(rotateIndex)
                        val bounds = mark?.let { normalizedBounds(it) }
                        if (bounds != null) {
                            val cx = bounds.centerX()
                            val cy = bounds.centerY()
                            val angle = Math.toDegrees(
                                atan2((norm.y - cy).toDouble(), (norm.x - cx).toDouble())
                            ).toFloat()
                            var delta = angle - lastAngle
                            if (delta > 180f) delta -= 360f
                            if (delta < -180f) delta += 360f
                            if (kotlin.math.abs(delta) > 0.5f) {
                                listener?.onRotate(rotateIndex, delta)
                                lastAngle = angle
                            }
                        }
                    }
                    Mode.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when (mode) {
                    Mode.DRAWING -> {
                        if (!cancelled && previewStroke.isNotEmpty()) {
                            val points = previewStroke.map { PageStamp.Point(it.x, it.y) }
                            listener?.onFreehand(listOf(PageStamp.Stroke(points)))
                        }
                        previewStroke.clear()
                    }
                    Mode.BOXING -> {
                        val box = previewBox
                        if (!cancelled && box != null && box.width() > 0.02f && box.height() > 0.02f) {
                            val armed = tool
                            listener?.onBox(
                                armed,
                                PageStamp.Box(box.left, box.top, box.right, box.bottom)
                            )
                        }
                        previewBox = null
                    }
                    else -> Unit
                }
                mode = Mode.NONE
                previewStroke.clear()
                previewBox = null
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startSelect(event: MotionEvent, page: RectF, norm: PointF) {
        val selected = selectedIndex
        if (selected != null) {
            val mark = marks.getOrNull(selected)
            val bounds = mark?.let { normalizedBounds(it) }
            if (mark != null && bounds != null) {
                val rect = toView(bounds, page)
                if (canRotate(mark)) {
                    val knob = PointF(rect.centerX(), rect.top - dp(30f))
                    if (distanceTo(event.x, event.y, knob) <= dp(22f)) {
                        mode = Mode.ROTATE
                        rotateIndex = selected
                        lastAngle = Math.toDegrees(
                            atan2(
                                (event.y - rect.centerY()).toDouble(),
                                (event.x - rect.centerX()).toDouble()
                            )
                        ).toFloat()
                        return
                    }
                }
                if (mark !is PageStamp.Mark.Freehand) {
                    val corner = corners(rect).indexOfFirst {
                        distanceTo(event.x, event.y, it) <= dp(22f)
                    }
                    if (corner >= 0) {
                        mode = Mode.RESIZE
                        resizeIndex = selected
                        resizeAnchor = when (corner) {
                            0 -> PointF(bounds.right, bounds.bottom)
                            1 -> PointF(bounds.left, bounds.bottom)
                            2 -> PointF(bounds.left, bounds.top)
                            else -> PointF(bounds.right, bounds.top)
                        }
                        return
                    }
                }
            }
        }
        val hit = hitTest(norm)
        listener?.onSelect(if (hit >= 0) hit else null)
        if (hit >= 0) {
            mode = Mode.MOVE
            moveIndex = hit
        }
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun distanceTo(x: Float, y: Float, target: PointF): Float {
        val dx = x - target.x
        val dy = y - target.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ------------------------------------------------------------------ rendering

    private fun rebuild() {
        val base = pageBitmap
        if (base == null || base.isRecycled) {
            composite?.let { if (!it.isRecycled) it.recycle() }
            composite = null
            invalidate()
            return
        }
        val next = try {
            base.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Throwable) {
            null
        }
        if (next != null) {
            PageStamp.render(next, marks, pageNumber, pageCount)
        }
        composite?.let { if (!it.isRecycled && it !== next) it.recycle() }
        composite = next
        invalidate()
    }

    override fun onDetachedFromWindow() {
        composite?.let { if (!it.isRecycled) it.recycle() }
        composite = null
        if (pageBitmap?.isRecycled == false) pageBitmap?.recycle()
        pageBitmap = null
        super.onDetachedFromWindow()
    }
}
