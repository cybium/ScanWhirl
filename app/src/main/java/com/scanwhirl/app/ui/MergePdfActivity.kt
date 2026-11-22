package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.L3FailureCopy
import com.scanwhirl.app.util.OutputNames
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
    /** Typed merge name and whether the name sheet was open, so rotation keeps both. */
    private var nameDraft: String? = null
    private var sheetWasOpen = false

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
        binding.root.applySystemBarsPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.mergeList.layoutManager = LinearLayoutManager(this)
        binding.mergeList.adapter = adapter
        touchHelper.attachToRecyclerView(binding.mergeList)

        binding.btnAddPdf.setOnClickListener {
            picker.launch(arrayOf("application/pdf"))
        }
        binding.btnMerge.setOnClickListener { promptName() }

        if (savedInstanceState != null) restoreState(savedInstanceState)
        updateUi()
        if (savedInstanceState == null) {
            picker.launch(arrayOf("application/pdf"))
        } else if (sheetWasOpen && picked.size >= 2) {
            // Rotation keeps the picked PDFs and the typed (possibly blank) name and its error.
            promptName(nameDraft)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PICKED_URI, ArrayList(picked.map { it.uri.toString() }))
        outState.putStringArrayList(KEY_PICKED_NAME, ArrayList(picked.map { it.name }))
        outState.putString(KEY_NAME_DRAFT, nameDraft)
        outState.putBoolean(KEY_SHEET_OPEN, sheetWasOpen)
    }

    private fun restoreState(saved: Bundle) {
        val uris = saved.getStringArrayList(KEY_PICKED_URI).orEmpty()
        val names = saved.getStringArrayList(KEY_PICKED_NAME).orEmpty()
        picked.clear()
        uris.indices.forEach { i ->
            if (i < names.size) picked += Picked(Uri.parse(uris[i]), names[i])
        }
        nameDraft = saved.getString(KEY_NAME_DRAFT)
        sheetWasOpen = saved.getBoolean(KEY_SHEET_OPEN, false)
    }

    private fun updateUi() {
        binding.btnMerge.isEnabled = picked.size >= 2
        binding.mergeHint.text = if (picked.isEmpty()) {
            getString(R.string.merge_hint)
        } else {
            getString(R.string.merge_hint) + "\n" +
                resources.getQuantityString(R.plurals.selected_count, picked.size, picked.size)
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

    private fun promptName(restored: String? = null) {
        if (picked.size < 2) {
            Toast.makeText(this, R.string.merge_needs_two_pdfs, Toast.LENGTH_SHORT).show()
            return
        }
        // Council §5: prefill the editable field with the first source's stem, never a verb
        // suffix. Uniqueness against the library is applied when the merge runs on IO.
        val prefill = restored ?: OutputNames.forNewCopy(picked.first().name, emptyList())
        nameDraft = prefill
        sheetWasOpen = true
        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.merge_builder_title)
            .setInput(initial = prefill, hint = getString(R.string.co_name_hint))
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.setOnDismissListener { sheetWasOpen = false }
        val field = requireNotNull(sheet.inputView)
        val error = TextView(this).apply {
            tag = NAME_ERROR_TAG
            text = getString(R.string.co_name_required)
            contentDescription = text
            textSize = 13f
            setTextColor(com.scanwhirl.app.ui.kit.SwStyle.color(this@MergePdfActivity, R.color.laser_danger))
            setLineSpacing(0f, 1.2f)
            setPadding(0, com.scanwhirl.app.ui.kit.SwStyle.dp(this@MergePdfActivity, 6f), 0, 0)
        }
        sheet.contentContainer.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.contentContainer.addView(
            TextView(this).apply {
                text = getString(R.string.co_new_copy_receipt)
                contentDescription = text
                textSize = 13f
                setLineSpacing(0f, 1.25f)
                setPadding(0, com.scanwhirl.app.ui.kit.SwStyle.dp(this@MergePdfActivity, 6f), 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        fun validate() {
            val blank = field.text.toString().isBlank()
            error.isVisible = blank
            sheet.primaryButton.isEnabled = !blank
            sheet.primaryButton.alpha = if (blank) 0.38f else 1f
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                nameDraft = s?.toString().orEmpty()
                validate()
            }
        })
        sheet.setPrimary(R.string.merge_action) {
            if (field.text.toString().isNotBlank()) confirmMerge(field.text.toString())
        }
        validate()
        sheet.show()
    }

    /** Device-PDF merge rasterizes each page; the user confirms that once. */
    private fun confirmMerge(name: String?) {
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.merge_builder_title)
            .setMessage(R.string.pdf_rasterize_note)
            .setPrimary(R.string.continue_label) { doMerge(name) }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun doMerge(name: String?) {
        var job: kotlinx.coroutines.Job? = null
        var sheet: com.scanwhirl.app.ui.kit.SwSheet? = null
        job = lifecycleScope.launch {
            try {
                val uris = picked.map { it.uri }
                val uniqueName = withContext(Dispatchers.IO) {
                    if (name.isNullOrBlank()) null
                    else OutputNames.newCopyFromField(
                        name,
                        runCatching { repo.listDocuments(includeVaulted = true).map { it.name } }
                            .getOrDefault(emptyList())
                    )
                }
                val doc = repo.mergePdfUrisPlanned(
                    uris = uris,
                    suggestedName = uniqueName,
                    onPlan = { plan ->
                        val decision = com.scanwhirl.app.ui.tools.DevicePdfGate
                            .decide(this@MergePdfActivity, plan)
                        if (decision != ScanRepository.ImportDecision.Cancel) {
                            sheet = com.scanwhirl.app.ui.tools.DevicePdfGate
                                .progress(this@MergePdfActivity) { job?.cancel() }
                                .also { it.show() }
                        }
                        decision
                    },
                    onPage = { done, total ->
                        runOnUiThread {
                            sheet?.setMessage(
                                com.scanwhirl.app.ui.tools.DevicePdfGate
                                    .progressText(this@MergePdfActivity, done, total)
                            )
                        }
                    }
                )
                sheet?.dismiss()
                if (doc == null) return@launch
                Toast.makeText(
                    this@MergePdfActivity,
                    R.string.merge_done,
                    Toast.LENGTH_LONG
                ).show()
                startActivity(PdfViewerActivity.intent(this@MergePdfActivity, doc.id, doc.name))
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                sheet?.dismiss()
                throw e
            } catch (e: SecurityException) {
                sheet?.dismiss()
                Toast.makeText(this@MergePdfActivity, R.string.pdf_protected, Toast.LENGTH_LONG).show()
            } catch (e: com.scanwhirl.app.util.PdfImport.ImportException) {
                sheet?.dismiss()
                if (e.reason == com.scanwhirl.app.util.PdfImport.Reason.PASSWORD) {
                    Toast.makeText(this@MergePdfActivity, R.string.pdf_protected, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this@MergePdfActivity,
                        L3FailureCopy.message(this@MergePdfActivity, e),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                sheet?.dismiss()
                Toast.makeText(
                    this@MergePdfActivity,
                    L3FailureCopy.message(this@MergePdfActivity, e),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showProgress(): com.scanwhirl.app.ui.kit.SwProgress {
        return com.scanwhirl.app.ui.kit.SwProgress.show(this, getString(R.string.progress_title))
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
            // L3-04: the spoken/visible position and named reorder actions make the
            // drag handle optional — TalkBack and keyboard can set the merge order.
            val where = getString(R.string.ux130_l3_merge_file_position, position + 1, picked.size)
            binding.root.findViewById<TextView>(R.id.ux130_l3_merge_position).text = where
            val earlier = binding.root.findViewById<View>(R.id.ux130_l3_merge_move_earlier)
            val later = binding.root.findViewById<View>(R.id.ux130_l3_merge_move_later)
            earlier.contentDescription = "${getString(R.string.ux130_l3_merge_move_earlier)}, $where"
            later.contentDescription = "${getString(R.string.ux130_l3_merge_move_later)}, $where"
            earlier.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos > 0) adapter.move(pos, pos - 1)
            }
            later.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in 0 until picked.size - 1) adapter.move(pos, pos + 1)
            }
            binding.btnPreview.setOnClickListener {
                startActivity(
                    PdfViewerActivity.uriIntent(
                        this@MergePdfActivity, item.uri.toString(), item.name
                    )
                )
            }
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
        private const val KEY_PICKED_URI = "co_merge_picked_uri"
        private const val KEY_PICKED_NAME = "co_merge_picked_name"
        private const val KEY_NAME_DRAFT = "co_merge_name_draft"
        private const val KEY_SHEET_OPEN = "co_merge_sheet_open"
        const val NAME_ERROR_TAG = "co_name_required"

        fun intent(context: Context): Intent =
            Intent(context, MergePdfActivity::class.java)
    }
}
