package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2 UI: 03_tools_hub — table detection from OCR geometry: columns by horizontal gaps, rows by
 * baseline tolerance, at least two columns and three rows.
 */
class TableDetectTest {

    private fun line(text: String, left: Int, top: Int, width: Int = 120, height: Int = 40) =
        OcrLine(text, left, top, left + width, top + height)

    private fun page(vararg lines: OcrLine, width: Int = 1200, height: Int = 1600) =
        OcrPage(width, height, lines.toList())

    private fun invoice() = page(
        line("Item", 50, 100), line("Qty", 400, 100), line("Rate", 650, 100), line("Amount", 900, 100),
        line("Pen", 50, 170), line("2", 400, 170), line("₹10", 650, 170), line("₹20", 900, 170),
        line("Book", 50, 240), line("1", 400, 240), line("₹250", 650, 240), line("₹250", 900, 240),
        line("Bag", 50, 310), line("3", 400, 310), line("₹500", 650, 310), line("₹1,500", 900, 310)
    )

    @Test
    fun `an invoice with four columns is a table`() {
        val table = TableDetect.detect(invoice())
        assertNotNull(table)
        assertEquals(4, table!!.rows.size)
        assertEquals(4, table.columns)
        assertEquals(listOf("Item", "Qty", "Rate", "Amount"), table.rows[0])
        assertEquals(listOf("Pen", "2", "₹10", "₹20"), table.rows[1])
        assertEquals("₹1,500", table.rows[3][3])
    }

    @Test
    fun `a two column list with three rows is a table`() {
        val table = TableDetect.detect(
            page(
                line("Name", 50, 100), line("Phone", 420, 100),
                line("Asha", 50, 170), line("98765 43210", 420, 170),
                line("Ravi", 50, 240), line("91234 56780", 420, 240)
            )
        )
        assertNotNull(table)
        assertEquals(2, table!!.columns)
        assertEquals(listOf("Ravi", "91234 56780"), table.rows[2])
    }

    @Test
    fun `plain paragraphs are not a table`() {
        val paragraphs = page(
            line("This is the first line of a letter.", 50, 100, width = 500),
            line("This is the second line, also plain text.", 50, 170, width = 520),
            line("A third line follows without any columns.", 50, 240, width = 480),
            line("And the last line of the paragraph.", 50, 310, width = 420)
        )
        assertNull(TableDetect.detect(paragraphs))
    }

    @Test
    fun `two rows or one column is not a table`() {
        // Two rows with two columns: below the three-row minimum.
        assertNull(
            TableDetect.detect(
                page(
                    line("Name", 50, 100), line("Phone", 420, 100),
                    line("Asha", 50, 170), line("98765 43210", 420, 170)
                )
            )
        )
        // Three rows, one column: a list, not a table.
        assertNull(
            TableDetect.detect(
                page(
                    line("one", 50, 100), line("two", 50, 170), line("three", 50, 240)
                )
            )
        )
    }

    @Test
    fun `rows with a single filled cell do not make a table`() {
        val mixed = page(
            line("Heading across the page", 50, 100, width = 500),
            line("Another heading", 50, 170, width = 450),
            line("Third heading line", 50, 240, width = 400),
            line("A", 50, 310), line("B", 420, 310)
        )
        assertNull(TableDetect.detect(mixed))
    }
}
