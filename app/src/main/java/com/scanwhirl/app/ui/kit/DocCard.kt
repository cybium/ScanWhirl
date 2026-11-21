package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.home.PageHitViews
import com.scanwhirl.app.util.CategoryLabels
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.PageTextMatches

/**
 * T28: what a Home search row may truthfully say about a stored-text hit.
 * Built off the main thread from the already-stored per-page OCR text; never
 * carries a page for a locked or Vault document.
 */
data class DocSearchHit(
    val docId: String,
    val revision: Long,
    val pageIndex: Int,
    val pageKey: String,
    val query: String,
    val excerpt: PageTextMatches.Excerpt
)

/**
 * v2 UI: 01_home_A — a document row: paper thumbnail with the page count,
 * name (+ lock badge), meta, folder dot, Share and ⋯.
 *
 * uihome (VISUAL_AUDIT_A X2): the thumbnail is a real first page at 72×102dp
 * (page aspect), not a 54dp blank square; a row is at least 88dp tall so the
 * tile is legible, and a document with no readable page shows its first letter
 * plus the page icon instead of an empty tile.
 */
class DocCard(context: Context) : LinearLayout(context) {

    val thumbView: ImageView
    val pageBadge: TextView
    val lockIcon: ImageView
    val nameView: TextView
    val lockBadge: TextView
    val metaView: TextView
    val categoryView: TextView
    val categoryDot: View
    val shareButton: ImageButton
    val moreButton: ImageButton

    /** The placeholder page icon and the document's first letter (never a blank tile). */
    val thumbPlaceholder: ImageView
    val thumbLetter: TextView

    /** The selection check; visible only while the library is selecting this card. */
    val selectionBadge: ImageView

    /** T28: "Page N" and the stored-text snippet for a search hit; hidden otherwise. */
    val searchPage: TextView
    val searchSnippet: TextView
    private val searchBlock: LinearLayout

    private val thumbFrame: FrameLayout
    private val thumbPlaceholderGroup: LinearLayout
    private val categoryRow: LinearLayout
    private val metaLine: MetaLine
    private val actions: LinearLayout

    /** KT1: selection mode is announced as a real checkable control, not colour alone. */
    private var selectionMode = false
    private var checkedState = false

    /**
     * UI-W4: at a compact width (or 200 % font) a row keeps one named overflow. The
     * direct Share moves into the same Actions sheet as More, so the name, page line
     * and snippet keep the full text column instead of being squeezed beside two icons.
     */
    private val compactRow: Boolean
        get() = resources.configuration.screenWidthDp <= 360 ||
            resources.configuration.fontScale >= 1.6f

