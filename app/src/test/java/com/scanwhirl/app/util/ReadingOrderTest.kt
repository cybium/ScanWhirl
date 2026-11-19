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

    /**
     * T47: the phone export of the public FynePDF sample still interleaved the columns because
     * its footer starts in the left column and runs a little past the gutter (620 of 880 px,
     * so not a "wide" line). The old band search carried that one line over the gutter and
     * found no gutter at all. This is the same shape, at unit-test size.
     */
    @Test
    fun `a footer that starts left and crosses the gutter does not hide it`() {
        val title = line("Two columns", 60, 60, 880)
        val left = column("left", 60, 410)
        val right = column("right", 530, 410)
        val footer = line("Sample file from the public sample library. Free for testing.", 60, 1300, 620)
        val lines = listOf(title) + left.zip(right).flatMap { (l, r) -> listOf(l, r) } + footer

        val ordered = texts(ReadingOrder.order(lines))
        assertEquals("the title stays first", title.text, ordered.first())
        assertEquals("the footer stays last", footer.text, ordered.last())
        assertEquals("the columns are read one after the other", texts(left) + texts(right), ordered.drop(1).dropLast(1))
    }

    // ------------------------------------------------ captured public sample (T47)

    /**
     * The geometry the phone stored for page [page] of the public CC0 FynePDF two-column
     * sample (`qa/document-corpus-public-2026-11-19/sample-two-column.pdf`), copied out of a
     * real import with `run-as` on 2026-11-19 (`qa/export-quality-2026-11-19/device-ocr/`).
     * It contains only that sample's own public text.
     */
    private fun capturedGeometry(page: String): List<ReadingOrder.LineBox> {
        val raw = javaClass.getResourceAsStream("/t38/sample-two-column.ocr_geo.txt")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val record = raw.lineSequence().first { it.startsWith("$page\t") }
        val payload = String(
            java.util.Base64.getDecoder().decode(record.substringAfter('\t')),
            Charsets.UTF_8
        )
        return payload.lineSequence()
            .drop(1)
            .filter { it.contains('\t') && !it.startsWith("#text-sha256") }
            .map { row ->
                val box = row.substringBefore('\t').split(' ').map { it.toInt() }
                ReadingOrder.LineBox(row.substringAfter('\t'), OcrBox(box[0], box[1], box[2], box[3]))
            }
            .toList()
    }

    @Test
    fun `the captured public two-column page reads the left column before the right`() {
        val lines = capturedGeometry("page_000.jpg")
        assertEquals("the captured page has 79 recognised lines", 79, lines.size)

        val ordered = ReadingOrder.order(lines)
        assertEquals("nothing is dropped or duplicated", lines.toSet(), ordered.toSet())
        assertEquals("the cross-column title stays first", "The PDF format in ten short parts", ordered.first().text)
        assertEquals(
            "the footer stays last",
            "Sample file from FynePDF. Free to use for testing. fynepdf.com/samples",
            ordered.last().text
        )

        // The phone exported these two lines next to each other, one from each column.
        val position = ordered.withIndex().associate { (index, line) -> line.text to index }
        val leftColumn = position.getValue("PDF stands for Portable Document Format. Adobe")
        val rightColumn = position.getValue("and screenshots are usually kept in a lossless")
        assertTrue("the left column comes first (left at $leftColumn, right at $rightColumn)", leftColumn < rightColumn)

        // Every line that ends before the gutter is read before any line that starts after it.
        var rightBegan = false
        for (line in ordered) {
            if (line.box.left >= 643) rightBegan = true
            if (line.box.right <= 595 && rightBegan) {
                throw AssertionError("left column line after the right column began: ${line.text}")
            }
        }
    }

    @Test
    fun `the captured public sample's single column page is returned unchanged`() {
        val lines = capturedGeometry("page_001.jpg")
        assertEquals("the captured page has 38 recognised lines", 38, lines.size)
        assertEquals("a one-column page keeps the stored order", lines, ReadingOrder.order(lines))
    }
}
