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
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressTarget
import com.scanwhirl.app.tools.CompressionPresentation
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.DocumentActionDispatcher
import com.scanwhirl.app.ui.DocumentViewerActivity
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
import com.scanwhirl.app.util.OutputNames
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.SizeUnits
import com.scanwhirl.app.util.StatusLine
import com.scanwhirl.app.util.ZipExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /**
     * F11 (H3-4): the ids whose document has an unsaved page draft, read with the listing (off the
     * main thread). Run uses it as a one-frame pre-check, so a normal tap still validates at once;
     * the authoritative re-read happens in [DocumentActionDispatcher.confirmSavedPages].
     */
    private var draftIds: Set<String> = emptySet()

    /** Selected source documents, in tap order (Merge keeps this order). */
    private val selected = LinkedHashSet<String>()

    private var format = ScanRepository.ImageExportFormat.JPG
    private var quality = 90
    private var level = 1
    private var target: Long? = null
    private var rangesText = ""
    private var nameText = ""
    /** The last name we prefilled ourselves; if the user has edited it we never overwrite it. */
    private var autoName = ""
    private var targetText = ""
    private var wordPictures = false
    private var excelTablesOnly = true
    private var compressEstimates: List<Long>? = null
    private var estimatesFor: String? = null

    /** J2: only one of the two compress aims is on screen; each keeps its own choice. */
    private var compressMode: String = MODE_QUALITY
    /** J2: the unit chosen for the custom numeric size (decimal KB / MB). */
    private var compressUnit: String = UNIT_KB
    /** J2: which of the four target chips is selected, if any. */
    private var compressTargetIndex: Int? = null
    /** J2: the four target chips, repainted after a chip, a unit or a typed value changes. */
    private val compressTargetChips = mutableListOf<TextView>()
    /** J2: setting the field from a chip is not the same as the person typing over it. */
    private var compressSuppressFieldChange = false
    /** J2: the live run, so Cancel can stop it before the engine publishes anything. */
    private var compressJob: Job? = null
    /** J2: both option trees are built once; only the active aim is visible. */
    private lateinit var compressQualityBlock: View
    private lateinit var compressSizeBlock: View
    private lateinit var compressQualityMode: TextView
    private lateinit var compressSizeMode: TextView

    /** V2T T2: a caller may hand us one verified output document to act on. */
    private var preselectedDocId: String? = null

    private lateinit var column: LinearLayout
    private lateinit var optionsHost: LinearLayout
    private lateinit var errorView: TextView
    private lateinit var runButton: TextView
    private lateinit var progressOverlay: View
    /** J2: real page counts and the one safe Cancel, inside the progress overlay. */
    private lateinit var compressProgressText: TextView
    private lateinit var compressProgressCancel: TextView
    /** J2: shown only after a compress failure, beside the inline error. */
    private lateinit var compressRetry: TextView
    /** Scrollable spacer below the last option: the pinned Continue can never cover an input. */
    private lateinit var bottomSpacer: View
    private var pinnedBarHeight = 0

    /** The four compress rows and the size label inside each (rebuilt with the options). */
    private val levelRows = mutableListOf<LinearLayout>()
    private val levelSizes = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // With the keyboard open the viewport shrinks; the ScrollView must resize (not pan) so
        // every option stays reachable by scrolling above the pinned action bar.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        repo = ScanWhirlApp.from(this).repository
        val id = intent.getStringExtra(EXTRA_TOOL_ID).orEmpty()
        preselectedDocId = intent.getStringExtra(EXTRA_SELECTED_DOC_ID)
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
        autoName = ""
        // H5-2: a recreation (rotation, dark mode, font size, language) must come back on the
        // same document with the same typed values, never on whatever is newest.
        if (savedInstanceState != null) restoreSetupState(savedInstanceState)
        // C07: draw the screen first, then stat the library off the main thread.
        setContentView(buildRoot())
        render()
        loadDocs(firstLoad = savedInstanceState == null)
    }

    // ---- recreation (H5-2) ------------------------------------------------

    /**
     * H5-2: the screen has no `android:configChanges`, so every rotation / theme / font-size /
     * language change recreates it. Without this it silently fell back to the newest document
     * and cleared the typed ranges, name and target size, so Run could work on the wrong file.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::tool.isInitialized) return
        outState.putStringArrayList(KEY_SELECTED, ArrayList(selected))
        outState.putString(KEY_FORMAT, format.name)
        outState.putInt(KEY_QUALITY, quality)
        outState.putInt(KEY_LEVEL, level)
        outState.putString(KEY_RANGES, rangesText)
        outState.putString(KEY_NAME, nameText)
        outState.putString(KEY_TARGET, targetText)
        outState.putBoolean(KEY_WORD_PICTURES, wordPictures)
        outState.putBoolean(KEY_EXCEL_TABLES, excelTablesOnly)
        // J2: a rotation keeps each compress aim and its own unit, never a reset to quality.
        outState.putString(KEY_COMPRESS_MODE, compressMode)
        outState.putString(KEY_COMPRESS_UNIT, compressUnit)
    }

    private fun restoreSetupState(saved: Bundle) {
        selected.clear()
        saved.getStringArrayList(KEY_SELECTED)?.let { selected.addAll(it) }
        format = saved.getString(KEY_FORMAT)
            ?.let { name ->
                ScanRepository.ImageExportFormat.values().firstOrNull { it.name == name }
            }
            ?: format
        quality = saved.getInt(KEY_QUALITY, quality)
        level = saved.getInt(KEY_LEVEL, level)
        rangesText = saved.getString(KEY_RANGES).orEmpty()
        nameText = saved.getString(KEY_NAME).orEmpty()
        targetText = saved.getString(KEY_TARGET).orEmpty()
        wordPictures = saved.getBoolean(KEY_WORD_PICTURES, false)
        excelTablesOnly = saved.getBoolean(KEY_EXCEL_TABLES, true)
        compressMode = saved.getString(KEY_COMPRESS_MODE)?.takeIf {
            it == MODE_QUALITY || it == MODE_SIZE
        } ?: MODE_QUALITY
        compressUnit = saved.getString(KEY_COMPRESS_UNIT)?.takeIf {
            it == UNIT_KB || it == UNIT_MB
        } ?: UNIT_KB
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
            val (fresh, drafts) = withContext(Dispatchers.IO) {
                val listed = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
                listed to openDraftsIn(listed)
            }
            if (isFinishing || isDestroyed || !::tool.isInitialized) return@launch
            docs = fresh
            draftIds = drafts
            if (firstLoad) {
                preselectedDocId?.let { preferred ->
                    if (docs.any { it.id == preferred }) selected += preferred
                }
                if (selected.isEmpty()) docs.firstOrNull()?.let { selected += it.id }
                // Council §5: the editable field is prefilled with the source stem only, never a
                // verb suffix. Because the source itself occupies that stem, forNewCopy returns
                // the collision-safe "<stem> (2)".
                if (toolId == "duplicate" || toolId == "merge") {
                    nameText = prefillCopyName()
                    autoName = nameText
                } else if (toolId == "zip") {
                    nameText = ""
                } else {
                    nameText = docs.firstOrNull()?.name.orEmpty()
                }
            } else {
                selected.retainAll { id -> docs.any { it.id == id } }
            }
            docsLoaded = true
            render()
        }
    }

    /**
     * F11 (H3-4): the ids among [listed] whose document has an unsaved page draft. A manifest
     * that cannot be read counts as a draft (it is not "no draft").
     */
    private suspend fun openDraftsIn(listed: List<ScanDocument>): Set<String> =
        listed.mapNotNull { doc ->
            val draft = runCatching { repo.draftOf(doc.id) }
            val open = draft.isFailure || (draft.getOrNull()?.changes ?: 0) > 0
            if (open) doc.id else null
        }.toSet()

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
            setPadding(pad, SwStyle.dp(this@ToolSetupActivity, 10f), pad, 0)
        }
        // Clearance is a real, scrollable child rather than bottom padding: Android does not
        // always count a LinearLayout's bottom padding in a fillViewport ScrollView's scroll
        // range, which is how the Split range field ended up under Continue on the phone.
        bottomSpacer = View(this).apply { tag = BOTTOM_SPACER_TAG }
        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Pinned action bar: Continue / the tool's action never scrolls away with the list.
        val bar = LinearLayout(this).apply {
            id = R.id.toolSetupBar
            orientation = LinearLayout.VERTICAL
            val side = SwStyle.dp(this@ToolSetupActivity, 18f)
            setPadding(side, SwStyle.dp(this@ToolSetupActivity, 10f), side, SwStyle.dp(this@ToolSetupActivity, 12f))
            setBackgroundColor(SwStyle.color(this@ToolSetupActivity, R.color.tok_surface))
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
        // J2: Retry appears only after a failure, next to the error, so the exact inputs the
        // person typed stay on screen and one tap repeats the same run.
        compressRetry = actionButton(getString(R.string.ux130_l3_compress_retry), primary = false).apply {
            tag = COMPRESS_RETRY_TAG
            isVisible = false
            minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
            setOnClickListener { run() }
        }
        bar.addView(compressRetry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolSetupActivity, 48f)
        ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 6f) })
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })
        // The spacer follows the real bar height (it grows while an error line is shown), so the
        // last input can always be scrolled clear of Continue at 100% font, 360 and 411 dp.
        bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h > 0 && pinnedBarHeight != h) {
                pinnedBarHeight = h
                resizeBottomSpacer()
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
                // J2: this label carries REAL page numbers only. The quality aim keeps the
                // honest "N pages" count; the size aim is updated by the engine's onPage.
                compressProgressText = SwStyle.text(
                    this@ToolSetupActivity, 14f,
                    SwStyle.color(this@ToolSetupActivity, R.color.text_hologram_mid)
                ).apply {
                    tag = COMPRESS_PROGRESS_TAG
                    text = getString(R.string.tools_setup_working)
                    gravity = Gravity.CENTER
                }
                addView(
                    compressProgressText,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 12f) }
                )
                // J2: one safe Cancel while the work is still in staging. Cancelling the job
                // stops the staged encode; no document is published and the inputs stay put.
                compressProgressCancel = actionButton(
                    getString(R.string.sheet_cancel), primary = false
                ).apply {
                    id = View.generateViewId()
                    tag = COMPRESS_CANCEL_TAG
                    minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
                    isVisible = false
                    setOnClickListener { cancelCompress() }
                }
                addView(
                    compressProgressCancel,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        SwStyle.dp(this@ToolSetupActivity, 48f)
                    ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 16f) }
                )
            },
            FrameLayout.LayoutParams(
                SwStyle.dp(this@ToolSetupActivity, 260f),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
    }

    private fun render() {
        column.removeAllViews()
        levelRows.clear()
        levelSizes.clear()
        column.addView(header())
        // VQA V114/V104/V105/V107: the first screen must say what the tool does, not just its
        // format name. One short line; no extra choice and no promise about the output.
        ToolFirstScreenCopy.text(this, tool.id)?.let { purpose ->
            column.addView(
                SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                    id = R.id.toolSetupPurpose
                    text = purpose
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                    setPadding(0, SwStyle.dp(this@ToolSetupActivity, 8f), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        column.addView(sectionLabel(getString(R.string.tools_pick_choose_doc)))
        column.addView(documentSource())
        column.addView(sectionLabel(getString(R.string.tools_setup_options)))
        optionsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.toolSetupOptions
        }
        buildOptions()
        column.addView(optionsHost)
        column.addView(bottomSpacer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, spacerHeight()
        ))
        applyRunEnabled()
    }

    /** At least one bar's worth of scrollable empty space below the last option. */
    private fun spacerHeight(): Int =
        maxOf(pinnedBarHeight + SwStyle.dp(this, 8f), SwStyle.dp(this, BAR_CLEARANCE_DP))

    private fun resizeBottomSpacer() {
        if (!::bottomSpacer.isInitialized) return
        bottomSpacer.layoutParams?.let { it.height = spacerHeight() }
        bottomSpacer.requestLayout()
    }

    /**
     * H13 (found on device): Merge's Continue looked ready with one document selected and
     * tapping it only produced an error. It is now visibly disabled (greyed, no ripple)
     * until two documents are picked; the sheet's own note already says two are needed.
     * Other tools keep their existing validation (their errors explain the rest).
     */
    private fun applyRunEnabled() {
        if (!::runButton.isInitialized) return
        // A name is mandatory wherever the screen offers an editable name field: an erased field
        // must never silently fall back to an auto-generated name.
        val ready = (tool.id != "merge" || selected.size >= 2) &&
            (!hasNameField() || nameText.isNotBlank())
        runButton.isEnabled = ready
        runButton.isClickable = ready
        runButton.alpha = if (ready) 1f else DISABLED_ALPHA
    }

    private fun hasNameField(): Boolean = toolId == "duplicate" || toolId == "merge"

    /** Inline validation under the name field; text watcher and rotation both rebuild it. */
    private fun refreshNameError() {
        if (!hasNameField()) return
        val error = optionsHost.findViewWithTag<TextView>(NAME_ERROR_TAG)
        error?.isVisible = nameText.isBlank()
        applyRunEnabled()
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
        // V2T T1: the durable result can always be reopened from setup.
        bar.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.rv_accent), bold = true).apply {
            text = getString(R.string.v2t_last_result)
            contentDescription = text
            minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(ToolResultActivity.intentLastResult(this@ToolSetupActivity)) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
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
        SwStyle.text(this, 16f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
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
    /**
     * FX1: the page image keeps its own white; the surface BEHIND it is the one shared dark
     * canvas (lane C's sw_cc_canvas) with the same 1dp #3DFFFFFF page edge as CompareView.
     * Lane K's bg_canvas_page_edge.xml is not in this base, so the edge is applied inline here.
     */
    private fun pageThumbBackground() = SwStyle.rounded(
        this,
        SwStyle.color(this, R.color.sw_cc_canvas),
        radiusDp = 8f,
        stroke = Color.parseColor("#3DFFFFFF"),
        strokeDp = 1f
    )

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
            background = pageThumbBackground()
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
            SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_hi)).apply {
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
            // Never replace the rounded drawable with a flat fill: that would drop both the
            // shared canvas token and its 1dp page edge. A locked source only tints the inner
            // image slot; the frame stays the same honest empty page surface.
            thumbFrame.background = pageThumbBackground()
            if (doc.isLocked) {
                thumb.setBackgroundColor(SwStyle.color(this, R.color.sw_thumb_locked))
            }
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
        val preview = SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_hi)).apply {
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
            // P1: a typed error carries a string resource; English prose is never
            // printed verbatim into the Hindi screen.
            preview.text = if (e is PageRanges.InvalidPageRange)
                getString(e.messageRes, *e.formatArgs.toTypedArray())
            else getString(R.string.tools_setup_bad_ranges)
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
        optionsHost.addView(note(getString(R.string.cdf1_setup_images_note)))
    }

    /**
     * J2 — the Compress setup is TWO screens, never one page with both aims. Switching only
     * rebuilds this tool's options; the level on one side and the numeric target + unit on the
     * other are Activity state, so each aim keeps its own choice across a switch or a rotation.
     */
    private fun buildCompressOptions() {
        levelRows.clear()
        levelSizes.clear()
        compressTargetChips.clear()
        errorView.isVisible = false

        // The aim switch is a two-way choice with selected semantics, not an icon-only tab.
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = COMPRESS_MODES_TAG
        }
        compressQualityMode = compressModeButton(
            getString(R.string.ux130_l3_compress_choose_quality),
            MODE_QUALITY, COMPRESS_MODE_QUALITY_TAG
        )
        compressSizeMode = compressModeButton(
            getString(CompressionPresentation.aimForSize), MODE_SIZE, COMPRESS_MODE_SIZE_TAG
        )
        modes.addView(compressQualityMode, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f) })
        modes.addView(compressSizeMode, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        optionsHost.addView(modes, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 12f) })

        // Both trees exist (as the storyboard's hidden panels do), but exactly one is shown.
        // Keeping both rows lets estimates keep arriving in place while the other aim is open,
        // and keeps a typed target when the person switches back.
        buildCompressQualityRows()
        buildCompressSizeRows()
        applyCompressMode()
        compressRetry.isVisible = false
        startEstimate()
        // A re-render (selection change, onResume) rebuilds the size labels: show the
        // estimates already measured instead of a fresh empty row.
        updateEstimateLines()
    }

    /** One aim on screen: the other tree is GONE, not merely greyed out. */
    private fun applyCompressMode() {
        if (!::compressQualityBlock.isInitialized || !::compressSizeBlock.isInitialized) return
        compressQualityBlock.isVisible = compressMode == MODE_QUALITY
        compressSizeBlock.isVisible = compressMode == MODE_SIZE
        paintChip(compressQualityMode, compressMode == MODE_QUALITY)
        paintChip(compressSizeMode, compressMode == MODE_SIZE)
        errorView.isVisible = false
        compressRetry.isVisible = false
    }

    /** A labelled 48dp aim button. The tag is the stable handle for tests and the atlas. */
    private fun compressModeButton(label: String, mode: String, tag: String): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim), bold = true).apply {
            text = label
            this.tag = tag
            contentDescription = label
            isClickable = true
            isFocusable = true
            maxLines = Int.MAX_VALUE
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
            setPadding(
                SwStyle.dp(this@ToolSetupActivity, 8f), SwStyle.dp(this@ToolSetupActivity, 8f),
                SwStyle.dp(this@ToolSetupActivity, 8f), SwStyle.dp(this@ToolSetupActivity, 8f)
            )
            setOnClickListener {
                if (compressMode == mode) return@setOnClickListener
                compressMode = mode
                // The other aim's choice is state on this Activity: switching never clears it.
                applyCompressMode()
            }
        }

    /** The four labelled levels with their own in-place estimate, Balanced first among equals. */
    private fun buildCompressQualityRows() {
        // L3-03: the same ordered labels as the Compress sheet, from one shared table.
        val levels = listOf(
            CompressLevel(CompressionPresentation.levelLabels[0], 0, R.id.toolSetupLevelBest),
            CompressLevel(CompressionPresentation.levelLabels[1], 1, R.id.toolSetupLevelBalanced),
            CompressLevel(CompressionPresentation.levelLabels[2], 2, R.id.toolSetupLevelSmaller),
            CompressLevel(CompressionPresentation.levelLabels[3], 3, R.id.toolSetupLevelSmallest)
        )
        // L3-03: at larger fonts the measured size drops below its label instead of being
        // squeezed beside it. Engine parameters are untouched (see CompressionPresentation).
        val stackEstimate = resources.configuration.fontScale > 1.15f
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.toolSetupLevels
            tag = COMPRESS_QUALITY_BLOCK_TAG
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
                // L3-03: no definite truncation — a long Hindi label wraps to a second line.
                maxLines = Int.MAX_VALUE
                gravity = Gravity.CENTER_VERTICAL
            }
            val size = SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_hi)).apply {
                id = R.id.toolSetupLevelSize
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                // An honest placeholder: until the engine measures, no number is shown.
                text = getString(R.string.ux130_l3_compress_estimate_pending)
            }
            val row = LinearLayout(this).apply {
                id = level.id
                orientation = if (stackEstimate) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
                val pad = SwStyle.dp(this@ToolSetupActivity, 12f)
                val padV = SwStyle.dp(this@ToolSetupActivity, if (stackEstimate) 8f else 0f)
                setPadding(pad, padV, pad, padV)
                background = chipBackground(false)
                contentDescription = getString(level.titleRes)
                setOnClickListener {
                    // J2: choosing a level changes ONLY the quality aim. The size aim's
                    // typed value and unit survive so switching back is not a reset.
                    this@ToolSetupActivity.level = level.index
                    paintLevels()
                }
            }
            row.addView(check, LinearLayout.LayoutParams(
                SwStyle.dp(this@ToolSetupActivity, 20f), SwStyle.dp(this@ToolSetupActivity, 20f)
            ).apply {
                marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f)
                if (stackEstimate) bottomMargin = SwStyle.dp(this@ToolSetupActivity, 4f)
            })
            // The recommended badge sits BELOW its label: beside it would steal the width
            // "Balanced" needs and made the label wrap at 320 dp in the fit test.
            val labelWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labelWrap.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            if (level.index == 1) {
                labelWrap.addView(
                    SwStyle.text(
                        this, 12f, SwStyle.color(this, R.color.cyan_primary), bold = true
                    ).apply {
                        tag = COMPRESS_RECOMMENDED_TAG
                        text = getString(R.string.ux130_l3_compress_recommended)
                        maxLines = Int.MAX_VALUE
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            row.addView(
                labelWrap,
                if (stackEstimate) {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                } else {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = SwStyle.dp(this@ToolSetupActivity, 8f)
                    }
                }
            )
            row.addView(size, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (stackEstimate) topMargin = SwStyle.dp(this@ToolSetupActivity, 4f) })
            levelRows += row
            levelSizes += size
            stack.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 8f) })
        }
        compressQualityBlock = stack
        optionsHost.addView(stack)
        paintLevels()
    }

    /** Four decimal targets plus a Custom numeric field and its KB/MB choice. */
    private fun buildCompressSizeRows() {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = COMPRESS_SIZE_BLOCK_TAG
        }
        block.addView(
            SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                text = getString(R.string.compress_target_help)
                maxLines = Int.MAX_VALUE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 6f) }
        )
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        CompressionPresentation.j2TargetBytes.forEachIndexed { index, bytes ->
            val label = getString(CompressionPresentation.j2TargetLabel(index))
            // The four ids stay the judged quick-chip ids; their labels and values are the
            // J2 decimal targets. One chip group, one selected value.
            val id = when (index) {
                0 -> R.id.v2t_quick_20
                1 -> R.id.v2t_quick_50
                2 -> R.id.v2t_quick_100
                else -> R.id.v2t_quick_200
            }
            val chip = chip(label, id).apply {
                tag = COMPRESS_TARGET_TAG
                contentDescription = label
                setOnClickListener { selectJ2Target(index, bytes) }
            }
            compressTargetChips += chip
            chips.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, SwStyle.dp(this@ToolSetupActivity, 48f)
            ).apply { marginEnd = SwStyle.dp(this@ToolSetupActivity, 6f) })
        }
        block.addView(chips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        paintCompressTargets()

        block.addView(
            SwStyle.text(this, 16f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.ux130_l3_compress_custom)
                maxLines = Int.MAX_VALUE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 12f) }
        )
        // The numeric field keeps the FULL row width so its hint fits at 320/360 dp;
        // the unit choice is a separate labelled row directly beneath it.
        val field = field(
            R.string.tools_pick_custom_size, R.id.toolSetupTarget, targetText,
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        ) { text ->
            targetText = text
            if (!compressSuppressFieldChange) {
                compressTargetIndex = null
                paintCompressTargets()
                errorView.isVisible = false
                compressRetry.isVisible = false
            }
        }
        block.addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val unitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val unitKb = compressModeButton(
            getString(R.string.ux130_l3_compress_target_200kb).substringAfter(' '),
            UNIT_KB, COMPRESS_UNIT_KB_TAG
        )
        val unitMb = compressModeButton(
            getString(R.string.ux130_l3_compress_target_1mb).substringAfter(' '),
            UNIT_MB, COMPRESS_UNIT_MB_TAG
        )
        listOf(unitKb, unitMb).forEach { unit ->
            unit.minimumWidth = SwStyle.dp(this@ToolSetupActivity, 56f)
            unit.minimumHeight = SwStyle.dp(this@ToolSetupActivity, 48f)
        }
        unitKb.setOnClickListener {
            compressUnit = UNIT_KB
            compressTargetIndex = null
            paintCompressUnits(unitKb, unitMb)
            paintCompressTargets()
            errorView.isVisible = false
        }
        unitMb.setOnClickListener {
            compressUnit = UNIT_MB
            compressTargetIndex = null
            paintCompressUnits(unitKb, unitMb)
            paintCompressTargets()
            errorView.isVisible = false
        }
        unitRow.addView(unitKb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        unitRow.addView(unitMb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = SwStyle.dp(this@ToolSetupActivity, 4f) })
        paintCompressUnits(unitKb, unitMb)
        block.addView(unitRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 6f) })
        compressSizeBlock = block
        optionsHost.addView(block)
    }

    private fun selectJ2Target(index: Int, bytes: Long) {
        compressTargetIndex = index
        // Decimal KB/MB: the chip label and the field always agree.
        compressUnit = if (bytes >= 1_000_000L) UNIT_MB else UNIT_KB
        // The numeric field holds the number in the chosen unit ("200" KB, "1" MB), so the
        // decimal parser multiplies it once — never "200000 KB" (a 200 MB mistake).
        targetText = getString(CompressionPresentation.j2TargetLabel(index))
            .substringBefore(' ').trim()
        compressSuppressFieldChange = true
        optionsHost.findViewById<EditText>(R.id.toolSetupTarget)?.setText(targetText)
        compressSuppressFieldChange = false
        paintCompressTargets()
        optionsHost.findViewWithTag<TextView>(COMPRESS_UNIT_KB_TAG)?.let { kb ->
            paintCompressUnits(kb, optionsHost.findViewWithTag<TextView>(COMPRESS_UNIT_MB_TAG) ?: kb)
        }
        errorView.isVisible = false
        compressRetry.isVisible = false
    }

    private fun paintCompressTargets() {
        compressTargetChips.forEachIndexed { index, view -> paintChip(view, index == compressTargetIndex) }
    }

    private fun paintCompressUnits(kb: TextView, mb: TextView) {
        paintChip(kb, compressUnit == UNIT_KB)
        paintChip(mb, compressUnit == UNIT_MB)
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
                        // CP1: the same "about" estimate and measured PDF overhead as CompressFlow.
                        SizeUnits.estimateJpegPages(average, doc.pageCount, doc.pageFiles.size)
                    }
                }.getOrNull()
            }
            // A re-render replaces the row views, but updateEstimateLines() reads the saved
            // values and paints every current row, so a late result still lands in place.
            if (estimates != null && selected.firstOrNull() == doc.id) {
                compressEstimates = estimates
                updateEstimateLines()
            }
        }
    }

    /**
     * Each estimate sits on its own row. "about" (cp1_est_only) is deliberate: an estimate is
     * never a guaranteed result, and the placeholder says so until a number is actually measured.
     */
    private fun updateEstimateLines() {
        val estimates = compressEstimates
        levelSizes.forEachIndexed { index, view ->
            val measured = estimates?.getOrNull(index)
            view.text = measured?.let {
                getString(R.string.cp1_est_only, SizeUnits.format(it))
            } ?: getString(R.string.ux130_l3_compress_estimate_pending)
        }
    }

    /** The four levels of the document compress sheet: (JPEG quality, longest page side). */
    private fun compressLevels(): List<Pair<Int, Int?>> =
        listOf(80 to null, 65 to 2000, 50 to 1600, 38 to 1200)

    private fun buildNameOption() {
        optionsHost.addView(
            SwStyle.text(this, 16f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.co_save_as_new_copy)
                maxLines = Int.MAX_VALUE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(this@ToolSetupActivity, 6f) }
        )
        // The extension is read-only: the editable field holds the stem only.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            field(R.string.co_name_hint, R.id.toolSetupName, nameText) { nameText = it },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            SwStyle.text(this, 16f, SwStyle.color(this, R.color.text_hologram_hi)).apply {
                text = getString(R.string.co_name_extension)
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(SwStyle.dp(this@ToolSetupActivity, 6f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, SwStyle.dp(this, 52f)
            )
        )
        optionsHost.addView(row)
        optionsHost.addView(
            SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
                tag = NAME_ERROR_TAG
                text = getString(R.string.co_name_required)
                contentDescription = text
                isVisible = nameText.isBlank()
                setLineSpacing(0f, 1.2f)
                maxLines = Int.MAX_VALUE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 6f) }
        )
        optionsHost.addView(
            SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                tag = RECEIPT_TAG
                text = getString(R.string.co_new_copy_receipt)
                contentDescription = text
                setLineSpacing(0f, 1.25f)
                maxLines = Int.MAX_VALUE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolSetupActivity, 6f) }
        )
    }

    private fun buildMergeOptions() {
        optionsHost.addView(note(getString(R.string.cdf1_setup_merge_note)))
        buildNameOption()
    }

    private fun buildZipOptions() {
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
            if (hasText) getString(R.string.cdf1_setup_word_note)
            else getString(R.string.cdf1_setup_no_text)
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
        if (wordPictures) optionsHost.addView(note(getString(R.string.cdf1_setup_word_pictures_note)))
    }

    private fun buildPptxOption() {
        optionsHost.addView(note(getString(R.string.cdf1_setup_pptx_note)))
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
        optionsHost.addView(note(getString(R.string.cdf1_setup_excel_note)))
    }

    private fun buildCleanOption() {
        optionsHost.addView(note(getString(R.string.cdf1_setup_metadata_note)))
    }

    private fun note(text: String): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
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
        // Tapping a field with the IME open scrolls it clear of the pinned Continue button.
        setOnFocusChangeListener { v, focused -> if (focused) keepFieldAboveBar(v) }
        setText(initial)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString().orEmpty())
                this@ToolSetupActivity.refreshNameError()
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
     * The pinned bar is a sibling of the ScrollView, so a focused field near the bottom would sit
     * behind Continue once the keyboard resizes the viewport. Scroll by the field's own offset in
     * the content, leaving one bar plus a little air below it.
     */
    private fun keepFieldAboveBar(field: View) {
        field.post {
            val scroll = findViewById<ScrollView>(R.id.toolSetupScroll) ?: return@post
            var y = 0
            var parent = field.parent
            while (parent is View && parent !== scroll) {
                y += parent.top
                parent = parent.parent
            }
            y += field.bottom
            val visibleBottom = scroll.height - pinnedBarHeight - SwStyle.dp(this, 12f)
            scroll.scrollTo(0, maxOf(0, y - visibleBottom))
        }
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
        // A new source gets a new prefill, but only while the field still shows our own default;
        // a name the person typed is never silently replaced.
        if ((toolId == "duplicate" || toolId == "merge") && nameText == autoName) {
            nameText = prefillCopyName()
            autoName = nameText
        }
        render()
    }

    /** The stem of the selected source, sanitised and collision-safe. */
    private fun prefillCopyName(): String {
        val source = selected.firstOrNull()?.let { id -> docs.firstOrNull { it.id == id } }
        return if (source == null) "" else OutputNames.forNewCopy(source.name, docs.map { it.name })
    }

    /** Sanitises what the person typed before it becomes the new copy's name. */
    private fun typedCopyName(): String? =
        if (nameText.isBlank()) null
        else OutputNames.newCopyFromField(nameText, docs.map { it.name })

    /**
     * A locked source is not a dead end: L3's Unlock sheet takes the password, then the user
     * is told what removing the protection does before anything is changed.
     */
    private fun promptUnlock(doc: ScanDocument) {
        UnlockSheet.showWithGrant(
            activity = this,
            documentName = doc.name,
            verify = { candidate -> repo.checkPdfPassword(doc.id, candidate) },
            onUnlocked = { grant -> unlockAndSelect(doc, grant) }
        )
    }

    /**
     * SAFE-UNLOCK-CONSENT: a right password only proves the file opens. Removing its password
     * changes the copy in the library for good, so that is a separate, explicit yes. Cancel,
     * Back and an outside tap leave the document encrypted and this row unselected; only the
     * primary button reaches [removeProtectionAndSelect].
     */
    private fun unlockAndSelect(doc: ScanDocument, grant: ScanRepository.PdfPasswordGrant) {
        SwSheet.builder(this)
            .setTitle(R.string.tools_setup_unlock_consent_title)
            .setMessage(
                getString(R.string.tools_setup_unlock_consent_body, getString(tool.title))
            )
            .setPrimary(R.string.tools_setup_unlock_consent_primary) {
                removeProtectionAndSelect(doc, grant)
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    /** Runs only after the explicit yes: unlocks the library copy and picks it for the tool. */
    private fun removeProtectionAndSelect(doc: ScanDocument, grant: ScanRepository.PdfPasswordGrant) {
        progressOverlay.isVisible = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.unlockPdf(doc.id, grant) }
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
                fail(getString(R.string.tools_setup_failed, setupFailureReason(e)))
            }
        }
    }

    // ---- run --------------------------------------------------------------

    private fun run() {
        errorView.isVisible = false
        // F11 (H3-4): every tool here reads the document's SAVED pages and this screen shows no
        // draft bar, so a document with unsaved page edits gets the document screen's own
        // question first. The cheap check is the ids read with the listing, so an ordinary tap
        // still validates and answers at once; the dispatcher re-reads the draft before running.
        if (selected.none { it in draftIds }) {
            runTool()
            return
        }
        DocumentActionDispatcher.confirmSavedPages(
            activity = this,
            repo = repo,
            docIds = selected.toList(),
            onFinishEditing = { doc ->
                startActivity(DocumentViewerActivity.intent(this, doc.id))
                finish()
            }
        ) { runTool() }
    }

    private fun runTool() {
        val docId = selected.firstOrNull()
        when (tool.id) {
            "split" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                val spans = try {
                    PageRanges.parse(rangesText, doc.pageCount)
                } catch (e: IllegalArgumentException) {
                    return fail(
                        if (e is PageRanges.InvalidPageRange)
                            getString(e.messageRes, *e.formatArgs.toTypedArray())
                        else getString(R.string.tools_setup_bad_ranges)
                    )
                }
                execute({
                    val ids = repo.splitByRange(doc.id, spans).map { it.id }
                    ids to captureDocs(ids)
                }) { (ids, identity) -> openDocs(ids, outputIdentity = identity) }
            }
            "extract" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                val spans = try {
                    PageRanges.parse(rangesText, doc.pageCount)
                } catch (e: IllegalArgumentException) {
                    return fail(
                        if (e is PageRanges.InvalidPageRange)
                            getString(e.messageRes, *e.formatArgs.toTypedArray())
                        else getString(R.string.tools_setup_bad_ranges)
                    )
                }
                val indices = spans.flatMap { it.indices().toList() }.distinct().sorted()
                execute({
                    val ids = listOf(repo.extractPages(doc.id, indices).id)
                    ids to captureDocs(ids)
                }) { (ids, identity) -> openDocs(ids, outputIdentity = identity) }
            }
            "duplicate" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val ids = listOf(repo.duplicateDocument(doc.id, typedCopyName()).id)
                    ids to captureDocs(ids)
                }) { (ids, identity) -> openDocs(ids, outputIdentity = identity) }
            }
            "merge" -> {
                val ids = selected.toList()
                if (ids.size < 2) return fail(getString(R.string.tools_setup_need_two))
                execute({
                    val out = listOf(repo.mergeDocuments(ids, typedCopyName()).id)
                    out to captureDocs(out)
                }) { (out, identity) -> openDocs(out, outputIdentity = identity) }
            }
            "compress" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                // J2: only the aim on screen is parsed. The other aim's saved choice is never
                // read, so a stale value cannot silently start a job the person did not ask for.
                val targetBytes = if (compressMode == MODE_SIZE) {
                    val raw = targetText.trim()
                    if (raw.isEmpty()) return fail(getString(R.string.compress_target_required))
                    val number = raw.toDoubleOrNull()
                    if (number == null || !number.isFinite() || number <= 0.0) {
                        // One clear error tied to the field: an invalid number blocks the start.
                        return fail(getString(R.string.ux130_l3_compress_invalid_custom))
                    }
                    // The same decimal-KB parser as the sheet keeps the 2 GiB safety ceiling.
                    when (val parsed = CompressTarget.parse("$raw $compressUnit")) {
                        is CompressTarget.Result.Bytes -> parsed.value
                        CompressTarget.Result.TooLarge ->
                            return fail(getString(R.string.compress_target_too_large))
                        else -> return fail(getString(R.string.ux130_l3_compress_invalid_custom))
                    }
                } else null
                target = targetBytes
                runCompressJ2(doc, targetBytes)
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
                    val result = ResultFiles(listOf(out), mimeFor(out))
                    result to captureFiles(result.files)
                }) { (result, identity) -> openFiles(result.files, result.mime, outputIdentity = identity) }
            }
            "long_image" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val files = repo.exportLongImage(doc.id, outputDir(), doc.name)
                    val result = ResultFiles(files, "image/jpeg")
                    result to captureFiles(result.files)
                }) { (result, identity) -> openFiles(result.files, result.mime, outputIdentity = identity) }
            }
            "to_word" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                var unreadPages: List<Int> = emptyList()
                execute({
                    val out = File(outputDir(), "${baseName(doc)}.docx")
                    val file = repo.exportWord(doc.id, out, includePictures = wordPictures) {
                        unreadPages = it
                    }
                    val result = ResultFiles(listOf(file), DOCX_MIME)
                    result to captureFiles(result.files)
                }) { (result, identity) ->
                    // O1-1: an export with unread pages is never reported as complete.
                    val unread = unreadPages.size
                    val note = if (unread > 0) {
                        resources.getQuantityString(R.plurals.tools_unread_pages, unread, unread)
                    } else null
                    openFiles(result.files, result.mime, note = note, outputIdentity = identity)
                }
            }
            "to_powerpoint" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val out = File(outputDir(), "${baseName(doc)}.pptx")
                    repo.exportPowerPoint(doc.id, out)
                    val result = ResultFiles(listOf(out), PPTX_MIME)
                    result to captureFiles(result.files)
                }) { (result, identity) -> openFiles(result.files, result.mime, outputIdentity = identity) }
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
                    val run = ExcelRun(report, doc)
                    run to captureFiles(listOfNotNull(report.file))
                }) { (run, identity) ->
                    val file = run.report.file
                    if (file == null) {
                        showNoTables(run.doc, run.report.unreadPages.size, run.report.totalPages)
                    } else {
                        // O1-6: show the unread count as well as the table count; neither is hidden.
                        val notes = mutableListOf<String>()
                        if (excelTablesOnly) {
                            notes += getString(
                                R.string.tools_excel_tables_note,
                                run.report.tablePages, run.report.totalPages
                            )
                        }
                        val unread = run.report.unreadPages.size
                        if (unread > 0) {
                            notes += resources.getQuantityString(R.plurals.tools_unread_pages, unread, unread)
                        }
                        openFiles(
                            listOf(file), XLSX_MIME,
                            note = notes.joinToString("\n").takeIf { it.isNotBlank() },
                            outputIdentity = identity
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
                    val result = ResultFiles(listOf(out), "application/zip")
                    result to captureFiles(result.files)
                }) { (result, identity) -> openFiles(result.files, result.mime, outputIdentity = identity) }
            }
            "remove_metadata" -> {
                val doc = docId?.let { id -> docs.firstOrNull { it.id == id } }
                    ?: return fail(getString(R.string.tools_setup_pick_one))
                execute({
                    val out = listOf(repo.removeMetadata(doc.id).id)
                    out to captureDocs(out)
                }) { (out, identity) -> openDocs(out, outputIdentity = identity) }
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
        // H2-7: only a known extension is removed ("12.05.2025 Rent receipt" stays whole).
        com.scanwhirl.app.util.ExportNames.baseName(doc.name)

    private fun outputDir(): File =
        File(cacheDir, "documents/tools/${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}")
            .apply { mkdirs() }

    private fun mimeFor(file: File): String = when {
        file.name.endsWith(".zip") -> "application/zip"
        file.name.endsWith(".png") -> "image/png"
        else -> "image/jpeg"
    }

    /**
     * J2 — one Compress run. The progress shows REAL page numbers from the target engine's
     * onPage callback; the quality engine has no such callback, so it reports the honest total
     * instead of an invented counter. Cancel stops the job while its output is still in staging.
     */
    private fun runCompressJ2(doc: ScanDocument, targetBytes: Long?) {
        compressJob?.cancel()
        errorView.isVisible = false
        compressRetry.isVisible = false
        runButton.isVisible = true
        runButton.isEnabled = false
        progressOverlay.isVisible = true
        compressProgressCancel.isVisible = true
        val total = doc.pageCount.coerceAtLeast(1)
        compressProgressText.text = getString(R.string.ux130_l3_compress_progress_pages, total)
        val before = doc.pdfFile.takeIf { it.isFile }?.length()
        val job = lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    compressBeforeRunForTest?.invoke()
                    currentCoroutineContext().ensureActive()
                    val out = if (targetBytes != null) {
                        repo.compressScanToTargetForResult(doc.id, targetBytes) { done, pages ->
                            this@ToolSetupActivity.runOnUiThread {
                                compressProgressText.text = getString(
                                    R.string.ux130_l3_compress_progress_page, done, pages
                                )
                            }
                        }
                    } else {
                        val (quality, maxDim) = compressLevel()
                        repo.compressScanForResult(doc.id, quality, maxDim)
                    }
                    val ids = listOfNotNull(out?.id)
                    // L3-02 P1: hashing happens on IO, never on the Main thread.
                    val identity = ToolResultActivity.captureOutputIdentityBlocking(
                        this@ToolSetupActivity, ids, emptyList()
                    )
                    Triple(ids, out?.pdfFile?.takeIf { it.isFile }?.length() ?: 0L, identity)
                }
                progressOverlay.isVisible = false
                runButton.isEnabled = true
                val (ids, after, identity) = outcome
                if (ids.isEmpty()) {
                    // No gain: the engine discarded the grown output and persisted the
                    // NOT_SMALLER receipt. There is no document to name in a result.
                    startActivity(ToolResultActivity.intentLastResult(this@ToolSetupActivity))
                    finish()
                    return@launch
                }
                // A target is met only when the measured file is at or below the cap. A miss
                // names the closest achieved size and is never titled a success.
                val missed = targetBytes != null && after > targetBytes
                val note = if (missed) getString(
                    R.string.cp1_compress_floor_miss,
                    StatusLine.formatBytes(targetBytes!!), StatusLine.formatBytes(after)
                ) else null
                openDocs(ids, targetBytes = targetBytes, receiptSourceId = doc.id,
                    outputIdentity = identity, beforeBytes = before,
                    outcomeClass = if (missed) J2_OUTCOME_MISSED else null,
                    compressTargetMissed = missed, note = note)
            } catch (e: CancellationException) {
                // Cancel: staging stops before publish, so no result and no new file exist.
                progressOverlay.isVisible = false
                compressProgressCancel.isVisible = false
                runButton.isVisible = true
                runButton.isEnabled = true
                Toast.makeText(
                    this@ToolSetupActivity, getString(R.string.cp1_compress_cancelled), Toast.LENGTH_SHORT
                ).show()
            } catch (e: Throwable) {
                progressOverlay.isVisible = false
                compressProgressCancel.isVisible = false
                runButton.isEnabled = true
                compressFail(getString(R.string.compress_failed, setupFailureReason(e)))
            }
        }
        compressJob = job
        job.invokeOnCompletion { compressJob = null }
    }

    private fun cancelCompress() {
        compressJob?.cancel()
        compressJob = null
    }

    /** Failure keeps every input on screen and replaces Continue with one Retry action. */
    private fun compressFail(message: String) {
        errorView.text = message
        errorView.isVisible = true
        runButton.isVisible = false
        compressRetry.isVisible = true
    }

    /**
     * Opens the result for new library documents. [targetBytes] is Compress's wanted size, so the
     * result can say honestly when that size was not reached (T05).
     */
    private fun openDocs(
        ids: List<String>,
        targetBytes: Long? = null,
        receiptSourceId: String? = null,
        outputIdentity: String? = null,
        beforeBytes: Long? = null,
        outcomeClass: String? = null,
        compressTargetMissed: Boolean = false,
        note: String? = null
    ) {
        if (ids.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        startActivity(
            ToolResultActivity.intent(
                this, com.scanwhirl.app.ui.result.ResultSpec(tool.id, docIds = ids, targetBytes = targetBytes,
                receiptSourceId = receiptSourceId, outputIdentity = outputIdentity,
                adjustIntent = Intent(intent), beforeBytes = beforeBytes,
                note = note, outcomeClass = outcomeClass,
                compressTargetMissed = compressTargetMissed)
            )
        )
    }

    private fun openFiles(
        files: List<File>,
        mime: String,
        note: String? = null,
        outputIdentity: String? = null
    ) {
        if (files.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        startActivity(ToolResultActivity.intent(this, com.scanwhirl.app.ui.result.ResultSpec(tool.id, files = files,
            mime = mime, note = note, outputIdentity = outputIdentity, adjustIntent = Intent(intent))))
    }

    /** Blocking helpers: called only inside execute()'s Dispatchers.IO block. */
    private fun captureDocs(ids: List<String>): String? =
        ToolResultActivity.captureOutputIdentityBlocking(this, ids, emptyList())

    private fun captureFiles(files: List<File>): String? =
        ToolResultActivity.captureOutputIdentityBlocking(this, emptyList(), files)

    /**
     * To Excel, Tables only: nothing was written. O1-4: when pages were unreadable, say how many
     * instead of claiming no page has a table; we never looked at their content.
     */
    private fun showNoTables(doc: ScanDocument, unread: Int, total: Int) {
        val title: Int
        val message: String
        when {
            unread > 0 && unread == total -> {
                title = R.string.tools_excel_unread_all_title
                message = getString(R.string.tools_excel_unread_all_msg, unread, total)
            }
            unread > 0 -> {
                title = R.string.tools_excel_no_tables_title
                message = getString(R.string.tools_excel_no_tables_unread_msg, unread, total)
            }
            else -> {
                title = R.string.tools_excel_no_tables_title
                message = getString(R.string.tools_excel_no_tables_msg)
            }
        }
        SwSheet.builder(this)
            .setTitle(title)
            .setMessage(message)
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
            val files = listOf(out)
            files to captureFiles(files)
        }) { (files, identity) -> openFiles(files, XLSX_MIME, outputIdentity = identity) }
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

    /**
     * P1: known failures are named in the user's language. A JVM message is
     * diagnostics only and is never shown, even as an English fallback.
     */
    private fun setupFailureReason(e: Throwable): String = when {
        ImageTools.isTooLargePhoto(e) -> getString(R.string.p1_setup_failed_too_large)
        e.message?.contains("no pages", true) == true ||
            e.message?.contains("empty document", true) == true ->
            getString(R.string.p1_setup_failed_no_pages)
        e.message?.contains("space", true) == true ||
            e.message?.contains("storage", true) == true ->
            getString(R.string.p1_setup_failed_no_space)
        e is IllegalArgumentException -> getString(R.string.p1_setup_failed_invalid_input)
        e is IllegalStateException -> getString(R.string.p1_setup_failed_unavailable)
        else -> getString(R.string.p1_setup_failed_generic)
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
                fail(getString(R.string.tools_setup_failed, setupFailureReason(e)))
            }
        }
    }

    private data class ResultFiles(val files: List<File>, val mime: String)

    private data class ExcelRun(val report: ScanRepository.ExcelExport, val doc: ScanDocument)

    companion object {
        private const val EXTRA_TOOL_ID = "tool_id"
        private const val EXTRA_SELECTED_DOC_ID = "v2t_selected_doc_id"
        /** Room kept at the end of the list for the pinned action bar (before its height is known). */
        private const val BAR_CLEARANCE_DP = 96f
        /** Every option chip label carries this tag, so a UI test can walk the chips on any tool. */
        const val CHIP_TAG = "swChip"
        /** The new-copy receipt under the name field (no new resource id needed). */
        const val RECEIPT_TAG = "co_new_copy_receipt"
        /** The scrollable clearance child below the last option. */
        const val BOTTOM_SPACER_TAG = "co_bottom_spacer"
        /** Inline required-name error under the editable field. */
        const val NAME_ERROR_TAG = "co_name_required"
        /** J2: tags let tests and the atlas find the two-mode setup without new shared ids. */
        const val COMPRESS_MODES_TAG = "j2:compress-modes"
        const val COMPRESS_QUALITY_BLOCK_TAG = "j2:compress-quality-block"
        const val COMPRESS_SIZE_BLOCK_TAG = "j2:compress-size-block"
        const val COMPRESS_MODE_QUALITY_TAG = "j2:compress-mode-quality"
        const val COMPRESS_MODE_SIZE_TAG = "j2:compress-mode-size"
        const val COMPRESS_RECOMMENDED_TAG = "j2:compress-recommended"
        const val COMPRESS_TARGET_TAG = "j2:compress-target"
        const val COMPRESS_UNIT_KB_TAG = "j2:compress-unit-kb"
        const val COMPRESS_UNIT_MB_TAG = "j2:compress-unit-mb"
        const val COMPRESS_PROGRESS_TAG = "j2:compress-progress"
        const val COMPRESS_CANCEL_TAG = "j2:compress-cancel"
        const val COMPRESS_RETRY_TAG = "j2:compress-retry"
        const val MODE_QUALITY = "quality"
        const val MODE_SIZE = "size"
        const val UNIT_KB = "KB"
        const val UNIT_MB = "MB"
        /** The shared result's persisted miss class; a miss is never titled a success. */
        const val J2_OUTCOME_MISSED = "TARGET_MISSED"

        /** Test seam: hold the run just before the engine, so Cancel can be proven deterministic. */
        @VisibleForTesting
        internal var compressBeforeRunForTest: (() -> Unit)? = null
        /** Material's disabled alpha: a disabled button at full alpha still looks tappable. */
        private const val DISABLED_ALPHA = 0.38f

        // H5-2: recreation state, so the screen keeps its document and typed values.
        private const val KEY_SELECTED = "com.scanwhirl.app.ui.tools.SELECTED"
        private const val KEY_FORMAT = "com.scanwhirl.app.ui.tools.FORMAT"
        private const val KEY_QUALITY = "com.scanwhirl.app.ui.tools.QUALITY"
        private const val KEY_LEVEL = "com.scanwhirl.app.ui.tools.LEVEL"
        private const val KEY_RANGES = "com.scanwhirl.app.ui.tools.RANGES"
        private const val KEY_NAME = "com.scanwhirl.app.ui.tools.NAME"
        private const val KEY_TARGET = "com.scanwhirl.app.ui.tools.TARGET"
        private const val KEY_WORD_PICTURES = "com.scanwhirl.app.ui.tools.WORD_PICTURES"
        private const val KEY_EXCEL_TABLES = "com.scanwhirl.app.ui.tools.EXCEL_TABLES"
        private const val KEY_COMPRESS_MODE = "com.scanwhirl.app.ui.tools.J2_COMPRESS_MODE"
        private const val KEY_COMPRESS_UNIT = "com.scanwhirl.app.ui.tools.J2_COMPRESS_UNIT"
        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        private const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        fun intent(context: Context, toolId: String): Intent =
            Intent(context, ToolSetupActivity::class.java).putExtra(EXTRA_TOOL_ID, toolId)

        /** V2T T2: open the setup for a specific verified output document. */
        fun intent(context: Context, toolId: String, selectedDocId: String): Intent =
            intent(context, toolId).putExtra(EXTRA_SELECTED_DOC_ID, selectedDocId)

        /** The tool id this intent opens (tests and the shell read it). */
        fun toolIdOf(intent: Intent): String? = intent.getStringExtra(EXTRA_TOOL_ID)
    }
}
