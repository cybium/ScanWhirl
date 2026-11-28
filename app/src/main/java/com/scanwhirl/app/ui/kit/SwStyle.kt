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
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.util.AppLanguage

/**
 * v2 UI: 01_home_A — shared paint for the kit. Colours and radii come from
 * `res/values/colors.xml` and `dimens_v2.xml`, mirroring `sw.css`.
 *
 * Manrope is the design's typeface; this tree has no TTF assets (offline), so
 * kit text uses the platform sans family at the same sizes and weights.
 */
object SwStyle {

    /** W5a: the one space scale (frontend-v09/core/tokens.css `--sp1`..`--sp6`). */
    const val SPACE_1_DP = 4f
    const val SPACE_2_DP = 8f
    const val SPACE_3_DP = 12f
    const val SPACE_4_DP = 16f
    const val SPACE_5_DP = 20f
    const val SPACE_6_DP = 24f

    /** W5a: the one shape scale (`--r-card`, `--r-ctl`, `--r-sheet`). */
    const val RADIUS_CARD_DP = 16f
    const val RADIUS_CONTROL_DP = 12f
    const val RADIUS_SHEET_DP = 24f

    /** W5a: `--touch`. Every tappable kit control keeps at least this box. */
    const val TOUCH_MIN_DP = 48f

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, res: Int): Int =
        if (SwTheme.isGold(context)) SwTheme.accent(context, res)
        else ContextCompat.getColor(context, res)

    /**
     * Kit card. The two surfaces are semantic tokens (light: white/#eef3f4,
     * dark: #162024/#1d2a2f), so no dark-only literal survives the card paint.
     * Radius is the token card radius; callers may still pass an explicit legacy value.
     */
    fun card(context: Context, radiusDp: Float = RADIUS_CARD_DP): GradientDrawable =
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

    /**
     * Shared text factory. [metadata] is additive: callers that do not pass it keep the exact
     * old size and `includeFontPadding = false`. Touched metadata callers get a 12sp floor
     * (14sp for Hindi, where matras need their padding back).
     */
    fun text(
        context: Context,
        sizeSp: Float,
        textColor: Int,
        bold: Boolean = false,
        letterSpacing: Float = 0f,
        metadata: Boolean = false
    ): TextView = TextView(context).apply {
        val size = if (metadata) maxOf(sizeSp, metaSizeSp(context)) else sizeSp
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(textColor)
        typeface = SwType.of(context, if (bold) SwType.BOLD else SwType.REGULAR)
        // Latin tracking pulls Devanagari matras off their base letters. Hindi keeps 0.
        this.letterSpacing = tracking(context, letterSpacing)
        includeFontPadding = metadata && AppLanguage.showingHindi(context)
    }

    /** L5 role token: 12sp metadata floor, 14sp where Devanagari is on screen. */
    fun metaSizeSp(context: Context): Float =
        if (AppLanguage.showingHindi(context)) 14f else 12f

    /**
     * Em tracking for kit labels. English keeps the requested value; Hindi is always 0
     * so a section header or chip does not break matras the way Latin small-caps tracking does.
     */
    fun tracking(context: Context, em: Float): Float =
        if (em == 0f || AppLanguage.showingHindi(context)) 0f else em

    /**
     * Decorative views used as actions must speak as Buttons. Additive: it changes only the
     * accessibility node and focusability, never the label, colour or click listener.
     */
    fun asButton(view: View): View = view.apply {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }

    /** TextView overload keeps the old call sites and their fluent type. */
    fun asButton(view: TextView): TextView = view.apply { asButton(this as View) }

    /**
     * A new quiet action with a boundary that clears 3:1 against the adjacent ink surface and a
     * visible focus ring. Only for NEW controls; existing callers keep their own backgrounds.
     */
    fun quietAction(
        context: Context,
        view: TextView,
        radiusDp: Float = RADIUS_CONTROL_DP,
        strokeDp: Float = 1f
    ): TextView = asButton(view).apply {
        isClickable = true
        minimumHeight = dp(context, TOUCH_MIN_DP)
        val padH = dp(context, 12f)
        setPadding(padH, dp(context, 8f), padH, dp(context, 8f))
        val rest = rounded(
            context, Color.TRANSPARENT, radiusDp,
            stroke = color(context, R.color.sw_boundary_functional), strokeDp = strokeDp
        )
        val focused = rounded(
            context, color(context, R.color.rv_surface_2), radiusDp,
            stroke = color(context, R.color.sw_boundary_functional), strokeDp = 2f
        )
        background = rest
        setOnFocusChangeListener { _, hasFocus -> background = if (hasFocus) focused else rest }
    }

    /** 48dp round icon tap target with the same 24dp tinted glyph. */
    fun iconButton(context: Context, iconRes: Int, tint: Int): android.widget.ImageButton =
        android.widget.ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            background = rounded(context, Color.TRANSPARENT, 999f)
            val size = dp(context, TOUCH_MIN_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            // CENTER_INSIDE never upscales the intrinsic 24dp vector. Retain 8dp padding:
            // callers with an explicit smaller box (e.g. VaultCard) keep their old glyph size.
            setPadding(dp(context, 8f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
        }

    /** Section header: uppercase label + count, on a glass card. */
    fun sectionHeader(context: Context, title: String, count: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val label = text(context, SwType.CAPTION_SP, color(context, R.color.text_hologram_mid), bold = true, letterSpacing = 0.08f)
            label.text = title.uppercase(java.util.Locale.getDefault())
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                text(context, SwType.CAPTION_SP, color(context, R.color.text_hologram_mid), bold = true).apply {
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
