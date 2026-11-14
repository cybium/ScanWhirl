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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.kit.TrustPill
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
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
    }

    lateinit var searchInput: EditText
    lateinit var createRow: LinearLayout
    lateinit var quickRow: LinearLayout
    lateinit var docList: LinearLayout

    private val chipRow: LinearLayout
    private val emptyView: LinearLayout
    private val listHeader: LinearLayout
    private val countLabel: TextView

    private var docs: List<ScanDocument> = emptyList()
    private var query = ""
    private var category: String? = null
    private var revealId: String? = null

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
        setPadding(0, 0, 0, SwStyle.dp(context, 12f))
        background = SwStyle.rounded(context, SwStyle.color(context, R.color.rv_bg), 0f)

        addView(appBar())
        addView(searchBox())
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
        addView(sectionHeader(context.getString(R.string.home_recent), context.getString(R.string.home_newest)))
        chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, 0, pad, 0)
        }
        addView(chipRow)
        addView(SwStyle.spacer(context, 12f))

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
        renderChips()
        renderDocs()
    }

    /**
     * v2 UI: 01_home_A — highlight and scroll to a document after a tool's "Show in library".
     * The target is kept so the post-onResume reload shows it highlighted too.
     */
    fun revealDocument(id: String) {
        revealId = id
        reload()
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
            row.addView(
                SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
                    text = action
                    isClickable = onAction != null
                    setOnClickListener { onAction?.invoke() }
                    minimumHeight = SwStyle.dp(context, 32f)
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
        }
        return row
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
        return docs.filter { doc ->
            (category == null || doc.category == category) &&
                (q.isEmpty() || doc.name.lowercase(Locale.getDefault()).contains(q) ||
                    runCatching { textCache.normalized(doc.id).contains(q) }.getOrDefault(false))
        }
    }

    private fun renderDocs() {
        val shown = visibleDocs()
        docList.removeAllViews()
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
                setOnClickListener { callbacks.onOpenDoc(doc) }
            }
            docList.addView(
                card,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    val m = SwStyle.dp(context, 18f)
                    setMargins(m, 0, m, SwStyle.dp(context, 10f))
                }
            )
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
        emptyView.isVisible = docs.isEmpty()
        if (docs.isEmpty()) {
            emptyView.findViewById<View>(R.id.swHomeEmpty)?.let { }
        }
        // A filter with no hits speaks with one quiet line instead of the first-run state.
        if (docs.isNotEmpty() && shown.isEmpty()) {
            emptyView.isVisible = true
            (emptyView.getChildAt(0) as? ImageView)?.setImageResource(R.drawable.ic_sym_search)
            (emptyView.getChildAt(1) as? TextView)?.text = context.getString(R.string.home_no_match)
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
        return "$pages · ${size(doc)} · ${whenDay(doc)}"
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
