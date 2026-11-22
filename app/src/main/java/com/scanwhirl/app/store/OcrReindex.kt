package com.scanwhirl.app.store

import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Re-recognition when the OCR language changes (Text screen language chip) or when the
 * "Hindi text" tool reads a document with the Devanagari model.
 *
 * Two contracts, kept deliberately separate:
 *  - A confirmed re-read ([replaceCorrections] true, from OcrEditor's confirm sheet) clears the
 *    old OCR up front, as before: the person agreed to replace corrections and the screen drops
 *    its unsaved text while the read runs.
 *  - An unconfirmed one-shot read (the "Hindi text" tool) never clears the document. Each page's
 *    old text stays on disk until that page's new text is stored, a failing page keeps its old
 *    text, and the loop always continues to the next page. No data is lost on a failed read.
 *
 * Both wait for any indexing pass on this document to finish, refresh the searchable PDF and
 * tell open Text screens to reload.
 */
class OcrReindex(private val repo: ScanRepository) {

    /**
     * Re-reads [docId] with [language]. Only set [replaceCorrections] after the person confirms.
     * Returns how many pages were recognised and stored, or -1 when the document is gone. A
     * missing text model is rethrown so the caller can show the retryable, honest reason.
     */
    suspend fun reRecognise(
        docId: String,
        language: OcrLanguage,
        replaceCorrections: Boolean = false
    ): Int = repo.ocrStore.withRecognitionLock(docId) {
        withContext(Dispatchers.IO) {
            val doc = repo.getDocument(docId) ?: return@withContext -1
            var stored = 0
            var unavailable: Throwable? = null
            try {
                if (replaceCorrections) {
                    // Confirmed path: the old screen may not write to a cleared page while we run.
                    val keep = repo.ocrStore.clearForReread(docId, replaceCorrections = true)
                    for (page in doc.pageFiles) {
                        if (!isActive) return@withContext stored
                        if (page.name in keep) continue
                        val sourceHash = OcrStore.locked { Sha256.of(page) }
                        val result = try {
                            OcrEngine.recognizeFile(page, language)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (OcrEngine.isUnavailable(e)) unavailable = unavailable ?: e
                            continue
                        } catch (_: OutOfMemoryError) {
                            continue
                        }
                        stored += repo.ocrStore.putRecognizedPages(
                            docId,
                            listOf(OcrStore.Recognized(page.name, sourceHash, result.text, result.page))
                        ).stored
                    }
                    if (stored + doc.pageFiles.count { it.name in keep } == doc.pageFiles.size) {
                        repo.ocrStore.clearPending(docId)
                    }
                } else {
                    // One-shot path: replace page by page, keeping old text on any failure.
                    val texts = repo.ocrStore.readText(docId)
                    val geometries = repo.ocrStore.readGeo(docId)
                    for (page in doc.pageFiles) {
                        if (!isActive) return@withContext stored
                        // Text without geometry is the person's own entry or correction.
                        if (texts[page.name] != null && !geometries.containsKey(page.name)) continue
                        val sourceHash = OcrStore.locked { Sha256.of(page) }
                        val result = try {
                            OcrEngine.recognizeFile(page, language)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (OcrEngine.isUnavailable(e)) unavailable = unavailable ?: e
                            continue
                        } catch (_: OutOfMemoryError) {
                            continue
                        }
                        // SEC2 R3: identity re-check and publish happen under one
                        // OCR lock; a refusal after a Vault move is quiet.
                        if (!repo.ocrStore.putPage(
                                docId, page.name, result.text, result.page, sourceHash
                            )) continue
                        stored++
                    }
                    if (doc.pageFiles.all { repo.ocrStore.readText(docId).containsKey(it.name) }) {
                        repo.ocrStore.clearPending(docId)
                    }
                }
            } finally {
                // D9-1: the stored text was rewritten; any open Text screen must reload it instead
                // of keeping (and later saving) the old text.
                OcrTextEvents.notifyChanged()
            }
            // P1: rebuild the searchable PDF here. clearPending() dequeues the document, so the
            // indexer's afterIndexed refresh can never fire for a re-read; without this the PDF
            // would keep the old text layer even though the stored text changed.
            if (stored > 0) repo.refreshPdfAfterIndex(docId)
            // P2: a missing text model is a device reason, not a page failure. Surface it even
            // when some pages were read, so the Text screen says "unavailable", not "failed".
            if (unavailable != null) throw unavailable!!
            stored
        }
    }
}
