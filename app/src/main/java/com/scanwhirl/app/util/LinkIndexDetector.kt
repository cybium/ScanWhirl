package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrPage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * LX — proposes index entries from OCR line geometry.
 *
 * Pure Kotlin (no Android, no ML Kit): a scanned contents page usually prints
 * `title ....... page`, `3. Title  45` or `१२. शीर्षक … ४५`. We read the trailing
 * integer, the separator before it, the indentation and the order of the lines.
 * The result is a PROPOSAL only: the UI asks the user to confirm every row before
 * anything is written. Nothing here guesses a page offset silently.
 */
object LinkIndexDetector {

    enum class Confidence { HIGH, MEDIUM, LOW }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Parsed(val title: String, val printed: Int, val leader: Boolean, val gap: Int)

    data class Candidate(
        val title: String,
        val printedPage: Int,
        val sourcePageIndex: Int,
        val box: Box,
        val level: Int,
        val confidence: Confidence,
        val hasLeader: Boolean
    )

    private val HEADERS = setOf(
        "contents", "table of contents", "index", "subject index", "contents page",
        "अनुक्रमणिका", "विषय सूची", "विषय-सूची", "सूची", "पन्ना", "page"
    )

    /** Digit value for ASCII, Devanagari (०-९) and fullwidth digits; null otherwise. */
    fun digit(c: Char): Int? {
        val v = Character.getNumericValue(c)
        return if (v in 0..9) v else null
    }

    /** Rewrites every known decimal digit to ASCII so numbering rules work in both scripts. */
    fun asciiDigits(text: String): String = buildString(text.length) {
        for (c in text) append(digit(c)?.toString() ?: c.toString())
    }

