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

        // Never acquire the repository PDF lock while holding this lock.
        internal fun <T> locked(action: () -> T): T = lock.withLock(action)
    }

    private fun docDir(id: String): File = File(docsDir, id)

    private fun pageKeyFor(index: Int): String = "page_%03d.jpg".format(index)

    // ------------------------------------------------------------------
    // Internal write helpers
    // ------------------------------------------------------------------

    /** Writes [content] to [target] atomically via `.tmp` + rename. */
    private fun atomicWrite(target: File, content: String) {
        Durable.atomicWriteText(target, content)
        target.parentFile?.let { Durable.syncDir(it) }
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
    internal fun putRecognizedPage(id: String, key: String, sourceHash: String, text: String, page: OcrPage?): Boolean = lock.withLock {
        val source = File(docDir(id), key)
        if (!source.isFile || Sha256.of(source) != sourceHash) return@withLock false
        if (key in readTextStrict(id)) return@withLock true
        putPage(id, key, text, page)
        true
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
            File(dir, PENDING_FILE).writeText("")
        }
    }

    fun clearPending(id: String) {
        lock.withLock {
            File(docDir(id), PENDING_FILE).delete()
        }
    }

    fun isPending(id: String): Boolean = File(docDir(id), PENDING_FILE).exists()

    fun hasText(id: String): Boolean = readText(id).isNotEmpty()
}
