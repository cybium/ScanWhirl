package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scanwhirl.app.R

/**
 * W5a-2 (V060/V061): the one settings-style list row. Icon, text column and
 * chevron are placed from the SAME horizontal padding and the same token gap, so
 * they keep their x positions at 320dp and font 2.0. W5d can adopt this for the
 * Settings/Me rows instead of hand-building each row.
 *
 * The label never breaks inside a word: [SwStyle.twoLineLabel] allows two whole
 * lines and ellipsizes anything longer.
 */
class SwSettingRow(context: Context) : LinearLayout(context) {

    val iconView: ImageView
    val titleView: TextView
    val subtitleView: TextView
    val chevronView: ImageView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = SwStyle.dp(context, SwStyle.TOUCH_MIN_DP)
        val pad = SwStyle.dp(context, SwStyle.SPACE_4_DP)
        setPadding(pad, SwStyle.dp(context, 8f), pad, SwStyle.dp(context, 8f))
        background = SwStyle.rounded(context, android.graphics.Color.TRANSPARENT, SwStyle.RADIUS_CONTROL_DP)

        iconView = ImageView(context).apply {
            id = View.generateViewId()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
        }
        addView(
            iconView,
            LayoutParams(
                SwStyle.dp(context, 24f), SwStyle.dp(context, 24f)
            ).apply { marginEnd = SwStyle.dp(context, SwStyle.SPACE_3_DP) }
        )

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        titleView = SwStyle.twoLineLabel(
            context, SwType.BODY_SP, SwStyle.color(context, R.color.text_hologram_hi)
        )
        subtitleView = SwStyle.twoLineLabel(
            context, SwType.SMALL_SP, SwStyle.color(context, R.color.text_hologram_mid)
        )
        texts.addView(titleView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        texts.addView(subtitleView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(context, 2f) })
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        chevronView = ImageView(context).apply {
            id = View.generateViewId()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(R.drawable.ic_sym_chevron_right)
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.sf_outline))
        }
        addView(
            chevronView,
            LayoutParams(
                SwStyle.dp(context, 20f), SwStyle.dp(context, 20f)
            ).apply { marginStart = SwStyle.dp(context, SwStyle.SPACE_3_DP) }
        )
    }

    fun bind(iconRes: Int, title: CharSequence, subtitle: CharSequence? = null): SwSettingRow = apply {
        iconView.setImageResource(iconRes)
        titleView.text = title
        subtitleView.text = subtitle?.toString().orEmpty()
        subtitleView.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        contentDescription = if (subtitle.isNullOrBlank()) title else "$title, $subtitle"
    }
}
