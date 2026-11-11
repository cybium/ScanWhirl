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
     * Unreadable files, remembered by request key (path + mtime + length, so an
     * edited page is retried) — a broken page is not re-read on every bind.
     */
    private val failed = LruCache<String, Boolean>(256)

    /**
     * Shows the cached thumb immediately, or decodes [file] on a background
     * thread and applies it only if [into] still shows the same request key.
     *
     * Home slice 2 additions (all optional, defaults keep the grid unchanged):
     * - [fitExactly] scales the sampled decode down so its longer side is
     *   [targetPx] (power-of-two sampling alone can leave it up to 2× larger),
     *   which keeps 100 list thumbs well inside the shared LRU budget;
     * - [placeholder] is shown while decoding (and kept on failure);
     * - [onLoaded] replaces the plain `setImageBitmap` (the Home row cross-fades
     *   only a fresh decode — `fromCache` rows appear instantly);
     * - [onFailed] runs, still key-guarded, when the file cannot be decoded;
     * - [skipStale] drops a queued job whose view was re-bound before it ran
     *   (Home flings queue far more rows than they show). Off by default: the
     *   review screen relies on scrolled-past film thumbs still filling the cache.
     */
    fun load(
        file: File,
        targetPx: Int,
        filter: ScanFilter,
        into: ImageView,
        fitExactly: Boolean = false,
        placeholder: Int = com.scanwhirl.app.R.drawable.ic_document,
        onLoaded: ((Bitmap, fromCache: Boolean) -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        skipStale: Boolean = false
    ) {
        val key = keyFor(file, targetPx, filter) + if (fitExactly) "|fit" else ""
        into.tag = key

        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) {
            if (onLoaded != null) onLoaded(cached, true) else into.setImageBitmap(cached)
            return
        }
        into.setImageResource(placeholder)
        if (failed.get(key) == true) {
            onFailed?.invoke()
            return
        }

        executor.execute {
            // The row was recycled for another document before this job started.
            if (skipStale && into.tag != key) return@execute
            val thumb = decode(file, targetPx, filter, fitExactly)
            if (thumb == null) {
                failed.put(key, true)
                mainHandler.post { if (into.tag == key) onFailed?.invoke() }
                return@execute
            }
            cache.put(key, thumb)
            mainHandler.post {
                if (into.tag == key) {
                    if (onLoaded != null) onLoaded(thumb, false) else into.setImageBitmap(thumb)
                }
            }
        }
    }

    /**
     * Forgets whatever request [into] was waiting for, so a late decode for the
     * document this recycled view showed before can never land on it.
     */
    fun cancel(into: ImageView) {
        into.tag = null
    }

    private fun decode(file: File, targetPx: Int, filter: ScanFilter, fitExactly: Boolean): Bitmap? {
        val decoded = ImageTools.decodeThumb(file, targetPx) ?: return null
        val sized = if (!fitExactly) decoded else {
            val longest = maxOf(decoded.width, decoded.height)
            if (longest <= targetPx) decoded else {
                val scale = targetPx.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    maxOf(1, (decoded.width * scale).toInt()),
                    maxOf(1, (decoded.height * scale).toInt()),
                    true
                )
                if (scaled !== decoded) decoded.recycle()
                scaled
            }
        }
        if (filter == ScanFilter.ORIGINAL) return sized
        val filtered = filter.applyTo(sized)
        if (filtered !== sized) sized.recycle()
        return filtered
    }

    /** The cached thumb for this exact request, or null (never decodes). */
    fun cached(file: File, targetPx: Int, filter: ScanFilter): Bitmap? =
        cache.get(keyFor(file, targetPx, filter))?.takeIf { !it.isRecycled }

    /**
     * Decodes a thumb off the main thread and hands it back on the main
     * thread (canvas UIs — the globe home — draw into bitmaps, not ImageViews).
     * The result lands in the same cache as [load], so no double decoding.
     */
    fun loadAsync(file: File, targetPx: Int, filter: ScanFilter, onResult: (Bitmap) -> Unit) {
        val key = keyFor(file, targetPx, filter)
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) {
            mainHandler.post { onResult(cached) }
            return
        }
        executor.execute {
            val thumb = decode(file, targetPx, filter, fitExactly = false) ?: return@execute
            cache.put(key, thumb)
            mainHandler.post { onResult(thumb) }
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
        synchronized(failed) {
            failed.snapshot().keys.filter { it.startsWith(prefix) }.forEach { failed.remove(it) }
        }
    }
}
