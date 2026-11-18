package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
        gridAdapter.move(from, to)
        // Positions changed: re-bind so every tile announces its new "Page X of Y".
        gridAdapter.notifyDataSetChanged()
        onDropped?.invoke(from, to)
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
                    if (dragEnabled && pos != NO_POSITION) {
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
}
