package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * M08 (owner report on device): search showed documents that do not contain what was
 * searched. Causes: substring matching ("art" in "start") and a multi-word query
 * matching one word in the name and another anywhere in the text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchWordMatchTest {

    private fun doc(id: String, name: String, created: Long = 0) =
        ScanDocument(id, name, created, 1, File("/x/$id/scan.pdf"), emptyList())

    private fun search(q: String, docs: List<ScanDocument>, text: Map<String, String>) =
        SearchMatcher.search(q, docs, false, { SearchMatcher.normalize(text[it].orEmpty()) }, { text[it].orEmpty() })
            .map { it.doc.name }

    @Test
    fun `a term matches the start of a word, never the middle`() {
        val d = listOf(doc("a", "Notes"))
        val t = mapOf("a" to "It is a good practice to start. The student should learn.")
        assertEquals(emptyList<String>(), search("art", d, t))
        assertEquals(emptyList<String>(), search("ear", d, t))
        assertEquals(listOf("Notes"), search("lea", d, t)) // prefix of "learn" still finds it
        assertEquals(listOf("Notes"), search("Practice", d, t))
    }

    @Test
    fun `numbers match whole numbers`() {
        val n = "page (2) of 21 in 2026"
        assertTrue(SearchMatcher.containsWord(SearchMatcher.normalize(n), "2"))
        assertTrue(SearchMatcher.containsWord(SearchMatcher.normalize(n), "21"))
        assertFalse(SearchMatcher.containsWord(SearchMatcher.normalize("of 21 in 2026"), "2"))
        assertFalse(SearchMatcher.containsWord(SearchMatcher.normalize("of 21 in 2026"), "202"))
    }

    @Test
    fun `Document 2 finds only Document 2, not other documents whose text has a 2`() {
        val docs = listOf(doc("1", "Document 1", 1), doc("2", "Document 2", 2), doc("3", "Document 3", 3))
        val t = mapOf("1" to "Chapter 2: A few special signs", "2" to "General scheme", "3" to "(2) Physical examination")
        assertEquals(listOf("Document 2"), search("Document 2", docs, t))
    }

    @Test
    fun `all words in the text of one document still match`() {
        val docs = listOf(doc("1", "Scan", 1))
        val t = mapOf("1" to "Examination of a Case of Hernia")
        assertEquals(listOf("Scan"), search("case hernia", docs, t))
        assertEquals(emptyList<String>(), search("scan hernia", docs, t)) // name word + text word: no
    }

    @Test
    fun `page hits and highlights follow the same word rule`() {
        val pages = listOf("page_000.jpg", "page_001.jpg")
        val ocr = mapOf("page_000.jpg" to "Start here and learn", "page_001.jpg" to "Art history")
        val r = PageTextMatches.find("art", pages, ocr)
        assertEquals(listOf(1), r.hits.map { it.pageIndex })
        assertEquals(listOf(0..2), r.hits.single().ranges)
        assertNull(PageTextMatches.matchPage("restart", listOf("start")))
    }
}
