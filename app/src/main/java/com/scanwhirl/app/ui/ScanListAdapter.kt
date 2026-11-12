package com.scanwhirl.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ItemScanBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.ui.home.DocThumbBinder
import com.scanwhirl.app.ui.home.HomeRow
import com.scanwhirl.app.ui.home.PageHitViews
import com.scanwhirl.app.ui.home.PageLink
import com.scanwhirl.app.ui.home.TextState
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.StatusLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home slice 2: the Index list. Rows are [HomeRow]s — the document plus disk facts
 * (size, text status) read off the main thread — so binding never touches the disk
 * beyond the thumbnail loader's cache-key stat.
 */
class ScanListAdapter(
    private val onClick: (ScanDocument) -> Unit,
    private val onMore: (ScanDocument) -> Unit,
    private val onLongClick: (ScanDocument) -> Unit,
    private val onCategory: (ScanDocument) -> Unit,
    /** Retrieval slice 5: a "Page N" link in a search row (always the ScanWhirl reader). */
    private val onPageLink: (ScanDocument, PageLink) -> Unit = { _, _ -> }
) : ListAdapter<HomeRow, ScanListAdapter.VH>(DIFF) {

    var selectionMode: Boolean = false
    var selectedIds: Set<String> = emptySet()
    /** Document-level search snippets (why a document matched; not a page jump). */
    var snippets: Map<String, CharSequence> = emptyMap()
    /**
     * True when ordinary opens go to an external viewer: page links then say they open here.
     * Changing it re-binds the shown rows: the rows themselves are unchanged, so DiffUtil alone
     * would leave the old note (found on device after switching the viewer in Settings).
     */
    var externalViewer: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    /**
     * The indexer's live state. Changing it re-binds only the status line of the
     * affected rows (payload), never the thumbnail.
     */
    var indexState: OcrIndexer.IndexState? = null
        set(value) {
            val before = field?.docId
            field = value
            for (id in setOfNotNull(before, value?.docId)) {
                val pos = currentList.indexOfFirst { it.id == id }
                if (pos >= 0) notifyItemChanged(pos, PAYLOAD_STATUS)
            }
        }

    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATUS }) {
            holder.bindStatus(getItem(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
    }

    inner class VH(private val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root) {

        fun recycle() = DocThumbBinder.unbind(binding.thumbImage, binding.thumbGlyph)

        fun bind(row: HomeRow) {
            val doc = row.doc
            val context = binding.root.context
            binding.scanName.text = doc.name
            binding.lockBadge.isVisible = doc.isLocked

            // Paper: real first page (ordinary documents only), category tab,
            // a second sheet behind multi-page documents.
            DocThumbBinder.bind(binding.thumbImage, binding.thumbGlyph, doc)
            val catColor = CategoryStyle.color(doc.category.orEmpty())
            binding.thumbTab.backgroundTintList = ColorStateList.valueOf(catColor)
            binding.thumbBack.isVisible = doc.pageCount > 1
            binding.pageBadge.text = doc.pageCount.toString()
            binding.pageBadge.isVisible = doc.pageCount > 1

            val sizeBytes = row.info.sizeBytes.takeIf { it >= 0 } ?: doc.pdfFile.length()
            val pagesText = context.resources
                .getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
            binding.scanMeta.text = "$pagesText · " +
                StatusLine.formatBytes(sizeBytes).replace(' ', ' ') + " · " +
                dateFmt.format(Date(doc.createdAt)).replace(' ', ' ')

            // Category: swatch + sentence-case label; tapping it moves the document.
            val catLabel = CategoryRules.label(doc.category.orEmpty())
            binding.catChip.text = catLabel
            binding.catChip.setTextColor(catColor)
            binding.catChip.compoundDrawablesRelative[0]?.mutate()?.setTint(catColor)
            binding.catChip.contentDescription =
                context.getString(R.string.doc_category_action, catLabel)

            // Retrieval slice 5: page links, only for a match computed for THIS document at
            // its current revision (a recycled or edited row never inherits another's pages).
            // A locked document never shows OCR-derived hints, whatever a stale row, a
            // recycled holder or a late snippet map carries: it is judged by the document
            // bound NOW.
            val match = row.match?.takeIf {
                !doc.isLocked && it.docId == doc.id && it.revision == doc.revision
            }
            val pagesShown = PageHitViews.bind(
                binding.pageHits, binding.matchScope, binding.pageHitNote, doc, match,
                enabled = !selectionMode, externalViewer = externalViewer, onOpen = onPageLink
            )

            // Search snippet: shown only when a text hit explains the match (page
            // links carry their own per-page excerpts instead).
            val snippet = snippets[doc.id]?.takeIf { !pagesShown && !doc.isLocked }
            binding.scanSnippet.isVisible = snippet != null
            binding.scanSnippet.text = snippet

            val selected = selectionMode && doc.id in selectedIds
            binding.checkSelect.isVisible = selectionMode
            binding.checkSelect.isChecked = selected
            binding.root.isActivated = selected
            binding.btnMore.isVisible = !selectionMode
            binding.btnMore.isEnabled = !selectionMode
            binding.btnMore.setOnClickListener { onMore(doc) }
            binding.btnMore.contentDescription = context.getString(R.string.doc_more_options, doc.name)
            ViewCompat.setStateDescription(
                binding.root,
                if (selected) context.getString(R.string.state_selected) else null
            )

            binding.catChip.setOnClickListener { onCategory(doc) }
            binding.root.setOnClickListener { onClick(doc) }
            binding.root.setOnLongClickListener {
                onLongClick(doc)
                true
            }
            bindStatus(row)
        }

        /** The honest text line, plus the row description that repeats it for TalkBack. */
        fun bindStatus(row: HomeRow) {
            val doc = row.doc
            val context = binding.root.context
            val live = indexState?.takeIf { it.docId == doc.id }
            val (text, color, icon) = when {
                live != null && live.total > 0 -> Triple(
                    context.getString(R.string.text_status_reading, live.done, live.total),
                    R.color.rv_warn, R.drawable.ic_sw_reading
                )
                live != null || row.info.text == TextState.WAITING -> Triple(
                    context.getString(R.string.text_status_waiting), R.color.rv_warn, R.drawable.ic_sw_reading
                )
                row.info.text == TextState.READY -> Triple(
                    context.getString(R.string.text_status_ready), R.color.sw_ok, R.drawable.ic_sw_check
                )
                row.info.text == TextState.PARTIAL -> Triple(
                    context.getString(R.string.text_status_partial, row.info.textPages, doc.pageCount),
                    R.color.rv_ink_3, 0
                )
                else -> Triple(null, 0, 0)
            }
            binding.scanTextStatus.isVisible = text != null
            if (text != null) {
                val tint = ContextCompat.getColor(context, color)
                binding.scanTextStatus.text = text
                binding.scanTextStatus.setTextColor(tint)
                val d = if (icon != 0) ContextCompat.getDrawable(context, icon)?.mutate() else null
                d?.let {
                    val size = (15 * context.resources.displayMetrics.density).toInt()
                    it.setBounds(0, 0, size, size)
                    it.setTint(tint)
                }
                binding.scanTextStatus.setCompoundDrawablesRelative(d, null, null, null)
            }

            val parts = mutableListOf(
                context.getString(
                    R.string.doc_row_desc, doc.name, doc.pageCount,
                    CategoryRules.label(doc.category.orEmpty())
                )
            )
            if (doc.isLocked) parts += context.getString(R.string.badge_locked)
            if (text != null) parts += text
            binding.root.contentDescription = parts.joinToString(", ")
        }
    }

    companion object {
        private const val PAYLOAD_STATUS = "status"

        val DIFF = object : DiffUtil.ItemCallback<HomeRow>() {
            override fun areItemsTheSame(a: HomeRow, b: HomeRow) = a.id == b.id
            override fun areContentsTheSame(a: HomeRow, b: HomeRow) = a == b
        }
    }
}
