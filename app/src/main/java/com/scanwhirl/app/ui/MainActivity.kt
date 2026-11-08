package com.scanwhirl.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.animation.ValueAnimator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityMainBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.tools.FlowUtil
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.tools.LongImageFlow
import com.scanwhirl.app.tools.MergeFlow
import com.scanwhirl.app.tools.OcrFlow
import com.scanwhirl.app.tools.ResizeFlow
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.tools.VaultFlow
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.HomeFilter
import com.scanwhirl.app.util.HomeState
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.QuickSortQueue
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.StatusLine
import com.scanwhirl.app.util.SwMotion
import com.scanwhirl.app.util.ZipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity(), HomeHost {

    private lateinit var binding: ActivityMainBinding
    private val adapter by lazy {
        ScanListAdapter(::onDocClick, ::onDocMore, ::onDocLongClick, ::onCategory)
    }
    private val repo by lazy { (application as ScanWhirlApp).repository }

    /** Tool flows extracted in Phase 2; launchers stay in this Activity. */
    private val resizeFlow by lazy { ResizeFlow(this, repo, this) }
    private val compressFlow: CompressFlow by lazy {
        CompressFlow(
            activity = this, repo = repo, host = this,
            pickPdf = { pickCompressPdf.launch("application/pdf") },
            docs = { allDocs }
        )
    }
    private val mergeFlow: MergeFlow by lazy {
        MergeFlow(
            activity = this, repo = repo, host = this,
            docs = { allDocs },
            onMerged = { merged ->
                setSelectionMode(false)
                refreshList()
                openViewer(merged)
            }
        )
    }
    private val lockFlow: LockFlow by lazy {
        LockFlow(
            activity = this, repo = repo, host = this,
            docs = { allDocs },
            pickPdf = { action ->
                pendingUriAction = action
                pickProPdf.launch(arrayOf("application/pdf"))
            }
        )
    }
    private val vaultFlow: VaultFlow by lazy {
        VaultFlow(
            activity = this, repo = repo, host = this,
            pickPdf = { action ->
                pendingUriAction = action
                pickProPdf.launch(arrayOf("application/pdf"))
            },
            setVaultMode = { enabled -> setVaultMode(enabled) },
            onProRequired = { showProPanel() }
        )
    }
    private val longImageFlow: LongImageFlow by lazy {
        LongImageFlow(activity = this, repo = repo, host = this)
    }
    private val ocrFlow: OcrFlow by lazy {
        OcrFlow(activity = this, repo = repo, host = this)
    }

    /** test26 T8.1: signal dot next to the wordmark. */
    private lateinit var signalDot: View
    private var backgroundWorkCount = 0
    private var signalAnimator: ValueAnimator? = null
    private val scanLauncher: ScanLauncher by lazy {
        ScanLauncher(
            activity = this, repo = repo, host = this,
            startScanning = { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        )
    }

    /** The single scan action: direct launcher, or Add to vault in vault mode. */
    internal var scanStarter: () -> Unit = { scanLauncher.launchScanner() }

    private var selectionMode = false
    private val selectedIds = LinkedHashSet<String>()

    /** True when the 4-digit PIN Hidden Vault is unlocked and showing private scans. */
    private var vaultMode = false

    /** Current search query (applies to the saved-scans list). */
    private var query = ""

    /** T3.4 — active chip-rail filter; `null` means the "All" chip. */
    private var selectedCategory: String? = null

    /** T3.5 — OCR "In text" toggle; gated by Pro inside HomeState.inText. */
    private var searchInText = true

    /** Documents in the list as last submitted; drives the list header count. */
    private var shownCount = 0

    /** T4.4 — the Quick Sort nudge's label (built once in onCreate). */
    private lateinit var nudgeLabel: TextView

    /** Edit tool: pick one device PDF and open it as editable pages. */
    private val pickEditPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmEditPdf(uri)
        }

    /**
     * Editing rebuilds the PDF from page images, so vector/text pages become
     * raster. The user confirms this once — honesty over surprise.
     */
    private fun confirmEditPdf(uri: Uri) {
        SwDialog.card(this)
            .setTitle(R.string.edit_pdf_note_title)
            .setMessage(R.string.pdf_rasterize_note)
            .setPositiveButton(R.string.continue_label) { _, _ -> startEditPdf(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    /** All documents on disk; refreshed off the main thread, queried in memory. */
    private var allDocs: List<ScanDocument> = emptyList()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selectionMode) {
                setSelectionMode(false)
            } else if (vaultMode) {
                setVaultMode(false)
            }
        }
    }

    private val pickResizeImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { resizeFlow.start(it) } }

    private val pickCompressPdf = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { compressFlow.startFromFile(it) } }

    /** Pending action for a Pro device-PDF picker (OCR, Lock, Long Image…). */
    private var pendingUriAction: ((Uri) -> Unit)? = null

    private val pickProPdf = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val action = pendingUriAction
        pendingUriAction = null
        if (uri != null) action?.invoke(uri)
    }

    private val pickProImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val action = pendingUriAction
        pendingUriAction = null
        if (uri != null) action?.invoke(uri)
    }

    /** Direct gallery import: no scanner, no perspective crop, no tilt. */
    private val pickGalleryImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) scanLauncher.stageAndReview(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()

        // test26 T8.1: signal dot next to the wordmark.
        signalDot = binding.signalDot

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.scanList.layoutManager = LinearLayoutManager(this)
        binding.scanList.adapter = adapter

        // T3.6: the bottom bar replaces the FAB and the tool cards (R7/Q7).
        // SCAN keeps the old FAB behaviour: add when the vault is open.
        binding.dockRoot.isVisible = true
        binding.btnTools.setOnClickListener { showToolsSheet() }
        // T4.4: the Quick Sort nudge (T3.1 left the container GONE) — one
        // glass-card label between the chip rail and the list header; it
        // counts Unsorted/Other docs and opens the §4.8 sheet. updateNudge()
        // (the renderHome path) keeps the count honest. The click target is
        // the whole card (the label stays inert and lets taps bubble).
        nudgeLabel = TextView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_glass_card)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, (14 * resources.displayMetrics.density).toInt(), pad,
                (14 * resources.displayMetrics.density).toInt())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyan_primary))
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        binding.nudge.addView(nudgeLabel)
        // R6: the card itself is the touch target (≥48dp with its padding).
        binding.nudge.minimumHeight = (48 * resources.displayMetrics.density).toInt()
        binding.nudge.setOnClickListener { showQuickSort() }
        binding.btnScan.setOnClickListener {
            binding.btnScan.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (vaultMode) vaultFlow.addToVault() else scanStarter()
        }
        binding.btnImport.setOnClickListener { showImportSheet() }
        // T6: empty-state buttons — visible only in the first-run case.
        binding.btnEmptyScan.setOnClickListener { scanStarter() }
        binding.btnEmptyImport.setOnClickListener { showImportSheet() }
        // T3.8: Show all drops every filter (query + category) in one tap.
        binding.btnShowAll.setOnClickListener {
            binding.searchInput.setText("")
            selectCategory(null)
        }

        binding.btnVaultLock.setOnClickListener { setVaultMode(false) }
        binding.btnVaultChangePin.setOnClickListener { vaultFlow.promptSetVaultPin(onSuccess = {}) }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                // T3.5: the clear (×) lives only while there is text to clear.
                binding.btnClearSearch.isVisible = !s.isNullOrEmpty()
                applyList()
            }
        })
        binding.sortSelector.setOnClickListener { showSortMenu() }
        binding.btnSelect.setOnClickListener { setSelectionMode(true) }
        binding.btnClearSearch.setOnClickListener {
            binding.searchInput.setText("")
            binding.searchInput.requestFocus()
        }
        // T6: the "In text" chip is deleted — search always covers names + text.
        binding.searchInput.onFocusChangeListener = null
        // Tapping the crown badge (or the whole title block) opens the Pro panel.
        binding.proBadge.setOnClickListener { showProPanel() }
        binding.titleBlock.setOnClickListener { showProPanel() }

        // T3.7: contextual selection header. Move is wired in T4.3 (category
        // picker) — it exists disabled so the row never re-flows.
        binding.btnSelClose.setOnClickListener { setSelectionMode(false) }
        // T4.3: Move opens the picker for the whole selection, then exits
        // selection and confirms with a "Moved N to <cat>" toast.
        binding.btnSelMove.setOnClickListener {
            moveCategory(selectedIds.toList(), fromSelection = true, current = null)
        }
        binding.btnSelMerge.setOnClickListener { mergeFlow.promptMerge(selectedIds.toList()) }
        binding.btnSelExport.setOnClickListener { exportSelectedZip() }
        binding.btnSelDelete.setOnClickListener { confirmDeleteSelected() }
        // T3.12: Select all picks every card the list currently shows —
        // the filtered (visible) set, never the hidden ones.
        binding.btnSelSelectAll.setOnClickListener {
            adapter.currentList.forEach { selectedIds.add(it.id) }
            adapter.notifyDataSetChanged()
            updateSelectionHeader()
        }

        // Stale edit snapshots (cache) go; an unfinished scan is offered back to
        // the user instead of silently wiped (it survives in cacheDir).
        val stale = cacheDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("edit_snapshot_")
        }
        stale?.forEach { it.deleteRecursively() }
        scanLauncher.offerUnfinishedScan()

        updateSelectionHeader()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        // AppCompat re-syncs the toolbar title from the manifest label during resume.
        updateSelectionHeader()
        updateStatusLine()
        lifecycleScope.launch {
            val background = withContext(Dispatchers.IO) {
                com.scanwhirl.app.util.Wallpaper.drawable(this@MainActivity)
            }
            if (background != null) {
                binding.root.background = background
            } else {
                binding.root.background = null
            }
        }
    }

    /**
     * HUD status line under the app name, when enabled in Settings (T1.5/T3.2).
     *
     * test26 T3.10 (complaint #14): derives from the **unfiltered** [allDocs],
     * so a category/search filter never changes the readout, and it is
     * refreshed from the single render path in [applyList] — first load, add,
     * delete and import all land there. Direct calls remain only for
     * show/hide transitions (selection mode, resume).
     */
    private fun updateStatusLine() {
        if (Prefs.showGreeting(this) && !selectionMode) {
            val docs = allDocs
            binding.statusLine.text = StatusLine.styled(
                this,
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                docs.size,
                docs.sumOf { it.pdfFile.length() }
            )
            binding.statusLine.isVisible = true
        } else {
            binding.statusLine.isVisible = false
        }
    }

    /**
     * test26 T4.4 — the Quick Sort nudge: visible only while the queue has
     * work (Unsorted + Other, §4.8), counting from the same unfiltered
     * snapshot as the status line. Closing the sheet mid-queue (Rev 4 §3D)
     * therefore never needs a manual refresh here: the next render shows
     * whatever is still unsorted.
     */
    private fun updateNudge() {
        val n = allDocs.count {
            it.category == CategoryRules.UNSORTED || it.category == CategoryRules.OTHER
        }
        binding.nudge.isVisible = n > 0
        if (n > 0) nudgeLabel.text = getString(R.string.quick_sort_nudge, n)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(SettingsActivity.intent(this))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun refreshList() {
        lifecycleScope.launch {
            allDocs = withContext(Dispatchers.IO) {
                if (vaultMode) repo.listVaultDocuments() else repo.listDocuments()
            }
            buildChipRail()
            applyList()
        }
    }

    override fun workStarted() {
        backgroundWorkCount++
        updateSignalDot()
    }

    override fun workEnded() {
        backgroundWorkCount = maxOf(0, backgroundWorkCount - 1)
        updateSignalDot()
    }

    /** test26 T8.1: light/dim the signal dot; breathe only when ambient allowed. */
    private fun updateSignalDot() {
        val active = backgroundWorkCount > 0
        signalDot.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            startSignalBreath()
        } else {
            stopSignalBreath()
            signalDot.alpha = 1f
        }
    }

    private fun startSignalBreath() {
        if (!SwMotion.ambientAllowed(this)) return
        signalAnimator?.cancel()
        signalAnimator = ValueAnimator.ofFloat(0.4f, 1f, 0.4f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                signalDot.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun stopSignalBreath() {
        signalAnimator?.cancel()
        signalAnimator = null
    }

    /** In-memory filter + sort over [allDocs]; used by search and sort too. */
    private fun applyList() {
        // Pure logic lives in HomeFilter (T3.3); this just builds the state.
        // Vault mode passes includeVaulted so the PIN'd list is the list.
        val state = HomeState(
            query = query,
            category = selectedCategory,
            sort = Prefs.sortMode(this),
            inText = searchInText && ProGate.isPro(this),
            includeVaulted = vaultMode
        )
        val sorted = HomeFilter.apply(allDocs, state) { repo.ocrDoc(it) }
        adapter.selectedIds = selectedIds
        adapter.snippets = emptyMap()
        adapter.submitList(sorted)
        // A search that matches nothing leaves nothing to show; if `sorted`
        // is empty the name and OCR legs both came up empty, so no extra
        // ocrMatches bookkeeping is needed.
        binding.emptyState.isVisible = sorted.isEmpty()
        if (sorted.isEmpty()) updateEmptyState()
        binding.scanList.isVisible = true
        shownCount = sorted.size
        updateListHeader()
        // test26 T3.10: the render path above feeds chips + adapter; the
        // status line joins it here (from the unfiltered allDocs snapshot),
        // so the numbers on screen can never lag the list (complaint #14).
        updateStatusLine()
        // test26 T4.4: the Quick Sort nudge is part of the same render —
        // it counts Unsorted/Other from the same unfiltered snapshot.
        updateNudge()
        if (selectionMode && allDocs.none { it.id in selectedIds }) {
            setSelectionMode(false)
        }
    }

    /**
     * test26 T3.8 — the empty state speaks for the situation it is in:
     * fresh library → viewfinder brackets + "Scan your first document" (the
     * SCAN button in the bottom bar is the call to action); a filter that
     * hides everything → "No <Category> documents yet" / "No documents match
     * your search" + a Show all chip; an empty vault keeps the copy set by
     * [setVaultMode].
     */
    private fun updateEmptyState() {
        binding.emptyFrame.isVisible = true
        when {
            vaultMode && allDocs.isEmpty() -> {
                binding.emptyTitle.setText(R.string.vault_empty_title)
                binding.emptySubtitle.setText(R.string.vault_empty_subtitle)
                binding.emptySubtitle.isVisible = true
                binding.btnShowAll.isVisible = false
            }
            allDocs.isEmpty() -> {
                binding.emptyTitle.setText(R.string.empty_first_scan)
                // T3.13: the muted hint names the call to action — the SCAN
                // button in the bottom bar.
                binding.emptySubtitle.setText(R.string.empty_first_scan_hint)
                binding.emptySubtitle.isVisible = true
                binding.btnShowAll.isVisible = false
            }
            else -> {
                // The library exists; only the active filter hides it all.
                val cat = selectedCategory
                binding.emptyTitle.text =
                    if (cat != null) {
                        getString(R.string.empty_category, CategoryRules.label(cat))
                    } else {
                        getString(R.string.empty_no_match)
                    }
                binding.emptySubtitle.isVisible = false
                binding.btnShowAll.isVisible = true
            }
        }
    }

    /**
     * test26 T3.4 — build the category chip rail: **All**, then **Unsorted**
     * only while it has documents, then the five picker categories. Each chip
     * shows its count (global, so chips don't jump around while typing) and
     * is tinted with its category colour when checked.
     */
    private fun buildChipRail() {
        val counts = HomeFilter.counts(allDocs)
        val rail = binding.chipGroup
        rail.removeAllViews()

        // (null, "All") stands in for "no category filter".
        val entries = mutableListOf<Pair<String?, String>>()
        entries += null to getString(R.string.chip_all)
        if ((counts[CategoryRules.UNSORTED] ?: 0) > 0) {
            entries += CategoryRules.UNSORTED to CategoryRules.label(CategoryRules.UNSORTED)
        }
        for (cat in CategoryRules.ALL) {
            entries += cat to CategoryRules.label(cat)
        }

        // Scoped context so only these chips pick up Widget.ScanWhirl.Chip;
        // the resize dialog and page viewer style their own chips inline.
        val ctx = ContextThemeWrapper(this, R.style.ThemeOverlay_ScanWhirl_ChipRail)
        for ((key, label) in entries) {
            val count = if (key == null) allDocs.size else counts[key] ?: 0
            val chip = Chip(ctx).apply {
                tag = key
                text = "$label $count"
                // Tapping the chip that is already active falls back to All.
                setOnClickListener {
                    selectCategory(if (key == selectedCategory) null else key)
                }
                // test26 T8.4: Unsorted chip gets a dashed outline.
                if (key == CategoryRules.UNSORTED) {
                    setBackgroundResource(R.drawable.bg_chip_unsorted_dashed)
                }
            }
            rail.addView(chip)
        }
        binding.chipRail.isVisible = true
        updateChipRailChecked()
    }

    /** Paint the one active chip (2dp stroke per §5.2) and uncheck the rest. */
    private fun updateChipRailChecked() {
        val rail = binding.chipGroup
        for (i in 0 until rail.childCount) {
            val chip = rail.getChildAt(i) as Chip
            val active = chip.tag as String? == selectedCategory
            chip.isChecked = active
            paintChip(chip, chip.tag as String?, active)
        }
    }

    /**
     * Checked chips take their category colour: a dark tint behind, the full
     * colour on the border (2dp) and label. Unchecked chips keep the plain
     * hairline look from [R.style.Widget_ScanWhirl_Chip].
     */
    private fun paintChip(chip: Chip, key: String?, active: Boolean) {
        // "All" has no category, so it borrows the app accent.
        val color = if (key == null) ContextCompat.getColor(this, R.color.cyan_primary)
        else CategoryStyle.color(key)
        val density = resources.displayMetrics.density

        chip.chipBackgroundColor = ColorStateList.valueOf(
            if (active) (color and 0x00FFFFFF) or (0x33 shl 24)
            else ContextCompat.getColor(this, R.color.chip_void)
        )
        chip.chipStrokeColor = ColorStateList.valueOf(
            if (active) color else ContextCompat.getColor(this, R.color.cyan_border_idle)
        )
        chip.chipStrokeWidth = (if (active) 2f else 1f) * density  // §5.2: 1dp hairline, 2dp selected
        chip.setTextColor(
            if (active) color else ContextCompat.getColor(this, R.color.text_hologram_mid)
        )
    }

    /** Chip tap: filter to [key], or back to All when it is tapped twice. */
    private fun selectCategory(key: String?) {
        selectedCategory = key
        // test26 T8.5: haptic tick on every category change.
        binding.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        updateChipRailChecked()
        applyList()
    }

    /**
     * List header (T3.5): "N documents" / "N in Category", the sort selector
     * label mirroring [Prefs.sortMode], and Select. Hidden while the list is
     * empty (the empty state speaks instead) or while selecting (the
     * contextual header takes over in T3.7).
     */
    private fun updateListHeader() {
        val show = shownCount > 0 && !selectionMode
        binding.listHeader.isVisible = show
        if (!show) return

        val cat = selectedCategory
        binding.headerCount.text =
            if (cat == null) {
                resources.getQuantityString(R.plurals.list_count, shownCount, shownCount)
            } else {
                getString(R.string.list_count_category, shownCount, CategoryRules.label(cat))
            }

        val label = when (Prefs.sortMode(this)) {
            Prefs.SORT_NAME -> R.string.sort_name
            Prefs.SORT_MODIFIED -> R.string.sort_modified
            Prefs.SORT_CATEGORY -> R.string.sort_category
            else -> R.string.sort_date
        }
        binding.sortSelector.text = getString(label) + " ▾"
        // Keep the a11y announcement in sync with the visible label.
        binding.sortSelector.contentDescription =
            getString(R.string.sort) + ", " + getString(label)
    }

    /**
     * T3.7/T3.12 — the contextual header replaces the toolbar row while
     * selecting: the toolbar (wordmark, status line, gear) hides and
     * `selectionHeader` shows `X · N selected · Select all`. The four
     * actions live in `selectionBar` now (§4.2 Rev 3 — the one-row layout
     * clipped the count, complaint #15). Move stays visible-but-disabled
     * until T4.3; Export only exists for Pro; Merge needs ≥ 2 picks;
     * Delete needs ≥ 1. The menu is static now, so no
     * `invalidateOptionsMenu` anywhere.
     */
    private fun updateSelectionHeader() {
        val n = selectedIds.size
        if (selectionMode) {
            binding.toolbar.visibility = View.GONE
            binding.selectionHeader.visibility = View.VISIBLE
            binding.selCount.text =
                resources.getQuantityString(R.plurals.selected_count, n, n)
            binding.btnSelMerge.isEnabled = n >= 2
            binding.btnSelDelete.isEnabled = n > 0
            // T4.3: Move is live (was disabled until T4.3).
            binding.btnSelMove.isEnabled = n > 0
            // Pro extras only appear in the Pro UI (Free keeps no dead buttons).
            val pro = ProGate.isPro(this)
            binding.btnSelExport.visibility = if (pro) View.VISIBLE else View.GONE
            binding.btnSelExport.isEnabled = pro && n > 0
        } else {
            binding.toolbar.visibility = View.VISIBLE
            binding.selectionHeader.visibility = View.GONE
            // AppCompat re-syncs the toolbar title from the manifest label
            // during resume — keep it suppressed (the title row carries the name).
            supportActionBar?.setDisplayShowTitleEnabled(false)
            supportActionBar?.title = null
            binding.toolbar.title = null
        }
        updateProBadge()
        updateStatusLine()
    }

    /**
     * Crown badge next to the app name: gold PRO unlocked, dim FREE otherwise.
     * In Free it is the entry point to the pitch (the Pro Suite card died in
     * T3.6; the Tools sheet gates its Pro tiles the same way). The search
     * hint no longer changes with Pro — T3.5 replaced it with the "In text"
     * chip.
     */
    private fun updateProBadge() {
        val pro = ProGate.isPro(this)
        if (pro) {
            binding.proBadge.text = getString(R.string.badge_pro)
            binding.proBadge.setTextColor(ContextCompat.getColor(this, R.color.pro_gold))
            binding.proBadge.compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.pro_gold))
            binding.proBadge.setBackgroundResource(R.drawable.bg_pro_badge)
        } else {
            binding.proBadge.text = getString(R.string.badge_free)
            binding.proBadge.setTextColor(ContextCompat.getColor(this, R.color.sf_on_surface_variant))
            binding.proBadge.compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.sf_on_surface_variant)
                )
            binding.proBadge.setBackgroundResource(R.drawable.bg_free_badge)
        }
    }

    /**
     * Pro panel: what the crown unlocks and where each extra lives. Test
     * builds get a "Preview Free/Pro" toggle so both UIs can be reviewed on
     * the same phone without a second APK.
     */
    private fun showProPanel() {
        val pro = ProGate.isPro(this)
        val view = layoutInflater.inflate(R.layout.dialog_pro_panel, null)
        view.findViewById<TextView>(R.id.proDossierStatus)?.text =
            getString(if (pro) R.string.pro_status_test else R.string.pro_status_unavailable)
        val builder = SwDialog.sheet(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
        if (com.scanwhirl.app.BuildConfig.TEST_BUILD) {
            builder.setNeutralButton(
                if (pro) R.string.preview_free_ui else R.string.preview_pro_ui
            ) { _, _ ->
                ProGate.setTestOverride(this, !pro)
                recreate()
                Toast.makeText(
                    this,
                    getString(
                        R.string.preview_changed,
                        getString(if (pro) R.string.badge_free else R.string.badge_pro)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        builder.showSw(isSheet = true)
    }

    /**
     * test26 T4.4 — Quick Sort (§4.8): a [QuickSortQueue] over the library's
     * Unsorted + Other docs, one document on screen with its thumbnail, five
     * category chips and Skip. Picking saves immediately (plan) plus one
     * CLOCK_TICK haptic; the X button top-right (Rev 4 §3D) just closes —
     * whatever was never picked stays unsorted and the nudge keeps counting
     * it. An empty queue opens straight onto "All sorted".
     */
    private fun showQuickSort() {
        val queue = QuickSortQueue.from(allDocs)
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_quick_sort, null)
        sheet.setContentView(view)

        val progress = view.findViewById<TextView>(R.id.qsProgress)
        val thumb = view.findViewById<ImageView>(R.id.qsThumb)
        val docLine = view.findViewById<TextView>(R.id.qsDoc)
        val chips = view.findViewById<ChipGroup>(R.id.qsChips)
        val done = view.findViewById<TextView>(R.id.qsDone)

        fun render() {
            val doc = queue.current()
            if (doc == null) {
                progress.isVisible = false
                thumb.isVisible = false
                docLine.isVisible = false
                chips.isVisible = false
                done.isVisible = true
                return
            }
            val p = queue.progress()
            progress.text = getString(R.string.qs_progress, p.position, p.total)
            val pages = resources.getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
            docLine.text = "${doc.name} · $pages"
            val first = doc.pageFiles.firstOrNull()
            if (first != null) {
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.setImageResource(R.drawable.ic_document)
                // Background decode + cache, same loader the list cards use.
                com.scanwhirl.app.ui.pagegrid.PageThumbLoader.load(
                    first, 640, com.scanwhirl.app.util.ScanFilter.ORIGINAL, thumb
                )
            } else {
                thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                thumb.setImageResource(R.drawable.ic_document)
            }
            progress.isVisible = true
            thumb.isVisible = true
            docLine.isVisible = true
            chips.isVisible = true
            done.isVisible = false
        }

        // Scoped context so the chips pick up Widget.ScanWhirl.Chip (the
        // same overlay Home's rail and the review screen use).
        val ctx = ContextThemeWrapper(this, R.style.ThemeOverlay_ScanWhirl_ChipRail)
        for (cat in CategoryRules.ALL) {
            val chip = Chip(ctx).apply {
                id = android.view.View.generateViewId()
                tag = cat
                text = CategoryRules.label(cat)
                setOnClickListener {
                    val doc = queue.pick(cat) ?: return@setOnClickListener
                    // Picking saves instantly + one tick of haptic (plan).
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    repo.setCategory(doc.id, cat)
                    render()
                    refreshList() // nudge, rail and list follow the new category
                }
            }
            chips.addView(chip)
        }
        val skipChip = Chip(ctx).apply {
            id = android.view.View.generateViewId()
            tag = "skip"
            text = getString(R.string.skip)
            setOnClickListener {
                queue.skip()
                render() // nothing saved, nothing to refresh
            }
        }
        chips.addView(skipChip)

        view.findViewById<View>(R.id.qsClose).setOnClickListener { sheet.dismiss() }
        sheet.show()
        render()
        // Open expanded, exactly like the Tools sheet: content taller than
        // the collapsed peek would hang its excess below the screen edge.
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    /**
     * T3.6 — the Tools sheet (§4.3): a Free section, a PRO divider and a Pro
     * section, every tile a [R.layout.view_tool_tile]. Core tiles call the
     * P2 flows directly; Pro tiles pass through [ProGate] first, so Free
     * gets the pitch instead of the flow.
     *
     * T3.11 (complaint #16): each section is chunked into horizontal rows of
     * [columns] — 4, or 3 when the longest label word would not fit the
     * tile's inner width at this width/font scale, so a word is never broken
     * across lines (§4.3 rule, measured with the tile's real TextPaint).
     */
    private fun showToolsSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_tools, null)
        sheet.setContentView(view)

        class Tile(val id: Int, val label: Int, val icon: Int, val pro: Boolean, val run: () -> Unit)

        val free = listOf(
            // T6: OCR tile (id tile_ocr) is Free — no Pro gate.
            Tile(R.id.tile_ocr, R.string.btn_ocr_text, R.drawable.ic_ocr, false) {
                chooseProSource(
                    R.string.ocr_title,
                    onScan = { doc -> ocrFlow.runOcrForScan(doc) },
                    onPdf = { uri -> ocrFlow.runOcrForPdf(uri) },
                    onImage = { uri -> ocrFlow.runOcrForImage(uri) }
                )
            },
            Tile(R.id.tile_merge, R.string.tool_merge, R.drawable.ic_merge, false) {
                mergeFlow.showMergeChooser()
            },
            Tile(R.id.tile_compress, R.string.tool_compress, R.drawable.ic_compress, false) {
                compressFlow.start()
            },
            Tile(R.id.tile_resize, R.string.tool_resize, R.drawable.ic_resize, false) {
                pickResizeImage.launch("image/*")
            },
            // T4.4: the 9th tile (§4.3) — same sheet entry as the nudge.
            Tile(R.id.tile_quick_sort, R.string.quick_sort, R.drawable.ic_sort, false) {
                showQuickSort()
            }
        )
        val proTiles = listOf(
            Tile(R.id.tile_lock, R.string.tool_lock, R.drawable.ic_lock, true) {
                lockFlow.chooseLockSource()
            },
            Tile(R.id.tile_long_image, R.string.btn_long_image, R.drawable.ic_long_image, true) {
                chooseProSource(
                    R.string.btn_long_image,
                    onScan = { doc -> longImageFlow.runLongImageForScan(doc) },
                    onPdf = { uri -> longImageFlow.runLongImageForPdf(uri) }
                )
            },
            Tile(R.id.tile_vault, R.string.bar_vault, R.drawable.ic_vault, true) {
                vaultFlow.openVaultWithPin()
            }
        )

        // §4.3 column rule: probe one label (12sp, already font-scaled at
        // inflation) and compare the longest word with the tile's inner width
        // in the 4-column grid; a miss drops the whole sheet to 3 columns.
        val columns = run {
            val probe = layoutInflater.inflate(R.layout.view_tool_tile, null)
                .findViewById<TextView>(R.id.tileLabel)
            val longest = (free + proTiles).maxOf { t ->
                getString(t.label)
                    .split(Regex("\\s+")).filter { it.isNotEmpty() }
                    .maxOf { probe.paint.measureText(it) }
            }
            val d = resources.displayMetrics.density
            val content = resources.displayMetrics.widthPixels - 24f * d // 12dp sheet sides
            fun inner(cols: Int): Float =
                (content - cols * 6f * d) / cols - 4f * d // 3dp margins, 2dp pads
            if (longest <= inner(4)) 4 else 3
        }

        fun addSection(section: LinearLayout, tiles: List<Tile>) {
            section.removeAllViews()
            for (rowTiles in tiles.chunked(columns)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                for (t in rowTiles) {
                    val tile = layoutInflater.inflate(R.layout.view_tool_tile, row, false)
                    tile.id = t.id
                    tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(t.icon)
                    tile.findViewById<TextView>(R.id.tileLabel).setText(t.label)
                    tile.setOnClickListener {
                        sheet.dismiss()
                        if (t.pro && !ProGate.isPro(this@MainActivity)) showProPanel()
                        else t.run()
                    }
                    row.addView(tile)
                }
                section.addView(row)
            }
        }

        addSection(view.findViewById(R.id.sheetFree), free)
        addSection(view.findViewById(R.id.sheetPro), proTiles)
        sheet.show()
        // T3.11: open expanded, not at Material's default rest state
        // (STATE_COLLAPSED). Collapsed equals "fully visible" only while the
        // content is shorter than the 16:9-keyline peek; at large font scales
        // this sheet is taller, and collapsed would hang its excess below the
        // screen edge (the TextFit rig caught exactly that at scale 1.3).
        // Expanded puts the sheet flush with the screen bottom
        // (fitToContentsOffset) at every scale; it stays draggable and its
        // content scrollable (NestedScrollView).
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        backCallback.isEnabled = enabled || vaultMode
        if (!enabled) selectedIds.clear()
        adapter.selectionMode = enabled
        adapter.notifyDataSetChanged()
        // T3.12 (amends T3.7's "bottom bar hidden while selecting"): the
        // SCAN bar still leaves, but the contextual action bar takes its
        // place instead of wasting the row (§4.2 Rev 3).
        binding.dockRoot.isVisible = !enabled
        binding.selectionBar.isVisible = enabled
        updateSelectionHeader()
        updateStatusLine()
        updateListHeader()
    }

    // ------------------------------------------------------------------
    // Pro Suite: Hidden Vault (4-digit PIN), OCR, Lock/Unlock, Long Image
    // ------------------------------------------------------------------


    internal fun setVaultMode(enabled: Boolean) {
        vaultMode = enabled
        backCallback.isEnabled = selectionMode || vaultMode
        binding.vaultBanner.isVisible = enabled
        // In the vault SCAN becomes "Add": gold disc, ic_add, "Add" label.
        binding.dockRoot.isVisible = !selectionMode
        val disc = binding.scanButtonDisc
        disc.setBackgroundResource(if (enabled) R.drawable.bg_scan_button_gold else R.drawable.bg_scan_button)
        binding.scanIcon.setImageResource(if (enabled) R.drawable.ic_add else R.drawable.ic_document_scanner)
        binding.scanLabel.text = getString(if (enabled) R.string.bar_add else R.string.bar_scan)
        binding.scanLabel.setTextColor(if (enabled) ContextCompat.getColor(this, R.color.pro_gold) else ContextCompat.getColor(this, R.color.cyan_primary))
        binding.btnScan.contentDescription = getString(if (enabled) R.string.vault_add_title else R.string.scan)
        binding.btnImport.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
        refreshList()
    }

    // ------------------------------------------------------------------
    // Scan source chooser (camera vs a straight gallery import)
    // ------------------------------------------------------------------

    /**
     * Import sheet: Photos from gallery / PDF as pages. Replaces the old
     * camera/gallery chooser.
     */
    private fun showImportSheet() {
        SwDialog.sheet(this)
            .setTitle(R.string.import_title)
            .setItems(
                arrayOf(
                    getString(R.string.import_photos),
                    getString(R.string.import_pdf)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickGalleryImages.launch("image/*")
                    1 -> pickEditPdf.launch(arrayOf("application/pdf"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }

    /**
     * Where should this Pro extra take its input from? Saved scans work
     * in place; device PDFs and gallery images run the same extra on an
     * imported file so the features are not limited to scans made in-app.
     */
    private fun chooseProSource(
        titleRes: Int,
        onScan: (ScanDocument) -> Unit,
        onPdf: (Uri) -> Unit,
        onImage: ((Uri) -> Unit)? = null
    ) {
        val items = mutableListOf(
            getString(R.string.source_saved_scan),
            getString(R.string.source_device_pdf)
        )
        if (onImage != null) items += getString(R.string.source_gallery_image)
        SwDialog.sheet(this)
            .setTitle(titleRes)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> FlowUtil.pickScan(this, titleRes, allDocs) { doc -> onScan(doc) }
                    1 -> {
                        pendingUriAction = onPdf
                        pickProPdf.launch(arrayOf("application/pdf"))
                    }
                    else -> {
                        pendingUriAction = onImage ?: return@setItems
                        pickProImage.launch("image/*")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }


    private fun toggleSelection(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        adapter.notifyDataSetChanged()
        updateSelectionHeader()
    }

    private fun onDocClick(doc: ScanDocument) {
        if (selectionMode) {
            toggleSelection(doc.id)
        } else {
            openViewer(doc)
        }
    }

    private fun onDocMore(doc: ScanDocument) {
        if (!selectionMode) showQuickActions(doc)
    }

    private fun onDocLongClick(doc: ScanDocument) {
        // T4.3 (owner decision): long-press opens the quick-actions sheet;
        // `Select` is what enters selection mode now (T3.7 amended).
        showQuickActions(doc)
    }

    /**
     * T4.3: the card's category chip → picker → `setCategory`. The picker
     * opens on the card's current category so its row carries the check.
     */
    private fun onCategory(doc: ScanDocument) {
        CategoryPickerSheet.show(this, doc.category) { cat ->
            repo.setCategory(doc.id, cat)
            refreshList()
        }
    }

    /**
     * T4.3: long-press quick actions for one card — Rename, Move to
     * category, Share, Delete (§4.x). Each action dismisses the sheet
     * before opening its own dialog/flow.
     */
    private fun showQuickActions(doc: ScanDocument) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_quick_actions, null)
        sheet.setContentView(view)
        view.findViewById<TextView>(R.id.qaTitle).text = doc.name
        view.findViewById<View>(R.id.qaEditPages).setOnClickListener {
            sheet.dismiss()
            openEditor(doc)
        }
        view.findViewById<View>(R.id.qaRename).setOnClickListener {
            sheet.dismiss()
            promptRename(doc)
        }
        view.findViewById<View>(R.id.qaMove).setOnClickListener {
            sheet.dismiss()
            moveCategory(listOf(doc.id), fromSelection = false, current = doc.category)
        }
        view.findViewById<View>(R.id.qaShare).setOnClickListener {
            sheet.dismiss()
            ShareUtils.share(this, doc)
        }
        view.findViewById<View>(R.id.qaDelete).setOnClickListener {
            sheet.dismiss()
            confirmDelete(listOf(doc.id))
        }
        sheet.show()
    }

    /** T4.3: rename dialog (same shape as the Document screen's prompt). */
    private fun promptRename(doc: ScanDocument) {
        val (builder, input) = SwDialog.input(
            this, hint = getString(R.string.rename_title), initial = doc.name
        )
        builder
            .setTitle(R.string.rename_title)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()
                if (!newName.isNullOrBlank()) {
                    repo.renameDocument(doc.id, newName.trim())
                    refreshList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    /**
     * T4.3: the category picker for one card (quick actions) or the whole
     * selection (Move). Bulk moves exit selection and confirm with a
     * "Moved N to <cat>" toast; a single-card move just refreshes.
     */
    private fun moveCategory(ids: List<String>, fromSelection: Boolean, current: String?) {
        if (ids.isEmpty()) return
        CategoryPickerSheet.show(this, current) { cat ->
            repo.setCategory(ids, cat)
            if (fromSelection && selectionMode) setSelectionMode(false)
            refreshList()
            if (fromSelection) {
                Toast.makeText(
                    this,
                    getString(R.string.moved_n_to_cat, ids.size, cat),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Edit / Merge device PDFs
    // ------------------------------------------------------------------

    /** Edit tool: import a device PDF as editable pages and open the editor. */
    private fun startEditPdf(uri: Uri) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val name = repo.displayNameOf(uri)
                    ?.removeSuffix(".pdf")?.removeSuffix(".PDF")
                    ?.takeIf { it.isNotBlank() } ?: "Imported PDF"
                val doc = withContext(Dispatchers.IO) { repo.importPdfAsScan(uri, name) }
                // Imported pages index into Pro text search too.
                if (ProGate.isPro(this@MainActivity)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try { repo.buildOcr(doc.id) } catch (_: Exception) { }
                    }
                }
                progress.dismiss()
                startActivity(DocumentViewerActivity.intent(this@MainActivity, doc.id))
            } catch (e: SecurityException) {
                progress.dismiss()
                Toast.makeText(this@MainActivity, R.string.pdf_protected, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.edit_pdf_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Search / sort / merge / settings
    // ------------------------------------------------------------------

    private fun showSortMenu() {
        val popup = PopupMenu(this, binding.sortSelector)
        popup.menu.add(0, 0, 0, getString(R.string.sort_name))
        popup.menu.add(0, 1, 1, getString(R.string.sort_date))
        popup.menu.add(0, 2, 2, getString(R.string.sort_modified))
        popup.menu.add(0, 3, 3, getString(R.string.sort_category))
        popup.setOnMenuItemClickListener { item ->
            val mode = when (item.itemId) {
                0 -> Prefs.SORT_NAME
                1 -> Prefs.SORT_DATE
                2 -> Prefs.SORT_MODIFIED
                else -> Prefs.SORT_CATEGORY
            }
            Prefs.setSortMode(this, mode)
            applyList()
            true
        }
        popup.show()
    }

    /** Pro extra: pack every ticked scan's PDF into one ZIP, then show the result. */
    private fun exportSelectedZip() {
        if (!ProGate.isPro(this)) return
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val docs = withContext(Dispatchers.IO) {
                    ids.mapNotNull { id -> allDocs.firstOrNull { it.id == id } }
                }
                val out = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "documents/share").apply { mkdirs() }
                    val file = File(dir, "ScanWhirl ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}.zip")
                    ZipExporter.export(docs, file)
                    file
                }
                progress.dismiss()
                startActivity(
                    ExportResultActivity.intent(
                        this@MainActivity,
                        outputPath = out.path,
                        previewPath = null,
                        mime = "application/zip",
                        displayName = out.name,
                        beforeBytes = docs.sumOf { it.pdfFile.length() },
                        afterBytes = out.length(),
                        width = 0,
                        height = 0,
                        title = out.name,
                        warning = null
                    )
                )
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmDeleteSelected() = confirmDelete(selectedIds.toList())

    /** T4.3: the single-card quick action shares the bulk confirm (T4.8 box). */
    private fun confirmDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        // T4.8: find the Documents/ScanWhirl copies these documents own while
        // their meta.txt still exists (recorded exports + exact-name rows).
        val plan = ids.mapNotNull { repo.getDocument(it) }
            .map { it to DeleteExports.copiesFor(this, repo, it.id, it.name) }
        val total = plan.sumOf { it.second.size }
        val builder = SwDialog.danger(this)
            .setTitle(if (ids.size == 1) R.string.delete_title else R.string.delete_selected_title)
            .setMessage(
                if (ids.size == 1) {
                    val name = repo.getDocument(ids[0])?.name ?: ""
                    getString(R.string.delete_message, name)
                } else {
                    resources.getQuantityString(R.plurals.delete_selected_message, ids.size, ids.size)
                }
            )
        var box: android.widget.CheckBox? = null
        if (total > 0) {
            box = DeleteExports.checkboxRow(this, total)
            builder.setView(box)
        }
        builder
            .setPositiveButton(R.string.delete) { _, _ ->
                ids.forEach { repo.deleteDocument(it) } // never blocked by copy removal
                if (selectionMode) setSelectionMode(false)
                refreshList()
                if (box?.isChecked == true && total > 0) {
                    val (removed, failed) =
                        DeleteExports.removeCopies(this, plan.flatMap { it.second })
                    Snackbar.make(
                        binding.root,
                        DeleteExports.message(this, removed, failed),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    override fun showProgress(titleRes: Int): AlertDialog {
        return SwDialog.progress(this, titleRes = titleRes)
    }

    override fun toast(res: Int, duration: Int) {
        Toast.makeText(this, res, duration).show()
    }

    override fun toast(message: CharSequence, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scanResult?.pages.orEmpty().mapNotNull { it.imageUri }
            if (uris.isEmpty()) {
                Toast.makeText(this, R.string.scan_no_pages, Toast.LENGTH_SHORT).show()
            } else {
                scanLauncher.stageAndReview(uris)
            }
        }

    private fun openViewer(doc: ScanDocument) {
        if (doc.isLocked) {
            promptUnlockBeforeOpen(doc) { launchViewerUnchecked(doc) }
            return
        }
        launchViewerUnchecked(doc)
    }

    private fun launchViewerUnchecked(doc: ScanDocument) {
        if (Prefs.defaultViewer(this) == Prefs.VIEWER_SYSTEM) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", doc.pdfFile)
            if (tryOpenExternally(uri)) return
        }
        startActivity(PdfViewerActivity.intent(this, doc.id, doc.name))
    }

    private fun promptUnlockBeforeOpen(doc: ScanDocument, onUnlocked: () -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.protect_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        SwDialog.card(this)
            .setTitle(R.string.unlock_pdf_title)
            .setMessage(getString(R.string.unlock_pdf_msg, doc.name))
            .setView(input)
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (repo.verifyPdfPassword(doc.id, pw)) {
                    onUnlocked()
                } else {
                    Toast.makeText(this, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.unlock_pdf_remove) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (repo.verifyPdfPassword(doc.id, pw)) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.unlockPdf(doc.id) }
                        Toast.makeText(this@MainActivity, R.string.unlock_pdf_done, Toast.LENGTH_LONG).show()
                        refreshList()
                        onUnlocked()
                    }
                } else {
                    Toast.makeText(this, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    /** Editing a locked scan asks for the password first (pages stay protected). */
    private fun openEditor(doc: ScanDocument) {
        if (doc.isLocked) {
            promptUnlockBeforeOpen(doc) { launchEditor(doc) }
            return
        }
        launchEditor(doc)
    }

    private fun launchEditor(doc: ScanDocument) {
        startActivity(DocumentViewerActivity.intent(this, doc.id))
    }

    /** Opens a PDF with the user's chosen external app. False = none installed. */
    private fun tryOpenExternally(uri: Uri): Boolean = try {
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_pdf_app, Toast.LENGTH_LONG).show()
        false
    }

    companion object {
    }
}
