package com.scanwhirl.app.ui.pagegrid

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ScanFilter
import java.io.File
import java.util.concurrent.Executors

/**
 * Decodes page thumbnails off the main thread and caches them.
 *
 * Grid cells are ~1/6 of a 1080p screen, so nothing here ever decodes a
 * full-resolution page: [ImageTools.decodeThumb] downsamples to the cell size
 * first. The cache is keyed by path + mtime + length + target size + filter,
 * so a rotated/replaced page simply misses and re-decodes.
 *
 * Stale-result guard: the target view is tagged with the request key before
 * the decode is queued; a late result whose key no longer matches the view
 * (the view was recycled for another page) is dropped instead of flashing
 * the wrong thumbnail.
 */
object PageThumbLoader {

    /** Far more than a 100-page grid of cell-size thumbs needs; evicts cheaply. */
    private const val CACHE_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "page-thumb").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun keyFor(file: File, targetPx: Int, filter: ScanFilter): String =
        "${file.path}|${file.lastModified()}|${file.length()}|$targetPx|${filter.id}"

    /**
     * Shows the cached thumb immediately, or decodes [file] on a background
     * thread and applies it only if [into] still shows the same request key.
     */
    fun load(file: File, targetPx: Int, filter: ScanFilter, into: ImageView) {
        val key = keyFor(file, targetPx, filter)
        into.tag = key

        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) {
            into.setImageBitmap(cached)
            return
        }
        into.setImageResource(com.scanwhirl.app.R.drawable.ic_document)

        executor.execute {
            val decoded = ImageTools.decodeThumb(file, targetPx) ?: return@execute
            val thumb = if (filter == ScanFilter.ORIGINAL) decoded else {
                val filtered = filter.applyTo(decoded)
                if (filtered !== decoded) decoded.recycle()
                filtered
            }
            cache.put(key, thumb)
            mainHandler.post {
                if (into.tag == key) into.setImageBitmap(thumb)
            }
        }
    }

    /** Drops cached thumbs for a path (called after rotate/replace so mtime isn't trusted). */
    fun invalidate(file: File) {
        val prefix = "${file.path}|"
        // LruCache has no prefix eviction; keys are cheap to rebuild on next bind.
        synchronized(cache) {
            val snapshot = cache.snapshot().keys.filter { it.startsWith(prefix) }
            snapshot.forEach { cache.remove(it) }
        }
    }
}
