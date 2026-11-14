package com.scanwhirl.app.util

/**
 * One recognised line before the recognizer seam is crossed: text, geometry and the script
 * that produced it. Pure data, so tests can build pages without ML Kit.
 */
data class OcrRawLine(
    val text: String,
    val box: OcrBox,
    val script: OcrScript = OcrScript.LATIN,
    val confidence: Float = 1f
)

/** A recognised page: pixel size plus every line, in any order. */
data class OcrRawPage(
    val width: Int,
    val height: Int,
    val lines: List<OcrRawLine>
)

/**
 * Text hygiene at the recognizer boundary.
 *
 * ML Kit occasionally hands back U+FFFD (the Unicode replacement character) where it could not
 * classify a glyph, and a text can carry unpaired surrogates or control characters. Stored text
 * must never contain any of those: they survive every save, break search and look like damage
 * in the viewer. Everything here is pure and unit-tested.
 */
object OcrText {

    const val REPLACEMENT = '\uFFFD'

    /**
     * Drops U+FFFD and unpaired surrogates, turns tabs/newlines/control characters into single
     * spaces, maps no-break spaces to spaces and collapses runs of spaces. Devanagari and every
     * other valid character is kept exactly.
     */
    fun sanitize(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var pendingSpace = false
        while (i < text.length) {
            val c = text[i]
            when {
                c == REPLACEMENT -> {
                    // A replacement character stands for one unreadable glyph: keep word spacing.
                    pendingSpace = out.isNotEmpty()
                }
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    appendChar(out, c, pendingSpace)
                    appendChar(out, text[i + 1], false)
                    pendingSpace = false
                    i++
                }
                Character.isSurrogate(c) -> {
                    // A lone half of a pair is meaningless: drop it without inventing a space.
                }
                c == '\u00A0' || c == '\u2007' || c == '\u202F' -> pendingSpace = out.isNotEmpty()
                c == '\t' || c == '\r' || c == '\n' -> pendingSpace = out.isNotEmpty()
                c.code < 0x20 -> pendingSpace = out.isNotEmpty()
                c == ' ' -> pendingSpace = out.isNotEmpty()
                else -> {
                    appendChar(out, c, pendingSpace)
                    pendingSpace = false
                }
            }
            i++
        }
        return out.toString().trim()
    }

    private fun appendChar(out: StringBuilder, c: Char, space: Boolean) {
        if (space && out.isNotEmpty() && out.last() != ' ') out.append(' ')
        out.append(c)
    }

    /** True when [text] contains at least one Devanagari character. */
    fun hasDevanagari(text: String): Boolean =
        text.any { it.code in 0x0900..0x097F || it.code in 0xA8E0..0xA8FF || it.code in 0x1CD0..0x1CFF }

    /** True when the text looks like Latin script (letters only). */
    fun looksLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val latin = letters.count { it.code < 0x0250 }
        return latin * 10 >= letters.length * 6
    }
}

/**
 * Merges the recognizers of a [OcrLanguage] into one page, then puts it in reading order.
 *
 * Two recognizers see the same page: the Latin model returns gibberish for Devanagari and the
 * Devanagari model returns gibberish for Latin, so for every visual line ([OcrLayout.sameLine])
 * the candidate that actually contains Devanagari characters wins when there is one, otherwise
 * the more confident candidate wins. Lines only one recognizer saw are kept.
 */
object OcrMerge {

    fun merge(pages: List<OcrRawPage>, rotationDegrees: Int = 0): OcrRawPage {
        val nonEmpty = pages.filter { it.lines.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            val first = pages.firstOrNull()
            return OcrRawPage(first?.width ?: 0, first?.height ?: 0, emptyList())
        }
        if (nonEmpty.size == 1) return ordered(nonEmpty.first(), rotationDegrees)

        val base = nonEmpty.first()
        val others = nonEmpty.drop(1)
        val merged = ArrayList<OcrRawLine>(base.lines.size)

        for (line in base.lines) {
            var best = line
            for (page in others) {
                val candidate = page.lines.firstOrNull { OcrLayout.sameLine(it.box, line.box) } ?: continue
                best = pick(best, candidate)
            }
            merged += best
        }
        // Lines that only the other recognizers saw.
        for (page in others) {
            for (line in page.lines) {
                val seen = base.lines.any { OcrLayout.sameLine(it.box, line.box) } ||
                    merged.any { OcrLayout.sameLine(it.box, line.box) }
                if (!seen) merged += line
            }
        }
        return ordered(OcrRawPage(base.width, base.height, merged), rotationDegrees)
    }

    /** The text with a usable script wins; otherwise the more confident line. */
    fun pick(a: OcrRawLine, b: OcrRawLine): OcrRawLine {
        val aDevanagari = OcrText.hasDevanagari(a.text)
        val bDevanagari = OcrText.hasDevanagari(b.text)
        return when {
            aDevanagari && !bDevanagari -> a
            bDevanagari && !aDevanagari -> b
            else -> if (a.confidence >= b.confidence) a else b
        }
    }

    /** Orders lines (columns, spread, rotation) and cleans every line. */
    fun ordered(page: OcrRawPage, rotationDegrees: Int = 0): OcrRawPage {
        val units = page.lines
            .map { OcrUnit(it.box, it.copy(text = OcrText.sanitize(it.text))) }
            .filter { it.value.text.isNotEmpty() }
        val sorted = OcrLayout.order(units, page.width, page.height, rotationDegrees).map { it.value }
        return OcrRawPage(page.width, page.height, sorted)
    }

    /**
     * Joins lines into paragraphs: a line break inside a paragraph, a blank line where the
     * vertical gap is clearly larger than the page's usual line spacing (also between columns).
     */
    fun assemble(page: OcrRawPage): String {
        val lines = page.lines
        if (lines.isEmpty()) return ""
        val heights = lines.map { it.box.height.coerceAtLeast(1) }.sorted()
        val typical = heights[heights.size / 2].coerceAtLeast(1)
        val threshold = (typical * 1.6f).toInt().coerceAtLeast(typical + 1)
        val out = StringBuilder()
        var previous: OcrRawLine? = null
        for (line in lines) {
            if (previous != null) {
                val gap = line.box.top - previous.box.bottom
                if (gap > threshold) out.append("\n\n") else out.append('\n')
            }
            out.append(line.text)
            previous = line
        }
        return out.toString().trim()
    }
}
