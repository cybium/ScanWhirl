package com.scanwhirl.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2 UI: 03_tools_hub — batch rename patterns.
 *
 * Tokens: `{name}` the current document name, `{n}` its 1-based position in the
 * selection, `{date}` its creation date (yyyy-MM-dd). Anything else is literal.
 * A result that is blank or unchanged keeps the document's old name.
 */
object BatchRename {

    const val DEFAULT_PATTERN = "{name} ({n})"

    data class Item(val name: String, val createdAt: Long)

    fun hasToken(pattern: String): Boolean =
        pattern.contains("{name}") || pattern.contains("{n}") || pattern.contains("{date}")

    /** Expands [pattern] for every item, in order. */
    fun apply(
        pattern: String,
        items: List<Item>,
        dateFormat: String = "yyyy-MM-dd"
    ): List<String> {
        if (items.isEmpty()) return emptyList()
        // R09 F2: a document name is data, not locale-formatted UI. th-TH would write a Buddhist
        // year and ar-EG Arabic-Indic digits; every other name/date formatter here pins ROOT.
        val fmt = SimpleDateFormat(dateFormat, Locale.ROOT)
        return items.mapIndexed { index, item ->
            val expanded = pattern
                .replace("{name}", item.name)
                .replace("{n}", (index + 1).toString())
                .replace("{date}", fmt.format(Date(item.createdAt)))
            sanitize(expanded).ifBlank { item.name }
        }
    }

    /** The first [max] names the pattern would produce, for the live preview. */
    fun preview(
        pattern: String,
        items: List<Item>,
        max: Int = 3,
        dateFormat: String = "yyyy-MM-dd"
    ): List<String> = apply(pattern, items.take(max), dateFormat)

    /** One line, no control characters, at most 80 characters (keeps the old name if blank). */
    fun sanitize(raw: String): String = raw
        .replace('\n', ' ')
        .replace('\r', ' ')
        .filter { !it.isISOControl() }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
}
