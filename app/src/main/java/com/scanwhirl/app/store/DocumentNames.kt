package com.scanwhirl.app.store

import java.util.Locale

/** Library titles, not export filenames. Keep the batch-rename limit, including any suffix. */
internal object DocumentNames {
    private const val MAX_LENGTH = 80

    fun unique(proposed: String, existing: Collection<String>): String {
        val base = proposed.trim().replace('\n', ' ').replace('\r', ' ').ifBlank { "Scan" }
        val taken = existing.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
        val clean = shorten(base, MAX_LENGTH)
        if (clean.lowercase(Locale.ROOT) !in taken) return clean
        var number = 2
        while (true) {
            val suffix = " ($number)"
            val candidate = shorten(base, MAX_LENGTH - suffix.length) + suffix
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
            number++
        }
    }

    private fun shorten(value: String, limit: Int): String {
        var end = minOf(value.length, limit)
        // Never split a supplementary Unicode character at the length boundary.
        if (end > 0 && end < value.length && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end).trimEnd()
    }
}
