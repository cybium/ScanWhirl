package com.scanwhirl.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityOcrEditorBinding
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.OcrCleanup
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
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
 *
 * Dirty-state: any edit to the current page's text marks the activity dirty.
 * Back while dirty shows a Save/Discard/Cancel confirm.
 */
class OcrEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrEditorBinding
    private lateinit var repo: ScanRepository

    /** Doc-backed mode: the document id. Null in temp mode. */
    private var docId: String? = null
    /** pageKey → current (possibly edited) text. */
    private val pageTexts = mutableMapOf<String, String>()
    /** pageKey → original text (for dirty detection). */
    private var originalTexts = mapOf<String, String>()
    /** One-level undo snapshot. */
    private var undoSnapshot: String? = null
    private var undoPageKey: String? = null

    private var currentPageKey: String = ""
    private var isDirty: Boolean = false
    private var cacheFileToDelete: File? = null

    private lateinit var editText: EditText
    private lateinit var wordCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ScanRepository(this)

        val intent = intent
        docId = intent.getStringExtra(EXTRA_DOC_ID)
        val textPath = intent.getStringExtra(EXTRA_TEXT_PATH)

        if (docId != null) {
            loadDocOcr(docId!!)
        } else if (textPath != null) {
            loadTempOcr(textPath)
        } else {
            finish()
            return
        }

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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ocr_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.ocr_copy_all -> { copyAllPages(); true }
        R.id.ocr_share_all -> { shareAllPages(); true }
        R.id.ocr_save_txt -> { saveAllAsTxt(); true }
        R.id.ocr_rescan -> { /* Re-scan: regenerate OCR via OcrFlow — handled in T6.3 */ true }
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
        val tabContainer: LinearLayout = binding.pageTabs
        tabContainer.removeAllViews()
        if (pageTexts.size <= 1) {
            tabContainer.visibility = View.GONE
            return
        }
        tabContainer.visibility = View.VISIBLE
        for (key in pageTexts.keys.sorted()) {
            val btn = android.widget.Button(this).apply {
                text = pageLabel(key)
                setAllCaps(false)
                textSize = 12f
                setPadding(16, 8, 16, 8)
                if (key == currentPageKey) {
                    setTextColor(ContextCompat.getColor(this@OcrEditorActivity, R.color.cyan_primary))
                }
                setOnClickListener { showPage(key) }
            }
            tabContainer.addView(btn)
        }
    }

    private fun setupEditText() {
        editText = binding.editText
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val current = s?.toString() ?: ""
                pageTexts[currentPageKey] = current
                recomputeDirty()
                updateWordCount()
            }
        })
    }

    private fun recomputeDirty() {
        isDirty = pageTexts.entries.any { (k, v) -> v != originalTexts[k] }
    }

    private fun setupCleanupBar() {
        binding.btnJoinLines.setOnClickListener { applyCleanup { OcrCleanup.joinLines(it) } }
        binding.btnFixSpaces.setOnClickListener { applyCleanup { OcrCleanup.fixSpaces(it) } }
        binding.btnUndo.setOnClickListener { applyUndo() }
        binding.btnRemoveGaps.setOnClickListener { applyCleanup { OcrCleanup.removeGaps(it) } }
    }

    private fun setupActionBar() {
        wordCount = binding.wordCount
        binding.btnCopy.setOnClickListener { copyCurrent() }
        binding.btnShare.setOnClickListener { shareCurrent() }
        binding.btnSave.setOnClickListener { saveCurrent() }
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
    }

    private fun updatePageTabs() {
        val tabContainer: LinearLayout = binding.pageTabs
        if (tabContainer.visibility == View.GONE) return
        for (i in 0 until tabContainer.childCount) {
            val btn = tabContainer.getChildAt(i) as android.widget.Button
            val key = pageTexts.keys.elementAt(i)
            btn.setTextColor(if (key == currentPageKey) {
                ContextCompat.getColor(this, R.color.cyan_primary)
            } else {
                android.R.color.darker_gray
            })
        }
    }

    private fun updateWordCount() {
        val text = editText.text.toString()
        val words = if (text.isBlank()) 0 else text.split(Regex("\\s+")).size
        val edited = if (isDirty) " · edited" else ""
        wordCount.text = getString(R.string.ocr_word_count, words, edited)
    }

    private fun pageLabel(key: String): String {
        return if (key.startsWith("page_")) {
            val num = key.substringAfterLast("_").substringBefore(".").toIntOrNull() ?: 0
            getString(R.string.ocr_page_label, num + 1)
        } else {
            "Page"
        }
    }

    // ------------------------------------------------------------------
    // Cleanup actions
    // ------------------------------------------------------------------

    private fun applyCleanup(transform: (String) -> String) {
        val snap = editText.text.toString()
        undoSnapshot = snap
        undoPageKey = currentPageKey
        val newText = transform(snap)
        editText.setText(newText)
        editText.setSelection(newText.length)
        recomputeDirty()
    }

    private fun applyUndo() {
        val snap = undoSnapshot ?: return
        val key = undoPageKey ?: return
        undoSnapshot = null
        undoPageKey = null
        editText.setText(snap)
        editText.setSelection(snap.length)
        pageTexts[key] = snap
        recomputeDirty()
        updateWordCount()
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

    private fun saveCurrent() {
        val text = editText.text.toString()
        if (docId != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    repo.updateOcrPage(docId!!, currentPageKey, text)
                }
            }
            originalTexts = pageTexts.toMap()
            isDirty = false
            updateWordCount()
            Toast.makeText(this, R.string.ocr_saved, Toast.LENGTH_SHORT).show()
        } else {
            // Temp mode: save as .txt via DownloadSaver.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    DownloadSaver.savePdfFile(
                        this@OcrEditorActivity,
                        text.toByteArray(),
                        "ocr_${currentPageKey}.txt"
                    )
                }
            }
            originalTexts = pageTexts.toMap()
            isDirty = false
            updateWordCount()
            Toast.makeText(this, R.string.ocr_saved, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Unsaved-changes confirm
    // ------------------------------------------------------------------

    private fun showUnsavedConfirm() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.ocr_unsaved_title)
            .setMessage(R.string.ocr_unsaved_msg)
            .setPositiveButton(R.string.ocr_save_btn) { _, _ ->
                saveCurrent()
                finish()
            }
            .setNegativeButton(R.string.ocr_discard_btn) { _, _ ->
                finish()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    // Overflow-menu stubs (Re-scan is wired in T6.3 with OcrFlow).
    private fun copyAllPages() {
        val all = pageTexts.entries.sortedBy { it.key }.joinToString("\n\n") { "${it.key}: ${it.value}" }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("ocr-all", all))
        Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareAllPages() {
        val all = pageTexts.entries.sortedBy { it.key }.joinToString("\n\n") { "${it.key}: ${it.value}" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ocr_editor_title))
            putExtra(Intent.EXTRA_TEXT, all)
        }
        startActivity(Intent.createChooser(send, getString(R.string.ocr_share_text)))
    }

    private fun saveAllAsTxt() {
        val all = pageTexts.entries.sortedBy { it.key }.joinToString("\n\n") { "${it.key}: ${it.value}" }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DownloadSaver.savePdfFile(
                    this@OcrEditorActivity,
                    all.toByteArray(),
                    "ocr_all.txt"
                )
            }
        }
        Toast.makeText(this, R.string.ocr_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cacheFileToDelete?.let { it.delete() }
        cacheFileToDelete = null
    }

    companion object {
        const val EXTRA_DOC_ID = "com.scanwhirl.app.extra.DOC_ID"
        const val EXTRA_TEXT_PATH = "com.scanwhirl.app.extra.TEXT_PATH"
    }
}
