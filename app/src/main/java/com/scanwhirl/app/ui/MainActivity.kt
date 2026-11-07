package com.scanwhirl.app.ui

import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityMainBinding
import com.scanwhirl.app.databinding.ItemScanBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.tools.FlowUtil
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.ResizeFlow
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SearchMatcher
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.ShareUtils
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
        if (uris.isNotEmpty()) stageAndReview(uris)
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
            if (vaultMode) addToVault() else showScanChooser()
        }
        binding.emptyState.setOnClickListener {
            if (vaultMode) addToVault() else showScanChooser()
        }

        binding.toolResize.setOnClickListener { pickResizeImage.launch("image/*") }
        binding.toolCompress.setOnClickListener { compressFlow.start() }
        binding.toolEdit.setOnClickListener {
            pickEditPdf.launch(arrayOf("application/pdf"))
        }
        binding.toolMerge.setOnClickListener { showMergeChooser() }

        // Expandable Pro Suite on Home: one button that expands Vault, OCR, Lock/Unlock, Long Image.
        binding.btnProSuite.setOnClickListener { toggleProSuite() }
        binding.toolVault.setOnClickListener { openVaultWithPin() }
        binding.toolOcr.setOnClickListener {
            chooseProSource(
                R.string.ocr_title,
                onScan = { doc -> runOcrForScan(doc) },
                onPdf = { uri -> runOcrForPdf(uri) },
                onImage = { uri -> runOcrForImage(uri) }
            )
        }
        binding.toolLockPdf.setOnClickListener { chooseLockSource() }
        binding.toolLongImage.setOnClickListener {
            chooseProSource(
                R.string.btn_long_image,
                onScan = { doc -> runLongImageForScan(doc) },
                onPdf = { uri -> runLongImageForPdf(uri) }
            )
        }
        binding.btnVaultLock.setOnClickListener { setVaultMode(false) }
        binding.btnVaultChangePin.setOnClickListener { promptSetVaultPin(onSuccess = {}) }

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
        offerUnfinishedScan()

        applyToolbarTitle()
        refreshList()

        // Launched from the globe home's scan button: jump straight into the
        // camera/gallery chooser (cleared so recreation doesn't re-show it).
        if (intent.getBooleanExtra(EXTRA_OPEN_SCANNER, false)) {
            intent.removeExtra(EXTRA_OPEN_SCANNER)
            showScanChooser()
        }
    }

    /**
     * A review screen that died mid-edit leaves its staging folder behind.
     * Offer to resume it; deleting is the user's explicit choice.
     */
    private fun offerUnfinishedScan() {
        val pending = cacheDir.listFiles { f ->
            f.isDirectory &&
                f.name.startsWith("pending_") &&
                !f.listFiles { p -> p.extension.equals("jpg", true) }.isNullOrEmpty()
        }?.maxByOrNull { it.lastModified() } ?: return
        val count = pending.listFiles { p -> p.extension.equals("jpg", true) }?.size ?: 0
        SwDialog.card(this)
            .setTitle(R.string.resume_scan_title)
            .setMessage(getString(R.string.resume_scan_msg, count))
            .setPositiveButton(R.string.resume_scan_resume) { _, _ ->
                startActivity(
                    ScanReviewActivity.intent(this, pending.path, null, repo.nextDocumentName())
                )
            }
            .setNegativeButton(R.string.discard) { _, _ -> pending.deleteRecursively() }
            .showSw(isSheet = false)
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
        R.id.action_merge -> { promptMerge(); true }
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

    private fun openVaultWithPin() {
        if (!ProGate.isPro(this)) {
            showProPanel()
            return
        }
        if (!Prefs.hasVaultPin(this)) {
            promptSetVaultPin { setVaultMode(true) }
            return
        }
        val (builder, input) = SwDialog.input(
            this,
            hint = getString(R.string.vault_pin_hint),
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        builder
            .setTitle(R.string.vault_enter_pin_title)
            .setMessage(R.string.vault_enter_pin_msg)
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                if (Prefs.verifyVaultPin(this, pin)) {
                    setVaultMode(true)
                } else {
                    Toast.makeText(this, R.string.vault_pin_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun promptSetVaultPin(onSuccess: () -> Unit) {
        val (builder, input) = SwDialog.input(
            this,
            hint = getString(R.string.vault_pin_hint),
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        builder
            .setTitle(R.string.vault_set_pin_title)
            .setMessage(R.string.vault_set_pin_msg)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                if (pin.length != 4 || !pin.all { it.isDigit() }) {
                    Toast.makeText(this, R.string.vault_pin_invalid, Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setVaultPin(this, pin)
                    Toast.makeText(this, R.string.vault_pin_saved, Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

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

    private fun runOcrForScan(doc: ScanDocument) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    if (!repo.hasOcr(doc.id) || repo.ocrDoc(doc.id).values.all { it.isBlank() }) {
                        repo.buildOcr(doc.id)
                    }
                    repo.ocrDoc(doc.id).entries
                        .sortedBy { it.key }
                        .mapIndexedNotNull { idx, entry ->
                            val clean = entry.value.trim()
                            if (clean.isEmpty()) null else "--- Page ${idx + 1} ---\n$clean"
                        }
                        .joinToString("\n\n")
                }
                progress.dismiss()
                showOcrTextDialog(
                    doc.name,
                    text.ifBlank { getString(R.string.ocr_empty) }
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

    private fun promptLockOrUnlockScan(doc: ScanDocument) {
        if (doc.isLocked) {
            SwDialog.sheet(this)
                .setTitle(doc.name)
                .setItems(
                    arrayOf(
                        getString(R.string.btn_unlock_pdf),
                        getString(R.string.btn_change_pw)
                    )
                ) { _, which ->
                    if (which == 0) {
                        lifecycleScope.launch {
                            val progress = showProgress()
                            try {
                                withContext(Dispatchers.IO) { repo.unlockPdf(doc.id) }
                                progress.dismiss()
                                Toast.makeText(this@MainActivity, R.string.unlock_pdf_done, Toast.LENGTH_LONG).show()
                                refreshList()
                            } catch (e: Exception) {
                                progress.dismiss()
                                Toast.makeText(this@MainActivity, getString(R.string.page_op_failed, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        promptNewPasswordForScan(doc)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showSw(isSheet = true)
        } else {
            promptNewPasswordForScan(doc)
        }
    }

    private fun promptNewPasswordForScan(doc: ScanDocument) {
        val (builder, input) = SwDialog.input(
            this,
            hint = getString(R.string.protect_hint),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        builder
            .setTitle(R.string.protect_title)
            .setMessage(R.string.protect_note)
            .setPositiveButton(R.string.protect_apply) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (pw.isBlank()) {
                    Toast.makeText(this, R.string.protect_hint, Toast.LENGTH_SHORT).show()
                } else {
                    lifecycleScope.launch {
                        val progress = showProgress()
                        try {
                            withContext(Dispatchers.IO) { repo.protectPdf(doc.id, pw) }
                            progress.dismiss()
                            Toast.makeText(this@MainActivity, R.string.protect_done, Toast.LENGTH_LONG).show()
                            refreshList()
                        } catch (e: Exception) {
                            progress.dismiss()
                            Toast.makeText(this@MainActivity, getString(R.string.page_op_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun runLongImageForScan(doc: ScanDocument) {
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
                val dims = ImageTools.imageSize(out.readBytes()) ?: (0 to 0)
                startActivity(
                    ExportResultActivity.intent(
                        this@MainActivity,
                        outputPath = out.path,
                        previewPath = out.path,
                        mime = "image/jpeg",
                        displayName = out.name,
                        beforeBytes = doc.pageFiles.sumOf { it.length() },
                        afterBytes = out.length(),
                        width = dims.first,
                        height = dims.second,
                        title = doc.name,
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
                    0 -> launchScanner()
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

    // ------------------------------------------------------------------
    // Pro Suite: Lock / Unlock for saved scans AND device PDFs
    // ------------------------------------------------------------------

    private fun chooseLockSource() {
        SwDialog.sheet(this)
            .setTitle(R.string.btn_lock_unlock)
            .setItems(
                arrayOf(
                    getString(R.string.source_saved_scan),
                    getString(R.string.lock_source_lock_device),
                    getString(R.string.lock_source_unlock_device)
                )
            ) { _, which ->
                when (which) {
                    0 -> FlowUtil.pickScan(this, R.string.btn_lock_unlock, allDocs) { doc ->
                        promptLockOrUnlockScan(doc)
                    }
                    1 -> {
                        pendingUriAction = { uri -> lockDevicePdf(uri) }
                        pickProPdf.launch(arrayOf("application/pdf"))
                    }
                    else -> {
                        pendingUriAction = { uri -> unlockDevicePdf(uri) }
                        pickProPdf.launch(arrayOf("application/pdf"))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }

    /**
     * Locking a device PDF honestly means importing it (pages become
     * images) and protecting the new ScanWhirl PDF. The confirmation says
     * so before anything is written.
     */
    private fun lockDevicePdf(uri: Uri) {
        SwDialog.card(this)
            .setTitle(R.string.lock_device_title)
            .setMessage(R.string.lock_device_msg)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                val (builder, input) = SwDialog.input(
                    this,
                    hint = getString(R.string.protect_hint),
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                builder
                    .setTitle(R.string.protect_title)
                    .setMessage(R.string.protect_note)
                    .setPositiveButton(R.string.protect_apply) { _, _ ->
                        runLockDevicePdf(uri, input.text?.toString().orEmpty())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showSw(isSheet = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun runLockDevicePdf(uri: Uri, password: String) {
        if (password.isBlank()) {
            Toast.makeText(this, R.string.protect_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val name = FlowUtil.devicePdfName(repo, uri)
                val doc = withContext(Dispatchers.IO) { repo.importPdfAsScan(uri, name) }
                val locked = withContext(Dispatchers.IO) { repo.protectPdf(doc.id, password) }
                progress.dismiss()
                refreshList()
                Toast.makeText(this@MainActivity, R.string.protect_done, Toast.LENGTH_LONG).show()
                startActivity(
                    ExportResultActivity.intent(
                        this@MainActivity,
                        outputPath = locked.pdfFile.path,
                        previewPath = locked.pageFiles.firstOrNull()?.path,
                        mime = "application/pdf",
                        displayName = DownloadSaver.pdfNameFor(locked.name),
                        beforeBytes = before,
                        afterBytes = locked.pdfFile.length(),
                        width = 0,
                        height = 0,
                        title = getString(R.string.lock_device_title),
                        warning = getString(R.string.lock_device_note)
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

    /**
     * Unlock a PDF file from storage. ScanWhirl recognises its own locks by
     * a SHA-256 index of the protected file; any other PDF stays untouched
     * with an honest explanation (we cannot break other people's passwords).
     */
    private fun unlockDevicePdf(uri: Uri) {
        val progress = showProgress(R.string.unlock_pdf_title)
        lifecycleScope.launch {
            try {
                val hash = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { Sha256.of(it) }
                        ?: throw IllegalStateException("could not read the PDF")
                }
                val doc = withContext(Dispatchers.IO) { repo.findLockedDocumentByHash(hash) }
                progress.dismiss()
                if (doc == null) {
                    SwDialog.card(this@MainActivity)
                        .setTitle(R.string.unlock_pdf_not_ours_title)
                        .setMessage(R.string.unlock_pdf_not_ours_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .showSw(isSheet = false)
                    return@launch
                }
                promptDeviceUnlockPassword(doc, uri)
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

    private fun promptDeviceUnlockPassword(doc: ScanDocument, uri: Uri) {
        val (builder, input) = SwDialog.input(
            this,
            hint = getString(R.string.protect_hint),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        builder
            .setTitle(R.string.unlock_pdf_title)
            .setMessage(getString(R.string.unlock_pdf_msg, doc.name))
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (repo.verifyPdfPassword(doc.id, pw)) {
                    runUnlockDevicePdf(doc, uri)
                } else {
                    Toast.makeText(this, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    /** Exports a clean (password-free) copy of the locked scan's pages. */
    private fun runUnlockDevicePdf(doc: ScanDocument, uri: Uri) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val clean = withContext(Dispatchers.IO) { repo.unlockedPreviewPdf(doc.id) }
                progress.dismiss()
                startActivity(
                    ExportResultActivity.intent(
                        this@MainActivity,
                        outputPath = clean.path,
                        previewPath = doc.pageFiles.firstOrNull()?.path,
                        mime = "application/pdf",
                        displayName = DownloadSaver.pdfNameFor("${doc.name} (unlocked)"),
                        beforeBytes = before,
                        afterBytes = clean.length(),
                        width = 0,
                        height = 0,
                        title = getString(R.string.unlock_pdf_title),
                        warning = getString(R.string.unlock_device_note)
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

    // ------------------------------------------------------------------
    // Pro Suite: OCR from a scan, a device PDF, or a gallery image
    // ------------------------------------------------------------------

    private fun runOcrForPdf(uri: Uri) {
        val progress = showProgress(R.string.ocr_running)
        lifecycleScope.launch {
            try {
                // Render + read one page at a time: no whole PDF in memory.
                val parts = withContext(Dispatchers.IO) {
                    PdfPageRenderer.withSeekablePdf(this@MainActivity, uri) { fd ->
                        val count = PdfPageRenderer.pageCount(fd)
                        (0 until count).mapNotNull { i ->
                            val page = PdfPageRenderer.renderPageJpeg(fd, i, 200f, 2400, 92)
                            val text = OcrEngine.recognizeBytes(page).trim()
                            if (text.isEmpty()) null else "--- Page ${i + 1} ---\n$text"
                        }
                    }
                }
                progress.dismiss()
                showOcrTextDialog(
                    FlowUtil.devicePdfName(repo, uri),
                    parts.joinToString("\n\n").ifBlank { getString(R.string.ocr_empty) }
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

    private fun runOcrForImage(uri: Uri) {
        val progress = showProgress(R.string.ocr_running)
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("could not read the image")
                    // EXIF rotation only — the photo is never warped or cropped.
                    OcrEngine.recognizeBytes(ImageTools.normalizePage(bytes)).trim()
                }
                progress.dismiss()
                val title = repo.displayNameOf(uri)?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() } ?: getString(R.string.source_gallery_image)
                showOcrTextDialog(title, text.ifBlank { getString(R.string.ocr_empty) })
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

    /** Shared OCR result dialog: every line, copyable and shareable. */
    private fun showOcrTextDialog(title: String, shown: String) {
        SwDialog.sheet(this)
            .setTitle(title)
            .setMessage(shown)
            .setPositiveButton(R.string.ocr_copy) { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cb?.setPrimaryClip(android.content.ClipData.newPlainText(title, shown))
                Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.share_pdf) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, shown)
                }
                startActivity(Intent.createChooser(send, getString(R.string.share_pdf)))
            }
            .setNegativeButton(R.string.close, null)
            .showSw(isSheet = true)
    }

    // ------------------------------------------------------------------
    // Pro Suite: Long Image from a scan or a device PDF
    // ------------------------------------------------------------------

    private fun runLongImageForPdf(uri: Uri) {
        val progress = showProgress()
        val tempDir = File(cacheDir, "longpdf_${System.nanoTime()}")
        lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    tempDir.mkdirs()
                    val pages = PdfPageRenderer.withSeekablePdf(this@MainActivity, uri) { fd ->
                        val count = PdfPageRenderer.pageCount(fd)
                        (0 until count).map { i ->
                            val file = File(
                                tempDir, String.format(Locale.ROOT, "page_%03d.jpg", i + 1)
                            )
                            file.writeBytes(PdfPageRenderer.renderPageJpeg(fd, i, 150f, 2048, 90))
                            file
                        }
                    }
                    val shareDir = File(cacheDir, "documents/share").apply { mkdirs() }
                    val file = File(shareDir, ShareUtils.sanitizeName(FlowUtil.devicePdfName(repo, uri)) + " - long.jpg")
                    LongImage.stitch(pages, file)
                    file
                }
                progress.dismiss()
                val dims = ImageTools.imageSize(out.readBytes()) ?: (0 to 0)
                val before = withContext(Dispatchers.IO) {
                    contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                startActivity(
                    ExportResultActivity.intent(
                        this@MainActivity,
                        outputPath = out.path,
                        previewPath = out.path,
                        mime = "image/jpeg",
                        displayName = out.name,
                        beforeBytes = before,
                        afterBytes = out.length(),
                        width = dims.first,
                        height = dims.second,
                        title = FlowUtil.devicePdfName(repo, uri),
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
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------
    // Hidden Vault: add from FAB (saved scan or device PDF)
    // ------------------------------------------------------------------

    private fun addToVault() {
        if (!ProGate.isPro(this)) {
            showProPanel()
            return
        }
        SwDialog.sheet(this)
            .setTitle(R.string.vault_add_title)
            .setItems(
                arrayOf(
                    getString(R.string.vault_add_scan),
                    getString(R.string.vault_add_pdf)
                )
            ) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch {
                        // Vault mode lists vault contents; moving in needs the home list.
                        val docs = withContext(Dispatchers.IO) { repo.listDocuments() }
                        if (docs.isEmpty()) {
                            Toast.makeText(this@MainActivity, R.string.empty_title, Toast.LENGTH_SHORT).show()
                        } else {
                            FlowUtil.pickScan(this@MainActivity, R.string.vault_add_scan, docs) { doc ->
                                moveDocToVault(doc)
                            }
                        }
                    }
                } else {
                    pendingUriAction = { uri -> importPdfToVault(uri) }
                    pickProPdf.launch(arrayOf("application/pdf"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }

    private fun moveDocToVault(doc: ScanDocument) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.setVaulted(doc.id, true) }
                progress.dismiss()
                Toast.makeText(this@MainActivity, R.string.vault_moved_in, Toast.LENGTH_SHORT).show()
                refreshList()
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

    private fun importPdfToVault(uri: Uri) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val name = FlowUtil.devicePdfName(repo, uri)
                withContext(Dispatchers.IO) { repo.importPdfIntoVault(uri, name) }
                progress.dismiss()
                Toast.makeText(this@MainActivity, R.string.vault_imported, Toast.LENGTH_SHORT).show()
                refreshList()
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

    /** Merge tool: choose saved scans or device PDFs. */
    private fun showMergeChooser() {
        SwDialog.sheet(this)
            .setTitle(R.string.merge_chooser_title)
            .setItems(
                arrayOf(
                    getString(R.string.merge_source_scans),
                    getString(R.string.merge_source_device)
                )
            ) { _, which ->
                if (which == 0) showMergePicker()
                else startActivity(MergePdfActivity.intent(this))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
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

    /** Merge tool: tick 2+ scans, then name the merged document. */
    private fun showMergePicker() {
        val docs = allDocs
        if (docs.size < 2) {
            Toast.makeText(this, R.string.merge_needs_two, Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(docs.size)
        val labels = docs.map { it.name }.toTypedArray()
        SwDialog.sheet(this)
            .setTitle(R.string.merge_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.merge) { _, _ ->
                val ids = docs.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.size < 2) {
                    Toast.makeText(this, R.string.merge_needs_two, Toast.LENGTH_SHORT).show()
                } else {
                    promptMergeName(ids)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }

    private fun promptMergeName(ids: List<String>) {
        val (builder, input) = SwDialog.input(this, hint = getString(R.string.merge_name_hint))
        builder
            .setTitle(R.string.merge_title)
            .setPositiveButton(R.string.save) { _, _ ->
                doMerge(ids, input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun promptMerge() {
        if (selectedIds.size < 2) {
            Toast.makeText(this, R.string.merge_needs_two, Toast.LENGTH_SHORT).show()
            return
        }
        val (builder, input) = SwDialog.input(this, hint = getString(R.string.merge_name_hint))
        builder
            .setTitle(R.string.merge_title)
            .setPositiveButton(R.string.save) { _, _ ->
                doMerge(selectedIds.toList(), input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun doMerge(ids: List<String>, name: String?) {
        lifecycleScope.launch {
            try {
                val merged = repo.mergeDocuments(ids, name)
                setSelectionMode(false)
                refreshList()
                openViewer(merged)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Merge failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        SwDialog.card(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(android.R.string.ok, null)
            .showSw(isSheet = false)
    }

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scanResult?.pages.orEmpty().mapNotNull { it.imageUri }
            if (uris.isEmpty()) {
                Toast.makeText(this, R.string.scan_no_pages, Toast.LENGTH_SHORT).show()
            } else {
                stageAndReview(uris)
            }
        }

    /**
     * Opens the Google scanner, mode dependent on auto-enhance setting.
     * Gallery import inside the scanner is disabled on purpose: it runs
     * perspective crop and tilted photos came back tilted. Photos are
     * imported directly from the scan chooser instead (EXIF only).
     */
    private fun launchScanner() {
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

    /** Copies the scanned page URIs into a staging folder, then opens the review screen. */
    private fun stageAndReview(uris: List<Uri>) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    val staging = File(cacheDir, "pending_${System.currentTimeMillis()}")
                    staging.mkdirs()
                    uris.forEachIndexed { index, uri ->
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("could not read a scanned page")
                        if (bytes.isEmpty()) throw IllegalStateException("a scanned page was empty")
                        File(staging, String.format(Locale.ROOT, "page_%03d.jpg", index + 1))
                            // EXIF rotation only: gallery photos are never cropped or tilted.
                            .writeBytes(ImageTools.normalizePage(bytes))
                    }
                    staging
                }
                startActivity(
                    ScanReviewActivity.intent(
                        this@MainActivity,
                        dir.path,
                        null,
                        repo.nextDocumentName()
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.stage_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
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

class ScanListAdapter(
    private val onClick: (ScanDocument) -> Unit,
    private val onView: (ScanDocument) -> Unit,
    private val onEdit: (ScanDocument) -> Unit,
    private val onLongClick: (ScanDocument) -> Unit
) : ListAdapter<ScanDocument, ScanListAdapter.VH>(DIFF) {

    var selectionMode: Boolean = false
    var selectedIds: Set<String> = emptySet()

    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemScanBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(doc: ScanDocument) {
            binding.scanName.text = doc.name
            binding.lockBadge.isVisible = doc.isLocked
            binding.pageBadge.text = doc.pageCount.toString()
            binding.pageBadge.isVisible = doc.pageCount > 0
            val sizeBytes = if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) {
                doc.pdfFile.length()
            } else {
                doc.pageFiles.sumOf { it.length() }
            }
            val whenDate = Date(doc.createdAt)
            // Compact single line: "3 pages · 243 KB · 6 Nov".
            // Non-breaking spaces keep each unit glued to its number.
            val pagesText = binding.root.context.resources
                .getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
            binding.scanMeta.text = "$pagesText · " +
                StatusLine.formatBytes(sizeBytes).replace(' ', '\u00A0') + " · " +
                dateFmt.format(whenDate).replace(' ', '\u00A0')
            val first = doc.pageFiles.firstOrNull()
            if (first != null) {
                // Background decode + cache; the main thread never touches JPEGs.
                binding.scanThumb.setImageResource(R.drawable.ic_document)
                com.scanwhirl.app.ui.pagegrid.PageThumbLoader.load(first, 320,
                    com.scanwhirl.app.util.ScanFilter.ORIGINAL, binding.scanThumb)
            } else {
                binding.scanThumb.setImageResource(R.drawable.ic_document)
            }

            val selected = selectionMode && doc.id in selectedIds
            binding.checkSelect.isVisible = selectionMode
            binding.checkSelect.isChecked = selected
            // View / edit are the two explicit actions while not selecting.
            binding.btnView.isVisible = !selectionMode
            binding.btnEdit.isVisible = !selectionMode
            binding.btnView.setOnClickListener { onView(doc) }
            binding.btnEdit.setOnClickListener { onEdit(doc) }
            val density = binding.root.resources.displayMetrics.density
            binding.root.strokeWidth = if (selected) (2 * density).toInt() else (1 * density).toInt()
            binding.root.strokeColor =
                ContextCompat.getColor(binding.root.context, if (selected) R.color.cyan_primary else R.color.cyan_border_idle)

            // Category accent rail + label chip (D3 palette via CategoryStyle).
            val catColor = CategoryStyle.color(doc.category.orEmpty())
            binding.catRail.backgroundTintList = ColorStateList.valueOf(catColor)
            binding.catChip.text = "● ${CategoryRules.label(doc.category.orEmpty()).uppercase()}"
            binding.catChip.setTextColor(catColor)

            binding.root.setOnClickListener { onClick(doc) }
            binding.root.setOnLongClickListener {
                onLongClick(doc)
                true
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScanDocument>() {
            override fun areItemsTheSame(a: ScanDocument, b: ScanDocument) = a.id == b.id
            override fun areContentsTheSame(a: ScanDocument, b: ScanDocument) = a == b
        }
    }
}
