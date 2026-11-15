package com.scanwhirl.app.ui.stamp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityStampEditorBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.store.StampStore
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolGroup
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.InkExtract
import com.scanwhirl.app.util.OutputNames
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.SensitiveScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * L5 Phase B — the Stamp editor (70 §3 "Edit & sign", product spec §3.7).
 *
 * Page view with drag / resize / rotate handles, a tool row, undo/redo and **Save as a new
 * copy**. Every tool bakes a real mark through [PageStamp]; Redact can suggest boxes from the
 * page's OCR geometry. The source document is never written (see
 * `ScanRepository.stampDocument`).
 *
 * The screen is a thin shell over [StampEditorState]; the page surface is [StampCanvasView].
 * It is launched with [EXTRA_DOC_ID] and optionally [EXTRA_TOOL], so the Tools hub's
 * Sign / Add text / Draw / Highlight / Watermark / Page numbers / Stamp / Redact tiles can
 * open it already armed.
 */
class StampEditorActivity : BaseActivity() {

    companion object {
        const val EXTRA_DOC_ID = "com.scanwhirl.app.ui.stamp.DOC_ID"
        const val EXTRA_TOOL = "com.scanwhirl.app.ui.stamp.TOOL"
        const val EXTRA_NEW_DOC_ID = "com.scanwhirl.app.ui.stamp.NEW_DOC_ID"

        const val TOOL_SIGN = "sign"
        const val TOOL_TEXT = "text"
        const val TOOL_DRAW = "draw"
        const val TOOL_HIGHLIGHT = "highlight"
        const val TOOL_WATERMARK = "watermark"
        const val TOOL_PAGE_NUMBERS = "page_numbers"
        const val TOOL_STAMP = "stamp"
        const val TOOL_REDACT = "redact"
        const val TOOL_MASK_AADHAAR = "mask_aadhaar"

        private const val DISABLED_ALPHA = 0.38f

        /** Intent for a screen or the Tools hub: open [docId] with [tool] already armed. */
        fun intent(context: android.content.Context, docId: String, tool: String? = null): Intent =
            Intent(context, StampEditorActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_TOOL, tool)
    }

    private lateinit var binding: ActivityStampEditorBinding
    private lateinit var repo: ScanRepository
    private lateinit var document: ScanDocument
    private lateinit var signatureStore: SignatureStore
    private lateinit var stampStore: StampStore

    /** Which list a photo import feeds: the signatures sheet or My stamps. */
    private enum class InkKind { SIGNATURE, STAMP }

    private var inkKind = InkKind.SIGNATURE
    private var pendingPhotoFile: File? = null

