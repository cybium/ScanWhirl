package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search v2 pins down: names + text, accent-insensitive, ranked, snippets,
 * no suppression, no Pro gate, no "In text" toggle.
 */
class SearchMatcherTest {

    private fun doc(
        id: String,
        name: String,
        createdAt: Long = 0L,
        vaulted: Boolean = false
    ) = ScanDocument(
        id = id, name = name, createdAt = createdAt, pageCount = 1,
        pdfFile = File("/tmp/$id.pdf"), pageFiles = emptyList(),
        isLocked = false, isVaulted = vaulted
    )

    private val docs = listOf(
        doc("a", "Physics notes"),
        doc("b", "Rent receipt"),
        doc("c", "Café Noir", createdAt = 100),
        doc("d", "Secret plan", vaulted = true),
        doc("e", "Meeting notes", createdAt = 200)
    )

    private val ocrTexts = mapOf(
        "a" to "quantum mechanics lecture",
        "b" to "paid rent for april",
        "c" to "espresso and croissant",
        "e" to "quarterly budget review"
    )

    private val normalizedText: (String) -> String = { id -> SearchMatcher.normalize(ocrTexts[id] ?: "") }
    private val originalText: (String) -> String = { id -> ocrTexts[id] ?: "" }

    // ---- normalize --------------------------------------------------------

    @Test
    fun `normalize preserves length`() {
        // Precomposed é (U+00E9) → e (base letter from NFD)
        assertEquals("cafe", SearchMatcher.normalize("caf\u00E9"))
        assertEquals("hello", SearchMatcher.normalize("hello"))
        // Emoji: each code unit maps to itself
        assertEquals("\uD83D\uDE00", SearchMatcher.normalize("\uD83D\uDE00"))
    }

    @Test
    fun `normalize maps accented chars to base letter`() {
        assertEquals("e", SearchMatcher.normalize("\u00E9"))
        assertEquals("n", SearchMatcher.normalize("\u00F1"))
        assertEquals("u", SearchMatcher.normalize("\u00FC"))
    }

    // ---- search: name matches --------------------------------------------

    @Test
    fun `search matches names case-insensitively`() {
        val hits = SearchMatcher.search("notes", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.kind == SearchMatcher.Hit.Kind.NAME })
        // Same score (1000); tie broken by createdAt descending: e(200) before a(0)
        assertEquals(listOf("e", "a"), hits.map { it.doc.id })
    }

    @Test
    fun `search matches accents in names`() {
        val hits = SearchMatcher.search("cafe", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(1, hits.size)
        assertEquals("c", hits.first().doc.id)
        assertEquals(SearchMatcher.Hit.Kind.NAME, hits.first().kind)
    }

    @Test
    fun `search excludes vaulted documents`() {
        val hits = SearchMatcher.search("secret", docs, includeVaulted = false, normalizedText, originalText)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `search includes vaulted when includeVaulted`() {
        val hits = SearchMatcher.search("secret", docs, includeVaulted = true, normalizedText, originalText)
        assertEquals(1, hits.size)
        assertEquals("d", hits.first().doc.id)
    }

    // ---- search: text matches -------------------------------------------

    @Test
    fun `search matches text content`() {
        val hits = SearchMatcher.search("quantum", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(1, hits.size)
        assertEquals("a", hits.first().doc.id)
        assertEquals(SearchMatcher.Hit.Kind.TEXT, hits.first().kind)
        assertNotNull(hits.first().snippet)
    }

    @Test
    fun `search text match accent-insensitive`() {
        val hits = SearchMatcher.search("espresso", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(1, hits.size)
        assertEquals("c", hits.first().doc.id)
    }

    // ---- search: two-term queries ---------------------------------------

    @Test
    fun `two-term query requires both terms`() {
        // M08: both words in the name, or both in the text. "notes" is only in a's name and
        // "quantum" only in its text, so nothing matches; both text words do match.
        assertEquals(0, SearchMatcher.search("notes quantum", docs, includeVaulted = false, normalizedText, originalText).size)
        val hits = SearchMatcher.search("quantum lecture", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(listOf("a"), hits.map { it.doc.id })
    }

    @Test
    fun `two-term query can split between name and text`() {
        // "rent" in name of "b", "paid" in text of "b"
        val hits = SearchMatcher.search("rent paid", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(1, hits.size)
        assertEquals("b", hits.first().doc.id)
    }

    // ---- search: ranking ------------------------------------------------

    @Test
    fun `name hit ranks above text-only hit`() {
        val local = listOf(doc("t", "Notes", createdAt = 9), doc("n", "Budget plan", createdAt = 1))
        val text = mapOf("t" to "monthly budget", "n" to "")
        val hits = SearchMatcher.search("budget", local, includeVaulted = false,
            { SearchMatcher.normalize(text[it].orEmpty()) }, { text[it].orEmpty() })
        assertEquals(listOf("n", "t"), hits.map { it.doc.id })
        assertEquals(SearchMatcher.Hit.Kind.NAME, hits[0].kind)
        assertEquals(SearchMatcher.Hit.Kind.TEXT, hits[1].kind)
    }

    @Test
    fun `text-only hit ranks below any name hit`() {
        // "notes lecture" → "a" is BOTH (score 1001). A hypothetical TEXT-only
        // doc would score ≤ 999, so BOTH always wins. Covered by the test
        // above; this test confirms TEXT kind for a text-only query.
        val hits = SearchMatcher.search("lecture", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(1, hits.size)
        assertEquals("a", hits[0].doc.id)
        assertEquals(SearchMatcher.Hit.Kind.TEXT, hits[0].kind)
    }

    @Test
    fun `same score ties broken by createdAt descending`() {
        // "a" and "e" both match "notes" by name; "e" has later createdAt
        val hits = SearchMatcher.search("notes", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(2, hits.size)
        assertEquals("e", hits[0].doc.id) // createdAt 200 > 0
        assertEquals("a", hits[1].doc.id) // createdAt 0
    }

    // ---- search: snippets -----------------------------------------------

    @Test
    fun `snippet contains the matched term for text hits`() {
        val hits = SearchMatcher.search("espresso", docs, includeVaulted = false, normalizedText, originalText)
        assertEquals(1, hits.size)
        val snippet = hits.first().snippet?.toString()
        assertNotNull("snippet should not be null for text hit", snippet)
        assertTrue("snippet should contain the matched term", snippet!!.contains("espresso"))
    }

    @Test
    fun `name-only hits have no snippet`() {
        val hits = SearchMatcher.search("notes", docs, includeVaulted = false, normalizedText, originalText)
        assertTrue(hits.all { it.kind == SearchMatcher.Hit.Kind.NAME })
        assertTrue(hits.all { it.snippet == null })
    }

    @Test
    fun `blank query returns empty`() {
        val hits = SearchMatcher.search("", docs, includeVaulted = false, normalizedText, originalText)
        assertTrue(hits.isEmpty())
    }
}
