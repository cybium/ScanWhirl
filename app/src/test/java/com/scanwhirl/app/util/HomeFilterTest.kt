package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * test26 T3.3 — the Home screen's whole decision surface: which documents
 * show for a category, for a search, for both at once, in each of the four
 * sort orders, and what the chip rail counts.
 */
class HomeFilterTest {

    private var seq = 0
    private val tempFiles = mutableListOf<File>()

    private fun doc(
        name: String,
        category: String = CategoryRules.OTHER,
        createdAt: Long = 0L,
        modifiedAt: Long = 0L,
        vaulted: Boolean = false
    ): ScanDocument {
        val f = File.createTempFile("hf_${seq++}", ".pdf").apply {
            deleteOnExit()
            if (modifiedAt > 0) setLastModified(modifiedAt)
        }
        tempFiles += f
        return ScanDocument(
            id = "id${seq}", name = name, createdAt = createdAt, pageCount = 1,
            pdfFile = f, pageFiles = emptyList(),
            isLocked = false, isVaulted = vaulted, category = category
        )
    }

    private val library = listOf(
        doc("Physics notes", CategoryRules.STUDY, createdAt = 300),
        doc("Rent receipt", CategoryRules.RECEIPTS, createdAt = 200),
        doc("Passport scan", CategoryRules.IDS, createdAt = 100),
        doc("Tax form", CategoryRules.FORMS, createdAt = 400),
        doc("Document 3", CategoryRules.OTHER, createdAt = 500),
        doc("Loose page", CategoryRules.UNSORTED, createdAt = 600)
    )

    // ---- category filter ------------------------------------------------

    @Test
    fun `each category narrows to only that category`() {
        for (cat in CategoryRules.ALL_WITH_UNSORTED) {
            val state = HomeState(category = cat)
            val out = HomeFilter.apply(library, state)
            assertTrue(
                "category $cat -> ${out.map { it.name }}",
                out.isNotEmpty() && out.all { it.category == cat }
            )
        }
    }

    @Test
    fun `null category shows everything`() {
        val out = HomeFilter.apply(library, HomeState(category = null))
        assertEquals(library.size, out.size)
    }

    @Test
    fun `category filter is case exact against the stored value`() {
        val out = HomeFilter.apply(library, HomeState(category = "study"))
        assertTrue("lowercase must not match", out.isEmpty())
    }

    // ---- search combined with category ----------------------------------

    @Test
    fun `search and category combine with AND`() {
        // "Rent receipt" is the only RECEIPTS doc and the only "receipt" name.
        val state = HomeState(query = "receipt", category = CategoryRules.RECEIPTS)
        assertEquals(listOf("Rent receipt"), HomeFilter.apply(library, state).map { it.name })

        // Same query but a category that holds no match -> nothing.
        val miss = HomeState(query = "receipt", category = CategoryRules.STUDY)
        assertTrue(HomeFilter.apply(library, miss).isEmpty())
    }

    @Test
    fun `a query that matches no name in the chosen category empties the list`() {
        val state = HomeState(query = "zzz-not-a-document", category = CategoryRules.OTHER)
        assertTrue(HomeFilter.apply(library, state).isEmpty())
    }

    @Test
    fun `ocr text matches only when inText is on`() {
        val docs = listOf(doc("Alpha", CategoryRules.OTHER))
        val ocr: (String) -> Map<String, String> = { emptyMap() }
        val lookup: (String) -> Map<String, String> =
            { id -> if (docs.first().id == id) mapOf("p1.jpg" to "hologram invoice") else emptyMap() }

        val on = HomeFilter.apply(docs, HomeState(query = "hologram", inText = true), lookup)
        assertEquals(listOf("Alpha"), on.map { it.name })

        val off = HomeFilter.apply(docs, HomeState(query = "hologram", inText = false), lookup)
        assertTrue("inText off must ignore OCR text", off.isEmpty())

        // and the default state never reaches for OCR at all
        val default = HomeFilter.apply(docs, HomeState(query = "hologram"), ocr)
        assertTrue(default.isEmpty())
    }

