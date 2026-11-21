package com.scanwhirl.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityScanReviewBinding
import com.scanwhirl.app.databinding.ItemReviewFilmBinding
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.store.SavedCopyPolicy
import com.scanwhirl.app.store.SavedReviewConflictException
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.util.CategoryLabels
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
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
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Shown after the scanner or a photo import: one review screen with two modes
 * (Review slice 1, "Index + Focus").
 *
 *  - FOCUS: one large page with "Page N of M", a numbered filmstrip, swipe or
 *    48dp arrows between pages, and labelled Crop / Rotate / Filter / Add page /
 *    Delete. Tapping the page (or Crop) opens the existing [PageViewerActivity]
 *    for zoom and crop; Focus comes back to the page that was on screen.
 *  - ARRANGE: the shared [com.scanwhirl.app.ui.pagegrid.PageGridView] with its
 *    2D grip drag, real page numbers, the selected page ringed, and Earlier /
 *    Later / Move to… which run the very same staging rename as a drag.
 *    Ticking checkboxes still selects several pages for bulk Rotate / Delete.
 *
 * One commit action, "Save N pages", runs the unchanged transactional
 * [saveAndFinish]. Nothing here says "saved" before that verified commit.
 * Back/discard asks for confirmation while pages exist.
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
    private var reviewStateReady = false
    private val reviewStateHandler = Handler(Looper.getMainLooper())
    private val persistReviewStateTask = Runnable { persistReviewState() }

    /** name -> lastModified at load; a diff on resume means the pager edited files. */
    private var initialSnapshot: Map<String, Long> = emptyMap()

    private val selected = linkedSetOf<String>()

    /** Review slice 1: the page shown in Focus and ringed in Arrange (index into [pages]). */
    internal var currentIndex = 0
        private set

    /** True while the Arrange grid is the visible mode. */
    internal var arrangeMode = false
        private set

    /** The owner confirmed the page order ("Order looks right") in this session. */
    private var orderChecked = false

    /** A filter was changed here (filters live in filters.txt, not in file mtimes). */
    private var filtersEdited = false

    /** Pages were already edited before a configuration change re-created this screen. */
    private var editedBeforeRecreate = false

    private fun isEdited() = structureChanged || filtersEdited || editedBeforeRecreate

    /**
     * A rotate or staging rename pass is running. Page indices and file names are
     * not stable until it lands, so new page operations, the viewer and Save wait.
     */
    internal var pageOpBusy = false
        private set

    private var applyingMode = false

    /** Page name reported by the viewer on return; resolved against the fresh list on reload. */
    private var returnedPageName: String? = null

    private val filmAdapter = FilmAdapter()

    /** One full-size decode at a time for the Focus page (bounded memory). */
    private val focusExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "review-focus").apply { isDaemon = true }
    }

    /** The existing full-screen viewer (zoom, crop); reports the page it closed on. */
    private val viewerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra(PageViewerActivity.RESULT_PAGE_NAME)?.let { returnedPageName = it }
        }

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
        val restored = savedInstanceState?.getString(REVIEW_STATE)?.let {
            PendingScans.decodeReviewState(stagingDir, it)
        } ?: PendingScans.latestReviewState(stagingDir)
        restored?.let {
            defaultName = it.defaultName
            chosenCategory = it.category
            categoryPickedByHand = it.categoryPickedByHand
        }
        binding.titleInput.setText(restored?.title ?: defaultName)
        setupCategoryRail()
        paintCategoryRail()
        reviewStateReady = true
        scheduleReviewState()
        loadFilters()
        initialSnapshot = snapshot()

        savedInstanceState?.let {
            currentIndex = it.getInt(STATE_CURRENT, 0)
            arrangeMode = it.getBoolean(STATE_ARRANGE, false)
            orderChecked = it.getBoolean(STATE_ORDER_OK, false)
            filtersEdited = it.getBoolean(STATE_FILTERS_EDITED, false)
            editedBeforeRecreate = it.getBoolean(STATE_EDITED, false)
        }
        currentIndex = currentIndex.coerceIn(0, pages.lastIndex)

        binding.pageGrid.filterFor = { f -> filters[f.path] ?: ScanFilter.ORIGINAL }
        binding.pageGrid.checkedAt = { i -> selected.contains(pages.getOrNull(i)?.path) }
        binding.pageGrid.currentAt = { i -> i == currentIndex }
        binding.pageGrid.spanCount = if (resources.configuration.screenWidthDp >= 360) 3 else 2
        binding.pageGrid.onTap = { i -> onGridTap(i) }
        binding.pageGrid.onCheckToggle = { i -> toggleSelect(i) }
        binding.pageGrid.onDropped = { from, to -> reorderStaging(from, to, announce = true) }

        setupFocus()
        setupArrange()
        fitLargeTextTools()
        binding.modeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !applyingMode) setMode(arrange = id == R.id.btnModeArrange)
        }
        applyMode(animate = false)
        binding.toolbar.setNavigationOnClickListener { if (!saving) confirmDiscard() }
        A11yTargets.fixTitleField(binding.titleInput)
        binding.titleInput.hint = getString(R.string.review_name_hint)
        binding.titleInput.contentDescription = getString(R.string.name_hint)

        binding.toolbar.inflateMenu(R.menu.menu_review)
        A11yTargets.widenOverflow(binding.toolbar)
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
        binding.btnSelRotate.setOnClickListener { rotateSelected(clockwise = true) }
        binding.btnSelDelete.setOnClickListener { deleteSelected() }
        // Same transactional save as ever; it only waits for a running rename to land.
        binding.btnSavePdf.setOnClickListener { if (!refuseWhileBusy()) saveAndFinish() }
    }

    override fun onResume() {
        super.onResume()
        if (saved || !::stagingDir.isInitialized) return
        // The full-screen view may have discarded the scan (last page deleted).
        if (!stagingDir.exists()) {
            finish()
            return
        }
        reloadFromDisk()
    }

    override fun onDestroy() {
        focusExecutor.shutdown()
        reviewStateHandler.removeCallbacks(persistReviewStateTask)
        // Leaving the screen makes any pending page delete permanent.
        if (!saved) commitPendingDeletes(pendingDeletes.toList())
        super.onDestroy()
    }

    private fun currentTitle(): String =
        binding.titleInput.text?.toString()?.trim().orEmpty().ifBlank { defaultName }

    private fun reviewState() = PendingScans.ReviewState(
        binding.titleInput.text?.toString().orEmpty(), defaultName, chosenCategory, categoryPickedByHand
    )

    private fun scheduleReviewState() {
        if (!reviewStateReady || saved || saving) return
        reviewStateHandler.removeCallbacks(persistReviewStateTask)
        reviewStateHandler.postDelayed(persistReviewStateTask, 350)
    }

    /** Lifecycle flush is submitted immediately and outlives this Activity.
     * Force-kill before the debounce/flush completes can still lose the last characters.
     */
    private fun persistReviewState() {
        reviewStateHandler.removeCallbacks(persistReviewStateTask)
        if (!reviewStateReady || saved || saving) return
        // Discarded elsewhere (the viewer deleted the last page): nothing left to describe,
        // and a failed write there must not be reported as lost metadata.
        if (!stagingDir.isDirectory) return
        val write = PendingScans.queueReviewState(stagingDir, reviewState())
        (application as ScanWhirlApp).appScope.launch {
            try {
                withContext(Dispatchers.IO) { write.get() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !saved && stagingDir.isDirectory) Toast.makeText(this@ScanReviewActivity,
                        R.string.review_state_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        persistReviewState()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT, currentIndex)
        outState.putBoolean(STATE_ARRANGE, arrangeMode)
        outState.putBoolean(STATE_ORDER_OK, orderChecked)
        outState.putBoolean(STATE_FILTERS_EDITED, filtersEdited)
        outState.putBoolean(STATE_EDITED, isEdited())
        if (reviewStateReady) {
            outState.putString(REVIEW_STATE, PendingScans.encodeReviewState(stagingDir, reviewState()))
            persistReviewState()
        }
        super.onSaveInstanceState(outState)
    }

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
                text = CategoryLabels.display(ctx, cat)
                setOnClickListener { pickCategory(cat) }
            }
            binding.categoryRail.addView(chip)
        }
        // The last chip must not be cut at the right edge of the rail.
        val end = (16 * resources.displayMetrics.density).toInt()
        binding.categoryRail.setPadding(
            binding.categoryRail.paddingLeft,
            binding.categoryRail.paddingTop,
            end,
            binding.categoryRail.paddingBottom
        )
        binding.titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                suggestCategoryFromTitle()
                scheduleReviewState()
            }
        })
        suggestCategoryFromTitle()
    }

    /** Manual pick wins: the name stops re-suggesting (T4.1). */
    private fun pickCategory(cat: String) {
        categoryPickedByHand = true
        chosenCategory = cat
        paintCategoryRail()
        scheduleReviewState()
    }

    /** Live suggestion from the title — until the owner picks by hand. */
    private fun suggestCategoryFromTitle() {
        if (categoryPickedByHand) return
        chosenCategory = CategoryRules.classify(currentTitle())
        paintCategoryRail()
    }

    /**
     * One checked chip at a time; the hint shows while nothing is selected.
     * The chosen chip is painted in its category colour with a tick, so the
     * choice is visible on screen (WP-05 gate caveat 1), not only in the saved badge.
     */
    private fun paintCategoryRail() {
        val rail = binding.categoryRail
        for (i in 0 until rail.childCount) {
            val chip = rail.getChildAt(i) as Chip
            val active = chip.tag == chosenCategory
            chip.isChecked = active
            paintCategoryChip(chip, chip.tag as String, active)
        }
        binding.categoryCaption.isVisible = chosenCategory == CategoryRules.UNSORTED
        if (::stagingDir.isInitialized) renderStatus()
    }

    private fun paintCategoryChip(chip: Chip, cat: String, active: Boolean) {
        val color = CategoryStyle.color(cat)
        val density = resources.displayMetrics.density
        chip.chipBackgroundColor = ColorStateList.valueOf(
            if (active) (color and 0x00FFFFFF) or (0x33 shl 24)
            else ContextCompat.getColor(this, R.color.rv_surface)
        )
        chip.chipStrokeColor = ColorStateList.valueOf(
            if (active) color else ContextCompat.getColor(this, R.color.rv_hairline)
        )
        chip.chipStrokeWidth = (if (active) 2f else 1f) * density
        chip.setTextColor(if (active) color else ContextCompat.getColor(this, R.color.rv_ink_2))
        chip.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        chip.isCheckedIconVisible = active
        if (active) {
            chip.setCheckedIconResource(R.drawable.ic_check)
            chip.checkedIconTint = ColorStateList.valueOf(color)
        }
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
        // Back from the viewer: Focus returns to the page that was on screen there.
        returnedPageName?.let { name ->
            pages.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { currentIndex = it }
        }
        returnedPageName = null
        currentIndex = currentIndex.coerceIn(0, pages.lastIndex)
        binding.pageGrid.submit(pages)
        updateSelectionUi()
        renderPages()
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

    private fun openPageViewer(index: Int, crop: Boolean = false) {
        if (refuseWhileBusy() || index !in pages.indices) return
        viewerLauncher.launch(
            if (crop) PageViewerActivity.stagingCropIntent(this, stagingDir.path, index)
            else PageViewerActivity.stagingIntent(this, stagingDir.path, index)
        )
    }

    /** Arrange: tap selects a page; tapping the selected page again opens it in Focus. */
    private fun onGridTap(index: Int) {
        if (index == currentIndex) setMode(arrange = false) else showPage(index)
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
        binding.arrangeTools.isVisible = selected.isEmpty()
        binding.selCount.text =
            resources.getQuantityString(R.plurals.selected_count, selected.size, selected.size)
    }

    /**
     * Persists a move (grip drag, Earlier / Later / Move to…, or its Undo): the new
     * order becomes the staging order through the same rename pass in every case.
     */
    internal fun reorderStaging(from: Int, to: Int, announce: Boolean = false) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        if (refuseWhileBusy()) {
            reloadFromDisk() // a drag already moved the cell on screen: put it back
            return
        }
        pageOpBusy = true
        val newCurrent = movedIndex(currentIndex, from, to)
        val countAtMove = pages.size
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
                pageOpBusy = false
                currentIndex = newCurrent
                reloadFromDisk()
                if (announce) {
                    haptic(confirmHaptic())
                    snack(getString(R.string.review_moved, to + 1)) {
                        if (pages.size == countAtMove) reorderStaging(to, from)
                    }
                }
            } catch (e: Exception) {
                pageOpBusy = false
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

    /** The ticked pages, or — with nothing ticked — the current page. */
    private fun actionTargets(): List<File> =
        if (selected.isNotEmpty()) pages.filter { it.path in selected }
        else listOfNotNull(pages.getOrNull(currentIndex))

    private fun rotateSelected(clockwise: Boolean) {
        rotatePages(actionTargets(), clockwise, announce = selected.isEmpty())
    }

    /** Rotates real page files in place (ImageTools.rotate90); Undo turns them back. */
    private fun rotatePages(targets: List<File>, clockwise: Boolean, announce: Boolean) {
        if (targets.isEmpty() || refuseWhileBusy()) return
        pageOpBusy = true
        val shownIndex = currentIndex
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { targets.forEach { ImageTools.rotate90(it, clockwise) } }
                targets.forEach { PageThumbLoader.invalidate(it) }
                structureChanged = true
                pageOpBusy = false
                reloadFromDisk()
                if (announce) {
                    haptic(confirmHaptic())
                    snack(getString(R.string.review_rotated, shownIndex + 1)) {
                        if (targets.all { it.exists() }) rotatePages(targets, !clockwise, announce = false)
                    }
                }
            } catch (e: Exception) {
                pageOpBusy = false
                Toast.makeText(
                    this@ScanReviewActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                reloadFromDisk()
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
        val victims = actionTargets()
        if (victims.isEmpty()) return
        if (victims.size >= pages.size) {
            confirmDiscard()
            return
        }
        if (refuseWhileBusy()) return
        // This snackbar replaces any previous one: the old Undo window is over.
        commitPendingDeletes(pendingDeletes.toList())
        val firstVictim = pages.indexOf(victims.first())
        pendingDeletes.addAll(victims.map { it.path })
        victims.forEach { it.renameTo(File(it.path + DEL_SUFFIX)) }
        selected.clear()
        // Stay at the same place: the next page slides into view.
        currentIndex = firstVictim.coerceIn(0, (pages.size - victims.size - 1).coerceAtLeast(0))
        reloadFromDisk()
        haptic(rejectHaptic())

        val batch = victims.map { it.path }
        styled(Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_LONG))
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
    // Review slice 1: Focus + Arrange
    // ------------------------------------------------------------------

    @android.annotation.SuppressLint("ClickableViewAccessibility") // taps re-dispatch performClick()
    /**
     * At 200% text the stacked icon+label tool buttons need ~74dp, which squeezes the page stage
     * and can clip the rail. Put the glyph beside the label instead: the rail scrolls
     * horizontally, every target stays >=48dp, and the labels stay on one line.
     */
    private fun fitLargeTextTools() {
        if (resources.configuration.fontScale < 1.8f) return
        val density = resources.displayMetrics.density
        val ids = listOf(
            R.id.btnToolCrop, R.id.btnToolRotate, R.id.btnToolFilter,
            R.id.btnToolAdd, R.id.btnToolDelete,
            R.id.btnArrEarlier, R.id.btnArrLater, R.id.btnArrMove,
            R.id.btnArrEdit, R.id.btnArrDelete
        )
        ids.forEach { id ->
            val button = findViewById<com.google.android.material.button.MaterialButton>(id)
                ?: return@forEach
            button.iconGravity =
                com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            button.iconPadding = (6 * density).toInt()
            button.minHeight = (48 * density).toInt()
        }
    }

    private fun setupFocus() {
        binding.filmstrip.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.filmstrip.adapter = filmAdapter
        binding.filmstrip.itemAnimator = null

        binding.btnPrevPage.setOnClickListener { showPage(currentIndex - 1) }
        binding.btnNextPage.setOnClickListener { showPage(currentIndex + 1) }
        binding.focusImage.setOnClickListener { openPageViewer(currentIndex) }
        val swipeMin = 48f * resources.displayMetrics.density
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean =
                binding.focusImage.performClick()
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) < swipeMin || abs(vx) < abs(vy)) return false
                showPage(if (dx < 0) currentIndex + 1 else currentIndex - 1)
                return true
            }
        })
        // Swipe = next/previous page, tap = open the zoomable viewer (also via TalkBack click).
        binding.focusImage.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        binding.btnToolCrop.setOnClickListener { openPageViewer(currentIndex, crop = true) }
        binding.btnToolRotate.setOnClickListener {
            rotatePages(listOfNotNull(pages.getOrNull(currentIndex)), clockwise = true, announce = true)
        }
        binding.btnToolFilter.setOnClickListener { chooseFilterForCurrent() }
        binding.btnToolAdd.setOnClickListener { showAddMenu(it) }
        binding.btnToolDelete.setOnClickListener {
            pages.getOrNull(currentIndex)?.let { deletePage(it) }
        }
        binding.orderLink.setOnClickListener { setMode(arrange = true) }
    }

    private fun setupArrange() {
        binding.orderText.text = SpannableStringBuilder().apply {
            append(getString(R.string.review_order_title), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(' ')
            append(getString(R.string.review_order_body))
        }
        binding.btnOrderOk.setOnClickListener {
            orderChecked = true
            haptic(confirmHaptic())
            renderPages()
        }
        binding.btnArrEarlier.setOnClickListener {
            reorderStaging(currentIndex, currentIndex - 1, announce = true)
        }
        binding.btnArrLater.setOnClickListener {
            reorderStaging(currentIndex, currentIndex + 1, announce = true)
        }
        binding.btnArrMove.setOnClickListener { showMoveTo() }
        binding.btnArrEdit.setOnClickListener { setMode(arrange = false) }
        binding.btnArrDelete.setOnClickListener {
            pages.getOrNull(currentIndex)?.let { deletePage(it) }
        }
    }

    /** Switches Focus <-> Arrange; the current page and the order carry across. */
    internal fun setMode(arrange: Boolean) {
        if (arrangeMode == arrange) return
        arrangeMode = arrange
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        applyMode(animate = true)
    }

    private fun applyMode(animate: Boolean) {
        applyingMode = true
        binding.modeToggle.check(if (arrangeMode) R.id.btnModeArrange else R.id.btnModeFocus)
        applyingMode = false
        val show = if (arrangeMode) binding.arrangePanel else binding.focusPanel
        val hide = if (arrangeMode) binding.focusPanel else binding.arrangePanel
        hide.animate().cancel()
        hide.isVisible = false
        hide.alpha = 1f
        show.isVisible = true
        binding.categoryBlock.isVisible = !arrangeMode
        if (animate) {
            show.alpha = 0f
            show.animate().alpha(1f).setDuration(180L).start()
        }
        if (arrangeMode) binding.pageGrid.scrollToPage(currentIndex)
        else if (pages.isNotEmpty()) renderCurrent()
        if (!arrangeMode) binding.focusPanel.post { ensureStageMinHeight() }
    }

    /**
     * F1-FIT: the Focus panel scrolls once the labels no longer fit (200 % text
     * on a narrow phone). A weight-1 stage then measures to zero, which would
     * hide the page completely, so it keeps a real preview height instead.
     */
    private fun ensureStageMinHeight() {
        val stage = binding.focusStage
        val min = (160 * resources.displayMetrics.density).toInt()
        if (stage.height < min && stage.layoutParams.height != min) {
            stage.layoutParams = stage.layoutParams.also { it.height = min }
        }
    }

    /** Makes [index] the current page (Focus page, filmstrip ring, Arrange ring). */
    internal fun showPage(index: Int) {
        if (index !in pages.indices || index == currentIndex) return
        val old = currentIndex
        currentIndex = index
        filmAdapter.notifyItemChanged(old)
        filmAdapter.notifyItemChanged(index)
        binding.pageGrid.refresh()
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        renderCurrent()
    }

    /** Everything that depends on the page list (after any reload). */
    private fun renderPages() {
        if (pages.isEmpty()) return
        currentIndex = currentIndex.coerceIn(0, pages.lastIndex)
        filmAdapter.notifyDataSetChanged()
        binding.btnSavePdf.text =
            resources.getQuantityString(R.plurals.review_save_pages, pages.size, pages.size)
        val needsOrderCheck = pages.size > 1 && !orderChecked
        binding.orderLink.isVisible = needsOrderCheck
        binding.orderCard.isVisible = needsOrderCheck
        // The card already says what to do; one instruction at a time keeps the grid tall.
        binding.arrangeHint.isVisible = !needsOrderCheck
        renderStatus()
        renderCurrent()
    }

    private fun renderCurrent() {
        val n = pages.size
        val i = currentIndex
        binding.pageStatus.text = getString(R.string.review_page_of, i + 1, n)
        binding.btnPrevPage.isVisible = n > 1
        binding.btnNextPage.isVisible = n > 1
        binding.btnPrevPage.isEnabled = i > 0
        binding.btnNextPage.isEnabled = i < n - 1
        binding.btnPrevPage.alpha = if (i > 0) 1f else 0.3f
        binding.btnNextPage.alpha = if (i < n - 1) 1f else 0.3f
        binding.btnArrEarlier.isEnabled = i > 0
        binding.btnArrLater.isEnabled = i < n - 1
        binding.btnArrMove.isEnabled = n > 1
        binding.focusImage.contentDescription = getString(R.string.review_preview_desc, i + 1, n)
        binding.filmstrip.scrollToPosition(i)
        if (arrangeMode) binding.pageGrid.scrollToPage(i)
        pages.getOrNull(i)?.let { loadFocusImage(it) }
    }

    /** "5 pages · Forms · Not saved yet" — the status always names the unsaved state. */
    private fun renderStatus() {
        val text = SpannableStringBuilder()
        text.append(resources.getQuantityString(R.plurals.review_page_count, pages.size, pages.size))
        if (chosenCategory != CategoryRules.UNSORTED) {
            text.append(" · ").append(CategoryLabels.display(this, chosenCategory))
        }
        text.append(" · ")
        text.append(
            getString(
                if (isEdited()) R.string.review_status_edited
                else R.string.review_status_unsaved
            ),
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.rv_warn)),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.reviewStatus.text = text
    }

    /**
     * Shows the current page large. A cached filmstrip thumb appears at once; one
     * display-size decode (never the raw file at full size) replaces it. A tag guard
     * drops late results when the owner has already moved on.
     */
    private fun loadFocusImage(file: File) {
        val filter = filters[file.path] ?: ScanFilter.ORIGINAL
        val key = "${file.path}|${file.lastModified()}|${file.length()}|${filter.id}"
        val view = binding.focusImage
        if (view.tag == key) return
        view.tag = key
        view.setImageBitmap(PageThumbLoader.cached(file, FILM_THUMB_PX, filter))
        binding.focusError.isVisible = false
        binding.focusLoading.isVisible = true
        val target = focusTargetPx()
        try {
            focusExecutor.execute {
                val shown: Bitmap? = try {
                    decodeFocus(file, target, filter)
                } catch (_: Throwable) {
                    null // unreadable or out of memory: say so, never crash Review
                }
                runOnUiThread {
                    if (isDestroyed || view.tag != key) return@runOnUiThread
                    binding.focusLoading.isVisible = false
                    if (shown != null) {
                        view.setImageBitmap(shown)
                    } else {
                        view.setImageDrawable(null)
                        binding.focusError.isVisible = true
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Screen is closing.
        }
    }

    private fun focusTargetPx(): Int {
        val v = binding.focusImage
        val longest = maxOf(v.width, v.height).takeIf { it > 0 }
            ?: maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 2
        return longest.coerceIn(800, 1600)
    }

    private fun decodeFocus(file: File, target: Int, filter: ScanFilter): Bitmap? {
        var bmp = ImageTools.decodeThumb(file, target) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > target * 5 / 4) {
            val k = target.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        if (filter == ScanFilter.ORIGINAL) return bmp
        val filtered = filter.applyTo(bmp)
        if (filtered !== bmp) bmp.recycle()
        return filtered
    }

    /** Filter sheet for the current page; the choice lives in filters.txt like the viewer's. */
    private fun chooseFilterForCurrent() {
        val file = pages.getOrNull(currentIndex) ?: return
        if (refuseWhileBusy()) return
        val all = ScanFilter.uiFilters
        val index = currentIndex
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.filter_title)
            .setOptions(
                all.map { getString(it.labelRes) },
                all.indexOf(filters[file.path] ?: ScanFilter.ORIGINAL)
            ) { which, _ ->
                val chosen = all[which]
                if (chosen == (filters[file.path] ?: ScanFilter.ORIGINAL) || !file.exists()) return@setOptions
                filters[file.path] = chosen
                filtersEdited = true
                writeFilters()
                binding.pageGrid.refresh()
                renderPages()
                snack(getString(R.string.review_filter_set, index + 1, getString(chosen.labelRes)), null)
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    /** Rewrites filters.txt; entries for pages hidden by a pending delete are kept for Undo. */
    private fun writeFilters() {
        val file = File(stagingDir, FILTERS_FILE)
        val merged = LinkedHashMap<String, String>()
        file.takeIf { it.exists() }?.readText()?.lineSequence()?.forEach { line ->
            val parts = line.split('=', limit = 2)
            if (parts.size == 2) merged[parts[0]] = parts[1]
        }
        pages.forEach { merged[it.name] = (filters[it.path] ?: ScanFilter.ORIGINAL).id }
        file.writeText(merged.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun showAddMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, R.id.action_add_pages, 0, R.string.add_pages)
            menu.add(0, R.id.action_add_gallery, 1, R.string.add_from_gallery)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_pages -> launchAddPages()
                    R.id.action_add_gallery -> galleryPagesLauncher.launch("image/*")
                }
                true
            }
        }.show()
    }

    /** Move to…: an explicit numbered position, the accessible alternative to dragging. */
    private fun showMoveTo() {
        if (pages.size < 2 || refuseWhileBusy()) return
        val from = currentIndex
        val labels = pages.indices.map { getString(R.string.review_move_position, it + 1) }
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(getString(R.string.review_move_title, from + 1))
            .setOptions(labels, from) { which, _ ->
                if (which != from) reorderStaging(from, which, announce = true)
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun deletePage(file: File) {
        selected.clear()
        selected.add(file.path)
        deleteSelected()
    }

    /** True (with a short note) while a rename/rotate is still landing. */
    private fun refuseWhileBusy(): Boolean {
        if (!pageOpBusy) return false
        Toast.makeText(this, R.string.review_busy, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun haptic(kind: Int) {
        binding.root.performHapticFeedback(kind)
    }

    private fun confirmHaptic(): Int =
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY

    private fun rejectHaptic(): Int =
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS

    /** Result snackbar with Undo when [undo] is given. */
    private fun snack(text: CharSequence, undo: (() -> Unit)?) {
        val bar = styled(Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG))
        if (undo != null) bar.setAction(R.string.undo) { undo() }
        bar.show()
    }

    /**
     * Floats above the visible tool row (never over Earlier / Later, so repeated
     * taps keep working) in the review's dark ink style.
     */
    private fun styled(bar: Snackbar): Snackbar {
        val anchor: View = when {
            !arrangeMode -> binding.focusTools.parent as View
            binding.selectBar.isVisible -> binding.selectBar
            else -> binding.arrangeTools.parent as View
        }
        return bar.setAnchorView(anchor)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.rv_surface_2))
            .setTextColor(ContextCompat.getColor(this, R.color.rv_ink))
            .setActionTextColor(ContextCompat.getColor(this, R.color.rv_accent))
    }

    private inner class FilmAdapter : RecyclerView.Adapter<FilmVH>() {
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FilmVH(ItemReviewFilmBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: FilmVH, position: Int) = holder.bind(position)
    }

    private inner class FilmVH(private val b: ItemReviewFilmBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(index: Int) {
            val file = pages[index]
            val current = index == currentIndex
            b.filmNum.text = String.format(Locale.ROOT, "%d", index + 1)
            b.filmNum.setBackgroundResource(if (current) R.drawable.bg_rv_num_current else R.drawable.bg_rv_num)
            b.filmNum.setTextColor(
                ContextCompat.getColor(this@ScanReviewActivity, if (current) R.color.rv_on_commit else R.color.rv_ink)
            )
            b.root.isSelected = current
            b.root.contentDescription = getString(
                if (current) R.string.review_film_page_current else R.string.review_film_page, index + 1
            )
            PageThumbLoader.load(file, FILM_THUMB_PX, filters[file.path] ?: ScanFilter.ORIGINAL, b.filmThumb)
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showPage(pos)
            }
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
            // M08: JPEG only — the PDF Google could also encode was never read.
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(ScanLauncher.CAMERA_PAGE_LIMIT)
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
                        getString(R.string.scanner_failed, getString(R.string.s1_reason_scanner_failed))
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.scanner_failed, getString(R.string.s1_reason_scanner_failed)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Copies [uris] into the staging folder with continuing page numbers. */
    internal fun appendStagedPages(uris: List<Uri>) {
        lifecycleScope.launch {
            var appended = false
            try {
                withContext(Dispatchers.IO) {
                    // M08: count pages hidden for Undo (.del) too, so an Undo can never land on an added page.
                    var next = PendingScans.lastPageNumber(stagingDir)
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
                    // Land on the first added page so the owner sees what came in.
                    currentIndex = pages.size
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
            reviewStateHandler.removeCallbacks(persistReviewStateTask)
            saving = true
            val state = reviewState()
            val progress = showProgress()
            lifecycleScope.launch {
                try {
                    val written = try {
                        withContext(Dispatchers.IO) {
                            PendingScans.queueReviewState(stagingDir, state).get()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (!written) {
                        // Name/category not committed (disk fault, or superseded): nothing is
                        // saved, staged pages are untouched, and the owner can retry.
                        saving = false
                        progress.dismiss()
                        Toast.makeText(this@ScanReviewActivity, R.string.review_state_failed,
                            Toast.LENGTH_LONG).show()
                        return@launch
                    }
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
                        state.title.trim().ifBlank { state.defaultName },
                        pageFilters = perPage,
                        category = state.category,
                        reviewDir = stagingDir,
                        ifSavedCopyDiffers = policy
                    )
                    saved = true

                    // T4.7 (N10): only place a copy in Documents/ScanWhirl when the
                    // owner opted in — no more silent copies on every save. Streamed from
                    // the saved PDF (never loaded whole); the original is only read.
                    // M08: shared with the direct save after Google's scanner.
                    val exported: String? = withContext(Dispatchers.IO) {
                        com.scanwhirl.app.store.AutoExport.afterSave(this@ScanReviewActivity, repo, doc)
                    }

                    // Verified committed document in hand: only now is the staging source released.
                    stagingDir.deleteRecursively()
                    progress.dismiss()
                    val message = if (exported != null) {
                        exported
                    } else {
                        getString(R.string.scan_saved)
                    }
                    Toast.makeText(this@ScanReviewActivity, message, Toast.LENGTH_LONG).show()
                    // M06: back on Home, the new document is scrolled into view.
                    com.scanwhirl.app.ui.home.HomeReveal.request(doc.id)
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
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.review_conflict_title)
            .setMessage(message)
            .setPrimary(R.string.review_conflict_save_new) { saveAndFinish(SavedCopyPolicy.SAVE_AS_NEW) }
            .setSecondary(R.string.review_conflict_open) {
                startActivity(DocumentViewerActivity.intent(this, conflict.documentId))
            }
            .build()
            .show()
    }

    private fun confirmDiscard() {
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.review_discard_title)
            .setMessage(R.string.review_discard_msg)
            .setPrimary(R.string.discard) { discard() }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun discard() {
        saved = true // stops any later cleanup concerns; staging is removed now
        // fixflow F22 fallout: a queued review-state write must not leave a file in the folder
        // and keep a discarded scan visible as "unfinished" (see PendingScans.discard).
        PendingScans.discard(stagingDir)
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

    private fun showProgress(): com.scanwhirl.app.ui.kit.SwProgress {
        return com.scanwhirl.app.ui.kit.SwProgress.show(this, getString(R.string.review_saving))
    }

    companion object {
        private const val EXTRA_STAGING = "staging_dir"
        private const val EXTRA_PDF = "pdf_path"
        private const val EXTRA_NAME = "default_name"
        private const val FILTERS_FILE = "filters.txt"
        private const val DEL_SUFFIX = ".del"
        private const val REVIEW_STATE = "review_state"
        private const val STATE_CURRENT = "review_current_page"
        private const val STATE_ARRANGE = "review_arrange_mode"
        private const val STATE_ORDER_OK = "review_order_checked"
        private const val STATE_FILTERS_EDITED = "review_filters_edited"
        private const val STATE_EDITED = "review_edited"

        /** Filmstrip thumb decode target (px); also the instant Focus placeholder. */
        private const val FILM_THUMB_PX = 200

        /** Where page [current] ends up after a move of [from] to [to]. */
        internal fun movedIndex(current: Int, from: Int, to: Int): Int = when {
            current == from -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }

        fun intent(context: Context, stagingDir: String, pdfPath: String?, defaultName: String): Intent =
            Intent(context, ScanReviewActivity::class.java)
                .putExtra(EXTRA_STAGING, stagingDir)
                .putExtra(EXTRA_PDF, pdfPath)
                .putExtra(EXTRA_NAME, defaultName)
    }
}