    var doc: ScanDocument? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        background = SwStyle.card(context, 18f)
        setPadding((12 * d).toInt(), (12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt())
        minimumHeight = SwStyle.dp(context, 88f)
        isClickable = true
        isFocusable = true
        SwMotion.press(this)
        androidx.core.view.ViewCompat.setAccessibilityDelegate(
            this,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (selectionMode) {
                        info.className = android.widget.CheckBox::class.java.name
                        info.isCheckable = true
                        info.isChecked = checkedState
                    }
                }
            }
        )

        thumbFrame = FrameLayout(context).apply {
            background = SwStyle.rounded(context, SwStyle.color(context, R.color.sw_paper_thumb), radiusDp = 8f)
            clipToOutline = true
        }
        thumbView = ImageView(context).apply {
            id = R.id.swDocThumb
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumbFrame.addView(
            thumbView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // No page image (locked, unreadable, still being written): a page glyph over the
        // document's first letter. Never an empty white tile (VISUAL_AUDIT_A X2 / §6.1).
        thumbPlaceholder = ImageView(context).apply {
            id = R.id.swDocThumbPlaceholder
            setImageResource(R.drawable.ic_document)
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.text_hologram_dim))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumbLetter = SwStyle.text(context, 24f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
            id = R.id.swDocThumbLetter
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumbPlaceholderGroup = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isVisible = false
        }
        thumbPlaceholderGroup.addView(
            thumbPlaceholder,
            LinearLayout.LayoutParams(SwStyle.dp(context, 20f), SwStyle.dp(context, 20f))
        )
        thumbPlaceholderGroup.addView(
            thumbLetter,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        thumbFrame.addView(
            thumbPlaceholderGroup,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        lockIcon = ImageView(context).apply {
            id = R.id.swDocLock
            setImageResource(R.drawable.ic_sym_lock)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
            scaleType = ImageView.ScaleType.CENTER
            isVisible = false
        }
        thumbFrame.addView(
            lockIcon,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // L1-04: badges and metadata below 12sp are raised to the readable floor.
        pageBadge = SwStyle.text(context, 12f, Color.WHITE, bold = true).apply {
            background = SwStyle.rounded(context, 0xCC0E1316.toInt(), radiusDp = 5f)
            setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        }
        thumbFrame.addView(
            pageBadge,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = (4 * d).toInt()
                bottomMargin = (4 * d).toInt()
            }
        )
        selectionBadge = ImageView(context).apply {
            id = R.id.swDocSelected
            setImageResource(R.drawable.ic_sym_check_circle_filled)
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.cyan_primary))
            background = SwStyle.rounded(context, 0xCC0E1316.toInt(), radiusDp = 999f)
            setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            isVisible = false
        }
        thumbFrame.addView(
            selectionBadge,
            FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = (3 * d).toInt()
                topMargin = (3 * d).toInt()
            }
        )
        addView(
            thumbFrame,
            LayoutParams(SwStyle.dp(context, 64f), SwStyle.dp(context, 90f)).apply {
                marginEnd = (12 * d).toInt()
            }
        )

        val info = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameView = SwStyle.text(context, 15.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.swDocName
            typeface = SwType.bold(context)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            // Same value as LineBreaker.BREAK_STRATEGY_HIGH_QUALITY (API 29+); Layout's constant works from API 23.
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            val density = resources.displayMetrics.density
            val scaled = resources.displayMetrics.scaledDensity
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                minOf(15.5f * scaled, 17f * density)
            )
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                (11f * density).toInt(),
                (15.5f * density).toInt(),
                1,
                android.util.TypedValue.COMPLEX_UNIT_PX
            )
        }
        nameRow.addView(nameView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lockBadge = SwStyle.text(context, 12f, SwStyle.color(context, R.color.gold_pro_primary), bold = true).apply {
            id = R.id.swDocLockBadge
            text = context.getString(R.string.home_protected)
            isVisible = false
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sym_lock, 0, 0, 0)
            compoundDrawablePadding = (3 * d).toInt()
            compoundDrawableTintList = ColorStateList.valueOf(
                SwStyle.color(context, R.color.gold_pro_primary)
            )
        }
        nameRow.addView(lockBadge)
        info.addView(nameRow)

        metaView = com.scanwhirl.app.ui.kit.SwStyle.text(
            context,
            // UI_PLAN 0.8: Hindi body/meta starts at 14sp; Latin meta at 12.5sp.
            if (com.scanwhirl.app.util.AppLanguage.showingHindi(context)) 14f else 12.5f,
            SwStyle.color(context, R.color.text_hologram_dim)
        ).apply {
            id = R.id.swDocMeta
            // M1 (device sweep): one line ellipsized the date away ("2 pages · 166 KB ·
            // Yest…"). Two lines keep the whole meta — date last and readable — and at
            // 200 % font a third line does the same (unclipped beats density, UI_PLAN 0.8).
            maxLines = if (resources.configuration.fontScale >= 1.6f) 3 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }

        categoryRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryDot = View(context).apply {
            background = SwStyle.rounded(context, SwStyle.color(context, R.color.cat_other), radiusDp = 3f)
        }
        categoryRow.addView(
            categoryDot,
            LayoutParams((8 * d).toInt(), (8 * d).toInt()).apply { marginEnd = (5 * d).toInt() }
        )
        categoryView = SwStyle.text(
            context,
            if (com.scanwhirl.app.util.AppLanguage.showingHindi(context)) 14f else 12f,
            SwStyle.color(context, R.color.text_hologram_mid), bold = true
        ).apply {
            id = R.id.swDocFolder
            minimumHeight = (14 * d).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryRow.addView(categoryView)

        // Meta + folder share one line when the folder still fits; on a narrow row or a
        // long meta the folder drops below instead of squeezing the meta (or drawing over it).
        metaLine = MetaLine(context)
        metaLine.addView(
            metaView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        metaLine.addView(
            categoryRow,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        info.addView(
            metaLine,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (3 * d).toInt() }
        )

        // T28: page evidence from stored OCR text. Two calm lines under the meta:
        // "Page 2" then one short excerpt with the matched word bold (never colour-only).
        // The row itself is the tap target, so this is not a nested 48 dp control.
        searchPage = SwStyle.text(
            context,
            if (com.scanwhirl.app.util.AppLanguage.showingHindi(context)) 14f else 12f,
            SwStyle.color(context, R.color.cyan_primary),
            bold = true
        ).apply {
            id = R.id.swSearchPage
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        searchSnippet = SwStyle.text(
            context,
            if (com.scanwhirl.app.util.AppLanguage.showingHindi(context)) 14f else 13f,
            SwStyle.color(context, R.color.text_hologram_mid)
        ).apply {
            id = R.id.swSearchSnippet
            maxLines = if (resources.configuration.fontScale >= 1.6f) 3 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }
        searchBlock = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            addView(searchPage)
            addView(
                searchSnippet,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = (2 * d).toInt() }
            )
        }
        info.addView(
            searchBlock,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (5 * d).toInt() }
        )

        addView(info, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shareButton = SwStyle.iconButton(
            context, R.drawable.ic_sym_share, SwStyle.color(context, R.color.text_hologram_mid)
        ).apply {
            id = R.id.swDocShare
            contentDescription = context.getString(R.string.share_pdf)
            layoutParams = LayoutParams(SwStyle.dp(context, 48f), SwStyle.dp(context, 48f))
            setPadding(SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f))
        }
        moreButton = SwStyle.iconButton(
            context, R.drawable.ic_sym_more_vert, SwStyle.color(context, R.color.text_hologram_mid)
        ).apply {
            id = R.id.swDocMore
            contentDescription = context.getString(R.string.more)
            layoutParams = LayoutParams(SwStyle.dp(context, 48f), SwStyle.dp(context, 48f))
            setPadding(SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f), SwStyle.dp(context, 12f))
        }
        actions.addView(shareButton)
        actions.addView(moreButton)
        addView(actions)
    }

    fun bind(
        document: ScanDocument,
        meta: String,
        onShare: () -> Unit,
        onMore: () -> Unit,
        search: DocSearchHit? = null
    ) {
        doc = document
        nameView.text = document.name
        metaView.text = meta
        lockBadge.isVisible = document.isLocked
        lockIcon.isVisible = document.isLocked
        alpha = if (document.isLocked) 0.92f else 1f
        pageBadge.text = document.pageCount.toString()
        pageBadge.isVisible = document.pageCount > 0

        // T28: evidence belongs to THIS document at its current revision. A locked or
        // Vault document shows none even if a stale map hands one in.
        val hit = search?.takeIf {
            !document.isLocked && !document.isVaulted &&
                it.docId == document.id && it.revision == document.revision
        }
        searchBlock.isVisible = hit != null
        if (hit == null) {
            searchPage.text = null
            searchSnippet.text = null
        } else {
            searchPage.text = context.getString(R.string.search_hit_page, hit.pageIndex + 1)
            searchSnippet.text = PageHitViews.styled(context, hit.excerpt)
        }

        // The row names the folder the document lives in (sentence case, as the person
        // typed it). An unsorted document still names its library category.
        val folderName = document.folder?.trim().orEmpty()
        categoryView.text = if (folderName.isNotEmpty()) {
            folderName
        } else {
            CategoryLabels.display(context, document.category)
        }
        categoryDot.background = SwStyle.rounded(
            context,
            if (folderName.isNotEmpty()) SwStyle.color(context, R.color.cyan_primary)
            else CategoryStyle.color(document.category),
            radiusDp = 3f
        )
        // KT1: a search hit used to replace the whole description, so TalkBack lost the name,
        // meta, folder and protected badge. Speak the whole row, then add the match.
        contentDescription = spokenDescription(hit)
        shareButton.setOnClickListener { onShare() }
        moreButton.setOnClickListener { onMore() }
        // UI-W4: one named overflow at compact width / large font, as the judged
        // Library row specifies. Share is still one tap away inside More's sheet.
        shareButton.isVisible = !compactRow
        moreButton.isVisible = true
        selectionBadge.isVisible = false

        thumbView.imageTintList = null
        thumbView.setImageDrawable(null)
        thumbView.setBackgroundColor(SwStyle.color(context, R.color.sw_paper_thumb))
        val firstPage = document.pageFiles.firstOrNull()
        // A locked or Vault document never gets a page image in a search/library row:
        // the neutral letter placeholder is the only tile it may show (UI-W4).
        val canShowPage = firstPage != null && firstPage.exists() &&
            !document.isLocked && !document.isVaulted
        showThumbPlaceholder(if (canShowPage) null else document)
        if (firstPage != null && canShowPage) {
            runCatching {
                // Sampled + cached by the shared page-thumb LRU; fitExactly keeps a
                // 100-document list inside the cache budget (72dp tile, not a 1080p page).
                com.scanwhirl.app.ui.pagegrid.PageThumbLoader.load(
                    firstPage,
                    THUMB_TARGET_PX,
                    com.scanwhirl.app.util.ScanFilter.ORIGINAL,
                    thumbView,
                    fitExactly = true,
                    placeholder = R.drawable.sw_thumb_blank,
                    onLoaded = { bitmap, _ ->
                        thumbView.setImageBitmap(bitmap)
                        showThumbPlaceholder(null)
                    },
                    onFailed = { showThumbPlaceholder(document) },
                    skipStale = true
                )
            }
        } else {
            com.scanwhirl.app.ui.pagegrid.PageThumbLoader.cancel(thumbView)
        }
    }

    /** One spoken sentence for the whole row: identity and metadata first, the match last. */
    private fun spokenDescription(hit: DocSearchHit?): String {
        val parts = mutableListOf<String>()
        parts += nameView.text.toString()
        if (lockBadge.isVisible) parts += lockBadge.text.toString()
        if (metaView.text.isNotBlank()) parts += metaView.text.toString()
        if (categoryView.text.isNotBlank()) parts += categoryView.text.toString()
        if (hit != null) {
            parts += searchPage.text.toString()
            parts += hit.excerpt.text
        }
        return parts.filter { it.isNotBlank() }.joinToString(", ")
    }

    /**
     * Shows the letter + page-glyph placeholder for [document] (null hides it, a real
     * page is on the tile). The letter is the first character of the name, upper-cased
     * with Locale.ROOT like a contact avatar.
     */
    private fun showThumbPlaceholder(document: ScanDocument?) {
        val show = document != null
        thumbPlaceholderGroup.isVisible = show
        if (show) {
            val letter = document!!.name.trim().firstOrNull()?.toString().orEmpty()
            thumbLetter.text = if (letter.isEmpty()) "#" else letter.uppercase()
        }
    }

    /**
     * v2 UI: 03_tools_hub — compact row for the kit document chooser: 40×52
     * thumbnail, one-line name and meta, lock badge, no category or buttons.
     */
    fun asCompact(): DocCard = apply {
        val d = resources.displayMetrics.density
        setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        minimumHeight = (64 * d).toInt()
        thumbFrame.layoutParams = LayoutParams((40 * d).toInt(), (52 * d).toInt())
            .apply { marginEnd = (12 * d).toInt() }
        thumbLetter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        thumbPlaceholder.layoutParams = LinearLayout.LayoutParams((12 * d).toInt(), (12 * d).toInt())
        categoryRow.visibility = View.GONE
        actions.visibility = View.GONE
        selectionBadge.visibility = View.GONE
        searchBlock.visibility = View.GONE
        nameView.maxLines = 2
        metaView.maxLines = 2
    }

    /**
     * v2 UI: 01_home_A — selection mode: the check badge appears only while the
     * library is selecting, and the per-card Share/⋯ give way to the action bar.
     */
    fun setSelection(active: Boolean, checked: Boolean) {
        selectionMode = active
        checkedState = checked
        selectionBadge.isVisible = active && checked
        shareButton.isVisible = !active && !compactRow
        moreButton.isVisible = !active
        background = if (checked) {
            SwStyle.card(context, 18f).apply {
                setStroke(
                    SwStyle.dp(context, 1f).coerceAtLeast(1),
                    SwStyle.color(context, R.color.cyan_primary)
                )
            }
        } else {
            SwStyle.card(context, 18f)
        }
    }

    companion object {
        /**
         * Longest side the row thumbnail is decoded to. The tile is 64×90dp, so even
         * at 3x it needs 270px; sampling to 320 keeps the shared 24 MB LRU honest.
         */
        const val THUMB_TARGET_PX = 320
    }
}

