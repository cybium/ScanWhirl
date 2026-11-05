package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityDocumentViewerBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class DocumentViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentViewerBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }
    private var document: ScanDocument? = null
    private var docId: String? = null

    /** Pages shown in the grid (document pages minus the one pending Undo). */
    private var visiblePages: List<java.io.File> = emptyList()

    /**
     * Path of the page whose Undo snackbar is showing. The file is only
     * deleted when the snackbar times out, is replaced by another delete,
     * or this screen closes (leaving = permanent).
     */
    private var pendingDelete: String? = null

    /** Pages whose delete has been triggered but not landed yet (no flash-back). */
    private val committingDeletes = mutableSetOf<String>()

    /** Not lifecycle-bound: a pending delete must land even during onDestroy. */
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var pendingDownload: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
            pendingDownload = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        docId = intent.getStringExtra(EXTRA_DOC_ID)
        if (!reloadDocument()) return

        binding.pageList.onTap = { index ->
            docId?.let { id ->
                startActivity(PageViewerActivity.intent(this, id, index))
            }
        }
        binding.pageList.onLongPressMenu = { index -> showPageActions(index) }
        binding.pageList.onDropped = { from, to -> reorderPages(from, to) }

        binding.btnShare.setOnClickListener { document?.let { ShareUtils.share(this, it) } }
        binding.btnDelete.setOnClickListener { document?.let { confirmDelete(it) } }
    }

    override fun onDestroy() {
        // Leaving the screen makes any pending delete permanent (rule: Undo
        // only lives as long as this screen).
        commitPendingDelete()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // AppCompat re-syncs the toolbar title from the manifest label during resume;
        // re-apply the document name so it wins.
        document?.let { binding.toolbar.title = it.name }
        // Returning from the full-screen page view: rotate/filter/delete may
        // have changed the document.
        if (document != null) reloadDocument()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_rename -> { promptRename(); true }
        R.id.action_save_device -> { promptDownload(); true }
        R.id.action_save_pdf -> { savePdfToDocuments(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Re-reads the document from disk and refreshes all UI. Returns false if it vanished. */
    private fun reloadDocument(): Boolean {
        val doc = repo.listDocuments().find { it.id == docId }
        if (doc == null) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        document = doc
        binding.toolbar.title = doc.name
        refreshGrid()
        binding.btnShare.isVisible = doc.pdfFile.exists() || doc.pageFiles.isNotEmpty()
        return true
    }

    /** Pushes the visible page list (minus any Undo-pending page) to the grid. */
    private fun refreshGrid() {
        val doc = document ?: return
        visiblePages = doc.pageFiles.filter {
            it.path != pendingDelete && it.path !in committingDeletes
        }
        binding.pageList.submit(visiblePages)
        binding.pageCount.text =
            resources.getQuantityString(R.plurals.pages, visiblePages.size, visiblePages.size)
    }

    /** Maps a grid (visible) index to the document's on-disk index, or -1. */
    private fun diskIndex(visibleIndex: Int): Int {
        val file = visiblePages.getOrNull(visibleIndex) ?: return -1
        return document?.pageFiles?.indexOfFirst { it.path == file.path } ?: -1
    }

    /** Copies the document PDF into Documents/ScanWhirl so it shows up in the Files app. */
    private fun savePdfToDocuments() {
        val doc = document ?: return
        if (!doc.pdfFile.exists() || doc.pdfFile.length() == 0L) {
            Toast.makeText(this, R.string.pdf_missing, Toast.LENGTH_SHORT).show()
            return
        }
        withStoragePermission {
            lifecycleScope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        DownloadSaver.savePdf(
                            this@DocumentViewerActivity,
                            doc.pdfFile.readBytes(),
                            DownloadSaver.pdfNameFor(doc.name)
                        )
                    }
                    Toast.makeText(
                        this@DocumentViewerActivity,
                        getString(R.string.pdf_saved_docs, name),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@DocumentViewerActivity,
                        getString(R.string.pdf_save_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Long-press menu: only Save / Rotate / Delete (reorder is drag now). */
    internal fun showPageActions(index: Int) {
        val doc = document ?: return
        if (index !in visiblePages.indices) return

        val actions = buildList {
            add(getString(R.string.save_this_page) to Action.SAVE_DEVICE)
            add(getString(R.string.action_rotate) to Action.ROTATE)
            if (visiblePages.size > 1) add(getString(R.string.action_delete_page) to Action.DELETE)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.page_actions_title, index + 1))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                performPageAction(actions[which].second, index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private enum class Action { SAVE_DEVICE, ROTATE, DELETE }

    private fun performPageAction(action: Action, visibleIndex: Int) {
        if (action == Action.SAVE_DEVICE) {
            downloadPages(listOf(visibleIndex))
            return
        }
        if (action == Action.DELETE) {
            showUndoDelete(visibleIndex)
            return
        }
        val id = docId ?: return
        val disk = diskIndex(visibleIndex)
        if (disk < 0) return
        lifecycleScope.launch {
            try {
                when (action) {
                    Action.SAVE_DEVICE -> Unit // handled before the coroutine
                    Action.ROTATE -> repo.rotatePage(id, disk)
                    Action.DELETE -> Unit // handled before the coroutine
                }
                reloadDocument()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Hides the page immediately and offers Undo. The file is deleted only
     * when the snackbar times out, is replaced, or the screen closes.
     */
    private fun showUndoDelete(visibleIndex: Int) {
        val file = visiblePages.getOrNull(visibleIndex) ?: return
        if ((document?.pageFiles?.size ?: 0) <= 1) {
            Toast.makeText(this, R.string.last_page_guard, Toast.LENGTH_LONG).show()
            return
        }
        // This snackbar replaces any previous one: the old page's Undo window
        // is over, so its delete commits now.
        commitPendingDelete()

        pendingDelete = file.path
        refreshGrid()
        Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                pendingDelete = null
                refreshGrid()
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
            // Refresh only now: pendingDelete is cleared, so an earlier refresh
            // would flash the page back before it is actually gone.
            runOnUiThread {
                if (!isFinishing && !isDestroyed) refreshGrid()
            }
        }
    }

    /** Persists a finished drag: the grid's new order becomes the disk order. */
    internal fun reorderPages(from: Int, to: Int) {
        val id = docId ?: return
        // The grid's pre-drag base order is exactly `visiblePages` (snapshot).
        val baseFile = visiblePages.getOrNull(from) ?: return
        lifecycleScope.launch {
            try {
                // Any pending delete must land first so grid indices == disk indices.
                withContext(Dispatchers.IO) { commitPendingDeleteNow() }
                val doc = repo.listDocuments().find { it.id == id } ?: return@launch
                val baseFrom = doc.pageFiles.indexOfFirst { it.path == baseFile.path }
                if (baseFrom >= 0 && baseFrom != to) repo.movePage(id, baseFrom, to)
                reloadDocument()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                reloadDocument()
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

    private fun promptRename() {
        val doc = document ?: return
        val input = EditText(this).apply { setText(doc.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()
                if (!newName.isNullOrBlank()) {
                    repo.renameDocument(doc.id, newName.trim())
                    reloadDocument()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptDownload() {
        val doc = document ?: return
        if (visiblePages.isEmpty()) return
        val current = binding.pageList.firstVisiblePosition().coerceAtLeast(0)
        val items = arrayOf(
            getString(R.string.save_this_page),
            getString(R.string.save_all_pages)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.save_to_device)
            .setItems(items) { _, which ->
                val indices: List<Int> =
                    if (which == 0) listOf(current.coerceAtMost(visiblePages.lastIndex))
                    else visiblePages.indices.toList()
                downloadPages(indices)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadPages(indices: List<Int>) {
        val doc = document ?: return
        withStoragePermission {
            lifecycleScope.launch {
                var count = 0
                var lastName = ""
                var error: String? = null
                for (i in indices) {
                    if (i !in visiblePages.indices) continue
                    val file = visiblePages[i]
                    val name = DownloadSaver.fileNameFor(doc.name, i + 1, visiblePages.size)
                    try {
                        lastName = withContext(Dispatchers.IO) {
                            DownloadSaver.saveJpeg(
                                this@DocumentViewerActivity,
                                file.readBytes(),
                                name
                            )
                        }
                        count++
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
                val toast = when {
                    count == 1 -> getString(R.string.dl_saved, lastName)
                    count > 1 -> getString(R.string.dl_saved_all, count)
                    else -> getString(R.string.dl_failed, error)
                }
                Toast.makeText(
                    this@DocumentViewerActivity,
                    toast,
                    if (count > 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
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

    private fun confirmDelete(doc: ScanDocument) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, doc.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteDocument(doc.id)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_DOC_ID = "doc_id"
        fun intent(context: Context, docId: String): Intent =
            Intent(context, DocumentViewerActivity::class.java).putExtra(EXTRA_DOC_ID, docId)
    }
}
