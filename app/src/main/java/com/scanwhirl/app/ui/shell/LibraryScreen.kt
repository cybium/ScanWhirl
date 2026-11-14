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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.SwChip
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.kit.TrustPill
import com.scanwhirl.app.util.BatchRename
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
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
    private val emptyView: LinearLayout
    private val listHeader: LinearLayout
    private val countLabel: TextView
    private var sortAction: TextView? = null
    private var sort: String = com.scanwhirl.app.util.Prefs.LIB_NEWEST

    // v2 UI: 03_tools_hub — folders, tags and selection mode.
    private lateinit var folderRow: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private var folderNames: List<String> = emptyList()
    private var folder: String? = null
    private var selectionMode = false
    private val selected = linkedSetOf<String>()
    private var lastBatchUndo: List<Pair<String, String>>? = null

    private var docs: List<ScanDocument> = emptyList()
    private var query = ""
    private var category: String? = null

    private val quickIds = listOf("merge", "compress", "sign", "protect", "to_word", "read_text")

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
        background = SwStyle.rounded(context, SwStyle.color(context, R.color.rv_bg), 0f)

        addView(appBar())
        addView(searchBox())
        addView(folderSection())
        addView(createBlock())
        addView(sectionHeader(context.getString(R.string.home_quick_tools), null) {
            callbacks.onAllTools()
        })
        quickRow = LinearLayout(context).apply {
            id = R.id.swQuickRow
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val pad = SwStyle.dp(context, 8f)
            setPadding(pad, 0, pad, 0)
        }
        addView(quickRow)
        addView(sectionHeader(context.getString(R.string.home_recent), context.getString(R.string.home_newest)) {
            showSortSheet()
        })
        chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, 0, pad, 0)
        }
        addView(chipRow)
        addView(SwStyle.spacer(context, 6f))

        addView(selectionBar())

        listHeader = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, SwStyle.dp(context, 4f), pad, SwStyle.dp(context, 6f))
        }
        countLabel = SwStyle.text(context, 13f, SwStyle.color(context, R.color.text_hologram_dim), bold = true)
        listHeader.addView(countLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(listHeader)

        docList = LinearLayout(context).apply {
            id = R.id.swDocList
            orientation = VERTICAL
        }
        addView(docList)

        emptyView = LinearLayout(context).apply {
            id = R.id.swHomeEmpty
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isVisible = false
            setPadding(
                SwStyle.dp(context, 24f), SwStyle.dp(context, 30f),
                SwStyle.dp(context, 24f), SwStyle.dp(context, 30f)
            )
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_sym_document_scanner)
                    imageTintList = ColorStateList.valueOf(
                        SwStyle.color(context, R.color.text_hologram_dim)
                    )
                },
                LayoutParams(SwStyle.dp(context, 56f), SwStyle.dp(context, 56f))
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
            val scan = SwStyle.text(context, 15f, SwStyle.color(context, R.color.sw_disc_ink), bold = true).apply {
                text = context.getString(R.string.shell_nav_scan)
                gravity = Gravity.CENTER
                background = SwStyle.rounded(
                    context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
                )
                isClickable = true
                setOnClickListener { callbacks.onScan() }
            }
            addView(
                scan,
                LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f))
                    .apply { topMargin = SwStyle.dp(context, 16f) }
            )
        }
        addView(emptyView)

        buildQuickRow()
    }

    // ---- public -----------------------------------------------------------

    fun reload() {
        docs = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        renderFolders()
        renderChips()
        renderDocs()
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
        undo.forEach { (id, name) -> repo.renameDocument(id, name) }
        reload()
    }

    // ---- folders ----------------------------------------------------------

    private fun folderSection(): View {
        val scroll = HorizontalScrollView(context).apply {
            id = R.id.swFolderScroll
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 8f), m, 0)
            }
        }
        folderRow = LinearLayout(context).apply {
            id = R.id.swFolderRow
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(folderRow)
        return scroll
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
        folderChip(
            label = context.getString(R.string.folder_new),
            active = false,
            accent = true
        ) {
            requirePro { newFolderSheet() }
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
            minimumHeight = SwStyle.dp(context, 34f)
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
            LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 34f))
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
            val created = repo.createFolder(name)
            if (!created) {
                Toast.makeText(context, context.getString(R.string.folder_exists), Toast.LENGTH_SHORT).show()
            }
            reload()
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
            val renamed = repo.renameFolder(name, next)
            if (!renamed) {
                Toast.makeText(context, context.getString(R.string.folder_exists), Toast.LENGTH_SHORT).show()
            } else if (folder.equals(name, true)) {
                folder = com.scanwhirl.app.store.LibraryMeta.normalizeFolder(next)
            }
            reload()
        }
        sheet.show()
    }

    private fun confirmDeleteFolder(name: String) {
        SwSheet.builder(context)
            .setTitle(R.string.folder_delete_title)
            .setMessage(context.getString(R.string.folder_delete_msg, name))
            .setPrimary(R.string.folder_delete) {
                repo.deleteFolder(name)
                if (folder.equals(name, true)) folder = null
                reload()
                Toast.makeText(context, context.getString(R.string.folder_deleted), Toast.LENGTH_SHORT).show()
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
            minimumHeight = SwStyle.dp(context, 32f)
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
                minimumHeight = SwStyle.dp(context, 34f)
            }
            actions.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 34f))
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
        if (selectionMode) {
            selectionCount.text = resources.getQuantityString(
                R.plurals.sel_count, selected.size, selected.size
            )
        }
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
                repo.setFolder(ids, target)
                folder = target
                exitSelection()
                reload()
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
                if (isChecked) repo.addTag(ids, tag) else ids.forEach { repo.removeTag(it, tag) }
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
            repo.addTag(ids, tag)
            reload()
        }
        sheet.show()
    }

    private fun confirmDeleteSelection() {
        val ids = selected.toList()
        SwSheet.builder(context)
            .setTitle(context.getString(R.string.sel_delete_title, ids.size))
            .setMessage(R.string.shell_trash_note)
            .setPrimary(R.string.shell_move_to_trash) {
                ids.forEach { repo.deleteDocument(it) }
                exitSelection()
                reload()
                Toast.makeText(context, context.getString(R.string.shell_moved_to_trash), Toast.LENGTH_SHORT).show()
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
        chosen.forEachIndexed { index, doc ->
            val next = newNames.getOrNull(index) ?: doc.name
            if (next != doc.name) {
                repo.renameDocument(doc.id, next)
                undo += doc.id to doc.name
            }
        }
        lastBatchUndo = undo.ifEmpty { null }
        exitSelection()
        reload()
        if (undo.isEmpty()) return
        Snackbar.make(this, context.getString(R.string.batch_done, undo.size), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo_action) { undoLastBatchRename() }
            .show()
    }

    // ---- pieces -----------------------------------------------------------

    private fun appBar(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = SwStyle.dp(context, 18f)
            setPadding(padH, SwStyle.dp(context, 10f), padH, SwStyle.dp(context, 6f))
        }
        row.addView(
            ImageView(context).apply {
                id = R.id.swBrandMark
                setImageResource(R.drawable.ic_brand_mark)
            },
            LayoutParams(SwStyle.dp(context, 32f), SwStyle.dp(context, 32f))
                .apply { marginEnd = SwStyle.dp(context, 10f) }
        )
        row.addView(
            SwStyle.text(context, 22f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.app_name)
                typeface = SwType.extraBold(context)
                letterSpacing = -0.01f
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(TrustPill.offlineNoAds(context))
        return row
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
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    renderDocs()
                }
            })
        }
        box.addView(searchInput, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
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
            LayoutParams(0, SwStyle.dp(context, 92f), 1f)
        )
        createRow.addView(
            createCard(
                R.drawable.ic_sym_photo_library,
                context.getString(R.string.home_create_photos),
                main = false,
                id = R.id.swCreatePhotos
            ) { callbacks.onCreatePhotos() },
            LayoutParams(0, SwStyle.dp(context, 92f), 1f)
                .apply { marginStart = SwStyle.dp(context, 10f) }
        )
        createRow.addView(
            createCard(
                R.drawable.ic_sym_upload_file,
                context.getString(R.string.home_create_import),
                main = false,
                id = R.id.swCreateImport
            ) { callbacks.onCreateImport() },
            LayoutParams(0, SwStyle.dp(context, 92f), 1f)
                .apply { marginStart = SwStyle.dp(context, 10f) }
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
            },
            LayoutParams(SwStyle.dp(context, 28f), SwStyle.dp(context, 28f))
        )
        card.addView(
            SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = label
                gravity = Gravity.CENTER
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
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
                minimumHeight = SwStyle.dp(context, 32f)
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

    private fun buildQuickRow() {
        for (id in quickIds) {
            val tool = ToolRegistry.byId(id) ?: continue
            val tile = ToolTile(context)
            tile.host = host
            tile.asQuick()
            tile.bind(tool)
            quickLabels[id]?.let { tile.setLabel(context.getString(it)) }
            tile.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            quickRow.addView(tile)
        }
    }

    private fun renderChips() {
        chipRow.removeAllViews()
        val counts = docs.groupingBy { it.category }.eachCount()
        val entries = mutableListOf<Pair<String?, String>>()
        entries += null to context.getString(R.string.home_chip_all, docs.size)
        for (cat in CategoryRules.ALL) {
            val count = counts[cat] ?: 0
            if (count > 0) entries += cat to context.getString(R.string.home_chip_category, CategoryRules.label(cat), count)
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
                minimumHeight = SwStyle.dp(context, 34f)
                gravity = Gravity.CENTER
                background = SwStyle.pill(
                    context,
                    if (active) SwStyle.color(context, R.color.cyan_primary) else SwStyle.color(context, R.color.text_hologram_mid),
                    if (active) SwStyle.color(context, R.color.sw_cyan_soft) else SwStyle.color(context, R.color.glass_surface),
                    if (active) SwStyle.color(context, R.color.sw_cyan_hair_strong) else SwStyle.color(context, R.color.sw_chip_border)
                )
                isClickable = true
                setOnClickListener {
                    category = if (key == category) null else key
                    renderChips()
                    renderDocs()
                }
            }
            chipRow.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, 34f))
                    .apply { marginEnd = SwStyle.dp(context, 8f) }
            )
        }
    }

    private fun visibleDocs(): List<ScanDocument> {
        val q = query.lowercase(Locale.getDefault())
        val filtered = docs.filter { doc ->
            (folder == null || doc.folder.equals(folder, true)) &&
                (category == null || doc.category == category) &&
                (q.isEmpty() || doc.name.lowercase(Locale.getDefault()).contains(q) ||
                    doc.tags.any { it.lowercase(Locale.getDefault()).contains(q) } ||
                    runCatching { textCache.normalized(doc.id).contains(q) }.getOrDefault(false))
        }
        return when (sort) {
            com.scanwhirl.app.util.Prefs.LIB_OLDEST -> filtered.sortedBy { it.createdAt }
            com.scanwhirl.app.util.Prefs.LIB_NAME_ASC ->
                filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
            com.scanwhirl.app.util.Prefs.LIB_NAME_DESC ->
                filtered.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            com.scanwhirl.app.util.Prefs.LIB_SIZE -> filtered.sortedByDescending { docSize(it) }
            else -> filtered.sortedByDescending { it.createdAt }
        }
    }

    private fun docSize(doc: ScanDocument): Long = runCatching {
        if (doc.pdfFile.exists()) doc.pdfFile.length() else doc.pageFiles.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun renderDocs() {
        val shown = visibleDocs()
        docList.removeAllViews()
        renderSelectionBar()
        listHeader.isVisible = shown.isNotEmpty()
        countLabel.text = resources.getQuantityString(
            com.scanwhirl.app.R.plurals.list_count, shown.size, shown.size
        )
        for (doc in shown.take(maxDocCards)) {
            val card = DocCard(context).apply {
                bind(
                    doc,
                    metaFor(doc),
                    onShare = { callbacks.onShareDoc(doc) },
                    onMore = { callbacks.onMoreDoc(doc) }
                )
                setSelection(selectionMode, doc.id in selected)
                setOnClickListener {
                    if (selectionMode) toggleSelection(doc.id) else callbacks.onOpenDoc(doc)
                }
                setOnLongClickListener {
                    if (!selectionMode) enterSelection(doc.id) else toggleSelection(doc.id)
                    true
                }
            }
            docList.addView(
                card,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    val m = SwStyle.dp(context, 18f)
                    setMargins(m, 0, m, SwStyle.dp(context, 10f))
                }
            )
        }
        emptyView.isVisible = docs.isEmpty()
        if (docs.isEmpty()) {
            emptyView.findViewById<View>(R.id.swHomeEmpty)?.let { }
        }
        // A filter with no hits speaks with one quiet line instead of the first-run state.
        if (docs.isNotEmpty() && shown.isEmpty()) {
            emptyView.isVisible = true
            (emptyView.getChildAt(0) as? ImageView)?.setImageResource(R.drawable.ic_sym_search)
            (emptyView.getChildAt(1) as? TextView)?.text =
                context.getString(R.string.home_no_match_for, query.ifBlank { "…" })
            (emptyView.getChildAt(2) as? TextView)?.isVisible = false
            (emptyView.getChildAt(3) as? TextView)?.isVisible = false
        } else if (docs.isEmpty()) {
            (emptyView.getChildAt(0) as? ImageView)?.setImageResource(R.drawable.ic_sym_document_scanner)
            (emptyView.getChildAt(1) as? TextView)?.text = context.getString(R.string.home_empty_title)
            (emptyView.getChildAt(2) as? TextView)?.isVisible = true
            (emptyView.getChildAt(3) as? TextView)?.isVisible = true
        }
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
}
