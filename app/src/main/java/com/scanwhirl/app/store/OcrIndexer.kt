package com.scanwhirl.app.store

import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguageSettings
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Background text-recognition indexer that survives the screen that started it.
 *
 * Runs in an application-scoped coroutine, one document at a time, one page at a
 * time. Partial progress survives: each page is stored through [OcrStore.putPage]
 * immediately after recognition, so a process kill only loses the in-flight page.
 *
 * Documents that need indexing are marked with an `ocr_pending` file (written by
 * [OcrStore.markPending] / [ScanRepository.saveScan] / [ScanRepository.finalizeDoc]).
 * `enqueue` adds a document to the queue; `resumePending` re-enqueues every
 * document that still carries the marker (e.g. after a process death).
 *
 * When [autoRun] is false (Robolectric), `enqueue` only writes the marker and no
 * consumer coroutine is started — tests drive the indexer directly via `indexNow`.
 */
class OcrIndexer(
    private val repo: ScanRepository,
    private val scope: CoroutineScope,
    private val recognize: suspend (File) -> OcrEngine.OcrResult = {
        OcrEngine.recognizeFile(it, OcrLanguageSettings.get(repo.appContext()))
    },
    private val autoRun: Boolean = true,
    private val afterIndexed: suspend (String) -> Unit = {}
) {

    private val store: OcrStore = repo.ocrStore
    private val docsDir: File = repo.docsDir

    /** Idle state has `docId == null`. */
    data class IndexState(
        val docId: String?,
        val done: Int,
        val total: Int,
        val failedPages: Int
    )

    private val channel = Channel<String>(Channel.UNLIMITED)
    private val enqueued = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()
    private val mutex = Mutex()

    private val _state = MutableStateFlow(IndexState(null, 0, 0, 0))
    val state: StateFlow<IndexState> = _state.asStateFlow()

    private var consumerJob: kotlinx.coroutines.Job? = null

    init {
        if (autoRun) {
            consumerJob = scope.launch {
                consumerLoop()
            }
        }
    }

    /** Marks [docId] pending and queues it for indexing (deduplicated). No-op when `autoRun` is false. */
    fun enqueue(docId: String) {
        store.markPending(docId)
        if (!autoRun) return
        synchronized(this) {
            if (docId !in enqueued && docId !in inFlight) {
                enqueued.add(docId)
                scope.launch {
                    channel.send(docId)
                }
            }
        }
    }

    /** Re-enqueues every document directory that carries an `ocr_pending` marker, newest first. */
    fun resumePending() {
        val pending = docsDir.listFiles { f ->
            f.isDirectory && !SaveFormat.isIncomplete(f) && File(f, "ocr_pending").exists()
        }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { it.name }
            ?: emptyList()
        for (id in pending) {
            enqueue(id)
        }
    }

    /**
     * Runs the indexing pass for [docId] in the caller's coroutine under the
     * [mutex]. Returns the number of pages the recognizer rejected. A page whose
     * key is already present in the store is skipped (resume-safe). Page-level
     * exceptions leave the key absent and increment the failure count;
     * [CancellationException] is rethrown.
     */
    suspend fun indexNow(docId: String): Int = index(docId).failedPages

    /**
     * M06: what one indexing pass achieved, so the UI can tell "no text on the
     * page" (stored, empty) from "could not read" (absent) and from
     * "recognition unavailable on this device" (retry later).
     */
    data class IndexReport(
        /** Pages in the document. */
        val totalPages: Int,
        /** Pages with stored text after the pass (including ones stored earlier). */
        val pagesWithText: Int,
        /** Pages this pass attempted but could not store. */
        val failedPages: Int,
        /** Recognition itself was unavailable (e.g. text model not downloaded). */
        val unavailable: Boolean
    )

    /**
     * Runs the indexing pass for [docId] in the caller's coroutine under the
     * [mutex]. A page whose key is already present in the store is skipped
     * (resume-safe, and it never overwrites corrected text). Page-level
     * exceptions leave the key absent. When recognition is unavailable the
     * pass stops early: the remaining pages would fail the same way. They stay
     * absent ("not read") and are counted as failed. [CancellationException]
     * is rethrown.
     */
    suspend fun index(docId: String): IndexReport = mutex.withLock {
        _state.value = IndexState(docId, 0, 0, 0)
        var failedPages = 0
        var superseded = false
        var unavailable = false

        val doc = repo.loadDocument(File(docsDir, docId))
            ?: return@withLock IndexReport(0, 0, 0, false)

        val existingText = store.readText(docId)
        val existingGeo = store.readGeo(docId)
        val total = doc.pageFiles.size
        var done = 0

        for (page in doc.pageFiles) {
            val key = page.name
            if (key in existingText || key in existingGeo) continue
            if (unavailable) {
                failedPages++
                continue
            }

            _state.value = IndexState(docId, done, total, failedPages)

            try {
                val sourceHash = OcrStore.locked { Sha256.of(page) }
                val result = recognize(page)
                if (!store.putRecognizedPage(docId, key, sourceHash, result.text, result.page)) {
                    superseded = true
                    failedPages++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Page unreadable: key stays absent ("not read"), never stored as empty text.
                failedPages++
                if (OcrEngine.isUnavailable(e)) unavailable = true
            }
            done++
        }

        // Do not erase an invalidation raised while this pass was recognizing. When
        // recognition was unavailable the document stays pending, so a later
        // launch retries it (resumePending) and Home keeps saying "waiting".
        if (!superseded && !unavailable) store.clearPending(docId)
        _state.value = IndexState(null, done, total, failedPages)

        afterIndexed(docId)

        val stored = store.readText(docId).keys.count { k -> doc.pageFiles.any { it.name == k } }
        return@withLock IndexReport(total, stored, failedPages, unavailable)
    }

    private suspend fun consumerLoop() {
        while (true) {
            val docId = channel.receive()
            mutex.withLock {
                enqueued.remove(docId)
                inFlight.add(docId)
            }
            try {
                indexNow(docId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Page-level failures are counted inside indexNow; nothing to do here.
            } finally {
                mutex.withLock {
                    inFlight.remove(docId)
                }
            }
        }
    }
}
