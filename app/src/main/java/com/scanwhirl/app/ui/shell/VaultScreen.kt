package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: 04_vault — the Vault tab. L2 builds the encrypted store and its setup
 * flow; until that lands this screen is honest about it and never a dead end.
 */
class VaultScreen(
    private val context: Context,
    private val onComingSoon: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setPadding(SwStyle.dp(context, 18f), 0, SwStyle.dp(context, 18f), 0)

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, SwStyle.dp(context, 10f), 0, SwStyle.dp(context, 6f))
        }
        bar.addView(
            SwStyle.text(context, 24f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.vault_tab_title)
                typeface = com.scanwhirl.app.ui.kit.SwType.extraBold(context)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(TrustPill.noAds(context))
        addView(bar)

        val lock = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = SwStyle.card(context, 26f)
            val pad = SwStyle.dp(context, 22f)
            setPadding(pad, pad, pad, pad)
        }
        lock.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_shield_lock)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
            },
            LayoutParams(SwStyle.dp(context, 64f), SwStyle.dp(context, 64f))
        )
        lock.addView(
            SwStyle.text(context, 16f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.vault_tab_note)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 18f) }
        )
        addView(
            lock,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 12f) }
        )

        val note = SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
            id = R.id.swVaultNote
            text = context.getString(R.string.vault_tab_coming)
            setLineSpacing(0f, 1.35f)
        }
        addView(
            note,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 16f) }
        )

        val action = SwStyle.text(context, 15f, SwStyle.color(context, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.swVaultAction
            text = context.getString(R.string.vault_tab_open)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
            )
            isClickable = true
            setOnClickListener { onComingSoon() }
        }
        addView(
            action,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 54f))
                .apply { topMargin = SwStyle.dp(context, 18f) }
        )
    }
}
