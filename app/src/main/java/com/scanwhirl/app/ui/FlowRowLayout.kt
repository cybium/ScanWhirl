package com.scanwhirl.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * F1-FIT: a dock row that reflows instead of squeezing its labels.
 *
 * Four or five actions spread evenly across one row read well at 100 % text.
 * At 200 % every cell is narrower than its longest word, and a MaterialButton
 * then breaks "Rotate" into "Rotat / e", clips "Share" to "Shar" or drops the
 * label of the fifth icon completely. Shrinking the type is not an option (the
 * owner raised the size on purpose), so the row has to wrap.
 *
 * This group measures every child at the width its own content asks for
 * (wrap-content, i.e. the width at which no label needs to break a word) and:
 *
 *  - keeps one evenly spread row **only when every child's natural width fits
 *    its equal share** — so 100 % looks exactly like the old weighted row;
 *  - otherwise wraps into balanced rows (2+2 rather than 3+1) and gives each
 *    child at least its natural width, so no label can be broken or clipped.
 *
 * Children keep their own margins; GONE children are ignored.
 */
class FlowRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** One measured child: its slot width (content + margins) and the index to lay out. */
    private class Slot(val index: Int, val width: Int)

    private var rows: List<List<Slot>> = emptyList()
    private var rowHeights: IntArray = IntArray(0)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = maxOf(0, widthSize - paddingLeft - paddingRight)

        val indices = ArrayList<Int>(childCount)
        val natural = HashMap<Int, Int>(childCount) // includes the child's horizontal margins
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            indices += i
            natural[i] = (child.measuredWidth + lp.leftMargin + lp.rightMargin)
                .coerceAtMost(available)
        }

        // MaterialButton reports the width its label wants on one line. A single
        // evenly spread row is only safe while every slot gets at least that width.
        val oneRowShare = if (indices.isEmpty()) 0 else available / indices.size
        val rowsPlan: List<List<Int>> = when {
            indices.isEmpty() -> emptyList()
            indices.all { natural.getValue(it) <= oneRowShare } -> listOf(indices)
            else -> wrapRows(indices, natural, available)
        }

        rows = rowsPlan.map { row ->
            val extra = if (row.isEmpty()) 0 else
                maxOf(0, available - row.sumOf { natural.getValue(it) }) / row.size
            row.map { Slot(it, natural.getValue(it) + extra) }
        }
        rowHeights = IntArray(rows.size)
        for ((r, row) in rows.withIndex()) {
            var tallest = 0
            for (slot in row) {
                val child = getChildAt(slot.index)
                val lp = child.layoutParams as MarginLayoutParams
                val content = maxOf(0, slot.width - lp.leftMargin - lp.rightMargin)
                child.measure(
                    MeasureSpec.makeMeasureSpec(content, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                tallest = maxOf(tallest, child.measuredHeight + lp.topMargin + lp.bottomMargin)
            }
            rowHeights[r] = tallest
        }

        val measuredHeight = rowHeights.sum() + paddingTop + paddingBottom
        val measuredWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            (rows.flatten().maxOfOrNull { it.width } ?: 0) + paddingLeft + paddingRight
        } else {
            widthSize
        }
        setMeasuredDimension(measuredWidth, resolveSize(measuredHeight, heightMeasureSpec))
    }

    /**
     * Greedy wrap, then an even re-balance when it keeps the same number of rows
     * (4 actions at 200 % should read 2+2, not 3+1).
     */
    private fun wrapRows(
        indices: List<Int>,
        natural: Map<Int, Int>,
        available: Int
    ): List<List<Int>> {
        val greedy = ArrayList<MutableList<Int>>()
        var row = ArrayList<Int>()
        var used = 0
        for (i in indices) {
            val w = natural.getValue(i)
            if (row.isNotEmpty() && used + w > available) {
                greedy += row
                row = ArrayList()
                used = 0
            }
            row += i
            used += w
        }
        if (row.isNotEmpty()) greedy += row

        val perRow = (indices.size + greedy.size - 1) / greedy.size
        val balanced = ArrayList<List<Int>>()
        var start = 0
        while (start < indices.size) {
            val chunk = indices.subList(start, minOf(indices.size, start + perRow))
            if (chunk.sumOf { natural.getValue(it) } > available) return greedy
            balanced += ArrayList(chunk)
            start += perRow
        }
        return if (balanced.size == greedy.size) balanced else greedy
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        for ((rowIndex, row) in rows.withIndex()) {
            var x = paddingLeft
            for (slot in row) {
                val child = getChildAt(slot.index)
                val lp = child.layoutParams as MarginLayoutParams
                val content = maxOf(0, slot.width - lp.leftMargin - lp.rightMargin)
                child.layout(
                    x + lp.leftMargin,
                    y + lp.topMargin,
                    x + lp.leftMargin + content,
                    y + lp.topMargin + child.measuredHeight
                )
                x += slot.width
            }
            y += rowHeights[rowIndex]
        }
    }

    /** Rows currently laid out — used by tests and by screens that reserve space for this dock. */
    val rowCount: Int get() = rows.size

    /** Index of the row [child] sits in, or -1. */
    fun rowOf(child: View): Int = rows.indexOfFirst { row -> row.any { it.index == indexOfChild(child) } }
}
