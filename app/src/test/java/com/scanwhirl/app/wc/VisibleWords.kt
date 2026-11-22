package com.scanwhirl.app.wc

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.scanwhirl.app.util.UserContent

/**
 * Lane WC: the word-count primitive behind both the report and the gate.
 *
 * Counts only words the APP writes and that a person can actually see at 100%
 * font on a 360x800dp screen: text of visible, laid-out, on-screen TextViews /
 * Buttons / Chips (all TextView subclasses) and the HINT of an EditText (its
 * typed/OCR content is user content and is never counted). contentDescription
 * is TalkBack-only and is deliberately ignored.
 *
 * A view tagged with [UserContent.mark] (and its whole subtree) is skipped.
 * Numeric values are not words: "1.6 MB", "200 x 230", "12 / 40 KB" contribute
 * zero. A word is a run of Unicode letters, except short unit tokens (MB, KB,
 * px, dpi, ...) and a single letter inside a value (the "x" in "200 x 230").
 */
object VisibleWords {

    private val LETTER_RUN = Regex("\\p{L}+")

    /** Unit abbreviations are part of a numeric value, not words on the screen. */
    private val UNITS = setOf(
        "mb", "kb", "gb", "tb", "px", "dpi", "mm", "cm", "pt", "in", "ft",
        "kbps", "mbps", "gbps", "fps", "a4", "kb/s", "mb/s"
    )

    /** The words in one visible string. Pure, so it is unit-testable on its own. */
    fun wordsIn(text: String): List<String> {
        val hasDigit = text.any { it.isDigit() }
        return LETTER_RUN.findAll(text).map { it.value }.filter { run ->
            val lower = run.lowercase()
            when {
                lower in UNITS -> false
                // single letter next to a number is a multiplication sign / unit,
                // not an app-authored word ("200 x 230", "A4", "5 x 7").
                run.length == 1 && hasDigit -> false
                else -> true
            }
        }.toList()
    }

    data class Result(val words: Int, val strings: List<String>) {
        /** The five longest visible strings, longest first (for the report). */
        fun topStrings(n: Int = 5): List<String> =
            strings.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
                .distinct()
                .take(n)
    }

    /** Count all visible app-authored words under [root]. */
    fun count(root: View): Result {
        val strings = ArrayList<String>()
        collect(root, root, strings)
        return Result(strings.sumOf { wordsIn(it).size }, strings)
    }

    private fun collect(root: View, v: View, out: MutableList<String>) {
        if (UserContent.isMarked(v)) return // user content: skip this view and its children
        val value = when (v) {
            is EditText -> v.hint // typed text (incl. OCR text) is user content
            is TextView -> v.text
            else -> null
        }
        if (value != null && v.isShown && v.width > 0 && v.height > 0 && onScreen(root, v)) {
            val s = value.toString().trim()
            if (s.isNotEmpty()) out += s
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collect(root, v.getChildAt(i), out)
        }
    }

    /** True when any part of [v] lies inside the laid-out [root] (the "screen"). */
    private fun onScreen(root: View, v: View): Boolean {
        var x = 0
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left - cur.scrollX
            y += cur.top - cur.scrollY
            cur = cur.parent as? View ?: return false
        }
        val r = Rect(x, y, x + v.width, y + v.height)
        return r.right > 0 && r.bottom > 0 && r.left < root.width && r.top < root.height
    }
}
