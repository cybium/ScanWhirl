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
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.StatusLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanListAdapter(
    private val onClick: (ScanDocument) -> Unit,
    private val onMore: (ScanDocument) -> Unit,
    private val onLongClick: (ScanDocument) -> Unit,
    private val onCategory: (ScanDocument) -> Unit
) : ListAdapter<ScanDocument, ScanListAdapter.VH>(DIFF) {

    var selectionMode: Boolean = false
    var selectedIds: Set<String> = emptySet()
    /** page snippets for search hits (Task 10) */
    var snippets: Map<String, CharSequence> = emptyMap()

    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(doc: ScanDocument) {
            binding.scanName.text = doc.name
            binding.lockBadge.isVisible = doc.isLocked
            binding.pageBadge.text = doc.pageCount.toString()
            binding.pageBadge.isVisible = doc.pageCount > 0
            val sizeBytes = if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) {
                doc.pdfFile.length()
            } else {
                doc.pageFiles.sumOf { it.length() }
            }
            val whenDate = Date(doc.createdAt)
            val pagesText = binding.root.context.resources
                .getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
            binding.scanMeta.text = "$pagesText · " +
                StatusLine.formatBytes(sizeBytes).replace(' ', '\u00A0') + " · " +
                dateFmt.format(whenDate).replace(' ', '\u00A0')

            // Category chip: sentence-case label, coloured dot
            binding.catChip.text = "● ${CategoryRules.label(doc.category.orEmpty())}"
            binding.catChip.setTextColor(CategoryStyle.color(doc.category.orEmpty()))

            // "Searchable" indicator when text_layer.flag exists
            binding.scanSearchable.isVisible = doc.isSearchable

            // Search snippet (Task 10): shown only when a text hit explains the match
            binding.scanSnippet.isVisible = snippets[doc.id] != null
            binding.scanSnippet.text = snippets[doc.id]

            val selected = selectionMode && doc.id in selectedIds
            binding.checkSelect.isVisible = selectionMode
            binding.checkSelect.isChecked = selected
            // btnMore replaces btnView/btnEdit; hidden in selection mode
            binding.btnMore.isVisible = !selectionMode
            binding.btnMore.isEnabled = !selectionMode
            binding.btnMore.setOnClickListener { onMore(doc) }
            binding.checkSelect.isClickable = false
            binding.checkSelect.isFocusable = false

            val density = binding.root.resources.displayMetrics.density
            binding.root.strokeWidth = if (selected) (2 * density).toInt() else (1 * density).toInt()
            binding.root.strokeColor =
                ContextCompat.getColor(binding.root.context, if (selected) R.color.cyan_primary else R.color.cyan_border_hair)

            // Category accent rail + label chip
            val catColor = CategoryStyle.color(doc.category.orEmpty())
            binding.catRail.backgroundTintList = ColorStateList.valueOf(catColor)

            // Accessibility
            binding.root.contentDescription = binding.root.context.getString(
                R.string.doc_row_desc, doc.name, doc.pageCount, CategoryRules.label(doc.category.orEmpty()))
            binding.btnMore.contentDescription = binding.root.context.getString(
                R.string.doc_more_options, doc.name)
            ViewCompat.setStateDescription(
                binding.root,
                if (selected) binding.root.context.getString(R.string.state_selected) else null
            )

            binding.catChip.setOnClickListener { onCategory(doc) }
            binding.root.setOnClickListener { onClick(doc) }
            binding.root.setOnLongClickListener {
                onLongClick(doc)
                true
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScanDocument>() {
            override fun areItemsTheSame(a: ScanDocument, b: ScanDocument) = a.id == b.id
            override fun areContentsTheSame(a: ScanDocument, b: ScanDocument) = a == b
        }
    }
}