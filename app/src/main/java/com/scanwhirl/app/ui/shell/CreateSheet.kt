package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwMotion
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.util.ProGate

/**
 * UI-B1-LIBRARY-CREATE (UI_CRITIQUE_GPT_2026-11-19 #2): the one "start something" sheet behind
 * the centre Scan button.
 *
 * It offers the same six routes the old Home row did, through the exact launchers the shell
 * already owns (camera scan, ID card, Passport, QR code, photos, PDF). Nothing new is launched
 * here: [Actions] is wired in the shell to the existing code paths, so the Pro gate on QR code
 * stays the registry's one `launchTool` gate.
 *
 * Layout follows the v4 concept intent, not its fixed CSS: a filled primary for the camera, then
 * three job tiles (ID card, Passport, QR code) and two import tiles at normal text. At 200 % font
 * (fontScale >= 1.6) every entry becomes a full width 48dp row, because a tile cannot hold a
 * wrapped label without clipping (UI_PLAN 0.8: unclipped text beats density).
 */
class CreateSheet(
    private val context: Context,
    private val actions: Actions
) {

    /** Everything the sheet can start. Implemented by the shell with its existing launchers. */
    interface Actions {
        fun onScan()
        fun onIdCard()
        fun onPassport()
        fun onQr()
        fun onImportPhotos()
        fun onImportPdf()
    }

    private data class Entry(
        val id: Int,
        val icon: Int,
        val label: Int,
        val pro: Boolean = false,
        val action: () -> Unit
    )

    private val entries = listOf(
        Entry(R.id.swCreateIdCard, R.drawable.ic_sym_badge, R.string.tool_id_card) {
            actions.onIdCard()
        },
        Entry(R.id.swCreatePassport, R.drawable.ic_sym_travel_explore, R.string.tool_passport) {
            actions.onPassport()
        },
        Entry(R.id.swCreateQr, R.drawable.ic_sym_qr_code_scanner, R.string.tool_qr_code, pro = true) {
            actions.onQr()
        },
        Entry(R.id.swCreatePhotos, R.drawable.ic_sym_photo_library, R.string.create_import_photos) {
            actions.onImportPhotos()
        },
        Entry(R.id.swCreateImport, R.drawable.ic_sym_upload_file, R.string.home_create_import) {
            actions.onImportPdf()
        }
    )

    private var dialog: SwSheet? = null
    private var chosen = false

    /**
     * Builds and shows the sheet. [onDismiss] runs on Close, Back or an outside tap with
     * `chosen = true` when one of the routes was taken (so no focus is pulled back to Scan
     * while another screen opens).
     */
    fun show(onDismiss: (chosen: Boolean) -> Unit = {}): SwSheet {
        chosen = false
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.tools_group_create)
            .setContent(content())
            .build()
        sheet.setOnDismissListener { onDismiss(chosen) }
        dialog = sheet
        sheet.show()
        return sheet
    }

    private fun content(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // The v4 order: the filled camera action sits first under the title, then the job
        // tiles. The kit's own bottom primary stays unused; one filled action per sheet.
        column.addView(
            scanButton(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(context, 12f) }
        )
        if (context.resources.configuration.fontScale >= 1.6f) {
            entries.forEachIndexed { index, entry -> column.addView(row(entry, index)) }
        } else {
            column.addView(tileRow(entries.take(3)))
            column.addView(
                wideRow(entries.drop(3)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(context, 10f) }
            )
        }
        // ATLAS-F2 (005): at 200 % the entries made the sheet taller than the screen and the
        // last row ("Import PDF") was cut at the bottom edge. The entries scroll inside a
        // bounded area, so the whole sheet fits and every row — the last one included — can be
        // brought fully into view by swiping.
        return BoundedScroll(context).apply {
            val screen = context.resources.displayMetrics.heightPixels
            maxHeight = (screen * MAX_HEIGHT_FRACTION).toInt()
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(0, 0, 0, SwStyle.dp(context, 6f))
            addView(
                column,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /**
     * A vertical scroll view that is as tall as its content, never taller than [maxHeight].
     * The sheet title and buttons stay outside it, so the dialog itself never grows past the
     * screen at large system fonts.
     */
    private class BoundedScroll(context: Context) :
        android.widget.ScrollView(context) {

        var maxHeight: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (maxHeight > 0 && measuredHeight > maxHeight) {
                super.onMeasure(
                    widthMeasureSpec,
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        maxHeight, android.view.View.MeasureSpec.AT_MOST
                    )
                )
            }
        }
    }

    /** The one filled action: the camera scan, styled like the kit's primary. */
    private fun scanButton(): TextView =
        SwStyle.text(context, 16f, SwStyle.color(context, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.swCreateScan
            text = context.getString(R.string.create_scan)
            gravity = Gravity.CENTER
            typeface = SwType.extraBold(context)
            minHeight = SwStyle.dp(context, 54f)
            maxLines = 2
            setPadding(
                SwStyle.dp(context, 12f), SwStyle.dp(context, 8f),
                SwStyle.dp(context, 12f), SwStyle.dp(context, 8f)
            )
            isClickable = true
            isFocusable = true
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
            )
            setOnClickListener {
                chosen = true
                dialog?.dismiss()
                dialog = null
                actions.onScan()
            }
            SwMotion.press(this)
        }

    // ---- normal text: tiles ------------------------------------------------

    private fun tileRow(items: List<Entry>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(context, 12f) }
        }
        items.forEachIndexed { index, entry ->
            val frame = FrameLayout(context).apply {
                id = entry.id
                background = SwStyle.card(context, 16f)
                isClickable = true
                isFocusable = true
                contentDescription = describe(entry)
                setOnClickListener { choose(entry) }
                SwMotion.press(this)
            }
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            tile.addView(
                icon(entry, 28f),
                LinearLayout.LayoutParams(SwStyle.dp(context, 28f), SwStyle.dp(context, 28f))
            )
            tile.addView(
                label(entry, 13.5f, Gravity.CENTER),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(context, 8f) }
            )
            frame.addView(
                tile,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    leftMargin = SwStyle.dp(context, 6f)
                    rightMargin = SwStyle.dp(context, 6f)
                    topMargin = SwStyle.dp(context, 10f)
                    bottomMargin = SwStyle.dp(context, 10f)
                }
            )
            if (entry.pro && !ProGate.isPro(context)) {
                frame.addView(
                    proBadge(entry.id),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        // Over the tile's top end, never over the glyph or the label.
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = SwStyle.dp(context, 4f)
                        marginEnd = SwStyle.dp(context, 4f)
                    }
                )
            }
            row.addView(
                frame,
                LinearLayout.LayoutParams(0, SwStyle.dp(context, 92f), 1f).apply {
                    if (index > 0) marginStart = SwStyle.dp(context, 10f)
                }
            )
        }
        return row
    }

    private fun wideRow(items: List<Entry>): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { index, entry ->
            val tile = entryRow(entry, iconSize = 26f, labelSp = 13.5f)
            row.addView(
                tile,
                LinearLayout.LayoutParams(0, SwStyle.dp(context, 56f), 1f).apply {
                    if (index > 0) marginStart = SwStyle.dp(context, 10f)
                }
            )
        }
        return row
    }

    // ---- large text: full width rows --------------------------------------

    private fun row(entry: Entry, index: Int): View {
        val view = entryRow(entry, iconSize = 24f, labelSp = 15f)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (index > 0) params.topMargin = SwStyle.dp(context, 6f)
        view.layoutParams = params
        return view
    }

    private fun entryRow(entry: Entry, iconSize: Float, labelSp: Float): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = entry.id
            background = SwStyle.card(context, 14f)
            minimumHeight = SwStyle.dp(context, 48f)
            setPadding(
                SwStyle.dp(context, 12f), SwStyle.dp(context, 8f),
                SwStyle.dp(context, 12f), SwStyle.dp(context, 8f)
            )
            isClickable = true
            isFocusable = true
            contentDescription = describe(entry)
            setOnClickListener { choose(entry) }
            SwMotion.press(this)
        }
        row.addView(
            icon(entry, iconSize),
            LinearLayout.LayoutParams(
                SwStyle.dp(context, iconSize), SwStyle.dp(context, iconSize)
            ).apply { marginEnd = SwStyle.dp(context, 12f) }
        )
        row.addView(
            label(entry, labelSp, Gravity.START),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (entry.pro && !ProGate.isPro(context)) {
            row.addView(
                proBadge(entry.id),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = SwStyle.dp(context, 8f) }
            )
        }
        return row
    }

    // ---- pieces -----------------------------------------------------------

    private fun icon(entry: Entry, sizeDp: Float): ImageView = ImageView(context).apply {
        setImageResource(entry.icon)
        imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = SwStyle.dp(context, sizeDp)
        layoutParams = FrameLayout.LayoutParams(size, size)
    }

    private fun label(entry: Entry, sizeSp: Float, gravity: Int): TextView =
        SwStyle.text(context, sizeSp, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            text = context.getString(entry.label)
            this.gravity = gravity
            maxLines = 2
            // A label wraps between words, never inside one (Devanagari matras stay whole).
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** Gold + the word "Pro" (UI_PLAN 0.11: never colour alone). Part of the row's label. */
    private fun proBadge(entryId: Int): TextView =
        SwStyle.text(context, 11.5f, SwStyle.color(context, R.color.sw_on_gold), bold = true).apply {
            id = R.id.swCreateQrPro
            text = context.getString(R.string.pro_title_pro)
            typeface = SwType.extraBold(context)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(
                SwStyle.dp(context, 6f), SwStyle.dp(context, 2f),
                SwStyle.dp(context, 6f), SwStyle.dp(context, 2f)
            )
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.gold_pro_primary), radiusDp = 6f
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = entryId
        }

    /** One focus stop per entry: its label plus "Pro" when it is a Pro tool. */
    private fun describe(entry: Entry): String {
        val name = context.getString(entry.label)
        return if (entry.pro && !ProGate.isPro(context)) {
            "$name, " + context.getString(R.string.pro_title_pro)
        } else {
            name
        }
    }

    private fun choose(entry: Entry) {
        chosen = true
        dialog?.dismiss()
        dialog = null
        entry.action()
    }

    private companion object {
        /** The entries never take more than this share of the screen; title/buttons sit outside. */
        const val MAX_HEIGHT_FRACTION = 0.58f
    }
}
