package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.StudentJob
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolRow
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.kit.TrustPill
import com.scanwhirl.app.util.Prefs

/**
 * UI_PLAN §0b / tools_v4 — the Tools tab answers "what do I want done?".
 *
 * B3 (APPROVALS): three named student jobs lead the tab — "Fit a photo to a form",
 * "Sign or fill a form", "Hide private info". Each is ONE full-width 64dp+ row with a short
 * label; tapping it opens that tool's picker on the same launch path as any tile. The block
 * disappears while searching, so results are the only thing on screen.
 *
 * T1 (owner-approved 2026-11-20): "Recent tools" follows the student jobs — the last up-to-six
 * distinct tools this person launched, most recent first, each one tap on the same launch path
 * as any other tool (so Pro gating is unchanged). The row is absent for someone who has never
 * launched a tool (no placeholder), and while searching.
 *
 * Then the seven approved jobs come first in reading order (Make it smaller / Combine and
 * organise / Sign and mark up / Keep it private / Get the text / Change the format / Start
 * something new), each one a wrapping grid of [ToolTile]s. "Make it smaller" is first and
 * names Compress, so making a file smaller no longer means decoding "Optimize".
 *
 * Every job lists every one of its shipping tools directly: there is no "All N tools" door,
 * no expansion state and nothing hidden behind a tap. Membership is derived from
 * [ToolRegistry.jobs]; a Coming-soon tool is never rendered.
 *
 * Search reuses the existing ranking and draws the same rows, so "Searchable PDF" reads in
 * full. The job grids instead grow labels and reduce columns at large font sizes.
 */
