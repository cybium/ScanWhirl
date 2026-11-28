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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwType
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.TrustPill

/**
 * v2 UI: 06_pro — gold hero, the promise, eight feature cells, Play's price once.
 * PRC-001: the amount is always Play's localized price. Until Play answers, the price line
 * says where the price comes from and shows no number (no hardcoded ₹ fallback).
 * The buy button opens Play Billing; it is never a dead end.
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

    /**
     * H3 (device sweep 2026-11-19): the hero, the promise and the tool grid scroll, while the
     * price and "Unlock Pro" stay pinned above the dock. On entry the offer is readable without a
     * swipe, instead of the price panel peeking out from behind the floating dock.
     */
    private val content = LinearLayout(context).apply { orientation = VERTICAL }

    private val rings = mutableListOf<View>()
    private lateinit var mark: ImageView
    private lateinit var titleRow: View
    private lateinit var subtitle: TextView
    private lateinit var trustRow: View
    private val featureCells = mutableListOf<View>()
    private lateinit var priceCard: View
    private lateinit var priceValue: TextView
    private lateinit var priceWrap: FrameLayout
    private lateinit var buyWrap: FrameLayout
    private lateinit var restoreRow: TextView
    private lateinit var continueFree: TextView
    private lateinit var priceStatus: TextView
    private lateinit var retryButton: TextView
    private lateinit var moreRow: TextView
    private var entrancePlayed = false

    /** True only when Play has actually returned a price. Get Pro is purchasable only then. */
    private var priceKnown = false
    private var buying = false

    /** How far the dock sits below the page bottom; the pinned bar stops above it. */
    private var dockOverlap: (() -> Int)? = null

    /**
     * B2-M4 (device sweep 2026-11-19): the decorative hero was a fixed 150dp, so at 200 % system
     * font the promise chips ("No ads / No account / Offline") were pushed under the pinned
     * price card and sliced in half. The hero is decoration: at large fonts it gives up height
     * so the title, the promise and the chips stay whole above the pinned offer. The words keep
     * their size — nothing that is read shrinks, only the rings and the mark.
     */
    private val largeFont = context.resources.configuration.fontScale >= 1.6f
    // PRO2 critic: the decorative ring hero ate too much of the first screen. Shrink the rings
    // (the real brand mark stays) so the honest tool list starts higher. At 200% it gives up more
    // height so the promise and the actions stay whole above the pinned offer (B2-M4).
    private val heroDp = if (largeFont) 56f else 96f

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
        content.addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // UX5-1: a missing price is an honest status, not a claim that payment is off. It and
        // the two free/restore actions live in the scrollable page, immediately visible at the
        // top, so the pinned offer stays the compact two-row price + purchase bar (no four-row
        // footer covering the promise chips at 130/200%).
        priceStatus = SwStyle.text(
            context, 16f, SwStyle.color(context, R.color.copydiet_pro2_primary), bold = true
        ).apply {
            id = R.id.swProPriceStatus
            text = context.getString(R.string.copydiet_pro2_price_unavailable)
            visibility = View.GONE
        }
        // Sits just after the trust chips so the missing-price state is immediately visible.

        val hero = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, heroDp))
            clipChildren = false
        }
        // The rings and the mark keep their proportions when the hero gives up height (B2-M4).
        val heroScale = heroDp / 150f
        for (size in listOf(150, 110, 70)) {
            val ring = SwStyle.dp(context, (size * heroScale).coerceAtLeast(1f))
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
            FrameLayout.LayoutParams(
                SwStyle.dp(context, 56f * heroScale).coerceAtLeast(1),
                SwStyle.dp(context, 56f * heroScale).coerceAtLeast(1)
            )
                .apply { gravity = Gravity.CENTER }
        )
        content.addView(hero)

        // ATLAS-F2 (026): the title was two TextViews in a fixed horizontal row, so at 200 % on a
        // 320dp phone "ScanWhirl Pro" ran into both edges. One TextView with a gold span on the
        // word "Pro" wraps at word boundaries inside the page margins instead.
        val titleText = context.getString(R.string.pro_title_prefix).trimEnd() + " " +
            context.getString(R.string.pro_title_pro)
        val titleGold = SwStyle.color(context, R.color.gold_pro_primary)
        val title = SwStyle.text(
            context, 22f, SwStyle.color(context, R.color.copydiet_pro2_primary), bold = true
        ).apply {
            text = android.text.SpannableString(titleText).apply {
                val proStart = titleText.lastIndexOf(context.getString(R.string.pro_title_pro))
                if (proStart >= 0) {
                    setSpan(
                        android.text.style.ForegroundColorSpan(titleGold),
                        proStart,
                        titleText.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            typeface = SwType.extraBold(context)
            gravity = Gravity.CENTER
            setPadding(SwStyle.dp(context, 14f), 0, SwStyle.dp(context, 14f), 0)
        }
        content.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        titleRow = title
        subtitle = SwStyle.text(context, 16f, SwStyle.color(context, R.color.copydiet_pro2_primary), bold = true).apply {
            // COPY_DIET PRO2: the compact sales slogan is deleted — the price card already owns
            // "One payment. No subscription." The owned status is a short P line, not a grey
            // thank-you paragraph.
            text = context.getString(R.string.copydiet_pro2_active_sub)
            gravity = Gravity.CENTER
            // ATLAS-F2 (026): wraps inside the margins instead of running edge to edge.
            setPadding(SwStyle.dp(context, 14f), 0, SwStyle.dp(context, 14f), 0)
            visibility = if (active) View.VISIBLE else View.GONE
        }
        content.addView(
            subtitle,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, 6f) }
        )

        val trustRow = LinearLayout(context).apply {
            id = R.id.swProTrust
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
        content.addView(
            trustRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(context, if (largeFont) 8f else 14f) }
        )
        this.trustRow = trustRow
        content.addView(
            priceStatus,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply {
                    leftMargin = SwStyle.dp(context, 18f)
                    rightMargin = SwStyle.dp(context, 18f)
                    topMargin = SwStyle.dp(context, 4f)
                }
        )

        // PRO2: the missing-price status is paired with an explicit retry that re-queries Play's
        // product details. The buy path stays disabled until Play actually answers.
        retryButton = SwStyle.text(
            context, 14f, SwStyle.color(context, R.color.copydiet_pro2_accent), bold = true
        ).apply {
            id = R.id.swProPriceRetry
            text = context.getString(R.string.ux130_l5_pro_price_retry)
            contentDescription = text
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(context, 48f)
            isClickable = true
            isFocusable = true
            SwStyle.asButton(this)
            visibility = View.GONE
            setOnClickListener { onRetryPrice() }
        }
        content.addView(
            retryButton,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f)).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 2f), m, 0)
            }
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
            R.drawable.ic_sym_qr_code_scanner to R.string.pro_feat_scan,
            R.drawable.ic_sym_check_circle to R.string.pro_feat_batch,
            // UX5-12: the feature is Tags; a label/badge glyph, not a folder.
            R.drawable.ic_sym_badge to R.string.pro_feat_folders
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
        content.addView(feat)

        moreRow = SwStyle.text(context, 14f, SwStyle.color(context, R.color.copydiet_pro2_primary), bold = true).apply {
            id = R.id.swProMore
            text = context.getString(R.string.copydiet_pro2_all_tools, ToolRegistry.proCount())
            contentDescription = text
            gravity = Gravity.CENTER
            // The label must wrap inside the page margins at 200 %, not touch the tile edge.
            setPadding(SwStyle.dp(context, 14f), 0, SwStyle.dp(context, 14f), 0)
            isClickable = true
            isFocusable = true
            SwStyle.asButton(this)
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.gold_tile_fill),
                radiusDp = 16f,
                stroke = SwStyle.color(context, R.color.gold_tile_border)
            )
            minimumHeight = SwStyle.dp(context, 48f)
            // Preserved: ProCountTest and the shell rely on this row opening the Tools hub.
            setOnClickListener { callbacks.onSeeAllProTools() }
        }
        content.addView(
            moreRow,
            LayoutParams(LayoutParams.MATCH_PARENT, SwStyle.dp(context, 48f)).apply {
                val m = SwStyle.dp(context, 18f)
                setMargins(m, SwStyle.dp(context, 8f), m, 0)
            }
        )
        // PRO2: one "See all N Pro tools" link only. The counted [moreRow] above opens the Tools
        // hub; the old duplicate un-counted in-place catalogue link was removed.

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
        priceValue = SwStyle.text(context, 28f, SwStyle.color(context, R.color.copydiet_pro2_primary), bold = true).apply {
            id = R.id.swProPrice
        }
        priceRow.addView(priceValue)
        priceText.addView(priceRow)
        // COPY_DIET PRO2: the "one time" suffix is deleted; this single S14 line is the only
        // payment disclosure next to the P28 Play price.
        priceText.addView(
            SwStyle.text(context, 14f, SwStyle.color(context, R.color.copydiet_pro2_secondary), bold = true).apply {
                text = context.getString(R.string.copydiet_pro2_once)
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
        buyButton = SwStyle.text(context, 16f, SwStyle.color(context, R.color.sw_on_gold), bold = true).apply {
            id = R.id.swProBuy
            text = context.getString(R.string.ux130_l5_pro_buy)
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
        // Fail closed: no amount until Play answers, never a built-in number. Must run after the
        // buy button exists — the status also drives its purchasable (enabled) state.
        showPrice(null)

        if (!active) {
            // UX5-1: Restore is no longer buried below the feature grid. It and Continue free
            // live in the immediately discoverable action area beside the purchase control.
            continueFree = SwStyle.quietAction(
                context,
                SwStyle.text(context, 14f, SwStyle.color(context, R.color.copydiet_pro2_accent), bold = true)
            ).apply {
                id = R.id.swProContinueFree
                text = context.getString(R.string.ux130_l5_pro_continue_free)
                contentDescription = text
                gravity = Gravity.CENTER
                maxLines = 2
                setOnClickListener { callbacks.onClose() }
            }
            restoreRow = SwStyle.quietAction(
                context,
                SwStyle.text(context, 14f, SwStyle.color(context, R.color.copydiet_pro2_primary), bold = true)
            ).apply {
                id = R.id.swProRestoreAction
                text = context.getString(R.string.ux130_l5_pro_restore)
                contentDescription = text
                gravity = Gravity.CENTER
                maxLines = 2
                setOnClickListener { onRestorePurchase() }
            }
            // minHeight 48dp is set by SwStyle.quietAction; the height wraps so a two-line label
            // at 200% or in Hindi can grow. ActionStack stacks them when they cannot fit side by side.
            val actions = ActionStack(context)
            // PRO2: both exits share the same width and height so they read as one aligned pair
            // (instead of the old unequally sized, vertically offset buttons).
            actions.addView(
                continueFree,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = SwStyle.dp(context, 4f) }
            )
            actions.addView(
                restoreRow,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = SwStyle.dp(context, 4f) }
            )
            content.addView(
                actions, 6,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply {
                        leftMargin = SwStyle.dp(context, 18f)
                        rightMargin = SwStyle.dp(context, 18f)
                        topMargin = SwStyle.dp(context, 10f)
                    }
            )
        }
        content.addView(SwStyle.spacer(context, 20f))

        // H3: the offer itself never scrolls away. An active build has no price or Unlock row,
        // so there it is simply the scrollable page.
        addView(
            ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
        if (!active) {
            addView(
                stickyBar(),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            applyDockOverlap()
        }

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
    private fun onUnlockPro() {
        // PRO2: fail closed. Without Play's real product details there is nothing to buy, so a
        // tap (even a programmatic one) can never launch the billing flow.
        if (!priceKnown || buying || active) return
        callbacks.onBuy()
    }

    /** Re-query Play's product details after "Try again". Stale/null answers change nothing. */
    private fun onRetryPrice() {
        if (active) return
        retryButton.isEnabled = false
        retryButton.alpha = 0.6f
        com.scanwhirl.app.util.ProBilling.price(context) { price ->
            if (isAttachedToWindow) {
                retryButton.isEnabled = true
                retryButton.alpha = 1f
                if (!price.isNullOrBlank()) setPrice(price)
            }
        }
    }

    private fun onRestorePurchase() = callbacks.onRestore()

    private fun refreshBuyAvailability() {
        val canBuy = priceKnown && !buying && !active
        buyButton.isEnabled = canBuy
        buyButton.alpha = if (canBuy) 1f else 0.6f
        buyButton.contentDescription = context.getString(
            if (priceKnown) R.string.ux130_l5_pro_buy else R.string.ux130_l5_pro_price_unavailable
        )
    }

    /**
     * Price copy: PRC-001 — the amount is only ever Play's localized price. A null/blank answer
     * (not loaded, billing unavailable, offline) keeps the line on the no-amount placeholder, so
     * no number is invented and the "one time" mark is not shown next to a missing amount.
     */
    fun setPrice(text: String?) {
        showPrice(text)
    }

    private fun showPrice(text: String?) {
        val amount = text?.trim().orEmpty()
        val known = amount.isNotEmpty()
        priceValue.text = if (known) amount else context.getString(R.string.pro_price_play)
        priceValue.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            if (known) 28f else 16f
        )
        // S2 item 9: a missing price is an honest status on the BUY screen, not a claim that
        // payment is unavailable. An already-owned (active) screen has no price/Unlock row at all,
        // so a missing Play price must not surface a purchase-price error there.
        priceKnown = known
        val showError = !known && !active
        priceStatus.visibility = if (showError) View.VISIBLE else View.GONE
        retryButton.visibility = if (showError) View.VISIBLE else View.GONE
        refreshBuyAvailability()
    }

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
    internal fun restoreRowForTest(): View? = if (::restoreRow.isInitialized) restoreRow else null

    /**
     * One purchase at a time: while Play is answering, the Buy row is disabled and greyed so a
     * double tap cannot open a second billing flow. [ShellActivity] re-enables it on every
     * terminal outcome (and a recreated screen starts disabled while the purchase is in flight).
     */
    fun setBuying(busy: Boolean) {
        buying = busy
        // Get Pro needs BOTH a real price and an idle purchase slot (PRO2 fail-closed).
        refreshBuyAvailability()
        // p2pro #3: Restore used to be a live tap during a Buy and ProBilling.restore() returned
        // silently (its Busy went to a no-op callback). Disable it with an honest accessible
        // state so the user cannot make a request that will be ignored.
        if (::restoreRow.isInitialized) {
            restoreRow.isEnabled = !busy
            restoreRow.alpha = if (busy) 0.6f else 1f
            restoreRow.contentDescription = context.getString(
                if (busy) R.string.pro_purchase_in_progress else R.string.pro_restore
            )
        }
    }

    /**
     * H3 (device sweep 2026-11-19): the pinned bar's bottom edge stops at the page bottom, i.e.
     * exactly above the floating dock. Passed in by the shell so a future dock that really does
     * cover the page (a non-zero overlap) lifts the bar by that amount, never by a guessed one.
     */
    fun keepClearOfDock(overlapPx: () -> Int) {
        dockOverlap = overlapPx
        applyDockOverlap()
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyDockOverlap() }
    }

    private fun applyDockOverlap() {
        val px = dockOverlap?.invoke() ?: return
        if (px == paddingBottom) return
        // The dock is measured on the same pass as this screen; re-pad after it settles.
        post {
            if (px != paddingBottom) {
                setPadding(paddingLeft, paddingTop, paddingRight, px)
            }
        }
    }

    /** Pinned price + Unlock row (never scrolls out of view). */
    private fun stickyBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(SwStyle.color(context, R.color.rv_bg))
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
        // UX5-1: keep the pinned offer two rows (price + purchase). The free exit, Restore and
        // missing-price status sit at the top of the scrollable copy where they are immediately
        // visible, but cannot grow a footer over the promise chips at 130/200% font.
        bar.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return bar
    }
    internal fun featureCellsForTest(): List<View> = featureCells

    private fun feature(icon: Int, label: Int): View {
        // V076/V077: at 200 % the icon sits above the label, giving a long word the whole
        // cell width, and every cell gets the same floor so sibling tiles stay the same height.
        val stacked = resources.configuration.fontScale >= 1.6f
        val cell = LinearLayout(context).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                context,
                0x2AFFB800,
                radiusDp = 16f,
                stroke = 0x2EFFB800
            )
            val pad = SwStyle.dp(context, if (stacked) 10f else 12f)
            setPadding(pad, SwStyle.dp(context, if (stacked) 12f else 6f),
                pad, SwStyle.dp(context, if (stacked) 12f else 6f))
            minimumHeight = SwStyle.dp(context, if (stacked) 96f else 48f)
        }
        cell.addView(
            ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(SwStyle.dp(context, 22f), SwStyle.dp(context, 22f)).apply {
                if (stacked) bottomMargin = SwStyle.dp(context, 8f)
                else marginEnd = SwStyle.dp(context, 10f)
            }
        )
        cell.addView(
            SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.copydiet_pro2_primary), bold = true).apply {
                text = context.getString(label)
                // UX5-3: SwStyle.text applied the 13.5sp token; no px cap. Three wrap lines are
                // allowed so the measured height always matches the laid-out text at 130/200%.
                maxLines = 3
                ellipsize = null
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                gravity = if (stacked) Gravity.CENTER else Gravity.START
                //noinspection WrongConstant
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                setLineSpacing(0f, 1.1f)
            },
            if (stacked) LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        return cell
    }
}

