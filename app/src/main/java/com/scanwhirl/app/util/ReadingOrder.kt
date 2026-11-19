package com.scanwhirl.app.util

import kotlin.math.max
import kotlin.math.min

/**
 * T38: reading order for one page's recognised lines, used by the Word text export.
 *
 * QA 2026-11-19 (case 5 FAIL, case 8 PARTIAL): a two-column page and a page with a narrow
 * sidebar exported to Word with the columns interleaved line by line, so the document read as
 * nonsense. The text itself was correct; only the order was wrong.
 *
 * The historical [OcrLayout] cut cannot split a page whose full-width title (or subtitle, or
 * table rule) crosses the gutter, so such a page fell back to row order and mixed the columns.
 * This function uses the same stored geometry but looks for a **vertical gutter that ordinary
 * body lines do not cross**: a title, a wide subheading, a rule or a footer may cross it and
 * keeps its own vertical position, while every other line must sit entirely on one side. The
 * page is then read left column top to bottom, right column top to bottom, with the crossing
 * lines emitted where they are on the page (a title first, a footer last).
 *
 * T47 (phone FAIL on the public FynePDF two-column sample): the earlier candidate search only
 * accepted a gutter that no *narrow* line crossed at all, so the sample's footer - which starts
 * in the left column and runs a little past the gutter - hid the gutter and the page still
 * exported interleaved. A gutter is now found from the line edges themselves and tolerates a
 * few crossing lines; the gates that reject tables, forms and single-column pages are unchanged.
 *
 * It is deliberately conservative: when no confident gutter exists (a form with two short
 * blocks, a table of cells, a page with one column, missing or degenerate boxes) the input order
 * is returned unchanged, so an export can never read worse than it did before. Pure Kotlin: no
 * Android dependency, unit-tested with synthetic boxes.
 */
object ReadingOrder {

    /** One recognised line: its text and its box in page-pixel coordinates. */
    data class LineBox(val text: String, val box: OcrBox)

    /** Smallest gutter (page px) that can separate two columns. */
    const val MIN_GUTTER = 24

    /** Gutter as a fraction of the page's text width. */
    const val GUTTER_FRACTION = 0.03

    /** A line this wide (fraction of the text width) may cross the gutter: title, footer, rule. */
    const val WIDE_FRACTION = 0.8

    /** The taller column must cover this much of the text block's height. */
    const val COLUMN_COVERAGE = 0.5

    /** The shorter column must cover this much of it (a sidebar covers less than the main text). */
    const val MIN_COLUMN_COVERAGE = 0.25

    /** Fewer lines than this on a side is not a column. */
    const val MIN_LINES_PER_COLUMN = 3

    /**
     * Median line width, as a fraction of that side's width. Two columns of text fill their
     * column; table cells are short values and fail here, which keeps a table in row order.
     */
    const val MIN_MEDIAN_WIDTH_FRACTION = 0.5

    /**
     * T47: lines that may still cross a gutter before it stops being a confident column break.
     * A title, a horizontal rule, a table heading and a footer do cross it, and they keep
     * their own place on the page. A line only counts as crossing when it really spans the
     * gutter: the real two-column sample has a footer that starts in the left column and runs
     * a little way past the gutter, which is neither a body line nor a full-width banner.
     */
    const val MIN_CROSSING_LINES = 3

    /**
     * [lines] in reading order, or [lines] itself when the page has no confident gutter. The
     * result always holds exactly the same lines: nothing is dropped, duplicated or rewritten.
     */
    fun order(lines: List<LineBox>): List<LineBox> {
        val gutter = detect(lines) ?: return lines
        val crossing = gutter.crossing.sortedWith(compareBy({ it.box.centerY }, { it.box.left }))
        fun band(line: LineBox): Int = crossing.count { it.box.centerY <= line.box.centerY }

        val leftBands = List(crossing.size + 1) { mutableListOf<LineBox>() }
        val rightBands = List(crossing.size + 1) { mutableListOf<LineBox>() }
        for (line in gutter.left) leftBands[band(line)] += line
        for (line in gutter.right) rightBands[band(line)] += line

        val byTop = compareBy<LineBox>({ it.box.top }, { it.box.left })
        val out = ArrayList<LineBox>(lines.size)
        for (index in 0..crossing.size) {
            out += leftBands[index].sortedWith(byTop)
            out += rightBands[index].sortedWith(byTop)
            if (index < crossing.size) out += crossing[index]
        }
        return out
    }

