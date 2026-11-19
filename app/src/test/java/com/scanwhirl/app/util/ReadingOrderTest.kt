package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T38: reading order for the Word text export. A page with a vertical gutter (two text columns,
 * or a main column beside a narrow sidebar) must be read column by column, while a full-width
 * title or footer keeps its place on the page. Every page without a confident gutter keeps the
 * order the caller handed in, so the export can never read worse than before.
 */
class ReadingOrderTest {

    private fun line(text: String, left: Int, top: Int, width: Int = 410, height: Int = 40) =
        ReadingOrder.LineBox(text, OcrBox(left, top, left + width, top + height))

    /** Six line boxes in a column starting at [left], [width] px wide, 50 px apart. */
    private fun column(prefix: String, left: Int, width: Int, count: Int = 6) =
        (0 until count).map { i -> line("$prefix ${i + 1}", left, 140 + 50 * i, width) }

    private fun texts(lines: List<ReadingOrder.LineBox>) = lines.map { it.text }

    @Test
    fun `a two-column page reads the whole left column before the right`() {
        val left = column("left", 60, 410)
        val right = column("right", 530, 410)
        // What the recogniser stored: both columns row by row.
        val interleaved = left.zip(right).flatMap { (l, r) -> listOf(l, r) }

        assertEquals(texts(left) + texts(right), texts(ReadingOrder.order(interleaved)))
    }

    @Test
    fun `a title that spans both columns stays first`() {
        val title = line("Two columns, one title", 60, 60, 880)
        val left = column("left", 60, 410)
        val right = column("right", 530, 410)
        val lines = listOf(title) + left.zip(right).flatMap { (l, r) -> listOf(l, r) }

        val ordered = texts(ReadingOrder.order(lines))
        assertEquals(title.text, ordered.first())
        assertEquals(texts(left) + texts(right), ordered.drop(1))
        assertEquals("nothing is lost", lines.size, ordered.size)
        assertEquals("nothing is duplicated", lines.map { it.text }.toSet(), ordered.toSet())
    }

    @Test
    fun `a full-width footer stays last`() {
        val footer = line("Page 2 of 2", 60, 1300, 880)
        val left = column("left", 60, 410)
        val right = column("right", 530, 410)
        val lines = left.zip(right).flatMap { (l, r) -> listOf(l, r) } + footer

        val ordered = texts(ReadingOrder.order(lines))
        assertEquals(footer.text, ordered.last())
        assertEquals(texts(left) + texts(right), ordered.dropLast(1))
    }

    @Test
    fun `a spanning line in the middle keeps its vertical position`() {
        val title = line("Report", 60, 60, 880)
        val left = column("left", 60, 410)
        val right = column("right", 530, 410)
        val tableHeading = line("Table A-3. Income by household", 60, 600, 880)
        val leftBelow = listOf(line("left below 1", 60, 660), line("left below 2", 60, 710))
        val rightBelow = listOf(line("right below 1", 530, 660), line("right below 2", 530, 710))
        val lines = listOf(title) + left.zip(right).flatMap { (l, r) -> listOf(l, r) } +
            listOf(tableHeading) + leftBelow + rightBelow

        val ordered = texts(ReadingOrder.order(lines))
        assertTrue("title first", ordered.indexOf(title.text) < ordered.indexOf(left.first().text))
        val headingAt = ordered.indexOf(tableHeading.text)
        assertTrue("everything above the heading comes before it", ordered.indexOf(right.last().text) < headingAt)
        assertTrue("the left column below the heading follows it", headingAt < ordered.indexOf(leftBelow.first().text))
        assertTrue("the right column below it comes last", ordered.indexOf(leftBelow.last().text) < ordered.indexOf(rightBelow.first().text))
    }

    @Test
    fun `a narrow sidebar is read after the main column`() {
        val title = line("Income in the United States: 2024", 60, 60, 880)
        val main = column("main", 60, 620, count = 5)
        val sidebar = (0 until 3).map { i -> line("sidebar ${i + 1}", 790, 140 + 50 * i, 150) }
        val lines = listOf(title) + main.zip(sidebar).flatMap { (m, s) -> listOf(m, s) } + main.drop(3)

        val ordered = texts(ReadingOrder.order(lines))
        assertEquals(title.text, ordered.first())
        assertEquals(
            "the main column is not split by the sidebar",
            texts(main) + texts(sidebar),
            ordered.drop(1)
        )
    }

    @Test
    fun `a single column page is returned unchanged`() {
        val lines = (0 until 8).map { i -> line("single ${i + 1}", 60, 60 + 60 * i, 880) }

        assertEquals(lines, ReadingOrder.order(lines))
    }

    @Test
    fun `a page with two short blocks side by side keeps the stored order`() {
        val lines = listOf(
            line("Date: 12 May 2026", 60, 60, 300),
            line("Invoice 0001", 700, 60, 240),
            line("Balance due", 60, 110, 300),
            line("Rs 4,200", 700, 110, 240)
        )

        assertEquals(lines, ReadingOrder.order(lines))
    }

    @Test
    fun `two blocks that do not run alongside each other keep the stored order`() {
        val figure = (0 until 3).map { i -> line("figure ${i + 1}", 60, 60 + 50 * i, 410) }
        val note = (0 until 3).map { i -> line("note ${i + 1}", 530, 800 + 50 * i, 410) }
        val lines = figure + note

        assertEquals(lines, ReadingOrder.order(lines))
    }

    @Test
    fun `a table of short cells keeps the stored order`() {
        // Four columns of narrow cells: geometrically a gutter exists on both sides of column 1,
        // but the lines are cell values, not two columns of text. Reordering would separate a
        // value from its row, so this must fall back.
        val rows = (0 until 4).map { r ->
            listOf("Item", "Qty", "Rate", "Amount").mapIndexed { c, _ ->
                line("cell $r$c", 60 + 240 * c, 100 + 70 * r, 150)
            }
        }
        val lines = rows.flatten()

        assertEquals(lines, ReadingOrder.order(lines))
    }

    @Test
    fun `lines that are not valid boxes keep the stored order`() {
        val lines = listOf(
            line("left 1", 60, 140), line("right 1", 530, 140),
            line("left 2", 60, 190), line("right 2", 530, 190),
            line("left 3", 60, 240), line("right 3", 530, 240),
            OcrBox(60, 300, 60, 340).let { ReadingOrder.LineBox("empty", it) }
        )

        assertEquals(lines, ReadingOrder.order(lines))
    }

    @Test
    fun `paragraph breaks are kept and columns get one blank line between them`() {
        val title = line("Two columns", 60, 60, 880)
        // The left column has a paragraph gap in the middle; the right column does not.
        val left = listOf(140, 190, 240, 400, 450, 500).mapIndexed { i, top -> line("left ${i + 1}", 60, top) }
        val right = column("right", 530, 410)
        val ordered = ReadingOrder.order(
            listOf(title) + left.zip(right).flatMap { (l, r) -> listOf(l, r) }
        )

        val text = ReadingOrder.toTextLines(ordered)
        assertEquals("Two columns", text.first())
        assertEquals("a paragraph gap inside a column becomes a blank paragraph", "", text[text.indexOf("left 4") - 1])
        assertEquals("left 3", text[text.indexOf("left 4") - 2])
        assertEquals("a blank line between the columns", "", text[text.indexOf("right 1") - 1])
        assertEquals("the last left line is directly before that blank", "left 6", text[text.indexOf("right 1") - 2])
        assertEquals("every line is still there", 13, text.count { it.isNotBlank() })
    }
}