/**
 * The free exit and Restore sit side by side only when both labels actually fit. A 200% or
 * Hindi "Restore purchase" needs two lines, so the row switches to a vertical stack before the
 * text can clip. The decision is measured, not guessed from locale or font scale. In either
 * orientation the two actions are forced to the same width and height (PRO2 alignment).
 */
private class ActionStack(context: Context) : LinearLayout(context) {

    private var paramsApplied = -1

    init {
        orientation = HORIZONTAL
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (childCount == 2 && View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
            val available = View.MeasureSpec.getSize(widthSpec)
            val needed = naturalWidth(0, available) + naturalWidth(1, available) +
                SwStyle.dp(context, 8f)
            val wanted = if (needed > available) VERTICAL else HORIZONTAL
            applyOrientation(wanted)
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    private fun applyOrientation(wanted: Int) {
        if (paramsApplied == wanted) return
        orientation = wanted
        paramsApplied = wanted
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            if (wanted == HORIZONTAL) {
                lp.width = 0
                lp.weight = 1f
                lp.height = LayoutParams.MATCH_PARENT
            } else {
                lp.width = LayoutParams.MATCH_PARENT
                lp.weight = 0f
                lp.height = LayoutParams.WRAP_CONTENT
            }
        }
    }

    /** Probes a child unwrapped, then restores its real layout params for the parent's pass. */
    private fun naturalWidth(index: Int, available: Int): Int {
        val child = getChildAt(index)
        val params = child.layoutParams as LayoutParams
        val width = params.width
        val weight = params.weight
        params.width = LayoutParams.WRAP_CONTENT
        params.weight = 0f
        child.measure(
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val result = child.measuredWidth
        params.width = width
        params.weight = weight
        return result
    }
}
