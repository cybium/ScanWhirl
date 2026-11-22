package com.scanwhirl.app.ui.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import com.scanwhirl.app.store.ReplaceCoordinator
import com.scanwhirl.app.util.ReplaceFeature
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ReplaceSheet
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.CompareModel
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
        // L3-02 P1: the identity was captured when this intent was built, before
        // any later owner of the id could replace the output.
        outputIdentity = intent.getStringExtra(EXTRA_OUTPUT_IDENTITY)
        // L3-02 P2: derive the original-kept receipt from the library right now.
        receiptSourceId = intent.getStringExtra(EXTRA_RECEIPT_SOURCE_ID)
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
            }
        }
        if (docIds.isNotEmpty() || files.isNotEmpty()) persistReceipt()
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
            savedAt = System.currentTimeMillis()
        ))
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@ToolResultActivity, 18f), SwStyle.dp(this@ToolResultActivity, 10f),
                SwStyle.dp(this@ToolResultActivity, 18f), SwStyle.dp(this@ToolResultActivity, 24f)
            )
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
            layoutParams = LinearLayout.LayoutParams(
                SwStyle.dp(this@ToolResultActivity, 48f), SwStyle.dp(this@ToolResultActivity, 48f)
            )
            setOnClickListener { handleBack() }
        })
        bar.addView(
            SwStyle.text(this, 22f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                // B3: a compressed result opens with what happened, not with "Done".
                // CP1: a target miss or a copy that grew must not be titled "Compressed".
                text = getString(
                    when {
                        isCompressResult &&
                            intent.getBooleanExtra(EXTRA_COMPRESS_MISSED, false) ->
                            R.string.cp1_compress_header_missed
                        isCompressResult &&
                            intent.getBooleanExtra(EXTRA_COMPRESS_GREW, false) ->
                            R.string.cp1_compress_header_grew
                        isCompressResult -> R.string.compress_result_header
                        else -> R.string.tools_result_done
                    }
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@ToolResultActivity, 10f)
            }
        )
        root.addView(bar)

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        previewHost = FrameLayout(this).apply {
            id = R.id.toolResultPreviewHost
            // fixidcrop: a white ID card page on a white box had no visible paper edge. The card
            // page sits on the ink backdrop, inset by a little padding and framed by a thin line,
            // so the sheet is obvious. Every other tool result keeps its previous preview.
            when {
                idCardFlow -> {
                    val pad = SwStyle.dp(this@ToolResultActivity, ID_PREVIEW_PADDING_DP)
                    setPadding(pad, pad, pad, pad)
                    background = SwStyle.rounded(
                        this@ToolResultActivity,
                        SwStyle.color(this@ToolResultActivity, R.color.void_abyss),
                        radiusDp = 16f,
                        stroke = ID_PREVIEW_LINE
                    )
                }
                // B3 (#8): a compressed page is shown smaller, on the ink surface, so the measured
                // numbers above it stay the headline instead of a big white preview.
                isCompressResult -> {
                    background = SwStyle.rounded(
                        this@ToolResultActivity,
                        SwStyle.color(this@ToolResultActivity, R.color.void_abyss),
                        radiusDp = 16f,
                        stroke = SwStyle.color(this@ToolResultActivity, R.color.sw_cyan_hair)
                    )
                }
                else -> {
                    background = SwStyle.rounded(
                        this@ToolResultActivity,
                        SwStyle.color(this@ToolResultActivity, R.color.glass_surface),
                        radiusDp = 16f,
                        stroke = SwStyle.color(this@ToolResultActivity, R.color.sw_cyan_hair)
                    )
                }
            }
            clipToOutline = true
        }
        preview = ImageView(this).apply {
            id = R.id.toolResultPreview
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!idCardFlow && !isCompressResult) setBackgroundColor(Color.WHITE)
            contentDescription = getString(R.string.tools_result_preview)
        }
        previewHost.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        nameView = SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.toolResultName
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        summary = SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            id = R.id.toolResultSummary
            setLineSpacing(0f, 1.25f)
        }

        // Outcome first. The receipt remains readable body text below the measured headline.
        column.addView(summary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 12f) })
        column.addView(nameView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })
        // V2T T1: one accessible status line, derived from actual library state.
        v2tStatus = SwStyle.text(this, 13f, SwStyle.color(this, R.color.rv_accent)).apply {
            id = R.id.v2t_status
            maxLines = Int.MAX_VALUE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getString(R.string.v2t_status_label)
        }
        column.addView(v2tStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 6f) })
        column.addView(previewHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolResultActivity, if (isCompressResult) 140f else 200f)
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 14f) })

        // Lane C: one Before | After surface. Added after the existing preview so no current
        // control or id moves; it stays GONE for results with no compare proof.
        compareView = CompareView(this).apply {
            id = R.id.ccCompare
            visibility = View.GONE
        }.also { view ->
            column.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 14f) }
            )
        }

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.toolResultError
            isVisible = false
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })

        if (idCardFlow) {
            // F1/W3: the card is already saved, so there is nothing to Save and nothing to Undo.
            // Two clear actions: Keep (done) and Discard (delete this one card).
            column.addView(
                button(getString(R.string.fixflow_keep), tag = TAG_KEEP, primary = true) { finish() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 16f) }
            )
            column.addView(
                button(getString(R.string.discard), tag = TAG_DISCARD) { discardIdCard() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
            )
            return root
        }

        // L3-02: Open is the one filled decision, Share is secondary. They sit side by side with
        // an 8dp gap while both labels fit; at 200% type they stack so neither is squeezed.
        val open = button(getString(R.string.tools_result_open), R.id.toolResultOpen, primary = true, icon = R.drawable.ic_sym_folder_open) { open() }
        val share = button(getString(R.string.tools_result_share), R.id.toolResultShare, icon = R.drawable.ic_sym_share) { share() }
        if (pairPrimaryActions()) {
            val pair = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.addView(open, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = SwStyle.dp(this@ToolResultActivity, 8f) })
            pair.addView(share, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            column.addView(
                pair,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 16f) }
            )
        } else {
            column.addView(
                open,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 16f) }
            )
            column.addView(
                share,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
            )
        }
        // UI-B3 (#12): "Also save in Documents" stays plainly visible for every result,
        // including a compress that missed its target (T05-RESULT-SLICE). It is added below.
        column.addView(
            button(getString(R.string.tools_result_also_save), R.id.toolResultAlsoSave, icon = R.drawable.ic_sym_download) { alsoSave() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
        )
        // H7: an exported file (Word, Excel, PowerPoint, images, ZIP) is not a library document
        // and the Library can never list it, so "Show in library" went to a screen where the
        // result was missing. The export lives in the app's own output folder until the user
        // Saves it somewhere, so the smaller honest change is to drop the button, not to point
        // it at another empty folder.
        val library = button(getString(R.string.tools_result_library), R.id.toolResultLibrary) {
            showInLibrary()
        }
        if (isExportOnlyResult()) library.visibility = View.GONE
        val morePanel = LinearLayout(this).apply {
            id = R.id.toolResultMorePanel
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        // Lane CR: the Replace entry point only. Dark by default; the sheet and
        // the coordinator own the safety work when the flag is on.
        if (replaceMayBeOffered()) {
            morePanel.addView(
                button(getString(R.string.cr_replace_confirm), id = R.id.cr_replace_entry,
                    icon = R.drawable.ic_sw_arrange) { offerReplace() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
            )
        }
        morePanel.addView(
            button(getString(R.string.tools_result_choose_location), R.id.toolResultSave) { save() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        morePanel.addView(
            library,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        // V2T T2: secondary actions on the verified OUTPUT copy. Open stays primary.
        morePanel.addView(
            button(getString(R.string.v2t_compress_this_copy), id = R.id.v2t_compress_copy,
                icon = R.drawable.ic_sym_compress) { chainCompress() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        morePanel.addView(
            button(getString(R.string.v2t_protect_this_copy), id = R.id.v2t_protect_copy,
                icon = R.drawable.ic_sym_encrypted) { chainProtect() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        val more = textAction(getString(R.string.tools_result_more_options)).apply {
            id = R.id.toolResultMore
            setOnClickListener {
                val opening = morePanel.visibility != View.VISIBLE
                morePanel.visibility = if (opening) View.VISIBLE else View.GONE
                text = getString(
                    if (opening) R.string.tools_result_fewer_options
                    else R.string.tools_result_more_options
                )
            }
        }
        column.addView(
            more,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
        )
        column.addView(
            morePanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        // L3-02: the output removal is named ("Remove this new copy") and its exact
        // consequence is stated above the action, before the person taps it. The
        // original is never a candidate here (B2-H1 / T10-FIX-PDFA-UNDO).
        column.addView(
            SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                text = getString(R.string.ux130_l3_result_remove_copy_consequence)
                setLineSpacing(0f, 1.2f)
                maxLines = Int.MAX_VALUE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
        // L3-02 (lead-authorized): one label for the same action everywhere,
        // including compress results without provenance. One-tap removal stays.
        val undoLabel = getString(R.string.ux130_l3_result_remove_copy)
        column.addView(
            button(undoLabel, R.id.toolResultUndo, icon = R.drawable.ic_sym_undo) { undo() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) }
        )
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

    // ---- Lane CR: Replace original entry point --------------------------

    /** Dark by default; only one proven library input/output can offer Replace. */
    private fun replaceMayBeOffered(): Boolean {
        if (!ReplaceFeature.isEnabled() || idCardFlow) return false
        val source = receiptSourceId ?: return false
        val output = docIds.singleOrNull() ?: return false
        return files.isEmpty() && repo.replaceIsEligible(source, output, toolId)
    }

    private fun offerReplace() {
        val source = receiptSourceId ?: return
        val output = docIds.singleOrNull() ?: return
        val name = runCatching { repo.getDocument(source)?.name }.getOrNull().orEmpty()
        ReplaceSheet.show(this) { performReplace(source, output, name) }
    }

    private fun performReplace(source: String, output: String, name: String) {
        val entry: View? = findViewById(R.id.cr_replace_entry)
        entry?.isEnabled = false
        clearFail()
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) {
                ReplaceCoordinator(this@ToolResultActivity, repo).replace(source, output, toolId, name)
            }
            entry?.isEnabled = true
            key.onFailure {
                fail(getString(R.string.cr_replace_refused))
            }.onSuccess { trashKey ->
                Toast.makeText(this@ToolResultActivity,
                    getString(R.string.cr_replace_done), Toast.LENGTH_LONG).show()
                com.google.android.material.snackbar.Snackbar.make(
                    rootView, R.string.cr_replace_undo,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).setAction(R.string.cr_replace_undo) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            ReplaceCoordinator(this@ToolResultActivity, repo).undoByTrashKey(trashKey)
                        }
                    }
                }.show()
            }
        }
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
        val docs = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
        if (docs.isNotEmpty()) {
            val pages = docs.sumOf { it.pageCount }
            val bytes = docs.sumOf { if (it.pdfFile.exists()) it.pdfFile.length() else 0L }
            nameView.text = if (docs.size == 1) {
                docs.first().name
            } else {
                getString(R.string.tools_result_name_more, docs.first().name, docs.size - 1)
            }
            var line = resources.getQuantityString(R.plurals.tools_result_pages, pages, pages)
            if (docs.size > 1) {
                line += " · " + resources.getQuantityString(
                    R.plurals.tools_result_documents, docs.size, docs.size
                )
            }
            line = getString(R.string.compress_result_size, StatusLine.formatBytes(bytes)) + "\n" + line
            if (isCompressResult) {
                applyCompressSummary(bytes)
            } else {
                applySummary(line)
            }
            previewPreview(docs.first().pageFiles.firstOrNull())
        } else if (files.isNotEmpty()) {
            val bytes = files.sumOf { it.length() }
            nameView.text = if (files.size == 1) {
                files.first().name
            } else {
                getString(R.string.tools_result_name_more, files.first().name, files.size - 1)
            }
            var line = resources.getQuantityString(R.plurals.tools_result_files_count, files.size, files.size)
            line = getString(R.string.compress_result_size, StatusLine.formatBytes(bytes)) + "\n" + line
            applySummary(line)
            val first = files.first()
            if (isTypedFile(first)) {
                // The type card already names this artifact; do not repeat that name outside it.
                nameView.isVisible = files.size > 1
                showFileCard(first)
            } else {
                preview.isVisible = true
                val image = files.firstOrNull { it.name.endsWith(".jpg") || it.name.endsWith(".png") }
                previewPreview(image)
                if (image == null) preview.setImageResource(iconFor(first))
            }
        } else {
            nameView.text = ""
            applySummary(getString(R.string.tools_result_nothing))
        }
        refreshLocationLines()
        renderV2tStatus()
        bindCompare()
    }

    /** V2T T1: the status is only a claim we can verify against the library. */
    private fun renderV2tStatus() {
        val view = v2tStatus ?: return
        val source = receiptSourceId
        when {
            source == null -> view.isVisible = false
            repo.getDocument(source) != null -> {
                view.isVisible = true
                view.text = getString(R.string.v2t_new_copy_original_kept)
                view.contentDescription = view.text
            }
            else -> {
                view.isVisible = true
                view.text = getString(R.string.v2t_original_missing)
                view.contentDescription = view.text
            }
        }
    }

    private fun applySummary(line: String) {
        summaryBaseLine = if (note.isNullOrBlank()) line else "$line\n$note"
        renderSummary()
    }

    private fun composeSummary(): String = buildString {
        append(
            if (isCompressResult && summaryBaseLine == null) compressSummaryText()
            else summaryBaseLine.orEmpty()
        )
        for (line in locationLines) {
            if (line.isNotBlank()) append("\n").append(line)
        }
    }

    private fun renderSummary() {
        val text = composeSummary()
        val end = text.indexOf('\n').let { if (it < 0) text.length else it }
        summary.text = android.text.SpannableString(text).apply {
            setSpan(android.text.style.RelativeSizeSpan(24f / 14f), 0, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * W3: the persistent receipt. A copy made from a library document lives in the Library; an
     * exported file lives in the app until it is saved; "Also save" and the system picker add the
     * exact place they wrote to. Only lines that are true for this result are shown.
     */
    private fun refreshLocationLines() {
        val next = mutableListOf<String>()
        if (docIds.isNotEmpty()) {
            val docs = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
            if (docs.isNotEmpty()) {
                next += getString(R.string.u4_where_library)
            }
        } else if (files.isNotEmpty()) {
            next += getString(R.string.w3_where_app_output)
        }
        savedCopy?.let { next += getString(R.string.w3_where_documents, it.name) }
        documentsExistingName?.let {
            next += getString(R.string.w3_where_documents_existing, it)
        }
        chosenLocationName?.let { next += getString(R.string.w3_where_chosen, it) }
        if (next != locationLines) {
            locationLines.clear()
            locationLines.addAll(next)
            renderSummary()
        }
    }

    /**
     * UI-B3 (#8): a Compress result answers the question it was asked, in this order:
     *
     * 1. the measured size, plus the target status only when the bytes prove it
     *    ("9 KB · Under your 10 KB limit", "... Exactly at ..." when equal, "132 KB · Above ..."
     *    when the smallest honest result is still bigger); with no target, the measured size
     *    alone, and no status line at all;
     * 2. "Before: 824 KB", when the source size is known;
     * 3. where the original is, for a copy made from a library document.
     *
     * A result whose PDF cannot be measured never claims a size or a success.
     */
    private fun applyCompressSummary(measuredBytes: Long?) {
        measuredSummaryBytes = measuredBytes
        summaryBaseLine = null
        renderSummary()
    }

    private fun compressSummaryText(): String = buildString {
        append(compressOutcome(measuredSummaryBytes))
        beforeBytes?.let {
            append("\n").append(
                getString(R.string.compress_result_before, StatusLine.formatBytes(it))
            )
        }
        if (sourceKept) {
            // V2T T1: a sourced result uses the lane's consistent wording; the
            // legacy boolean-only callers keep the old line (existing contract).
            val line = if (receiptSourceId != null) {
                getString(R.string.v2t_new_copy_original_kept)
            } else {
                getString(R.string.compress_result_source_kept)
            }
            append("\n").append(line)
        }
        if (!note.isNullOrBlank()) append("\n").append(note)
    }

    private enum class SizeRelation { BELOW, EQUAL, ABOVE }

    private fun compressOutcome(measuredBytes: Long?): String {
        val measured = measuredBytes?.takeIf { it > 0L }
            ?: return getString(R.string.compress_result_size_unknown)
        val target = targetBytes
            ?: return getString(R.string.compress_result_size, StatusLine.formatBytes(measured))
        // W3: exact bytes decide; the displayed number must never contradict that verdict.
        val relation = when {
            measured < target -> SizeRelation.BELOW
            measured > target -> SizeRelation.ABOVE
            else -> SizeRelation.EQUAL
        }
        val shown = measuredForDisplay(measured, target, relation)
        val targetShown = StatusLine.formatBytes(target)
        return when (relation) {
            SizeRelation.BELOW -> getString(R.string.compress_result_under_limit, shown, targetShown)
            SizeRelation.EQUAL -> getString(R.string.compress_result_at_limit, shown, targetShown)
            SizeRelation.ABOVE -> getString(R.string.compress_result_over_limit, shown, targetShown)
        }
    }

    /**
     * Whole-KB rounding can print a file one byte over 100 KB as "100 KB", which would read
     * "100 KB · Above your 100 KB limit". When the rounded size would collide with the target,
     * the exact byte count is shown next to it, so the sentence cannot contradict itself.
     */
    private fun measuredForDisplay(measured: Long, target: Long, relation: SizeRelation): String {
        val shown = StatusLine.formatBytes(measured)
        if (relation == SizeRelation.EQUAL) return shown
        return if (shown == StatusLine.formatBytes(target)) {
            getString(R.string.w3_size_with_exact_bytes, shown, measured)
        } else {
            shown
        }
    }

    // ---- Lane C: Before | After -----------------------------------------

    /** The tools whose proof is a marked page 1 plus "1 mark added" (council §6). */
    private val markTools = setOf(
        "sign", "add_text", "draw", "highlight", "stamp", "watermark", "page_numbers"
    )

    private fun bindCompare() {
        val view = compareView ?: return
        val explicit = intent.getStringExtra(EXTRA_COMPARE_OUTCOME)
        val model = when {
            !explicit.isNullOrBlank() -> explicitCompareModel(explicit)
            isCompressResult -> compressCompareModel()
            toolId in markTools -> markCompareModel()
            else -> null
        }
        if (model == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.bind(model, lifecycleScope)
    }

    /** Measured proof supplied by a producer (OCR words, removed fields, page counts, ...). */
    private fun explicitCompareModel(outcome: String): CompareModel {
        fun pane(captionKey: String, imageKey: String, detailKey: String, missing: Int): ComparePane {
            val path = intent.getStringExtra(imageKey)?.takeIf { it.isNotBlank() }
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
            showToggle = intent.getBooleanExtra(EXTRA_COMPARE_SHOW_TOGGLE, true)
        )
    }

    /**
     * Compress uses the bytes we actually produced and the source document's first page as the
     * real before image. A locked/vaulted source is never decoded to plaintext here.
     */
    private fun compressCompareModel(): CompareModel {
        val after = measuredSummaryBytes ?: docIds.firstOrNull()
            ?.let { id -> runCatching { repo.getDocument(id) }.getOrNull() }
            ?.pdfFile?.let { if (it.isFile) it.length() else 0L }?.takeIf { it > 0L }
        val afterSize = after?.let { StatusLine.formatBytes(it) }
        val beforeSize = beforeBytes?.takeIf { it > 0L }?.let { StatusLine.formatBytes(it) }
        val source = receiptSourceId?.let { id -> runCatching { repo.getDocument(id) }.getOrNull() }
            ?.takeIf { !it.isLocked && !it.isVaulted }
        val beforeImage = source?.pageFiles?.firstOrNull()?.takeIf { it.isFile }?.path
        val afterImage = docIds.firstOrNull()
            ?.let { id -> runCatching { repo.getDocument(id) }.getOrNull() }
            ?.pageFiles?.firstOrNull()?.takeIf { it.isFile }?.path
        return CompareModel(
            outcome = compressOutcome(after),
            before = ComparePane(
                imagePath = beforeImage,
                caption = beforeSize?.let { getString(R.string.cc_before_size, it) }
                    ?: getString(R.string.cc_no_numbers),
                emptyText = if (beforeImage == null) getString(R.string.cc_no_before_image) else null
            ),
            after = ComparePane(
                imagePath = afterImage,
                caption = afterSize?.let { getString(R.string.cc_after_size, it) }
                    ?: getString(R.string.cc_no_numbers),
                emptyText = if (afterImage == null) getString(R.string.cc_no_after_image) else null
            ),
            pageLabel = getString(R.string.cc_page, 1)
        )
    }

    /** Page tools: the result page plus the honest "1 mark added" caption. */
    private fun markCompareModel(): CompareModel {
        val afterImage = docIds.firstOrNull()
            ?.let { id -> runCatching { repo.getDocument(id) }.getOrNull() }
            ?.pageFiles?.firstOrNull()?.takeIf { it.isFile }?.path
        return CompareModel(
            outcome = getString(R.string.cc_mark_added),
            before = ComparePane(
                caption = getString(R.string.cc_no_before_image),
                emptyText = getString(R.string.cc_no_before_image)
            ),
            after = ComparePane(
                imagePath = afterImage,
                caption = getString(R.string.cc_after_mark),
                emptyText = if (afterImage == null) getString(R.string.cc_no_after_image) else null
            ),
            pageLabel = getString(R.string.cc_page, 1)
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
        val meta = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
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
                SavedSnackbar.showExport(
                    this@ToolResultActivity, rootView, saved, R.string.result_saved_documents, docId
                ) { result ->
                    // W3: if the bar's Undo removed the copy, the screen's receipt must stop
                    // claiming it is there.
                    if (result == DownloadSaver.UndoResult.REMOVED) {
                        savedCopy = null
                        documentsExistingName = null
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
            compareShowToggle: Boolean = true
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
