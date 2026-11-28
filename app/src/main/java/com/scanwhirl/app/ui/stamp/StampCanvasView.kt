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
import android.view.ViewConfiguration
import android.view.HapticFeedbackConstants
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

        /** E1: brackets a drag / resize / rotate so the editor records ONE undo step per gesture. */
        fun onGestureStart() {}
        fun onGestureEnd() {}
        fun onGestureCancel() {}
        fun onPlace(mark: PageStamp.Mark, box: PageStamp.Box) {}

        /** Accessibility "Delete mark"; hosts without a delete path ignore it. */
        fun onDelete(index: Int) {}
    }

    var listener: Listener? = null
    var placement: PageStamp.Mark? = null
    var originalReview = false
        set(value) { field = value; rebuild() }
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    private var multiTouch = false
    private var span = 0f
    private var centroid = PointF()
    private var down = PointF()
    private var origin = PointF()
    private var dragged = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    fun setZoom(value: Float) {
        zoom = value.coerceIn(1f, 5f)
        boundPan()
        invalidate()
    }

    fun cancelActiveGesture(): Boolean {
        if (mode == Mode.NONE) return false
        listener?.onGestureCancel()
        mode = Mode.NONE
        previewStroke.clear()
        previewBox = null
        stopEdgePan()
        invalidate()
        return true
    }

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
    private val handleRadius = dp(6f)

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

    private enum class Mode { NONE, MOVE, RESIZE, ROTATE, DRAWING, BOXING, PLACE, PAN }

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
            if (tool == Tool.REDACT && !originalReview) {
                canvas.drawRect(toView(box, page), Paint().apply { color = Color.BLACK })
            } else canvas.drawRect(toView(box, page), previewPaint)
        }
        if (originalReview) marks.filterIsInstance<PageStamp.Mark.Redact>().forEach {
            canvas.drawRect(toView(box(it.box), page), previewPaint)
        }
    }

    private fun drawSelection(canvas: Canvas, page: RectF) {
        val mark = marks.getOrNull(selectedIndex ?: return) ?: return
        val bounds = normalizedBounds(mark) ?: return
        val rect = toView(bounds, page).apply { inset(-dp(6f), -dp(6f)) }
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
        val w = bmp.width * scale * zoom
        val h = bmp.height * scale * zoom
        val left = (width - w) / 2f + panX
        val top = (height - h) / 2f + panY
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
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            cancelActiveGesture()
            multiTouch = true
            span = pointerSpan(event)
            centroid = pointerCenter(event)
            return true
        }
        if (multiTouch) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount >= 2) {
                val center = pointerCenter(event)
                val nextSpan = pointerSpan(event)
                val old = zoom
                zoom = (zoom * nextSpan / span.coerceAtLeast(1f)).coerceIn(1f, 5f)
                val ratio = zoom / old
                panX = center.x - width / 2f - (centroid.x - width / 2f - panX) * ratio
                panY = center.y - height / 2f - (centroid.y - height / 2f - panY) * ratio
                centroid = center
                span = nextSpan
                boundPan()
                invalidate()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                multiTouch = false
            }
            return true
        }
        val page = pageRect()
        if (page.isEmpty) return false
        val norm = toNormalised(event.x, event.y, page)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastNorm.set(norm)
                origin.set(norm)
                down.set(event.x, event.y)
                dragged = false
                if (placement != null) {
                    mode = Mode.PLACE
                    return true
                }
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
                // Only a transform gesture opens an undo step; a bare tap only selects.
                if (mode == Mode.MOVE || mode == Mode.RESIZE || mode == Mode.ROTATE) {
                    listener?.onGestureStart()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragged && distanceTo(event.x, event.y, down) > slop) {
                    dragged = true
                    if (mode != Mode.PAN && mode != Mode.NONE) {
                        performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30)
                            HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                if (!dragged) return true
                when (mode) {
                    Mode.DRAWING -> {
                        val last = previewStroke.lastOrNull()
                        if (last == null || distance(last, norm) > 0.002f) {
                            previewStroke += PointF(norm.x, norm.y)
                            invalidate()
                        }
                    }
                    Mode.BOXING -> {
                        previewBox = RectF(
                            min(origin.x, norm.x), min(origin.y, norm.y),
                            max(origin.x, norm.x), max(origin.y, norm.y)
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
                        var box = PageStamp.Box(
                            min(resizeAnchor.x, norm.x), min(resizeAnchor.y, norm.y),
                            max(resizeAnchor.x, norm.x), max(resizeAnchor.y, norm.y)
                        )
                        val mark = marks.getOrNull(resizeIndex)
                        if (mark is PageStamp.Mark.Signature || mark is PageStamp.Mark.Picture) {
                            val old = mark?.let(::normalizedBounds)
                            if (old != null) {
                                val ratio = old.width() / old.height()
                                val w = min(box.right - box.left, (box.bottom - box.top) * ratio)
                                val h = w / ratio
                                val l = if (norm.x < resizeAnchor.x) resizeAnchor.x - w else resizeAnchor.x
                                val t = if (norm.y < resizeAnchor.y) resizeAnchor.y - h else resizeAnchor.y
                                box = PageStamp.Box(l, t, l + w, t + h)
                            }
                        }
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
                    Mode.PLACE -> {
                        previewBox = RectF(min(origin.x, norm.x), min(origin.y, norm.y),
                            max(origin.x, norm.x), max(origin.y, norm.y))
                        invalidate()
                    }
                    Mode.PAN -> {
                        panX += event.x - down.x
                        panY += event.y - down.y
                        down.set(event.x, event.y)
                        boundPan()
                        invalidate()
                    }
                    Mode.NONE -> Unit
                }
                updateEdgePan(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                // Closes the undo step opened on DOWN (a no-op when this was a draw / box or a
                // bare tap). CANCEL ends it too, so a half-done gesture is still one step.
                stopEdgePan()
                if (cancelled) listener?.onGestureCancel() else listener?.onGestureEnd()
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
                        if (!cancelled && dragged && box != null && box.width() * 612f >= 4f &&
                            box.height() * pagePointsHeight() >= 4f) {
                            val armed = tool
                            listener?.onBox(
                                armed,
                                PageStamp.Box(box.left, box.top, box.right, box.bottom)
                            )
                        }
                        previewBox = null
                    }
                    Mode.PLACE -> if (!cancelled) {
                        val mark = placement
                        if (mark != null) {
                            val default = normalizedBounds(mark) ?: RectF(.2f, .5f, .6f, .6f)
                            val ratio = default.width() / default.height()
                            val requested = previewBox
                            val w = if (dragged && requested != null)
                                min(requested.width(), requested.height() * ratio)
                                else default.width()
                            val h = w / ratio
                            if (w > 0f && h > 0f) {
                                val l = (requested?.left ?: (origin.x - w / 2)).coerceIn(0f, 1f - w)
                                val t = (requested?.top ?: (origin.y - h / 2)).coerceIn(0f, 1f - h)
                                listener?.onPlace(mark, PageStamp.Box(l, t, l + w, t + h))
                                placement = null
                            }
                        }
                    }
                    else -> Unit
                }
                mode = Mode.NONE
                previewStroke.clear()
                previewBox = null
                invalidate()
                if (!cancelled) performClick()
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
                val rect = toView(bounds, page).apply { inset(-dp(6f), -dp(6f)) }
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
                    val corner = corners(rect).withIndex().filter {
                        kotlin.math.abs(event.x - it.value.x) <= dp(24f) &&
                            kotlin.math.abs(event.y - it.value.y) <= dp(24f)
                    }.minByOrNull { distanceTo(event.x, event.y, it.value) }?.index ?: -1
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
        } else mode = Mode.PAN
    }

    fun pagePointsHeight(): Float = pageBitmap?.let { 612f * it.height / it.width } ?: 792f

    private fun pointerCenter(e: MotionEvent) = PointF(
        (e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f)
    private fun pointerSpan(e: MotionEvent): Float =
        kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun boundPan() {
        val bmp = pageBitmap ?: return
        val scale = min((width - dp(20f)) / bmp.width, (height - dp(20f)) / bmp.height)
        val maxX = max(0f, (bmp.width * scale * zoom - width) / 2f + dp(10f))
        val maxY = max(0f, (bmp.height * scale * zoom - height) / 2f + dp(10f))
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private var edgeX = 0f
    private var edgeY = 0f
    private var edgePosted = false
    private val edgePan = object : Runnable {
        override fun run() {
            edgePosted = false
            if (zoom <= 1f || mode !in listOf(Mode.MOVE, Mode.RESIZE)) return
            fun speed(p: Float, size: Int): Float = when {
                p < dp(32f) -> (dp(32f) - p).coerceAtMost(dp(32f)) / dp(32f)
                p > size - dp(32f) -> -(p - size + dp(32f)).coerceAtMost(dp(32f)) / dp(32f)
                else -> 0f
            } * dp(240f) / 60f
            val dx = speed(edgeX, width)
            val dy = speed(edgeY, height)
            if (dx == 0f && dy == 0f) return
            panX += dx; panY += dy
            boundPan()
            val e = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, edgeX, edgeY, 0)
            onTouchEvent(e)
            e.recycle()
        }
    }
    private fun updateEdgePan(x: Float, y: Float) {
        edgeX = x; edgeY = y
        if (!edgePosted && zoom > 1f && mode in listOf(Mode.MOVE, Mode.RESIZE)) {
            edgePosted = true
            postOnAnimation(edgePan)
        }
    }
    private fun stopEdgePan() { removeCallbacks(edgePan); edgePosted = false }

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
            PageStamp.render(next, if (originalReview) marks.filterNot { it is PageStamp.Mark.Redact }
                else marks, pageNumber, pageCount)
        }
        composite?.let { if (!it.isRecycled && it !== next) it.recycle() }
        composite = next
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // A view torn down mid-gesture (screen close) must not leave the editor's undo step open.
        cancelActiveGesture()
        composite?.let { if (!it.isRecycled) it.recycle() }
        composite = null
        if (pageBitmap?.isRecycled == false) pageBitmap?.recycle()
        pageBitmap = null
        super.onDetachedFromWindow()
    }
}
