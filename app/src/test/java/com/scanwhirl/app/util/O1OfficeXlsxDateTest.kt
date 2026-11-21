package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * O1 office honesty: splitCell must never cut a DD.MM.YYYY (or DD/MM/YYYY, DD-MM-YYYY) date into
 * two columns, and an Indian grouped amount must stay one numeric cell.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class O1OfficeXlsxDateTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a dotted date stays one cell instead of splitting into number and fraction`() {
        assertEquals(listOf("12.05.2025"), XlsxWriter.splitCell("12.05.2025"))
        assertEquals(listOf("Date: 12.05.2025"), XlsxWriter.splitCell("Date: 12.05.2025"))
        assertEquals(listOf("From 12/05/2025 to 19/05/2025"), XlsxWriter.splitCell("From 12/05/2025 to 19/05/2025"))
        assertEquals(listOf("12-05-2025"), XlsxWriter.splitCell("12-05-2025"))
    }

    @Test
    fun `a written workbook keeps the date intact and the Indian amount numeric`() {
        val file = File(app.cacheDir, "o1_dates_${System.nanoTime()}.xlsx")
        XlsxWriter.write(
            file,
            listOf(
                XlsxWriter.Sheet(
                    "Page 1",
                    listOf(
                        listOf("Date", "Amount"),
                        listOf("12.05.2025", "Total 1,23,456.00"),
                        listOf("19/05/2025", "₹4,560")
                    )
                )
            )
        )
        val sheet = ZipFile(file).use { zip ->
            zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        }
        assertTrue(sheet, sheet.contains("<t xml:space=\"preserve\">12.05.2025</t>"))
        assertTrue(sheet, sheet.contains("<t xml:space=\"preserve\">19/05/2025</t>"))
        assertFalse(sheet, sheet.contains(">12.05<"))
        assertFalse(sheet, sheet.contains(">.2025<"))
        assertTrue("Indian amount stays one number", sheet.contains("<v>123456</v>"))
        assertTrue("rupee amount stays numeric", sheet.contains("<v>4560</v>"))
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }

    private fun assertFalse(message: String, condition: Boolean) {
        org.junit.Assert.assertFalse(message, condition)
    }
}
