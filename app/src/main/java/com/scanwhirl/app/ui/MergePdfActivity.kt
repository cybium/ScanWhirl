package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityMergePdfBinding
import com.scanwhirl.app.databinding.ItemMergePdfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Merge builder for device PDFs: pick several PDFs (the picker allows multiple),
 * add more with "Add PDF", drag the handles to set the order, then merge into a
 * new scan. Reordering uses a single-column list, where vertical drag is exact.
 */
class MergePdfActivity : BaseActivity() {

    private data class Picked(val uri: Uri, val name: String)

    private lateinit var binding: ActivityMergePdfBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }

    private val picked = mutableListOf<Picked>()
    private val adapter = PickAdapter()

    private val touchHelper = ItemTouchHelper(object :
        ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun onMove(
            rv: RecyclerView,
            vh: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun isLongPressDragEnabled(): Boolean = false
    })

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) {
                if (picked.isEmpty()) finish()
                return@registerForActivityResult
            }
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                if (picked.none { it.uri == uri }) {
                    picked.add(Picked(uri, pdfName(uri)))
                }
            }
            adapter.notifyDataSetChanged()
            updateUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMergePdfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.mergeList.layoutManager = LinearLayoutManager(this)
        binding.mergeList.adapter = adapter
        touchHelper.attachToRecyclerView(binding.mergeList)

        binding.btnAddPdf.setOnClickListener {
            picker.launch(arrayOf("application/pdf"))
        }
        binding.btnMerge.setOnClickListener { promptName() }

        updateUi()
        if (savedInstanceState == null) {
            picker.launch(arrayOf("application/pdf"))
        }
    }

    private fun updateUi() {
        binding.btnMerge.isEnabled = picked.size >= 2
        binding.mergeHint.text = if (picked.isEmpty()) {
            getString(R.string.merge_hint)
        } else {
            getString(R.string.merge_hint) + "\n" + getString(R.string.selected_count, picked.size)
        }
    }

    private fun pdfName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i) else null
            } else {
                null
            }
        } ?: "PDF"
    } catch (_: Exception) {
        "PDF"
    }

    private fun promptName() {
        if (picked.size < 2) {
            Toast.makeText(this, R.string.merge_needs_two_pdfs, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { hint = getString(R.string.merge_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.merge_builder_title)
            .setView(input)
            .setPositiveButton(R.string.merge_action) { _, _ ->
                doMerge(input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doMerge(name: String?) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val uris = picked.map { it.uri }
                val doc = withContext(Dispatchers.IO) { repo.mergePdfUris(uris, name) }
                progress.dismiss()
                Toast.makeText(
                    this@MergePdfActivity,
                    R.string.merge_done,
                    Toast.LENGTH_LONG
                ).show()
                startActivity(PdfViewerActivity.intent(this@MergePdfActivity, doc.id, doc.name))
                finish()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@MergePdfActivity,
                    getString(R.string.merge_pdf_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showProgress(): android.app.Dialog {
        val bar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        return AlertDialog.Builder(this)
            .setTitle(R.string.progress_title)
            .setView(bar)
            .setCancelable(false)
            .show()
    }

    private inner class PickAdapter : RecyclerView.Adapter<VH>() {
        fun move(from: Int, to: Int) {
            if (from !in picked.indices || to !in picked.indices || from == to) return
            picked.add(to, picked.removeAt(from))
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = picked.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemMergePdfBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)
    }

    private inner class VH(private val binding: ItemMergePdfBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val item = picked[position]
            binding.pdfName.text = item.name
            binding.btnRemove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                picked.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                adapter.notifyItemRangeChanged(pos, picked.size - pos)
                updateUi()
            }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(this)
                }
                false
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, MergePdfActivity::class.java)
    }
}
