package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityPageViewerBinding
import com.scanwhirl.app.databinding.ItemPagerPageBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ScanFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen page view: swipe between pages (PagerSnapHelper — no extra
 * dependency), pinch / double-tap zoom, and a bottom bar with Rotate,
 * Filter and Delete (Crop joins in the crop commit).
 *
 * Operates on a saved document. Deletes use the same deferred Undo rule as
 * the grid viewer: the file only goes away when the snackbar times out, is
 * replaced, or this screen closes.
 */
class PageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageViewerBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }
    private var docId: String? = null

    /** Non-null = reviewing an unsaved scan: no repository, files live here. */
    private var stagingDir: File? = null

    /** Staging filters by absolute path (persisted to filters.txt for review). */
    private val stagingFilters = mutableMapOf<String, ScanFilter>()

    private var document: ScanDocument? = null
    private var pages: List<File> = emptyList()

    private var pendingDelete: String? = null
    private val committingDeletes = mutableSetOf<String>()
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Last known page (kept when the snap view isn't measurable yet). */
    private var currentPos: Int = 0

    /** Session memory of filters the user picked (disk keeps no filter state). */
    private val filterChoice = mutableMapOf<String, ScanFilter>()

    /** Crop overlay state: the page being cropped + its display-size copy. */
    private var cropPath: String? = null
    private var cropIndex: Int = -1
    private var cropBitmap: Bitmap? = null

    private val fullExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "page-full").apply { isDaemon = true }
    }

    private val pagerAdapter = PagerAdapter()
    private lateinit var snapHelper: PagerSnapHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pager.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.pager.adapter = pagerAdapter
        snapHelper = PagerSnapHelper().also { it.attachToRecyclerView(binding.pager) }
        binding.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateIndicator()
            }
        })

        docId = intent.getStringExtra(EXTRA_DOC_ID)
        intent.getStringExtra(EXTRA_STAGING)?.let { stagingDir = File(it) }
        if (!reload()) return

        val start = intent.getIntExtra(EXTRA_PAGE, 0)
            .coerceIn(0, (pages.lastIndex).coerceAtLeast(0))
        currentPos = start
        binding.pager.scrollToPosition(start)
        updateIndicator()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnRotate.setOnClickListener { rotateCurrent() }
        binding.btnCrop.setOnClickListener { startCrop() }
        binding.btnFilter.setOnClickListener { filterCurrent() }
        binding.btnDelete.setOnClickListener { deleteCurrent() }
        binding.btnCancelCrop.setOnClickListener { closeCrop() }
        binding.btnApplyCrop.setOnClickListener { applyCrop() }

        // Back cancels an open crop first; otherwise it leaves the view.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.cropOverlay.visibility == android.view.View.VISIBLE) closeCrop()
                else finish()
            }
        })
    }

    override fun onDestroy() {
        // Leaving the screen makes any pending delete permanent.
        commitPendingDelete()
        super.onDestroy()
    }

    /** Re-reads the current page list (minus pending deletes) into the pager. */
    private fun reload(): Boolean {
        val dir = stagingDir
        return if (dir != null) reloadStaging(dir) else reloadSaved()
    }

    private fun reloadSaved(): Boolean {
        val id = docId ?: run { finish(); return false }
        val doc = repo.listDocuments().find { it.id == id }
        if (doc == null) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        document = doc
        pages = doc.pageFiles.filter {
            it.path != pendingDelete && it.path !in committingDeletes
        }
        pagerAdapter.submit(pages)
        return true
    }

    private fun reloadStaging(dir: File): Boolean {
        if (!dir.exists()) {
            // The review screen discarded the scan while we were open.
            finish()
            return false
        }
        pages = dir.listFiles { f -> f.extension.equals("jpg", true) && f.length() > 0 }
            ?.sortedBy { it.nameWithoutExtension }
            .orEmpty()
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        loadStagingFilters(dir)
        pagerAdapter.submit(pages)
        return true
    }

    /** Reads filters.txt; pages without an entry default to Enhance (review's default). */
    private fun loadStagingFilters(dir: File) {
        val fromFile = File(dir, FILTERS_FILE).takeIf { it.exists() }
            ?.readText()?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) parts[0] to ScanFilter.fromId(parts[1]) else null
            }?.toMap()
            ?: emptyMap()
        stagingFilters.clear()
        pages.forEach { stagingFilters[it.path] = fromFile[it.name] ?: ScanFilter.ENHANCE }
    }

    /** Writes the whole staging filter map so the review screen sees the choice. */
    private fun writeStagingFilters(dir: File) {
        File(dir, FILTERS_FILE).writeText(
            pages.joinToString("\n") {
                "${it.name}=${stagingFilters[it.path]?.id ?: ScanFilter.ENHANCE.id}"
            }
        )
    }

    /** Index of the page currently snapped on screen, or -1. */
    private fun currentPosition(): Int {
        val view = snapHelper.findSnapView(binding.pager.layoutManager) ?: return -1
        return binding.pager.getChildAdapterPosition(view)
    }

    private fun updateIndicator() {
        val snapped = currentPosition()
        if (snapped in pages.indices) currentPos = snapped
        binding.pageIndicator.text =
            getString(R.string.page_indicator, currentPos + 1, pages.size)
    }

    private fun rotateCurrent() {
        val pos = currentPosition()
        val file = pages.getOrNull(pos) ?: return
        val dir = stagingDir
        lifecycleScope.launch {
            try {
                if (dir != null) {
                    withContext(Dispatchers.IO) { ImageTools.rotate90(file) }
                } else {
                    val id = docId ?: return@launch
                    withContext(Dispatchers.IO) { commitPendingDeleteNow() }
                    repo.rotatePage(id, pos)
                }
                reload()
                currentPos = pos.coerceAtMost(pages.lastIndex)
                binding.pager.scrollToPosition(currentPos)
                updateIndicator()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PageViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun filterCurrent() {
        val pos = currentPosition()
        val file = pages.getOrNull(pos) ?: return
        val filters = ScanFilter.entries
        val dir = stagingDir
        if (dir != null) {
            // Staging: the choice is virtual, persisted for the review screen.
            val checked = filters.indexOf(stagingFilters[file.path] ?: ScanFilter.ENHANCE)
            AlertDialog.Builder(this)
                .setTitle(R.string.filter_title)
                .setSingleChoiceItems(
                    filters.map { getString(it.labelRes) }.toTypedArray(),
                    checked
                ) { dialog, which ->
                    dialog.dismiss()
                    stagingFilters[file.path] = filters[which]
                    writeStagingFilters(dir)
                    reload()
                    currentPos = pos.coerceAtMost(pages.lastIndex)
                    binding.pager.scrollToPosition(currentPos)
                    updateIndicator()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                // Land any pending delete first so `pos` matches disk indices.
                withContext(Dispatchers.IO) { commitPendingDeleteNow() }
                val checked = filters.indexOf(
                    filterChoice[file.path] ?: ScanFilter.ORIGINAL
                )
                AlertDialog.Builder(this@PageViewerActivity)
                    .setTitle(R.string.filter_title)
                    .setSingleChoiceItems(
                        filters.map { getString(it.labelRes) }.toTypedArray(),
                        checked
                    ) { dialog, which ->
                        dialog.dismiss()
                        applyFilter(id, pos, file, filters[which])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PageViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun applyFilter(id: String, pos: Int, file: File, filter: ScanFilter) {
        lifecycleScope.launch {
            try {
                repo.setPageFilter(id, pos, filter)
                filterChoice[file.path] = filter
                reload()
                currentPos = pos.coerceAtMost(pages.lastIndex)
                binding.pager.scrollToPosition(currentPos)
                updateIndicator()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PageViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Hides the page immediately and offers Undo (files go only after). */
    private fun deleteCurrent() {
        val pos = currentPosition()
        val file = pages.getOrNull(pos) ?: return
        if (pages.size <= 1) {
            // Deleting the last page of an unsaved scan = discarding it (confirmed).
            if (stagingDir != null) confirmStagingDiscard()
            else Toast.makeText(this, R.string.last_page_guard, Toast.LENGTH_LONG).show()
            return
        }
        // This snackbar replaces any previous one: the old Undo window is over.
        commitPendingDelete()

        if (stagingDir != null && !file.renameTo(File(file.path + DEL_SUFFIX))) return
        pendingDelete = file.path
        reload()
        currentPos = pos.coerceAtMost(pages.lastIndex)
        binding.pager.scrollToPosition(currentPos)
        updateIndicator()

        val path = file.path
        Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                if (stagingDir != null) {
                    File(path + DEL_SUFFIX).takeIf { it.exists() }?.renameTo(File(path))
                }
                pendingDelete = null
                reload()
                currentPos = pos.coerceAtMost(pages.lastIndex)
                binding.pager.scrollToPosition(currentPos)
                updateIndicator()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    // Only commit this snackbar's own page: a replacement may
                    // already have moved pendingDelete on to a newer one.
                    if (event != DISMISS_EVENT_ACTION && pendingDelete == path) {
                        commitPendingDelete()
                    }
                }
            })
            .show()
    }

    /** Last page of an unsaved scan: deleting it means discarding the scan. */
    private fun confirmStagingDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.review_discard_title)
            .setMessage(R.string.review_discard_msg)
            .setPositiveButton(R.string.discard) { _, _ ->
                stagingDir?.deleteRecursively()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Crop (manual corner drag; no auto-detection)
    // ------------------------------------------------------------------

    private fun startCrop() {
        val pos = currentPosition()
        val file = pages.getOrNull(pos) ?: return
        if (binding.cropOverlay.visibility == android.view.View.VISIBLE) return
        lifecycleScope.launch {
            try {
                // Land any pending delete first so the page index stays valid.
                if (stagingDir == null) withContext(Dispatchers.IO) { commitPendingDeleteNow() }
            } catch (_: Exception) {
                // Best effort; cropping proceeds against the visible page.
            }
            showCropOverlay(pos, file)
        }
    }

    private fun showCropOverlay(pos: Int, file: File) {
        cropIndex = pos
        cropPath = file.path
        binding.cropView.clearImage()
        binding.cropOverlay.visibility = android.view.View.VISIBLE

        // Display-size copy only (max 2048px) — the full-res file is read on Apply.
        val key = "${file.path}|${file.lastModified()}|${file.length()}"
        binding.cropView.tag = key
        fullExecutor.execute {
            val bmp = try {
                ImageTools.decodeSampled(file.readBytes(), 2048)
            } catch (_: Exception) {
                null
            } ?: return@execute
            runOnUiThread {
                if (binding.cropView.tag == key) {
                    cropBitmap?.recycle()
                    cropBitmap = bmp
                    binding.cropView.setImage(bmp)
                } else {
                    bmp.recycle()
                }
            }
        }
    }

    private fun closeCrop() {
        binding.cropOverlay.visibility = android.view.View.GONE
        binding.cropView.clearImage()
        binding.cropView.tag = null // drop any in-flight decode result
        cropBitmap?.recycle()
        cropBitmap = null
        cropPath = null
    }

    private fun applyCrop() {
        val path = cropPath ?: return closeCrop()
        val bmp = cropBitmap ?: return closeCrop()
        val rect = binding.cropView.getCropRect()
        if (rect.width() < 1f || rect.height() < 1f) return closeCrop()

        val file = File(path)
        val size = try {
            ImageTools.imageSize(file.readBytes())
        } catch (_: Exception) {
            null
        } ?: return closeCrop()

        // Scale the display-copy rectangle onto the full-resolution file.
        val sx = size.first.toFloat() / bmp.width
        val sy = size.second.toFloat() / bmp.height
        val fileRect = RectF(rect.left * sx, rect.top * sy, rect.right * sx, rect.bottom * sy)
        val pos = cropIndex
        val dir = stagingDir
        closeCrop()

        lifecycleScope.launch {
            try {
                if (dir != null) {
                    withContext(Dispatchers.IO) { ImageTools.cropTo(file, fileRect) }
                } else {
                    val id = docId ?: return@launch
                    repo.cropPage(id, pos, fileRect)
                }
                reload()
                currentPos = pos.coerceAtMost(pages.lastIndex)
                binding.pager.scrollToPosition(currentPos)
                updateIndicator()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PageViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Deletes the Undo-pending page for good. No-op when nothing is pending. */
    internal fun commitPendingDelete() {
        val path = pendingDelete ?: return
        pendingDelete = null
        if (stagingDir != null) {
            // Hidden since the rename; only now does the file really go.
            File(path + DEL_SUFFIX).takeIf { it.exists() }?.delete()
            return
        }
        val id = docId ?: return
        committingDeletes.add(path)
        deleteScope.launch {
            try {
                val doc = repo.listDocuments().find { it.id == id } ?: return@launch
                val idx = doc.pageFiles.indexOfFirst { it.path == path }
                if (idx >= 0 && doc.pageFiles.size > 1) repo.deletePage(id, idx)
            } catch (_: Exception) {
                // Best effort; a failed delete leaves the page in place.
            }
            committingDeletes.remove(path)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    val keep = currentPosition().takeIf { it in pages.indices } ?: currentPos
                    reload()
                    currentPos = keep.coerceAtMost(pages.lastIndex)
                    binding.pager.scrollToPosition(currentPos)
                    updateIndicator()
                }
            }
        }
    }

    /** Blocking variant used before index-sensitive work; clears pendingDelete. */
    private suspend fun commitPendingDeleteNow() {
        val path = pendingDelete ?: return
        pendingDelete = null
        val id = docId ?: return
        try {
            val doc = repo.listDocuments().find { it.id == id } ?: return
            val idx = doc.pageFiles.indexOfFirst { it.path == path }
            if (idx >= 0 && doc.pageFiles.size > 1) repo.deletePage(id, idx)
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private inner class PagerAdapter : RecyclerView.Adapter<PagerVH>() {
        var items: List<File> = emptyList()

        fun submit(newItems: List<File>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PagerVH =
            PagerVH(
                ItemPagerPageBinding.inflate(
                    android.view.LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun onBindViewHolder(holder: PagerVH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }

    private inner class PagerVH(private val binding: ItemPagerPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /**
         * Decodes a display-size copy (max 2048px, never the raw multi-MB
         * file) on a background thread; the tag guard drops late results if
         * this cell was rebound to another page first.
         */
        fun bind(file: File) {
            val key = "${file.path}|${file.lastModified()}|${file.length()}"
            binding.pageImage.tag = key
            fullExecutor.execute {
                val bmp = try {
                    ImageTools.decodeSampled(file.readBytes(), 2048)
                } catch (_: Exception) {
                    null
                } ?: return@execute
                runOnUiThread {
                    if (binding.pageImage.tag == key) {
                        binding.pageImage.setImageBitmap(bmp)
                    } else {
                        bmp.recycle()
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DOC_ID = "doc_id"
        private const val EXTRA_PAGE = "page_index"
        private const val EXTRA_STAGING = "staging_dir"
        private const val FILTERS_FILE = "filters.txt"
        private const val DEL_SUFFIX = ".del"

        fun intent(context: Context, docId: String, pageIndex: Int): Intent =
            Intent(context, PageViewerActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_PAGE, pageIndex)

        /** Full-screen view over an unsaved scan (the review screen's staging folder). */
        fun stagingIntent(context: Context, stagingDir: String, pageIndex: Int): Intent =
            Intent(context, PageViewerActivity::class.java)
                .putExtra(EXTRA_STAGING, stagingDir)
                .putExtra(EXTRA_PAGE, pageIndex)
    }
}
