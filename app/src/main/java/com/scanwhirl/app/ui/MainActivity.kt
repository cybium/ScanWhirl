package com.scanwhirl.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
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
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
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
import com.scanwhirl.app.databinding.DialogCompressBinding
import com.scanwhirl.app.databinding.DialogResizeBinding
import com.scanwhirl.app.databinding.ItemScanBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Greeting
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.ZipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter by lazy {
        ScanListAdapter(::onDocClick, ::onDocView, ::onDocEdit, ::onDocLongClick)
    }
    private val repo by lazy { (application as ScanWhirlApp).repository }

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
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_pdf_note_title)
            .setMessage(R.string.pdf_rasterize_note)
            .setPositiveButton(R.string.continue_label) { _, _ -> startEditPdf(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
    ) { uri -> uri?.let { startResize(it) } }

    private val pickCompressPdf = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { startCompressFromFile(it) } }

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
        binding.toolCompress.setOnClickListener { startCompress() }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.resume_scan_title)
            .setMessage(getString(R.string.resume_scan_msg, count))
            .setPositiveButton(R.string.resume_scan_resume) { _, _ ->
                startActivity(
                    ScanReviewActivity.intent(this, pending.path, null, repo.nextDocumentName())
                )
            }
            .setNegativeButton(R.string.discard) { _, _ -> pending.deleteRecursively() }
            .show()
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
            binding.root.background = background
        }
    }

    /** Time-of-day hello under the app name, when enabled in Settings. */
    private fun updateGreeting() {
        if (Prefs.showGreeting(this) && !selectionMode) {
            binding.greetingText.text =
                Greeting.forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
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

    private fun refreshList() {
        lifecycleScope.launch {
            allDocs = withContext(Dispatchers.IO) {
                if (vaultMode) repo.listVaultDocuments() else repo.listDocuments()
            }
            applyList()
        }
    }

    /** In-memory filter + sort over [allDocs]; used by search and sort too. */
    private fun applyList() {
        val byName = allDocs.filter { it.name.contains(query, true) }
        // Pro: when the name search misses, fall back to OCR text search.
        ocrMatches = if (!ProGate.isPro(this)) emptyList()
        else if (query.isBlank() || byName.isNotEmpty()) emptyList()
        else repoOcrMatches(query)
        val shown = if (byName.isEmpty() && ocrMatches.isNotEmpty()) ocrMatches else byName
        val sorted = sortDocs(shown)
        adapter.selectedIds = selectedIds
        adapter.submitList(sorted) {
            adapter.notifyDataSetChanged()
        }
        binding.emptyState.isVisible = sorted.isEmpty() && ocrMatches.isEmpty()
        binding.scanList.isVisible = true
        if (selectionMode && allDocs.none { it.id in selectedIds }) {
            setSelectionMode(false)
        }
    }

    /** Pro OCR search: documents whose scanned text matches [query]. */
    private fun repoOcrMatches(query: String): List<ScanDocument> =
        allDocs.filter { doc ->
            repo.ocrDoc(doc.id).values.any { it.contains(query, ignoreCase = true) }
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
            binding.proSuiteChevron.setImageResource(R.drawable.ic_chevron_down)
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
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_pro_title))
            .setMessage(getString(if (pro) R.string.pro_panel_home else R.string.pro_panel_free))
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
        builder.show()
    }

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        backCallback.isEnabled = enabled || vaultMode
        if (!enabled) selectedIds.clear()
        adapter.selectionMode = enabled
        adapter.notifyDataSetChanged()
        binding.fabScan.isVisible = !enabled
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
        binding.proSuiteChevron.setImageResource(
            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
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
        val input = EditText(this).apply {
            hint = getString(R.string.vault_pin_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_enter_pin_title)
            .setMessage(R.string.vault_enter_pin_msg)
            .setView(input)
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                if (Prefs.verifyVaultPin(this, pin)) {
                    setVaultMode(true)
                } else {
                    Toast.makeText(this, R.string.vault_pin_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptSetVaultPin(onSuccess: () -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.vault_pin_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vault_set_pin_title)
            .setMessage(R.string.vault_set_pin_msg)
            .setView(input)
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
            .show()
    }

    internal fun setVaultMode(enabled: Boolean) {
        vaultMode = enabled
        backCallback.isEnabled = selectionMode || vaultMode
        binding.vaultBanner.isVisible = enabled
        binding.toolsRow.isVisible = !enabled && !selectionMode
        // In the vault the FAB becomes "add": move a scan in, or import a PDF.
        binding.fabScan.isVisible = !selectionMode
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
            AlertDialog.Builder(this)
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
                .show()
        } else {
            promptNewPasswordForScan(doc)
        }
    }

    private fun promptNewPasswordForScan(doc: ScanDocument) {
        val input = EditText(this).apply {
            hint = getString(R.string.protect_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.protect_title)
            .setMessage(R.string.protect_note)
            .setView(input)
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
            .show()
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
        AlertDialog.Builder(this)
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
            .show()
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
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> pickScan(titleRes) { doc -> onScan(doc) }
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
            .show()
    }

    // ------------------------------------------------------------------
    // Pro Suite: Lock / Unlock for saved scans AND device PDFs
    // ------------------------------------------------------------------

    private fun chooseLockSource() {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_lock_unlock)
            .setItems(
                arrayOf(
                    getString(R.string.source_saved_scan),
                    getString(R.string.lock_source_lock_device),
                    getString(R.string.lock_source_unlock_device)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickScan(R.string.btn_lock_unlock) { doc -> promptLockOrUnlockScan(doc) }
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
            .show()
    }

    /**
     * Locking a device PDF honestly means importing it (pages become
     * images) and protecting the new ScanWhirl PDF. The confirmation says
     * so before anything is written.
     */
    private fun lockDevicePdf(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_device_title)
            .setMessage(R.string.lock_device_msg)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                val input = EditText(this).apply {
                    hint = getString(R.string.protect_hint)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.protect_title)
                    .setMessage(R.string.protect_note)
                    .setView(input)
                    .setPositiveButton(R.string.protect_apply) { _, _ ->
                        runLockDevicePdf(uri, input.text?.toString().orEmpty())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                val name = devicePdfName(uri)
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.unlock_pdf_not_ours_title)
                        .setMessage(R.string.unlock_pdf_not_ours_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
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
        val input = EditText(this).apply {
            hint = getString(R.string.protect_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.unlock_pdf_title)
            .setMessage(getString(R.string.unlock_pdf_msg, doc.name))
            .setView(input)
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (repo.verifyPdfPassword(doc.id, pw)) {
                    runUnlockDevicePdf(doc, uri)
                } else {
                    Toast.makeText(this, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                    devicePdfName(uri),
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
        AlertDialog.Builder(this)
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
            .show()
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
                    val file = File(shareDir, ShareUtils.sanitizeName(devicePdfName(uri)) + " - long.jpg")
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
                        title = devicePdfName(uri),
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
        AlertDialog.Builder(this)
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
                            pickScan(R.string.vault_add_scan, docs) { doc -> moveDocToVault(doc) }
                        }
                    }
                } else {
                    pendingUriAction = { uri -> importPdfToVault(uri) }
                    pickProPdf.launch(arrayOf("application/pdf"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                val name = devicePdfName(uri)
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
        AlertDialog.Builder(this)
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
            .show()
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
        AlertDialog.Builder(this)
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
            .show()
    }

    private fun promptMergeName(ids: List<String>) {
        val input = EditText(this).apply { hint = getString(R.string.merge_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.merge_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                doMerge(ids, input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptMerge() {
        if (selectedIds.size < 2) {
            Toast.makeText(this, R.string.merge_needs_two, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { hint = getString(R.string.merge_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.merge_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                doMerge(selectedIds.toList(), input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_selected_title)
            .setMessage(resources.getQuantityString(R.plurals.delete_selected_message, ids.size, ids.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                ids.forEach { repo.deleteDocument(it) }
                setSelectionMode(false)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Tool: resize image
    // ------------------------------------------------------------------

    private fun startResize(uri: Uri) {
        val bytes = readUriBytes(uri) ?: run {
            Toast.makeText(this, getString(R.string.resize_failed, "unreadable image"), Toast.LENGTH_LONG).show()
            return
        }
        val size = ImageTools.imageSize(bytes) ?: run {
            Toast.makeText(this, getString(R.string.resize_failed, "not an image"), Toast.LENGTH_LONG).show()
            return
        }
        val name = repo.displayNameOf(uri) ?: "image"
        showResizeDialog(bytes, size.first, size.second, name)
    }

    private fun showResizeDialog(bytes: ByteArray, origW: Int, origH: Int, name: String) {
        val dialogBinding = DialogResizeBinding.inflate(layoutInflater)
        dialogBinding.fileInfo.text = getString(
            R.string.resize_file_info, name, formatBytes(bytes.size.toLong()), origW, origH
        )

        val presetValues = mapOf(
            R.id.preset50 to "50 KB",
            R.id.preset100 to "100 KB",
            R.id.preset200 to "200 KB",
            R.id.preset500 to "500 KB"
        )

        // Preset chip fills the size field; typing a custom size deselects the chip.
        var suppressPreset = false
        dialogBinding.presetGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = presetValues[checkedId] ?: return@setOnCheckedChangeListener
            suppressPreset = true
            dialogBinding.editMaxSize.setText(value)
            suppressPreset = false
        }
        dialogBinding.editMaxSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressPreset) return
                val checkedId = dialogBinding.presetGroup.checkedChipId
                val text = s?.toString()?.trim().orEmpty()
                if (checkedId != -1 && presetValues[checkedId]?.equals(text, true) != true) {
                    dialogBinding.presetGroup.clearCheck()
                }
            }
        })

        dialogBinding.checkAdvanced.setOnCheckedChangeListener { _, checked ->
            dialogBinding.advancedBox.isVisible = checked
        }

        var suppress = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppress || !dialogBinding.checkRatio.isChecked) return
                val editingHeight = s === dialogBinding.editHeight.text
                val value = s?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return
                suppress = true
                if (editingHeight) {
                    val w = (value.toFloat() * origW / origH).roundToInt().coerceIn(1, 12000)
                    dialogBinding.editWidth.setText(intText(w))
                } else {
                    val h = (value.toFloat() * origH / origW).roundToInt().coerceIn(1, 12000)
                    dialogBinding.editHeight.setText(intText(h))
                }
                suppress = false
            }
        }
        dialogBinding.editWidth.addTextChangedListener(watcher)
        dialogBinding.editHeight.addTextChangedListener(watcher)

        dialogBinding.btnKeepOriginal.setOnClickListener {
            suppress = true
            dialogBinding.editWidth.text?.clear()
            dialogBinding.editHeight.text?.clear()
            suppress = false
            dialogBinding.editMaxSize.text?.clear()
            dialogBinding.presetGroup.clearCheck()
            dialogBinding.checkAdvanced.isChecked = false
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.resize_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.resize_action) { _, _ ->
                val ratio = dialogBinding.checkRatio.isChecked
                val ew = dialogBinding.editWidth.text.toString().toIntOrNull()
                val eh = dialogBinding.editHeight.text.toString().toIntOrNull()
                val targetText = dialogBinding.editMaxSize.text?.toString()

                val target = if (targetText.isNullOrBlank()) null else ImageTools.parseSizeInput(targetText)
                if (!targetText.isNullOrBlank() && target == null) {
                    Toast.makeText(this, R.string.resize_invalid_target, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val (w, h) = when {
                    ew == null && eh == null -> origW to origH
                    ratio && ew == null -> (derive(eh!!, origH, origW)) to eh
                    ratio -> ew to derive(ew!!, origW, origH)
                    else -> (ew ?: origW) to (eh ?: origH)
                }
                if (w !in 1..12000 || h !in 1..12000) {
                    Toast.makeText(this, R.string.resize_invalid_size, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // DPI is intentionally not exposed: a DPI tag alone does not change
                // pixels, so only real pixel dimensions + target size are offered,
                // and no DPI metadata is stamped the user didn't ask for.
                doResize(
                    bytes, name,
                    ImageTools.ResizeOptions(
                        width = w, height = h, keepRatio = ratio,
                        maxBytes = target, keepPng = false, dpi = 0
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun derive(from: Int, fromOrig: Int, toOrig: Int): Int =
        if (fromOrig > 0) (from.toDouble() * toOrig / fromOrig).roundToInt() else from

    private fun doResize(bytes: ByteArray, name: String, opts: ImageTools.ResizeOptions) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ImageTools.resize(bytes, opts) }
                val dir = File(filesDir, "exports").apply { mkdirs() }
                val base = sanitizeFileName(name).substringBeforeLast('.').ifBlank { "image" }
                val ext = if (result.isPng) "png" else "jpg"
                val fileName = "${base}_${result.width}x${result.height}.$ext"
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) { file.writeBytes(result.bytes) }

                // Show the real result (preview + before/after) instead of a toast.
                startActivity(
                    ExportResultActivity.intent(
                        this@MainActivity,
                        outputPath = file.path,
                        previewPath = file.path,
                        mime = if (result.isPng) "image/png" else "image/jpeg",
                        displayName = fileName,
                        beforeBytes = bytes.size.toLong(),
                        afterBytes = result.bytes.size.toLong(),
                        width = result.width,
                        height = result.height,
                        title = name,
                        warning = null
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.resize_failed, e.message), Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    // ------------------------------------------------------------------
    // Tool: compress PDF
    // ------------------------------------------------------------------

    private data class Level(
        val nameRes: Int,
        val quality: Int,
        val maxDim: Int?,
        val dpi: Float
    )

    private val levels = listOf(
        Level(R.string.compress_level_low, 80, null, 200f),
        Level(R.string.compress_level_medium, 65, 2000, 150f),
        Level(R.string.compress_level_high, 50, 1600, 130f),
        Level(R.string.compress_level_ultra, 38, 1200, 110f)
    )

    private fun startCompress() {
        // No saved scans yet: the source is necessarily an external file.
        if (allDocs.isEmpty()) {
            pickCompressPdf.launch("application/pdf")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compress_title)
            .setItems(arrayOf(
                getString(R.string.compress_source_scan),
                getString(R.string.compress_source_file)
            )) { _, which ->
                when (which) {
                    0 -> pickScan(R.string.compress_pick_scan) { doc -> estimateForScan(doc) }
                    1 -> pickCompressPdf.launch("application/pdf")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Name for an imported device PDF: real file name when we can read it. */
    private fun devicePdfName(uri: Uri): String =
        repo.displayNameOf(uri)
            ?.removeSuffix(".pdf")?.removeSuffix(".PDF")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: "Imported PDF"

    private fun pickScan(
        titleRes: Int,
        docs: List<ScanDocument> = allDocs,
        onPicked: (ScanDocument) -> Unit
    ) {
        if (docs.isEmpty()) {
            Toast.makeText(this, R.string.empty_title, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(docs.map { it.name }.toTypedArray()) { _, which ->
                onPicked(docs[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Compress sample pages at each level, then show the choice with sizes. */
    private fun estimateForScan(doc: ScanDocument) {
        val progress = showProgress(R.string.compress_estimating)
        lifecycleScope.launch {
            try {
                val estimates = withContext(Dispatchers.IO) {
                    val sampleFiles = listOfNotNull(
                        doc.pageFiles.firstOrNull(),
                        doc.pageFiles.getOrNull(doc.pageFiles.size / 2)
                    ).distinct()
                    val samples = sampleFiles.map { it.readBytes() }
                    levels.map { level ->
                        val avg = samples.map {
                            ImageTools.recompressJpeg(it, level.quality, level.maxDim).size.toLong()
                        }.average()
                        (avg * doc.pageCount * 1.02 + 1024).toLong()
                    }
                }
                progress.dismiss()
                pickLevel(estimates,
                    onChosen = { level -> runCompressScan(doc, level) },
                    onTarget = { target -> runCompressScanToTarget(doc, target) })
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@MainActivity, getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Render page 1 of an external PDF at each level, extrapolate, then choose. */
    private fun estimateForPdfFile(uri: Uri) {
        val progress = showProgress(R.string.compress_estimating)
        lifecycleScope.launch {
            try {
                val estimates = withContext(Dispatchers.IO) {
                    // One seekable copy: SAF pipes cannot be rendered directly.
                    PdfPageRenderer.withSeekablePdf(this@MainActivity, uri) { fd ->
                        val count = PdfPageRenderer.pageCount(fd)
                        levels.map { level ->
                            val first = PdfPageRenderer.renderPageJpeg(
                                fd, 0, level.dpi, level.maxDim ?: 4096, level.quality
                            )
                            (first.size.toDouble() * count * 1.02 + 1024).toLong()
                        }
                    }
                }
                progress.dismiss()
                pickLevel(estimates,
                    onChosen = { level -> runCompressFile(uri, level) },
                    onTarget = { target -> runCompressFileToTarget(uri, target) })
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@MainActivity, getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Level picker with size estimates, plus an exact target-size field. */
    private fun pickLevel(estimates: List<Long>, onChosen: (Level) -> Unit, onTarget: (Long) -> Unit) {
        val dialogBinding = DialogCompressBinding.inflate(layoutInflater)
        levels.forEachIndexed { i, level ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(
                    R.string.compress_est_item,
                    getString(level.nameRes),
                    formatBytes(estimates.getOrElse(i) { 0L })
                )
            }
            dialogBinding.levelGroup.addView(button)
        }
        (dialogBinding.levelGroup.getChildAt(1) as? RadioButton)?.isChecked = true

        AlertDialog.Builder(this)
            .setTitle(R.string.compress_level_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targetText = dialogBinding.editCompressTarget.text?.toString()
                if (!targetText.isNullOrBlank()) {
                    val target = ImageTools.parseSizeInput(targetText)
                    if (target == null) {
                        Toast.makeText(this, R.string.resize_invalid_target, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    onTarget(target)
                    return@setPositiveButton
                }
                val checked = dialogBinding.levelGroup.checkedRadioButtonId
                val index = dialogBinding.levelGroup.indexOfChild(dialogBinding.levelGroup.findViewById(checked))
                onChosen(levels.getOrElse(if (index >= 0) index else 1) { levels[1] })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runCompressScan(doc: ScanDocument, level: Level) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val before = if (doc.pdfFile.exists()) doc.pdfFile.length() else 0L
                val out = repo.compressScan(doc.id, level.quality, level.maxDim)
                val after = if (out.pdfFile.exists()) out.pdfFile.length() else 0L
                refreshList()
                showCompressResult(out, before, after)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    /** Compresses a saved scan so the finished PDF fits the exact target size. */
    private fun runCompressScanToTarget(doc: ScanDocument, target: Long) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val before = if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) {
                    doc.pdfFile.length()
                } else {
                    doc.pageFiles.sumOf { it.length() }
                }
                val pages = withContext(Dispatchers.IO) { fitFilesToTotal(doc.pageFiles, target) }
                val out = repo.createDocument("${doc.name} (compressed)", pages)
                refreshList()
                showCompressResult(out, before, out.pdfFile.length(),
                    if (out.pdfFile.length() > target) getString(R.string.compress_target_missed, formatBytes(target)) else null)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    /** Opens the real result screen (preview + actual before/after + save/share). */
    private fun showCompressResult(out: ScanDocument, before: Long, after: Long, extraWarning: String? = null) {
        val warning = when {
            extraWarning != null -> extraWarning
            before > 0 && after > before -> getString(R.string.compress_grew_warning)
            else -> null
        }
        startActivity(
            ExportResultActivity.intent(
                this,
                outputPath = out.pdfFile.path,
                previewPath = out.pageFiles.firstOrNull()?.path,
                mime = "application/pdf",
                displayName = DownloadSaver.pdfNameFor(out.name),
                beforeBytes = before,
                afterBytes = after,
                width = 0,
                height = 0,
                title = out.name,
                warning = warning
            )
        )
    }

    private fun startCompressFromFile(uri: Uri) {
        estimateForPdfFile(uri)
    }

    private fun runCompressFile(uri: Uri, level: Level) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val name = devicePdfName(uri)
                val out = repo.importPdfAsScan(uri, name, level.dpi, level.maxDim ?: 4096, level.quality)
                val after = if (out.pdfFile.exists()) out.pdfFile.length() else 0L
                refreshList()
                showCompressResult(out, before, after)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    /** Renders an external PDF and re-fits its pages so the new scan hits the exact target. */
    private fun runCompressFileToTarget(uri: Uri, target: Long) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val baseName = devicePdfName(uri)
                val pages = withContext(Dispatchers.IO) {
                    val rendered = PdfPageRenderer.withSeekablePdf(this@MainActivity, uri) { fd ->
                        // One page at a time: never hold a whole PDF's JPEGs in memory.
                        val out = mutableListOf<ByteArray>()
                        val count = PdfPageRenderer.pageCount(fd)
                        for (i in 0 until count) {
                            PdfPageRenderer.renderPageJpeg(fd, i, 150f, 2048, 85).let { out.add(it) }
                        }
                        out
                    }
                    fitJpegsToTotal(rendered, target)
                }
                val out = repo.createDocument("$baseName (compressed)", pages)
                refreshList()
                showCompressResult(out, before, out.pdfFile.length(),
                    if (out.pdfFile.length() > target) getString(R.string.compress_target_missed, formatBytes(target)) else null)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    // ------------------------------------------------------------------
    // Exact-target compression helpers (PDF ≈ Σ page JPEGs + ~600 B/page)
    // ------------------------------------------------------------------

    private fun fitFilesToTotal(files: List<File>, target: Long): List<ByteArray> {
        if (files.isEmpty()) throw IllegalStateException("No pages to compress")
        val budget = (target - (files.size * 600L + 1000L)).coerceAtLeast(2048L)
        val sizes = files.map { it.length().coerceAtLeast(1L) }
        val sum = sizes.sum().coerceAtLeast(1L)
        return files.mapIndexed { i, file ->
            fitToShare(file.readBytes(), (budget * sizes[i] / sum).coerceAtLeast(1024L))
        }
    }

    private fun fitJpegsToTotal(pages: List<ByteArray>, target: Long): List<ByteArray> {
        if (pages.isEmpty()) throw IllegalStateException("No pages to compress")
        val budget = (target - (pages.size * 600L + 1000L)).coerceAtLeast(2048L)
        val sum = pages.sumOf { it.size.toLong() }.coerceAtLeast(1L)
        return pages.map { orig ->
            fitToShare(orig, (budget * orig.size / sum).coerceAtLeast(1024L))
        }
    }

    /** Never grows a page: keeps the original bytes when they already fit the share. */
    private fun fitToShare(orig: ByteArray, share: Long): ByteArray {
        if (orig.size.toLong() <= share) return orig
        val bmp = try {
            ImageTools.decodeSampled(orig, 3000)
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("A page could not be decoded")
        val fitted = ImageTools.compressToFit(bmp, share).bytes
        bmp.recycle()
        return if (fitted.size >= orig.size) orig else fitted
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private fun showProgress(titleRes: Int = R.string.progress_title): AlertDialog {
        val bar = ProgressBar(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        return AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(bar)
            .setCancelable(false)
            .show()
    }

    private fun readUriBytes(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun intText(value: Int): String = String.format(Locale.ROOT, "%d", value)

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('_')
        return cleaned.ifBlank { "image" }.take(60)
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        AlertDialog.Builder(this)
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
            .show()
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
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
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
                formatBytes(sizeBytes).replace(' ', '\u00A0') + " · " +
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
            binding.root.strokeWidth = if (selected) (2 * density).toInt() else 0
            binding.root.strokeColor =
                ContextCompat.getColor(binding.root.context, R.color.sf_primary)

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
