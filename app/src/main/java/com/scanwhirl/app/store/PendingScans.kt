package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
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

    /** Staged pages in review order (the order the review screen shows and saves). */
    fun pages(dir: File): List<File> =
        dir.listFiles { f -> f.extension.equals("jpg", true) }?.sortedBy { it.nameWithoutExtension } ?: emptyList()

    /**
     * The review identity of a staging folder exactly as the review screen would hand it to
     * `saveScan`: staged page bytes in order plus the persisted per-page filters (a page without
     * an entry is Original, as in the review). Null when the folder has no pages.
     */
    fun identity(dir: File): SaveFormat.Identity? {
        val pages = pages(dir).ifEmpty { return null }
        val saved = File(dir, FILTERS_FILE).takeIf { it.isFile }?.readLines()
            ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.toMap() ?: emptyMap()
        return SaveFormat.identity(
            pages.map { Sha256.of(it) },
            pages.map { ScanFilter.fromId(saved[it.name]).id }
        )
    }

    private const val FILTERS_FILE = "filters.txt"

    fun pageCount(dir: File): Int = dir.listFiles { p -> p.extension.equals("jpg", true) }?.size ?: 0
}
