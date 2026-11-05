package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.databinding.ItemPageGridBinding
import com.scanwhirl.app.util.ScanFilter
import java.io.File

/**
 * The one page-grid component used by both the saved-document viewer and the
 * scan review screen: a vertical 2-column grid of downsampled, cached thumbs.
 *
 * Consumers set [onTap] / [onLongPress] and push pages through [submit];
 * [filterFor] lets each screen decide which look a cell shows (the review
 * screen defaults every page to Enhance via its staging map).
 */
class PageGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    /** Called with the page index when a cell is tapped. */
    var onTap: ((Int) -> Unit)? = null

    /** Called with the page index when a cell is long-pressed (drag/menu entry point). */
    var onLongPress: ((Int) -> Unit)? = null

    /** Which filter a cell at [index] should display. */
    var filterFor: (Int) -> ScanFilter = { ScanFilter.ORIGINAL }

    private val gridAdapter = GridAdapter()

    init {
        layoutManager = GridLayoutManager(context, 2)
        adapter = gridAdapter
        itemAnimator = null
        setHasFixedSize(false)
    }

    /** Replaces the displayed pages. Pass a stable copy; the list is not mutated. */
    fun submit(pages: List<File>) {
        gridAdapter.pages = pages
        gridAdapter.notifyDataSetChanged()
    }

    /** Re-binds every cell (filter or file content changed in place). */
    fun refresh() {
        gridAdapter.notifyDataSetChanged()
    }

    fun pageCount(): Int = gridAdapter.itemCount

    fun pageAt(index: Int): File? = gridAdapter.pages.getOrNull(index)

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
        var pages: List<File> = emptyList()

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
                filterFor(index),
                binding.pageThumb
            )
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onTap?.invoke(pos)
            }
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != NO_POSITION) onLongPress?.invoke(pos)
                pos != NO_POSITION
            }
        }
    }
}
