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
import com.scanwhirl.app.util.Greeting
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.Prefs
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

    /** Current search query (applies to the saved-scans list). */
    private var query = ""

    /** Edit tool: pick one device PDF and open it as editable pages. */
    private val pickEditPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startEditPdf(uri)
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setSelectionMode(false)
        }
    }

    private val pickResizeImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { startResize(it) } }

    private val pickCompressPdf = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { startCompressFromFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.scanList.layoutManager = LinearLayoutManager(this)
        binding.scanList.adapter = adapter

        binding.fabScan.setOnClickListener { launchScanner() }
        binding.emptyState.setOnClickListener { launchScanner() }

        binding.toolResize.setOnClickListener { pickResizeImage.launch("image/*") }
        binding.toolCompress.setOnClickListener { startCompress() }
        binding.toolEdit.setOnClickListener {
            pickEditPdf.launch(arrayOf("application/pdf"))
        }
        binding.toolMerge.setOnClickListener { showMergeChooser() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refreshList()
            }
        })
        binding.btnSort.setOnClickListener { showSortMenu() }

        // Staging folders / edit snapshots from an earlier process death are stale.
        cacheDir.listFiles { f ->
            f.isDirectory &&
                (f.name.startsWith("pending_") || f.name.startsWith("edit_snapshot_"))
        }?.forEach { it.deleteRecursively() }

        refreshList()
        updateGreeting()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        // AppCompat re-syncs the toolbar title from the manifest label during resume.
        binding.toolbar.title = selectionTitle()
        updateGreeting()
    }

    /** Time-of-day hello under the app name, when enabled in Settings. */
    private fun updateGreeting() {
        binding.toolbar.subtitle =
            if (Prefs.showGreeting(this) && !selectionMode) {
                Greeting.forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            } else {
                null
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
        else -> super.onOptionsItemSelected(item)
    }

    private fun refreshList() {
        val docs = repo.listDocuments()
        val filtered = docs.filter { query.isBlank() || it.name.contains(query, true) }
        val sorted = sortDocs(filtered)
        adapter.selectedIds = selectedIds
        adapter.submitList(sorted) {
            adapter.notifyDataSetChanged()
        }
        binding.emptyState.isVisible = sorted.isEmpty()
        binding.scanList.isVisible = true
        if (selectionMode && docs.none { it.id in selectedIds }) {
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

    private fun selectionTitle(): String =
        if (selectionMode) {
            resources.getQuantityString(R.plurals.selected_count, selectedIds.size, selectedIds.size)
        } else {
            getString(R.string.app_name)
        }

    private fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        backCallback.isEnabled = enabled
        if (!enabled) selectedIds.clear()
        adapter.selectionMode = enabled
        adapter.notifyDataSetChanged()
        binding.fabScan.isVisible = !enabled
        binding.toolsRow.isVisible = !enabled
        binding.toolbar.title = selectionTitle()
        updateGreeting()
        invalidateOptionsMenu()
    }

    private fun toggleSelection(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        adapter.notifyDataSetChanged()
        binding.toolbar.title = selectionTitle()
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
            binding.toolbar.title = selectionTitle()
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
                progress.dismiss()
                startActivity(DocumentViewerActivity.intent(this@MainActivity, doc.id))
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
            refreshList()
            true
        }
        popup.show()
    }

    /** Merge tool: tick 2+ scans, then name the merged document. */
    private fun showMergePicker() {
        val docs = repo.listDocuments()
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
        dialogBinding.editDpi.setText(getString(R.string.resize_default_dpi))

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
            dialogBinding.editDpi.setText(getString(R.string.resize_default_dpi))
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
                val dpi = dialogBinding.editDpi.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 96
                doResize(
                    bytes, name,
                    ImageTools.ResizeOptions(
                        width = w, height = h, dpi = dpi, keepRatio = ratio,
                        maxBytes = target, keepPng = true
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
                val base = sanitizeFileName(name).substringBeforeLast('.')
                val ext = if (result.isPng) "png" else "jpg"
                val file = File(dir, "${base}_${result.width}x${result.height}.$ext")
                file.writeBytes(result.bytes)

                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.resize_done, file.name, formatBytes(result.bytes.size.toLong())),
                    Toast.LENGTH_SHORT
                ).show()

                val contentUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = if (result.isPng) "image/png" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.resize_share)))
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
        if (repo.listDocuments().isEmpty()) {
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

    private fun pickScan(titleRes: Int, onPicked: (ScanDocument) -> Unit) {
        val docs = repo.listDocuments()
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
                    val count = contentResolver.openFileDescriptor(uri, "r")?.use {
                        PdfPageRenderer.pageCount(it)
                    } ?: throw IllegalStateException("Could not open PDF")
                    levels.map { level ->
                        val first = contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                            PdfPageRenderer.renderPageJpeg(
                                fd, 0, level.dpi, level.maxDim ?: 4096, level.quality
                            )
                        } ?: throw IllegalStateException("Could not render PDF")
                        (first.size.toDouble() * count * 1.02 + 1024).toLong()
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
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.compress_done, formatBytes(before), formatBytes(after)),
                    Toast.LENGTH_LONG
                ).show()
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
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.compress_done, formatBytes(before), formatBytes(out.pdfFile.length())),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun startCompressFromFile(uri: Uri) {
        estimateForPdfFile(uri)
    }

    private fun runCompressFile(uri: Uri, level: Level) {
        val progress = showProgress()
        lifecycleScope.launch {
            try {
                val before = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                val name = repo.displayNameOf(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF")
                val out = repo.importPdfAsScan(uri, name, level.dpi, level.maxDim ?: 4096, level.quality)
                val after = if (out.pdfFile.exists()) out.pdfFile.length() else 0L
                refreshList()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.compress_done, formatBytes(before), formatBytes(after)),
                    Toast.LENGTH_LONG
                ).show()
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
                val before = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                val baseName = repo.displayNameOf(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF")
                val pages = withContext(Dispatchers.IO) {
                    val fd = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("Could not open PDF")
                    val rendered = fd.use {
                        PdfPageRenderer.renderJpegs(it, dpi = 150f, maxDim = 2048, quality = 85)
                    }
                    fitJpegsToTotal(rendered, target)
                }
                val out = repo.createDocument("${baseName ?: "Document"} (compressed)", pages)
                refreshList()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.compress_done, formatBytes(before), formatBytes(out.pdfFile.length())),
                    Toast.LENGTH_LONG
                ).show()
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
        val bmp = BitmapFactory.decodeByteArray(orig, 0, orig.size)
            ?: throw IllegalStateException("A page could not be decoded")
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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        } catch (e: Exception) {
            "—"
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

    /** Opens Google\'s on-device document scanner (ML Kit). */
    private fun launchScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
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
                            .writeBytes(bytes)
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
        if (Prefs.defaultViewer(this) == Prefs.VIEWER_SYSTEM) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", doc.pdfFile)
            if (tryOpenExternally(uri)) return
        }
        startActivity(PdfViewerActivity.intent(this, doc.id, doc.name))
    }

    private fun openEditor(doc: ScanDocument) {
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
    bytes <= 0 -> "–"
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

    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

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
            val baseMeta = binding.root.context.resources.getQuantityString(
                R.plurals.page_count_and_date,
                doc.pageCount,
                doc.pageCount,
                dateFmt.format(Date(doc.createdAt))
            )
            val sizeBytes = if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) {
                doc.pdfFile.length()
            } else {
                doc.pageFiles.sumOf { it.length() }
            }
            binding.scanMeta.text = "$baseMeta · ${formatBytes(sizeBytes)}"
            val first = doc.pageFiles.firstOrNull()
            val thumb = if (first != null) ImageTools.decodeThumb(first, 320) else null
            if (thumb != null) {
                binding.scanThumb.setImageBitmap(thumb)
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
