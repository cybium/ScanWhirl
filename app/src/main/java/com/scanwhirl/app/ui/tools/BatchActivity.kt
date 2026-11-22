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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.L3FailureCopy
import com.scanwhirl.app.tools.CompressTarget
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.DownloadSaver
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
    private var docs: List<ScanDocument> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var query = ""

    private lateinit var listHost: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var continueButton: TextView
    private lateinit var errorView: TextView

    private var job: Job? = null
    @Volatile private var cancelRequested = false
    private var progressSheet: SwSheet? = null
    private var searchView: EditText? = null
    private var requestedIds: List<String> = emptyList()

    /**
     * H2-4: on Android 7-9 "Export as PDF" needs the runtime storage grant for the Documents
     * folder. The chosen action waits here while Android shows its prompt.
     */
    private var pendingAction: (() -> Unit)? = null
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAction
            pendingAction = null
            if (granted) {
                errorView.isVisible = false
                action?.invoke()
            } else {
                errorView.text = getString(R.string.f7save_perm_denied_batch)
                errorView.isVisible = true
            }
        }

    private var compressLevel = 1
    private var imagesFormat = ScanRepository.ImageExportFormat.JPG
    private var oneZip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        val restored = savedInstanceState != null
        if (savedInstanceState != null) {
            savedInstanceState.getStringArrayList(KEY_SELECTED)?.let { selected.addAll(it) }
            query = savedInstanceState.getString(KEY_QUERY).orEmpty()
            // T1: without the requested set a run resumed after process death reports zero
            // "not processed" documents in its cancellation summary.
            savedInstanceState.getStringArrayList(KEY_REQUESTED)?.let { requestedIds = it }
        }
        // Batch tools is a Pro tool. The registry and the launcher gate the normal path; a direct
        // internal intent must land on the same shell offer (with real billing), never a local Pro
        // screen whose Buy/Restore only said "billing is not switched on".
        if (!com.scanwhirl.app.ui.kit.ProOffer.require(this)) {
            finish()
            return
        }
        val incoming = intent.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
        setContentView(buildRoot())
        renderList()
        // C07: the chooser is on screen while the library is stat'd off the main thread.
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { repo.listDocuments() }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed) return@launch
            docs = fresh
            // T1: the chooser is built before the library responds, so add the search row now.
            addSearchIfNeeded()
            if (!restored) {
                selected += incoming.filter { id -> docs.any { it.id == id } }
            }
            renderList()
            if (!restored && incoming.isNotEmpty() && selected.isNotEmpty()) {
                listHost.post { showActionSheet() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selected))
        outState.putString(KEY_QUERY, query)
        outState.putStringArrayList(KEY_REQUESTED, ArrayList(requestedIds))
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
        // T1: docs have not loaded yet at this point; addSearchIfNeeded() is called again after load.
        addSearchIfNeeded()

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.batchError
            isVisible = false
        }
        root.addView(errorView)

        continueButton = SwStyle.text(this, 16f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.batchContinue
            // The primary action must never render as a blank cyan pill: its label travels to
            // TalkBack through contentDescription as well.
            text = getString(R.string.tools_batch_continue)
            contentDescription = getString(R.string.tools_batch_continue)
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

    /**
     * T1: the search field belongs in the chooser once there are more than 8 documents. The root
     * is built before the library loads, so this is called again after [docs] is filled.
     */
    private fun addSearchIfNeeded() {
        if (docs.size <= 8 || searchView != null || !::listHost.isInitialized) return
        val scroll = listHost.parent as? ScrollView ?: return
        val root = scroll.parent as? LinearLayout ?: return
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
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderList()
                }
            })
        }
        searchView = search
        root.addView(search, root.indexOfChild(scroll), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BatchActivity, 48f)
        ).apply { topMargin = SwStyle.dp(this@BatchActivity, 8f) })
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
            // Council §5: every batch result is a new copy under the source stem, and the source
            // is kept. Say so before the run, not only on the result screen.
            .setMessage(
                getString(R.string.tools_batch_action_note) + "\n" +
                    getString(R.string.co_new_copy_receipt)
            )
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
            hint = getString(R.string.tools_pick_custom_size)
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
        // v2 UI: 03_tools_setup_picker — the same four levels as the document compress sheet and
        // the Compress setup page (one row each, so no Hindi label can be squeezed or clipped).
        val levels = listOf(
            Triple(R.id.batchLevelBest, R.string.compress_level_low, 0),
            Triple(R.id.batchLevelBalanced, R.string.compress_level_medium, 1),
            Triple(R.id.batchLevelSmaller, R.string.compress_level_high, 2),
            Triple(R.id.batchLevelSmallest, R.string.compress_level_ultra, 3)
        )
        val views = mutableListOf<TextView>()
        fun paint() {
            views.forEachIndexed { i, chip -> paintChip(chip, i == compressLevel) }
        }
        for ((id, title, index) in levels) {
            val chip = chip(getString(title), id).apply {
                tag = ToolSetupActivity.CHIP_TAG
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setOnClickListener {
                    compressLevel = index
                    target.setText("")
                    paint()
                }
            }
            views += chip
            content.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BatchActivity, 48f)
            ).apply { if (index < levels.lastIndex) bottomMargin = SwStyle.dp(this@BatchActivity, 8f) })
        }
        content.addView(target, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@BatchActivity, 48f)
        ).apply { topMargin = SwStyle.dp(this@BatchActivity, 4f) })
        paint()
        sheet.setContent(content)
        sheet.setPrimary(R.string.tools_batch_start)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val size = target.text.toString().trim()
            // H3-7: same parser and ceiling as the Compress sheet; a huge target is named, not
            // silently turned into 2 GiB by Int saturation.
            val parsed = if (size.isEmpty()) null else CompressTarget.parse(size)
            val measured = parsed as? CompressTarget.Result.Bytes
            val targetBytes = when (parsed) {
                null, CompressTarget.Result.Blank -> null
                is CompressTarget.Result.Bytes -> parsed.value
                else -> null
            }
            if (size.isNotEmpty() && measured == null) {
                sheet.setMessage(
                    if (parsed == CompressTarget.Result.TooLarge) R.string.compress_target_too_large
                    else R.string.tools_setup_bad_target
                )
            } else {
                sheet.dismiss()
                val (quality, maxDim) = when (compressLevel) {
                    0 -> 80 to null
                    2 -> 50 to 1600
                    3 -> 38 to 1200
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
        formatRow.addView(jpg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@BatchActivity, 8f)
        })
        formatRow.addView(png, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        zipRow.addView(perDoc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@BatchActivity, 8f)
        })
        zipRow.addView(all, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            // A password is on screen: block screenshots, recents and casting like every
            // other password sheet in the app (TYPE12 item 4).
            .secure()
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
        // H2-4: "Export as PDF" is the batch action that writes into public Documents. Before
        // Android 10 that needs the runtime storage grant, so it is asked for first, with a plain
        // reason, instead of every document failing with a raw EACCES line.
        if (action is ScanRepository.BatchAction.ExportPdf &&
            DownloadSaver.needsLegacyStoragePermission(this)
        ) {
            pendingAction = { start(action) }
            errorView.text = getString(R.string.f7save_perm_why_documents)
            errorView.isVisible = true
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
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
        requestedIds = ids
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
                errorView.text = L3FailureCopy.message(this@BatchActivity, e)
                errorView.isVisible = true
            }
        }
    }

    private fun showSummary(results: List<ScanRepository.BatchItem>) {
        val done = results.count { it.ok }
        val failed = results.size - done
        // T1: a cancelled run stops early; the remaining requested documents were not processed.
        val notProcessed = notProcessedCount(requestedIds, results)
        val sheet = SwSheet.builder(this)
            .setTitle(getString(R.string.tools_batch_summary_title_t1, done, failed, notProcessed))
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
        // L3-04: a 100-document summary scrolls inside its own bounded area, so
        // "Share all" in the sheet footer stays reachable at 200% type.
        val SUMMARY_MAX_HEIGHT = resources.displayMetrics.heightPixels / 2
        sheet.setContent(androidx.core.widget.NestedScrollView(this).apply {
            addView(list)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SUMMARY_MAX_HEIGHT
            )
        })
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
        // H2-1: (stored file, outgoing name). A library PDF is always stored as scan.pdf, so it
        // goes out under its document's own name; export files already carry theirs.
        val items = mutableListOf<Pair<File, String>>()
        for (item in results.filter { it.ok }) {
            item.files.forEach { items += it to it.name }
            val doc = item.outputId?.let { runCatching { repo.getDocument(it) }.getOrNull() }
            if (doc != null) items += ShareUtils.documentItems(listOf(doc))
            if (item.outputId == null && item.files.isEmpty() && item.savedName != null) {
                runCatching { repo.getDocument(item.docId) }.getOrNull()
                    ?.let { items += ShareUtils.documentItems(listOf(it)) }
            }
        }
        val shareable = items.filter { it.first.isFile }.distinctBy { it.first }
        val uris = ShareUtils.namedUris(this, shareable)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.tools_result_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val type = if (items.all { it.second.endsWith(".pdf") }) "application/pdf" else "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData(ClipDescription("ScanWhirl batch", arrayOf(type)), ClipData.Item(uris.first()))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            // fixshare: every Uri must be in ClipData too — the Android 10+ grant model follows it,
            // so a ClipData with only the first file shared one file of a batch.
            ShareUtils.multiShareIntent(this, uris, type, "ScanWhirl batch")
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
            // L3-04: wrap height with a 48dp floor, never a clipped 40dp chip.
            maxLines = Int.MAX_VALUE
            minimumHeight = SwStyle.dp(this@BatchActivity, 48f)
            setPadding(
                SwStyle.dp(this@BatchActivity, 8f), SwStyle.dp(this@BatchActivity, 8f),
                SwStyle.dp(this@BatchActivity, 8f), SwStyle.dp(this@BatchActivity, 8f)
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
        // L3-04: TalkBack hears the choice as a checked state, not just a colour/tick.
        chipView.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                info.isChecked = selected
            }
        }
    }

    companion object {
        const val EXTRA_DOC_IDS = "doc_ids"
        private const val KEY_SELECTED = "t1_batch_selected"
        private const val KEY_QUERY = "t1_batch_query"
        private const val KEY_REQUESTED = "t1_batch_requested"

        /** Requested ids that produced no result (the run was cancelled before reaching them). */
        internal fun notProcessedCount(
            requested: List<String>,
            results: List<ScanRepository.BatchItem>
        ): Int {
            val processed = results.map { it.docId }.toSet()
            return requested.count { it !in processed }
        }

        fun intent(context: Context, ids: List<String>): Intent =
            Intent(context, BatchActivity::class.java)
                .putStringArrayListExtra(EXTRA_DOC_IDS, ArrayList(ids))
    }
}
