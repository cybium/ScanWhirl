package com.scanwhirl.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.method.TextKeyListener
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityOcrEditorBinding
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.OcrReindex
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.OcrCleanup
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrLanguageSettings
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
 * Back while dirty shows a Save/Discard/Cancel SwSheet.
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
    /** M06: pages with no stored text entry ("not read"), as opposed to read-but-empty. */
    private val unreadKeys = linkedSetOf<String>()
    /** Why pages are unread, from the last indexing pass: [PROBLEM_UNAVAILABLE] / [PROBLEM_FAILED]. */
    private var readProblem: String? = null
    private var retrying = false
    private var rereading = false
    /** Per-page undo/redo history. */
    private val pageHistories = mutableMapOf<String, TextHistory>()
    /** Suppresses recording while the activity sets text programmatically. */
    private var applyingHistory = false
    private var saving = false
    /** v2 UI: 08_text_ocr — Text is the default pane; Page shows the scan. */
    private var pageMode = false
    /**
     * W5: the screen opens read-first. [editing] is the explicit edit mode; read mode
     * keeps the text selectable but not typeable, so recognised text is never changed
     * by an accidental tap or cursor.
     */
    private var editing = false

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
        // N1: opening the text screen is reading, not typing: no keyboard, top of the page.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        repo = ScanRepository(this)

        val intent = intent
        docId = intent.getStringExtra(EXTRA_DOC_ID)
        // M06 (found on device): doc mode never received a title, so every
        // document's text export was named "Document.txt".
        docTitle = intent.getStringExtra(EXTRA_TITLE)
            ?: docId?.let { id -> repo.getDocument(id)?.name }
        val textPath = intent.getStringExtra(EXTRA_TEXT_PATH)

        readProblem = savedInstanceState?.getString(STATE_PROBLEM, null)
            ?: intent.getStringExtra(EXTRA_READ_PROBLEM)

        // Originals are always (re)loaded from the repo/file so dirty detection
        // works against the true baseline and every page is present. On
        // recreation, the bundle's dirty pages and current page key go on top.
        if (docId != null) {
            loadDocOcr(docId!!)
        } else if (textPath != null) {
            loadTempOcr(textPath)
        } else {
            finish()
            return
        }
        savedInstanceState?.let { state ->
            val dirtyPages = state.getBundle("dirtyPages") ?: Bundle()
            for (key in dirtyPages.keySet()) {
                pageTexts[key] = dirtyPages.getString(key) ?: ""
            }
            state.getString("currentPageKey")?.takeIf { it in pageTexts }?.let { currentPageKey = it }
        }

        // Recompute dirty from restored state.
        isDirty = pageTexts.any { (k, v) -> v != (originalTexts[k] ?: "") }

        setupToolbar()
        setupPageTabs()
        setupEditText()
        setupActionBar()
        // W5: opening the text screen is reading, not typing.
        setEditing(false)
        // One short line normally; at large text a second line is better than clipping.
        if (resources.configuration.fontScale > 1.5f) binding.ocrFootNote.maxLines = 2
        binding.btnOcrRetry.setOnClickListener { retryReading() }
        showPage(currentPageKey)

        // Dirty-state back handling. In read mode with no edits, Back leaves the screen.
        onBackPressedDispatcher.addCallback(
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (editing && !isDirty) {
                        setEditing(false)
                    } else if (isDirty) {
                        showUnsavedConfirm()
                    } else {
                        finish()
                    }
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
        outState.putString(STATE_PROBLEM, readProblem)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ocr_editor, menu)
        A11yTargets.widenOverflow(binding.toolbar)
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

    /**
     * M06: every page of the document gets a tab. A page with stored text
     * (possibly empty: read, nothing recognised) shows it. A page with no stored
     * entry was *not read* (failed, unavailable or not yet). It is shown as such,
     * never as an ordinary empty page, and it is never written back as "" unless the
     * person types into it.
     */
    private fun loadDocOcr(id: String) {
        val texts = repo.ocrDoc(id)
        val pageKeys = repo.getDocument(id)?.pageFiles?.map { it.name } ?: emptyList()
        val keys = (pageKeys + texts.keys).distinct().sorted()
            .ifEmpty { listOf("page_000.jpg") }
        unreadKeys.clear()
        unreadKeys.addAll(keys.filter { it !in texts })
        originalTexts = keys.associateWith { texts[it] ?: "" }
        pageTexts.clear()
        pageTexts.putAll(originalTexts)
        currentPageKey = keys.first()
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
        supportActionBar?.title = getString(R.string.ocr_screen_title)
        updateSubtitle()
        A11yTargets.stackToolbarText(toolbar)
        toolbar.setNavigationOnClickListener {
            when {
                editing && !isDirty -> setEditing(false)
                isDirty -> showUnsavedConfirm()
                else -> finish()
            }
        }
    }

    /** v2 UI: 08_text_ocr — the language chip beside the Page/Text switch. */
    private fun addLanguageChip() {
        val chip = com.scanwhirl.app.ui.kit.SwChip(this).apply {
            id = R.id.ocrLangChip
            bind(
                R.drawable.ic_sym_language,
                getString(languageLabel(OcrLanguageSettings.get(this@OcrEditorActivity)))
            )
            // L2: the chip is now the OCR language switch (English / English + हिंदी / हिंदी).
            isClickable = true
            isFocusable = true
            setOnClickListener { showLanguageSheet() }
        }
        binding.ocrLangHost.addView(
            chip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        com.scanwhirl.app.ui.A11yTargets.fixChip(chip)
    }

    private fun languageLabel(language: OcrLanguage): Int = when (language) {
        OcrLanguage.ENGLISH -> R.string.ocr_lang_english
        OcrLanguage.ENGLISH_HINDI -> R.string.ocr_lang_english_hindi
        OcrLanguage.HINDI -> R.string.ocr_lang_hindi
    }

    /** v2 UI: 08_text_ocr — pick the language; a doc-backed page is read again in it. */
    private fun showLanguageSheet() {
        if (rereading) return
        val languages = listOf(OcrLanguage.ENGLISH, OcrLanguage.ENGLISH_HINDI, OcrLanguage.HINDI)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.ocr_lang_title)
            .setMessage(R.string.ocr_lang_note)
            .setOptions(languages.map { getString(languageLabel(it)) }) { which, _ ->
                onLanguagePicked(languages[which.coerceIn(0, languages.lastIndex)])
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun onLanguagePicked(language: OcrLanguage) {
        val id = docId
        if (id == null) {
            OcrLanguageSettings.set(this, language)
            Toast.makeText(this, R.string.ocr_lang_saved, Toast.LENGTH_SHORT).show()
            recreate()
            return
        }
        // W5: reading again replaces stored text, so it never happens silently:
        // the person confirms that corrections will be replaced.
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.ocr_w5_reread_title)
            .setMessage(R.string.ocr_w5_reread_msg)
            .setPrimary(R.string.ocr_w5_reread_confirm) { reReadDocument(id, language) }
            .setSecondary(android.R.string.cancel) { }
            .build()
            .show()
    }

    /** W5: the confirmed re-read; drops unsaved edits only after the person agreed. */
    private fun reReadDocument(id: String, language: OcrLanguage) {
        if (rereading) return
        rereading = true
        OcrLanguageSettings.set(this, language)
        // The confirmation replaces unsaved edits, so drop them before recreation
        // instead of restoring them on top of the freshly recognised text.
        setEditing(false)
        pageTexts.clear()
        pageTexts.putAll(originalTexts)
        isDirty = false
        pageHistories.clear()
        Toast.makeText(this, R.string.ocr_lang_reading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                OcrReindex(repo).reRecognise(id, language, replaceCorrections = true)
                readProblem = PROBLEM_FAILED // Only displayed if a page remains unread.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val unavailable = OcrEngine.isUnavailable(e)
                readProblem = if (unavailable) PROBLEM_UNAVAILABLE else PROBLEM_FAILED
                Toast.makeText(
                    this@OcrEditorActivity,
                    if (unavailable) getString(R.string.ocr_unavailable_toast)
                    else getString(R.string.ocr_lang_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
            }
            recreate()
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
                text = if (key in unreadKeys) {
                    getString(R.string.ocr_page_label_unread, pageNumberOf(key))
                } else {
                    pageLabel(key)
                }
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

    /** v2 UI: 08_text_ocr — the three text fixes live in one sheet. */
    private fun showTidySheet() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.ocr_tidy_text)
            .setContent(body)
            .setSecondary(R.string.sheet_cancel)
            .build()
        val rowId = intArrayOf(R.id.ocrTidyJoin, R.id.ocrTidySpaces, R.id.ocrTidyGaps)
        val icons = intArrayOf(
            R.drawable.ic_sym_notes_lines, R.drawable.ic_sym_format_list_numbered,
            R.drawable.ic_sym_compress
        )
        val titles = intArrayOf(
            R.string.ocr_join_lines, R.string.ocr_fix_spaces, R.string.ocr_remove_gaps
        )
        val descriptions = intArrayOf(
            R.string.ocr_join_lines_desc, R.string.ocr_fix_spaces_desc, R.string.ocr_remove_gaps_desc
        )
        val actions = listOf<((String) -> String)>(
            { OcrCleanup.joinLines(it) }, { OcrCleanup.fixSpaces(it) }, { OcrCleanup.removeGaps(it) }
        )
        for (i in 0..2) {
            val row = LinearLayout(this).apply {
                id = rowId[i]
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = com.scanwhirl.app.ui.kit.SwStyle.dp(this@OcrEditorActivity, 56f)
                setPadding(0, com.scanwhirl.app.ui.kit.SwStyle.dp(this@OcrEditorActivity, 8f), 0,
                    com.scanwhirl.app.ui.kit.SwStyle.dp(this@OcrEditorActivity, 8f))
                setOnClickListener {
                    applyCleanup(actions[i])
                    sheet.dismiss()
                }
            }
            row.addView(
                android.widget.ImageView(this).apply {
                    setImageResource(icons[i])
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        com.scanwhirl.app.ui.kit.SwStyle.color(this@OcrEditorActivity, R.color.cyan_primary)
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(
                    com.scanwhirl.app.ui.kit.SwStyle.dp(this, 22f),
                    com.scanwhirl.app.ui.kit.SwStyle.dp(this, 22f)
                ).apply { marginEnd = com.scanwhirl.app.ui.kit.SwStyle.dp(this@OcrEditorActivity, 12f) }
            )
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(
                com.scanwhirl.app.ui.kit.SwStyle.text(
                    this, 15f,
                    com.scanwhirl.app.ui.kit.SwStyle.color(this@OcrEditorActivity, R.color.text_hologram_hi),
                    bold = true
                ).apply { setText(titles[i]) }
            )
            text.addView(
                com.scanwhirl.app.ui.kit.SwStyle.text(
                    this, 12.5f,
                    com.scanwhirl.app.ui.kit.SwStyle.color(this@OcrEditorActivity, R.color.text_hologram_dim)
                ).apply { setText(descriptions[i]) }
            )
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        sheet.show()
    }

    private fun setupActionBar() {
        wordCount = binding.wordCount
        btnUndo = binding.btnUndo
        btnRedo = binding.btnRedo
        btnUndo.setOnClickListener { doUndo() }
        btnRedo.setOnClickListener { doRedo() }

        // v2 UI: 08_text_ocr — Copy all · Share text · Save .txt · To Word as chips,
        // on two rows so To Word is always visible (never hidden in a scroller).
        // Owner F8: chips keep an 8dp gap, the rows 12dp, and every chip a 48dp touch target.
        val actions = binding.ocrActions
        val chipGap = com.scanwhirl.app.ui.kit.SwStyle.dp(this, 8f)
        fun chip(row: LinearLayout, id: Int, icon: Int, label: Int, pro: Boolean = false, weight: Float = 1f): com.scanwhirl.app.ui.kit.SwChip {
            val chip = com.scanwhirl.app.ui.kit.SwChip(this).apply {
                this.id = id
                bind(icon, getString(label))
                if (pro) withProTag()
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    weight
                ).apply {
                    marginEnd = chipGap
                }
            )
            com.scanwhirl.app.ui.A11yTargets.fixChip(chip)
            return chip
        }
        // The last chip on a row has no trailing gap: its edge lines up with the pane.
        fun sealRow(row: LinearLayout) {
            val last = row.getChildAt(row.childCount - 1) ?: return
            (last.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Tidy shares the first action row: its own row stole the height the text pane needs at 1.3.
        chip(row1, R.id.btnTidyText, R.drawable.ic_sym_auto_fix_high, R.string.ocr_tidy_text)
            .setOnClickListener {
                // W5: cleaning text is an edit, so it never changes read-only text.
                if (!editing) setEditing(true)
                showTidySheet()
            }
        chip(row1, R.id.btnCopy, R.drawable.ic_sym_content_copy, R.string.ocr_w5_copy_page)
            .setOnClickListener { copyCurrent() }
        chip(row1, R.id.btnShare, R.drawable.ic_sym_share, R.string.ocr_share_btn)
            .setOnClickListener { shareCurrent() }
        chip(row2, R.id.btnSave, R.drawable.ic_sym_notes, R.string.ocr_save_btn)
            .setOnClickListener { saveCurrent() }
        chip(row2, R.id.btnToWord, R.drawable.ic_sym_description, R.string.ocr_to_word_pro, weight = 1.45f)
            .apply { labelView.setTextColor(com.scanwhirl.app.ui.kit.SwStyle.color(this@OcrEditorActivity, R.color.gold_pro_primary)) }
            .setOnClickListener {
                // v2 UI: 08_text_ocr — the real To Word tool, not an info sheet. It is Pro, and
                // this Context overload does not pass through ToolHost.launchTool's gate, so the
                // offer is required here; ToolSetupActivity has the registry-driven backstop.
                if (!com.scanwhirl.app.ui.kit.ProOffer.require(this)) return@setOnClickListener
                com.scanwhirl.app.ui.tools.ToolLaunch.start(this, "to_word")
            }
        actions.addView(row1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        actions.addView(row2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = com.scanwhirl.app.ui.kit.SwStyle.dp(this@OcrEditorActivity, 12f) })
        sealRow(row1)
        sealRow(row2)
        setupModeSwitch()
        setupReadRow()
        updateWordCount()
        updateUndoRedoState()
    }

    /** W5: page indicator, Copy all pages, Edit text / Done and Save edits. */
    private fun setupReadRow() {
        binding.btnCopyAll.setOnClickListener { copyAllPages() }
        binding.btnEditText.setOnClickListener {
            setEditing(!editing)
        }
        binding.btnSaveEdits.setOnClickListener { saveEdits() }
        binding.btnTxtRetry.setOnClickListener {
            hideTxtFailure()
            saveCurrent()
        }
        // Buttons default to a single line; a wrapped label must break between words
        // instead of clipping or splitting a word at large type.
        for (button in listOf(binding.btnCopyAll, binding.btnSaveEdits, binding.btnEditText)) {
            button.isSingleLine = false
            button.maxLines = 3
            button.ellipsize = null
        }
        reflowReadRowForLargeText()
        reflowActionsForLargeText()
        compactLargeTextSpacing()
    }

    /** W5: at 200 % on 320dp, a few 2–4dp gaps decide whether the Hindi footer fits. */
    private fun compactLargeTextSpacing() {
        if (resources.configuration.fontScale <= 1.5f) return
        val d = resources.displayMetrics.density
        binding.ocrReadRow.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        binding.ocrSegmentRow.setPadding(
            (12 * d).toInt(), (2 * d).toInt(), (12 * d).toInt(), 0
        )
        binding.ocrActions.setPadding(
            (12 * d).toInt(), 0, (12 * d).toInt(), (2 * d).toInt()
        )
        (binding.ocrFootNote.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.bottomMargin = (2 * d).toInt()
            binding.ocrFootNote.layoutParams = it
        }
    }

    /**
     * W5 large type: five chips on two weighted rows leave ~90dp each, so a word
     * like "Share" or ".txt" is split inside the word. Two columns keep every word
     * whole and wrap only between words.
     */
    private fun reflowActionsForLargeText() {
        if (resources.configuration.fontScale <= 1.5f) return
        val container = binding.ocrActions
        val ids = listOf(
            R.id.btnTidyText, R.id.btnCopy, R.id.btnShare, R.id.btnSave, R.id.btnToWord
        )
        val chips = ids.mapNotNull { findViewById<View>(it) }
        for (chip in chips) (chip.parent as? android.view.ViewGroup)?.removeView(chip)
        container.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()
        val rowGap = (8 * resources.displayMetrics.density).toInt()
        val rows = listOf(chips.subList(0, 2), chips.subList(2, 4), chips.subList(4, 5))
        rows.forEachIndexed { index, group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEachIndexed { i, chip ->
                row.addView(
                    chip,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = if (i < group.lastIndex) gap else 0 }
                )
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = rowGap }
            )
        }
    }

    /**
     * W5: at large type three controls on one line squeeze the page indicator into
     * clipped lines. The indicator takes a full line and the actions share the next
     * one, which also keeps the text pane tall enough.
     */
    private fun reflowReadRowForLargeText() {
        if (resources.configuration.fontScale <= 1.5f) return
        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val list = listOf(binding.btnCopyAll, binding.btnSaveEdits, binding.btnEditText)
        list.forEach {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            buttons.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginEnd = gap }
            )
        }
        (buttons.getChildAt(buttons.childCount - 1).layoutParams as LinearLayout.LayoutParams)
            .marginEnd = 0
        binding.ocrReadRow.orientation = LinearLayout.VERTICAL
        binding.ocrReadRow.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        binding.ocrPageIndicator.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        binding.ocrPageIndicator.maxLines = 3
        for (button in list) {
            button.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            button.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
    }

    /**
     * W5 read-first: read mode keeps the text selectable but not typeable; edit mode
     * installs the key listener and shows Save edits. Switching modes never changes
     * the text or the page.
     */
    private fun setEditing(edit: Boolean) {
        editing = edit
        val field = editText
        // setTextIsSelectable() re-applies the current text; without this guard the
        // TextWatcher would treat that as typing and blank the page's stored text.
        val wasApplying = applyingHistory
        applyingHistory = true
        try {
            if (edit) {
                field.setTextIsSelectable(false)
                field.isFocusable = true
                field.isFocusableInTouchMode = true
                field.isCursorVisible = true
                field.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                field.keyListener = TextKeyListener.getInstance()
            } else {
                field.setKeyListener(null)
                field.isCursorVisible = false
                field.setTextIsSelectable(true)
            }
        } finally {
            applyingHistory = wasApplying
        }
        if (edit) {
            field.requestFocus()
            field.post {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            field.clearFocus()
        }
        binding.btnEditText.setText(
            if (edit) R.string.ocr_w5_done_editing else R.string.ocr_w5_edit_text
        )
        binding.btnSaveEdits.isVisible = edit
        binding.btnCopyAll.isVisible = !edit
        // Read-first keeps one 48dp action line: the edit bar (Undo/Redo/word count)
        // replaces the read line only while the person is editing.
        binding.ocrEditBar.isVisible = edit
        updateUndoRedoState()
    }

    private fun setupModeSwitch() {
        binding.ocrModePage.setOnClickListener { setPageMode(true) }
        binding.ocrModeText.setOnClickListener { setPageMode(false) }
        addLanguageChip()
        setPageMode(false)
    }

    /** v2 UI: 08_text_ocr — Page and Text share the pane; Text stays editable. */
    private fun setPageMode(page: Boolean) {
        pageMode = page
        binding.ocrPagePane.isVisible = page
        binding.editText.isVisible = !page
        val d = resources.displayMetrics.density
        val active = com.scanwhirl.app.ui.kit.SwStyle.rounded(
            this, getColor(com.scanwhirl.app.R.color.sw_cyan_soft), 10f
        )
        for ((view, on) in listOf(
            binding.ocrModePage to page,
            binding.ocrModeText to !page
        )) {
            view.background = if (on) active else null
            view.setTextColor(
                getColor(
                    if (on) com.scanwhirl.app.R.color.cyan_primary
                    else com.scanwhirl.app.R.color.text_hologram_dim
                )
            )
            view.setPadding(0, 0, 0, 0)
            view.minimumHeight = (48 * d).toInt()
        }
        if (page) loadPageImage(currentPageKey)
    }

    /** The scan behind this page; null when the text came from a plain file. */
    private fun loadPageImage(key: String) {
        val doc = docId?.let { repo.getDocument(it) }
        val file = doc?.pageFiles?.firstOrNull { it.name == key }
        val note = binding.ocrPagePaneNote
        val overlay = binding.ocrPageOverlay
        if (file == null || !file.exists()) {
            binding.ocrPageImage.setImageDrawable(null)
            overlay.setPage(emptyList(), 0, 0)
            note.isVisible = true
            return
        }
        note.isVisible = false
        val geometry = runCatching {
            docId?.let { repo.ocrGeometry(it) }?.get(key)
        }.getOrNull()
        val token = key
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                com.scanwhirl.app.util.ImageTools.decodeOriented(file, 1600)
            }
            if (token != currentPageKey || !pageMode) return@launch
            if (bitmap == null) {
                binding.ocrPageImage.setImageDrawable(null)
                note.isVisible = true
                return@launch
            }
            binding.ocrPageImage.setImageBitmap(bitmap)
            overlay.setPage(
                geometry?.lines.orEmpty(),
                geometry?.width ?: bitmap.width,
                geometry?.height ?: bitmap.height
            )
        }
        overlay.onLineTap = { line -> jumpToLine(line) }
    }

    /** v2 UI: 08_text_ocr — a tapped line box opens the Text tab at that line. */
    private fun jumpToLine(line: com.scanwhirl.app.model.OcrLine) {
        setPageMode(false)
        val text = editText.text.toString()
        val at = text.indexOf(line.text)
        if (at >= 0) {
            if (editing) editText.requestFocus()
            editText.setSelection(at.coerceAtMost(text.length))
        }
    }

    private fun updateSubtitle() {
        val name = docTitle ?: getString(R.string.ocr_screen_title)
        val total = maxOf(1, pageTexts.size)
        val current = pageNumberOf(currentPageKey).coerceIn(1, total)
        // W5: the filename is the toolbar subtitle; the read line below carries
        // the explicit "Page n of m" indicator.
        binding.toolbar.subtitle = name
        binding.ocrPageIndicator.text = getString(R.string.ocr_w5_page_of, current, total)
        A11yTargets.stackToolbarText(binding.toolbar)
    }

    // ------------------------------------------------------------------
    // Page display
    // ------------------------------------------------------------------

    private fun showPage(key: String) {
        currentPageKey = key
        editText.hint = if (key in unreadKeys) getString(R.string.ocr_page_not_read_hint) else null
        // Loading a page is not an edit: it must not fill the undo history.
        applyingHistory = true
        editText.setText(pageTexts[key] ?: "")
        applyingHistory = false
        // N1 (found on device): a page opened with the cursor at its last character
        // scrolled the text box to the bottom, so people read the end first. A page
        // being read starts at its first line; a tap still moves the cursor.
        editText.setSelection(0)
        updatePageTabs()
        updateWordCount()
        updateUndoRedoState()
        updateStatus()
        updateSubtitle()
        if (pageMode) loadPageImage(key)
    }

    // ------------------------------------------------------------------
    // M06: honest recognition status
    // ------------------------------------------------------------------

    /**
     * One banner, one truth:
     *  - every page read but nothing recognised → "No text was found" (no Retry)
     *  - recognition unavailable on this device → explain it, offer Retry
     *  - no page could be read → say so, offer Retry
     *  - some pages not read → "Text on n of m pages; page x, y not read", Retry
     *  - otherwise nothing.
     * Typing is always possible: text you type for a page is saved as that page's
     * text and becomes searchable, which is real manual entry, not fake OCR.
     */
    private fun updateStatus() {
        val total = pageTexts.size
        val unread = unreadKeys.size
        val message: String? = when {
            retrying -> getString(R.string.ocr_status_retrying)
            docId == null ->
                if ((originalTexts["temp"] ?: "").isBlank()) getString(R.string.ocr_status_no_text_temp) else null
            unread == 0 ->
                if (originalTexts.values.all { it.isBlank() }) getString(R.string.ocr_status_no_text) else null
            readProblem == PROBLEM_UNAVAILABLE -> getString(R.string.ocr_status_unavailable)
            unread >= total -> getString(R.string.ocr_status_not_read)
            else -> resources.getQuantityString(
                R.plurals.ocr_status_partial, total,
                total - unread, total,
                unreadKeys.sorted().joinToString(", ") { pageNumberOf(it).toString() }
            )
        }
        binding.ocrStatus.isVisible = message != null
        binding.ocrStatusText.text = message
        val canRetry = docId != null && unread > 0 && !retrying
        binding.btnOcrRetry.isVisible = canRetry
    }

    /**
     * Reads the unread pages again. Already stored pages, including corrected
     * text, are never re-read or overwritten. A page the person has typed into
     * keeps their text.
     */
    private fun retryReading() {
        val id = docId ?: return
        if (retrying || unreadKeys.isEmpty()) return
        retrying = true
        updateStatus()
        lifecycleScope.launch {
            val report = try {
                ScanWhirlApp.from(this@OcrEditorActivity).indexer.index(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val texts = withContext(Dispatchers.IO) { repo.ocrDoc(id) }
            retrying = false
            var newlyRead = 0
            for (key in unreadKeys.toList()) {
                val text = texts[key] ?: continue
                unreadKeys.remove(key)
                newlyRead++
                originalTexts = originalTexts + (key to text)
                if ((pageTexts[key] ?: "").isEmpty()) pageTexts[key] = text
            }
            readProblem = report?.let { problemOf(it) } ?: PROBLEM_FAILED
            recomputeDirty()
            setupPageTabs()
            applyingHistory = true
            showPage(currentPageKey)
            applyingHistory = false
            if (newlyRead > 0) {
                Toast.makeText(
                    this@OcrEditorActivity,
                    resources.getQuantityString(R.plurals.ocr_retry_read, newlyRead, newlyRead),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun pageNumberOf(key: String): Int =
        (key.substringAfterLast("_").substringBefore(".").toIntOrNull() ?: 0) + 1

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
        if (text.isBlank() && currentPageKey in unreadKeys) {
            // Not "0 words": nothing was read here.
            wordCount.text = getString(R.string.ocr_page_not_read)
            return
        }
        val words = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
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
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, R.string.ocr_w5_page_copied,
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).show()
        findViewById<com.scanwhirl.app.ui.kit.SwChip>(R.id.btnCopy)
            ?.flashDone(R.string.ocr_w5_page_copied)
    }

    /**
     * W5: "Save edits" persists the corrected OCR text in the document (search,
     * reopen) and writes no file. It never claims a .txt copy; [saveCurrent] does
     * that. The screen closes only after the library write really succeeded.
     */
    private fun saveEdits(finishAfter: Boolean = false) {
        if (saving) return
        val id = docId
        if (id == null) {
            Toast.makeText(this, R.string.ocr_w5_not_in_library, Toast.LENGTH_LONG).show()
            return
        }
        val changedPages = pageTexts.filter { (k, v) -> v != (originalTexts[k] ?: "") }
        if (changedPages.isEmpty()) {
            Toast.makeText(this, R.string.ocr_w5_no_edits, Toast.LENGTH_SHORT).show()
            if (finishAfter) finish()
            return
        }
        saving = true
        lifecycleScope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    checkNotNull(repo.updateOcrPages(id, changedPages.toMap())) {
                        "Document no longer exists"
                    }
                }
                originalTexts = originalTexts + changedPages
                if (unreadKeys.removeAll(changedPages.keys)) setupPageTabs()
                recomputeDirty()
                updateWordCount()
                updateStatus()
                updateSubtitle()
                hideTxtFailure()
                Toast.makeText(this@OcrEditorActivity, R.string.ocr_w5_edits_saved, Toast.LENGTH_SHORT).show()
                finishSaveAttempt()
                if (finishAfter) finish()
            } catch (e: CancellationException) {
                finishSaveAttempt()
                throw e
            } catch (e: Exception) {
                finishSaveAttempt()
                Toast.makeText(
                    this@OcrEditorActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** W5: the .txt write failed but the library text is kept; Retry is next to it. */
    private fun showTxtFailure() {
        val message = if (docId != null) {
            R.string.ocr_w5_txt_failed_kept
        } else {
            R.string.ocr_w5_txt_failed_here
        }
        binding.ocrTxtFailureText.setText(message)
        binding.ocrTxtFailure.isVisible = true
    }

    private fun hideTxtFailure() {
        binding.ocrTxtFailure.isVisible = false
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
        val id = docId
        if (id != null) {
            saveDocumentTxt(id, finishAfterSave)
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
     * H5/H6: in a document-backed editor "Save .txt" writes the OCR text as
     * "<doc name> - text.txt" (UTF-8) into Documents/ScanWhirl, exactly like the
     * temporary-text export. The corrected text is stored on the document first so
     * search keeps finding it; the "edited" marker is cleared only after the .txt
     * write succeeded. A failed write is reported and the edits stay in the editor.
     */
    private fun saveDocumentTxt(id: String, finishAfterSave: Boolean) {
        val changedPages = pageTexts.filter { (k, v) -> v != (originalTexts[k] ?: "") }
        val title = docTitle ?: getString(R.string.ocr_screen_title)
        val fileName = "${sanitizeTitle(title)} - text.txt"
        if (saving) return
        saving = true
        findViewById<View>(R.id.btnSave).isEnabled = false
        lifecycleScope.launch {
            // 1. Keep the corrected text searchable in the document. This never needs
            //    storage permission, so a permission prompt cannot lose an edit.
            try {
                if (changedPages.isNotEmpty()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        checkNotNull(repo.updateOcrPages(id, changedPages.toMap())) {
                            "Document no longer exists"
                        }
                    }
                    // Typed text for an unread page is now that page's stored text.
                    if (unreadKeys.removeAll(changedPages.keys)) setupPageTabs()
                    updateStatus()
                }
            } catch (e: CancellationException) {
                finishSaveAttempt()
                throw e
            } catch (e: Exception) {
                finishSaveAttempt()
                Toast.makeText(
                    this@OcrEditorActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // 2. Write the .txt (and ask for storage permission only if the OS refuses it).
            writeDocumentTxt(fileName, finishAfterSave)
        }
    }

    /** The `.txt` half of [saveDocumentTxt]; the document text is already stored. */
    private fun writeDocumentTxt(fileName: String, finishAfterSave: Boolean, retried: Boolean = false) {
        lifecycleScope.launch {
            try {
                val saved = withContext(NonCancellable + Dispatchers.IO) {
                    DownloadSaver.saveText(this@OcrEditorActivity, renderAllPages(), fileName)
                }
                // Only now is the file really there: clear the "edited" marker.
                originalTexts = pageTexts.toMap()
                isDirty = false
                updateWordCount()
                updateStatus()
                val message = when {
                    !saved.created -> getString(R.string.export_already_saved, saved.name)
                    saved.keptEarlier -> getString(R.string.export_saved_new_copy, saved.name)
                    else -> getString(R.string.text_saved_docs, saved.name)
                }
                com.scanwhirl.app.util.SavedSnackbar.show(
                    binding.root,
                    message,
                    onOpen = { com.scanwhirl.app.util.PdfOpener.open(this@OcrEditorActivity, saved.uri) },
                    onUndo = null
                )
                Toast.makeText(this@OcrEditorActivity, message, Toast.LENGTH_LONG).show()
                hideTxtFailure()
                finishSaveAttempt()
                // The sheet asked to close only after the save landed. On a failure the
                // error above says the .txt could not be written; the document text is
                // already stored, so the edits are never lost either way.
                if (finishAfterSave) finish()
            } catch (e: CancellationException) {
                finishSaveAttempt()
                throw e
            } catch (e: Exception) {
                if (!retried && needsStoragePermission() &&
                    Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
                ) {
                    // Pre-Android-10 public storage: ask, then retry the same write.
                    withStoragePermission(
                        action = { writeDocumentTxt(fileName, finishAfterSave, retried = true) },
                        onDenied = {
                            reportTxtFailure(e)
                            showTxtFailure()
                            finishSaveAttempt()
                        }
                    )
                } else {
                    reportTxtFailure(e)
                    showTxtFailure()
                    finishSaveAttempt()
                    // W5: a failed .txt write keeps the screen open with Retry. The
                    // document text was stored first, so the message can say it is kept.
                }
            }
        }
    }

    private fun reportTxtFailure(e: Exception) {
        Toast.makeText(
            this,
            getString(R.string.text_save_failed, e.message ?: e.javaClass.simpleName),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun finishSaveAttempt() {
        saving = false
        findViewById<View>(R.id.btnSave).isEnabled = true
    }

    /** Before Android 10 the public Documents folder needs the storage permission. */
    private fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

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
            findViewById<View>(R.id.btnSave).isEnabled = false
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
                    com.scanwhirl.app.util.SavedSnackbar.show(
                        binding.root,
                        message,
                        onOpen = { com.scanwhirl.app.util.PdfOpener.open(this@OcrEditorActivity, saved.uri) },
                        onUndo = null
                    )
                    // The outcome is also a toast so it is reported even when a
                    // snackbar cannot show (and existing export tests read it).
                    Toast.makeText(this@OcrEditorActivity, message, Toast.LENGTH_LONG).show()
                    hideTxtFailure()
                    saving = false
                    findViewById<View>(R.id.btnSave).isEnabled = true
                    onSaved()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    saving = false
                    findViewById<View>(R.id.btnSave).isEnabled = true
                    Toast.makeText(
                        this@OcrEditorActivity,
                        getString(R.string.text_save_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                    // W5: temp text stays on screen; the failure is actionable with Retry.
                    showTxtFailure()
                }
            }
        }
    }

    private var pendingExport: (() -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingExport
            val denied = pendingDenied
            pendingExport = null
            pendingDenied = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
                denied?.invoke()
            }
        }

    /**
     * Before Android 10 the public Documents folder needs the storage permission.
     * [onDenied] lets a caller release its own in-progress state when the person
     * refuses; the temporary-text save keeps the old behaviour of just saying so.
     */
    private fun withStoragePermission(onDenied: (() -> Unit)? = null, action: () -> Unit) {
        if (!needsStoragePermission()) {
            action()
        } else {
            pendingExport = action
            pendingDenied = onDenied
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
        Toast.makeText(this, R.string.ocr_w5_all_copied, Toast.LENGTH_SHORT).show()
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
            // M06 (found on device): headings are 1-based like the tabs (they said
            // "Page 0"), and a page that was never read says so in the export
            // instead of looking like a page with no text.
            entries.joinToString("\n\n") { (key, text) ->
                val heading = if (key in unreadKeys && text.isBlank()) {
                    getString(R.string.ocr_page_label_unread, pageNumberOf(key))
                } else {
                    getString(R.string.ocr_page_label, pageNumberOf(key))
                }
                "$heading\n$text"
            }
        }
    }

    // ------------------------------------------------------------------
    // Unsaved-changes confirm (SwSheet)
    // ------------------------------------------------------------------

    /**
     * W5: back with edits offers the three honest outcomes: Save edits (library
     * text only), Save .txt and close (a file, then close) or Discard. The sheet's
     * primary keeps the corrections in the document; the close icon keeps editing.
     */
    private fun showUnsavedConfirm() {
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.ocr_unsaved_title)
            .setMessage(R.string.ocr_w5_back_msg)
            .setOptions(
                listOf(
                    getString(R.string.ocr_w5_save_txt_and_close),
                    getString(R.string.ocr_discard_btn)
                )
            ) { which, _ ->
                if (which == 0) saveCurrent(finishAfterSave = true) else finish()
            }
            .setPrimary(R.string.ocr_w5_save_edits) {
                saveEdits(finishAfter = true)
            }
            .setSecondary(R.string.ocr_w5_keep_editing) { }
            .build()
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
        /** M06: why some pages have no text after the last pass (doc mode). */
        const val EXTRA_READ_PROBLEM = "com.scanwhirl.app.extra.READ_PROBLEM"
        const val PROBLEM_UNAVAILABLE = "unavailable"
        const val PROBLEM_FAILED = "failed"
        private const val STATE_PROBLEM = "readProblem"

        fun problemOf(report: OcrIndexer.IndexReport): String? = when {
            report.unavailable -> PROBLEM_UNAVAILABLE
            report.failedPages > 0 -> PROBLEM_FAILED
            else -> null
        }
    }
}
