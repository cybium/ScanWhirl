package com.scanwhirl.app.ui.stamp

import android.content.Context
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
import android.widget.Button
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
import com.scanwhirl.app.ui.V2vFailureCopy
import com.scanwhirl.app.ui.TrashActions
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

        /** C1 item 4: Back is held only this long during a Save, then the person may leave. */
        internal var saveTimeoutMs: Long = 60_000L

        // H5-1: recreation (rotation, dark mode, font size, language) must not lose user work.
        private const val KEY_MARKS_FILE = "com.scanwhirl.app.ui.stamp.MARKS_FILE"
        private const val KEY_PENDING_PHOTO = "com.scanwhirl.app.ui.stamp.PENDING_PHOTO"
        private const val KEY_INK_KIND = "com.scanwhirl.app.ui.stamp.INK_KIND"
        private const val KEY_HIDE_MODE = "com.scanwhirl.app.ui.stamp.HIDE_MODE"
        private const val KEY_HIDE_LABEL = "com.scanwhirl.app.ui.stamp.HIDE_LABEL"

        /** Cache folder for the serialised editor while the screen is being recreated. */
        private const val STATE_DIR = "stamp_editor"

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
    internal var pendingPhotoFile: File? = null

    /** H5-1: the file holding this screen's marks while it is being recreated. */
    private var stateFile: File? = null

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
    private var hideLabelRes = R.string.private_info_label

    private var findRunning = false

    /**
     * What one "Hide private info" run found. [unreadablePages] are 0-based indexes whose text
     * could not be read at all: those pages were not checked, so they are never called clean.
     */
    internal data class FindOutcome(
        val hits: List<Pair<Int, SensitiveScan.Hit>>,
        val unreadablePages: List<Int> = emptyList()
    )

    /** Test seam: replaces OCR detection with synthetic per-page hits. */
    internal var findScanner: (suspend (List<File>) -> FindOutcome)? = null

    private var saving = false
    private var savingStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backstop: this screen is the whole Pro Edit & sign suite. Whichever caller opened it
        // (the reader dock, a future flow), a Free build gets the Pro offer and no editor.
        if (!com.scanwhirl.app.ui.kit.ProOffer.require(this)) {
            finish()
            return
        }
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
        // C1: the process-scoped save reports to whichever screen is alive when it finishes.
        StampSaveRunner.setReceiver(doc.id, this)
        editor = StampEditorState(doc.pageCount)
        if (savedInstanceState != null) restoreEditorState(savedInstanceState)

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

            override fun onGestureStart() {
                editor.beginGesture()
            }

            override fun onGestureEnd() {
                editor.endGesture()
                refresh()
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
        // UXG G5: Find is toggled by arm() rather than styled by paint(), so it never received
        // the Button role the other painted actions get. Give it the same focus/clickable/Button
        // semantics so TalkBack announces "Find, button" and keyboard focus can reach it.
        binding.findRedactions.apply {
            isFocusable = true
            isClickable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: android.view.accessibility.AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
        }
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
        if (savedInstanceState == null) {
            applyRequestedTool(intent.getStringExtra(EXTRA_TOOL))
        } else {
            // H5-1: the entry tool belongs to the first launch only; a recreation re-joins a
            // Save that was already going instead of offering a second copy.
            resumeRunningSave()
        }
    }

    // ------------------------------------------------------------------ recreation (H5-1)

    /**
     * H5-1: no `android:configChanges` is declared, so rotation, dark mode, a font-size change
     * and a language change all destroy this screen. The marks are too large to trust to a
     * Bundle (freehand points, imported stamps), so they are written to a small file in the
     * cache folder whose path travels in the Bundle; the file is deleted as soon as it is read.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::editor.isInitialized || !::document.isInitialized) return
        outState.putString(KEY_PENDING_PHOTO, pendingPhotoFile?.path)
        outState.putString(KEY_INK_KIND, inkKind.name)
        outState.putBoolean(KEY_HIDE_MODE, hideMode)
        outState.putInt(KEY_HIDE_LABEL, hideLabelRes)
        val dir = File(cacheDir, STATE_DIR).apply { mkdirs() }
        val file = File(dir, "state_${document.id}_${UUID.randomUUID().toString().take(8)}.bin")
        try {
            java.io.DataOutputStream(
                java.io.BufferedOutputStream(java.io.FileOutputStream(file))
            ).use { editor.writeTo(it) }
            stateFile?.delete()
            stateFile = file
            outState.putString(KEY_MARKS_FILE, file.path)
        } catch (_: Exception) {
            file.delete()
        }
    }

    /** H5-1: read back everything [onSaveInstanceState] kept, before the screen is drawn. */
    private fun restoreEditorState(saved: Bundle) {
        // The camera app writes this file while the screen is away: keep the path even before
        // the bytes exist, or a recreation during capture would drop the photo again.
        pendingPhotoFile = saved.getString(KEY_PENDING_PHOTO)?.let(::File)
        inkKind = saved.getString(KEY_INK_KIND)
            ?.let { name -> InkKind.values().firstOrNull { it.name == name } }
            ?: InkKind.SIGNATURE
        hideMode = saved.getBoolean(KEY_HIDE_MODE, false)
        hideLabelRes = saved.getInt(KEY_HIDE_LABEL, R.string.private_info_label)
        val file = saved.getString(KEY_MARKS_FILE)?.let(::File) ?: return
        try {
            java.io.DataInputStream(
                java.io.BufferedInputStream(java.io.FileInputStream(file))
            ).use { editor.readFrom(it) }
        } catch (_: Exception) {
            // Unreadable state never blocks the document: the editor opens on a clean page.
        } finally {
            file.delete()
        }
    }

    /**
     * H5-1: after a recreation during Save, wait for the same write instead of starting a second
     * one, so the library never gains a silent duplicate. The marks stay on screen meanwhile.
     */
    private fun resumeRunningSave() {
        if (StampSaveRunner.pendingFor(document.id, SaveMeta(editor.edits(), hideLabelResForSave())) == null) return
        saving = true
        savingStartedAt = System.currentTimeMillis()
        binding.saveCopy.setText(R.string.c1_still_saving)
        refreshSelection()
        // The runner's single process-scope watcher (started by writeCopy) reports to the
        // receiver registered in onCreate, so a recreation must not start a second delivery.
    }

    /** 0 means a plain edit; otherwise the label of a hide/Find save. */
    private fun hideLabelResForSave(): Int = if (hideMode) hideLabelRes else 0

    override fun onDestroy() {
        super.onDestroy()
        // Only a real close discards the state; a recreation needs it after this returns.
        if (!isFinishing) return
        stateFile?.delete()
        // C1 review P1: a bounded-timeout exit must NOT consume the in-flight promise — the write
        // is still running and an editor reopened with the same marks must be able to join it.
        // Only the destroyed screen's receipt target is removed; delivery falls back to a Toast.
        if (::document.isInitialized) StampSaveRunner.clearReceiver(document.id)
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
        // V2V V2: one saved signature, placed on every page, previewed first and
        // undoable in one step. The screen itself is already Pro-gated on entry.
        // Short visible label for 200% reflow; the full contract is spoken.
        tile(R.id.v2vApplySignature, R.string.v2v_apply_signature_short, R.drawable.ic_sym_signature) {
            promptApplySignature()
        }.apply {
            // FX1 critic: the selected-mark action names the real page count, not a fixed
            // slogan. The count makes the label longer than a standard tile, so this one tile
            // is wider; otherwise the first line clips at 1.3x (AccessibilityAuditTest).
            labelView.text = getString(R.string.v2v_apply_to_all_count, editor.pageCount)
            layoutParams.width = dp(100f)
            contentDescription = getString(R.string.cdf1_apply_signature)
            row.addView(this)
        }
        add(R.id.toolText, R.string.stamp_tool_text, R.drawable.ic_sym_text_fields) {
            promptText()
        }
        add(R.id.toolDraw, R.string.stamp_tool_draw, R.drawable.ic_sym_draw) {
            arm(StampCanvasView.Tool.DRAW, R.string.stamp_hint_draw)
        }
        add(R.id.toolHighlight, R.string.stamp_tool_highlight, R.drawable.ic_sym_ink_highlighter) {
            arm(StampCanvasView.Tool.HIGHLIGHT, R.string.stamp_hint_highlight)
        }
        add(R.id.toolWatermark, R.string.stamp_tool_watermark, R.drawable.ic_sym_watermark) {
            promptWatermark()
        }
        add(R.id.toolPageNumbers, R.string.stamp_tool_page_numbers, R.drawable.ic_sym_format_list_numbered) {
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
            // V2V: long labels wrap to three full lines instead of ellipsising at 200%.
            labelView.maxLines = 3
            labelView.ellipsize = null
            setOnClickListener { action() }
            minimumHeight = dp(68f)
            layoutParams = LinearLayout.LayoutParams(
                dp(82f), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(2f)
            }
        }
    }

    /** V2V V2: preview the multi-page placement, then apply it as one undoable step. */
    private fun promptApplySignature() {
        val saved = signatureStore.list().firstOrNull { it.pngFile?.isFile == true }
        val file = saved?.pngFile
        if (file == null) {
            note(getString(R.string.v2v_no_saved_signature))
            return
        }
        SwSheet.builder(this)
            .setTitle(R.string.cdf1_apply_signature)
            .setMessage(R.string.v2v_apply_signature_preview)
            .setPrimary(R.string.cdf1_apply_signature_apply) { applySignatureToAllForTest(file) }
            .setSecondary(R.string.stamp_keep_editing) { }
            .build()
            .show()
    }

    /**
     * FX1 critic: the receipt is emitted ONLY when a signature mark really is on every page.
     * It counts the pages from the editor state, never from the intent, so a partial batch can
     * never produce a "added to N pages" lie.
     */
    internal fun signatureReceipt(): String? {
        val pages = editor.pageCount
        val complete = (0 until pages).all { page ->
            editor.marks(page).any { it is PageStamp.Mark.Picture || it is PageStamp.Mark.Signature }
        }
        if (!complete) return null
        // COPY_DIET: "Signed N pages" is the deciding receipt; Undo is a separate action.
        return resources.getQuantityString(R.plurals.cdf1_signed_pages, pages, pages)
    }

    /** Test seam: place a saved signature on every page via the editor's undoable batch. */
    internal fun applySignatureToAllForTest(file: java.io.File) {
        addMarkToAll(PageStamp.Mark.Picture(file, signatureBox()))
        // FX1: the old toast named no number and vanished before the person reached page 2,
        // so the mark looked as if it had gone missing. Keep a countable receipt ON the screen
        // and announce it politely only when the batch really covered every page.
        val receipt = signatureReceipt()
        if (receipt == null) {
            note(getString(R.string.stamp_no_marks))
            return
        }
        binding.hint.text = receipt
        binding.hint.contentDescription = receipt
        binding.hint.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        binding.hint.visibility = View.VISIBLE
        noteWithUndo(receipt)
    }

    /** COPY_DIET: the receipt and Undo are two distinct controls, not one sentence. */
    private fun noteWithUndo(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.cdf1_undo) { if (editor.undo()) refresh() }
            .show()
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
            note(getString(R.string.cdf1_stamp_no_suggestions))
            return
        }
        val before = editor.marks().size
        editor.addAll(suggestions.map { PageStamp.Mark.Redact(it.box) })
        editor.select(before + suggestions.size - 1)
        disarm()
        refresh()
    }

    /**
     * Redact → **Hide private info**: the sheet first asks WHAT to hide (all choices on), then
     * Find scans only those kinds and lists the counts; the chosen groups become adjustable
     * redaction boxes.
     */
    private fun openFindSheet() {
        if (findRunning) return
        data class Choice(val kinds: Set<SensitiveScan.Kind>, val label: Int, val id: Int)
        val choices = listOf(
            Choice(setOf(SensitiveScan.Kind.PHONE), R.string.find_choice_phone, R.id.findChoicePhone),
            Choice(setOf(SensitiveScan.Kind.EMAIL), R.string.find_choice_email, R.id.findChoiceEmail),
            Choice(
                setOf(
                    SensitiveScan.Kind.AADHAAR, SensitiveScan.Kind.VID,
                    SensitiveScan.Kind.PAN, SensitiveScan.Kind.DRIVING_LICENCE,
                    SensitiveScan.Kind.PASSPORT
                ),
                R.string.find_choice_id,
                R.id.findChoiceId
            ),
            Choice(
                setOf(SensitiveScan.Kind.CARD, SensitiveScan.Kind.IBAN, SensitiveScan.Kind.ACCOUNT),
                R.string.find_choice_cards,
                R.id.findChoiceCards
            ),
            Choice(setOf(SensitiveScan.Kind.SSN), R.string.find_choice_other, R.id.findChoiceOther)
        )
        val boxes = choices.map { choice ->
            CheckBox(this).apply {
                id = choice.id
                text = getString(choice.label)
                isChecked = true
                setTextColor(SwStyle.color(this@StampEditorActivity, R.color.text_hologram_hi))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                minimumHeight = SwStyle.dp(this@StampEditorActivity, 48f)
                maxLines = 3
                isSingleLine = false
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(this@StampEditorActivity, R.color.cyan_primary)
                )
            }
        }
        val keep4 = CheckBox(this).apply {
            id = R.id.findKeepLast4
            text = getString(R.string.find_choice_keep4)
            isChecked = false
            setTextColor(SwStyle.color(this@StampEditorActivity, R.color.text_hologram_mid))
            minimumHeight = SwStyle.dp(this@StampEditorActivity, 48f)
            buttonTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(this@StampEditorActivity, R.color.cyan_primary)
            )
        }
        val explanation = TextView(this).apply {
            text = getString(R.string.cdf1_find_explanation)
            setTextColor(SwStyle.color(this@StampEditorActivity, R.color.text_hologram_mid))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.2f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(explanation)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            boxes.forEach { addView(it, params) }
            addView(keep4, params)
        }
        SwSheet.builder(this)
            .setTitle(R.string.find_title)
            .setContent(content)
            .setPrimary(R.string.find_action) {
                val selected = choices.filterIndexed { index, _ -> boxes[index].isChecked }
                    .flatMap { it.kinds }
                    .toSet()
                if (selected.isEmpty()) {
                    note(getString(R.string.find_choose_one))
                } else {
                    runFind(selected, keep4.isChecked)
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun categoryLabel(kind: SensitiveScan.Kind): Int = when (kind) {
        SensitiveScan.Kind.PHONE -> R.string.find_choice_phone
        SensitiveScan.Kind.EMAIL -> R.string.find_choice_email
        SensitiveScan.Kind.AADHAAR, SensitiveScan.Kind.VID, SensitiveScan.Kind.PAN,
        SensitiveScan.Kind.DRIVING_LICENCE, SensitiveScan.Kind.PASSPORT ->
            R.string.find_choice_id
        SensitiveScan.Kind.CARD, SensitiveScan.Kind.IBAN, SensitiveScan.Kind.ACCOUNT ->
            R.string.find_choice_cards
        SensitiveScan.Kind.SSN -> R.string.find_choice_other
    }

    /** Test seam: run Find with a deterministic scanner and no dialog in the way. */
    internal fun runFindForTest(
        kinds: Set<SensitiveScan.Kind> = setOf(SensitiveScan.Kind.PHONE),
        keepLast4: Boolean = false
    ) = runFind(kinds, keepLast4)

    private fun runFind(kinds: Set<SensitiveScan.Kind>, keepLast4: Boolean) {
        if (findRunning) return
        findRunning = true
        binding.hint.setText(R.string.find_scanning)
        binding.hint.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = try {
                findScanner?.invoke(document.pageFiles) ?: scanSensitive(kinds, keepLast4)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The whole run failed: every page is unchecked, never "nothing found".
                FindOutcome(emptyList(), document.pageFiles.indices.toList())
            }
            findRunning = false
            binding.hint.visibility = View.GONE
            val hits = outcome.hits.filter { it.second.kind in kinds }
            if (hits.isEmpty()) {
                if (outcome.unreadablePages.isEmpty()) {
                    // Text was read on every page and nothing matched. The snackbar
                    // keeps the existing contract; the sheet makes the honest empty
                    // outcome persistent and visible where the person was looking.
                    note(getString(R.string.cdf1_find_none_body))
                    SwSheet.builder(this@StampEditorActivity)
                        .setTitle(R.string.cdf1_find_none_title)
                        .setMessage(R.string.cdf1_find_none_body)
                        .setPrimary(R.string.cdf1_find_review_page) {
                            arm(StampCanvasView.Tool.REDACT, R.string.stamp_hint_redact)
                        }
                        .build()
                        .show()
                } else {
                    warnUnreadable(outcome.unreadablePages)
                }
                return@launch
            }
            showFindResults(hits, outcome.unreadablePages)
        }
    }

    /**
     * At least one page's text could not be read: say so, say how many pages, and offer the
     * manual box tool. Never a "no private info" line on an unchecked page.
     */
    private fun warnUnreadable(pages: List<Int>) {
        SwSheet.builder(this)
            .setTitle(R.string.find_title)
            .setMessage(unreadableMessage(pages))
            .setPrimary(R.string.cdf1_find_draw_box) {
                arm(StampCanvasView.Tool.REDACT, R.string.stamp_hint_redact)
            }
            .setSecondary(R.string.sheet_got_it)
            .build()
            .show()
    }

    /** "this page" for one failure, the page count for several. */
    private fun unreadableMessage(pages: List<Int>): String =
        if (pages.size == 1) {
            getString(R.string.cdf1_find_unreadable_one)
        } else {
            resources.getQuantityString(R.plurals.find_unreadable_pages, pages.size, pages.size)
        }

    /**
     * OCR (element boxes) on every page, then the detectors; saved geometry is the fallback.
     * A page with no recognisable text (recognizer error, model unavailable, empty result) is
     * reported as unreadable instead of contributing an empty, falsely clean result.
     */
    private suspend fun scanSensitive(
        kinds: Set<SensitiveScan.Kind>,
        keepLast4: Boolean
    ): FindOutcome = withContext(Dispatchers.IO) {
        val out = ArrayList<Pair<Int, SensitiveScan.Hit>>()
        val unreadable = ArrayList<Int>()
        document.pageFiles.forEachIndexed { index, file ->
            val words = runCatching {
                com.scanwhirl.app.util.OcrEngine.recognizeWords(file)
            }.getOrNull()
            if (words != null && words.words.isNotEmpty()) {
                val found = SensitiveScan.find(
                    words.words.map {
                        SensitiveScan.Word(it.text, it.left, it.top, it.right, it.bottom)
                    },
                    words.page.width,
                    words.page.height,
                    kinds,
                    keepLast4,
                    // C1: "Keep last 4 digits" applies to cards too. Unchecked, every box covers
                    // the whole match, including the card's last four digits (no PCI exception
                    // in this copy). Checked, cards keep their last four as before.
                    fullMask = !keepLast4
                )
                out += found.map { index to it }
                return@forEachIndexed
            }
            val page = runCatching {
                com.scanwhirl.app.util.OcrEngine.recognizeFile(file).page
            }.getOrNull()
            if (page != null && page.lines.any { it.text.isNotBlank() }) {
                out += SensitiveScan.find(
                    page, kinds, keepLast4, fullMask = !keepLast4
                ).map { index to it }
            } else {
                unreadable += index
            }
        }
        FindOutcome(out, unreadable)
    }

    private fun showFindResults(
        hits: List<Pair<Int, SensitiveScan.Hit>>,
        unreadablePages: List<Int> = emptyList()
    ) {
        val groups = hits.groupBy { categoryLabel(it.second.kind) }
        val order = listOf(
            R.string.find_choice_phone, R.string.find_choice_email, R.string.find_choice_id,
            R.string.find_choice_cards, R.string.find_choice_other
        )
        val categories = order.filter { groups.containsKey(it) }
        val labels = categories.map { label ->
            "${getString(label)} (${groups.getValue(label).size})"
        }
        val checked = BooleanArray(categories.size) { true }
        val builder = SwSheet.builder(this)
            .setTitle(
                resources.getQuantityString(R.plurals.find_results_title, hits.size, hits.size)
            )
        // Partial failure: the counts below do not cover the pages that could not be read.
        if (unreadablePages.isNotEmpty()) builder.setMessage(unreadableMessage(unreadablePages))
        builder
            .setOptions(labels, multiChecked = checked) { index, isChecked ->
                checked[index] = isChecked
            }
            .setPrimary(R.string.find_hide_selected) { applyFind(hits, categories, checked) }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun applyFind(
        hits: List<Pair<Int, SensitiveScan.Hit>>,
        categories: List<Int>,
        checked: BooleanArray
    ) {
        val chosenCategories = categories.filterIndexed { index, _ -> checked[index] }.toSet()
        val chosen = hits.filter { categoryLabel(it.second.kind) in chosenCategories }
        if (chosen.isEmpty()) {
            // C1 review P2: unchecking every category hides nothing. Do not let a *previous*
            // Find's "private info hidden" label survive onto this run's copy; the only truth is
            // whether redaction marks are actually on the page now.
            hideMode = editor.hasRedaction()
            if (!hideMode) hideLabelRes = R.string.private_info_label
            return
        }
        editor.currentPage = chosen.minOf { it.first }
        var added = 0
        chosen.groupBy({ it.first }, { it.second.box }).forEach { (page, boxes) ->
            editor.addAllAt(page, boxes.map { PageStamp.Mark.Redact(it) })
            added += boxes.size
        }
        hideMode = true
        // C1: only claim "private info hidden" when every found item was actually hidden.
        hideLabelRes = if (checked.all { it }) R.string.private_info_label else R.string.c1_redacted_label
        disarm()
        refresh()
        showPage()
        binding.hint.setText(
            resources.getQuantityString(R.plurals.find_applied, added, added)
        )
        binding.hint.visibility = View.VISIBLE
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

    internal fun refresh() {
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
            if (editor.selected != null) R.string.cdf1_stamp_hint_select else R.string.stamp_no_selection
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
        savingStartedAt = System.currentTimeMillis()
        binding.saveCopy.isEnabled = false
        binding.saveCopy.setText(R.string.stamp_saving)
        val docId = document.id
        val sourceName = document.name
        val edits = editor.edits()
        val hide = hideMode
        val labelRes = hideLabelRes
        val meta = SaveMeta(edits, if (hide) labelRes else 0)
        val app = applicationContext
        // C1 review P1: a write for this source document AND these exact marks may already be in
        // flight (this screen was recreated, or a timed-out editor was reopened). Join it — say so
        // on the button — instead of starting a second write and silently making a second copy.
        if (StampSaveRunner.pendingFor(docId, meta) != null) {
            binding.saveCopy.setText(R.string.c1_still_saving)
        } else {
            StampSaveRunner.submit(docId, meta) {
                withContext(Dispatchers.IO) {
                    val writer = ScanRepository(app)
                    val suggested = if (hide) {
                        val existing = writer.listDocuments(includeVaulted = true).map { it.name }
                        OutputNames.forTool(sourceName, app.getString(labelRes), existing)
                    } else {
                        null
                    }
                    writer.stampDocument(docId, edits, suggested)
                }
            }
        }
        // Delivery is owned by the runner's process scope, not this screen's lifecycleScope: a
        // person who leaves after the new bounded timeout still gets the receipt as a Toast.
    }

    /** Test seam for the persistent receipt without going through the async runner. */
    internal fun deliverSaveForTest(result: Result<ScanDocument>, labelRes: Int = 0) =
        deliverSave(result, labelRes)

    /**
     * V2V V2: a redaction outcome is permanent until the person dismisses it. It
     * names the new copy, says the original still holds the information, and offers
     * a NAMED, CONFIRMED Trash route. Nothing is deleted automatically.
     */
    private fun showPersistentReceipt(copy: ScanDocument) {
        SwSheet.builder(this)
            .setTitle(R.string.v2v_redact_receipt_title)
            .setMessage(getString(R.string.cdf1_redact_receipt_body, copy.name))
            // P2: the safe, reversible choice is primary; Trash is the secondary.
            .setPrimary(R.string.v2v_keep_original) { }
            .setSecondary(R.string.v2v_move_original_to_trash) {
                confirmTrashOriginal(document.id)
            }
            .build()
            .show()
    }

    private fun confirmTrashOriginal(id: String) {
        SwSheet.builder(this)
            .setTitle(R.string.v2v_trash_original_confirm)
            .setMessage(R.string.v2v_trash_original_body)
            .setPrimary(R.string.v2v_keep_original) { }
            .setSecondary(R.string.v2v_move_original_to_trash) {
                // Same path as every other Trash route: off the main thread, with the
                // named Undo snackbar. Never delete without this confirmation.
                lifecycleScope.launch {
                    val moved = withContext(Dispatchers.IO) {
                        TrashActions.moveAll(repo, listOf(id))
                    }
                    if (moved.keys.isEmpty()) {
                        note(getString(R.string.trash_move_failed))
                        return@launch
                    }
                    val root = findViewById<View>(android.R.id.content) ?: window.decorView
                    TrashActions.showMoved(this@StampEditorActivity, root, null, moved) { }
                    note(getString(R.string.v2v_trash_original_done))
                }
            }
            .build()
            .show()
    }

    /** Reports the finished save on whichever instance of the screen is alive, exactly once. */
    internal fun deliverSave(result: Result<ScanDocument>, labelRes: Int = 0) {
        StampSaveRunner.consume(document.id)
        val alive = !isFinishing && !isDestroyed
        result.onSuccess { copy ->
            if (alive) setResult(RESULT_OK, Intent().putExtra(EXTRA_NEW_DOC_ID, copy.id))
            val redaction = labelRes == R.string.c1_redacted_label ||
                labelRes == R.string.private_info_label
            if (alive && redaction) {
                // V2V V2: the redaction outcome is permanent until dismissed.
                showPersistentReceipt(copy)
            } else {
                val saved = if (labelRes != 0) {
                    getString(savedReceiptRes(labelRes), copy.name)
                } else getString(R.string.stamp_saved, copy.name)
                // App context after a bounded-timeout exit: still one honest receipt.
                Toast.makeText(applicationContext, saved, Toast.LENGTH_LONG).show()
            }
            if (alive && !redaction) finish()
        }.onFailure { error ->
            val failed = getString(R.string.stamp_save_failed,
                V2vFailureCopy.message(this@StampEditorActivity, error))
            if (alive) {
                saving = false
                binding.saveCopy.setText(R.string.stamp_save_copy)
                binding.saveCopy.isEnabled = true
                note(failed)
            } else {
                Toast.makeText(applicationContext, failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmExit() {
        // A leftover IME would consume the first Back; drop it before asking.
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        // E1: leaving mid-save used to create the copy with no receipt (the result went to a
        // dead screen) and invite a duplicate Save on reopen. Keep the screen until the write
        // reports back; a real close with marks still asks to discard as before.
        if (saving) {
            if (System.currentTimeMillis() - savingStartedAt >= saveTimeoutMs) {
                // C1: a bounded wait. The write keeps running in the process-scoped runner and
                // its receipt is reported by deliverSave (app-context Toast), so leaving here
                // cannot lose the copy or its receipt.
                note(getString(R.string.cdf1_saving_timed_out))
                finish()
            } else {
                note(getString(R.string.stamp_saving_wait))
            }
            return
        }
        if (editor.isEmpty()) {
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
            note(V2vFailureCopy.message(this, e))
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
            // UX-L4-04: these painted TextViews are actions. TalkBack must hear a
            // Button with its enabled state, and keyboard focus must reach them.
            view.isFocusable = true
            view.isClickable = true
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: android.view.accessibility.AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
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

/**
 * What one in-flight save is writing, so an editor that reopens with the same marks joins that
 * write instead of starting a second one (C1 review P1). [labelRes] is 0 for a plain edit,
 * otherwise the hide label the copy was named with.
 */
internal data class SaveMeta(
    val edits: List<PageStamp.PageEdits> = emptyList(),
    val labelRes: Int = 0
)

/** The same honest receipt on the live screen and in the app-context fallback. */
private fun savedReceiptRes(labelRes: Int): Int =
    R.string.cdf1_redact_receipt_body

/**
 * H5-1 / C1: one Save, shared by every instance of the Stamp editor.
 *
 * The screen has no `android:configChanges`, so a rotation, dark-mode switch, font-size change or
 * language change destroys it mid-save. A write tied to the dying screen would be cancelled —
 * leaving either a half-written staging file or, after the person presses Save again, a silent
 * duplicate in the library. This runner keeps the single write on a scope that outlives the
 * screen, remembers the marks it is writing, and sends the receipt to whichever live screen is
 * showing those marks (or to an app-context Toast if the person already left).
 */
internal object StampSaveRunner {

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate
    )

    private var docId: String? = null
    private var pending: kotlinx.coroutines.CompletableDeferred<Result<ScanDocument>>? = null
    private var inFlight = SaveMeta()
    private var receiverId: String? = null
    /** Weak: a receiver that was destroyed after a bounded-timeout exit is never held alive. */
    private var receiver: java.lang.ref.WeakReference<StampEditorActivity>? = null
    private var appContext: Context? = null

    /** The live screen that should receive this save's receipt, if any. */
    fun setReceiver(id: String, activity: StampEditorActivity) {
        receiverId = id
        receiver = java.lang.ref.WeakReference(activity)
        appContext = activity.applicationContext
    }

    /** A real close must not leave a destroyed screen as the receipt target. */
    fun clearReceiver(id: String) {
        if (receiverId == id) {
            receiverId = null
            receiver = null
        }
    }

    /**
     * The save already running for [id], if any. Passing [meta] joins only a write of the same
     * source document and marks; a fresh Save with different marks starts its own write.
     */
    fun pendingFor(
        id: String,
        meta: SaveMeta? = null
    ): kotlinx.coroutines.CompletableDeferred<Result<ScanDocument>>? {
        if (docId != id) return null
        if (meta != null && inFlight != meta) return null
        return pending
    }

    /** Starts [block] once on the process scope; returns the promise every screen can await. */
    fun submit(
        id: String,
        meta: SaveMeta = SaveMeta(),
        block: suspend () -> ScanDocument
    ): kotlinx.coroutines.CompletableDeferred<Result<ScanDocument>> {
        val promise = kotlinx.coroutines.CompletableDeferred<Result<ScanDocument>>()
        docId = id
        pending = promise
        inFlight = meta
        scope.launch {
            // Capture both now: an onDestroy after a bounded-timeout exit clears the runner's
            // fields, but this save must still know where and how to report its receipt.
            val ctx = appContext
            val labelRes = meta.labelRes
            val result = runCatching { block() }
            promise.complete(result)
            val live = receiver?.get()?.takeIf {
                docId == id && receiverId == id && !it.isFinishing && !it.isDestroyed
            }
            if (live != null) {
                live.deliverSave(result, labelRes)
            } else if (ctx != null) {
                fallbackReceipt(result, ctx, id, labelRes)
            }
        }
        return promise
    }

    private fun fallbackReceipt(result: Result<ScanDocument>, ctx: Context, id: String, labelRes: Int) {
        result.onSuccess { copy ->
            val res = if (labelRes != 0) savedReceiptRes(labelRes) else R.string.stamp_saved
            Toast.makeText(ctx, ctx.getString(res, copy.name), Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Toast.makeText(ctx, ctx.getString(R.string.stamp_save_failed, V2vFailureCopy.message(ctx, error)), Toast.LENGTH_LONG).show()
        }
        consume(id)
    }

    /** Drops the finished save so a later Save starts fresh. */
    fun consume(id: String) {
        if (docId == id) {
            docId = null
            pending = null
            inFlight = SaveMeta()
            receiverId = null
            receiver = null
            appContext = null
        }
    }
}
