package com.scanwhirl.app.util

/**
 * v2 UI: 03_tools_hub — the pre-check before importing a device PDF.
 *
 * Importing renders every page to a JPEG, so a 500-page textbook can fill the phone. The plan is
 * computed from the page count and free space *before* any page is rendered:
 *  - a typical page is [TYPICAL_PAGE_BYTES] at import resolution (2048 px, JPEG 85);
 *  - [Plan.needsDecision] when there are more than [DECISION_PAGE_COUNT] pages or the estimate is
 *    more than a quarter of free space;
 *  - [Plan.tooLittleSpace] refuses before starting when the import would leave less than
 *    [MIN_FREE_AFTER] free.
 */
object PdfImportBudget {

    /** Typical rendered text page on disk at 2048 px / JPEG 85. */
    const val TYPICAL_PAGE_BYTES = 600_000L

    /** Small documents never ask; a page range is offered after this many pages. */
    const val DECISION_PAGE_COUNT = 50

    /** Never finish an import with less than this free. */
    const val MIN_FREE_AFTER = 500L * 1024 * 1024

    /** A safety ceiling; range selection handles bigger books. */
    const val MAX_PAGES = 10_000

    data class Plan(
        val pageCount: Int,
        val sourceBytes: Long,
        val estimateBytes: Long,
        val freeBytes: Long
    ) {
        val needsDecision: Boolean
            get() = pageCount > DECISION_PAGE_COUNT || estimateBytes > freeBytes / 4

        val tooLittleSpace: Boolean
            get() = freeBytes - estimateBytes < MIN_FREE_AFTER

        /** Estimate for a chosen subset, for the range sheet. */
        fun estimateFor(pages: Int): Long = estimateBytes / pageCount.coerceAtLeast(1) * pages
    }

    /** The typical JPEG size at [maxDim] (imports default to 2048). */
    fun pageBytes(maxDim: Int): Long = when {
        maxDim >= 2000 -> TYPICAL_PAGE_BYTES
        maxDim >= 1600 -> 450_000L
        else -> 320_000L
    }

    fun plan(
        pageCount: Int,
        freeBytes: Long,
        bytesPerPage: Long = TYPICAL_PAGE_BYTES
    ): Plan = Plan(
        pageCount = pageCount.coerceAtLeast(0),
        sourceBytes = 0L,
        estimateBytes = pageCount.coerceAtLeast(0) * bytesPerPage,
        freeBytes = freeBytes
    )

    /** 0-based page indices for a ranges field ("1-20, 45"); null when it does not parse. */
    fun indices(text: String, pageCount: Int): List<Int>? = try {
        PageRanges.parse(text, pageCount)
            .flatMap { it.indices().toList() }
            .distinct()
            .sorted()
            .takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }
}
