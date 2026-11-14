package com.scanwhirl.app.util

import java.util.Locale

/**
 * One naming rule for every tool output, so suffixes never stack:
 * `Document 1 (page 1) (compressed) (signed) (watermarked)` becomes `Document 1 - Watermarked`.
 *
 * The output is `base - short tool label`, with any suffix a previous tool added removed first.
 * If the result already exists, ` (2)`, ` (3)`, ... is appended. Pure and Android-free so every
 * lane (including L5's stamp tools) can use the same rule.
 */
object OutputNames {

    /** Short labels our tools append, in English and Hindi (review copy). */
    private val TOOL_LABELS = listOf(
        "compressed", "clean", "signed", "watermarked", "numbered", "stamped", "redacted",
        "highlighted", "marked", "drawn", "merged", "copy", "long image", "to word",
        "to powerpoint", "business card", "passport photo", "id card",
        "संपीड़ित", "साफ़", "हस्ताक्षरित", "वॉटरमार्क", "प्रतिलिपि", "क्रमांकित"
    )

    private val SUFFIX = Regex(
        "(?i)\\s*(?:-\\s*|\\(\\s*)(" +
            TOOL_LABELS.joinToString("|") { Regex.escape(it) } +
            "|pages?\\s*\\d+(?:\\s*-\\s*\\d+)?)\\s*\\)?$"
    )

    /**
     * @param baseName the name a previous tool produced (or the document's own name).
     * @param toolLabel the short label of the tool now writing, e.g. "Compressed", "Pages 2-3".
     * @param existingNames every name already taken in the library.
     * @return the new, unique document name.
     */
    fun forTool(
        baseName: String,
        toolLabel: String,
        existingNames: Collection<String>
    ): String {
        var base = baseName.trim().replace(Regex("\\s+"), " ")
        // Strip every suffix a previous run stacked, outermost last.
        while (true) {
            val match = SUFFIX.find(base) ?: break
            if (match.range.last != base.lastIndex) break
            val stripped = base.substring(0, match.range.first).trim()
            if (stripped.isEmpty()) break
            base = stripped
        }
        if (base.isEmpty()) base = baseName.trim().ifBlank { "Scan" }
        val label = toolLabel.trim().replace(Regex("\\s+"), " ").ifBlank { "Copy" }
        val candidate = "$base - $label"
        val taken = existingNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        var n = 2
        while ("$candidate ($n)".lowercase(Locale.ROOT) in taken) n++
        return "$candidate ($n)"
    }
}
