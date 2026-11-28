package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.TrashActions
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.DocSearchHit
import com.scanwhirl.app.ui.kit.SwChip
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.util.AppLanguage
import com.scanwhirl.app.util.AppLocale
import com.scanwhirl.app.util.BatchRename
import com.scanwhirl.app.util.CategoryLabels
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.PageTextMatches
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SearchMatcher
import com.scanwhirl.app.util.SizeUnits
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * L1-02: the user's place in the library is part of the shell's saved state. Kept as a
 * plain snapshot so [ShellActivity] can put it in the instance bundle without reaching
 * into this screen's private fields. Selection ids are filtered against loaded documents
 * on restore, so an anchor that was removed meanwhile falls back safely.
 */
data class LibraryUiState(
    val query: String,
    val folder: String?,
    val category: String?,
    val sort: String,
    val selection: List<String>,
    val selectionMode: Boolean,
    val scrollY: Int
)

/**
 * UI-B1 (v4 Home, UI_PLAN §0b) + owner decision UX-008: the Library is documents first. Header
 * (mark, A/अ), search, compact folder chips, a compact customisable Quick tools row, the Recent
 * sort row, category chips and the real document rows.
 *
 * The old creation tiles are gone from this screen: the centre Scan action opens [CreateSheet].
 * Quick tools came back on the owner's 2026-11-21 decision (UX-008): ONE compact horizontally
 * scrolling row of icon tiles, a pencil that opens one editor sheet (add/remove/reorder/reset,
 * up to 12), and the same Pro gate as the Tools tab. The trust line moved to About and is still
 * shown on the empty library. The first document card still clears the dock at 360x640dp.
 */
