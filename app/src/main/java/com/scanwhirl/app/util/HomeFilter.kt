package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import java.util.Locale

/**
 * test26 T3.3 / T10 — everything the Home screen decides about *which*
 * documents to show and *in what order*, as pure functions so it can be
 * tested without an Activity.
 *
 * Search (non-blank query) uses [SearchMatcher] and returns ranked hits
 * with snippets; the Sort selector is ignored while a query is active.
 * Blank query: category filter + sort exactly as before.
 *
 * The screen itself stays dumb: it holds a [HomeState], hands it to [apply],
 * and renders whatever comes back.
 */
data class HomeState(
    val query: String = "",
    val category: String? = null,
    val sort: String = Prefs.SORT_DATE,
    val includeVaulted: Boolean = false
)

/** The result of [applyDetailed]: the documents to show plus per-doc snippets. */
data class HomeResult(
    val docs: List<ScanDocument>,
    val snippets: Map<String, CharSequence>
)

object HomeFilter {

    /**
     * The documents to show for [state]. Convenience wrapper around
     * [applyDetailed] that discards snippets.
     */
    fun apply(
        docs: List<ScanDocument>,
        state: HomeState,
        normalizedText: (String) -> String,
        originalText: (String) -> String
    ): List<ScanDocument> =
        applyDetailed(docs, state, normalizedText, originalText).docs

    /**
     * The documents to show for [state], plus per-document snippets for
     * text hits. With a non-blank query the result order is the search
     * order (sort is ignored); with a blank query it is category filter +
     * sort exactly as today. Category filtering applies after matching.
     */
    fun applyDetailed(
        docs: List<ScanDocument>,
        state: HomeState,
        normalizedText: (String) -> String,
        originalText: (String) -> String
    ): HomeResult {
        val visible = if (state.includeVaulted) docs else docs.filter { !it.isVaulted }

        val result = if (state.query.isBlank()) {
            HomeResult(visible, emptyMap())
        } else {
            val hits = SearchMatcher.search(
                state.query, visible, state.includeVaulted, normalizedText, originalText
            )
            val snippets = hits.filter { it.snippet != null }.associate { it.doc.id to it.snippet!! }
            HomeResult(hits.map { it.doc }, snippets)
        }

        val narrowed =
            if (state.category == null) result.docs
            else result.docs.filter { it.category == state.category }

        val narrowedIds = narrowed.map { it.id }.toSet()
        val filteredSnippets = result.snippets.filterKeys { narrowedIds.contains(it) }

        val sorted = if (state.query.isBlank()) sort(narrowed, state.sort) else narrowed

        return HomeResult(sorted, filteredSnippets)
    }

    /** Ordering only — [applyDetailed] runs this last for blank queries so filters never re-order search results. */
    fun sort(docs: List<ScanDocument>, mode: String): List<ScanDocument> = when (mode) {
        Prefs.SORT_NAME -> docs.sortedBy { it.name.lowercase(Locale.getDefault()) }
        Prefs.SORT_MODIFIED -> docs.sortedByDescending {
            maxOf(
                it.pdfFile.lastModified(),
                it.pageFiles.maxOfOrNull { p -> p.lastModified() } ?: 0L
            )
        }
        // Group in picker order (Study … Other), Unsorted last, newest first
        // inside each group so it reads like the Date sort within a category.
        Prefs.SORT_CATEGORY -> docs.sortedWith(
            compareBy({ categoryRank(it.category) }, { -it.createdAt })
        )
        else -> docs.sortedByDescending { it.createdAt }
    }

    /**
     * Document counts per category for the chip rail: every one of the six
     * keys is present (zero-filled) so a chip can always render "Study 0".
     * The rail's "All" count is simply the size of the list handed in.
     *
     * Counts are over [docs] as given — the caller decides whether that is
     * the whole library or a search-narrowed set.
     */
    fun counts(docs: List<ScanDocument>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (cat in CategoryRules.ALL_WITH_UNSORTED) out[cat] = 0
        for (doc in docs) {
            val key = if (CategoryRules.isValid(doc.category)) doc.category
            else CategoryRules.OTHER
            out[key] = (out[key] ?: 0) + 1
        }
        return out
    }

    /** Picker order for the Category sort; unknown categories sort as Other. */
    private fun categoryRank(category: String): Int {
        val i = CategoryRules.ALL_WITH_UNSORTED.indexOf(category)
        return if (i >= 0) i else CategoryRules.ALL_WITH_UNSORTED.indexOf(CategoryRules.OTHER)
    }
}
