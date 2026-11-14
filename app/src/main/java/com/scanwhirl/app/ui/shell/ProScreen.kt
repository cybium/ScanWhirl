package com.scanwhirl.app.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: 06_pro — gold hero, the promise, eight feature cells, ₹100 one time.
 * The buy button opens a "Coming soon" sheet until billing lands; it is never
 * a dead end.
 */
class ProScreen(
    private val context: Context,
    private val callbacks: Callbacks
) : LinearLayout(context) {

    interface Callbacks {
        fun onClose()
        fun onBuy()
        fun onRestore()
        fun onSeeAllProTools()
    }

    val buyButton: TextView

    init {
        orientation = VERTICAL
        id = R.id.swProScreen
        background = SwStyle.gradient(
            context, 0x24FFB800, SwStyle.color(context, R.color.rv_bg), radiusDp = 0f
        )

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.START
            setPadding(SwStyle.dp(context, 12f), SwStyle.dp(context, 6f), 0, 0)
        }
        top.addView(
            SwStyle.iconButton(context, R.drawable.ic_sym_close, SwStyle.color(context, R.color.text_hologram_mid)).apply {
                id = R.id.swProClose
                contentDescription = context.getString(R.string.sheet_close)
                setOnClickListener { callbacks.onClose() }
            }
        )
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val hero = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 170f))
        }
        for (size in listOf(250, 180, 120)) {
            val ring = SwStyle.dp(context, size.toFloat())
            hero.addView(
                View(context).apply {
                    background = SwStyle.rounded(
                        context, 0x00FFB800, radiusDp = 999f,
                        stroke = 0x29FFB800, strokeDp = 1f
                    )
                },
                FrameLayout.LayoutParams(ring, ring).apply { gravity = Gravity.CENTER }
            )
        }
        hero.addView(
            ImageView(context).apply { setImageResource(R.drawable.ic_whirl_gold) },
            FrameLayout.LayoutParams(SwStyle.dp(context, 72f), SwStyle.dp(context, 72f))
                .apply { gravity = Gravity.CENTER }
        )
        addView(hero)

        val title = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        title.addView(
            SwStyle.text(context, 28f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.pro_title_prefix)
            }
        )
        title.addView(
            SwStyle.text(context, 28f, SwStyle.color(context, R.color.gold_pro_primary), bold = true).apply {
                text = context.getString(R.string.pro_title_pro)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
        )
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
                text = context.getString(R.string.pro_sub)
                gravity = Gravity.CENTER
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 6f) }
        )

        val trustRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (pill in listOf(
            TrustPill.noAds(context),
            TrustPill.noAccount(context),
            TrustPill.offline(context)
        )) {
            trustRow.addView(
                pill,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = SwStyle.dp(context, 4f); marginEnd = SwStyle.dp(context, 4f) }
            )
        }
        addView(
            trustRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 14f) }
        )

        val feat = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(SwStyle.dp(context, 18f), SwStyle.dp(context, 16f), SwStyle.dp(context, 18f), 0)
        }
        val features = listOf(
            R.drawable.ic_sym_signature to R.string.pro_feat_sign,
            R.drawable.ic_sym_description to R.string.pro_feat_word,
            R.drawable.ic_sym_call_split to R.string.pro_feat_split,
            R.drawable.ic_sym_watermark to R.string.pro_feat_watermark,
            R.drawable.ic_sym_ink_eraser to R.string.pro_feat_redact,
            R.drawable.ic_sym_menu_book to R.string.pro_feat_scan,
            R.drawable.ic_sym_check_circle to R.string.pro_feat_batch,
            R.drawable.ic_sym_create_new_folder to R.string.pro_feat_folders
        )
        for (pair in features.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((icon, label) in pair) {
                row.addView(
                    feature(icon, label),
                    LayoutParams(0, SwStyle.dp(context, 48f), 1f)
                        .apply { marginEnd = SwStyle.dp(context, 10f) }
                )
            }
            feat.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = SwStyle.dp(context, 10f) }
            )
        }
        addView(feat)

        addView(
            SwStyle.text(context, 13f, SwStyle.color(context, R.color.cyan_primary), bold = true).apply {
                text = context.getString(R.string.pro_see_all)
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { callbacks.onSeeAllProTools() }
                minimumHeight = SwStyle.dp(context, 36f)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 2f) }
        )

        val price = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.gradient(
                context, 0x1FFFB800, 0x05FFB800, radiusDp = 20f, stroke = SwStyle.color(context, R.color.gold_pro_primary)
            )
            setPadding(
                SwStyle.dp(context, 16f), SwStyle.dp(context, 14f),
                SwStyle.dp(context, 16f), SwStyle.dp(context, 14f)
            )
        }
        val priceText = LinearLayout(context).apply { orientation = VERTICAL }
        val priceRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        priceRow.addView(
            SwStyle.text(context, 30f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                id = R.id.swProPrice
                text = context.getString(R.string.pro_price)
            }
        )
        priceRow.addView(
            SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
                text = context.getString(R.string.pro_one_time)
                setPadding(SwStyle.dp(context, 6f), 0, 0, SwStyle.dp(context, 3f))
            }
        )
        priceText.addView(priceRow)
        priceText.addView(
            SwStyle.text(context, 12.5f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
                text = context.getString(R.string.pro_price_note)
            }
        )
        price.addView(priceText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        price.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_workspace_premium)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
            },
            LayoutParams(SwStyle.dp(context, 34f), SwStyle.dp(context, 34f))
        )
        addView(
            price,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 10f), m, 0)
            }
        )

        buyButton = SwStyle.text(context, 16f, SwStyle.color(context, R.color.sw_on_gold), bold = true).apply {
            id = R.id.swProBuy
            text = context.getString(R.string.pro_unlock)
            gravity = Gravity.CENTER
            background = SwStyle.gradient(
                context,
                SwStyle.color(context, R.color.sw_gold_light),
                SwStyle.color(context, R.color.gold_pro_primary),
                radiusDp = 16f
            )
            isClickable = true
            setOnClickListener { callbacks.onBuy() }
        }
        addView(
            buyButton,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 54f)).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 16f), m, 0)
            }
        )

        addView(
            SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
                text = context.getString(R.string.pro_restore)
                gravity = Gravity.CENTER
                isClickable = true
                minimumHeight = SwStyle.dp(context, 44f)
                setOnClickListener { callbacks.onRestore() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 44f))
        )
        addView(SwStyle.spacer(context, 20f))
    }

    private fun feature(icon: Int, label: Int): View {
        val cell = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.rounded(
                context,
                0x2AFFB800,
                radiusDp = 16f,
                stroke = 0x2EFFB800
            )
            val pad = SwStyle.dp(context, 12f)
            setPadding(pad, 0, pad, 0)
        }
        cell.addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
            },
            LayoutParams(SwStyle.dp(context, 22f), SwStyle.dp(context, 22f))
                .apply { marginEnd = SwStyle.dp(context, 10f) }
        )
        cell.addView(
            SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(label)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        return cell
    }
}
