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
import com.scanwhirl.app.ui.kit.ToolGroup
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: 03_tools_hub — every tool, grouped, searchable over titles and
 * keywords. A Soon tile is never a dead tap: it opens the Coming soon sheet.
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
            setPadding(pad, SwStyle.dp(context, 10f), pad, SwStyle.dp(context, 6f))
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
            layoutParams = LayoutParams(SwStyle.dp(context, 36f), SwStyle.dp(context, 36f))
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
                setMargins(m, SwStyle.dp(context, 8f), m, SwStyle.dp(context, 12f))
            }
        )

        body = LinearLayout(context).apply {
            id = R.id.swToolsBody
            orientation = VERTICAL
        }
        addView(body)

        render("")
    }

    /** Rebuilds the body for [query]; blank shows every group. */
    fun render(query: String) {
        if (::clearButton.isInitialized) clearButton.isVisible = query.isNotBlank()
        body.removeAllViews()
        if (query.isBlank()) {
            for ((group, tools) in ToolRegistry.grouped()) {
                body.addView(section(group, tools))
            }
            return
        }
        val hits = ToolRegistry.search(context, query)
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
            val m = SwStyle.dp(context, 14f)
            val pad = SwStyle.dp(context, 10f)
            setPadding(pad, SwStyle.dp(context, 12f), pad, SwStyle.dp(context, 6f))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, 0, m, SwStyle.dp(context, 10f))
            }
        }
        val title = if (group != null) context.getString(groupTitle(group)) else context.getString(R.string.tools_title)
        val header = SwStyle.sectionHeader(context, title, tools.size)
        val pad = SwStyle.dp(context, 6f)
        header.setPadding(pad, 0, pad, SwStyle.dp(context, 10f))
        card.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        var row: LinearLayout? = null
        for ((index, tool) in tools.withIndex()) {
            if (index % 4 == 0) {
                row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    weightSum = 4f
                }
                card.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            val tile = ToolTile(context)
            tile.id = R.id.swToolsGrid
            tile.host = host
            tile.bind(tool)
            tile.layoutParams = LayoutParams(0, SwStyle.dp(context, 96f), 1f)
            row!!.addView(tile)
        }
        // Fill the last row so four columns stay aligned.
        while (row != null && row.childCount % 4 != 0) {
            row.addView(View(context), LayoutParams(0, SwStyle.dp(context, 96f), 1f))
        }
        return card
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
