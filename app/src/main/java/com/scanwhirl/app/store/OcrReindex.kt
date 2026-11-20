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
 * Before dropping previous OCR, wait for any indexing pass on this document to finish.
 * Otherwise its result could be stored first even when both passes read the same JPEG.
 * Pages are stored one by one through
 * [OcrStore.putRecognizedPage] (which refuses a page whose image changed meanwhile), and the
 * searchable PDF is regenerated afterwards.
 */
class OcrReindex(private val repo: ScanRepository) {

    /**
     * Re-reads [docId] with [language]. Only set [replaceCorrections] after confirmation.
     * Returns how many pages were recognised and stored, or -1 when the document is gone.
     */
    suspend fun reRecognise(
        docId: String,
        language: OcrLanguage,
        replaceCorrections: Boolean = false
    ): Int = repo.ocrStore.withRecognitionLock(docId) {
        withContext(Dispatchers.IO) {
            val doc = repo.getDocument(docId) ?: return@withContext -1
            val keep = repo.ocrStore.clearForReread(docId, replaceCorrections)
            var stored = 0
            for (page in doc.pageFiles) {
                if (page.name in keep) continue
                val sourceHash = OcrStore.locked { Sha256.of(page) }
                val result = OcrEngine.recognizeFile(page, language)
                stored += repo.ocrStore.putRecognizedPages(
                    docId, listOf(OcrStore.Recognized(page.name, sourceHash, result.text, result.page))
                ).stored
            }
            if (stored + doc.pageFiles.count { it.name in keep } == doc.pageFiles.size) {
                repo.ocrStore.clearPending(docId)
            }
            if (stored > 0) repo.refreshPdfAfterIndex(docId)
            stored
        }
    }
}
