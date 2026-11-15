package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R

/**
 * v2 UI: 01_home_A / 03_tools_hub — icon, label and an optional PRO or Soon
 * tag. Used by the Tools hub grid and the Home quick row.
 */
class ToolTile(context: Context) : FrameLayout(context) {

    val iconView: ImageView
    val labelView: TextView
    val proTag: TextView
    val soonTag: TextView

    private val inner: LinearLayout

    var tool: SwTool? = null
        private set

    init {
        isClickable = true
        isFocusable = true
        SwMotion.press(this)
        background = SwStyle.rounded(context, Color.TRANSPARENT, radiusDp = 14f)
        foreground = RippleDrawable(
            ColorStateList.valueOf(SwStyle.color(context, R.color.sw_cyan_soft)),
            null,
            SwStyle.rounded(context, Color.WHITE, radiusDp = 14f)
        )
        val d = resources.displayMetrics.density

        inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
        }
        addView(
            inner,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        iconView = ImageView(context).apply {
            id = R.id.swToolIcon
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
        }
        inner.addView(iconView, LinearLayout.LayoutParams((25 * d).toInt(), (25 * d).toInt()))

        labelView = SwStyle.text(
            context, 11.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = false
        ).apply {
            id = R.id.swToolLabel
            gravity = Gravity.CENTER
            maxLines = 3
            minLines = 2
            setLineSpacing(0f, 1.15f)
            // Break at spaces/hyphens only; a single long word shrinks instead
            // of being split mid-word ("To PowerP|oint").
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            val density = resources.displayMetrics.density
            val scaled = resources.displayMetrics.scaledDensity
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                minOf(11.5f * scaled, 14f * density)
            )
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                (8f * density).toInt(),
                (11.5f * density).toInt(),
                1,
                TypedValue.COMPLEX_UNIT_PX
            )
        }
        inner.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (7 * d).toInt() }
        )

        proTag = tagView(context, context.getString(R.string.badge_pro), gold = true)
        soonTag = tagView(context, context.getString(R.string.tool_soon_tag), gold = false)
        proTag.id = R.id.swToolProTag
        soonTag.id = R.id.swToolSoonTag
        addView(proTag, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 0
            marginEnd = 0
        })
        addView(soonTag, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (6 * d).toInt()
            marginEnd = (6 * d).toInt()
        })
    }

    fun bind(tool: SwTool) {
        this.tool = tool
        setImageResourceInternal(tool.icon)
        labelView.text = context.getString(tool.title)
        // One tag at most: Soon wins over PRO, and the tag is never dimmed.
        // A Pro build owns every tool, so no PRO tag is drawn there at all.
        val soon = tool.status == ToolStatus.SOON
        val showPro = tool.pro && !com.scanwhirl.app.util.ProGate.isPro(context)
        proTag.isVisible = showPro && !soon
        soonTag.isVisible = soon
        // Only the icon + label fade on a Soon tile; the tag stays readable.
        inner.alpha = if (soon) 0.55f else 1f
        alpha = 1f
        // Hub tiles: reserve the top corner for the tag so it never sits on the glyph.
        if (!quick) {
            val shown = proTag.isVisible || soonTag.isVisible
            (iconView.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.topMargin = if (shown) SwStyle.dp(context, 16f) else 0
                iconView.layoutParams = lp
            }
        }
        contentDescription = buildString {
            append(labelView.text)
            if (showPro) append(", ").append(context.getString(R.string.badge_pro))
            if (soon) append(", ").append(context.getString(R.string.tool_soon_tag))
        }
        // A Soon/Build tile still has a real action: the sheet is set by the host.
        // Pro gating lives in launchTool, the one dispatch point.
        setOnClickListener { host?.launchTool(tool) }
    }

    /** A short label for the Home quick row (the tile keeps its full title elsewhere). */
    fun setLabel(text: CharSequence) {
        labelView.text = text
        contentDescription = buildString {
            append(text)
            if (tool?.pro == true) append(", ").append(context.getString(R.string.badge_pro))
        }
    }

    /** The host that runs this tile's launch lambda (set by the screen that owns it). */
    var host: ToolHost? = null

    /** Home quick row variant: a 52dp circle behind the glyph, label underneath. */
    fun asQuick() {
        quick = true
        background = SwStyle.rounded(context, Color.TRANSPARENT, radiusDp = 14f)
        // No side padding: six equal columns must fit a 360dp phone exactly.
        inner.setPadding(0, SwStyle.dp(context, 4f), 0, 0)
        iconView.background = SwStyle.rounded(
            context,
            SwStyle.color(context, R.color.glass_surface_raised),
            radiusDp = 999f,
            stroke = SwStyle.color(context, R.color.sw_cyan_hair)
        )
        val pad = SwStyle.dp(context, 14f)
        iconView.setPadding(pad, pad, pad, pad)
        iconView.layoutParams = (iconView.layoutParams as LinearLayout.LayoutParams).apply {
            width = SwStyle.dp(context, 52f)
            height = SwStyle.dp(context, 52f)
            topMargin = 0
            bottomMargin = 0
        }
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        labelView.maxLines = 2
        labelView.minLines = 2
        labelView.setLineSpacing(0f, 1f)
        labelView.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        labelView.breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        val density = resources.displayMetrics.density
        val scaled = resources.displayMetrics.scaledDensity
        labelView.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            minOf(11f * scaled, 13.5f * density)
        )
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            labelView,
            (8f * density).toInt(),
            (11f * density).toInt(),
            1,
            TypedValue.COMPLEX_UNIT_PX
        )
        soonTag.visibility = View.GONE
        // PRO sits on the circle's top-right edge, inside the column.
        proTag.translationX = 0f
        proTag.translationY = 0f
        proTag.layoutParams = (proTag.layoutParams as? LayoutParams)?.apply {
            topMargin = 0
            marginEnd = 0
        } ?: proTag.layoutParams
    }

    private fun setImageResourceInternal(res: Int) {
        iconView.setImageResource(res)
        iconView.imageTintList = ColorStateList.valueOf(
            SwStyle.color(context, if (tool?.status == ToolStatus.SOON) R.color.text_hologram_dim else R.color.cyan_primary)
        )
    }

    /** True once [asQuick] turned this into the Home circle tile. */
    private var quick = false

    private fun tagView(context: Context, text: String, gold: Boolean): TextView =
        SwStyle.text(
            context,
            10f,
            SwStyle.color(context, if (gold) R.color.gold_pro_primary else R.color.text_hologram_dim),
            bold = true,
            letterSpacing = if (gold) 0.06f else 0f
        ).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (gold) 10f else 8.5f)
            setPadding(
                SwStyle.dp(context, 7f), SwStyle.dp(context, 2f),
                SwStyle.dp(context, 7f), SwStyle.dp(context, 2f)
            )
            background = if (gold) {
                SwStyle.pill(
                    context,
                    SwStyle.color(context, R.color.gold_pro_primary),
                    SwStyle.color(context, R.color.gold_pro_glow),
                    SwStyle.color(context, R.color.gold_tile_border)
                )
            } else {
                SwStyle.rounded(
                    context,
                    Color.TRANSPARENT,
                    radiusDp = 5f,
                    stroke = SwStyle.color(context, R.color.sw_field_border)
                )
            }
        }

    /** Unavailable actions are visibly greyed (38%), never a silent dead tap. */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }
}
