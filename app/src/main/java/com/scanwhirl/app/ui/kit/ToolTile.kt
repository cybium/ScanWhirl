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
 *  - the label below it: 12sp semibold (11sp in the Home quick row), centred on the tile's
 *    own width, two lines (three on Home at 200 % font), breaking on word boundaries. A Home
 *    quick tile is never ellipsized (T23, owner device sweep 2026-11-19: "Compress" ended in
 *    "…" at 200 % and "Read text" was dropped); the hub keeps an ellipsis as its last resort.
 *    The label can never be wider than its column and never draws outside the tile: a centred
 *    label whose text layout is wider than its box paints over its neighbours (lead's
 *    1.2.3-test3 check: the six Hindi quick labels ran together on one line, and "Batch
 *    tools" spilled to the left of its column in the hub);
 *  - the icon + label group centred horizontally, with the tag's band reserved above the
 *    container so tagged and untagged tiles line up;
 *  - the PRO badge (12sp, gold, the word PRO) on the container's top-right corner, clear
 *    of the glyph and clear of the tile edge — as ONE line. When the word is wider than the
 *    48dp corridor above the glyph (200 % system font on the owner's phone, B2-M1: "PR" / "O"
 *    over the icon), the badge moves under the label, where the whole column is available, and
 *    stays gold with the full word (UI_PLAN §0.11). It is never wrapped and never shrunk, and a
 *    lock glyph is not needed at any font the app supports.
 *
 * The tile is exactly its content (12dp + 48dp + 8dp + label + 8dp, no fixed minimum): the
 * lead's 2026-11-18 visual check found the old ~125dp rows wasted a screen of tools. The
 * tag's band comes out of the 12dp top space, so a larger system font grows the band
 * instead of pushing the badge off the tile. A two-line label simply grows the tile.
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

    /**
     * True when the PRO badge could not fit the 48dp corridor above the glyph and sits under the
     * label instead (large system fonts, B2-M1). Exposed so a test can tell the two placements
     * apart without guessing from pixels.
     */
    var proTagBelowLabel = false
        private set

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
            // No side padding: the label gets the tile's full width, which keeps a five
            // column grid's longest word ("Searchable") on one line at 12sp.
            setPadding(0, SwStyle.dp(context, 12f), 0, SwStyle.dp(context, 8f))
        }
        addView(
            inner,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        proTag = tagView(context, context.getString(R.string.badge_pro), gold = true)
        soonTag = tagView(context, context.getString(R.string.tool_soon_tag), gold = false)
        proTag.id = R.id.swToolProTag
        soonTag.id = R.id.swToolSoonTag

        // The badge is one line, always (tagView enforces it). 12sp gold on a 48dp corridor is
        // fine at 100 % and 130 %; at 200 % the word is wider than the corridor, so it is
        // placed under the label instead of being squeezed into it (B2-M1).
        proTag.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        proTagBelowLabel = proTag.measuredWidth > SwStyle.dp(context, 48f)

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
        // band it needs is real layout space (a negative-offset badge was clipped by its
        // container), but it is paid for out of the tile's 12dp top space, not on top of it:
        // a tile is 12 + 48 + 8 + label + 8, nothing more. A badge that cannot fit the corridor
        // (B2-M1) gets no band at all: it goes under the label instead.
        val overhang =
            if (proTagBelowLabel) 0 else maxOf(0, proTag.measuredHeight - SwStyle.dp(context, 8f))
        iconSlot = FrameLayout(context)
        iconSlot.addView(
            iconBox,
            FrameLayout.LayoutParams(
                SwStyle.dp(context, 48f), SwStyle.dp(context, 48f),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
        if (!proTagBelowLabel) {
            iconSlot.addView(
                proTag,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                )
            )
        }
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
            // Exactly the column, centred on both axes, at most two lines. Gravity.CENTER
            // (not TOP) keeps the text inside the box even if a font reports odd padding.
            gravity = Gravity.CENTER
            maxLines = 2
            // The hub keeps ellipsis as its last resort; a Home quick tile clears it in
            // asQuick() so a word is never replaced by "…" (T23 H1/H2).
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1f)
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            // Greedy word wrapping (HIGH_QUALITY let Devanagari lines come out wider than
            // the column, which a centred label paints across its neighbours); a word still
            // wider than the column is broken instead of overflowing.
            // LineBreaker's constant (== Layout.BREAK_STRATEGY_SIMPLE == 0) is a compile-time
            // int, inlined, so it is safe below API 29; it satisfies lint's WrongConstant check.
            breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        }
        inner.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(context, 8f) }
        )

        // B2-M1: at 200 % the badge does not fit the 48dp corridor, so it moves under the
        // label where the whole column is available. Same gold pill, same word, one line, and
        // it can no longer sit on the icon.
        if (proTagBelowLabel) {
            inner.addView(
                proTag,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(context, 6f) }
            )
        }

        // 12dp is the brief's minimum between the tile top and the icon container; the badge
        // hangs in that band, so the tile never grows past its content (a bigger font grows
        // the band instead of clipping the badge).
        inner.setPadding(
            0,
            maxOf(SwStyle.dp(context, 2f), SwStyle.dp(context, 12f) - overhang),
            0,
            SwStyle.dp(context, 8f)
        )
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
            // Same rule as bind(): a Pro build owns every tool, so no PRO is spoken or drawn.
            if (tool?.pro == true && !com.scanwhirl.app.util.ProGate.isPro(context)) {
                append(", ").append(context.getString(R.string.badge_pro))
            }
        }
    }

    /** The host that runs this tile's launch lambda (set by the screen that owns it). */
    var host: ToolHost? = null

    /**
     * Home quick row: five columns on a 360dp phone, so the column is narrower than the tool
     * grid. The geometry is the same (48dp container, 26dp glyph); the label is 11sp and may
     * use two lines — never smaller, because a 9sp Hindi label both reads badly and ran the
     * six labels together on the lead's phone (1.2.3-test3).
     */
    fun asQuick() {
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, QUICK_LABEL_SP)
        // T23-FIX-HOME-200 (H1/H2): a Home quick label is never truncated. On the device at
        // 200 % "Compress" became "Comp…" and "फ़ाइल छोटी करें" ended in "…" — the owner lost
        // the one word that names the tool. The tile grows instead.
        labelView.ellipsize = null
        // At 200 % a two-word Hindi label needs a third line in a narrow column; a taller
        // tile is honest, a dropped word is not. Normal sizes keep two.
        if (resources.configuration.fontScale >= 1.6f) labelView.maxLines = 3
        // A 2dp inset each side: next to five equally tight neighbours a label that fills
        // its column must not touch the one beside it.
        val inset = SwStyle.dp(context, 2f)
        labelView.setPadding(inset, 0, inset, 0)
        // VQA V081: the strip scrolls, so the last tile is only partly on screen. Keep the
        // glyph and label against the tile's LEFT edge instead of centring them: the visible
        // part of a clipped neighbour still shows the whole icon and its whole name.
        inner.gravity = Gravity.TOP or Gravity.START
        val edge = SwStyle.dp(context, 4f)
        inner.setPadding(edge, inner.paddingTop, edge, inner.paddingBottom)
        labelView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    /**
     * VQA V023: on a 640dp-tall phone (the API 24 leg) a 94dp tile pushes the second row's
     * labels under the dock. Reclaim the badge band and the generous gaps: the 48dp icon
     * container, the whole label and the 48dp touch target all stay; only empty space goes.
     */
    fun compact() {
        val d = resources.displayMetrics.density
        inner.setPadding(
            SwStyle.dp(context, 4f), (2f * d).toInt(),
            SwStyle.dp(context, 4f), (2f * d).toInt()
        )
        // Keep a 4dp band above the 48dp plate so the PRO word still clears the glyph.
        (iconSlot.layoutParams as LinearLayout.LayoutParams).height = (52f * d).toInt()
        (labelView.layoutParams as LinearLayout.LayoutParams).topMargin = (2f * d).toInt()
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
            12f,
            SwStyle.color(context, if (gold) R.color.gold_pro_primary else R.color.text_hologram_dim),
            bold = true,
            letterSpacing = if (gold) 0.06f else 0f
        ).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            // A badge is one line: the words are never broken across lines (B2-M1).
            maxLines = 1
            ellipsize = null
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
        /** The label's size in the tool grids (the hub, the stamp editor). */
        const val LABEL_SP = 12f

        /** The Home quick row's floor: never smaller, two lines instead. */
        const val QUICK_LABEL_SP = 12f
    }
}
