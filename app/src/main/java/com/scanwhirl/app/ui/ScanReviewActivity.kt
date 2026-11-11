package com.scanwhirl.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityScanReviewBinding
import com.scanwhirl.app.store.SavedCopyPolicy
import com.scanwhirl.app.store.SavedReviewConflictException
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ScanFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Shown after the scanner finishes: the captured pages in the shared page grid
 * (tap = full-screen page view, checkbox = multi-select, grip = reorder), an
 * editable title, "Add pages" for another scanner batch, and one primary
 * "Save as PDF" action. Rotate/Delete for the ticked pages live in the toolbar
 * overflow. Back/discard asks for confirmation while pages exist.
 *
 * Pages live in a staging directory that is cleaned up on save or discard.
 * Per-page filters default to Enhance and are kept in `filters.txt` so the
 * full-screen view (a separate activity) sees the same choices.
 */
class ScanReviewActivity : BaseActivity() {

    private lateinit var binding: ActivityScanReviewBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }

    private lateinit var stagingDir: File
    private var pdfFile: File? = null
    private var pages: List<File> = emptyList()
    private var defaultName: String = "Document"
    private var saved = false
    private var saving = false

    /** Per-page filter by absolute path (missing entries default to Enhance). */
    private val filters = mutableMapOf<String, ScanFilter>()

    /** Original paths renamed to `.del` while their Undo snackbar is showing. */
    private val pendingDeletes = mutableSetOf<String>()

    /**
     * Set when pages were added/rotated/deleted/reordered since the scan:
     * the scanner's PDF no longer matches and must be regenerated.
     */
    private var structureChanged = false

    /** T4.1: the category the rail hands to saveScan. */
    private var chosenCategory: String = CategoryRules.UNSORTED

    /** T4.1: once the owner taps a chip by hand, the title stops re-suggesting. */
    private var categoryPickedByHand = false

    /** name -> lastModified at load; a diff on resume means the pager edited files. */
    private var initialSnapshot: Map<String, Long> = emptyMap()

    private val selected = linkedSetOf<String>()

    private var pendingDownload: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
            pendingDownload = null
        }

    /** Another scanner batch ("Add pages"), appended to this staging folder. */
    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scanResult?.pages.orEmpty().mapNotNull { it.imageUri }
            if (uris.isEmpty()) {
                Toast.makeText(this, R.string.scan_no_pages, Toast.LENGTH_SHORT).show()
            } else {
                appendStagedPages(uris)
            }
        }

    /** Direct gallery import ("Add from gallery"): EXIF orientation only, no crop. */
    private val galleryPagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                appendStagedPages(uris)
            } else {
                Toast.makeText(this, R.string.scan_no_pages, Toast.LENGTH_SHORT).show()
            }
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (saving) return
            // Clearing the selection first is friendlier than discarding the scan.
            if (selected.isNotEmpty()) {
                clearSelection()
                return
            }
            confirmDiscard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        onBackPressedDispatcher.addCallback(this, backCallback)

        stagingDir = File(intent.getStringExtra(EXTRA_STAGING) ?: return finish())
        pages = listStaged()
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
        setupCategoryRail()
        loadFilters()
        initialSnapshot = snapshot()

        binding.pageGrid.filterFor = { f -> filters[f.path] ?: ScanFilter.ORIGINAL }
        binding.pageGrid.checkedAt = { i -> selected.contains(pages.getOrNull(i)?.path) }
        binding.pageGrid.onTap = { i -> openPageViewer(i) }
        binding.pageGrid.onCheckToggle = { i -> toggleSelect(i) }
        binding.pageGrid.onDropped = { from, to -> reorderStaging(from, to) }

        binding.toolbar.inflateMenu(R.menu.menu_review)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rotate_left -> {
                    rotateSelected(clockwise = false)
                    true
                }
                R.id.action_rotate_right -> {
                    rotateSelected(clockwise = true)
                    true
                }
                R.id.action_delete_page -> {
                    deleteSelected()
                    true
                }
                R.id.action_add_pages -> {
                    launchAddPages()
                    true
                }
                R.id.action_add_gallery -> {
                    galleryPagesLauncher.launch("image/*")
                    true
                }
                else -> false
            }
        }

        binding.btnSelClear.setOnClickListener { clearSelection() }
        binding.btnSavePdf.setOnClickListener { saveAndFinish() }
    }

    override fun onResume() {
        super.onResume()
        if (saved) return
        // The full-screen view may have discarded the scan (last page deleted).
        if (!stagingDir.exists()) {
            finish()
            return
        }
        reloadFromDisk()
    }

    override fun onDestroy() {
        // Leaving the screen makes any pending page delete permanent.
        if (!saved) commitPendingDeletes(pendingDeletes.toList())
        super.onDestroy()
    }

    private fun currentTitle(): String =
        binding.titleInput.text?.toString()?.trim().orEmpty().ifBlank { defaultName }

    // ------------------------------------------------------------------
    // Category rail (T4.1)
    // ------------------------------------------------------------------

    /**
     * Single-select chips under the title. Pre-selects
     * [CategoryRules.classify] of the current name, re-suggests live as the
     * name changes, and stops doing so the moment a chip is tapped by hand.
     */
    private fun setupCategoryRail() {
        // Scoped context so only these chips pick up Widget.ScanWhirl.Chip
        // (same pattern as Home's filter rail).
        val ctx = ContextThemeWrapper(this, R.style.ThemeOverlay_ScanWhirl_ChipRail)
        for (cat in CategoryRules.ALL) {
            val chip = Chip(ctx).apply {
                id = android.view.View.generateViewId()
                tag = cat
                text = CategoryRules.label(cat)
                setOnClickListener { pickCategory(cat) }
            }
            binding.categoryRail.addView(chip)
        }
        binding.titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = suggestCategoryFromTitle()
        })
        suggestCategoryFromTitle()
    }

    /** Manual pick wins: the name stops re-suggesting (T4.1). */
    private fun pickCategory(cat: String) {
        categoryPickedByHand = true
        chosenCategory = cat
        paintCategoryRail()
    }

    /** Live suggestion from the title — until the owner picks by hand. */
    private fun suggestCategoryFromTitle() {
        if (categoryPickedByHand) return
        chosenCategory = CategoryRules.classify(currentTitle())
        paintCategoryRail()
    }

    /** One checked chip at a time; the hint shows while nothing is selected. */
    private fun paintCategoryRail() {
        val rail = binding.categoryRail
        for (i in 0 until rail.childCount) {
            val chip = rail.getChildAt(i) as Chip
            chip.isChecked = chip.tag == chosenCategory
        }
        binding.categoryCaption.isVisible = chosenCategory == CategoryRules.UNSORTED
    }

    // ------------------------------------------------------------------
    // Staging folder
    // ------------------------------------------------------------------

    private fun listStaged(): List<File> =
        stagingDir.listFiles { f -> f.extension.equals("jpg", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()

    private fun snapshot(): Map<String, Long> =
        pages.associate { it.path to it.lastModified() }

    /** Re-reads pages + filters from disk (after the pager edited them). */
    internal fun reloadFromDisk() {
        pages = listStaged()
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (snapshot() != initialSnapshot) structureChanged = true
        loadFilters()
        selected.retainAll(pages.map { it.path }.toSet())
        binding.pageGrid.submit(pages)
        updateSelectionUi()
    }

    /** Rebuilds the filter map: file entries win, missing pages default to Enhance. */
    private fun loadFilters() {
        val fromFile = File(stagingDir, FILTERS_FILE).takeIf { it.exists() }
            ?.readText()?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size == 2) parts[0] to ScanFilter.fromId(parts[1]) else null
            }?.toMap()
            ?: emptyMap()
        filters.clear()
        pages.forEach { filters[it.path] = fromFile[it.name] ?: ScanFilter.ORIGINAL }
    }

    // ------------------------------------------------------------------
    // Grid interactions: tap / multi-select / drag
    // ------------------------------------------------------------------

    private fun openPageViewer(index: Int) {
        startActivity(PageViewerActivity.stagingIntent(this, stagingDir.path, index))
    }

    /** Ticks/untick a page. Called by the grid checkbox. */
    internal fun toggleSelect(index: Int) {
        val path = pages.getOrNull(index)?.path ?: return
        if (!selected.remove(path)) selected.add(path)
        updateSelectionUi()
        binding.pageGrid.refresh()
    }

    internal fun clearSelection() {
        selected.clear()
        updateSelectionUi()
        binding.pageGrid.refresh()
    }

    private fun updateSelectionUi() {
        binding.selectBar.isVisible = selected.isNotEmpty()
        binding.selCount.text = getString(R.string.selected_count, selected.size)
    }

    /** Persists a finished drag: the grid's new order becomes the staging order. */
    private fun reorderStaging(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        lifecycleScope.launch {
            try {
                val moved = pages.toMutableList().apply { add(to, removeAt(from)) }
                val renumbered = moved.mapIndexed { i, f ->
                    String.format(Locale.ROOT, "page_%03d.jpg", i) to
                        (filters[f.path] ?: ScanFilter.ORIGINAL)
                }
                withContext(Dispatchers.IO) {
                    // Any pending delete must land first (its .del may collide).
                    commitPendingDeletes(pendingDeletes.toList())
                    val tmpNames = mutableListOf<File>()
                    moved.forEachIndexed { i, f ->
                        val tmp = File(stagingDir, String.format(Locale.ROOT, "tmp_%03d.jpg", i))
                        f.renameTo(tmp)
                        tmpNames.add(tmp)
                    }
                    tmpNames.forEachIndexed { i, tmp ->
                        tmp.renameTo(File(stagingDir, String.format(Locale.ROOT, "page_%03d.jpg", i)))
                    }
                }
                // Filters follow their files through the renumbering.
                renumbered.forEach { (name, filter) ->
                    filters[File(stagingDir, name).path] = filter
                }
                structureChanged = true
                // Write the renumbered map directly: `pages` is stale until reload.
                File(stagingDir, FILTERS_FILE).writeText(
                    renumbered.joinToString("\n") { (name, filter) -> "$name=${filter.id}" }
                )
                reloadFromDisk()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ScanReviewActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                reloadFromDisk()
            }
        }
    }

    // ------------------------------------------------------------------
    // Multi-select: rotate / delete
    // ------------------------------------------------------------------

    private fun rotateSelected(clockwise: Boolean) {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_SHORT).show()
            return
        }
        val targets = pages.filter { it.path in selected }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { targets.forEach { ImageTools.rotate90(it, clockwise) } }
                structureChanged = true
                reloadFromDisk()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ScanReviewActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Deletes the selection with one Undo snackbar: files are renamed to
     * `.del` immediately (hidden everywhere) and only really go when the
     * snackbar times out, is replaced, or the screen closes. Deleting every
     * page means discarding the scan — confirmed first.
     */
    internal fun deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (selected.size >= pages.size) {
            confirmDiscard()
            return
        }
        // This snackbar replaces any previous one: the old Undo window is over.
        commitPendingDeletes(pendingDeletes.toList())
        val victims = pages.filter { it.path in selected }
        pendingDeletes.addAll(victims.map { it.path })
        victims.forEach { it.renameTo(File(it.path + DEL_SUFFIX)) }
        selected.clear()
        reloadFromDisk()

        val batch = victims.map { it.path }
        Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undoDeletes(batch) }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) commitPendingDeletes(batch)
                }
            })
            .show()
    }

    private fun undoDeletes(paths: Collection<String>) {
        paths.forEach { path ->
            File(path + DEL_SUFFIX).takeIf { it.exists() }?.renameTo(File(path))
            pendingDeletes.remove(path)
        }
        reloadFromDisk()
    }

    /** Deletes the `.del` files of [paths] for good (no-op when already gone). */
    internal fun commitPendingDeletes(paths: Collection<String>) {
        paths.forEach { path ->
            pendingDeletes.remove(path)
            File(path + DEL_SUFFIX).takeIf { it.exists() }?.delete()
        }
    }

    // ------------------------------------------------------------------
    // Add pages (second scanner batch)
    // ------------------------------------------------------------------

    /**
     * Opens Google's on-device document scanner (ML Kit), like the home
     * screen. Gallery import is off inside the scanner because it applies
     * perspective crop — photos are added via "Add from gallery" instead.
     */
    private fun launchAddPages() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setScannerMode(
                if (Prefs.autoEnhanceScan(this)) {
                    GmsDocumentScannerOptions.SCANNER_MODE_FULL
                } else {
                    GmsDocumentScannerOptions.SCANNER_MODE_BASE
                }
            )
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setPageLimit(30)
            .build()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { e ->
                    val msg = if (e is ApiException) {
                        getString(R.string.scanner_unavailable, e.statusCode)
                    } else {
                        getString(R.string.scanner_failed, e.message)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.scanner_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Copies [uris] into the staging folder with continuing page numbers. */
    internal fun appendStagedPages(uris: List<Uri>) {
        lifecycleScope.launch {
            var appended = false
            try {
                withContext(Dispatchers.IO) {
                    var next = (stagingDir.listFiles { f -> f.extension.equals("jpg", true) }
                        ?.mapNotNull { it.nameWithoutExtension.removePrefix("page_").toIntOrNull() }
                        ?.maxOrNull() ?: 0)
                    uris.forEach { uri ->
                        val bytes = contentResolver.openInputStream(uri)?.use(ImageTools::readPageBytes)
                            ?: throw IllegalArgumentException("Cannot read this image. Choose it again.")
                        next += 1
                        File(stagingDir, String.format(Locale.ROOT, "page_%03d.jpg", next))
                            // Real JPEG, white alpha and EXIF orientation; no perspective crop.
                            .writeBytes(ImageTools.normalizePage(bytes))
                        appended = true
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ScanReviewActivity,
                    getString(R.string.stage_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                // Even when a later input is invalid, earlier added pages invalidate
                // the scanner PDF and must remain visible/recoverable in review.
                if (appended) {
                    pdfFile = null
                    structureChanged = true
                    reloadFromDisk()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Save / discard
    // ------------------------------------------------------------------

    private fun saveAndFinish(policy: SavedCopyPolicy = SavedCopyPolicy.ASK) {
        if (saving) return
        if (pages.isEmpty()) return
        withStoragePermission {
            saving = true
            val progress = showProgress()
            lifecycleScope.launch {
                try {
                    val uris = pages.map { Uri.fromFile(it) }
                    // Complete map: every page defaults to Original unless changed.
                    val perPage = pages.mapIndexed { i, f ->
                        i to (filters[f.path] ?: ScanFilter.ORIGINAL)
                    }.toMap()
                    // Transactional (WP-05): returns only once the document is published and
                    // verified. The staging folder carries the stable transaction identity, so
                    // a retry after process death resumes this save instead of duplicating it.
                    // The staging pages stay untouched until the verified document is in hand.
                    val doc = repo.saveScan(
                        uris,
                        null,
                        currentTitle(),
                        pageFilters = perPage,
                        category = chosenCategory,
                        reviewDir = stagingDir,
                        ifSavedCopyDiffers = policy
                    )
                    saved = true

                    // T4.7 (N10): only place a copy in Documents/ScanWhirl when the
                    // owner opted in — no more silent copies on every save. Streamed from
                    // the saved PDF (never loaded whole); the original is only read.
                    var exported: String? = null
                    if (Prefs.autoCopyToDocuments(this@ScanReviewActivity) &&
                        doc.pdfFile.exists() && doc.pdfFile.length() > 0
                    ) {
                        try {
                            exported = withContext(Dispatchers.IO) {
                                val copy = DownloadSaver.savePdfFile(
                                    this@ScanReviewActivity,
                                    doc.pdfFile,
                                    DownloadSaver.pdfNameFor(doc.name)
                                )
                                // T4.8: remember the copy so delete can remove it. A failure to
                                // record it must not turn a finished export into a failed one.
                                try {
                                    repo.addExport(doc.id, copy.uri)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
                                copy.name
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            exported = null
                        }
                    }

                    // Verified committed document in hand: only now is the staging source released.
                    stagingDir.deleteRecursively()
                    progress.dismiss()
                    val message = if (exported != null) {
                        getString(R.string.pdf_saved_docs, exported)
                    } else {
                        getString(R.string.scan_saved)
                    }
                    Toast.makeText(this@ScanReviewActivity, message, Toast.LENGTH_LONG).show()
                    startActivity(DocumentViewerActivity.intent(this@ScanReviewActivity, doc.id))
                    // Index the pages for text search in the background (Free, survives screen close).
                    // Indexing failure can never invalidate the saved PDF.
                    ScanWhirlApp.from(this@ScanReviewActivity).indexer.enqueue(doc.id)
                    finish()
                } catch (e: CancellationException) {
                    // Not a failed save: the transaction already cleaned up (or had committed).
                    saving = false
                    progress.dismiss()
                    throw e
                } catch (e: SavedReviewConflictException) {
                    // An earlier save of this review exists but is not a faithful save of what is
                    // on screen now. Nothing was saved, replaced or deleted; the owner chooses.
                    saving = false
                    progress.dismiss()
                    confirmSavedCopyConflict(e)
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

    /** Explicit choice after a replay conflict: a separate new document, or the earlier one. Staging is kept. */
    private fun confirmSavedCopyConflict(conflict: SavedReviewConflictException) {
        val message = if (conflict.reason == SavedReviewConflictException.Reason.REVIEW_CHANGED) {
            R.string.review_conflict_changed
        } else {
            R.string.review_conflict_modified
        }
        SwDialog.card(this)
            .setTitle(R.string.review_conflict_title)
            .setMessage(message)
            .setPositiveButton(R.string.review_conflict_save_new) { _, _ -> saveAndFinish(SavedCopyPolicy.SAVE_AS_NEW) }
            .setNeutralButton(R.string.review_conflict_open) { _, _ ->
                startActivity(DocumentViewerActivity.intent(this, conflict.documentId))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun confirmDiscard() {
        SwDialog.danger(this)
            .setTitle(R.string.review_discard_title)
            .setMessage(R.string.review_discard_msg)
            .setPositiveButton(R.string.discard) { _, _ -> discard() }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun discard() {
        saved = true // stops any later cleanup concerns; staging is removed now
        stagingDir.deleteRecursively()
        finish()
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
        return SwDialog.progress(this, titleRes = R.string.review_saving)
    }

    companion object {
        private const val EXTRA_STAGING = "staging_dir"
        private const val EXTRA_PDF = "pdf_path"
        private const val EXTRA_NAME = "default_name"
        private const val FILTERS_FILE = "filters.txt"
        private const val DEL_SUFFIX = ".del"

        fun intent(context: Context, stagingDir: String, pdfPath: String?, defaultName: String): Intent =
            Intent(context, ScanReviewActivity::class.java)
                .putExtra(EXTRA_STAGING, stagingDir)
                .putExtra(EXTRA_PDF, pdfPath)
                .putExtra(EXTRA_NAME, defaultName)
    }
}
