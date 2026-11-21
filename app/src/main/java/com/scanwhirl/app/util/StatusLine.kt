package com.scanwhirl.app.util

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R
import java.util.Locale

/**
 * test26 T1.5 — the Home status line (N7: no emoji anywhere; icons are
 * vectors, text is text).
 *
 * Replaces the old [Greeting] object, whose hand-written Unicode emoji
 * rendered in the phone's emoji font and clashed with the cyan line-art
 * style. The phases now live in string resources (localizable) and the line
 * is a fixed HUD micro-caps readout:
 *
 *     GOOD AFTERNOON · 2 DOCS · 14.0 MB
 *
 * Byte formatting mirrors the app's existing `formatBytes` (one decimal for
 * MB, whole KB) so sizes never disagree between screens.
 */
object StatusLine {

    /** R.string id for the day phase: 05–11 morning, 12–16 afternoon, 17–20 evening. */
    fun phase(hour: Int): Int = when (hour) {
        in 5..11 -> R.string.status_morning
        in 12..16 -> R.string.status_afternoon
        in 17..20 -> R.string.status_evening
        else -> R.string.status_night
    }

    /** `"GOOD AFTERNOON · 2 DOCS · 14.0 MB"` — period, plural count, size. */
    fun format(context: Context, hour: Int, docCount: Int, totalBytes: Long): String {
        val period = context.getString(phase(hour))
        val docs = context.resources.getQuantityString(
            R.plurals.status_docs, docCount, docCount
        )
        return "$period · $docs · ${formatBytes(totalBytes)}".uppercase(Locale.ROOT)
    }

    /**
     * CP1: decimal sizes. KB = 1000 bytes and MB = 1,000,000, matching the unit the Compress
     * target field accepts. A file described as "under 500 KB" is therefore <= 500,000 bytes and
     * also fits a portal that counts a KB as 1024 bytes. The UI label stays "KB".
     */
    fun formatBytes(bytes: Long): String = SizeUnits.format(bytes)

    /**
     * test26 T3.2 — the same readout as [format] but as a `SpannableString`:
     * digit runs in monospace, the ` · ` separators in `cyan_border_hair`.
     * Everything else keeps the TextView's own colour (`cyan_dim` from the
     * layout), so this only paints what the spec asks it to paint.
     */
    fun styled(context: Context, hour: Int, docCount: Int, totalBytes: Long): SpannableString {
        val text = format(context, hour, docCount, totalBytes)
        val span = SpannableString(text)
        val hair = ContextCompat.getColor(context, R.color.cyan_border_hair)

        // Separators: the " · " tokens between the three HUD segments.
        var at = text.indexOf(SEP)
        while (at >= 0) {
            span.setSpan(
                ForegroundColorSpan(hair), at, at + SEP.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            at = text.indexOf(SEP, at + SEP.length)
        }

        // Numbers: each run of digits (and its decimal point) goes monospace.
        val mono = TypefaceSpan("monospace")
        var i = 0
        while (i < text.length) {
            if (text[i].isDigit()) {
                var end = i
                while (end < text.length && (text[end].isDigit() || text[end] == '.')) end++
                span.setSpan(mono, i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end
            } else i++
        }
        return span
    }

    private const val SEP = " · "
}
