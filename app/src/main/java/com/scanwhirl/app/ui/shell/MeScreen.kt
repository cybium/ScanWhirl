package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: Me tab — the restyled Settings entry, Pro, Trash and About.
 */
class MeScreen(
    private val context: Context,
    private val callbacks: Callbacks
) : LinearLayout(context) {

    interface Callbacks {
        fun onPro()
        fun onSettings()
        fun onBackup()
        fun onTrash()
        fun onAbout()
    }

    val list: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, SwStyle.dp(context, 20f))

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = SwStyle.dp(context, 18f)
            setPadding(pad, SwStyle.dp(context, 10f), pad, SwStyle.dp(context, 6f))
        }
        bar.addView(
            SwStyle.text(context, 24f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.me_title)
                typeface = com.scanwhirl.app.ui.kit.SwType.extraBold(context)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(TrustPill.offlineNoAds(context))
        addView(bar)

        list = LinearLayout(context).apply {
            id = R.id.swMeList
            orientation = VERTICAL
            setPadding(SwStyle.dp(context, 14f), SwStyle.dp(context, 8f), SwStyle.dp(context, 14f), 0)
        }
        addView(list)
        buildRows()
    }

    /** Rebuilds the rows: the Pro row's wording follows a purchase or a test flip. */
    fun refreshProState() {
        buildRows()
    }

    private fun buildRows() {
        list.removeAllViews()
        list.addView(
            row(
                R.drawable.ic_sym_star_filled,
                context.getString(
                    if (com.scanwhirl.app.util.ProGate.isPro(context)) R.string.me_pro_active
                    else R.string.me_pro
                ),
                context.getString(
                    if (com.scanwhirl.app.util.ProGate.isPro(context)) R.string.me_pro_active_summary
                    else R.string.me_pro_summary
                ),
                R.id.swMePro,
                gold = true
            ) { callbacks.onPro() }
        )
        // L2's single Backup & restore row (ui/backup/BackupActivity), directly
        // after Pro. Do not add another.
        list.addView(
            row(
                R.drawable.ic_sym_settings,
                context.getString(R.string.me_settings),
                context.getString(R.string.me_settings_summary),
                R.id.swMeSettings
            ) { callbacks.onSettings() }
        )
        list.addView(
            row(
                R.drawable.ic_sym_archive,
                context.getString(R.string.me_backup),
                context.getString(R.string.me_backup_summary),
                R.id.swMeBackup
            ) { callbacks.onBackup() }
        )
        list.addView(
            row(
                R.drawable.ic_sym_delete,
                context.getString(R.string.me_trash),
                context.getString(R.string.me_trash_summary),
                R.id.swMeTrash
            ) { callbacks.onTrash() }
        )
        list.addView(
            row(
                R.drawable.ic_sym_info,
                context.getString(R.string.me_about),
                context.getString(R.string.me_about_summary),
                R.id.swMeAbout
            ) { callbacks.onAbout() }
        )
    }

    private fun row(
        icon: Int,
        title: String,
        summary: String,
        id: Int,
        gold: Boolean = false,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(context).apply {
            this.id = id
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.card(context, 18f)
            isClickable = true
            isFocusable = true
            // M7 (device sweep 2026-11-19): the visible label lives on child TextViews, so the
            // clickable node TalkBack focused had no name. The row now carries its own words.
            contentDescription = if (summary.isBlank()) title else "$title, $summary"
            val pad = SwStyle.dp(context, 14f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = SwStyle.dp(context, 10f)
            }
            setOnClickListener { onClick() }
        }
        val tint = if (gold) R.color.gold_pro_primary else R.color.cyan_primary
        card.addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, tint))
            },
            LayoutParams(SwStyle.dp(context, 26f), SwStyle.dp(context, 26f))
                .apply { marginEnd = SwStyle.dp(context, 14f) }
        )
        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(
            SwStyle.text(
                context, 15.5f,
                SwStyle.color(context, if (gold) R.color.gold_pro_primary else R.color.text_hologram_hi),
                bold = true
            ).apply { text = title }
        )
        info.addView(
            SwStyle.text(context, 12.5f, SwStyle.color(context, R.color.text_hologram_dim)).apply {
                text = summary
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 3f) }
        )
        card.addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        card.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_chevron_right)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.text_hologram_dim))
            },
            LayoutParams(SwStyle.dp(context, 22f), SwStyle.dp(context, 22f))
        )
        return card
    }
}
