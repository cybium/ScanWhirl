package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.shell.ProScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * v2 UI: 03_tools_hub — Batch tools (Pro): pick many documents, run one action over them one at a
 * time, watch "Document k of N", then read a per-document summary (Share all / Show in library).
 *
 * The engine is `ScanRepository.runBatch`; it publishes each output through the same path as the
 * single tools, records one document's failure and continues, and a Cancel stops after the current
 * document with finished copies kept.
 */
class BatchActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var docs: List<ScanDocument>
    private val selected = LinkedHashSet<String>()
    private var query = ""

    private lateinit var listHost: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var continueButton: TextView
    private lateinit var errorView: TextView

    private var job: Job? = null
    @Volatile private var cancelRequested = false
    private var progressSheet: SwSheet? = null

    private var compressLevel = 1
    private var imagesFormat = ScanRepository.ImageExportFormat.JPG
    private var oneZip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        if (!ProGate.isPro(this)) {
            showProScreen()
            return
        }
        docs = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        val incoming = intent.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
        selected += incoming.filter { id -> docs.any { it.id == id } }
        setContentView(buildRoot())
        renderList()
        if (incoming.isNotEmpty() && selected.isNotEmpty()) {
            listHost.post { showActionSheet() }
        }
    }

    // ---- Pro gate ---------------------------------------------------------

    private fun showProScreen() {
        val screen = ProScreen(this, object : ProScreen.Callbacks {
            override fun onClose() = finish()
            override fun onBuy() = showBillingSoon()
            override fun onRestore() = showBillingSoon()
            override fun onSeeAllProTools() {
                startActivity(ShellActivity.intent(this@BatchActivity))
                finish()
            }
        })
        setContentView(screen)
        screen.applySystemBarsPadding()
    }

    private fun showBillingSoon() {
        SwSheet.builder(this)
            .setTitle(R.string.pro_unlock)
            .setMessage(R.string.pro_buy_coming)
            .setPrimary(R.string.sheet_got_it)
            .build()
            .show()
    }

    // ---- chooser ----------------------------------------------------------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = SwStyle.dp(this@BatchActivity, 18f)
            setPadding(pad, SwStyle.dp(this@BatchActivity, 10f), pad, SwStyle.dp(this@BatchActivity, 18f))
            applySystemBarsPadding()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(SwStyle.iconButton(
            this, R.drawable.ic_sym_arrow_back, SwStyle.color(this, R.color.text_hologram_hi)
        ).apply {
            contentDescription = getString(R.string.tools_setup_back)
            setOnClickListener { finish() }
        })
        bar.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.tools_batch_title)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@BatchActivity, 10f)
            }
        )
        root.addView(bar)
        root.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(R.string.tools_batch_hint)
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@BatchActivity, 8f), 0, SwStyle.dp(this@BatchActivity, 6f))
        })

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countLabel = SwStyle.text(this, 13f, SwStyle.color(this, R.color.cyan_dim), bold = true).apply {
            id = R.id.batchCount
        }
        toolsRow.addView(countLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val selectAll = SwStyle.text(this, 13f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            id = R.id.batchSelectAll
            text = getString(R.string.tools_batch_select_all)
            setPadding(
                SwStyle.dp(this@BatchActivity, 10f), SwStyle.dp(this@BatchActivity, 6f),
                SwStyle.dp(this@BatchActivity, 10f), SwStyle.dp(this@BatchActivity, 6f)
            )
            isClickable = true
            setOnClickListener { toggleAll() }
        }
        toolsRow.addView(selectAll)
        root.addView(toolsRow)

        if (docs.size > 8) {
            val search = EditText(this).apply {
                id = R.id.batchSearch
                hint = getString(R.string.tools_batch_search)
                setHintTextColor(SwStyle.color(this@BatchActivity, R.color.text_hologram_dim))
                setTextColor(SwStyle.color(this@BatchActivity, R.color.text_hologram_hi))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = SwStyle.rounded(
                    this@BatchActivity,
                    SwStyle.color(this@BatchActivity, R.color.glass_surface),
                    radiusDp = 14f,
                    stroke = SwStyle.color(this@BatchActivity, R.color.sw_field_border)
                )
                val pad = SwStyle.dp(this@BatchActivity, 12f)
                setPadding(pad, pad, pad, pad)
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        renderList()
                    }
                })
            }
            root.addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BatchActivity, 48f)
            ).apply { topMargin = SwStyle.dp(this@BatchActivity, 8f) })
        }

        listHost = LinearLayout(this).apply {
            id = R.id.batchList
            orientation = LinearLayout.VERTICAL
            setPadding(0, SwStyle.dp(this@BatchActivity, 8f), 0, SwStyle.dp(this@BatchActivity, 8f))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(listHost)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.batchError
            isVisible = false
        }
        root.addView(errorView)

        continueButton = SwStyle.text(this, 16f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.batchContinue
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@BatchActivity, SwStyle.color(this@BatchActivity, R.color.cyan_primary), radiusDp = 16f
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selected.isEmpty()) {
                    errorView.text = getString(R.string.tools_batch_none)
                    errorView.isVisible = true
                } else {
                    errorView.isVisible = false
                    showActionSheet()
                }
            }
        }
        root.addView(continueButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BatchActivity, 54f)
        ).apply { topMargin = SwStyle.dp(this@BatchActivity, 10f) })
        updateCount()
        return root
    }

    private fun renderList() {
        listHost.removeAllViews()
        val shown = docs.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        if (shown.isEmpty()) {
            listHost.addView(SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                text = getString(R.string.tools_setup_no_docs)
                setPadding(0, SwStyle.dp(this@BatchActivity, 16f), 0, 0)
            })
            updateCount()
            return
        }
        for (doc in shown) {
            val check = ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_check)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(this@BatchActivity, R.color.cyan_primary)
                )
                visibility = if (doc.id in selected) View.VISIBLE else View.INVISIBLE
                layoutParams = LinearLayout.LayoutParams(
                    SwStyle.dp(this@BatchActivity, 22f), SwStyle.dp(this@BatchActivity, 22f)
                ).apply { marginEnd = SwStyle.dp(this@BatchActivity, 12f) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                id = R.id.batchRow
                tag = doc.id
                isClickable = true
                isFocusable = true
                background = if (doc.id in selected) {
                    SwStyle.gradient(
                        this@BatchActivity,
                        SwStyle.color(this@BatchActivity, R.color.glass_surface_raised),
                        SwStyle.color(this@BatchActivity, R.color.glass_surface),
                        16f,
                        SwStyle.color(this@BatchActivity, R.color.cyan_primary)
                    )
                } else {
                    SwStyle.card(this@BatchActivity, 16f)
                }
                val pad = SwStyle.dp(this@BatchActivity, 14f)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { toggle(doc.id) }
            }
            row.addView(check)
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textColumn.addView(SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                this.text = doc.name
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textColumn.addView(SwStyle.text(this, 12f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                this.text = resources.getQuantityString(
                    R.plurals.tools_setup_doc_meta, doc.pageCount, doc.pageCount,
                    StatusLine.formatBytes(doc.pdfFile.length())
                )
            })
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (doc.isLocked) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_sym_lock)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        SwStyle.color(this@BatchActivity, R.color.gold_pro_primary)
                    )
                }, LinearLayout.LayoutParams(
                    SwStyle.dp(this@BatchActivity, 18f), SwStyle.dp(this@BatchActivity, 18f)
                ))
            }
            listHost.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(this@BatchActivity, 8f) })
        }
        updateCount()
    }

    private fun toggle(id: String) {
        if (id in selected) selected.remove(id) else selected += id
        renderList()
    }

    private fun toggleAll() {
        val shown = docs.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        if (shown.all { it.id in selected }) {
            shown.forEach { selected.remove(it.id) }
        } else {
            shown.forEach { selected += it.id }
        }
        renderList()
    }

    private fun updateCount() {
        if (!::countLabel.isInitialized) return
        countLabel.text = getString(R.string.tools_batch_count, selected.size, docs.size)
        continueButton.isEnabled = selected.isNotEmpty()
        continueButton.alpha = if (selected.isNotEmpty()) 1f else 0.5f
    }

    // ---- actions ----------------------------------------------------------

    private fun showActionSheet() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tools_batch_action_title)
            .setMessage(R.string.tools_batch_action_note)
            .setSecondary(R.string.sheet_cancel)
            .build()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun action(id: Int, icon: Int, title: Int, run: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                this.id = id
                isClickable = true
                isFocusable = true
                minimumHeight = SwStyle.dp(this@BatchActivity, 52f)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    sheet.dismiss()
                    run()
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(this@BatchActivity, R.color.cyan_primary)
                )
            }, LinearLayout.LayoutParams(
                SwStyle.dp(this@BatchActivity, 24f), SwStyle.dp(this@BatchActivity, 24f)
            ).apply { marginEnd = SwStyle.dp(this@BatchActivity, 14f) })
            row.addView(SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi)).apply {
                text = getString(title)
            })
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BatchActivity, 52f)
            ))
        }
        action(R.id.batchActionCompress, R.drawable.ic_sym_compress, R.string.tool_compress) { showCompressOptions() }
        action(R.id.batchActionExport, R.drawable.ic_sym_download, R.string.tools_batch_export_pdf) {
            start(ScanRepository.BatchAction.ExportPdf)
        }
        action(R.id.batchActionImages, R.drawable.ic_sym_image, R.string.tool_pdf_to_images) { showImagesOptions() }
        action(R.id.batchActionWord, R.drawable.ic_sym_description, R.string.tool_to_word) {
            start(ScanRepository.BatchAction.ToWord)
        }
        action(R.id.batchActionClean, R.drawable.ic_sym_shield_lock, R.string.tool_remove_metadata) {
            start(ScanRepository.BatchAction.RemoveMetadata)
        }
        action(R.id.batchActionProtect, R.drawable.ic_sym_lock, R.string.tool_protect) { showProtectOptions() }
        sheet.setContent(list)
        sheet.show()
    }

    private fun showCompressOptions() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tool_compress)
            .setMessage(R.string.tools_setup_compress_note)
            .setSecondary(R.string.sheet_cancel)
            .build()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val target = EditText(this).apply {
            id = R.id.batchTarget
            hint = getString(R.string.tools_setup_target_hint)
            setHintTextColor(SwStyle.color(this@BatchActivity, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(this@BatchActivity, R.color.text_hologram_hi))
            background = SwStyle.rounded(
                this@BatchActivity, SwStyle.color(this@BatchActivity, R.color.glass_surface),
                radiusDp = 14f, stroke = SwStyle.color(this@BatchActivity, R.color.sw_field_border)
            )
            val pad = SwStyle.dp(this@BatchActivity, 12f)
            setPadding(pad, pad, pad, pad)
            isSingleLine = true
        }
        content.addView(target, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BatchActivity, 48f)
        ))
        val chips = listOf(
            Triple(R.id.batchLevelLight, R.string.tools_setup_level_light, 0),
            Triple(R.id.batchLevelMedium, R.string.tools_setup_level_medium, 1),
            Triple(R.id.batchLevelStrong, R.string.tools_setup_level_strong, 2)
        )
        val views = mutableListOf<TextView>()
        fun paint() {
            views.forEachIndexed { i, chip -> paintChip(chip, i == compressLevel) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEachIndexed { i, (id, title, index) ->
            val chip = chip(getString(title), id).apply {
                setOnClickListener {
                    compressLevel = index
                    target.setText("")
                    paint()
                }
            }
            views += chip
            row.addView(chip, LinearLayout.LayoutParams(0, SwStyle.dp(this@BatchActivity, 40f), 1f).apply {
                if (i < 2) marginEnd = SwStyle.dp(this@BatchActivity, 8f)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@BatchActivity, 10f) })
        paint()
        sheet.setContent(content)
        sheet.setPrimary(R.string.tools_batch_start)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val size = target.text.toString().trim()
            val targetBytes = if (size.isEmpty()) null else ImageTools.parseSizeInput(size)
            if (size.isNotEmpty() && targetBytes == null) {
                sheet.setMessage(R.string.tools_setup_bad_target)
            } else {
                sheet.dismiss()
                val (quality, maxDim) = when (compressLevel) {
                    0 -> 80 to null
                    2 -> 50 to 1600
                    else -> 65 to 2000
                }
                start(
                    ScanRepository.BatchAction.Compress(
                        quality = quality, maxDim = maxDim, targetBytes = targetBytes
                    )
                )
            }
        }
        sheet.show()
    }

    private fun showImagesOptions() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tool_pdf_to_images)
            .setMessage(R.string.tools_setup_images_note)
            .setSecondary(R.string.sheet_cancel)
            .build()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val jpg = chip(getString(R.string.tools_setup_jpg), R.id.batchFormatJpg)
        val png = chip(getString(R.string.tools_setup_png), R.id.batchFormatPng)
        fun paintFormat() {
            paintChip(jpg, imagesFormat == ScanRepository.ImageExportFormat.JPG)
            paintChip(png, imagesFormat == ScanRepository.ImageExportFormat.PNG)
        }
        jpg.setOnClickListener {
            imagesFormat = ScanRepository.ImageExportFormat.JPG
            paintFormat()
        }
        png.setOnClickListener {
            imagesFormat = ScanRepository.ImageExportFormat.PNG
            paintFormat()
        }
        val formatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        formatRow.addView(jpg, LinearLayout.LayoutParams(0, SwStyle.dp(this@BatchActivity, 40f), 1f).apply {
            marginEnd = SwStyle.dp(this@BatchActivity, 8f)
        })
        formatRow.addView(png, LinearLayout.LayoutParams(0, SwStyle.dp(this@BatchActivity, 40f), 1f))
        content.addView(formatRow)
        paintFormat()

        val perDoc = chip(getString(R.string.tools_batch_per_doc), R.id.batchPerDoc)
        val all = chip(getString(R.string.tools_batch_one_zip), R.id.batchOneZip)
        fun paintZip() {
            paintChip(perDoc, !oneZip)
            paintChip(all, oneZip)
        }
        perDoc.setOnClickListener { oneZip = false; paintZip() }
        all.setOnClickListener { oneZip = true; paintZip() }
        val zipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zipRow.addView(perDoc, LinearLayout.LayoutParams(0, SwStyle.dp(this@BatchActivity, 40f), 1f).apply {
            marginEnd = SwStyle.dp(this@BatchActivity, 8f)
        })
        zipRow.addView(all, LinearLayout.LayoutParams(0, SwStyle.dp(this@BatchActivity, 40f), 1f))
        content.addView(zipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@BatchActivity, 10f) })
        paintZip()
        sheet.setContent(content)
        sheet.setPrimary(R.string.tools_batch_start) {
            start(ScanRepository.BatchAction.PdfToImages(imagesFormat, oneZip))
        }
        sheet.show()
    }

    private fun showProtectOptions() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tool_protect)
            .setMessage(R.string.tools_batch_password)
            .setSecondary(R.string.sheet_cancel)
            .build()
        val password = PasswordField(this).apply { id = R.id.batchPassword; showStrength = false }
        sheet.setContent(password)
        sheet.setPrimary(R.string.tools_batch_start)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val text = password.editText.text.toString()
            if (text.length < 6) {
                sheet.setMessage(R.string.tools_batch_password_short)
            } else {
                sheet.dismiss()
                start(ScanRepository.BatchAction.Protect(text))
            }
        }
        sheet.show()
    }

    // ---- run / summary ----------------------------------------------------

    private fun start(action: ScanRepository.BatchAction) {
        cancelRequested = false
        progressSheet = SwSheet.builder(this)
            .setTitle(R.string.tools_batch_progress_title)
            .setMessage(R.string.tools_batch_progress_start)
            .setSecondary(R.string.sheet_cancel) {
                cancelRequested = true
                progressSheet?.setMessage(R.string.tools_batch_cancelling)
            }
            .build()
            .also { it.show() }
        val ids = selected.toList()
        job = lifecycleScope.launch {
            try {
                val results = repo.runBatch(
                    ids = ids,
                    action = action,
                    onProgress = { number, total, name ->
                        runOnUiThread {
                            progressSheet?.setMessage(
                                getString(R.string.tools_batch_progress_msg, number, total, name)
                            )
                        }
                    },
                    cancelled = { cancelRequested }
                )
                progressSheet?.dismiss()
                progressSheet = null
                showSummary(results)
            } catch (e: CancellationException) {
                progressSheet?.dismiss()
                progressSheet = null
                throw e
            } catch (e: Exception) {
                progressSheet?.dismiss()
                progressSheet = null
                errorView.text = getString(R.string.tools_batch_failed, e.message ?: "")
                errorView.isVisible = true
            }
        }
    }

    private fun showSummary(results: List<ScanRepository.BatchItem>) {
        val done = results.count { it.ok }
        val failed = results.size - done
        val sheet = SwSheet.builder(this)
            .setTitle(getString(R.string.tools_batch_summary_title, done, failed))
            .build()
        val list = LinearLayout(this).apply {
            id = R.id.batchSummary
            orientation = LinearLayout.VERTICAL
        }
        results.forEach { item ->
            val line = SwStyle.text(
                this, 13f,
                SwStyle.color(this, if (item.ok) R.color.ok_green else R.color.laser_danger)
            ).apply {
                text = if (item.ok) item.name
                else getString(R.string.tools_batch_line_failed, item.name, item.reason ?: "")
                setLineSpacing(0f, 1.2f)
                setPadding(0, SwStyle.dp(this@BatchActivity, 6f), 0, 0)
            }
            list.addView(line)
        }
        sheet.setContent(list)
        if (results.any { it.ok }) {
            sheet.setPrimary(R.string.tools_batch_share_all) { shareAll(results) }
        }
        sheet.setSecondary(R.string.tools_batch_show_library) {
            sheet.dismiss()
            showLibrary(results)
        }
        sheet.show()
    }

    private fun shareAll(results: List<ScanRepository.BatchItem>) {
        val files = mutableListOf<File>()
        for (item in results.filter { it.ok }) {
            files += item.files
            val doc = item.outputId?.let { runCatching { repo.getDocument(it) }.getOrNull() }
            if (doc != null) files += doc.pdfFile
            if (item.outputId == null && item.files.isEmpty() && item.savedName != null) {
                runCatching { repo.getDocument(item.docId) }.getOrNull()?.let { files += it.pdfFile }
            }
        }
        val uris = files.filter { it.isFile }.distinct().mapNotNull { file ->
            runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull()
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.tools_result_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val type = if (files.all { it.name.endsWith(".pdf") }) "application/pdf" else "*/*"
        val intent = (if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                this.type = type
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }).apply {
            clipData = ClipData(ClipDescription("ScanWhirl batch", arrayOf(type)), ClipData.Item(uris.first()))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.tools_batch_share_all)))
    }

    private fun showLibrary(results: List<ScanRepository.BatchItem>) {
        val docId = results.firstOrNull { it.ok && it.outputId != null }?.outputId
            ?: results.firstOrNull { it.ok }?.docId
        startActivity(
            ShellActivity.intent(this, docId).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    // ---- small kit helpers ------------------------------------------------

    private fun chip(text: String, id: Int): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim), bold = true).apply {
            this.id = id
            this.text = text
            gravity = Gravity.CENTER
            setPadding(
                SwStyle.dp(this@BatchActivity, 8f), 0,
                SwStyle.dp(this@BatchActivity, 8f), 0
            )
            background = SwStyle.rounded(
                this@BatchActivity,
                SwStyle.color(this@BatchActivity, R.color.glass_surface),
                radiusDp = 12f,
                stroke = SwStyle.color(this@BatchActivity, R.color.sw_field_border)
            )
        }

    private fun paintChip(chipView: TextView, selected: Boolean) {
        chipView.isSelected = selected
        chipView.setTextColor(
            SwStyle.color(this, if (selected) R.color.cyan_primary else R.color.text_hologram_dim)
        )
        chipView.background = if (selected) {
            SwStyle.rounded(
                this, SwStyle.color(this, R.color.sw_cyan_soft), radiusDp = 12f,
                stroke = SwStyle.color(this, R.color.cyan_primary)
            )
        } else {
            SwStyle.rounded(
                this, SwStyle.color(this, R.color.glass_surface), radiusDp = 12f,
                stroke = SwStyle.color(this, R.color.sw_field_border)
            )
        }
        chipView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (selected) R.drawable.ic_sym_check else 0, 0, 0, 0
        )
        chipView.compoundDrawablePadding = SwStyle.dp(this, 6f)
    }

    companion object {
        const val EXTRA_DOC_IDS = "doc_ids"

        fun intent(context: Context, ids: List<String>): Intent =
            Intent(context, BatchActivity::class.java)
                .putStringArrayListExtra(EXTRA_DOC_IDS, ArrayList(ids))
    }
}
