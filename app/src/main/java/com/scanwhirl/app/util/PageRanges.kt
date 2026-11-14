package com.scanwhirl.app.util

/**
 * Parses the page-range syntax the tools use: `1-3, 5, 8-`.
 *
 * A bare number is one page, `a-b` is an inclusive range, and `a-` runs to the last page.
 * En and em dashes are accepted (phones type them), and so are stray spaces. Every page must
 * exist in the document, which catches a typo before anything is written.
 */
object PageRanges {

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
     * @throws IllegalArgumentException with a sentence a user can act on, for an empty range,
     *   a page outside `1..pageCount`, a backwards range or a part that is not a number.
     */
    fun parse(text: String, pageCount: Int): List<Span> {
        require(pageCount >= 1) { "This document has no pages." }
        val normal = text.replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-')
        val parts = normal.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Type page numbers, for example 1-3, 5, 8-" }
        return parts.map { part ->
            val match = PART.find(part)
                ?: throw IllegalArgumentException("Can't read \"$part\". Use 1-3, 5, 8-.")
            val from = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException("Can't read \"$part\". Use 1-3, 5, 8-.")
            val hasDash = part.contains('-')
            val to = when {
                !hasDash -> from
                match.groupValues[2].isBlank() -> pageCount
                else -> match.groupValues[2].toIntOrNull()
                    ?: throw IllegalArgumentException("Can't read \"$part\". Use 1-3, 5, 8-.")
            }
            require(from in 1..pageCount) { "Page $from is not in this document (1-$pageCount)." }
            require(to in 1..pageCount) { "Page $to is not in this document (1-$pageCount)." }
            require(to >= from) { "\"$part\" runs backwards." }
            Span(from, to)
        }
    }

    /** `page 2` or `pages 1-3`, for a document name. */
    fun label(span: Span): String =
        if (span.from == span.to) "page ${span.from}" else "pages ${span.from}-${span.to}"
}
