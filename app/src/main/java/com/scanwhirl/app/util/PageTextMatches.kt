package com.scanwhirl.app.util

/**
 * Retrieval slice 5: which PAGES of one saved document truly contain a query,
 * computed only from the per-page OCR text that is already stored (`ocr.txt`,
 * keyed by page file name) and the document's CURRENT page order
 * ([com.scanwhirl.app.model.ScanDocument.pageFiles]).
 *
 * Rules (each one is a test in PageTextMatchesTest):
 *  - Terms are [SearchMatcher.normalize]d and split on whitespace, exactly like
 *    Home search, so a page hit always implies the document-level hit.
 *  - A page is a hit only when EVERY term occurs in that same page's text.
 *    Terms spread over several pages, or over the name and a page, are a
 *    document match, never a page match.
 *  - Page N is the position of the key in [find]'s `pageKeys` (the current
 *    order). OCR keys that are not in the current order are counted as
 *    orphans and never matched; a page with no stored key is "not indexed".
 *    Nothing is guessed from key names or sorted OCR text.
 *  - Match ranges are character ranges in that page's ORIGINAL text (the
 *    normaliser maps every char to exactly one char). They are text evidence
 *    only: nothing here knows, or claims, where a word sits on the page image.
 *
 * Pure Kotlin; reads and writes nothing.
 */
object PageTextMatches {

    /** Per page, ranges kept for navigation (counts stay exact up to this many). */
    const val MAX_RANGES_PER_PAGE = 200

    private val WHITESPACE = Regex("\\s+")

    /** How much of the document's current page order has stored text. */
    data class Coverage(
        /** Pages in the current order. */
        val totalPages: Int,
        /** Current pages that have a stored OCR entry (empty text counts: it was read). */
        val indexedPages: Int,
        /** Stored OCR keys that match no current page (stale/mismatched; ignored). */
        val orphanKeys: Int
    ) {
        val isComplete: Boolean get() = totalPages > 0 && indexedPages >= totalPages
        val isPartial: Boolean get() = indexedPages in 1 until totalPages
        val isEmpty: Boolean get() = indexedPages == 0
    }

    /** One page whose stored text contains every term. */
    data class PageHit(
        /** 0-based index in the current page order (PDF page = index + 1). */
        val pageIndex: Int,
        /** The page file name the text is stored under (e.g. `page_001.jpg`). */
        val pageKey: String,
        /** Merged, sorted ranges of term occurrences in the page's original text. */
        val ranges: List<IntRange>,
        /** Total merged occurrences on the page (may exceed [ranges].size). */
        val count: Int
    )

    data class Result(
        val terms: List<String>,
        val hits: List<PageHit>,
        val coverage: Coverage
    ) {
        val matchCount: Int get() = hits.sumOf { it.count }
        val pageCount: Int get() = hits.size
    }

    /** A short single-line excerpt with highlight ranges inside [text]. */
    data class Excerpt(val text: String, val highlights: List<IntRange>)

    /** Normalised, non-blank query terms (same rule as [SearchMatcher.search]). */
    fun terms(query: String): List<String> =
        SearchMatcher.normalize(query).split(WHITESPACE).filter { it.isNotBlank() }

    /**
     * The pages of a document that contain every term of [query].
     *
     * @param pageKeys the CURRENT page order (page file names); index = page index.
     * @param ocr the stored per-page text, keyed by page file name.
     */
    fun find(query: String, pageKeys: List<String>, ocr: Map<String, String>): Result {
        val terms = terms(query)
        val seen = HashSet<String>()
        var indexed = 0
        for (key in pageKeys) if (seen.add(key) && ocr.containsKey(key)) indexed++
        val coverage = Coverage(
            totalPages = pageKeys.size,
            indexedPages = indexed,
            orphanKeys = ocr.keys.count { it !in seen }
        )
        if (terms.isEmpty()) return Result(terms, emptyList(), coverage)

        val hits = ArrayList<PageHit>()
        val used = HashSet<String>()
        pageKeys.forEachIndexed { index, key ->
            // A duplicated key cannot be two pages: only its first position counts.
            if (!used.add(key)) return@forEachIndexed
            val text = ocr[key] ?: return@forEachIndexed
            matchPage(text, terms)?.let { (ranges, count) ->
                hits += PageHit(index, key, ranges, count)
            }
        }
        return Result(terms, hits, coverage)
    }

    /**
     * Merged occurrence ranges of [terms] in [text] when every term occurs, else null.
     * Returns the first [MAX_RANGES_PER_PAGE] ranges and the exact total count.
     */
    internal fun matchPage(text: String, terms: List<String>): Pair<List<IntRange>, Int>? {
        if (text.isEmpty() || terms.isEmpty()) return null
        val norm = SearchMatcher.normalize(text)
        // The normaliser is one char in, one char out; if that ever failed,
        // ranges would point at the wrong text, so report no page hit at all.
        if (norm.length != text.length) return null
        if (!terms.all { norm.contains(it) }) return null

        val raw = ArrayList<IntRange>()
        for (term in terms) {
            var from = 0
            while (true) {
                val idx = norm.indexOf(term, from)
                if (idx < 0) break
                raw += idx until idx + term.length
                from = idx + term.length
            }
        }
        raw.sortWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
        val merged = ArrayList<IntRange>()
        var count = 0
        var cur: IntRange? = null
        for (r in raw) {
            val c = cur
            cur = when {
                c == null -> r
                r.first <= c.last -> c.first..maxOf(c.last, r.last)
                else -> {
                    count++
                    if (merged.size < MAX_RANGES_PER_PAGE) merged += c
                    r
                }
            }
        }
        cur?.let {
            count++
            if (merged.size < MAX_RANGES_PER_PAGE) merged += it
        }
        return merged to count
    }

    /**
     * A one-line excerpt of [pageText] around [match] (control whitespace
     * flattened, runs of spaces kept, never splitting a surrogate pair), with
     * "…" at cut ends. Every occurrence of [terms] inside the excerpt is
     * returned as a highlight; nothing else is.
     */
    fun excerpt(pageText: String, match: IntRange, terms: List<String>, radius: Int = 36): Excerpt {
        if (pageText.isEmpty()) return Excerpt("", emptyList())
        val first = match.first.coerceIn(0, pageText.length - 1)
        val last = match.last.coerceIn(first, pageText.length - 1)
        var start = maxOf(0, first - radius)
        var end = minOf(pageText.length, last + 1 + radius)
        if (start > 0 && Character.isLowSurrogate(pageText[start])) start--
        if (end < pageText.length && Character.isLowSurrogate(pageText[end])) end++
        // Prefer to start/end on a word boundary when one is close.
        if (start > 0) {
            val space = pageText.indexOfAny(charArrayOf(' ', '\n', '\t', '\r'), start)
            if (space in start until first) start = space + 1
        }
        if (end < pageText.length) {
            val space = pageText.lastIndexOfAny(charArrayOf(' ', '\n', '\t', '\r'), end - 1)
            if (space > last) end = space
        }
        val body = pageText.substring(start, end)
            .map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }
            .joinToString("")
            .trim()
        val text = buildString {
            if (start > 0) append('…')
            append(body)
            if (end < pageText.length) append('…')
        }
        val highlights = matchPage(text, terms.filter { SearchMatcher.normalize(text).contains(it) })
            ?.first.orEmpty()
        return Excerpt(text, highlights)
    }
}
