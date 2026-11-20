package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ProGate

/**
 * B4-TOOLS: one tool as a full-width row under its job on the Tools tab (UI_PLAN §0b /
 * tools_v4: icon, real name, Pro marker). The old five-column grid cut "Searchable PDF" to
 * an ellipsis on the lead's phone; a row has the whole width, so nothing has to be cut.
 *
 *  - 40dp tonal plate with a 22dp cyan glyph, then the tool's own name at 15sp semibold.
 *  - The label has no maxLines and no ellipsis: the row grows instead of dropping a word.
 *    At 200% a long Hindi name uses two or three lines and stays whole.
 *  - The word Pro sits on a gold chip on the name's own line, right after the last word, when
 *    the tool is Pro and this build is Free (a Pro build owns every tool and shows no tag).
 *    Gold is never the only signal: the chip says the word, and TalkBack reads it after the name.
 *  - 56dp tall at 100% and never below 48dp, so the whole row is the touch target.
 */
class ToolRow(context: Context) : LinearLayout(context) {

    val iconBox: FrameLayout
    val iconView: ImageView
    val labelView: TextView
    val proTag: TextView

    var tool: SwTool? = null
        private set

    /** The host that runs the tap (the shell, or a test recorder). */
    var host: ToolHost? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = dp(56f)
        val pad = dp(8f)
        setPadding(pad, pad, pad, pad)
        background = SwStyle.rounded(context, Color.TRANSPARENT, radiusDp = 12f)
        foreground = RippleDrawable(
            ColorStateList.valueOf(SwStyle.color(context, R.color.sw_cyan_soft)),
            null,
            SwStyle.rounded(context, Color.WHITE, radiusDp = 12f)
        )

        iconBox = FrameLayout(context).apply {
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface_high),
                radiusDp = 12f
            )
        }
        iconView = ImageView(context).apply {
            id = R.id.swToolIcon
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        iconBox.addView(
            iconView,
            FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER)
        )
        addView(iconBox, LayoutParams(dp(40f), dp(40f)))

        val textRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelView = SwStyle.text(
            context, LABEL_SP, SwStyle.color(context, R.color.text_hologram_hi)
        ).apply {
            id = R.id.swToolLabel
            typeface = SwType.semibold(context)
            gravity = Gravity.START
            // Never truncated: a longer name (or a bigger system font) makes a taller row.
            ellipsize = null
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            // Greedy word wrapping; a single very long word breaks instead of overflowing.
            breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        }
        textRow.addView(
            labelView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )

        proTag = SwStyle.text(
            context,
            TAG_SP,
            SwStyle.color(context, R.color.gold_pro_primary),
            bold = true,
            letterSpacing = 0.06f
        ).apply {
            id = R.id.swToolProTag
            text = context.getString(R.string.badge_pro)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TAG_SP)
            maxLines = 1
            ellipsize = null
            setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
            background = SwStyle.pill(
                context,
                SwStyle.color(context, R.color.gold_pro_primary),
                SwStyle.color(context, R.color.gold_pro_glow),
                SwStyle.color(context, R.color.gold_tile_border)
            )
            isVisible = false
        }
        // ATLAS-F2 (002): the badge sits on the name's own line, after the last word. It used
        // to drop under the label and read as a separate row. The label keeps the flexible
        // width (weight 1) and the badge is measured first, so the two never fight: a long name
        // wraps at word boundaries and the badge keeps its whole word.
        textRow.addView(
            proTag,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8f)
            }
        )
        addView(
            textRow,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) }
        )
    }

    fun bind(tool: SwTool) {
        this.tool = tool
        iconView.setImageResource(tool.icon)
        iconView.imageTintList = ColorStateList.valueOf(
            SwStyle.color(context, R.color.cyan_primary)
        )
        labelView.text = context.getString(tool.title)
        val showPro = tool.pro && !ProGate.isPro(context)
        proTag.isVisible = showPro
        contentDescription = buildString {
            append(labelView.text)
            if (showPro) append(", ").append(context.getString(R.string.badge_pro))
        }
        setOnClickListener { host?.launchTool(tool) }
    }

    private fun dp(value: Float): Int = SwStyle.dp(context, value)

    private companion object {
        /** The row label: bigger than a grid tile's, because the row has the width. */
        const val LABEL_SP = 15f

        /** The Pro chip's size; the word never shrinks or wraps. */
        const val TAG_SP = 12f
    }
}
