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
 * v2 UI: 08_text_ocr — the kit chip: 38dp tall, 12dp radius, glass fill with a
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
        background = SwStyle.rounded(
            context,
            SwStyle.color(context, R.color.glass_surface_raised),
            radiusDp = 12f,
            stroke = SwStyle.color(context, R.color.sw_cyan_hair)
        )
        val padH = SwStyle.dp(context, 12f)
        setPadding(padH, 0, padH, 0)
        minimumHeight = SwStyle.dp(context, 38f)

        iconView = ImageView(context).apply {
            id = R.id.swChipIcon
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(
            iconView,
            LayoutParams(SwStyle.dp(context, 18f), SwStyle.dp(context, 18f))
                .apply { marginEnd = SwStyle.dp(context, 7f) }
        )

        labelView = SwStyle.text(
            context, 13f, SwStyle.color(context, R.color.text_hologram_hi), bold = true
        ).apply {
            id = R.id.swChipLabel
            maxLines = 1
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

    /** Adds the small gold PRO pill after the label. */
    fun withProTag(): SwChip = apply {
        val pill = SwStyle.text(context, 9f, SwStyle.color(context, R.color.gold_pro_primary), bold = true)
            .apply {
                text = context.getString(R.string.badge_pro)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                gravity = Gravity.CENTER
                background = SwStyle.pill(
                    context,
                    SwStyle.color(context, R.color.gold_pro_primary),
                    SwStyle.color(context, R.color.gold_pro_glow),
                    SwStyle.color(context, R.color.gold_tile_border)
                )
                setPadding(
                    SwStyle.dp(context, 6f), SwStyle.dp(context, 1f),
                    SwStyle.dp(context, 6f), SwStyle.dp(context, 1f)
                )
            }
        addView(
            pill,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = SwStyle.dp(context, 6f) }
        )
    }
}
