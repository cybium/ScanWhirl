package com.scanwhirl.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Manual crop surface: the page with a dimmed overlay outside the crop rectangle, four corner
 * handles and four edge handles, all draggable (kit cyan, 24dp touch targets). No auto-detection.
 *
 * A preset sets [aspectRatio] and immediately reshapes the frame to that ratio, centred on the
 * current frame and clamped inside the image (owner QA run 1). Dragging keeps the ratio while a
 * preset is active. The rectangle is kept in image pixels; [getCropRect] returns it for the caller
 * to scale onto the full-resolution file.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var image: Bitmap? = null

    /** Crop rectangle in image pixel coordinates. */
    private val crop = RectF()

    /** Where the bitmap is drawn (fitCenter inside the view). */
    private val imageRect = RectF()

    /** Which corner is being dragged: 0 TL, 1 TR, 2 BR, 3 BL; -1 none. */
    private var activeCorner = -1

    /** Which edge is being dragged: 0 top, 1 right, 2 bottom, 3 left; -1 none. */
    private var activeEdge = -1

    /**
     * Fixed aspect ratio (width / height). Setting a preset reshapes the frame at once; null is a
     * free crop. The setter is the only way presets touch the frame.
     */
    var aspectRatio: Float? = null
        set(value) {
            field = value
            if (value != null && image != null) fitAspectCentered(value)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val touchRadius = 24f * density      // owner: 24dp touch targets
    private val handleRadius = 6f * density      // drawn corner dot
    private val edgeHalf = 13f * density         // drawn edge bar half-length
    private val minViewSize = 48f * density

    private val cyan = SwStyle.color(context, R.color.cyan_primary)
    private val ink = SwStyle.color(context, R.color.void_abyss)

    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint().apply {
        color = cyan
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan
        style = Paint.Style.FILL
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    /** The handle currently focused by TalkBack/switch access or keyboard. */
    private var selectedHandle = -1

    /** Last change announcement; kept so a test can assert a real announcement happened. */
    internal var lastAnnouncement: String = ""
        private set

    /** Virtual-view ids invalidated after the latest move (test seam). */
    private val invalidatedForTest = mutableListOf<Int>()

    /** Virtual-view events sent after the latest move (test seam). */
    private val eventsForTest = mutableListOf<Pair<Int, Int>>()

    /**
     * Eight virtual children (four corners, four edges) exposed through AndroidX's
     * [ExploreByTouchHelper]. Explore-by-touch hit-testing, node bounds and focus
     * are managed by the framework; moving a handle still routes through the very
     * same drag/aspect/clamp code the finger path uses.
     */
    private inner class CropTouchHelper : ExploreByTouchHelper(this@CropView) {
        /** Exposes the protected hit test to the host view's test seam. */
        internal fun hitTest(x: Float, y: Float): Int = getVirtualViewAt(x, y)

        /** The helper's own provider (Robolectric wraps the one on the View). */
        internal fun providerForTest(): AccessibilityNodeProviderCompat =
            getAccessibilityNodeProvider(this@CropView)!!

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (image == null) return ExploreByTouchHelper.INVALID_ID
            val points = handlePoints()
            var best = ExploreByTouchHelper.INVALID_ID
            var bestDist = Float.MAX_VALUE
            for (i in 0 until 8) {
                val dist = hypot(x - points[i * 2], y - points[i * 2 + 1])
                // touchRadius is a 24dp radius, i.e. a 48dp effective target.
                if (dist <= touchRadius && dist < bestDist) {
                    best = HANDLE_BASE + i
                    bestDist = dist
                }
            }
            return best
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (image == null) return
            for (handle in 0 until 8) virtualViewIds.add(HANDLE_BASE + handle)
        }

        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            node.contentDescription = context.getString(R.string.ux130_l2_crop_frame)
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            val handle = virtualViewId - HANDLE_BASE
            if (handle in 0..7) event.contentDescription = handleLabel(handle)
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val handle = virtualViewId - HANDLE_BASE
            if (handle !in 0..7 || image == null) {
                node.setBoundsInParent(Rect())
                return
            }
            node.className = CropView::class.java.name
            node.isFocusable = true
            node.text = handleLabel(handle)
            node.contentDescription = handleLabel(handle)
            val points = handlePoints()
            val x = points[handle * 2]
            val y = points[handle * 2 + 1]
            val r = touchRadius
            val bounds = Rect((x - r).toInt(), (y - r).toInt(), (x + r).toInt(), (y + r).toInt())
            // ExploreByTouchHelper derives screen bounds from these parent bounds.
            node.setBoundsInParent(bounds)
            for ((action, label) in moveActionsFor(handle)) {
                node.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        action, context.getString(label)
                    )
                )
            }
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            val handle = virtualViewId - HANDLE_BASE
            if (handle !in 0..7) return false
            // Only accept moves that make sense for this handle (corners: all four;
            // horizontal edges: up/down; vertical edges: left/right).
            if (action in ACTION_MOVE_LEFT..ACTION_MOVE_DOWN &&
                action !in moveActionsFor(handle).map { it.first }
            ) {
                return false
            }
            return when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    selectHandle(handle)
                    true
                }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (selectedHandle == handle) {
                        selectedHandle = -1
                        invalidate()
                    }
                    true
                }
                ACTION_MOVE_LEFT -> moveHandle(handle, -1f, 0f)
                ACTION_MOVE_RIGHT -> moveHandle(handle, 1f, 0f)
                ACTION_MOVE_UP -> moveHandle(handle, 0f, -1f)
                ACTION_MOVE_DOWN -> moveHandle(handle, 0f, 1f)
                else -> false
            }
        }

        override fun onVirtualViewKeyboardFocusChanged(virtualViewId: Int, hasFocus: Boolean) {
            super.onVirtualViewKeyboardFocusChanged(virtualViewId, hasFocus)
            val handle = virtualViewId - HANDLE_BASE
            if (hasFocus && handle in 0..7) selectHandle(handle)
        }
    }

    private val cropTouchHelper = CropTouchHelper()

    init {
        // L2-01: the canvas surface is a real accessibility target, not just drawn pixels.
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, cropTouchHelper)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        cropTouchHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        cropTouchHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        cropTouchHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    /** Test seam: the helper's real provider (TalkBack sees it through ViewCompat). */
    internal fun nodeProviderForTest(): AccessibilityNodeProviderCompat = cropTouchHelper.providerForTest()

    /** Test seam: explore-by-touch hit test against the existing handle geometry. */
    internal fun virtualViewAt(x: Float, y: Float): Int = cropTouchHelper.hitTest(x, y)

    internal fun nodeForVirtualViewForTest(id: Int): AccessibilityNodeInfoCompat? =
        cropTouchHelper.providerForTest().createAccessibilityNodeInfo(id)

    internal fun performVirtualActionForTest(id: Int, action: Int): Boolean =
        cropTouchHelper.providerForTest().performAction(id, action, null)

    internal fun invalidatedVirtualViewsForTest(): List<Int> = invalidatedForTest.toList()

    internal fun virtualEventsForTest(): List<Pair<Int, Int>> = eventsForTest.toList()

    /** Test seam: ExploreByTouchHelper owns accessibility focus; set the visible focus ring. */
    internal fun selectHandleForTest(handle: Int) {
        if (handle in 0..7) selectHandle(handle)
    }

    /** Hands the page bitmap to the view (a display-size copy, never full-res). */
    fun setImage(bitmap: Bitmap) {
        image = bitmap
        computeImageRect()
        resetCrop()
        invalidate()
    }

    /** Drops the current bitmap (before loading another page's copy). */
    fun clearImage() {
        image = null
        crop.setEmpty()
        invalidate()
    }

    /** Crop rectangle in image pixel coordinates (full image when untouched). */
    fun getCropRect(): RectF = RectF(crop)

    /**
     * fixidcrop: restores a saved free-crop rectangle (image pixels), clamped to the image. Call
     * it after [setImage]; a new image resets the frame, so the caller re-applies its own rect.
     * A preset's aspect ratio is not re-fitted here (this is the manual crop, not a preset).
     *
     * Add-only: the Passport preset never calls it, so its behaviour is unchanged.
     */
    fun setCropRect(rect: RectF) {
        val bmp = image ?: return
        val left = rect.left.coerceIn(0f, bmp.width.toFloat())
        val top = rect.top.coerceIn(0f, bmp.height.toFloat())
        val right = rect.right.coerceIn(left, bmp.width.toFloat())
        val bottom = rect.bottom.coerceIn(top, bmp.height.toFloat())
        crop.set(left, top, right, bottom)
        invalidate()
    }

    private fun resetCrop() {
        val bmp = image ?: return
        crop.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        aspectRatio?.let { fitAspectCentered(it) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
        if (image != null && crop.isEmpty) resetCrop()
        invalidate()
    }

    /** fitCenter mapping of the bitmap into the view. */
    private fun computeImageRect() {
        val bmp = image ?: return
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imageRect.set(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = image ?: return
        canvas.drawBitmap(bmp, null, imageRect, null)

        // Dim everything inside the image but outside the crop.
        val c = viewSpaceCrop()
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, c.top, dimPaint)
        canvas.drawRect(imageRect.left, c.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, c.top, c.left, c.bottom, dimPaint)
        canvas.drawRect(c.right, c.top, imageRect.right, c.bottom, dimPaint)

        canvas.drawRect(c, borderPaint)

        // Four corner dots + four edge bars: every one is a drag target.
        for (corner in corners(c)) {
            canvas.drawCircle(corner.first, corner.second, handleRadius, handlePaint)
            canvas.drawCircle(corner.first, corner.second, handleRadius, handleRing)
        }
        drawEdgeBar(canvas, c.centerX(), c.top, horizontal = true)
        drawEdgeBar(canvas, c.right, c.centerY(), horizontal = false)
        drawEdgeBar(canvas, c.centerX(), c.bottom, horizontal = true)
        drawEdgeBar(canvas, c.left, c.centerY(), horizontal = false)

        // Focus indication: the keyboard/screen-reader handle gets a visible ring.
        if (selectedHandle in 0..7) {
            val point = handlePoints()
            canvas.drawCircle(
                point[selectedHandle * 2], point[selectedHandle * 2 + 1],
                handleRadius + 5f * density, focusRingPaint
            )
        }
    }

    private fun drawEdgeBar(canvas: Canvas, x: Float, y: Float, horizontal: Boolean) {
        if (horizontal) {
            canvas.drawLine(x - edgeHalf, y, x + edgeHalf, y, edgePaint)
        } else {
            canvas.drawLine(x, y - edgeHalf, x, y + edgeHalf, edgePaint)
        }
    }

    private fun corners(c: RectF) = listOf(
        c.left to c.top,
        c.right to c.top,
        c.right to c.bottom,
        c.left to c.bottom
    )

    /** The crop rectangle in view coordinates. */
    private fun viewSpaceCrop(): RectF {
        val bmp = image ?: return RectF()
        val sx = imageRect.width() / bmp.width
        val sy = imageRect.height() / bmp.height
        return RectF(
            imageRect.left + crop.left * sx,
            imageRect.top + crop.top * sy,
            imageRect.left + crop.right * sx,
            imageRect.top + crop.bottom * sy
        )
    }

    /**
     * View-space handle centres, in order: corners TL, TR, BR, BL, then edges top, right,
     * bottom, left. Test seam (and the source of hit-testing).
     */
    internal fun handlePoints(): FloatArray {
        val c = viewSpaceCrop()
        return floatArrayOf(
            c.left, c.top, c.right, c.top, c.right, c.bottom, c.left, c.bottom,
            c.centerX(), c.top, c.right, c.centerY(), c.centerX(), c.bottom, c.left, c.centerY()
        )
    }

    private fun handleLabel(handle: Int): String = context.getString(
        when (handle) {
            0 -> R.string.ux130_l2_crop_corner_tl
            1 -> R.string.ux130_l2_crop_corner_tr
            2 -> R.string.ux130_l2_crop_corner_br
            3 -> R.string.ux130_l2_crop_corner_bl
            4 -> R.string.ux130_l2_crop_edge_top
            5 -> R.string.ux130_l2_crop_edge_right
            6 -> R.string.ux130_l2_crop_edge_bottom
            else -> R.string.ux130_l2_crop_edge_left
        }
    )

    /** Corners can move on both axes; an edge only moves perpendicular to itself. */
    private fun moveActionsFor(handle: Int): List<Pair<Int, Int>> = when (handle) {
        in 0..3 -> listOf(
            ACTION_MOVE_LEFT to R.string.ux130_l2_crop_move_left,
            ACTION_MOVE_RIGHT to R.string.ux130_l2_crop_move_right,
            ACTION_MOVE_UP to R.string.ux130_l2_crop_move_up,
            ACTION_MOVE_DOWN to R.string.ux130_l2_crop_move_down
        )
        4, 6 -> listOf(
            ACTION_MOVE_UP to R.string.ux130_l2_crop_move_up,
            ACTION_MOVE_DOWN to R.string.ux130_l2_crop_move_down
        )
        else -> listOf(
            ACTION_MOVE_LEFT to R.string.ux130_l2_crop_move_left,
            ACTION_MOVE_RIGHT to R.string.ux130_l2_crop_move_right
        )
    }

    private fun selectHandle(handle: Int) {
        selectedHandle = handle
        setTag(R.id.ux130_l2_crop_handle, handle)
        invalidate()
    }

    private fun cornerImagePoint(handle: Int): Pair<Float, Float> = when (handle) {
        0 -> crop.left to crop.top
        1 -> crop.right to crop.top
        2 -> crop.right to crop.bottom
        else -> crop.left to crop.bottom
    }

    private fun edgeImagePoint(edge: Int): Pair<Float, Float> = when (edge) {
        0 -> crop.centerX() to crop.top
        1 -> crop.right to crop.centerY()
        2 -> crop.centerX() to crop.bottom
        else -> crop.left to crop.centerY()
    }

    /**
     * Moves one handle by a fixed view-dp step on either axis and routes through
     * the very same drag/aspect/clamp code a finger uses. Returns true only when
     * the rectangle actually changed, so a boundary no-op is not announced.
     */
    private fun moveHandle(handle: Int, directionX: Float, directionY: Float): Boolean {
        val bmp = image ?: return false
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return false
        val step = 24f * density
        val dx = directionX * step * bmp.width / imageRect.width()
        val dy = directionY * step * bmp.height / imageRect.height()
        val before = RectF(crop)
        if (handle < 4) {
            activeCorner = handle
            val (x, y) = cornerImagePoint(handle)
            dragCornerTo(bmp, x + dx, y + dy)
            activeCorner = -1
        } else {
            activeEdge = handle - 4
            val (x, y) = edgeImagePoint(handle - 4)
            dragEdgeTo(bmp, x + dx, y + dy)
            activeEdge = -1
        }
        clampToImage(bmp)
        selectHandle(handle)
        if (crop == before) return false
        invalidate()
        // The virtual node moved: refresh TalkBack's cached bounds and announce it.
        val virtualId = HANDLE_BASE + handle
        invalidatedForTest += virtualId
        cropTouchHelper.invalidateVirtualView(virtualId)
        eventsForTest += virtualId to AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        cropTouchHelper.sendEventForVirtualView(virtualId, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        val announcement = context.getString(R.string.ux130_l2_crop_moved, handleLabel(handle))
        lastAnnouncement = announcement
        try {
            announceForAccessibility(announcement)
        } catch (_: Throwable) {
            // A test host may not have an AccessibilityManager; the written state still holds.
        }
        return true
    }

    /**
     * D-pad moves the focused (or first) handle. Tab only advances while a handle
     * is focused and there is another one after it; from the last handle (or with
     * no handle focused) the event belongs to the normal focus order, so Save and
     * Apply stay reachable.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (image != null) {
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
                KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
                KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
                else -> null
            }
            if (direction != null) {
                val handle = if (selectedHandle in 0..7) selectedHandle else 0
                if (moveHandle(handle, direction.first, direction.second)) return true
            } else if (keyCode == KeyEvent.KEYCODE_TAB && selectedHandle in 0..6) {
                selectHandle(selectedHandle + 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = image ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val points = handlePoints()
                var best = -1
                var bestDist = Float.MAX_VALUE
                for (i in 0 until 8) {
                    val dist = hypot(event.x - points[i * 2], event.y - points[i * 2 + 1])
                    if (dist <= touchRadius && dist < bestDist) {
                        best = i
                        bestDist = dist
                    }
                }
                if (best < 0) return false
                if (best < 4) activeCorner = best else activeEdge = best - 4
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner < 0 && activeEdge < 0) return false
                val pts = floatArrayOf(event.x, event.y)
                imagePoint(pts)
                val ix = pts[0].coerceIn(0f, bmp.width.toFloat())
                val iy = pts[1].coerceIn(0f, bmp.height.toFloat())
                if (activeCorner >= 0) dragCornerTo(bmp, ix, iy) else dragEdgeTo(bmp, ix, iy)
                clampToImage(bmp)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = activeCorner >= 0 || activeEdge >= 0
                activeCorner = -1
                activeEdge = -1
                return wasDragging
            }
        }
        return false
    }

    /** Maps a view point into image pixels by hand (no matrix drift after a resize). */
    private fun imagePoint(pts: FloatArray) {
        val bmp = image ?: return
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return
        val sx = bmp.width / imageRect.width()
        val sy = bmp.height / imageRect.height()
        pts[0] = (pts[0] - imageRect.left) * sx
        pts[1] = (pts[1] - imageRect.top) * sy
    }

    /** Moves the active corner to image coordinate (ix, iy), keeping a sane size. */
    private fun dragCornerTo(bmp: Bitmap, ix: Float, iy: Float) {
        val sx = bmp.width / imageRect.width().coerceAtLeast(1f)
        val sy = bmp.height / imageRect.height().coerceAtLeast(1f)
        val minW = minViewSize * sx
        val minH = minViewSize * sy

        when (activeCorner) {
            0 -> {
                crop.left = ix.coerceIn(0f, crop.right - minW)
                crop.top = iy.coerceIn(0f, crop.bottom - minH)
            }
            1 -> {
                crop.right = ix.coerceIn(crop.left + minW, bmp.width.toFloat())
                crop.top = iy.coerceIn(0f, crop.bottom - minH)
            }
            2 -> {
                crop.right = ix.coerceIn(crop.left + minW, bmp.width.toFloat())
                crop.bottom = iy.coerceIn(crop.top + minH, bmp.height.toFloat())
            }
            3 -> {
                crop.left = ix.coerceIn(0f, crop.right - minW)
                crop.bottom = iy.coerceIn(crop.top + minH, bmp.height.toFloat())
            }
        }
        if (aspectRatio != null) applyAspectRatio()
    }

    /** Moves the active edge to image coordinate (ix, iy), keeping the opposite edge fixed. */
    private fun dragEdgeTo(bmp: Bitmap, ix: Float, iy: Float) {
        val sx = bmp.width / imageRect.width().coerceAtLeast(1f)
        val sy = bmp.height / imageRect.height().coerceAtLeast(1f)
        val minW = minViewSize * sx
        val minH = minViewSize * sy

        when (activeEdge) {
            0 -> crop.top = iy.coerceIn(0f, crop.bottom - minH)
            1 -> crop.right = ix.coerceIn(crop.left + minW, bmp.width.toFloat())
            2 -> crop.bottom = iy.coerceIn(crop.top + minH, bmp.height.toFloat())
            3 -> crop.left = ix.coerceIn(0f, crop.right - minW)
        }
        if (aspectRatio != null) applyAspectRatioForEdge(bmp)
    }

    /**
     * Keeps width/height at [aspectRatio], anchored to the corner opposite
     * the one being dragged. Clamped to the image bounds.
     */
    private fun applyAspectRatio() {
        val ratio = aspectRatio ?: return
        val bmp = image ?: return
        when (activeCorner) {
            0 -> { // anchored BR
                var h = crop.width() / ratio
                if (h > crop.bottom) {
                    h = crop.bottom
                    crop.left = crop.right - h * ratio
                }
                crop.top = crop.bottom - h
            }
            1 -> { // anchored BL
                var h = crop.width() / ratio
                if (h > crop.bottom) {
                    h = crop.bottom
                    crop.right = crop.left + h * ratio
                }
                crop.top = crop.bottom - h
            }
            2 -> { // anchored TL
                var h = crop.width() / ratio
                if (crop.top + h > bmp.height) {
                    h = bmp.height - crop.top
                    crop.right = crop.left + h * ratio
                }
                crop.bottom = crop.top + h
            }
            3 -> { // anchored TR
                var h = crop.width() / ratio
                if (crop.top + h > bmp.height) {
                    h = bmp.height - crop.top
                    crop.left = crop.right - h * ratio
                }
                crop.bottom = crop.top + h
            }
        }
    }

    /** Keeps the ratio when an edge moves: the perpendicular span follows, centred. */
    private fun applyAspectRatioForEdge(bmp: Bitmap) {
        val ratio = aspectRatio ?: return
        when (activeEdge) {
            0, 2 -> {
                var w = crop.height() * ratio
                if (w > bmp.width) {
                    w = bmp.width.toFloat()
                    val h = w / ratio
                    if (activeEdge == 0) crop.top = crop.bottom - h else crop.bottom = crop.top + h
                }
                val left = (crop.centerX() - w / 2f).coerceIn(0f, bmp.width - w)
                crop.left = left
                crop.right = left + w
            }
            1, 3 -> {
                var h = crop.width() / ratio
                if (h > bmp.height) {
                    h = bmp.height.toFloat()
                    val w = h * ratio
                    if (activeEdge == 1) crop.right = crop.left + w else crop.left = crop.right - w
                }
                val top = (crop.centerY() - h / 2f).coerceIn(0f, bmp.height - h)
                crop.top = top
                crop.bottom = top + h
            }
        }
    }

    private fun clampToImage(bmp: Bitmap) {
        crop.left = max(0f, crop.left)
        crop.top = max(0f, crop.top)
        crop.right = min(bmp.width.toFloat(), crop.right)
        crop.bottom = min(bmp.height.toFloat(), crop.bottom)
    }

    /**
     * A preset fits the largest rectangle of [ratio] inside the image, centred on the current
     * frame and never larger than it (owner QA: the chip used to change nothing).
     */
    private fun fitAspectCentered(ratio: Float) {
        val bmp = image ?: return
        if (bmp.width <= 0 || bmp.height <= 0 || ratio <= 0f) return
        if (crop.isEmpty) crop.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val cx = crop.centerX()
        val cy = crop.centerY()
        var width = min(crop.width(), bmp.width.toFloat())
        var height = width / ratio
        if (height > bmp.height) {
            height = bmp.height.toFloat()
            width = height * ratio
        }
        if (width > bmp.width) {
            width = bmp.width.toFloat()
            height = width / ratio
        }
        val left = (cx - width / 2f).coerceIn(0f, bmp.width - width)
        val top = (cy - height / 2f).coerceIn(0f, bmp.height - height)
        crop.set(left, top, left + width, top + height)
        invalidate()
    }

    private companion object {
        /** Virtual child ids are HOST_VIEW_ID + 1 .. + 8 (corners then edges). */
        const val HANDLE_BASE = 1

        /** Custom actions live well above the framework's standard action range. */
        const val ACTION_MOVE_LEFT = 0x01000001
        const val ACTION_MOVE_RIGHT = 0x01000002
        const val ACTION_MOVE_UP = 0x01000003
        const val ACTION_MOVE_DOWN = 0x01000004
    }
}
