package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
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
import com.scanwhirl.app.util.PageOrder
import com.scanwhirl.app.util.ScanFilter
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class PageViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityPageViewerBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }
    private var docId: String? = null

    /** Non-null = reviewing an unsaved scan: no repository, files live here. */
    private var stagingDir: File? = null

    /** Staging filters by absolute path (persisted to filters.txt for review). */
    private val stagingFilters = mutableMapOf<String, ScanFilter>()

    private var document: ScanDocument? = null
    private var pages: List<File> = emptyList()

    /** The page waiting for its Undo window to end, by path (staging) and stable identity (saved). */
    private data class PendingPage(val path: String, val identity: String)

    private var pendingDelete: PendingPage? = null
    private val committingDeletes = mutableSetOf<String>()
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * B6: one flight for every operation that mutates the draft and its display state. An edit
     * holds this lock across its IO work, so a filter-result write, a delete remap, a rotate and a
     * crop can never interleave. The gate is DOCUMENT-scoped (see [gateFor]): two Activity instances
     * for the same saved document — a recreation, or a delete still finishing on [deleteScope]
     * after onDestroy — share the same Mutex, so ordering survives the screen going away.
     * Initialised in [onCreate] once [docId] is known. State (filterChoice / pageIds / pages) is
     * only touched while holding it, on the main thread; the repo calls inside run on IO.
     */
    private lateinit var pageOps: Mutex

    /** Last known page (kept when the snap view isn't measurable yet). */
    private var currentPos: Int = 0

    /**
     * PVR: stable identity for each visible page, parallel to [pages]. A base page keeps the
     * identity of its source file across filter / rotate / crop renames; an inserted page keeps
     * its first replacement. Correlating by identity (never by a shifting index) is what lets a
     * late filter result be dropped when its page was deleted meanwhile.
     */
    private var pageIds: List<String> = emptyList()

    /**
     * PVR: filters the user picked for SAVED-document pages, keyed by stable page IDENTITY.
     * Persisted in instance state so a recreation does not forget the checked filter. On disk the
     * filter is already baked into the replacement file, so this is display state only.
     */
    private val filterChoice = mutableMapOf<String, ScanFilter>()

    /** Crop overlay state: the page being cropped + its display-size copy. */
    private var cropPath: String? = null
    private var cropIndex: Int = -1
    /** B6: immutable identity of the page being cropped, captured when the overlay opened. */
    private var cropIdentity: String? = null
    private var cropBitmap: Bitmap? = null

    private val fullExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "page-full").apply { isDaemon = true }
    }

    private val pagerAdapter = PagerAdapter()
    private lateinit var snapHelper: PagerSnapHelper

    /**
     * L2-03: the full-screen page is more than an unnamed image. Expose named
     * zoom actions that drive the existing double-tap gesture in
     * [ZoomableImageView] (no separate renderer, no engine change).
     */
    private val zoomAccessibility = object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    ACTION_ZOOM_PAGE, host.context.getString(R.string.ux130_l2_zoom_page)
                )
            )
            info.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    ACTION_CLOSE_ZOOM, host.context.getString(R.string.ux130_l2_close_zoom)
                )
            )
        }

        override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
            if (action == ACTION_ZOOM_PAGE || action == ACTION_CLOSE_ZOOM) {
                dispatchDoubleTap(host)
                return true
            }
            return super.performAccessibilityAction(host, action, arguments)
        }
    }

    /** Toggles zoom through the same double-tap the sighted path uses. */
    private fun dispatchDoubleTap(view: View) {
        val now = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0))
        view.onTouchEvent(MotionEvent.obtain(now + 20, now + 20, MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(MotionEvent.obtain(now + 20, now + 20, MotionEvent.ACTION_UP, x, y, 0))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()

        binding.pager.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.pager.adapter = pagerAdapter
        // F1-FIT: the dock floats over the pager, so the page must be able to sit
        // fully above it — measured, not a guessed constant, because the dock now
        // wraps to two rows at large text.
        binding.pageDock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reserveDockSpace()
        }
        binding.pageDock.post { reserveDockSpace() }
        snapHelper = PagerSnapHelper().also { it.attachToRecyclerView(binding.pager) }
        binding.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateIndicator()
            }
        })

        docId = intent.getStringExtra(EXTRA_DOC_ID)
        // B6: all edit paths below share this gate for the document, across Activity instances.
        pageOps = gateFor(docId)
        // SECURITY_JUDGE 1.3.0: FLAG_SECURE is per-window, so a page tap from a
        // protected document needs the same document policy resolved here before
        // any preview is drawn. No other page-viewer behaviour changes.
        com.scanwhirl.app.util.SecureView.applyForDocumentId(this, window, docId)
        intent.getStringExtra(EXTRA_STAGING)?.let { stagingDir = File(it) }
        savedInstanceState?.let(::restoreFilterChoices)
        if (!reload()) return

        val start = intent.getIntExtra(EXTRA_PAGE, 0)
            .coerceIn(0, (pages.lastIndex).coerceAtLeast(0))
        currentPos = start
        binding.pager.scrollToPosition(start)
        updateIndicator()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnRotateRight.setOnClickListener { rotateCurrent(clockwise = true) }
        binding.btnCrop.setOnClickListener { startCrop() }
        binding.btnFilter.setOnClickListener { filterCurrent() }
        binding.btnDelete.setOnClickListener { deleteCurrent() }
        binding.btnCancelCrop.setOnClickListener { closeCrop() }
        binding.btnApplyCrop.setOnClickListener { applyCrop() }
        setupCropPresets()

        // Back cancels an open crop first; otherwise it leaves the view.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.cropOverlay.visibility == android.view.View.VISIBLE) closeCrop()
                else finish()
            }
        })

        // Review slice 1: Review's labelled "Crop" lands straight in the existing crop
        // overlay (same CropView + ImageTools.cropTo; nothing is duplicated).
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_CROP, false)) {
            binding.pager.post { if (!isFinishing) startCrop() }
        }
    }

    /**
     * Review slice 1: tell the caller which page was on screen, so Review's Focus
     * comes back to the page the owner was looking at. Callers that ignore results
     * (the saved-document grid) are unaffected.
     */
    override fun finish() {
        if (::binding.isInitialized && ::snapHelper.isInitialized) {
            val snapped = currentPosition()
            if (snapped in pages.indices) currentPos = snapped
            pages.getOrNull(currentPos)?.let { shown ->
                setResult(
                    RESULT_OK,
                    Intent().putExtra(RESULT_PAGE_NAME, shown.name).putExtra(EXTRA_PAGE, currentPos)
                )
            }
        }
        super.finish()
    }

    override fun onDestroy() {
        // Leaving the screen makes any pending delete permanent. A rotation / dark-mode / font
        // change only recreates the screen: hunt H5-5 - committing then deleted the page for good
        // inside its Undo window. The page simply stays (same guard as DocumentViewerActivity).
        if (isChangingConfigurations) restorePendingDelete() else commitPendingDelete()
        // Stop full-page decodes: a thread that outlives the screen would decode into a dead
        // context (and, in tests, into a closed Robolectric class loader).
        fullExecutor.shutdownNow()
        super.onDestroy()
    }

    /** PVR: restores the saved-document filter choices across a recreation. */
    private fun restoreFilterChoices(saved: Bundle) {
        val ids = saved.getStringArray(KEY_FILTER_KEYS) ?: return
        val names = saved.getStringArray(KEY_FILTER_VALUES) ?: return
        filterChoice.clear()
        ids.zip(names).forEach { (id, name) ->
            runCatching { ScanFilter.valueOf(name) }.getOrNull()?.let { filterChoice[id] = it }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(KEY_FILTER_KEYS, filterChoice.keys.toTypedArray())
        outState.putStringArray(KEY_FILTER_VALUES, filterChoice.values.map { it.name }.toTypedArray())
    }

    /**
     * PVR: choices are keyed by identity, so removing a page needs no index shifting at all —
     * drop that one identity's choice and every surviving page keeps exactly what the person chose.
     */
    private fun dropFilterChoice(identity: String) {
        filterChoice.remove(identity)
    }

    /** Re-reads the current page list (minus pending deletes) into the pager. */
    private fun reload(): Boolean {
        val dir = stagingDir
        return if (dir != null) reloadStaging(dir) else reloadSaved()
    }

    /**
     * F1-FIT: keeps the whole page above the floating dock. The dock wraps to a
     * second row at large text, so its height is measured here instead of being
     * guessed — a fixed padding would hide the bottom of the page behind it.
     */
    private fun reserveDockSpace() {
        val dock = binding.pageDock
        if (dock.height <= 0) return
        val gap = (12 * resources.displayMetrics.density).toInt()
        val bottom = dock.height + gap
        if (binding.pager.paddingBottom != bottom) {
            binding.pager.setPadding(
                binding.pager.paddingLeft,
                binding.pager.paddingTop,
                binding.pager.paddingRight,
                bottom
            )
        }
    }

    private fun reloadSaved(): Boolean {
        val id = docId ?: run { finish(); return false }
        val doc = repo.getDocument(id)
        if (doc == null) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        document = doc
        // PM-2.3b: the pager shows the draft's pages (base pages where nothing was edited).
        // PVR: keep the stable identity of each visible page in lockstep with [pages].
        val allPages = kotlinx.coroutines.runBlocking { repo.draftPages(id) }
        val allIds = identitiesFor(allPages)
        val visible = allPages.indices.filter { i ->
            allPages[i].path != pendingDelete?.path && allPages[i].path !in committingDeletes
        }
        pages = visible.map { allPages[it] }
        pageIds = visible.map { allIds[it] }
        pagerAdapter.filterFor = { file -> previewFilterFor(file) }
        pagerAdapter.submit(pages)
        return true
    }

    /**
     * PVR: a stable identity per draft page, in the same order as [allPages]. A base page (with
     * or without a draft yet) uses its source name, which no edit ever changes; an inserted page
     * uses its first replacement. Never a shifting index or the current file name.
     */
    private fun identitiesFor(allPages: List<File>): List<String> {
        val id = docId ?: return allPages.map { it.path }
        val entries = kotlinx.coroutines.runBlocking { repo.draftOf(id) }?.entries
        val baseNames = document?.pageFiles?.map { it.name }.orEmpty()
        return allPages.indices.map { i ->
            val e = entries?.getOrNull(i)
            e?.source?.let { "src:$it" }
                ?: e?.replacementOrig?.let { "r:$it" }
                ?: e?.replacement?.let { "r:$it" }
                ?: baseNames.getOrNull(i)?.let { "src:$it" }
                ?: allPages[i].path
        }
    }

    /**
     * E1: the filter a page's preview should apply on top of the file. For a saved document the
     * filter is already baked into the replacement jpg by ScanRepository.draftFilter, so the
     * preview shows the file exactly as saved — applying the choice again double-processes it.
     * Staging filters are virtual (filters.txt), so those still tint the preview here.
     */
    internal fun previewFilterFor(file: File): ScanFilter =
        if (stagingDir != null) stagingFilters[file.path] ?: ScanFilter.ORIGINAL
        else ScanFilter.ORIGINAL

    /** Selected filter for a saved-document page, for the sheet's checked row. */
    internal fun selectedFilter(index: Int): ScanFilter =
        pageIds.getOrNull(index)?.let { filterChoice[it] } ?: ScanFilter.ORIGINAL

    private fun reloadStaging(dir: File): Boolean {
        if (!dir.exists()) {
            // The review screen discarded the scan while we were open.
            finish()
            return false
        }
        // E1: a page hidden as `page_XXX.jpg.del` while its Undo snackbar was up is silently
        // lost if the process died before Undo or commit — neither onDestroy path ran and the
        // plain *.jpg listing below would skip it. On a fresh load there is no live Undo
        // window, so put every orphan back (except a page still pending in THIS screen).
        restoreOrphanedStagingDeletes(dir)
        pages = dir.listFiles { f -> f.extension.equals("jpg", true) && f.length() > 0 }
            ?.sortedWith(PageOrder.comparator)
            .orEmpty()
        if (pages.isEmpty()) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        loadStagingFilters(dir)
        pagerAdapter.filterFor = { file -> previewFilterFor(file) }
        pagerAdapter.submit(pages)
        return true
    }

    /**
     * E1: brings back `.del` pages left behind by a process death. [pendingDelete] is the one
     * page whose Undo window is still live in this screen — it must stay hidden until the
     * snackbar acts or this screen closes. Every other orphan has no owner, so rename it back.
     */
    private fun restoreOrphanedStagingDeletes(dir: File) {
        dir.listFiles { f -> f.name.endsWith(DEL_SUFFIX) && f.length() > 0 }
            ?.forEach { hidden ->
                val originalPath = hidden.path.removeSuffix(DEL_SUFFIX)
                val tombstone = File(originalPath + TOMBSTONE_SUFFIX)
                if (tombstone.exists()) {
                    // C1: a commit that failed to delete left a tombstone. This page was
                    // deliberately removed, not orphaned by a process death: keep it deleted and
                    // clean up both markers now that no Undo window owns them.
                    hidden.delete()
                    tombstone.delete()
                    return@forEach
                }
                if (originalPath != pendingDelete?.path) {
                    val original = File(originalPath)
                    if (!original.exists()) hidden.renameTo(original)
                }
            }
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
        pages.forEach { stagingFilters[it.path] = fromFile[it.name] ?: ScanFilter.ORIGINAL }
    }

    /** Writes the whole staging filter map so the review screen sees the choice. */
    private fun writeStagingFilters(dir: File) {
        File(dir, FILTERS_FILE).writeText(
            pages.joinToString("\n") {
                "${it.name}=${stagingFilters[it.path]?.id ?: ScanFilter.ORIGINAL.id}"
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

    private fun rotateCurrent(clockwise: Boolean) {
        val pos = currentPosition()
        val file = pages.getOrNull(pos) ?: return
        // B6: capture an IMMUTABLE identity at tap time. The queued edit resolves this against
        // the live draft under pageOps, after all earlier work including any pending delete —
        // it must never reuse this tap position, which can point at a neighbour by commit time.
        val identity = pageIds.getOrNull(pos) ?: file.path
        val dir = stagingDir
        lifecycleScope.launch {
            pageOps.withLock {
                try {
                    val liveIndex: Int
                    if (dir != null) {
                        liveIndex = pages.indexOfFirst { it.path == identity }
                        if (liveIndex < 0) { showPageSkipped(); return@withLock }
                        withContext(Dispatchers.IO) { ImageTools.rotate90(pages[liveIndex], clockwise) }
                    } else {
                        val id = docId ?: return@withLock
                        liveIndex = resolveIdentityAfterDeleteLocked(id, identity)
                        if (liveIndex < 0) { showPageSkipped(); return@withLock }
                        repo.draftRotate(id, liveIndex, clockwise)
                    }
                    reload()
                    currentPos = liveIndex.coerceAtMost(pages.lastIndex)
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
    }

    private fun filterCurrent() {
        val pos = currentPosition()
        val file = pages.getOrNull(pos) ?: return
        val filters = ScanFilter.uiFilters
        val dir = stagingDir
        if (dir != null) {
            // Staging: the choice is virtual, persisted for the review screen.
            val checked = filters.indexOf(stagingFilters[file.path] ?: ScanFilter.ORIGINAL)
            com.scanwhirl.app.ui.kit.SwSheet.builder(this)
                .setTitle(R.string.filter_title)
                .setOptions(filters.map { getString(it.labelRes) }, checked) { which, _ ->
                    stagingFilters[file.path] = filters[which]
                    writeStagingFilters(dir)
                    reload()
                    currentPos = pos.coerceAtMost(pages.lastIndex)
                    binding.pager.scrollToPosition(currentPos)
                    updateIndicator()
                }
                .setSecondary(android.R.string.cancel)
                .build()
                .show()
            return
        }
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                // Land any pending delete first, then read the page position again: the
                // committed delete shifts every later index down by one.
                withContext(Dispatchers.IO) { commitPendingDeleteNow() }
                val livePos = currentPosition().takeIf { it in pages.indices } ?: currentPos
                if (livePos !in pages.indices) return@launch
                // PVR: remember WHICH page the person chose for, not just its position. If a
                // delete remaps the order before the filter lands, the result is dropped instead
                // of landing on the neighbour that shifted into this slot.
                val targetIdentity = pageIds[livePos]
                val checked = filters.indexOf(selectedFilter(livePos))
                com.scanwhirl.app.ui.kit.SwSheet.builder(this@PageViewerActivity)
                    .setTitle(R.string.filter_title)
                    .setOptions(filters.map { getString(it.labelRes) }, checked) { which, _ ->
                        applyFilter(id, targetIdentity, filters[which])
                    }
                    .setSecondary(android.R.string.cancel)
                    .build()
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

    /**
     * PVR: apply a filter to the page with [identity]. The whole read-modify-write of the draft
     * and its display state runs as one flight under [pageOps], so a delete cannot remap between
     * the repo write and the choice write. If the page was deleted before this runs, the result
     * is dropped: neither the choice nor a replacement file is written for the neighbour.
     */
    internal fun applyFilter(id: String, identity: String, filter: ScanFilter) {
        lifecycleScope.launch {
            pageOps.withLock {
                try {
                    val liveIndex = pageIds.indexOf(identity)
                    if (liveIndex < 0) return@withLock // deleted meanwhile: drop the stale result
                    repo.draftFilter(id, liveIndex, filter)
                    // Still under the lock: no delete can have shifted the page in between.
                    filterChoice[identity] = filter
                    reload()
                    currentPos = liveIndex.coerceAtMost(pages.lastIndex)
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
        pendingDelete = PendingPage(file.path, pageIds.getOrNull(pos) ?: file.path)
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
                    if (event != DISMISS_EVENT_ACTION && pendingDelete?.path == path) {
                        commitPendingDelete()
                    }
                }
            })
            .show()
    }

    /** Last page of an unsaved scan: deleting it means discarding the scan. */
    private fun confirmStagingDiscard() {
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.review_discard_title)
            .setMessage(R.string.review_discard_msg)
            .setPrimary(R.string.discard) {
                stagingDir?.deleteRecursively()
                finish()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    // ------------------------------------------------------------------
    // Crop (manual corner drag; no auto-detection)
    // ------------------------------------------------------------------

    private fun startCrop() {
        // Before the first snap settles there is no snap view yet: use the start page.
        val pos = currentPosition().takeIf { it in pages.indices } ?: currentPos
        val file = pages.getOrNull(pos) ?: return
        // B6: crop is index-based too. Capture an immutable identity now; the overlay and the
        // later Apply must use the index this page currently occupies, not the captured position.
        val identity = pageIds.getOrNull(pos) ?: file.path
        if (binding.cropOverlay.visibility == android.view.View.VISIBLE) return
        lifecycleScope.launch {
            val livePos: Int
            try {
                livePos = if (stagingDir == null) {
                    val id = docId ?: return@launch
                    // One serialized edit: land any pending delete, then re-resolve the identity.
                    pageOps.withLock { resolveIdentityAfterDeleteLocked(id, identity) }
                } else {
                    pages.indexOfFirst { it.path == identity }
                }
            } catch (_: Exception) {
                showPageSkipped()
                return@launch
            }
            if (livePos !in pages.indices) { showPageSkipped(); return@launch }
            showCropOverlay(livePos, pages[livePos], identity)
        }
    }

    private fun showCropOverlay(pos: Int, file: File, identity: String) {
        cropIndex = pos
        cropPath = file.path
        cropIdentity = identity
        // Owner QA: only Cancel / Apply belong on screen while cropping; the page toolbar
        // underneath must not keep visible or clickable areas.
        binding.pageDock.isVisible = false
        binding.btnClose.isVisible = false
        binding.pageIndicator.isVisible = false
        binding.cropView.clearImage()
        binding.cropOverlay.visibility = View.VISIBLE
        // WP-22-7: ID/passport crop presets are free.
        binding.cropPresets.isVisible = true
        binding.chipCropFree.isChecked = true

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
                    cropBitmap?.takeIf { !it.isRecycled }?.recycle()
                    cropBitmap = bmp
                    binding.cropView.setImage(bmp)
                } else {
                    bmp.recycle()
                }
            }
        }
    }

    /** Wire the Pro photo-size chips (Free / Passport / ID card / Visa). */
    private fun setupCropPresets() {
        val ratios = listOf(
            binding.chipCropFree to null,
            binding.chipCropPassport to 35f / 45f,
            binding.chipCropIdCard to 85.6f / 54f,
            binding.chipCropVisa to 5f / 7f
        )
        val group = binding.chipCropGroup
        group.setOnCheckedStateChangeListener { _, checked ->
            val chip = group.findViewById<com.google.android.material.chip.Chip>(
                checked.firstOrNull() ?: View.NO_ID
            )
            binding.cropView.aspectRatio = ratios.firstOrNull { it.first == chip }?.second
        }
    }

    private fun closeCrop() {
        binding.cropOverlay.visibility = android.view.View.GONE
        // Restore the page toolbar the crop mode hid.
        binding.pageDock.isVisible = true
        binding.btnClose.isVisible = true
        binding.pageIndicator.isVisible = true
        binding.cropView.tag = null // drop any in-flight decode result
        binding.cropView.clearImage() // drop the view's reference BEFORE recycling
        cropBitmap?.takeIf { !it.isRecycled }?.recycle()
        cropBitmap = null
        cropPath = null
        cropIdentity = null
    }

    private fun applyCrop() {
        val path = cropPath ?: return closeCrop()
        // B6: keep the tap-time identity before closeCrop() clears it; Apply re-resolves it
        // under pageOps in case a delete/reorder landed while the overlay was open.
        val identity = cropIdentity ?: path
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
        val dir = stagingDir
        closeCrop()

        lifecycleScope.launch {
            pageOps.withLock {
                try {
                    val liveIndex: Int
                    if (dir != null) {
                        liveIndex = pages.indexOfFirst { it.path == identity }
                        if (liveIndex < 0) { showPageSkipped(); return@withLock }
                        withContext(Dispatchers.IO) { ImageTools.cropTo(pages[liveIndex], fileRect) }
                    } else {
                        val id = docId ?: return@withLock
                        liveIndex = resolveIdentityAfterDeleteLocked(id, identity)
                        if (liveIndex < 0) { showPageSkipped(); return@withLock }
                        repo.draftCrop(id, liveIndex, fileRect)
                    }
                    reload()
                    currentPos = liveIndex.coerceAtMost(pages.lastIndex)
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
    }

    /**
     * Hunt H5-5: a configuration change ends this screen inside the Undo window, and the
     * recreated screen cannot offer that Undo any more. The pending page is put back exactly as
     * Undo would (an unsaved scan's hidden `.del` file is renamed back), so nothing is lost.
     */
    private fun restorePendingDelete() {
        val path = pendingDelete?.path ?: return
        pendingDelete = null
        if (stagingDir != null) {
            File(path + DEL_SUFFIX).takeIf { it.exists() }?.renameTo(File(path))
        }
    }

    /** Deletes the Undo-pending page for good. No-op when nothing is pending. */
    internal fun commitPendingDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        if (stagingDir != null) {
            // Hidden since the rename; only now does the file really go.
            val hidden = File(pending.path + DEL_SUFFIX)
            if (hidden.exists() && !stagingHiddenDelete(hidden)) {
                // C1: the file could not be deleted. Without a tombstone the next load's orphan
                // restore would rename it back and resurrect a deliberately deleted page.
                runCatching { File(pending.path + TOMBSTONE_SUFFIX).writeText("1") }
            }
            return
        }
        val id = docId ?: return
        committingDeletes.add(pending.path)
        deleteScope.launch {
            val deleted = pageOps.withLock { commitPendingDeleteLocked(id, pending) }
            committingDeletes.remove(pending.path)
            runOnUiThread {
                if (deleted) dropFilterChoice(pending.identity)
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
    private suspend fun commitPendingDeleteNow() = pageOps.withLock {
        commitPendingDeleteNowLocked()
    }

    /**
     * [pageOps] is already held by the caller. Lands the pending delete and clears the choice of
     * the page it removed, before the caller re-reads page indices.
     */
    private suspend fun commitPendingDeleteNowLocked() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        val id = docId ?: return
        if (commitPendingDeleteLocked(id, pending)) {
            withContext(Dispatchers.Main) { dropFilterChoice(pending.identity) }
        }
    }

    /**
     * B6: resolve a tap-time identity to the index it occupies RIGHT NOW. Caller holds [pageOps].
     * Land every queued delete first, re-read the draft, then look the identity up — a UI position
     * captured before this edit was queued must never reach draftRotate / draftCrop. -1 means the
     * page is gone: the caller abandons the edit and changes nothing.
     */
    private suspend fun resolveIdentityAfterDeleteLocked(id: String, identity: String): Int {
        commitPendingDeleteNowLocked()
        if (!reload()) return -1
        return pageIds.indexOf(identity)
    }

    /** B6: a queued edit targeted a page that is no longer here; say so briefly. */
    private fun showPageSkipped() {
        Toast.makeText(this, R.string.page_edit_skipped, Toast.LENGTH_SHORT).show()
    }

    /**
     * [pageOps] is already held. Deletes the pending page resolved by stable IDENTITY, so an
     * in-flight edit that renamed its file cannot send the delete to the wrong page (or lose it).
     */
    private suspend fun commitPendingDeleteLocked(id: String, pending: PendingPage): Boolean = try {
        val idxInView = pageIds.indexOf(pending.identity)
        if (idxInView in pageIds.indices && pageIds.size > 1) {
            repo.draftDelete(id, idxInView)
            true
        } else {
            // Defensive: state changed before this flight began; resolve against a fresh draft.
            val fresh = repo.draftPages(id)
            val idx = identitiesFor(fresh).indexOf(pending.identity)
            if (idx in fresh.indices && fresh.size > 1) {
                repo.draftDelete(id, idx)
                true
            } else false
        }
    } catch (_: Exception) {
        // Best effort; a failed delete leaves the page in place.
        false
    }

    private inner class PagerAdapter : RecyclerView.Adapter<PagerVH>() {
        var items: List<File> = emptyList()
        var filterFor: (File) -> ScanFilter = { ScanFilter.ORIGINAL }

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
            holder.bind(items[position], position, items.size)
        }

        override fun getItemCount() = items.size
    }

    private inner class PagerVH(private val binding: ItemPagerPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /**
         * Decodes a display-size copy (max 2048px, never the raw multi-MB
         * file) on a background thread; the tag guard drops late results if
         * this cell was rebound to another page first.
         *
         * The chosen [ScanFilter] is applied to the preview exactly like the
         * grid thumbnails are, so picking a filter changes the page on
         * screen immediately — in staging where the filter is virtual and
         * in saved documents alike. The key includes the filter so a filter
         * change re-renders even though the file itself did not change.
         */
        fun bind(file: File, position: Int, total: Int) {
            binding.pageImage.contentDescription = binding.root.context.getString(
                R.string.ux130_l2_preview_page, position + 1, total
            )
            ViewCompat.setAccessibilityDelegate(binding.pageImage, zoomAccessibility)
            val filter = pagerAdapter.filterFor(file)
            val key = "${file.path}|${file.lastModified()}|${file.length()}|${filter.id}"
            binding.pageImage.tag = key
            fullExecutor.execute {
                val bmp = try {
                    ImageTools.decodeSampled(file.readBytes(), 2048)
                } catch (_: Exception) {
                    null
                } ?: return@execute
                val shown: Bitmap = if (filter == ScanFilter.ORIGINAL) bmp else try {
                    filter.applyTo(bmp).also { applied -> if (applied !== bmp) bmp.recycle() }
                } catch (_: Exception) {
                    bmp
                }
                runOnUiThread {
                    if (binding.pageImage.tag == key && !shown.isRecycled) {
                        binding.pageImage.setImageBitmap(shown)
                    } else {
                        if (!shown.isRecycled) shown.recycle()
                    }
                }
            }
        }
    }

    companion object {
        /**
         * B6: one serial gate per saved document, shared by every Activity instance for that
         * document — a recreation, or a delete still finishing on a previous screen's
         * [deleteScope], cannot reorder this screen's queued edits. Staging reviews have no
         * document id and keep their own per-screen gate.
         */
        private val documentGates = mutableMapOf<String, Mutex>()

        @Synchronized
        private fun gateFor(docId: String?): Mutex =
            docId?.let { documentGates.getOrPut(it) { Mutex() } } ?: Mutex()

        /** Custom accessibility actions for the full-screen page preview. */
        private const val ACTION_ZOOM_PAGE = 0x01000021
        private const val ACTION_CLOSE_ZOOM = 0x01000022

        private const val EXTRA_DOC_ID = "doc_id"
        private const val EXTRA_PAGE = "page_index"
        private const val EXTRA_START_CROP = "start_crop"

        /** Result extra: file name of the page on screen when the viewer closed. */
        const val RESULT_PAGE_NAME = "result_page_name"
        private const val EXTRA_STAGING = "staging_dir"
        private const val FILTERS_FILE = "filters.txt"
        private const val DEL_SUFFIX = ".del"
        /** C1: a hidden page that failed to delete is marked, so orphan restore leaves it gone. */
        private const val TOMBSTONE_SUFFIX = ".gone"

        /** C1 seam: the staged page's real delete, overridable by tests that force a failure. */
        @androidx.annotation.VisibleForTesting
        internal var stagingHiddenDelete: (File) -> Boolean = { it.delete() }

        private const val KEY_FILTER_KEYS = "pvr_filter_keys"
        private const val KEY_FILTER_VALUES = "pvr_filter_values"

        fun intent(context: Context, docId: String, pageIndex: Int): Intent =
            Intent(context, PageViewerActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_PAGE, pageIndex)

        /** Full-screen view over an unsaved scan (the review screen's staging folder). */
        fun stagingIntent(context: Context, stagingDir: String, pageIndex: Int): Intent =
            Intent(context, PageViewerActivity::class.java)
                .putExtra(EXTRA_STAGING, stagingDir)
                .putExtra(EXTRA_PAGE, pageIndex)

        /** As [stagingIntent], opening directly in the crop overlay for that page. */
        fun stagingCropIntent(context: Context, stagingDir: String, pageIndex: Int): Intent =
            stagingIntent(context, stagingDir, pageIndex).putExtra(EXTRA_START_CROP, true)
    }
}
