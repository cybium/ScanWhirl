package com.scanwhirl.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.widget.TextView
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
    private var pendingDeleteDeadline = 0L
    private var pendingDeleteBar: Snackbar? = null
    private val deleteUndoHandler = Handler(Looper.getMainLooper())
    private val expirePageDelete = Runnable { commitPendingDelete() }

    /** Pages whose delete has been triggered but not landed yet (no flash-back). */
    private val committingDeletes = mutableSetOf<String>()

    /** True once onCreate has finished its async first load. */
    private var firstLoad = false

    /** F5/W2: this viewer is the first look at a just-imported document. */
    private var freshImport = false

    /** F5/W2: the import question was already shown. A failed discard allows a retry. */
    private var freshImportAsked = false
    private var importDiscardInProgress = false
    /** UX-007: the live question sheet. Its visibility is the honest "is it on screen" state. */
    private var importQuestionSheet: SwSheet? = null

    /**
     * T44: the user has acted on this import (saved a copy to Documents, shared it, or changed the
     * document). It is theirs now: Back never offers to delete it, in line with the library rule
     * that a document the user worked with is never silently thrown away.
     */
    private var importKept = false

    /** Not lifecycle-bound: a pending delete must land even during onDestroy. */
    private val deleteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var pendingDownload: (() -> Unit)? = null
    private var pendingSaveKind: String? = null
    private var pendingSaveTarget: String? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // B2: clear before invoking so a replayed result cannot save twice.
            val action = pendingDownload
            pendingDownload = null
            pendingSaveKind = null
            pendingSaveTarget = null
            if (granted) action?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
        }

    // Insert pages (Free, Organize): camera pages, photos or another library document.
    private val insertScanner =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages.orEmpty().mapNotNull { it.imageUri }
            onInsertScanUris(uris)
        }

    /**
     * B2 F2: the camera insert owns its temp copies from the moment they exist on disk. A failed
     * copy or a position sheet the person never confirms must delete them immediately, not wait
     * for the cold-start PrivacySweep. Also a test seam: GMS need not be running to drive it.
     */
    internal fun onInsertScanUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            toast(R.string.insert_none, Toast.LENGTH_SHORT)
            return
        }
        val dir = File(cacheDir, "insert_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        lifecycleScope.launch {
            var failure: Throwable? = null
            val files = withContext(Dispatchers.IO) {
                runCatching { copyInsertUris(uris, dir) }
                    .onFailure { failure = it }
                    .getOrNull()
            }
            if (files.isNullOrEmpty()) {
                // Empty/failed result: nothing handed off, so no plaintext copy may survive.
                dir.deleteRecursively()
                toast(getString(R.string.s1_insert_photo_failed, insertReason(failure)), Toast.LENGTH_LONG)
            } else {
                askInsertPosition(
                    files,
                    onReady = { ready, position -> insertFiles(ready, position) },
                    onAbandon = { deleteBatch(dir) }
                )
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
                var failure: Throwable? = null
                val files = try {
                    withContext(Dispatchers.IO) {
                        try {
                            copyInsertUris(uris, batch.dir)
                        } catch (e: Exception) {
                            failure = e
                            null
                        } catch (e: OutOfMemoryError) {
                            failure = e
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
                    if (!batch.abandoned) {
                        toast(getString(R.string.s1_insert_photo_failed, insertReason(failure)), Toast.LENGTH_LONG)
                    }
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
        // B2: a storage-permission answer may be re-delivered here after a rotation. The token
        // carries only the action kind and document id, never the document's bytes or text.
        PendingSaveAction.restore(savedInstanceState, ACTION_SAVE_PDF)?.let { (kind, target) ->
            if (target == docId) {
                pendingSaveKind = kind
                pendingSaveTarget = target
                pendingDownload = {
                    val bound = repo.getDocument(target)
                    if (bound == null || !bound.pdfFile.exists() || bound.pdfFile.length() == 0L) {
                        Toast.makeText(this, R.string.pdf_missing, Toast.LENGTH_LONG).show()
                    } else {
                        savePdfCopy(bound)
                    }
                }
            }
        }
        freshImport = intent.getBooleanExtra(EXTRA_FRESH_IMPORT, false)
        // T44: a rotation must not resurrect the delete question after Keep/Discard, nor undo the
        // fact that the user already acted on this import.
        freshImportAsked = savedInstanceState?.getBoolean(KEY_IMPORT_ASKED) ?: false
        importKept = savedInstanceState?.getBoolean(KEY_IMPORT_KEPT) ?: false
        pendingDeletes.addAll(savedInstanceState?.getStringArrayList(KEY_PENDING_DELETES).orEmpty())
        pendingDeleteDeadline = savedInstanceState?.getLong(KEY_DELETE_DEADLINE) ?: 0L
        // DV: ticked pages and the selection mode that owns them are screen state, not edits.
        // A rotation must not silently untick the pages the person is working on.
        savedInstanceState?.getStringArrayList(KEY_SELECTED_PAGES)?.let { selected.addAll(it) }
        selectMode = selected.isNotEmpty()
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
        // UX-L4-01: a visible, TalkBack-reachable entry into the same selection mode.
        // The long-press shortcut stays; this control is the discoverable alternative.
        findViewById<View>(R.id.ux130_l4_select_pages).setOnClickListener { enterSelectionMode() }
        binding.pageList.checkedAt = { i ->
            selected.contains(visiblePages.getOrNull(i)?.path)
        }
        binding.pageList.onDropped = { from, to -> reorderPages(from, to) }

        // V3: the dock is one compact row — Edit & crop, OCR, Share and Actions — each bound to
        // the document this screen shows. Compress, Sign and Save-copy now live in the Actions
        // sheet; their ids and guarded handlers stay in a GONE layout container so the existing
        // behaviour tests still drive exactly the same flows by id.
        //  - Edit / crop, OCR and Share keep their pre-V3 handlers and ids;
        //  - Actions opens the one named Actions sheet (rename/move/copy/protect/Trash/export);
        //  - a locked or Vault document keeps OCR (and the hidden Compress/Sign) disabled with
        //    the sheet's own reason (applyActionAvailability): no handler is reachable.
        binding.btnShare.setOnClickListener {
            document?.let { doc ->
                traceAction("share", doc)
                ProtectionDialogs.guardExport(this, repo, doc, onResolved = { lifecycleScope.launch { reloadDocument() } }) {
                    // W1: the id decides, not the screen's transient handle; a draft asks first and
                    // "Use saved version" hands over the saved document's own bytes.
                    val shareRun: (ScanDocument) -> Unit = { bound -> ShareUtils.share(this, bound) }
                    DocumentActionDispatcher.dispatch(
                        activity = this,
                        repo = repo,
                        docId = doc.id,
                        action = DocumentActionDispatcher.Action.SHARE,
                        onUsed = { _ -> noteImportUsed() },
                        onFinishEditing = { finishEditingThen(shareRun) },
                        run = shareRun
                    )
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
                // The More sheet's output is chosen after this answer, so finishing here means
                // offering the save choices; the chosen action can then be tapped again.
                onFinishEditing = { finishEditingThen { _ -> Unit } },
                // V3: "Save copy to Documents" is now a sheet row; it runs the same guarded
                // save-to-Documents handler the old dock button ran. OCR and Share stay in the
                // dock, so the viewer's sheet does not repeat them.
                onExport = { savePdfToDocuments() },
                includeDockActions = false
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
        reflowSelectionBarForLargeText()
        // PM-2.2c: the draft bar appears after the first edit; every action is explicit.
        styleDraftBar()
        binding.btnDraftDiscard.setOnClickListener { discardFlow() }
        binding.btnDraftReplace.setOnClickListener { replaceFlow() }
        binding.btnDraftSave.setOnClickListener { saveDraftFlow() }
        // Rename lives in the Actions sheet (the name row is a heading, not a button).
        // The toolbar carries no menu: Save PDF is the panel's labelled action, exactly once.
        // Checkboxes stay hidden until Select pages is tapped (§4.5, D-D), unless a
        // restored selection means the person was already selecting before rotation.
        binding.pageList.checkboxesVisible = selectMode

        // First load (off the main thread). Opening a document writes no draft (G14).
        lifecycleScope.launch {
            if (!reloadDocument()) return@launch
            firstLoad = true
            showPendingDeleteUndo()
            if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_INSERT_PAGES, false)) {
                startInsertPages()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IMPORT_ASKED, freshImportAsked)
        outState.putBoolean(KEY_IMPORT_KEPT, importKept)
        outState.putStringArrayList(KEY_PENDING_DELETES, ArrayList(pendingDeletes))
        outState.putLong(KEY_DELETE_DEADLINE, pendingDeleteDeadline)
        outState.putStringArrayList(KEY_SELECTED_PAGES, ArrayList(selected))
        pendingPhotoPosition?.let { outState.putInt(KEY_PHOTO_INSERT_AT, it) }
        pendingPhotoCount?.let { outState.putInt(KEY_PHOTO_COUNT, it) }
        // R1: only a batch the crop flow already owns is worth restoring; a copy in flight is
        // cancelled by the recreation and cleaned up by its worker.
        pendingPhotoBatch?.takeIf { it.handedOff }?.dir
            ?.let { outState.putString(KEY_PHOTO_DIR, it.path) }
        // B2: persist the pending save as a token; its lambda belongs to the old screen.
        PendingSaveAction.save(outState, pendingSaveKind, pendingSaveTarget)
    }

    override fun onDestroy() {
        // Detach the OLD bar before dismissing it. Its delayed callbacks must not commit a
        // delete whose state now belongs to the recreated activity.
        clearPageDeleteBar()
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
            showPendingDeleteUndo()
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
            showDraftUnusable(V2vFailureCopy.message(this@DocumentViewerActivity, damage))
        }
        binding.docName.text = doc.name
        // v2 UI: 07_reader_dock — the meta line carries the size as well as the page count.
        docBytes = withContext(Dispatchers.IO) { docSizeBytes(doc) }
        binding.docLockBadge.isVisible = doc.isLocked
        // V2V: the library copy of a protected document is readable on purpose;
        // protection belongs to the exported/shared PDF. Be explicit about it.
        findViewById<View>(R.id.v2v_protected_banner).isVisible = doc.isLocked
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
            com.scanwhirl.app.util.CategoryStyle.color(doc.category, this)
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
     * V3 (screenshot critique runs 5-6): the dock is ONE compact row of four named actions:
     * Edit &amp; crop · OCR · Share · Actions. Everything else lives in the Actions sheet.
     *
     *  - one row of four at normal type (the labels are short enough even at 320 dp);
     *  - from 160 % type the panel moves into the scroll flow ABOVE the pages as two growing
     *    columns, inside the one scroll owner;
     *  - labels wrap but are never ellipsized, and no label is ever shrunk below 12 sp.
     *  - Compress, Sign and Save keep their handlers (and their test seams) in the layout's
     *    GONE container; the person opens them from the Actions sheet.
     */
    private fun applyActionPanel() {
        applyActionLabels()
        val fontScale = resources.displayMetrics.scaledDensity / resources.displayMetrics.density
        val large = fontScale >= 1.6f
        val columns = if (large) 2 else 4
        val items = listOf(
            binding.btnEdit,
            binding.btnText,
            binding.btnShare,
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
        binding.btnText.text = getString(R.string.v3_ocr)
        binding.btnText.contentDescription = getString(R.string.v3_ocr_a11y)
        binding.btnShare.text = getString(R.string.share_pdf)
        binding.btnShare.contentDescription = getString(R.string.share_pdf)
        binding.btnCompress.text = getString(R.string.tool_compress)
        binding.btnCompress.contentDescription = getString(R.string.tool_compress)
        binding.btnSavePdf.text = getString(R.string.v3_save_copy)
        // Spoken: the visible label also says where the copy goes.
        binding.btnSavePdf.contentDescription = getString(R.string.save_pdf_device)
        // V3: the fourth dock cell is the named Actions sheet, not a generic "More".
        binding.btnMore.text = getString(R.string.doc_actions)
        binding.btnMore.contentDescription = getString(R.string.v3_actions_title)
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
            binding.btnText to getString(R.string.v3_ocr)
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
        // UX-L4-01: clicking "Select pages" enters selection with nothing ticked yet,
        // so the bars must stay up. Only a genuinely empty document leaves the mode.
        if (selectMode && count == 0 && visiblePages.isEmpty()) selectMode = false
        supportActionBar?.subtitle = null
        binding.selectionTopBar.isVisible = selectMode
        binding.selectionActionBar.isVisible = selectMode
        binding.bottomDock.isVisible = !selectMode
        binding.draftBar.isVisible = !selectMode && (draft?.changes ?: 0) > 0
        binding.selCount.text = resources.getQuantityString(R.plurals.selected_count, count, count)
        binding.btnSelAll.isEnabled = count < visiblePages.size
        val reason = document?.let(::protectedReason)
        if (reason != null) binding.selCount.append("\n" + reason)
        for (button in listOf(binding.btnSelShare, binding.btnSelExtract)) {
            button.isEnabled = count > 0 && document != null && reason == null
            button.alpha = if (button.isEnabled) 1f else 0.45f
            button.contentDescription = listOfNotNull(button.text.toString(), reason).joinToString(". ")
        }
        binding.pageList.checkboxesVisible = selectMode
        // The entry control is redundant (and costs height) once selection is open.
        findViewById<View>(R.id.ux130_l4_select_pages).isVisible = !selectMode
    }

    /**
     * TYPE12 item 3: at 200 % five fixed columns squeeze a 12sp label until Android breaks
     * inside the word ("Rotat/e"). A wrapping flow gives each action its natural width and
     * moves a whole action to the next line instead of splitting it.
     */
    private fun reflowSelectionBarForLargeText() {
        if (resources.configuration.fontScale <= 1.5f) return
        val bar = binding.selectionActionBar
        val ids = listOf(
            R.id.btnSelRotate, R.id.btnSelDelete, R.id.btnSelExtract,
            R.id.btnSelShare, R.id.btnSelMore
        )
        val actions = ids.mapNotNull { findViewById<View>(it) }
        if (actions.isEmpty()) return
        for (action in actions) (action.parent as? ViewGroup)?.removeView(action)
        bar.orientation = LinearLayout.VERTICAL
        bar.removeAllViews()
        val flow = com.scanwhirl.app.ui.kit.SwFlowLayout(
            this, horizontalGapDp = 4f, verticalGapDp = 4f
        )
        for (action in actions) {
            action.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            flow.addView(action)
        }
        bar.addView(
            flow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
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

    /** UX-L4-01: enter selection mode from the visible control (no page ticked yet). */
    private fun enterSelectionMode() {
        if (selectMode) return
        selectMode = true
        binding.pageList.checkboxesVisible = true
        binding.pageList.refresh()
        updateSelectionUi()
    }

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
    private fun selectionExportSource(): ScanDocument? {
        val shown = document ?: return null
        val fresh = repo.getDocument(shown.id) ?: return null
        val reason = protectedReason(shown) ?: protectedReason(fresh)
        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            return null
        }
        return fresh
    }

    private fun shareSelectedPages() {
        val doc = selectionExportSource() ?: return
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
        if (document?.let(::protectedReason) != null) {
            actions.removeAll { it.label == R.string.draft_extract_new }
        }
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
                    getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
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

    internal fun moveSelectedPage(to: Int) {
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
                    getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
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
            // The Task can finish after a rotation. An activity-scoped listener is removed on
            // destroy; the guard also covers a callback already queued on the main thread,
            // whose launcher would otherwise be unregistered and throw on launch.
            .addOnSuccessListener(this) { sender ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                insertScanner.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    getString(R.string.scanner_failed, getString(R.string.s1_reason_scanner_failed)),
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

    /** S1 defect 6: a localized reason, never the decoder's raw English/JVM text. */
    private fun insertReason(failure: Throwable?): String = getString(
        if (failure != null && ImageTools.isTooLargePhoto(failure)) R.string.s1_reason_too_large
        else R.string.s1_reason_unreadable
    )

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
            // S1 defect 4: gallery photos can be HEIC/HEIF or another container. The shared
            // normalizer keeps the JPEG fast path and re-encodes the others with EXIF respected.
            Durable.writeFile(file, ImageTools.normalizeGalleryPhoto(bytes))
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
                    getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
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
                    getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
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

        noteImportUsed()
        pendingDeletes.addAll(paths)
        pendingDeleteDeadline = SystemClock.elapsedRealtime() + TrashActions.undoDurationMs(this)
        selected.clear()
        refreshGrid()
        showPendingDeleteUndo()
    }

    private fun showPendingDeleteUndo() {
        if (pendingDeletes.isEmpty()) return
        val remaining = pendingDeleteDeadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            commitPendingDelete()
            return
        }
        deleteUndoHandler.removeCallbacks(expirePageDelete)
        deleteUndoHandler.postDelayed(expirePageDelete, remaining)
        if (pendingDeleteBar != null) return
        val batch = pendingDeletes.toList()
        // An absolute monotonic deadline controls expiry, not a fresh Snackbar timeout on
        // every rotation (nor a second accessibility extension of the remaining duration).
        val bar = Snackbar.make(binding.root, R.string.page_deleted, Snackbar.LENGTH_INDEFINITE)
        pendingDeleteBar = bar
        bar
            .setAction(R.string.undo) {
                clearPageDeleteBar()
                pendingDeletes.removeAll(batch.toSet())
                pendingDeleteDeadline = 0L
                refreshGrid()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (pendingDeleteBar !== transientBottomBar || isChangingConfigurations || isDestroyed) return
                    if (event != DISMISS_EVENT_ACTION) commitPendingDeletes(batch)
                }
            })
            .show()
    }

    private fun clearPageDeleteBar() {
        deleteUndoHandler.removeCallbacks(expirePageDelete)
        val old = pendingDeleteBar
        pendingDeleteBar = null
        old?.dismiss()
    }

    /**
     * Extract (§4.5 contextual bar): the ticked pages become a NEW document —
     * this one keeps every page, its exports and its category.
     */
    private fun extractSelected() {
        selectionExportSource() ?: return
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
                val doc = selectionExportSource() ?: return@launch
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
                    getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
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
        if (pendingDeletes.isEmpty()) {
            pendingDeleteDeadline = 0L
            clearPageDeleteBar()
        }
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
        pendingDeleteDeadline = 0L
        runOnUiThread { clearPageDeleteBar() }
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
                    getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
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
            val savePdfRun: (ScanDocument) -> Unit = { bound ->
                if (!bound.pdfFile.exists() || bound.pdfFile.length() == 0L) {
                    Toast.makeText(this, R.string.pdf_missing, Toast.LENGTH_SHORT).show()
                } else {
                    withStoragePermission(ACTION_SAVE_PDF, bound.id) { savePdfCopy(bound) }
                }
            }
            DocumentActionDispatcher.dispatch(
                activity = this,
                repo = repo,
                docId = doc.id,
                action = DocumentActionDispatcher.Action.SAVE_PDF,
                onUsed = { _ -> noteImportUsed() },
                onFinishEditing = { finishEditingThen(savePdfRun) },
                run = savePdfRun
            )
        }
    }

    /** B2: the PDF copy itself, separate from the permission gate so a recreated screen can re-run it. */
    private fun savePdfCopy(bound: ScanDocument) {
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
                    getString(R.string.pdf_save_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
                    Toast.LENGTH_LONG
                ).show()
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
        val compressRun: (ScanDocument) -> Unit = { bound -> compressFlow.startWith(bound) }
        DocumentActionDispatcher.dispatch(
            activity = this,
            repo = repo,
            docId = doc.id,
            action = DocumentActionDispatcher.Action.COMPRESS,
            onUsed = { _ -> noteImportUsed() },
            onFinishEditing = { finishEditingThen(compressRun) },
            run = compressRun
        )
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
        val signRun: (ScanDocument) -> Unit = { bound ->
            if (ProOffer.require(this)) {
                startActivity(
                    StampEditorActivity.intent(this, bound.id, StampEditorActivity.TOOL_SIGN)
                )
            }
        }
        DocumentActionDispatcher.dispatch(
            activity = this,
            repo = repo,
            docId = doc.id,
            action = DocumentActionDispatcher.Action.SIGN,
            onUsed = { _ -> noteImportUsed() },
            onFinishEditing = { finishEditingThen(signRun) },
            run = signRun
        )
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

    private fun withStoragePermission(kind: String, target: String, action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingDownload = action
            pendingSaveKind = kind
            pendingSaveTarget = target
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ------------------------------------------------------------------
    // Leaving the editor: the draft (PM-2.2c)
    // ------------------------------------------------------------------

    private fun handleEditorBack() {
        if (importDiscardInProgress) return
        // T44: the question only exists for an import the user has not touched. Once acted on
        // (a saved copy, a share, an edit) the import is kept, never offered for Discard here.
        if (freshImport && !importKept) {
            // A dismissed/cancelled/rotated-away question is NOT an answer. Re-ask from disk
            // truth rather than trusting a dialog OnDismissListener (a BottomSheet hides with
            // an animation, so that listener may fire late or not at all in a test/recreate).
            importQuestionSheet?.dismiss()
            importQuestionSheet = null
            freshImportAsked = false
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
     * asks first. Keep leaves it like any other document; Discard has its own finite Undo.
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
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // UXG G7: a failed tap inside this modal must be reported INSIDE it. A Snackbar attached
        // to the activity root is painted behind the sheet, so the person never sees the reason.
        val discardStatus = SwStyle.text(this, 13f,
            SwStyle.color(this, R.color.laser_danger), bold = true, metadata = true).apply {
            setId(R.id.ux_uxg_discard_status)
            text = getString(R.string.ux130_l4_discard_unavailable)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            androidx.core.view.ViewCompat.setAccessibilityLiveRegion(
                this, androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
            )
        }
        choices.addView(discardStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@DocumentViewerActivity, 8f) })
        fun choice(label: Int, icon: Int, safe: Boolean): TextView =
            SwStyle.text(this, 16f, SwStyle.color(this,
                if (safe) R.color.sw_disc_ink else R.color.text_hologram_mid), bold = true).apply {
                text = getString(label)
                this.id = if (safe) R.id.swSheetPrimary else R.id.swSheetSecondary
                gravity = if (safe) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = SwStyle.dp(this@DocumentViewerActivity, if (safe) 54f else 48f)
                minimumWidth = SwStyle.dp(this@DocumentViewerActivity, 48f)
                val p = SwStyle.dp(this@DocumentViewerActivity, 8f)
                setPadding(p, p, p, p)
                compoundDrawablePadding = p
                val glyph = ContextCompat.getDrawable(context, icon)?.mutate()
                glyph?.setTint(currentTextColor)
                val size = SwStyle.dp(this@DocumentViewerActivity, 24f)
                glyph?.setBounds(0, 0, size, size)
                setCompoundDrawablesRelative(glyph, null, null, null)
                if (safe) background = SwStyle.rounded(context,
                    SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f)
                isFocusable = true
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = Button::class.java.name
                    }
                }
            }
        val discard = choice(R.string.u1_import_discard, R.drawable.ic_sym_delete, false)
        val keep = choice(R.string.fixflow_keep, R.drawable.ic_sym_check, true)
        choices.addView(discard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@DocumentViewerActivity, 8f) })
        choices.addView(keep, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // Reserve the entire former Scan/nav zone AND its 24dp exclusion gap for Keep.
            topMargin = SwStyle.dp(this@DocumentViewerActivity, 96f)
        })
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(choices)
        }
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.fixflow_import_keep_title)
            .setMessage(R.string.u1_import_source_unchanged)
            .setScrollableContent(scroll)
            .build()
        // Local icon+label content, not the shared kit footers (owned by another lane).
        (sheet.primaryButton.parent as ViewGroup).removeView(sheet.primaryButton)
        (sheet.secondaryButton.parent as ViewGroup).removeView(sheet.secondaryButton)
        // Safety is enforced at click time, in one coordinate space, for every viewport/font.
        // The row is never disabled/dimmed on a speculative layout pass: a BottomSheet lays
        // itself out in stages, and a transient small height must not silently kill the action.
        fun safelyPlaced(): Boolean {
            val decor = sheet.window?.decorView ?: return false
            val actionAt = IntArray(2)
            val decorAt = IntArray(2)
            discard.getLocationOnScreen(actionAt)
            decor.getLocationOnScreen(decorAt)
            val bottom = actionAt[1] + discard.height - decorAt[1]
            // nav ~100dp + 24dp clearance + 24dp for bars/insets.
            return bottom <= decor.height - SwStyle.dp(this, 148f)
        }
        discard.setOnClickListener {
            if (safelyPlaced()) {
                importQuestionSheet = null
                sheet.dismiss()
                discardImportedCopy(id)
            } else {
                // UX-L4-01 / UX-007: never silently ignore the tap. Keep stays reachable;
                // the reason appears inline in this sheet and is announced, not behind it.
                discardStatus.visibility = View.VISIBLE
                discardStatus.announceForAccessibility(
                    getString(R.string.ux130_l4_discard_unavailable)
                )
            }
        }
        keep.setOnClickListener {
            importQuestionSheet = null
            sheet.dismiss()
            finish()
        }
        importQuestionSheet = sheet
        // Back/X/outside and rotation leave the copy intact; the next Back re-asks from scratch.
        sheet.setOnDismissListener {
            if (importQuestionSheet === sheet) importQuestionSheet = null
            if (!isFinishing && !importDiscardInProgress) freshImportAsked = false
        }
        sheet.show()
    }

    private fun discardImportedCopy(id: String) {
        if (importDiscardInProgress) return
        // T44: never discard an import the user has acted on, whatever asked.
        if (importKept) {
            finish()
            return
        }
        importDiscardInProgress = true
        lifecycleScope.launch {
            val duration = TrashActions.undoDurationMs(this@DocumentViewerActivity)
            val token = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    com.scanwhirl.app.store.ImportDiscardStore(applicationContext, repo).discard(id, duration)
                }.getOrNull()
            }
            importDiscardInProgress = false
            if (token == null) {
                freshImportAsked = false
                Snackbar.make(binding.root, R.string.u1_import_discard_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            DiscardActions.scheduleExpiry(applicationContext, token)
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
            // UX-L4-01: the layout declares 48dp; the runtime paint must not shrink it.
            view.minHeight = dp(48f)
            view.minimumWidth = dp(48f)
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

    /**
     * DV: the W1 answer "Finish editing first" used to be an empty lambda, so the answer was
     * silently dropped. Offer the same save choices the draft bar offers. Once a save succeeds
     * the original action runs on the saved document, so neither the edits nor the person's
     * intent are lost. "Keep editing" is the explicit way to stop without saving.
     */
    private fun finishEditingThen(continueOn: (ScanDocument) -> Unit) {
        if (document == null) return
        SwSheet.builder(this)
            .setTitle(R.string.draft_back_title)
            .setMessage(R.string.dv_finish_editing_msg)
            .setOptions(
                listOf(
                    getString(R.string.draft_save_new),
                    getString(R.string.draft_replace),
                    getString(R.string.draft_keep_editing)
                )
            ) { index, _ ->
                when (index) {
                    0 -> saveDraftFlow(onSaved = continueOn)
                    1 -> replaceFlow(onSaved = continueOn)
                    // 2 (Keep editing): close the sheet, change nothing.
                }
            }
            .build()
            .show()
    }

    /** A continued action still gets the dispatcher's "could not start" safety net. */
    private fun continueAfterSave(continueOn: (ScanDocument) -> Unit, saved: ScanDocument) {
        try {
            continueOn(saved)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Save as new copy: commit pending deletes, ask for a name (prefilled), publish.
     * [onSaved] is set only by "Finish editing first": the caller continues its action on the
     * new copy instead of closing this screen. The draft bar still just saves and finishes.
     */
    private fun saveDraftFlow(onSaved: ((ScanDocument) -> Unit)? = null) {
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
                            if (onSaved == null) {
                                finish()
                            } else {
                                reloadDocument()
                                continueAfterSave(onSaved, copy)
                            }
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

    /**
     * Replace original: confirm, then the two-rename publish (previous version goes to Trash).
     * [onSaved] continues the original output on the published document after the draft is gone.
     */
    private fun replaceFlow(onSaved: ((ScanDocument) -> Unit)? = null) {
        val id = docId ?: return
        val doc = document ?: return
        SwSheet.builder(this)
            .setTitle(getString(R.string.draft_replace_title, doc.name))
            .setMessage(getString(R.string.draft_replace_msg, doc.name))
            .setPrimary(R.string.draft_replace) {
                lifecycleScope.launch {
                    try {
                        val published = withContext(Dispatchers.IO) {
                            commitPendingDeletesNow()
                            repo.replaceWithDraft(id).document
                        }
                        Toast.makeText(
                            this@DocumentViewerActivity,
                            R.string.draft_replaced,
                            Toast.LENGTH_LONG
                        ).show()
                        reloadDocument()
                        if (onSaved != null) continueAfterSave(onSaved, published)
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
                    pendingDeleteDeadline = 0L
                    clearPageDeleteBar()
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
            pendingDeleteDeadline = 0L
            clearPageDeleteBar()
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
            pendingDeleteDeadline = 0L
            clearPageDeleteBar()
            reloadDocument()
        }
    }

    private fun showDraftError(e: Exception) {
        if (e is DraftStore.DraftStale || e is DraftStore.DraftDamaged) {
            showDraftUnusable(V2vFailureCopy.message(this@DocumentViewerActivity, e))
        } else {
            Toast.makeText(
                this,
                getString(R.string.page_op_failed, V2vFailureCopy.message(this@DocumentViewerActivity, e)),
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
        private const val ACTION_SAVE_PDF = "viewer_save_pdf"
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
        private const val KEY_PENDING_DELETES = "pending_page_deletes"
        private const val KEY_DELETE_DEADLINE = "page_delete_deadline"
        /** DV: ticked page paths and selection mode survive configuration changes. */
        private const val KEY_SELECTED_PAGES = "selected_pages"

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
