package com.scanwhirl.app.util

import java.util.Locale

/**
 * CP1: the single decimal size unit for every size field and readout in the app.
 *
 * KB = 1000 bytes, MB = 1,000,000 and GB = 1,000,000,000; the label stays "KB"/"MB"/"GB".
 * A file displayed as "under 500 KB" is therefore <= 500,000 bytes and also fits a portal that
 * counts a KB as 1024 bytes. A bare number means KB, as the Compress hints say.
 *
 * [parse] keeps the long-arithmetic ceiling (no Int saturation) and [format] uses the same
 * one-decimal-MB / whole-KB rounding the Home status line has always used. All callers (Compress,
 * Resize, the hub, PDF import and exports) go through this object so a size cannot disagree
 * between two screens.
 */
object SizeUnits {

    const val BYTES_PER_KB = 1_000L
    const val BYTES_PER_MB = 1_000_000L
    const val BYTES_PER_GB = 1_000_000_000L

    /** The shared 2 GiB ceiling; kept binary so the old "2048 MB" boundary stays a valid size. */
    const val MAX_BYTES = 2L * 1024L * 1024L * 1024L

    private val SIZE = Regex("(\\d+(?:\\.\\d+)?)(gb|mb|kb|g|m|k|b)?")

    /** Bytes, or null for empty/unparseable/over-ceiling text. Never rounds through Int. */
    fun parse(text: String?, maxBytes: Long = MAX_BYTES): Long? {
        val cleaned = text?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "") ?: return null
        if (cleaned.isEmpty()) return null
        val match = SIZE.matchEntire(cleaned) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        if (!number.isFinite()) return null
        val bytes = when (match.groupValues[2]) {
            "gb", "g" -> number * BYTES_PER_GB
            "mb", "m" -> number * BYTES_PER_MB
            "kb", "k" -> number * BYTES_PER_KB
            "b" -> number
            else -> number * BYTES_PER_KB // bare number = KB, as the hints say
        }
        if (!bytes.isFinite() || bytes > maxBytes.toDouble()) return null
        return bytes.toLong().coerceAtLeast(1L)
    }

    /** "0 B", "500 KB", "1.0 MB" or "1.0 GB". */
    fun format(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= BYTES_PER_GB -> String.format(Locale.ROOT, "%.1f GB", bytes / BYTES_PER_GB.toDouble())
        bytes >= BYTES_PER_MB -> String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MB.toDouble())
        bytes >= BYTES_PER_KB -> String.format(Locale.ROOT, "%.0f KB", bytes / BYTES_PER_KB.toDouble())
        else -> "$bytes B"
    }

    /**
     * The measured PDF overhead model shared by Compress's two estimate sites: ~600 bytes of PDF
     * wrapper per page plus the document objects. An estimate is always labelled "about"; the real
     * size is measured and shown after the run.
     */
    fun estimateJpegPages(averagePageBytes: Double, pageCount: Int, overheadPages: Int = pageCount): Long =
        (averagePageBytes * pageCount + overheadPages * 600L + 1_200L).toLong()
}