/**
 * uihome: the row's meta line and its folder dot + name.
 *
 * The meta is at most two lines (ellipsize end beyond that; M1: the date must stay
 * readable, so the line wraps at word boundaries instead of cutting "Yest…"). The folder
 * sits after it when both fit the text column; otherwise it drops to a second line, never
 * over the meta and never squeezed to nothing (long Hindi titles, 200% font, narrow phones).
 */
private class MetaLine(context: Context) : ViewGroup(context) {

    private val gap = (6 * resources.displayMetrics.density).toInt()
    private var stacked = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val meta = getChildAt(0)
        val folder = getChildAt(1)
        val folderShown = folder.visibility != GONE

        meta.measure(
            MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        var folderW = 0
        var folderH = 0
        if (folderShown) {
            folder.measure(
                MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            folderW = folder.measuredWidth
            folderH = folder.measuredHeight
        }
        stacked = folderShown && meta.measuredWidth + gap + folderW > avail

        val contentH = if (stacked) {
            meta.measuredHeight + gap + folderH
        } else {
            maxOf(meta.measuredHeight, folderH)
        }
        setMeasuredDimension(
            resolveSize(avail + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentH + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = width - paddingLeft - paddingRight
        val meta = getChildAt(0)
        val folder = getChildAt(1)
        val folderShown = folder.visibility != GONE

        meta.layout(
            paddingLeft, paddingTop,
            paddingLeft + meta.measuredWidth, paddingTop + meta.measuredHeight
        )
        if (!folderShown) return
        if (stacked) {
            val top = paddingTop + meta.measuredHeight + gap
            folder.layout(paddingLeft, top, paddingLeft + folder.measuredWidth, top + folder.measuredHeight)
        } else {
            val top = paddingTop + (maxOf(meta.measuredHeight, folder.measuredHeight) - folder.measuredHeight) / 2
            val left = (paddingLeft + meta.measuredWidth + gap).coerceAtMost(paddingLeft + avail - folder.measuredWidth)
            folder.layout(left, top, left + folder.measuredWidth, top + folder.measuredHeight)
        }
    }
}
