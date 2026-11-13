package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

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

    /** Optional metadata, deliberately independent of the save journal and page identity. */
    data class ReviewState(
        val title: String,
        val defaultName: String,
        val category: String,
        val categoryPickedByHand: Boolean
    )

    internal const val REVIEW_STATE_FILE = "review_state.json"
    private val stateIoLock = Any()
    private class StateWrite(val state: ReviewState)
    private val pendingStateWrites = ConcurrentHashMap<String, StateWrite>()
    private val stateWriter = Executors.newSingleThreadExecutor { work ->
        Thread(work, "review-state-writer").apply { isDaemon = true }
    }

    private fun folderKey(dir: File): String = dir.absoluteFile.normalize().path

    internal fun encodeReviewState(dir: File, state: ReviewState): String = JSONObject().apply {
        put("version", 1)
        put("folder", folderKey(dir))
        put("title", state.title)
        put("defaultName", state.defaultName)
        put("category", state.category)
        put("manualCategory", state.categoryPickedByHand)
    }.toString()

    internal fun decodeReviewState(dir: File, text: String): ReviewState? = try {
        val record = JSONObject(text)
        if (record.getInt("version") != 1 || record.getString("folder") != folderKey(dir)) null
        else {
            val title = record.get("title") as String
            val defaultName = record.get("defaultName") as String
            val category = record.get("category") as String
            val manual = record.get("manualCategory") as Boolean
            if (!CategoryRules.isValid(category) || defaultName.isBlank()) null
            else ReviewState(title, defaultName, category, manual)
        }
    } catch (_: Exception) { null }

    /** Cold restore reads only the committed optional record, never a torn .tmp. */
    fun readReviewState(dir: File): ReviewState? = try {
        val file = File(dir, REVIEW_STATE_FILE)
        if (!file.isFile || file.length() > 1_048_576) null
        else decodeReviewState(dir, file.readText())
    } catch (_: IOException) { null }

    /** Also covers recreation while the old activity's lifecycle flush is still queued. */
    fun latestReviewState(dir: File): ReviewState? =
        pendingStateWrites[folderKey(dir)]?.state ?: readReviewState(dir)

    /** No mkdir: a late lifecycle write must never resurrect saved/discarded staging. */
    internal fun writeReviewState(dir: File, state: ReviewState) = synchronized(stateIoLock) {
        if (!dir.isDirectory) throw IOException("Review folder no longer exists")
        require(CategoryRules.isValid(state.category) && state.defaultName.isNotBlank())
        val encoded = encodeReviewState(dir, state)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Review title is too long" }
        Durable.atomicWriteText(File(dir, REVIEW_STATE_FILE), encoded)
        Durable.syncDir(dir) // best effort, not a hardware power-loss guarantee
        Unit
    }

    /** FIFO, off-main writes. Superseded queued snapshots cannot overwrite a newer one.
     * Save awaits its returned future before entering the existing save transaction.
     */
    fun queueReviewState(dir: File, state: ReviewState): Future<Boolean> {
        val key = folderKey(dir)
        val request = StateWrite(state)
        pendingStateWrites[key] = request
        return stateWriter.submit<Boolean> {
            try {
                if (pendingStateWrites[key] !== request) false
                else { writeReviewState(dir, state); true }
            } finally {
                pendingStateWrites.remove(key, request)
            }
        }
    }

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

    private val PAGE_NAME = Regex("^page_(\\d+)\\.jpg(\\.del)?$", RegexOption.IGNORE_CASE)

    /**
     * Highest page number in [dir] (0 when none), counting pages a review has hidden for
     * Undo (`page_NNN.jpg.del`): a page appended after it can never be overwritten when
     * that Undo renames the hidden page back.
     */
    fun lastPageNumber(dir: File): Int =
        dir.list()?.mapNotNull { PAGE_NAME.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
}
