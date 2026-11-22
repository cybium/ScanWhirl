package com.scanwhirl.app.ui.tools.linkindex

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.DocumentPicker
import com.scanwhirl.app.ui.kit.ProOffer
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.util.LinkIndexDetector
import com.scanwhirl.app.util.LinkIndexTrial
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguageSettings
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * LX — Link index. Pick a document, mark its contents page(s), confirm the
 * proposed title -> page rows and save a NEW linked PDF. The source is never
 * edited. Pro uses the shared [ProOffer] screen after one free successful use.
 *
 * No ToolRegistry / ToolsHub edits belong to this lane: the shell wires the
 * [TOOL_ID] tile after lane N merges. [intent] is the only public entry point.
 */
class LinkIndexActivity : BaseActivity() {

    companion object {
        const val TOOL_ID = "link_index"

        private const val KEY_SOURCE = "lx_source"
        private const val KEY_PAGES = "lx_pages"
        private const val KEY_OFFSET = "lx_offset"
        private const val KEY_STEP = "lx_step"
        private const val KEY_ROWS = "lx_rows"
        private const val KEY_COPY = "lx_copy"
        private const val KEY_CREATED = "lx_created"

        private const val STEP_PICK = 1
        private const val STEP_SET = 2
        private const val STEP_RESULT = 3

        private const val FIELD = '\u001e'

        fun intent(context: Context): Intent = Intent(context, LinkIndexActivity::class.java)
    }

    private data class Row(
        var title: String,
        var printed: Int,
        var sourcePage: Int,
        var box: LinkIndexDetector.Box,
        var level: Int,
        var confidence: LinkIndexDetector.Confidence,
        var checked: Boolean
    ) {
        fun serialize(): String = listOf(
            title, printed.toString(), sourcePage.toString(), box.left.toString(), box.top.toString(),
            box.right.toString(), box.bottom.toString(), level.toString(), confidence.name,
            if (checked) "1" else "0"
        ).joinToString(FIELD.toString())

        companion object {
            fun parse(value: String): Row? {
                val p = value.split(FIELD)
                if (p.size < 10) return null
                return Row(
                    title = p[0],
                    printed = p[1].toIntOrNull() ?: return null,
                    sourcePage = p[2].toIntOrNull() ?: return null,
                    box = LinkIndexDetector.Box(
                        p[3].toIntOrNull() ?: return null, p[4].toIntOrNull() ?: return null,
                        p[5].toIntOrNull() ?: return null, p[6].toIntOrNull() ?: return null
                    ),
                    level = p[7].toIntOrNull() ?: return null,
                    confidence = runCatching { LinkIndexDetector.Confidence.valueOf(p[8]) }
                        .getOrDefault(LinkIndexDetector.Confidence.LOW),
                    checked = p[9] == "1"
                )
            }
        }
    }

    private val repo: ScanRepository by lazy { ScanWhirlApp.from(this).repository }

    private var source: ScanDocument? = null
    private var newCopy: ScanDocument? = null
    private val indexPages = linkedSetOf<Int>()
    private val pageOcr = mutableMapOf<Int, OcrPage>()
    private val rows = mutableListOf<Row>()
    private var offset = 0
    private var step = STEP_PICK
    private var busy = false
    private var createdCount = 0

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            progress(true, R.string.lx_reading)
            try {
                val imported = withContext(Dispatchers.IO) { repo.importPdfAsScan(uri, null) }
                progress(false)
                onPicked(imported)
            } catch (t: Throwable) {
                progress(false)
                toast(getString(R.string.lx_error, t.message.orEmpty()))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link_index)
        v<View>(R.id.lxRoot).applySystemBarsPadding()

        // A recreation after a successful use must still be able to show its receipt:
        // only a fresh launch is gated.
        if (savedInstanceState == null && LinkIndexTrial.needsPro(this)) {
            ProOffer.require(this)
            finish()
            return
        }

        restore(savedInstanceState)
        wireButtons()
        applyStep(step)

