package com.scanwhirl.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone

/**
 * A two-part header row — a flexible [labelId] text on the start side, an
 * "actions" group on the end side — that reflows instead of clipping.
 *
 * Normally it is a plain horizontal row (the label takes the leftover width, the
 * actions wrap their content), so layouts at normal font sizes are unchanged. When
 * the label cannot show its text in its own box — a word would be split, or a line
 * would be ellipsized (large system font, narrow screen) — the row measures again
 * stacked: the label gets the full width on top and the actions sit on their own row
 * underneath, end-aligned. Nothing is shrunk and nothing is hidden.
 *
 * The decision is made from real measurement, not from a font-scale threshold, so it
 * also covers narrow screens and long translated strings.
 */
class AdaptiveHeaderRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Resource id of the flexible label child; the last child is the actions group. */
    var labelId: Int = View.NO_ID

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val label = if (labelId != View.NO_ID) findViewById<TextView>(labelId) else null
        val actions = if (childCount >= 2) getChildAt(childCount - 1) else null
        if (label == null || actions == null || label.isGone) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        stack(label, actions, false)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!label.showsAllText()) {
            stack(label, actions, true)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun stack(label: TextView, actions: View, stacked: Boolean) {
        val wanted = if (stacked) VERTICAL else HORIZONTAL
        if (orientation != wanted) orientation = wanted
        // Mutate the params in place — onMeasure must not call setLayoutParams.
        (label.layoutParams as LayoutParams).apply {
            width = if (stacked) LayoutParams.MATCH_PARENT else 0
            weight = if (stacked) 0f else 1f
        }
        (actions.layoutParams as LayoutParams).gravity =
            if (stacked) android.view.Gravity.END else android.view.Gravity.NO_GRAVITY
    }

    /** True when [this] shows its whole text: no split word, no ellipsized line. */
    private fun TextView.showsAllText(): Boolean {
        val l = layout ?: return true
        val box = measuredWidth - totalPaddingLeft - totalPaddingRight
        if (box <= 0) return false
        for (line in 0 until l.lineCount) if (l.getEllipsisCount(line) > 0) return false
        val longest = text.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .maxOfOrNull { paint.measureText(it) } ?: 0f
        return longest <= box + 0.5f
    }
}
