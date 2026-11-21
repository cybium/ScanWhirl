package com.scanwhirl.app.util

import androidx.annotation.StringRes
import com.scanwhirl.app.R

/**
 * Parses the page-range syntax the tools use: `1-3, 5, 8-`.
 *
 * A bare number is one page, `a-b` is an inclusive range, and `a-` runs to the last page.
 * En and em dashes are accepted (phones type them), and so are stray spaces. Every page must
 * exist in the document, which catches a typo before anything is written.
 *
 * P1: failures carry a string resource instead of English prose. The caller renders them
 * with `getString`, so an error typed in Hindi is never shown as an English sentence.
 */
object PageRanges {

    /** A user-facing range error that can be localized at the display site. */
    class InvalidPageRange(
        @StringRes val messageRes: Int,
        val formatArgs: List<Any> = emptyList()
    ) : IllegalArgumentException()

    /** One 1-based, inclusive page span. */
    data class Span(val from: Int, val to: Int) {
        init {
            require(from >= 1) { "Page numbers start at 1" }
            require(to >= from) { "Page range runs backwards" }
        }

        val size: Int get() = to - from + 1

        /** Zero-based page indices this span covers. */
        fun indices(): IntRange = (from - 1)..(to - 1)
    }

    private val PART = Regex("^(\\d+)\\s*(?:-\\s*(\\d*))?$")

    /**
     * @throws InvalidPageRange for an empty range, a page outside `1..pageCount`,
     *   a backwards range or a part that is not a number.
     */
    fun parse(text: String, pageCount: Int): List<Span> {
        if (pageCount < 1) throw InvalidPageRange(R.string.p1_page_range_no_pages)
        val normal = text.replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-')
        val parts = normal.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw InvalidPageRange(R.string.p1_page_range_empty)
        return parts.map { part ->
            fun unreadable(): InvalidPageRange =
                InvalidPageRange(R.string.p1_page_range_cannot_read, listOf(part))
            val match = PART.find(part) ?: throw unreadable()
            val from = match.groupValues[1].toIntOrNull() ?: throw unreadable()
            val hasDash = part.contains('-')
            val to = when {
                !hasDash -> from
                match.groupValues[2].isBlank() -> pageCount
                else -> match.groupValues[2].toIntOrNull() ?: throw unreadable()
            }
            if (from !in 1..pageCount) {
                throw InvalidPageRange(R.string.p1_page_range_outside, listOf(from, pageCount))
            }
            if (to !in 1..pageCount) {
                throw InvalidPageRange(R.string.p1_page_range_outside, listOf(to, pageCount))
            }
            if (to < from) throw InvalidPageRange(R.string.p1_page_range_backwards, listOf(part))
            Span(from, to)
        }
    }

    /** `page 2` or `pages 1-3`, for a document name. */
    fun label(span: Span): String =
        if (span.from == span.to) "page ${span.from}" else "pages ${span.from}-${span.to}"
}
