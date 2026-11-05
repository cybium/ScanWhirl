package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
    private var document: ScanDocument? = null
    private var pages: List<File> = emptyList()

    private var pendingDelete: String? = null
    private val committingDeletes = mutableSetOf<String>()
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Last known page (kept when the snap view isn't measurable yet). */
    private var currentPos: Int = 0

    /** Session memory of filters the user picked (disk keeps no filter state). */
    private val filterChoice = mutableMapOf<String, ScanFilter>()

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
        if (!reload()) return

        val start = intent.getIntExtra(EXTRA_PAGE, 0)
            .coerceIn(0, (pages.lastIndex).coerceAtLeast(0))
        currentPos = start
        binding.pager.scrollToPosition(start)
        updateIndicator()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnRotate.setOnClickListener { rotateCurrent() }
        binding.btnFilter.setOnClickListener { filterCurrent() }
        binding.btnDelete.setOnClickListener { deleteCurrent() }
    }

    override fun onDestroy() {
        // Leaving the screen makes any pending delete permanent.
        commitPendingDelete()
        super.onDestroy()
    }

    /** Re-reads the document (minus pending/committing deletes) into the pager. */
    private fun reload(): Boolean {
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
        val id = docId ?: return
        val pos = currentPosition()
        if (pos !in pages.indices) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { commitPendingDeleteNow() }
                repo.rotatePage(id, pos)
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
        val id = docId ?: return
        val pos = currentPosition()
        val file = pages.getOrNull(pos) ?: return
        val filters = ScanFilter.entries
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
            Toast.makeText(this, R.string.last_page_guard, Toast.LENGTH_LONG).show()
            return
        }
        // This snackbar replaces any previous one: the old Undo window is over.
        commitPendingDelete()

        pendingDelete = file.path
        reload()
        currentPos = pos.coerceAtMost(pages.lastIndex)
        binding.pager.scrollToPosition(currentPos)
        updateIndicator()

        Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                pendingDelete = null
                reload()
                currentPos = pos.coerceAtMost(pages.lastIndex)
                binding.pager.scrollToPosition(currentPos)
                updateIndicator()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) commitPendingDelete()
                }
            })
            .show()
    }

    /** Deletes the Undo-pending page for good. No-op when nothing is pending. */
    internal fun commitPendingDelete() {
        val path = pendingDelete ?: return
        pendingDelete = null
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

        fun intent(context: Context, docId: String, pageIndex: Int): Intent =
            Intent(context, PageViewerActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_PAGE, pageIndex)
    }
}
