package com.scanwhirl.app.ui

import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.EditText
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
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SearchMatcher
import com.scanwhirl.app.util.StatusLine
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
        ScanListAdapter(::onDocClick, ::onDocView, ::onDocEdit, ::onDocLongClick)
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
    private val scanLauncher: ScanLauncher by lazy {
        ScanLauncher(
            activity = this, repo = repo, host = this,
            startScanning = { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        )
    }

    private var selectionMode = false
    private val selectedIds = LinkedHashSet<String>()

    /** True when the 4-digit PIN Hidden Vault is unlocked and showing private scans. */
    private var vaultMode = false

    /** Current search query (applies to the saved-scans list). */
    private var query = ""

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

    /** Documents whose scanned text matched the current query (Pro search). */
    private var ocrMatches: List<ScanDocument> = emptyList()

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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.scanList.layoutManager = LinearLayoutManager(this)
        binding.scanList.adapter = adapter

        binding.fabScan.setOnClickListener {
            if (vaultMode) vaultFlow.addToVault() else showScanChooser()
        }
        binding.emptyState.setOnClickListener {
            if (vaultMode) vaultFlow.addToVault() else showScanChooser()
        }

        binding.toolResize.setOnClickListener { pickResizeImage.launch("image/*") }
        binding.toolCompress.setOnClickListener { compressFlow.start() }
        binding.toolEdit.setOnClickListener {
            pickEditPdf.launch(arrayOf("application/pdf"))
        }
        binding.toolMerge.setOnClickListener { mergeFlow.showMergeChooser() }

        // Expandable Pro Suite on Home: one button that expands Vault, OCR, Lock/Unlock, Long Image.
        binding.btnProSuite.setOnClickListener { toggleProSuite() }
        binding.toolVault.setOnClickListener { vaultFlow.openVaultWithPin() }
        binding.toolOcr.setOnClickListener {
            chooseProSource(
                R.string.ocr_title,
                onScan = { doc -> ocrFlow.runOcrForScan(doc) },
                onPdf = { uri -> ocrFlow.runOcrForPdf(uri) },
                onImage = { uri -> ocrFlow.runOcrForImage(uri) }
            )
        }
        binding.toolLockPdf.setOnClickListener { lockFlow.chooseLockSource() }
        binding.toolLongImage.setOnClickListener {
            chooseProSource(
                R.string.btn_long_image,
                onScan = { doc -> longImageFlow.runLongImageForScan(doc) },
                onPdf = { uri -> longImageFlow.runLongImageForPdf(uri) }
            )
        }
        binding.btnVaultLock.setOnClickListener { setVaultMode(false) }
        binding.btnVaultChangePin.setOnClickListener { vaultFlow.promptSetVaultPin(onSuccess = {}) }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyList()
            }
        })
        binding.btnSort.setOnClickListener { showSortMenu() }        // Tapping the crown badge (or the whole title block) opens the Pro panel.
        binding.proBadge.setOnClickListener { showProPanel() }
        binding.titleBlock.setOnClickListener { showProPanel() }

        // Stale edit snapshots (cache) go; an unfinished scan is offered back to
        // the user instead of silently wiped (it survives in cacheDir).
        val stale = cacheDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("edit_snapshot_")
        }
        stale?.forEach { it.deleteRecursively() }
        scanLauncher.offerUnfinishedScan()

        applyToolbarTitle()
        refreshList()

        // Launched from the globe home's scan button: jump straight into the
        // camera/gallery chooser (cleared so recreation doesn't re-show it).
        if (intent.getBooleanExtra(EXTRA_OPEN_SCANNER, false)) {
            intent.removeExtra(EXTRA_OPEN_SCANNER)
            showScanChooser()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        // AppCompat re-syncs the toolbar title from the manifest label during resume.
        applyToolbarTitle()
        updateGreeting()
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
     * test25 Phase 4: leaving the Shelf back to the globe home plays the
     * return warp (globe enters, shelf exits) instead of the system fade.
     * Only when the globe is the activity below — otherwise plain finish.
     */
    override fun finish() {
        super.finish()
        if (Prefs.globeHomeEnabled(this)) {
            com.scanwhirl.app.ui.SwTransitions.warpBackToGlobe(this)
        }
    }

    /** HUD status line under the app name, when enabled in Settings (T1.5). */
    private fun updateGreeting() {
        if (Prefs.showGreeting(this) && !selectionMode) {
            val docs = adapter.currentList
            binding.greetingText.text = StatusLine.format(
                this,
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                docs.size,
                docs.sumOf { it.pdfFile.length() }
            )
            binding.greetingText.isVisible = true
        } else {
            binding.greetingText.isVisible = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_close)?.isVisible = selectionMode
        menu.findItem(R.id.action_merge)?.isVisible = selectionMode
        menu.findItem(R.id.action_merge)?.isEnabled = selectedIds.size >= 2
        menu.findItem(R.id.action_delete_selected)?.isVisible = selectionMode
        // Pro extras only appear in the Pro UI (Free keeps no dead buttons around).
        menu.findItem(R.id.action_export_zip)?.isVisible = selectionMode && ProGate.isPro(this)
        menu.findItem(R.id.action_export_zip)?.isEnabled =
            selectedIds.isNotEmpty() && ProGate.isPro(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_close -> { setSelectionMode(false); true }
        R.id.action_merge -> { mergeFlow.promptMerge(selectedIds.toList()); true }
        R.id.action_settings -> {
            startActivity(SettingsActivity.intent(this))
            true
        }
        R.id.action_delete_selected -> { confirmDeleteSelected(); true }
        R.id.action_export_zip -> { exportSelectedZip(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun refreshList() {
        lifecycleScope.launch {
            allDocs = withContext(Dispatchers.IO) {
                if (vaultMode) repo.listVaultDocuments() else repo.listDocuments()
            }
            applyList()
        }
    }

    /** In-memory filter + sort over [allDocs]; used by search and sort too. */
    private fun applyList() {
        // Shared with the globe home (test23): name match, Pro OCR fallback,
        // vaulted docs never surface before the PIN.
        val (shown, ocr) = SearchMatcher.match(
            query, allDocs, ProGate.isPro(this), includeVaulted = vaultMode
        ) { repo.ocrDoc(it) }
        ocrMatches = ocr
        val sorted = sortDocs(shown)
        adapter.selectedIds = selectedIds
        adapter.submitList(sorted) {
            adapter.notifyDataSetChanged()
        }
        binding.emptyState.isVisible = sorted.isEmpty() && ocrMatches.isEmpty()
        binding.scanList.isVisible = true
        updateSortLabel()
        if (selectionMode && allDocs.none { it.id in selectedIds }) {
            setSelectionMode(false)
        }
    }

    private fun sortDocs(docs: List<ScanDocument>): List<ScanDocument> =
        when (Prefs.sortMode(this)) {
            Prefs.SORT_NAME -> docs.sortedBy { it.name.lowercase(Locale.getDefault()) }
            Prefs.SORT_MODIFIED -> docs.sortedByDescending {
                maxOf(
                    it.pdfFile.lastModified(),
                    it.pageFiles.maxOfOrNull { p -> p.lastModified() } ?: 0L
                )
            }
            else -> docs.sortedByDescending { it.createdAt }
        }

    /** Sort pill label: mirrors [Prefs.sortMode] so the toolbar always says what's active. */
    private fun updateSortLabel() {
        val label = when (Prefs.sortMode(this)) {
            Prefs.SORT_NAME -> R.string.sort_name
            Prefs.SORT_MODIFIED -> R.string.sort_modified
            else -> R.string.sort_date
        }
        binding.sortLabel.setText(label)
        // Keep the pill's a11y announcement in sync with the visible label.
        binding.btnSort.contentDescription =
            getString(R.string.sort) + ", " + getString(label)
    }

    private fun selectionTitle(): String =
        if (selectionMode) {
            resources.getQuantityString(R.plurals.selected_count, selectedIds.size, selectedIds.size)
        } else {
            getString(R.string.app_name)
        }

    /**
     * Title + badge live together: normal mode shows the custom title row
     * (name + Pro badge) and a null toolbar title; selection mode hides the
     * row and lets the toolbar show "N selected".
     */
    private fun applyToolbarTitle() {
        val title = selectionTitle()
        if (selectionMode) {
            binding.titleBlock.visibility = View.GONE
            binding.titleRow.visibility = View.GONE
            supportActionBar?.setDisplayShowTitleEnabled(true)
            supportActionBar?.title = title
            binding.toolbar.title = title
        } else {
            supportActionBar?.setDisplayShowTitleEnabled(false)
            supportActionBar?.title = null
            binding.toolbar.title = null
            binding.titleBlock.visibility = View.VISIBLE
            binding.titleRow.visibility = View.VISIBLE
        }
        updateProBadge()
        updateGreeting()
    }

    /**
     * Crown badge next to the app name: gold PRO unlocked, dim FREE otherwise.
     * In Free, the Pro Suite card is hidden entirely — the badge is the only
     * entry point to the pitch, and OCR search hint falls back to plain search.
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
            // Free UI: no Pro Suite card, always a collapsed grid when it returns.
            binding.proSuiteGrid.isVisible = false
            binding.proSuiteChevron.rotation = 0f
        }
        binding.proSuiteCard.isVisible = pro
        binding.searchInput.hint =
            getString(if (pro) R.string.search_hint_pro else R.string.search_hint)
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
            if (pro) "UNLOCKED IN TEST" else "ONE-TIME PURCHASE"
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
     * The scan FAB lives inside the 96dp orbital [R.id.fabHost], so both views must be
     * shown or hidden together — UiSmokeTest asserts `fabScan` visibility directly, and a
     * host-only toggle would leave the button on screen (and vice versa).
     */
    private fun setFabVisible(visible: Boolean) {
        binding.fabHost.isVisible = visible
        binding.fabScan.isVisible = visible
    }

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        backCallback.isEnabled = enabled || vaultMode
        if (!enabled) selectedIds.clear()
        adapter.selectionMode = enabled
        adapter.notifyDataSetChanged()
        setFabVisible(!enabled)
        binding.toolsRow.isVisible = !enabled && !vaultMode
        applyToolbarTitle()
        updateGreeting()
        invalidateOptionsMenu()
    }

    /** Expands or collapses the Pro Suite grid right below the core tools. */
    private fun toggleProSuite() {
        if (!ProGate.isPro(this)) {
            showProPanel()
            return
        }
        val expanded = !binding.proSuiteGrid.isVisible
        binding.proSuiteGrid.isVisible = expanded
        // Single chevron asset, spun 180° instead of swapping up/down drawables.
        // One-shot animator: the infinite-animation ambient gate (SwMotion) is Phase 4.
        ObjectAnimator.ofFloat(
            binding.proSuiteChevron, "rotation", if (expanded) 180f else 0f
        ).apply {
            duration = 200L
            // Material3 standard curve (0.4,0,0.2,1) — PathInterpolator lives in the
            // framework jar; androidx' FastOutSlowInInterpolator is not on our classpath.
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            start()
        }
    }

    // ------------------------------------------------------------------
    // Pro Suite: Hidden Vault (4-digit PIN), OCR, Lock/Unlock, Long Image
    // ------------------------------------------------------------------


    internal fun setVaultMode(enabled: Boolean) {
        vaultMode = enabled
        backCallback.isEnabled = selectionMode || vaultMode
        binding.vaultBanner.isVisible = enabled
        binding.toolsRow.isVisible = !enabled && !selectionMode
        // In the vault the FAB becomes "add": move a scan in, or import a PDF.
        setFabVisible(!selectionMode)
        binding.fabScan.setImageResource(if (enabled) R.drawable.ic_add else R.drawable.ic_camera)
        binding.fabScan.contentDescription =
            getString(if (enabled) R.string.vault_add_title else R.string.scan)
        binding.emptyTitle.setText(if (enabled) R.string.vault_empty_title else R.string.empty_title)
        binding.emptySubtitle.setText(if (enabled) R.string.vault_empty_subtitle else R.string.empty_subtitle)
        refreshList()
    }

    // ------------------------------------------------------------------
    // Scan source chooser (camera vs a straight gallery import)
    // ------------------------------------------------------------------

    /**
     * One entry point for new pages. The gallery path bypasses ML Kit's
     * scanner on purpose: the scanner perspective-crops imported photos,
     * which is what used to tilt them. Direct import only fixes EXIF
     * rotation — the picture is otherwise exactly as taken.
     */
    private fun showScanChooser() {
        SwDialog.sheet(this)
            .setTitle(R.string.scan_chooser_title)
            .setItems(
                arrayOf(
                    getString(R.string.scan_chooser_camera),
                    getString(R.string.scan_chooser_gallery)
                )
            ) { _, which ->
                when (which) {
                    0 -> scanLauncher.launchScanner()
                    1 -> pickGalleryImages.launch("image/*")
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
        applyToolbarTitle()
        invalidateOptionsMenu()
    }

    private fun onDocClick(doc: ScanDocument) {
        if (selectionMode) {
            toggleSelection(doc.id)
        } else {
            openViewer(doc)
        }
    }

    private fun onDocView(doc: ScanDocument) {
        if (!selectionMode) openViewer(doc)
    }

    private fun onDocEdit(doc: ScanDocument) {
        if (!selectionMode) openEditor(doc)
    }

    private fun onDocLongClick(doc: ScanDocument) {
        if (!selectionMode) {
            setSelectionMode(true)
            selectedIds.add(doc.id)
            adapter.notifyDataSetChanged()
            applyToolbarTitle()
            invalidateOptionsMenu()
        } else {
            toggleSelection(doc.id)
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
        val popup = PopupMenu(this, binding.btnSort)
        popup.menu.add(0, 0, 0, getString(R.string.sort_name))
        popup.menu.add(0, 1, 1, getString(R.string.sort_date))
        popup.menu.add(0, 2, 2, getString(R.string.sort_modified))
        popup.setOnMenuItemClickListener { item ->
            val mode = when (item.itemId) {
                0 -> Prefs.SORT_NAME
                1 -> Prefs.SORT_DATE
                else -> Prefs.SORT_MODIFIED
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

    private fun confirmDeleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        SwDialog.danger(this)
            .setTitle(R.string.delete_selected_title)
            .setMessage(resources.getQuantityString(R.plurals.delete_selected_message, ids.size, ids.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                ids.forEach { repo.deleteDocument(it) }
                setSelectionMode(false)
                refreshList()
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
        /** Set by the globe home so the scan button lands on the chooser. */
        const val EXTRA_OPEN_SCANNER = "open_scanner"
    }
}
