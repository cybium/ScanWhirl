package com.scanwhirl.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ItemDevicePdfBinding
import com.scanwhirl.app.model.DevicePdf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Device PDFs tab: name, size/date, and a single View action. */
class DevicePdfAdapter(
    private val onView: (DevicePdf) -> Unit
) : ListAdapter<DevicePdf, DevicePdfAdapter.VH>(DIFF) {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDevicePdfBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemDevicePdfBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pdf: DevicePdf) {
            binding.deviceName.text = pdf.name
            val size = when {
                pdf.size >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", pdf.size / 1_048_576.0)
                pdf.size >= 1024 -> String.format(Locale.ROOT, "%.0f KB", pdf.size / 1024.0)
                pdf.size > 0 -> "${pdf.size} B"
                else -> "—"
            }
            val date = if (pdf.modified > 0) dateFmt.format(Date(pdf.modified)) else "—"
            binding.deviceMeta.text = "$size · $date"
            binding.btnDeviceView.setOnClickListener { onView(pdf) }
            binding.root.setOnClickListener { onView(pdf) }
            binding.root.contentDescription =
                binding.root.context.getString(R.string.view_pdf_named, pdf.name)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DevicePdf>() {
            override fun areItemsTheSame(a: DevicePdf, b: DevicePdf) =
                a.uri.toString() == b.uri.toString()

            override fun areContentsTheSame(a: DevicePdf, b: DevicePdf) = a == b
        }
    }
}
