package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolRow
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.TrustPill
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate

/**
 * UI_PLAN §0b / tools_v4 — the Tools tab answers "what do I want done?".
 *
 * T1 (owner-approved 2026-11-20): everyday tools come first. Under the search field sits
 * "Recent tools" — the last up-to-six distinct tools this person launched, most recent first,
 * each one tap on the same launch path as any other row (so Pro gating is unchanged). The row
 * is absent for someone who has never launched a tool (no placeholder), and while searching.
 *
 * Then the seven approved jobs come first in reading order (Make it smaller / Combine and
 * organise / Sign and mark up / Keep it private / Get the text / Change the format / Start
 * something new), each one a card of full-width [ToolRow]s. "Make it smaller" is first and
 * names Compress, so making a file smaller no longer means decoding "Optimize".
 *
 * Every job lists every one of its shipping tools directly: there is no "All N tools" door,
 * no expansion state and nothing hidden behind a tap. Membership is derived from
 * [ToolRegistry.jobs]; a Coming-soon tool is never rendered.
 *
 * Search reuses the existing ranking and draws the same rows, so "Searchable PDF" reads in
 * full instead of the old five-column "…".
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
            // T1: the everyday tools first, then every job with every tool open.
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
     * "Recent tools": the same full-width rows as a job, under their own heading, each one
     * tapping into the one launch path ([ToolHost.launchTool]) so Pro gating is unchanged.
     */
    private fun recentCard(tools: List<SwTool>): View {
        val card = card()
        card.tag = TAG_RECENT
        card.addView(jobHeader(context.getString(R.string.tools_recent_title), false))
        card.addView(headRule())
        for (tool in tools) card.addView(row(tool))
        return card
    }

    /** One job: its heading and every one of its shipping rows, with nothing hidden. */
    private fun jobCard(job: ToolJob, tools: List<SwTool>): View {
        val card = card()
        // The card remembers its job, so a rebuild keeps its place and tests can find it.
        card.tag = job
        card.addView(jobHeader(context.getString(job.title), job.pro))
        card.addView(headRule())
        for (tool in tools) card.addView(row(tool))
        return card
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

    /** Sentence case (never ALL CAPS), 16sp semibold, with the job's Pro word in Free builds. */
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
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        if (proJob && !ProGate.isPro(context)) {
            header.addView(proChip(), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        return header
    }

    private fun headRule(): View = View(context).apply {
        setBackgroundColor(SwStyle.color(context, R.color.sw_cyan_hair))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
            val m = SwStyle.dp(context, 8f)
            setMargins(m, SwStyle.dp(context, 2f), m, SwStyle.dp(context, 2f))
        }
    }

    private fun proChip(): TextView = SwStyle.text(
        context,
        PRO_TAG_SP,
        SwStyle.color(context, R.color.gold_pro_primary),
        bold = true,
        letterSpacing = 0.06f
    ).apply {
        text = context.getString(R.string.badge_pro)
        maxLines = 1
        ellipsize = null
        setPadding(
            SwStyle.dp(context, 6f), SwStyle.dp(context, 1f),
            SwStyle.dp(context, 6f), SwStyle.dp(context, 1f)
        )
        background = SwStyle.pill(
            context,
            SwStyle.color(context, R.color.gold_pro_primary),
            SwStyle.color(context, R.color.gold_pro_glow),
            SwStyle.color(context, R.color.gold_tile_border)
        )
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

        private const val PRO_TAG_SP = 12f

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
