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

    /**
     * M08: where [term] (already normalised) occurs in [norm] as a WORD match, from [from]:
     * it must start a word (no letter or digit just before it), so "art" finds "Art" and
     * "artery" but never "start"; a term made only of digits must also end the number, so
     * "2" finds "2" and "(2)" but never "21" or "2026". Returns -1 when there is none.
     */
    fun findWord(norm: String, term: String, from: Int = 0): Int {
        if (term.isEmpty()) return -1
        val numeric = term.all { it.isDigit() }
        var i = norm.indexOf(term, from.coerceAtLeast(0))
        while (i >= 0) {
            val startsWord = i == 0 || !norm[i - 1].isLetterOrDigit()
            val end = i + term.length
            val endsNumber = !numeric || end >= norm.length || !norm[end].isDigit()
            if (startsWord && endsNumber) return i
            i = norm.indexOf(term, i + 1)
        }
        return -1
    }

    /** True when [term] occurs in [norm] as a word match ([findWord]). */
    fun containsWord(norm: String, term: String): Boolean = findWord(norm, term) >= 0

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
     * its normalised text (from [normalizedText]). Locked documents match on
     * their name alone: neither callback is invoked for them.
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
            // Privacy: a password-locked document is searchable by its (already visible)
            // name only. Its OCR is never read here, so no term can be probed against it —
            // not even a term that also appears in the name — and no snippet can exist.
            val normText = if (doc.isLocked) "" else normalizedText(doc.id)

            val nameTerms = terms.count { containsWord(normName, it) }
            val textTerms = terms.count { containsWord(normText, it) }

            // M08: every term in the name, or every term in the text — never one word from
            // the name and another from the text ("Document 2" must not find "Document 1"
            // just because its text has a "(2)" somewhere).
            if (nameTerms < terms.size && textTerms < terms.size) return@mapNotNull null

            val inName = nameTerms == terms.size
            val inText = textTerms == terms.size
            val kind = when {
                inName && inText -> Hit.Kind.BOTH
                inName -> Hit.Kind.NAME
                else -> Hit.Kind.TEXT
            }

            val textOccurrences = if (textTerms == terms.size) countOccurrences(normText, terms) else 0
            val score = 1000 * nameTerms + minOf(textOccurrences, 999)

            // A snippet only when the text itself matches the whole query.
            val snippet = if (!doc.isLocked && textTerms == terms.size) buildSnippet(originalText(doc.id), terms) else null

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
                val idx = findWord(text, term, start)
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
            val idx = findWord(norm, term)
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
                    val idx = findWord(normSlice, term, searchFrom)
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
