package com.scanwhirl.app.store

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import kotlin.concurrent.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * One store for OCR text and line geometry, correct across page
 * delete/move/rotate/crop operations.
 *
 * Files in each document directory:
 *   - `ocr.txt` (unchanged format and header `#scanwhirl-ocr-v2`)
 *   - `ocr_geo.txt` (header `#scanwhirl-ocrgeo-v1`; one line per page:
 *     `pageKey<TAB>Base64(UTF-8 payload)`; payload line 1 is
 *     `<width> <height>`, each further line is
 *     `<left> <top> <right> <bottom><TAB><text>` with tabs, CR and LF in
 *     `text` replaced by spaces)
 *   - `ocr_pending` marker file (presence means "needs indexing")
 *
 * All methods synchronise on one process-wide lock because several
 * [ScanRepository] instances exist at once. Every write goes to `<file>.tmp`
 * and is fsynced and renamed over the target. Failed replacement never
 * truncates the previous file. Geometry payloads may carry a text digest;
 * this binds geometry to the text it describes across interrupted edits.
 */
class OcrStore(private val docsDir: File) {

    private val OCR_HEADER = "#scanwhirl-ocr-v2"
    private val OCR_GEO_HEADER = "#scanwhirl-ocrgeo-v1"
    private val PENDING_FILE = "ocr_pending"
    private val TEXT_FILE = "ocr.txt"
    private val GEO_FILE = "ocr_geo.txt"

    companion object {
        private val lock = ReentrantLock()

        /** Atomic writes performed since [resetWriteCountForTests]; a test seam for write counts. */
        private val writeCount = java.util.concurrent.atomic.AtomicInteger()

        internal fun writeCountForTests(): Int = writeCount.get()
        internal fun resetWriteCountForTests() { writeCount.set(0) }

        // Never acquire the repository PDF lock while holding this lock.
        internal fun <T> locked(action: () -> T): T = lock.withLock(action)
    }

    private fun docDir(id: String): File = File(docsDir, id)

    /** One small file listing documents that carry an `ocr_pending` marker (see [pendingQueue]). */
    private val queueFile: File
        get() = File(docsDir.parentFile ?: docsDir, "ocr_pending_queue.txt")

    private fun pageKeyFor(index: Int): String = "page_%03d.jpg".format(index)

    // ------------------------------------------------------------------
    // Internal write helpers
    // ------------------------------------------------------------------

    /** Writes [content] to [target] atomically via `.tmp` + rename. */
    private fun atomicWrite(target: File, content: String) {
        Durable.atomicWriteText(target, content)
        target.parentFile?.let { Durable.syncDir(it) }
        writeCount.incrementAndGet()
    }

