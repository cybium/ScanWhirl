package com.scanwhirl.app.store

import android.util.LruCache
import com.scanwhirl.app.util.SearchMatcher
import java.io.File

/**
 * Caches normalised OCR text for search. Holds only normalised text, keyed
 * by `docId|ocr.txt lastModified`, in an LruCache bounded to 4,000,000
 * characters total. Original text is read from disk on demand (only for
 * hits shown).
 */
class OcrTextCache(private val repo: ScanRepository) {

    private val cache = object : LruCache<String, String>(4_000_000) {
        override fun sizeOf(key: String, value: String): Int = value.length
    }

    /** Normalised OCR text for [docId], cached by document and file modification time. */
    fun normalized(docId: String): String {
        val ocrFile = File(repo.docsDir, docId).listFiles()?.firstOrNull { it.name == "ocr.txt" }
        val modified = ocrFile?.lastModified() ?: 0L
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

    /** Evicts a cached entry when the document's OCR changes. */
    fun evict(docId: String) {
        val keys = cache.snapshot().keys.filter { it.startsWith("$docId|") }
        keys.forEach { cache.remove(it) }
    }
}
