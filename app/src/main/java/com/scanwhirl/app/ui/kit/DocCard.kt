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
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle

/**
 * v2 UI: 01_home_A — a document row: paper thumbnail with the page count,
 * name (+ lock badge), meta, category dot, Share and ⋯.
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

    /** The selection check; visible only while the library is selecting this card. */
    val selectionBadge: ImageView

    private val thumbFrame: FrameLayout
    private val categoryRow: LinearLayout
    private val actions: LinearLayout

    var doc: ScanDocument? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        background = SwStyle.card(context, 18f)
        setPadding((10 * d).toInt(), (10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
        isClickable = true
        isFocusable = true
        SwMotion.press(this)

        thumbFrame = FrameLayout(context).apply {
            background = SwStyle.rounded(context, SwStyle.color(context, R.color.sw_paper_thumb), radiusDp = 9f)
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
        pageBadge = SwStyle.text(context, 10f, Color.WHITE, bold = true).apply {
            background = SwStyle.rounded(context, 0xCC0E1316.toInt(), radiusDp = 5f)
            setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
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
            LayoutParams(SwStyle.dp(context, 54f), SwStyle.dp(context, 70f)).apply {
                marginEnd = (14 * d).toInt()
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
        lockBadge = SwStyle.text(context, 11f, SwStyle.color(context, R.color.gold_pro_primary), bold = true).apply {
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

        metaView = SwStyle.text(context, 12.5f, SwStyle.color(context, R.color.text_hologram_dim)).apply {
            id = R.id.swDocMeta
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(
            metaView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (4 * d).toInt() }
        )

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
        categoryView = SwStyle.text(context, 11f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
            id = R.id.swDocCategory
            minimumHeight = (14 * d).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryRow.addView(categoryView)
        info.addView(
            categoryRow,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (6 * d).toInt() }
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
        onMore: () -> Unit
    ) {
        doc = document
        nameView.text = document.name
        metaView.text = meta
        lockBadge.isVisible = document.isLocked
        lockIcon.isVisible = document.isLocked
        alpha = if (document.isLocked) 0.92f else 1f
        pageBadge.text = document.pageCount.toString()
        pageBadge.isVisible = document.pageCount > 0
        categoryView.text = CategoryRules.label(document.category)
        categoryDot.background = SwStyle.rounded(
            context, CategoryStyle.color(document.category), radiusDp = 3f
        )
        shareButton.setOnClickListener { onShare() }
        moreButton.setOnClickListener { onMore() }
        shareButton.isVisible = true
        moreButton.isVisible = true
        selectionBadge.isVisible = false

        val firstPage = document.pageFiles.firstOrNull()
        if (firstPage != null && firstPage.exists() && !document.isLocked) {
            thumbView.imageTintList = null
            runCatching {
                com.scanwhirl.app.ui.pagegrid.PageThumbLoader.load(
                    firstPage, 320, com.scanwhirl.app.util.ScanFilter.ORIGINAL, thumbView
                )
            }
        } else {
            thumbView.setImageDrawable(null)
            thumbView.setBackgroundColor(
                SwStyle.color(context, if (document.isLocked) R.color.sw_thumb_locked else R.color.sw_paper_thumb)
            )
        }
    }

    /**
     * v2 UI: 03_tools_hub — compact row for the kit document chooser: 40×52
     * thumbnail, one-line name and meta, lock badge, no category or buttons.
     */
    fun asCompact(): DocCard = apply {
        val d = resources.displayMetrics.density
        setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        thumbFrame.layoutParams = LayoutParams((40 * d).toInt(), (52 * d).toInt())
            .apply { marginEnd = (12 * d).toInt() }
        categoryRow.visibility = View.GONE
        actions.visibility = View.GONE
        selectionBadge.visibility = View.GONE
        nameView.maxLines = 2
        metaView.maxLines = 2
    }

    /**
     * v2 UI: 01_home_A — selection mode: the check badge appears only while the
     * library is selecting, and the per-card Share/⋯ give way to the action bar.
     */
    fun setSelection(active: Boolean, checked: Boolean) {
        selectionBadge.isVisible = active && checked
        shareButton.isVisible = !active
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
}
