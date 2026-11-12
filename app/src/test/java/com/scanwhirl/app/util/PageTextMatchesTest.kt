package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Retrieval slice 5 — page-level evidence is computed only from stored per-page
 * OCR text and the CURRENT page order, and a "Page N" claim needs every term on
 * that same page. Pure JVM.
 */
class PageTextMatchesTest {

    private fun keys(n: Int) = (0 until n).map { "page_%03d.jpg".format(it) }

    /** A synthetic n-page document: filler on every page, [extra] appended to chosen pages. */
    private fun ocr(n: Int, extra: Map<Int, String> = emptyMap()): Map<String, String> =
        (0 until n).associate { i -> "page_%03d.jpg".format(i) to "Filler line for sheet ${i + 1}.\n${extra[i].orEmpty()}" }

    private fun slice(text: String, r: IntRange) = text.substring(r.first, r.last + 1)

    // ---- single word, 1 / 5 / 30 pages ----------------------------------------

    @Test
    fun `unique word only on page 2 of 5 is a hit on page 2 alone`() {
        val text = ocr(5, mapOf(1 to "Reference ZEPHYRQUILL 77"))
        val r = PageTextMatches.find("zephyrquill", keys(5), text)
        assertEquals(listOf(1), r.hits.map { it.pageIndex })
        assertEquals("page_001.jpg", r.hits.single().pageKey)
        assertEquals(1, r.matchCount)
        val range = r.hits.single().ranges.single()
        assertEquals("ZEPHYRQUILL", slice(text.getValue("page_001.jpg"), range))
        assertTrue(r.coverage.isComplete)
    }

    @Test
    fun `one page and thirty pages`() {
        assertEquals(listOf(0), PageTextMatches.find("filler", keys(1), ocr(1)).hits.map { it.pageIndex })
        val thirty = ocr(30, mapOf(0 to "alpha", 17 to "alpha", 29 to "alpha alpha"))
        val r = PageTextMatches.find("ALPHA", keys(30), thirty)
        assertEquals(listOf(0, 17, 29), r.hits.map { it.pageIndex })
        assertEquals(4, r.matchCount)
        assertEquals(30, r.coverage.totalPages)
    }

    @Test
    fun `no match and blank query give no hits`() {
        assertTrue(PageTextMatches.find("absent", keys(5), ocr(5)).hits.isEmpty())
        assertTrue(PageTextMatches.find("   ", keys(5), ocr(5)).hits.isEmpty())
        assertTrue(PageTextMatches.find("", keys(5), ocr(5)).terms.isEmpty())
    }

    // ---- multi-term ---------------------------------------------------------

    @Test
    fun `all terms on one page is a page hit`() {
        val r = PageTextMatches.find("annual income", keys(5),
            ocr(5, mapOf(2 to "has an annual family income of")))
        assertEquals(listOf(2), r.hits.map { it.pageIndex })
        assertEquals(2, r.hits.single().count)
    }

    @Test
    fun `terms spread over pages are never a page hit even though the document matches`() {
        val text = ocr(5, mapOf(0 to "annual", 3 to "income"))
        val r = PageTextMatches.find("annual income", keys(5), text)
        assertTrue("no page holds both terms", r.hits.isEmpty())
        // Home's document-level search still matches the same document.
        val doc = ScanDocument("d", "Form", 0, 5, File("/tmp/d.pdf"), emptyList())
        val joined = text.values.joinToString("\n")
        val docHits = SearchMatcher.search("annual income", listOf(doc), false,
            { SearchMatcher.normalize(joined) }, { joined })
        assertEquals(1, docHits.size)
    }

    @Test
    fun `name-only and name plus page terms are not page hits`() {
        // "scholarship" is only in the document NAME, never in OCR.
        val text = ocr(3, mapOf(1 to "income certificate"))
        assertTrue(PageTextMatches.find("scholarship", keys(3), text).hits.isEmpty())
        assertTrue(PageTextMatches.find("scholarship income", keys(3), text).hits.isEmpty())
    }

    @Test
    fun `every page hit implies the document-level hit`() {
        val text = ocr(6, mapOf(1 to "Blue Heron", 4 to "heron blue sky"))
        val r = PageTextMatches.find("heron blue", keys(6), text)
        assertEquals(listOf(1, 4), r.hits.map { it.pageIndex })
        val joinedNorm = SearchMatcher.normalize(text.values.joinToString("\n"))
        assertTrue(r.terms.all { joinedNorm.contains(it) })
    }

    // ---- duplicates, accents, case, Unicode ----------------------------------

