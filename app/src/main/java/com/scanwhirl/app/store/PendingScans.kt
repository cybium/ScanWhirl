package com.scanwhirl.app.store

import android.content.Context
import java.io.File

/**
 * Durable review staging (WP-05).
 *
 * New review sessions live under `filesDir/pending_scans/pending_<ms>` — app-owned
 * persistent storage, outside `documents/` (so no document loader, indexer or
 * backfill ever sees them) and outside `cacheDir` (which Android may evict).
 *
 * Sessions created by earlier app versions live in `cacheDir/pending_*`. They are
 * still listed and resumed exactly as before and are never deleted automatically.
 *
 * Nothing here ever sweeps or deletes a session: only an explicit user action
 * (Discard / Remove leftover) or a verified committed save removes one.
 */
internal object PendingScans {
    const val ROOT = "pending_scans"
    private const val PREFIX = "pending_"

    fun root(context: Context) = File(context.filesDir, ROOT)

    /** A brand-new folder: an existing one (same millisecond) is never reused or deleted. */
    fun create(context: Context): File {
        val root = root(context)
        root.mkdirs()
        val stamp = System.currentTimeMillis()
        for (attempt in 0 until 100) {
            val name = if (attempt == 0) "$PREFIX$stamp" else "$PREFIX${stamp}_$attempt"
            val candidate = File(root, name)
            if (candidate.mkdir()) return candidate // exclusive: fails when the path exists
        }
        throw IllegalStateException("Could not create a staging folder. Free some space and try again.")
    }

    /** Every unfinished session (new location and legacy cache), newest first, only those with pages. */
    fun all(context: Context): List<File> {
        val dirs = listOf(root(context), context.cacheDir).flatMap { parent ->
            parent.listFiles { f ->
                f.isDirectory && f.name.startsWith(PREFIX) &&
                    !f.listFiles { p -> p.extension.equals("jpg", true) }.isNullOrEmpty()
            }?.toList() ?: emptyList()
        }
        return dirs.sortedByDescending { it.lastModified() }
    }

    fun pageCount(dir: File): Int = dir.listFiles { p -> p.extension.equals("jpg", true) }?.size ?: 0
}
