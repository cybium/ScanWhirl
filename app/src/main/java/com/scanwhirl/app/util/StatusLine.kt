package com.scanwhirl.app.util

import android.content.Context
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

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
