package com.scanwhirl.app.ui.home

import android.util.LruCache
import com.scanwhirl.app.model.ScanDocument
import java.io.File

/**
 * What a Home row can truthfully say about a document's recognised text.
 *
 * Derived only from the indexer's own evidence: the `ocr_pending` marker and
 * the page keys actually stored in `ocr.txt` (never from `text_layer.flag`,
 * which only says a PDF text layer was written once). The live "Reading text
 * n/m" state comes from [com.scanwhirl.app.store.OcrIndexer.state] at bind time.
 */
enum class TextState {
    /** No marker and no stored page text: say nothing (names are still searchable). */
    NONE,
    /** Marked `ocr_pending`: text has not been read yet (or must be re-read after an edit). */
    WAITING,
    /** Indexing finished but only some pages have stored text. */
    PARTIAL,
    /** Every page has stored text: the document's text is in Home search. */
    READY
}

/** Disk facts for one row, computed off the main thread (sizes and status never stat on bind). */
data class HomeDocInfo(
    val sizeBytes: Long,
    val text: TextState,
    val textPages: Int
) {
    companion object {
        val UNKNOWN = HomeDocInfo(sizeBytes = -1L, text = TextState.NONE, textPages = 0)
    }
}

/** One Home list item. Equality covers both, so DiffUtil re-binds when either changes. */
data class HomeRow(val doc: ScanDocument, val info: HomeDocInfo) {
    val id: String get() = doc.id
}

/** Reads [HomeDocInfo] for the library. Call on an IO thread. */
object HomeDocInfoReader {

    private const val PENDING_FILE = "ocr_pending"
    private const val TEXT_FILE = "ocr.txt"
    private const val V2_HEADER = "#scanwhirl-ocr-v2"

    /** `ocr.txt` page-key counts, keyed by path + mtime + length (re-read only after a change). */
    private val keyCounts = LruCache<String, Int>(1024)

    fun readAll(docsDir: File, docs: List<ScanDocument>): Map<String, HomeDocInfo> =
        docs.associate { it.id to read(docsDir, it) }

    fun read(docsDir: File, doc: ScanDocument): HomeDocInfo {
        val dir = File(docsDir, doc.id)
        val pdfBytes = doc.pdfFile.length()
        val size = if (pdfBytes > 0) pdfBytes else doc.pageFiles.sumOf { it.length() }
        if (File(dir, PENDING_FILE).exists()) {
            return HomeDocInfo(size, TextState.WAITING, 0)
        }
        val keys = minOf(countKeys(File(dir, TEXT_FILE)), maxOf(doc.pageCount, 0))
        val state = when {
            doc.pageCount > 0 && keys >= doc.pageCount -> TextState.READY
            keys > 0 -> TextState.PARTIAL
            else -> TextState.NONE
        }
        return HomeDocInfo(size, state, keys)
    }

    /**
     * Stored page entries in `ocr.txt`: one `key<TAB>text` line per page in both the
     * v2 format (Base64, NO_WRAP) and the legacy one; the v2 header has no tab.
     * A v2 file with any line lacking a tab is unreadable to search
     * ([com.scanwhirl.app.store.OcrStore.readText] returns nothing), so it counts 0.
     */
    internal fun countKeys(file: File): Int {
        if (!file.isFile) return 0
        val cacheKey = "${file.path}|${file.lastModified()}|${file.length()}"
        keyCounts.get(cacheKey)?.let { return it }
        val n = try {
            file.bufferedReader().useLines { seq ->
                var v2 = false
                var count = 0
                var malformed = false
                seq.forEachIndexed { i, line ->
                    if (i == 0 && line == V2_HEADER) v2 = true
                    else if (line.indexOf('\t') > 0) count++
                    else if (v2) malformed = true
                }
                if (malformed) 0 else count
            }
        } catch (_: Exception) {
            0
        }
        keyCounts.put(cacheKey, n)
        return n
    }
}
