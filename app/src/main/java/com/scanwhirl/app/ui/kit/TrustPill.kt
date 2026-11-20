package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import com.scanwhirl.app.R

/**
 * v2 UI: 01_home_A / 06_pro — green promise pill: "Private · No ads".
 *
 * D-11 (2026-11-20): the camera scanner, QR reader and English text reader run in Google Play
 * services and may fetch themselves once, so no pill promises that the app never touches a
 * network. The labels state where the work happens instead.
 */
class TrustPill(context: Context) : AppCompatTextView(context) {

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = SwType.bold(context)
        maxLines = 1
        // The three trust pills share one row; cap the label so 2.0x font
        // cannot make the row wider than the phone.
        val density = resources.displayMetrics.density
        val scaled = resources.displayMetrics.scaledDensity
        setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(11.5f * scaled, 13f * density))
        setTextColor(SwStyle.color(context, R.color.ok_green))
        background = SwStyle.pill(
            context,
            SwStyle.color(context, R.color.ok_green),
            SwStyle.color(context, R.color.ok_green_soft),
            SwStyle.color(context, R.color.ok_green_hair)
        )
        val padH = SwStyle.dp(context, 10f)
        val padV = SwStyle.dp(context, 5f)
        setPadding(padH, padV, padH, padV)
        compoundDrawablePadding = SwStyle.dp(context, 5f)
        includeFontPadding = false
    }

    fun set(iconRes: Int, text: CharSequence) {
        this.text = text
        // Size the icon BEFORE handing it to the TextView. Setting it with intrinsic bounds and
        // shrinking it afterwards left a 24dp slot with a 14dp glyph drawn in its top-left corner:
        // the icon sat high and far from the text.
        val size = SwStyle.dp(context, 14f)
        val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, iconRes)?.mutate()
        icon?.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawableTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.ok_green))
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    companion object {
        fun offlineNoAds(context: Context): TrustPill = TrustPill(context).apply {
            set(R.drawable.ic_sym_verified_user, context.getString(R.string.trust_offline_no_ads))
        }

        fun allOffline(context: Context): TrustPill = TrustPill(context).apply {
            set(R.drawable.ic_sym_wifi_off, context.getString(R.string.trust_all_offline))
        }

        fun noAds(context: Context): TrustPill = TrustPill(context).apply {
            set(R.drawable.ic_sym_block, context.getString(R.string.trust_no_ads))
        }

        fun noAccount(context: Context): TrustPill = TrustPill(context).apply {
            set(R.drawable.ic_sym_person_off, context.getString(R.string.trust_no_account))
        }

        fun offline(context: Context): TrustPill = TrustPill(context).apply {
            set(R.drawable.ic_sym_wifi_off, context.getString(R.string.trust_offline))
        }
    }
}
