package com.scanwhirl.app.ui.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.V2tResultReceiptStore
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.CompareModel
import com.scanwhirl.app.ui.kit.CompareMode
import com.scanwhirl.app.ui.kit.ComparePane
import com.scanwhirl.app.ui.kit.CompareView
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OfficeFileCounts
import com.scanwhirl.app.util.PptxWriter
import com.scanwhirl.app.util.SavedSnackbar
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.ZipExporter
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * v2 UI: 03_tools_hub — the result of every L4 tool.
 *
 * One screen for a new document copy and for an exported file: Open, "Also save in Documents"
 * (one tap into Documents/ScanWhirl), Share, Save as… (the system Save-as picker), Show in library
 * and Undo. Undo removes only the copy this run created — including the Documents copy it saved
 * (B2-H1) — and the source document is never touched.
 * Compress also says so when the produced size is still above the size the person asked for.
 * Built with the kit on the ink background.
 *
 * F1/W3: the ID card result is already saved the moment the layout is chosen, so that flow shows
 * Keep (done) and Discard instead of the save and undo actions, and Back asks "Discard this ID
 * card?" first.
 */
class ToolResultActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var rootView: View
    private lateinit var toolName: String
    private var docIds: MutableList<String> = mutableListOf()
    private var files: MutableList<File> = mutableListOf()
    private var mime = "application/pdf"
    private var undone = false
    private var undoChecking = false
    private var chainChecking = false
    private lateinit var summary: TextView
    private lateinit var nameView: TextView
    private lateinit var errorView: TextView
    private lateinit var previewHost: FrameLayout
    private lateinit var preview: ImageView
    /** Lane C: the one Before | After surface on this result, GONE until bindCompare succeeds. */
    private var compareView: CompareView? = null
    private lateinit var saveAs: ActivityResultLauncher<String>
    private var toolId = ""
    private var note: String? = null

    /** Lane RS: the persisted outcome class, so a reopened Last result tells the truth. */
    private enum class OutcomeClass { SMALLER, NOT_SMALLER, TARGET_MISSED }
    private var outcomeClass: OutcomeClass? = null
    private var alreadyCompact = false
    private var noStrongerPreset = false
    /** Lane RS: our own fresh output, captured before it is removed from the library. */
    private var grownOutputIds: List<String> = emptyList()
    private var grownOutputFiles: List<File> = emptyList()
    private lateinit var chipView: TextView
    private lateinit var savedLine: TextView
    private lateinit var bottomBar: LinearLayout
    private var alsoSaveButton: TextView? = null
    private var saveAnotherCopy: View? = null
    private var moreLocation: TextView? = null
    private var moreNote: TextView? = null

    /**
     * H2-4: on Android 7-9 a Documents copy needs the runtime storage grant. The save this screen
     * asked for waits here while Android shows its prompt, so the answer continues the same tap.
     */
    private var pendingSave: (() -> Unit)? = null
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingSave
            pendingSave = null
            if (granted) {
                clearFail()
                action?.invoke()
            } else {
                fail(getString(R.string.f7save_perm_denied_documents))
            }
        }

    /** T05: the size Compress was asked for, so the result can say it was not reached. */
    private var targetBytes: Long? = null

    /**
     * B2-M2: the size of the source document, when Compress started from a document's More row.
     * It keeps the "Before … → After …" line the old export screen showed for the same run.
     */
    private var beforeBytes: Long? = null

    /**
     * L3-02 P1: identity of the output, captured by [intent] at creation time.
     * Undo only removes a document that still has these exact bytes, so a stale
     * id that was later reused by another document is never deleted.
     */
    private var outputIdentity: String? = null

    /** F1: the ID card flow's own result actions (Keep / Discard). */
    private val idCardFlow: Boolean get() = toolId == "id_card"

    /** UI-B3: Compress leads with the measured size and an honest target status. */
    private val isCompressResult: Boolean get() = toolId == "compress"

    /**
     * W3: the result's own receipt lines: which copies exist and where. They are appended to the
     * summary and survive the save that created them, instead of living only in a 6 s snackbar.
     */
    private val locationLines = mutableListOf<String>()
    private var summaryBaseLine: String? = null
    private var measuredSummaryBytes: Long? = null

    /** W3: the name written through the system Save-as picker, after its bytes were verified. */
    private var chosenLocationName: String? = null

    /** W3: a Documents copy this run found already there (nothing was created, nothing to undo). */
    private var documentsExistingName: String? = null

    /**
     * L3-02 P2: the "original kept" receipt is derived from the library's ACTUAL
     * state at render time (is the source document still there?), not from a
     * producer's boolean. [sourceKept] is the derived value; the legacy boolean
     * extra is only a fallback for callers that supply no source id.
     */
    private var receiptSourceId: String? = null
    private var sourceKept = false

    /** V2T T1: durable result receipt and its accessible status line. */
    private lateinit var receiptStore: V2tResultReceiptStore
    private var v2tStatus: TextView? = null

    /** V2T T1: the result owns its progress/status a11y when chaining into a flow. */
    private val v2tHost = object : HomeHost {
        override fun refreshList() = Unit
        override fun showProgress(titleRes: Int): SwProgress {
            val progress = SwProgress.show(this@ToolResultActivity, getString(titleRes))
            progress.view.findViewById<View>(R.id.swProgressLabel)?.apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            return progress
        }
        override fun toast(res: Int, duration: Int) =
            Toast.makeText(this@ToolResultActivity, res, duration).show()
        override fun toast(message: CharSequence, duration: Int) =
            Toast.makeText(this@ToolResultActivity, message, duration).show()
    }

    /**
     * B2-H1 (phone, build 2 = 1485874c): the Documents/ScanWhirl copy this result created.
     * The confirmation bar's Undo removes it only while its six seconds are up; the screen's own
     * Undo must undo the whole run, so it removes this copy too however long the person takes.
     */
    private var savedCopy: DownloadSaver.SavedFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        toolId = intent.getStringExtra(EXTRA_TOOL_ID).orEmpty()
        toolName = ToolRegistry.byId(toolId)?.let { getString(it.title) }.orEmpty()
        docIds = intent.getStringArrayListExtra(EXTRA_DOC_IDS)?.toMutableList() ?: mutableListOf()
        files = intent.getStringArrayListExtra(EXTRA_FILES)?.map { File(it) }?.toMutableList() ?: mutableListOf()
        mime = intent.getStringExtra(EXTRA_MIME) ?: defaultMime()
        note = intent.getStringExtra(EXTRA_NOTE)
        targetBytes = intent.getLongExtra(EXTRA_TARGET, 0L).takeIf { it > 0 }
        beforeBytes = intent.getLongExtra(EXTRA_BEFORE, 0L).takeIf { it > 0 }
        outputIdentity = intent.getStringExtra(EXTRA_OUTPUT_IDENTITY)
        receiptSourceId = intent.getStringExtra(EXTRA_RECEIPT_SOURCE_ID)
        // New producers reject inside staging; legacy receipts may still need guarded cleanup.
        outcomeClass = intent.getStringExtra(EXTRA_OUTCOME_CLASS)
            ?.let { runCatching { OutcomeClass.valueOf(it) }.getOrNull() }
        noStrongerPreset = intent.getBooleanExtra(EXTRA_NO_STRONGER_PRESET, false)
        if (isCompressResult) {
            val grew = intent.getBooleanExtra(EXTRA_COMPRESS_GREW, false)
            val measured = measuredOutputBytes()
            alreadyCompact = outcomeClass == OutcomeClass.NOT_SMALLER ||
                grew || (beforeBytes?.let { measured >= it } == true)
            if (alreadyCompact) outcomeClass = OutcomeClass.NOT_SMALLER
            else if (outcomeClass == null) {
                outcomeClass = if (
                    intent.getBooleanExtra(EXTRA_COMPRESS_MISSED, false) ||
                    targetBytes?.let { measured > it } == true
                ) {
                    OutcomeClass.TARGET_MISSED
                } else {
                    OutcomeClass.SMALLER
                }
            }
        }
        if (alreadyCompact) {
            grownOutputIds = docIds.toList()
            grownOutputFiles = files.toList()
            docIds.clear()
            files.clear()
            discardGrownOutput()
        }
        // L3-02 P2: derive the original-kept receipt from the library right now.
        val legacyClaim = intent.getBooleanExtra(EXTRA_SOURCE_KEPT, false)
        sourceKept = receiptSourceId?.let { repo.getDocument(it) != null } ?: legacyClaim

        // V2T T1: a durable receipt survives process death. A "Last result"
        // reopen carries no output extras; everything comes from the store.
        receiptStore = V2tResultReceiptStore(this)
        if (intent.getBooleanExtra(EXTRA_USE_LAST_RECEIPT, false)) {
            receiptStore.load()?.let { saved ->
                toolId = saved.toolId
                toolName = ToolRegistry.byId(toolId)?.let { t -> getString(t.title) }.orEmpty()
                docIds = saved.docIds.toMutableList()
                files = saved.files.map { File(it) }.toMutableList()
                mime = saved.mime ?: defaultMime()
                note = saved.note
                targetBytes = saved.targetBytes
                beforeBytes = saved.beforeBytes
                outputIdentity = saved.outputIdentity
                receiptSourceId = saved.receiptSourceId
                chosenLocationName = saved.chosenLocationName
                documentsExistingName = saved.documentsExistingName
                undone = saved.undone
                sourceKept = receiptSourceId?.let { repo.getDocument(it) != null } ?: saved.sourceKept
                if (toolId == "resize" && saved.outcomeClass == "NOT_SMALLER") {
                    outcomeClass = OutcomeClass.NOT_SMALLER
                    alreadyCompact = true
                    noStrongerPreset = true
                }
                // Lane RS: an older receipt has no class. Derive it from real bytes so a grown
                // output can never reopen titled "Compressed".
                if (toolId == "compress") {
                    outcomeClass = saved.outcomeClass?.let { runCatching { OutcomeClass.valueOf(it) }.getOrNull() }
                        ?: if (saved.beforeBytes?.let { measuredOutputBytes() >= it } == true) {
                            OutcomeClass.NOT_SMALLER
                        } else if (targetBytes?.let { measuredOutputBytes() > it } == true) {
                            OutcomeClass.TARGET_MISSED
                        } else {
                            OutcomeClass.SMALLER
                        }
                    alreadyCompact = outcomeClass == OutcomeClass.NOT_SMALLER
                    if (alreadyCompact && (docIds.isNotEmpty() || files.isNotEmpty())) {
                        grownOutputIds = docIds.toList()
                        grownOutputFiles = files.toList()
                        docIds.clear()
                        files.clear()
                        discardGrownOutput()
                    }
                }
            }
        }
        if (docIds.isNotEmpty() || files.isNotEmpty() || alreadyCompact) persistReceipt()
        // W3: a rotation must not lose what this run already learned. The saved Documents copy
        // stays undoable (it is still the same copy, still verified by its fingerprint), and an
        // already-undone result never offers the actions again as if nothing happened.
        savedInstanceState?.let { state ->
            undone = state.getBoolean(KEY_UNDONE)
            savedCopy = readSavedCopy(state)
            chosenLocationName = state.getString(KEY_CHOSEN_NAME)
            documentsExistingName = state.getString(KEY_EXISTING_NAME)
        }
        saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument(pickerMime())) { uri ->
            uri ?: return@registerForActivityResult
            saveTo(uri)
        }
        if (idCardFlow) {
            // F1/W3: leaving an already-saved card asks first; Discard deletes exactly this card.
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = confirmDiscardIdCard()
            })
        }
        rootView = buildRoot()
        setContentView(rootView)
        if (savedCopy != null || documentsExistingName != null) markDocumentsSaved()
        render()
        // W3: if this result was already undone before a rotation, do not offer its actions again.
        if (undone) showAlreadyUndone()
        // v2 UI: 74_motion — the one success moment call for every tool result.
        com.scanwhirl.app.ui.kit.SwMotion.success(this)
    }

    /** V2T T1: write the honest fact record after every verified state change. */
    private fun persistReceipt() {
        if (!::receiptStore.isInitialized) return
        receiptStore.save(V2tResultReceiptStore.Receipt(
            toolId = toolId,
            docIds = docIds.toList(),
            files = files.map { it.path },
            mime = mime,
            note = note,
            targetBytes = targetBytes,
            beforeBytes = beforeBytes,
            outputIdentity = outputIdentity,
            receiptSourceId = receiptSourceId,
            sourceKept = sourceKept,
            chosenLocationName = chosenLocationName,
            documentsExistingName = documentsExistingName,
            undone = undone,
            savedAt = System.currentTimeMillis(),
            outcomeClass = outcomeClass?.name
        ))
    }

    /** Stat-only size of this run's output; safe to read on Main, never a hash. */
    private fun measuredOutputBytes(): Long = when {
        docIds.isNotEmpty() -> docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
            .sumOf { doc -> if (doc.pdfFile.isFile) doc.pdfFile.length() else 0L }
        files.isNotEmpty() -> files.sumOf { file -> if (file.isFile) file.length() else 0L }
        else -> 0L
    }

    /**
     * Lane RS: a size tool that produced a larger file must not leave our own fresh output in the
     * library. We re-check the SAME recorded identity Undo uses before deleting only those outputs;
     * the source document (never in [grownOutputIds]) is untouched.
     */
    private fun discardGrownOutput() {
        val ids = grownOutputIds
        val outs = grownOutputFiles
        if (ids.isEmpty() && outs.isEmpty()) return
        val expected = outputIdentity ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                synchronized(ScanRepository.PDF_LOCK) {
                    com.scanwhirl.app.store.OcrStore.locked {
                        val current = captureOutputIdentityBlocking(this@ToolResultActivity, ids, outs)
                        if (current == null || current != expected) return@locked
                        ids.filter { it != receiptSourceId }.forEach { id ->
                            runCatching { repo.deleteDocument(id) }
                        }
                        // Legacy arbitrary file extras do not grant ownership for deletion.
                    }
                }
            }
        }
    }

    /**
     * W3: keep the run's receipt and its one undoable Documents copy across recreation. Only the
     * copy state is persisted — never a storage policy — so a recreated screen either keeps the
     * verified Undo or narrows its claim instead of silently forgetting it.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNDONE, undone)
        chosenLocationName?.let { outState.putString(KEY_CHOSEN_NAME, it) }
        documentsExistingName?.let { outState.putString(KEY_EXISTING_NAME, it) }
        savedCopy?.let { copy ->
            outState.putString(KEY_COPY_URI, copy.uri.toString())
            outState.putString(KEY_COPY_NAME, copy.name)
            outState.putString(KEY_COPY_REQUESTED, copy.requestedName)
            outState.putString(KEY_COPY_OUTCOME, copy.outcome.name)
            copy.fingerprint?.let { outState.putString(KEY_COPY_FINGERPRINT, it.encode()) }
        }
    }

    private fun readSavedCopy(state: Bundle): DownloadSaver.SavedFile? {
        val raw = state.getString(KEY_COPY_URI) ?: return null
        val name = state.getString(KEY_COPY_NAME) ?: return null
        val outcome = state.getString(KEY_COPY_OUTCOME)
            ?.let { runCatching { DownloadSaver.Outcome.valueOf(it) }.getOrNull() }
            ?: return null
        return DownloadSaver.SavedFile(
            uri = Uri.parse(raw),
            name = name,
            outcome = outcome,
            requestedName = state.getString(KEY_COPY_REQUESTED) ?: name,
            fingerprint = state.getString(KEY_COPY_FINGERPRINT)
                ?.let { DownloadSaver.Fingerprint.decode(it) }
        )
    }

    private fun buildRoot(): View {
        val hi = SwStyle.color(this, R.color.text_hologram_hi)
        val mid = SwStyle.color(this, R.color.text_hologram_mid)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@ToolResultActivity, 18f), SwStyle.dp(this@ToolResultActivity, 10f),
                SwStyle.dp(this@ToolResultActivity, 18f), SwStyle.dp(this@ToolResultActivity, 12f)
            )
            applySystemBarsPadding()
        }

        // 1. Top bar: the tool name, never a sentence (except the honest outcome override).
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(SwStyle.iconButton(
            this, R.drawable.ic_sym_arrow_back, hi
        ).apply {
            id = R.id.rsBack
            contentDescription = getString(R.string.tools_setup_back)
            layoutParams = LinearLayout.LayoutParams(
                SwStyle.dp(this@ToolResultActivity, 48f), SwStyle.dp(this@ToolResultActivity, 48f)
            )
            setOnClickListener { handleBack() }
        })
        bar.addView(
            SwStyle.text(this, 22f, SwStyle.color(this, R.color.sw_text_primary), bold = true).apply {
                id = R.id.rsTitle
                text = when {
                    alreadyCompact -> getString(R.string.rs_not_smaller_title)
                    outcomeClass == OutcomeClass.TARGET_MISSED ->
                        getString(R.string.cp1_compress_header_missed)
                    else -> toolName
                }
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@ToolResultActivity, 8f)
            }
        )
        root.addView(bar)

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 2. One hero row + one chip + one receipt row. No other sentences above the visual.
        val heroBlock = LinearLayout(this).apply {
            id = R.id.rsHeroBlock
            orientation = LinearLayout.VERTICAL
        }
        summary = SwStyle.text(this, 28f, SwStyle.color(this, R.color.sw_text_primary), bold = true).apply {
            id = R.id.toolResultSummary
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        heroBlock.addView(summary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        chipView = SwStyle.text(this, 14f, SwStyle.color(this, R.color.rv_accent), bold = true).apply {
            id = R.id.rsChip
            visibility = View.GONE
            gravity = Gravity.CENTER
            setPadding(
                SwStyle.dp(this@ToolResultActivity, 10f), SwStyle.dp(this@ToolResultActivity, 6f),
                SwStyle.dp(this@ToolResultActivity, 10f), SwStyle.dp(this@ToolResultActivity, 6f)
            )
            background = SwStyle.rounded(
                this@ToolResultActivity, SwStyle.color(this@ToolResultActivity, R.color.glass_surface),
                radiusDp = 12f, stroke = SwStyle.color(this@ToolResultActivity, R.color.sw_cyan_hair)
            )
        }
        heroBlock.addView(chipView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 6f) })
        column.addView(heroBlock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 16f) })

        savedLine = SwStyle.text(this, 14f, SwStyle.color(this, R.color.sw_text_secondary)).apply {
            id = R.id.toolResultName
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            movementMethod = LinkMovementMethod.getInstance()
            minimumHeight = SwStyle.dp(this@ToolResultActivity, 48f)
            gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(savedLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })

        // V2T T1: an a11y status node, deliberately not a visible line.
        v2tStatus = SwStyle.text(this, 13f, SwStyle.color(this, R.color.rv_accent)).apply {
            id = R.id.v2t_status
            visibility = View.GONE
            maxLines = Int.MAX_VALUE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getString(R.string.v2t_status_label)
        }
        column.addView(v2tStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 3. The one visual surface: stacked zoom crops, one After page, or nothing at all.
        val visualHost = FrameLayout(this).apply { id = R.id.rsVisualHost }
        previewHost = FrameLayout(this).apply {
            id = R.id.toolResultPreviewHost
            visibility = if (idCardFlow) View.VISIBLE else View.GONE
            if (idCardFlow) {
                val pad = SwStyle.dp(this@ToolResultActivity, ID_PREVIEW_PADDING_DP)
                setPadding(pad, pad, pad, pad)
            }
            background = SwStyle.rounded(
                this@ToolResultActivity, SwStyle.color(this@ToolResultActivity, R.color.void_abyss),
                radiusDp = 16f, stroke = ID_PREVIEW_LINE
            )
            clipToOutline = true
        }
        preview = ImageView(this).apply {
            id = R.id.toolResultPreview
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = getString(R.string.tools_result_preview)
        }
        previewHost.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        visualHost.addView(previewHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (idCardFlow) SwStyle.dp(this@ToolResultActivity, 200f)
            else SwStyle.dp(this@ToolResultActivity, 1f)
        ))
        compareView = CompareView(this).apply {
            id = R.id.ccCompare
            visibility = View.GONE
        }
        visualHost.addView(compareView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(visualHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 12f) })

        errorView = SwStyle.text(this, 14f, SwStyle.color(this, R.color.laser_danger), bold = true).apply {
            id = R.id.toolResultError
            isVisible = false
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 6f) })

        if (alreadyCompact) {
            bottomBar = LinearLayout(this).apply {
                id = R.id.rsBottomBar
                orientation = LinearLayout.HORIZONTAL
            }
            if (!noStrongerPreset && !receiptSourceId.isNullOrBlank()) {
                bottomBar.addView(
                    button(getString(R.string.rs_try_strong), R.id.rsTryStrong, primary = true) { tryStrong() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = SwStyle.dp(this@ToolResultActivity, 8f)
                    }
                )
            }
            bottomBar.addView(
                button(getString(R.string.tools_result_done), R.id.rsDone,
                    primary = noStrongerPreset || receiptSourceId.isNullOrBlank()) { finish() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            root.addView(bottomBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) })
            return root
        }

        if (idCardFlow) {
            bottomBar = LinearLayout(this).apply { id = R.id.rsBottomBar; orientation = LinearLayout.HORIZONTAL }
            bottomBar.addView(
                button(getString(R.string.fixflow_keep), tag = TAG_KEEP, primary = true) { finish() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = SwStyle.dp(this@ToolResultActivity, 8f)
                }
            )
            bottomBar.addView(
                button(getString(R.string.discard), tag = TAG_DISCARD) { discardIdCard() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            root.addView(bottomBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) })
            return root
        }

        // Secondary actions live in one collapsible More panel (lane cr adds its Replace row here).
        val morePanel = LinearLayout(this).apply {
            id = R.id.toolResultMorePanel
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        moreLocation = SwStyle.text(this, 14f, SwStyle.color(this, R.color.sw_text_secondary)).apply {
            id = R.id.rsMoreLocation
            visibility = View.GONE
        }
        morePanel.addView(moreLocation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        moreNote = SwStyle.text(this, 14f, SwStyle.color(this, R.color.sw_text_secondary)).apply {
            id = R.id.rsMoreNote
            visibility = View.GONE
        }
        morePanel.addView(moreNote, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        alsoSaveButton = button(
            getString(R.string.tools_result_also_save), R.id.toolResultAlsoSave,
            icon = R.drawable.ic_sym_download
        ) { alsoSave() }
        morePanel.addView(alsoSaveButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })
        saveAnotherCopy = textAction(getString(R.string.rs_save_another_copy)).apply {
            id = R.id.rsSaveAnother
            visibility = View.GONE
            setOnClickListener {
                alsoSaveButton?.text = getString(R.string.tools_result_also_save)
                alsoSaveButton?.isEnabled = true
                visibility = View.GONE
                alsoSave()
            }
        }
        morePanel.addView(saveAnotherCopy, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        morePanel.addView(
            button(getString(R.string.tools_result_choose_location), R.id.toolResultSave) { save() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        val library = button(getString(R.string.tools_result_library), R.id.toolResultLibrary) {
            showInLibrary()
        }
        if (isExportOnlyResult()) library.visibility = View.GONE
        morePanel.addView(library, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })
        morePanel.addView(
            button(getString(R.string.v2t_compress_this_copy), R.id.v2t_compress_copy,
                icon = R.drawable.ic_sym_compress) { chainCompress() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        morePanel.addView(
            button(getString(R.string.v2t_protect_this_copy), R.id.v2t_protect_copy,
                icon = R.drawable.ic_sym_encrypted) { chainProtect() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        morePanel.addView(
            SwStyle.text(this, 13f, mid).apply {
                id = R.id.rsRemoveNote
                text = getString(R.string.rs_remove_copy_note)
                // The saved row already carries this fact; keep the explanation non-visual.
                visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        morePanel.addView(
            button(getString(R.string.ux130_l3_result_remove_copy), R.id.toolResultUndo,
                icon = R.drawable.ic_sym_delete) { undo() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        column.addView(morePanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 12f) })

        // 4. Primary actions are pinned, always visible at 100% on 360/411dp portrait.
        bottomBar = LinearLayout(this).apply {
            id = R.id.rsBottomBar
            orientation = LinearLayout.VERTICAL
            background = SwStyle.rounded(
                this@ToolResultActivity, SwStyle.color(this@ToolResultActivity, R.color.glass_surface),
                radiusDp = 16f, stroke = SwStyle.color(this@ToolResultActivity, R.color.sw_cyan_hair)
            )
        }
        val open = button(getString(R.string.tools_result_open), R.id.toolResultOpen,
            primary = true, icon = R.drawable.ic_sym_folder_open) { open() }
        val share = button(getString(R.string.tools_result_share), R.id.toolResultShare,
            icon = R.drawable.ic_sym_share) { share() }
        var proofWasVisible = false
        val more = button(getString(R.string.rs_more), R.id.toolResultMore) {
            val opening = morePanel.visibility != View.VISIBLE
            // Options replace the proof surface instead of stacking another screenful of facts.
            if (opening) {
                proofWasVisible = compareView?.visibility == View.VISIBLE
                compareView?.visibility = View.GONE
            } else if (proofWasVisible) {
                compareView?.visibility = View.VISIBLE
            }
            morePanel.visibility = if (opening) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.toolResultMore)?.text = getString(
                if (opening) R.string.tools_result_fewer_options else R.string.rs_more
            )
        }
        val actions = listOf(open, share, more)
        actions.forEach { action ->
            bottomBar.addView(action, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })
        return root
    }

    /** L3-02: at 200% type a paired row would clip, so the actions stack in full-width rows. */
    private fun stacksActionsAtLargeFont(): Boolean =
        resources.configuration.fontScale > 1.15f

    /** L3-02: pair Open/Share only where both labels and icons fit side by side. */
    private fun pairPrimaryActions(): Boolean =
        !stacksActionsAtLargeFont() && resources.configuration.screenWidthDp >= 320

    /** H7: a result made of exported files only (no library document behind it). */
    private fun isExportOnlyResult(): Boolean = docIds.isEmpty() && files.isNotEmpty()

    /** A quiet tertiary control (the "More options" disclosure): text, 48dp tall, no box. */
    private fun textAction(text: String): TextView =
        SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            this.text = text
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(this@ToolResultActivity, 50f)
            isClickable = true
            isFocusable = true
        }

    private fun button(
        text: String,
        id: Int = View.NO_ID,
        tag: String? = null,
        primary: Boolean = false,
        icon: Int? = null,
        onClick: () -> Unit
    ): TextView =
        SwStyle.text(this, 15f, SwStyle.color(
            this, if (primary) R.color.sw_disc_ink else R.color.text_hologram_hi
        ), bold = true).apply {
            if (id != View.NO_ID) this.id = id
            if (tag != null) this.tag = tag
            this.text = text
            gravity = Gravity.CENTER
            // At 200% type a long action ("Also save in Documents") needs more than two lines.
            maxLines = Int.MAX_VALUE
            val horizontal = SwStyle.dp(this@ToolResultActivity, 16f)
            val vertical = SwStyle.dp(this@ToolResultActivity, 12f)
            setPadding(horizontal, vertical, horizontal, vertical)
            icon?.let {
                val glyph = requireNotNull(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, it)).mutate()
                val size = SwStyle.dp(this@ToolResultActivity, 24f)
                glyph.setBounds(0, 0, size, size)
                glyph.setTint(currentTextColor)
                setCompoundDrawablesRelative(glyph, null, null, null)
                compoundDrawablePadding = SwStyle.dp(this@ToolResultActivity, 12f)
            }
            // L3-02: every primary/secondary action clears the 56dp decision target.
            minimumHeight = SwStyle.dp(this@ToolResultActivity, 56f)
            background = if (primary) {
                SwStyle.rounded(this@ToolResultActivity, SwStyle.color(this@ToolResultActivity, R.color.cyan_primary), radiusDp = 16f)
            } else {
                SwStyle.rounded(
                    this@ToolResultActivity,
                    SwStyle.color(this@ToolResultActivity, R.color.glass_surface),
                    radiusDp = 16f,
                    stroke = SwStyle.color(this@ToolResultActivity, R.color.sw_cyan_hair)
                )
            }
            isClickable = true
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
            setOnClickListener { onClick() }
        }

    // ---- V2T T2: verified-copy chaining ----------------------------------

    /** Only a single live library output can be chained; never a foreign or stale id. */
    private fun verifiedOutputDoc(): com.scanwhirl.app.model.ScanDocument? {
        val id = docIds.singleOrNull() ?: return null
        return runCatching { repo.getDocument(id) }.getOrNull()
    }

    /**
     * V2T review fix: the SAME recorded identity check Undo uses. Runs on IO;
     * never adopts the current identity at tap time, so a reopened receipt whose
     * id was reused cannot authorize an action on somebody else's document.
     */
    private suspend fun identityStillMatches(): Boolean = withContext(Dispatchers.IO) {
        val expected = outputIdentity
        when {
            expected == null -> docIds.isEmpty() && files.isEmpty()
            expected.startsWith(CHEAP_IDENTITY_PREFIX) ->
                cheapIdentity(this@ToolResultActivity, docIds, files) == expected
            else -> captureOutputIdentityBlocking(this@ToolResultActivity, docIds, files) == expected
        }
    }

    /** Chaining re-checks identity first, with both actions held disabled. */
    private fun withVerifiedOutput(action: (com.scanwhirl.app.model.ScanDocument) -> Unit) {
        if (chainChecking) return
        chainChecking = true
        val buttons = listOf(
            findViewById<View>(R.id.v2t_compress_copy),
            findViewById<View>(R.id.v2t_protect_copy)
        )
        buttons.forEach { it?.isEnabled = false }
        lifecycleScope.launch {
            val matches = try {
                identityStillMatches()
            } finally {
                chainChecking = false
                buttons.forEach { it?.isEnabled = true }
            }
            if (!matches) {
                fail(getString(R.string.ux130_l3_copy_changed))
                return@launch
            }
            val doc = verifiedOutputDoc()
                ?: return@launch fail(getString(R.string.tools_result_nothing))
            action(doc)
        }
    }

    private fun chainCompress() = withVerifiedOutput { doc ->
        startActivity(ToolSetupActivity.intent(this, "compress", selectedDocId = doc.id))
    }

    private fun chainProtect() = withVerifiedOutput { doc ->
        LockFlow(this, repo, v2tHost, { listOf(doc) }, { _ -> }).protectDocument(doc)
    }

    // ---- content ----------------------------------------------------------

    private fun render() {
        if (alreadyCompact) {
            renderAlreadyCompact()
            return
        }
        val docs = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
        val measured = when {
            docs.isNotEmpty() -> docs.sumOf { if (it.pdfFile.isFile) it.pdfFile.length() else 0L }
            files.isNotEmpty() -> files.sumOf { if (it.isFile) it.length() else 0L }
            else -> 0L
        }
        val pages = docs.sumOf { it.pageCount }
        if (isCompressResult) {
            val before = beforeBytes
            if (before != null && before > 0L) {
                val from = StatusLine.formatBytes(before)
                val to = StatusLine.formatBytes(measured)
                summary.text = getString(R.string.rs_size_hero, from, to)
                summary.contentDescription = getString(
                    R.string.rs_hero_a11y_size, from, to, reductionA11y(before, measured)
                )
                val target = targetBytes
                chipView.text = target?.let { t ->
                    val limit = StatusLine.formatBytes(t)
                    when {
                        measured > t -> getString(R.string.rs_over_limit, limit)
                        measured == t -> getString(R.string.rs_at_limit, limit)
                        else -> getString(R.string.rs_under_limit, limit)
                    }
                } ?: reductionChip(before, measured)
                setStatusIcon(chipView, measured < before && (target == null || measured <= target))
                chipView.visibility = View.VISIBLE
            } else if (measured <= 0L) {
                summary.text = getString(R.string.compress_result_size_unknown)
                summary.contentDescription = summary.text
                chipView.visibility = View.GONE
            } else {
                summary.text = getString(R.string.compress_result_size, StatusLine.formatBytes(measured))
                summary.contentDescription = summary.text
                val target = targetBytes
                if (target != null) {
                    val limit = StatusLine.formatBytes(target)
                    chipView.text = when {
                        measured > target -> getString(R.string.rs_over_limit, limit)
                        measured == target -> getString(R.string.rs_at_limit, limit)
                        else -> getString(R.string.rs_under_limit, limit)
                    }
                    setStatusIcon(chipView, measured <= target)
                    chipView.visibility = View.VISIBLE
                } else {
                    chipView.visibility = View.GONE
                }
            }
        } else {
            // UX_RULES_SCANNER: the hero is numbers only. A producer note (Form photo's six-line
            // measurement paragraph) is detail: it lives in More, never on the face of the screen.
            val fact = measuredFactFromNote(note)
                ?: intent.getStringExtra(EXTRA_COMPARE_OUTCOME)?.takeIf { it.isNotBlank() }
                ?: (if (pages > 0) resources.getQuantityString(R.plurals.rs_fact_pages, pages, pages) else null)
                ?: (if (measured > 0L) getString(R.string.compress_result_size, StatusLine.formatBytes(measured)) else null)
                ?: getString(R.string.tools_result_nothing)
            summary.text = fact
            summary.contentDescription = fact
            chipView.visibility = View.GONE
        }

        val name = when {
            docs.size == 1 -> docs.first().name
            docs.size > 1 -> getString(R.string.tools_result_name_more, docs.first().name, docs.size - 1)
            files.size == 1 -> files.first().name
            files.size > 1 -> getString(R.string.tools_result_name_more, files.first().name, files.size - 1)
            else -> ""
        }
        renderSavedLine(name)

        if (idCardFlow) {
            previewHost.visibility = View.VISIBLE
            preview.visibility = View.VISIBLE
            previewPreview(docs.firstOrNull()?.pageFiles?.firstOrNull())
        } else {
            previewHost.visibility = View.GONE
            if (docs.isEmpty() && files.size == 1 && isTypedFile(files.first())) {
                showFileCard(files.first())
            }
        }
        renderMoreLocation()
        moreNote?.let { view ->
            // UX_RULES_SCANNER: never the full producer paragraph. At most a 2-line summary
            // (<= MORE_NOTE_WORDS words); the rest sits behind an info "Details" sheet.
            val full = note?.trim()?.takeIf { it.isNotBlank() }
            view.isVisible = full != null
            view.maxLines = 2
            view.ellipsize = android.text.TextUtils.TruncateAt.END
            if (full == null) {
                view.text = ""
                view.setCompoundDrawablesRelative(null, null, null, null)
                view.setOnClickListener(null)
                view.isClickable = false
            } else {
                val firstLine = full.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                val words = firstLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val summary = if (words.size > MORE_NOTE_WORDS) {
                    words.take(MORE_NOTE_WORDS).joinToString(" ") + "…"
                } else firstLine
                view.text = summary
                if (summary != full) {
                    val d = androidx.appcompat.content.res.AppCompatResources
                        .getDrawable(this, R.drawable.ic_sym_info)?.mutate()
                    val size = SwStyle.dp(this, 18f)
                    d?.setBounds(0, 0, size, size)
                    d?.setTint(SwStyle.color(this, R.color.sw_text_secondary))
                    view.setCompoundDrawablesRelative(null, null, d, null)
                    view.compoundDrawablePadding = SwStyle.dp(this, 6f)
                    view.contentDescription = summary + ". " + getString(R.string.rs_details_title)
                    view.isClickable = true
                    view.setOnClickListener {
                        com.scanwhirl.app.ui.kit.SwSheet.note(this, getString(R.string.rs_details_title), full)
                    }
                } else {
                    view.setCompoundDrawablesRelative(null, null, null, null)
                    view.contentDescription = null
                    view.setOnClickListener(null)
                    view.isClickable = false
                }
            }
        }
        renderV2tStatus()
        bindCompare()
    }

    private fun renderMoreLocation() {
        moreLocation?.let { view ->
            val text = chosenLocationName?.let { name ->
                getString(R.string.rs_where_chosen) + " · " + name
            } ?: documentsExistingName?.let { name ->
                getString(R.string.rs_where_documents_existing) + " · " + name
            } ?: savedCopy?.let { copy ->
                getString(R.string.rs_where_documents) + " · " + copy.name
            } ?: when {
                // The saved row plus Show in library already establish this copy's location.
                docIds.isNotEmpty() -> null
                files.isNotEmpty() -> getString(R.string.rs_unsaved_output)
                else -> null
            }
            view.isVisible = text != null
            view.text = text.orEmpty()
        }
    }

    /**
     * Pull the two deciding numbers out of a producer's measurement note, so the screen shows
     * "140 × 60 px · 32 KB" instead of the whole paragraph. The note itself stays in More.
     */
    private fun measuredFactFromNote(note: String?): String? {
        if (note.isNullOrBlank()) return null
        val px = Regex("""(\d+)\s*[×xX]\s*(\d+)\s*px""").find(note)?.let {
            "${it.groupValues[1]} × ${it.groupValues[2]} px"
        }
        val size = Regex("""\b\d+(?:\.\d+)?\s*(?:KB|MB|GB|B)\b""").find(note)?.value
        return listOfNotNull(px, size).joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /** UX_RULES_SCANNER: status by vector icon, never a glyph in shipped text. */
    internal fun setStatusIcon(view: TextView, ok: Boolean) {
        val d = androidx.appcompat.content.res.AppCompatResources.getDrawable(
            this, if (ok) R.drawable.ic_sym_check else R.drawable.ic_sym_error
        )?.mutate()
        val size = SwStyle.dp(this, 16f)
        d?.setBounds(0, 0, size, size)
        d?.setTint(SwStyle.color(this, if (ok) R.color.rv_accent else R.color.laser_danger))
        view.setCompoundDrawablesRelative(d, null, null, null)
        view.compoundDrawablePadding = SwStyle.dp(this, 6f)
    }

    private fun renderAlreadyCompact() {
        summary.text = getString(R.string.rs_already_compact_title)
        summary.contentDescription = summary.text
        chipView.visibility = View.GONE
        savedLine.text = getString(R.string.rs_already_compact_msg)
        savedLine.isVisible = true
        savedLine.contentDescription = savedLine.text
        previewHost.visibility = View.GONE
        compareView?.visibility = View.GONE
    }

    /** The one receipt row: a tappable name between a prefix and an honest original suffix. */
    private fun renderSavedLine(name: String) {
        if (name.isBlank()) {
            savedLine.isVisible = false
            return
        }
        savedLine.isVisible = true
        val prefix = getString(R.string.rs_saved_prefix)
        val suffix = when {
            sourceKept -> getString(R.string.rs_saved_suffix_kept)
            receiptSourceId == null -> getString(R.string.rs_saved_suffix_no_source)
            repo.getDocument(receiptSourceId!!) != null -> getString(R.string.rs_saved_suffix_kept)
            else -> getString(R.string.rs_saved_suffix_missing)
        }
        val start = prefix.length
        val end = start + name.length
        SpannableString(prefix + name + suffix).apply {
            setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = open()
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            savedLine.text = this
        }
        savedLine.contentDescription = getString(R.string.rs_saved_line_a11y, name)
    }

    /** Rounding can print "1.6 MB → 1.6 MB"; the chip then states the real signed delta. */
    private fun reductionChip(before: Long, after: Long): String = when {
        after >= before -> getString(R.string.rs_delta_larger, StatusLine.formatBytes(after - before))
        StatusLine.formatBytes(before) == StatusLine.formatBytes(after) ->
            getString(R.string.rs_delta_smaller, StatusLine.formatBytes(before - after))
        before / after.coerceAtLeast(1L) >= 2L ->
            getString(R.string.rs_chip_ratio, before / after.coerceAtLeast(1L))
        else -> getString(
            R.string.rs_chip_percent,
            ((before - after) * 100 / before).toInt().coerceIn(1, 99)
        )
    }

    private fun reductionA11y(before: Long, after: Long): String = when {
        after >= before -> getString(R.string.rs_delta_larger, StatusLine.formatBytes(after - before))
        before / after.coerceAtLeast(1L) >= 2L ->
            getString(R.string.rs_reduction_a11y_ratio, before / after.coerceAtLeast(1L))
        else -> getString(
            R.string.rs_reduction_a11y_percent,
            ((before - after) * 100 / before).toInt().coerceIn(1, 99)
        )
    }

    /** V2T T1: the status remains a live a11y node; the visible receipt is the one saved row. */
    private fun renderV2tStatus() {
        val view = v2tStatus ?: return
        val source = receiptSourceId
        when {
            source == null -> view.text = ""
            repo.getDocument(source) != null -> {
                view.text = getString(R.string.v2t_new_copy_original_kept)
                view.contentDescription = view.text
            }
            else -> {
                view.text = getString(R.string.v2t_original_missing)
                view.contentDescription = view.text
            }
        }
        view.isVisible = false
    }

    /** Receipt bookkeeping stays; the visible receipt is the single saved row above. */
    private fun refreshLocationLines() = Unit

    /** Lane RS: reopen a stronger compress setup for the untouched source document. */
    private fun tryStrong() {
        val source = receiptSourceId ?: return
        startActivity(ToolSetupActivity.intent(this, "compress", selectedDocId = source))
        finish()
    }
    // ---- Lane RS: the one proof surface -----------------------------------

    /** One After page, no Before: a mark was added and the rest of the page is unchanged. */
    private val markTools = setOf(
        "sign", "add_text", "draw", "highlight", "stamp", "watermark", "page_numbers"
    )

    /** State-only tools carry no page image at all (never decode a protected/vaulted source). */
    private val noVisualTools = setOf(
        "protect", "unlock", "ocr", "ocr_hindi", "clear_metadata", "redact", "pdf_a", "pdfa"
    )

    private fun bindCompare() {
        val view = compareView ?: return
        if (alreadyCompact) {
            view.visibility = View.GONE
            return
        }
        val explicit = intent.getStringExtra(EXTRA_COMPARE_OUTCOME)
        val model = when {
            explicit != null && toolId in noVisualTools ->
                CompareModel(explicit, ComparePane(caption = ""), ComparePane(caption = ""),
                    mode = CompareMode.NONE)
            isCompressResult -> compressCompareModel()
            toolId in markTools -> markCompareModel()
            !explicit.isNullOrBlank() -> explicitCompareModel(explicit)
            else -> null
        }
        if (model == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.bind(model, lifecycleScope, canDecode = { path ->
            // Re-resolve ownership and protection on IO immediately before each decode.
            (docIds + listOfNotNull(receiptSourceId)).any { id ->
                repo.getDocument(id)?.let { doc ->
                    !doc.isLocked && !doc.isVaulted &&
                        doc.pageFiles.any { it.canonicalPath == File(path).canonicalPath }
                } == true
            }
        })
    }

    private fun compareModeForTool(): CompareMode = when {
        toolId in noVisualTools || !intent.getBooleanExtra(EXTRA_COMPARE_SHOW_TOGGLE, true) ->
            CompareMode.NONE
        toolId in markTools -> CompareMode.AFTER_ONLY
        toolId == "compress" || toolId == "resize" -> CompareMode.ZOOM_STACKED
        else -> CompareMode.NONE
    }

    /** Measured proof supplied by a producer (OCR words, removed fields, page counts, ...). */
    private fun explicitCompareModel(outcome: String): CompareModel {
        fun pane(captionKey: String, imageKey: String, detailKey: String, missing: Int): ComparePane {
            // Explicit image extras are not authority to bypass a source/output's protection.
            val protected = (docIds + listOfNotNull(receiptSourceId)).any { id ->
                repo.getDocument(id)?.let { it.isLocked || it.isVaulted } != false
            }
            val path = intent.getStringExtra(imageKey)?.takeIf { it.isNotBlank() && !protected }
            return ComparePane(
                imagePath = path,
                caption = intent.getStringExtra(captionKey)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.cc_no_numbers),
                detail = intent.getStringArrayExtra(detailKey)?.toList().orEmpty(),
                emptyText = if (path == null) getString(missing) else null
            )
        }
        return CompareModel(
            outcome = outcome,
            before = pane(
                EXTRA_COMPARE_BEFORE_CAPTION, EXTRA_COMPARE_BEFORE_IMAGE,
                EXTRA_COMPARE_BEFORE_DETAIL, R.string.cc_no_before_image
            ),
            after = pane(
                EXTRA_COMPARE_AFTER_CAPTION, EXTRA_COMPARE_AFTER_IMAGE,
                EXTRA_COMPARE_AFTER_DETAIL, R.string.cc_no_after_image
            ),
            pageLabel = intent.getStringExtra(EXTRA_COMPARE_PAGE)?.takeIf { it.isNotBlank() },
            mode = compareModeForTool()
        )
    }

    /**
     * Compress uses the bytes we actually produced and the source document's first page as the
     * real before image. A locked/vaulted source is never decoded to plaintext here.
     */
    private fun compressCompareModel(): CompareModel {
        val after = measuredOutputBytes().takeIf { it > 0L }
        val before = beforeBytes?.takeIf { it > 0L }
        val source = receiptSourceId?.let { id -> runCatching { repo.getDocument(id) }.getOrNull() }
            ?.takeIf { !it.isLocked && !it.isVaulted }
        val output = docIds.firstOrNull()
            ?.let { id -> runCatching { repo.getDocument(id) }.getOrNull() }
            ?.takeIf { !it.isLocked && !it.isVaulted }
        val beforeImage = source?.pageFiles?.firstOrNull()?.takeIf { it.isFile }?.path
        val afterImage = output?.pageFiles?.firstOrNull()?.takeIf { it.isFile }?.path
        return CompareModel(
            outcome = summary.text.toString(),
            before = ComparePane(
                imagePath = beforeImage,
                caption = before?.let { StatusLine.formatBytes(it) } ?: getString(R.string.cc_no_numbers),
                emptyText = if (beforeImage == null) getString(R.string.cc_no_before_image) else null
            ),
            after = ComparePane(
                imagePath = afterImage,
                caption = after?.let { StatusLine.formatBytes(it) } ?: getString(R.string.cc_no_numbers),
                emptyText = if (afterImage == null) getString(R.string.cc_no_after_image) else null
            ),
            pageLabel = getString(R.string.cc_page, 1),
            mode = CompareMode.ZOOM_STACKED
        )
    }

    /** Page tools: one After page with an honest mark caption, never a duplicate Before page. */
    private fun markCompareModel(): CompareModel {
        val output = docIds.firstOrNull()
            ?.let { id -> runCatching { repo.getDocument(id) }.getOrNull() }
            ?.takeIf { !it.isLocked && !it.isVaulted }
        val afterImage = output?.pageFiles?.firstOrNull()?.takeIf { it.isFile }?.path
        return CompareModel(
            outcome = getString(R.string.cc_mark_added),
            before = ComparePane(caption = ""),
            after = ComparePane(
                imagePath = afterImage,
                caption = getString(R.string.rs_after_size_label, getString(R.string.cc_after_mark)),
                emptyText = if (afterImage == null) getString(R.string.cc_no_after_image) else null
            ),
            pageLabel = getString(R.string.cc_page, 1),
            mode = CompareMode.AFTER_ONLY
        )
    }

    /**
     * True for the results that have no page to render: the generated Office packages, a ZIP,
     * and a PDF the person produced (M6: a lone PDF result used to paint an empty white box;
     * it now gets the same type card as the other file results).
     */
    private fun isTypedFile(file: File): Boolean =
        file.name.endsWith(".docx") || file.name.endsWith(".xlsx") ||
            file.name.endsWith(".pptx") || file.name.endsWith(".zip") ||
            file.name.endsWith(".pdf", ignoreCase = true)

    /**
     * The file-type card (v2 UI run4a): the Office/ZIP/PDF results are real files with no page
     * image, so the preview area shows a big type badge, the file name, its size and
     * page/sheet/slide count instead of an empty white rectangle.
     */
    private fun showFileCard(file: File) {
        preview.isVisible = false
        previewHost.removeViews(1, (previewHost.childCount - 1).coerceAtLeast(0))
        val extension = file.extension.lowercase()
        val accent = when (extension) {
            "docx" -> R.color.file_docx
            "xlsx" -> R.color.file_xlsx
            "pptx" -> R.color.file_pptx
            "pdf" -> R.color.file_pdf
            else -> R.color.file_zip
        }
        val label = when (extension) {
            "docx" -> R.string.tools_result_type_docx
            "xlsx" -> R.string.tools_result_type_xlsx
            "pptx" -> R.string.tools_result_type_pptx
            "pdf" -> R.string.tools_result_type_pdf
            else -> R.string.tools_result_type_zip
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = SwStyle.dp(this@ToolResultActivity, 18f)
            setPadding(pad, pad, pad, pad)
        }
        val badge = SwStyle.text(
            this, 22f, SwStyle.color(this, R.color.sw_disc_ink), bold = true, letterSpacing = 0.08f
        ).apply {
            id = R.id.toolResultTypeBadge
            text = getString(label)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@ToolResultActivity,
                SwStyle.color(this@ToolResultActivity, accent),
                radiusDp = 12f
            )
            val h = SwStyle.dp(this@ToolResultActivity, 18f)
            val v = SwStyle.dp(this@ToolResultActivity, 7f)
            setPadding(h, v, h, v)
        }
        card.addView(badge)
        card.addView(SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.toolResultFileName
            text = file.name
            isVisible = files.size == 1
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 12f) })
        val meta = SwStyle.text(this, 14f, SwStyle.color(this, R.color.sw_text_primary)).apply {
            id = R.id.toolResultFileMeta
            text = StatusLine.formatBytes(file.length())
            gravity = Gravity.CENTER
        }
        card.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 6f) })
        // T37 / O1-5: a PPTX is a picture deck. Say so on the result, but only claim speaker
        // notes when the file actually contains them; otherwise say there was no text to read.
        var pptxNoteView: TextView? = null
        if (extension == "pptx") {
            pptxNoteView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                id = R.id.toolResultRasterNote
                text = getString(R.string.tools_result_pptx_no_text_note)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            }
            card.addView(pptxNoteView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })
        }
        previewHost.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { OfficeFileCounts.count(file) }
            if (count != null && !isFinishing) {
                meta.text = getString(
                    R.string.tools_result_with_size, StatusLine.formatBytes(file.length()), countLabel(extension, count)
                )
            }
            if (extension == "pptx") {
                val withNotes = withContext(Dispatchers.IO) { PptxWriter.hasNotes(file) }
                if (!isFinishing) {
                    pptxNoteView?.text = getString(
                        if (withNotes) R.string.tools_result_pptx_note
                        else R.string.tools_result_pptx_no_text_note
                    )
                }
            }
        }
    }

    private fun countLabel(extension: String, count: Int): String = when (extension) {
        "docx" -> resources.getQuantityString(R.plurals.tools_result_pages, count, count)
        "xlsx" -> resources.getQuantityString(R.plurals.tools_result_sheets, count, count)
        "pptx" -> resources.getQuantityString(R.plurals.tools_result_slides, count, count)
        else -> resources.getQuantityString(R.plurals.tools_result_files_count, count, count)
    }

    private fun previewPreview(file: File?) {
        if (file == null) {
            preview.setImageResource(R.drawable.ic_sym_description)
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { ImageTools.decodeSampledFile(file, 900) }.getOrNull() }
            if (bitmap != null) preview.setImageBitmap(bitmap) else preview.setImageResource(iconFor(file))
        }
    }

    private fun iconFor(file: File): Int = when {
        file.name.endsWith(".zip") -> R.drawable.ic_sym_archive
        file.name.endsWith(".docx") -> R.drawable.ic_sym_description
        file.name.endsWith(".pptx") -> R.drawable.ic_sym_slideshow
        file.name.endsWith(".xlsx") -> R.drawable.ic_sym_table_chart
        else -> R.drawable.ic_sym_image
    }

    // ---- actions ----------------------------------------------------------

    private fun open() {
        val doc = docIds.firstOrNull()?.let { runCatching { repo.getDocument(it) }.getOrNull() }
        if (doc != null) {
            startActivity(DocumentViewerActivity.intent(this, doc.id))
            return
        }
        val file = files.firstOrNull()
        if (file == null || !file.isFile || file.length() == 0L) {
            return fail(getString(R.string.tools_result_nothing))
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            fail(getString(R.string.tools_result_open_failed))
        }
    }

    private fun share() {
        val docs = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
        if (docs.size == 1) {
            ShareUtils.share(this, docs.first())
            return
        }
        if (docs.size > 1) {
            shareUris(ShareUtils.documentItems(docs), "application/pdf")
            return
        }
        if (files.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        if (files.size == 1) {
            ShareUtils.shareFile(this, files.first(), mime)
        } else {
            shareUris(ShareUtils.fileItems(files), mime)
        }
    }

    /** H2-1: [items] are (stored file, outgoing name); the receiver sees the names, numbered if equal. */
    private fun shareUris(items: List<Pair<File, String>>, type: String) {
        val uris = ShareUtils.namedUris(this, items)
        if (uris.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        // fixshare: every Uri must be in ClipData too — the Android 10+ grant model follows it,
        // so a ClipData with only the first file shared one file from a batch.
        val intent = ShareUtils.multiShareIntent(this, uris, type, toolName)
        startActivity(Intent.createChooser(intent, getString(R.string.tools_result_share)))
    }

    private fun save() {
        val name = suggestedName() ?: return fail(getString(R.string.tools_result_nothing))
        saveAs.launch(name)
    }

    /**
     * T05-RESULT-SLICE: "Also save in Documents" — the one-tap copy into Documents/ScanWhirl,
     * with the export transaction's rules (see [DownloadSaver]) and the same confirmation the
     * export result screen uses: Saved / Already saved / "a new copy, the earlier file was kept",
     * and an Undo that removes only the copy this save created.
     */
    private fun alsoSave() {
        // H2-4: before Android 10 this copy lands in the public Documents folder through a plain
        // file, which needs the runtime storage grant. Ask with a plain reason instead of letting
        // the write fail on EACCES and showing its raw system text.
        if (DownloadSaver.needsLegacyStoragePermission(this)) {
            pendingSave = { alsoSave() }
            fail(getString(R.string.f7save_perm_why_documents))
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val source = sourceForSave() ?: return fail(getString(R.string.tools_result_nothing))
        if (!source.isFile || source.length() == 0L) {
            return fail(getString(R.string.tools_result_nothing))
        }
        val name = suggestedName() ?: source.name
        val type = saveMimeFor(name)
        // A single document result is that document's own PDF, so the copy is recorded against
        // it like any other export of it (delete-with-document then knows the copy).
        val docId = docIds.singleOrNull()
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    DownloadSaver.saveFile(this@ToolResultActivity, source, name, type).also { copy ->
                        if (docId != null) {
                            try {
                                repo.addExport(docId, copy)
                            } catch (_: Exception) {
                                // A failure to record the copy must not turn a finished save into a failure.
                            }
                        }
                    }
                }
                // B2-H1: only a copy this save actually created may ever be undone here. An
                // identical file that was already in Documents stays out of reach, as always.
                if (saved.created) {
                    savedCopy = saved
                    documentsExistingName = null
                } else {
                    documentsExistingName = saved.name
                }
                refreshLocationLines()
                persistReceipt()
                // Lead addition: the button itself becomes the durable receipt, not just a toast.
                markDocumentsSaved()
                SavedSnackbar.showExport(
                    this@ToolResultActivity, rootView, saved, R.string.result_saved_documents, docId
                ) { result ->
                    // W3: if the bar's Undo removed the copy, the screen's receipt must stop
                    // claiming it is there.
                    if (result == DownloadSaver.UndoResult.REMOVED) {
                        savedCopy = null
                        documentsExistingName = null
                        markDocumentsNotSaved()
                        refreshLocationLines()
                        persistReceipt()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(getString(R.string.tools_result_save_failed, e.message ?: ""))
            }
        }
    }

    /** Lead addition: a successful Documents save is a durable button state, not a passing toast. */
    private fun markDocumentsSaved() {
        alsoSaveButton?.text = getString(R.string.rs_saved_documents)
        alsoSaveButton?.isEnabled = false
        saveAnotherCopy?.isVisible = true
    }

    private fun markDocumentsNotSaved() {
        alsoSaveButton?.text = getString(R.string.tools_result_also_save)
        alsoSaveButton?.isEnabled = true
        saveAnotherCopy?.isVisible = false
    }

    /** The type the Documents copy is indexed with: a document's PDF, a ZIP, or the result's own. */
    private fun saveMimeFor(name: String): String =
        if (name.endsWith(".zip", ignoreCase = true)) "application/zip" else pickerMime()

    /**
     * H2-2: the type the system Save-as picker is asked for must match the bytes it will receive.
     * Several results are one ZIP; a single document result is its PDF; a single exported file
     * keeps its own type. Before this, a multi-document result asked for application/pdf and the
     * picker saved ZIP bytes as "Split.zip.pdf".
     */
    private fun pickerMime(): String = when {
        docIds.size > 1 || files.size > 1 -> "application/zip"
        docIds.size == 1 -> "application/pdf"
        else -> mime
    }

    private fun suggestedName(): String? = when {
        docIds.size == 1 -> (docIds.firstOrNull()?.let { repo.getDocument(it)?.name } ?: toolName) + ".pdf"
        docIds.size > 1 -> "$toolName.zip"
        files.size == 1 -> files.first().name
        files.isNotEmpty() -> "$toolName.zip"
        else -> null
    }

    private fun sourceForSave(): File? {
        val doc = docIds.firstOrNull()
        if (doc != null) {
            val documents = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
            if (documents.size == 1) return documents.first().pdfFile
            return zipOf(ShareUtils.documentItems(documents))
        }
        if (files.size == 1) return files.first()
        return zipOf(ShareUtils.fileItems(files))
    }

    /** H2-1: entries carry the documents' own names (numbered when equal), not the stored scan.pdf. */
    private fun zipOf(entries: List<Pair<File, String>>): File? {
        if (entries.none { it.first.isFile }) return null
        val out = File(cacheDir, "documents/save/${System.currentTimeMillis()}.zip").apply {
            parentFile?.mkdirs()
        }
        return try {
            ZipExporter.exportFiles(entries, out)
            out.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            out.delete()
            fail(getString(R.string.tools_result_save_failed, e.message ?: ""))
            null
        }
    }

    private fun saveTo(uri: Uri) {
        val source = sourceForSave() ?: return fail(getString(R.string.tools_result_nothing))
        val suggested = suggestedName() ?: source.name
        clearFail()
        lifecycleScope.launch {
            try {
                val verified = withContext(Dispatchers.IO) {
                    // H2-2: the picker has already created the document. If anything below fails,
                    // that empty or half-written file must be removed again (see the catch).
                    contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                        out.flush()
                    } ?: throw IOException("No access to the destination")
                    // W3: the location is named only after the written bytes were read back and
                    // match the produced file, not just because the picker returned a URI.
                    writtenBytesMatch(uri, source)
                }
                if (!verified) throw IOException("The copy could not be verified")
                // H2-2: the provider may have adjusted the extension or numbered the name, and the
                // person may have typed another name in the picker. The receipt names the file
                // that really exists, never the suggestion we asked for.
                val created = withContext(Dispatchers.IO) { createdName(uri, suggested) }
                chosenLocationName = created
                refreshLocationLines()
                renderMoreLocation()
                persistReceipt()
                Toast.makeText(
                    this@ToolResultActivity,
                    getString(R.string.tools_result_saved, created),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // H2-2: no half-written document is left in the folder the person chose. The
                // failure is said in the app's own words, never as a raw system message.
                val removed = withContext(Dispatchers.IO) { removeCreatedDocument(uri) }
                fail(
                    when {
                        e is SecurityException -> getString(R.string.f7save_perm_denied_documents)
                        !removed -> getString(
                            R.string.tools_result_save_failed,
                            getString(R.string.f7save_picker_write_failed_left)
                        )
                        else -> getString(
                            R.string.tools_result_save_failed,
                            getString(R.string.f7save_picker_write_failed)
                        )
                    }
                )
            }
        }
    }

    /** H2-2: the name the created file really has; the suggestion is only the fallback. */
    private fun createdName(uri: Uri, fallback: String): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0 && !cursor.isNull(0)) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    } catch (_: Exception) {
        fallback
    }

    /**
     * H2-2: removes the document the picker created for a copy that then failed, so no empty or
     * partial file stays in the folder the person chose. A Documents provider is asked through
     * [DocumentsContract]; any other provider through the resolver's own delete.
     */
    private fun removeCreatedDocument(uri: Uri): Boolean {
        val removedByProvider = try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
            false
        }
        if (removedByProvider) return true
        return try {
            contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    /** W3: exact read-back comparison of the file just written through the system picker. */
    private fun writtenBytesMatch(uri: Uri, source: File): Boolean {
        val readBack = contentResolver.openInputStream(uri) ?: return false
        return readBack.use { input ->
            source.inputStream().use { expected ->
                val a = ByteArray(64 * 1024)
                val b = ByteArray(64 * 1024)
                var same = true
                while (true) {
                    val n = expected.read(a)
                    val m = input.read(b)
                    if (n != m) {
                        same = false
                        break
                    }
                    if (n < 0) break
                    for (i in 0 until n) {
                        if (a[i] != b[i]) {
                            same = false
                            break
                        }
                    }
                    if (!same) break
                }
                same
            }
        }
    }
    private fun showInLibrary() {
        val docId = docIds.firstOrNull()
        startActivity(
            ShellActivity.intent(this, docId).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun undo() {
        if (undone || undoChecking) return
        undoChecking = true
        val undoButton: View? = findViewById(R.id.toolResultUndo)
        undoButton?.isEnabled = false
        lifecycleScope.launch {
            // L3-02 P1: the re-check hashes on IO, never on Main, while the
            // button is disabled so a second tap cannot race the first.
            val allowed = try {
                identityStillMatches()
            } finally {
                // P2: a cancelled re-check must never leave Undo disabled.
                undoChecking = false
                undoButton?.isEnabled = true
            }
            if (!allowed) {
                fail(getString(R.string.ux130_l3_copy_changed))
                return@launch
            }
            undone = true
            persistReceipt()
            val outs = files.toList()
            // T10-FIX-PDFA-UNDO data-safety guard: a result carrying BOTH a library document and a
            // generated file is an export whose docIds are inputs, not copies. No tool legitimately
            // creates that shape — document copies pass docIds only, exports pass files only — and
            // deleteDocument permanently purges (no Trash). Undo therefore removes the generated
            // files and keeps the document: the safe direction for an unknown caller.
            val docs = if (outs.isEmpty()) docIds.toList() else emptyList()
            val recordDoc = docIds.singleOrNull()
            // B2-H1: the Documents copy this run saved is part of the run, so the screen's Undo
            // removes it too — the confirmation bar is no longer the only way, and pressing Undo
            // after that bar has gone no longer leaves the copy behind. Only the copy this run
            // created is a candidate, and only while it still holds exactly the bytes it wrote.
            val copy = savedCopy
            savedCopy = null
            documentsExistingName = null
            docIds.clear()
            files.clear()
            locationLines.clear()
            val copyUndo = copy?.let { saved ->
                withContext(Dispatchers.IO) {
                    val result = try {
                        DownloadSaver.undoSave(this@ToolResultActivity, saved)
                    } catch (_: Exception) {
                        DownloadSaver.UndoResult.FAILED
                    }
                    if (result == DownloadSaver.UndoResult.REMOVED && recordDoc != null && docs.isEmpty()) {
                        try {
                            repo.removeExport(recordDoc, saved.uri)
                        } catch (_: Exception) {
                            // A stale record is harmless: delete-with-document re-checks the file.
                        }
                    }
                    result
                }
            }
            withContext(Dispatchers.IO) {
                docs.forEach { runCatching { repo.deleteDocument(it) } }
                outs.forEach { runCatching { it.delete() } }
            }
            // W3: claim removal only after checking the copies are really gone. deleteDocument
            // and File.delete can fail; a recreated screen can also ask to undo a run that is
            // already gone. In both cases the honest answer is "not every copy was removed".
            val remainingDocs = withContext(Dispatchers.IO) {
                docs.filter { runCatching { repo.getDocument(it) }.getOrNull() != null }
            }
            val remainingFiles = withContext(Dispatchers.IO) { outs.filter { it.exists() } }
            val allRunCopiesGone = remainingDocs.isEmpty() && remainingFiles.isEmpty()
            val nothingToRemove = docs.isEmpty() && outs.isEmpty() && copy == null
            // Data honesty: a copy that could not be removed is never reported as undone.
            val (text, long) = when {
                copyUndo == DownloadSaver.UndoResult.KEPT_CHANGED ->
                    getString(R.string.export_undo_kept_changed, copy?.name.orEmpty()) to true
                copyUndo == DownloadSaver.UndoResult.FAILED ->
                    getString(R.string.export_undo_failed, copy?.name.orEmpty()) to true
                nothingToRemove || !allRunCopiesGone ->
                    getString(R.string.w3_undo_not_removed) to true
                // B3 (#9-now): say exactly what this Undo removed, now that it is verified.
                isCompressResult && copy != null ->
                    getString(R.string.compress_undo_removed_with_copy) to false
                isCompressResult ->
                    getString(R.string.compress_undo_removed) to false
                else -> getString(R.string.tools_result_undone) to false
            }
            Toast.makeText(
                this@ToolResultActivity,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    /**
     * W3: a result that was already undone must not offer its actions again after recreation —
     * the files it names may be gone, and a repeated Undo would make an unverifiable claim.
     */
    private fun showAlreadyUndone() {
        listOf(
            R.id.toolResultOpen, R.id.toolResultShare, R.id.toolResultAlsoSave,
            R.id.toolResultMore, R.id.toolResultMorePanel, R.id.toolResultUndo,
            R.id.toolResultSave, R.id.toolResultLibrary
        ).forEach { id -> findViewById<View>(id)?.visibility = View.GONE }
        if (::bottomBar.isInitialized) bottomBar.isVisible = false
        summary.text = getString(R.string.w3_result_undone_note)
    }

    // ---- ID card: Keep / Discard (F1/W3) ----------------------------------

    private fun handleBack() {
        if (idCardFlow) confirmDiscardIdCard() else finish()
    }

    /** Back on a saved ID card asks before anything is deleted. */
    private fun confirmDiscardIdCard() {
        if (undone) {
            finish()
            return
        }
        SwSheet.builder(this)
            .setTitle(R.string.fixflow_id_discard_title)
            .setMessage(R.string.fixflow_id_discard_msg)
            .setPrimary(R.string.fixflow_id_delete_copy) { discardIdCard() }
            .setSecondary(R.string.fixflow_keep)
            .build()
            .show()
    }

    /** Deletes exactly the card this run created (and its preview); nothing else is touched. */
    private fun discardIdCard() {
        if (undone) return
        undone = true
        val docs = docIds.toList()
        docIds.clear()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { docs.forEach { runCatching { repo.deleteDocument(it) } } }
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    private fun fail(message: String) {
        errorView.text = message
        errorView.isVisible = true
    }

    /** H2-4: a granted permission clears the reason line it was asked with. */
    private fun clearFail() {
        errorView.text = ""
        errorView.isVisible = false
    }

    private fun defaultMime(): String = when {
        docIds.isNotEmpty() -> "application/pdf"
        files.any { it.name.endsWith(".zip") } -> "application/zip"
        files.any { it.name.endsWith(".png") } -> "image/png"
        files.any { it.name.endsWith(".docx") } ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        files.any { it.name.endsWith(".pptx") } ->
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        files.any { it.name.endsWith(".xlsx") } ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "image/jpeg"
    }

    override fun onDestroy() {
        // SEC2 N3: after a handoff this screen owns the FORM1 output cache.
        // A real exit ends the flow; rotation keeps the file restorable.
        if (isFinishing && !isChangingConfigurations) {
            val form1Cache = File(cacheDir, "form1_out").canonicalFile
            files.forEach { file ->
                runCatching {
                    if (file.canonicalFile.parentFile == form1Cache) file.delete()
                }
            }
        }
        super.onDestroy()
    }

    companion object {
        /** Word cap of the More-panel note summary; the rest is in the Details sheet. */
        internal const val MORE_NOTE_WORDS = 10

        private const val EXTRA_TOOL_ID = "tool_id"
        private const val EXTRA_DOC_IDS = "doc_ids"
        private const val EXTRA_FILES = "files"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NOTE = "note"

        /** T05: Compress's wanted size in bytes (absent or 0 = no target). */
        private const val EXTRA_TARGET = "target_bytes"

        /** B2-M2: the source size for Compress's "Before … → After …" line (absent or 0 = none). */
        private const val EXTRA_BEFORE = "before_bytes"

        /** UI-B3 (#9): the compressed source is a library document and was left in place. */
        private const val EXTRA_SOURCE_KEPT = "source_kept"

        /** V2T T1: reopen the durable last-result receipt after process death. */
        private const val EXTRA_USE_LAST_RECEIPT = "v2t_use_last_receipt"

        /** CP1: the result did not reach the typed size, or the copy grew. The header says so. */
        private const val EXTRA_COMPRESS_MISSED = "compress_missed"
        private const val EXTRA_COMPRESS_GREW = "compress_grew"

        /** L3-02 P1: output identity captured at result creation, checked before removal. */
        private const val EXTRA_OUTPUT_IDENTITY = "l3_output_identity"
        private const val CHEAP_IDENTITY_PREFIX = "C:"

        /** L3-02 P2: the library source used to derive the "original kept" receipt. */
        private const val EXTRA_RECEIPT_SOURCE_ID = "l3_receipt_source_id"

        // Lane C: optional, measured before/after proof for the compare surface.
        private const val EXTRA_COMPARE_OUTCOME = "cc_outcome"
        private const val EXTRA_COMPARE_BEFORE_CAPTION = "cc_before_caption"
        private const val EXTRA_COMPARE_AFTER_CAPTION = "cc_after_caption"
        private const val EXTRA_COMPARE_BEFORE_IMAGE = "cc_before_image"
        private const val EXTRA_COMPARE_AFTER_IMAGE = "cc_after_image"
        private const val EXTRA_COMPARE_BEFORE_DETAIL = "cc_before_detail"
        private const val EXTRA_COMPARE_AFTER_DETAIL = "cc_after_detail"
        private const val EXTRA_COMPARE_PAGE = "cc_page_label"
        private const val EXTRA_COMPARE_SHOW_TOGGLE = "cc_show_toggle"

        /** Lane RS: persisted outcome class and whether a stronger preset still exists. */
        private const val EXTRA_OUTCOME_CLASS = "rs_outcome_class"
        private const val EXTRA_NO_STRONGER_PRESET = "rs_no_stronger_preset"

        /** W3: recreation-safe receipt/undo state (never a storage policy, only this run's own copy). */
        private const val KEY_UNDONE = "w3_undone"
        private const val KEY_COPY_URI = "w3_copy_uri"
        private const val KEY_COPY_NAME = "w3_copy_name"
        private const val KEY_COPY_REQUESTED = "w3_copy_requested"
        private const val KEY_COPY_OUTCOME = "w3_copy_outcome"
        private const val KEY_COPY_FINGERPRINT = "w3_copy_fingerprint"
        private const val KEY_CHOSEN_NAME = "w3_chosen_name"
        private const val KEY_EXISTING_NAME = "w3_existing_name"

        /** F1: view tags for the ID card's two actions (no new ids needed). */
        const val TAG_KEEP = "fixflow:id_card_keep"
        const val TAG_DISCARD = "fixflow:id_card_discard"

        /** fixidcrop: the card page's dark preview backdrop (ink fill, 1dp #33FFFFFF line). */
        private const val ID_PREVIEW_LINE = 0x33FFFFFF
        private const val ID_PREVIEW_PADDING_DP = 10f

        fun intent(
            context: Context,
            toolId: String,
            docIds: List<String> = emptyList(),
            files: List<File> = emptyList(),
            mime: String? = null,
            note: String? = null,
            /** T05: what Compress was asked to reach, so a miss can be said out loud. */
            targetBytes: Long? = null,
            /** B2-M2: the size before Compress ran, for the "Before … → After …" line. */
            beforeBytes: Long? = null,
            /** UI-B3: the source was a library document and stays there untouched. */
            sourceKept: Boolean = false,
            /** CP1: the typed target was missed, so the header must not claim "Compressed". */
            compressTargetMissed: Boolean = false,
            /** CP1: after > before, so the result must admit the copy is larger. */
            compressGrew: Boolean = false,
            /** L3-02 P2: library source, used only to derive the receipt from actual state. */
            receiptSourceId: String? = null,
            /** L3-02 P1: identity captured by the producer OFF the main thread. */
            outputIdentity: String? = null,
            /** Lane C: explicit before/after proof; absent = derive for compress/page tools. */
            compareOutcome: String? = null,
            compareBeforeCaption: String? = null,
            compareAfterCaption: String? = null,
            compareBeforeImage: String? = null,
            compareAfterImage: String? = null,
            compareBeforeDetail: List<String>? = null,
            compareAfterDetail: List<String>? = null,
            comparePageLabel: String? = null,
            compareShowToggle: Boolean = true,
            /** Lane RS: SMALLER / NOT_SMALLER / TARGET_MISSED, persisted for a truthful reopen. */
            outcomeClass: String? = null,
            /** Lane RS: hide "Try Strong" when the strongest preset was already used. */
            noStrongerPreset: Boolean = false
        ): Intent = Intent(context, ToolResultActivity::class.java)
            .putExtra(EXTRA_TOOL_ID, toolId)
            .putStringArrayListExtra(EXTRA_DOC_IDS, ArrayList(docIds))
            .putStringArrayListExtra(EXTRA_FILES, ArrayList(files.map { it.path }))
            .putExtra(EXTRA_MIME, mime)
            .putExtra(EXTRA_NOTE, note)
            .apply {
                if (targetBytes != null && targetBytes > 0L) putExtra(EXTRA_TARGET, targetBytes)
                if (beforeBytes != null && beforeBytes > 0L) putExtra(EXTRA_BEFORE, beforeBytes)
                if (sourceKept) putExtra(EXTRA_SOURCE_KEPT, true)
                if (compressTargetMissed) putExtra(EXTRA_COMPRESS_MISSED, true)
                if (compressGrew) putExtra(EXTRA_COMPRESS_GREW, true)
                if (receiptSourceId != null) putExtra(EXTRA_RECEIPT_SOURCE_ID, receiptSourceId)
                // Lane C: measured before/after proof, only when a producer supplies it.
                if (compareOutcome != null) putExtra(EXTRA_COMPARE_OUTCOME, compareOutcome)
                if (compareBeforeCaption != null) putExtra(EXTRA_COMPARE_BEFORE_CAPTION, compareBeforeCaption)
                if (compareAfterCaption != null) putExtra(EXTRA_COMPARE_AFTER_CAPTION, compareAfterCaption)
                if (compareBeforeImage != null) putExtra(EXTRA_COMPARE_BEFORE_IMAGE, compareBeforeImage)
                if (compareAfterImage != null) putExtra(EXTRA_COMPARE_AFTER_IMAGE, compareAfterImage)
                if (compareBeforeDetail != null) putExtra(EXTRA_COMPARE_BEFORE_DETAIL, compareBeforeDetail.toTypedArray())
                if (compareAfterDetail != null) putExtra(EXTRA_COMPARE_AFTER_DETAIL, compareAfterDetail.toTypedArray())
                if (comparePageLabel != null) putExtra(EXTRA_COMPARE_PAGE, comparePageLabel)
                if (!compareShowToggle) putExtra(EXTRA_COMPARE_SHOW_TOGGLE, false)
                if (outcomeClass != null) putExtra(EXTRA_OUTCOME_CLASS, outcomeClass)
                if (noStrongerPreset) putExtra(EXTRA_NO_STRONGER_PRESET, true)
                // No hashing here: intent() runs on Main. Producers capture the
                // full identity in their own IO work; any caller that did not gets
                // a cheap stat-only identity (id + createdAt + size + mtime), so
                // the identity is always recorded BEFORE the user can reach Undo.
                val identity = outputIdentity ?: cheapIdentity(context, docIds, files)
                if (identity != null) putExtra(EXTRA_OUTPUT_IDENTITY, identity)
            }

        /** V2T T1: reopen the last durable result after process death. */
        fun intentLastResult(context: Context): Intent =
            Intent(context, ToolResultActivity::class.java)
                .putExtra(EXTRA_USE_LAST_RECEIPT, true)

        /**
         * Cheap identity: ordinary file stats only, no content hashing. Safe to
         * call on Main. Used for callers that did not supply a producer identity.
         */
        fun cheapIdentity(context: Context, docIds: List<String>, files: List<File>): String? {
            return try {
                if (files.isNotEmpty()) {
                    val parts = files.sortedBy { it.path }.map { f ->
                        "${f.path}|${f.length()}|${f.lastModified()}"
                    }
                    "C:F:" + parts.joinToString(";")
                } else if (docIds.isNotEmpty()) {
                    val repo = ScanWhirlApp.from(context).repository
                    val parts = docIds.sorted().map { id ->
                        val doc = repo.getDocument(id) ?: return null
                        val pdf = doc.pdfFile
                        val size = if (pdf.isFile) pdf.length() else -1L
                        val modified = if (pdf.isFile) pdf.lastModified() else -1L
                        "$id|${doc.createdAt}|$size|$modified"
                    }
                    "C:D:" + parts.joinToString(";")
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        /** Test seam: observes the thread that performs identity hashing. */
        @Volatile
        internal var identityThreadProbe: ((Thread) -> Unit)? = null

        /**
         * L3-02 P1: hash the exact outputs. BLOCKING — callers must already be on
         * Dispatchers.IO. A later document that merely reuses an id has different
         * bytes and can never satisfy the same identity, so Undo cannot delete it.
         */
        fun captureOutputIdentityBlocking(
            context: Context,
            docIds: List<String>,
            files: List<File>
        ): String? {
            identityThreadProbe?.invoke(Thread.currentThread())
            return try {
                if (files.isNotEmpty()) {
                    val parts = mutableListOf<String>()
                    for (file in files.sortedBy { it.path }) {
                        if (!file.exists()) return null
                        // A result may name a directory (a failed export folder);
                        // keep those removable so the honest "not every copy was
                        // removed" path still works on base behavior.
                        parts += if (file.isFile) {
                            "${file.path}|${file.length()}|${Sha256.of(file)}"
                        } else {
                            "${file.path}|dir|${file.lastModified()}"
                        }
                    }
                    "F:" + parts.joinToString(";")
                } else if (docIds.isNotEmpty()) {
                    val repo = ScanWhirlApp.from(context).repository
                    val parts = mutableListOf<String>()
                    for (id in docIds.sorted()) {
                        val doc = repo.getDocument(id) ?: return null
                        val pdf = doc.pdfFile
                        if (!pdf.isFile) return null
                        parts += "$id|${doc.createdAt}|${pdf.length()}|${Sha256.of(pdf)}"
                    }
                    "D:" + parts.joinToString(";")
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
