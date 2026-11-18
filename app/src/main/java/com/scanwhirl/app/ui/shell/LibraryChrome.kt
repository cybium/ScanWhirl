package com.scanwhirl.app.ui.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * uihome: a visible, draggable fast-scroll bar for the Home library.
 *
 * VISUAL_AUDIT_A §5.2 asks for 48dp targets and VISUAL_AUDIT_A X2 for identity; the
 * owner could not navigate a page of names at all. The bar draws a neutral track at
 * the end edge and a thumb sized to the visible fraction; dragging the thumb jumps
 * the parent [ScrollView] proportionally (fast scroll), and only the thumb consumes
 * touches, so the rows under the rest of the strip still tap normally.
 *
 * It is deliberately a sibling of the ScrollView (inside [LibraryViewportChrome]),
 * so it never scrolls away and never scrolls with the content.
 */
class LibraryFastScrollBar(context: Context) : View(context) {

    /** The library scroller this bar drives. */
    var scrollView: ScrollView? = null

    /** Documents currently in the list; below [MIN_DOCUMENTS] the bar stays hidden. */
    var documentCount: Int = 0
        set(value) {
            field = value
            refresh()
        }

    private val density = resources.displayMetrics.density
    private val thumbMinHeight = 36f * density
    private val trackWidth = 2f * density
    private val thumbWidth = 4f * density

    private var dragging = false
    private var dragOffset = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SwStyle.color(context, R.color.text_hologram_dim)
        alpha = 0x33
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SwStyle.color(context, R.color.text_hologram_mid)
        alpha = 0xE6
        style = Paint.Style.FILL
    }

    init {
        id = R.id.swLibraryFastScroll
        isEnabled = true
        contentDescription = context.getString(R.string.home_fast_scroll)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(0, SwStyle.dp(context, 8f), 0, SwStyle.dp(context, 96f))
        isVisible = false
    }

    /** True when at least [MIN_DOCUMENTS] rows are in a list taller than the screen. */
    val isActive: Boolean
        get() = isEnabled && documentCount >= MIN_DOCUMENTS && contentHeight() > viewportHeight()

    fun refresh() {
        isVisible = isActive
        invalidate()
    }

    private fun contentHeight(): Int =
        scrollView?.getChildAt(0)?.height ?: 0

    private fun viewportHeight(): Int {
        val sv = scrollView ?: return 0
        return (sv.height - sv.paddingTop - sv.paddingBottom).coerceAtLeast(0)
    }

    private fun maxScroll(): Int = (contentHeight() - viewportHeight()).coerceAtLeast(0)

    private fun trackTop(): Float = paddingTop.toFloat()

    private fun trackBottom(): Float = (height - paddingBottom).toFloat().coerceAtLeast(trackTop() + 1f)

    private fun thumbRect(): RectF {
        val top = trackTop()
        val bottom = trackBottom()
        val viewport = viewportHeight().toFloat()
        val content = contentHeight().toFloat()
        val track = (bottom - top).coerceAtLeast(1f)
        val ratio = if (content <= 0f) 1f else (viewport / content).coerceIn(0f, 1f)
        val thumbHeight = max(thumbMinHeight, track * ratio).coerceAtMost(track)
        val max = maxScroll()
        val fraction = if (max <= 0) 0f else (scrollView?.scrollY ?: 0).toFloat() / max
        val thumbTop = top + (track - thumbHeight) * fraction.coerceIn(0f, 1f)
        val cx = width - SwStyle.dp(context, 6f) - thumbWidth / 2f
        return RectF(cx - thumbWidth / 2f, thumbTop, cx + thumbWidth / 2f, thumbTop + thumbHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isActive) return
        val top = trackTop()
        val bottom = trackBottom()
        val cx = width - SwStyle.dp(context, 6f) - thumbWidth / 2f

        val track = RectF(
            cx - trackWidth / 2f, top,
            cx + trackWidth / 2f, bottom
        )
        canvas.drawRoundRect(track, trackWidth / 2f, trackWidth / 2f, trackPaint)

        val thumb = thumbRect()
        val radius = thumb.width() / 2f
        canvas.drawRoundRect(thumb, radius, radius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isActive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val thumb = thumbRect()
                // 8dp of slop around the 4dp thumb: a finger never lands exactly on it.
                val slop = 8f * density
                val hit = RectF(thumb)
                hit.inset(-slop, -slop)
                if (!hit.contains(event.x, event.y)) return false
                dragging = true
                dragOffset = event.y - thumb.top
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                applyDrag(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                dragOffset = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun applyDrag(y: Float) {
        val top = trackTop()
        val track = (trackBottom() - top).coerceAtLeast(1f)
        val thumb = thumbRect()
        val travel = (track - thumb.height()).coerceAtLeast(1f)
        val fraction = ((y - dragOffset - top) / travel).coerceIn(0f, 1f)
        scrollView?.scrollTo(0, (fraction * maxScroll()).roundToInt())
        invalidate()
    }

    /** Test seam and the keyboard/programmatic equivalent of dragging the thumb. */
    internal fun scrollToFraction(fraction: Float) {
        scrollView?.scrollTo(0, (fraction.coerceIn(0f, 1f) * maxScroll()).roundToInt())
        invalidate()
    }

    companion object {
        /** > ~12 documents: the brief's threshold for a fast-scroll affordance. */
        const val MIN_DOCUMENTS = 12
    }
}

/**
 * uihome: the pinned layer of the Home library — only the fast-scroll bar.
 *
 * The lead's device check rejected a floating count chip: anything drawn over the
 * list hides the first row's thumbnail and title. The document count stays as
 * plain text in the list header (inside the ScrollView); this layer holds one
 * 24dp bar strip at the end edge and nothing else, so no content is covered.
 */
class LibraryViewportChrome(context: Context) : FrameLayout(context) {

    private val bar: LibraryFastScrollBar
    private var scrollView: ScrollView? = null

    init {
        id = R.id.swLibraryChrome
        isClickable = false
        isFocusable = false
        clipChildren = false
        setWillNotDraw(true)

        bar = LibraryFastScrollBar(context)
        addView(
            bar,
            LayoutParams(SwStyle.dp(context, 24f), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        )
    }

    /** Hooks the bar to [scrollView]; nothing here draws over the rows. */
    fun attach(scrollView: ScrollView) {
        this.scrollView = scrollView
        bar.scrollView = scrollView
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> update() }
        scrollView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        update()
    }

    /** Detaches from the scroll view so a hidden tab leaves no listener behind. */
    fun release() {
        scrollView?.setOnScrollChangeListener(null)
        bar.scrollView = null
        scrollView = null
    }

    fun setDocumentCount(count: Int) {
        bar.documentCount = count
        update()
    }

    fun update() {
        bar.refresh()
    }
}
