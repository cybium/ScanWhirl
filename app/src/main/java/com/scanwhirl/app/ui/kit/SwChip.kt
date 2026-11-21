package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scanwhirl.app.R

/**
 * v2 UI: 08_text_ocr — the kit chip: a 48dp touch target painted 38dp tall, 12dp radius, glass fill with a
 * cyan hairline, an 18dp cyan glyph and a 13sp bold label. An optional
 * trailing view (the PRO pill) rides on the right.
 */
class SwChip(context: Context) : LinearLayout(context) {

    val iconView: ImageView
    val labelView: TextView

    init {
        id = R.id.swChip
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        val face = SwStyle.rounded(
            context,
            SwStyle.color(context, R.color.glass_surface_raised),
            radiusDp = 12f,
            stroke = SwStyle.color(context, R.color.sw_cyan_hair)
        )
        // KT1: the touch target is 48dp, but the painted face keeps its 38dp look. The inset
        // draws the same glass pill 5dp short of each edge; existing fixChip callers stay correct.
        val inset = (SwStyle.dp(context, 48f) - SwStyle.dp(context, 38f)) / 2
        background = android.graphics.drawable.InsetDrawable(face, 0, inset, 0, inset)
        val padH = SwStyle.dp(context, 10f)
        setPadding(padH, 0, padH, 0)
        minimumHeight = SwStyle.dp(context, 48f)
        SwMotion.press(this)

        iconView = ImageView(context).apply {
            id = R.id.swChipIcon
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(
            iconView,
            LayoutParams(SwStyle.dp(context, 16f), SwStyle.dp(context, 16f))
                .apply { marginEnd = SwStyle.dp(context, 6f) }
        )

        // L1-04: the chip label is body/meta text and never below 12sp.
        labelView = SwStyle.text(
            context, 12f, SwStyle.color(context, R.color.text_hologram_hi), bold = true
        ).apply {
            id = R.id.swChipLabel
            maxLines = 1
            isSingleLine = true
            includeFontPadding = false
        }
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(iconRes: Int?, label: CharSequence): SwChip = apply {
        if (iconRes != null) {
            iconView.setImageResource(iconRes)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }
        labelView.text = label
        contentDescription = label
    }

    /**
     * v2 UI: 74_motion — "Copied" morph: the chip turns into a check for 1.2 s,
     * then back. No animation (and no flicker) when animations are off.
     */
    fun flashDone(textRes: Int) {
        if (labelView.text == context.getString(textRes)) return
        val oldIcon = iconView.drawable
        val oldText = labelView.text
        iconView.setImageResource(R.drawable.ic_sym_check_circle_filled)
        labelView.text = context.getString(textRes)
        val delay = SwMotion.duration(context, 1200L).coerceAtLeast(1L)
        postDelayed({
            iconView.setImageDrawable(oldIcon)
            labelView.text = oldText
        }, delay)
    }

    /** Adds the small gold PRO pill after the label. */
    fun withProTag(): SwChip = apply {        val pill = SwStyle.text(context, 9f, SwStyle.color(context, R.color.gold_pro_primary), bold = true)
            .apply {
                text = context.getString(R.string.badge_pro)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                isSingleLine = true
                maxLines = 1
                gravity = Gravity.CENTER
                background = SwStyle.pill(
                    context,
                    SwStyle.color(context, R.color.gold_pro_primary),
                    SwStyle.color(context, R.color.gold_pro_glow),
                    SwStyle.color(context, R.color.gold_tile_border)
                )
                setPadding(
                    SwStyle.dp(context, 4f), SwStyle.dp(context, 1f),
                    SwStyle.dp(context, 4f), SwStyle.dp(context, 1f)
                )
            }
        addView(
            pill,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = SwStyle.dp(context, 6f) }
        )
    }

    /** Unavailable chips are visibly greyed (38%), matching the tiles. */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }
}
