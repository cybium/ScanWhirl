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
    private lateinit var noteView: android.widget.TextView
    private lateinit var actionView: android.widget.TextView
    private lateinit var factView: android.widget.TextView
    private lateinit var relockRow: android.widget.LinearLayout

    private enum class State { NOT_SET_UP, LOCKED, UNLOCKED }

    /**
     * VQA V049/V050: one state read, applied to every piece of status copy. The tab must never
     * claim protection ("encrypted", "AES-256", "Open Vault") while the Vault does not exist.
     * Nothing here changes the Vault's setup, gate or recovery logic — it only reads the state.
     */
    fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val state = runCatching {
            when {
                !store.isSetUp() -> State.NOT_SET_UP
                com.scanwhirl.app.store.vault.VaultSession.isUnlocked() -> State.UNLOCKED
                else -> State.LOCKED
            }
        }.getOrDefault(State.LOCKED)
        noteView.text = context.getString(
            when (state) {
                State.NOT_SET_UP -> R.string.vault_tab_note_not_setup
                State.LOCKED -> R.string.vault_tab_note_locked
                State.UNLOCKED -> R.string.vault_tab_note_unlocked
            }
        )
        statusView.text = when (state) {
            State.NOT_SET_UP -> context.getString(R.string.vault_tab_not_setup)
            State.LOCKED -> context.getString(R.string.vault_tab_locked)
            State.UNLOCKED -> {
                val count = runCatching { store.listEntries().size }.getOrDefault(0)
                context.resources.getQuantityString(R.plurals.vault_tab_unlocked, count, count)
            }
        }
        actionView.text = context.getString(
            when (state) {
                State.NOT_SET_UP -> R.string.vault_tab_setup
                State.LOCKED -> R.string.vault_tab_unlock
                State.UNLOCKED -> R.string.vault_tab_open
            }
        )
        factView.text = context.getString(
            if (state == State.NOT_SET_UP) R.string.vault_tab_fact_empty
            else R.string.vault_tab_fact_on_phone
        )
        relockRow.visibility = if (state == State.NOT_SET_UP) android.view.View.GONE
        else android.view.View.VISIBLE
    }

    /**
     * H10 (found on device): the tab only refreshed when the nav bar was tapped, so
     * coming back from VaultActivity after creating the Vault still said "Not set up
     * yet". Re-read the real state whenever this tab is shown again.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) refreshStatus()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) refreshStatus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshStatus()
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
                noteView = this
                text = context.getString(R.string.vault_tab_note_not_setup)
                gravity = Gravity.CENTER
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 16f) }
        )
        statusView = SwStyle.text(context, 13f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
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

        // VQA V051/V052: one plain-language fact per short row, icon first and never dropped
        // into wrapped text. No algorithm name is shown to a person choosing a private space.
        fun factRow(icon: Int, initial: Int): Pair<android.widget.LinearLayout, android.widget.TextView> {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                minimumHeight = SwStyle.dp(context, 48f)
            }
            row.addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(SwStyle.dp(context, 18f), SwStyle.dp(context, 18f))
                    .apply {
                        marginEnd = SwStyle.dp(context, 10f)
                        topMargin = SwStyle.dp(context, 3f)
                    }
            )
            val text = SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
                setText(initial)
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            }
            row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            return row to text
        }

        val facts = LinearLayout(context).apply {
            orientation = VERTICAL
            background = SwStyle.card(context, 18f)
            val pad = SwStyle.dp(context, 16f)
            setPadding(pad, pad, pad, pad)
        }
        val (firstRow, firstText) = factRow(
            R.drawable.ic_sym_shield_lock, R.string.vault_tab_fact_empty
        )
        factView = firstText
        facts.addView(firstRow)
        val (relock, _) = factRow(R.drawable.ic_sym_lock, R.string.vault_tab_relock)
        relockRow = relock
        facts.addView(
            relockRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 8f) }
        )
        addView(
            facts,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 12f) }
        )

        val action = SwStyle.text(context, 15f, SwStyle.color(context, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.swVaultAction
            actionView = this
            text = context.getString(R.string.vault_tab_setup)
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
