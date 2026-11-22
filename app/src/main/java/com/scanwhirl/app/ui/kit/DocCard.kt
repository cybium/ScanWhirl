package com.scanwhirl.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.home.PageHitViews
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
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
 * Council §3 / UI_100 §C — one document row, three calm parts:
 *  - a real first-page thumbnail on the shared dark canvas, with the lock/vault
 *    state as a 16dp scrim circle OVER the thumb (never a badge on the name);
 *  - the name in its own two-line, whole-word block (autosize 15.5→12sp);
 *  - ONE meta line "N pages · size · date" (app locale, decimal size), nothing else.
 *
 * One named ⋯ lives at every width; Share moved into its sheet, so a name can
 * never be squeezed by a second action cell. Folders appear only in search
 * results (the caller supplies that evidence), never as a second meta line.
 */
class DocCard(context: Context) : LinearLayout(context) {

    val thumbView: ImageView
    val pageBadge: TextView
    val lockIcon: ImageView
    val nameView: TextView
    val metaView: TextView
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
    private val actions: LinearLayout

    /** KT1: selection mode is announced as a real checkable control, not colour alone. */
    private var selectionMode = false
    private var checkedState = false

    var doc: ScanDocument? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        background = SwStyle.card(context, 18f)
        // The band redesign freed the 640dp clearance; no category line now, so
        // 10dp vertical padding keeps at least two cards above the dock at 360x640.
        setPadding((12 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
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
            // Council §4: the page sits on sw_canvas with a 3dp inset and a 1dp
            // #FFFFFF@24% edge. The page's OWN white is kept; only its host changed.
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.sw_cc_canvas), 6f,
                stroke = SwStyle.color(context, R.color.sw_cc_page_edge)
            )
            setForeground(
                SwStyle.rounded(
                    context, Color.TRANSPARENT, 4f,
                    stroke = SwStyle.color(context, R.color.sw_cc_page_edge)
                )
            )
            setPadding((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
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
        // Council §3 / OWNER 2026-11-22: lock is a 16dp gold-on-dark scrim circle at the
        // thumb's top-end corner. There is no "Protected" badge on the name line.
        lockIcon = ImageView(context).apply {
            id = R.id.swDocLock
            setImageResource(R.drawable.ic_sym_lock)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = ColorStateList.valueOf(SwStyle.color(context, R.color.gold_pro_primary))
            background = SwStyle.rounded(context, 0xCC0E1316.toInt(), radiusDp = 999f)
            scaleType = ImageView.ScaleType.CENTER
            setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            isVisible = false
        }
        thumbFrame.addView(
            lockIcon,
            FrameLayout.LayoutParams((16 * d).toInt(), (16 * d).toInt()).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = (2 * d).toInt()
                topMargin = (2 * d).toInt()
            }
        )
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
        // Council §3: the name owns the whole text column. No lock badge, no
        // category dot, no second competitor for width — so "annot_test" and a
        // 53-character name break at whole words, never mid-identifier.
        nameView = SwStyle.text(context, 15.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.swDocName
            typeface = SwType.bold(context)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            val density = resources.displayMetrics.density
            val scaled = resources.displayMetrics.scaledDensity
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                minOf(15.5f * scaled, 17f * density)
            )
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                (12f * density).toInt(),
                (15.5f * density).toInt(),
                1,
                android.util.TypedValue.COMPLEX_UNIT_PX
            )
        }
        info.addView(nameView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ONE meta line at 100%. The extra lines are only a large-font safety valve
        // so the 200% no-clip tests keep passing (UI_100 explicitly leaves them alone).
        metaView = SwStyle.text(
            context,
            if (com.scanwhirl.app.util.AppLanguage.showingHindi(context)) 14f else 12.5f,
            SwStyle.color(context, R.color.text_hologram_dim)
        ).apply {
            id = R.id.swDocMeta
            maxLines = when {
                resources.configuration.fontScale >= 1.6f -> 3
                resources.configuration.fontScale >= 1.15f -> 2
                else -> 1
            }
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }
        info.addView(
            metaView,
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

        // Council §3: a single named ⋮ at every width. Share lives in that sheet.
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
        lockIcon.isVisible = document.isLocked || document.isVaulted
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

        // KT1: a search hit used to replace the whole description, so TalkBack lost the
        // name, meta, folder and protected state. Speak the whole row, then add the match.
        contentDescription = spokenDescription(hit)
        shareButton.setOnClickListener { onShare() }
        moreButton.setOnClickListener { onMore() }
        shareButton.isVisible = false
        moreButton.isVisible = true
        selectionBadge.isVisible = false

        thumbView.imageTintList = null
        thumbView.setImageDrawable(null)
        thumbView.setBackgroundColor(Color.TRANSPARENT)
        val firstPage = document.pageFiles.firstOrNull()
        // A locked or Vault document never gets a page image in a search/library row:
        // the neutral letter placeholder is the only tile it may show (UI-W4).
        // PERF 3: pageFiles came from the repository's listFiles() pass; its non-empty list and
        // pageCount are the cached existence proof. No main-thread exists()/stat per bind.
        val canShowPage = firstPage != null && document.pageCount > 0 &&
            !document.isLocked && !document.isVaulted
        showThumbPlaceholder(if (canShowPage) null else document)
        if (firstPage != null && canShowPage) {
            runCatching {
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
                    skipStale = true,
                    revision = document.revision.toString()
                )
            }
        } else {
            PageThumbLoader.cancel(thumbView)
        }
    }

    /** One spoken sentence for the whole row: identity and metadata first, the match last. */
    private fun spokenDescription(hit: DocSearchHit?): String {
        val parts = mutableListOf<String>()
        parts += nameView.text.toString()
        // The lock is a thumb overlay now; the spoken "Protected" comes from the
        // string resource directly, exactly once, never from a badge's text.
        if (doc?.isLocked == true || doc?.isVaulted == true) {
            parts += context.getString(R.string.home_protected)
        }
        if (metaView.text.isNotBlank()) parts += metaView.text.toString()
        // Folder is evidence, not a visible second line; keep it for TalkBack only
        // when the caller actually set one (never the "Unsorted" placeholder).
        doc?.folder?.trim()?.takeIf { it.isNotBlank() }?.let { parts += it }
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

    /** Compact kit chooser row: no actions, a smaller thumb, same calm text rules. */
    fun asCompact(): DocCard = apply {
        val d = resources.displayMetrics.density
        setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        minimumHeight = (64 * d).toInt()
        thumbFrame.layoutParams = LayoutParams((40 * d).toInt(), (52 * d).toInt())
            .apply { marginEnd = (12 * d).toInt() }
        thumbLetter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        thumbPlaceholder.layoutParams = LinearLayout.LayoutParams((12 * d).toInt(), (12 * d).toInt())
        actions.visibility = View.GONE
        selectionBadge.visibility = View.GONE
        searchBlock.visibility = View.GONE
        nameView.maxLines = 2
        metaView.maxLines = 1
    }

    /**
     * v2 UI: 01_home_A — selection mode: the check badge appears only while the
     * library is selecting, and the per-card ⋯ gives way to the action bar.
     */
    fun setSelection(active: Boolean, checked: Boolean) {
        selectionMode = active
        checkedState = checked
        selectionBadge.isVisible = active && checked
        shareButton.isVisible = false
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
