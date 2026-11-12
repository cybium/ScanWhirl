package com.scanwhirl.app.ui.home

import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.PageTextMatches

/**
 * Retrieval slice 5 — what a Home search row may truthfully say about WHERE a
 * document matched.
 *
 * Built off the main thread for one query, only for documents the existing
 * document-level search already returned with a text hit. It never changes
 * which documents match or their order (that stays [com.scanwhirl.app.util.HomeFilter]).
 *
 *  - [pages] non-empty: those pages (current order) hold every term → "Page N".
 *  - [pages] empty: the terms are spread across pages / name and text, or the
 *    text sits under keys that no longer map to a page → "Matches across this
 *    document". No page is invented.
 *
 * Tied to the document id AND revision it was computed for; the row binder
 * drops a match whose document has since changed.
 */
data class RowMatch(
    val docId: String,
    val revision: Long,
    val query: String,
    /** The first few matching pages, in page order. */
    val pages: List<PagePreview>,
    /** Every matching page (may exceed [pages]). */
    val pageHitCount: Int,
    /** First matching page — where "See all" opens. */
    val firstPageIndex: Int,
    val firstPageKey: String?
) {
    val isPageEvidence: Boolean get() = pages.isNotEmpty()
}

/** One matching page as shown in a Home row. [excerpt] is null for locked documents. */
data class PagePreview(
    val pageIndex: Int,
    val pageKey: String,
    val count: Int,
    val excerpt: PageTextMatches.Excerpt?
)

object HomePageMatches {

    /** Pages listed in a row before "See all N pages". */
    const val MAX_PREVIEWS = 3

    /**
     * @param docs the documents Home is about to show (already vault-filtered).
     * @param textHitIds ids whose document-level hit includes OCR text.
     * @param pages stored per-page OCR for a document id (read-only).
     */
    fun build(
        query: String,
        docs: List<ScanDocument>,
        textHitIds: Set<String>,
        pages: (String) -> Map<String, String>
    ): Map<String, RowMatch> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, RowMatch>()
        for (doc in docs) {
            if (doc.id !in textHitIds) continue
            val text = pages(doc.id)
            val result = PageTextMatches.find(query, doc.pageFiles.map { it.name }, text)
            val previews = result.hits.take(MAX_PREVIEWS).map { hit ->
                PagePreview(
                    pageIndex = hit.pageIndex,
                    pageKey = hit.pageKey,
                    count = hit.count,
                    excerpt = if (doc.isLocked) null else text[hit.pageKey]?.let {
                        PageTextMatches.excerpt(it, hit.ranges.first(), result.terms)
                    }
                )
            }
            val first = result.hits.firstOrNull()
            out[doc.id] = RowMatch(
                docId = doc.id,
                revision = doc.revision,
                query = query,
                pages = previews,
                pageHitCount = result.hits.size,
                firstPageIndex = first?.pageIndex ?: -1,
                firstPageKey = first?.pageKey
            )
        }
        return out
    }
}
