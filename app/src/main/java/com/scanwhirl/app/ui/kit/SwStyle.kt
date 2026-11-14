package com.scanwhirl.app.ui.kit

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R

/**
 * v2 UI: 01_home_A — shared paint for the kit. Colours and radii come from
 * `res/values/colors.xml` and `dimens_v2.xml`, mirroring `sw.css`.
 *
 * Manrope is the design's typeface; this tree has no TTF assets (offline), so
 * kit text uses the platform sans family at the same sizes and weights.
 */
object SwStyle {

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, res: Int): Int = ContextCompat.getColor(context, res)

    /** Glass card: `#1C252A → #161D21`, 1dp cyan hairline, radius 18/26. */
    fun card(context: Context, radiusDp: Float = 18f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color(context, R.color.glass_surface_raised), color(context, R.color.glass_surface))
        ).apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setStroke(dp(context, 1f).coerceAtLeast(1), color(context, R.color.sw_cyan_hair))
        }

    /** Flat rounded surface (sheets, fields, nav bars). */
    fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Float,
        stroke: Int? = null,
        strokeDp: Float = 1f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(context, strokeDp).coerceAtLeast(1), stroke)
    }

    /** Vertical two-stop gradient. */
    fun gradient(
        context: Context,
        top: Int,
        bottom: Int,
        radiusDp: Float,
        stroke: Int? = null
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
    ).apply {
        cornerRadius = dp(context, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(context, 1f).coerceAtLeast(1), stroke)
    }

    /** The 999px pill used by trust, PRO and Soon tags. */
    fun pill(context: Context, textColor: Int, fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 999f).toFloat()
            setColor(fill)
            setStroke(dp(context, 1f).coerceAtLeast(1), stroke)
        }

    fun text(
        context: Context,
        sizeSp: Float,
        textColor: Int,
        bold: Boolean = false,
        letterSpacing: Float = 0f
    ): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(textColor)
        typeface = SwType.of(context, if (bold) SwType.BOLD else SwType.REGULAR)
        this.letterSpacing = letterSpacing
        includeFontPadding = false
    }

    /** 40dp round icon tap target with a 24dp tinted glyph. */
    fun iconButton(context: Context, iconRes: Int, tint: Int): android.widget.ImageButton =
        android.widget.ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            background = rounded(context, Color.TRANSPARENT, 999f)
            val size = dp(context, 40f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
        }

    /** Section header: uppercase label + count, on a glass card. */
    fun sectionHeader(context: Context, title: String, count: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val label = text(context, 12f, color(context, R.color.cyan_dim), bold = true, letterSpacing = 0.08f)
            label.text = title.uppercase(java.util.Locale.getDefault())
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                text(context, 12f, color(context, R.color.cyan_dim), bold = true).apply {
                    text = count.toString()
                }
            )
        }

    fun spacer(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(context, heightDp)
        )
    }
}
