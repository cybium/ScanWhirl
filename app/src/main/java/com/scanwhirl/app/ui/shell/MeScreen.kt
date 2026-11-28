package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.TrustPill
import com.scanwhirl.app.util.Prefs

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

    /** Large system font: the decorations give the text their width and height. */
    private val largeFont = context.resources.configuration.fontScale >= 1.6f

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
            SwStyle.text(context, SwType.TITLE_SP, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
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
            // ATLAS-F2 (004/320dp): at large fonts the copying text needs the width; the
            // card inset gives 4dp of it back on each side.
            val inset = SwStyle.dp(context, if (largeFont) 10f else 14f)
            setPadding(inset, SwStyle.dp(context, 8f), inset, 0)
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
        // Saving: one switch, shared with Settings (the same preference, not a second one).
        list.addView(
            toggleRow(
                // V003 (W5a-2 wiring): the kit's dedicated save-to-Documents glyph.
                R.drawable.ic_sw_save_documents,
                context.getString(R.string.me_also_save_to_documents),
                context.getString(R.string.me_also_save_to_documents_summary),
                R.id.swMeAlsoSave,
                R.id.swMeAlsoSaveSwitch,
                Prefs.autoCopyToDocuments(context)
            ) { checked -> Prefs.setAutoCopyToDocuments(context, checked) }
        )
        // W5d V097: Theme is not duplicated here. One appearance home owns it —
        // Settings > Appearance (Theme, Font, Text size) — so Me stays a short hub.
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
                // V101 (W5a-2 wiring): the kit backup glyph, distinct from ZIP/PDF-A.
                R.drawable.ic_sw_backup,
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

    /** A Me row whose only control is a switch. The card itself is the full 48dp target. */
    private fun toggleRow(
        icon: Int,
        title: String,
        summary: String,
        id: Int,
        switchId: Int,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val card = RowCard(context).apply {
            this.id = id
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.card(context, SwStyle.RADIUS_CARD_DP)
            minimumHeight = SwStyle.dp(context, SwStyle.TOUCH_MIN_DP)
            val pad = SwStyle.dp(context, if (largeFont) 8f else 14f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = SwStyle.dp(context, 10f)
            }
            contentDescription = "$title, $summary"
        }
        val iconDp = if (largeFont) 18f else 26f
        val iconGapDp = if (largeFont) 8f else 14f
        card.addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
            },
            LayoutParams(SwStyle.dp(context, iconDp), SwStyle.dp(context, iconDp))
                .apply { marginEnd = SwStyle.dp(context, iconGapDp) }
        )
        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(
            SwStyle.text(context, SwType.BODY_SP,
                SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply { text = title }
        )
        info.addView(
            SwStyle.text(context,
                // 2.0x on 320dp needs a slightly tighter summary to wrap inside the
                // card instead of overhanging its 1px border (MeRowFit).
                if (largeFont) SwType.CAPTION_SP - 0.5f else SwType.CAPTION_SP,
                SwStyle.color(context, R.color.text_hologram_dim)).apply { text = summary },
            // WRAP_CONTENT sized one long line past the card at 2.0x; constrain to the
            // info column so it wraps instead of overflowing (MeRowFit).
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 3f) }
        )
        card.addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val toggle = SwitchCompat(context).apply {
            this.id = switchId
            isChecked = checked
            contentDescription = title
            isFocusable = true
            minimumWidth = SwStyle.dp(context, SwStyle.TOUCH_MIN_DP)
            minimumHeight = SwStyle.dp(context, SwStyle.TOUCH_MIN_DP)
        }
        // A fixed 48dp box clipped the switch's track and thumb (half-circle at
        // the left edge). Let it measure its natural width; 48dp stays the touch
        // floor, and the row's end padding then contains the whole control.
        card.addView(
            toggle,
            LayoutParams(LayoutParams.WRAP_CONTENT, SwStyle.dp(context, SwStyle.TOUCH_MIN_DP))
        )
        toggle.setOnCheckedChangeListener { _, value -> onToggle(value) }
        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        return card
    }

    private fun row(
        icon: Int,
        title: String,
        summary: String,
        id: Int,
        gold: Boolean = false,
        summaryId: Int = View.NO_ID,
        onClick: () -> Unit
    ): View {
        val card = RowCard(context).apply {
            this.id = id
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.card(context, SwStyle.RADIUS_CARD_DP)
            // UI-W8: every Me row keeps a 48dp target even when the text is short.
            minimumHeight = SwStyle.dp(context, 48f)
            isClickable = true
            isFocusable = true
            // M7 (device sweep 2026-11-19): the visible label lives on child TextViews, so the
            // clickable node TalkBack focused had no name. The row now carries its own words.
            contentDescription = if (summary.isBlank()) title else "$title, $summary"
            val pad = SwStyle.dp(context, if (largeFont) 8f else 14f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = SwStyle.dp(context, 10f)
            }
            setOnClickListener { onClick() }
        }
        val tint = if (gold) R.color.gold_pro_primary else R.color.cyan_primary
        // The Latin brand name "ScanWhirl" is ~1 % wider in the Devanagari-capable font, so at
        // large fonts the decorations give the row's words their width back.
        val iconDp = if (largeFont) 18f else 26f
        val iconGapDp = if (largeFont) 8f else 14f
        val chevronDp = if (largeFont) 16f else 22f
        card.addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, tint))
            },
            LayoutParams(SwStyle.dp(context, iconDp), SwStyle.dp(context, iconDp))
                .apply { marginEnd = SwStyle.dp(context, iconGapDp) }
        )
        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(
            SwStyle.text(
                context, SwType.BODY_SP,
                SwStyle.color(context, if (gold) R.color.gold_pro_primary else R.color.text_hologram_hi),
                bold = true
            ).apply { text = title }
        )
        info.addView(
            SwStyle.text(context,
                // 2.0x on 320dp needs a slightly tighter summary to wrap inside the
                // card instead of overhanging its 1px border (MeRowFit).
                if (largeFont) SwType.CAPTION_SP - 0.5f else SwType.CAPTION_SP,
                SwStyle.color(context, R.color.text_hologram_dim)).apply {
                text = summary
                if (summaryId != View.NO_ID) setId(summaryId)
            },
            // WRAP_CONTENT sized one long line past the card at 2.0x; constrain to the
            // info column so it wraps instead of overflowing (MeRowFit).
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 3f) }
        )
        card.addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // The chevron is decoration. At large fonts it gives its 16dp back to the words (the
        // card is still clickable, named and focusable), so a long summary never runs past the
        // card edge.
        if (!largeFont) {
            card.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_sym_chevron_right)
                    // V061 (W5a-2 token): the chevron affordance, not decorative text ink.
                    imageTintList = ColorStateList.valueOf(
                        SwStyle.color(context, R.color.sf_outline)
                    )
                },
                LayoutParams(SwStyle.dp(context, chevronDp), SwStyle.dp(context, chevronDp))
            )
        }
        return card
    }

    /**
     * ATLAS-F2 (004): a Me row is a text card, so its height is never a design decision —
     * it is whatever the wrapped title + summary need. A plain wrap_content row still lets a
     * limited height spec crush the second summary line, which cut "30 days" at the card
     * border at 200 %. Measuring the row with an unbounded height makes the card grow instead;
     * the tab's ScrollView then scrolls. The 48dp floor still applies.
     */
    private class RowCard(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED
                )
            )
        }
    }
}
