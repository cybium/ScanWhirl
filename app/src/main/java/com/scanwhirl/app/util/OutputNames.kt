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
        "संपीड़ित", "साफ़", "हस्ताक्षरित", "वॉटरमार्क", "प्रतिलिपि", "क्रमांकित", "छिपाया गया"
    )

    // C1: a "Pages" suffix may be a range (1-3) or a comma list (1, 10, 13-15). Both are one
    // suffix, so neither may stack when the next tool names its output.
    private val PAGE_RANGE = "\\d+(?:\\s*-\\s*\\d+)?"
    private val SUFFIX = Regex(
        "(?i)\\s*(?:-\\s*|\\(\\s*)(" +
            TOOL_LABELS.joinToString("|") { Regex.escape(it) } +
            "|pages?\\s*$PAGE_RANGE(?:\\s*,\\s*$PAGE_RANGE)*)\\s*\\)?$"
    )

    /** Owner F9: a split part's old name ended in "part 2" (Hindi "भाग 2"). That is stripped too. */
    private val PART_SUFFIX = Regex("(?i)\\s*(?:-\\s*)?(?:part|भाग)\\s*\\d+$")

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
        val base = cleanBase(baseName)
        val label = toolLabel.trim().replace(Regex("\\s+"), " ").ifBlank { "Copy" }
        return unique("$base - $label", existingNames)
    }

    /**
     * Applies the same " (2)", " (3)", ... rule to a name a caller already built in full.
     * H3-6: Compress-to-target passes its own "<name> (compressed)" and still gets a unique one.
     */
    fun uniqueName(candidate: String, existingNames: Collection<String>): String {
        val base = candidate.trim().replace(Regex("\\s+"), " ").ifBlank { "Scan" }
        return unique(base, existingNames)
    }

    /**
     * Owner F9: a split output is "<document name> part 1", "<name> part 2", ... in page order.
     * [partWord] is localised by the caller (Hindi: "भाग"). A name that a previous split already
     * gave a part suffix is cleaned first, so parts never stack.
     */
    fun forSplitPart(
        baseName: String,
        part: Int,
        existingNames: Collection<String>,
        partWord: String = "part"
    ): String {
        val base = PART_SUFFIX.replace(cleanBase(baseName), "").trim().ifBlank { "Scan" }
        val word = partWord.trim().replace(Regex("\\s+"), " ").ifBlank { "part" }
        return unique("$base $word ${part.coerceAtLeast(1)}", existingNames)
    }

    /** Trims, collapses spaces and strips every tool suffix a previous run stacked. */
    private fun cleanBase(baseName: String): String {
        var base = baseName.trim().replace(Regex("\\s+"), " ")
        while (true) {
            val match = SUFFIX.find(base) ?: break
            if (match.range.last != base.lastIndex) break
            val stripped = base.substring(0, match.range.first).trim()
            if (stripped.isEmpty()) break
            base = stripped
        }
        return if (base.isEmpty()) baseName.trim().ifBlank { "Scan" } else base
    }

    /** Appends " (2)", " (3)", ... until the name is free (case-insensitive). */
    private fun unique(candidate: String, existingNames: Collection<String>): String {
        val taken = existingNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        var n = 2
        while ("$candidate ($n)".lowercase(Locale.ROOT) in taken) n++
        return "$candidate ($n)"
    }
}