        if (savedInstanceState == null && LinkIndexTrial.shouldOffer(this)) showTrialOffer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SOURCE, source?.id)
        outState.putIntArray(KEY_PAGES, indexPages.sorted().toIntArray())
        outState.putInt(KEY_OFFSET, offset)
        outState.putInt(KEY_STEP, step)
        outState.putStringArrayList(KEY_ROWS, ArrayList(rows.map { it.serialize() }))
        outState.putString(KEY_COPY, newCopy?.id)
        outState.putInt(KEY_CREATED, createdCount)
    }

    private fun restore(state: Bundle?) {
        state ?: return
        state.getString(KEY_SOURCE)?.let { id -> source = repo.getDocument(id) }
        indexPages.clear()
        indexPages.addAll(state.getIntArray(KEY_PAGES)?.toList().orEmpty())
        offset = state.getInt(KEY_OFFSET, 0)
        step = state.getInt(KEY_STEP, STEP_PICK)
        rows.clear()
        state.getStringArrayList(KEY_ROWS)?.forEach { Row.parse(it)?.let { row -> rows.add(row) } }
        newCopy = state.getString(KEY_COPY)?.let { id -> repo.getDocument(id) }
        createdCount = state.getInt(KEY_CREATED, 0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> v(id: Int): T = findViewById(id) as T

    private fun wireButtons() {
        v<View>(R.id.lxClose).setOnClickListener { finish() }
        v<View>(R.id.lxPickLibrary).setOnClickListener { showDocumentPicker() }
        v<View>(R.id.lxPickFile).setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        v<View>(R.id.lxOffsetMinus).setOnClickListener { changeOffset(-1) }
        v<View>(R.id.lxOffsetPlus).setOnClickListener { changeOffset(1) }
        v<View>(R.id.lxCalibrate).setOnClickListener { showCalibrate() }
        v<View>(R.id.lxAddEntry).setOnClickListener { addEntry() }
        v<View>(R.id.lxCreate).setOnClickListener { createLinkedCopy() }
        v<View>(R.id.lxOpen).setOnClickListener {
            newCopy?.let { startActivity(DocumentViewerActivity.intent(this, it.id)) }
        }
        v<View>(R.id.lxShare).setOnClickListener { newCopy?.let { ShareUtils.share(this, it) } }
        v<View>(R.id.lxDone).setOnClickListener { finish() }
    }

    private fun showTrialOffer() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lx_trial_title)
            .setMessage(R.string.lx_trial_msg)
            .setPositiveButton(R.string.lx_try_free) { dialog, _ ->
                dialog.dismiss()
                applyStep(STEP_PICK)
            }
            .setNegativeButton(R.string.lx_not_now) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun applyStep(next: Int) {
        step = next
        v<View>(R.id.lxStepPick).isVisible = next == STEP_PICK
        v<View>(R.id.lxStepSet).isVisible = next == STEP_SET
        v<View>(R.id.lxStepResult).isVisible = next == STEP_RESULT
        when (next) {
            STEP_SET -> {
                v<TextView>(R.id.lxDocName).text = source?.name.orEmpty()
                if (v<EditText>(R.id.lxCopyName).text.isNullOrBlank()) {
                    v<EditText>(R.id.lxCopyName).setText(getString(R.string.lx_copy_name))
                }
                buildPageStrip()
                renderOffset()
                renderRows()
            }
            STEP_RESULT -> {
                val copy = newCopy
                v<TextView>(R.id.lxResultTitle).text = if (copy == null) {
                    getString(R.string.lx_error, "")
                } else {
                    getString(R.string.lx_result, createdCount, createdCount)
                }
            }
        }
    }

    private fun showDocumentPicker() {
        val all = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        val eligible = all.filter { doc ->
            !doc.isLocked && !doc.isVaulted && PdfProtection.isEncrypted(doc.pdfFile) != true
        }
        DocumentPicker.show(
            activity = this,
            title = getString(R.string.lx_choose_doc),
            docs = eligible,
            extraAction = DocumentPicker.ExtraAction(getString(R.string.lx_from_pdf)) {
                pickPdf.launch(arrayOf("application/pdf"))
            },
            onEmptyImport = { pickPdf.launch(arrayOf("application/pdf")) },
            noEligibleTitle = if (all.isNotEmpty() && eligible.isEmpty()) R.string.lx_protected else null,
            onPick = { onPicked(it) },
            onDismiss = { if (step == STEP_PICK && !busy) finish() }
        )
    }

    /** All refusals happen before any page is OCR'd or any copy is made. */
    private fun onPicked(doc: ScanDocument) {
        when {
            doc.isVaulted -> toast(getString(R.string.lx_vault))
            doc.isLocked || PdfProtection.isEncrypted(doc.pdfFile) == true ->
                toast(getString(R.string.lx_protected))
            looksPdfA(doc.pdfFile) -> toast(getString(R.string.lx_pdfa))
            doc.pageFiles.isEmpty() -> toast(getString(R.string.lx_no_geometry))
            else -> {
                source = doc
                newCopy = null
                indexPages.clear()
                pageOcr.clear()
                rows.clear()
                offset = 0
                v<EditText>(R.id.lxCopyName).setText(doc.name)
                applyStep(STEP_SET)
            }
        }
    }

    private fun looksPdfA(file: File): Boolean {
        if (!file.isFile) return false
        val head = ByteArray(minOf(file.length(), 2L * 1024 * 1024).toInt())
        file.inputStream().use { it.read(head) }
        val text = String(head, Charsets.ISO_8859_1)
        return text.contains("pdfaid") || text.contains("/OutputIntent")
    }

    // ---- pages and detection ------------------------------------------------

    private fun buildPageStrip() {
        val host = v<LinearLayout>(R.id.lxPageStrip)
        host.removeAllViews()
        val doc = source ?: return
        val density = resources.displayMetrics.density
        doc.pageFiles.forEachIndexed { index, page ->
            val item = layoutInflater.inflate(R.layout.item_lx_page, host, false)
            val root = item.findViewById<View>(R.id.lxPageRoot)
            val label = getString(R.string.lx_page_number, index + 1)
            item.findViewById<TextView>(R.id.lxPageLabel).text = label
            root.contentDescription = label
            item.findViewById<CheckBox>(R.id.lxPageCheck).isChecked = indexPages.contains(index)
            PageThumbLoader.load(
                page, (72f * density * 2).toInt(), ScanFilter.ORIGINAL,
                item.findViewById<ImageView>(R.id.lxPageThumb), fitExactly = true, skipStale = true
            )
            root.setOnClickListener {
                val on = indexPages.add(index)
                if (!on) indexPages.remove(index)
                item.findViewById<CheckBox>(R.id.lxPageCheck).isChecked = on
                if (indexPages.isNotEmpty()) refreshDetection() else {
                    rows.clear()
                    renderRows()
                }
            }
            host.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() })
        }
    }

    private fun refreshDetection() {
        val doc = source ?: return
        lifecycleScope.launch {
            progress(true, R.string.lx_reading)
            try {
                val detected = withContext(Dispatchers.IO) {
                    val pages = mutableListOf<Pair<Int, OcrPage>>()
                    for (index in indexPages.sorted()) {
                        if (index !in doc.pageFiles.indices) continue
                        val key = String.format(Locale.ROOT, "page_%03d.jpg", index)
                        var geometry = repo.ocrGeometry(doc.id)[key]
                        if (geometry == null || geometry.lines.none { it.text.isNotBlank() }) {
                            geometry = OcrEngine.recognizeFile(
                                doc.pageFiles[index], OcrLanguageSettings.get(this@LinkIndexActivity)
                            ).page
                        }
                        if (geometry != null) {
                            pageOcr[index] = geometry
                            pages += index to geometry
                        }
                    }
                    LinkIndexDetector.detect(pages, doc.pageCount)
                }
                rows.clear()
                rows.addAll(detected.map {
                    Row(it.title, it.printedPage, it.sourcePageIndex, it.box, it.level, it.confidence, true)
                })
                offset = LinkIndexDetector.suggestOffset(detected, doc.pageCount)
                renderOffset()
                renderRows()
            } catch (t: Throwable) {
                toast(getString(R.string.lx_error, t.message.orEmpty()))
            } finally {
                progress(false)
            }
        }
    }

    private fun changeOffset(delta: Int) {
        offset = (offset + delta).coerceIn(-100, 100)
        renderOffset()
        renderRows()
    }

    private fun renderOffset() {
        v<TextView>(R.id.lxOffsetValue).text = (1 + offset).toString()
    }

    private fun showCalibrate() {
        val fieldPrinted = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.lx_printed_page_hint)
        }
        val fieldPdf = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.lx_page_number, 1)
        }
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(fieldPrinted)
            addView(fieldPdf)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lx_calibrate)
            .setView(host)
            .setPositiveButton(R.string.lx_create) { dialog, _ ->
                val printed = fieldPrinted.text.toString().toIntOrNull()
                val actual = fieldPdf.text.toString().toIntOrNull()
                if (printed != null && actual != null) {
                    offset = (actual - printed).coerceIn(-100, 100)
                    renderOffset()
                    renderRows()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.lx_not_now, null)
            .show()
    }

    // ---- editable proposal rows --------------------------------------------

    private fun addEntry() {
        val doc = source ?: return
        rows.add(
            Row(
                title = "", printed = 1,
                sourcePage = indexPages.firstOrNull() ?: 0,
                box = LinkIndexDetector.Box(0, 0, doc.pageFiles.firstOrNull()?.let {
                    PdfGenerator.readJpegInfo(it.readBytes()).width / 3
                } ?: 200, 120),
                level = 0,
                confidence = LinkIndexDetector.Confidence.LOW,
                checked = true
            )
        )
        renderRows()
    }

    private fun renderRows() {
        val host = v<LinearLayout>(R.id.lxRows)
        host.removeAllViews()
        val doc = source
        if (rows.isEmpty()) {
            host.addView(TextView(this).apply {
                text = getString(R.string.lx_no_entries)
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_hologram_mid))
                textSize = 14f
                setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            return
        }
        val density = resources.displayMetrics.density
        for (row in rows) {
            val view = layoutInflater.inflate(R.layout.item_lx_row, host, false)
            val check = view.findViewById<CheckBox>(R.id.lxRowCheck)
            check.isChecked = row.checked
            check.contentDescription = getString(R.string.lx_link_description, row.title)
            check.setOnCheckedChangeListener { _, on -> row.checked = on }

            val title = view.findViewById<EditText>(R.id.lxRowTitle)
            title.setText(row.title)
            title.afterText { row.title = it }

            val printed = view.findViewById<EditText>(R.id.lxRowPage)
            printed.setText(if (row.printed > 0) row.printed.toString() else "")
            printed.afterText { row.printed = it.toIntOrNull() ?: 0 }

            val target = if (doc == null) null else LinkIndexDetector.targetPage(row.printed, offset, doc.pageCount)
            if (target == null) row.checked = false
            val confidence = view.findViewById<TextView>(R.id.lxRowConfidence)
            confidence.text = getString(
                when (row.confidence) {
                    LinkIndexDetector.Confidence.HIGH -> R.string.lx_confidence_high
                    LinkIndexDetector.Confidence.MEDIUM -> R.string.lx_confidence_medium
                    LinkIndexDetector.Confidence.LOW -> R.string.lx_confidence_low
                }
            ) + if (target == null) " (*)" else ""
            view.findViewById<TextView>(R.id.lxRowLevel).text = when (row.level) {
                0 -> ""
                1 -> "·"
                else -> "··"
            }
            check.isChecked = row.checked
            view.findViewById<View>(R.id.lxRowRemove).setOnClickListener {
                rows.remove(row)
                renderRows()
            }
            host.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() })
        }
    }

    private fun EditText.afterText(action: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = action(s?.toString().orEmpty())
        })
    }

    // ---- output -------------------------------------------------------------

    private fun createLinkedCopy() {
        val doc = source ?: return
        if (busy) return
        val chosen = rows.filter { it.checked }
            .mapNotNull { row ->
                val target = LinkIndexDetector.targetPage(row.printed, offset, doc.pageCount)
                if (target == null || row.title.isBlank()) null else row to target
            }
        if (chosen.isEmpty()) {
            toast(getString(R.string.lx_no_entries))
            return
        }
        val name = v<EditText>(R.id.lxCopyName).text.toString().trim().ifBlank { doc.name }
        busy = true
        progress(true, R.string.lx_working)
        lifecycleScope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    if (doc.isVaulted) throw IllegalStateException(getString(R.string.lx_vault))
                    if (doc.isLocked || PdfProtection.isEncrypted(doc.pdfFile) == true) {
                        throw IllegalStateException(getString(R.string.lx_protected))
                    }
                    if (looksPdfA(doc.pdfFile)) throw IllegalStateException(getString(R.string.lx_pdfa))
                    val links = chosen.map { (row, target) ->
                        PdfGenerator.PdfLink(
                            pageIndex = row.sourcePage.coerceIn(0, doc.pageCount - 1),
                            title = row.title,
                            targetPage = target - 1,
                            rect = PdfGenerator.PdfLinkRect(row.box.left, row.box.top, row.box.right, row.box.bottom)
                        )
                    }
                    val duplicate = repo.duplicateDocument(doc.id, name)
                    try {
                        val geo = repo.ocrGeometry(duplicate.id)
                        val layers = duplicate.pageFiles.indices.mapNotNull { index ->
                            geo[String.format(Locale.ROOT, "page_%03d.jpg", index)]?.let { index to it }
                        }.toMap()
                        PdfGenerator.imagesToPdf(
                            duplicate.pageFiles, duplicate.pdfFile, textLayers = layers, links = links
                        )
                        PdfVerifier.verify(duplicate.pdfFile, expectedImages = duplicate.pageFiles)
                        duplicate
                    } catch (t: Throwable) {
                        // A failed export never purges a document: the unfinished copy goes to Trash.
                        runCatching { repo.moveToTrash(duplicate.id) }
                        throw t
                    }
                }
                LinkIndexTrial.recordSuccess(this@LinkIndexActivity)
                newCopy = copy
                createdCount = chosen.size
                v<TextView>(R.id.lxSkipped).isVisible = chosen.size < rows.size
                if (chosen.size < rows.size) {
                    v<TextView>(R.id.lxSkipped).text =
                        getString(R.string.lx_skipped, rows.size - chosen.size)
                }
                progress(false)
                busy = false
                applyStep(STEP_RESULT)
            } catch (t: Throwable) {
                progress(false)
                busy = false
                toast(getString(R.string.lx_error, t.message.orEmpty()))
            }
        }
    }

    private fun progress(on: Boolean, message: Int = R.string.lx_working) {
        v<View>(R.id.lxProgress).isVisible = on
        v<TextView>(R.id.lxProgressText).setText(message)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
