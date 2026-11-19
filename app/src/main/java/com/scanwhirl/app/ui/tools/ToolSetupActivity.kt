package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
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
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.ui.protect.UnlockSheet
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.ScanFilter
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
 * Extract, format/quality for PDF to images, a target size for Compress, and so on. ID card is
 * not here at all: [IdCardActivity] is its only path. Everything is built with the kit
 * (`SwStyle`) on the ink background.
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

    /** The four compress rows and the size label inside each (rebuilt with the options). */
    private val levelRows = mutableListOf<LinearLayout>()
    private val levelSizes = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        val id = intent.getStringExtra(EXTRA_TOOL_ID).orEmpty()
        val found = ToolRegistry.byId(id)
        if (found == null) {
            finish()
            return
        }
        // Registry-driven backstop: a Pro tool must not be executable from a direct internal
        // intent either (the Text screen's Context launch, a future caller). Free gets the same
        // shell offer the hub shows, before any executable UI is built.
        if (found.pro && !com.scanwhirl.app.util.ProGate.isPro(this)) {
            com.scanwhirl.app.ui.kit.ProOffer.require(this)
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
        // The source list can be longer than the screen: keep its scrollbar visible instead of
        // letting it fade out, so a long library is obviously scrollable (owner screenshot).
        val scroll = ScrollView(this).apply {
            id = R.id.toolSetupScroll
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = SwStyle.dp(this@ToolSetupActivity, 18f)
            // The pinned bar covers the bottom of the viewport: the list ends above it, so the
            // last source row or option is never hidden behind Continue.
            setPadding(
                pad,
                SwStyle.dp(this@ToolSetupActivity, 10f),
                pad,
                SwStyle.dp(this@ToolSetupActivity, BAR_CLEARANCE_DP)
            )
        }
        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Pinned action bar: Continue / the tool's action never scrolls away with the list.
        val bar = LinearLayout(this).apply {
            id = R.id.toolSetupBar
            orientation = LinearLayout.VERTICAL
            val side = SwStyle.dp(this@ToolSetupActivity, 18f)
            setPadding(side, SwStyle.dp(this@ToolSetupActivity, 10f), side, SwStyle.dp(this@ToolSetupActivity, 12f))
            setBackgroundColor(0xFF0E1316.toInt())
            addView(View(this@ToolSetupActivity).apply {
                setBackgroundColor(SwStyle.color(this@ToolSetupActivity, R.color.sw_cyan_hair))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolSetupActivity, 1f)
            ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 10f) })
        }
        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.toolSetupError
            isVisible = false
        }
        bar.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 8f) })
        runButton = actionButton(getString(R.string.tools_setup_run), primary = true).apply {
            id = R.id.toolSetupRun
            setOnClickListener { run() }
        }
        bar.addView(runButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolSetupActivity, 56f)
        ))
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })
        // The list's bottom clearance follows the real bar height (it grows while an error line
        // is shown), so the last row is never covered.
        bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h > 0 && column.paddingBottom != h) {
                column.setPadding(column.paddingLeft, column.paddingTop, column.paddingRight, h)
            }
        }

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
        levelRows.clear()
        levelSizes.clear()
        column.addView(header())
        column.addView(sectionLabel(getString(R.string.tools_pick_choose_doc)))
        column.addView(documentSource())
        column.addView(sectionLabel(getString(R.string.tools_setup_options)))
        optionsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.toolSetupOptions
        }
        buildOptions()
        column.addView(optionsHost)
        applyRunEnabled()
    }

    /**
     * H13 (found on device): Merge's Continue looked ready with one document selected and
     * tapping it only produced an error. It is now visibly disabled (greyed, no ripple)
     * until two documents are picked; the sheet's own note already says two are needed.
     * Other tools keep their existing validation (their errors explain the rest).
     */
    private fun applyRunEnabled() {
        if (!::runButton.isInitialized) return
        val ready = tool.id != "merge" || selected.size >= 2
        runButton.isEnabled = ready
        runButton.isClickable = ready
        runButton.alpha = if (ready) 1f else DISABLED_ALPHA
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

    /**
     * Sentence case, no Latin small-caps tracking (the mockups' section heading). The old
     * label shouted "SOURCE"/"OPTIONS" in capitals, which the owner read as developer chrome.
     */
    private fun sectionLabel(text: String): TextView =
        SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid), bold = true).apply {
            id = R.id.toolSetupSection
            this.text = text
            setPadding(0, SwStyle.dp(this@ToolSetupActivity, 20f), 0, SwStyle.dp(this@ToolSetupActivity, 8f))
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
            list.addView(documentRow(doc), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 8f) })
        }
        if (tool.id == "merge") {
            list.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
                text = getString(R.string.tools_setup_merge_device)
                gravity = Gravity.CENTER
                isClickable = true
                minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
                setOnClickListener { startActivity(MergePdfActivity.intent(this@ToolSetupActivity)) }
            })
        }
        return list
    }

    /**
     * One source in the chooser: the document's real first page (the library's own thumbnail
     * loader and cache, sampled off the main thread), name and "3 pages · 544 KB", a lock badge
     * for a protected file, and a check on the tinted row when it is selected. Rows are 76 dp.
     */
    private fun documentRow(doc: ScanDocument): LinearLayout {
        val selectedHere = doc.id in selected
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = R.id.toolSetupDocRow
            tag = doc.id
            isClickable = true
            isFocusable = true
            minimumHeight = SwStyle.dp(this@ToolSetupActivity, 76f)
            val pad = SwStyle.dp(this@ToolSetupActivity, 10f)
            setPadding(pad, pad, pad, pad)
            background = if (selectedHere) {
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
        }
        // 0: the tick stays the row's first child (the selection, never colour alone).
        row.addView(
            ImageView(this).apply {
                id = R.id.toolSetupDocCheck
                setImageResource(R.drawable.ic_sym_check)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary)
                )
                visibility = if (selectedHere) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(
                SwStyle.dp(this@ToolSetupActivity, 22f), SwStyle.dp(this@ToolSetupActivity, 22f)
            ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 10f) }
        )
        // 1: the first page, at least 56 dp wide, 1:1.414.
        val thumb = ImageView(this).apply {
            id = R.id.toolSetupDocThumb
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val thumbFrame = FrameLayout(this).apply {
            background = SwStyle.rounded(
                this@ToolSetupActivity,
                SwStyle.color(this@ToolSetupActivity, R.color.sw_paper_thumb),
                radiusDp = 8f
            )
            clipToOutline = true
            addView(thumb, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        row.addView(thumbFrame, LinearLayout.LayoutParams(
            SwStyle.dp(this@ToolSetupActivity, 56f), SwStyle.dp(this@ToolSetupActivity, 79f)
        ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 12f) })

        // 2: name (two lines, ellipsis) over the page-count and size line.
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(SwStyle.text(this, 15.5f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.toolSetupDocName
            text = doc.name
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        })
        info.addView(
            SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                id = R.id.toolSetupDocMeta
                text = resources.getQuantityString(
                    R.plurals.tools_setup_doc_meta, doc.pageCount, doc.pageCount,
                    StatusLine.formatBytes(doc.pdfFile.length())
                )
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 3f) }
        )
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 3: a locked source keeps its lock badge as a direct child of the row.
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

        // A real first page, through the library's shared loader/cache (off the main thread,
        // sampled, and dropped if the view is re-bound). A locked document shows paper only.
        val firstPage = doc.pageFiles.firstOrNull()
        if (firstPage != null && firstPage.exists() && !doc.isLocked) {
            PageThumbLoader.load(
                firstPage,
                (56f * resources.displayMetrics.density * 2).toInt(),
                ScanFilter.ORIGINAL,
                thumb,
                fitExactly = true,
                skipStale = true
            )
        } else {
            thumb.setImageDrawable(null)
            thumbFrame.setBackgroundColor(
                SwStyle.color(this, if (doc.isLocked) R.color.sw_thumb_locked else R.color.sw_paper_thumb)
            )
        }

        row.setOnClickListener {
            if (doc.isLocked) promptUnlock(doc) else choose(doc.id)
        }
        return row
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
            ViewGroup.LayoutParams.WRAP_CONTENT, SwStyle.dp(this, 48f)
        ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f) })
        chips.addView(png, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, SwStyle.dp(this, 48f)
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
        // The document's compress sheet wording, in the same order: quality first, smallest last.
        val levels = listOf(
            CompressLevel(R.string.compress_level_low, 0, R.id.toolSetupLevelBest),
            CompressLevel(R.string.compress_level_medium, 1, R.id.toolSetupLevelBalanced),
            CompressLevel(R.string.compress_level_high, 2, R.id.toolSetupLevelSmaller),
            CompressLevel(R.string.compress_level_ultra, 3, R.id.toolSetupLevelSmallest)
        )
        levelRows.clear()
        levelSizes.clear()
        // Full-width rows, one level per line: Hindi labels ("सबसे अच्छी क्वालिटी") never fit a
        // three-up chip row at 360 dp, which is what clipped "Mediu / m" on the owner's phone.
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.toolSetupLevels
        }
        for (level in levels) {
            val check = ImageView(this).apply {
                id = R.id.toolSetupLevelCheck
                setImageResource(R.drawable.ic_sym_check)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(this@ToolSetupActivity, R.color.cyan_primary)
                )
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = SwStyle.text(
                this, 14f, SwStyle.color(this, R.color.text_hologram_dim), bold = true
            ).apply {
                id = R.id.toolSetupLevelLabel
                tag = CHIP_TAG
                text = getString(level.titleRes)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }
            val size = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                id = R.id.toolSetupLevelSize
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            val row = LinearLayout(this).apply {
                id = level.id
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                val pad = SwStyle.dp(this@ToolSetupActivity, 12f)
                setPadding(pad, 0, pad, 0)
                background = chipBackground(false)
                setOnClickListener {
                    this@ToolSetupActivity.level = level.index
                    target = null
                    targetText = ""
                    optionsHost.findViewById<EditText>(R.id.toolSetupTarget)?.setText("")
                    paintLevels()
                }
            }
            row.addView(check, LinearLayout.LayoutParams(
                SwStyle.dp(this@ToolSetupActivity, 20f), SwStyle.dp(this@ToolSetupActivity, 20f)
            ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f) })
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f)
            })
            row.addView(size)
            levelRows += row
            levelSizes += size
            stack.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolSetupActivity, 48f)
            ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 8f) })
        }
        optionsHost.addView(stack)
        // The sheet ends with "Custom size"; the setup page does the same.
        optionsHost.addView(field(
            R.string.tools_pick_custom_size, R.id.toolSetupTarget, targetText,
            inputType = InputType.TYPE_CLASS_TEXT
        ) { targetText = it }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 4f) })
        paintLevels()
        startEstimate()
        // A re-render (selection change, onResume) rebuilds the size labels: show the estimates
        // that are already known instead of waiting for a job that is skipped.
        updateEstimateLines()
        optionsHost.addView(note(getString(R.string.tools_setup_compress_note)))
    }

    private data class CompressLevel(val titleRes: Int, val index: Int, val id: Int)

    /** The selected level: soft cyan fill, cyan border and a check, never colour alone. */
    private fun paintLevels() {
        levelRows.forEachIndexed { index, row ->
            val on = index == level
            row.isSelected = on
            row.background = if (on) {
                SwStyle.rounded(
                    this,
                    SwStyle.color(this, R.color.sw_cyan_soft),
                    radiusDp = 12f,
                    stroke = SwStyle.color(this, R.color.cyan_primary)
                )
            } else {
                chipBackground(false)
            }
            row.findViewById<View>(R.id.toolSetupLevelCheck).visibility =
                if (on) View.VISIBLE else View.INVISIBLE
            row.findViewById<TextView>(R.id.toolSetupLevelLabel).setTextColor(
                SwStyle.color(this, if (on) R.color.cyan_primary else R.color.text_hologram_dim)
            )
            row.findViewById<TextView>(R.id.toolSetupLevelSize).setTextColor(
                SwStyle.color(this, if (on) R.color.text_hologram_mid else R.color.text_hologram_dim)
            )
        }
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
        updateEstimateLines()
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
                updateEstimateLines()
            }
        }
    }

    /** Each level's own estimate sits on its row, like the compress sheet's radio list. */
    private fun updateEstimateLines() {
        val estimates = compressEstimates
        levelSizes.forEachIndexed { index, view ->
            view.text = estimates?.getOrNull(index)?.let { StatusLine.formatBytes(it) }.orEmpty()
        }
    }

    /** The four levels of the document compress sheet: (JPEG quality, longest page side). */
    private fun compressLevels(): List<Pair<Int, Int?>> =
        listOf(80 to null, 65 to 2000, 50 to 1600, 38 to 1200)

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
        // Hindi "सिर्फ़ तालिकाएँ" plus the selected check does not fit a half-width chip at
        // 360 dp, so the two choices stack full width like the compress levels.
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(tables, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 48f)
        ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 8f) })
        stack.addView(all, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 48f)
        ))
        optionsHost.addView(stack)
        paint()
        optionsHost.addView(note(getString(R.string.tools_setup_excel_note)))
    }

    private fun buildCleanOption() {
        optionsHost.addView(note(getString(R.string.tools_setup_metadata_note)))
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

    /**
     * A single-line option chip. Its label must fit at 360 dp in English and Hindi: no wrap,
     * no ellipsis, 48 dp tall tap target (the owner's phone clipped "Medium" to "Mediu / m").
     */
    private fun chip(text: String, id: Int): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim), bold = true).apply {
            this.id = id
            tag = CHIP_TAG
            this.text = text
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
     * A locked source is not a dead end: L3's Unlock sheet takes the password, then the user
     * is told what removing the protection does before anything is changed.
     */
    private fun promptUnlock(doc: ScanDocument) {
        UnlockSheet.show(
            activity = this,
            documentName = doc.name,
            verify = { candidate -> repo.verifyPdfPassword(doc.id, candidate) },
            onUnlocked = { password -> unlockAndSelect(doc, password) }
        )
    }

    /**
     * SAFE-UNLOCK-CONSENT: a right password only proves the file opens. Removing its password
     * changes the copy in the library for good, so that is a separate, explicit yes. Cancel,
     * Back and an outside tap leave the document encrypted and this row unselected; only the
     * primary button reaches [removeProtectionAndSelect].
     */
    private fun unlockAndSelect(doc: ScanDocument, password: String) {
        SwSheet.builder(this)
            .setTitle(R.string.tools_setup_unlock_consent_title)
            .setMessage(
                getString(R.string.tools_setup_unlock_consent_body, getString(tool.title))
            )
            .setPrimary(R.string.tools_setup_unlock_consent_primary) {
                removeProtectionAndSelect(doc, password)
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    /** Runs only after the explicit yes: unlocks the library copy and picks it for the tool. */
    private fun removeProtectionAndSelect(doc: ScanDocument, password: String) {
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
                }) { openDocs(it, targetBytes = targetBytes) }
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
            else -> fail(getString(R.string.tools_setup_nothing))
        }
    }

    private fun compressLevel(): Pair<Int, Int?> = when (level) {
        0 -> 80 to null
        2 -> 50 to 1600
        3 -> 38 to 1200
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

    /**
     * Opens the result for new library documents. [targetBytes] is Compress's wanted size, so the
     * result can say honestly when that size was not reached (T05).
     */
    private fun openDocs(ids: List<String>, targetBytes: Long? = null) {
        if (ids.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        startActivity(
            ToolResultActivity.intent(this, tool.id, docIds = ids, targetBytes = targetBytes)
        )
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

    /**
     * T37: a page with no recognised text is not a dead end. Name the setting that decides
     * whether the text can be read and give one tap into it; the message stays in the error
     * line after the sheet closes.
     */
    private fun showNoTextGuidance() {
        val message = getString(R.string.tools_word_no_text)
        errorView.text = message
        errorView.isVisible = true
        SwSheet.builder(this)
            .setTitle(R.string.tools_word_no_text_title)
            .setMessage(message)
            .setPrimary(R.string.tools_word_change_language) {
                startActivity(com.scanwhirl.app.ui.SettingsActivity.intent(this))
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
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
            } catch (e: ScanRepository.NoTextForExportException) {
                progressOverlay.isVisible = false
                runButton.isEnabled = true
                showNoTextGuidance()
            } catch (e: Exception) {
                progressOverlay.isVisible = false
                runButton.isEnabled = true
                fail(getString(R.string.tools_setup_failed, e.message ?: ""))
            }
        }
    }

    private data class ResultFiles(val files: List<File>, val mime: String)

    private data class ExcelRun(val report: ScanRepository.ExcelExport, val doc: ScanDocument)

    companion object {
        private const val EXTRA_TOOL_ID = "tool_id"
        /** Room kept at the end of the list for the pinned action bar (before its height is known). */
        private const val BAR_CLEARANCE_DP = 96f
        /** Every option chip label carries this tag, so a UI test can walk the chips on any tool. */
        const val CHIP_TAG = "swChip"
        /** Material's disabled alpha: a disabled button at full alpha still looks tappable. */
        private const val DISABLED_ALPHA = 0.38f
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