    private fun digest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun encodeText(text: String): String =
        android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    private fun decodeText(b64: String): String =
        String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), Charsets.UTF_8)

    private fun encodeGeo(page: OcrPage): String = buildString {
        append("${page.width} ${page.height}\n")
        page.lines.forEach { line ->
            // Replace tabs/CR/LF in text with spaces so the payload stays line-based.
            val safeText = line.text.replace("\t", " ").replace("\r", " ").replace("\n", " ")
            append("${line.left} ${line.top} ${line.right} ${line.bottom}\t$safeText\n")
        }
    }

    private fun decodeGeo(payload: String): OcrPage? {
        val payloadLines = payload.lines()
        if (payloadLines.isEmpty()) return null
        val dims = payloadLines.first().split(' ', limit = 2)
        if (dims.size != 2) return null
        val width = dims[0].toIntOrNull() ?: return null
        val height = dims[1].toIntOrNull() ?: return null
        val lines = mutableListOf<OcrLine>()
        for (geoLine in payloadLines.drop(1)) {
            val geoParts = geoLine.split('\t', limit = 2)
            if (geoParts.size != 2) continue
            val coords = geoParts[0].split(' ', limit = 4)
            if (coords.size != 4) continue
            val left = coords[0].toIntOrNull() ?: continue
            val top = coords[1].toIntOrNull() ?: continue
            val right = coords[2].toIntOrNull() ?: continue
            val bottom = coords[3].toIntOrNull() ?: continue
            lines.add(
                OcrLine(
                    text = geoParts[1],
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )
            )
        }
        return OcrPage(width, height, lines)
    }

    // ------------------------------------------------------------------
    // Text (ocr.txt)
    // ------------------------------------------------------------------

    /** Reads both the v2 format and the legacy no-header format exactly as
     *  [ScanRepository.ocrDoc] does today. */
    fun readText(id: String): Map<String, String> = lock.withLock {
        try { readTextStrict(id) } catch (_: Exception) { emptyMap() }
    }

    private fun readTextStrict(id: String): Map<String, String> {
        val file = File(docDir(id), TEXT_FILE)
        if (!file.exists()) return emptyMap()
        return run {
            val lines = file.readLines()
            if (lines.firstOrNull() == OCR_HEADER) {
                lines.drop(1).mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) throw IOException("Malformed OCR text")
                    parts[0] to decodeText(parts[1])
                }.toMap()
            } else {
                // Legacy file (pre-test21): only the first line of each page survived.
                lines.mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
            }
        }
    }

    /** Intentional replace-all operation. Editors must use [updateTexts] instead. */
    fun putTexts(id: String, pages: Map<String, String>) {
        lock.withLock {
            val dir = docDir(id)
            dir.mkdirs()
            bindGeometry(id, readTextStrict(id))
            atomicWrite(
                File(dir, TEXT_FILE),
                (listOf(OCR_HEADER) + pages.map { (name, text) ->
                    "$name\t${encodeText(text)}"
                }).joinToString("\n")
            )
        }
    }

    /** Merge only changed keys. The single text rename is the edit commit point.
     * Bind legacy geometry before committing text, so an interrupted edit leaves
     * old text readable and never presents old coordinates for corrected text.
     * Geometry for untouched pages remains valid; pending status is unchanged.
     */
    fun updateTexts(id: String, changes: Map<String, String>): Map<String, String> = lock.withLock {
        if (!docDir(id).isDirectory) throw IOException("Document no longer exists")
        val before = readTextStrict(id)
        if (changes.isEmpty()) return@withLock before
        bindGeometry(id, before)
        val merged = before + changes
        writeTexts(id, merged)
        merged
    }

    /**
     * PM-2.2b: writes the OCR carried over from a draft's base into a staging folder in one go.
     * Geometry goes first; the text rename is the commit point (`OcrStore.kt` write order).
     */
    internal fun putAll(id: String, texts: Map<String, String>, geo: Map<String, OcrPage>) {
        lock.withLock {
            docDir(id).mkdirs()
            if (geo.isNotEmpty()) writeGeometry(id, geo.filterKeys { it in texts }, texts)
            if (texts.isNotEmpty()) writeTexts(id, texts)
        }
    }

    private fun writeTexts(id: String, texts: Map<String, String>) = atomicWrite(
        File(docDir(id), TEXT_FILE),
        (listOf(OCR_HEADER) + texts.map { (key, text) -> "$key\t${encodeText(text)}" }).joinToString("\n")
    )

    private fun writeGeometry(
        id: String,
        geo: Map<String, OcrPage>,
        texts: Map<String, String>,
        previous: Map<String, Pair<String, OcrPage>> = emptyMap()
    ) {
        fun record(key: String, text: String, page: OcrPage): String {
            val payload = encodeGeo(page) + "#text-sha256 ${digest(text)}\n"
            return "$key\t${encodeText(payload)}"
        }
        atomicWrite(File(docDir(id), GEO_FILE),
            (listOf(OCR_GEO_HEADER) + previous.map { (key, old) -> record(key, old.first, old.second) } +
                geo.map { (key, page) -> record(key, texts[key].orEmpty(), page) }).joinToString("\n"))
    }

    private fun bindGeometry(id: String, texts: Map<String, String>) {
        if (File(docDir(id), GEO_FILE).exists()) writeGeometry(id, readGeoStrict(id, texts), texts)
    }

    // ------------------------------------------------------------------
    // Geometry (ocr_geo.txt)
    // ------------------------------------------------------------------

    fun readGeo(id: String): Map<String, OcrPage> = lock.withLock {
        try { readGeoStrict(id, readTextStrict(id)) } catch (_: Exception) { emptyMap() }
    }

    private fun readGeoStrict(id: String, texts: Map<String, String>): Map<String, OcrPage> {
        val file = File(docDir(id), GEO_FILE)
        if (!file.exists()) return emptyMap()
        return run {
            val lines = file.readLines()
            if (lines.firstOrNull() != OCR_GEO_HEADER) throw IOException("Malformed OCR geometry")
            else lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2) throw IOException("Malformed OCR geometry")
                val payload = decodeText(parts[1])
                val bound = payload.lineSequence().firstOrNull { it.startsWith("#text-sha256 ") }
                    ?.removePrefix("#text-sha256 ")
                if (bound != null && (parts[0] !in texts || bound != digest(texts.getValue(parts[0])))) null
                else (decodeGeo(payload) ?: throw IOException("Malformed OCR geometry")).let { parts[0] to it }
            }.toMap()
        }
    }

    /** Writes one page's text + geometry in one synchronized rewrite. */
    fun putPage(id: String, key: String, text: String, page: OcrPage?) {
        lock.withLock {
            val dir = docDir(id)
            dir.mkdirs()

            val before = readTextStrict(id)
            val currentGeo = readGeoStrict(id, before).toMutableMap()
            // Keep the previous geometry candidate until the text commit. A
            // failed/cancelled text write can still resolve the old pair.
            val previous = currentGeo[key]?.takeIf { before[key] != text }
                ?.let { mapOf(key to (before[key].orEmpty() to it)) } ?: emptyMap()
            val currentText = before.toMutableMap()
            currentText[key] = text
            if (page == null) {
                currentGeo.remove(key)
            } else {
                currentGeo[key] = page
            }
            writeGeometry(id, currentGeo, currentText, previous)
            writeTexts(id, currentText)
        }
    }

    /** Recognition runs outside all file locks. Recheck under the shared lock:
     * an editor, delete, rotation or reorder may have superseded this result.
     */
    internal fun putRecognizedPage(id: String, key: String, sourceHash: String, text: String, page: OcrPage?): Boolean {
        val result = putRecognizedPages(id, listOf(Recognized(key, sourceHash, text, page)))
        return result.stored + result.already > 0
    }

    /** A recognized page waiting for a batch commit. */
    data class Recognized(val key: String, val sourceHash: String, val text: String, val page: OcrPage?)

    /** How a batch commit ended: pages written, pages already stored, pages whose source changed. */
    data class BatchResult(val stored: Int, val already: Int, val stale: Int)

    /**
     * Commits several recognized pages with ONE geometry write and ONE text write. Order is the
     * same as [putPage]: geometry first, then the text rename as the commit point, so a crash
     * between the two leaves readable text that no longer claims the new coordinates. Pages that
     * were already stored are left alone (resume-safe, never overwrites corrected text), and a
     * page whose file changed while recognition ran is dropped exactly like [putRecognizedPage].
     *
     * This is what makes indexing linear: N pages cost N/16 commits instead of N whole-file
     * rewrites. A crash loses at most the caller's unflushed batch, which the next pass reads again.
     */
    internal fun putRecognizedPages(id: String, pages: List<Recognized>): BatchResult = lock.withLock {
        if (pages.isEmpty()) return@withLock BatchResult(0, 0, 0)
        val dir = docDir(id)
        // Read before creating anything: a page whose directory was deleted (or a hash that no
        // longer matches) must never resurrect the document.
        val before = readTextStrict(id).toMutableMap()
        val geo = readGeoStrict(id, before).toMutableMap()
        val previous = mutableMapOf<String, Pair<String, OcrPage>>()
        var stored = 0
        var already = 0
        var stale = 0

        for (p in pages) {
            val source = File(dir, p.key)
            if (!source.isFile || Sha256.of(source) != p.sourceHash) {
                stale++
                continue
            }
            if (p.key in before) {
                already++
                continue
            }
            // Keep the old geometry candidate until the text commit, as putPage does.
            val oldGeo = geo[p.key]
            if (oldGeo != null && before[p.key] != p.text) {
                previous[p.key] = before[p.key].orEmpty() to oldGeo
            }
            if (p.page == null) geo.remove(p.key) else geo[p.key] = p.page
            before[p.key] = p.text
            stored++
        }

        if (stored > 0) {
            dir.mkdirs()
            writeGeometry(id, geo, before, previous)
            writeTexts(id, before)
        }
        BatchResult(stored, already, stale)
    }

    // ------------------------------------------------------------------
    // Page operations
    // ------------------------------------------------------------------

    /** Drops [index]'s key and shifts every later key down by one (text and geometry). */
    fun afterDelete(id: String, index: Int) {
        lock.withLock {
            val dir = docDir(id)
            val removedKey = pageKeyFor(index)
            val currentText = readText(id).toMutableMap()
            val currentGeo = readGeo(id).toMutableMap()
            currentText.remove(removedKey)
            currentGeo.remove(removedKey)

            // Rebuild with shifted keys: keep pages before `index`, drop the
            // page at `index`, and shift every page after it down by one.
            val newText = mutableMapOf<String, String>()
            val newGeo = mutableMapOf<String, OcrPage>()
            for (oldKey in currentText.keys + currentGeo.keys) {
                val i = oldKey.removePrefix("page_").removeSuffix(".jpg").toIntOrNull()
                val newKey = if (i != null && i > index) pageKeyFor(i - 1) else oldKey
                currentText[oldKey]?.let { newText[newKey] = it }
                currentGeo[oldKey]?.let { newGeo[newKey] = it }
            }

            if (newText.isEmpty()) {
                File(dir, TEXT_FILE).delete()
            } else {
                atomicWrite(
                    File(dir, TEXT_FILE),
                    (listOf(OCR_HEADER) + newText.map { (name, t) ->
                        "$name\t${encodeText(t)}"
                    }).joinToString("\n")
                )
            }
            if (newGeo.isEmpty()) {
                File(dir, GEO_FILE).delete()
            } else {
                atomicWrite(
                    File(dir, GEO_FILE),
                    (listOf(OCR_GEO_HEADER) + newGeo.map { (name, p) ->
                        "$name\t${encodeText(encodeGeo(p))}"
                    }).joinToString("\n")
                )
            }
        }
    }

    /** Applies the same permutation [ScanRepository.movePage] applies to files. */
    fun afterMove(id: String, from: Int, to: Int) {
        lock.withLock {
            val dir = docDir(id)
            fun remap(key: String): String {
                val index = key.removePrefix("page_").removeSuffix(".jpg").toIntOrNull() ?: return key
                return pageKeyFor(when {
                    index == from -> to
                    from < to && index in (from + 1)..to -> index - 1
                    from > to && index in to until from -> index + 1
                    else -> index
                })
            }
            val currentText = readText(id).mapKeys { remap(it.key) }
            val currentGeo = readGeo(id).mapKeys { remap(it.key) }

            if (currentText.isEmpty()) {
                File(dir, TEXT_FILE).delete()
            } else {
                atomicWrite(
                    File(dir, TEXT_FILE),
                    (listOf(OCR_HEADER) + currentText.map { (name, t) ->
                        "$name\t${encodeText(t)}"
                    }).joinToString("\n")
                )
            }
            if (currentGeo.isEmpty()) {
                File(dir, GEO_FILE).delete()
            } else {
                atomicWrite(
                    File(dir, GEO_FILE),
                    (listOf(OCR_GEO_HEADER) + currentGeo.map { (name, p) ->
                        "$name\t${encodeText(encodeGeo(p))}"
                    }).joinToString("\n")
                )
            }
        }
    }

    /** Drops [key] from both text and geometry files, then marks pending. */
    fun invalidate(id: String, key: String) {
        lock.withLock {
            val dir = docDir(id)
            val currentText = readText(id).toMutableMap()
            val currentGeo = readGeo(id).toMutableMap()
            currentText.remove(key)
            currentGeo.remove(key)

            if (currentText.isEmpty()) {
                File(dir, TEXT_FILE).delete()
            } else {
                atomicWrite(
                    File(dir, TEXT_FILE),
                    (listOf(OCR_HEADER) + currentText.map { (name, t) ->
                        "$name\t${encodeText(t)}"
                    }).joinToString("\n")
                )
            }
            if (currentGeo.isEmpty()) {
                File(dir, GEO_FILE).delete()
            } else {
                atomicWrite(
                    File(dir, GEO_FILE),
                    (listOf(OCR_GEO_HEADER) + currentGeo.map { (name, p) ->
                        "$name\t${encodeText(encodeGeo(p))}"
                    }).joinToString("\n")
                )
            }

            markPending(id)
        }
    }

    // ------------------------------------------------------------------
    // Pending marker
    // ------------------------------------------------------------------

    /**
     * Marks an EXISTING, visible document for indexing. It never creates a
     * directory and never touches a directory a save transaction still owns
     * (`.incomplete`), so it cannot resurrect a deleted or unpublished document.
     */
    fun markPending(id: String) {
        lock.withLock {
            val dir = docDir(id)
            if (!dir.isDirectory || SaveFormat.isIncomplete(dir)) return
            // Queue first: a crash before the marker still leaves the id in the queue, and the
            // next pass marks it again. The marker alone is the older, slower fallback.
            enqueuePending(id)
            File(dir, PENDING_FILE).writeText("")
        }
    }

    fun clearPending(id: String) {
        lock.withLock {
            File(docDir(id), PENDING_FILE).delete()
            dequeuePending(id)
        }
    }

    /**
     * The documents waiting for indexing, newest first. Reads one small queue file instead of
     * listing every document directory on launch; entries whose marker disappeared (indexed,
     * deleted, or a save that never published) are dropped and the file is rewritten.
     *
     * A missing queue file (first launch after an upgrade) is rebuilt once from the markers.
     */
    internal fun pendingQueue(): List<String> = lock.withLock {
        val rebuilt = !queueFile.isFile
        val listed = if (!rebuilt) readQueue() else scanPendingMarkers()
        val alive = listed.filter { id ->
            val dir = docDir(id)
            dir.isDirectory && !SaveFormat.isIncomplete(dir) && File(dir, PENDING_FILE).isFile
        }.sortedByDescending { docDir(it).lastModified() }
        if (rebuilt || alive != listed) writeQueue(alive)
        alive
    }

    private fun enqueuePending(id: String) {
        val current = if (queueFile.isFile) readQueue() else scanPendingMarkers()
        if (id in current) return
        writeQueue(current + id)
    }

    private fun dequeuePending(id: String) {
        if (!queueFile.isFile) return
        val current = readQueue()
        if (id !in current) return
        writeQueue(current - id)
    }

    private fun readQueue(): List<String> = try {
        queueFile.takeIf { it.isFile }?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    private fun writeQueue(ids: List<String>) {
        try {
            Durable.atomicWriteText(queueFile, ids.joinToString("\n"))
            queueFile.parentFile?.let { Durable.syncDir(it) }
        } catch (_: IOException) {
            // The marker files stay authoritative; pendingQueue() rebuilds from them next time.
        }
    }

    private fun scanPendingMarkers(): List<String> =
        docsDir.listFiles { f ->
            f.isDirectory && !SaveFormat.isIncomplete(f) && File(f, PENDING_FILE).isFile
        }?.sortedByDescending { it.lastModified() }?.mapNotNull { it.name } ?: emptyList()

    /**
     * Drops every page's text and geometry (a re-recognition in another language) and marks the
     * document pending, so the indexer reads every page again. One atomic rewrite of each file.
     */
    fun clearAll(id: String) {
        lock.withLock {
            val dir = docDir(id)
            if (!dir.isDirectory) return@withLock
            File(dir, TEXT_FILE).delete()
            File(dir, GEO_FILE).delete()
        }
        markPending(id)
    }

    fun isPending(id: String): Boolean = File(docDir(id), PENDING_FILE).exists()

    fun hasText(id: String): Boolean = readText(id).isNotEmpty()
}
