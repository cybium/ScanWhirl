package com.scanwhirl.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityDocumentViewerBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.tools.OcrFlow
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SavedSnackbar
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocumentViewerActivity : BaseActivity(), HomeHost {

    private lateinit var binding: ActivityDocumentViewerBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }

    // test26 T5.2 (§4.5): the shared tool flows, hosted here (HomeHost).
    private val ocrFlow: OcrFlow by lazy { OcrFlow(activity = this, repo = repo, host = this) }
    private val compressFlow: CompressFlow by lazy {
        CompressFlow(
            activity = this, repo = repo, host = this,
            // Unreachable from startWith(): this screen already shows the scan.
            pickPdf = {},
            docs = { listOfNotNull(document) }
        )
    }
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
        binding.root.applySystemBarsPadding()

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

        // test26 T5.2 (§4.5): one home per action. Dock = Share · Edit · Text ·
        // More; everything secondary lives in showMoreSheet(); page-level work
        // lives in Select pages mode or the page editor.
        binding.btnShare.setOnClickListener {
            document?.let { doc ->
                ProtectionDialogs.guardExport(this, repo, doc, onResolved = { lifecycleScope.launch { reloadDocument() } }) {
                    ShareUtils.share(this, doc)
                }
            }
        }
        binding.btnEdit.setOnClickListener {
            docId?.let { id -> startActivity(PageViewerActivity.intent(this, id, 0)) }
        }
        binding.btnText.setOnClickListener { runTextFlow() }
        binding.btnMore.setOnClickListener { showMoreSheet() }
        // T4.6: the header category chip is tappable now — it opens the
        // category picker (the old "Categorize" list dialog is gone).
        binding.docCategoryChip.setOnClickListener { promptCategorize() }
        // Select pages (§4.5): the toggle + its contextual bar.
        binding.btnSelectPages.setOnClickListener { toggleSelectMode() }
        binding.btnCtxRotate.setOnClickListener { rotateSelected(clockwise = true) }
        binding.btnCtxExtract.setOnClickListener { extractSelected() }
        binding.btnCtxDelete.setOnClickListener { deleteSelectedPages() }
        // Title tappable → Rename (replaces the deleted toolbar rename icon).
        binding.toolbar.setOnClickListener { promptRename() }
        // The ONLY toolbar action: one always-shown download icon, no overflow (⋮).
        binding.toolbar.inflateMenu(R.menu.menu_document)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save_pdf) {
                savePdfToDocuments()
                true
            } else {
                false
            }
        }
        // Checkboxes stay hidden until Select pages is tapped (§4.5, D-D).
        binding.pageList.checkboxesVisible = false

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

    // test26 T5.2 (§4.5): the single Save-a-copy action is exposed both as a
    // toolbar listener (real taps) and via onMenuItemSelected (Robolectric's
    // clickMenuItem used by ExportHygieneTest dispatches through here).
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_document, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_save_pdf -> { savePdfToDocuments(); true }
        else -> super.onOptionsItemSelected(item)
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

    // test26 T5.2: menu_viewer.xml + the whole overflow machinery are gone
    // (D-A…D-F). The toolbar's single download icon is inflated in onCreate.

    /** Re-reads the document from disk and refreshes all UI. Returns false if it vanished. */
    private suspend fun reloadDocument(): Boolean {
        val id = docId ?: return false
        val doc = withContext(Dispatchers.IO) {
            repo.getDocument(id)
        }
        if (doc == null) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        document = doc
        binding.toolbar.title = doc.name
        binding.docLockBadge.isVisible = doc.isLocked
        binding.docVaultBadge.isVisible = doc.isVaulted
        // test25 Phase 5C: category tag in the header (recategorise funnels
        // through here too — see promptCategorize()).
        binding.docCategoryChip.text = "● " +
            com.scanwhirl.app.util.CategoryRules.label(doc.category).uppercase()
        binding.docCategoryChip.setTextColor(
            com.scanwhirl.app.util.CategoryStyle.color(doc.category)
        )
        binding.docCategoryChip.isVisible = true
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
    // Select pages mode (§4.5) + page actions (contextual bar)
    // ------------------------------------------------------------------

    /** True while the Select pages mode is on: checkboxes + contextual bar. */
    private var selectMode = false

    /** Enters or leaves Select pages mode; leaving clears the ticked set. */
    private fun toggleSelectMode() {
        if (selectMode) {
            exitSelectMode()
        } else {
            selectMode = true
            binding.pageList.checkboxesVisible = true
            binding.selContextRow.isVisible = true
            binding.btnSelectPages.setText(R.string.select_done)
        }
    }

    private fun exitSelectMode() {
        selectMode = false
        selected.clear()
        binding.pageList.checkboxesVisible = false
        binding.selContextRow.isVisible = false
        binding.btnSelectPages.setText(R.string.select_pages)
        binding.pageList.refresh()
        updateSelectionUi()
    }

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
                        val doc = repo.getDocument(id) ?: break
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

    /**
     * Extract (§4.5 contextual bar): the ticked pages become a NEW document —
     * this one keeps every page, its exports and its category.
     */
    private fun extractSelected() {
        val paths = requireSelection() ?: return
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                // Land pending deletes first so disk indices are stable.
                withContext(Dispatchers.IO) { commitPendingDeletesNow() }
                val fresh = repo.getDocument(id) ?: return@launch
                val wanted = paths.toSet()
                val indices = fresh.pageFiles.indices.filter {
                    fresh.pageFiles[it].path in wanted
                }
                if (indices.isEmpty()) return@launch
                withContext(Dispatchers.IO) { repo.extractPages(id, indices) }
                exitSelectMode()
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.extracted, indices.size),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
                    val doc = repo.getDocument(id) ?: break
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
            val doc = repo.getDocument(id) ?: return
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
                val doc = repo.getDocument(id) ?: return@launch
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

    /**
     * Saves a copy of the document PDF into Documents/ScanWhirl (T4.7) with the
     * M06 export transaction. The PDF is streamed, never loaded whole, and an
     * earlier copy is never overwritten. Undo only ever removes a copy this save
     * created.
     */
    private fun savePdfToDocuments() {
        val doc = document ?: return
        // PM-3.1c: a document whose lock and PDF disagree is repaired first, never saved silently.
        ProtectionDialogs.guardExport(this, repo, doc, onResolved = { lifecycleScope.launch { reloadDocument() } }) {
            if (!doc.pdfFile.exists() || doc.pdfFile.length() == 0L) {
                Toast.makeText(this, R.string.pdf_missing, Toast.LENGTH_SHORT).show()
                return@guardExport
            }
            withStoragePermission {
                lifecycleScope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            DownloadSaver.savePdfFile(
                                this@DocumentViewerActivity,
                                doc.pdfFile,
                                DownloadSaver.pdfNameFor(doc.name)
                            ).also {
                                // T4.8: remember the copy so delete can remove it. A failure to
                                // record it must not turn a finished export into a failed one.
                                try {
                                    repo.addExport(doc.id, it)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        SavedSnackbar.showExport(
                            this@DocumentViewerActivity,
                            binding.root,
                            saved,
                            R.string.pdf_saved_docs,
                            doc.id
                        )
                    } catch (e: CancellationException) {
                        throw e
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
    }

    /**
     * test26 T5.2 (§4.5, D-A): the Free upgrade pitch — the Pro suite sheet.
     * Every locked action funnels here from the More sheet's PRO pills.
     */
    private fun showProPanel() {
        val dossier = layoutInflater.inflate(R.layout.dialog_pro_panel, null)
        SwDialog.sheet(this)
            .setView(dossier)
            .setPositiveButton(android.R.string.ok, null)
            .showSw(isSheet = true)
    }

    /**
     * Dock "Text" (§4.5): OCR through the shared [OcrFlow] — one home per
     * action; Free taps land on the Pro panel.
     */
    private fun runTextFlow() {
        val doc = document ?: return
        ocrFlow.runOcrForScan(doc)
    }

    /**
     * Dock "More" (§4.5): the single sheet for every secondary action, in
     * exactly three groups. No OCR row (the dock's Text is its entry), no
     * Categorize (the chip is its entry), no save rows (the toolbar's one
     * download icon is the only export). Locked rows show a PRO pill to Free.
     */
    private fun showMoreSheet() {
        val doc = document ?: return
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_more_document, null)
        sheet.setContentView(view)
        val pro = ProGate.isPro(this)

        // Tools labels that flip with the document's own state.
        view.findViewById<android.widget.TextView>(R.id.labelLock).setText(
            if (doc.isLocked) R.string.btn_unlock_pdf else R.string.menu_protect_pdf
        )
        view.findViewById<android.widget.TextView>(R.id.labelVault).setText(
            if (doc.isVaulted) R.string.btn_vault_unhide else R.string.btn_vault_hide
        )
        // PRO pills: visible only to Free (D-A); Pro sees no badges. A locked
        // PDF's row is Unlock (Free, WP-22-5) and the vault row is never Pro
        // (remove is Free, new additions are unavailable), so neither is badged.
        view.findViewById<android.widget.TextView>(R.id.pillLock).isVisible = !pro && !doc.isLocked
        view.findViewById<android.widget.TextView>(R.id.pillVault).isVisible = false

        // Organize
        view.findViewById<View>(R.id.rowMoreRename).setOnClickListener {
            sheet.dismiss()
            promptRename()
        }
        view.findViewById<View>(R.id.rowMoreMove).setOnClickListener {
            sheet.dismiss()
            promptCategorize()
        }
        view.findViewById<View>(R.id.rowMoreDuplicate).setOnClickListener {
            sheet.dismiss()
            duplicateDocument()
        }
        // Tools
        view.findViewById<View>(R.id.rowMoreCompress).setOnClickListener {
            sheet.dismiss()
            compressFlow.startWith(doc)
        }
        // Unlock is free (WP-22-5); protect remains Pro-gated.
        view.findViewById<View>(R.id.rowMoreLock).setOnClickListener {
            sheet.dismiss()
            if (doc.isLocked) unlockCurrentPdf()
            else if (pro) promptProtectPdf() else showProPanel()
        }
        // Vault row is NOT Pro-guarded (WP-22): removing an existing vaulted
        // doc is Free; adding is unavailable and says so (no Pro redirect).
        // M08: adding to the legacy Vault is unavailable (O-02), so the row exists only to
        // take an existing Vault item back out — never as a dead end that just shows a toast.
        view.findViewById<View>(R.id.rowMoreVault).apply {
            isVisible = doc.isVaulted
            setOnClickListener {
                sheet.dismiss()
                toggleVaultDoc()
            }
        }
        // Danger: ordinary documents go to Trash; legacy Vault items are deleted permanently.
        view.findViewById<android.widget.TextView>(R.id.labelMoreDelete).setText(
            if (doc.isVaulted) R.string.delete_document else R.string.trash_move
        )
        view.findViewById<View>(R.id.rowMoreDelete).setOnClickListener {
            sheet.dismiss()
            confirmDelete(doc)
        }
        sheet.show()
    }

    /** More → Organize → Duplicate (§4.5): a copy stays put, Home refreshes. */
    private fun duplicateDocument() {
        val doc = document ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.duplicateDocument(doc.id, "${doc.name} Copy")
                }
                toast(R.string.saved_as_new, Toast.LENGTH_LONG)
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Removes password protection from this scan and restores the clean PDF. */
    private fun unlockCurrentPdf() {
        val doc = document ?: return
        // PM-3.1b: Unlock needs the current password (F9).
        LockFlow.promptCurrentPassword(this, doc.name) { pw ->
            lifecycleScope.launch {
                val progress = showProgress()
                try {
                    withContext(Dispatchers.IO) { repo.unlockPdf(doc.id, pw) }
                    progress.dismiss()
                    Toast.makeText(
                        this@DocumentViewerActivity,
                        R.string.unlock_pdf_done,
                        Toast.LENGTH_LONG
                    ).show()
                    reloadDocument()
                } catch (e: PdfProtection.WrongPassword) {
                    progress.dismiss()
                    Toast.makeText(
                        this@DocumentViewerActivity,
                        R.string.unlock_pdf_wrong,
                        Toast.LENGTH_LONG
                    ).show()
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

    /** Moves this scan out of the 4-digit PIN Hidden Vault (free, WP-22-6).
     *  Moving INTO vault is disabled until encrypted Private ships (WP-14). */
    private fun toggleVaultDoc() {
        val doc = document ?: return
        if (doc.isVaulted) {
            // Removing from vault is always free — users can always access their own files.
            repo.setVaulted(doc.id, false)
            Toast.makeText(this, R.string.vault_moved_out, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { reloadDocument() }
        } else {
            // New vault additions disabled until WP-14 encrypted Private ships.
            Toast.makeText(this, R.string.vault_new_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** Password-protect the document's PDF (Pro; honest RC4-128 note included). */
    private fun promptProtectPdf() {
        if (!ProGate.isPro(this)) return
        val doc = document ?: return
        withStoragePermission {
            val (builder, input) = SwDialog.input(
                this@DocumentViewerActivity,
                hint = getString(R.string.protect_hint),
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            builder
                .setTitle(R.string.protect_title)
                .setMessage(R.string.protect_note)
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
                .showSw(isSheet = false)
        }
    }

    private fun promptRename() {
        val doc = document ?: return
        val (builder, input) = SwDialog.input(this, hint = getString(R.string.rename_title), initial = doc.name)
        builder
            .setTitle(R.string.rename_title)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()
                if (!newName.isNullOrBlank()) {
                    repo.renameDocument(doc.id, newName.trim())
                    dirty = true
                    lifecycleScope.launch { reloadDocument() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    /**
     * T4.6: the header category chip — and, until T5.2 removes it (D-F), the
     * overflow `Categorize` item — open the T4.2 category picker. The old
     * single-choice "Categorize this document" list dialog is gone; a pick
     * saves on IO and repaints the chip through [reloadDocument] (the chip
     * itself is the confirmation, no toast).
     */
    private fun promptCategorize() {
        val doc = document ?: return
        CategoryPickerSheet.show(this, doc.category) { cat ->
            lifecycleScope.launch {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repo.setCategory(doc.id, cat)
                }
                reloadDocument()
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
        SwDialog.card(this)
            .setTitle(R.string.save_changes_title)
            .setMessage(R.string.save_changes_msg)
            .setPositiveButton(R.string.overwrite_pdf) { _, _ -> finish() }
            .setNeutralButton(R.string.save_as_new) { _, _ -> saveAsNewDocument() }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
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
                    val currentName = repo.getDocument(id)?.name ?: "Document"
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

    // ------------------------------------------------------------------
    // HomeHost (§4.5): the tool flows (OCR, Compress) ask the host for
    // progress/toasts — DocumentViewer answers for itself.
    // ------------------------------------------------------------------

    override fun refreshList() {
        lifecycleScope.launch { reloadDocument() }
    }

    override fun showProgress(titleRes: Int): androidx.appcompat.app.AlertDialog =
        SwDialog.progress(this, titleRes = titleRes)

    override fun toast(res: Int, duration: Int) {
        Toast.makeText(this, res, duration).show()
    }

    override fun toast(message: CharSequence, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    /**
     * M07 P1: an ordinary document moves to Trash at once; Home shows the Undo
     * snackbar as this screen closes. A legacy Vault item never enters Trash, so it
     * keeps the explicit permanent delete below.
     */
    private fun confirmDelete(doc: ScanDocument) {
        if (doc.isVaulted) {
            confirmVaultDelete(doc)
            return
        }
        lifecycleScope.launch {
            val moved = withContext(NonCancellable + Dispatchers.IO) {
                TrashActions.moveAll(repo, listOf(doc.id))
            }
            if (moved.keys.isEmpty()) {
                Snackbar.make(binding.root, R.string.trash_move_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            TrashActions.post(moved)
            finish()
        }
    }

    private fun confirmVaultDelete(doc: ScanDocument) {
        // M07: only copies this document provably made (signed records) are offered.
        val copies = DeleteExports.copiesFor(repo, doc.id)
        val body = DeleteExports.dialogBody(this, copies.size)
        val box: android.widget.CheckBox? = body.findViewById(R.id.deleteCopiesBox)
        val builder = SwDialog.danger(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_vault_message, doc.name))
            .setView(body)
        builder
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteDocument(doc.id) // never blocked by copy removal
                if (box?.isChecked == true && copies.isNotEmpty()) {
                    lifecycleScope.launch {
                        // M06: removal re-reads each copy to prove it is still ours.
                        val (removed, failed) = withContext(NonCancellable + Dispatchers.IO) {
                            DeleteExports.removeCopies(applicationContext, copies)
                        }
                        // The viewer is closing, so hold the close until the
                        // result message has actually been seen.
                        val snackbar = Snackbar.make(
                            binding.root,
                            DeleteExports.message(this@DocumentViewerActivity, removed, failed),
                            Snackbar.LENGTH_LONG
                        )
                        snackbar.addCallback(object : Snackbar.Callback() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                if (!isFinishing) finish()
                            }
                        })
                        snackbar.show()
                    }
                } else {
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    companion object {
        private const val EXTRA_DOC_ID = "doc_id"
        fun intent(context: Context, docId: String): Intent =
            Intent(context, DocumentViewerActivity::class.java).putExtra(EXTRA_DOC_ID, docId)
    }
}
