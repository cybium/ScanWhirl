package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.MergePdfActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.protect.UnlockSheet
import com.scanwhirl.app.util.IdCard
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.PaperSize
import com.scanwhirl.app.util.StatusLine
import com.scanwhirl.app.util.ZipExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * v2 UI: 03_tools_hub — one setup screen for every L4 tool.
 *
 * The source picker and the option views are built per tool: a ranges field for Split and
 * Extract, format/quality for PDF to images, a target size for Compress, two image pickers for
 * ID card, and so on. Everything is built with the kit (`SwStyle`) on the ink background.
 * Running the tool publishes through `ScanRepository` and opens [ToolResultActivity].
 */
class ToolSetupActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var tool: SwTool
    private var toolId = ""
    private var docs: List<ScanDocument> = emptyList()
    /** False until the first library listing arrives (C07: the layout is up first). */
    private var docsLoaded = false

    /** Selected source documents, in tap order (Merge keeps this order). */
    private val selected = LinkedHashSet<String>()

    private var format = ScanRepository.ImageExportFormat.JPG
    private var quality = 90
    private var level = 1
    private var target: Long? = null
    private var frontBytes: ByteArray? = null
    private var backBytes: ByteArray? = null
    private var rangesText = ""
    private var nameText = ""
    private var targetText = ""
    private var wordPictures = false
    private var excelTablesOnly = true
    private var compressEstimates: List<Long>? = null
    private var estimatesFor: String? = null

    private lateinit var column: LinearLayout
    private lateinit var optionsHost: LinearLayout
    private lateinit var errorView: TextView
    private lateinit var runButton: TextView
    private lateinit var progressOverlay: View

    private val pickFront = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        readImage(uri) { frontBytes = it; render() }
    }

    private val pickBack = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        readImage(uri) { backBytes = it; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        val id = intent.getStringExtra(EXTRA_TOOL_ID).orEmpty()
        val found = ToolRegistry.byId(id)
        if (found == null) {
            finish()
            return
        }
        tool = found
        toolId = id
        nameText = ""
        // C07: draw the screen first, then stat the library off the main thread.
        setContentView(buildRoot())
        render()
        loadDocs(firstLoad = true)
    }

    override fun onResume() {
        super.onResume()
        if (!::tool.isInitialized) return
        loadDocs(firstLoad = false)
    }

    /**
     * C07: `listDocuments` stats every page of every document. It runs in the caller's lifecycle
     * scope on IO; the setup chrome is already on screen while it works.
     */
    private fun loadDocs(firstLoad: Boolean) {
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { repo.listDocuments() }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed || !::tool.isInitialized) return@launch
            docs = fresh
            if (firstLoad) {
                docs.firstOrNull()?.let { selected += it.id }
                nameText = if (toolId == "duplicate" || toolId == "merge" || toolId == "zip") {
                    ""
                } else {
                    docs.firstOrNull()?.name.orEmpty()
                }
            } else {
                selected.retainAll { id -> docs.any { it.id == id } }
            }
            docsLoaded = true
            render()
        }
    }

    // ---- layout -----------------------------------------------------------

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        val scroll = ScrollView(this).apply { id = R.id.toolSetupScroll; isFillViewport = true }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = SwStyle.dp(this@ToolSetupActivity, 18f)
            setPadding(pad, SwStyle.dp(this@ToolSetupActivity, 10f), pad, SwStyle.dp(this@ToolSetupActivity, 40f))
        }
        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        progressOverlay = buildProgress()
        root.addView(progressOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.applySystemBarsPadding()
        return root
    }

    private fun buildProgress(): View = FrameLayout(this).apply {
        id = R.id.toolSetupProgress
        isVisible = false
        setBackgroundColor(0xCC0E1316.toInt())
        isClickable = true
        addView(
            LinearLayout(this@ToolSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ProgressBar(this@ToolSetupActivity))
                addView(
                    SwStyle.text(this@ToolSetupActivity, 14f, SwStyle.color(this@ToolSetupActivity, R.color.text_hologram_mid)).apply {
                        text = getString(R.string.tools_setup_working)
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 12f) }
                )
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
    }

    private fun render() {
        column.removeAllViews()
        column.addView(header())
        column.addView(sectionLabel(getString(R.string.tools_setup_source)))
        if (tool.id == "id_card") {
            column.addView(idCardSource())
        } else {
            column.addView(documentSource())
        }
        column.addView(sectionLabel(getString(R.string.tools_setup_options)))
        optionsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.toolSetupOptions
        }
        buildOptions()
        column.addView(optionsHost)

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.toolSetupError
            isVisible = false
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 10f) })

        runButton = actionButton(getString(R.string.tools_setup_run), primary = true).apply {
            id = R.id.toolSetupRun
            setOnClickListener { run() }
        }
        column.addView(runButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolSetupActivity, 54f)
        ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 18f) })
    }

    private fun header(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(SwStyle.iconButton(
            this, R.drawable.ic_sym_arrow_back, SwStyle.color(this, R.color.text_hologram_hi)
        ).apply {
            contentDescription = getString(R.string.tools_setup_back)
            layoutParams = LinearLayout.LayoutParams(
                SwStyle.dp(this@ToolSetupActivity, 48f), SwStyle.dp(this@ToolSetupActivity, 48f)
            )
            setOnClickListener { finish() }
        })
        bar.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(tool.title)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@ToolSetupActivity, 10f)
            }
        )
        if (tool.pro) {
            bar.addView(SwStyle.text(this, 10f, SwStyle.color(this, R.color.gold_pro_primary), bold = true).apply {
                text = getString(R.string.badge_pro)
                setPadding(
                    SwStyle.dp(this@ToolSetupActivity, 8f), SwStyle.dp(this@ToolSetupActivity, 3f),
                    SwStyle.dp(this@ToolSetupActivity, 8f), SwStyle.dp(this@ToolSetupActivity, 3f)
                )
                background = SwStyle.pill(
                    this@ToolSetupActivity,
                    SwStyle.color(this@ToolSetupActivity, R.color.gold_pro_primary),
                    SwStyle.color(this@ToolSetupActivity, R.color.gold_pro_glow),
                    SwStyle.color(this@ToolSetupActivity, R.color.gold_tile_border)
                )
            })
        }
        return bar
    }

    private fun sectionLabel(text: String): TextView =
        SwStyle.text(this, 12f, SwStyle.color(this, R.color.text_hologram_mid), bold = true, letterSpacing = 0.08f).apply {
            this.text = text.uppercase(java.util.Locale.getDefault())
            setPadding(0, SwStyle.dp(this@ToolSetupActivity, 16f), 0, SwStyle.dp(this@ToolSetupActivity, 8f))
        }

    private fun documentSource(): View {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.toolSetupSource
        }
        if (docs.isEmpty()) {
            list.addView(SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                text = getString(R.string.tools_setup_no_docs)
                val pad = SwStyle.dp(this@ToolSetupActivity, 14f)
                setPadding(pad, pad, pad, pad)
                background = SwStyle.card(this@ToolSetupActivity, 16f)
            })
            return list
        }
        for (doc in docs) {
            val mark = ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_check)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary)
                )
                visibility = if (doc.id in selected) View.VISIBLE else View.INVISIBLE
                layoutParams = LinearLayout.LayoutParams(
                    SwStyle.dp(this@ToolSetupActivity, 22f), SwStyle.dp(this@ToolSetupActivity, 22f)
                ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 10f) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                id = R.id.toolSetupDocRow
                tag = doc.id
                isClickable = true
                isFocusable = true
                background = if (doc.id in selected) {
                    SwStyle.gradient(
                        this@ToolSetupActivity,
                        SwStyle.color(this@ToolSetupActivity, R.color.glass_surface_raised),
                        SwStyle.color(this@ToolSetupActivity, R.color.glass_surface),
                        16f,
                        SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary)
                    )
                } else {
                    SwStyle.card(this@ToolSetupActivity, 16f)
                }
                val pad = SwStyle.dp(this@ToolSetupActivity, 14f)
                setPadding(pad, pad, pad, pad)
            }
            row.addView(mark)
            row.addView(
                SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                    text = doc.name
                    maxLines = 2
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                SwStyle.text(this, 12f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                    text = resources.getQuantityString(
                        R.plurals.tools_setup_doc_meta, doc.pageCount, doc.pageCount,
                        StatusLine.formatBytes(doc.pdfFile.length())
                    )
                }
            )
            if (doc.isLocked) {
                row.addView(
                    ImageView(this).apply {
                        setImageResource(R.drawable.ic_sym_lock)
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            SwStyle.color(this@ToolSetupActivity, R.color.gold_pro_primary)
                        )
                        contentDescription = getString(R.string.tools_setup_locked)
                    },
                    LinearLayout.LayoutParams(
                        SwStyle.dp(this@ToolSetupActivity, 18f), SwStyle.dp(this@ToolSetupActivity, 18f)
                    ).apply { marginStart = SwStyle.dp(this@ToolSetupActivity, 8f) }
                )
            }
            row.setOnClickListener {
                if (doc.isLocked) promptUnlock(doc) else choose(doc.id)
            }
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 8f) })
        }
        if (tool.id == "merge") {
            list.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
                text = getString(R.string.tools_setup_merge_device)
                gravity = Gravity.CENTER
                isClickable = true
                minimumHeight = SwStyle.dp(this@ToolSetupActivity, 44f)
                setOnClickListener { startActivity(MergePdfActivity.intent(this@ToolSetupActivity)) }
            })
        }
        return list
    }

    private fun idCardSource(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            id = R.id.toolSetupSource
        }
        val side = { label: Int, bytes: ByteArray?, id: Int, pick: () -> Unit ->
            sideView(label, bytes, id, pick)
        }
        row.addView(
            side(R.string.tools_setup_id_front, frontBytes, R.id.toolSetupFront) { pickFront.launch("image/*") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f)
            }
        )
        row.addView(
            side(R.string.tools_setup_id_back, backBytes, R.id.toolSetupBack) { pickBack.launch("image/*") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@ToolSetupActivity, 8f)
            }
        )
        return row
    }

    /** One picture slot of the ID-card setup (front or back). */
    private fun sideView(label: Int, bytes: ByteArray?, id: Int, pick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = SwStyle.dp(this@ToolSetupActivity, 10f)
            setPadding(pad, pad, pad, pad)
            background = SwStyle.card(this@ToolSetupActivity, 16f)
            isClickable = true
            isFocusable = true
            setId(id)
            setOnClickListener { pick() }
            addView(
                ImageView(this@ToolSetupActivity).apply {
                    setImageResource(R.drawable.ic_sym_add_photo_alternate)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary)
                    )
                    bytes?.let { data ->
                        runCatching {
                            setImageBitmap(ImageTools.decodeSampled(data, 600))
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolSetupActivity, 110f)
                )
            )
            addView(
                SwStyle.text(
                    this@ToolSetupActivity,
                    13f,
                    SwStyle.color(this@ToolSetupActivity, R.color.text_hologram_mid)
                ).apply {
                    text = getString(label)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 6f) }
            )
        }

    // ---- options ----------------------------------------------------------

    private fun buildOptions() {
        when (tool.id) {
            "split", "extract" -> buildRangesOption()
            "pdf_to_images" -> buildPdfToImagesOptions()
            "compress" -> buildCompressOptions()
            "duplicate" -> buildNameOption()
            "merge" -> buildMergeOptions()
            "zip" -> buildZipOptions()
            "long_image" -> buildLongImageOption()
            "to_word" -> buildWordOption()
            "to_powerpoint" -> buildPptxOption()
            "to_excel" -> buildExcelOptions()
            "remove_metadata" -> buildCleanOption()
            "id_card" -> buildIdCardOption()
            else -> optionsHost.addView(note(getString(R.string.tools_setup_nothing)))
        }
    }

    private fun buildRangesOption() {
        optionsHost.addView(field(R.string.tools_setup_ranges_hint, R.id.toolSetupRanges, rangesText) { rangesText = it })
        val preview = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            id = R.id.toolSetupRangePreview
            setLineSpacing(0f, 1.2f)
        }
        optionsHost.addView(preview)
        updateRangePreview(preview)
    }

    private fun updateRangePreview(preview: TextView) {
        val doc = selected.firstOrNull()?.let { id -> docs.firstOrNull { it.id == id } }
        if (doc == null) {
            preview.text = getString(R.string.tools_setup_pick_one)
            return
        }
        if (rangesText.isBlank()) {
            preview.text = getString(R.string.tools_setup_ranges_example)
            preview.setTextColor(SwStyle.color(this, R.color.text_hologram_dim))
            return
        }
        try {
            val spans = PageRanges.parse(rangesText, doc.pageCount)
            val labels = spans.joinToString(", ") { PageRanges.label(it) }
            preview.text = if (tool.id == "split") {
                resources.getQuantityString(
                    R.plurals.tools_setup_split_preview, spans.size, spans.size, labels
                )
            } else {
                val pages = spans.sumOf { it.size }
                resources.getQuantityString(
                    R.plurals.tools_setup_extract_preview, pages, pages, labels
                )
            }
            preview.setTextColor(SwStyle.color(this, R.color.ok_green))
        } catch (e: IllegalArgumentException) {
            preview.text = e.message
            preview.setTextColor(SwStyle.color(this, R.color.laser_danger))
        }
    }

    private fun buildPdfToImagesOptions() {
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val jpg = chip(getString(R.string.tools_setup_jpg), R.id.toolSetupFormatJpg)
        val png = chip(getString(R.string.tools_setup_png), R.id.toolSetupFormatPng)
        fun paint() {
            val jpgOn = format == ScanRepository.ImageExportFormat.JPG
            paintChip(jpg, jpgOn)
            paintChip(png, !jpgOn)
        }
        jpg.setOnClickListener { format = ScanRepository.ImageExportFormat.JPG; paint(); render() }
        png.setOnClickListener { format = ScanRepository.ImageExportFormat.PNG; paint(); render() }
        chips.addView(jpg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, SwStyle.dp(this, 40f)
        ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f) })
        chips.addView(png, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, SwStyle.dp(this, 40f)
        ))
        optionsHost.addView(chips)
        paint()

        if (format == ScanRepository.ImageExportFormat.JPG) {
            val label = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                id = R.id.toolSetupQualityValue
                text = getString(R.string.tools_setup_quality_value, quality)
            }
            val seek = SeekBar(this).apply {
                id = R.id.toolSetupQuality
                max = 40
                progress = quality - 60
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        quality = 60 + value
                        label.text = getString(R.string.tools_setup_quality_value, quality)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    SwStyle.text(this@ToolSetupActivity, 13f, SwStyle.color(this@ToolSetupActivity, R.color.text_hologram_mid)).apply {
                        text = getString(R.string.tools_setup_quality)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(label)
            }
            optionsHost.addView(row)
            optionsHost.addView(seek, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        optionsHost.addView(note(getString(R.string.tools_setup_images_note)))
    }

    private fun buildCompressOptions() {
        optionsHost.addView(field(
            R.string.tools_setup_target_hint, R.id.toolSetupTarget, targetText,
            inputType = InputType.TYPE_CLASS_TEXT
        ) { targetText = it })
        val levels = listOf(
            Triple(getString(R.string.tools_setup_level_light), 0, R.id.toolSetupLevelLight),
            Triple(getString(R.string.tools_setup_level_medium), 1, R.id.toolSetupLevelMedium),
            Triple(getString(R.string.tools_setup_level_strong), 2, R.id.toolSetupLevelStrong)
        )
        val chips = mutableListOf<TextView>()
        fun paintChips() {
            chips.forEachIndexed { index, chip -> paintChip(chip, index == level) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((title, index, id) in levels) {
            val view = chip(title, id).apply {
                setOnClickListener {
                    level = index
                    target = null
                    targetText = ""
                    optionsHost.findViewById<EditText>(R.id.toolSetupTarget)?.setText("")
                    paintChips()
                    updateEstimateLine()
                }
            }
            chips += view
            row.addView(view, LinearLayout.LayoutParams(
                0, SwStyle.dp(this, 40f), 1f
            ).apply { marginEnd = if (index < 2) SwStyle.dp(this@ToolSetupActivity, 8f) else 0 })
        }
        optionsHost.addView(row)
        paintChips()
        val estimate = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            id = R.id.toolSetupEstimate
        }
        optionsHost.addView(estimate)
        startEstimate()
        updateEstimateLine()
        optionsHost.addView(note(getString(R.string.tools_setup_compress_note)))
    }

    /** The kit chip's selected look: cyan border, soft cyan fill and a check. */
    private fun paintChip(chipView: TextView, selected: Boolean) {
        chipView.isSelected = selected
        chipView.setTextColor(
            SwStyle.color(this, if (selected) R.color.cyan_primary else R.color.text_hologram_dim)
        )
        chipView.background = if (selected) {
            SwStyle.rounded(
                this,
                SwStyle.color(this, R.color.sw_cyan_soft),
                radiusDp = 12f,
                stroke = SwStyle.color(this, R.color.cyan_primary)
            )
        } else {
            chipBackground(false)
        }
        chipView.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (selected) R.drawable.ic_sym_check else 0, 0, 0, 0
        )
        chipView.compoundDrawablePadding = SwStyle.dp(this, 6f)
    }

    /** Estimates each level on two sample pages (the same extrapolation as CompressFlow). */
    private fun startEstimate() {
        val doc = selected.firstOrNull()?.let { id -> docs.firstOrNull { it.id == id } } ?: return
        if (estimatesFor == doc.id) return
        estimatesFor = doc.id
        compressEstimates = null
        updateEstimateLine()
        lifecycleScope.launch {
            val estimates = withContext(Dispatchers.IO) {
                runCatching {
                    val samples = listOfNotNull(
                        doc.pageFiles.firstOrNull(),
                        doc.pageFiles.getOrNull(doc.pageFiles.size / 2)
                    ).distinct().map { it.readBytes() }
                    compressLevels().map { (quality, maxDim) ->
                        val average = samples.map {
                            ImageTools.recompressJpeg(it, quality, maxDim).size.toLong()
                        }.average()
                        (average * doc.pageCount * 1.02 + 1024).toLong()
                    }
                }.getOrNull()
            }
            if (estimates != null && selected.firstOrNull() == doc.id) {
                compressEstimates = estimates
                updateEstimateLine()
            }
        }
    }

    private fun updateEstimateLine() {
        val line = optionsHost.findViewById<TextView>(R.id.toolSetupEstimate) ?: return
        val estimate = compressEstimates?.getOrNull(level)
        line.text = if (estimate != null) {
            getString(R.string.tools_setup_estimate, StatusLine.formatBytes(estimate))
        } else {
            ""
        }
    }

    private fun compressLevels(): List<Pair<Int, Int?>> = listOf(80 to null, 65 to 2000, 50 to 1600)

    private fun buildNameOption() {
        optionsHost.addView(field(
            R.string.tools_setup_name_hint, R.id.toolSetupName, nameText
        ) { nameText = it })
    }

    private fun buildMergeOptions() {
        optionsHost.addView(note(getString(R.string.tools_setup_merge_note)))
        buildNameOption()
    }

    private fun buildZipOptions() {
        optionsHost.addView(note(getString(R.string.tools_setup_zip_note)))
    }

    private fun buildLongImageOption() {
        val info = note(getString(R.string.tools_setup_long_calculating)).apply { id = R.id.toolSetupInfo }
        optionsHost.addView(info)
        val doc = selected.firstOrNull()?.let { id -> docs.firstOrNull { it.id == id } } ?: return
        lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) {
                runCatching {
                    val heights = LongImage.scaledHeights(doc.pageFiles)
                    LongImage.plan(heights).size
                }.getOrNull()
            }
            if (plan != null && !isFinishing) {
                info.text = resources.getQuantityString(R.plurals.tools_setup_long_note, plan, plan)
            } else if (!isFinishing) {
                info.text = getString(R.string.tools_setup_long_failed)
            }
        }
    }

    private fun buildWordOption() {
        val doc = selected.firstOrNull()?.let { id -> docs.firstOrNull { it.id == id } }
        val hasText = doc != null && runCatching { repo.hasOcr(doc.id) }.getOrDefault(false)
        optionsHost.addView(note(
            if (hasText) getString(R.string.tools_setup_word_note)
            else getString(R.string.tools_setup_no_text)
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = R.id.toolSetupWordPictures
            isClickable = true
            isFocusable = true
            val pad = SwStyle.dp(this@ToolSetupActivity, 12f)
            setPadding(pad, pad, pad, pad)
            background = SwStyle.rounded(
                this@ToolSetupActivity,
                if (wordPictures) SwStyle.color(this@ToolSetupActivity, R.color.glass_surface_raised)
                else SwStyle.color(this@ToolSetupActivity, R.color.glass_surface),
                radiusDp = 12f,
                stroke = if (wordPictures) SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary)
                else SwStyle.color(this@ToolSetupActivity, R.color.sw_field_border)
            )
            setOnClickListener {
                wordPictures = !wordPictures
                render()
            }
            addView(
                ImageView(this@ToolSetupActivity).apply {
                    setImageResource(R.drawable.ic_sym_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary)
                    )
                    visibility = if (wordPictures) View.VISIBLE else View.INVISIBLE
                },
                LinearLayout.LayoutParams(
                    SwStyle.dp(this@ToolSetupActivity, 20f), SwStyle.dp(this@ToolSetupActivity, 20f)
                ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 10f) }
            )
            addView(
                SwStyle.text(this@ToolSetupActivity, 14f, SwStyle.color(this@ToolSetupActivity, R.color.text_hologram_hi)).apply {
                    text = getString(R.string.tools_setup_word_pictures)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        optionsHost.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolSetupActivity, 48f)
        ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 6f) })
        if (wordPictures) optionsHost.addView(note(getString(R.string.tools_setup_word_pictures_note)))
    }

    private fun buildPptxOption() {
        optionsHost.addView(note(getString(R.string.tools_setup_pptx_note)))
    }

    private fun buildExcelOptions() {
        val tables = chip(getString(R.string.tools_setup_excel_tables_only), R.id.toolSetupExcelTables)
        val all = chip(getString(R.string.tools_setup_excel_all_text), R.id.toolSetupExcelAll)
        fun paint() {
            paintChip(tables, excelTablesOnly)
            paintChip(all, !excelTablesOnly)
        }
        tables.setOnClickListener {
            excelTablesOnly = true
            paint()
        }
        all.setOnClickListener {
            excelTablesOnly = false
            paint()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(tables, LinearLayout.LayoutParams(0, SwStyle.dp(this, 40f), 1f).apply {
            marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f)
        })
        row.addView(all, LinearLayout.LayoutParams(0, SwStyle.dp(this, 40f), 1f))
        optionsHost.addView(row)
        paint()
        optionsHost.addView(note(getString(R.string.tools_setup_excel_note)))
    }

    private fun buildCleanOption() {
        optionsHost.addView(note(getString(R.string.tools_setup_metadata_note)))
    }

    private fun buildIdCardOption() {
        optionsHost.addView(note(getString(R.string.tools_setup_id_note)))
        optionsHost.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            text = getString(R.string.tools_id_paper_title)
            setPadding(0, SwStyle.dp(this@ToolSetupActivity, 8f), 0, SwStyle.dp(this@ToolSetupActivity, 6f))
        })
        var paper = IdCardPrefs.paper(this)
        val a4 = idCardPaperChip(getString(R.string.tools_id_paper_a4))
        val letter = idCardPaperChip(getString(R.string.tools_id_paper_letter))
        fun paint() {
            paintPaperChip(a4, paper == PaperSize.A4)
            paintPaperChip(letter, paper == PaperSize.LETTER)
        }
        a4.setOnClickListener {
            paper = PaperSize.A4
            IdCardPrefs.rememberPaper(this, paper)
            paint()
        }
        letter.setOnClickListener {
            paper = PaperSize.LETTER
            IdCardPrefs.rememberPaper(this, paper)
            paint()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a4, LinearLayout.LayoutParams(0, SwStyle.dp(this@ToolSetupActivity, 42f), 1f).apply {
            marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f)
        })
        row.addView(letter, LinearLayout.LayoutParams(0, SwStyle.dp(this@ToolSetupActivity, 42f), 1f))
        optionsHost.addView(row)
        paint()
    }

    private fun idCardPaperChip(text: String): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim), bold = true).apply {
            this.text = text
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }

    private fun paintPaperChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(
            SwStyle.color(this, if (selected) R.color.cyan_primary else R.color.text_hologram_dim)
        )
        chip.background = if (selected) {
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
    }

    private fun note(text: String): TextView =
        SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            this.text = text
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@ToolSetupActivity, 8f), 0, SwStyle.dp(this@ToolSetupActivity, 4f))
        }

    private fun field(
        hintRes: Int,
        id: Int,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onChange: (String) -> Unit
    ): EditText = EditText(this).apply {
        this.id = id
        hint = getString(hintRes)
        setHintTextColor(SwStyle.color(this@ToolSetupActivity, R.color.text_hologram_dim))
        setTextColor(SwStyle.color(this@ToolSetupActivity, R.color.text_hologram_hi))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = SwStyle.rounded(
            this@ToolSetupActivity,
            SwStyle.color(this@ToolSetupActivity, R.color.glass_surface),
            radiusDp = 14f,
            stroke = SwStyle.color(this@ToolSetupActivity, R.color.sw_field_border)
        )
        val pad = SwStyle.dp(this@ToolSetupActivity, 14f)
        setPadding(pad, pad, pad, pad)
        this.inputType = inputType
        isSingleLine = true
        setText(initial)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString().orEmpty())
                if (this@ToolSetupActivity.tool.id == "split" || this@ToolSetupActivity.tool.id == "extract") {
                    optionsHost.findViewById<TextView>(R.id.toolSetupRangePreview)
                        ?.let { updateRangePreview(it) }
                }
            }
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        minimumHeight = SwStyle.dp(this@ToolSetupActivity, 52f)
    }

    private fun chip(text: String, id: Int): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim), bold = true).apply {
            this.id = id
            this.text = text
            gravity = Gravity.CENTER
            setPadding(
                SwStyle.dp(this@ToolSetupActivity, 10f), 0,
                SwStyle.dp(this@ToolSetupActivity, 10f), 0
            )
            background = chipBackground(false)
        }

    private fun chipBackground(on: Boolean) = SwStyle.rounded(
        this,
        if (on) SwStyle.color(this, R.color.glass_surface_raised)
        else SwStyle.color(this, R.color.glass_surface),
        radiusDp = 12f,
        stroke = if (on) SwStyle.color(this, R.color.cyan_primary)
        else SwStyle.color(this, R.color.sw_field_border)
    )

    private fun actionButton(text: String, primary: Boolean): TextView =
        SwStyle.text(this, 16f, SwStyle.color(
            this, if (primary) R.color.sw_disc_ink else R.color.text_hologram_hi
        ), bold = true).apply {
            this.text = text
            gravity = Gravity.CENTER
            background = if (primary) {
                SwStyle.rounded(this@ToolSetupActivity, SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary), radiusDp = 16f)
            } else {
                SwStyle.rounded(
                    this@ToolSetupActivity,
                    SwStyle.color(this@ToolSetupActivity, R.color.glass_surface),
                    radiusDp = 16f,
                    stroke = SwStyle.color(this@ToolSetupActivity, R.color.sw_cyan_hair)
                )
            }
            isClickable = true
            isFocusable = true
            minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
        }

    // ---- selection --------------------------------------------------------

    private fun choose(docId: String) {
        if (tool.id == "merge" || tool.id == "zip") {
            if (docId in selected) selected.remove(docId) else selected += docId
        } else {
            selected.clear()
            selected += docId
        }
        render()
    }

    /**
     * A locked source is not a dead end: L3's Unlock sheet takes the password, the document is
     * unlocked in place, and the same row becomes the selection so the tool can continue.
     */
    private fun promptUnlock(doc: ScanDocument) {
        UnlockSheet.show(
            activity = this,
            documentName = doc.name,
            verify = { candidate -> repo.verifyPdfPassword(doc.id, candidate) },
            onUnlocked = { password -> unlockAndSelect(doc, password) }
        )
    }

    private fun unlockAndSelect(doc: ScanDocument, password: String) {
        progressOverlay.isVisible = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.unlockPdf(doc.id, password) }
                docs = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
                if (tool.id == "merge" || tool.id == "zip") {
                    selected += doc.id
                } else {
                    selected.clear()
                    selected += doc.id
                }
                progressOverlay.isVisible = false
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progressOverlay.isVisible = false
                render()
                fail(getString(R.string.tools_setup_failed, e.message ?: ""))
            }
        }
    }

    // ---- run --------------------------------------------------------------

    private fun run() {
        errorView.isVisible = false
        val docId = selected.firstOrNull()
        when (tool.id) {
            "split" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                val spans = try {
                    PageRanges.parse(rangesText, doc.pageCount)
                } catch (e: IllegalArgumentException) {
                    return fail(e.message ?: getString(R.string.tools_setup_bad_ranges))
                }
                execute({ repo.splitByRange(doc.id, spans).map { it.id } }) { openDocs(it) }
            }
            "extract" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                val spans = try {
                    PageRanges.parse(rangesText, doc.pageCount)
                } catch (e: IllegalArgumentException) {
                    return fail(e.message ?: getString(R.string.tools_setup_bad_ranges))
                }
                val indices = spans.flatMap { it.indices().toList() }.distinct().sorted()
                execute({ listOf(repo.extractPages(doc.id, indices).id) }) { openDocs(it) }
            }
            "duplicate" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({ listOf(repo.duplicateDocument(doc.id, nameText.ifBlank { null }).id) }) { openDocs(it) }
            }
            "merge" -> {
                val ids = selected.toList()
                if (ids.size < 2) return fail(getString(R.string.tools_setup_need_two))
                execute({ listOf(repo.mergeDocuments(ids, nameText.ifBlank { null }).id) }) { openDocs(it) }
            }
            "compress" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                val size = targetText.trim()
                val targetBytes = if (size.isEmpty()) null else ImageTools.parseSizeInput(size)
                if (size.isNotEmpty() && targetBytes == null) return fail(getString(R.string.tools_setup_bad_target))
                target = targetBytes
                execute({
                    val out = if (targetBytes != null) {
                        repo.compressScanToTarget(doc.id, targetBytes).id
                    } else {
                        val (quality, maxDim) = compressLevel()
                        repo.compressScan(doc.id, quality, maxDim).id
                    }
                    listOf(out)
                }) { openDocs(it) }
            }
            "pdf_to_images" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val dir = outputDir()
                    val single = doc.pageCount == 1
                    val extension = if (format == ScanRepository.ImageExportFormat.PNG) "png" else "jpg"
                    val name = "${baseName(doc)}.$extension" + if (!single) ".zip" else ""
                    val out = File(dir, name)
                    repo.pdfToImages(doc.id, format, zip = !single, outFile = out, quality = quality)
                    ResultFiles(listOf(out), mimeFor(out))
                }) { openFiles(it.files, it.mime) }
            }
            "long_image" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val files = repo.exportLongImage(doc.id, outputDir(), doc.name)
                    ResultFiles(files, "image/jpeg")
                }) { openFiles(it.files, it.mime) }
            }
            "to_word" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val out = File(outputDir(), "${baseName(doc)}.docx")
                    repo.exportWord(doc.id, out, includePictures = wordPictures)
                    ResultFiles(listOf(out), DOCX_MIME)
                }) { openFiles(it.files, it.mime) }
            }
            "to_powerpoint" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val out = File(outputDir(), "${baseName(doc)}.pptx")
                    repo.exportPowerPoint(doc.id, out)
                    ResultFiles(listOf(out), PPTX_MIME)
                }) { openFiles(it.files, it.mime) }
            }
            "to_excel" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val out = File(outputDir(), "${baseName(doc)}.xlsx")
                    val report = repo.exportExcel(
                        doc.id, out,
                        tablesOnly = excelTablesOnly,
                        noTableNote = getString(R.string.tools_excel_no_table)
                    )
                    ExcelRun(report, doc)
                }) { run ->
                    val file = run.report.file
                    if (file == null) {
                        showNoTables(run.doc)
                    } else {
                        openFiles(
                            listOf(file), XLSX_MIME,
                            note = if (excelTablesOnly) {
                                getString(
                                    R.string.tools_excel_tables_note,
                                    run.report.tablePages, run.report.totalPages
                                )
                            } else null
                        )
                    }
                }
            }
            "zip" -> {
                val ids = selected.toList()
                if (ids.isEmpty()) return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val chosen = ids.mapNotNull { id -> docs.firstOrNull { it.id == id } }
                    val out = File(outputDir(), "${getString(R.string.tools_setup_zip_name)}.zip")
                    ZipExporter.export(chosen, out)
                    ResultFiles(listOf(out), "application/zip")
                }) { openFiles(it.files, it.mime) }
            }
            "remove_metadata" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({ listOf(repo.removeMetadata(doc.id).id) }) { openDocs(it) }
            }
            "id_card" -> {
                val front = frontBytes ?: return fail(getString(R.string.tools_setup_need_front))
                val back = backBytes ?: return fail(getString(R.string.tools_setup_need_back))
                execute({
                    val page = withContext(Dispatchers.Default) {
                        IdCard.compose(front, back, IdCardPrefs.paper(this@ToolSetupActivity))
                    }
                    listOf(repo.createDocument(getString(R.string.tools_id_card_name), listOf(page)).id)
                }) { openDocs(it) }
            }
            else -> fail(getString(R.string.tools_setup_nothing))
        }
    }

    private fun compressLevel(): Pair<Int, Int?> = when (level) {
        0 -> 80 to null
        2 -> 50 to 1600
        else -> 65 to 2000
    }

    private fun baseName(doc: ScanDocument): String =
        com.scanwhirl.app.util.ShareUtils.sanitizeName(doc.name).substringBeforeLast('.').ifBlank { "Scan" }

    private fun outputDir(): File =
        File(cacheDir, "documents/tools/${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}")
            .apply { mkdirs() }

    private fun mimeFor(file: File): String = when {
        file.name.endsWith(".zip") -> "application/zip"
        file.name.endsWith(".png") -> "image/png"
        else -> "image/jpeg"
    }

    private fun openDocs(ids: List<String>) {
        if (ids.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        startActivity(ToolResultActivity.intent(this, tool.id, docIds = ids))
    }

    private fun openFiles(files: List<File>, mime: String, note: String? = null) {
        if (files.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        startActivity(ToolResultActivity.intent(this, tool.id, files = files, mime = mime, note = note))
    }

    /** To Excel, Tables only: no page had a table, so nothing was written. Offer all text. */
    private fun showNoTables(doc: ScanDocument) {
        SwSheet.builder(this)
            .setTitle(R.string.tools_excel_no_tables_title)
            .setMessage(R.string.tools_excel_no_tables_msg)
            .setPrimary(R.string.tools_excel_export_all) { exportAllText(doc) }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    private fun exportAllText(doc: ScanDocument) {
        execute({
            val out = File(outputDir(), "${baseName(doc)}.xlsx")
            repo.exportExcel(
                doc.id, out,
                tablesOnly = false,
                noTableNote = getString(R.string.tools_excel_no_table)
            )
            out
        }) { file -> openFiles(listOf(file), XLSX_MIME) }
    }

    private fun fail(message: String) {
        errorView.text = message
        errorView.isVisible = true
    }

    private fun <T> execute(block: suspend () -> T, onDone: (T) -> Unit) {
        progressOverlay.isVisible = true
        runButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                progressOverlay.isVisible = false
                runButton.isEnabled = true
                onDone(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progressOverlay.isVisible = false
                runButton.isEnabled = true
                fail(getString(R.string.tools_setup_failed, e.message ?: ""))
            }
        }
    }

    private fun readImage(uri: Uri, onRead: (ByteArray) -> Unit) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
                }.getOrNull()
            }
            if (bytes != null) onRead(bytes) else fail(getString(R.string.tools_setup_image_failed))
        }
    }

    private data class ResultFiles(val files: List<File>, val mime: String)

    private data class ExcelRun(val report: ScanRepository.ExcelExport, val doc: ScanDocument)

    companion object {
        private const val EXTRA_TOOL_ID = "tool_id"
        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        private const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        fun intent(context: Context, toolId: String): Intent =
            Intent(context, ToolSetupActivity::class.java).putExtra(EXTRA_TOOL_ID, toolId)

        /** The tool id this intent opens (tests and the shell read it). */
        fun toolIdOf(intent: Intent): String? = intent.getStringExtra(EXTRA_TOOL_ID)
    }
}
