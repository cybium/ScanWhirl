package com.scanwhirl.app.ui.stamp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityStampEditorBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.util.PageStamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    }

    private lateinit var binding: ActivityStampEditorBinding
    private lateinit var repo: ScanRepository
    private lateinit var document: ScanDocument
    private lateinit var signatureStore: SignatureStore

    internal lateinit var editor: StampEditorState
        private set

    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStampEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()

        repo = ScanRepository(this)
        signatureStore = SignatureStore(this)

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

        binding.toolSignature.setOnClickListener { pickSignature() }
        binding.toolText.setOnClickListener {
            StampPrompts.text(
                this, R.string.stamp_text_title, R.string.stamp_text_hint, ""
            ) { text -> addMark(PageStamp.Mark.Text(text, textBox(), size = 0.05f)) }
        }
        binding.toolDraw.setOnClickListener {
            arm(StampCanvasView.Tool.DRAW, R.string.stamp_hint_draw)
        }
        binding.toolHighlight.setOnClickListener {
            arm(StampCanvasView.Tool.HIGHLIGHT, R.string.stamp_hint_highlight)
        }
        binding.toolWatermark.setOnClickListener {
            StampPrompts.text(
                this, R.string.stamp_watermark_title, R.string.stamp_watermark_hint,
                getString(R.string.stamp_watermark_default)
            ) { text -> addMarkToAll(PageStamp.Mark.Watermark(text)) }
        }
        binding.toolPageNumbers.setOnClickListener {
            addMarkToAll(PageStamp.Mark.PageNumber())
        }
        binding.toolStamp.setOnClickListener {
            StampPrompts.badges(this) { kind ->
                addMark(PageStamp.Mark.Badge(kind, badgeBox()))
            }
        }
        binding.toolRedact.setOnClickListener {
            arm(StampCanvasView.Tool.REDACT, R.string.stamp_hint_redact)
        }
        binding.toolSuggest.setOnClickListener { suggestRedactions() }

        binding.deleteMark.setOnClickListener {
            if (editor.removeSelected()) refresh()
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
        applyRequestedTool(intent.getStringExtra(EXTRA_TOOL))
    }

    // ------------------------------------------------------------------ tools

    private fun applyRequestedTool(tool: String?) {
        when (tool) {
            TOOL_SIGN -> pickSignature()
            TOOL_TEXT -> binding.toolText.performClick()
            TOOL_DRAW -> arm(StampCanvasView.Tool.DRAW, R.string.stamp_hint_draw)
            TOOL_HIGHLIGHT -> arm(StampCanvasView.Tool.HIGHLIGHT, R.string.stamp_hint_highlight)
            TOOL_WATERMARK -> binding.toolWatermark.performClick()
            TOOL_PAGE_NUMBERS -> addMarkToAll(PageStamp.Mark.PageNumber())
            TOOL_STAMP -> binding.toolStamp.performClick()
            TOOL_REDACT -> arm(StampCanvasView.Tool.REDACT, R.string.stamp_hint_redact)
            else -> Unit
        }
    }

    private fun arm(tool: StampCanvasView.Tool, hintRes: Int) {
        editor.select(null)
        binding.canvas.tool = tool
        binding.hint.setText(hintRes)
        binding.hint.visibility = android.view.View.VISIBLE
        refreshSelection()
    }

    private fun disarm() {
        binding.canvas.tool = StampCanvasView.Tool.SELECT
        binding.hint.visibility = android.view.View.GONE
    }

    private fun addMark(mark: PageStamp.Mark) {
        editor.add(mark)
        disarm()
        refresh()
    }

    private fun addMarkToAll(mark: PageStamp.Mark) {
        editor.addToAll(mark)
        disarm()
        refresh()
    }

    private fun pickSignature() {
        StampPrompts.signature(this, signatureStore) { saved ->
            addMark(
                PageStamp.Mark.Signature(
                    strokes = saved.strokes,
                    box = signatureBox(),
                    color = android.graphics.Color.BLACK
                )
            )
        }
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
        binding.prevPage.isEnabled = editor.currentPage > 0
        binding.nextPage.isEnabled = editor.currentPage < editor.pageCount - 1
        binding.undo.isEnabled = editor.canUndo
        binding.redo.isEnabled = editor.canRedo
        binding.deleteMark.isEnabled = editor.selected != null
        binding.saveCopy.isEnabled = !editor.isEmpty() && !saving
        binding.selectionHint.setText(
            if (editor.selected != null) R.string.stamp_hint_select else R.string.stamp_no_selection
        )
    }

    private fun save() {
        if (saving || editor.isEmpty()) return
        saving = true
        binding.saveCopy.isEnabled = false
        binding.saveCopy.setText(R.string.stamp_saving)
        val edits = editor.edits()
        lifecycleScope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    repo.stampDocument(document.id, edits, null)
                }
                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_NEW_DOC_ID, copy.id)
                )
                Toast.makeText(
                    this@StampEditorActivity,
                    getString(R.string.stamp_saved, copy.name),
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
        if (editor.isEmpty() || saving) {
            finish()
            return
        }
        SwDialog.card(this)
            .setTitle(R.string.stamp_discard_title)
            .setMessage(R.string.stamp_discard_message)
            .setPositiveButton(R.string.stamp_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.stamp_keep_editing, null)
            .show()
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