    /**
     * Splits one OCR line into (title, printed page). Returns null for prose,
     * headers and lines whose last digits belong to the title (e.g. "COVID-19").
     */
    fun parse(raw: String): Parsed? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        var end = text.length
        while (end > 0 && (text[end - 1] == '.' || text[end - 1] == '।')) end--
        var start = end
        var value = 0
        var place = 1
        var count = 0
        while (start > 0) {
            val d = digit(text[start - 1]) ?: break
            value += d * place
            place *= 10
            count++
            start--
        }
        if (count == 0 || count > 4 || value < 1 || value > 9999) return null
        // A digit run glued to a word letter is not a page number. A leader or
        // whitespace before it is; a hyphen is deliberately NOT accepted.
        if (start > 0) {
            val prev = text[start - 1]
            if (!(prev.isWhitespace() || prev == '.' || prev == '\u00B7' || prev == '\u2026' || prev == ':')) {
                return null
            }
        }
        val before = text.substring(0, start)
        val gap = before.length - before.trimEnd().length
        val leader = before.trimEnd().let { head ->
            head.takeLastWhile { it == '.' || it == '\u00B7' || it == '\u2026' }.length >= 2 ||
                head.endsWith("\u2026") ||
                Regex("(?:\\.\\s*){3,}$").containsMatchIn(head)
        }
        val title = before.trim()
            .trim('.', '\u00B7', '\u2026', ' ', '\t', ':')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '\u00B7', '\u2026', ' ')
        if (title.length < 2 || title.none { it.isLetter() }) return null
        if (title.lowercase(Locale.ROOT) in HEADERS) return null
        return Parsed(title, value, leader, gap)
    }

    /** Detect entries across one or more index pages, keeping document reading order. */
    fun detect(pages: List<Pair<Int, OcrPage>>, pageCount: Int): List<Candidate> {
        if (pageCount <= 0) return emptyList()
        val out = mutableListOf<Candidate>()
        val seen = HashSet<String>()
        var lastPrinted = 0
        for ((pageIndex, page) in pages.sortedBy { it.first }) {
            if (page.width <= 0 || page.height <= 0) continue
            val minLeft = page.lines.filter { it.text.isNotBlank() }.minOfOrNull { it.left } ?: 0
            for (line in page.lines) {
                val parsed = parse(line.text) ?: continue
                val key = parsed.title.lowercase(Locale.ROOT) + "|" + parsed.printed
                if (!seen.add(key)) continue
                val indent = line.left - minLeft
                val level = when {
                    indent < page.width * 0.04 -> 0
                    indent < page.width * 0.10 -> 1
                    else -> 2
                }
                var score = when {
                    parsed.leader -> 3
                    parsed.gap >= 2 -> 2
                    else -> -2
                }
                score += when {
                    parsed.printed in 1..pageCount -> 2
                    parsed.printed <= pageCount + 50 -> 1
                    else -> -3
                }
                score += if (parsed.printed >= lastPrinted) 1 else -2
                val numbered = asciiDigits(parsed.title)
                val numberedSection = Regex("^\\d{1,3}[.)]\\s").containsMatchIn(numbered) ||
                    numbered.lowercase(Locale.ROOT).startsWith("chapter ") ||
                    parsed.title.startsWith("अध्याय")
                if (numberedSection) score += 1
                if (line.right >= page.width * 0.7) score += 1
                if (parsed.title.length < 3) score -= 2
                val confidence = when {
                    score >= 7 -> Confidence.HIGH
                    score >= 4 -> Confidence.MEDIUM
                    else -> Confidence.LOW
                }
                if (confidence != Confidence.LOW) lastPrinted = parsed.printed
                out += Candidate(
                    title = parsed.title,
                    printedPage = parsed.printed,
                    sourcePageIndex = pageIndex,
                    box = Box(line.left, line.top, line.right, line.bottom),
                    level = level,
                    confidence = confidence,
                    hasLeader = parsed.leader
                )
            }
        }
        return out
    }

    /** Convenience overload for a single index page. */
    fun detect(page: OcrPage, sourcePageIndex: Int, pageCount: Int): List<Candidate> =
        detect(listOf(sourcePageIndex to page), pageCount)

    /** printed page + offset -> 1-based PDF page, or null when it maps outside the document. */
    fun targetPage(printed: Int, offset: Int, pageCount: Int): Int? {
        val target = printed + offset
        return if (pageCount > 0 && target in 1..pageCount) target else null
    }

    /**
     * Suggests an offset that keeps the most entries in range and in increasing
     * order. Only a suggestion: the UI always shows the chosen value and lets the
     * user step it. Zero wins ties, so we never change the printed numbers without
     * evidence.
     */
    fun suggestOffset(candidates: List<Candidate>, pageCount: Int): Int {
        if (candidates.isEmpty() || pageCount <= 0) return 0
        var best = 0
        var bestScore = Int.MIN_VALUE
        for (offset in -100..100) {
            var score = 0
            var previous = 0
            for (c in candidates) {
                val target = targetPage(c.printedPage, offset, pageCount) ?: continue
                score += 2
                if (target >= previous) score += 1
                previous = target
            }
            if (score > bestScore || (score == bestScore && kotlin.math.abs(offset) < kotlin.math.abs(best))) {
                bestScore = score
                best = offset
            }
        }
        return best
    }

    /** Pixel box -> rounded PDF points, using the same top-left to bottom-left transform as the text layer. */
    internal fun toPdfRect(box: Box, pageWidthPx: Int, pageHeightPx: Int, pageWidthPt: Int, pageHeightPt: Int): IntArray {
        val sx = pageWidthPt.toDouble() / pageWidthPx.coerceAtLeast(1)
        val sy = pageHeightPt.toDouble() / pageHeightPx.coerceAtLeast(1)
        val l = (box.left * sx).roundToInt().coerceIn(0, pageWidthPt)
        val r = (box.right * sx).roundToInt().coerceIn(l + 1, pageWidthPt)
        val t = (box.top * sy).roundToInt().coerceIn(0, pageHeightPt)
        val b = (box.bottom * sy).roundToInt().coerceIn(t + 1, pageHeightPt)
        return intArrayOf(l, pageHeightPt - b, r, pageHeightPt - t)
    }
}
