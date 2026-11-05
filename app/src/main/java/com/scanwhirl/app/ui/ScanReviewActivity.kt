package com.scanwhirl.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityScanReviewBinding
import com.scanwhirl.app.databinding.DialogZoomPageBinding
import com.scanwhirl.app.databinding.ItemReviewPageBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shown after the scanner finishes: all captured pages, an editable default title,
 * and Save PDF / Save & share PDF actions. Pages live in a staging directory that is
 * cleaned up once the scan is saved or explicitly discarded.
 */
class ScanReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanReviewBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }

    private lateinit var stagingDir: File
    private var pdfFile: File? = null
    private var pages: List<File> = emptyList()
    private var defaultName: String = "Document"
    private var saved = false
    private var saving = false
    private var selectedFilter = ScanFilter.ORIGINAL

    private var pendingDownload: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
            pendingDownload = null
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (saving) return
            confirmDiscard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, backCallback)

        stagingDir = File(intent.getStringExtra(EXTRA_STAGING) ?: return finish())
        pages = stagingDir.listFiles { f -> f.extension.equals("jpg", true) }
            ?.sortedBy { it.nameWithoutExtension }
            .orEmpty()
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        intent.getStringExtra(EXTRA_PDF)?.let { path ->
            File(path).takeIf { it.exists() && it.length() > 0 }?.let { pdfFile = it }
        }
        defaultName = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: "Document"

        binding.titleInput.setText(defaultName)

        // 2 columns on phones (bigger, readable thumbnails); 3 from 600dp up.
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        binding.pageGrid.layoutManager = GridLayoutManager(this, if (widthDp >= 600f) 3 else 2)
        val adapter = ReviewPageAdapter(pages, { downloadPage(it) }, { showZoom(it) })
        binding.pageGrid.adapter = adapter

        binding.filterGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedFilter = when (checkedId) {
                R.id.chipEnhance -> ScanFilter.ENHANCE
                R.id.chipClean -> ScanFilter.CLEAN
                R.id.chipBlackWhite -> ScanFilter.BLACKWHITE
                else -> ScanFilter.ORIGINAL
            }
            adapter.filter = selectedFilter
            adapter.notifyDataSetChanged()
        }

        binding.btnSavePdf.setOnClickListener { saveAndFinish(share = false) }
        binding.btnSaveShare.setOnClickListener { saveAndFinish(share = true) }
    }

    private fun currentTitle(): String =
        binding.titleInput.text?.toString()?.trim().orEmpty().ifBlank { defaultName }

    private fun saveAndFinish(share: Boolean) {
        if (saving) return
        withStoragePermission {
            saving = true
            val progress = showProgress()
            lifecycleScope.launch {
                try {
                    val uris = pages.map { Uri.fromFile(it) }
                    val doc = repo.saveScan(
                        uris,
                        pdfFile?.let { Uri.fromFile(it) },
                        currentTitle(),
                        selectedFilter
                    )
                    saved = true

                    // Also place the finished PDF in Documents/ScanWhirl (Files app).
                    var exported: String? = null
                    if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) {
                        try {
                            exported = withContext(Dispatchers.IO) {
                                DownloadSaver.savePdf(
                                    this@ScanReviewActivity,
                                    doc.pdfFile.readBytes(),
                                    DownloadSaver.pdfNameFor(doc.name)
                                )
                            }
                        } catch (e: Exception) {
                            exported = null
                        }
                    }

                    stagingDir.deleteRecursively()
                    progress.dismiss()
                    val message = if (exported != null) {
                        getString(R.string.pdf_saved_docs, exported)
                    } else {
                        getString(R.string.save_inside_app)
                    }
                    Toast.makeText(this@ScanReviewActivity, message, Toast.LENGTH_LONG).show()
                    if (share) ShareUtils.share(this@ScanReviewActivity, doc)
                    startActivity(DocumentViewerActivity.intent(this@ScanReviewActivity, doc.id))
                    finish()
                } catch (e: Exception) {
                    saving = false
                    progress.dismiss()
                    Toast.makeText(
                        this@ScanReviewActivity,
                        getString(R.string.page_op_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmDiscard() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.review_discard_title)
            .setMessage(R.string.review_discard_msg)
            .setPositiveButton(R.string.discard) { _, _ -> discard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun discard() {
        saved = true // stops any later cleanup concerns; staging is removed now
        stagingDir.deleteRecursively()
        finish()
    }

    private fun downloadPage(index: Int) {
        if (index !in pages.indices) return
        withStoragePermission {
            val file = pages[index]
            val name = DownloadSaver.fileNameFor(currentTitle(), index + 1, pages.size)
            lifecycleScope.launch {
                try {
                    val savedName = withContext(Dispatchers.IO) {
                        DownloadSaver.saveJpeg(this@ScanReviewActivity, file.readBytes(), name)
                    }
                    Toast.makeText(
                        this@ScanReviewActivity,
                        getString(R.string.dl_saved, savedName),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ScanReviewActivity,
                        getString(R.string.dl_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Full-screen pinch-zoom preview of one page (with the current filter applied). */
    private fun showZoom(index: Int) {
        val file = pages.getOrNull(index) ?: return
        val dialogBinding = DialogZoomPageBinding.inflate(layoutInflater)
        val decoded = ImageTools.decodeThumb(file, 1600)
        if (decoded != null) {
            dialogBinding.zoomImage.setImageBitmap(selectedFilter.applyTo(decoded))
        } else {
            dialogBinding.zoomImage.setImageResource(R.drawable.ic_document)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialogBinding.btnZoomClose.setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun withStoragePermission(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingDownload = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun showProgress(): android.app.Dialog {
        val bar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        return androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.review_saving)
            .setView(bar)
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val EXTRA_STAGING = "staging_dir"
        private const val EXTRA_PDF = "pdf_path"
        private const val EXTRA_NAME = "default_name"

        fun intent(context: Context, stagingDir: String, pdfPath: String?, defaultName: String): Intent =
            Intent(context, ScanReviewActivity::class.java)
                .putExtra(EXTRA_STAGING, stagingDir)
                .putExtra(EXTRA_PDF, pdfPath)
                .putExtra(EXTRA_NAME, defaultName)
    }
}

private class ReviewPageAdapter(
    private val pages: List<File>,
    private val onSavePage: (Int) -> Unit,
    private val onZoom: (Int) -> Unit
) : RecyclerView.Adapter<ReviewPageAdapter.VH>() {

    var filter: ScanFilter = ScanFilter.ORIGINAL

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val binding = ItemReviewPageBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(pages[position], position)
    }

    override fun getItemCount() = pages.size

    inner class VH(private val binding: ItemReviewPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File, index: Int) {
            val decoded = ImageTools.decodeThumb(file, 640)
            if (decoded != null) {
                binding.pageThumb.setImageBitmap(filter.applyTo(decoded))
            } else {
                binding.pageThumb.setImageResource(R.drawable.ic_document)
            }
            binding.pageNum.text =
                String.format(java.util.Locale.ROOT, "%d", index + 1)
            binding.btnSavePage.setOnClickListener { onSavePage(bindingAdapterPosition) }
            binding.root.setOnClickListener { onZoom(bindingAdapterPosition) }
        }
    }
}