class ToolsHubScreen(
    private val context: Context,
    private val host: ToolHost
) : LinearLayout(context) {

    val searchInput: EditText
    private lateinit var clearButton: ImageButton
    private lateinit var searchBox: LinearLayout
    val body: LinearLayout

    init {
        orientation = VERTICAL

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, SwStyle.dp(context, 6f), pad, SwStyle.dp(context, 2f))
        }
        bar.addView(
            SwStyle.text(context, 24f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.tools_title)
                typeface = SwType.extraBold(context)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(TrustPill.allOffline(context))
        addView(bar)

        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface),
                radiusDp = 18f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair)
            )
            setPadding(SwStyle.dp(context, 16f), 0, SwStyle.dp(context, 16f), 0)
            // W7: 50dp is the minimum, not a cap. At 200 % the 15sp query becomes 30sp and a
            // fixed 50dp box clips the field the person is typing in (UI_PLAN 0.8).
            minimumHeight = SwStyle.dp(context, 50f)
        }
        searchBox = box
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
            id = R.id.swToolsSearch
            hint = context.getString(R.string.tools_search_hint)
            contentDescription = context.getString(R.string.tools_search_hint)
            setHintTextColor(SwStyle.color(context, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(context, R.color.text_hologram_hi))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            minHeight = SwStyle.dp(context, 48f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    render(s?.toString().orEmpty())
                    if (::clearButton.isInitialized) {
                        clearButton.isVisible = !s.isNullOrBlank()
                    }
                }
            })
        }
        box.addView(searchInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        clearButton = SwStyle.iconButton(
            context, R.drawable.ic_sym_close, SwStyle.color(context, R.color.text_hologram_dim)
        ).apply {
            id = R.id.swToolsClear
            contentDescription = context.getString(R.string.tools_clear_search)
            // M2 (device sweep): the visible glyph stays small; the touch target is 48dp.
            layoutParams = LayoutParams(SwStyle.dp(context, 48f), SwStyle.dp(context, 48f))
            setPadding(SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f))
            isVisible = false
            setOnClickListener {
                searchInput.setText("")
                render("")
                isVisible = false
            }
        }
        box.addView(clearButton)
        addView(
            box,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 4f), m, SwStyle.dp(context, 8f))
            }
        )

        body = LinearLayout(context).apply {
            id = R.id.swToolsBody
            orientation = VERTICAL
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        render("")
    }

    /** Re-binds after a Free/Pro change; keeps the current search. */
    fun refreshProState() {
        render(searchInput.text?.toString().orEmpty())
    }

    /**
     * Rebuilds the body for [query]; blank shows the seven jobs.
     *
     * W7 states, each distinct and honest:
     *  - blank query, jobs present: the seven job cards;
     *  - blank query, nothing READY: "no tools available", never "no match";
     *  - query with no hit: the query is echoed, with a Clear search action;
     *  - catalogue failure: "tools could not load" with Try again, never an empty page.
     */
    fun render(query: String) {
        if (::clearButton.isInitialized) clearButton.isVisible = query.isNotBlank()
        body.removeAllViews()
        try {
            renderBody(query)
        } catch (failure: Throwable) {
            // A registry fault must not leave a blank tab that looks like "no tools".
            body.removeAllViews()
            body.addView(
                stateCard(
                    title = context.getString(R.string.w7_tools_error_title),
                    detail = context.getString(R.string.w7_tools_error_body),
                    action = context.getString(R.string.w7_tools_retry),
                    actionTag = TAG_RETRY
                ) { render(query) }
            )
        }
        if (query.isNotBlank()) showResultsFromTop()
    }

    private fun renderBody(query: String) {
        if (query.isBlank()) {
            // Only READY tools: a Coming-soon or unshipped entry is never a reachable row.
            val jobs = catalogue().map { (job, tools) ->
                job to tools.filter { it.status == ToolStatus.READY }
            }.filter { it.second.isNotEmpty() }
            if (jobs.isEmpty()) {
                body.addView(
                    stateCard(
                        title = context.getString(R.string.w7_tools_empty_title),
                        detail = context.getString(R.string.w7_tools_empty_body)
                    )
                )
                return
            }
            // B3: the three named student jobs first; T1's Recent follows them; the grouped
            // catalogue comes last. Search never builds any of these blocks.
            body.addView(studentJobCard())
            val recents = recentTools()
            if (recents.isNotEmpty()) body.addView(recentCard(recents))
            for ((job, tools) in jobs) body.addView(jobCard(job, tools))
            return
        }
        val hits = ToolRegistry.search(context, query).filter { it.status == ToolStatus.READY }
        if (hits.isEmpty()) {
            body.addView(
                stateCard(
                    title = context.getString(R.string.tools_no_match, query.trim()),
                    detail = null,
                    action = context.getString(R.string.tools_clear_search),
                    actionTag = TAG_CLEAR_EMPTY
                ) { clearSearch(keepFocus = true) }
            )
            return
        }
        body.addView(resultsCard(hits))
    }

    /**
     * W7: a query is typed while the person may have scrolled far down the job list. Scrolling
     * to the search field's own row puts the field and its results at the top of the viewport,
     * so the keyboard never leaves the results off screen.
     */
    private fun showResultsFromTop() {
        if (!::searchBox.isInitialized) return
        val top = searchBox.top
        post {
            val scroll = ancestorScroll() ?: return@post
            if (scroll.scrollY != top) scroll.scrollTo(0, top)
        }
    }

    private fun ancestorScroll(): android.widget.ScrollView? {
        var parent = parent
        while (parent != null && parent !is android.widget.ScrollView) parent = parent.parent
        return parent as? android.widget.ScrollView
    }

    /** v2 UI: 03_tools_hub — entering the tab from the nav starts from the full catalogue. */
    fun clearSearch() = clearSearch(keepFocus = false)

    /**
     * Clears the query and shows the jobs again. [keepFocus] is the Clear-search action inside
     * the page (WINNER_SPEC "collapse/clear preserve focus"); entering the tab from the dock
     * must not steal focus or raise the keyboard.
     */
    fun clearSearch(keepFocus: Boolean) {
        searchInput.setText("")
        render("")
        if (::clearButton.isInitialized) clearButton.isVisible = false
        post { ancestorScroll()?.scrollTo(0, 0) }
        if (keepFocus) focusAndAnnounce(searchInput)
    }

    /** The catalogue the hub renders: the registry, or a test's replacement. */
    private fun catalogue(): List<Pair<ToolJob, List<SwTool>>> =
        catalogueSource?.invoke() ?: ToolRegistry.jobs()

    /**
     * T1: the tools this person launched last, most recent first. Read through [Prefs], which
     * drops an id this build no longer ships (never a dead row) and keeps the list short.
     */
    private fun recentTools(): List<SwTool> = Prefs.recentTools(context)
        .mapNotNull { ToolRegistry.byId(it) }
        .filter { it.status == ToolStatus.READY }
        .take(Prefs.MAX_RECENT_TOOLS)

    /**
     * B3: one card holding the three approved student jobs. It is built only for a blank
     * query; while searching, the body contains results alone (UX_EVIDENCE Tools 1–3).
     */
    private fun studentJobCard(): View {
        val card = card()
        card.tag = TAG_STUDENT_JOBS
        // No section heading or paragraph: the three named rows ARE the section, and the
        // 40-word screen budget stays intact at 100% / 360dp (WordBudgetGateTest).
        for (job in ToolRegistry.studentJobs()) card.addView(studentJobRow(job))
        return card
    }

    /**
     * A destination row, not a tile: 44dp icon plate, one short label, chevron, 64dp minimum
     * height and the whole row as one 48dp+ touch target. The label wraps (never clips) at
     * font 2.0 / 320dp; a Pro job goes through the same [ToolHost.launchTool] gate as a tile.
     */
    private fun studentJobRow(job: StudentJob): View {
        val tool = ToolRegistry.toolFor(job)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = job
            isClickable = true
            isFocusable = true
            minimumHeight = SwStyle.dp(context, 64f)
            setPadding(SwStyle.dp(context, 12f), SwStyle.dp(context, 8f), SwStyle.dp(context, 12f), SwStyle.dp(context, 8f))
            background = SwStyle.rounded(context, Color.TRANSPARENT, radiusDp = 12f)
            foreground = RippleDrawable(
                ColorStateList.valueOf(SwStyle.color(context, R.color.sw_cyan_soft)),
                null,
                SwStyle.rounded(context, Color.WHITE, radiusDp = 12f)
            )
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val iconBox = FrameLayout(context).apply {
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.glass_surface_high), radiusDp = 14f
            )
        }
        iconBox.addView(
            ImageView(context).apply {
                setImageResource(tool.icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            FrameLayout.LayoutParams(SwStyle.dp(context, 24f), SwStyle.dp(context, 24f), Gravity.CENTER)
        )
        row.addView(iconBox, LayoutParams(SwStyle.dp(context, 44f), SwStyle.dp(context, 44f)))

        val showPro = tool.pro && !com.scanwhirl.app.util.ProGate.isPro(context)
        val label = SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_hi)).apply {
            id = R.id.swToolLabel
            typeface = SwType.semibold(context)
            text = context.getString(job.title)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 3
            ellipsize = null
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        }
        row.addView(
            label,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = SwStyle.dp(context, 12f) }
        )
        row.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_chevron_right)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_dim))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(SwStyle.dp(context, 20f), SwStyle.dp(context, 20f)).apply {
                marginStart = SwStyle.dp(context, 8f)
            }
        )
        row.contentDescription = buildString {
            append(label.text)
            if (showPro) append(", ").append(context.getString(R.string.badge_pro))
        }
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View, info: android.view.accessibility.AccessibilityNodeInfo
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
        row.setOnClickListener { this@ToolsHubScreen.host.launchTool(tool) }
        return row
    }

    /**
     * "Recent tools": the same tiles as a job, in one horizontally scrollable row, each one
     * tapping into the one launch path ([ToolHost.launchTool]) so Pro gating is unchanged.
     */
    private fun recentCard(tools: List<SwTool>): View {
        val card = card()
        card.tag = TAG_RECENT
        card.addView(jobHeader(context.getString(R.string.tools_recent_title), false))
        card.addView(headRule())
        card.addView(object : HorizontalScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                (getChildAt(0) as? ToolGrid)?.viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            addView(ToolGrid(singleRow = true).apply {
                for (tool in tools) addView(tile(tool))
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        return card
    }

    /** One job: its heading and every shipping tile, with nothing hidden behind another tap. */
    private fun jobCard(job: ToolJob, tools: List<SwTool>): View {
        val card = card()
        // The card remembers its job, so a rebuild keeps its place and tests can find it.
        card.tag = job
        card.addView(jobHeader(context.getString(job.title), job.pro))
        card.addView(headRule())
        card.addView(ToolGrid().apply {
            for (tool in tools) addView(tile(tool))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun tile(tool: SwTool): ToolTile = ToolTile(context).apply {
        host = this@ToolsHubScreen.host
        bind(tool)
        // The hub renders READY tools only, so it has no Soon placeholder, even as a hidden node.
        (soonTag.parent as? ViewGroup)?.removeView(soonTag)
        // T2 owner 2026-11-21: icon grid. Grow labels rather than hiding any part of a name.
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        labelView.ellipsize = null
        // 320dp / 2.0: keep wrapping and let the tile grow; a 2dp inset stops a centred
        // label from touching the column beside it, and no word is ever cut.
        labelView.maxLines = Int.MAX_VALUE
        val inset = SwStyle.dp(context, 2f)
        labelView.setPadding(inset, 0, inset, 0)
        // One focus stop, full tool name + Pro from bind(), not separate icon/label/badge nodes.
        (getChildAt(0) as ViewGroup).importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View, info: android.view.accessibility.AccessibilityNodeInfo
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }

    /**
     * Row-major children keep keyboard/TalkBack order identical to reading order. A 64dp
     * minimum gives five columns in the 336dp card interior on 360dp, four on 320dp. Font
     * scaling grows that minimum, not the glyph; content-sized labels may use extra lines.
     */
    inner class ToolGrid(private val singleRow: Boolean = false) : ViewGroup(context) {
        var viewportWidth = 0
        var columnCount: Int = 1
            private set
        private var columnWidth = 0
        private var rowHeights = emptyList<Int>()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val minimum = SwStyle.dp(context, 64f * maxOf(1f, resources.configuration.fontScale))
            // HorizontalScrollView gives its child UNSPECIFIED width; use its actual viewport.
            val available = if (singleRow) viewportWidth else MeasureSpec.getSize(widthMeasureSpec)
            // Five columns is the design ceiling at 100% (a wider phone must not squeeze
            // "Watermark" into six 64dp slots and break it mid-word). Large system fonts
            // still reduce the count through [minimum]; the cap only stops the 411dp phone
            // from trading whole words for a sixth column.
            val fitting = minOf(5, maxOf(1, available / minimum))
            columnWidth = maxOf(minimum, available / fitting)
            columnCount = if (singleRow) maxOf(1, childCount) else fitting
            val heights = mutableListOf<Int>()
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                child.measure(
                    MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val row = index / columnCount
                if (row == heights.size) heights += 0
                heights[row] = maxOf(heights[row], child.measuredHeight)
            }
            rowHeights = heights
            setMeasuredDimension(
                if (singleRow) columnWidth * childCount else available,
                heights.sum()
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            var y = 0
            for (index in 0 until childCount) {
                val column = index % columnCount
                if (column == 0 && index > 0) y += rowHeights[index / columnCount - 1]
                val x = column * columnWidth
                val child = getChildAt(index)
                child.layout(x, y, x + columnWidth, y + child.measuredHeight)
            }
        }
    }

    /**
     * A page state that is not a list of tools: nothing READY, no search hit, or a catalogue
     * failure. Title first, then why, then one action when the person can do something.
     */
    private fun stateCard(
        title: String,
        detail: String?,
        action: String? = null,
        actionTag: String? = null,
        onAction: (() -> Unit)? = null
    ): View {
        val card = card()
        card.addView(
            SwStyle.text(context, 16f, SwStyle.color(context, R.color.text_hologram_hi)).apply {
                id = R.id.swToolsEmpty
                typeface = SwType.semibold(context)
                text = title
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                setPadding(
                    SwStyle.dp(context, 12f), SwStyle.dp(context, 14f),
                    SwStyle.dp(context, 12f), SwStyle.dp(context, 2f)
                )
            }
        )
        if (detail != null) {
            card.addView(
                SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_dim)).apply {
                    text = detail
                    setPadding(
                        SwStyle.dp(context, 12f), SwStyle.dp(context, 2f),
                        SwStyle.dp(context, 12f), SwStyle.dp(context, 8f)
                    )
                }
            )
        }
        if (action != null && onAction != null) {
            val button = SwStyle.text(
                context, 15f, SwStyle.color(context, R.color.cyan_primary), bold = true
            ).apply {
                text = action
                gravity = Gravity.CENTER
                minimumHeight = SwStyle.dp(context, 48f)
                isClickable = true
                isFocusable = true
                tag = actionTag
                setPadding(
                    SwStyle.dp(context, 12f), SwStyle.dp(context, 8f),
                    SwStyle.dp(context, 12f), SwStyle.dp(context, 8f)
                )
                background = SwStyle.rounded(
                    context, android.graphics.Color.TRANSPARENT, radiusDp = 12f
                )
                foreground = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(SwStyle.color(context, R.color.sw_cyan_soft)),
                    null,
                    SwStyle.rounded(context, android.graphics.Color.WHITE, radiusDp = 12f)
                )
                setOnClickListener { onAction() }
            }
            card.addView(
                button,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    val m = SwStyle.dp(context, 6f)
                    setMargins(m, 0, m, SwStyle.dp(context, 6f))
                }
            )
        }
        return card
    }

    /** Search results: the same full-width rows, under one honest "Results" heading. */
    private fun resultsCard(hits: List<SwTool>): View {
        val card = card()
        card.addView(jobHeader(context.getString(R.string.tools_search_results), false))
        card.addView(headRule())
        for (tool in hits) card.addView(row(tool))
        return card
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        background = SwStyle.card(context, 18f)
        setPadding(
            SwStyle.dp(context, 4f), SwStyle.dp(context, 4f),
            SwStyle.dp(context, 4f), SwStyle.dp(context, 6f)
        )
        val m = SwStyle.dp(context, 8f)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(m, 0, m, SwStyle.dp(context, 12f))
        }
    }

    /**
     * Sentence case (never ALL CAPS), 16sp semibold. L1-04: the job header no longer repeats
     * a Pro chip — every gated tile already carries exactly one Pro indication in its own row.
     */
    private fun jobHeader(title: String, proJob: Boolean): View {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(SwStyle.dp(context, 8f), SwStyle.dp(context, 6f), SwStyle.dp(context, 8f), SwStyle.dp(context, 2f))
            minimumHeight = SwStyle.dp(context, 44f)
        }
        header.addView(
            SwStyle.text(context, 16f, SwStyle.color(context, R.color.text_hologram_hi)).apply {
                typeface = SwType.semibold(context)
                text = title
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        @Suppress("UNUSED_VARIABLE")
        val proJobUnused = proJob // signature kept for the callers and existing tests
        return header
    }

    private fun headRule(): View = View(context).apply {
        setBackgroundColor(SwStyle.color(context, R.color.sw_cyan_hair))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
            val m = SwStyle.dp(context, 8f)
            setMargins(m, SwStyle.dp(context, 2f), m, SwStyle.dp(context, 2f))
        }
    }

    private fun row(tool: SwTool): ToolRow = ToolRow(context).apply {
        host = this@ToolsHubScreen.host
        bind(tool)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    /** Keyboard and TalkBack focus in one stop: a list that rebuilds must not drop either. */
    private fun focusAndAnnounce(view: View) {
        view.requestFocus()
        androidx.core.view.ViewCompat.performAccessibilityAction(
            view,
            android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )
    }

    companion object {
        /** Tag of the B3 student-jobs card, so tests find it without a private id. */
        const val TAG_STUDENT_JOBS = "swToolsStudentJobs"

        /** Tag of the "Recent tools" card, so tests can find it without a private id. */
        const val TAG_RECENT = "swToolsRecent"

        /** Tag of the Clear search action inside the no-match state. */
        const val TAG_CLEAR_EMPTY = "swToolsClearEmpty"

        /** Tag of the Try again action inside the catalogue-error state. */
        const val TAG_RETRY = "swToolsRetry"

        /**
         * Tests only: replaces the catalogue the hub renders, so the empty and failure states
         * can be proven. Null (every real build) uses [ToolRegistry.jobs].
         */
        @Volatile
        internal var catalogueSource: (() -> List<Pair<ToolJob, List<SwTool>>>)? = null


        /** Every tool row in the hub, in screen order (tests walk them). */
        fun rows(root: View): List<ToolRow> {
            val found = mutableListOf<ToolRow>()
            fun walk(view: View) {
                if (view is ToolRow) found += view
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(root)
            return found
        }

        /** The three B3 destination rows in approved order (blank query only). */
        fun studentJobRows(root: View): List<View> {
            val card = cardTagged(root, TAG_STUDENT_JOBS) ?: return emptyList()
            return (card as? ViewGroup)?.let { group ->
                (0 until group.childCount).mapNotNull { group.getChildAt(it).takeIf { v -> v.tag is StudentJob } }
            }.orEmpty()
        }

        /** Catalogue and Recent tiles in row-major order (search intentionally uses rows). */
        fun tiles(root: View): List<ToolTile> {
            val found = mutableListOf<ToolTile>()
            fun walk(view: View) {
                if (view is ToolTile) found += view
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(root)
            return found
        }

        /** The cards the hub renders, by tag (tests find the Recent card and a job card). */
        fun cardTagged(root: View, tag: Any): View? {
            if (root.tag == tag) return root
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) cardTagged(root.getChildAt(i), tag)?.let { return it }
            }
            return null
        }
    }
}
