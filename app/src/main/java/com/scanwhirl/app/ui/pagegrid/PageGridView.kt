package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.databinding.ItemPageGridBinding
import com.scanwhirl.app.util.ScanFilter
import java.io.File

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
 * Long-press no longer starts a drag and no longer opens a menu, so a drag can
 * never be mistaken for a tap. Page actions live in the host's toolbar menu.
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

    /** Set false to disable reordering (the handle simply stops responding). */
    var dragEnabled: Boolean = true

    private val gridAdapter = GridAdapter()
    private var dragFrom = NO_POSITION
    private var dragMoved = false

    private val touchHelper = ItemTouchHelper(object :
        ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun getMovementFlags(rv: RecyclerView, vh: ViewHolder): Int =
            if (dragEnabled) super.getMovementFlags(rv, vh) else makeFlag(0, 0)

        /** Drag starts only from the grip handle, never from a long-press. */
        override fun isLongPressDragEnabled(): Boolean = false

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
            // A real move reports the new order; nothing else opens a menu here.
            if (moved && from != NO_POSITION && to != NO_POSITION && from != to) {
                onDropped?.invoke(from, to)
            }
        }
    })

    init {
        layoutManager = GridLayoutManager(context, 2)
        adapter = gridAdapter
        // Keep ItemTouchHelper's own drag animation authoritative.
        itemAnimator = null
        touchHelper.attachToRecyclerView(this)
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
        // Cell height is 4/3 of its width; decode a little larger so the
        // centerCrop fill stays crisp.
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

            // Checkbox = selection only. It consumes its own tap, so it never
            // opens the page and never starts the drag.
            binding.checkSelect.isChecked = checkedAt(index)
            binding.checkSelect.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onCheckToggle?.invoke(pos)
            }

            // Reordering is started from the grip only.
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
                    val pos = bindingAdapterPosition
                    if (pos != NO_POSITION) touchHelper.startDrag(this)
                }
                // Don't consume: the touch helper needs the follow-up events.
                false
            }
            // Consume taps on the grip so they can't fall through to the cell.
            binding.dragHandle.setOnClickListener { }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onTap?.invoke(pos)
            }
            // Outline the cell while it is checked.
            binding.root.strokeWidth =
                if (checkedAt(index)) (3 * resources.displayMetrics.density).toInt() else 0
        }
    }
}
