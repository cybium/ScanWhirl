package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import com.scanwhirl.app.R

/**
 * v2 UI: 01_home_A / 06_pro — green promise pill: "Offline · No ads".
 */
class TrustPill(context: Context) : AppCompatTextView(context) {

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
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
        setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.ok_green))
        val size = SwStyle.dp(context, 14f)
        compoundDrawablesRelative.forEach { it?.setBounds(0, 0, size, size) }
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
