package com.scanwhirl.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
    private val onView: (ScanDocument) -> Unit,
    private val onEdit: (ScanDocument) -> Unit,
    private val onLongClick: (ScanDocument) -> Unit
) : ListAdapter<ScanDocument, ScanListAdapter.VH>(DIFF) {

    var selectionMode: Boolean = false
    var selectedIds: Set<String> = emptySet()

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
            // Compact single line: "3 pages · 243 KB · 6 Nov".
            // Non-breaking spaces keep each unit glued to its number.
            val pagesText = binding.root.context.resources
                .getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
            binding.scanMeta.text = "$pagesText · " +
                StatusLine.formatBytes(sizeBytes).replace(' ', '\u00A0') + " · " +
                dateFmt.format(whenDate).replace(' ', '\u00A0')
            val first = doc.pageFiles.firstOrNull()
            if (first != null) {
                // Background decode + cache; the main thread never touches JPEGs.
                binding.scanThumb.setImageResource(R.drawable.ic_document)
                com.scanwhirl.app.ui.pagegrid.PageThumbLoader.load(first, 320,
                    com.scanwhirl.app.util.ScanFilter.ORIGINAL, binding.scanThumb)
            } else {
                binding.scanThumb.setImageResource(R.drawable.ic_document)
            }

            val selected = selectionMode && doc.id in selectedIds
            binding.checkSelect.isVisible = selectionMode
            binding.checkSelect.isChecked = selected
            // View / edit are the two explicit actions while not selecting.
            binding.btnView.isVisible = !selectionMode
            binding.btnEdit.isVisible = !selectionMode
            binding.btnView.setOnClickListener { onView(doc) }
            binding.btnEdit.setOnClickListener { onEdit(doc) }
            val density = binding.root.resources.displayMetrics.density
            binding.root.strokeWidth = if (selected) (2 * density).toInt() else (1 * density).toInt()
            binding.root.strokeColor =
                ContextCompat.getColor(binding.root.context, if (selected) R.color.cyan_primary else R.color.cyan_border_idle)

            // Category accent rail + label chip (D3 palette via CategoryStyle).
            val catColor = CategoryStyle.color(doc.category.orEmpty())
            binding.catRail.backgroundTintList = ColorStateList.valueOf(catColor)
            binding.catChip.text = "● ${CategoryRules.label(doc.category.orEmpty()).uppercase()}"
            binding.catChip.setTextColor(catColor)

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
