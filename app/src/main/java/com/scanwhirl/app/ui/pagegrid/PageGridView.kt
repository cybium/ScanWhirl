package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.databinding.ItemPageGridBinding
import com.scanwhirl.app.util.ScanFilter
import java.io.File

/**
 * The one page-grid component used by both the saved-document viewer and the
 * scan review screen: a vertical 2-column grid of downsampled, cached thumbs.
 *
 * Reordering is long-press-and-drag (ItemTouchHelper provides edge
 * auto-scroll natively). A long-press released without moving fires
 * [onLongPressMenu] instead; a drag that actually moved fires [onDropped]
 * once, with the start and final indices.
 */
class PageGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    /** Called with the page index when a cell is tapped. */
    var onTap: ((Int) -> Unit)? = null

    /** Long-press released without moving: open the page menu at this index. */
    var onLongPressMenu: ((Int) -> Unit)? = null

    /** Drag finished after moving: indices are pre-drag positions of the item. */
    var onDropped: ((from: Int, to: Int) -> Unit)? = null

    /** Which filter a cell's file should display (file-based so it stays correct mid-drag). */
    var filterFor: (File) -> ScanFilter = { ScanFilter.ORIGINAL }

    /** Multi-select: while true, cells not selected are dimmed and outlined. */
    var selectionMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    /** Whether the cell at [index] is selected (read only while [selectionMode]). */
    var selectedAt: (Int) -> Boolean = { false }

    /** Set false to disable long-press drag (e.g. while multi-selecting). */
    var dragEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) touchHelper.attachToRecyclerView(null)
            else touchHelper.attachToRecyclerView(this)
        }

    private val gridAdapter = GridAdapter()
    private var dragFrom = NO_POSITION
    private var dragMoved = false

    private val touchHelper = ItemTouchHelper(object :
        ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun getMovementFlags(rv: RecyclerView, vh: ViewHolder): Int =
            if (dragEnabled) super.getMovementFlags(rv, vh) else makeFlag(0, 0)

        override fun isLongPressDragEnabled(): Boolean = dragEnabled

        override fun onMove(rv: RecyclerView, vh: ViewHolder, target: ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == NO_POSITION || to == NO_POSITION) return false
            gridAdapter.move(from, to)
            dragMoved = true
            return true
        }

        override fun onSwiped(vh: ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(vh: ViewHolder?, actionState: Int) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragFrom = vh?.bindingAdapterPosition ?: NO_POSITION
                dragMoved = false
            }
            super.onSelectedChanged(vh, actionState)
        }

        override fun clearView(rv: RecyclerView, vh: ViewHolder) {
            super.clearView(rv, vh)
            val from = dragFrom
            val to = vh.bindingAdapterPosition
            dragFrom = NO_POSITION
            val moved = dragMoved
            dragMoved = false
            if (from == NO_POSITION || to == NO_POSITION) return
            if (moved && from != to) onDropped?.invoke(from, to)
            else if (!moved) onLongPressMenu?.invoke(to)
        }
    })

    init {
        layoutManager = GridLayoutManager(context, 2)
        adapter = gridAdapter
        itemAnimator = null
        touchHelper.attachToRecyclerView(this)
    }

    /** Replaces the displayed pages. The list is copied; callers may pass a temp. */
    fun submit(pages: List<File>) {
        gridAdapter.pages = pages.toMutableList()
        gridAdapter.notifyDataSetChanged()
    }

    /** Re-binds every cell (filter or file content changed in place). */
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
        // Longest thumb side ~1.5x cell width keeps A4 pages crisp at cell size.
        return (cell * 3 / 2).coerceAtLeast(160)
    }

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
            val selected = selectionMode && selectedAt(index)
            binding.root.alpha = if (selectionMode && !selected) 0.45f else 1f
            binding.root.strokeWidth =
                if (selected) (3 * resources.displayMetrics.density).toInt() else 0
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onTap?.invoke(pos)
            }
            // Long-press is owned by ItemTouchHelper (drag); a stationary
            // long-press comes back through clearView -> onLongPressMenu.
        }
    }
}
