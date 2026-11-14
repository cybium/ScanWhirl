package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import java.util.Locale

/**
 * Builds an invisible PDF text layer for one recognised page.
 *
 * `contentFor(page, pageW, pageH)` returns the per-line content-stream fragment that maps
 * recognised [OcrPage] geometry onto the rendered page (pageW × pageH points):
 *
 *     /Span << /ActualText <FEFF…UTF-16BE…> >> BDC
 *     BT /F1 <fs> Tf 3 Tr <tz> Tz 1 0 0 1 <x> <y> Tm (<text>) Tj ET
 *     EMC
 *
 * Two things matter for search:
 *  - **`/ActualText`** carries the *exact* recognised line as a UTF-16BE string (PDF 1.5
 *    marked content). Every conforming extractor (MuPDF, Poppler, Acrobat) returns that text,
 *    so Devanagari, curly quotes, "₹" and any other Unicode survive extraction. The `Tj`
 *    inside is the visual fallback and stays inside the invisible Helvetica/WinAnsi glyphs.
 *  - The fallback is sanitised (never adds characters, never splits a surrogate pair), so a
 *    viewer that ignores `/ActualText` still gets readable Latin text and no replacement
 *    characters.
 *
 * The font dictionary is inline (no new objects):
 *
 *     /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica
 *              /Encoding /WinAnsiEncoding >> >>
 */
object PdfTextLayer {

    private const val MAX_LINES = 600
    private const val MAX_CHARS_PER_LINE = 400

    /** The inline font dictionary added to the page /Resources. */
    const val FONT_DICT =
        "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >> >>"