    @Test
    fun `duplicate words count separately and overlapping terms merge`() {
        val text = mapOf("page_000.jpg" to "income Income INCOME incomes")
        val r = PageTextMatches.find("income", keys(1), text)
        assertEquals(4, r.hits.single().count)
        // "inc" and "income" overlap: one merged range per occurrence, not two.
        val merged = PageTextMatches.find("inc income", keys(1), text)
        assertEquals(4, merged.hits.single().count)
        assertEquals(listOf(0..5, 7..12, 14..19, 21..26), merged.hits.single().ranges)
        // A repeated query term does not double count.
        assertEquals(4, PageTextMatches.find("income income", keys(1), text).hits.single().count)
    }

    @Test
    fun `accent and case folding match Home search`() {
        val text = mapOf("page_000.jpg" to "x", "page_001.jpg" to "Reçu du CAFÉ Noël")
        val r = PageTextMatches.find("cafe noel recu", keys(2), text)
        assertEquals(listOf(1), r.hits.map { it.pageIndex })
        val page = text.getValue("page_001.jpg")
        assertEquals(listOf("Reçu", "CAFÉ", "Noël"), r.hits.single().ranges.map { slice(page, it) })
        // Accented query against plain text.
        assertEquals(1, PageTextMatches.find("CAFÉ", keys(2), mapOf("page_001.jpg" to "cafe")).hits.size)
    }

    @Test
    fun `normaliser keeps one char per char for awkward Unicode`() {
        val samples = listOf(
            "İstanbul ǅ ß ﬁ", "आय प्रमाण पत्र", "é café", "📄 page 😀 two", "ΣΊΣΥΦΟΣ", "𝐀bold"
        )
        for (s in samples) assertEquals(s, s.length, SearchMatcher.normalize(s).length)
    }

    @Test
    fun `hindi, emoji and decomposed text ranges point at the right original chars`() {
        val page = "📄 आय प्रमाण पत्र — café RECEIPT"
        val text = mapOf("page_000.jpg" to "none", "page_001.jpg" to page)
        val hindi = PageTextMatches.find("प्रमाण", keys(2), text)
        assertEquals("प्रमाण", slice(page, hindi.hits.single().ranges.single()))
        val latin = PageTextMatches.find("receipt cafe", keys(2), text)
        assertEquals(listOf("cafe", "RECEIPT"), latin.hits.single().ranges.map { slice(page, it) })
    }

    // ---- coverage: pending / partial / empty / malformed ---------------------

    @Test
    fun `no stored text means no hits and empty coverage, not indexed`() {
        val r = PageTextMatches.find("anything", keys(5), emptyMap())
        assertTrue(r.hits.isEmpty())
        assertTrue(r.coverage.isEmpty)
        assertFalse(r.coverage.isComplete)
    }

    @Test
    fun `partial text is reported partial and only stored pages can hit`() {
        val text = mapOf("page_000.jpg" to "alpha", "page_003.jpg" to "alpha beta")
        val r = PageTextMatches.find("alpha", keys(5), text)
        assertEquals(listOf(0, 3), r.hits.map { it.pageIndex })
        assertEquals(2, r.coverage.indexedPages)
        assertTrue(r.coverage.isPartial)
        assertFalse(r.coverage.isComplete)
    }

    @Test
    fun `an unreadable page stored as empty text counts as read but never hits`() {
        val text = mapOf("page_000.jpg" to "", "page_001.jpg" to "gamma")
        val r = PageTextMatches.find("gamma", keys(2), text)
        assertEquals(listOf(1), r.hits.map { it.pageIndex })
        assertTrue(r.coverage.isComplete)
    }

    @Test
    fun `zero pages and PDF-only documents can never claim a page`() {
        val r = PageTextMatches.find("alpha", emptyList(), mapOf("page_000.jpg" to "alpha"))
        assertTrue(r.hits.isEmpty())
        assertEquals(0, r.coverage.totalPages)
        assertEquals(1, r.coverage.orphanKeys)
        assertFalse(r.coverage.isComplete)
    }

    // ---- current order, reorder, delete, key mismatch -------------------------

    @Test
    fun `page index follows the current order, not OCR map order or sorted keys`() {
        // OCR map iterates in a different order from the current pages.
        val text = linkedMapOf("page_002.jpg" to "omega", "page_000.jpg" to "x", "page_001.jpg" to "y")
        val r = PageTextMatches.find("omega", keys(3), text)
        assertEquals(2, r.hits.single().pageIndex)
        // A custom current order (legacy names) is honoured exactly.
        val order = listOf("b.jpg", "a.jpg", "c.jpg")
        val legacy = mapOf("a.jpg" to "omega", "b.jpg" to "x", "c.jpg" to "y")
        assertEquals(1, PageTextMatches.find("omega", order, legacy).hits.single().pageIndex)
    }

