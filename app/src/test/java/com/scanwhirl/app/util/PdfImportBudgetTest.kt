package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 UI: 03_tools_hub — the big-PDF pre-check: when to ask, when to refuse, and the page-range
 * parsing used by the "Choose pages" sheet.
 */
class PdfImportBudgetTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    @Test
    fun `more than fifty pages asks before importing`() {
        val plan = PdfImportBudget.plan(51, freeBytes = 10 * gb)
        assertTrue(plan.needsDecision)
        assertFalse(plan.tooLittleSpace)
        assertEquals(51L * PdfImportBudget.TYPICAL_PAGE_BYTES, plan.estimateBytes)
        assertFalse("a small PDF never asks", PdfImportBudget.plan(50, 10 * gb).needsDecision)
    }

    @Test
    fun `an estimate over a quarter of free space asks`() {
        val plan = PdfImportBudget.plan(10, freeBytes = 2 * gb, bytesPerPage = 100 * mb)
        assertEquals(1000 * mb, plan.estimateBytes)
        assertTrue("1 GB is more than a quarter of 2 GB", plan.needsDecision)
        assertFalse("1 GB stays free", plan.tooLittleSpace)
    }

    @Test
    fun `under five hundred MB left refuses before starting`() {
        val plan = PdfImportBudget.plan(1000, freeBytes = 1 * gb)
        assertEquals(1000L * PdfImportBudget.TYPICAL_PAGE_BYTES, plan.estimateBytes)
        assertTrue("1 GB - 600 MB leaves 424 MB", plan.tooLittleSpace)
        assertTrue(plan.needsDecision)

        val enough = PdfImportBudget.plan(1000, freeBytes = 5 * gb)
        assertFalse("5 GB - 600 MB keeps over 4 GB", enough.tooLittleSpace)
    }

    @Test
    fun `the estimate follows the import resolution`() {
        assertEquals(600_000L, PdfImportBudget.pageBytes(2048))
        assertEquals(450_000L, PdfImportBudget.pageBytes(1600))
        assertEquals(320_000L, PdfImportBudget.pageBytes(1024))
        val plan = PdfImportBudget.plan(100, freeBytes = 10 * gb, bytesPerPage = 1000)
        assertEquals(20_000L, plan.estimateFor(20))
    }

    @Test
    fun `page ranges parse to zero based indices`() {
        assertEquals(
            (0..19).toList() + 44,
            PdfImportBudget.indices("1-20, 45", pageCount = 100)
        )
        assertEquals(listOf(0, 1, 2), PdfImportBudget.indices("1-3", 3))
        assertEquals(listOf(7, 8, 9, 10, 11), PdfImportBudget.indices("8-", 12))
        assertNull(PdfImportBudget.indices("", 10))
        assertNull(PdfImportBudget.indices("0-2", 10))
        assertNull(PdfImportBudget.indices("9-12", 10))
    }
}
