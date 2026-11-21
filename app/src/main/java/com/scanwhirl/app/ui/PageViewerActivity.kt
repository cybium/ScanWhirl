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

    private var pendingDelete: String? = null
    private val committingDeletes = mutableSetOf<String>()
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Last known page (kept when the snap view isn't measurable yet). */
    private var currentPos: Int = 0

    /**
     * E1: filters the user picked for SAVED-document pages, keyed by page INDEX (not path),
     * because applying a filter or rotating a page gives it a new `r_<n>.jpg` name. Persisted
     * in instance state so a recreation does not forget the checked filter. On disk the filter
     * is already baked into the replacement file, so this is display state only.
     */
    private val filterChoice = mutableMapOf<Int, ScanFilter>()

    /** Crop overlay state: the page being cropped + its display-size copy. */
    private var cropPath: String? = null
    private var cropIndex: Int = -1
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

    /** E1: restores the saved-document filter choices across a recreation. */
    private fun restoreFilterChoices(saved: Bundle) {
        val indices = saved.getIntArray(KEY_FILTER_INDICES) ?: return
        val names = saved.getStringArray(KEY_FILTER_IDS) ?: return
        filterChoice.clear()
        indices.zip(names).forEach { (index, name) ->
            runCatching { ScanFilter.valueOf(name) }.getOrNull()?.let { filterChoice[index] = it }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(KEY_FILTER_INDICES, filterChoice.keys.toIntArray())
        outState.putStringArray(KEY_FILTER_IDS, filterChoice.values.map { it.name }.toTypedArray())
    }

    /**
     * C1: [filterChoice] is keyed by page index. Once a page is really gone, the choice for its
     * index is dropped and every later page shifts down by one; otherwise a neighbour inherits a
     * filter the person never chose for it.
     */
    private fun remapFilterChoiceAfterDelete(deletedIndex: Int) {
        val shifted = filterChoice.mapNotNull { (index, filter) ->
            when {
                index == deletedIndex -> null
                index > deletedIndex -> (index - 1) to filter
                else -> index to filter
            }
        }.toMap()
        filterChoice.clear()
        filterChoice.putAll(shifted)
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
        pages = kotlinx.coroutines.runBlocking { repo.draftPages(id) }.filter {
            it.path != pendingDelete && it.path !in committingDeletes
        }
        pagerAdapter.filterFor = { file -> previewFilterFor(file) }
        pagerAdapter.submit(pages)
        return true
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
        filterChoice[index] ?: ScanFilter.ORIGINAL

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
            ?.sortedBy { it.nameWithoutExtension }
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
                if (originalPath != pendingDelete) {
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
        val dir = stagingDir
        lifecycleScope.launch {
            try {
                if (dir != null) {
                    withContext(Dispatchers.IO) { ImageTools.rotate90(file, clockwise) }
                } else {
                    val id = docId ?: return@launch
                    withContext(Dispatchers.IO) { commitPendingDeleteNow() }
                    repo.draftRotate(id, pos, clockwise)
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
                val checked = filters.indexOf(selectedFilter(livePos))
                com.scanwhirl.app.ui.kit.SwSheet.builder(this@PageViewerActivity)
                    .setTitle(R.string.filter_title)
                    .setOptions(filters.map { getString(it.labelRes) }, checked) { which, _ ->
                        applyFilter(id, livePos, filters[which])
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

    private fun applyFilter(id: String, pos: Int, filter: ScanFilter) {
        lifecycleScope.launch {
            try {
                repo.draftFilter(id, pos, filter)
                // Keep the choice by page index: the replacement file gets a new name, so a
                // path-keyed map would make the sheet show Original after the next rotation.
                filterChoice[pos] = filter
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
                    repo.draftCrop(id, pos, fileRect)
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

    /**
     * Hunt H5-5: a configuration change ends this screen inside the Undo window, and the
     * recreated screen cannot offer that Undo any more. The pending page is put back exactly as
     * Undo would (an unsaved scan's hidden `.del` file is renamed back), so nothing is lost.
     */
    private fun restorePendingDelete() {
        val path = pendingDelete ?: return
        pendingDelete = null
        if (stagingDir != null) {
            File(path + DEL_SUFFIX).takeIf { it.exists() }?.renameTo(File(path))
        }
    }

    /** Deletes the Undo-pending page for good. No-op when nothing is pending. */
    internal fun commitPendingDelete() {
        val path = pendingDelete ?: return
        pendingDelete = null
        if (stagingDir != null) {
            // Hidden since the rename; only now does the file really go.
            val hidden = File(path + DEL_SUFFIX)
            if (hidden.exists() && !stagingHiddenDelete(hidden)) {
                // C1: the file could not be deleted. Without a tombstone the next load's orphan
                // restore would rename it back and resurrect a deliberately deleted page.
                runCatching { File(path + TOMBSTONE_SUFFIX).writeText("1") }
            }
            return
        }
        val id = docId ?: return
        committingDeletes.add(path)
        deleteScope.launch {
            var deletedIndex = -1
            try {
                val now = repo.draftPages(id)
                val idx = now.indexOfFirst { it.path == path }
                if (idx >= 0 && now.size > 1) {
                    repo.draftDelete(id, idx)
                    deletedIndex = idx
                }
            } catch (_: Exception) {
                // Best effort; a failed delete leaves the page in place.
            }
            committingDeletes.remove(path)
            runOnUiThread {
                if (deletedIndex >= 0) remapFilterChoiceAfterDelete(deletedIndex)
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
            val now = repo.draftPages(id)
            val idx = now.indexOfFirst { it.path == path }
            if (idx >= 0 && now.size > 1) {
                repo.draftDelete(id, idx)
                withContext(Dispatchers.Main) { remapFilterChoiceAfterDelete(idx) }
            }
        } catch (_: Exception) {
            // Best effort.
        }
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

        private const val KEY_FILTER_INDICES = "e1_filter_indices"
        private const val KEY_FILTER_IDS = "e1_filter_ids"

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
