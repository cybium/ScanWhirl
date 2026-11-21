package com.scanwhirl.app.store

/**
 * v2 UI: 03_tools_hub — folders and tags live in the existing `meta.txt` as two
 * extra key lines after the three fixed ones:
 *
 *     folder=<name>
 *     tags=<tag><tag>…
 *
 * Old builds ignore unknown lines and old documents have neither line, so they
 * read as "no folder, no tags". Names and tags never contain newlines; tags are
 * separated by a Unit Separator so a comma in a tag is safe.
 */
object LibraryMeta {

    const val FOLDER_KEY = "folder="
    const val TAGS_KEY = "tags="
    const val TAG_SEP = "\u001F"

    /** Folder names are one line, trimmed, at most 40 characters. */
    fun normalizeFolder(raw: String?): String? =
        raw?.replace('\n', ' ')?.replace('\r', ' ')?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
            ?.take(40)

    /** Tags are trimmed, de-duplicated case-insensitively, at most 24 chars, 30 max. */
    fun normalizeTags(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (tag in raw) {
            val clean = tag.replace('\n', ' ').replace('\r', ' ').trim()
                .replace(Regex("\\s+"), " ").take(24)
            if (clean.isNotBlank() && seen.none { it.equals(clean, true) }) seen += clean
            if (seen.size >= 30) break
        }
        return seen.toList()
    }

    data class Organised(val folder: String?, val tags: List<String>)

    /** Reads the extra lines; anything unreadable or absent means "not organised". */
    fun read(lines: List<String>): Organised {
        var folder: String? = null
        var tags: List<String> = emptyList()
        for (line in lines.drop(HEADER_LINES)) {
            when {
                line.startsWith(FOLDER_KEY) ->
                    folder = normalizeFolder(line.substring(FOLDER_KEY.length))
                line.startsWith(TAGS_KEY) -> tags = normalizeTags(
                    line.substring(TAGS_KEY.length).split(TAG_SEP).filter { it.isNotBlank() }
                )
            }
        }
        return Organised(folder, tags)
    }

    /**
     * Replaces the two keys, keeping every other line untouched.
     *
     * L1lib #5: only lines AFTER the fixed three-line header (`name`, `createdAt`,
     * `category`) are metadata. A document whose title literally starts with
     * `tags=` or `folder=` must keep its title; the old `removeAll { startsWith }`
     * deleted line 0 and corrupted the title/date/category. Iterate by index so
     * the header is never considered a key, even when it looks like one.
     */
    fun write(lines: MutableList<String>, folder: String?, tags: List<String>) {
        for (i in lines.lastIndex downTo HEADER_LINES) {
            val line = lines[i]
            if (line.startsWith(FOLDER_KEY) || line.startsWith(TAGS_KEY)) lines.removeAt(i)
        }
        normalizeFolder(folder)?.let { lines += FOLDER_KEY + it }
        val clean = normalizeTags(tags)
        if (clean.isNotEmpty()) lines += TAGS_KEY + clean.joinToString(TAG_SEP)
    }

    /** `meta.txt` header: name, createdAt, category (see [read]). */
    private const val HEADER_LINES = 3
}
