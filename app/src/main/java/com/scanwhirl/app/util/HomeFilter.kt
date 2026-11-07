package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import java.util.Locale

/**
 * test26 T3.3 — everything the Home screen decides about *which* documents
 * to show and *in what order*, as pure functions so it can be tested
 * without an Activity.
 *
 * The screen itself stays dumb: it holds a [HomeState], hands it to [apply],
 * and renders whatever comes back.
 *
 * @param query free text; matched against names, then (when [inText]) OCR text.
 * @param category `null` = All, otherwise one of [CategoryRules]' six values.
 * @param sort one of [Prefs.SORT_DATE] / [SORT_NAME] / [SORT_MODIFIED] / [SORT_CATEGORY].
 * @param inText whether OCR text may match (the Pro "In text" toggle).
 * @param includeVaulted for the Vault screen only — the PIN has already been
 *   passed, so vaulted documents are the list rather than hidden from it.
 */
data class HomeState(
    val query: String = "",
    val category: String? = null,
    val sort: String = Prefs.SORT_DATE,
    val inText: Boolean = false,
    val includeVaulted: Boolean = false
)

object HomeFilter {

    /**
     * The documents to show for [state]: search, then category, then sort.
     *
     * Search reuses [SearchMatcher] so Home, the globe and the Shelf can never
     * disagree about what matches (name first, OCR fallback only when enabled
     * and the names missed).
     */
    fun apply(
        docs: List<ScanDocument>,
        state: HomeState,
        ocrLookup: (String) -> Map<String, String> = { emptyMap() }
    ): List<ScanDocument> {
        val (shown, _) = SearchMatcher.match(
            state.query,
            docs,
            ocrEnabled = state.inText,
            includeVaulted = state.includeVaulted,
            ocrLookup = ocrLookup
        )
        val narrowed =
            if (state.category == null) shown
            else shown.filter { it.category == state.category }
        return sort(narrowed, state.sort)
    }

    /** Ordering only — [apply] runs this last so filters never re-order it. */
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
