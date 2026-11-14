package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: 04_vault — the Vault tab's entry state: the shield emblem, one honest
 * line and the one button that opens L2's encrypted Vault.
 */
class VaultScreen(
    private val context: Context
) : LinearLayout(context) {

    private val store by lazy {
        com.scanwhirl.app.store.vault.VaultStore(
            context, com.scanwhirl.app.store.ScanRepository(context)
        )
    }
    private lateinit var statusView: android.widget.TextView

    /** v2 UI: 04_vault — L2's live state: set up, locked/unlocked and how many files. */
    fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val text = runCatching {
            when {
                !store.isSetUp() -> context.getString(R.string.vault_tab_not_setup)
                com.scanwhirl.app.store.vault.VaultSession.isUnlocked() -> {
                    val count = runCatching { store.listEntries().size }.getOrDefault(0)
                    context.resources.getQuantityString(
                        R.plurals.vault_tab_unlocked, count, count
                    )
                }
                else -> context.getString(R.string.vault_tab_locked)
            }
        }.getOrDefault(context.getString(R.string.vault_tab_locked))
        statusView.text = text
    }

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
                typeface = SwType.extraBold(context)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(TrustPill.offlineNoAds(context))
        addView(bar)

        // Emblem: the shield on the kit's glass tile, centred like the mockup.
        val emblem = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = SwStyle.card(context, 26f)
            setPadding(
                SwStyle.dp(context, 22f), SwStyle.dp(context, 26f),
                SwStyle.dp(context, 22f), SwStyle.dp(context, 26f)
            )
        }
        emblem.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_shield_lock)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(SwStyle.dp(context, 64f), SwStyle.dp(context, 64f))
        )
        emblem.addView(
            SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
                id = R.id.swVaultNote
                text = context.getString(R.string.vault_tab_encrypted)
                gravity = Gravity.CENTER
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 16f) }
        )
        statusView = SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_dim), bold = true).apply {
            id = R.id.swVaultStatus
            gravity = Gravity.CENTER
            text = context.getString(R.string.vault_tab_status_loading)
        }
        emblem.addView(
            statusView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 6f) }
        )
        addView(
            emblem,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 12f) }
        )

        // v2 UI: 04_vault — the honest facts, so the tab is never half empty.
        val facts = LinearLayout(context).apply {
            orientation = VERTICAL
            background = SwStyle.card(context, 18f)
            val pad = SwStyle.dp(context, 16f)
            setPadding(pad, pad, pad, pad)
        }
        for (line in listOf(R.string.vault_tab_aes, R.string.vault_tab_relock)) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = SwStyle.dp(context, 34f)
            }
            row.addView(
                ImageView(context).apply {
                    setImageResource(
                        if (line == R.string.vault_tab_aes) R.drawable.ic_sym_encrypted
                        else R.drawable.ic_sym_lock
                    )
                    imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(SwStyle.dp(context, 18f), SwStyle.dp(context, 18f))
                    .apply { marginEnd = SwStyle.dp(context, 10f) }
            )
            row.addView(
                SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
                    setText(line)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
            facts.addView(row)
        }
        addView(
            facts,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 12f) }
        )

        val action = SwStyle.text(context, 15f, SwStyle.color(context, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.swVaultAction
            text = context.getString(R.string.vault_tab_open)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { context.startActivity(com.scanwhirl.app.ui.vault.VaultActivity.intent(context)) }
        }
        addView(
            action,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 54f))
                .apply { topMargin = SwStyle.dp(context, 18f) }
        )
    }
}
