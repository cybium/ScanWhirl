package com.scanwhirl.app.store

import android.util.LruCache
import com.scanwhirl.app.util.SearchMatcher
import java.io.File

/**
 * Caches normalised OCR text for search. Holds only normalised text, keyed
 * by `docId|ocr.txt lastModified`, in an LruCache bounded to 4,000,000
 * characters total. Original text is read from disk on demand (only for
 * hits shown).
 *
 * Retrieval slice 5: [pages] keeps the stored per-page map (read-only, keyed
 * by page file name) for documents that matched a search, so page-level hits
 * do not re-read `ocr.txt` on every keystroke. Keyed by `ocr.txt` mtime AND
 * length; bounded to 3,000,000 characters.
 */
class OcrTextCache(private val repo: ScanRepository) {

    private val cache = object : LruCache<String, String>(4_000_000) {
        override fun sizeOf(key: String, value: String): Int = value.length
    }

    private val pageCache = object : LruCache<String, Map<String, String>>(3_000_000) {
        override fun sizeOf(key: String, value: Map<String, String>): Int =
            maxOf(1, value.entries.sumOf { it.key.length + it.value.length })
    }

    /** Normalised OCR text for [docId], cached by document and file modification time. */
    fun normalized(docId: String): String {
        // Direct path: listing the document directory to find ocr.txt walks every page file and
        // turns each keystroke into O(pages) work for long textbooks.
        val ocrFile = File(File(repo.docsDir, docId), "ocr.txt")
        val modified = ocrFile.lastModified()
        val key = "$docId|$modified"
        return cache.get(key) ?: run {
            val text = repo.ocrDoc(docId).values.joinToString("\n")
            val norm = SearchMatcher.normalize(text)
            cache.put(key, norm)
            norm
        }
    }

    /** Original OCR text for [docId] (page texts joined with newline), read from disk. */
    fun original(docId: String): String =
        repo.ocrDoc(docId).values.joinToString("\n")

    /**
     * The stored per-page OCR text of [docId] (page file name → text), exactly
     * as [ScanRepository.ocrDoc] returns it. Never writes. Empty when there is
     * no `ocr.txt` (or it is unreadable).
     */
    fun pages(docId: String): Map<String, String> {
        val file = File(File(repo.docsDir, docId), "ocr.txt")
        if (!file.isFile) return emptyMap()
        val key = "$docId|${file.lastModified()}|${file.length()}"
        return pageCache.get(key) ?: repo.ocrDoc(docId).also { pageCache.put(key, it) }
    }

    /** Evicts a cached entry when the document's OCR changes. */
    fun evict(docId: String) {
        val keys = cache.snapshot().keys.filter { it.startsWith("$docId|") }
        keys.forEach { cache.remove(it) }
        pageCache.snapshot().keys.filter { it.startsWith("$docId|") }.forEach { pageCache.remove(it) }
    }
}
