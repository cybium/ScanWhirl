package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyword guessing that puts scans onto the globe's five rings. */
class CategoryRulesTest {

    @Test
    fun `receipts and bills land in Receipts`() {
        assertEquals(CategoryRules.RECEIPTS, CategoryRules.classify("Receipt - Amazon"))
        assertEquals(CategoryRules.RECEIPTS, CategoryRules.classify("Electricity Bill Nov"))
        assertEquals(CategoryRules.RECEIPTS, CategoryRules.classify("invoice 2026-10"))
        assertEquals(CategoryRules.RECEIPTS, CategoryRules.classify("Paystub September"))
    }

    @Test
    fun `passports and cards land in IDs`() {
        assertEquals(CategoryRules.IDS, CategoryRules.classify("Passport Scan"))
        assertEquals(CategoryRules.IDS, CategoryRules.classify("Student ID card"))
        assertEquals(CategoryRules.IDS, CategoryRules.classify("Driving License Front"))
    }

    @Test
    fun `applications and forms land in Forms`() {
        assertEquals(CategoryRules.FORMS, CategoryRules.classify("Application form"))
        assertEquals(CategoryRules.FORMS, CategoryRules.classify("Registration worksheet"))
    }

    @Test
    fun `notes and exams land in Study`() {
        assertEquals(CategoryRules.STUDY, CategoryRules.classify("Exam revision notes"))
        assertEquals(CategoryRules.STUDY, CategoryRules.classify("Physics lecture"))
        assertEquals(CategoryRules.STUDY, CategoryRules.classify("Chapter 4 homework"))
    }

    @Test
    fun `everything else is Other`() {
        assertEquals(CategoryRules.OTHER, CategoryRules.classify("Scanned document 3"))
        // "information" must not trip the "form" keyword.
        assertEquals(CategoryRules.OTHER, CategoryRules.classify("information sheet"))
    }

    @Test
    fun `all five categories are valid and nothing else is`() {
        assertEquals(5, CategoryRules.ALL.size)
        for (c in CategoryRules.ALL) assertTrue("valid: $c", CategoryRules.isValid(c))
        assertFalse(CategoryRules.isValid("Finance"))
        assertFalse(CategoryRules.isValid(""))
    }
}
