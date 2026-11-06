package com.scanwhirl.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityDocumentViewerBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocumentViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityDocumentViewerBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }
    private var document: ScanDocument? = null
    private var docId: String? = null

    /** Pages shown in the grid (document pages minus any pending Undo deletes). */
    private var visiblePages: List<java.io.File> = emptyList()

    /** Checked page paths; page actions in the toolbar menu act on these. */
    private val selected = linkedSetOf<String>()

    /**
     * Paths whose Undo snackbar is showing. They are hidden from the grid and
     * only deleted once the snackbar times out, is replaced, or this screen
     * closes (leaving = permanent).
     */
    private val pendingDeletes = linkedSetOf<String>()

    /** Pages whose delete has been triggered but not landed yet (no flash-back). */
    private val committingDeletes = mutableSetOf<String>()

    /** True once this editing session changed anything (drives the Back prompt). */
    private var dirty = false

    /** Pre-edit copy of the document directory, for "Save as new". */
    private var snapshotDir: File? = null

    /** File signature at open; a diff on resume means the page view edited files. */
    private var startSignature: String = ""

    /** True once onCreate has finished its async first load. */
    private var firstLoad = false

    /** Not lifecycle-bound: a pending delete must land even during onDestroy. */
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Background snapshot of the pre-edit document (keeps opening large docs fast). */
    private val snapshotScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var snapshotJob: Job? = null

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
        binding.toolbar.setNavigationOnClickListener { handleEditorBack() }

        docId = intent.getStringExtra(EXTRA_DOC_ID)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleEditorBack()
        })

        binding.pageList.onTap = { index ->
            docId?.let { id ->
                startActivity(PageViewerActivity.intent(this, id, index))
            }
        }
        binding.pageList.onCheckToggle = { index -> toggleSelected(index) }
        binding.pageList.checkedAt = { i ->
            selected.contains(visiblePages.getOrNull(i)?.path)
        }
        binding.pageList.onDropped = { from, to -> reorderPages(from, to) }

        binding.btnShare.setOnClickListener { document?.let { ShareUtils.share(this, it) } }
        binding.btnDelete.setOnClickListener { document?.let { confirmDelete(it) } }
        binding.btnProtect.setOnClickListener { promptProtectPdf() }
        binding.btnLongImage.setOnClickListener { shareLongImage() }

        // First load (off the main thread), then start the pre-edit snapshot.
        lifecycleScope.launch {
            if (!reloadDocument()) return@launch
            startSignature = signature()
            firstLoad = true
            // Editing happens in place; keep a pre-edit copy so Back can offer
            // "Save as new" (original restored) as well as "Overwrite".
            docId?.let { id ->
                if (snapshotDir == null) {
                    snapshotJob = snapshotScope.launch {
                        snapshotDir = takeSnapshot(id)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // Leaving the screen makes any pending delete permanent (rule: Undo
        // only lives as long as this screen).
        if (!isChangingConfigurations) {
            snapshotJob?.cancel()
            commitPendingDelete()
            snapshotDir?.deleteRecursively()
            snapshotDir = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // AppCompat re-syncs the toolbar title from the manifest label during resume;
        // re-apply the document name so it wins.
        document?.let { binding.toolbar.title = it.name }
        // Returning from the full-screen page view: rotate/filter/delete may
        // have changed the document.
        if (document != null && firstLoad) {
            lifecycleScope.launch {
                if (reloadDocument() && startSignature.isNotEmpty() && signature() != startSignature) {
                    dirty = true
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_rename -> { promptRename(); true }
        R.id.action_save_device -> { promptDownload(); true }
        R.id.action_save_pdf -> { savePdfToDocuments(); true }
        R.id.action_long_image -> { shareLongImage(); true }
        R.id.action_protect_pdf -> { promptProtectPdf(); true }
        R.id.action_rotate_left -> { rotateSelected(clockwise = false); true }
        R.id.action_rotate_right -> { rotateSelected(clockwise = true); true }
        R.id.action_delete_page -> { deleteSelectedPages(); true }
        R.id.action_save_page -> { saveSelectedPages(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Re-reads the document from disk and refreshes all UI. Returns false if it vanished. */
    private suspend fun reloadDocument(): Boolean {
        val doc = withContext(Dispatchers.IO) {
            repo.listDocuments().find { it.id == docId }
        }
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

    /** Pushes the visible page list (minus pending deletes) to the grid. */
    private fun refreshGrid() {
        val doc = document ?: return
        visiblePages = doc.pageFiles.filter {
            it.path !in pendingDeletes && it.path !in committingDeletes
        }
        // Pages that vanished (deleted/screen closed) must not stay checked.
        selected.retainAll(visiblePages.map { it.path }.toSet())
        binding.pageList.submit(visiblePages)
        binding.pageCount.text =
            resources.getQuantityString(R.plurals.pages, visiblePages.size, visiblePages.size)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        supportActionBar?.subtitle =
            if (selected.isEmpty()) null
            else resources.getQuantityString(
                R.plurals.selected_count, selected.size, selected.size
            )
    }

    /** Maps a grid (visible) index to the document's on-disk index, or -1. */
    private fun diskIndex(visibleIndex: Int): Int {
        val file = visiblePages.getOrNull(visibleIndex) ?: return -1
        return document?.pageFiles?.indexOfFirst { it.path == file.path } ?: -1
    }

    // ------------------------------------------------------------------
    // Selection (checkbox) + page actions (toolbar overflow)
    // ------------------------------------------------------------------

    /** Ticks/untick a page. Called by the grid checkbox. */
    internal fun toggleSelected(index: Int) {
        val path = visiblePages.getOrNull(index)?.path ?: return
        if (!selected.remove(path)) selected.add(path)
        binding.pageList.refresh()
        updateSelectionUi()
    }

    private fun requireSelection(): List<String>? {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_SHORT).show()
            return null
        }
        return selected.toList()
    }

    /** Rotates every ticked page (a single selection rotates just that page). */
    internal fun rotateSelected(clockwise: Boolean) {
        val paths = requireSelection() ?: return
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Land pending deletes first so disk indices are stable.
                    commitPendingDeletesNow()
                    for (path in paths) {
                        val doc = repo.listDocuments().find { it.id == id } ?: break
                        val idx = doc.pageFiles.indexOfFirst { it.path == path }
                        if (idx >= 0) repo.rotatePage(id, idx, clockwise)
                    }
                }
                dirty = true
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
     * Deletes every ticked page with one Undo snackbar. Pages stay on disk (and
     * are only hidden) until the snackbar times out, is replaced, or the screen
     * closes.
     */
    internal fun deleteSelectedPages() {
        val paths = requireSelection() ?: return
        document ?: return
        if (paths.size >= visiblePages.size) {
            Toast.makeText(this, R.string.last_page_guard, Toast.LENGTH_LONG).show()
            return
        }
        // This snackbar replaces any previous one: the old Undo window is over.
        commitPendingDelete()

        pendingDeletes.addAll(paths)
        selected.clear()
        refreshGrid()
        dirty = true

        val batch = paths.toList()
        Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                pendingDeletes.removeAll(batch.toSet())
                refreshGrid()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) commitPendingDeletes(batch)
                }
            })
            .show()
    }

    private fun saveSelectedPages() {
        val paths = requireSelection() ?: return
        val indices = visiblePages.indices.filter { visiblePages[it].path in paths }
        if (indices.isNotEmpty()) downloadPages(indices)
    }

    /** Deletes all Undo-pending pages for good. No-op when nothing is pending. */
    internal fun commitPendingDelete() = commitPendingDeletes(pendingDeletes.toList())

    /** Deletes the given Undo-pending paths (idempotent; recomputes each index). */
    internal fun commitPendingDeletes(paths: Collection<String>) {
        val id = docId ?: return
        val toDelete = paths.filter { pendingDeletes.contains(it) }
        if (toDelete.isEmpty()) return
        pendingDeletes.removeAll(toDelete.toSet())
        committingDeletes.addAll(toDelete)
        deleteScope.launch {
            try {
                for (path in toDelete) {
                    val doc = repo.listDocuments().find { it.id == id } ?: break
                    val idx = doc.pageFiles.indexOfFirst { it.path == path }
                    if (idx >= 0 && doc.pageFiles.size > 1) repo.deletePage(id, idx)
                }
            } catch (_: Exception) {
                // Best effort; a failed delete leaves the page in place.
            }
            committingDeletes.removeAll(toDelete.toSet())
            // Refresh only now: the pages are gone from disk, so they can't flash back.
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    lifecycleScope.launch { reloadDocument() }
                }
            }
        }
    }

    /** Blocking variant used before index-sensitive work; clears all pending deletes. */
    private suspend fun commitPendingDeletesNow() {
        val id = docId ?: return
        val toDelete = pendingDeletes.toList()
        if (toDelete.isEmpty()) return
        pendingDeletes.removeAll(toDelete.toSet())
        for (path in toDelete) {
            val doc = repo.listDocuments().find { it.id == id } ?: return
            val idx = doc.pageFiles.indexOfFirst { it.path == path }
            if (idx >= 0 && doc.pageFiles.size > 1) repo.deletePage(id, idx)
        }
    }

    // ------------------------------------------------------------------
    // Reorder
    // ------------------------------------------------------------------

    /** Persists a finished drag: the grid's new order becomes the disk order. */
    internal fun reorderPages(from: Int, to: Int) {
        val id = docId ?: return
        // The grid's pre-drag base order is exactly `visiblePages` (snapshot).
        val baseFile = visiblePages.getOrNull(from) ?: return
        lifecycleScope.launch {
            try {
                // Any pending delete must land first so grid indices == disk indices.
                withContext(Dispatchers.IO) { commitPendingDeletesNow() }
                val doc = repo.listDocuments().find { it.id == id } ?: return@launch
                val baseFrom = doc.pageFiles.indexOfFirst { it.path == baseFile.path }
                if (baseFrom >= 0 && baseFrom != to) repo.movePage(id, baseFrom, to)
                dirty = true
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

    // ------------------------------------------------------------------
    // Document-level actions
    // ------------------------------------------------------------------

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

    /** Stitches the document's pages into one tall image (Pro) and shares it. */
    private fun shareLongImage() {
        if (!ProGate.isPro(this)) return
        val doc = document ?: return
        if (doc.pageFiles.isEmpty()) return
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "documents/share").apply { mkdirs() }
                    val file = File(dir, ShareUtils.sanitizeName(doc.name) + " - long.jpg")
                    LongImage.stitch(doc.pageFiles, file)
                    file
                }
                progress.dismiss()
                ShareUtils.shareFile(this@DocumentViewerActivity, out, "image/jpeg")
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Password-protect the document's PDF (Pro; honest RC4-128 note included). */
    private fun promptProtectPdf() {
        if (!ProGate.isPro(this)) return
        val doc = document ?: return
        withStoragePermission {
            val input = android.widget.EditText(this@DocumentViewerActivity).apply {
                hint = getString(R.string.protect_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this@DocumentViewerActivity)
                .setTitle(R.string.protect_title)
                .setMessage(R.string.protect_note)
                .setView(input)
                .setPositiveButton(R.string.protect_apply) { _, _ ->
                    val password = input.text?.toString().orEmpty()
                    if (password.isBlank()) {
                        Toast.makeText(this@DocumentViewerActivity, R.string.protect_hint, Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch {
                            val progress = showProgress()
                            try {
                                withContext(Dispatchers.IO) { repo.protectPdf(doc.id, password) }
                                progress.dismiss()
                                Toast.makeText(
                                    this@DocumentViewerActivity,
                                    R.string.protect_done,
                                    Toast.LENGTH_LONG
                                ).show()
                                reloadDocument()
                            } catch (e: Exception) {
                                progress.dismiss()
                                Toast.makeText(
                                    this@DocumentViewerActivity,
                                    getString(R.string.page_op_failed, e.message),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun promptRename() {        val doc = document ?: return
        val input = EditText(this).apply { setText(doc.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()
                if (!newName.isNullOrBlank()) {
                    repo.renameDocument(doc.id, newName.trim())
                    dirty = true
                    lifecycleScope.launch { reloadDocument() }
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

    // ------------------------------------------------------------------
    // Leaving the editor: Overwrite / Save as new / Cancel
    // ------------------------------------------------------------------

    private fun handleEditorBack() {
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_changes_title)
            .setMessage(R.string.save_changes_msg)
            .setPositiveButton(R.string.overwrite_pdf) { _, _ -> finish() }
            .setNeutralButton(R.string.save_as_new) { _, _ -> saveAsNewDocument() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Copies the edited state to a new document, then puts the pre-edit
     * snapshot back over the original so "Save as new" never touches it.
     */
    private fun saveAsNewDocument() {
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Make sure the pre-edit snapshot is complete before restoring.
                    snapshotJob?.join()
                    commitPendingDeletesNow()
                    val currentName = repo.listDocuments().find { it.id == id }?.name ?: "Document"
                    repo.duplicateDocument(id, "$currentName (edited)")
                    restoreSnapshot(id)
                }
                dirty = false
                Toast.makeText(
                    this@DocumentViewerActivity,
                    R.string.saved_as_new,
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Pre-edit copy of the document directory (persistent files dir, never listed). */
    private fun takeSnapshot(id: String): File? = try {
        val src = File(filesDir, "documents/$id")
        // Files dir, not cache: a process death mid-edit must not lose the
        // revert copy, because the edits themselves are destructive.
        val dest = File(filesDir, "edit_snapshot_$id")
        dest.deleteRecursively()
        dest.mkdirs()
        src.listFiles()?.forEach { f ->
            if (f.isFile) f.copyTo(File(dest, f.name), overwrite = true)
        }
        dest
    } catch (_: Exception) {
        null
    }

    /** Restores the pre-edit files over the original document, then drops them. */
    private fun restoreSnapshot(id: String) {
        val snap = snapshotDir ?: return
        val dest = File(filesDir, "documents/$id")
        dest.listFiles()?.forEach { if (it.isFile) it.delete() }
        snap.listFiles()?.forEach { f ->
            if (f.isFile) f.copyTo(File(dest, f.name), overwrite = true)
        }
        snap.deleteRecursively()
        snapshotDir = null
    }

    /** Cheap change fingerprint: page names/sizes/timestamps + PDF size. */
    private fun signature(): String {
        val doc = document ?: return ""
        return buildString {
            append(doc.pageCount).append('|').append(doc.pdfFile.length())
            doc.pageFiles.forEach { f ->
                append('|').append(f.name)
                    .append(':').append(f.length())
                    .append(':').append(f.lastModified())
            }
        }
    }

    /** Simple determinate-free progress dialog for Pro export jobs. */
    private fun showProgress(): androidx.appcompat.app.AlertDialog {
        val bar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        return androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.progress_title)
            .setView(bar)
            .setCancelable(false)
            .show()
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
