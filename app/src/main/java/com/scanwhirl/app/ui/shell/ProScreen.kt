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
import com.scanwhirl.app.ui.kit.SwType
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

    private val rings = mutableListOf<View>()
    private lateinit var mark: ImageView
    private lateinit var titleRow: View
    private lateinit var subtitle: TextView
    private lateinit var trustRow: View
    private val featureCells = mutableListOf<View>()
    private lateinit var priceCard: View
    private lateinit var priceWrap: FrameLayout
    private lateinit var buyWrap: FrameLayout
    private var entrancePlayed = false

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
            val ringView = View(context).apply {
                background = SwStyle.rounded(
                    context, 0x00FFB800, radiusDp = 999f,
                    stroke = 0x29FFB800, strokeDp = 1f
                )
            }
            rings += ringView
            hero.addView(
                ringView,
                FrameLayout.LayoutParams(ring, ring).apply { gravity = Gravity.CENTER }
            )
        }
        mark = ImageView(context).apply { setImageResource(R.drawable.ic_brand_mark_gold) }
        hero.addView(
            mark,
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
                typeface = SwType.extraBold(context)
            }
        )
        title.addView(
            SwStyle.text(context, 28f, SwStyle.color(context, R.color.gold_pro_primary), bold = true).apply {
                text = context.getString(R.string.pro_title_pro)
                typeface = SwType.extraBold(context)
            }
        )
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        titleRow = title
        subtitle = SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
            text = context.getString(R.string.pro_sub)
            gravity = Gravity.CENTER
        }
        addView(
            subtitle,
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
        this.trustRow = trustRow

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
                val cell = feature(icon, label)
                featureCells += cell
                row.addView(
                    cell,
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
        priceCard = price
        priceWrap = FrameLayout(context).apply {
            addView(
                price,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(
            priceWrap,
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
        buyWrap = FrameLayout(context).apply {
            addView(
                buyButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        addView(
            buyWrap,
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

        // v2 UI: 74_motion — the Pro entrance. Every animator is finite and
        // attach-scoped: leaving the screen cancels them, and "Remove animations"
        // leaves the final state in place with no movement.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!entrancePlayed) {
                    entrancePlayed = true
                    playEntrance()
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                rings.forEach { it.animate().cancel() }
                mark.animate().cancel()
                titleRow.animate().cancel()
                subtitle.animate().cancel()
                trustRow.animate().cancel()
                featureCells.forEach { it.animate().cancel() }
                priceCard.animate().cancel()
                buyButton.animate().cancel()
            }
        })
    }

    private fun playEntrance() {
        if (!com.scanwhirl.app.ui.kit.SwMotion.enabled(context)) return
        val lift = SwStyle.dp(context, 12f)
        rings.forEachIndexed { index, ring ->
            ring.alpha = 0f
            ring.scaleX = 0.72f
            ring.scaleY = 0.72f
            ring.animate()
                .alpha(0.7f).scaleX(1f).scaleY(1f)
                .setStartDelay(index * 80L)
                .setDuration(600L)
                .withEndAction {
                    ring.animate().alpha(0.32f).setDuration(260L).start()
                }
                .start()
        }
        mark.rotation = 0f
        mark.animate().rotation(360f).setDuration(600L).start()

        titleRow.alpha = 0f
        titleRow.translationY = lift.toFloat()
        titleRow.animate().alpha(1f).translationY(0f)
            .setStartDelay(120L).setDuration(320L).start()
        subtitle.alpha = 0f
        subtitle.translationY = lift * 0.6f
        subtitle.animate().alpha(1f).translationY(0f)
            .setStartDelay(180L).setDuration(320L).start()

        val cascade = mutableListOf(trustRow).apply { addAll(featureCells) }
        cascade.forEachIndexed { index, cell ->
            cell.alpha = 0f
            cell.translationY = (lift * 0.7f)
            cell.animate().alpha(1f).translationY(0f)
                .setStartDelay(200L + index * 30L)
                .setDuration(220L)
                .start()
        }

        priceWrap.post { shine(priceWrap) }
        buyWrap.post { shine(buyWrap) }
        buyButton.postDelayed({
            if (!buyButton.isAttachedToWindow) return@postDelayed
            buyButton.animate().scaleX(1.03f).scaleY(1.03f)
                .setDuration(120L)
                .withEndAction {
                    buyButton.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
                .start()
        }, 900L)
    }

    /** One soft gold sweep across [wrap]; the shine view dies with its parent. */
    private fun shine(wrap: FrameLayout) {
        if (wrap.width <= 0 || !wrap.isAttachedToWindow) return
        val width = SwStyle.dp(context, 90f)
        val shine = View(context).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00FFB800, 0x66FFD257.toInt(), 0x00FFB800)
            )
            alpha = 0f
            translationX = -width.toFloat()
        }
        wrap.addView(
            shine,
            FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        shine.animate()
            .translationX(wrap.width.toFloat())
            .alpha(0.8f)
            .setDuration(600L)
            .withEndAction { (shine.parent as? ViewGroup)?.removeView(shine) }
            .start()
    }

    // Test seams for the entrance (no behavioural change).
    internal fun entrancePlayedForTest(): Boolean = entrancePlayed
    internal fun titleForTest(): View = titleRow
    internal fun priceWrapForTest(): FrameLayout = priceWrap
    internal fun buyWrapForTest(): FrameLayout = buyWrap

    private fun feature(icon: Int, label: Int): View {        val cell = LinearLayout(context).apply {
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
