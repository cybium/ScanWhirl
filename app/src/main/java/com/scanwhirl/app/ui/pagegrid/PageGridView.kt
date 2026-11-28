package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ItemPageGridBinding
import com.scanwhirl.app.ui.kit.SwMotion
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.util.ScanFilter
import java.io.File

/**
 * v2 UI: Organize pages grid (owner revision) — the one page grid used by the saved-document
 * viewer and the scan review screen.
 *
 *  - Normal state: a clean thumbnail with its page number. Tap opens the page.
 *  - Long-press enters the host's selection mode and selects that page; dragging after the
 *    long-press reorders (ItemTouchHelper, both axes) and reports one [onDropped].
 *  - The small round check is shown only while [checkboxesVisible] is true (selection mode).
 *
 * Scan review keeps checkboxes visible in its Arrange mode; the saved-document viewer turns
 * them on only after a long-press.
 */
class PageGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    /** Called with the page index when a cell is tapped. */
    var onTap: ((Int) -> Unit)? = null

    /** Called when a cell's check is tapped (host owns the selected set). */
    var onCheckToggle: ((Int) -> Unit)? = null

    /** Long-press: the host enters selection mode and selects this page. */
    var onLongPress: ((Int) -> Unit)? = null

    /** Drag finished after moving: indices are pre-drag positions of the item. */
    var onDropped: ((from: Int, to: Int) -> Unit)? = null

    /** One atomic group drop. Files are identities, never visual slot numbers. */
    var onOrderDropped: ((order: List<File>, moved: List<File>) -> Unit)? = null
    var onMoveSelected: (() -> Unit)? = null
    var onRotate: ((Int) -> Unit)? = null
    var onDelete: ((Int) -> Unit)? = null
    var provenanceFor: (File, Int) -> Int? = { _, _ -> null }
    var identityFor: (File) -> String = { it.path }

    /** Which filter a cell's file should display (file-based so it stays correct mid-drag). */
    var filterFor: (File) -> ScanFilter = { ScanFilter.ORIGINAL }

    /**
     * Identity of the submitted page set. Pages are immutable: every edit writes a new
     * `r_<n>` replacement, so the set itself is the revision and binding never stats a file.
     */
    private var pageRevision: String = "0"

    /** Whether the cell at [index] shows a ticked check (host state). */
    var checkedAt: (Int) -> Boolean = { false }

    /** Set false to disable long-press reordering. */
    var dragEnabled: Boolean = true

    /** Whether cells show their round selection check (selection mode). */
    var checkboxesVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            gridAdapter.notifyDataSetChanged()
        }

    /**
     * Review slice 1: the page shown in Review's Focus mode. It wears the accent ring
     * (`state_activated`) and an accent number badge.
     */
    var currentAt: (Int) -> Boolean = { false }

    /** Columns in the grid (2 by default; Review's Arrange uses 3 on wide phones). */
    var spanCount: Int
        get() = (layoutManager as GridLayoutManager).spanCount
        set(value) {
            val lm = layoutManager as GridLayoutManager
            if (lm.spanCount == value) return
            lm.spanCount = value.coerceAtLeast(1)
            gridAdapter.notifyDataSetChanged()
        }

    private val gridAdapter = GridAdapter()

    private data class Drag(
        val before: List<File>,
        val moving: List<File>,
        val anchor: File,
        val preview: FrameLayout,
        val host: ViewGroup,
        val bitmap: Bitmap,
        val offsetX: Float,
        val offsetY: Float,
        val origin: RectF,
        var lastTargetX: Float,
        var lastTargetY: Float
    )

    private var drag: Drag? = null
    private var downIndex = NO_POSITION
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var lastFrame = 0L
    private var settling = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val hold = Runnable { beginDrag(downIndex) }
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (drag == null) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(0, 32) / 1000f
            lastFrame = now
            val band = px(48f)
            val velocity = when {
                fingerX !in 0f..width.toFloat() || fingerY !in 0f..height.toFloat() -> 0f
                fingerY < paddingTop + band -> -((paddingTop + band - fingerY) / band).coerceIn(0f, 1f)
                fingerY > height - paddingBottom - band ->
                    ((fingerY - height + paddingBottom + band) / band).coerceIn(0f, 1f)
                else -> 0f
            } * px(600f)
            if (velocity != 0f) {
                scrollBy(0, (velocity * dt).toInt())
                updateTarget()
            }
            postOnAnimation(this)
        }
    }

    private fun px(dp: Float) = SwStyle.dp(context, dp).toFloat()

    /**
     * Intercept only AFTER the configurable hold. Until then scrolling belongs to RecyclerView.
     * A second pointer, ACTION_CANCEL, Back or detach rolls the preview back without a host call.
     */
    private val touches = object : SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (settling) return true
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                removeCallbacks(hold)
                downX = e.x
                downY = e.y
                fingerX = e.x
                fingerY = e.y
                downIndex = findChildViewUnder(e.x, e.y)?.let(::getChildAdapterPosition) ?: NO_POSITION
                if (dragEnabled && downIndex != NO_POSITION) {
                    postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
            } else if (drag == null) {
                if (e.actionMasked != MotionEvent.ACTION_MOVE ||
                    kotlin.math.hypot(e.x - downX, e.y - downY) > touchSlop) removeCallbacks(hold)
            }
            return drag != null
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            fingerX = e.x
            fingerY = e.y
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    followFinger()
                    updateTarget()
                }
                MotionEvent.ACTION_UP -> finishDrag(valid = e.x in 0f..width.toFloat() &&
                    e.y in paddingTop.toFloat()..(height - paddingBottom).toFloat())
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelDrag()
            }
        }
    }

    private fun beginDrag(index: Int): Boolean {
        if (!dragEnabled || settling || drag != null) return false
        val cell = findViewHolderForAdapterPosition(index)?.itemView ?: return false
        if (cell.width <= 0 || cell.height <= 0) return false
        val before = pageOrder()
        val anchor = before.getOrNull(index) ?: return false
        val selected = before.indices.filter { checkedAt(it) }
        // Never silently replace an existing selection. Select this page; hold again to drag.
        if (selected.isNotEmpty() && index !in selected) {
            onLongPress?.invoke(index)
            return false
        }
        val moving = if (selected.isEmpty()) listOf(anchor) else selected.map { before[it] }
        val bitmap = Bitmap.createBitmap(cell.width, cell.height, Bitmap.Config.ARGB_8888)
        cell.draw(Canvas(bitmap))
        val host = rootView as? ViewGroup ?: return false.also { bitmap.recycle() }
        val preview = FrameLayout(context).apply {
            clipChildren = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = SwStyle.card(context)
            elevation = px(8f)
        }
        if (moving.size > 1) {
            for (level in 2 downTo 1) {
                preview.addView(View(context).apply { background = SwStyle.card(context) },
                    FrameLayout.LayoutParams(cell.width, cell.height).apply {
                        leftMargin = px(level * 4f).toInt()
                        topMargin = px(level * 4f).toInt()
                    })
            }
        }
        preview.addView(ImageView(context).apply { setImageBitmap(bitmap) },
            FrameLayout.LayoutParams(cell.width, cell.height))
        if (moving.size > 1) {
            preview.addView(SwStyle.text(context, SwType.BODY_SP,
                SwStyle.color(context, R.color.sw_disc_ink), bold = true).apply {
                text = moving.size.toString()
                setPadding(px(8f).toInt(), px(4f).toInt(), px(8f).toInt(), px(4f).toInt())
                background = SwStyle.rounded(context, SwStyle.color(context, R.color.cyan_primary), 12f)
            }, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            })
        }
        val rootAt = IntArray(2).also(host::getLocationOnScreen)
        val at = IntArray(2).also(cell::getLocationOnScreen)
        val left = (at[0] - rootAt[0]).toFloat()
        val top = (at[1] - rootAt[1]).toFloat()
        preview.measure(MeasureSpec.makeMeasureSpec(cell.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cell.height, MeasureSpec.EXACTLY))
        preview.layout(left.toInt(), top.toInt(), left.toInt() + cell.width, top.toInt() + cell.height)
        // Pivot at the touch anchor: magnification never pulls the page away from the finger.
        preview.pivotX = (fingerX - cell.left).coerceIn(0f, cell.width.toFloat())
        preview.pivotY = (fingerY - cell.top).coerceIn(0f, cell.height.toFloat())
        host.overlay.add(preview)
        drag = Drag(before, moving, anchor, preview, host, bitmap,
            fingerX - cell.left, fingerY - cell.top,
            RectF(left, top, left + cell.width, top + cell.height), fingerX, fingerY)
        // Legacy review/viewer callers use long hold to enter selection. The standalone
        // organizer taps to select and never changes selection merely because it lifted.
        if (onOrderDropped == null) onLongPress?.invoke(index)
        hideMovingCells()
        preview.animate().scaleX(1.08f).scaleY(1.08f)
            .setDuration(SwMotion.duration(context, SwMotion.SHORT))
            .setInterpolator(SwMotion.emphasized()).start()
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.DRAG_START
            else HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        stopScroll()
        lastFrame = SystemClock.uptimeMillis()
        postOnAnimation(edgeScroll)
        return true
    }

    private fun followFinger() {
        val d = drag ?: return
        val gridAt = IntArray(2).also(::getLocationOnScreen)
        val hostAt = IntArray(2).also(d.host::getLocationOnScreen)
        d.preview.x = gridAt[0] - hostAt[0] + fingerX - d.offsetX
        d.preview.y = gridAt[1] - hostAt[1] + fingerY - d.offsetY
    }

    /** 50% crossing of the destination cell plus 8dp spatial hysteresis. */
    private fun updateTarget() {
        val d = drag ?: return
        if (fingerX !in 0f..width.toFloat() || fingerY !in 0f..height.toFloat()) return
        val x = fingerX - d.offsetX + d.preview.width / 2f
        val y = fingerY - d.offsetY + d.preview.height / 2f
        val cell = (0 until childCount).map(::getChildAt).firstOrNull {
            x >= it.left && x <= it.right && y >= it.top && y <= it.bottom &&
                pageAt(getChildAdapterPosition(it)) !in d.moving
        } ?: return
        val target = getChildAdapterPosition(cell)
        if (target == NO_POSITION) return
        val old = gridAdapter.pages.indexOf(d.anchor)
        val forward = target > old
        val horizontal = old / spanCount == target / spanCount
        val center = if (horizontal) (cell.left + cell.right) / 2f else (cell.top + cell.bottom) / 2f
        val coordinate = if (horizontal) x else y
        if (if (forward) coordinate < center + px(8f) else coordinate > center - px(8f)) return
        val rest = gridAdapter.pages.filterNot { it in d.moving }.toMutableList()
        val at = rest.indexOf(gridAdapter.pages[target]) + if (forward) 1 else 0
        rest.addAll(at.coerceIn(0, rest.size), d.moving)
        previewOrder(rest)
        d.lastTargetX = fingerX
        d.lastTargetY = fingerY
    }

    private fun previewOrder(order: List<File>) {
        order.forEachIndexed { target, file ->
            val from = gridAdapter.pages.indexOf(file)
            if (from != target) gridAdapter.move(from, target)
        }
        // Payload binds update labels/selection only, not thumbnail decoding.
        gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount, POSITION_PAYLOAD)
        post { hideMovingCells() }
    }

    private fun hideMovingCells() {
        val moving = drag?.moving.orEmpty()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.alpha = if (pageAt(getChildAdapterPosition(child)) in moving) 0f else 1f
        }
    }

    fun cancelDrag(): Boolean {
        removeCallbacks(hold)
        if (drag == null) return false
        finishDrag(false)
        return true
    }

    private fun finishDrag(valid: Boolean) {
        val d = drag ?: return
        removeCallbacks(edgeScroll)
        removeCallbacks(hold)
        val after = pageOrder()
        val changed = valid && after != d.before
        if (!changed) previewOrder(d.before)
        drag = null
        settling = true
        parent?.requestDisallowInterceptTouchEvent(false)
        val destination = findViewHolderForAdapterPosition(gridAdapter.pages.indexOf(d.anchor))?.itemView
        val rootAt = IntArray(2).also(d.host::getLocationOnScreen)
        val at = destination?.let { IntArray(2).also(it::getLocationOnScreen) }
        val endX = at?.let { (it[0] - rootAt[0]).toFloat() } ?: d.origin.left
        val endY = at?.let { (it[1] - rootAt[1]).toFloat() } ?: d.origin.top
        val done = {
            d.host.overlay.remove(d.preview)
            d.bitmap.recycle()
            settling = false
            hideMovingCells()
        }
        if (SwMotion.enabled(context)) {
            d.preview.animate().x(endX).y(endY).scaleX(1f).scaleY(1f).z(px(1f))
                .setDuration(SwMotion.duration(context, SwMotion.SETTLE))
                .setInterpolator(SwMotion.standard()).withEndAction { done() }.start()
        } else done()
        if (changed) {
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY)
            if (onOrderDropped != null) onOrderDropped?.invoke(after, d.moving)
            else onDropped?.invoke(d.before.indexOf(d.anchor), after.indexOf(d.anchor))
        }
    }

    init {
        layoutManager = GridLayoutManager(context, 2)
        adapter = gridAdapter
        itemAnimator = DefaultItemAnimator().apply {
            moveDuration = SwMotion.duration(context, SwMotion.MEDIUM)
            changeDuration = 0L
            supportsChangeAnimations = false
        }
        addOnItemTouchListener(touches)
        clipToPadding = false
        setPadding(paddingLeft, paddingTop, paddingRight, maxOf(paddingBottom, px(24f).toInt()))
    }

    override fun onDetachedFromWindow() {
        cancelDrag()
        removeCallbacks(hold)
        removeCallbacks(edgeScroll)
        super.onDetachedFromWindow()
    }

    /** Replaces the displayed pages. The list is copied; callers may pass a temp. */
    fun submit(pages: List<File>) {
        cancelDrag()
        gridAdapter.pages = pages.toMutableList()
        pageRevision = revisionOf(pages)
        gridAdapter.notifyDataSetChanged()
    }

    private fun revisionOf(pages: List<File>): String {
        var hash = 17
        for (page in pages) hash = hash * 31 + page.path.hashCode()
        return Integer.toHexString(hash)
    }

    /** Re-binds every cell (selection, filter or file content changed in place). */
    fun refresh() {
        gridAdapter.notifyDataSetChanged()
    }

    fun pageCount(): Int = gridAdapter.itemCount

    fun pageAt(index: Int): File? = gridAdapter.pages.getOrNull(index)

    /** Current page order (post-drag). */
    fun pageOrder(): List<File> = gridAdapter.pages.toList()

    /** Test/host helper: reorder without a gesture. */
    fun moveItem(from: Int, to: Int) {
        gridAdapter.move(from, to)
    }

    /**
     * Accessibility reorder: the same adapter move a drag performs, followed by the host's
     * one [onDropped] so the draft order is persisted, and an announcement of the new slot.
     */
    private fun moveBy(from: Int, to: Int) {
        if (from !in 0 until gridAdapter.itemCount || to !in 0 until gridAdapter.itemCount) return
        if (from == to) return
        val moving = gridAdapter.pages[from]
        gridAdapter.move(from, to)
        // Positions changed: re-bind so every tile announces its new "Page X of Y".
        gridAdapter.notifyDataSetChanged()
        if (onOrderDropped != null) onOrderDropped?.invoke(pageOrder(), listOf(moving))
        else onDropped?.invoke(from, to)
        post {
            scrollToPosition(to)
            findViewHolderForAdapterPosition(to)?.itemView?.let {
                it.requestFocus()
                it.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
        announceForAccessibility(
            context.getString(R.string.a11y_moved_to, to + 1, gridAdapter.itemCount)
        )
    }

    /** Index of the top-most visible cell (used by "save this page"). */
    fun firstVisiblePosition(): Int =
        (layoutManager as GridLayoutManager).findFirstVisibleItemPosition()

    /** Brings the cell at [index] on screen (no animation; cheap for 30+ pages). */
    fun scrollToPage(index: Int) {
        if (index in 0 until gridAdapter.itemCount) scrollToPosition(index)
    }

    /** Test seam: start the same ItemTouchHelper drag a long-press would start. */
    internal fun startDragAt(index: Int): Boolean {
        val cell = findViewHolderForAdapterPosition(index)?.itemView ?: return false
        fingerX = (cell.left + cell.right) / 2f
        fingerY = (cell.top + cell.bottom) / 2f
        return beginDrag(index)
    }

    /** Test seam: a full start-drag → adapter move → drop, as a finger drag would produce. */
    internal fun simulateDrag(from: Int, to: Int): Boolean {
        if (!startDragAt(from)) return false
        val d = drag ?: return false
        val rest = d.before.filterNot { it in d.moving }.toMutableList()
        rest.addAll(to.coerceIn(0, rest.size), d.moving)
        previewOrder(rest)
        finishDrag(true)
        return true
    }

    internal fun previewDragTo(to: Int) {
        val d = drag ?: return
        val rest = d.before.filterNot { it in d.moving }.toMutableList()
        rest.addAll(to.coerceIn(0, rest.size), d.moving)
        previewOrder(rest)
    }

    /** Cell width in px, used as the thumbnail decode target. */
    private fun thumbTargetPx(): Int {
        val usable = if (width > 0) width else resources.displayMetrics.widthPixels
        val cell = (usable - paddingLeft - paddingRight) / spanCount
        return (cell * 3 / 2).coerceAtLeast(160)
    }

    private inner class GridAdapter : Adapter<VH>() {
        var pages: MutableList<File> = mutableListOf()
        private val ids = mutableMapOf<String, Long>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long =
            ids.getOrPut(identityFor(pages[position])) { ids.size.toLong() }

        fun move(from: Int, to: Int) {
            if (from !in pages.indices || to !in pages.indices || from == to) return
            pages.add(to, pages.removeAt(from))
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemPageGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // The glass card face: set in code (MaterialCardView drops XML backgrounds).
            binding.root.setBackgroundResource(R.drawable.bg_glass_card_ripple)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(pages[position], position)
        }

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) holder.bind(pages[position], position)
            else holder.bindLabels(pages[position], position)
        }

        override fun getItemCount() = pages.size
    }

    private inner class VH(private val binding: ItemPageGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val provenance = SwStyle.text(context, SwType.CAPTION_SP,
            SwStyle.color(context, R.color.text_hologram_mid), metadata = true).apply {
            gravity = android.view.Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            val column = binding.pageNum.parent as LinearLayout
            // Selection lives BELOW the artwork, with an empty outlined state, not a solid disc.
            (binding.checkSelectTouch.parent as ViewGroup).removeView(binding.checkSelectTouch)
            column.removeView(binding.pageNum)
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            labels.addView(binding.pageNum, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            labels.addView(binding.checkSelectTouch)
            column.addView(labels)
            column.addView(provenance, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            binding.pageNum.typeface = SwType.regular(context)
            binding.pageNum.textSize = SwType.BODY_SP
            binding.root.minimumHeight = px(48f).toInt()
            binding.root.cardElevation = px(1f)
            binding.root.radius = px(SwStyle.RADIUS_CARD_DP)
            binding.checkSelect.imageTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(context, R.color.cyan_primary))
            binding.checkSelect.background = SwStyle.rounded(context, Color.TRANSPARENT, 999f,
                SwStyle.color(context, R.color.sw_boundary_functional))
        }

        fun bindLabels(file: File, index: Int) {
            val originalIndex = drag?.before?.indexOf(file)?.takeIf { it >= 0 } ?: index
            val selected = checkedAt(originalIndex)
            binding.pageNum.text = (index + 1).toString()
            val was = provenanceFor(file, index)
            provenance.text = was?.let { context.getString(R.string.j5_was_page, it) }
            provenance.isVisible = was != null
            binding.root.contentDescription = context.getString(
                if (selected) R.string.a11y_page_of_selected else R.string.a11y_page_of,
                index + 1, gridAdapter.itemCount) +
                (was?.let { ", " + context.getString(R.string.j5_original_page, it) } ?: "")
            binding.root.isSelected = selected
            binding.root.alpha = if (file in drag?.moving.orEmpty()) 0f else 1f
            binding.checkSelectTouch.isVisible = checkboxesVisible
            binding.checkSelect.isVisible = checkboxesVisible
            binding.checkSelect.imageAlpha = if (selected) 255 else 0
        }

        fun bind(file: File, index: Int) {
            bindLabels(file, index)
            val total = gridAdapter.itemCount
            val selected = checkedAt(index)
            binding.pageNum.text = String.format(java.util.Locale.ROOT, "%d", index + 1)
            val current = currentAt(index)
            binding.root.isActivated = current
            binding.currentRing.isVisible = current
            // v2 UI: 07_reader_dock — page identity lives under the paper, never on top of it:
            // a mid-grey tabular number that turns accent (and bold) for Review's current page.
            binding.pageNum.setBackgroundResource(0)
            binding.pageNum.setTextColor(
                context.getColor(
                    if (current) R.color.cyan_primary else R.color.text_hologram_mid
                )
            )
            binding.pageNum.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            binding.root.contentDescription = context.getString(
                if (selected) R.string.a11y_page_of_selected else R.string.a11y_page_of,
                index + 1,
                total
            )
            provenanceFor(file, index)?.let {
                binding.root.contentDescription = binding.root.contentDescription.toString() +
                    ", " + context.getString(R.string.j5_original_page, it)
            }
            binding.checkSelectTouch.contentDescription =
                context.getString(R.string.a11y_select_page_of, index + 1, total)
            PageThumbLoader.load(
                file,
                thumbTargetPx(),
                filterFor(file),
                binding.pageThumb,
                revision = pageRevision
            )

            // The round check exists only in selection mode; it is not a drag handle.
            binding.checkSelectTouch.isVisible = checkboxesVisible
            binding.checkSelect.isVisible = checkboxesVisible
            binding.checkSelect.imageAlpha = if (selected) 255 else 0
            binding.checkSelectTouch.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onCheckToggle?.invoke(pos)
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onTap?.invoke(pos)
            }
            // Drag reorder has an accessibility twin: Move up / Move down in the local
            // context menu, so pages reorder without a long-press-and-drag gesture.
            ViewCompat.setAccessibilityDelegate(binding.root, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val pos = bindingAdapterPosition
                    // UX-L4-01: selection has a TalkBack route, not just a long-press.
                    if (pos != NO_POSITION) {
                        info.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                R.id.ux130_l4_a11y_select_page,
                                context.getString(R.string.ux130_l4_select_page)
                            )
                        )
                    }
                    if (dragEnabled && pos != NO_POSITION) {
                        listOf(
                            R.string.j5_move_first to R.string.j5_move_first,
                            R.string.j5_move_last to R.string.j5_move_last,
                            R.string.j5_move_selected to R.string.j5_move_selected,
                            R.string.a11y_rotate_cw to R.string.a11y_rotate_cw,
                            R.string.j5_delete_page to R.string.j5_delete_page
                        ).forEach { (id, label) ->
                            info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                id, context.getString(label)))
                        }
                        if (pos > 0) {
                            info.addAction(
                                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                    R.id.a11y_move_up, context.getString(R.string.a11y_move_up)
                                )
                            )
                        }
                        if (pos < gridAdapter.itemCount - 1) {
                            info.addAction(
                                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                    R.id.a11y_move_down, context.getString(R.string.a11y_move_down)
                                )
                            )
                        }
                    }
                }

                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    val pos = bindingAdapterPosition
                    if (pos != NO_POSITION) {
                        when (action) {
                            R.string.j5_move_first -> { moveBy(pos, 0); return true }
                            R.string.j5_move_last -> { moveBy(pos, gridAdapter.itemCount - 1); return true }
                            R.string.j5_move_selected -> { onMoveSelected?.invoke(); return true }
                            R.string.a11y_rotate_cw -> { onRotate?.invoke(pos); return true }
                            R.string.j5_delete_page -> { onDelete?.invoke(pos); return true }
                            R.id.ux130_l4_a11y_select_page -> {
                                // Enter selection on the first actuation; toggle afterwards.
                                if (!checkboxesVisible) onLongPress?.invoke(pos) else onCheckToggle?.invoke(pos)
                                return true
                            }
                            R.id.a11y_move_up -> if (pos > 0) {
                                moveBy(pos, pos - 1)
                                return true
                            }
                            R.id.a11y_move_down -> if (pos < gridAdapter.itemCount - 1) {
                                moveBy(pos, pos + 1)
                                return true
                            }
                        }
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
            // No OnLongClickListener here: it would consume the gesture before ItemTouchHelper
            // can start its drag. Selection enters from onSelectedChanged (drag start), so a
            // long-press without movement still selects; movement drags.
            // Selected state paints the cyan stroke + tinted fill via the drawable.
            binding.root.isSelected = selected
        }
    }

    private companion object {
        const val POSITION_PAYLOAD = "position"
    }
}
