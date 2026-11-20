package com.scanwhirl.app.util

import java.util.Locale

/**
 * The one place that turns a document's display name into an outgoing file name (share, ZIP,
 * Documents copy, Word/Excel/PowerPoint/image exports). H2-1/H2-3/H2-6/H2-7:
 *
 *  - only a KNOWN file extension is ever treated as an extension, so `12.05.2025 Rent receipt`
 *    keeps its whole name instead of becoming `12.05`;
 *  - the 60-character limit applies to the base name only, counted in code points and never
 *    cutting through a Devanagari conjunct/matra, an emoji sequence or a surrogate pair, so the
 *    extension always survives;
 *  - duplicates inside one share/ZIP get `Name (2).pdf`, compared case-insensitively because
 *    the receiving side may be a case-insensitive file system.
 */
object ExportNames {

    /** Limit for the base name (without extension), in Unicode code points. */
    const val MAX_BASE_CODE_POINTS = 60

    /** Extensions that are really extensions. Anything else after a dot is part of the name. */
    private val KNOWN_EXTENSIONS = setOf(
        "pdf", "jpg", "jpeg", "png", "docx", "xlsx", "pptx", "txt", "zip",
        "webp", "gif", "bmp", "heic", "doc", "xls", "ppt", "csv"
    )

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    /** Indic viramas: a cut right after one would leave a dangling half-letter. */
    private val VIRAMAS = setOf(
        0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D4D, 0x0DCA, 0x0E3A
    )

    /** True when [ext] (no dot) is a file extension this app knows. */
    fun isKnownExtension(ext: String): Boolean = ext.lowercase(Locale.ROOT) in KNOWN_EXTENSIONS

    /**
     * Splits [name] into the part before a trailing chain of known extensions and that chain
     * (including the dots, e.g. `.jpg.zip`). The chain is at most two extensions long and the
     * stem is never empty. Unknown "extensions" stay in the stem.
     */
    fun splitKnownSuffix(name: String): Pair<String, String> {
        var end = name.length
        var count = 0
        while (count < 2) {
            val dot = name.lastIndexOf('.', end - 1)
            if (dot <= 0) break
            if (!isKnownExtension(name.substring(dot + 1, end))) break
            end = dot
            count++
        }
        return name.substring(0, end) to name.substring(end)
    }

    /** [name] without one trailing known extension (`Rent.pdf` -> `Rent`, `12.05.2025 Rent` unchanged). */
    fun stripKnownExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || !isKnownExtension(name.substring(dot + 1))) return name
        return name.substring(0, dot)
    }

    /** Replaces file-name characters a phone cannot store, trims, and falls back when nothing is left. */
    fun sanitizeBase(name: String, fallback: String = "Scan", max: Int = MAX_BASE_CODE_POINTS): String {
        val cleaned = trimJunk(name.replace(ILLEGAL, "_"))
        if (cleaned.isEmpty()) return fallback
        return trimJunk(truncate(cleaned, max)).ifEmpty { fallback }
    }

    /**
     * The base name for an export of a document called [docName]: one trailing known extension
     * removed, then sanitised and limited. `baseName("12.05.2025 Rent receipt")` is unchanged.
     */
    fun baseName(docName: String, fallback: String = "Scan"): String =
        sanitizeBase(stripKnownExtension(docName.trim()), fallback)

    /** `<base name>.<extension>`, e.g. `fileName("Rent.pdf", "docx")` = `Rent.docx`. */
    fun fileName(docName: String, extension: String, fallback: String = "Scan"): String =
        baseName(docName, fallback) + "." + extension.trimStart('.')

    /**
     * Sanitises a complete file name: the base is limited (60 code points), the known
     * extension chain is kept intact (`<60 chars>.pdf`, `<60 chars>.jpg.zip`).
     */
    fun sanitizeFileName(fileName: String, fallback: String = "Scan"): String {
        val (stem, suffix) = splitKnownSuffix(fileName.trim())
        return sanitizeBase(stem, fallback) + suffix.lowercase(Locale.ROOT)
    }

    /** True when [fileName] already ends in a known extension. */
    fun hasKnownExtension(fileName: String): Boolean = splitKnownSuffix(fileName).second.isNotEmpty()

    /** Appends " (2)", " (3)", ... before the known extension chain until [candidate] is free. */
    fun uniqueName(candidate: String, taken: (String) -> Boolean): String {
        if (!taken(candidate)) return candidate
        val (stem, suffix) = splitKnownSuffix(candidate)
        var n = 2
        while (taken("$stem ($n)$suffix")) n++
        return "$stem ($n)$suffix"
    }

    /** Same as [uniqueName] for a folder name (there is no extension to keep). */
    fun uniqueFolder(candidate: String, taken: (String) -> Boolean): String {
        if (!taken(candidate)) return candidate
        var n = 2
        while (taken("$candidate ($n)")) n++
        return "$candidate ($n)"
    }

    /** Hands out unique names inside ONE share or ZIP (case-insensitive). */
    class Allocator {
        private val used = HashSet<String>()

        fun file(candidate: String): String =
            uniqueName(candidate) { it.lowercase(Locale.ROOT) in used }
                .also { used += it.lowercase(Locale.ROOT) }

        fun folder(candidate: String): String =
            uniqueFolder(candidate) { it.lowercase(Locale.ROOT) in used }
                .also { used += it.lowercase(Locale.ROOT) }
    }

    /**
     * Cuts [text] to at most [max] code points without ever splitting a character cluster:
     * a surrogate pair, a base letter and its matra/combining sign, a virama conjunct, an emoji
     * ZWJ sequence or a flag pair. If the text is one unbreakable cluster the hard cut is used.
     */
    fun truncate(text: String, max: Int): String {
        require(max > 0) { "max must be positive" }
        if (text.codePointCount(0, text.length) <= max) return text
        val hardCut = text.offsetByCodePoints(0, max)
        var cut = hardCut
        while (cut > 0 && splitsCluster(text, cut)) cut = text.offsetByCodePoints(cut, -1)
        if (cut == 0) cut = hardCut
        return text.substring(0, cut)
    }

    /** True when a cut at [index] (between two code points) would tear a cluster apart. */
    private fun splitsCluster(text: String, index: Int): Boolean {
        val next = text.codePointAt(index)
        val prev = text.codePointBefore(index)
        if (isExtender(next)) return true
        if (prev == ZWJ || prev in VIRAMAS) return true
        if (isRegionalIndicator(prev)) {
            var run = 0
            var i = index
            while (i > 0 && isRegionalIndicator(text.codePointBefore(i))) {
                run++
                i = text.offsetByCodePoints(i, -1)
            }
            if (run % 2 == 1) return true
        }
        return false
    }

    private fun isExtender(cp: Int): Boolean {
        if (cp == ZWJ || cp == ZWNJ) return true
        if (cp in 0xFE00..0xFE0F) return true // variation selectors
        if (cp in 0x1F3FB..0x1F3FF) return true // emoji skin tones
        if (cp in 0xE0020..0xE007F) return true // emoji tag sequences
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun trimJunk(s: String): String = s.trim { it == '_' || it == '.' || it.isWhitespace() }
}
