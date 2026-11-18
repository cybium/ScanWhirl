package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
 * v2 UI: 01_home_A / 03_tools_hub — one tool tile, one geometry everywhere
 * (Tools tab, Home quick tools, the stamp editor's tool row).
 *
 * Owner critique 2026-11-18: the glyph nearly touched the tile's top edge and read
 * smaller and lighter than the label under it. A tile is now:
 *
 *  - a 48dp tonal rounded square (14dp radius) holding a 26dp accent glyph, centred;
 *  - the label below it: 12.5sp semibold, two lines, centred (the Home quick row, six
 *    columns wide, may shrink the label to fit one line, never below 9sp);
 *  - the icon + label group centred horizontally, with the tag's band reserved above the
 *    container so tagged and untagged tiles line up;
 *  - the PRO badge (12sp, gold, the word PRO) on the container's top-right corner, clear
 *    of the glyph and clear of the tile edge.
 *
 * The tag's reserved band is measured from the tag itself, so a larger system font grows
 * the tile instead of pushing the badge off its top edge.
 */
class ToolTile(context: Context) : FrameLayout(context) {

    val iconView: ImageView

    /** The 48dp tonal container behind the glyph (the PRO tag sits on its top-right corner). */
    val iconBox: FrameLayout

    /** The container plus the band the PRO tag needs above it (layout space, never clipped). */
    val iconSlot: FrameLayout
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
            setPadding((2 * d).toInt(), SwStyle.dp(context, 12f), (2 * d).toInt(), SwStyle.dp(context, 10f))
        }
        addView(
            inner,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        proTag = tagView(context, context.getString(R.string.badge_pro), gold = true)
        soonTag = tagView(context, context.getString(R.string.tool_soon_tag), gold = false)
        proTag.id = R.id.swToolProTag
        soonTag.id = R.id.swToolSoonTag

        iconBox = FrameLayout(context).apply {
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface_high),
                radiusDp = 14f
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
            FrameLayout.LayoutParams(SwStyle.dp(context, 26f), SwStyle.dp(context, 26f), Gravity.CENTER)
        )

        // The badge hangs on the container's top-right corner: its bottom edge is 8dp below
        // the container's top, so it never reaches the glyph (which starts 11dp down). The
        // slot above the container is real layout space, so nothing is drawn out of bounds
        // (a negative-offset badge was clipped by its container on the device render).
        proTag.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val overhang = maxOf(0, proTag.measuredHeight - SwStyle.dp(context, 8f))
        iconSlot = FrameLayout(context)
        iconSlot.addView(
            iconBox,
            FrameLayout.LayoutParams(
                SwStyle.dp(context, 48f), SwStyle.dp(context, 48f),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        iconSlot.addView(
            proTag,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        iconSlot.addView(
            soonTag,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        inner.addView(
            iconSlot,
            LinearLayout.LayoutParams(
                SwStyle.dp(context, 48f), SwStyle.dp(context, 48f) + overhang
            )
        )

        labelView = SwStyle.text(
            context, LABEL_SP, SwStyle.color(context, R.color.text_hologram_hi), bold = false
        ).apply {
            id = R.id.swToolLabel
            typeface = SwType.semibold(context)
            // Horizontally centred, top aligned: Gravity.CENTER would also centre a one-line
            // label inside its two-line box and push it away from the icon.
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            maxLines = 2
            // Two lines are always reserved so a row of tiles keeps one icon line and one
            // label line, tagged or not.
            minLines = 2
            setLineSpacing(0f, 1.1f)
            // Break at spaces/hyphens only; a single long word shrinks instead
            // of being split mid-word ("To PowerP|oint").
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            // Same value as LineBreaker.BREAK_STRATEGY_HIGH_QUALITY (API 29+); Layout's constant works from API 23.
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        }
        inner.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(context, 8f) }
        )

        // 12dp is the brief's minimum between the tile top and the icon container; the tag
        // hangs in the extra band above the container, so it never touches the tile edge.
        minimumHeight = SwStyle.dp(context, 104f)
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
        contentDescription = buildString {
            append(labelView.text)
            if (showPro) append(", ").append(context.getString(R.string.badge_pro))
            if (soon) append(", ").append(context.getString(R.string.tool_soon_tag))
        }
        // Soon tiles are no longer rendered by the hubs; the sheet stays reachable from the
        // catalogue. Wiring the tap here keeps every tile a real action.
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

    /**
     * Home quick row: six columns on a 360dp phone, so the column is narrower than the tool
     * grid. The geometry is the same (48dp container, 26dp glyph, 12.5sp semibold label); the
     * label may shrink to fit one line, never below 9sp.
     */
    fun asQuick() {
        quick = true
        inner.setPadding(0, inner.paddingTop, 0, inner.paddingBottom)
        labelView.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            LABEL_SP * resources.displayMetrics.scaledDensity
        )
        // Six columns on a 360dp phone are narrower than a grid tile; onMeasure fits the
        // label to the column so a word like "Compress" stays on one line.
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (quick) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight -
                inner.paddingLeft - inner.paddingRight -
                labelView.totalPaddingLeft - labelView.totalPaddingRight
            fitQuickLabel(avail)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Largest label size in 9..12.5sp that keeps the quick row's label on one line. */
    private fun fitQuickLabel(availPx: Int) {
        val text = labelView.text
        if (availPx <= 0 || text.isNullOrEmpty()) return
        val scaled = resources.displayMetrics.scaledDensity
        val minPx = 9f * scaled
        val paint = android.text.TextPaint(labelView.paint)
        var size = LABEL_SP * scaled
        paint.textSize = size
        while (size > minPx && paint.measureText(text, 0, text.length) > availPx) {
            size -= 0.5f
            paint.textSize = size
        }
        if (kotlin.math.abs(labelView.textSize - size) > 0.1f) {
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun setImageResourceInternal(res: Int) {
        iconView.setImageResource(res)
        iconView.imageTintList = ColorStateList.valueOf(
            SwStyle.color(context, if (tool?.status == ToolStatus.SOON) R.color.text_hologram_dim else R.color.cyan_primary)
        )
    }

    /** True once [asQuick] turned this into the Home quick-row tile. */
    private var quick = false

    private fun tagView(context: Context, text: String, gold: Boolean): TextView =
        SwStyle.text(
            context,
            12f,
            SwStyle.color(context, if (gold) R.color.gold_pro_primary else R.color.text_hologram_dim),
            bold = true,
            letterSpacing = if (gold) 0.06f else 0f
        ).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(
                SwStyle.dp(context, 6f), SwStyle.dp(context, 1f),
                SwStyle.dp(context, 6f), SwStyle.dp(context, 1f)
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

    private companion object {
        /** The label's ceiling; [asQuick] may shrink it to fit a six-column row. */
        const val LABEL_SP = 12.5f
    }
}
