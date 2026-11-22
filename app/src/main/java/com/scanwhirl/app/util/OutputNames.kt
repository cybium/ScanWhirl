package com.scanwhirl.app.util

import java.util.Locale

/**
 * One naming rule for every tool output (council §5, lane O).
 *
 * A new copy is saved under the SOURCE STEM. There is no forced "- Compressed", "- Clean",
 * "- Signed" or any other verb suffix: the editable name field is prefilled with the source
 * stem, the user may change it, and a taken name gets " (2)", " (3)", ... . Old suffixed names
 * written by earlier versions are still read and stripped, so re-running a tool on one of them
 * never stacks suffixes. Names are sanitised with [ExportNames] (illegal file-name characters
 * removed, 60 code points, Devanagari kept), and the extension stays read-only.
 *
 * Pure and Android-free so every lane can use the same rule.
 */
object OutputNames {

    /** Short labels older builds appended, in English and Hindi (read back only). */
    private val TOOL_LABELS = listOf(
        "compressed", "clean", "signed", "watermarked", "numbered", "stamped", "redacted",
        "highlighted", "marked", "drawn", "merged", "copy", "long image", "to word",
        "to powerpoint", "business card", "passport photo", "id card",
        "संपीड़ित", "साफ़", "हस्ताक्षरित", "वॉटरमार्क", "प्रतिलिपि", "क्रमांकित", "छिपाया गया"
    )

    // A "Pages" suffix may be a range (1-3) or a comma list (1, 10, 13-15). Both are one suffix.
    private val PAGE_RANGE = "\\d+(?:\\s*-\\s*\\d+)?"
    private val SUFFIX = Regex(
        "(?i)\\s*(?:-\\s*|\\(\\s*)(" +
            TOOL_LABELS.joinToString("|") { Regex.escape(it) } +
            "|pages?\\s*$PAGE_RANGE(?:\\s*,\\s*$PAGE_RANGE)*)\\s*\\)?$"
    )

    /** A split part's old name ended in "part 2" (Hindi "भाग 2"); that is stripped too. */
    private val PART_SUFFIX = Regex("(?i)\\s*(?:-\\s*)?(?:part|भाग)\\s*\\d+$")

    /**
     * The default for a new copy: the source stem, sanitised and made unique. No verb suffix.
     * This is also the prefill of the editable name field.
     */
    fun forNewCopy(sourceName: String, existingNames: Collection<String>): String {
        val stem = ExportNames.sanitizeBase(cleanBase(sourceName))
        return unique(stem, existingNames)
    }

    /**
     * Kept for callers written against the old API. [toolLabel] is deliberately ignored: council
     * §5 removes forced verb suffixes, so every output is just a uniquely named new copy.
     */
    fun forTool(
        baseName: String,
        toolLabel: String,
        existingNames: Collection<String>
    ): String = forNewCopy(baseName, existingNames)

    /** Normalises a name typed into the editable field before it is published. */
    fun newCopyFromField(typed: String, existingNames: Collection<String>): String {
        val stem = ExportNames.sanitizeBase(
            ExportNames.stripKnownExtension(typed.trim().replace(Regex("\\s+"), " "))
        )
        return unique(stem, existingNames)
    }

    /**
     * Applies the same " (2)", " (3)", ... rule to a name a caller already built in full.
     * H3-6: Compress-to-target passes its own name and still gets a unique one.
     */
    fun uniqueName(candidate: String, existingNames: Collection<String>): String {
        val base = ExportNames.sanitizeBase(
            candidate.trim().replace(Regex("\\s+"), " ")
        )
        return unique(base, existingNames)
    }

    /**
     * A split output is "<document name> part 1", "<name> part 2", ... in page order.
     * A part number is a meaningful result, not a verb suffix, so it is kept. [partWord] is
     * localised by the caller (Hindi: "भाग"). A previous part suffix is cleaned first.
     */
    fun forSplitPart(
        baseName: String,
        part: Int,
        existingNames: Collection<String>,
        partWord: String = "part"
    ): String {
        val base = ExportNames.sanitizeBase(
            PART_SUFFIX.replace(cleanBase(baseName), "").trim()
        ).ifBlank { "Scan" }
        val word = partWord.trim().replace(Regex("\\s+"), " ").ifBlank { "part" }
        return unique("$base $word ${part.coerceAtLeast(1)}", existingNames)
    }

    /** Trims, drops a known extension and strips every old tool suffix a previous run stacked. */
    private fun cleanBase(baseName: String): String {
        var base = ExportNames.stripKnownExtension(baseName.trim()).replace(Regex("\\s+"), " ")
        while (true) {
            val match = SUFFIX.find(base) ?: break
            if (match.range.last != base.lastIndex) break
            val stripped = base.substring(0, match.range.first).trim()
            if (stripped.isEmpty()) break
            base = stripped
        }
        return if (base.isEmpty()) baseName.trim().ifBlank { "Scan" } else base
    }

    /** Appends " (2)", " (3)", ... until the name is free (case-insensitive), within 60 points. */
    private fun unique(candidate: String, existingNames: Collection<String>): String {
        val taken = existingNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val base = ExportNames.truncate(candidate, ExportNames.MAX_BASE_CODE_POINTS)
            .trim().ifBlank { "Scan" }
        if (base.lowercase(Locale.ROOT) !in taken) return base
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val room = ExportNames.MAX_BASE_CODE_POINTS - suffix.length
            val stem = ExportNames.truncate(base, room).trimEnd().ifBlank { "Scan" }
            val proposal = "$stem$suffix"
            if (proposal.lowercase(Locale.ROOT) !in taken) return proposal
            n++
        }
    }
}