    /**
     * The paragraphs a Word export writes for [lines]: one line per paragraph, with a blank
     * paragraph where the vertical gap is clearly larger than the page's line spacing (the same
     * rule [OcrMerge.assemble] uses for the stored text) and where the reading order steps from
     * one column back to the left one, so the two columns do not run into each other.
     */
    fun toTextLines(lines: List<LineBox>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val heights = lines.map { max(1, it.box.height) }.sorted()
        val typical = max(1, heights[heights.size / 2])
        val threshold = max(typical + 1, (typical * 1.6f).toInt())
        val textWidth = lines.maxOf { it.box.right } - lines.minOf { it.box.left }
        val gutter = max(MIN_GUTTER, (textWidth * GUTTER_FRACTION).toInt())
        val out = ArrayList<String>(lines.size + 4)
        var previous: LineBox? = null
        for (line in lines) {
            val last = previous
            if (last != null) {
                val gap = line.box.top - last.box.bottom
                val startsNewColumn = line.box.left - last.box.left >= gutter
                if (gap > threshold || startsNewColumn) out += ""
            }
            out += line.text
            previous = line
        }
        return out
    }

    /** A page's division into two columns plus the wide lines that cross the gutter. */
    private data class Gutter(
        val left: List<LineBox>,
        val right: List<LineBox>,
        val crossing: List<LineBox>
    )

    private fun detect(lines: List<LineBox>): Gutter? {
        if (lines.size < 2 * MIN_LINES_PER_COLUMN) return null
        // A box we cannot reason about (a page with no recognised geometry) means no reordering.
        if (lines.any { it.box.width <= 0 || it.box.height <= 0 }) return null
        val textLeft = lines.minOf { it.box.left }
        val textRight = lines.maxOf { it.box.right }
        val textWidth = textRight - textLeft
        if (textWidth <= 0) return null
        val minGutter = max(MIN_GUTTER, (textWidth * GUTTER_FRACTION).toInt())

        // Only ordinary lines block a gutter position: a wide line may be a title or a footer.
        val blocking = lines.filter { it.box.width < WIDE_FRACTION * textWidth }
        if (blocking.size < MIN_LINES_PER_COLUMN) return null
        // T47: title, rule, table heading, footer - a few lines may cross the gutter and keep
        // their own height. They are not body lines, so they must not hide the gutter.
        val maxCrossing = max(MIN_CROSSING_LINES, lines.size / 8)

        var best: Gutter? = null
        var bestScore = -1
        var bestWidth = -1
        // T47 (phone FAIL, real sample-two-column.pdf): the old candidate search swept the
        // line span left to right, so a single line that started in the left column and
        // reached a little past the gutter (the sample's "Sample file from FynePDF..." footer)
        // carried that sweep over the gutter and no band was ever found - the page fell back
        // to the interleaved recogniser order. A gutter can only start where a line of the
        // right column starts, so every distinct line left edge is the candidate, and the band
        // ends at the last line that ends before it.
        for (b in lines.map { it.box.left }.distinct().sorted()) {
            val a = lines.filter { it.box.right < b }.maxOfOrNull { it.box.right } ?: continue
            if (b - a < minGutter) continue

            val candidate = partition(lines, a, b)
            val left = candidate.left
            val right = candidate.right
            if (left.size < MIN_LINES_PER_COLUMN || right.size < MIN_LINES_PER_COLUMN) continue
            if (candidate.crossing.size > maxCrossing) continue

            val leftSpan = left.maxOf { it.box.bottom } - left.minOf { it.box.top }
            val rightSpan = right.maxOf { it.box.bottom } - right.minOf { it.box.top }
            // The two columns together are the body: a crossing title or footer is not part of
            // it, so it cannot stretch the height the columns are measured against.
            val bodyTop = min(left.minOf { it.box.top }, right.minOf { it.box.top })
            val bodyBottom = max(left.maxOf { it.box.bottom }, right.maxOf { it.box.bottom })
            val bodyHeight = bodyBottom - bodyTop
            if (bodyHeight > 0) {
                if (max(leftSpan, rightSpan) < bodyHeight * COLUMN_COVERAGE) continue
                if (min(leftSpan, rightSpan) < bodyHeight * MIN_COLUMN_COVERAGE) continue
            }
            if (medianWidth(left) < max(1, a - textLeft) * MIN_MEDIAN_WIDTH_FRACTION) continue
            if (medianWidth(right) < max(1, textRight - b) * MIN_MEDIAN_WIDTH_FRACTION) continue

            val score = min(left.size, right.size)
            if (score > bestScore || (score == bestScore && b - a > bestWidth)) {
                best = candidate
                bestScore = score
                bestWidth = b - a
            }
        }
        return best
    }

    private fun medianWidth(lines: List<LineBox>): Int =
        lines.map { it.box.width }.sorted().let { it[it.size / 2] }

    /** Splits every line into the two columns or the crossing set; nothing is left out. */
    private fun partition(lines: List<LineBox>, a: Int, b: Int): Gutter {
        val left = ArrayList<LineBox>()
        val right = ArrayList<LineBox>()
        val crossing = ArrayList<LineBox>()
        val middle = (a + b) / 2
        for (line in lines) {
            when {
                line.box.left < b && line.box.right > a -> crossing += line
                line.box.right <= a -> left += line
                line.box.left >= b -> right += line
                line.box.centerX <= middle -> left += line
                else -> right += line
            }
        }
        return Gutter(left, right, crossing)
    }
}
