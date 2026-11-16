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
import androidx.core.view.doOnPreDraw
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: 06_pro — gold hero, the promise, eight feature cells, ₹100 one time.
 * The buy button opens a "Coming soon" sheet until billing lands; it is never
 * a dead end.
 */
class ProScreen(
    private val context: Context,
    private val callbacks: Callbacks,
    private val active: Boolean = false
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

    /** Test seam: the order in which the entrance phases actually ran. */
    internal val entranceSteps = mutableListOf<String>()

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
                layoutParams = LayoutParams(SwStyle.dp(context, 48f), SwStyle.dp(context, 48f))
                setPadding(SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f))
                setOnClickListener { callbacks.onClose() }
            }
        )
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val hero = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 150f))
            clipChildren = false
        }
        for (size in listOf(150, 110, 70)) {
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
        mark = ImageView(context).apply {
            setImageResource(R.drawable.ic_brand_mark_gold)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        hero.addView(
            mark,
            FrameLayout.LayoutParams(SwStyle.dp(context, 56f), SwStyle.dp(context, 56f))
                .apply { gravity = Gravity.CENTER }
        )
        addView(hero)

        val title = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        title.addView(
            SwStyle.text(context, 24f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(R.string.pro_title_prefix)
                typeface = SwType.extraBold(context)
            }
        )
        title.addView(
            SwStyle.text(context, 24f, SwStyle.color(context, R.color.gold_pro_primary), bold = true).apply {
                text = context.getString(R.string.pro_title_pro)
                typeface = SwType.extraBold(context)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = SwStyle.dp(context, 7f) }
            }
        )
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        titleRow = title
        subtitle = SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
            text = context.getString(if (active) R.string.pro_active_sub else R.string.pro_sub_compact)
            gravity = Gravity.CENTER
            maxLines = 2
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
        val pills = listOf(
            TrustPill.noAds(context),
            TrustPill.noAccount(context),
            TrustPill.offline(context)
        )
        for (pill in pills) {
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
            R.drawable.ic_sym_qr_code_scanner to R.string.pro_feat_scan,
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
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
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
            SwStyle.text(context, 14f, SwStyle.color(context, R.color.gold_pro_primary), bold = true).apply {
                id = R.id.swProMore
                text = context.getString(R.string.pro_more_tools, ToolRegistry.proCount())
                gravity = Gravity.CENTER
                isClickable = true
                background = SwStyle.rounded(
                    context,
                    SwStyle.color(context, R.color.gold_tile_fill),
                    radiusDp = 16f,
                    stroke = SwStyle.color(context, R.color.gold_tile_border)
                )
                minimumHeight = SwStyle.dp(context, 48f)
                setOnClickListener { callbacks.onSeeAllProTools() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f)).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 8f), m, 0)
            }
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
                text = priceLabel()
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
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        if (!active) addView(
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
            setOnClickListener { onUnlockPro() }
        }
        buyWrap = FrameLayout(context).apply {
            addView(
                buyButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        if (!active) addView(
            buyWrap,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 54f)).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 16f), m, 0)
            }
        )

        if (!active) addView(
            SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
                text = context.getString(R.string.pro_restore)
                gravity = Gravity.CENTER
                isClickable = true
                minimumHeight = SwStyle.dp(context, 48f)
                setOnClickListener { onRestorePurchase() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f))
        )
        addView(SwStyle.spacer(context, 20f))

        // Every animated part starts in its start state before the first frame.
        // the pinned bar is left alone so it is at the bottom from frame 1.
        val lift = SwStyle.dp(context, 12f).toFloat()
        titleRow.alpha = 0f
        titleRow.translationY = lift
        subtitle.alpha = 0f
        subtitle.translationY = lift * 0.6f
        trustRow.alpha = 0f
        trustRow.translationY = lift * 0.7f
        featureCells.forEach {
            it.alpha = 0f
            it.translationY = lift * 0.7f
        }
        rings.forEach {
            it.alpha = 0f
            it.scaleX = 0.72f
            it.scaleY = 0.72f
        }
        mark.rotation = 0f

        // v2 UI: 74_motion — run the entrance only after the first layout pass.
        // Starting on attach drew an empty screen with the bar at the top.
        doOnPreDraw {
            if (!entrancePlayed) {
                entrancePlayed = true
                playEntrance()
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

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

    /** Billing seams: L2 wires Play Billing here without touching the layout. */
    private fun onUnlockPro() = callbacks.onBuy()

    private fun onRestorePurchase() = callbacks.onRestore()

    /** Price copy: L2 owns the text. */
    private fun priceLabel(): CharSequence = context.getString(R.string.pro_price)

    private fun playEntrance() {
        if (!com.scanwhirl.app.ui.kit.SwMotion.enabled(context)) {
            // Final state immediately: nothing moves for "Remove animations".
            rings.forEach { it.alpha = 0.32f; it.scaleX = 1f; it.scaleY = 1f }
            titleRow.alpha = 1f; titleRow.translationY = 0f
            subtitle.alpha = 1f; subtitle.translationY = 0f
            trustRow.alpha = 1f; trustRow.translationY = 0f
            featureCells.forEach { it.alpha = 1f; it.translationY = 0f }
            return
        }
        val lift = SwStyle.dp(context, 12f)
        entranceSteps += "rings"
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
        entranceSteps += "title"
        titleRow.animate().alpha(1f).translationY(0f)
            .setStartDelay(120L).setDuration(320L).start()
        subtitle.alpha = 0f
        subtitle.translationY = lift * 0.6f
        subtitle.animate().alpha(1f).translationY(0f)
            .setStartDelay(180L).setDuration(320L).start()

        val cascade = mutableListOf(trustRow).apply { addAll(featureCells) }
        entranceSteps += "cascade"
        cascade.forEachIndexed { index, cell ->
            cell.alpha = 0f
            cell.translationY = (lift * 0.7f)
            cell.animate().alpha(1f).translationY(0f)
                .setStartDelay(200L + index * 30L)
                .setDuration(220L)
                .start()
        }
    }

    // Test seams (no behavioural change).
    internal fun entrancePlayedForTest(): Boolean = entrancePlayed
    internal fun titleForTest(): View = titleRow
    internal fun priceWrapForTest(): FrameLayout = priceWrap
    internal fun buyWrapForTest(): FrameLayout = buyWrap
    /**
     * Billing: show Play's localized price when the store answers. Only the price text changes;
     * the rest of the layout is untouched.
     */
    fun setPrice(text: String) {
        findViewById<android.widget.TextView>(R.id.swProPrice)?.text = text
    }

    /** Pinned price + Unlock row (never scrolls out of view). */
    private fun stickyBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(0xFF0E1316.toInt())
        }
        bar.addView(
            View(context).apply {
                setBackgroundColor(SwStyle.color(context, R.color.sw_cyan_hair))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 1)
        )
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(
                SwStyle.dp(context, 18f), SwStyle.dp(context, 8f),
                SwStyle.dp(context, 18f), SwStyle.dp(context, 8f)
            )
        }
        row.addView(priceWrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The shine is clipped to the button's rounded bounds, never the screen.
        buyWrap.clipChildren = true
        buyWrap.clipToOutline = true
        buyWrap.background = SwStyle.rounded(
            context, android.graphics.Color.TRANSPARENT, radiusDp = 16f
        )
        row.addView(
            buyWrap,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 54f))
                .apply { topMargin = SwStyle.dp(context, 8f) }
        )
        bar.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return bar
    }
    internal fun featureCellsForTest(): List<View> = featureCells


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
            setPadding(pad, SwStyle.dp(context, 6f), pad, SwStyle.dp(context, 6f))
            minimumHeight = SwStyle.dp(context, 48f)
        }
        cell.addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(SwStyle.dp(context, 22f), SwStyle.dp(context, 22f))
                .apply { marginEnd = SwStyle.dp(context, 10f) }
        )
        cell.addView(
            SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                text = context.getString(label)
                maxLines = 2
                setLineSpacing(0f, 1.1f)
                val density = resources.displayMetrics.density
                val scaled = resources.displayMetrics.scaledDensity
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    minOf(13.5f * scaled, 16f * density)
                )
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        return cell
    }
}
