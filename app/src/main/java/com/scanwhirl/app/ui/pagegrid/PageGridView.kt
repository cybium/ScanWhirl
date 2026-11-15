package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ItemPageGridBinding
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

    /** Which filter a cell's file should display (file-based so it stays correct mid-drag). */
    var filterFor: (File) -> ScanFilter = { ScanFilter.ORIGINAL }

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

    // Drag bookkeeping: ItemTouchHelper moves the cells; the host hears one drop.
    private var dragFrom = NO_POSITION
    private var dragTo = NO_POSITION
    private var originalElevation = 0f

    private val dragCallback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0
    ) {
        override fun isLongPressDragEnabled(): Boolean = dragEnabled

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: ViewHolder,
            target: ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == NO_POSITION || to == NO_POSITION || from == to) return false
            gridAdapter.move(from, to)
            if (dragFrom != NO_POSITION) dragTo = to
            return true
        }

        override fun onSwiped(viewHolder: ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragFrom = viewHolder.bindingAdapterPosition
                dragTo = dragFrom
                originalElevation = viewHolder.itemView.elevation
                viewHolder.itemView.elevation = 12f * resources.displayMetrics.density
                viewHolder.itemView.scaleX = 1.04f
                viewHolder.itemView.scaleY = 1.04f
                if (dragFrom != NO_POSITION) onLongPress?.invoke(dragFrom)
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.elevation = originalElevation
            viewHolder.itemView.scaleX = 1f
            viewHolder.itemView.scaleY = 1f
            val from = dragFrom
            val to = dragTo
            dragFrom = NO_POSITION
            dragTo = NO_POSITION
            if (from != NO_POSITION && to != NO_POSITION && from != to) {
                onDropped?.invoke(from, to)
            }
        }
    }

    private val dragHelper = ItemTouchHelper(dragCallback)

    init {
        layoutManager = GridLayoutManager(context, 2)
        adapter = gridAdapter
        itemAnimator = null
        dragHelper.attachToRecyclerView(this)
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

    /** Brings the cell at [index] on screen (no animation; cheap for 30+ pages). */
    fun scrollToPage(index: Int) {
        if (index in 0 until gridAdapter.itemCount) scrollToPosition(index)
    }

    /** Test seam: start the same ItemTouchHelper drag a long-press would start. */
    internal fun startDragAt(index: Int): Boolean {
        val vh = findViewHolderForAdapterPosition(index) ?: return false
        dragHelper.startDrag(vh)
        return true
    }

    /** Test seam: a full start-drag → adapter move → drop, as a finger drag would produce. */
    internal fun simulateDrag(from: Int, to: Int): Boolean {
        val vh = findViewHolderForAdapterPosition(from) ?: return false
        dragHelper.startDrag(vh)
        if (dragFrom == NO_POSITION) dragFrom = from
        val target = findViewHolderForAdapterPosition(to)
        if (target != null) {
            dragCallback.onMove(this, vh, target)
        } else {
            // Off-screen target in Robolectric: apply the same adapter move the callback would.
            gridAdapter.move(from, to)
            dragTo = to
        }
        dragCallback.clearView(this, vh)
        return true
    }

    /** Cell width in px, used as the thumbnail decode target. */
    private fun thumbTargetPx(): Int {
        val usable = if (width > 0) width else resources.displayMetrics.widthPixels
        val cell = (usable - paddingLeft - paddingRight) / spanCount
        return (cell * 3 / 2).coerceAtLeast(160)
    }

    private inner class GridAdapter : Adapter<VH>() {
        var pages: MutableList<File> = mutableListOf()

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

        override fun getItemCount() = pages.size
    }

    private inner class VH(private val binding: ItemPageGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(file: File, index: Int) {
            binding.pageNum.text = String.format(java.util.Locale.ROOT, "%d", index + 1)
            val current = currentAt(index)
            binding.root.isActivated = current
            binding.currentRing.isVisible = current
            binding.pageNum.setBackgroundResource(
                if (current) R.drawable.bg_rv_num_current else R.drawable.bg_chip_void
            )
            binding.pageNum.setTextColor(
                if (current) context.getColor(R.color.rv_on_commit) else 0xFFFFFFFF.toInt()
            )
            binding.root.contentDescription =
                context.getString(R.string.review_film_page, index + 1)
            binding.checkSelect.contentDescription =
                context.getString(R.string.review_select_page, index + 1)
            PageThumbLoader.load(
                file,
                thumbTargetPx(),
                filterFor(file),
                binding.pageThumb
            )

            // The round check exists only in selection mode; it is not a drag handle.
            binding.checkSelect.isVisible = checkboxesVisible
            binding.checkSelect.imageAlpha = if (checkedAt(index)) 255 else 0
            binding.checkSelect.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onCheckToggle?.invoke(pos)
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onTap?.invoke(pos)
            }
            // No OnLongClickListener here: it would consume the gesture before ItemTouchHelper
            // can start its drag. Selection enters from onSelectedChanged (drag start), so a
            // long-press without movement still selects; movement drags.
            // Selected state paints the cyan stroke + tinted fill via the drawable.
            binding.root.isSelected = checkedAt(index)
        }
    }
}