    private val inkGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyPhotoToCache(uri) }
            if (file == null) {
                note(getString(R.string.stamp_ink_none))
            } else {
                loadInk(file)
            }
        }
    }

    private val inkCamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (!ok || file == null) return@registerForActivityResult
        loadInk(file)
    }

    internal lateinit var editor: StampEditorState
        private set

    /** True once a find/mask flow seeded redactions: save as "<name> - Masked/Redacted". */
    private var hideMode = false

    /** Output label for the current hide flow: "Masked" (Aadhaar preset) or "Redacted". */
    private var hideLabelRes = R.string.mask_label

    private var findRunning = false

    /** Test seam: replaces OCR detection with synthetic per-page hits. */
    internal var findScanner: (suspend (List<File>) -> List<Pair<Int, SensitiveScan.Hit>>)? = null

    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStampEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        applyKitChrome()

        repo = ScanRepository(this)
        signatureStore = SignatureStore(this)
        stampStore = StampStore(this)

        val id = intent.getStringExtra(EXTRA_DOC_ID)
        val doc = id?.let { repo.getDocument(it) }
        if (doc == null) {
            note(getString(R.string.stamp_missing))
            finish()
            return
        }
        if (doc.isLocked) {
            note(getString(R.string.stamp_locked))
            finish()
            return
        }
        document = doc
        editor = StampEditorState(doc.pageCount)

        binding.toolbar.setNavigationOnClickListener { confirmExit() }
        binding.toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)

        binding.canvas.listener = object : StampCanvasView.Listener {
            override fun onSelect(index: Int?) {
                editor.select(index)
                refreshSelection()
            }

            override fun onMove(index: Int, dx: Float, dy: Float) {
                if (editor.moveBy(index, dx, dy)) refresh()
            }

            override fun onResize(index: Int, box: PageStamp.Box) {
                if (editor.resizeTo(index, box)) refresh()
            }

            override fun onRotate(index: Int, degrees: Float) {
                if (editor.rotateBy(index, degrees)) refresh()
            }

            override fun onFreehand(strokes: List<PageStamp.Stroke>) {
                addMark(PageStamp.Mark.Freehand(strokes))
            }

            override fun onBox(tool: StampCanvasView.Tool, box: PageStamp.Box) {
                when (tool) {
                    StampCanvasView.Tool.HIGHLIGHT -> addMark(PageStamp.Mark.Highlight(box))
                    StampCanvasView.Tool.REDACT -> addMark(PageStamp.Mark.Redact(box))
                    else -> Unit
                }
            }

            override fun onDelete(index: Int) {
                if (editor.selected == index && editor.removeSelected()) refresh()
            }
        }

        binding.prevPage.setOnClickListener {
            if (editor.currentPage > 0) {
                editor.currentPage -= 1
                showPage()
            }
        }
        binding.nextPage.setOnClickListener {
            if (editor.currentPage < editor.pageCount - 1) {
                editor.currentPage += 1
                showPage()
            }
        }

        buildToolRow()
        binding.deleteMark.setOnClickListener {
            if (editor.removeSelected()) refresh()
        }
        binding.findRedactions.setOnClickListener { openFindSheet() }
        binding.undo.setOnClickListener {
            if (editor.undo()) refresh()
        }
        binding.redo.setOnClickListener {
            if (editor.redo()) refresh()
        }
        binding.saveCopy.setOnClickListener { save() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmExit()
            }
        })

        showPage()
        applyRequestedTool(intent.getStringExtra(EXTRA_TOOL))
    }

    // ------------------------------------------------------------------ tools

    /**
     * The bottom tool row, made of kit [ToolTile]s so it matches the Tools hub and Home quick
     * row. Ids stay `toolSignature`…`toolSuggest` for the screen tests.
     */
    private fun buildToolRow() {
        val row = binding.stampToolRow
        fun add(id: Int, title: Int, icon: Int, action: () -> Unit) {
            row.addView(tile(id, title, icon, action))
        }
        add(R.id.toolSignature, R.string.stamp_tool_signature, R.drawable.ic_sym_signature) {
            pickSignature()
        }
        add(R.id.toolText, R.string.stamp_tool_text, R.drawable.ic_sym_text_fields) {
            promptText()
        }
        add(R.id.toolDraw, R.string.stamp_tool_draw, R.drawable.ic_sym_edit) {
            arm(StampCanvasView.Tool.DRAW, R.string.stamp_hint_draw)
        }
        add(R.id.toolHighlight, R.string.stamp_tool_highlight, R.drawable.ic_sym_format_list_numbered) {
            arm(StampCanvasView.Tool.HIGHLIGHT, R.string.stamp_hint_highlight)
        }
        add(R.id.toolWatermark, R.string.stamp_tool_watermark, R.drawable.ic_sym_watermark) {
            promptWatermark()
        }
        add(R.id.toolPageNumbers, R.string.stamp_tool_page_numbers, R.drawable.ic_sym_notes_lines) {
            addMarkToAll(PageStamp.Mark.PageNumber())
        }
        add(R.id.toolStamp, R.string.stamp_tool_stamp, R.drawable.ic_sym_approval) {
            promptBadges()
        }
        add(R.id.toolRedact, R.string.stamp_tool_redact, R.drawable.ic_sym_ink_eraser) {
            arm(StampCanvasView.Tool.REDACT, R.string.stamp_hint_redact)
        }
        add(R.id.toolSuggest, R.string.stamp_suggest, R.drawable.ic_sym_auto_fix_high) {
            suggestRedactions()
        }
    }

    private fun tile(id: Int, title: Int, icon: Int, action: () -> Unit): ToolTile {
        val tool = SwTool(
            id = "stamp_edit_$id",
            group = ToolGroup.EDIT,
            title = title,
            icon = icon,
            pro = false,
            status = ToolStatus.READY,
            keywords = "edit stamp"
        )
        return ToolTile(this).apply {
            this.id = id
            bind(tool)
            labelView.maxLines = 3
            setOnClickListener { action() }
            minimumHeight = dp(68f)
            layoutParams = LinearLayout.LayoutParams(
                dp(82f), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(2f)
            }
        }
    }

    private fun promptText() {
        StampPrompts.text(
            this, R.string.stamp_text_title, R.string.stamp_text_hint, ""
        ) { text -> addMark(PageStamp.Mark.Text(text, textBox(), size = 0.05f)) }
    }

    private fun promptWatermark() {
        StampPrompts.text(
            this, R.string.stamp_watermark_title, R.string.stamp_watermark_hint,
            getString(R.string.stamp_watermark_default)
        ) { text -> addMarkToAll(PageStamp.Mark.Watermark(text)) }
    }

    private fun promptBadges() {
        StampPrompts.badges(
            this,
            stampStore,
            onPick = { kind -> addMark(PageStamp.Mark.Badge(kind, badgeBox())) },
            onStamp = { stamp -> addMark(PageStamp.Mark.Picture(stamp.pngFile, badgeBox())) },
            onImportPhoto = { startInkImport(InkKind.STAMP) }
        )
    }

    private fun applyRequestedTool(tool: String?) {
        when (tool) {
            TOOL_SIGN -> pickSignature()
            TOOL_TEXT -> promptText()
            TOOL_DRAW -> arm(StampCanvasView.Tool.DRAW, R.string.stamp_hint_draw)
            TOOL_HIGHLIGHT -> arm(StampCanvasView.Tool.HIGHLIGHT, R.string.stamp_hint_highlight)
            TOOL_WATERMARK -> promptWatermark()
            TOOL_PAGE_NUMBERS -> addMarkToAll(PageStamp.Mark.PageNumber())
            TOOL_STAMP -> promptBadges()
            TOOL_REDACT -> arm(StampCanvasView.Tool.REDACT, R.string.stamp_hint_redact)
            TOOL_MASK_AADHAAR -> openFindSheet()
            else -> Unit
        }
    }

    private fun arm(tool: StampCanvasView.Tool, hintRes: Int) {
        editor.select(null)
        binding.canvas.tool = tool
        binding.hint.setText(hintRes)
        binding.hint.visibility = android.view.View.VISIBLE
        binding.findRedactions.visibility =
            if (tool == StampCanvasView.Tool.REDACT) android.view.View.VISIBLE
            else android.view.View.GONE
        refreshSelection()
    }

    private fun disarm() {
        binding.canvas.tool = StampCanvasView.Tool.SELECT
        binding.hint.visibility = android.view.View.GONE
        binding.findRedactions.visibility = android.view.View.GONE
    }

    private fun addMark(mark: PageStamp.Mark) {
        editor.add(cascade(mark, editor.marks().size))
        disarm()
        refresh()
    }

    /** Each new mark is offset 6% down-right from the previous one; wraps after five. */
    private fun cascade(mark: PageStamp.Mark, index: Int): PageStamp.Mark {
        if (index <= 0) return mark
        val step = 0.06f * (index % 5)
        fun shiftedBox(box: PageStamp.Box): PageStamp.Box {
            val width = (box.right - box.left).coerceAtLeast(0.02f)
            val height = (box.bottom - box.top).coerceAtLeast(0.02f)
            val left = (box.left + step).coerceIn(0f, (1f - width).coerceAtLeast(0f))
            val top = (box.top + step).coerceIn(0f, (1f - height).coerceAtLeast(0f))
            return PageStamp.Box(left, top, left + width, top + height)
        }
        return when (mark) {
            is PageStamp.Mark.Text -> mark.copy(box = shiftedBox(mark.box))
            is PageStamp.Mark.Signature -> mark.copy(box = shiftedBox(mark.box))
            is PageStamp.Mark.Picture -> mark.copy(box = shiftedBox(mark.box))
            is PageStamp.Mark.Badge -> mark.copy(box = shiftedBox(mark.box))
            is PageStamp.Mark.Redact -> mark.copy(box = shiftedBox(mark.box))
            is PageStamp.Mark.Highlight -> mark.copy(box = shiftedBox(mark.box))
            is PageStamp.Mark.Freehand -> mark.copy(
                strokes = mark.strokes.map { stroke ->
                    PageStamp.Stroke(stroke.points.map {
                        PageStamp.Point(
                            (it.x + step).coerceIn(0f, 1f),
                            (it.y + step).coerceIn(0f, 1f)
                        )
                    })
                }
            )
            is PageStamp.Mark.Watermark, is PageStamp.Mark.PageNumber -> mark
        }
    }

    /** Test seams: the photo-ink save+place path without a gallery dialog. */
    internal fun placeImportedSignatureForTest(bitmap: Bitmap) {
        inkKind = InkKind.SIGNATURE
        saveInk(bitmap)
    }

    internal fun placeImportedStampForTest(bitmap: Bitmap) {
        inkKind = InkKind.STAMP
        saveInk(bitmap)
    }

    private fun addMarkToAll(mark: PageStamp.Mark) {
        editor.addToAll(mark)
        disarm()
        refresh()
    }

    private fun pickSignature() {
        StampPrompts.signature(
            this,
            signatureStore,
            onPick = { saved ->
                val file = saved.pngFile
                if (file != null) {
                    addMark(PageStamp.Mark.Picture(file, signatureBox()))
                } else {
                    addMark(
                        PageStamp.Mark.Signature(
                            strokes = saved.strokes,
                            box = signatureBox(),
                            color = android.graphics.Color.BLACK
                        )
                    )
                }
            },
            onImportPhoto = { startInkImport(InkKind.SIGNATURE) }
        )
    }

    private fun suggestRedactions() {
        val page = document.pageFiles.getOrNull(editor.currentPage)
        val geometry = page?.let { repo.ocrStore.readGeo(document.id)[it.name] }
        if (geometry == null) {
            note(getString(R.string.stamp_no_ocr))
            return
        }
        val suggestions = PageStamp.suggestRedactions(geometry)
        if (suggestions.isEmpty()) {
            note(getString(R.string.stamp_no_suggestions))
            return
        }
        val before = editor.marks().size
        editor.addAll(suggestions.map { PageStamp.Mark.Redact(it.box) })
        editor.select(before + suggestions.size - 1)
        disarm()
        refresh()
    }

    /**
     * Redact → **Find**: a "Find" button in the redact editor opens this sheet. It keeps the
     * Aadhaar quick path as a preset chip and offers the whole-number option for Aadhaar.
     */
    private fun openFindSheet() {
        if (findRunning) return
        val whole = CheckBox(this).apply {
            id = R.id.findWholeAadhaar
            text = getString(R.string.find_whole_aadhaar)
            setTextColor(SwStyle.color(this@StampEditorActivity, R.color.text_hologram_hi))
            minimumHeight = SwStyle.dp(this@StampEditorActivity, 48f)
            buttonTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(this@StampEditorActivity, R.color.cyan_primary)
            )
        }
        val preset = com.scanwhirl.app.ui.kit.SwChip(this).apply {
            id = R.id.findPresetAadhaar
            bind(
                R.drawable.ic_sym_visibility_off,
                getString(R.string.find_preset_aadhaar)
            )
            minimumHeight = SwStyle.dp(this@StampEditorActivity, 48f)
            setOnClickListener { askMaskOptions() }
        }
        val explanation = TextView(this).apply {
            text = getString(R.string.find_explanation)
            setTextColor(SwStyle.color(this@StampEditorActivity, R.color.text_hologram_mid))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.2f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(explanation)
            addView(
                preset,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = SwStyle.dp(this@StampEditorActivity, 12f)
                    bottomMargin = SwStyle.dp(this@StampEditorActivity, 4f)
                }
            )
            addView(whole)
        }
        SwSheet.builder(this)
            .setTitle(R.string.find_title)
            .setContent(content)
            .setPrimary(R.string.find_action) { runFind(whole.isChecked) }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun findKindLabel(kind: SensitiveScan.Kind): String = getString(
        when (kind) {
            SensitiveScan.Kind.AADHAAR -> R.string.find_kind_aadhaar
            SensitiveScan.Kind.VID -> R.string.find_kind_vid
            SensitiveScan.Kind.PAN -> R.string.find_kind_pan
            SensitiveScan.Kind.CARD -> R.string.find_kind_card
            SensitiveScan.Kind.PHONE -> R.string.find_kind_phone
            SensitiveScan.Kind.EMAIL -> R.string.find_kind_email
            SensitiveScan.Kind.IBAN -> R.string.find_kind_iban
            SensitiveScan.Kind.SSN -> R.string.find_kind_ssn
            SensitiveScan.Kind.ACCOUNT -> R.string.find_kind_account
        }
    )

    private fun runFind(wholeAadhaar: Boolean) {
        if (findRunning) return
        findRunning = true
        binding.hint.setText(R.string.find_scanning)
        binding.hint.visibility = View.VISIBLE
        lifecycleScope.launch {
            val hits = try {
                findScanner?.invoke(document.pageFiles) ?: scanSensitive(wholeAadhaar)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            findRunning = false
            binding.hint.visibility = View.GONE
            if (hits.isEmpty()) {
                note(getString(R.string.find_none))
                return@launch
            }
            showFindResults(hits)
        }
    }

    /** OCR (element boxes) on every page, then the detectors; saved geometry is the fallback. */
    private suspend fun scanSensitive(
        wholeAadhaar: Boolean
    ): List<Pair<Int, SensitiveScan.Hit>> = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<Int, SensitiveScan.Hit>>()
        document.pageFiles.forEachIndexed { index, file ->
            val words = runCatching {
                com.scanwhirl.app.util.OcrEngine.recognizeWords(file)
            }.getOrNull()
            if (words != null) {
                val found = SensitiveScan.find(
                    words.words.map {
                        SensitiveScan.Word(it.text, it.left, it.top, it.right, it.bottom)
                    },
                    words.page.width,
                    words.page.height,
                    wholeAadhaar
                )
                out += found.map { index to it }
            } else {
                val page = runCatching {
                    com.scanwhirl.app.util.OcrEngine.recognizeFile(file).page
                }.getOrNull()
                if (page != null) {
                    out += SensitiveScan.find(page, wholeAadhaar).map { index to it }
                }
            }
        }
        out
    }

    private fun showFindResults(hits: List<Pair<Int, SensitiveScan.Hit>>) {
        val groups = hits.groupBy { it.second.kind }
        val kinds = SensitiveScan.Kind.values().filter { groups.containsKey(it) }
        val labels = kinds.map { kind ->
            "${findKindLabel(kind)} (${groups.getValue(kind).size})"
        }
        val checked = BooleanArray(kinds.size) { true }
        SwSheet.builder(this)
            .setTitle(getString(R.string.find_results_title, hits.size))
            .setOptions(labels, multiChecked = checked) { index, isChecked ->
                checked[index] = isChecked
            }
            .setPrimary(R.string.find_hide_selected) { applyFind(hits, kinds, checked) }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun applyFind(
        hits: List<Pair<Int, SensitiveScan.Hit>>,
        kinds: List<SensitiveScan.Kind>,
        checked: BooleanArray
    ) {
        val chosenKinds = kinds.filterIndexed { index, _ -> checked[index] }.toSet()
        val chosen = hits.filter { it.second.kind in chosenKinds }
        if (chosen.isEmpty()) return
        editor.currentPage = chosen.minOf { it.first }
        var added = 0
        chosen.groupBy({ it.first }, { it.second.box }).forEach { (page, boxes) ->
            editor.addAllAt(page, boxes.map { PageStamp.Mark.Redact(it) })
            added += boxes.size
        }
        hideMode = true
        hideLabelRes = R.string.redact_label
        disarm()
        refresh()
        showPage()
        binding.hint.setText(getString(R.string.find_applied, added))
        binding.hint.visibility = View.VISIBLE
    }

    /**
     * Mask Aadhaar: find 12-digit Aadhaar / 16-digit VID numbers on every page (ML Kit element
     * boxes; stored OCR geometry when the model is unavailable), seed redaction boxes over the
     * first 8 digits, optionally hide the QR code, and let the user adjust them. The save path
     * uses the true redaction burn and names the copy with [OutputNames].
     */
    private fun askMaskOptions() {
        val checked = booleanArrayOf(true, true, false)
        SwSheet.builder(this)
            .setTitle(R.string.mask_options_title)
            .setOptions(
                listOf(
                    getString(R.string.mask_opt_number),
                    getString(R.string.mask_opt_vid),
                    getString(R.string.mask_opt_qr)
                ),
                multiChecked = checked
            ) { index, isChecked ->
                checked[index] = isChecked
            }
            .setPrimary(R.string.mask_continue) { maskAadhaar(checked[1], checked[2]) }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun maskAadhaar(hideVid: Boolean, hideQr: Boolean) {
        val id = document.id
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val geometries = runCatching { repo.ocrStore.readGeo(id) }.getOrDefault(emptyMap())
                val pages = ArrayList<Pair<Int, List<PageStamp.Box>>>()
                var count = 0
                document.pageFiles.forEachIndexed { index, page ->
                    val boxes = ArrayList<PageStamp.Box>()
                    // Device path: ML Kit element boxes (exact first two 4-digit groups).
                    val words = runCatching {
                        com.scanwhirl.app.util.OcrEngine.recognizeWords(page)
                    }.getOrNull()
                    if (words != null) {
                        boxes += com.scanwhirl.app.util.AadhaarMask.findWords(
                            words.words.map {
                                com.scanwhirl.app.util.AadhaarMask.Word(
                                    it.text, it.left, it.top, it.right, it.bottom
                                )
                            },
                            words.page.width,
                            words.page.height,
                            maskVid = hideVid
                        )
                    } else {
                        // Host/old-OCR fallback: the saved line geometry.
                        val geo = geometries[page.name]
                            ?: runCatching {
                                com.scanwhirl.app.util.OcrEngine.recognizeFile(page).page
                            }.getOrNull()
                        if (geo != null) {
                            boxes += com.scanwhirl.app.util.AadhaarMask.find(geo, maskVid = hideVid)
                        }
                    }
                    if (hideQr) {
                        val bitmap = com.scanwhirl.app.util.InkExtract.decodeSampled(page, 1400)
                        if (bitmap != null) {
                            com.scanwhirl.app.util.AadhaarMask.findQr(bitmap)?.let {
                                boxes += it
                            }
                            bitmap.recycle()
                        }
                    }
                    if (boxes.isNotEmpty()) {
                        pages += index to boxes
                        count += boxes.size
                    }
                }
                pages to count
            }
            val (pages, count) = found
            if (count == 0) {
                note(getString(R.string.mask_aadhaar_none))
                return@launch
            }
            val start = editor.currentPage
            if (pages.isNotEmpty()) editor.currentPage = pages.first().first
            pages.forEach { (index, boxes) ->
                editor.addAllAt(index, boxes.map { PageStamp.Mark.Redact(it) })
            }
            if (pages.isEmpty()) editor.currentPage = start
            hideMode = true
            hideLabelRes = R.string.mask_label
            showPage()
            binding.hint.setText(R.string.mask_aadhaar_hint)
            binding.hint.visibility = android.view.View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ page and chrome

    private fun showPage() {
        val page = document.pageFiles.getOrNull(editor.currentPage) ?: return
        val bitmap = loadPreview(page)
        if (bitmap == null) {
            note(getString(R.string.stamp_missing))
            return
        }
        binding.canvas.pageNumber = editor.currentPage + 1
        binding.canvas.pageCount = document.pageCount
        binding.canvas.pageBitmap = bitmap
        disarm()
        refresh()
    }

    private fun refresh() {
        binding.canvas.marks = editor.marks()
        refreshSelection()
        binding.canvas.invalidate()
    }

    private fun refreshSelection() {
        binding.canvas.selectedIndex = editor.selected
        binding.pageIndicator.text = getString(
            R.string.stamp_page_indicator,
            editor.currentPage + 1,
            editor.pageCount
        )
        val hasPrev = editor.currentPage > 0
        val hasNext = editor.currentPage < editor.pageCount - 1
        binding.prevPage.isEnabled = hasPrev
        binding.nextPage.isEnabled = hasNext
        binding.undo.isEnabled = editor.canUndo
        binding.redo.isEnabled = editor.canRedo
        binding.deleteMark.isEnabled = editor.selected != null
        binding.saveCopy.isEnabled = !editor.isEmpty() && !saving
        // v2 kit: a flat TextView does not dim itself when disabled, so say so plainly.
        binding.prevPage.alpha = if (hasPrev) 1f else DISABLED_ALPHA
        binding.nextPage.alpha = if (hasNext) 1f else DISABLED_ALPHA
        binding.undo.alpha = if (editor.canUndo) 1f else DISABLED_ALPHA
        binding.redo.alpha = if (editor.canRedo) 1f else DISABLED_ALPHA
        binding.deleteMark.alpha = if (editor.selected != null) 1f else DISABLED_ALPHA
        binding.saveCopy.alpha = if (!editor.isEmpty() && !saving) 1f else DISABLED_ALPHA
        binding.selectionHint.setText(
            if (editor.selected != null) R.string.stamp_hint_select else R.string.stamp_no_selection
        )
    }

    private fun save() {
        if (saving) return
        if (editor.isEmpty()) {
            note(getString(R.string.stamp_no_marks))
            return
        }
        // I9/E10: redaction destroys pixels and text in the copy — warn before anything is written.
        if (editor.hasRedaction()) {
            SwSheet.builder(this)
                .setTitle(R.string.stamp_redact_warn_title)
                .setMessage(R.string.stamp_redact_warn_message)
                .setPrimary(R.string.stamp_redact_warn_save) { writeCopy() }
                .setSecondary(R.string.stamp_keep_editing)
                .build()
                .show()
            return
        }
        writeCopy()
    }

    private fun writeCopy() {
        if (saving || editor.isEmpty()) return
        saving = true
        binding.saveCopy.isEnabled = false
        binding.saveCopy.setText(R.string.stamp_saving)
        val edits = editor.edits()
        lifecycleScope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    val suggested = if (hideMode) {
                        val existing = repo.listDocuments(includeVaulted = true).map { it.name }
                        OutputNames.forTool(
                            document.name,
                            getString(hideLabelRes),
                            existing
                        )
                    } else {
                        null
                    }
                    repo.stampDocument(document.id, edits, suggested)
                }
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_NEW_DOC_ID, copy.id)
                )
                Toast.makeText(
                    this@StampEditorActivity,
                    when {
                        hideLabelRes == R.string.mask_label ->
                            getString(R.string.mask_aadhaar_saved, copy.name)
                        hideMode -> getString(R.string.redacted_saved, copy.name)
                        else -> getString(R.string.stamp_saved, copy.name)
                    },
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                binding.saveCopy.setText(R.string.stamp_save_copy)
                binding.saveCopy.isEnabled = true
                note(getString(R.string.stamp_save_failed, e.message ?: ""))
            }
        }
    }

    private fun confirmExit() {
        // A leftover IME would consume the first Back; drop it before asking.
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        if (editor.isEmpty() || saving) {
            finish()
            return
        }
        SwSheet.builder(this)
            .setTitle(R.string.stamp_discard_title)
            .setMessage(R.string.stamp_discard_message)
            .setPrimary(R.string.stamp_discard) { finish() }
            .setSecondary(R.string.stamp_keep_editing)
            .build()
            .show()
    }

    private fun dp(value: Float): Int = SwStyle.dp(this, value)

    // ------------------------------------------------------------------ ink from a photo

    /** "From photo": gallery or camera, then the preview/trim/recolour sheet. */
    private fun startInkImport(kind: InkKind) {
        inkKind = kind
        SwSheet.builder(this)
            .setTitle(R.string.stamp_import_photo)
            .setOptions(
                listOf(
                    getString(R.string.stamp_import_choose),
                    getString(R.string.stamp_import_camera)
                )
            ) { which, _ ->
                if (which == 0) inkGallery.launch("image/*") else launchInkCamera()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun launchInkCamera() {
        val dir = File(cacheDir, "documents").apply { mkdirs() }
        val file = File(dir, "ink_${UUID.randomUUID().toString().take(8)}.jpg")
        pendingPhotoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        inkCamera.launch(uri)
    }

    private fun copyPhotoToCache(uri: android.net.Uri): File? = try {
        val dir = File(cacheDir, "documents").apply { mkdirs() }
        val file = File(dir, "ink_${UUID.randomUUID().toString().take(8)}.jpg")
        val bytes = contentResolver.openInputStream(uri)?.use(ImageTools::readPageBytes)
        if (bytes == null) null else {
            com.scanwhirl.app.util.Durable.writeFile(file, bytes)
            file
        }
    } catch (_: Exception) {
        null
    }

    private fun loadInk(file: File) {
        val bitmap = InkExtract.decodeSampled(file)
        if (bitmap == null) {
            note(getString(R.string.stamp_ink_none))
            return
        }
        showInkPreview(bitmap)
    }

    /** Preview on a checkerboard, with Original / Black / Blue for a signature. */
    private fun showInkPreview(source: Bitmap) {
        var current: InkExtract.Extracted? =
            InkExtract.extract(source, InkExtract.Recolor.ORIGINAL)
        if (current == null) {
            source.recycle()
            note(getString(R.string.stamp_ink_none))
            return
        }

        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = checkerboard()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210f)
            )
            setImageBitmap(current!!.bitmap)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview)
        }
        if (inkKind == InkKind.SIGNATURE) {
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(10f), 0, 0)
            }
            listOf(
                InkExtract.Recolor.ORIGINAL to R.string.stamp_ink_original,
                InkExtract.Recolor.BLACK to R.string.stamp_ink_black,
                InkExtract.Recolor.BLUE to R.string.stamp_ink_blue
            ).forEachIndexed { index, (recolor, label) ->
                val chip = SwStyle.text(
                    this@StampEditorActivity, 14f,
                    SwStyle.color(this@StampEditorActivity, R.color.cyan_primary),
                    bold = true
                ).apply {
                    text = getString(label)
                    gravity = Gravity.CENTER
                    minWidth = dp(84f)
                    minHeight = dp(44f)
                    setPadding(dp(10f), 0, dp(10f), 0)
                }
                chip.setOnClickListener {
                    val next = InkExtract.extract(source, recolor) ?: return@setOnClickListener
                    current?.bitmap?.recycle()
                    current = next
                    preview.setImageBitmap(next.bitmap)
                }
                chips.addView(chip, LinearLayout.LayoutParams(0, dp(48f), 1f))
            }
            content.addView(chips)
        }

        SwSheet.builder(this)
            .setTitle(R.string.stamp_ink_title)
            .setContent(content)
            .setPrimary(R.string.stamp_ink_save) {
                val bitmap = current?.bitmap ?: return@setPrimary
                saveInk(bitmap)
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun saveInk(bitmap: Bitmap) {
        try {
            if (inkKind == InkKind.SIGNATURE) {
                val name = getString(R.string.stamp_signature_name, signatureStore.list().size + 1)
                val saved = signatureStore.saveBitmap(name, bitmap)
                addMark(PageStamp.Mark.Picture(requireNotNull(saved.pngFile), signatureBox()))
            } else {
                val name = getString(R.string.stamp_stamp_name, stampStore.list().size + 1)
                val saved = stampStore.save(name, bitmap)
                addMark(PageStamp.Mark.Picture(saved.pngFile, badgeBox()))
            }
            bitmap.recycle()
        } catch (e: Exception) {
            note(e.message ?: getString(R.string.stamp_stamp_full))
        }
    }

    private fun checkerboard(): Drawable {
        val cell = 12
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(0xFFEDEDED.toInt())
        val paint = Paint().apply { color = 0xFFC9C9C9.toInt() }
        canvas.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint)
        canvas.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, paint)
        return BitmapDrawable(resources, tile).apply {
            tileModeX = Shader.TileMode.REPEAT
            tileModeY = Shader.TileMode.REPEAT
        }
    }

    private fun dimenDp(res: Int): Float =
        resources.getDimension(res) / resources.displayMetrics.density

    // ------------------------------------------------------------------ kit chrome

    /**
     * v2 UI: 03_tools_hub — the screen's own buttons are painted by the kit: the 54dp cyan
     * primary (the same as SwSheet's Save), glass secondary for Undo/Redo/Delete and the page
     * navigator. Radii and the save height come from dimens_v2 so they follow the tokens.
     */
    private fun applyKitChrome() {
        val radiusMedium = dimenDp(R.dimen.sw_radius_medium)
        val radiusSmall = dimenDp(R.dimen.sw_radius_small)
        val radiusLarge = dimenDp(R.dimen.sw_radius_large)

        fun paint(view: TextView, fill: Int, ink: Int, stroke: Int?, radius: Float) {
            view.background = SwStyle.rounded(this, fill, radiusDp = radius, stroke = stroke)
            view.setTextColor(ink)
            view.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            view.gravity = Gravity.CENTER
        }

        paint(
            binding.saveCopy,
            SwStyle.color(this, R.color.cyan_primary),
            SwStyle.color(this, R.color.sw_disc_ink),
            null,
            radiusMedium
        )
        paint(
            binding.undo,
            SwStyle.color(this, R.color.glass_surface_raised),
            SwStyle.color(this, R.color.text_hologram_hi),
            SwStyle.color(this, R.color.sw_cyan_hair),
            radiusMedium
        )
        paint(
            binding.redo,
            SwStyle.color(this, R.color.glass_surface_raised),
            SwStyle.color(this, R.color.text_hologram_hi),
            SwStyle.color(this, R.color.sw_cyan_hair),
            radiusMedium
        )
        paint(
            binding.deleteMark,
            SwStyle.color(this, R.color.laser_danger_surface),
            SwStyle.color(this, R.color.laser_danger),
            SwStyle.color(this, R.color.laser_danger_border),
            radiusMedium
        )
        paint(
            binding.prevPage,
            SwStyle.color(this, R.color.glass_surface_high),
            SwStyle.color(this, R.color.cyan_primary),
            SwStyle.color(this, R.color.sw_cyan_hair),
            999f
        )
        paint(
            binding.nextPage,
            SwStyle.color(this, R.color.glass_surface_high),
            SwStyle.color(this, R.color.cyan_primary),
            SwStyle.color(this, R.color.sw_cyan_hair),
            999f
        )
        binding.pageNav.background = SwStyle.rounded(
            this,
            SwStyle.color(this, R.color.glass_surface_raised),
            radiusDp = radiusLarge,
            stroke = SwStyle.color(this, R.color.sw_cyan_hair_strong)
        )
        binding.hint.background = SwStyle.rounded(
            this,
            SwStyle.color(this, R.color.glass_surface),
            radiusDp = radiusSmall,
            stroke = SwStyle.color(this, R.color.sw_cyan_hair)
        )
    }

    private fun note(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ defaults and preview

    private fun textBox() = PageStamp.Box(0.08f, 0.06f, 0.92f, 0.16f)

    private fun signatureBox() = PageStamp.Box(0.22f, 0.58f, 0.78f, 0.72f)

    private fun badgeBox() = PageStamp.Box(0.52f, 0.62f, 0.92f, 0.75f)

    /** Display copy only: sampled to ≤ 1400 px, freed by [StampCanvasView] when replaced. */
    private fun loadPreview(page: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(page.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w > 1400 || h > 1400) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(page.path, options)
    }
}
