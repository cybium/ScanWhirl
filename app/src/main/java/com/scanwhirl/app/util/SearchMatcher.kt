package com.scanwhirl.app.util

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.scanwhirl.app.model.ScanDocument
import java.text.Normalizer
import java.util.Locale

/**
 * Search v2: names + text, accent-insensitive, ranked, with snippets.
 *
 * Every document that matches (by name, by text, or both) is returned as a
 * [Hit] ranked by score. There is no suppression when names match, no Pro
 * gate, and no "In text" toggle — search always covers names and text for
 * everyone.
 */
object SearchMatcher {

    private val WHITESPACE = Regex("\\s+")

    /** The colour used for matched terms inside snippets (cyan_primary = #00F0FF). */
    private val snippetColor by lazy { Color.parseColor("#00F0FF") }

    /**
     * Normalises [s] for matching: lower-case, then NFD-decompose and keep
     * the base letter (so "é" → "e"). Each input character maps to exactly
     * one output character, so positions line up between the original and
     * the normalised text (enables snippet slicing by index).
     */
    fun normalize(s: String): String = buildString {
        for (c in s) {
            val lower = c.lowercaseChar()
            val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
            append(decomposed.firstOrNull() ?: lower)
        }
    }

    /** A single search hit. */
    data class Hit(
        val doc: ScanDocument,
        val kind: Kind,
        val snippet: CharSequence?,
        val score: Int
    ) {
        enum class Kind { NAME, TEXT, BOTH }
    }

    /**
     * Searches [docs] for [query]. Terms are the normalised query split on
     * whitespace. Vaulted documents are excluded unless [includeVaulted].
     * A document matches when every term occurs in its normalised name or
     * its normalised text (from [normalizedText]).
     *
     * [kind] is NAME when all terms occur in the name and none only in the
     * text; TEXT when no term occurs in the name; otherwise BOTH.
     * [score] = 1000 × (terms found in name) + min(term occurrences in text, 999).
     * Results are ordered by score descending, then createdAt descending.
     *
     * [snippet] (only for TEXT/BOTH hits) is about 60 characters of
     * [originalText](doc.id) around the first hit, with matched terms bold
     * and coloured [snippetColor].
     */
    fun search(
        query: String,
        docs: List<ScanDocument>,
        includeVaulted: Boolean,
        normalizedText: (String) -> String,
        originalText: (String) -> String
    ): List<Hit> {
        val terms = normalize(query).split(WHITESPACE).filter { it.isNotBlank() }
        val visible = if (includeVaulted) docs else docs.filter { !it.isVaulted }

        if (terms.isEmpty()) return emptyList()

        val hits = visible.mapNotNull { doc ->
            val normName = normalize(doc.name)
            val normText = normalizedText(doc.id)

            val nameTerms = terms.count { normName.contains(it) }
            val textTerms = terms.count { normText.contains(it) }

            // Every term must occur in name or text.
            if (!terms.all { normName.contains(it) || normText.contains(it) }) return@mapNotNull null

            val kind = when {
                nameTerms == terms.size && textTerms == 0 -> Hit.Kind.NAME
                nameTerms == 0 -> Hit.Kind.TEXT
                else -> Hit.Kind.BOTH
            }

            val textOccurrences = countOccurrences(normText, terms)
            val score = 1000 * nameTerms + minOf(textOccurrences, 999)

            val snippet = if (textTerms > 0) buildSnippet(originalText(doc.id), terms) else null

            Hit(doc, kind, snippet, score)
        }

        return hits.sortedWith(
            compareByDescending<Hit> { it.score }
                .thenByDescending { it.doc.createdAt }
        )
    }

    /** Total occurrences of all terms in [text]. */
    private fun countOccurrences(text: String, terms: List<String>): Int {
        var count = 0
        for (term in terms) {
            var start = 0
            while (true) {
                val idx = text.indexOf(term, start)
                if (idx < 0) break
                count++
                start = idx + 1
            }
        }
        return count
    }

    /**
     * Builds a snippet from [text]: about 60 characters around the first
     * term hit (newlines → spaces), with ellipsis at cut ends, every term
     * occurrence inside the slice bold and coloured [snippetColor].
     */
    private fun buildSnippet(text: String, terms: List<String>): CharSequence {
        val flat = text.replace("\n", " ").replace("\r", " ")
        val norm = normalize(flat)

        // Find the first occurrence of any term.
        var firstHit = -1
        for (term in terms) {
            val idx = norm.indexOf(term)
            if (idx >= 0 && (firstHit < 0 || idx < firstHit)) firstHit = idx
        }
        if (firstHit < 0) return flat

        val window = 30 // ~60 chars around the hit
        val start = maxOf(0, firstHit - window)
        val end = minOf(flat.length, firstHit + window + 1)

        val slice = flat.substring(start, end)
        val normSlice = normalize(slice)

        val sb = StringBuilder()
        val ellipsisLen = if (start > 0) 1 else 0
        if (start > 0) sb.append("…")
        sb.append(slice)
        if (end < flat.length) sb.append("…")

        return try {
            val spanned = SpannableString(sb.toString())
            for (term in terms) {
                var searchFrom = 0
                while (true) {
                    val idx = normSlice.indexOf(term, searchFrom)
                    if (idx < 0) break
                    val absIdx = ellipsisLen + idx
                    // setSpan is not mocked under Robolectric; on real devices
                    // it bolds and colours the matched term.
                    spanned.setSpan(
                        StyleSpan(android.graphics.Typeface.BOLD),
                        absIdx,
                        absIdx + term.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spanned.setSpan(
                        ForegroundColorSpan(snippetColor),
                        absIdx,
                        absIdx + term.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    searchFrom = idx + 1
                }
            }
            spanned
        } catch (_: Exception) {
            // Spans unavailable (e.g. Robolectric): fall back to plain text.
            sb.toString()
        }
    }
}
