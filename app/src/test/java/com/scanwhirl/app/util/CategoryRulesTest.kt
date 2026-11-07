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
    fun `everything else is Unsorted`() {
        // N3: no confident match means nobody has sorted it — not "Other".
        assertEquals(CategoryRules.UNSORTED, CategoryRules.classify("Scanned document 3"))
        // "information" must not trip the "form" keyword.
        assertEquals(CategoryRules.UNSORTED, CategoryRules.classify("information sheet"))
        assertEquals(CategoryRules.UNSORTED, CategoryRules.classify(""))
    }

    @Test
    fun `ocr text decides only with two hits and a clear winner`() {
        // Plan T1.3 sample set: each OCR body below must win outright.
        assertEquals(
            CategoryRules.RECEIPTS,
            CategoryRules.classify("Document 3", "TOTAL 450.00 GST Invoice No 12")
        )
        assertEquals(
            CategoryRules.IDS,
            CategoryRules.classify("Document 3", "Date of Birth Aadhaar")
        )
        assertEquals(
            CategoryRules.FORMS,
            CategoryRules.classify("Document 3", "Application Form applicant signature")
        )
        assertEquals(
            CategoryRules.STUDY,
            CategoryRules.classify("Document 3", "Chapter 3 Exercise marks")
        )
        assertEquals(
            CategoryRules.UNSORTED,
            CategoryRules.classify("Document 3", "hello world")
        )
    }

    @Test
    fun `ocr needs two hits and beats every rival`() {
        // One hit is not enough…
        assertEquals(
            CategoryRules.UNSORTED,
            CategoryRules.classify("Untitled", "just one invoice here")
        )
        // …and a tie is no clear winner.
        assertEquals(
            CategoryRules.UNSORTED,
            CategoryRules.classify("Untitled", "chapter exercise invoice total")
        )
        // The name still wins outright even when OCR disagrees.
        assertEquals(
            CategoryRules.RECEIPTS,
            CategoryRules.classify("Electricity Bill", "chapter exercise theorem marks syllabus")
        )
        // No OCR text at all → plain name classification.
        assertEquals(CategoryRules.UNSORTED, CategoryRules.classify("Document 3", null))
    }

    @Test
    fun `label is the stored value and all six values are valid`() {
        for (c in CategoryRules.ALL_WITH_UNSORTED) {
            assertEquals("label mirrors the stored value", c, CategoryRules.label(c))
        }
        assertEquals(5, CategoryRules.ALL.size)
        assertEquals(6, CategoryRules.ALL_WITH_UNSORTED.size)
        for (c in CategoryRules.ALL_WITH_UNSORTED) assertTrue("valid: $c", CategoryRules.isValid(c))
        assertFalse(CategoryRules.isValid("Finance"))
        assertFalse(CategoryRules.isValid(""))
        assertFalse(CategoryRules.isValid("unsorted"))
    }
}