class LibraryScreen(
    private val context: Context,
    private val host: ToolHost,
    private val repo: ScanRepository,
    private val callbacks: Callbacks
) : LinearLayout(context) {

    interface Callbacks {
        fun onScan()
        fun onCreatePhotos()
        fun onCreateImport()
        fun onCreateIdCard()
        fun onAllTools()
        fun onOpenDoc(doc: ScanDocument)
        fun onShareDoc(doc: ScanDocument)
        fun onMoreDoc(doc: ScanDocument)
        /** Several documents from selection mode. */
        fun onShareDocs(docs: List<ScanDocument>)
        /** A Pro-only library action was tapped in a Free build. */
        fun onProRequired()
        /**
         * T40-FIX-SNACK-ANCHOR: a selection moved to Trash. The host shows the
         * "Moved N documents to Trash" Undo bar, because only it knows the bottom
         * nav the bar must sit above (a null anchor drew Undo under the nav on the
         * phone and the tap opened Me).
         */
        fun onMovedToTrash(moved: TrashActions.Moved)
    }

    lateinit var searchInput: EditText
    lateinit var docList: LinearLayout

    // UX-008: the Home Quick tools row. The saved ids come from Prefs (which the backup
    // allowlist keeps); the sheet edits a draft so closing it without Done changes nothing.
    private var quickIds: MutableList<String> =
        com.scanwhirl.app.util.Prefs.quickTools(context).toMutableList()
    private var quickDraft: MutableList<String>? = null
    private var quickSheet: SwSheet? = null
    /** Council §7: the one folder+category chooser sheet. */
    private var chooserSheet: SwSheet? = null
    private lateinit var quickHeader: View
    private lateinit var quickRow: HorizontalScrollView
    private lateinit var quickStrip: LinearLayout
    /** Council §7: a plain "Customize" text button replaces the bare pencil. */
    private lateinit var quickCustomize: TextView
    private lateinit var quickAll: TextView
    private val quickLabels = mapOf(
        "merge" to R.string.cb_quick_merge,
        "compress" to R.string.cb_quick_compress,
        "sign" to R.string.cb_quick_sign,
        "protect" to R.string.cb_quick_protect,
        "to_word" to R.string.quick_to_word,
        // Council §1: "OCR" is the single label for text extraction. Lane N renames the
        // registry id; accept both the old and the new id so this lane is order-independent.
        "read_text" to R.string.cb_quick_ocr,
        "ocr" to R.string.cb_quick_ocr
    )

    private lateinit var chipRow: LinearLayout
    private var chipScroll: View? = null
    private val emptyView: LinearLayout
    private lateinit var listHeader: LinearLayout
    private lateinit var countLabel: TextView
    private var sortAction: TextView? = null
    private var sort: String = com.scanwhirl.app.util.Prefs.LIB_NEWEST

    // v2 UI: 03_tools_hub — folders, tags and selection mode.
    /** Council §7: folder rows live in the shared folder/category chooser sheet. */
    private lateinit var folderRow: LinearLayout
    /** Council §7: category rows live in the same shared chooser sheet. */
    private lateinit var categoryRow: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    /** T32: the scroll hook that keeps the selection bar pinned in the viewport. */
    private var selectionPin: View.OnScrollChangeListener? = null
    private var folderNames: List<String> = emptyList()
    private var folder: String? = null
    private var selectionMode = false
    private val selected = linkedSetOf<String>()
    private var lastBatchUndo: List<Pair<String, String>>? = null
    private var listStaggered = false
    /** The last batch-rename Undo snackbar (tests inspect its anchored, accessible action). */
    internal var lastUndoSnackbar: com.google.android.material.snackbar.Snackbar? = null
    private var recentHeader: View? = null
    private var folderScroll: View? = null
    private lateinit var controlBand: LinearLayout
    /** Council §7: the active filter is a removable chip that appears only while filtering. */
    private lateinit var filterRow: LinearLayout
    /** The list heading; its text follows the chosen sort ("Recent" or "Documents"). */
    private lateinit var docHeading: TextView
    private lateinit var activeFilter: TextView
    private lateinit var noMatchView: TextView
    private lateinit var homeClear: ImageButton
    /** L1-05: live result count + the folder/category scope it applies to. */
    private lateinit var searchStatus: TextView

    private var docs: List<ScanDocument> = emptyList()
    private var query = ""
    private var category: String? = null
    private var revealId: String? = null

    /**
     * UI-W4: every render gets a token; only the newest pass may bind rows. Cancelling
     * the previous job is not enough when a slow text read is already off the main
     * thread, so fast typing or clearing can never re-show an older query's rows.
     */
    private var renderGeneration = 0

    /** T28: page + snippet evidence for the text hits currently shown, by document id. */
    private var rowEvidence: Map<String, DocSearchHit> = emptyMap()

    // uihome: the pinned layer (sticky count + fast-scroll bar) lives above the
    // ScrollView that ShellActivity owns; this screen may not edit that activity,
    // so it installs the overlay into the scroll view's host on attach.
    private var chrome: LibraryViewportChrome? = null

    // L1 performance: never read the library on the main thread.
    private var loadJob: Job? = null
    private var renderJob: Job? = null
    private var renderedDocs: List<ScanDocument> = emptyList()
    private var renderedSignatures: List<String> = emptyList()

    /** Tests wait on this after [reload] when they force a threaded IO dispatcher. */
    internal var loaded = false
        private set

    /**
     * L1-02: one restore pass per instance. A snapshot queued before the async load finishes
     * is applied exactly once, after [docs] is current, so no duplicate render job is started.
     */
    private var pendingRestore: LibraryUiState? = null
    internal var restoreAppliedCount: Int = 0
        private set

    internal fun captureLibraryState(): LibraryUiState = LibraryUiState(
        query = query,
        folder = folder,
        category = category,
        sort = sort,
        selection = selected.toList(),
        selectionMode = selectionMode,
        scrollY = (context as? ShellActivity)?.libraryScrollY() ?: 0
    )

    /** Queue a snapshot; if the data is already loaded it is applied at once. */
    internal fun restoreLibraryState(state: LibraryUiState) {
        if (pendingRestore != null || restoreAppliedCount > 0) return
        pendingRestore = state
        if (loaded) applyPendingRestore()
    }

    private fun applyPendingRestore() {
        val s = pendingRestore ?: return
        pendingRestore = null
        restoreAppliedCount++
        query = s.query
        if (::searchInput.isInitialized) searchInput.setText(s.query)
        folder = s.folder
        category = s.category
        sort = s.sort
        com.scanwhirl.app.util.Prefs.setLibrarySort(context, sort)
        renderSortLabel()
        selectionMode = s.selectionMode
        selected.clear()
        val known = docs.map { it.id }.toSet()
        selected.addAll(s.selection.filter { it in known })
        renderFolders()
        renderChips()
        renderDocs()
        if (s.scrollY > 0) {
            post { (context as? ShellActivity)?.restoreLibraryScrollY(s.scrollY) }
        }
    }

    /**
     * L1lib #1: no hard cap. [allVisibleDocs] is the full filtered/sorted result;
     * [cardsShown] is how many rows are currently bound. The first page keeps the old
     * 60-card look; scrolling appends the next page until every document is reachable.
     */
    private var allVisibleDocs: List<ScanDocument> = emptyList()
    private var cardsShown = INITIAL_CARDS
    private val textCache by lazy { com.scanwhirl.app.store.OcrTextCache(repo) }

    init {
        orientation = VERTICAL
        sort = com.scanwhirl.app.util.Prefs.librarySort(context)
        setPadding(0, 0, 0, SwStyle.dp(context, 12f))
        // No background: the shell's tinted gradient is the page background. An opaque
        // rv_bg fill here painted over it from the list down, so Home's tint looked cut
        // (Tools, Vault and Me already leave their page transparent).

        addView(appBar())
        addView(searchBox())
        noMatchView = SwStyle.text(
            context, 14f, SwStyle.color(context, R.color.text_hologram_mid), bold = true
        ).apply {
            id = R.id.swNoMatches
            gravity = Gravity.CENTER
            setPadding(
                SwStyle.dp(context, 18f), SwStyle.dp(context, 26f),
                SwStyle.dp(context, 18f), SwStyle.dp(context, 10f)
            )
            isVisible = false
            background = readableBand()
        }
        addView(noMatchView)
        // Council §7: ONE row holds All folders ▾, Newest ▾ and the live count.
        buildControlBand()
        addView(controlBand)
        // Council §7: the Filter row exists only while a filter is active (a removable chip).
        filterRow = LinearLayout(context).apply {
            id = R.id.cbFilterRow
            orientation = HORIZONTAL
            isVisible = false
            background = readableBand()
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 4f), m, 0)
            }
        }
        addView(filterRow)
        activeFilter = SwStyle.text(
            context,
            if (AppLanguage.showingHindi(context)) 14f else 12.5f,
            SwStyle.color(context, R.color.cyan_primary)
        ).apply {
            id = R.id.swActiveFilter
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            minimumHeight = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(SwStyle.dp(context, 4f), 0, SwStyle.dp(context, 4f), 0)
            background = readableBand()
        }
        filterRow.addView(activeFilter, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        SwStyle.iconButton(
            context, R.drawable.ic_sym_close, SwStyle.color(context, R.color.text_hologram_mid)
        ).apply {
            id = R.id.cbRemoveFilter
            contentDescription = context.getString(R.string.cb_remove_filter)
            minimumHeight = SwStyle.dp(context, 48f)
            minimumWidth = SwStyle.dp(context, 48f)
            setOnClickListener {
                folder = null
                category = null
                renderFolders()
                renderChips()
                renderDocs()
            }
            filterRow.addView(this, LayoutParams(SwStyle.dp(context, 48f), SwStyle.dp(context, 48f)))
        }
        // The list heading follows the sort. Keeping it as plain text (not a floating chip)
        // means it never covers the first row and keeps the "heading follows sort" intent.
        docHeading = SwStyle.text(
            context, 15f, SwStyle.color(context, R.color.text_hologram_hi), bold = true
        ).apply {
            id = R.id.cbDocHeading
            text = context.getString(R.string.home_recent)
            contentDescription = text
            setPadding(
                SwStyle.dp(context, 18f), SwStyle.dp(context, 8f),
                SwStyle.dp(context, 18f), SwStyle.dp(context, 2f)
            )
            maxLines = 2
            ellipsize = null
            background = readableBand()
        }
        // A sort persisted from before this screen was created must already have the
        // right heading; renderSortLabel() ran while docHeading was not yet initialised.
        renderSortLabel()
        quickHeader = quickToolsHeader().apply { background = readableBand() }
        addView(quickHeader)
        // Council §7: ONE horizontally scrolling row on Home. The editor is capped at 6,
        // but the document list must own the first screen (proved at 360x640dp).
        quickStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
        quickRow = HorizontalScrollView(context).apply {
            id = R.id.swQuickRow
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(SwStyle.dp(context, 18f))
            addView(quickStrip, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        addView(quickRow)
        addView(docHeading)

        val selectBar = selectionBar()

        docList = LinearLayout(context).apply {
            id = R.id.swDocList
            orientation = VERTICAL
            // L1-04: reserve the fast-scroll strip's gutter so its >=48dp target never covers
            // a card's Share/More hit area. The last card still clears the real dock (M5).
            setPadding(0, 0, 0, listBottomInset())
            // U5: the lifted Scan disc grows the measured dock AFTER the first layout pass.
            // Re-read it on every layout so the last card always clears the real dock.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val inset = listBottomInset()
                if (paddingBottom != inset) setPadding(paddingLeft, paddingTop, paddingRight, inset)
            }
        }

        emptyView = LinearLayout(context).apply {
            id = R.id.swHomeEmpty
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isVisible = false
            setPadding(
                SwStyle.dp(context, 24f), SwStyle.dp(context, 14f),
                SwStyle.dp(context, 24f), SwStyle.dp(context, 10f)
            )
            val d = SwStyle.dp(context, 44f)
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_sym_document_scanner)
                    imageTintList = ColorStateList.valueOf(
                        SwStyle.color(context, R.color.text_hologram_dim)
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(d, d)
            )
            addView(
                SwStyle.text(context, 18f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                    text = context.getString(R.string.home_empty_title)
                    gravity = Gravity.CENTER
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 14f) }
            )
            addView(
                SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
                    text = context.getString(R.string.home_empty_body)
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.3f)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 6f) }
            )
            // UI-B1 (#1): an empty library still offers both starting routes, not just the disc:
            // the camera scan and the import chooser. The trust line stays here (IDENTITY.md).
            addView(
                SwStyle.text(context, 16f, SwStyle.color(context, R.color.sw_disc_ink), bold = true).apply {
                    id = R.id.swEmptyScan
                    text = context.getString(R.string.create_scan)
                    gravity = Gravity.CENTER
                    background = SwStyle.rounded(
                        context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
                    )
                    minimumHeight = SwStyle.dp(context, 54f)
                    maxLines = 2
                    setPadding(
                        SwStyle.dp(context, 12f), SwStyle.dp(context, 8f),
                        SwStyle.dp(context, 12f), SwStyle.dp(context, 8f)
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { callbacks.onScan() }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 18f) }
            )
            addView(
                SwStyle.text(context, 14f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
                    id = R.id.swEmptyImport
                    text = context.getString(R.string.empty_import_button)
                    gravity = Gravity.CENTER
                    minimumHeight = SwStyle.dp(context, 48f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { callbacks.onCreateImport() }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f))
                    .apply { topMargin = SwStyle.dp(context, 4f) }
            )
            addView(
                SwStyle.text(
                    context,
                    if (AppLanguage.showingHindi(context)) 14f else 12.5f,
                    SwStyle.color(context, R.color.text_hologram_mid)
                ).apply {
                    id = R.id.swEmptyTrust
                    text = context.getString(R.string.trust_offline_no_ads)
                    gravity = Gravity.CENTER
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 16f) }
            )
        }
        // v2 UI: 01_home_A — the selection bar OVERLAYS the list (it never
        // shrinks it): top bar over the first rows, which get a matching inset.
        val listHost = FrameLayout(context).apply { id = R.id.swListHost }
        listHost.addView(
            docList,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        listHost.addView(
            emptyView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        listHost.addView(
            selectBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        )
        addView(
            listHost,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        // Only now: buildQuickRows() calls updateChrome(), which needs the list views above.
        buildQuickRows()
    }

    // ---- pinned library chrome -------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        installChrome()
        installSelectionPin()
        // G6: the dock grows with wrapped nav labels, so re-measure the list's reserved
        // clearance after the shell's own layout pass, not only on the first render.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val inset = listBottomInset()
            if (docList.paddingBottom != inset) {
                docList.setPadding(docList.paddingLeft, docList.paddingTop, docList.paddingRight, inset)
            }
        }
    }

    override fun onDetachedFromWindow() {
        // The host (ShellActivity's tab content) clears its children on a tab switch.
        // Drop the scroll listener but never remove a sibling while the host is
        // iterating its children; installChrome() adopts a surviving overlay.
        releaseSelectionPin()
        chrome?.release()
        chrome = null
        super.onDetachedFromWindow()
    }

    /**
     * uihome: installs the sticky count + fast-scroll bar over the library. The
     * parent ScrollView is created by ShellActivity (not this lane's file), so the
     * overlay is added to that ScrollView's FrameLayout host.
     */
    private fun installChrome() {
        val scroll = parent as? ScrollView ?: return
        val host = scroll.parent as? ViewGroup ?: return
        val pinned = host.findViewById<LibraryViewportChrome>(R.id.swLibraryChrome)
            ?: LibraryViewportChrome(context).also {
                host.addView(
                    it,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        chrome = pinned
        pinned.attach(scroll)
        // L1lib #1: the fast-scroll thumb must imply every document is reachable, so it
        // reflects the full filtered result, not only the cards bound on the first page.
        pinned.setDocumentCount(allVisibleDocs.size)
    }

    // ---- public -----------------------------------------------------------

    /** Re-binds the cards after a Free/Pro change (the Create sheet reads Pro itself). */
    fun refreshProState() {
        renderDocs()
    }

    /** PERF 6: observable count of listing requests, for the cold-start budget test. */
    internal var reloadRequests: Int = 0
        private set

    fun reload() {
        reloadRequests++
        val owner = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
        if (owner == null) {
            // No lifecycle (unit-only host): keep the old synchronous behaviour.
            docs = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
            loaded = true
            if (pendingRestore != null) applyPendingRestore()
            else {
                renderFolders()
                renderChips()
                renderDocs()
            }
            return
        }
        loadJob?.cancel()
        loadJob = owner.launch {
            // L1-1: the whole library load (including the per-page stats inside
            // listDocuments) happens off the main thread; the old list stays up.
            val fresh = withContext(ioDispatcher) {
                runCatching { repo.listDocuments() }.getOrDefault(emptyList())
            }
            if (!isActive) return@launch
            docs = fresh
            loaded = true
            // L1-02: apply the queued snapshot now that the document list is current.
            if (pendingRestore != null) applyPendingRestore()
            else {
                renderFolders()
                renderChips()
                renderDocs()
            }
        }
    }

    /**
     * C05: folder/tag/batch metadata writes rewrite every touched `meta.txt` with an fsync; they
     * run off the main thread, one writer at a time, then [after] (UI work) resumes on main.
     *
     * L1lib #3: a disk-full failure (`Durable.SyncException`) must never escape the coroutine
     * and crash the app. On failure we skip [after] (it assumes the write succeeded), show an
     * honest, localised message and reload so the cards match what is actually on disk.
     * [onError] lets a multi-document action report a partial count, e.g. "N moved".
     */
    private fun <T> ioWrite(
        block: () -> T,
        onError: (suspend () -> Unit)? = null,
        after: (T) -> Unit = {}
    ) {
        val fail: suspend () -> Unit = { if (onError != null) onError() else writeFailed() }
        val owner = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
        if (owner == null) {
            val result = try {
                block()
            } catch (t: Exception) {
                runBlocking { fail() }
                return
            }
            after(result)
            return
        }
        owner.launch {
            val result = try {
                metaWrite.withLock { withContext(ioDispatcher) { block() } }
            } catch (t: Exception) {
                withContext(Dispatchers.Main) { fail() }
                return@launch
            }
            after(result)
        }
    }

    /** Honest, localised fallback for a write we cannot attribute to a specific action. */
    private fun writeFailed() {
        Toast.makeText(context, context.getString(R.string.l1lib_write_failed), Toast.LENGTH_LONG).show()
        reload()
    }

    private val metaWrite = kotlinx.coroutines.sync.Mutex()

    /**
     * v2 UI: 01_home_A - highlight and scroll to a document after a tool's "Show in library".
     * The target is kept so the post-onResume reload shows it highlighted too.
     */
    fun revealDocument(id: String) {
        revealId = id
        query = ""
        searchInput.setText("")
        searchInput.clearFocus()
        folder = null
        category = null
        sort = com.scanwhirl.app.util.Prefs.LIB_NEWEST
        com.scanwhirl.app.util.Prefs.setLibrarySort(context, sort)
        renderSortLabel()
        reload()
    }

    /** v2 UI: 03_tools_hub — the Folders tool lands here with the folder row in view. */
    fun focusFolders() {
        // UI-W4: the tool opens the explicit folder list instead of a toast that told the
        // person to discover a long-press.
        renderFolders()
        showAllFolders()
    }

    /** v2 UI: 03_tools_hub — Batch rename: select two or more documents first. */
    fun startBatchRename() {
        if (docs.size < 2) {
            Toast.makeText(context, context.getString(R.string.batch_select_hint), Toast.LENGTH_SHORT).show()
            return
        }
        selectionMode = true
        selected.clear()
        renderDocs()
        Toast.makeText(context, context.getString(R.string.batch_select_hint), Toast.LENGTH_SHORT).show()
    }

    /** Undo the last applied batch rename (the snackbar action calls this). */
    fun undoLastBatchRename() {
        val undo = lastBatchUndo ?: return
        lastBatchUndo = null
        ioWrite({ undo.forEach { (id, name) -> repo.renameDocument(id, name) } }, after = { reload() })
    }

    // ---- folders ----------------------------------------------------------

    /** True at the device's 200 % setting, where whole-unit stacking beats density. */
    private fun largeFont(): Boolean = resources.configuration.fontScale >= 1.6f

    /**
     * Council §7: ONE row — [All folders ▾] [Newest ▾]   9 documents.
     * Folder and category share one chooser sheet; sort keeps its own sheet. The old
     * "Folder:" / "Filter:" / "Sort:" captions leave the face; their meaning survives as
     * TalkBack names on the three controls. At large text the row stacks into whole-unit
     * lines so a single word never clips (the V2L device-reflow contract).
     */
    private fun buildControlBand() {
        val band = LinearLayout(context).apply {
            id = R.id.uxL1ControlBand
            orientation = if (largeFont()) VERTICAL else HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = readableBand()
            minimumHeight = SwStyle.dp(context, 48f)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 4f), m, 0)
            }
        }
        fun controlParams(): LayoutParams = if (largeFont()) {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 4f) }
        } else {
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        fun control(id: Int, talkbackRes: Int, onClick: () -> Unit): TextView = SwStyle.text(
            context,
            if (AppLanguage.showingHindi(context)) 14f else 13f,
            SwStyle.color(context, R.color.cyan_primary), bold = true
        ).apply {
            this.id = id
            minimumHeight = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = if (largeFont()) Int.MAX_VALUE else 1
            ellipsize = null
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = context.getString(talkbackRes)
        }

        val folderControl = control(R.id.swAllFolders, R.string.cb_folder_talkback) { showAllFolders() }
        folderControl.text = context.getString(R.string.cb_all_folders_control)
        band.addView(folderControl, controlParams())

        sortAction = control(R.id.swSort, R.string.cb_sort_talkback) { showSortSheet() }
        band.addView(sortAction, controlParams())

        countLabel = SwStyle.text(
            context, 13f, SwStyle.color(context, R.color.text_hologram_dim), bold = true
        ).apply {
            id = R.id.swDocCount
            minimumHeight = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = null
            contentDescription = context.getString(R.string.cb_count_talkback)
        }
        band.addView(countLabel, controlParams())

        controlBand = band
        listHeader = band
        renderSortLabel()
    }

    /**
     * G3: text over a bright wallpaper photo needs a real surface under it. The shell's
     * readable wallpaper scrim deliberately stays at its tested strength, so the library
     * puts its exposed text rows on the same near-opaque glass surface its cards and
     * search field already use. This is a visual surface only - it changes no geometry.
     */
    private fun readableBand(): android.graphics.drawable.Drawable = SwStyle.rounded(
        context, SwStyle.color(context, R.color.glass_surface), radiusDp = 12f
    )

    private fun folderSection(): View {
        // Compact folder chips: one scrolling row under the search field. The A/अ language
        // chip moved up into the header (v4 header: mark, A/अ, search).
        //
        // UI-W4: the rail is followed by one plain sentence naming the active folder, so
        // the current filter is identifiable without relying on chip colour alone (200 %,
        // EN or HI). The sentence is hidden when no folder filter is active.
        val section = LinearLayout(context).apply {
            orientation = VERTICAL
            // L1-01: the parent control band owns the page margins.
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val scroll = HorizontalScrollView(context).apply {
            id = R.id.swFolderScroll
            isHorizontalScrollBarEnabled = false
            // ATLAS-F2 (001): same affordance as the category rail — folders keep going past
            // the edge instead of the last one looking cut in half.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(SwStyle.dp(context, 24f))
        }
        folderRow = LinearLayout(context).apply {
            id = R.id.swFolderRow
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ATLAS-F2 (001): end padding so the last folder chip can rest wholly inside the
            // rail instead of touching the screen edge after a swipe.
            setPadding(0, 0, SwStyle.dp(context, 18f), 0)
        }
        scroll.addView(folderRow)
        // The scroll stays a direct child of the section that search mode hides, so the
        // existing collapse test (and the visible behaviour) still sees the rail's parent.
        section.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        activeFilter = SwStyle.text(
            context,
            // UI_PLAN 0.8: Hindi body/meta starts at 14sp.
            if (AppLanguage.showingHindi(context)) 14f else 12.5f,
            SwStyle.color(context, R.color.cyan_primary)
        ).apply {
            id = R.id.swActiveFilter
            maxLines = if (resources.configuration.fontScale >= 1.6f) 3 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            val pad = SwStyle.dp(context, 2f)
            setPadding(pad, pad, pad, pad)
            isVisible = false
            background = readableBand()
        }
        section.addView(activeFilter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return section
    }

    private fun renderFolders() {
        folderNames = runCatching { repo.listFolders() }.getOrDefault(emptyList())
        // Council §7: the folder rail lives inside the shared chooser sheet, so a reload
        // before the sheet is ever opened has nothing to populate.
        if (!::folderRow.isInitialized) return
        folderRow.removeAllViews()
        folderRow.addView(sheetChoice(context.getString(R.string.folder_all), folder == null) {
            chooserSheet?.dismiss()
            folder = null
            renderFolders()
            renderDocs()
        })
        // PRO_PLAN line 10-11: creating a folder is FREE; tags stay Pro.
        folderRow.addView(
            sheetChoice(context.getString(R.string.folder_new), false) { newFolderSheet() }
                .apply { id = R.id.swNewFolder }
        )
        for (name in folderNames) {
            val count = docs.count { it.folder.equals(name, true) }
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = SwStyle.dp(context, 48f)
            }
            row.addView(
                sheetChoice("$name ($count)", folder.equals(name, true)) {
                    chooserSheet?.dismiss()
                    folder = if (folder.equals(name, true)) null else name
                    renderFolders()
                    renderDocs()
                }.apply {
                    // Kept as a shortcut for the existing long-press gesture, never the only route.
                    setOnLongClickListener {
                        showFolderOptions(name)
                        true
                    }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                SwStyle.iconButton(
                    context, R.drawable.ic_sym_more_vert, SwStyle.color(context, R.color.text_hologram_mid)
                ).apply {
                    contentDescription = context.getString(R.string.folder_more_desc, name)
                    layoutParams = LayoutParams(SwStyle.dp(context, 48f), SwStyle.dp(context, 48f))
                    setPadding(
                        SwStyle.dp(context, 12f), SwStyle.dp(context, 12f),
                        SwStyle.dp(context, 12f), SwStyle.dp(context, 12f)
                    )
                    setOnClickListener { showFolderOptions(name) }
                }
            )
            folderRow.addView(row)
        }
    }

    /** One full-width chooser row (folder or category) with a real 48dp touch target. */
    private fun sheetChoice(label: String, active: Boolean, onClick: () -> Unit): TextView =
        SwStyle.text(
            context, 15f,
            if (active) SwStyle.color(context, R.color.cyan_primary)
            else SwStyle.color(context, R.color.text_hologram_hi)
        ).apply {
            text = label
            minimumHeight = SwStyle.dp(context, 48f)
            minimumWidth = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = null
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            isClickable = true
            isFocusable = true
            if (active) {
                // V028: the selected folder/category has a visible marker, not just teal text.
                val check = androidx.appcompat.content.res.AppCompatResources
                    .getDrawable(context, R.drawable.ic_sym_check)?.mutate()
                val size = SwStyle.dp(context, 18f)
                check?.setBounds(0, 0, size, size)
                check?.setTint(SwStyle.color(context, R.color.cyan_primary))
                setCompoundDrawablesRelative(check, null, null, null)
                compoundDrawablePadding = SwStyle.dp(context, 8f)
                contentDescription = context.getString(R.string.sort_selected, label)
            }
            setOnClickListener { onClick() }
        }

    private fun folderChip(
        label: String,
        active: Boolean,
        accent: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val color = when {
            active || accent -> SwStyle.color(context, R.color.cyan_primary)
            else -> SwStyle.color(context, R.color.text_hologram_mid)
        }
        val chip = SwStyle.text(context, 13f, color, bold = true).apply {
            text = label
            setPadding(SwStyle.dp(context, 14f), 0, SwStyle.dp(context, 14f), 0)
            minimumHeight = SwStyle.dp(context, 48f)
            // M8 (device sweep): "All" measured 44dp wide, under the 48dp touch floor.
            minimumWidth = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER
            // ATLAS-F2: a chip never breaks a word or cuts one with an ellipsis. A long name
            // stays whole; the rail scrolls instead.
            ellipsize = null
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            background = SwStyle.pill(
                context,
                color,
                if (active) SwStyle.color(context, R.color.sw_cyan_soft) else SwStyle.color(context, R.color.glass_surface),
                if (active || accent) SwStyle.color(context, R.color.sw_cyan_hair_strong)
                else SwStyle.color(context, R.color.sw_chip_border)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        folderRow.addView(
            chip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = SwStyle.dp(context, 8f) }
        )
        return chip
    }

    private fun newFolderSheet() {
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.folder_new)
            .setInput(hint = context.getString(R.string.folder_name_hint))
            .setSecondary(R.string.sheet_cancel)
            .build()
        sheet.setPrimary(R.string.folder_create) {
            val name = sheet.inputView?.text?.toString().orEmpty()
            ioWrite({ repo.createFolder(name) }) { created ->
                if (!created) {
                    Toast.makeText(context, context.getString(R.string.folder_exists), Toast.LENGTH_SHORT).show()
                }
                reload()
            }
        }
        sheet.show()
    }

    private fun showFolderOptions(name: String) {
        val sheet = SwSheet.builder(context)
            .setTitle(name)
            .setMessage(R.string.folder_options_note)
            .setPrimary(R.string.folder_rename) { renameFolderSheet(name) }
            .setSecondary(R.string.folder_delete) { confirmDeleteFolder(name) }
            .build()
        sheet.show()
    }

    /**
     * Council §7: folder and category share ONE chooser sheet behind "All folders ▾".
     * Every folder (even an empty one) gets a named row and its own More; New folder stays
     * the primary action. Category rows below filter by category. Deleting a folder only
     * removes the folder, never a document ([ScanRepository.deleteFolder]).
     */
    private fun showAllFolders() {
        val names = runCatching { repo.listFolders() }.getOrDefault(emptyList())
        val list = LinearLayout(context).apply {
            id = R.id.swFolderSheet
            orientation = VERTICAL
        }

        list.addView(sheetHeading(context.getString(R.string.ux130_l1_manage_folders)))
        folderRow = LinearLayout(context).apply {
            id = R.id.swFolderRow
            orientation = VERTICAL
        }
        renderFolders()
        list.addView(folderRow)

        if (names.isEmpty()) {
            list.addView(
                SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
                    id = R.id.swFolderSheetEmpty
                    text = context.getString(R.string.folder_none)
                    setPadding(0, SwStyle.dp(context, 10f), 0, SwStyle.dp(context, 6f))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }

        // V111: say plainly that categories are a second, separate way to organise, so
        // "All folders", "Manage folders" and "Set category" do not read as one system.
        list.addView(sheetHeading(context.getString(R.string.rs_categories_separate)))
        categoryRow = LinearLayout(context).apply {
            id = R.id.swChipRow
            orientation = VERTICAL
        }
        renderChips()
        list.addView(categoryRow)

        val scroll = androidx.core.widget.NestedScrollView(context).apply {
            isFillViewport = false
            addView(list)
        }
        if (names.size > 7) {
            scroll.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.5f).toInt()
            )
        }
        val built = SwSheet.builder(context)
            .setTitle(R.string.folder_all_folders)
            .setContent(scroll)
            .setSecondary(R.string.sheet_cancel)
            .build()
        // V027: "New folder" is the one creation control — the plain row right under "All".
        chooserSheet = built
        built.show()
    }

    /** A section caption inside the shared folder/category chooser. */
    private fun sheetHeading(label: String): TextView =
        // V028: a group caption is neutral, not another teal "selected" control.
        SwStyle.text(context, 13f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
            text = label
            contentDescription = label
            minimumHeight = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
        }

    private fun renameFolderSheet(name: String) {
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.folder_rename)
            .setInput(initial = name, hint = context.getString(R.string.folder_name_hint))
            .setSecondary(R.string.sheet_cancel)
            .build()
        sheet.setPrimary(R.string.save) {
            val next = sheet.inputView?.text?.toString().orEmpty()
            ioWrite({ repo.renameFolder(name, next) }) { renamed ->
                if (!renamed) {
                    Toast.makeText(context, context.getString(R.string.folder_exists), Toast.LENGTH_SHORT).show()
                } else if (folder.equals(name, true)) {
                    folder = com.scanwhirl.app.store.LibraryMeta.normalizeFolder(next)
                }
                reload()
            }
        }
        sheet.show()
    }

    private fun confirmDeleteFolder(name: String) {
        SwSheet.builder(context)
            .setTitle(R.string.folder_delete_title)
            .setMessage(context.getString(R.string.folder_delete_msg, name))
            .setPrimary(R.string.folder_delete) {
                ioWrite({ repo.deleteFolder(name) }) {
                    if (folder.equals(name, true)) folder = null
                    reload()
                    Toast.makeText(context, context.getString(R.string.folder_deleted), Toast.LENGTH_SHORT).show()
                }
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    private fun requirePro(action: () -> Unit) {
        if (ProGate.isPro(context)) action() else callbacks.onProRequired()
    }

    // ---- selection mode ---------------------------------------------------

    private fun selectionBar(): View {
        val bar = LinearLayout(context).apply {
            id = R.id.swSelectionBar
            orientation = VERTICAL
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface_raised),
                radiusDp = 18f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair_strong)
            )
            val pad = SwStyle.dp(context, 10f)
            setPadding(pad, pad, pad, pad)
            isVisible = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 4f), m, SwStyle.dp(context, 4f))
            }
            // T32: the reserved list space follows the bar's real height (a font
            // scale change reshapes the bar) instead of a fixed 92 dp guess.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateSelectionInset()
                pinSelectionBar()
            }
        }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        selectionCount = SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
            id = R.id.swSelCount
        }
        top.addView(selectionCount, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(top)

        // Council §7: four named chips + a 5th "More" chip; the rest sit behind it.
        val overflow = LinearLayout(context).apply {
            id = R.id.cbSelOverflow
            orientation = HORIZONTAL
            isVisible = false
        }
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        // V017/D5: at 200 % five chips cannot fit a 320 dp row. Wrap two per row so no
        // label is cut and the More chip is always on screen.
        val wrapChips = largeFont()
        val chipRows = if (wrapChips) List(3) { LinearLayout(context).apply { orientation = HORIZONTAL } }
            else emptyList()
        val chipHost = LinearLayout(context).apply { orientation = VERTICAL }
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        var chipIndex = 0
        fun action(id: Int, icon: Int, label: Int, onClick: () -> Unit) {
            val chip = SwChip(context).apply {
                this.id = id
                bind(icon, context.getString(label))
                setOnClickListener { onClick() }
                minimumHeight = SwStyle.dp(context, 48f)
                minimumWidth = SwStyle.dp(context, 48f)
            }
            val container = if (wrapChips) chipRows[minOf(chipRows.lastIndex, chipIndex++ / 2)] else actions
            container.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f))
                    .apply { marginEnd = SwStyle.dp(context, 8f) }
            )
        }
        action(R.id.swSelMove, R.drawable.ic_sym_folder_open, R.string.sel_move) { showMoveSheet() }
        action(R.id.swSelTags, R.drawable.ic_sym_badge, R.string.sel_tags) {
            requirePro { showTagsSheet() }
        }
        action(R.id.swSelShare, R.drawable.ic_sym_share, R.string.sel_share) {
            val chosen = docs.filter { it.id in selected }
            if (chosen.isNotEmpty()) callbacks.onShareDocs(chosen)
        }
        // v2 UI: 03_tools_hub — L4's Batch screen opens with these ids preselected.
        action(R.id.swSelBatch, R.drawable.ic_sym_view_module, R.string.sel_batch) {
            val ids = docs.filter { it.id in selected }.map { it.id }
            if (ids.isEmpty()) return@action
            requirePro {
                (context as? android.app.Activity)?.let {
                    com.scanwhirl.app.ui.tools.BatchLauncher.start(it, ids)
                }
            }
        }
        // V017: a short visible label (the spoken name keeps the full one) fits at 200%.
        // V018: More opens a real sheet so the second state is unmistakable; the two chip
        // views stay in the tree (hidden) as the named controls the rest of the app uses.
        val renameSelected: () -> Unit = {
            if (selected.size >= 2) requirePro { showBatchRename() }
            else Toast.makeText(context, context.getString(R.string.batch_select_hint), Toast.LENGTH_SHORT).show()
        }
        val deleteSelected: () -> Unit = { confirmDeleteSelection() }
        val more = SwChip(context).apply {
            id = R.id.cbSelMore
            bind(R.drawable.ic_sym_more_horiz, context.getString(R.string.rs_more))
            contentDescription = context.getString(R.string.cb_more_actions)
            minimumHeight = SwStyle.dp(context, 48f)
            minimumWidth = SwStyle.dp(context, 48f)
            setOnClickListener {
                com.scanwhirl.app.ui.kit.SwSheet.builder(context)
                    .setTitle(context.getString(R.string.cb_more_actions))
                    .setOptions(
                        listOf(
                            context.getString(R.string.sel_rename),
                            context.getString(R.string.ux130_l1_move_to_trash)
                        )
                    ) { which, _ ->
                        if (which == 0) renameSelected() else deleteSelected()
                    }
                    .build()
                    .show()
            }
        }
        val moreHost = if (wrapChips) chipRows[minOf(chipRows.lastIndex, chipIndex++ / 2)] else actions
        moreHost.addView(
            more,
            LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f))
                .apply { marginEnd = SwStyle.dp(context, 8f) }
        )
        // The two hidden named chips stay in the tree for existing handlers; they are never
        // clipped because the sheet owns their visible presentation now.
        overflow.addView(SwChip(context).apply {
            id = R.id.swSelRename
            bind(R.drawable.ic_sym_drive_file_rename, context.getString(R.string.sel_rename))
            setOnClickListener { renameSelected() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f)))
        overflow.addView(SwChip(context).apply {
            id = R.id.swSelDelete
            bind(R.drawable.ic_sym_delete, context.getString(R.string.ux130_l1_move_to_trash))
            setOnClickListener { deleteSelected() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f)))
        if (wrapChips) {
            chipRows.forEach { chipHost.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
            bar.addView(chipHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 8f) })
        } else {
            scroll.addView(actions)
            bar.addView(
                scroll,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 8f) }
            )
        }
        bar.addView(
            overflow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 4f) }
        )
        // Council §7: "Finish selection" owns its own full-width row.
        val done = SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
            id = R.id.swSelDone
            text = context.getString(R.string.ux130_l1_finish_selection)
            setPadding(SwStyle.dp(context, 10f), 0, SwStyle.dp(context, 10f), 0)
            minimumHeight = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { exitSelection() }
        }
        bar.addView(
            done,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f))
                .apply { topMargin = SwStyle.dp(context, 8f) }
        )
        selectionBar = bar
        return bar
    }

    private fun renderSelectionBar() {
        selectionBar.isVisible = selectionMode
        // T32 (phone build 3): the bar was inside the scrolling content, so one
        // scroll left only a 6 px sliver of it on screen, and its bottom sat ~19 dp
        // over the first row although the list reserved 92 dp. The list now
        // reserves the bar's *measured* height (the old 92 dp was a guess that a
        // 200 % font beats) and the bar is pinned to the viewport top while the
        // list scrolls under it.
        updateSelectionInset()
        pinSelectionBar()
        if (selectionMode) {
            selectionCount.text = resources.getQuantityString(
                R.plurals.sel_count, selected.size, selected.size
            )
            // The bar has no height until it is laid out; reserve it right after.
            selectionBar.post {
                updateSelectionInset()
                pinSelectionBar()
            }
        }
    }

    /**
     * T32: reserves exactly the room the selection bar takes above the list, so no
     * row is covered at 100 % or 200 % font. The bar reports its own height, so a
     * font scale change cannot leave a guess behind (92 dp was too small).
     */
    private fun updateSelectionInset() {
        val bottom = listBottomInset()
        if (!selectionMode) {
            if (docList.paddingTop != 0) docList.setPadding(0, 0, 0, bottom)
            return
        }
        if (selectionBar.height <= 0) {
            // Not measured yet (first frame of selection mode): keep the previous
            // 92 dp floor so no row flashes under the bar, then use the real height.
            val provisional = SwStyle.dp(context, 92f)
            if (docList.paddingTop != provisional) docList.setPadding(0, provisional, 0, bottom)
            return
        }
        val lp = selectionBar.layoutParams as? MarginLayoutParams
        val wanted = selectionBar.height +
            (lp?.topMargin ?: 0) +
            (lp?.bottomMargin ?: 0) +
            SwStyle.dp(context, 6f)
        if (docList.paddingTop != wanted) docList.setPadding(0, wanted, 0, bottom)
    }

    /**
     * T32: keeps the bar at the top of the viewport instead of letting it scroll
     * away. Its natural place is in the content (below the quick tools); once the
     * list scrolls past that place, a translation holds it at the viewport's top
     * edge. Touches follow the translation (Android hit-tests the drawn position),
     * so Done/Move/Delete stay reachable while scrolling.
     */
    private fun pinSelectionBar() {
        if (!selectionMode || selectionBar.height <= 0 || !selectionBar.isAttachedToWindow) {
            if (selectionBar.translationY != 0f) selectionBar.translationY = 0f
            return
        }
        val scroll = parent as? ScrollView ?: return
        // Layout offset of the bar inside the scrolling content (no location reads:
        // those lag a just-applied translation).
        var offset = 0
        var view: View? = selectionBar
        while (view != null && view !== this) {
            offset += view.top
            view = view.parent as? View
        }
        val delta = scroll.scrollY - offset
        val wanted = if (delta > 0) delta.toFloat() else 0f
        if (selectionBar.translationY != wanted) selectionBar.translationY = wanted
    }

    /** T32: the scroll hook that keeps the selection bar pinned (removed on detach). */
    private fun installSelectionPin() {
        if (selectionPin != null) return
        val scroll = parent as? ScrollView ?: return
        // View.setOnScrollChangeListener is called synchronously from the scroll
        // itself, so the bar is moved in the same frame as the content (no lag).
        // The chrome's own listener did exactly this refresh; it is re-run here so
        // taking the single slot loses nothing.
        val listener = View.OnScrollChangeListener { view, _, scrollY, _, _ ->
            chrome?.update()
            pinSelectionBar()
            // L1lib #1: load the next page before the reader runs out of rows. There is
            // no hard cap, so the fast-scroll thumb always matches real content.
            val scroll = view as? ScrollView ?: return@OnScrollChangeListener
            val content = scroll.getChildAt(0)?.height ?: 0
            if (content - scrollY - scroll.height <= SwStyle.dp(context, 400f)) {
                loadMoreCards()
            }
        }
        selectionPin = listener
        scroll.setOnScrollChangeListener(listener)
    }

    private fun releaseSelectionPin() {
        val listener = selectionPin ?: return
        selectionPin = null
        (parent as? ScrollView)?.setOnScrollChangeListener(null)
    }

    private fun enterSelection(id: String) {
        selectionMode = true
        selected.clear()
        if (id.isNotEmpty()) selected += id
        renderDocs()
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        renderDocs()
    }

    private fun toggleSelection(id: String) {
        if (!selected.remove(id)) selected += id
        if (selected.isEmpty()) selectionMode = false
        renderDocs()
    }

    private fun showMoveSheet() {
        val ids = selected.toList()
        val labels = listOf(context.getString(R.string.folder_all_documents)) + folderNames
        SwSheet.builder(context)
            .setTitle(R.string.folder_move_title)
            .setOptions(labels) { which, _ ->
                val target = if (which == 0) null else folderNames[which - 1]
                ioWrite(
                    block = { repo.setFolder(ids, target) },
                    after = {
                        folder = target
                        exitSelection()
                        reload()
                    },
                    onError = {
                        // L1lib2 P2: counting moved documents reads every meta.txt, so do
                        // it on IO (withContext returns here on Main for the Toast).
                        val moved = withContext(ioDispatcher) {
                            ids.count { id ->
                                repo.getDocument(id)?.folder.equals(target, true)
                            }
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.l1lib_move_partial, moved, ids.size),
                            Toast.LENGTH_LONG
                        ).show()
                        reload()
                    }
                )
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    /**
     * L1lib #2: listTags() reads every document's meta.txt, so it must not run on the main
     * thread. Open the sheet immediately with a visible loading line, load tags on the IO
     * dispatcher, then fill the options on main. Under Robolectric the dispatcher is
     * immediate, so existing tag tests still see options after one `idle()`.
     */
    private fun showTagsSheet() {
        val ids = selected.toList()
        val chosen = docs.filter { it.id in selected }
        val common = chosen.map { it.tags.map { t -> t.lowercase(Locale.getDefault()) }.toSet() }
            .reduceOrNull { a, b -> a intersect b } ?: emptySet()

        val sheet = SwSheet.builder(context)
            .setTitle(R.string.tags_title)
            .setMessage(R.string.l1lib_tags_loading)
            .setPrimary(R.string.tags_new) { newTagSheet(ids) }
            .setSecondary(R.string.tags_done) { reload() }
            .build()
        sheet.show()

        fun populate(allTags: List<String>) {
            val labels = if (allTags.isEmpty()) listOf(context.getString(R.string.tags_empty)) else allTags
            val checked = BooleanArray(labels.size) { i ->
                labels[i].lowercase(Locale.getDefault()) in common && allTags.isNotEmpty()
            }
            if (labels.isEmpty()) return
            sheet.messageView.isVisible = false
            sheet.setOptions(labels, multiChecked = checked) { which, isChecked ->
                if (allTags.isEmpty()) return@setOptions
                val tag = allTags[which]
                if (isChecked) {
                    ioWrite({ repo.addTag(ids, tag) }, after = { reload() })
                } else {
                    ioWrite({ ids.forEach { repo.removeTag(it, tag) } }, after = { reload() })
                }
            }
        }

        val owner = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
        if (owner == null) {
            populate(runCatching { repo.listTags() }.getOrDefault(emptyList()))
            return
        }
        owner.launch {
            val allTags = withContext(ioDispatcher) {
                runCatching { repo.listTags() }.getOrDefault(emptyList())
            }
            if (isActive) populate(allTags)
        }
    }

    private fun newTagSheet(ids: List<String>) {
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.tags_new)
            .setInput(hint = context.getString(R.string.tags_hint))
            .setSecondary(R.string.sheet_cancel)
            .build()
        sheet.setPrimary(R.string.tags_add) {
            val tag = sheet.inputView?.text?.toString().orEmpty()
            ioWrite({ repo.addTag(ids, tag) }, after = { reload() })
        }
        sheet.show()
    }

    /**
     * T27-FIX-TRASH (data loss): the sheet says "You can restore it for 30 days", so a
     * selected document moves to Trash (Undo restores it) instead of being purged.
     *
     * T40-FIX-SNACK-ANCHOR: the "Moved N documents to Trash" Undo bar is shown by the
     * host (ShellActivity) so it is anchored above the bottom nav. Showing it here with
     * a null anchor drew it over the nav, where the phone's Undo tap opened Me.
     */
    private fun confirmDeleteSelection() {
        val ids = selected.toList()
        SwSheet.builder(context)
            .setTitle(context.resources.getQuantityString(R.plurals.v2l_move_to_trash_title, ids.size, ids.size))
            .setMessage(R.string.shell_trash_note)
            .setPrimary(R.string.shell_move_to_trash) {
                ioWrite({ TrashActions.moveAll(repo, ids) }) { moved ->
                    exitSelection()
                    reload()
                    callbacks.onMovedToTrash(moved)
                }
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    // ---- batch rename -----------------------------------------------------

    private fun showBatchRename() {
        val chosen = docs.filter { it.id in selected }
        if (chosen.size < 2) return
        val items = chosen.map { BatchRename.Item(it.name, it.createdAt) }
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        val input = EditText(context).apply {
            id = R.id.swBatchInput
            setText(BatchRename.DEFAULT_PATTERN)
            setSelection(text.length)
            hint = context.getString(R.string.batch_pattern_hint)
            setTextColor(SwStyle.color(context, R.color.text_hologram_hi))
            setHintTextColor(SwStyle.color(context, R.color.text_hologram_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface_raised),
                radiusDp = 14f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair)
            )
            val padH = SwStyle.dp(context, 12f)
            val padV = SwStyle.dp(context, 10f)
            setPadding(padH, padV, padH, padV)
            minHeight = SwStyle.dp(context, 48f)
            isSingleLine = true
        }
        val preview = SwStyle.text(context, 13f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
            id = R.id.swBatchPreview
            setLineSpacing(0f, 1.25f)
        }
        fun refresh() {
            val names = BatchRename.preview(input.text.toString(), items)
            preview.text = context.getString(R.string.batch_preview_title) + "\n" + names.joinToString("\n")
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        body.addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(
            preview,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 10f) }
        )
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.batch_rename_title)
            .setMessage(R.string.batch_rename_note)
            .setContent(body)
            .setSecondary(R.string.sheet_cancel)
            .build()
        sheet.setPrimary(R.string.batch_apply) {
            applyBatchRename(chosen, BatchRename.apply(input.text.toString(), items))
        }
        refresh()
        sheet.show()
    }

    private fun applyBatchRename(chosen: List<ScanDocument>, newNames: List<String>) {
        val undo = mutableListOf<Pair<String, String>>()
        ioWrite({
            chosen.forEachIndexed { index, doc ->
                val next = newNames.getOrNull(index) ?: doc.name
                if (next != doc.name) {
                    repo.renameDocument(doc.id, next)
                    undo += doc.id to doc.name
                }
            }
        }) {
            lastBatchUndo = undo.ifEmpty { null }
            exitSelection()
            reload()
            if (undo.isEmpty()) return@ioWrite
            // v2 UI: 74_motion — glass snackbar above the nav, after the IME closes.
            val host = context as? android.app.Activity
            val snack = if (host != null) {
                com.scanwhirl.app.ui.kit.SwSnackbar.show(
                    host,
                    this,
                    context.getString(R.string.batch_done, undo.size),
                    context.getString(R.string.undo_action),
                    onAction = { undoLastBatchRename() }
                )
            } else {
                Snackbar.make(this, context.getString(R.string.batch_done, undo.size), Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo_action) { undoLastBatchRename() }
                    .also { it.show() }
            }
            lastUndoSnackbar = snack
        }
    }

    // ---- pieces -----------------------------------------------------------

    private fun appBar(): View {
        val padH = SwStyle.dp(context, 18f)
        val mark = ImageView(context).apply {
            id = R.id.swBrandMark
            setImageResource(
                if (com.scanwhirl.app.ui.kit.SwTheme.isGold(context)) R.drawable.ic_brand_mark_gold
                else R.drawable.ic_brand_mark
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        mark.post { com.scanwhirl.app.ui.kit.SwMotion.rotateBrandOnce(mark) }
        val wordmark = SwStyle.text(context, 22f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            text = context.getString(R.string.app_name)
            typeface = SwType.extraBold(context)
            letterSpacing = -0.01f
            // The brand name never breaks: a longer translated pill used to squeeze it
            // into "ScanWhi / rl" (Hindi). The name keeps its natural width on one line.
            maxLines = 1
            isSingleLine = true
        }
        // v4 header: mark, A/अ, search. The trust pill moved to About and the empty Library
        // (UI_CRITIQUE #1), so the header carries no promise text any more.
        val language = languageChip()
        val large = resources.configuration.fontScale >= 1.6f
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            // UI-B1: large text keeps every row unclipped; the header tightens so a real
            // document row still clears the dock on the first screen.
            setPadding(
                padH,
                SwStyle.dp(context, if (large) 6f else 4f),
                padH,
                SwStyle.dp(context, if (large) 2f else 2f)
            )
        }
        if (resources.configuration.fontScale >= 1.6f) {
            // H2 (200 %): brand and language stack instead of squeezing one row off the edge.
            val brandRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            brandRow.addView(
                mark,
                LayoutParams(SwStyle.dp(context, 32f), SwStyle.dp(context, 32f))
                    .apply { marginEnd = SwStyle.dp(context, 10f) }
            )
            brandRow.addView(wordmark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            column.addView(brandRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            column.addView(
                language,
                LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f))
                    .apply {
                        topMargin = SwStyle.dp(context, 4f)
                        gravity = Gravity.END
                    }
            )
            return column
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            mark,
            LayoutParams(SwStyle.dp(context, 32f), SwStyle.dp(context, 32f))
                .apply { marginEnd = SwStyle.dp(context, 10f) }
        )
        row.addView(wordmark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(android.view.View(context), LayoutParams(0, 1, 1f))
        row.addView(
            language,
            LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f))
                .apply { marginStart = SwStyle.dp(context, 8f) }
        )
        column.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return column
    }

    /**
     * "A/अ" chip on Home: a Hindi reader can switch language without finding the English
     * Settings row. Opens a two-choice sheet written in each language's own script.
     */
    private fun languageChip(): View =
        SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.swLanguageChip
            text = context.getString(R.string.language_chip_label)
            contentDescription = context.getString(R.string.language_chip_desc)
            gravity = Gravity.CENTER
            maxLines = 1
            minWidth = SwStyle.dp(context, 48f)
            val padH = SwStyle.dp(context, 12f)
            setPadding(padH, 0, padH, 0)
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface),
                radiusDp = 24f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair)
            )
            isClickable = true
            isFocusable = true
            // FX1: git log -S shows the handler was added in 017f771c and never removed by a
            // merge; a real-tap hit-test (Fx1Run5DefectsTest) confirms it still opens the sheet.
            // Kit button semantics make the tap target speak as a Button to TalkBack, so a
            // reader knows the visible chip is actionable instead of a decorative label.
            SwStyle.asButton(this)
            setOnClickListener { pickLanguage() }
        }

    private fun pickLanguage() {
        val tags = listOf(AppLanguage.ENGLISH, AppLanguage.HINDI)
        // Same source as Settings: AppCompat's application locales first (also covers a
        // language set from Android's per-app language screen), then our saved tag.
        val current = AppLanguage.currentTag(context)
        val checked = tags.indexOf(current).let {
            if (it >= 0) it else if (AppLanguage.showingHindi(context)) 1 else 0
        }
        com.scanwhirl.app.ui.kit.SwSheet.builder(context)
            .setTitle(R.string.language_chip_title)
            .setOptions(tags.map { AppLanguage.label(it) }, checked) { which, _ ->
                val tag = tags[which.coerceIn(0, tags.lastIndex)]
                if (which != checked) AppLanguage.apply(context, tag)
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun searchBox(): View {
        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface),
                radiusDp = 18f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair)
            )
            minimumHeight = SwStyle.dp(context, 50f)
            val padH = SwStyle.dp(context, 16f)
            setPadding(padH, 0, padH, 0)
        }
        box.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_search)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_dim))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(SwStyle.dp(context, 22f), SwStyle.dp(context, 22f))
                .apply { marginEnd = SwStyle.dp(context, 12f) }
        )
        searchInput = EditText(context).apply {
            id = R.id.swHomeSearch
            // LEAD DECISION: short field hint + a persistent, wrapping helper line below it.
            // Council §7: the helper line becomes the field's placeholder. At 2.0x the
            // full sentence cannot fit the field, so large text keeps the short form.
            hint = context.getString(
                if (largeFont()) R.string.cb_search_hint_short else R.string.ux130_l1_search_help
            )
            setHintTextColor(SwStyle.color(context, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(context, R.color.text_hologram_hi))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            isSingleLine = true
            // v2 UI: 01_home_A — a new session starts with an empty query.
            isSaveEnabled = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    if (::homeClear.isInitialized) homeClear.isVisible = !s.isNullOrEmpty()
                    updateChrome()
                    renderDocs()
                }
            })
            setOnFocusChangeListener { _, _ ->
                updateChrome()
            }
        }
        box.addView(searchInput, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        homeClear = SwStyle.iconButton(
            context, R.drawable.ic_sym_close, SwStyle.color(context, R.color.text_hologram_dim)
        ).apply {
            id = R.id.swHomeClear
            contentDescription = context.getString(R.string.home_search_clear)
            layoutParams = LayoutParams(SwStyle.dp(context, 48f), SwStyle.dp(context, 48f))
            isVisible = false
            setOnClickListener {
                searchInput.setText("")
                searchInput.clearFocus()
                updateChrome()
                renderDocs()
            }
        }
        box.addView(homeClear)

        // L1-05: the live count and its scope live directly under the field, so a filtered
        // search never reads as a global result. One accessible status stop, not a second rail.
        searchStatus = SwStyle.text(
            context,
            if (AppLanguage.showingHindi(context)) 14f else 12.5f,
            SwStyle.color(context, R.color.text_hologram_hi)
        ).apply {
            id = R.id.uxL1SearchStatus
            isVisible = false
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            // V095: give the count its own breathing room under the field in every leg.
            setPadding(0, SwStyle.dp(context, 10f), 0, SwStyle.dp(context, 6f))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isFocusable = true
            // P1: these lines sit on the bare wallpaper scrim without a surface otherwise.
            background = readableBand()
        }
        val wrap = LinearLayout(context).apply {
            orientation = VERTICAL
            // V026: the field owns the one visible outline. The wrapper keeps an explicit
            // transparent drawable (not a second band) so the exposed row still has a surface.
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        wrap.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 50f)))
        wrap.addView(searchStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = SwStyle.dp(context, 4f)
        })
        return wrap.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = SwStyle.dp(context, 18f)
                val top = if (resources.configuration.fontScale >= 1.6f) 4f else 6f
                setMargins(m, SwStyle.dp(context, top), m, 0)
            }
        }
    }

    // ---- Home quick tools (owner decision UX-008) ------------------------

    /** Council §7: "Quick tools" / "Customize" / "All tools ›" — no bare pencil. */
    private fun quickToolsHeader(): View {
        // Large text needs a little more room for the wrapped "Quick tools" word;
        // tighten the outer and action paddings instead of clipping the title.
        val pad = SwStyle.dp(context, if (largeFont()) 12f else 18f)
        val actionPad = SwStyle.dp(context, if (largeFont()) 4f else 8f)
        val row = LinearLayout(context).apply {
            orientation = if (largeFont()) VERTICAL else HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The 48dp Customize control sets the row height.
            val top = if (resources.configuration.fontScale >= 1.6f) SwStyle.dp(context, 4f) else 0
            setPadding(pad, top, pad, 0)
        }
        row.addView(
            SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                // Never cap at one line: at 200 % the wrapped title must grow the header rather
                // than clip (AccessibilityAuditTest caught this on the 2.0x Library).
                text = context.getString(R.string.home_quick_tools)
            },
            if (largeFont()) LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        quickAll = SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
            id = R.id.swQuickAll
            text = context.getString(R.string.home_all_tools)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(context, 48f)
            minimumWidth = SwStyle.dp(context, 48f)
            isClickable = true
            isFocusable = true
            setPadding(actionPad, 0, actionPad, 0)
            setOnClickListener { callbacks.onAllTools() }
        }
        val actionRow = if (largeFont()) LinearLayout(context).apply {
            orientation = HORIZONTAL
            row.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else row
        actionRow.addView(quickAll, if (largeFont()) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        quickCustomize = SwStyle.text(
            context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true
        ).apply {
            id = R.id.swQuickEdit
            text = context.getString(R.string.cb_customize)
            contentDescription = context.getString(R.string.cb_customize_desc)
            minimumHeight = SwStyle.dp(context, 48f)
            minimumWidth = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER
            setPadding(actionPad, 0, actionPad, 0)
            ellipsize = null
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            isClickable = true
            isFocusable = true
            setOnClickListener { showQuickEditor() }
        }
        actionRow.addView(quickCustomize, if (largeFont()) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    /** Editor grid columns (Home itself is one horizontal strip, never a second row). */
    private fun quickColumns(): Int = when {
        resources.configuration.fontScale >= 1.6f -> 3
        resources.configuration.fontScale >= 1.25f -> 5
        else -> 6
    }

    /** Wide tiles let every EN/HI label fit one line at 100 %, so the strip stays short. */
    private fun quickTileWidth(): Int = SwStyle.dp(
        context, if (resources.configuration.fontScale >= 1.6f) 144f else 88f
    )

    /** One tool tile. The Home strip leaves [ToolTile.bind]'s launch click intact (Pro gate included). */
    private fun quickTile(
        tool: SwTool,
        onClick: (() -> Unit)? = null,
        badgeRes: Int? = null,
        spoken: String? = null,
        atLimit: Boolean = false
    ): ToolTile {
        val tile = ToolTile(context)
        tile.host = host
        tile.asQuick()
        tile.bind(tool)
        quickLabels[tool.id]?.let { tile.setLabel(context.getString(it)) }
        // A quick label wraps and grows the tile; it is never shrunk or ellipsized (UI_PLAN 0.8).
        tile.labelView.maxLines = if (resources.configuration.fontScale >= 1.6f) 4 else 2
        if (spoken != null) tile.contentDescription = spoken
        if (onClick != null) tile.setOnClickListener { onClick() }
        if (atLimit) {
            // A disabled tile is not focusable; keep it focusable so TalkBack can hear why.
            tile.alpha = 0.38f
            tile.isFocusable = true
        }
        if (badgeRes != null) {
            val badge = ImageView(context).apply {
                setImageResource(badgeRes)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.sw_on_gold))
                background = SwStyle.rounded(
                    context, SwStyle.color(context, R.color.gold_pro_primary), radiusDp = 999f
                )
                // The tile speaks "Add/Remove <tool>"; the glyph is decorative to TalkBack.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(
                    SwStyle.dp(context, 3f), SwStyle.dp(context, 3f),
                    SwStyle.dp(context, 3f), SwStyle.dp(context, 3f)
                )
            }
            tile.addView(
                badge,
                FrameLayout.LayoutParams(SwStyle.dp(context, 18f), SwStyle.dp(context, 18f)).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = SwStyle.dp(context, 2f)
                    marginStart = SwStyle.dp(context, 2f)
                }
            )
        }
        return tile
    }

    /** One horizontal strip of all saved tools (legacy lists longer than six are kept). */
    internal fun buildQuickRows() {
        quickStrip.removeAllViews()
        val tools = quickIds.mapNotNull { ToolRegistry.byId(it) }
        val width = quickTileWidth()
        for (tool in tools) {
            quickStrip.addView(
                quickTile(tool),
                LayoutParams(width, LayoutParams.WRAP_CONTENT)
            )
        }
        quickRow.isVisible = tools.isNotEmpty()
        updateChrome()
    }

    /** Reorder by long-press drag; works on the Home rows and inside the editor sheet. */
    internal fun moveQuickTool(id: String, toIndex: Int) {
        val list = quickDraft ?: quickIds
        val from = list.indexOf(id)
        if (from < 0) return
        list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), id)
        if (quickDraft != null) rebuildQuickEditor?.invoke() else buildQuickRows()
    }

    private var rebuildQuickEditor: (() -> Unit)? = null

    /**
     * UX-008: the pencil opens ONE sheet for add, remove, reorder and reset. The Home rows are
     * untouched until Save; the X and Back discard the draft.
     */
    /**
     * Council §7 + SH2: newcomer-safe editor. A single list of checkbox rows with the FULL tool
     * name, ▲▼ buttons (no drag needed), ONE instruction line ("Choose up to 6 tools. Use the
     * arrows to reorder.") and Reset. Cap 6: a saved list longer than six is kept but cannot
     * grow until one is unchecked. The Home strip is untouched until "Save quick tools"; the
     * X discards the draft. This is non-destructive, so no duplicate Cancel footer: only a
     * destructive confirmation (Reset) keeps an explicit Cancel.
     */
    private fun showQuickEditor() {
        val draft = quickIds.toMutableList()
        quickDraft = draft
        val content = LinearLayout(context).apply {
            id = R.id.cbQuickEditorList
            orientation = VERTICAL
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
            )
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        fun moveButton(tool: SwTool, up: Boolean): View = SwStyle.text(
            context, 18f, SwStyle.color(context, R.color.cyan_primary), bold = true
        ).apply {
            id = if (up) R.id.cbQuickUp else R.id.cbQuickDown
            // Council §7: literal ▲ / ▼ controls, no drag and no new icon asset.
            text = if (up) "▲" else "▼"
            contentDescription = context.getString(
                if (up) R.string.cb_move_up else R.string.cb_move_down,
                context.getString(tool.title)
            )
            minimumHeight = SwStyle.dp(context, 48f)
            minimumWidth = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(
                SwStyle.dp(context, 10f), SwStyle.dp(context, 10f),
                SwStyle.dp(context, 10f), SwStyle.dp(context, 10f)
            )
            tag = tool.id
            setOnClickListener {
                val at = draft.indexOf(tool.id)
                if (at >= 0) moveQuickTool(tool.id, if (up) at - 1 else at + 1)
            }
        }
        fun rebuildEditor() {
            content.removeAllViews()
            content.addView(
                SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_dim)).apply {
                    id = R.id.cbQuickHint
                    text = context.getString(R.string.cb_quick_editor_hint)
                    contentDescription = text
                    setLineSpacing(0f, 1.25f)
                    minimumHeight = SwStyle.dp(context, 48f)
                    gravity = Gravity.CENTER_VERTICAL
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            // SH2: the cap is already in the single instruction line; a second cyan
            // "up to 6" statement repeated it and pushed the first row off a short sheet.
            val atLimit = draft.size >= QUICK_CAP
            for (tool in ToolRegistry.all.filter { it.status == ToolStatus.READY }) {
                val checked = tool.id in draft
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = SwStyle.dp(context, 48f)
                    tag = tool.id
                }
                val box = CheckBox(context).apply {
                    id = R.id.cbQuickCheck
                    text = context.getString(tool.title)
                    contentDescription = context.getString(tool.title)
                    isChecked = checked
                    // A saved list over six is kept: its boxes stay on, extra adds are blocked.
                    isEnabled = checked || !atLimit
                    buttonTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
                    setTextColor(SwStyle.color(context, R.color.text_hologram_hi))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    minimumHeight = SwStyle.dp(context, 48f)
                    setOnCheckedChangeListener { _, on ->
                        if (on && draft.size < QUICK_CAP) {
                            if (tool.id !in draft) draft.add(tool.id)
                        } else if (!on) {
                            draft.remove(tool.id)
                        }
                        rebuildQuickEditor?.invoke()
                    }
                    tag = tool.id
                }
                row.addView(box, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                if (checked) {
                    row.addView(moveButton(tool, up = true))
                    row.addView(moveButton(tool, up = false))
                }
                content.addView(
                    row,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                )
            }
            content.addView(
                SwStyle.text(context, 14f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
                    id = R.id.cbQuickReset
                    text = context.getString(R.string.u4_quick_reset)
                    minimumHeight = SwStyle.dp(context, 48f)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        SwSheet.builder(context)
                            .setTitle(R.string.ux130_l1_reset_quick)
                            .setPrimary(R.string.ux130_l1_reset) {
                                draft.clear()
                                draft.addAll(com.scanwhirl.app.util.Prefs.DEFAULT_QUICK_TOOLS)
                                rebuildQuickEditor?.invoke()
                            }
                            .setSecondary(R.string.sheet_cancel) { /* discard */ }
                            .build().show()
                    }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 12f) }
            )
        }
        rebuildQuickEditor = { rebuildEditor() }
        rebuildEditor()
        var saved = false
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.cb_quick_editor_title)
            .setContent(scroll)
            .setPrimary(R.string.cb_quick_save) {
                com.scanwhirl.app.util.Prefs.setQuickTools(context, draft)
                quickIds = com.scanwhirl.app.util.Prefs.quickTools(context).toMutableList()
                saved = true
                buildQuickRows()
            }
            // SH2: no Cancel here. The X (and Back) discard the draft; only the
            // destructive Reset confirmation below keeps an explicit Cancel.
            .build()
        sheet.setOnDismissListener {
            rebuildQuickEditor = null
            quickDraft = null
            quickSheet = null
            if (!saved) buildQuickRows()
        }
        quickSheet = sheet
        sheet.show()
    }

    private fun sectionHeader(title: String, action: String?, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            // UX-008: the compact Quick tools strip must not push documents off a 640dp
            // first screen; the 48dp sort control still sets the row's touch-target height.
            setPadding(pad, SwStyle.dp(context, 4f), pad, SwStyle.dp(context, 2f))
        }
        row.addView(
            SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = title
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        if (action != null) {
            val actionView = SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
                id = R.id.swSort
                text = action
                isClickable = onAction != null
                setOnClickListener { onAction?.invoke() }
                minimumHeight = SwStyle.dp(context, 48f)
                gravity = Gravity.CENTER_VERTICAL
                // Device reflow: a single sort word must stay one whole line. Full-width at
                // large font gives it room; never break or ellipsize mid-word.
                maxLines = 1
                ellipsize = null
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                //noinspection WrongConstant
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            }
            sortAction = actionView
            row.addView(actionView)
            renderSortLabel()
        }
        return row
    }

    private fun sortLabelRes(): Int = when (sort) {
        com.scanwhirl.app.util.Prefs.LIB_OLDEST -> R.string.sort_oldest
        com.scanwhirl.app.util.Prefs.LIB_NAME_ASC -> R.string.sort_name_az
        com.scanwhirl.app.util.Prefs.LIB_NAME_DESC -> R.string.sort_name_za
        com.scanwhirl.app.util.Prefs.LIB_SIZE -> R.string.sort_size
        else -> R.string.sort_newest
    }

    private fun renderSortLabel() {
        // The heading follows the sort (U4CopyIconsTest intent), but now lives in its own
        // plain-text row instead of competing with the two band controls.
        if (::docHeading.isInitialized) {
            docHeading.text = context.getString(
                when (sort) {
                    com.scanwhirl.app.util.Prefs.LIB_NAME_ASC,
                    com.scanwhirl.app.util.Prefs.LIB_NAME_DESC,
                    com.scanwhirl.app.util.Prefs.LIB_SIZE -> R.string.u4_library_documents
                    else -> R.string.home_recent
                }
            )
            docHeading.contentDescription = docHeading.text
        }
        val label = context.getString(sortLabelRes())
        sortAction?.text = "$label ▾"
        sortAction?.contentDescription = context.getString(R.string.cb_sort_talkback, label)
    }

    /** v2 UI: 01_home_A — "Newest ▾": the one honest sort control, persisted. */
    private fun showSortSheet() {
        val values = listOf(
            com.scanwhirl.app.util.Prefs.LIB_NEWEST,
            com.scanwhirl.app.util.Prefs.LIB_OLDEST,
            com.scanwhirl.app.util.Prefs.LIB_NAME_ASC,
            com.scanwhirl.app.util.Prefs.LIB_NAME_DESC,
            com.scanwhirl.app.util.Prefs.LIB_SIZE
        )
        val labels = listOf(
            context.getString(R.string.sort_newest),
            context.getString(R.string.sort_oldest),
            context.getString(R.string.sort_name_az),
            context.getString(R.string.sort_name_za),
            context.getString(R.string.rs_sort_size_desc)
        )
        val checked = values.indexOf(sort).coerceAtLeast(0)
        SwSheet.builder(context)
            .setTitle(R.string.sort_title)
            .setOptions(labels, checked) { which, _ ->
                sort = values[which]
                com.scanwhirl.app.util.Prefs.setLibrarySort(context, sort)
                renderSortLabel()
                renderDocs()
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    /**
     * Council §7: categories live in the same chooser sheet as folders (the face has no
     * filter rail). Rows are only shown for categories that actually have documents, plus
     * "All categories" to clear the filter.
     */
    private fun renderChips() {
        if (!::categoryRow.isInitialized) return
        categoryRow.removeAllViews()
        val counts = docs.groupingBy { it.category }.eachCount()
        categoryRow.addView(
            sheetChoice(context.getString(R.string.home_filter_all_documents), category == null) {
                chooserSheet?.dismiss()
                category = null
                renderChips()
                renderDocs()
            }
        )
        for (cat in CategoryRules.ALL) {
            val count = counts[cat] ?: 0
            if (count == 0) continue
            val label = context.getString(
                R.string.home_chip_category, CategoryLabels.display(context, cat), count
            )
            categoryRow.addView(sheetChoice(label, cat == category) {
                chooserSheet?.dismiss()
                category = if (cat == category) null else cat
                renderChips()
                renderDocs()
            })
        }
    }

    /**
     * H8: the category choice behind Home's "All" chip, so tapping the active chip does
     * something: the same categories the rail filters by, plus "All categories" to clear.
     */
    private fun showCategoryFilterSheet() {
        val counts = docs.groupingBy { it.category }.eachCount()
        val keys: List<String?> = listOf(null) + CategoryRules.ALL
        val labels = keys.map { key ->
            if (key == null) {
                context.getString(R.string.home_filter_all_documents)
            } else {
                context.getString(
                    R.string.home_chip_category,
                    CategoryLabels.display(context, key),
                    counts[key] ?: 0
                )
            }
        }
        SwSheet.builder(context)
            .setTitle(R.string.home_filter_category_title)
            .setOptions(labels, keys.indexOf(category)) { which, _ ->
                category = keys[which.coerceIn(0, keys.lastIndex)]
                renderChips()
                renderDocs()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    /** The docs to show and which of them matched by stored OCR text (never locked/Vault). */
    private data class VisibleDocs(
        val docs: List<ScanDocument>,
        val textHitIds: Set<String>
    )

    private fun computeVisible(
        q: String,
        folderFilter: String?,
        categoryFilter: String?,
        sortMode: String
    ): VisibleDocs {
        // L1lib #4: use the tested SearchMatcher (word boundaries, accent folding) for
        // names, tags and stored OCR, and do the cheap folder/category filtering BEFORE
        // touching any OCR file. The old substring match made "2" find "2026" and made
        // "café" miss an OCR "cafe".
        val terms = SearchMatcher.normalize(q).split(Regex("\\s+")).filter { it.isNotBlank() }
        val textHits = HashSet<String>()
        val cheap = docs.filter { doc ->
            (folderFilter == null || doc.folder.equals(folderFilter, true)) &&
                (categoryFilter == null || doc.category == categoryFilter)
        }
        val filtered = cheap.filter { doc ->
            if (terms.isEmpty()) return@filter true
            val normName = SearchMatcher.normalize(doc.name)
            val normTags = SearchMatcher.normalize(doc.tags.joinToString(" "))
            val nameHit = terms.all { SearchMatcher.containsWord(normName, it) } ||
                terms.all { SearchMatcher.containsWord(normTags, it) }
            if (nameHit) return@filter true
            // UI-W4 privacy: a locked or Vault document is never searched by its OCR;
            // it stays searchable by its visible name only (no snippet/page evidence).
            if (doc.isLocked || doc.isVaulted) return@filter false
            val normText = runCatching { textCache.normalized(doc.id) }.getOrDefault("")
            val textHit = terms.all { SearchMatcher.containsWord(normText, it) }
            if (textHit) textHits += doc.id
            textHit
        }
        val sorted = when (sortMode) {
            com.scanwhirl.app.util.Prefs.LIB_OLDEST -> filtered.sortedBy { it.createdAt }
            com.scanwhirl.app.util.Prefs.LIB_NAME_ASC ->
                filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
            com.scanwhirl.app.util.Prefs.LIB_NAME_DESC ->
                filtered.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            com.scanwhirl.app.util.Prefs.LIB_SIZE -> filtered.sortedByDescending { docSize(it) }
            else -> filtered.sortedByDescending { it.createdAt }
        }
        return VisibleDocs(if (revealId == null) sorted else sorted.sortedByDescending { it.id == revealId }, textHits)
    }

    /**
     * T28: one "Page N" + short excerpt per shown document whose stored page text
     * holds the query. Runs on the library IO dispatcher with the same pass that
     * filtered the list; it reads no file the search did not already read, changes
     * no ranking and invents no page: [PageTextMatches] only reports a page when
     * every term occurs there. Name-only hits stay absent.
     */
    private fun computeRowEvidence(q: String, visible: VisibleDocs): Map<String, DocSearchHit> {
        if (q.isBlank() || visible.textHitIds.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, DocSearchHit>()
        for (doc in visible.docs) {
            if (doc.id !in visible.textHitIds || doc.isLocked || doc.isVaulted) continue
            val pages = runCatching { textCache.pages(doc.id) }.getOrDefault(emptyMap())
            if (pages.isEmpty()) continue
            val result = runCatching {
                PageTextMatches.find(q, doc.pageFiles.map { it.name }, pages)
            }.getOrNull() ?: continue
            val first = result.hits.firstOrNull() ?: continue
            val pageText = pages[first.pageKey] ?: continue
            val range = first.ranges.firstOrNull() ?: continue
            val excerpt = PageTextMatches.excerpt(pageText, range, result.terms)
            if (excerpt.text.isBlank()) continue
            out[doc.id] = DocSearchHit(
                docId = doc.id,
                revision = doc.revision,
                pageIndex = first.pageIndex,
                pageKey = first.pageKey,
                query = q,
                excerpt = excerpt
            )
        }
        return out
    }

    /** L1-4: the size is cached by [ScanRepository.loadDocument]; no page stat pass. */
    private fun docSize(doc: ScanDocument): Long =
        if (doc.cachedSize > 0L) doc.cachedSize
        else runCatching {
            if (doc.pdfFile.exists()) doc.pdfFile.length() else doc.pageFiles.sumOf { it.length() }
        }.getOrDefault(0L)

    /**
     * L1-2: a keystroke cancels the previous search, waits 200 ms, runs the
     * name/tag/OCR match off the main thread and only then rebinds the list.
     * Under Robolectric the wait is 0 and the dispatcher is immediate, so tests
     * still settle on one `idle()`.
     */
    private fun renderDocs() {
        val generation = ++renderGeneration
        val owner = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
        if (owner == null) {
            val visible = computeVisible(query, folder, category, sort)
            if (generation == renderGeneration) {
                applyDocs(visible.docs, computeRowEvidence(query, visible))
            }
            return
        }
        renderJob?.cancel()
        val q = query
        val f = folder
        val c = category
        val s = sort
        renderJob = owner.launch {
            if (q.isNotBlank() && searchDebounceMs > 0L) delay(searchDebounceMs)
            val shown = withContext(ioDispatcher) {
                val visible = computeVisible(q, f, c, s)
                visible to computeRowEvidence(q, visible)
            }
            // The token, not only the job's cancellation, decides: a blocking text read
            // cannot be interrupted, so an older pass must never bind over a newer query.
            if (isActive && generation == renderGeneration) {
                applyDocs(shown.first.docs, shown.second)
            }
        }
    }

    /** What a card shows; a change here means that one row must rebind. */
    private fun signature(doc: ScanDocument): String = buildString {
        append(doc.id).append('\u0000')
        append(doc.name).append('\u0000')
        append(doc.category).append('\u0000')
        append(doc.folder).append('\u0000')
        append(doc.tags.joinToString("\u0001")).append('\u0000')
        append(doc.pageCount).append('\u0000')
        append(doc.revision).append('\u0000')
        append(doc.isLocked).append('\u0000')
        append(doc.isVaulted).append('\u0000')
        append(selectionMode && doc.id in selected).append('\u0000')
        append(doc.id == revealId).append('\u0000')
        rowEvidence[doc.id]?.let {
            append(it.pageIndex).append('\u0000').append(it.query).append('\u0000')
                .append(it.excerpt.text)
        }
    }

    private fun bindCard(card: DocCard, doc: ScanDocument) {
        val hit = rowEvidence[doc.id]
        card.bind(
            doc,
            metaFor(doc),
            onShare = { callbacks.onShareDoc(doc) },
            onMore = { callbacks.onMoreDoc(doc) },
            search = hit
        )
        card.setSelection(selectionMode, doc.id in selected)
        // The badge belongs to the name column, not an overlay on the document or its actions.
        val info = card.findViewById<TextView>(R.id.swDocName).parent as LinearLayout
        val badge = info.findViewWithTag<TextView>("result-new-badge") ?: SwStyle.text(
            context, com.scanwhirl.app.ui.kit.SwType.CAPTION_SP,
            SwStyle.color(context, R.color.cyan_primary), bold = true
        ).apply {
            tag = "result-new-badge"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            text = context.getString(R.string.rs_new)
            background = SwStyle.rounded(context, SwStyle.color(context, R.color.rv_surface_2),
                SwStyle.RADIUS_CONTROL_DP)
            setPadding(SwStyle.dp(context, SwStyle.SPACE_2_DP), SwStyle.dp(context, SwStyle.SPACE_1_DP),
                SwStyle.dp(context, SwStyle.SPACE_2_DP), SwStyle.dp(context, SwStyle.SPACE_1_DP))
            info.addView(this, 0, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        badge.isVisible = doc.id == revealId
        if (doc.id == revealId)
            card.contentDescription = context.getString(R.string.rs_new) + " · " + card.contentDescription
        card.setOnClickListener {
            if (selectionMode) {
                toggleSelection(doc.id)
            } else if (hit != null && !doc.isLocked && !doc.isVaulted && hit.revision == doc.revision) {
                openAtPage(doc, hit)
            } else {
                callbacks.onOpenDoc(doc)
            }
        }
        card.setOnLongClickListener {
            com.scanwhirl.app.ui.kit.SwHaptics.tick(it)
            if (!selectionMode) enterSelection(doc.id) else toggleSelection(doc.id)
            true
        }
        if (doc.id == revealId) {
            card.isSelected = true
            card.background = SwStyle.gradient(
                context,
                SwStyle.color(context, R.color.glass_surface_raised),
                SwStyle.color(context, R.color.glass_surface),
                18f,
                SwStyle.color(context, R.color.cyan_primary)
            )
            card.requestFocus()
            card.post {
                card.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, card.width, card.height), true
                )
            }
        }
    }

    /**
     * T28: a text-hit row opens the ScanWhirl reader at the matched page, with Find
     * pre-filled. [PdfViewerActivity.startPage] refuses a stale page order and says so,
     * so an edited document never opens on the wrong page. No callback, no document
     * screen change: the reader API already exists and is the same path Find uses.
     */
    private fun openAtPage(doc: ScanDocument, hit: DocSearchHit) {
        val activity = context as? android.app.Activity
        if (activity == null) {
            callbacks.onOpenDoc(doc)
            return
        }
        runCatching {
            activity.startActivity(
                PdfViewerActivity.pageIntent(
                    activity, doc.id, doc.name, hit.pageIndex, hit.pageKey, hit.query
                )
            )
        }.onFailure { callbacks.onOpenDoc(doc) }
    }

    private fun newCard(doc: ScanDocument): DocCard = DocCard(context).also { bindCard(it, doc) }

    private fun addCard(doc: ScanDocument) {
        docList.addView(
            newCard(doc),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, 0, m, SwStyle.dp(context, 10f))
            }
        )
    }

    private fun rebindChanged(
        limit: List<ScanDocument>,
        signatures: List<String>,
        existingRows: Int
    ) {
        for (i in 0 until existingRows) {
            if (renderedSignatures.getOrNull(i) != signatures[i]) {
                (docList.getChildAt(i) as? DocCard)?.let { bindCard(it, limit[i]) }
            }
        }
    }

    /**
     * L1lib #1: bind the first [cardsShown] documents. When a page is appended the already
     * bound prefix is kept and only the new tail creates views; unchanged rows are never
     * rebuilt. This keeps scrolling cheap while making every document reachable.
     */
    private fun renderCards() {
        val limit = allVisibleDocs.take(cardsShown)
        val signatures = limit.map { signature(it) }
        val renderedIds = renderedDocs.map { it.id }
        val canAppend = limit.size > renderedDocs.size &&
            docList.childCount == renderedDocs.size &&
            renderedIds == allVisibleDocs.take(renderedDocs.size).map { it.id }
        when {
            canAppend -> {
                rebindChanged(limit, signatures, renderedDocs.size)
                for (i in renderedDocs.size until limit.size) addCard(limit[i])
            }
            renderedIds == limit.map { it.id } && docList.childCount == limit.size ->
                rebindChanged(limit, signatures, limit.size)
            else -> {
                docList.removeAllViews()
                for (doc in limit) addCard(doc)
            }
        }
        renderedDocs = limit
        renderedSignatures = signatures
    }

    /**
     * L1lib #1: append the next page of document cards. Called by the scroll listener
     * when the reader nears the bottom; returns true when more rows were added.
     */
    internal fun loadMoreCards(): Boolean {
        if (cardsShown >= allVisibleDocs.size) return false
        val target = minOf(cardsShown + CARD_PAGE, allVisibleDocs.size)
        val generation = renderGeneration
        var from = cardsShown
        // Commit the whole range up front so two scroll events cannot schedule the same
        // cards. The views arrive in ~20-card frames, each guarded against a stale render.
        cardsShown = target
        while (from < target) {
            val to = minOf(from + APPEND_CHUNK, target)
            val start = from
            post {
                if (generation == renderGeneration && start == renderedDocs.size) {
                    val bound = minOf(to, allVisibleDocs.size)
                    for (i in start until bound) addCard(allVisibleDocs[i])
                    renderedDocs = allVisibleDocs.take(bound)
                    renderedSignatures = renderedDocs.map { signature(it) }
                }
            }
            from = to
        }
        return true
    }

    /**
     * L1-2: only changed rows are rebuilt. Same ids in the same order (the
     * common rename/selection/sort-in-place case) rebinds just the rows whose
     * content signature changed; an insert/remove/filter reorders the list.
     */
    private fun applyDocs(shown: List<ScanDocument>, hits: Map<String, DocSearchHit> = emptyMap()) {
        rowEvidence = hits
        renderActiveFilter()
        // L1lib #1: a new result set starts at the first page; a re-render of the SAME set
        // (selection, rename, reveal) keeps every page the reader already loaded.
        val sameSet = shown.map { it.id } == allVisibleDocs.map { it.id }
        allVisibleDocs = shown
        if (!sameSet) cardsShown = minOf(INITIAL_CARDS, shown.size)
        renderCards()

        renderSelectionBar()
        listHeader.isVisible = shown.isNotEmpty()
        countLabel.text = resources.getQuantityString(
            com.scanwhirl.app.R.plurals.list_count, shown.size, shown.size
        )
        // L1-05: a live count that always names its scope, so a folder/category filter
        // is never presented as a global result.
        if (query.isNotBlank() || folder != null || category != null) {
            val scope = folder
                ?: category?.let { CategoryLabels.display(context, it) }
                ?: context.getString(R.string.ux130_l1_scope_all)
            val status = resources.getQuantityString(
                R.plurals.ux130_l1_result_count, shown.size, shown.size
            ) + " · " + context.getString(R.string.ux130_l1_search_scope, scope)
            searchStatus.text = status
            searchStatus.contentDescription = status
        } else {
            searchStatus.text = null
            searchStatus.contentDescription = null
        }
        // uihome: the pinned chrome only adds the fast-scroll bar (the count stays as
        // plain text in the list header - nothing is drawn over the rows).
        chrome?.setDocumentCount(shown.size)
        val filtering = query.isNotBlank() || folder != null || category != null
        noMatchView.isVisible = shown.isEmpty() && filtering
        if (noMatchView.isVisible) {
            noMatchView.text = if (query.isNotBlank()) {
                context.getString(R.string.home_no_match_for, query)
            } else {
                context.getString(R.string.empty_filtered)
            }
            (emptyView.getChildAt(0) as? ImageView)?.setImageResource(R.drawable.ic_sym_search)
        }
        emptyView.isVisible = docs.isEmpty() && !filtering
        if (emptyView.isVisible) {
            (emptyView.getChildAt(0) as? ImageView)?.setImageResource(R.drawable.ic_sym_document_scanner)
            (emptyView.getChildAt(1) as? TextView)?.text = context.getString(R.string.home_empty_title)
            (emptyView.getChildAt(2) as? TextView)?.text = context.getString(R.string.home_empty_body)
            (emptyView.getChildAt(2) as? TextView)?.isVisible = true
        }
        updateChrome()
        if (!listStaggered && shown.isNotEmpty()) {
            listStaggered = true
            com.scanwhirl.app.ui.kit.SwMotion.stagger(docList)
        }
    }

    /**
     * v2 UI: 01_home_A — search mode collapses everything above the results (folders, section
     * header and chips) so the list starts right under the field; an empty library also
     * collapses them so the empty state fits above the nav/disc. The creation tiles and quick
     * tools are gone from Home (UI-B1 #1): the Create sheet and the Tools tab own them now.
     */
    /**
     * UI-W4: the current folder filter is stated below the chip rail too. A coloured
     * chip alone is not enough at 200 % or for TalkBack, and the rail may be scrolled
     * past the selected chip.
     */
    private fun renderActiveFilter() {
        if (!::activeFilter.isInitialized) return
        // Council §7: the Filter row only exists while a filter is active, and the chip is
        // removable via cbRemoveFilter. Folder wording is kept for the existing tests.
        val text: String? = when {
            folder != null -> context.getString(R.string.folder_showing, folder!!)
            category != null -> context.getString(
                R.string.cb_filter_showing, CategoryLabels.display(context, category!!)
            )
            else -> null
        }
        filterRow.isVisible = text != null
        activeFilter.isVisible = text != null
        if (text == null) {
            activeFilter.text = null
            activeFilter.contentDescription = null
        } else {
            activeFilter.text = text
            activeFilter.contentDescription = if (folder != null) {
                context.getString(R.string.folder_filter_active, folder!!)
            } else {
                context.getString(R.string.cb_filter_active, text)
            }
        }
    }

    private fun updateChrome() {
        val searching = query.isNotBlank() ||
            (::searchInput.isInitialized && searchInput.hasFocus())
        val firstRun = docs.isEmpty() && !searching && folder == null && category == null
        controlBand.isVisible = !searching && !firstRun
        filterRow.isVisible = !searching && (folder != null || category != null)
        quickHeader.isVisible = !searching && !firstRun
        quickRow.isVisible = !searching && !firstRun && quickStrip.childCount > 0
        docHeading.isVisible = !searching && allVisibleDocs.isNotEmpty()
        listHeader.isVisible = !searching && !firstRun
        searchStatus.isVisible = query.isNotBlank() || folder != null || category != null
        // M5: keep the list's bottom inset at or above the dock's real height (it grows when
        // the scan disc rises over the page); never a smaller magic number.
        val inset = listBottomInset()
        val gutter = if (allVisibleDocs.size >= LibraryFastScrollBar.MIN_DOCUMENTS)
            SwStyle.dp(context, SwStyle.TOUCH_MIN_DP) else 0
        if (docList.paddingBottom != inset || docList.paddingRight != gutter) {
            docList.setPadding(0, docList.paddingTop, gutter, inset)
        }
    }

    /**
     * How far the last row and the count must sit above the viewport bottom so the floating
     * dock never covers them. Measured from the shell's dock (T23 M5); 96dp is the floor for
     * hosts without the shell.
     */
    private fun listBottomInset(): Int {
        val dock = (context as? ShellActivity)?.dockClearancePx() ?: 0
        val base = maxOf(SwStyle.dp(context, 96f), dock)
        // U5/G6: at 200 % the Scan action is lifted 40 dp above the pill, so the measured
        // dock is taller than the nav host. Reserve that lift up front (the dock is measured
        // only after the first layout) so the last card never hides under the disc.
        return if (resources.configuration.fontScale >= 1.6f) {
            base + SwStyle.dp(context, 48f)
        } else base
    }

    private fun metaFor(doc: ScanDocument): String {
        // ATLAS-F2 (001): a number stays with its unit. "4 KB" and "1 page" use a
        // non-breaking space, so a wrapped meta breaks only at the " · " separators
        // ("1 page ·" / "4 KB ·" / "Today"), never between 4 and KB.
        val pages = resources.getQuantityString(
            com.scanwhirl.app.R.plurals.pages, doc.pageCount, doc.pageCount
        ).replace(' ', '\u00A0')
        val tags = if (doc.tags.isEmpty()) "" else " · " + doc.tags.take(2).joinToString(" ") { "#$it" }
        // V025: at 200 % a dangling "·" left "Today" on its own line. Keep the size and
        // date together at large font; the default-font wording stays byte-for-byte unchanged.
        return if (largeFont())
            "$pages · ${size(doc)}\u00A0·\u00A0${whenDay(doc)}$tags"
        else "$pages · ${size(doc)} · ${whenDay(doc)}$tags"
    }

    /**
     * PERF 1: the repository already measured every byte while listing the library. Never
     * stat a file on the main thread just to reformat a number we already have.
     */
    private fun size(doc: ScanDocument): String {
        val bytes = doc.cachedSize
        // CP1: one decimal unit helper (KB = 1000, MB = 1,000,000). Keep a number and its
        // unit together with an NBSP. A missing size stays "—", never a pretend "0 B".
        if (bytes <= 0L) return "—"
        return SizeUnits.format(bytes).replace(' ', '\u00A0')
    }

    /**
     * PERF 2: one formatter and two Calendar instances per locale, not three allocations on
     * every bind. Recreated only when the app language actually changes (not device locale).
     */
    private var dayFormatter: SimpleDateFormat? = null
    private var dayFormatterLocale: java.util.Locale? = null
    private val dayDocCalendar = Calendar.getInstance()
    private val dayTodayCalendar = Calendar.getInstance()

    private fun whenDay(doc: ScanDocument): String {
        val locale = AppLocale.of(context)
        val fmt = if (dayFormatter == null || dayFormatterLocale != locale) {
            SimpleDateFormat("d MMM", locale).also {
                dayFormatter = it
                dayFormatterLocale = locale
            }
        } else dayFormatter!!
        dayDocCalendar.timeInMillis = doc.createdAt
        dayTodayCalendar.timeInMillis = System.currentTimeMillis()
        val sameDay = dayDocCalendar.get(Calendar.YEAR) == dayTodayCalendar.get(Calendar.YEAR) &&
            dayDocCalendar.get(Calendar.DAY_OF_YEAR) == dayTodayCalendar.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return context.getString(R.string.shell_today)
        dayTodayCalendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dayDocCalendar.get(Calendar.YEAR) == dayTodayCalendar.get(Calendar.YEAR) &&
            dayDocCalendar.get(Calendar.DAY_OF_YEAR) == dayTodayCalendar.get(Calendar.DAY_OF_YEAR)
        if (yesterday) return context.getString(R.string.shell_yesterday)
        return fmt.format(Date(doc.createdAt))
    }

    internal companion object {
        /**
         * Council §7: the quick-tools editor is capped at six. Prefs owns the saved list
         * and its own (older) cap constant; this lane enforces the decided cap at the UI
         * so a legacy list longer than six is kept but cannot grow.
         */
        const val QUICK_CAP = 6

        /** First page of document cards; more are appended as the reader scrolls. */
        const val INITIAL_CARDS = 60
        const val CARD_PAGE = 60
        /** Append a page across a few frames instead of inflating 60 views in one frame. */
        const val APPEND_CHUNK = 20

        private val isRobolectric =
            android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

        /**
         * Every library read (list + search) runs here. Production uses the IO
         * pool; the Robolectric suite uses an immediate dispatcher so its
         * single `idle()` still sees the loaded list. The perf test overrides
         * this with a real threaded dispatcher to prove no disk I/O on main.
         */
        @Volatile
        var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            if (isRobolectric) kotlinx.coroutines.Dispatchers.Unconfined
            else kotlinx.coroutines.Dispatchers.IO

        /** 200 ms between keystroke and search; 0 under Robolectric. */
        @Volatile
        var searchDebounceMs: Long = if (isRobolectric) 0L else 200L
    }
}