    @Test
    fun `vaulted documents stay hidden unless the state says otherwise`() {
        val docs = listOf(doc("Visible"), doc("Secret", vaulted = true))
        assertTrue(HomeFilter.apply(docs, HomeState()).none { it.name == "Secret" })
        val inVault = HomeFilter.apply(docs, HomeState(includeVaulted = true))
        assertEquals(2, inVault.size)
    }

    // ---- the four sorts -------------------------------------------------

    @Test
    fun `date sort is newest first`() {
        val out = HomeFilter.sort(library, Prefs.SORT_DATE)
        assertEquals(
            listOf("Loose page", "Document 3", "Tax form", "Physics notes", "Rent receipt", "Passport scan"),
            out.map { it.name }
        )
    }

    @Test
    fun `name sort is alphabetical and case insensitive`() {
        val docs = listOf(doc("banana"), doc("Apple"), doc("cherry"))
        assertEquals(
            listOf("Apple", "banana", "cherry"),
            HomeFilter.sort(docs, Prefs.SORT_NAME).map { it.name }
        )
    }

    @Test
    fun `modified sort is most recently touched first`() {
        val docs = listOf(
            doc("old", modifiedAt = 1_000L),
            doc("new", modifiedAt = 9_000L),
            doc("mid", modifiedAt = 5_000L)
        )
        assertEquals(
            listOf("new", "mid", "old"),
            HomeFilter.sort(docs, Prefs.SORT_MODIFIED).map { it.name }
        )
    }

    @Test
    fun `category sort groups in picker order with unsorted last`() {
        val out = HomeFilter.sort(library, Prefs.SORT_CATEGORY)
        assertEquals(
            listOf(
                "Physics notes",   // Study
                "Rent receipt",    // Receipts
                "Passport scan",   // IDs
                "Tax form",        // Forms
                "Document 3",      // Other
                "Loose page"       // Unsorted last
            ),
            out.map { it.name }
        )
    }

    @Test
    fun `category sort keeps newest first inside a group`() {
        val docs = listOf(
            doc("study old", CategoryRules.STUDY, createdAt = 1),
            doc("study new", CategoryRules.STUDY, createdAt = 9),
            doc("study mid", CategoryRules.STUDY, createdAt = 5)
        )
        assertEquals(
            listOf("study new", "study mid", "study old"),
            HomeFilter.sort(docs, Prefs.SORT_CATEGORY).map { it.name }
        )
    }

    @Test
    fun `unknown sort mode falls back to date`() {
        assertEquals(
            HomeFilter.sort(library, Prefs.SORT_DATE).map { it.name },
            HomeFilter.sort(library, "nonsense").map { it.name }
        )
    }

    // ---- counts ---------------------------------------------------------

    @Test
    fun `counts zero fills all six categories`() {
        val counts = HomeFilter.counts(emptyList())
        assertEquals(CategoryRules.ALL_WITH_UNSORTED.toSet(), counts.keys)
        assertTrue("all zero", counts.values.all { it == 0 })
    }

    @Test
    fun `counts tally each category including unsorted`() {
        val counts = HomeFilter.counts(library)
        assertEquals(1, counts[CategoryRules.STUDY])
        assertEquals(1, counts[CategoryRules.RECEIPTS])
        assertEquals(1, counts[CategoryRules.IDS])
        assertEquals(1, counts[CategoryRules.FORMS])
        assertEquals(1, counts[CategoryRules.OTHER])
        assertEquals(1, counts[CategoryRules.UNSORTED])
        assertEquals(library.size, counts.values.sum())
    }

    @Test
    fun `counts treat an unknown stored category as other`() {
        val counts = HomeFilter.counts(listOf(doc("weird", category = "NotACategory")))
        assertEquals(1, counts[CategoryRules.OTHER])
        assertEquals(0, counts[CategoryRules.STUDY])
    }

    @Test
    fun `counts over an empty list still offer every chip a number`() {
        val counts = HomeFilter.counts(emptyList())
        for (cat in CategoryRules.ALL_WITH_UNSORTED) {
            assertTrue("missing $cat", counts.containsKey(cat))
        }
    }
}
