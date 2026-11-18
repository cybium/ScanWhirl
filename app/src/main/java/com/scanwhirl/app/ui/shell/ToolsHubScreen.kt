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
import com.scanwhirl.app.ui.kit.ToolGroup
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: 03_tools_hub — every shipped tool, grouped, searchable over titles and
 * keywords. A Coming-soon tool is not shown here at all (UI_PLAN §0b: no Soon
 * tiles); the catalogue keeps it for the Coming soon sheet.
 */
class ToolsHubScreen(
    private val context: Context,
    private val host: ToolHost
) : LinearLayout(context) {

    val searchInput: EditText
    private lateinit var clearButton: ImageButton
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
                typeface = com.scanwhirl.app.ui.kit.SwType.extraBold(context)
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
            id = R.id.swToolsSearch
            hint = context.getString(R.string.tools_search_hint)
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
                    render(s?.toString().orEmpty())
                    if (::clearButton.isInitialized) {
                        clearButton.isVisible = !s.isNullOrBlank()
                    }
                }
            })
        }
        box.addView(searchInput, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
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
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 50f)).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 4f), m, SwStyle.dp(context, 8f))
            }
        )

        body = LinearLayout(context).apply {
            id = R.id.swToolsBody
            orientation = VERTICAL
        }
        addView(body)

        render("")
    }

    /** Re-binds every tile after a Free/Pro change; keeps the current search. */
    fun refreshProState() {
        render(searchInput.text?.toString().orEmpty())
    }

    /** Rebuilds the body for [query]; blank shows every grouped section. */
    fun render(query: String) {
        if (::clearButton.isInitialized) clearButton.isVisible = query.isNotBlank()
        body.removeAllViews()
        if (query.isBlank()) {
            // UI_PLAN §0b: no Soon tiles. A group whose tools are all Coming soon is not
            // rendered at all, so the hub never shows an empty section.
            for (group in ToolGroup.entries) {
                val tools = shipped(group)
                if (tools.isEmpty()) continue
                body.addView(section(group, tools))
            }
            return
        }
        val hits = ToolRegistry.search(context, query).filter { it.status != ToolStatus.SOON }
        if (hits.isEmpty()) {
            body.addView(
                SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_dim)).apply {
                    id = R.id.swToolsEmpty
                    text = context.getString(R.string.tools_no_match, query.trim())
                    val pad = SwStyle.dp(context, 18f)
                    setPadding(pad, SwStyle.dp(context, 20f), pad, 0)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            return
        }
        body.addView(section(null, hits))
    }

    /** v2 UI: 03_tools_hub — entering the tab from the nav starts from the full catalogue. */
    fun clearSearch() {
        searchInput.setText("")
        render("")
        if (::clearButton.isInitialized) clearButton.isVisible = false
    }

    private fun section(group: ToolGroup?, tools: List<SwTool>): View {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = SwStyle.card(context, 18f)
            val m = SwStyle.dp(context, 8f)
            // No side padding: the five 48dp squares keep their own gutters inside the
            // column, and the widest label ("Searchable") needs the full column. 8dp
            // top/bottom keeps the card tight but readable; 12dp separates the cards.
            setPadding(0, SwStyle.dp(context, 8f), 0, SwStyle.dp(context, 8f))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, 0, m, SwStyle.dp(context, 12f))
            }
        }
        val title = if (group != null) context.getString(groupTitle(group)) else context.getString(R.string.tools_title)
        val header = sectionTitle(title)
        val pad = SwStyle.dp(context, 6f)
        header.setPadding(pad, 0, pad, SwStyle.dp(context, 8f))
        card.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // A 4-column grid on a normal phone; wider text gets fewer columns instead of a
        // clipped label (checkable at fontScale 1.3 on a 360dp screen).
        val columns = columns()
        var row: LinearLayout? = null
        var rows = 0
        for ((index, tool) in tools.withIndex()) {
            if (index % columns == 0) {
                row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    weightSum = columns.toFloat()
                }
                card.addView(
                    row,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        if (rows > 0) topMargin = SwStyle.dp(context, 6f)
                    }
                )
                rows++
            }
            val tile = ToolTile(context)
            tile.id = R.id.swToolTile
            tile.host = host
            tile.bind(tool)
            tile.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            row!!.addView(tile)
        }
        // Fill the last row so the columns stay aligned.
        while (row != null && row.childCount % columns != 0) {
            row.addView(View(context), LayoutParams(0, 0, 1f))
        }
        return card
    }

    /** A group's tools that actually ship: a Coming-soon tool is never a hub tile. */
    private fun shipped(group: ToolGroup): List<SwTool> =
        ToolRegistry.byGroup(group).filter { it.status != ToolStatus.SOON }

    private fun columns(): Int = when {
        resources.configuration.fontScale >= 1.6f -> 2
        resources.configuration.fontScale >= 1.25f -> 3
        // Five columns on a normal phone: the 48dp squares keep ~10dp gutters in a 68dp
        // column, the widest label ("Searchable", 65dp at 12sp) stays on one line, and the
        // five-tool Create group no longer leaves an empty second row.
        else -> 5
    }

    /** Sentence case (never ALL CAPS), 16sp semibold: a heading, not a category stamp. */
    private fun sectionTitle(title: String): TextView =
        SwStyle.text(context, 16f, SwStyle.color(context, R.color.text_hologram_hi)).apply {
            typeface = SwType.semibold(context)
            text = title
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

    private fun groupTitle(group: ToolGroup): Int = when (group) {
        ToolGroup.CREATE -> R.string.tools_group_create
        ToolGroup.ORGANIZE -> R.string.tools_group_organize
        ToolGroup.OPTIMIZE -> R.string.tools_group_optimize
        ToolGroup.CONVERT -> R.string.tools_group_convert
        ToolGroup.EDIT -> R.string.tools_group_edit
        ToolGroup.SECURE -> R.string.tools_group_secure
        ToolGroup.TEXT -> R.string.tools_group_text
    }

    companion object {
        /** Every tile in the hub, in catalogue order (tests walk them). */
        fun tiles(root: View): List<ToolTile> {
            val found = mutableListOf<ToolTile>()
            fun walk(v: View) {
                if (v is ToolTile) found += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            return found
        }
    }
}
