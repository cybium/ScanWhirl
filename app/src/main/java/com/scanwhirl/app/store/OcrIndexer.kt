package com.scanwhirl.app.store

import com.scanwhirl.app.util.Durable
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
 *
 * F-02: a page that throws (bad JPEG, disk error, OutOfMemoryError) is retried on the next
 * pass, and the document keeps its `ocr_pending` marker while it has attempts left. The
 * attempts are stored per page and per page image, capped at [MAX_PAGE_ATTEMPTS], so a page
 * that cannot be decoded is never attempted forever on every cold start. Once the budget is
 * used up the marker is cleared and the Text screen says the pages could not be read.
 */
class OcrIndexer(
    private val repo: ScanRepository,
    private val scope: CoroutineScope,
    private val recognize: suspend (File) -> OcrEngine.OcrResult = {
        OcrEngine.recognizeFile(it, OcrLanguageSettings.get(repo.appContext()))
    },
    private val autoRun: Boolean = true,
    private val afterIndexed: suspend (String) -> Unit = {},
    /** Pages committed per store write; a crash loses at most this many recognized pages. */
    private val batchPages: Int = 16,
    /** Longest a partial batch waits before it is committed (the page itself may take seconds). */
    private val batchMillis: Long = 2_000L
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
    private val rerun = mutableSetOf<String>()
    /** All queue bookkeeping uses this lock; never held across recognition or suspension. */
    private val queueLock = Any()
    /** Serializes indexing passes, not queue state. */
    private val mutex = Mutex()

    private val _state = MutableStateFlow(IndexState(null, 0, 0, 0))
    val state: StateFlow<IndexState> = _state.asStateFlow()

    init {
        if (autoRun) {
            scope.launch {
                consumerLoop()
            }
        }
    }

    /** Marks [docId] pending and queues it for indexing (deduplicated). No-op when `autoRun` is false. */
    fun enqueue(docId: String) {
        synchronized(queueLock) {
            // Serialize the marker with consumer completion: an enqueue must not be lost
            // between clearPending and removal from inFlight.
            store.markPending(docId)
            if (!autoRun) return
            if (docId in inFlight) {
                rerun.add(docId)
            } else if (enqueued.add(docId)) {
                // UNLIMITED: publishing is synchronous and cannot be cancelled between
                // adding the set entry and delivering its work item.
                if (channel.trySend(docId).isFailure) enqueued.remove(docId)
            }
        }
    }

    /**
     * Re-enqueues every document that still carries an `ocr_pending` marker, newest first.
     * Reads the store's small pending queue instead of listing every document directory.
     */
    fun resumePending() {
        for (id in store.pendingQueue()) {
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
        val unavailable: Boolean,
        /**
         * F-02: pages that have used up their automatic attempts and stay unread.
         * Automatic retries stop for them; the person can still tap Try again.
         */
        val gaveUpPages: Int = 0
    )

    /**
     * Runs the indexing pass for [docId] in the caller's coroutine under the
     * [mutex]. A page whose key is already present in the store is skipped
     * (resume-safe, and it never overwrites corrected text). Page-level
     * exceptions leave the key absent. When recognition is unavailable the
     * pass stops early: the remaining pages would fail the same way. They stay
     * absent ("not read") and are counted as failed. [CancellationException]
     * is rethrown.
     *
     * [resetAttempts] is for an explicit "Try again" from the person: it gives every page a
     * fresh automatic retry budget (automatic passes never reset it themselves).
     */
    suspend fun index(docId: String, resetAttempts: Boolean = false): IndexReport = mutex.withLock {
        try {
            // PERF item 5: reuse one recognizer per script for this whole document pass,
            // then close them when the pass ends (including cancellation and failure).
            OcrEngine.withRecognizerSession {
                store.withRecognitionLock(docId) { indexDocument(docId, resetAttempts) }
            }
        } catch (failure: Throwable) {
            // A failed commit is not a completed pass, including a direct index() call
            // that did not go through enqueue(). Keep it discoverable on the next pass.
            try {
                store.markPending(docId)
            } catch (pendingFailure: Exception) {
                failure.addSuppressed(pendingFailure)
            }
            _state.value = IndexState(null, 0, 0, 1)
            throw failure
        }
    }

    /** F-02: true when an unread page of [docId] used up its automatic attempts. */
    fun retryExhausted(docId: String): Boolean {
        val doc = repo.loadDocument(File(docsDir, docId)) ?: return false
        val texts = store.readText(docId)
        val geo = store.readGeo(docId)
        val attempts = readAttempts(docId)
        return doc.pageFiles.any { page ->
            val key = page.name
            key !in texts && key !in geo && (attempts[key]?.count ?: 0) >= MAX_PAGE_ATTEMPTS
        }
    }

    private suspend fun indexDocument(docId: String, resetAttempts: Boolean = false): IndexReport {
        val doc = repo.loadDocument(File(docsDir, docId))
            ?: return IndexReport(0, 0, 0, false)

        _state.value = IndexState(docId, 0, 0, 0)
        var failedPages = 0
        var superseded = false
        var unavailable = false
        // page key -> attempts so far for THIS page image (a replaced page starts again)
        val attempts = if (resetAttempts) mutableMapOf() else readAttempts(docId)

        val existingText = store.readText(docId)
        val existingGeo = store.readGeo(docId)
        val total = doc.pageFiles.size
        var done = 0

        // Recognized pages wait here until a batch commit (16 pages or 2 s), so indexing costs
        // N/16 whole-file writes instead of one per page.
        val pending = ArrayList<OcrStore.Recognized>(batchPages)
        var lastFlush = System.nanoTime()

        fun flush() {
            if (pending.isEmpty()) return
            val result = store.putRecognizedPages(docId, pending)
            pending.clear()
            lastFlush = System.nanoTime()
            if (result.stale > 0) {
                superseded = true
                failedPages += result.stale
            }
        }

        try {
            for (page in doc.pageFiles) {
                val key = page.name
                if (key in existingText || key in existingGeo) continue
                if (unavailable) {
                    failedPages++
                    continue
                }

                _state.value = IndexState(docId, done, total, failedPages)

                var sourceHash: String? = null
                try {
                    sourceHash = OcrStore.locked { Sha256.of(page) }
                    val previous = attempts[key]
                    val used = if (previous?.imageHash == sourceHash) previous.count else 0
                    if (used >= MAX_PAGE_ATTEMPTS) {
                        // F-02: automatic retries are used up. Never keep decoding a page that
                        // fails (an OOM loop would come back at every launch). The marker is
                        // cleared below and the Text screen says the pages could not be read.
                        failedPages++
                        done++
                        continue
                    }
                    val result = recognize(page)
                    pending += OcrStore.Recognized(key, sourceHash, result.text, result.page)
                    attempts.remove(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Page unreadable: key stays absent ("not read"), never stored as empty text.
                    failedPages++
                    countAttempt(attempts, key, sourceHash ?: NO_IMAGE_HASH)
                    if (OcrEngine.isUnavailable(e)) unavailable = true
                } catch (_: OutOfMemoryError) {
                    // C06: a 24 MB page plus the recognizer's copy can exhaust a 2 GB phone.
                    // Count the page as unread and keep indexing the rest; never kill the app.
                    failedPages++
                    countAttempt(attempts, key, sourceHash ?: NO_IMAGE_HASH)
                }
                // Persistence is not a recognition failure. It must escape the page retry
                // handler rather than spend the per-image recognition budget.
                if (pending.size >= batchPages ||
                    (System.nanoTime() - lastFlush) / 1_000_000 >= batchMillis
                ) {
                    flush()
                }
                done++
            }
        } finally {
            // Commit what was recognized even when the pass is cancelled: at most the current
            // page is lost, which stays absent and is recognized again on resume.
            flush()
        }

        // Do not erase an invalidation raised while this pass was recognizing. When
        // recognition was unavailable the document stays pending, so a later
        // launch retries it (resumePending) and Home keeps saying "waiting".
        attempts.keys.retainAll(doc.pageFiles.map { it.name }.toSet())
        attempts.keys.retainAll { key -> key !in existingText && key !in existingGeo }
        writeAttempts(docId, attempts)
        val storedNow = store.readText(docId)
        val storedGeoNow = store.readGeo(docId)
        val unreadAfter = doc.pageFiles
            .map { it.name }
            .filter { it !in storedNow && it !in storedGeoNow }
        // F-02: a page-level failure only stays queued while it has automatic attempts left.
        val retryLeft = unreadAfter.any { key ->
            (attempts[key]?.count ?: 0) < MAX_PAGE_ATTEMPTS
        }
        if (!superseded && !unavailable) {
            if (retryLeft) store.markPending(docId) else store.clearPending(docId)
        }
        val gaveUp = unreadAfter.count { (attempts[it]?.count ?: 0) >= MAX_PAGE_ATTEMPTS }
        _state.value = IndexState(null, done, total, failedPages)

        OcrTextEvents.notifyChanged()
        afterIndexed(docId)

        // Stored page names are exactly the page keys: count this document's pages against
        // them once (the old code scanned every page for every stored key).
        val storedText = store.readText(docId)
        val stored = doc.pageFiles.count { it.name in storedText }
        return IndexReport(total, stored, failedPages, unavailable, gaveUp)
    }

    /** F-02: one more failed attempt for this page image; the counter is per page image. */
    private fun countAttempt(
        attempts: MutableMap<String, PageAttempt>,
        key: String,
        imageHash: String
    ) {
        val previous = attempts[key]
        val used = if (previous?.imageHash == imageHash) previous.count else 0
        attempts[key] = PageAttempt(imageHash, used + 1)
    }

    private fun retryFile(docId: String): File = File(File(docsDir, docId), RETRY_FILE)

    /** F-02: page key -> attempts for the page image that was tried. */
    private fun readAttempts(docId: String): MutableMap<String, PageAttempt> {
        val file = retryFile(docId)
        if (!file.isFile) return mutableMapOf()
        val out = mutableMapOf<String, PageAttempt>()
        try {
            for (line in file.readLines()) {
                if (line == RETRY_HEADER) continue
                val parts = line.split('\t')
                if (parts.size != 3) continue
                val count = parts[2].toIntOrNull() ?: continue
                out[parts[0]] = PageAttempt(parts[1], count)
            }
        } catch (_: Exception) {
            return mutableMapOf()
        }
        return out
    }

    private fun writeAttempts(docId: String, attempts: Map<String, PageAttempt>) {
        val file = retryFile(docId)
        if (attempts.isEmpty()) {
            if (file.exists() && !file.delete()) throw java.io.IOException("Could not clear OCR attempts")
            return
        }
        val body = (listOf(RETRY_HEADER) + attempts.map { (key, attempt) ->
            "$key\t${attempt.imageHash}\t${attempt.count}"
        }).joinToString("\n")
        Durable.atomicWriteText(file, body)
    }

    private suspend fun consumerLoop() {
        while (true) {
            val docId = channel.receive()
            synchronized(queueLock) {
                enqueued.remove(docId)
                inFlight.add(docId)
            }
            try {
                indexNow(docId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Page-level failures are counted inside indexNow; nothing to do here.
            } catch (_: OutOfMemoryError) {
                // C06: the consumer must survive a page that could not be decoded.
            } finally {
                synchronized(queueLock) {
                    inFlight.remove(docId)
                    if (rerun.remove(docId)) {
                        // This request may refer to a newer page revision than the pass
                        // just completed. A resume-safe follow-up never re-recognizes
                        // already stored pages.
                        store.markPending(docId)
                        if (enqueued.add(docId) && channel.trySend(docId).isFailure) {
                            enqueued.remove(docId)
                        }
                    }
                }
            }
        }
    }

    private data class PageAttempt(val imageHash: String, val count: Int)

    private companion object {
        /** F-02: automatic attempts per page image before retries stop. Never unlimited on OOM. */
        const val MAX_PAGE_ATTEMPTS = 3
        const val RETRY_FILE = "ocr_retry.txt"
        const val RETRY_HEADER = "ocr-retry-v1"
        /** Page image could not even be hashed (unreadable file): its own budget. */
        const val NO_IMAGE_HASH = ""
    }
}

/**
 * D9-1: in-process notification that a document's stored OCR text may have changed, so an open
 * Text screen can reload the pages the person did not edit instead of showing stale text (and
 * then writing it back). Notification only — the store stays the single source of truth.
 */
object OcrTextEvents {

    private val _revision = MutableStateFlow(0L)

    /** Increments after a document's stored text was written or cleared. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun notifyChanged() {
        _revision.value = _revision.value + 1L
    }
}
