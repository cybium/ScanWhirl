package com.scanwhirl.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityDocumentViewerBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.DraftStore
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.tools.FlowUtil
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.OcrFlow
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.ui.kit.ProOffer
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.ui.tools.PhotoCropFlowActivity
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SavedSnackbar
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class DocumentViewerActivity : BaseActivity(), HomeHost {

    private lateinit var binding: ActivityDocumentViewerBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }

    // test26 T5.2 (§4.5): the shared tool flows, hosted here (HomeHost).
    private val ocrFlow: OcrFlow by lazy { OcrFlow(activity = this, repo = repo, host = this) }
    private val compressFlow: CompressFlow by lazy {
        CompressFlow(
            activity = this, repo = repo, host = this,
            // Unreachable from startWith(): this screen already shows the scan.
            pickPdf = {},
            docs = { listOfNotNull(document) }
        )
    }
    private var document: ScanDocument? = null
    private var docId: String? = null

    /** The draft's page list (a replacement file where an edit wrote one, else the base page). */
    private var pages: List<java.io.File> = emptyList()

    /** v2 UI: 07_reader_dock — bytes of the document as stored, for the meta line. */
    private var docBytes = 0L

    /** The open page draft, or null. The first page edit creates it; opening writes nothing. */
    private var draft: DraftStore.Draft? = null

    /** Pages shown in the grid (document pages minus any pending Undo deletes). */
    private var visiblePages: List<java.io.File> = emptyList()

    /** Checked page paths; page actions in the toolbar menu act on these. */
    private val selected = linkedSetOf<String>()

    /**
     * Paths whose Undo snackbar is showing. They are hidden from the grid and
     * only deleted once the snackbar times out, is replaced, or this screen
     * closes (leaving = permanent).
     */
    private val pendingDeletes = linkedSetOf<String>()

    /** Pages whose delete has been triggered but not landed yet (no flash-back). */
    private val committingDeletes = mutableSetOf<String>()

    /** True once onCreate has finished its async first load. */
    private var firstLoad = false

    /** F5/W2: this viewer is the first look at a just-imported document. */
    private var freshImport = false

    /** F5/W2: the import question was already shown. A failed move allows a retry. */
    private var freshImportAsked = false
    private var importTrashInProgress = false

    /**
     * T44: the user has acted on this import (saved a copy to Documents, shared it, or changed the
     * document). It is theirs now: Back never offers to delete it, in line with the library rule
     * that a document the user worked with is never silently thrown away.
     */
    private var importKept = false

    /** Not lifecycle-bound: a pending delete must land even during onDestroy. */
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var pendingDownload: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
            pendingDownload = null
        }

    // Insert pages (Free, Organize): camera pages, photos or another library document.
    private val insertScanner =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages.orEmpty().mapNotNull { it.imageUri }
            if (uris.isEmpty()) {
                toast(R.string.insert_none, Toast.LENGTH_SHORT)
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val files = withContext(Dispatchers.IO) {
                    runCatching { copyInsertUris(uris) }.getOrNull()
                }
                if (files.isNullOrEmpty()) {
                    toast(R.string.insert_none, Toast.LENGTH_SHORT)
                } else {
                    askInsertPosition(files)
                }
            }
        }

    private val insertPhotos =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            // R1: the batch folder is owned from the moment it exists on disk, not after the
            // copy returns; a failed or abandoned copy must not leave the picked photos behind.
            val batch = InsertPhotoBatch(
                File(cacheDir, "insert_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
            )
            pendingPhotoBatch = batch
            lifecycleScope.launch {
                val files = try {
                    withContext(Dispatchers.IO) {
                        try {
                            copyInsertUris(uris, batch.dir)
                        } catch (e: Exception) {
                            null
                        } finally {
                            // The screen may have gone away (Back/rotation/finish) while this
                            // blocking copy was still running: the worker itself guarantees the
                            // folder is gone once it has finished writing.
                            if (batch.abandoned) batch.dir.deleteRecursively()
                        }
                    }
                } catch (e: CancellationException) {
                    // Cancelled with the screen; the worker's finally did the cleanup.
                    throw e
                }
                if (files.isNullOrEmpty()) {
                    batch.dir.deleteRecursively()
                    if (pendingPhotoBatch === batch) pendingPhotoBatch = null
                    if (!batch.abandoned) toast(R.string.insert_none, Toast.LENGTH_SHORT)
                    return@launch
                }
                if (batch.abandoned || pendingPhotoBatch !== batch) {
                    // The screen went away before the copy finished: keep nothing.
                    batch.dir.deleteRecursively()
                    return@launch
                }
                // A8: a picked gallery photo is not a scan. The position is asked here (as
                // for camera/PDF inserts) but the pages are cropped and confirmed per photo
                // first; only then does the draft change.
                askInsertPosition(files, ::startPhotoCrop, onAbandon = {
                    deleteBatch(batch.dir)
                    if (pendingPhotoBatch === batch) pendingPhotoBatch = null
                })
            }
        }

    /**
     * R1: one picked-photo batch. [handedOff] means the corner crop flow owns it (the viewer
     * must never delete it then); [abandoned] means the screen went away while the copies were
     * still being written, so the copy worker deletes the folder when it finishes.
     */
    private class InsertPhotoBatch(val dir: File) {
        @Volatile var handedOff = false
        @Volatile var abandoned = false
    }

    /** Position chosen for the photo batch currently on the corner crop screen. */
    private var pendingPhotoPosition: Int? = null

    /** How many photos that batch has; every one must come back confirmed. */
    private var pendingPhotoCount: Int? = null

    /** The picked-photo batch of the flow, from the copy until the draft has it. */
    private var pendingPhotoBatch: InsertPhotoBatch? = null

    /** Tests: hold the picked-photo copy so a finish/rotation can race it. Null in production. */
    @Volatile
    internal var photoCopyGateForTest: ((Int) -> Unit)? = null

    /** Back from the corner screen returns RESULT_CANCELED: the batch inserts nothing (A8). */
    private val insertPhotoCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val batch = pendingPhotoBatch
            val position = pendingPhotoPosition
            val count = pendingPhotoCount
            pendingPhotoBatch = null
            pendingPhotoPosition = null
            pendingPhotoCount = null
            val cropped = if (result.resultCode == RESULT_OK) {
                result.data?.getStringArrayListExtra(PhotoCropFlowActivity.EXTRA_CROPPED)
                    .orEmpty().map(::File).filter { it.isFile }
            } else {
                emptyList()
            }
            // D1: a batch is inserted only when every picked photo came back confirmed, in one
            // piece; a short result (an abandoned or failed crop) inserts nothing.
            if (position == null || count == null || cropped.size != count) {
                batch?.dir?.deleteRecursively()
                return@registerForActivityResult
            }
            insertFiles(cropped, position)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // The document's own name is the header H1 (it wraps, never an ellipsis at 200 %);
        // the toolbar keeps only Back and the brand mark.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationOnClickListener { handleEditorBack() }

        docId = intent.getStringExtra(EXTRA_DOC_ID)
        freshImport = intent.getBooleanExtra(EXTRA_FRESH_IMPORT, false)
        // T44: a rotation must not resurrect the delete question after Keep/Discard, nor undo the
        // fact that the user already acted on this import.
        freshImportAsked = savedInstanceState?.getBoolean(KEY_IMPORT_ASKED) ?: false
        importKept = savedInstanceState?.getBoolean(KEY_IMPORT_KEPT) ?: false
        // A8: a viewer recreated while the corner crop screen was on top still knows where the
        // confirmed photos go when they come back. A batch whose position was never chosen dies
        // with the old screen (the question is gone), so its picked copies are not kept.
        val restoredPosition = savedInstanceState
            ?.takeIf { it.containsKey(KEY_PHOTO_INSERT_AT) }
            ?.getInt(KEY_PHOTO_INSERT_AT)
        val restoredCount = savedInstanceState
            ?.takeIf { it.containsKey(KEY_PHOTO_COUNT) }
            ?.getInt(KEY_PHOTO_COUNT)
        pendingPhotoPosition = restoredPosition
        pendingPhotoCount = restoredCount
        pendingPhotoBatch = savedInstanceState?.getString(KEY_PHOTO_DIR)?.let { path ->
            // Restored means the corner crop flow owns it: this screen must not delete it.
            InsertPhotoBatch(File(path)).apply { handedOff = true }
        }
        if (restoredPosition == null || restoredCount == null) {
            pendingPhotoBatch?.dir?.deleteRecursively()
            pendingPhotoBatch = null
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleEditorBack()
        })

        binding.pageList.onTap = { index ->
            if (selectMode) {
                toggleSelected(index)
            } else {
                docId?.let { id ->
                    startActivity(PageViewerActivity.intent(this, id, index))
                }
            }
        }
        binding.pageList.onCheckToggle = { index -> toggleSelected(index) }
        binding.pageList.onLongPress = { index -> enterSelection(index) }
        binding.pageList.checkedAt = { i ->
            selected.contains(visiblePages.getOrNull(i)?.path)
        }
        binding.pageList.onDropped = { from, to -> reorderPages(from, to) }

        // UI-B6 (GPT judgement 2026-11-19b, item 1): the document screen is the workspace.
        // Seven named actions, each bound to the document this screen shows:
        //  - Edit / crop, Read text and Share keep their pre-B6 handlers and ids;
        //  - Compress runs the SAME guarded handler the Actions sheet runs
        //    (CompressFlow.startWith(doc), no source chooser);
        //  - Sign goes through ProOffer.require, then the stamp editor armed with Sign for
        //    THIS id — never the generic picker and never a trial promise;
        //  - Save PDF is the one save-to-Documents copy (the icon-only toolbar route is gone);
        //  - More is the named Actions sheet.
        // A locked or Vault document keeps Compress, Sign and Read text disabled with the
        // sheet's own reason (applyActionAvailability): no handler is reachable from them.
        binding.btnShare.setOnClickListener {
            document?.let { doc ->
                traceAction("share", doc)
                ProtectionDialogs.guardExport(this, repo, doc, onResolved = { lifecycleScope.launch { reloadDocument() } }) {
                    // W1: the id decides, not the screen's transient handle; a draft asks first and
                    // "Use saved version" hands over the saved document's own bytes.
                    DocumentActionDispatcher.dispatch(
                        activity = this,
                        repo = repo,
                        docId = doc.id,
                        action = DocumentActionDispatcher.Action.SHARE,
                        onUsed = { _ -> noteImportUsed() }
                    ) { bound -> ShareUtils.share(this, bound) }
                }
            }
        }
        binding.btnEdit.setOnClickListener {
            document?.let { doc -> traceAction("edit", doc) }
            docId?.let { id -> startActivity(PageViewerActivity.intent(this, id, 0)) }
        }
        binding.btnText.setOnClickListener { runTextFlow() }
        binding.btnCompress.setOnClickListener { compressFromPanel() }
        binding.btnSign.setOnClickListener { signFromPanel() }
        binding.btnSavePdf.setOnClickListener { savePdfToDocuments() }
        // The named Actions sheet, bound to this document by id. The sheet owns every
        // secondary action (rename/move/duplicate/protect, Trash) and reloads this screen on
        // a change, or closes it when the document leaves.
        binding.btnMore.setOnClickListener {
            val id = docId ?: return@setOnClickListener
            document?.let { doc -> traceAction("more", doc) }
            DocumentActionsSheet.show(
                this,
                id,
                onChange = { lifecycleScope.launch { reloadDocument() } },
                onGone = { if (!isFinishing) finish() },
                // W1: a sheet action the user started on a fresh import counts as acting on it,
                // and "Finish editing first" must not stack a second document screen here.
                onUsed = { noteImportUsed() },
                onFinishEditing = {}
            )
        }
        applyActionPanel()
        // T4.6: the header category chip is tappable now — it opens the
        // category picker (the old "Categorize" list dialog is gone).
        // v2 UI: 07_reader_dock — the 48 dp container is the tap target; the pill itself is
        // only 32 dp tall, so the visible chip can stay small and calm.
        binding.docCategoryTouch.setOnClickListener { promptCategorize() }
        // Selection mode (owner revision): long-press enters it; the top bar counts,
        // closes and selects all; the bottom bar acts on the ticked pages.
        binding.btnSelClose.setOnClickListener { exitSelectMode() }
        binding.btnSelAll.setOnClickListener { selectAllPages() }
        binding.btnSelRotate.setOnClickListener { rotateSelected(clockwise = true) }
        binding.btnSelDelete.setOnClickListener { confirmDeleteSelected() }
        binding.btnSelExtract.setOnClickListener { extractSelected() }
        binding.btnSelShare.setOnClickListener { shareSelectedPages() }
        binding.btnSelMore.setOnClickListener { showSelectionMore() }
        binding.btnEmptyAddPages.setOnClickListener { startInsertPages() }
        styleSelectionBars()
        // PM-2.2c: the draft bar appears after the first edit; every action is explicit.
        styleDraftBar()
        binding.btnDraftDiscard.setOnClickListener { discardFlow() }
        binding.btnDraftReplace.setOnClickListener { replaceFlow() }
        binding.btnDraftSave.setOnClickListener { saveDraftFlow() }
        // Rename lives in the Actions sheet (the name row is a heading, not a button).
        // The toolbar carries no menu: Save PDF is the panel's labelled action, exactly once.
        // Checkboxes stay hidden until Select pages is tapped (§4.5, D-D).
        binding.pageList.checkboxesVisible = false

        // First load (off the main thread). Opening a document writes no draft (G14).
        lifecycleScope.launch {
            if (!reloadDocument()) return@launch
            firstLoad = true
            if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_INSERT_PAGES, false)) {
                startInsertPages()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IMPORT_ASKED, freshImportAsked)
        outState.putBoolean(KEY_IMPORT_KEPT, importKept)
        pendingPhotoPosition?.let { outState.putInt(KEY_PHOTO_INSERT_AT, it) }
        pendingPhotoCount?.let { outState.putInt(KEY_PHOTO_COUNT, it) }
        // R1: only a batch the crop flow already owns is worth restoring; a copy in flight is
        // cancelled by the recreation and cleaned up by its worker.
        pendingPhotoBatch?.takeIf { it.handedOff }?.dir
            ?.let { outState.putString(KEY_PHOTO_DIR, it.path) }
    }

    override fun onDestroy() {
        // Leaving the screen makes any pending delete permanent (rule: Undo
        // only lives as long as this screen).
        if (!isChangingConfigurations) {
            commitPendingDelete()
        }
        // R1: a picked batch is never left behind. A copy still in flight marks the batch
        // abandoned (its worker deletes the folder when it has finished writing); a batch the
        // corner crop flow owns is not this screen's to delete.
        pendingPhotoBatch?.let { batch ->
            if (!batch.handedOff) {
                batch.abandoned = true
                batch.dir.deleteRecursively()
            }
        }
        pendingPhotoBatch = null
        pendingPhotoPosition = null
        pendingPhotoCount = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // AppCompat re-syncs the toolbar title from the manifest label during resume; the
        // toolbar hides its title (the header H1 carries the document name), but the Pro word
        // on Sign must follow a purchase made while this screen was away.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        document?.let { doc ->
            binding.docName.text = doc.name
            applySignLabel()
            applyActionAvailability(doc)
        }
        // Returning from the full-screen page view: a draft edit may have been committed.
        if (document != null && firstLoad) {
            lifecycleScope.launch { reloadDocument() }
        }
    }

    /** Re-reads the document from disk and refreshes all UI. Returns false if it vanished. */
    private suspend fun reloadDocument(): Boolean {
        val id = docId ?: return false
        // One pass: the document, its pages and its draft share one manifest read (L5-2).
        val state = withContext(Dispatchers.IO) { repo.draftState(id) }
        val doc = state.document
        if (doc == null) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        document = doc
        document = doc
        // SECURITY_AUDIT 2026-11-15: protected/Vault documents never appear in a
        // screenshot or the recents card; ordinary scans follow the user's setting.
        com.scanwhirl.app.util.SecureView.applyForDocument(
            this, window, doc.isVaulted || doc.isLocked
        )
        pages = state.pages
        draft = state.draft
        val damage = state.damage
        // T44: the record, not only this screen's flag, decides whether the import is the user's.
        // A saved copy in Documents/ScanWhirl or any page change means the import was acted on.
        if (freshImport && !importKept) {
            val recorded = runCatching { repo.exportRecordsOf(doc.id) }.getOrDefault(emptyList())
            if (recorded.isNotEmpty() || (draft?.changes ?: 0) > 0) importKept = true
        }
        if (damage != null) {
            // Never invent pages from a damaged manifest: show the base and offer Discard only.
            showDraftUnusable(damage.message ?: getString(R.string.draft_damaged))
        }
        binding.docName.text = doc.name
        // v2 UI: 07_reader_dock — the meta line carries the size as well as the page count.
        docBytes = withContext(Dispatchers.IO) { docSizeBytes(doc) }
        binding.docLockBadge.isVisible = doc.isLocked
        binding.docVaultBadge.isVisible = doc.isVaulted
        // test25 Phase 5C: category tag in the header (recategorise funnels
        // through here too — see promptCategorize()).
        // v2 UI: 07_reader_dock — a compact tonal pill: 8 dp dot in the category's colour,
        // sentence-case 13 sp label; the 48 dp container around it is the tap target and
        // carries the spoken label, so the pill never has to be a 48 dp box.
        val categoryLabel = com.scanwhirl.app.util.CategoryLabels.display(this, doc.category)
        binding.docCategoryLabel.text = categoryLabel
        binding.docCategoryTouch.contentDescription =
            getString(R.string.doc_category_action, categoryLabel)
        binding.docCategoryDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            com.scanwhirl.app.util.CategoryStyle.color(doc.category)
        )
        binding.docCategoryChip.isVisible = true
        binding.docCategoryTouch.isVisible = true
        refreshGrid()
        updateDraftBar()
        applyActionAvailability(doc)
        binding.btnShare.isVisible = doc.pdfFile.exists() || doc.pageFiles.isNotEmpty()
        return true
    }

    /** Pushes the visible page list (minus pending deletes) to the grid. */
    private fun refreshGrid() {
        visiblePages = pages.filter {
            it.path !in pendingDeletes && it.path !in committingDeletes
        }
        // Pages that vanished (deleted/screen closed) must not stay checked.
        selected.retainAll(visiblePages.map { it.path }.toSet())
        binding.pageList.submit(visiblePages)
        binding.gridEmpty.isVisible = visiblePages.isEmpty()
        binding.pageList.isVisible = visiblePages.isNotEmpty()
        // v2 UI: 07_reader_dock — "3 pages · 544 KB", the same shape as a library row.
        binding.pageCount.text = resources.getQuantityString(
            R.plurals.viewer_meta,
            visiblePages.size,
            visiblePages.size,
            StatusLine.formatBytes(docBytes)
        )
        updateSelectionUi()
    }

    /**
     * UI-B6 (GPT judgement 2026-11-19b, item 1): the document workspace panel.
     *
     * Seven NAMED actions, each its own labelled 48 dp+ target:
     * Edit / crop · Read text · Share · Compress · Sign (+ Pro) · Save PDF · More.
     *
     *  - 4 + 3 on a 360 dp phone; 3 + 3 + 1 on a 320 dp phone (a 320 dp quarter-width cell
     *    cannot hold "Read text"/"Compress" at 130 % without breaking a word);
     *  - from 160 % type the panel moves into the scroll flow ABOVE the pages as two growing
     *    columns (the last action keeps the full width), inside the one scroll owner;
     *  - labels wrap but are never ellipsized, and no label is ever shrunk below 12 sp.
     */
    private fun applyActionPanel() {
        applyActionLabels()
        val fontScale = resources.displayMetrics.scaledDensity / resources.displayMetrics.density
        val large = fontScale >= 1.6f
        val narrow = resources.configuration.screenWidthDp < 360
        val columns = when {
            large -> 2
            narrow -> 3
            else -> 4
        }
        val items = listOf(
            binding.btnEdit,
            binding.btnText,
            binding.btnShare,
            binding.btnCompress,
            binding.btnSign,
            binding.btnSavePdf,
            binding.btnMore
        )
        val grid = binding.actionGrid
        grid.removeAllViews()
        items.chunked(columns).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = dp(6f) }
            )
            chunk.forEachIndexed { index, item ->
                val params = if (large && chunk.size == 1) {
                    // One action left in a two-column grid grows across both columns.
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                } else {
                    LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { if (index > 0) marginStart = dp(6f) }
                }
                row.addView(item, params)
            }
        }
        // At 200 % the meta line stacks (pages/size above the chip), as the concept does. The
        // page line takes the whole width when stacked: a 0 dp weighted cell in a VERTICAL row
        // would measure to nothing and the "2 pages · 10 KB" line would disappear.
        val stacked = large
        binding.docMetaRow.orientation =
            if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        binding.pageCount.layoutParams = if (stacked) {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        (binding.docCategoryTouch.layoutParams as? LinearLayout.LayoutParams)?.let { chip ->
            chip.topMargin = if (stacked) dp(4f) else 0
            binding.docCategoryTouch.layoutParams = chip
        }
        moveActionPanel(large)
    }

    /** The visible and spoken labels; the Pro word on Sign follows the build's real state. */
    private fun applyActionLabels() {
        binding.btnEdit.text = getString(R.string.doc_action_edit_crop)
        binding.btnEdit.contentDescription = getString(R.string.doc_action_edit_crop)
        binding.btnText.text = getString(R.string.dock_read_text)
        binding.btnText.contentDescription = getString(R.string.dock_read_text)
        binding.btnShare.text = getString(R.string.share_pdf)
        binding.btnShare.contentDescription = getString(R.string.share_pdf)
        binding.btnCompress.text = getString(R.string.tool_compress)
        binding.btnCompress.contentDescription = getString(R.string.tool_compress)
        binding.btnSavePdf.text = getString(R.string.doc_action_save_pdf)
        // Spoken: the visible label also says where the copy goes.
        binding.btnSavePdf.contentDescription = getString(R.string.save_pdf_device)
        binding.btnMore.text = getString(R.string.more)
        binding.btnMore.contentDescription = getString(R.string.doc_action_more_desc)
        applySignLabel()
    }

    /**
     * Sign reads "Sign Pro" in Free (the word Pro alone is gold — never the whole button) and
     * "Sign" once Pro is owned. The description says the same thing to TalkBack, exactly as
     * the Actions sheet does.
     */
    private fun applySignLabel() {
        val sign = getString(R.string.tool_sign)
        val pro = getString(R.string.doc_action_pro)
        val free = !ProGate.isPro(this)
        binding.btnSign.text = if (free) {
            // The gold word keeps its own space from the label: "Sign Pro" ran together as
            // "SignPro" on the phone; the dot is the screen's own metadata separator.
            val full = "$sign · $pro"
            SpannableString(full).apply {
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this@DocumentViewerActivity, R.color.pro_gold)),
                    full.length - pro.length,
                    full.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            SpannableString(sign)
        }
        binding.btnSign.contentDescription = signSpokenLabel()
    }

    /** What TalkBack says for Sign in the build's real state ("Sign, Pro" while Free). */
    private fun signSpokenLabel(): String {
        val sign = getString(R.string.tool_sign)
        return if (ProGate.isPro(this)) sign
        else "$sign, ${getString(R.string.doc_action_pro)}"
    }

    /** The sheet's own reason for a document no tool may read (null when it is usable). */
    private fun protectedReason(doc: ScanDocument): String? = when {
        doc.isLocked -> getString(R.string.locked_source_refused, doc.name)
        doc.isVaulted -> getString(R.string.doc_actions_vault_reason)
        else -> null
    }

    /**
     * Compress, Sign and Read text are disabled for a locked or Vault document with the same
     * words the Actions sheet prints under those rows; the handlers also refuse defensively,
     * so no route is reachable from a disabled button.
     */
    private fun applyActionAvailability(doc: ScanDocument) {
        val reason = protectedReason(doc)
        val blocked = reason != null
        val items = listOf(
            binding.btnCompress to getString(R.string.tool_compress),
            binding.btnSign to getString(R.string.tool_sign),
            binding.btnText to getString(R.string.dock_read_text)
        )
        for ((button, label) in items) {
            button.isEnabled = !blocked
            button.alpha = if (blocked) 0.45f else 1f
            button.contentDescription = when {
                blocked -> "$label. $reason"
                button.id == R.id.btnSign -> signSpokenLabel()
                else -> label
            }
        }
        binding.actionPanelReason.isVisible = blocked
        binding.actionPanelReason.text = reason
    }

    /**
     * Standard type: the page grid owns the scroll and the panel is pinned below it.
     * 160 % and above: the panel and the grid move together into [binding.actionFlowScroll]
     * (panel first, pages after) so the action area grows instead of eating the page area —
     * one scroll owner either way.
     */
    private fun moveActionPanel(large: Boolean) {
        val panel = binding.bottomDock
        val pages = binding.pageList
        val column = binding.actionFlowColumn
        if (large) {
            (panel.parent as? ViewGroup)?.takeIf { it !== column }?.removeView(panel)
            (pages.parent as? ViewGroup)?.takeIf { it !== column }?.removeView(pages)
            if (panel.parent == null) {
                column.addView(
                    panel,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            if (pages.parent == null) {
                pages.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                pages.isNestedScrollingEnabled = false
                column.addView(pages)
            }
            binding.actionFlowScroll.isVisible = true
        } else {
            if (panel.parent === column) column.removeView(panel)
            if (pages.parent === column) column.removeView(pages)
            binding.actionFlowScroll.isVisible = false
            if (panel.parent == null) {
                panel.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(4f)
                    rightMargin = dp(4f)
                    bottomMargin = dp(4f)
                }
                binding.contentRoot.addView(panel)
            }
            if (pages.parent == null) {
                pages.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
                pages.isNestedScrollingEnabled = true
                binding.pagesHost.addView(pages)
            }
        }
    }

    /** Bytes the document occupies: its PDF, or its pages while no PDF exists. */
    private fun docSizeBytes(doc: ScanDocument): Long =
        if (doc.pdfFile.isFile && doc.pdfFile.length() > 0L) doc.pdfFile.length()
        else doc.pageFiles.sumOf { it.length() }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateSelectionUi() {
        val count = selected.size
        if (selectMode && count == 0) selectMode = false
        supportActionBar?.subtitle = null
        binding.selectionTopBar.isVisible = selectMode
        binding.selectionActionBar.isVisible = selectMode
        binding.bottomDock.isVisible = !selectMode
        binding.draftBar.isVisible = !selectMode && (draft?.changes ?: 0) > 0
        binding.selCount.text = resources.getQuantityString(R.plurals.selected_count, count, count)
        binding.btnSelAll.isEnabled = count < visiblePages.size
        binding.pageList.checkboxesVisible = selectMode
    }

    /** Kit paint for the selection bars (icons already come from the layout). */
    private fun styleSelectionBars() {
        binding.selectionActionBar.background = SwStyle.card(this, 18f)
        listOf(binding.btnSelRotate, binding.btnSelDelete, binding.btnSelExtract, binding.btnSelShare, binding.btnSelMore)
            .forEach { view ->
                view.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                view.minHeight = dp(64f)
            }
        binding.selCount.setTextColor(SwStyle.color(this, R.color.text_hologram_hi))
        binding.selCount.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        binding.btnSelAll.setTextColor(SwStyle.color(this, R.color.cyan_primary))
        binding.btnSelAll.typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    /** Maps a grid (visible) index to the draft page index, or -1. */
    private fun diskIndex(visibleIndex: Int): Int {
        val file = visiblePages.getOrNull(visibleIndex) ?: return -1
        return pages.indexOfFirst { it.path == file.path }
    }

    // ------------------------------------------------------------------
    // Selection mode (owner revision): long-press, top bar, action bar
    // ------------------------------------------------------------------

    /** True while pages are selected: round checks + top bar + action bar. */
    private var selectMode = false

    /** Long-press: enter selection mode with this page selected (idempotent). */
    private fun enterSelection(index: Int) {
        val path = visiblePages.getOrNull(index)?.path ?: return
        if (!selectMode) {
            selectMode = true
            binding.pageList.checkboxesVisible = true
        }
        selected.add(path)
        binding.pageList.refresh()
        updateSelectionUi()
    }

    private fun exitSelectMode() {
        selectMode = false
        selected.clear()
        binding.pageList.checkboxesVisible = false
        binding.pageList.refresh()
        updateSelectionUi()
    }

    /** Ticks/untick a page. A tap on a tile behaves the same while selecting. */
    internal fun toggleSelected(index: Int) {
        val path = visiblePages.getOrNull(index)?.path ?: return
        if (!selected.remove(path)) selected.add(path)
        if (selected.isEmpty()) exitSelectMode() else updateSelectionUi()
        binding.pageList.refresh()
    }

    private fun selectAllPages() {
        visiblePages.mapTo(selected) { it.path }
        if (selected.isNotEmpty() && !selectMode) {
            selectMode = true
            binding.pageList.checkboxesVisible = true
        }
        binding.pageList.refresh()
        updateSelectionUi()
    }

    /** Delete asks first (owner revision): an SwSheet, then the draft delete with Undo. */
    private fun confirmDeleteSelected() {
        val count = selected.size
        if (count == 0) return
        SwSheet.builder(this)
            .setTitle(resources.getQuantityString(R.plurals.draft_delete_title, count, count))
            .setMessage(R.string.draft_delete_msg)
            .setPrimary(R.string.delete) { deleteSelectedPages() }
            .setSecondary(R.string.draft_keep_editing)
            .build()
            .show()
    }

    /** Share the ticked draft pages as images. */
    private fun shareSelectedPages() {
        val doc = document ?: return
        val files = pages.filter { it.path in selected }
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_SHORT).show()
            return
        }
        ShareUtils.shareImages(this, files, doc.name)
        noteImportUsed()
    }

    /** ⋯ for selection: Rotate left/right, Duplicate, Move to… (10+), Insert pages here, Extract. */
    private fun showSelectionMore() {
        data class Action(val icon: Int, val label: Int, val run: () -> Unit)

        val actions = mutableListOf(
            Action(R.drawable.ic_sw_rotate, R.string.draft_rotate_left) { rotateSelected(false) },
            Action(R.drawable.ic_sym_redo, R.string.draft_rotate_right) { rotateSelected(true) },
            Action(R.drawable.ic_sym_content_copy, R.string.draft_duplicate) {
                duplicateSelectedPages()
            },
            Action(R.drawable.ic_sym_note_add, R.string.draft_insert_here) {
                startInsertPagesHere()
            },
            Action(R.drawable.ic_sym_content_cut, R.string.draft_extract_new) {
                extractSelected()
            }
        )
        if (visiblePages.size >= 10 && selected.size == 1) {
            actions.add(
                3,
                Action(R.drawable.ic_sw_move_page, R.string.draft_move_to) { promptMoveTo() }
            )
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.draftMoreRows
        }
        lateinit var sheet: com.scanwhirl.app.ui.kit.SwSheet
        actions.forEach { action ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), 0, dp(12f), 0)
                minimumHeight = dp(52f)
                isClickable = true
                isFocusable = true
                background = SwStyle.rounded(
                    this@DocumentViewerActivity,
                    SwStyle.color(this@DocumentViewerActivity, R.color.glass_surface_raised),
                    radiusDp = 14f
                )
            }
            row.addView(
                ImageView(this).apply {
                    setImageResource(action.icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        SwStyle.color(this@DocumentViewerActivity, R.color.cyan_primary)
                    )
                },
                LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(12f) }
            )
            row.addView(
                SwStyle.text(
                    this,
                    15f,
                    SwStyle.color(this@DocumentViewerActivity, R.color.text_hologram_hi)
                ).apply { setText(action.label) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.setOnClickListener {
                sheet.dismiss()
                action.run()
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            )
        }
        sheet = SwSheet.builder(this)
            .setTitle(R.string.more)
            .setContent(list)
            .setSecondary(R.string.draft_keep_editing)
            .build()
        sheet.show()
    }

    /** Duplicate the ticked draft pages right after the last one. */
    private fun duplicateSelectedPages() {
        val id = docId ?: return
        val files = pages.filter { it.path in selected }
        if (files.isEmpty()) return
        val insertAt = (pages.indexOfLast { it.path in selected } + 1).coerceAtLeast(files.size)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.draftInsertPages(id, files, insertAt)
                }
                reloadDocument()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Move exactly one selected page to a typed page number (long documents only). */
    private fun promptMoveTo() {
        val max = visiblePages.size
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.draft_move_to)
            .setInput(
                hint = getString(R.string.draft_move_to_hint),
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            )
            .setPrimary(R.string.draft_move_to)
            .build()
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val target = sheet.inputView?.text?.toString()?.toIntOrNull()
            if (target == null || target !in 1..max) {
                Toast.makeText(this, R.string.draft_move_invalid, Toast.LENGTH_SHORT).show()
            } else {
                sheet.dismiss()
                moveSelectedPage(target - 1)
            }
        }
        sheet.show()
    }

    private fun moveSelectedPage(to: Int) {
        val id = docId ?: return
        val path = selected.singleOrNull() ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    commitPendingDeletesNow()
                    val now = repo.draftPages(id)
                    val from = now.indexOfFirst { it.path == path }
                    if (from >= 0 && from != to) repo.draftMove(id, from, to)
                }
                reloadDocument()
                selected.clear()
                updateSelectionUi()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Where a "Insert pages here" action puts the picked pages (null = ask). */
    private var insertHere: Int? = null

    // ------------------------------------------------------------------
    // Insert pages (Free, Organize): into the draft, never the base
    // ------------------------------------------------------------------

    /** "Add pages from": camera, photos or another library document. */
    internal fun startInsertPages() {
        insertHere = null
        showInsertSources()
    }

    /** Insert at the position after the current selection (the ⋯ action). */
    private fun startInsertPagesHere() {
        val maxIndex = visiblePages.indices
            .filter { visiblePages[it].path in selected }
            .maxOrNull()
        insertHere = (maxIndex ?: -1) + 1
        showInsertSources()
    }

    private fun showInsertSources() {
        val options = listOf(
            getString(R.string.insert_from_camera),
            getString(R.string.insert_from_photos),
            getString(R.string.insert_from_document)
        )
        SwSheet.builder(this)
            .setTitle(R.string.insert_pages_title)
            .setOptions(options) { which, _ ->
                when (which) {
                    0 -> scanForInsert()
                    1 -> insertPhotos.launch("image/*")
                    else -> pickDocumentForInsert()
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun scanForInsert() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setScannerMode(
                if (Prefs.autoEnhanceScan(this)) {
                    GmsDocumentScannerOptions.SCANNER_MODE_FULL
                } else {
                    GmsDocumentScannerOptions.SCANNER_MODE_BASE
                }
            )
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(ScanLauncher.CAMERA_PAGE_LIMIT)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                insertScanner.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    getString(R.string.scanner_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun pickDocumentForInsert() {
        val all = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        val docs = all.filter { it.id != docId && !it.isLocked && !it.isVaulted }
        val noEligible = R.string.r3fit_no_insert_source.takeIf { all.isNotEmpty() && docs.isEmpty() }
        FlowUtil.pickScan(this, R.string.insert_pages_title, docs, noEligibleTitle = noEligible) { source ->
            // Copies, never moves: the source document keeps every page.
            askInsertPosition(source.pageFiles)
        }
    }

    /** Copies picked camera/photo URIs into a new temp folder of normalised JPEG pages. */
    private fun copyInsertUris(uris: List<android.net.Uri>): List<File> =
        copyInsertUris(
            uris,
            File(cacheDir, "insert_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        )

    /**
     * Copies [uris] into [dir] as normalised JPEG pages. The caller owns [dir]: the camera path
     * creates it here and deletes it after `insertFiles`, the photos path (R1) owns and cleans it
     * from before the first read.
     */
    private fun copyInsertUris(uris: List<android.net.Uri>, dir: File): List<File> {
        val files = ArrayList<File>(uris.size)
        uris.forEachIndexed { index, uri ->
            photoCopyGateForTest?.invoke(index)
            val bytes = contentResolver.openInputStream(uri)?.use(ImageTools::readPageBytes)
                ?: throw IllegalArgumentException("Page ${index + 1} could not be read")
            val file = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", index))
            Durable.writeFile(file, ImageTools.normalizePage(bytes))
            files += file
        }
        return files
    }

    /**
     * Position picker: at the start, after page N, or at the end. [onReady] receives the chosen
     * position and the pages to put there; photos hand it to the corner crop flow first (A8),
     * camera and PDF inserts keep going straight to [insertFiles]. [onAbandon] runs when the
     * position is never chosen, so a photo batch's picked copies do not stay in the cache.
     */
    private fun askInsertPosition(
        files: List<File>,
        onReady: (List<File>, Int) -> Unit = { ready, position -> insertFiles(ready, position) },
        onAbandon: (() -> Unit)? = null
    ) {
        if (files.isEmpty()) {
            toast(R.string.insert_none, Toast.LENGTH_SHORT)
            return
        }
        val here = insertHere
        if (here != null) {
            insertHere = null
            onReady(files, here)
            return
        }
        val options = listOf(
            getString(R.string.insert_at_start),
            getString(R.string.insert_after_page),
            getString(R.string.insert_at_end)
        )
        var movedOn = false
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.insert_position_title)
            .setOptions(options) { which, _ ->
                movedOn = true
                when (which) {
                    0 -> onReady(files, 0)
                    1 -> promptInsertAfterPage(files, onReady, onAbandon)
                    else -> onReady(files, visiblePages.size)
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.setOnDismissListener { if (!movedOn) onAbandon?.invoke() }
        sheet.show()
    }

    private fun promptInsertAfterPage(
        files: List<File>,
        onReady: (List<File>, Int) -> Unit,
        onAbandon: (() -> Unit)? = null
    ) {
        val max = visiblePages.size
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.insert_after_page)
            .setInput(
                hint = getString(R.string.insert_page_hint),
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            )
            .setPrimary(R.string.insert_after_page)
            .build()
        var movedOn = false
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val number = sheet.inputView?.text?.toString()?.toIntOrNull()
            if (number == null || number !in 1..max) {
                Toast.makeText(this, R.string.insert_page_invalid, Toast.LENGTH_SHORT).show()
            } else {
                movedOn = true
                sheet.dismiss()
                onReady(files, number)
            }
        }
        sheet.setOnDismissListener { if (!movedOn) onAbandon?.invoke() }
        sheet.show()
    }

    /**
     * A8: a picked gallery photo is not a scan, so it is auto-cropped and its corners confirmed
     * on the corner crop screen, one photo after another, before the draft is touched. The batch
     * folder is the picked copies; the crop flow deletes everything if any photo is cancelled.
     */
    private fun startPhotoCrop(files: List<File>, position: Int) {
        val batch = pendingPhotoBatch ?: return
        batch.handedOff = true
        pendingPhotoPosition = position
        pendingPhotoCount = files.size
        insertPhotoCrop.launch(PhotoCropFlowActivity.intent(this, files))
    }

    /** A photo batch's cache folder holds plaintext picked photos; it never outlives the flow. */
    private fun deleteBatch(dir: File) {
        dir.takeIf { it.name.startsWith("insert_") }?.deleteRecursively()
    }

    /** Test seam and the one writer: copied pages enter the draft at [position]. */
    internal fun insertFiles(files: List<File>, position: Int) {
        val id = docId ?: return
        insertHere = null
        // Picked camera/gallery pages live in `cacheDir/insert_<id>`: once the draft has copied
        // them they are plaintext copies of user pages, so the folder goes with them (B9).
        val tempDir = files.firstOrNull()?.parentFile?.takeIf { it.name.startsWith("insert_") }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.draftInsertPages(id, files, position) }
                reloadDocument()
                Toast.makeText(
                    this@DocumentViewerActivity,
                    resources.getQuantityString(R.plurals.insert_done, files.size, files.size),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                tempDir?.deleteRecursively()
            }
        }
    }

    private fun requireSelection(): List<String>? {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_pages_first, Toast.LENGTH_SHORT).show()
            return null
        }
        return selected.toList()
    }

    /** Rotates every ticked page (a single selection rotates just that page), in the draft. */
    internal fun rotateSelected(clockwise: Boolean) {
        val paths = requireSelection() ?: return
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                val rotated = withContext(Dispatchers.IO) {
                    // Land pending deletes first so draft indices are stable.
                    commitPendingDeletesNow()
                    val now = repo.draftPages(id)
                    val indices = paths.mapNotNull { path ->
                        now.indexOfFirst { it.path == path }.takeIf { it >= 0 }
                    }
                    indices.forEach { repo.draftRotate(id, it, clockwise) }
                    indices
                }
                reloadDocument()
                // A rotated page gets a new file: re-select by index.
                selected.clear()
                rotated.mapNotNull { pages.getOrNull(it)?.path }.forEach { selected.add(it) }
                binding.pageList.refresh()
                updateSelectionUi()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Deletes every ticked page with one Undo snackbar. Pages stay on disk (and
     * are only hidden) until the snackbar times out, is replaced, or the screen
     * closes.
     */
    internal fun deleteSelectedPages() {
        val paths = requireSelection() ?: return
        document ?: return
        if (paths.size >= visiblePages.size) {
            Toast.makeText(this, R.string.last_page_guard, Toast.LENGTH_LONG).show()
            return
        }
        // This snackbar replaces any previous one: the old Undo window is over.
        commitPendingDelete()

        pendingDeletes.addAll(paths)
        selected.clear()
        refreshGrid()

        val batch = paths.toList()
        Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                pendingDeletes.removeAll(batch.toSet())
                refreshGrid()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) commitPendingDeletes(batch)
                }
            })
            .show()
    }

    /**
     * Extract (§4.5 contextual bar): the ticked pages become a NEW document —
     * this one keeps every page, its exports and its category.
     */
    private fun extractSelected() {
        val paths = requireSelection() ?: return
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                val indices = withContext(Dispatchers.IO) {
                    // Land pending deletes first so draft indices are stable.
                    commitPendingDeletesNow()
                    val now = repo.draftPages(id)
                    val wanted = paths.toSet()
                    now.indices.filter { now[it].path in wanted }
                }
                if (indices.isEmpty()) return@launch
                val doc = document ?: return@launch
                withContext(Dispatchers.IO) {
                    if (repo.draftOf(id) == null) {
                        repo.extractPages(id, indices)
                    } else {
                        // Extracting from a draft extracts its edited pages and leaves the draft.
                        repo.saveDraftAsNew(id, repo.extractedName(doc.name, indices), indices)
                    }
                }
                exitSelectMode()
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.extracted, indices.size),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Deletes all Undo-pending pages for good. No-op when nothing is pending. */
    internal fun commitPendingDelete() = commitPendingDeletes(pendingDeletes.toList())

    /** Deletes the given Undo-pending paths from the draft (idempotent; recomputes each index). */
    internal fun commitPendingDeletes(paths: Collection<String>) {
        val id = docId ?: return
        val toDelete = paths.filter { pendingDeletes.contains(it) }
        if (toDelete.isEmpty()) return
        pendingDeletes.removeAll(toDelete.toSet())
        committingDeletes.addAll(toDelete)
        deleteScope.launch {
            try {
                for (path in toDelete) {
                    val now = repo.draftPages(id)
                    val idx = now.indexOfFirst { it.path == path }
                    if (idx >= 0 && now.size > 1) repo.draftDelete(id, idx)
                }
            } catch (_: Exception) {
                // Best effort; a failed delete leaves the page in place.
            }
            committingDeletes.removeAll(toDelete.toSet())
            // Refresh only now: the pages are gone from the draft, so they can't flash back.
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    lifecycleScope.launch { reloadDocument() }
                }
            }
        }
    }

    /** Blocking variant used before index-sensitive work; clears all pending deletes. */
    private suspend fun commitPendingDeletesNow() {
        val id = docId ?: return
        val toDelete = pendingDeletes.toList()
        if (toDelete.isEmpty()) return
        pendingDeletes.removeAll(toDelete.toSet())
        for (path in toDelete) {
            val now = repo.draftPages(id)
            val idx = now.indexOfFirst { it.path == path }
            if (idx >= 0 && now.size > 1) repo.draftDelete(id, idx)
        }
    }

    // ------------------------------------------------------------------
    // Reorder
    // ------------------------------------------------------------------

    /** Persists a finished drag: the grid's new order becomes the draft order. */
    internal fun reorderPages(from: Int, to: Int) {
        val id = docId ?: return
        // The grid's pre-drag base order is exactly `visiblePages` (snapshot).
        val baseFile = visiblePages.getOrNull(from) ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Any pending delete must land first so grid indices == draft indices.
                    commitPendingDeletesNow()
                    val now = repo.draftPages(id)
                    val draftFrom = now.indexOfFirst { it.path == baseFile.path }
                    if (draftFrom >= 0 && draftFrom != to) repo.draftMove(id, draftFrom, to)
                }
                reloadDocument()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                reloadDocument()
            }
        }
    }

    // ------------------------------------------------------------------
    // Document-level actions
    // ------------------------------------------------------------------

    /**
     * Saves a copy of the document PDF into Documents/ScanWhirl (T4.7) with the
     * M06 export transaction. The PDF is streamed, never loaded whole, and an
     * earlier copy is never overwritten. Undo only ever removes a copy this save
     * created.
     */
    private fun savePdfToDocuments() {
        val doc = document ?: return
        traceAction("save_pdf", doc)
        // PM-3.1c: a document whose lock and PDF disagree is repaired first, never saved silently.
        ProtectionDialogs.guardExport(this, repo, doc, onResolved = { lifecycleScope.launch { reloadDocument() } }) {
            // W1: with an unsaved draft the user chooses "Finish editing first" or "Use saved
            // version"; the copy written below is always the saved PDF's own bytes.
            DocumentActionDispatcher.dispatch(
                activity = this,
                repo = repo,
                docId = doc.id,
                action = DocumentActionDispatcher.Action.SAVE_PDF,
                onUsed = { _ -> noteImportUsed() }
            ) { bound ->
                if (!bound.pdfFile.exists() || bound.pdfFile.length() == 0L) {
                    Toast.makeText(this, R.string.pdf_missing, Toast.LENGTH_SHORT).show()
                    return@dispatch
                }
                withStoragePermission {
                    lifecycleScope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) {
                                DownloadSaver.savePdfFile(
                                    this@DocumentViewerActivity,
                                    bound.pdfFile,
                                    DownloadSaver.pdfNameFor(bound.name)
                                ).also {
                                    // T4.8: remember the copy so delete can remove it. A failure to
                                    // record it must not turn a finished export into a failed one.
                                    try {
                                        repo.addExport(bound.id, it)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            // T44: a copy the user saved makes this import theirs; Back keeps it.
                            noteImportUsed()
                            SavedSnackbar.showExport(
                                this@DocumentViewerActivity,
                                binding.root,
                                saved,
                                R.string.pdf_saved_docs,
                                bound.id
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@DocumentViewerActivity,
                                getString(R.string.pdf_save_failed, e.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * Dock "Read text" (B2; was "Text"): OCR through the shared [OcrFlow] — one home per
     * action. Read text is a Free tool; only its "To Word" action is Pro and asks there.
     * A locked or Vault document keeps the button disabled (applyActionAvailability) and this
     * guard refuses even a programmatic click.
     */
    private fun runTextFlow() {
        val doc = document ?: return
        if (protectedReason(doc) != null) return
        traceAction("read_text", doc)
        ocrFlow.runOcrForScan(doc)
    }

    /** Compress from the panel: exactly the guarded handler the Actions sheet runs. */
    private fun compressFromPanel() {
        val doc = document ?: return
        if (protectedReason(doc) != null) return
        traceAction("compress", doc)
        // W1: a draft asks first; the level sheet is estimated from the saved pages.
        DocumentActionDispatcher.dispatch(
            activity = this,
            repo = repo,
            docId = doc.id,
            action = DocumentActionDispatcher.Action.COMPRESS,
            onUsed = { _ -> noteImportUsed() }
        ) { bound -> compressFlow.startWith(bound) }
    }

    /**
     * Sign from the panel: the Pro gate first (Free lands on the Pro screen), then the stamp
     * editor armed with Sign for THIS document — never StampToolLauncher's generic picker.
     */
    private fun signFromPanel() {
        val doc = document ?: return
        if (protectedReason(doc) != null) return
        traceAction("sign", doc)
        // W1: a draft asks first; Sign then opens the editor for the saved version's pages.
        DocumentActionDispatcher.dispatch(
            activity = this,
            repo = repo,
            docId = doc.id,
            action = DocumentActionDispatcher.Action.SIGN,
            onUsed = { _ -> noteImportUsed() }
        ) { bound ->
            if (ProOffer.require(this)) {
                startActivity(
                    StampEditorActivity.intent(this, bound.id, StampEditorActivity.TOOL_SIGN)
                )
            }
        }
    }

    /**
     * T4.6: the header category chip — and, until T5.2 removes it (D-F), the
     * overflow `Categorize` item — open the T4.2 category picker. The old
     * single-choice "Categorize this document" list dialog is gone; a pick
     * saves on IO and repaints the chip through [reloadDocument] (the chip
     * itself is the confirmation, no toast).
     */
    private fun promptCategorize() {
        val doc = document ?: return
        CategoryPickerSheet.show(this, doc.category) { cat ->
            lifecycleScope.launch {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repo.setCategory(doc.id, cat)
                }
                noteImportUsed()
                reloadDocument()
            }
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingDownload = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ------------------------------------------------------------------
    // Leaving the editor: the draft (PM-2.2c)
    // ------------------------------------------------------------------

    private fun handleEditorBack() {
        if (importTrashInProgress) return
        // T44: the question only exists for an import the user has not touched. Once acted on
        // (a saved copy, a share, an edit) the import is kept, never offered for Trash here.
        if (freshImport && !freshImportAsked && !importKept) {
            askKeepImported()
            return
        }
        if ((draft?.changes ?: 0) == 0) {
            finish()
            return
        }
        SwSheet.builder(this)
            .setTitle(R.string.draft_back_title)
            .setMessage(R.string.draft_back_msg)
            .setPrimary(R.string.draft_save_new) { saveDraftFlow() }
            .setSecondary(R.string.draft_discard) { discardNow() }
            .build()
            .show()
    }

    /**
     * F5/W2: an imported PDF is in the library the moment it arrives, so leaving the first look
     * asks first. Keep leaves it like any other document; Move to Trash is reversible.
     * T44: it is never asked once the user has acted on the import.
     */
    private fun askKeepImported() {
        val id = docId
        if (id == null) {
            finish()
            return
        }
        if (importKept) {
            // Defensive: a saved copy or an edit happened between the Back and this call.
            finish()
            return
        }
        freshImportAsked = true
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.fixflow_import_keep_title)
            .setMessage(R.string.r1import_keep_message)
            .setPrimary(R.string.fixflow_keep) { finish() }
            .setSecondary(R.string.trash_move) { moveImportToTrash(id) }
            .build()
        val actions = sheet.view as LinearLayout
        val trash = sheet.secondaryButton
        // The bottom-centre area belongs to the safe answer, not the Trash action.
        // This sheet alone puts a quiet, wrapping start-aligned action above that area.
        actions.removeView(trash)
        actions.addView(trash, actions.indexOfChild(sheet.primaryButton), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@DocumentViewerActivity, 8f) })
        trash.apply {
            val padding = SwStyle.dp(this@DocumentViewerActivity, 8f)
            minimumWidth = SwStyle.dp(this@DocumentViewerActivity, 48f)
            setPaddingRelative(padding, padding, padding, padding)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
        }
        (sheet.primaryButton.layoutParams as LinearLayout.LayoutParams).topMargin = SwStyle.dp(this, 40f)
        sheet.show()
    }

    private fun moveImportToTrash(id: String) {
        if (importTrashInProgress) return
        // T44: never move an import the user has acted on, whatever asked.
        if (importKept) {
            finish()
            return
        }
        importTrashInProgress = true
        lifecycleScope.launch {
            val moved = withContext(NonCancellable + Dispatchers.IO) {
                TrashActions.moveAll(repo, listOf(id))
            }
            importTrashInProgress = false
            if (moved.keys.isEmpty()) {
                freshImportAsked = false
                TrashActions.showMoved(this@DocumentViewerActivity, binding.root, null, moved) {}
                return@launch
            }
            TrashActions.post(moved)
            finish()
        }
    }

    /** T44: this import is the user's now; Back must never offer to move it to Trash. */
    private fun noteImportUsed() {
        if (freshImport) importKept = true
    }

    /** `Draft · N pages · K changes`, visible only while K > 0. */
    private fun updateDraftBar() {
        val changes = draft?.changes ?: 0
        binding.draftBar.isVisible = !selectMode && changes > 0
        val pagesText = resources.getQuantityString(R.plurals.draft_pages, pages.size, pages.size)
        val changesText = resources.getQuantityString(R.plurals.draft_changes, changes, changes)
        binding.draftHeader.text = getString(R.string.draft_header, pagesText, changesText)
    }

    /** Kit paint for the bar: glass card, cyan Save, quiet Discard/Replace. */
    private fun styleDraftBar() {
        val density = resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()
        binding.draftBar.background = SwStyle.card(this, 18f)
        binding.draftHeader.setTextColor(SwStyle.color(this, R.color.text_hologram_hi))
        binding.draftHeader.typeface = Typeface.create("sans-serif", Typeface.BOLD)

        fun paint(view: android.widget.TextView, fill: Int, ink: Int) {
            view.background = SwStyle.rounded(this, fill, radiusDp = 14f)
            view.setTextColor(ink)
            view.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            view.gravity = Gravity.CENTER
            view.minHeight = dp(44f)
            view.setPadding(dp(10f), 0, dp(10f), 0)
        }
        paint(
            binding.btnDraftDiscard,
            SwStyle.color(this, R.color.glass_surface_raised),
            SwStyle.color(this, R.color.text_hologram_hi)
        )
        paint(
            binding.btnDraftReplace,
            SwStyle.color(this, R.color.glass_surface_raised),
            SwStyle.color(this, R.color.text_hologram_hi)
        )
        paint(
            binding.btnDraftSave,
            SwStyle.color(this, R.color.cyan_primary),
            SwStyle.color(this, R.color.sw_disc_ink)
        )
    }

    /** Save as new copy: commit pending deletes, ask for a name (prefilled), publish. */
    private fun saveDraftFlow() {
        val id = docId ?: return
        val doc = document ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { commitPendingDeletesNow() }
                promptDraftName(getString(R.string.draft_edited_name, doc.name)) { name ->
                    lifecycleScope.launch {
                        try {
                            val copy = withContext(Dispatchers.IO) {
                                repo.saveDraftAsNew(id, name)
                            }
                            Toast.makeText(
                                this@DocumentViewerActivity,
                                getString(R.string.draft_saved_new, copy.name),
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        } catch (e: Exception) {
                            showDraftError(e)
                        }
                    }
                }
            } catch (e: Exception) {
                showDraftError(e)
            }
        }
    }

    /** Replace original: confirm, then the two-rename publish (previous version goes to Trash). */
    private fun replaceFlow() {
        val id = docId ?: return
        val doc = document ?: return
        SwSheet.builder(this)
            .setTitle(getString(R.string.draft_replace_title, doc.name))
            .setMessage(getString(R.string.draft_replace_msg, doc.name))
            .setPrimary(R.string.draft_replace) {
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            commitPendingDeletesNow()
                            repo.replaceWithDraft(id)
                        }
                        Toast.makeText(
                            this@DocumentViewerActivity,
                            R.string.draft_replaced,
                            Toast.LENGTH_LONG
                        ).show()
                        reloadDocument()
                    } catch (e: Exception) {
                        showDraftError(e)
                    }
                }
            }
            .setSecondary(R.string.draft_keep_editing)
            .build()
            .show()
    }

    /** Discard from the bar: confirm first, then drop only the draft. */
    private fun discardFlow() {
        val changes = draft?.changes ?: 0
        if (changes == 0) return
        SwSheet.builder(this)
            .setTitle(R.string.draft_discard)
            .setMessage(getString(R.string.draft_discard_msg, changes))
            .setPrimary(R.string.draft_discard) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.discardDraft(docId ?: return@withContext) }
                    pendingDeletes.clear()
                    reloadDocument()
                }
            }
            .setSecondary(R.string.draft_keep_editing)
            .build()
            .show()
    }

    /** Discard with no further question (the Back prompt already explained it). */
    private fun discardNow() {
        val id = docId ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.discardDraft(id) }
            pendingDeletes.clear()
            finish()
        }
    }

    /** A damaged/stale draft can only be discarded (PM-2.2b §5.2). */
    private fun showDraftUnusable(message: String) {
        SwSheet.builder(this)
            .setTitle(R.string.draft_back_title)
            .setMessage(message)
            .setPrimary(R.string.draft_discard) { discardNowAndReload() }
            .build()
            .show()
    }

    private fun discardNowAndReload() {
        val id = docId ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { repo.discardDraft(id) }
            }
            pendingDeletes.clear()
            reloadDocument()
        }
    }

    private fun showDraftError(e: Exception) {
        if (e is DraftStore.DraftStale || e is DraftStore.DraftDamaged) {
            showDraftUnusable(e.message ?: getString(R.string.draft_stale))
        } else {
            Toast.makeText(
                this,
                getString(R.string.page_op_failed, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Kit name sheet (SwSheet.setInput): one field, prefilled, with Save. */
    private fun promptDraftName(initial: String, onOk: (String) -> Unit) {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.draft_save_new)
            .setInput(initial = initial)
            .setPrimary(R.string.save)
            .build()
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            sheet.dismiss()
            onOk(sheet.inputView?.text?.toString().orEmpty())
        }
        sheet.show()
        sheet.inputView?.requestFocus()
    }

    // ------------------------------------------------------------------
    // HomeHost (§4.5): the tool flows (OCR, Compress) ask the host for
    // progress/toasts — DocumentViewer answers for itself.
    // ------------------------------------------------------------------

    override fun refreshList() {
        lifecycleScope.launch { reloadDocument() }
    }

    override fun showProgress(titleRes: Int): com.scanwhirl.app.ui.kit.SwProgress =
        com.scanwhirl.app.ui.kit.SwProgress.show(this, getString(titleRes))

    override fun toast(res: Int, duration: Int) {
        Toast.makeText(this, res, duration).show()
    }

    override fun toast(message: CharSequence, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    /** Test seam: records (action, document) just before the real handler runs. */
    private fun traceAction(action: String, doc: ScanDocument) {
        actionTraceForTests?.invoke(action, doc)
    }

    companion object {
        private const val EXTRA_DOC_ID = "doc_id"
        private const val EXTRA_INSERT_PAGES = "insert_pages"

        /**
         * Test seam: proves which document each panel action is about to act on. Null in
         * production, where every action runs its real handler.
         */
        @androidx.annotation.VisibleForTesting
        internal var actionTraceForTests: ((String, ScanDocument) -> Unit)? = null

        /** A8: the insert position and cache folder of a photo batch on the corner crop screen. */
        private const val KEY_PHOTO_INSERT_AT = "insert_photo_position"
        private const val KEY_PHOTO_COUNT = "insert_photo_count"
        private const val KEY_PHOTO_DIR = "insert_photo_dir"

        /** F5/W2: this viewer was opened right after an import; the first Back asks first. */
        private const val EXTRA_FRESH_IMPORT = "fresh_import"

        /** T44: the keep question and the "user acted on the import" state survive rotation. */
        private const val KEY_IMPORT_ASKED = "import_asked"
        private const val KEY_IMPORT_KEPT = "import_kept"

        fun intent(context: Context, docId: String): Intent =
            Intent(context, DocumentViewerActivity::class.java).putExtra(EXTRA_DOC_ID, docId)

        /** An import that just landed: Back offers Keep / Discard before anything is deleted. */
        fun intent(context: Context, docId: String, freshImport: Boolean): Intent =
            intent(context, docId).putExtra(EXTRA_FRESH_IMPORT, freshImport)

        /** Open Organize and immediately offer "Add pages from" (Insert pages tool). */
        fun insertIntent(context: Context, docId: String): Intent =
            intent(context, docId).putExtra(EXTRA_INSERT_PAGES, true)

        /** Insert pages tool: pick the target document first, then the source. */
        fun openInsertPicker(activity: android.app.Activity) {
            openPicker(activity, insert = true)
        }

        /** Organize pages tool: pick a document, then open its grid. */
        fun openOrganizePicker(activity: android.app.Activity) {
            openPicker(activity, insert = false)
        }

        private fun openPicker(activity: android.app.Activity, insert: Boolean) {
            if (activity !is androidx.appcompat.app.AppCompatActivity) return
            val all = runCatching { ScanRepository(activity).listDocuments() }.getOrDefault(emptyList())
            val docs = all.filter { !it.isLocked && !it.isVaulted }
            if (docs.isEmpty()) {
                val message = if (all.isEmpty()) R.string.empty_title else R.string.r3fit_no_page_tool_target
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                return
            }
            FlowUtil.pickScan(activity, R.string.tool_pick_document, docs) { doc ->
                activity.startActivity(
                    if (insert) insertIntent(activity, doc.id) else intent(activity, doc.id)
                )
            }
        }
    }
}
