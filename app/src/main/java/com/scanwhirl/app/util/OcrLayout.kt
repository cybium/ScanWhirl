package com.scanwhirl.app.util

/** An axis-aligned box in page pixels (left/top inclusive, right/bottom exclusive). */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

/** A box plus the thing it positions (a line or a block). */
data class OcrUnit<T>(val box: OcrBox, val value: T)

/**
 * Reading order for recognised text (design: text recognition quality).
 *
 * ML Kit returns blocks and lines in no guaranteed order, and a page can hold two text
 * columns or a two-page book spread. This is a recursive XY-cut: a clean vertical gutter
 * splits the page into columns (finish the left column, then the right one, which also puts
 * the left page of a spread before the right one); without a gutter, units are grouped into
 * rows and read top-to-bottom, left-to-right inside a row. Every decision is geometric, so
 * it is unit-tested with synthetic boxes and never needs ML Kit.
 */
object OcrLayout {

    /** Smallest gutter considered a column break. */
    const val MIN_GUTTER = 24

    /** Gutter as a fraction of the page width. */
    const val GUTTER_FRACTION = 0.03

    /**
     * Orders [units] for [pageWidth] x [pageHeight]. [rotationDegrees] is how far the content
     * is turned clockwise in the stored image (0, 90, 180, 270); boxes are mapped upright
     * first, so a sideways scan reads in its own text direction.
     */
    fun <T> order(
        units: List<OcrUnit<T>>,
        pageWidth: Int,
        pageHeight: Int,
        rotationDegrees: Int = 0
    ): List<OcrUnit<T>> {
        if (units.size <= 1) return units
        val upright = if (rotationDegrees % 360 == 0) {
            units
        } else {
            units.map { OcrUnit(unrotate(it.box, pageWidth, pageHeight, rotationDegrees), it.value) }
        }
        return orderUpright(upright, pageWidth)
    }

    /** Convenience for plain lines: returns their values in reading order. */
    fun <T> valuesInReadingOrder(
        units: List<OcrUnit<T>>,
        pageWidth: Int,
        pageHeight: Int,
        rotationDegrees: Int = 0
    ): List<T> = order(units, pageWidth, pageHeight, rotationDegrees).map { it.value }

    private fun <T> orderUpright(units: List<OcrUnit<T>>, pageWidth: Int): List<OcrUnit<T>> {
        if (units.size <= 1) return units
        val gutter = maxOf(MIN_GUTTER, (pageWidth * GUTTER_FRACTION).toInt())

        // A vertical cut is only clean when every unit is entirely on one side of the gap.
        val byLeft = units.sortedWith(compareBy({ it.box.left }, { it.box.top }))
        var maxRight = byLeft.first().box.right
        var bestGap = 0
        var cut = -1
        for (i in 1 until byLeft.size) {
            val gap = byLeft[i].box.left - maxRight
            if (gap >= gutter && gap > bestGap) {
                bestGap = gap
                cut = i
            }
            maxRight = maxOf(maxRight, byLeft[i].box.right)
        }
        if (cut > 0) {
            return orderUpright(byLeft.subList(0, cut), pageWidth) +
                orderUpright(byLeft.subList(cut, byLeft.size), pageWidth)
        }

        // One column: rows of vertically overlapping units, top to bottom, left to right.
        val byTop = units.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val rows = ArrayList<MutableList<OcrUnit<T>>>()
        var rowBottom = Int.MIN_VALUE
        for (unit in byTop) {
            if (rows.isEmpty() || unit.box.top > rowBottom) {
                rows += mutableListOf(unit)
                rowBottom = unit.box.bottom
            } else {
                rows.last() += unit
                rowBottom = maxOf(rowBottom, unit.box.bottom)
            }
        }
        return rows.flatMap { row -> row.sortedWith(compareBy({ it.box.left }, { it.box.top })) }
    }

    /**
     * Maps a box seen in a [rotationDegrees]-clockwise image back to upright page coordinates.
     * For 90 degrees: upright x is the old y, upright y is (pageWidth - old x).
     */
    fun unrotate(box: OcrBox, pageWidth: Int, pageHeight: Int, rotationDegrees: Int): OcrBox =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> OcrBox(
                left = box.top.coerceAtLeast(0),
                top = (pageWidth - box.right).coerceAtLeast(0),
                right = box.bottom.coerceAtLeast(0),
                bottom = (pageWidth - box.left).coerceAtLeast(0)
            )
            180 -> OcrBox(
                left = (pageWidth - box.right).coerceAtLeast(0),
                top = (pageHeight - box.bottom).coerceAtLeast(0),
                right = (pageWidth - box.left).coerceAtLeast(0),
                bottom = (pageHeight - box.top).coerceAtLeast(0)
            )
            270 -> OcrBox(
                left = (pageHeight - box.bottom).coerceAtLeast(0),
                top = box.left.coerceAtLeast(0),
                right = (pageHeight - box.top).coerceAtLeast(0),
                bottom = box.right.coerceAtLeast(0)
            )
            else -> box
        }

    /** True when two boxes describe the same visual line (used to merge two recognizers). */
    fun sameLine(a: OcrBox, b: OcrBox): Boolean {
        val verticalOverlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val horizontalOverlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (verticalOverlap <= 0 || horizontalOverlap <= 0) return false
        val minHeight = minOf(a.height, b.height).coerceAtLeast(1)
        val minWidth = minOf(a.width, b.width).coerceAtLeast(1)
        return verticalOverlap * 2 >= minHeight && horizontalOverlap * 2 >= minWidth
    }
}
