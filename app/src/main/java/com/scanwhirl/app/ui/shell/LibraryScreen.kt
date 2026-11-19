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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.kit.TrustPill
import com.scanwhirl.app.util.AppLanguage
import com.scanwhirl.app.util.BatchRename
import com.scanwhirl.app.util.CategoryLabels
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.PageTextMatches
import com.scanwhirl.app.util.ProGate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v2 UI: 01_home_A — Library: search, create row (ID card · Photos · Import
 * PDF), six quick tools, category chips and document cards with Share and ⋯.
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
    }

    lateinit var searchInput: EditText
    lateinit var createRow: LinearLayout
    lateinit var quickRow: LinearLayout
    lateinit var docList: LinearLayout

    private val chipRow: LinearLayout
    private var chipScroll: View? = null
    private val emptyView: LinearLayout
    private val listHeader: LinearLayout
    private val countLabel: TextView
    private var sortAction: TextView? = null
    private var sort: String = com.scanwhirl.app.util.Prefs.LIB_NEWEST

    // v2 UI: 03_tools_hub — folders, tags and selection mode.
    private lateinit var folderRow: LinearLayout
    private var quickIds: MutableList<String> =
        com.scanwhirl.app.util.Prefs.quickTools(context).toMutableList()
    private var quickEditing = false
    private val quickRows = mutableListOf<LinearLayout>()
    private lateinit var quickAddGrid: LinearLayout
    private lateinit var quickEdit: ImageButton
    private lateinit var quickDone: TextView
    private lateinit var quickCancel: TextView
    private lateinit var quickAll: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    /** T32: the scroll hook that keeps the selection bar pinned in the viewport. */
    private var selectionPin: android.view.ViewTreeObserver.OnScrollChangedListener? = null
    private var folderNames: List<String> = emptyList()
    private var folder: String? = null
    private var selectionMode = false
    private val selected = linkedSetOf<String>()
    private var lastBatchUndo: List<Pair<String, String>>? = null
    private var listStaggered = false
    /** The last batch-rename Undo snackbar (tests inspect its anchored, accessible action). */
    internal var lastUndoSnackbar: com.google.android.material.snackbar.Snackbar? = null
    private var quickHeader: View? = null
    private var recentHeader: View? = null
    private var folderScroll: View? = null
    private lateinit var noMatchView: TextView
    private lateinit var homeClear: ImageButton

    private var docs: List<ScanDocument> = emptyList()
    private var query = ""
    private var category: String? = null
    private var revealId: String? = null

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

    /** Short labels that fit one line in a sixth of a 360dp phone. */
    private val quickLabels = mapOf(
        "merge" to R.string.quick_merge,
        "compress" to R.string.quick_compress,
        "sign" to R.string.quick_sign,
        "protect" to R.string.quick_protect,
        "to_word" to R.string.quick_to_word,
        "read_text" to R.string.quick_read_text
    )
    private val maxDocCards = 60
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
        }
        addView(noMatchView)
        folderScroll = folderSection()
        addView(folderScroll)
        addView(createBlock())
        quickHeader = quickToolsHeader()
        addView(quickHeader)
        quickRow = quickRowShell().apply { id = R.id.swQuickRow }
        addView(quickRow)
        quickRows += quickRow
        // Three row shells always exist so R.id.swQuickRow2/3 stay addressable (tests and
        // drag targets); only the rows a font scale needs are visible. A fourth row appears
        // on demand for the 12-tool limit at 3 columns.
        repeat(2) {
            val row = quickRowShell().apply {
                id = if (quickRows.size == 1) R.id.swQuickRow2 else R.id.swQuickRow3
                isVisible = false
            }
            addView(row)
            quickRows += row
        }
        quickAddGrid = LinearLayout(context).apply {
            id = R.id.swQuickAddGrid
            orientation = VERTICAL
            val pad = SwStyle.dp(context, 12f)
            setPadding(pad, SwStyle.dp(context, 4f), pad, 0)
            isVisible = false
        }
        addView(quickAddGrid)
        recentHeader = sectionHeader(context.getString(R.string.home_recent), context.getString(R.string.home_newest)) {
            showSortSheet()
        }
        addView(recentHeader)
        // The category chips scroll like the folder chips: five or more chips are wider than a
        // 320-360dp phone, and a plain LinearLayout squeezed the trailing ones to zero width.
        chipRow = LinearLayout(context).apply {
            id = R.id.swChipRow
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, 0, pad, 0)
        }
        chipScroll = HorizontalScrollView(context).apply {
            id = R.id.swChipScroll
            isHorizontalScrollBarEnabled = false
            addView(
                chipRow,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(chipScroll)
        addView(SwStyle.spacer(context, 6f))

        val selectBar = selectionBar()

        listHeader = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, SwStyle.dp(context, 4f), pad, SwStyle.dp(context, 6f))
        }
        countLabel = SwStyle.text(context, 13f, SwStyle.color(context, R.color.text_hologram_dim), bold = true)
        countLabel.id = R.id.swDocCount
        listHeader.addView(countLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(listHeader)

        docList = LinearLayout(context).apply {
            id = R.id.swDocList
            orientation = VERTICAL
            // The last card must clear the nav bar and the scan disc (M5: measured against the
            // real dock, never a smaller magic number).
            setPadding(0, 0, 0, listBottomInset())
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

        buildQuickRow()
    }

    // ---- pinned library chrome -------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        installChrome()
        installSelectionPin()
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
        pinned.setDocumentCount(renderedDocs.size)
    }

    // ---- public -----------------------------------------------------------

    /** Re-binds the quick tools and cards after a Free/Pro change. */
    fun refreshProState() {
        buildQuickRow()
        renderDocs()
    }

    fun reload() {
        val owner = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
        if (owner == null) {
            // No lifecycle (unit-only host): keep the old synchronous behaviour.
            docs = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
            loaded = true
            renderFolders()
            renderChips()
            renderDocs()
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
            renderFolders()
            renderChips()
            renderDocs()
        }
    }

    /**
     * C05: folder/tag/batch metadata writes rewrite every touched `meta.txt` with an fsync; they
     * run off the main thread, one writer at a time, then [after] (UI work) resumes on main.
     */
    private fun <T> ioWrite(block: () -> T, after: (T) -> Unit = {}) {
        val owner = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
        if (owner == null) {
            after(block())
            return
        }
        owner.launch {
            val result = metaWrite.withLock { withContext(ioDispatcher) { block() } }
            after(result)
        }
    }

    private val metaWrite = kotlinx.coroutines.sync.Mutex()

    /**
     * v2 UI: 01_home_A - highlight and scroll to a document after a tool's "Show in library".
     * The target is kept so the post-onResume reload shows it highlighted too.
     */
    fun revealDocument(id: String) {
        revealId = id
        reload()
    }

    /** v2 UI: 03_tools_hub — the Folders tool lands here with the folder row in view. */
    fun focusFolders() {
        renderFolders()
        Toast.makeText(context, context.getString(R.string.folder_hint), Toast.LENGTH_SHORT).show()
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
        ioWrite({ undo.forEach { (id, name) -> repo.renameDocument(id, name) } }, { reload() })
    }

    // ---- folders ----------------------------------------------------------

    private fun folderSection(): View {
        // Folder chips scroll; the A/अ language chip sits outside the scroll at the right end,
        // so it is always visible on Home whatever the number of folders.
        val section = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 8f), m, 0)
            }
        }
        val scroll = HorizontalScrollView(context).apply {
            id = R.id.swFolderScroll
            isHorizontalScrollBarEnabled = false
        }
        folderRow = LinearLayout(context).apply {
            id = R.id.swFolderRow
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(folderRow)
        section.addView(scroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        section.addView(
            languageChip(),
            LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f))
                .apply { marginStart = SwStyle.dp(context, 8f) }
        )
        return section
    }

    private fun renderFolders() {
        folderNames = runCatching { repo.listFolders() }.getOrDefault(emptyList())
        folderRow.removeAllViews()
        folderChip(
            label = context.getString(R.string.folder_all),
            active = folder == null
        ) {
            folder = null
            renderFolders()
            renderDocs()
        }
        // "+ New folder" sits right after "All" so it never scrolls off-screen as folders grow.
        folderChip(
            label = context.getString(R.string.folder_new),
            active = false,
            accent = true
        ) {
            requirePro { newFolderSheet() }
        }.id = R.id.swNewFolder
        for (name in folderNames) {
            val count = docs.count { it.folder.equals(name, true) }
            folderChip(label = "$name ($count)", active = folder.equals(name, true)) {
                folder = if (folder.equals(name, true)) null else name
                renderFolders()
                renderDocs()
            }.setOnLongClickListener {
                showFolderOptions(name)
                true
            }
        }
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
            LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f))
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
        val done = SwStyle.text(context, 13f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
            id = R.id.swSelDone
            text = context.getString(R.string.sel_done)
            setPadding(SwStyle.dp(context, 10f), 0, SwStyle.dp(context, 10f), 0)
            minimumHeight = SwStyle.dp(context, 48f)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { exitSelection() }
        }
        top.addView(done)
        bar.addView(top)

        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun action(id: Int, icon: Int, label: Int, onClick: () -> Unit) {
            val chip = SwChip(context).apply {
                this.id = id
                bind(icon, context.getString(label))
                setOnClickListener { onClick() }
                minimumHeight = SwStyle.dp(context, 48f)
                minimumWidth = SwStyle.dp(context, 48f)
            }
            actions.addView(
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
        action(R.id.swSelRename, R.drawable.ic_sym_drive_file_rename, R.string.sel_rename) {
            if (selected.size >= 2) requirePro { showBatchRename() }
            else Toast.makeText(context, context.getString(R.string.batch_select_hint), Toast.LENGTH_SHORT).show()
        }
        action(R.id.swSelDelete, R.drawable.ic_sym_delete, R.string.sel_delete) { confirmDeleteSelection() }
        scroll.addView(actions)
        bar.addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
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
        if (selectionBar.height <= 0) return
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
        val listener = android.view.ViewTreeObserver.OnScrollChangedListener { pinSelectionBar() }
        selectionPin = listener
        scroll.viewTreeObserver.addOnScrollChangedListener(listener)
    }

    private fun releaseSelectionPin() {
        val listener = selectionPin ?: return
        selectionPin = null
        val vto = viewTreeObserver
        if (vto.isAlive) vto.removeOnScrollChangedListener(listener)
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
                ioWrite({ repo.setFolder(ids, target) }) {
                    folder = target
                    exitSelection()
                    reload()
                }
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    private fun showTagsSheet() {
        val ids = selected.toList()
        val chosen = docs.filter { it.id in selected }
        val common = chosen.map { it.tags.map { t -> t.lowercase(Locale.getDefault()) }.toSet() }
            .reduceOrNull { a, b -> a intersect b } ?: emptySet()
        val allTags = runCatching { repo.listTags() }.getOrDefault(emptyList())
        val labels = if (allTags.isEmpty()) listOf(context.getString(R.string.tags_empty)) else allTags
        val checked = BooleanArray(labels.size) { i ->
            labels[i].lowercase(Locale.getDefault()) in common && allTags.isNotEmpty()
        }
        if (labels.isEmpty()) return
        SwSheet.builder(context)
            .setTitle(R.string.tags_title)
            .setOptions(labels, multiChecked = checked) { which, isChecked ->
                if (allTags.isEmpty()) return@setOptions
                val tag = allTags[which]
                if (isChecked) {
                    ioWrite({ repo.addTag(ids, tag) }, { reload() })
                } else {
                    ioWrite({ ids.forEach { repo.removeTag(it, tag) } }, { reload() })
                }
            }
            .setPrimary(R.string.tags_new) { newTagSheet(ids) }
            .setSecondary(R.string.tags_done) { reload() }
            .build()
            .show()
    }

    private fun newTagSheet(ids: List<String>) {
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.tags_new)
            .setInput(hint = context.getString(R.string.tags_hint))
            .setSecondary(R.string.sheet_cancel)
            .build()
        sheet.setPrimary(R.string.tags_add) {
            val tag = sheet.inputView?.text?.toString().orEmpty()
            ioWrite({ repo.addTag(ids, tag) }, { reload() })
        }
        sheet.show()
    }

    /**
     * T27-FIX-TRASH (data loss): the sheet says "You can restore it for 30 days", so a
     * selected document moves to Trash (Undo restores it) instead of being purged.
     */
    private fun confirmDeleteSelection() {
        val ids = selected.toList()
        SwSheet.builder(context)
            .setTitle(context.getString(R.string.sel_delete_title, ids.size))
            .setMessage(R.string.shell_trash_note)
            .setPrimary(R.string.shell_move_to_trash) {
                ioWrite({ TrashActions.moveAll(repo, ids) }) { moved ->
                    exitSelection()
                    reload()
                    val activity = context as? AppCompatActivity
                    if (activity != null) {
                        TrashActions.showMoved(activity, this, null, moved) { reload() }
                    } else if (moved.keys.isEmpty()) {
                        Toast.makeText(context, R.string.trash_move_failed, Toast.LENGTH_SHORT).show()
                    }
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
        val pill = TrustPill.offlineNoAds(context)
        // H1/H2 (200 % font, device sweep 2026-11-19): with the wordmark at 44sp the trust
        // pill was laid out past the 360dp edge and its text was cut. At large text the pill
        // takes its own line under the brand: unclipped beats density (UI_PLAN 0.8).
        if (resources.configuration.fontScale >= 1.6f) {
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.START
                setPadding(padH, SwStyle.dp(context, 10f), padH, SwStyle.dp(context, 6f))
            }
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
                pill,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 6f) }
            )
            return column
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, SwStyle.dp(context, 10f), padH, SwStyle.dp(context, 6f))
        }
        row.addView(
            mark,
            LayoutParams(SwStyle.dp(context, 32f), SwStyle.dp(context, 32f))
                .apply { marginEnd = SwStyle.dp(context, 10f) }
        )
        row.addView(wordmark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(android.view.View(context), LayoutParams(0, 1, 1f))
        row.addView(
            pill.apply { ellipsize = android.text.TextUtils.TruncateAt.END },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = SwStyle.dp(context, 8f) }
        )
        return row
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
            hint = context.getString(R.string.home_search_hint)
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
        return box.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 50f)).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 8f), m, 0)
            }
        }
    }

    private fun createBlock(): View {
        createRow = LinearLayout(context).apply {
            id = R.id.swCreateRow
            orientation = HORIZONTAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, SwStyle.dp(context, 14f), pad, 0)
        }
        createRow.addView(
            createCard(
                R.drawable.ic_sym_badge,
                context.getString(R.string.home_create_id_card),
                main = true,
                id = R.id.swCreateIdCard
            ) { callbacks.onCreateIdCard() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { minimumHeight = SwStyle.dp(context, 92f) }
        )
        createRow.addView(
            createCard(
                R.drawable.ic_sym_photo_library,
                context.getString(R.string.home_create_photos),
                main = false,
                id = R.id.swCreatePhotos
            ) { callbacks.onCreatePhotos() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply {
                    minimumHeight = SwStyle.dp(context, 92f)
                    marginStart = SwStyle.dp(context, 10f)
                }
        )
        createRow.addView(
            createCard(
                R.drawable.ic_sym_upload_file,
                context.getString(R.string.home_create_import),
                main = false,
                id = R.id.swCreateImport
            ) { callbacks.onCreateImport() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply {
                    minimumHeight = SwStyle.dp(context, 92f)
                    marginStart = SwStyle.dp(context, 10f)
                }
        )
        return createRow
    }

    private fun createCard(icon: Int, label: String, main: Boolean, id: Int, onClick: () -> Unit): View {
        val card = LinearLayout(context).apply {
            this.id = id
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = if (main) {
                SwStyle.gradient(
                    context,
                    SwStyle.color(context, R.color.sw_cyan_soft),
                    0x0D00F0FF,
                    radiusDp = 18f,
                    stroke = SwStyle.color(context, R.color.sw_cyan_hair_strong)
                )
            } else {
                SwStyle.card(context, 18f)
            }
            setOnClickListener { onClick() }
        }
        card.addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(SwStyle.dp(context, 28f), SwStyle.dp(context, 28f))
        )
        card.addView(
            SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = label
                gravity = Gravity.CENTER
                // H1: "Import PDF" at 200 % painted past its tile because the label was
                // WRAP_CONTENT. It now owns the card's width and wraps between words.
                maxLines = 2
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                val inset = SwStyle.dp(context, 4f)
                setPadding(inset, 0, inset, 0)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 8f) }
        )
        return card
    }

    private fun sectionHeader(title: String, action: String?, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, SwStyle.dp(context, 20f), pad, SwStyle.dp(context, 10f))
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
        sortAction?.text = context.getString(sortLabelRes()) + " ▾"
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
            context.getString(R.string.sort_size)
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

    /** Header with the pencil edit affordance (48dp target, 20dp glyph). */
    private fun quickToolsHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, SwStyle.dp(context, 20f), pad, SwStyle.dp(context, 6f))
        }
        row.addView(
            SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.home_quick_tools)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        quickAll = SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
            id = R.id.swQuickAll
            text = context.getString(R.string.home_all_tools)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(context, 48f)
            minimumWidth = SwStyle.dp(context, 48f)
            isClickable = true
            setPadding(SwStyle.dp(context, 10f), 0, SwStyle.dp(context, 10f), 0)
            setOnClickListener { callbacks.onAllTools() }
        }
        row.addView(quickAll)
        quickEdit = SwStyle.iconButton(
            context, R.drawable.ic_sym_edit, SwStyle.color(context, R.color.text_hologram_mid)
        ).apply {
            id = R.id.swQuickEdit
            contentDescription = context.getString(R.string.quick_edit)
            val size = SwStyle.dp(context, 48f)
            layoutParams = LayoutParams(size, size)
            setPadding(SwStyle.dp(context, 14f), SwStyle.dp(context, 14f), SwStyle.dp(context, 14f), SwStyle.dp(context, 14f))
            setOnClickListener { enterQuickEditing() }
        }
        row.addView(quickEdit)
        quickCancel = SwStyle.text(context, 13f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
            id = R.id.swQuickCancel
            text = context.getString(android.R.string.cancel)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(context, 48f)
            isClickable = true
            setPadding(SwStyle.dp(context, 8f), 0, SwStyle.dp(context, 8f), 0)
            isVisible = false
            setOnClickListener { exitQuickEditing(save = false) }
        }
        row.addView(quickCancel)
        quickDone = SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
            id = R.id.swQuickDone
            text = context.getString(R.string.sel_done)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(context, 48f)
            isClickable = true
            setPadding(SwStyle.dp(context, 8f), 0, SwStyle.dp(context, 8f), 0)
            isVisible = false
            setOnClickListener { exitQuickEditing(save = true) }
        }
        row.addView(quickDone)
        return row
    }

    private fun enterQuickEditing() {
        quickEditing = true
        applyQuickEditingChrome()
        buildQuickRow()
    }

    private fun exitQuickEditing(save: Boolean) {
        quickEditing = false
        if (save) {
            com.scanwhirl.app.util.Prefs.setQuickTools(context, quickIds)
        } else {
            quickIds = com.scanwhirl.app.util.Prefs.quickTools(context).toMutableList()
        }
        applyQuickEditingChrome()
        buildQuickRow()
        updateChrome()
    }

    private fun applyQuickEditingChrome() {
        if (!::quickEdit.isInitialized) return
        quickEdit.isVisible = !quickEditing
        quickAll.isVisible = !quickEditing
        quickDone.isVisible = quickEditing
        quickCancel.isVisible = quickEditing
        quickAddGrid.isVisible = quickEditing
    }

    /** Long-press drag reorder (also the tested persistence path). */
    internal fun moveQuickTool(id: String, toIndex: Int) {
        val from = quickIds.indexOf(id)
        if (from < 0) return
        quickIds.removeAt(from)
        quickIds.add(toIndex.coerceIn(0, quickIds.size), id)
        buildQuickRow()
    }

    /** One quick-tool row: equal columns, flush 8dp gutters, tiles top-aligned. */
    private fun quickRowShell(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        val pad = SwStyle.dp(context, 8f)
        setPadding(pad, 0, pad, 0)
    }

    private fun buildQuickRow() {
        quickAddGrid.removeAllViews()
        val tools = quickIds.mapNotNull { ToolRegistry.byId(it) }
            .take(com.scanwhirl.app.util.Prefs.MAX_QUICK_TOOLS)
        quickIds = tools.map { it.id }.toMutableList()
        val columns = quickColumns()
        val rowsNeeded = maxOf(1, (tools.size + columns - 1) / columns)
        ensureQuickRows(rowsNeeded)
        val rows = quickRows
        for (row in rows) {
            row.removeAllViews()
            row.setOnDragListener { _, event ->
                if (event.action == android.view.DragEvent.ACTION_DROP) {
                    val dragged = event.clipData?.getItemAt(0)?.text?.toString()
                    if (dragged != null && quickIds.contains(dragged)) {
                        moveQuickTool(dragged, quickIds.lastIndex)
                    }
                }
                true
            }
        }
        tools.chunked(columns).forEachIndexed { index, rowTools ->
            val row = rows.getOrNull(index) ?: return@forEachIndexed
            row.isVisible = true
            for (tool in rowTools) {
                row.addView(
                    quickToolTile(tool),
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            // Keep the grid: a short row stays left-aligned instead of stretching its lone
            // tile across the screen.
            repeat(columns - rowTools.size) {
                row.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        rows.forEachIndexed { index, row ->
            row.isVisible = tools.size > index * columns
        }
        if (quickEditing) buildQuickAddGrid()
        updateChrome()
    }

    /**
     * H1/H2 (device sweep, 200 % font): five columns on a 360dp phone leave 69dp for a label
     * that needs ~97dp at 22sp, so "Compress" broke into "Comp"/"ress" and "Read text" was
     * dropped. Large text gets fewer, wider columns (the tile grows instead of the word
     * splitting), exactly like the Tools hub.
     */
    private fun quickColumns(): Int = when {
        resources.configuration.fontScale >= 1.6f -> 3
        resources.configuration.fontScale >= 1.25f -> 4
        else -> QUICK_COLUMNS
    }

    /** Rows exist for 5/4/3 columns; the 12-tool limit needs at most four at 3 columns. */
    private fun ensureQuickRows(count: Int) {
        while (quickRows.size < count) {
            val row = quickRowShell()
            val insertAt = if (::quickAddGrid.isInitialized) indexOfChild(quickAddGrid) else -1
            if (insertAt >= 0) addView(row, insertAt) else addView(row)
            quickRows += row
        }
    }

    private fun quickToolTile(tool: SwTool): ToolTile {
        val tile = ToolTile(context)
        tile.host = host
        tile.asQuick()
        tile.bind(tool)
        quickLabels[tool.id]?.let { tile.setLabel(context.getString(it)) }
        if (quickEditing) {
            // Edit mode: tapping the minus badge (or the tile) removes it; a
            // long-press starts a drag reorder.
            tile.setOnClickListener { removeQuickTool(tool.id) }
            tile.setOnLongClickListener { view ->
                view.startDragAndDrop(
                    android.content.ClipData.newPlainText("tool", tool.id),
                    android.view.View.DragShadowBuilder(view),
                    null,
                    0
                )
                true
            }
            tile.setOnDragListener { _, event ->
                if (event.action == android.view.DragEvent.ACTION_DROP) {
                    val dragged = event.clipData?.getItemAt(0)?.text?.toString()
                    if (dragged != null && dragged != tool.id) {
                        val target = quickIds.indexOf(tool.id)
                        if (target >= 0) moveQuickTool(dragged, target)
                    }
                }
                true
            }
            val badge = ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_close)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.sw_on_gold))
                background = SwStyle.rounded(
                    context, SwStyle.color(context, R.color.gold_pro_primary), radiusDp = 999f
                )
                contentDescription = context.getString(R.string.quick_remove, context.getString(tool.title))
                setPadding(SwStyle.dp(context, 3f), SwStyle.dp(context, 3f), SwStyle.dp(context, 3f), SwStyle.dp(context, 3f))
                setOnClickListener { removeQuickTool(tool.id) }
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

    private fun removeQuickTool(id: String) {
        if (quickIds.size <= 1) return
        quickIds.remove(id)
        buildQuickRow()
    }

    private fun addQuickTool(id: String) {
        if (quickIds.size >= com.scanwhirl.app.util.Prefs.MAX_QUICK_TOOLS) return
        if (quickIds.contains(id)) return
        quickIds.add(id)
        buildQuickRow()
    }

    private fun buildQuickAddGrid() {
        val available = ToolRegistry.all.filter { it.status == ToolStatus.READY && it.id !in quickIds }
        quickAddGrid.addView(
            SwStyle.text(context, 12f, SwStyle.color(context, R.color.cyan_dim), bold = true).apply {
                id = R.id.swQuickAddTitle
                text = context.getString(R.string.quick_add_tools)
                letterSpacing = SwStyle.tracking(context, 0.02f)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        if (available.isEmpty()) return
        val atLimit = quickIds.size >= com.scanwhirl.app.util.Prefs.MAX_QUICK_TOOLS
        val columns = quickColumns()
        for (chunk in available.chunked(columns)) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (tool in chunk) {
                val tile = ToolTile(context).apply {
                    host = this@LibraryScreen.host
                    asQuick()
                    bind(tool)
                    alpha = if (atLimit) 0.38f else 1f
                    isEnabled = !atLimit
                    contentDescription = if (atLimit) {
                        context.getString(R.string.quick_max_reached)
                    } else {
                        context.getString(R.string.quick_add, context.getString(tool.title))
                    }
                    setOnClickListener {
                        if (!atLimit) addQuickTool(tool.id)
                    }
                    val plus = ImageView(context).apply {
                        setImageResource(R.drawable.ic_sym_add)
                        imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.sw_on_gold))
                        background = SwStyle.rounded(
                            context, SwStyle.color(context, R.color.gold_pro_primary), radiusDp = 999f
                        )
                        setPadding(SwStyle.dp(context, 3f), SwStyle.dp(context, 3f), SwStyle.dp(context, 3f), SwStyle.dp(context, 3f))
                    }
                    addView(
                        plus,
                        FrameLayout.LayoutParams(SwStyle.dp(context, 18f), SwStyle.dp(context, 18f)).apply {
                            gravity = Gravity.TOP or Gravity.START
                            topMargin = SwStyle.dp(context, 2f)
                            marginStart = SwStyle.dp(context, 2f)
                        }
                    )
                }
                row.addView(tile, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(columns - chunk.size) {
                row.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            quickAddGrid.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = SwStyle.dp(context, 6f) }
            )
        }
    }

    private fun renderChips() {
        chipRow.removeAllViews()
        val counts = docs.groupingBy { it.category }.eachCount()
        val entries = mutableListOf<Pair<String?, String>>()
        entries += null to context.getString(R.string.home_chip_all, docs.size)
        for (cat in CategoryRules.ALL) {
            val count = counts[cat] ?: 0
            if (count > 0) entries += cat to context.getString(R.string.home_chip_category, CategoryLabels.display(context, cat), count)
        }
        for ((key, label) in entries) {
            val active = key == category
            val chip = SwStyle.text(
                context,
                13f,
                if (active) SwStyle.color(context, R.color.cyan_primary) else SwStyle.color(context, R.color.text_hologram_mid),
                bold = true
            ).apply {
                text = label
                setPadding(SwStyle.dp(context, 14f), 0, SwStyle.dp(context, 14f), 0)
                minimumHeight = SwStyle.dp(context, 48f)
                minimumWidth = SwStyle.dp(context, 48f)
                gravity = Gravity.CENTER
                background = SwStyle.pill(
                    context,
                    if (active) SwStyle.color(context, R.color.cyan_primary) else SwStyle.color(context, R.color.text_hologram_mid),
                    if (active) SwStyle.color(context, R.color.sw_cyan_soft) else SwStyle.color(context, R.color.glass_surface),
                    if (active) SwStyle.color(context, R.color.sw_cyan_hair_strong) else SwStyle.color(context, R.color.sw_chip_border)
                )
                isClickable = true
                setOnClickListener {
                    if (key == null && category == null) {
                        // H8 (found on device): with everything already shown, the "All"
                        // chip was a dead tap. It opens the category choice instead.
                        showCategoryFilterSheet()
                        return@setOnClickListener
                    }
                    category = if (key == category) null else key
                    renderChips()
                    renderDocs()
                }
            }
            chipRow.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 48f))
                    .apply { marginEnd = SwStyle.dp(context, 8f) }
            )
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
        val needle = q.lowercase(Locale.getDefault())
        val textHits = HashSet<String>()
        val filtered = docs.filter { doc ->
            val textHit = needle.isNotEmpty() &&
                runCatching { textCache.normalized(doc.id).contains(needle) }.getOrDefault(false)
            // A locked/Vault document stays searchable by its visible name only for
            // evidence purposes: its OCR never becomes a snippet or a page link.
            if (textHit && !doc.isLocked && !doc.isVaulted) textHits += doc.id
            (folderFilter == null || doc.folder.equals(folderFilter, true)) &&
                (categoryFilter == null || doc.category == categoryFilter) &&
                (needle.isEmpty() || doc.name.lowercase(Locale.getDefault()).contains(needle) ||
                    doc.tags.any { it.lowercase(Locale.getDefault()).contains(needle) } || textHit)
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
        return VisibleDocs(sorted, textHits)
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
        for (doc in visible.docs.take(maxDocCards)) {
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
        val owner = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
        if (owner == null) {
            val visible = computeVisible(query, folder, category, sort)
            applyDocs(visible.docs, computeRowEvidence(query, visible))
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
            if (isActive) applyDocs(shown.first.docs, shown.second)
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

    /**
     * L1-2: only changed rows are rebuilt. Same ids in the same order (the
     * common rename/selection/sort-in-place case) rebinds just the rows whose
     * content signature changed; an insert/remove/filter reorders the list.
     */
    private fun applyDocs(shown: List<ScanDocument>, hits: Map<String, DocSearchHit> = emptyMap()) {
        rowEvidence = hits
        val limit = shown.take(maxDocCards)
        val signatures = limit.map { signature(it) }
        val sameIds = limit.map { it.id } == renderedDocs.map { it.id }
        if (sameIds && docList.childCount == limit.size) {
            limit.forEachIndexed { index, doc ->
                if (renderedSignatures.getOrNull(index) != signatures[index]) {
                    val card = docList.getChildAt(index) as? DocCard ?: return@forEachIndexed
                    bindCard(card, doc)
                }
            }
        } else {
            docList.removeAllViews()
            for (doc in limit) {
                docList.addView(
                    newCard(doc),
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        val m = SwStyle.dp(context, 18f)
                        setMargins(m, 0, m, SwStyle.dp(context, 10f))
                    }
                )
            }
        }
        renderedDocs = limit
        renderedSignatures = signatures

        renderSelectionBar()
        listHeader.isVisible = shown.isNotEmpty()
        countLabel.text = resources.getQuantityString(
            com.scanwhirl.app.R.plurals.list_count, shown.size, shown.size
        )
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
     * v2 UI: 01_home_A — search mode collapses everything above the results (the
     * create row, quick tools, folders and chips) so the list starts right under
     * the field; an empty library also collapses them so the empty state fits
     * above the nav/disc.
     */
    private fun updateChrome() {
        val searching = query.isNotBlank() ||
            (::searchInput.isInitialized && searchInput.hasFocus())
        val firstRun = docs.isEmpty() && !searching && folder == null && category == null
        folderScroll?.isVisible = !searching
        createRow.isVisible = !searching
        quickHeader?.isVisible = !searching && !firstRun
        for (row in quickRows) {
            row.isVisible = !searching && !firstRun && row.childCount > 0
        }
        quickAddGrid.isVisible = quickEditing && !searching
        recentHeader?.isVisible = !searching && !firstRun
        chipScroll?.isVisible = !searching && !firstRun && docs.isNotEmpty()
        listHeader.isVisible = !searching && !firstRun
        // M5: keep the list's bottom inset at or above the dock's real height (it grows when
        // the scan disc rises over the page); never a smaller magic number.
        val inset = listBottomInset()
        if (docList.paddingBottom != inset) {
            docList.setPadding(0, docList.paddingTop, 0, inset)
        }
    }

    /**
     * How far the last row and the count must sit above the viewport bottom so the floating
     * dock never covers them. Measured from the shell's dock (T23 M5); 96dp is the floor for
     * hosts without the shell.
     */
    private fun listBottomInset(): Int {
        val dock = (context as? ShellActivity)?.dockClearancePx() ?: 0
        return maxOf(SwStyle.dp(context, 96f), dock)
    }

    private fun metaFor(doc: ScanDocument): String {
        val pages = resources.getQuantityString(com.scanwhirl.app.R.plurals.pages, doc.pageCount, doc.pageCount)
        val tags = if (doc.tags.isEmpty()) "" else " · " + doc.tags.take(2).joinToString(" ") { "#$it" }
        return "$pages · ${size(doc)} · ${whenDay(doc)}$tags"
    }

    private fun size(doc: ScanDocument): String {
        val bytes = runCatching {
            if (doc.pdfFile.exists()) doc.pdfFile.length() else doc.pageFiles.sumOf { it.length() }
        }.getOrDefault(0L)
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576f)
            bytes >= 1024 -> "${bytes / 1024} KB"
            bytes > 0 -> "$bytes B"
            else -> "—"
        }
    }

    private fun whenDay(doc: ScanDocument): String {
        val docDay = Calendar.getInstance().apply { timeInMillis = doc.createdAt }
        val today = Calendar.getInstance()
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val sameDay = docDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            docDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return context.getString(R.string.shell_today)
        today.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = docDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            docDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        if (yesterday) return context.getString(R.string.shell_yesterday)
        return fmt.format(Date(doc.createdAt))
    }

    internal companion object {
        /**
         * Home quick tools: five columns, so a Hindi label keeps 11sp and two lines on a
         * 360dp phone (six columns forced 9sp labels that ran together on the lead's phone).
         * Twelve quick tools therefore use three rows.
         */
        internal const val QUICK_COLUMNS = 5

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
