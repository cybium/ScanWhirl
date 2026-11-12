package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.home.HomePageMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Privacy: a password-locked document may be found by its visible NAME, but Home must
 * never read, match, rank, count or summarise its OCR before the per-document password.
 *
 * Every OCR callback below THROWS (or records) when asked about a locked document id, so a
 * regression fails loudly instead of leaking quietly.
 */
class LockedDocumentSearchPrivacyTest {

    private fun doc(
        id: String,
        name: String,
        locked: Boolean = false,
        vaulted: Boolean = false,
        createdAt: Long = 0L,
        pageNames: List<String> = listOf("page_000.jpg", "page_001.jpg", "page_002.jpg")
    ) = ScanDocument(
        id = id, name = name, createdAt = createdAt, pageCount = pageNames.size,
        pdfFile = File("/tmp/$id.pdf"), pageFiles = pageNames.map { File("/tmp/$id/$it") },
        isLocked = locked, isVaulted = vaulted
    )

    private val lockedIds = setOf("locked", "lockedVault")
    private val touched = mutableListOf<String>()

    private val ocr = mapOf(
        "open" to "Reference ZEPHYRQUILL appears here",
        "locked" to "The secret ZEPHYRQUILL salary is 99",
        "lockedVault" to "ZEPHYRQUILL in a vaulted locked file"
    )

    private fun guarded(id: String): String {
        touched += id
        if (id in lockedIds) throw AssertionError("OCR of locked document '$id' was read")
        return ocr[id] ?: ""
    }

    private val normalized: (String) -> String = { SearchMatcher.normalize(guarded(it)) }
    private val original: (String) -> String = { guarded(it) }

    private val docs = listOf(
        doc("open", "Scholarship form", createdAt = 1),
        doc("locked", "Pay slip", locked = true, createdAt = 2),
        doc("lockedVault", "Vault pay slip", locked = true, vaulted = true, createdAt = 3)
    )

    // ---- matcher ----------------------------------------------------------------

    @Test
    fun `OCR-only word does not find a locked document and never reads its OCR`() {
        val hits = SearchMatcher.search("zephyrquill", docs, true, normalized, original)
        assertEquals(listOf("open"), hits.map { it.doc.id })
        assertFalse("locked OCR was requested", touched.any { it in lockedIds })
    }

    @Test
    fun `a locked document is still found by its visible name with no snippet or text kind`() {
        val hits = SearchMatcher.search("pay slip", docs, true, normalized, original)
        assertEquals(setOf("locked", "lockedVault"), hits.map { it.doc.id }.toSet())
        for (h in hits) {
            assertEquals(SearchMatcher.Hit.Kind.NAME, h.kind)
            assertNull(h.snippet)
            assertEquals("score comes from the name alone", 2000, h.score)
        }
    }

    @Test
    fun `a name term plus a content term is not an oracle for a locked document`() {
        // "pay" is in the name; "salary" only in the locked OCR. Must NOT match.
        val hits = SearchMatcher.search("pay salary", docs, true, normalized, original)
        assertTrue(hits.none { it.doc.isLocked })
        // The same two-term query against an unlocked doc with name+text still works.
        val mixed = listOf(doc("open", "Pay record"))
        val ok = SearchMatcher.search("pay zephyrquill", mixed, true, normalized, original)
        assertEquals(listOf("open"), ok.map { it.doc.id })
        assertEquals(SearchMatcher.Hit.Kind.BOTH, ok.single().kind)
    }

    @Test
    fun `score and rank of a locked name hit ignore its content`() {
        val sameName = listOf(
            doc("locked", "Report", locked = true, createdAt = 1),
            doc("open", "Report", createdAt = 2)
        )
        val a = SearchMatcher.search("report", sameName, true, normalized, original)
        // open doc OCR does not contain "report", locked cannot add occurrences either.
        assertEquals(listOf(1000, 1000), a.map { it.score })
        assertEquals(listOf("open", "locked"), a.map { it.doc.id }) // createdAt tie-break only
    }

    @Test
    fun `vaulted documents stay absent when the vault is not included`() {
        val hits = SearchMatcher.search("pay slip", docs, false, normalized, original)
        assertEquals(listOf("locked"), hits.map { it.doc.id })
    }

    @Test
    fun `ordinary unlocked OCR search is unchanged`() {
        val hits = SearchMatcher.search("zephyrquill", listOf(docs[0]), false, normalized, original)
        assertEquals(1, hits.size)
        assertEquals(SearchMatcher.Hit.Kind.TEXT, hits.single().kind)
        assertTrue(hits.single().snippet.toString().contains("ZEPHYRQUILL"))
    }

    // ---- HomeFilter (authenticated legacy vault included) ------------------------

    @Test
    fun `HomeFilter in an authenticated vault still gives a locked document no snippet`() {
        val state = HomeState(query = "zephyrquill", includeVaulted = true)
        val result = HomeFilter.applyDetailed(docs, state, normalized, original)
        assertEquals(listOf("open"), result.docs.map { it.id })
        assertEquals(setOf("open"), result.snippets.keys)
        val byName = HomeFilter.applyDetailed(docs, HomeState(query = "vault pay", includeVaulted = true), normalized, original)
        assertEquals(listOf("lockedVault"), byName.docs.map { it.id })
        assertTrue(byName.snippets.isEmpty())
    }

    // ---- page-hit construction -------------------------------------------------------

    @Test
    fun `page hits are never built for a locked document even if handed its id`() {
        val reads = mutableListOf<String>()
        val pages: (String) -> Map<String, String> = { id ->
            reads += id
            if (id in lockedIds) throw AssertionError("page OCR of locked '$id' was read")
            mapOf("page_001.jpg" to "ZEPHYRQUILL")
        }
        // A caller that wrongly lists a locked id as a text hit gets nothing for it.
        val out = HomePageMatches.build(
            "zephyrquill", docs, setOf("open", "locked", "lockedVault"), pages
        )
        assertEquals(setOf("open"), out.keys)
        assertEquals(listOf("open"), reads)
        val open = out.getValue("open")
        assertEquals(1, open.pageHitCount)
        assertEquals(1, open.firstPageIndex)
    }

    @Test
    fun `end to end Home pipeline for a locked document touches no OCR path`() {
        val pageReads = mutableListOf<String>()
        val result = HomeFilter.applyDetailed(
            docs, HomeState(query = "pay", includeVaulted = true), normalized, original
        )
        val matches = HomePageMatches.build("pay", result.docs, result.snippets.keys) { id ->
            pageReads += id
            throw AssertionError("page OCR requested for $id")
        }
        assertEquals(setOf("locked", "lockedVault"), result.docs.map { it.id }.toSet())
        assertTrue("no page hint, count or link", matches.isEmpty())
        assertTrue(pageReads.isEmpty())
        assertTrue(touched.none { it in lockedIds })
    }
}
