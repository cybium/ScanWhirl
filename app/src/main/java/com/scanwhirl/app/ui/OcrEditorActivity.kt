package com.scanwhirl.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityOcrEditorBinding
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.OcrCleanup
import com.scanwhirl.app.util.TextHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * P6 OCR Editor (§4.7). Edits OCR text page-by-page before copying, sharing
 * or saving. Replaces the old read-only `showOcrTextDialog` in `OcrFlow`.
 *
 * Inputs (one required):
 * - `EXTRA_DOC_ID` — doc-backed mode: reads/writes `repo.ocrDoc(id)`, per-page.
 * - `EXTRA_TEXT_PATH` — temp mode: reads a cache file, single page, saves via
 *   `DownloadSaver` as a `.txt` file.
 * - `EXTRA_TITLE` — temp mode: document title used for the exported `.txt` name.
 *
 * Dirty-state: any edit to the current page's text marks the activity dirty.
 * Back while dirty shows a Save/Discard/Cancel confirm (SwDialog.card).
 *
 * Rotation safety: `currentPageKey` and the dirty pages (text differing from
 * `originalTexts`) are saved in `onSaveInstanceState` and restored on top of
 * freshly loaded originals. The temp cache file is deleted only when the
 * activity finishes (not on a configuration change).
 */
class OcrEditorActivity : BaseActivity() {

    private lateinit var binding: ActivityOcrEditorBinding
    private lateinit var repo: ScanRepository

    /** Doc-backed mode: the document id. Null in temp mode. */
    private var docId: String? = null
    /** Temp mode: the document title for export file names. */
    private var docTitle: String? = null
    /** pageKey → current (possibly edited) text. */
    private val pageTexts = mutableMapOf<String, String>()
    /** pageKey → original text (for dirty detection). */
    private var originalTexts = mapOf<String, String>()

    private var currentPageKey: String = ""
    private var isDirty: Boolean = false
    private var cacheFileToDelete: File? = null
    /** Per-page undo/redo history. */
    private val pageHistories = mutableMapOf<String, TextHistory>()
    /** Suppresses recording while the activity sets text programmatically. */
    private var applyingHistory = false
    private var saving = false

    private lateinit var editText: EditText
    private lateinit var wordCount: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: let the system insets (nav bar, IME) be applied to the
        // root view so content never lands under the gesture bar or keyboard.
        binding.root.applySystemBarsPadding()

        repo = ScanRepository(this)

        val intent = intent
        docId = intent.getStringExtra(EXTRA_DOC_ID)
        docTitle = intent.getStringExtra(EXTRA_TITLE)
        val textPath = intent.getStringExtra(EXTRA_TEXT_PATH)

        // Restore rotation state: dirty pages and current page key come from
        // the bundle; originalTexts are re-read from the repo/file so dirty
        // detection works against the true baseline.
        var hasSavedState = false
        savedInstanceState?.let { state ->
            hasSavedState = true
            currentPageKey = state.getString("currentPageKey") ?: ""
            val dirtyPages = state.getBundle("dirtyPages") ?: Bundle()
            for (key in dirtyPages.keySet()) {
                val text = dirtyPages.getString(key) ?: ""
                pageTexts[key] = text
            }
        }

        if (docId != null) {
            if (hasSavedState) {
                originalTexts = repo.ocrDoc(docId!!).toMap()
            } else {
                loadDocOcr(docId!!)
            }
        } else if (textPath != null) {
            if (hasSavedState) {
                val file = File(textPath)
                originalTexts = mapOf("temp" to (if (file.exists()) file.readText() else ""))
            } else {
                loadTempOcr(textPath)
            }
        } else {
            finish()
            return
        }

        // Recompute dirty from restored state.
        isDirty = pageTexts.any { (k, v) -> v != (originalTexts[k] ?: "") }

        setupToolbar()
        setupPageTabs()
        setupEditText()
        setupCleanupBar()
        setupActionBar()
        showPage(currentPageKey)

