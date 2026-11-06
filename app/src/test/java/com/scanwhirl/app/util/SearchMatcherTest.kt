package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The globe home and the Shelf must agree about what matches — this pins
 * the shared behaviour down: name matches, Pro OCR fallback, vaulted docs
 * never surfacing before the PIN.
 */
class SearchMatcherTest {

    private fun doc(
        id: String,
        name: String,
        vaulted: Boolean = false,
        locked: Boolean = false
    ) = ScanDocument(
        id = id, name = name, createdAt = 0L, pageCount = 1,
        pdfFile = File("/tmp/$id.pdf"), pageFiles = emptyList(),
        isLocked = locked, isVaulted = vaulted
    )

    private val docs = listOf(
        doc("a", "Physics notes"),
        doc("b", "Rent receipt"),
        doc("c", "Secret plan", vaulted = true),
        doc("d", "Locked diary", locked = true)
    )

    @Test
    fun `name search matches case-insensitively`() {
        val (shown, ocr) = SearchMatcher.match("notes", docs, ocrEnabled = false)
        assertEquals(listOf("a"), shown.map { it.id })
        assertTrue(ocr.isEmpty())
    }

    @Test
    fun `blank query shows everything except vaulted`() {
        val (shown, _) = SearchMatcher.match("", docs, ocrEnabled = true)
        assertEquals(listOf("a", "b", "d"), shown.map { it.id })
    }

    @Test
    fun `vaulted documents never surface`() {
        val (shown, _) = SearchMatcher.match("Secret", docs, ocrEnabled = true)
        assertTrue("vaulted name must not match", shown.isEmpty())
    }

    @Test
    fun `ocr fallback only runs for pro and only when names miss`() {
        val ocr: (String) -> Map<String, String> = { id ->
            if (id == "a") mapOf("page_001.jpg" to "hidden laser text") else emptyMap()
        }
        // Pro + name miss → OCR decides.
        val (proShown, proOcr) = SearchMatcher.match("laser", docs, ocrEnabled = true, ocrLookup = ocr)
        assertEquals(listOf("a"), proShown.map { it.id })
        assertEquals(1, proOcr.size)

        // Free → no OCR results.
        val (freeShown, freeOcr) = SearchMatcher.match("laser", docs, ocrEnabled = false, ocrLookup = ocr)
        assertTrue(freeShown.isEmpty())
        assertTrue(freeOcr.isEmpty())

        // Names hit → OCR is not mixed in.
        val (mixed, mixedOcr) = SearchMatcher.match("notes", docs, ocrEnabled = true, ocrLookup = ocr)
        assertEquals(listOf("a"), mixed.map { it.id })
        assertTrue(mixedOcr.isEmpty())
    }
}
