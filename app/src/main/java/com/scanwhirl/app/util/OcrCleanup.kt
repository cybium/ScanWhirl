package com.scanwhirl.app.util

import kotlin.text.RegexOption

/**
 * Pure text-cleanup functions for the OCR Editor (P6, §4.7).
 *
 * ML Kit returns each text block as its own paragraph, so raw OCR output is
 * full of spurious line breaks. These functions let the owner clean it up
 * before copying or saving. All functions are pure and side-effect free so
 * they can be unit-tested without Android.
 */
object OcrCleanup {

    /** Lines starting with these are bullet/list items — never joined across lines. */
    private val BULLET_START = Regex("^[\\-•*]|^\\d+\\.")

    /**
     * Inside a paragraph (text separated by blank lines) replace single newlines
     * with a space. Blank-line paragraph breaks are kept.
     *
     * De-hyphenates `exam-\nple` → `example` when the character after the hyphen's
     * line break is lowercase (i.e. a true word split, not a dash).
     *
     * Bullet/list lines (starting with `-`, `•`, `*` or `N.`) are paragraph starts
     * and are never joined to the next line.
     */
    fun joinLines(text: String): String {
        if (text.isBlank()) return text
        val paragraphs = text.split(Regex("\n{2,}"))
        return paragraphs.joinToString("\n\n") { para -> joinParagraph(para) }
    }

    private fun joinParagraph(para: String): String {
        val lines = para.lines()
        if (lines.size <= 1) return para
        // Bullet/list paragraph: keep as-is.
        if (BULLET_START.containsMatchIn(lines.first())) return para
        val sb = StringBuilder()
        var prev: String? = null
        for (line in lines) {
            if (prev == null) {
                sb.append(line)
            } else {
                // De-hyphenate: previous line ends with '-' and this line starts lowercase.
                if (prev.endsWith("-") && line.firstOrNull()?.isLowerCase() == true) {
                    sb.setLength(sb.length - 1) // drop the hyphen
                    sb.append(line)
                } else {
                    sb.append(" ")
                    sb.append(line)
                }
            }
            prev = line
        }
        return sb.toString()
    }

    /**
     * Collapse runs of spaces/tabs to a single space, trim leading/trailing
     * whitespace on each line, and collapse runs of 3+ newlines to 2.
     */
    fun fixSpaces(text: String): String {
        if (text.isBlank()) return text
        var result = text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("^[ \\t]+|[ \\t]+$", RegexOption.MULTILINE), "")
        result = result.replace(Regex("\n{3,}"), "\n\n")
        return result.trim()
    }

    /**
     * Collapse every run of blank lines to a single newline, then trim.
     * Needed because ML Kit returns each text block as its own paragraph —
     * [joinLines] alone does nothing on that kind of input.
     */
    fun removeGaps(text: String): String {
        if (text.isBlank()) return text
        return text.replace(Regex("\n{2,}"), "\n").trim()
    }
}
