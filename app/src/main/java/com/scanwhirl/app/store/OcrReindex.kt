package com.scanwhirl.app.store

import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-recognition when the OCR language changes (Text screen language chip) or when the
 * "Hindi text" tool reads a document with the Devanagari model.
 *
 * Both paths must never mix languages in one document, so the stored text and geometry are
 * dropped first, then every page is recognised again. Pages are stored one by one through
 * [OcrStore.putRecognizedPage] (which refuses a page whose image changed meanwhile), and the
 * searchable PDF is regenerated afterwards.
 */
class OcrReindex(private val repo: ScanRepository) {

    /**
     * Re-reads [docId] with [language]. Returns how many pages were recognised and stored, or
     * -1 when the document no longer exists.
     */
    suspend fun reRecognise(docId: String, language: OcrLanguage): Int = withContext(Dispatchers.IO) {
        val doc = repo.getDocument(docId) ?: return@withContext -1
        repo.ocrStore.clearAll(docId)
        var stored = 0
        for (page in doc.pageFiles) {
            val sourceHash = OcrStore.locked { Sha256.of(page) }
            val result = OcrEngine.recognizeFile(page, language)
            if (repo.ocrStore.putRecognizedPage(docId, page.name, sourceHash, result.text, result.page)) {
                stored++
            }
        }
        repo.ocrStore.clearPending(docId)
        if (stored > 0) repo.refreshPdfAfterIndex(docId)
        stored
    }
}
