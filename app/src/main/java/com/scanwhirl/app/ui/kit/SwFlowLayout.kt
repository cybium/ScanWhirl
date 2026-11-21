package com.scanwhirl.app.ui.kit

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup

/**
 * A small wrapping row for kit controls. Each child keeps its natural width; when the next
 * child would overrun the row it moves to a new line instead of being squeezed until its
 * label splits a word. Used where a fixed number of weighted columns would force a mid-word
 * break at 200 % type (OCR chips, the Document viewer's page-selection actions).
 *
 * Positions are computed once in [onMeasure] and reused in [onLayout], so a child can never
 * land somewhere its label was not measured for.
 */
class SwFlowLayout(
    context: Context,
    private val horizontalGapDp: Float = 8f,
    private val verticalGapDp: Float = 8f
) : ViewGroup(context) {

    private data class Slot(val view: View, val x: Int, val y: Int)
    private var slots: List<Slot> = emptyList()

    private val gap: Int
        get() = (horizontalGapDp * resources.displayMetrics.density).toInt()
    private val rowGap: Int
        get() = (verticalGapDp * resources.displayMetrics.density).toInt()

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val avail = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val placed = mutableListOf<Slot>()
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            // AT_MOST the whole row: a child is as wide as its label needs, never wider.
            child.measure(
                MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val needed = child.measuredWidth + if (x == 0) 0 else gap
            if (x > 0 && x + needed > avail) {
                y += rowHeight + rowGap
                x = 0
                rowHeight = 0
            }
            if (x > 0) x += gap
            placed += Slot(child, x, y)
            x += child.measuredWidth
            widest = maxOf(widest, x)
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        y += rowHeight
        slots = placed
        val width = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthSpec)
        } else {
            widest + paddingLeft + paddingRight
        }
        setMeasuredDimension(width, y + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (slot in slots) {
            slot.view.layout(
                paddingLeft + slot.x, paddingTop + slot.y,
                paddingLeft + slot.x + slot.view.measuredWidth,
                paddingTop + slot.y + slot.view.measuredHeight
            )
        }
    }
}
