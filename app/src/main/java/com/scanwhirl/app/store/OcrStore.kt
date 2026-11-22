package com.scanwhirl.app.store

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withCoroutineLock
import kotlin.concurrent.withLock
import java.io.ByteArrayOutputStream
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

    /** Legacy 4-digit-PIN Vault marker (see ScanRepository.VAULT_FILE). */
    private val VAULT_FILE = "vault.flag"

    companion object {
        /**
         * SECURITY_JUDGE 1.3.0 finding 4: an attacker-authored backup can carry
         * enormous text files. These caps are shared with restore-time rejection
         * so a file that was published can always be read within a bounded heap.
         */
        internal const val MAX_TEXT_BYTES = 8L * 1024 * 1024
        internal const val MAX_GEO_BYTES = 8L * 1024 * 1024
        private const val MAX_LINES = 200_000

        /**
         * Reads a text file only within [cap]. An oversized or line-bomb file is
         * treated as damaged (throws [IOException]) instead of being read whole.
         */
        internal fun readBoundedLines(file: File, cap: Long): List<String> {
            if (!file.isFile) return emptyList()
            if (file.length() > cap) throw IOException("Oversized text file")
            val bytes = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val want = minOf(buffer.size.toLong(), cap - total + 1).toInt()
                    val count = input.read(buffer, 0, want)
                    if (count < 0) break
                    if (count.toLong() > cap - total) throw IOException("Oversized text file")
                    bytes.write(buffer, 0, count)
                    total += count
                }
            }
            // useLines has the same readLine semantics as the old readLines():
            // a trailing newline does not create an extra empty line. Plain
            // String.lineSequence() does, which would mark every saved file as
            // malformed on the trailing record.
            val lines = ArrayList<String>()
            String(bytes.toByteArray(), Charsets.UTF_8).reader().buffered().useLines { sequence ->
                for (line in sequence) {
                    if (lines.size >= MAX_LINES) throw IOException("Too many text lines")
                    lines += line
                }
            }
            return lines
        }

        private val lock = ReentrantLock()

        private class RecognitionLock(val mutex: Mutex = Mutex(), var users: Int = 0)
        private val recognitionLocks = mutableMapOf<File, RecognitionLock>()

        /** Atomic writes performed since [resetWriteCountForTests]; a test seam for write counts. */
        private val writeCount = java.util.concurrent.atomic.AtomicInteger()

        internal fun writeCountForTests(): Int = writeCount.get()
        internal fun resetWriteCountForTests() { writeCount.set(0) }

        /** SEC2 N2: called under the OCR lock after validation, before publishing. */
        @Volatile internal var recognizedCommitProbe: (() -> Unit)? = null

        // Never acquire the repository PDF lock while holding this lock.
        internal fun <T> locked(action: () -> T): T = lock.withLock(action)
    }

    private fun docDir(id: String): File = File(docsDir, id)

    // Repository instances share a library. Hold this across recognition and publish, never the
    // file lock. Retain the entry until the last caller (including any waiter) has left.
    internal suspend fun <T> withRecognitionLock(id: String, action: suspend () -> T): T {
        val dir = docDir(id).absoluteFile
        val entry = synchronized(recognitionLocks) {
            recognitionLocks.getOrPut(dir) { RecognitionLock() }.also { it.users++ }
        }
        try {
            return entry.mutex.withCoroutineLock { action() }
        } finally {
            synchronized(recognitionLocks) {
                if (--entry.users == 0) recognitionLocks.remove(dir)
            }
        }
    }

    /** One small file listing documents that carry an `ocr_pending` marker (see [pendingQueue]). */
    private val queueFile: File
        get() = File(docsDir.parentFile ?: docsDir, "ocr_pending_queue.txt")

    // H4-3: page files are always named with Locale.ROOT (ASCII digits). Using the default
    // locale here produced "page_००५.jpg" on a Marathi/Arabic phone, so the shifted keys matched
    // no page and every later page lost its recognised text.
    private fun pageKeyFor(index: Int): String = String.format(java.util.Locale.ROOT, "page_%03d.jpg", index)

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
            // SEC1 1.3.0: bound every read; a restored 512 MiB file is damaged, not OOM.
            val lines = readBoundedLines(file, MAX_TEXT_BYTES)
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

    /**
     * Intentional replace-all operation. Editors must use [updateTexts] instead.
     * SEC2 R3: returns false and writes nothing when the live document is gone.
     */
    fun putTexts(id: String, pages: Map<String, String>): Boolean = lock.withLock {
        val dir = docDir(id)
        if (!dir.isDirectory) false
        else {
            bindGeometry(id, readTextStrict(id))
            atomicWrite(
                File(dir, TEXT_FILE),
                (listOf(OCR_HEADER) + pages.map { (name, text) ->
                    "$name\t${encodeText(text)}"
                }).joinToString("\n")
            )
            true
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

    data class UpdateResult(val texts: Map<String, String>, val conflictingKeys: Set<String>)

    /**
     * Compare and merge under the recognition publisher's file lock. Null means no stored entry,
     * not recognized empty text. A conflict performs no writes, including geometry binding.
     */
    fun updatePagesIfUnchanged(
        id: String,
        expected: Map<String, String?>,
        updates: Map<String, String>
    ): UpdateResult = lock.withLock {
        require(expected.keys.containsAll(updates.keys)) { "Every update needs an expected value" }
        if (!docDir(id).isDirectory) throw IOException("Document no longer exists")
        val before = readTextStrict(id)
        val conflicts = expected.keys.filterTo(linkedSetOf()) { before[it] != expected[it] }
        if (conflicts.isNotEmpty()) return@withLock UpdateResult(before, conflicts)
        if (updates.isEmpty()) return@withLock UpdateResult(before, emptySet())
        bindGeometry(id, before)
        val merged = before + updates
        writeTexts(id, merged)
        UpdateResult(merged, emptySet())
    }

    /**
     * PM-2.2b: writes the OCR carried over from a draft's base into a staging folder in one go.
     * Geometry goes first; the text rename is the commit point (`OcrStore.kt` write order).
     */
    internal fun putStagedAll(id: String, texts: Map<String, String>, geo: Map<String, OcrPage>) {
        lock.withLock {
            // Explicit staging API: the caller owns a fresh private staging
            // folder, so creating it here is intentional, not a live-document
            // resurrection. Live publishers use putPage/putTexts and refuse a
            // missing directory.
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
            val lines = readBoundedLines(file, MAX_GEO_BYTES)
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

    /**
     * Writes one page's text + geometry in one synchronized rewrite.
     * SEC2 R3: a late recognition result must never recreate a document that
     * was moved or purged while recognition ran. When [expectedSourceHash] is
     * given, the page's bytes are re-checked under this same lock, so identity
     * validation and publication are atomic. Returns false on refusal.
     */
    fun putPage(
        id: String,
        key: String,
        text: String,
        page: OcrPage?,
        expectedSourceHash: String? = null
    ): Boolean = lock.withLock {
        val dir = docDir(id)
        val source = File(dir, key)
        when {
            !dir.isDirectory -> false
            expectedSourceHash != null &&
                (!source.isFile || Sha256.of(source) != expectedSourceHash) -> false
            else -> {
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
                true
            }
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
            recognizedCommitProbe?.invoke()
            // SEC2 N2: a Vault move (or any delete) can remove this directory
            // between validation and publish. Never mkdirs a removed document:
            // that would leave plaintext OCR under a fresh ordinary library id.
            if (!dir.isDirectory) return@withLock BatchResult(0, already, stale)
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
     * Vault (`vault.flag`) and password-locked (`locked.meta`) documents are
     * never queued: their pages must never be read for text.
     */
    fun markPending(id: String) {
        lock.withLock {
            val dir = docDir(id)
            if (!dir.isDirectory || SaveFormat.isIncomplete(dir)) return
            if (File(dir, VAULT_FILE).exists() || File(dir, PdfProtection.LOCK_FILE).exists()) return
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
     * The documents waiting for indexing, newest first.
     *
     * The `ocr_pending` marker file is the only authority. [ScanRepository.buildAndPublish]
     * writes the marker inside a staging folder *before* the document has an id, so such a
     * document can never be queued at write time; older builds did the same. Trusting the
     * queue file alone therefore hid those documents forever (T41: a compressed or otherwise
     * re-encoded output was never recognised and never became searchable). The queue file is
     * kept as a small, self-healing cache of this listing; one directory listing is cheap next
     * to the page reads an indexing pass does.
     *
     * Vault (`vault.flag`) and password-locked (`locked.meta`) documents are never returned:
     * their pages must never be read for text.
     */
    internal fun pendingQueue(): List<String> = lock.withLock {
        val alive = scanPendingMarkers()
        if (readQueue() != alive) writeQueue(alive)
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
            f.isDirectory &&
                !SaveFormat.isIncomplete(f) &&
                File(f, PENDING_FILE).isFile &&
                !File(f, VAULT_FILE).exists() &&
                !File(f, PdfProtection.LOCK_FILE).exists()
        }?.sortedByDescending { it.lastModified() }?.mapNotNull { it.name } ?: emptyList()

    /**
     * Clears OCR for a reread. Without explicit permission to replace corrections, keep text
     * without matching recognition geometry: it may be manually entered or corrected.
     */
    internal fun clearForReread(id: String, replaceCorrections: Boolean): Set<String> = lock.withLock {
        val dir = docDir(id)
        if (!dir.isDirectory) return@withLock emptySet()
        val keep = if (replaceCorrections) emptyMap() else {
            val texts = readTextStrict(id)
            texts - readGeoStrict(id, texts).keys
        }
        if (keep.isEmpty()) {
            val textFile = File(dir, TEXT_FILE)
            if (textFile.exists() && !textFile.delete()) throw IOException("Could not clear OCR text")
        } else {
            writeTexts(id, keep)
        }
        val geoFile = File(dir, GEO_FILE)
        if (geoFile.exists() && !geoFile.delete()) throw IOException("Could not clear OCR geometry")
        markPending(id)
        keep.keys
    }

    fun isPending(id: String): Boolean = File(docDir(id), PENDING_FILE).exists()

    fun hasText(id: String): Boolean = readText(id).isNotEmpty()
}
