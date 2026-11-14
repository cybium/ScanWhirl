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
        background = SwStyle.rounded(context, Color.TRANSPARENT, radiusDp = 14f)
        foreground = RippleDrawable(
            ColorStateList.valueOf(SwStyle.color(context, R.color.sw_cyan_soft)),
            null,
            SwStyle.rounded(context, Color.WHITE, radiusDp = 14f)
        )
        val d = resources.displayMetrics.density

        inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
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
            maxLines = 2
            setLineSpacing(0f, 1.15f)
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
            topMargin = (6 * d).toInt()
            marginEnd = (6 * d).toInt()
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
        proTag.isVisible = tool.pro
        soonTag.isVisible = tool.status == ToolStatus.SOON
        alpha = if (tool.status == ToolStatus.SOON) 0.55f else 1f
        contentDescription = buildString {
            append(labelView.text)
            if (tool.pro) append(", ").append(context.getString(R.string.badge_pro))
            if (tool.status == ToolStatus.SOON) append(", ").append(context.getString(R.string.tool_soon_tag))
        }
        // A Sooon/Build tile still has a real action: the sheet is set by the host.
        setOnClickListener { tool.launch(host ?: return@setOnClickListener, tool) }
    }

    /** The host that runs this tile's launch lambda (set by the screen that owns it). */
    var host: ToolHost? = null

    /** Home quick row variant: a 52dp circle behind the glyph, label underneath. */
    fun asQuick() {
        background = SwStyle.rounded(context, Color.TRANSPARENT, radiusDp = 14f)
        iconView.background = SwStyle.rounded(
            context,
            SwStyle.color(context, R.color.glass_surface_raised),
            radiusDp = 999f,
            stroke = SwStyle.color(context, R.color.sw_cyan_hair)
        )
        val pad = SwStyle.dp(context, 14f)
        iconView.setPadding(pad, pad, pad, pad)
        iconView.layoutParams = iconView.layoutParams.apply {
            width = SwStyle.dp(context, 52f)
            height = SwStyle.dp(context, 52f)
        }
        soonTag.visibility = View.GONE
        proTag.translationY = SwStyle.dp(context, 6f).toFloat()
        proTag.translationX = SwStyle.dp(context, 6f).toFloat()
    }

    private fun setImageResourceInternal(res: Int) {
        iconView.setImageResource(res)
        iconView.imageTintList = ColorStateList.valueOf(
            SwStyle.color(context, if (tool?.status == ToolStatus.SOON) R.color.text_hologram_dim else R.color.cyan_primary)
        )
    }

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
}