    /**
     * Sanitises recognised text for the visible fallback inside a PDF text-showing operator:
     * - keeps U+0020–U+007E and U+00A0–U+00FF,
     * - maps curly quotes → straight quotes, en/em dash → -, ellipsis → ...,
     * - every other character → a space (never a replacement character),
     * - collapses runs of spaces,
     * - escapes `\` `(` `)` and writes U+00A0–U+00FF as three-digit octal.
     */
    private fun sanitize(text: String): String {
        val out = StringBuilder(text.length)
        var prevSpace = true // so leading spaces collapse
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val mapped = when (c) {
                '\u201C', '\u201D' -> '"'
                '\u2018', '\u2019' -> '\''
                '\u2013', '\u2014' -> '-'
                else -> c
            }
            if (c == '\u2026') {
                out.append("...")
                prevSpace = false
                i++
                continue
            }
            when {
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    // A surrogate pair is one glyph: keep a single space, never half a pair.
                    if (!prevSpace) out.append(' ')
                    prevSpace = true
                    i++
                }
                mapped in '\u0020'..'\u007E' -> {
                    if (mapped == ' ') {
                        if (!prevSpace) out.append(' ')
                        prevSpace = true
                    } else {
                        out.append(mapped)
                        prevSpace = false
                    }
                }
                mapped.code in 0x00A0..0x00FF -> {
                    out.append("\\%03o".format(mapped.code))
                    prevSpace = false
                }
                else -> {
                    if (!prevSpace) {
                        out.append(' ')
                        prevSpace = true
                    }
                }
            }
            i++
        }
        return out.toString().trim()
    }

    /** Escapes `\` `(` `)` for safe placement inside a PDF `(...) Tj` operator. */
    private fun escapeForTextShow(text: String): String =
        text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    /** The exact line as a PDF UTF-16BE hex string (with the required BOM), or null when empty. */
    fun actualTextHex(text: String): String? {
        if (text.isEmpty()) return null
        val bytes = text.toByteArray(Charsets.UTF_16BE)
        // UTF-16BE always starts with a BOM in the PDF string; add it explicitly.
        val sb = StringBuilder(4 + bytes.size * 2)
        sb.append("<FEFF")
        bytes.forEach { b -> sb.append("%02X".format(b.toInt() and 0xFF)) }
        sb.append('>')
        return sb.toString()
    }

    /** Approximate glyph width in em units for one character. */
    private fun approxWidthEm(c: Char): Double = when {
        c == ' ' || c in "iljtf.,:;'!|I()[]".toSet() -> 0.28
        c in "mwM W@%".toSet() -> 0.85
        c in 'A'..'Z' -> 0.68
        c in '0'..'9' -> 0.556
        c in '\u0900'..'\u097F' -> 0.6 // Devanagari: a fixed, harmless estimate
        else -> 0.5
    }

    /**
     * Builds the content-stream fragment for [page] mapped onto a page of
     * [pageW] × [pageH] points. Returns one text-showing operation per
     * recognised line, each terminated with a newline. Skips blank lines and
     * lines beyond [MAX_LINES] / [MAX_CHARS_PER_LINE].
     */
    fun contentFor(page: OcrPage, pageW: Int, pageH: Int): String {
        if (page.width <= 0 || page.height <= 0) return ""
        val sx = pageW.toDouble() / page.width
        val sy = pageH.toDouble() / page.height

        val out = StringBuilder()
        var emitted = 0
        for (line in page.lines) {
            if (emitted >= MAX_LINES) break
            // The exact text is trimmed of control characters only; the fallback is sanitised.
            val exact = exactLine(line.text)
            if (exact.isBlank()) continue
            val clean = sanitize(exact)
            if (clean.isBlank()) continue
            val fallback = if (clean.length > MAX_CHARS_PER_LINE) {
                val cut = clean.lastIndexOf(' ', MAX_CHARS_PER_LINE - 1)
                val trimmed = if (cut >= 0) clean.substring(0, cut) else clean.substring(0, MAX_CHARS_PER_LINE)
                if (trimmed.isBlank()) continue else trimmed
            } else {
                clean
            }
            val exactForActual = if (exact.length > MAX_CHARS_PER_LINE * 4) {
                exact.take(MAX_CHARS_PER_LINE * 4).let { safeCut(it) }
            } else {
                exact
            }
            out.append(textOp(line.copy(text = fallback), exactForActual, sx, sy, pageW, pageH))
            emitted++
        }
        return out.toString()
    }

    /** Keeps the line text but removes line breaks/tabs (one line per operator). */
    private fun exactLine(text: String): String =
        text.replace("\r", " ").replace("\n", " ").replace("\t", " ").trim()

    /** Cuts at a code point boundary so a surrogate pair is never split. */
    private fun safeCut(text: String): String =
        if (text.isNotEmpty() && Character.isHighSurrogate(text.last())) text.dropLast(1) else text

    /** Builds one `BT … Tj ET` text-showing operation for [line], wrapped in `/ActualText`. */
    private fun textOp(
        line: OcrLine,
        exact: String,
        sx: Double,
        sy: Double,
        pageW: Int,
        pageH: Int
    ): String {
        val boxH = (line.bottom - line.top).toDouble() * sy
        val boxW = (line.right - line.left).toDouble() * sx
        val fs = (boxH * 0.8).coerceIn(4.0, 72.0)
        val natural = line.text.map { approxWidthEm(it) }.sum() * fs
        val tz = if (natural > 0) (boxW / natural * 100.0).coerceIn(20.0, 300.0) else 100.0
        val x = line.left.toDouble() * sx
        val y = pageH.toDouble() - line.bottom.toDouble() * sy + boxH * 0.2

        val actual = actualTextHex(exact)
        val prefix = if (actual != null) "/Span << /ActualText $actual >> BDC " else ""
        val suffix = if (actual != null) " EMC" else ""

        return "$prefix" +
            "BT /F1 ${"%.2f".format(Locale.ROOT, fs)} Tf 3 Tr ${"%.2f".format(Locale.ROOT, tz)} Tz " +
            "1 0 0 1 ${"%.2f".format(Locale.ROOT, x)} ${"%.2f".format(Locale.ROOT, y)} Tm " +
            "(${escapeForTextShow(line.text)}) Tj ET" +
            "$suffix\n"
    }
}