        // Dirty-state back handling.
        onBackPressedDispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isDirty) showUnsavedConfirm() else finish()
                }
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentPageKey", currentPageKey)
        val dirtyPages = Bundle()
        for ((key, value) in pageTexts) {
            val orig = originalTexts[key] ?: ""
            if (value != orig) dirtyPages.putString(key, value)
        }
        outState.putBundle("dirtyPages", dirtyPages)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ocr_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.ocr_copy_all -> { copyAllPages(); true }
        R.id.ocr_share_all -> { shareAllPages(); true }
        R.id.ocr_save_txt -> { saveAllAsTxt(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private fun loadDocOcr(id: String) {
        val pages = repo.ocrDoc(id)
        if (pages.isEmpty()) {
            // No OCR yet: single empty page so the editor still opens.
            pageTexts["page_000.jpg"] = ""
            originalTexts = mapOf("page_000.jpg" to "")
            currentPageKey = "page_000.jpg"
        } else {
            pageTexts.putAll(pages)
            originalTexts = pages.toMap()
            currentPageKey = pages.keys.first()
        }
    }

    private fun loadTempOcr(path: String) {
        val file = File(path)
        val text = if (file.exists()) file.readText() else ""
        cacheFileToDelete = file
        pageTexts["temp"] = text
        originalTexts = mapOf("temp" to text)
        currentPageKey = "temp"
    }

    // ------------------------------------------------------------------
    // UI setup
    // ------------------------------------------------------------------

    private fun setupToolbar() {
        val toolbar: Toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ocr_editor_title)
        toolbar.setNavigationOnClickListener {
            if (isDirty) showUnsavedConfirm() else finish()
        }
    }

    private fun setupPageTabs() {
        chipGroup = binding.pageTabs
        chipGroup.removeAllViews()
        chipGroup.isSingleSelection = true
        chipGroup.isSelectionRequired = true

        if (pageTexts.size <= 1) {
            chipGroup.visibility = View.GONE
            return
        }
        chipGroup.visibility = View.VISIBLE

        for (key in pageTexts.keys.sorted()) {
            val chip = Chip(this).apply {
                text = pageLabel(key)
                tag = key
                setCheckable(true)
                isChecked = (key == currentPageKey)
                chipBackgroundColor = null // use the theme's chip style colors
                setOnClickListener { showPage(key) }
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupEditText() {
        editText = binding.editText
        var typingOld: String = ""
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!applyingHistory) typingOld = s?.toString() ?: ""
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (applyingHistory) return
                val current = s?.toString() ?: ""
                pageTexts[currentPageKey] = current
                val history = pageHistories[currentPageKey] ?: TextHistory().also {
                    pageHistories[currentPageKey] = it
                }
                history.record(typingOld, current, TextHistory.Kind.TYPING)
                typingOld = current
                recomputeDirty()
                updateWordCount()
                updateUndoRedoState()
            }
        })
    }

    private fun recomputeDirty() {
        isDirty = pageTexts.any { (k, v) -> v != (originalTexts[k] ?: "") }
    }

    private fun setupCleanupBar() {
        binding.btnJoinLines.setOnClickListener { applyCleanup { OcrCleanup.joinLines(it) } }
        binding.btnFixSpaces.setOnClickListener { applyCleanup { OcrCleanup.fixSpaces(it) } }
        binding.btnRemoveGaps.setOnClickListener { applyCleanup { OcrCleanup.removeGaps(it) } }
    }

    private fun setupActionBar() {
        wordCount = binding.wordCount
        btnUndo = binding.btnUndo
        btnRedo = binding.btnRedo
        btnUndo.setOnClickListener { doUndo() }
        btnRedo.setOnClickListener { doRedo() }
        binding.btnCopy.setOnClickListener { copyCurrent() }
        binding.btnShare.setOnClickListener { shareCurrent() }
        binding.btnSave.setOnClickListener { saveCurrent() }
        updateWordCount()
        updateUndoRedoState()
    }

    // ------------------------------------------------------------------
    // Page display
    // ------------------------------------------------------------------

    private fun showPage(key: String) {
        currentPageKey = key
        editText.setText(pageTexts[key] ?: "")
        editText.setSelection(editText.text.length)
        updatePageTabs()
        updateWordCount()
        updateUndoRedoState()
    }

    private fun updatePageTabs() {
        if (chipGroup.visibility == View.GONE) return
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as Chip
            val key = chip.tag as String
            chip.isChecked = (key == currentPageKey)
        }
    }

    private fun updateWordCount() {
        val text = editText.text.toString()
        val words = if (text.isBlank()) 0 else text.split(Regex("\\s+")).size
        val resId = if (isDirty) R.string.ocr_word_count_edited else R.string.ocr_word_count
        wordCount.text = getString(resId, words)
    }

    private fun pageLabel(key: String): String {
        return if (key.startsWith("page_")) {
            val num = key.substringAfterLast("_").substringBefore(".").toIntOrNull() ?: 0
            getString(R.string.ocr_page_label, num + 1)
        } else {
            key
        }
    }

    // ------------------------------------------------------------------
    // Cleanup actions
    // ------------------------------------------------------------------

    private fun applyCleanup(transform: (String) -> String) {
        val snap = editText.text.toString()
        val newText = transform(snap)
        if (newText == snap) return // no-op: nothing changes, no history needed
        val history = pageHistories[currentPageKey] ?: TextHistory().also {
            pageHistories[currentPageKey] = it
        }
        history.record(snap, newText, TextHistory.Kind.ACTION)
        applyingHistory = true
        editText.setText(newText)
        editText.setSelection(newText.length)
        applyingHistory = false
        pageTexts[currentPageKey] = newText
        recomputeDirty()
        updateWordCount()
        updateUndoRedoState()
    }

    private fun doUndo() {
        val history = pageHistories[currentPageKey] ?: return
        val previous = history.undo(editText.text.toString()) ?: return
        applyingHistory = true
        editText.setText(previous)
        editText.setSelection(previous.length)
        applyingHistory = false
        pageTexts[currentPageKey] = previous
        recomputeDirty()
        updateWordCount()
        updateUndoRedoState()
    }

    private fun doRedo() {
        val history = pageHistories[currentPageKey] ?: return
        val next = history.redo(editText.text.toString()) ?: return
        applyingHistory = true
        editText.setText(next)
        editText.setSelection(next.length)
        applyingHistory = false
        pageTexts[currentPageKey] = next
        recomputeDirty()
        updateWordCount()
        updateUndoRedoState()
    }

    private fun updateUndoRedoState() {
        val history = pageHistories[currentPageKey]
        val canU = history?.canUndo == true
        val canR = history?.canRedo == true
        btnUndo.isEnabled = canU
        btnUndo.alpha = if (canU) 1f else 0.38f
        btnRedo.isEnabled = canR
        btnRedo.alpha = if (canR) 1f else 0.38f
    }

    // ------------------------------------------------------------------
    // Copy / Share / Save
    // ------------------------------------------------------------------

    private fun copyCurrent() {
        val text = editText.text.toString()
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("ocr", text))
        Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrent() {
        val text = editText.text.toString()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ocr_editor_title))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.ocr_share_text)))
    }

    private fun saveCurrent(finishAfterSave: Boolean = false) {
        if (saving) return
        val text = editText.text.toString()
        if (docId != null) {
            // Persist every page whose text differs from originalTexts, in one
            // rewrite, then update originals for exactly the pages written.
            val changedPages = pageTexts.filter { (k, v) -> v != (originalTexts[k] ?: "") }
            if (changedPages.isNotEmpty()) {
                saving = true
                binding.btnSave.isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(NonCancellable + Dispatchers.IO) {
                            checkNotNull(repo.updateOcrPages(docId!!, changedPages.toMap())) {
                                "Document no longer exists"
                            }
                        }
                        for ((key, value) in changedPages) {
                            originalTexts = originalTexts + (key to value)
                        }
                        recomputeDirty()
                        updateWordCount()
                        Toast.makeText(this@OcrEditorActivity, R.string.ocr_saved, Toast.LENGTH_SHORT).show()
                        if (finishAfterSave && !isDirty) finish()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(this@OcrEditorActivity,
                            getString(R.string.page_op_failed, e.message), Toast.LENGTH_LONG).show()
                    } finally {
                        saving = false
                        binding.btnSave.isEnabled = true
                    }
                }
            } else if (finishAfterSave) finish()
        } else {
            // Temp mode: save as .txt via DownloadSaver (M06 transaction).
            val title = docTitle ?: "Document"
            exportText(text, "${sanitizeTitle(title)}.txt") {
                originalTexts = pageTexts.toMap()
                isDirty = false
                updateWordCount()
                if (finishAfterSave) finish()
            }
        }
    }

    /**
     * M06: writes [text] to Documents/ScanWhirl/[fileName] with the export
     * transaction. An earlier .txt is never overwritten, and an identical one
     * is reported as already saved. A failure is shown and never crashes the
     * editor; edits stay in place. [onSaved] runs only after a verified save.
     */
    private fun exportText(text: String, fileName: String, onSaved: () -> Unit = {}) {
        if (saving) return
        withStoragePermission {
            saving = true
            binding.btnSave.isEnabled = false
            lifecycleScope.launch {
                try {
                    val saved = withContext(NonCancellable + Dispatchers.IO) {
                        DownloadSaver.saveText(this@OcrEditorActivity, text, fileName)
                    }
                    val message = when {
                        !saved.created -> getString(R.string.export_already_saved, saved.name)
                        saved.keptEarlier -> getString(R.string.export_saved_new_copy, saved.name)
                        else -> getString(R.string.text_saved_docs, saved.name)
                    }
                    Toast.makeText(this@OcrEditorActivity, message, Toast.LENGTH_LONG).show()
                    saving = false
                    binding.btnSave.isEnabled = true
                    onSaved()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    saving = false
                    binding.btnSave.isEnabled = true
                    Toast.makeText(
                        this@OcrEditorActivity,
                        getString(R.string.text_save_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private var pendingExport: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingExport?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
            pendingExport = null
        }

    /** Before Android 10 the public Documents folder needs the storage permission. */
    private fun withStoragePermission(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingExport = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun sanitizeTitle(title: String): String {
        return title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(60)
            .ifBlank { "Document" }
    }

    // ------------------------------------------------------------------
    // Copy all / Share all / Save all as .txt (overflow menu)
    // ------------------------------------------------------------------

    private fun copyAllPages() {
        val all = renderAllPages()
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("ocr-all", all))
        Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAllPages() {
        val all = renderAllPages()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ocr_editor_title))
            putExtra(Intent.EXTRA_TEXT, all)
        }
        startActivity(Intent.createChooser(send, getString(R.string.ocr_share_text)))
    }

    private fun saveAllAsTxt() {
        val title = docTitle ?: "Document"
        val fileName = "${sanitizeTitle(title)}.txt"
        exportText(renderAllPages(), fileName)
    }

    /** Renders all pages with localized "Page N" headings. Omits headings for a single page. */
    private fun renderAllPages(): String {
        val entries = pageTexts.entries.sortedBy { it.key }
        return if (entries.size <= 1) {
            entries.firstOrNull()?.value ?: ""
        } else {
            entries.joinToString("\n\n") { (key, text) ->
                "${getString(R.string.ocr_page_label, pageNumber(key))}\n$text"
            }
        }
    }

    private fun pageNumber(key: String): Int {
        return if (key.startsWith("page_")) {
            key.substringAfterLast("_").substringBefore(".").toIntOrNull() ?: 0
        } else {
            0
        }
    }

    // ------------------------------------------------------------------
    // Unsaved-changes confirm (SwDialog.card)
    // ------------------------------------------------------------------

    private fun showUnsavedConfirm() {
        SwDialog.card(this)
            .setTitle(R.string.ocr_unsaved_title)
            .setMessage(R.string.ocr_unsaved_msg)
            .setPositiveButton(R.string.ocr_save_btn) { _, _ ->
                saveCurrent(finishAfterSave = true)
            }
            .setNegativeButton(R.string.ocr_discard_btn) { _, _ ->
                finish()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only delete the temp cache file when the activity is truly finishing,
        // not when it's being destroyed for a configuration change (rotation).
        if (isFinishing && !isChangingConfigurations) {
            cacheFileToDelete?.let { it.delete() }
        }
        cacheFileToDelete = null
    }

    companion object {
        const val EXTRA_DOC_ID = "com.scanwhirl.app.extra.DOC_ID"
        const val EXTRA_TEXT_PATH = "com.scanwhirl.app.extra.TEXT_PATH"
        const val EXTRA_TITLE = "com.scanwhirl.app.extra.TITLE"
    }
}