    @Test
    fun `after a move the remapped OCR follows the page`() {
        // Word on page index 1; the user moves page 1 to position 3 (OcrStore.afterMove permutation).
        val before = ocr(4, mapOf(1 to "ZEPHYRQUILL"))
        fun remap(index: Int, from: Int, to: Int) = when {
            index == from -> to
            from < to && index in (from + 1)..to -> index - 1
            from > to && index in to until from -> index + 1
            else -> index
        }
        val after = before.mapKeys { (k, _) ->
            "page_%03d.jpg".format(remap(k.substring(5, 8).toInt(), 1, 3))
        }
        assertEquals(1, PageTextMatches.find("zephyrquill", keys(4), before).hits.single().pageIndex)
        assertEquals(3, PageTextMatches.find("zephyrquill", keys(4), after).hits.single().pageIndex)
    }

    @Test
    fun `stale keys after delete are orphans and never mapped to a guessed page`() {
        // OCR still lists page_004 but the document now has only 4 pages.
        val text = ocr(4) + ("page_004.jpg" to "ZEPHYRQUILL")
        val r = PageTextMatches.find("zephyrquill", keys(4), text)
        assertTrue(r.hits.isEmpty())
        assertEquals(1, r.coverage.orphanKeys)
        // A key spelling mismatch (e.g. .jpeg vs .jpg) is also no jump.
        val mismatch = PageTextMatches.find("zephyrquill", keys(2), mapOf("page_001.jpeg" to "zephyrquill"))
        assertTrue(mismatch.hits.isEmpty())
        assertEquals(0, mismatch.coverage.indexedPages)
    }

    @Test
    fun `a duplicated key in the order is only one page`() {
        val r = PageTextMatches.find("alpha", listOf("page_000.jpg", "page_000.jpg"), mapOf("page_000.jpg" to "alpha"))
        assertEquals(listOf(0), r.hits.map { it.pageIndex })
        assertEquals(1, r.coverage.indexedPages)
    }

    @Test
    fun `edited OCR text is what is matched`() {
        val original = ocr(3, mapOf(1 to "Recieved with thanks"))
        val edited = original + ("page_001.jpg" to "Received with thanks")
        assertTrue(PageTextMatches.find("received", keys(3), original).hits.isEmpty())
        assertEquals(1, PageTextMatches.find("received", keys(3), edited).hits.single().pageIndex)
    }

    @Test
    fun `range count is bounded but the total stays exact`() {
        val page = (1..500).joinToString(" ") { "zz" }
        val hit = PageTextMatches.find("zz", keys(1), mapOf("page_000.jpg" to page)).hits.single()
        assertEquals(500, hit.count)
        assertEquals(PageTextMatches.MAX_RANGES_PER_PAGE, hit.ranges.size)
    }

    @Test
    fun `matching does not mutate the inputs`() {
        val text = HashMap(ocr(3, mapOf(1 to "alpha")))
        val copy = HashMap(text)
        val order = keys(3)
        PageTextMatches.find("alpha", order, text)
        assertEquals(copy, text)
        assertEquals(keys(3), order)
    }

    // ---- excerpts --------------------------------------------------------------

    @Test
    fun `excerpt is one line, near the match, with genuine highlights only`() {
        val page = "REVENUE OFFICE\nINCOME CERTIFICATE\nThis is to certify that the family has an annual income from all sources of 180000 for the year"
        val hit = PageTextMatches.find("income", keys(1), mapOf("page_000.jpg" to page)).hits.single()
        val ex = PageTextMatches.excerpt(page, hit.ranges[1], listOf("income"))
        assertFalse(ex.text.contains('\n'))
        assertTrue(ex.text.startsWith("…"))
        assertTrue(ex.text.length < 100)
        assertTrue(ex.highlights.isNotEmpty())
        ex.highlights.forEach { assertEquals("income", ex.text.substring(it.first, it.last + 1).lowercase()) }
    }

    @Test
    fun `excerpt never splits a surrogate pair`() {
        val page = "😀".repeat(30) + "needle" + "😀".repeat(30)
        val hit = PageTextMatches.find("needle", keys(1), mapOf("page_000.jpg" to page)).hits.single()
        val ex = PageTextMatches.excerpt(page, hit.ranges.single(), listOf("needle"), radius = 11)
        val body = ex.text.trim('…')
        assertFalse(Character.isLowSurrogate(body.first()))
        assertFalse(Character.isHighSurrogate(body.last()))
        assertEquals("needle", ex.text.substring(ex.highlights.single().first, ex.highlights.single().last + 1))
    }

    @Test
    fun `excerpt of a short page has no ellipsis`() {
        val ex = PageTextMatches.excerpt("Total due 42", 0..4, listOf("total"))
        assertEquals("Total due 42", ex.text)
        assertEquals(listOf(0..4), ex.highlights)
        assertNull(PageTextMatches.matchPage("", listOf("x")))
    }
}
