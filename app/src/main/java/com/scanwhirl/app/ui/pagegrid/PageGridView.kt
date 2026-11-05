package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ItemPageGridBinding
import com.scanwhirl.app.util.ScanFilter
import java.io.File
import kotlin.math.abs

/**
 * The one page-grid component used by the saved-document viewer and the scan
 * review screen: a vertical 2-column grid of downsampled, cached thumbs in
 * fixed 3:4 cells.
 *
 * Gestures are deliberately separate:
 *  - tap the cell            -> [onTap] (open the page)
 *  - tap the checkbox        -> [onCheckToggle] (multi-select; no drag/menu)
 *  - drag the grip handle    -> reorder, then [onDropped] once with the
 *                               pre-drag and final indices
 *
 * Reordering is a real two-dimensional drag: the grabbed thumbnail floats
 * under the finger and the cell under its center is the drop target. This is
 * why a page can be dragged left/right as well as up/down (ItemTouchHelper is
 * one-dimensional and cannot do that reliably in a grid).
 */
class PageGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    /** Called with the page index when a cell is tapped. */
    var onTap: ((Int) -> Unit)? = null

    /** Called when a cell's checkbox is tapped (host owns the selected set). */
    var onCheckToggle: ((Int) -> Unit)? = null

    /** Drag finished after moving: indices are pre-drag positions of the item. */
    var onDropped: ((from: Int, to: Int) -> Unit)? = null

    /** Which filter a cell's file should display (file-based so it stays correct mid-drag). */
    var filterFor: (File) -> ScanFilter = { ScanFilter.ORIGINAL }

    /** Whether the cell at [index] shows a ticked checkbox (host state). */
    var checkedAt: (Int) -> Boolean = { false }

    /** Set false to disable reordering (the grip simply stops responding). */
    var dragEnabled: Boolean = true

    private val gridAdapter = GridAdapter()

    // --- 2D drag state -------------------------------------------------
    private var dragVH: ViewHolder? = null
    private var dragFrom = NO_POSITION
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private var originalElevation = 0f
    private var dragMoved = false
    private val rvLocation = IntArray(2)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        layoutManager = GridLayoutManager(context, 2)
        adapter = gridAdapter
        itemAnimator = null
    }

    /** Replaces the displayed pages. The list is copied; callers may pass a temp. */
    fun submit(pages: List<File>) {
        gridAdapter.pages = pages.toMutableList()
        gridAdapter.notifyDataSetChanged()
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

    /** Index of the top-most visible cell (used by "save this page"). */
    fun firstVisiblePosition(): Int =
        (layoutManager as GridLayoutManager).findFirstVisibleItemPosition()

    /** Cell width in px, used as the thumbnail decode target. */
    private fun thumbTargetPx(): Int {
        val usable = if (width > 0) width else resources.displayMetrics.widthPixels
        val cell = (usable - paddingLeft - paddingRight) / 2
        return (cell * 3 / 2).coerceAtLeast(160)
    }

    // ------------------------------------------------------------------
    // Touch handling: start only from the grip, track in 2D, drop on release
    // ------------------------------------------------------------------

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
            val itemView = findChildViewUnder(e.x, e.y)
            if (itemView != null && isPointOnGrip(itemView, e.x, e.y)) {
                val vh = findContainingViewHolder(itemView) ?: return super.onInterceptTouchEvent(e)
                if (beginDrag(vh, e)) {
                    // Consume the rest of the gesture ourselves.
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dragVH != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    trackDrag(e)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    endDrag(cancelled = false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    endDrag(cancelled = true)
                    return true
                }
                else -> return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** True when (x, y) — RecyclerView coordinates — is over this cell's grip. */
    private fun isPointOnGrip(itemView: View, x: Float, y: Float): Boolean {
        val grip = itemView.findViewById<View>(R.id.dragHandle) ?: return false
        if (grip.width == 0 || grip.height == 0) return false
        val gripLoc = IntArray(2)
        grip.getLocationOnScreen(gripLoc)
        getLocationOnScreen(rvLocation)
        val left = gripLoc[0] - rvLocation[0]
        val top = gripLoc[1] - rvLocation[1]
        return x >= left && x <= left + grip.width && y >= top && y <= top + grip.height
    }

    private fun beginDrag(vh: ViewHolder, e: MotionEvent): Boolean {
        val pos = vh.bindingAdapterPosition
        if (pos == NO_POSITION) return false
        val view = vh.itemView
        dragVH = vh
        dragFrom = pos
        dragMoved = false
        dragStartRawX = e.rawX
        dragStartRawY = e.rawY

        // Offset from the finger to the cell's center keeps the thumbnail
        // under the finger on the first frame instead of jumping.
        getLocationOnScreen(rvLocation)
        val fingerX = e.rawX - rvLocation[0]
        val fingerY = e.rawY - rvLocation[1]
        grabOffsetX = fingerX - (view.left + view.width / 2f)
        grabOffsetY = fingerY - (view.top + view.height / 2f)

        originalElevation = view.elevation
        view.elevation = 12f * resources.displayMetrics.density
        view.scaleX = 1.04f
        view.scaleY = 1.04f
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun trackDrag(e: MotionEvent) {
        val vh = dragVH ?: return
        val dx = e.rawX - dragStartRawX
        val dy = e.rawY - dragStartRawY
        if (!dragMoved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragMoved = true
        positionUnderFinger(vh, e.rawX, e.rawY)
    }

    /** Puts the cell's visual center where the finger is (minus the grab offset). */
    private fun positionUnderFinger(vh: ViewHolder, rawX: Float, rawY: Float) {
        val view = vh.itemView
        getLocationOnScreen(rvLocation)
        val fingerX = rawX - rvLocation[0]
        val fingerY = rawY - rvLocation[1]
        view.translationX = (fingerX - grabOffsetX) - (view.left + view.width / 2f)
        view.translationY = (fingerY - grabOffsetY) - (view.top + view.height / 2f)
    }

    private fun endDrag(cancelled: Boolean) {
        val vh = dragVH ?: return
        val view = vh.itemView
        val from = dragFrom

        // The drop target is whichever cell sits under the floating center.
        val centerX = view.left + view.width / 2f + view.translationX
        val centerY = view.top + view.height / 2f + view.translationY
        val to = targetIndexAt(centerX, centerY, from)

        resetDragVisuals(vh)
        dragVH = null
        dragFrom = NO_POSITION
        parent?.requestDisallowInterceptTouchEvent(false)

        if (!cancelled && dragMoved && to != NO_POSITION && to != from) {
            gridAdapter.move(from, to)
            onDropped?.invoke(from, to)
        }
    }

    private fun resetDragVisuals(vh: ViewHolder) {
        val view = vh.itemView
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.elevation = originalElevation
    }

    /**
     * Which adapter position owns the point, in two dimensions. A cell under
     * the point wins; otherwise the nearest cell center does. Returns [from]
     * while the point is still inside the dragged cell so a tiny wobble
     * doesn't reorder anything.
     */
    private fun targetIndexAt(cx: Float, cy: Float, from: Int): Int {
        val fromView = findViewHolderForAdapterPosition(from)?.itemView
        if (fromView != null &&
            cx >= fromView.left && cx <= fromView.right &&
            cy >= fromView.top && cy <= fromView.bottom
        ) {
            return from
        }
        var nearest = NO_POSITION
        var bestDist = Float.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val pos = getChildAdapterPosition(child)
            if (pos == NO_POSITION || pos == from) continue
            if (cx >= child.left && cx <= child.right &&
                cy >= child.top && cy <= child.bottom
            ) {
                return pos
            }
            val dx = cx - (child.left + child.right) / 2f
            val dy = cy - (child.top + child.bottom) / 2f
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                nearest = pos
            }
        }
        return nearest
    }

    /** Test seam: the drop index for a point, without a real gesture. */
    internal fun targetForPoint(cx: Float, cy: Float, from: Int): Int =
        targetIndexAt(cx, cy, from)

    private inner class GridAdapter : Adapter<VH>() {
        var pages: MutableList<File> = mutableListOf()

        fun move(from: Int, to: Int) {
            if (from !in pages.indices || to !in pages.indices || from == to) return
            pages.add(to, pages.removeAt(from))
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPageGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(pages[position], position)
        }

        override fun getItemCount() = pages.size
    }

    private inner class VH(private val binding: ItemPageGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(file: File, index: Int) {
            binding.pageNum.text = String.format(java.util.Locale.ROOT, "%d", index + 1)
            PageThumbLoader.load(
                file,
                thumbTargetPx(),
                filterFor(file),
                binding.pageThumb
            )

            // Checkbox = selection only.
            binding.checkSelect.isChecked = checkedAt(index)
            binding.checkSelect.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onCheckToggle?.invoke(pos)
            }

            // The grip is a visual affordance only: the RecyclerView intercepts
            // the gesture itself (onInterceptTouchEvent) when a touch lands here.
            binding.dragHandle.isClickable = false
            binding.dragHandle.isFocusable = false

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onTap?.invoke(pos)
            }
            binding.root.strokeWidth =
                if (checkedAt(index)) (3 * resources.displayMetrics.density).toInt() else 0
        }
    }
}
