package com.scanwhirl.app.store

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import kotlin.concurrent.withLock
import java.io.File
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
 * and is renamed over the target (fall back to copy-then-delete when
 * `renameTo` returns false).
 */
class OcrStore(private val docsDir: File) {

    private val OCR_HEADER = "#scanwhirl-ocr-v2"
    private val OCR_GEO_HEADER = "#scanwhirl-ocrgeo-v1"
    private val PENDING_FILE = "ocr_pending"
    private val TEXT_FILE = "ocr.txt"
    private val GEO_FILE = "ocr_geo.txt"

    private val lock = ReentrantLock()

    private fun docDir(id: String): File = File(docsDir, id)

    private fun pageKeyFor(index: Int): String = "page_%03d.jpg".format(index)

    // ------------------------------------------------------------------
    // Internal write helpers
    // ------------------------------------------------------------------

    /** Writes [content] to [target] atomically via `.tmp` + rename. */
    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile ?: target, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Fall back to copy-then-delete when renameTo returns false.
            target.writeText(content)
            tmp.delete()
        }
    }

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
    fun readText(id: String): Map<String, String> {
        val file = File(docDir(id), TEXT_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val lines = file.readLines()
            if (lines.firstOrNull() == OCR_HEADER) {
                lines.drop(1).mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) null else parts[0] to decodeText(parts[1])
                }.toMap()
            } else {
                // Legacy file (pre-test21): only the first line of each page survived.
                lines.mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Replaces every given page's text in one rewrite through [atomicWrite]. */
    fun putTexts(id: String, pages: Map<String, String>) {
        lock.withLock {
            val dir = docDir(id)
            dir.mkdirs()
            atomicWrite(
                File(dir, TEXT_FILE),
                (listOf(OCR_HEADER) + pages.map { (name, text) ->
                    "$name\t${encodeText(text)}"
                }).joinToString("\n")
            )
        }
    }

    // ------------------------------------------------------------------
    // Geometry (ocr_geo.txt)
    // ------------------------------------------------------------------

    fun readGeo(id: String): Map<String, OcrPage> {
        val file = File(docDir(id), GEO_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            val lines = file.readLines()
            if (lines.firstOrNull() != OCR_GEO_HEADER) emptyMap()
            else lines.drop(1).mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2) null else decodeGeo(decodeText(parts[1]))?.let { parts[0] to it }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Writes one page's text + geometry in one synchronized rewrite. */
    fun putPage(id: String, key: String, text: String, page: OcrPage?) {
        lock.withLock {
            val dir = docDir(id)
            dir.mkdirs()

            // Text
            val currentText = readText(id).toMutableMap()
            currentText[key] = text
            atomicWrite(
                File(dir, TEXT_FILE),
                (listOf(OCR_HEADER) + currentText.map { (name, t) ->
                    "$name\t${encodeText(t)}"
                }).joinToString("\n")
            )

            // Geometry
            val currentGeo = readGeo(id).toMutableMap()
            if (page == null) {
                currentGeo.remove(key)
            } else {
                currentGeo[key] = page
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
            var newIndex = 0
            var i = 0
            while (true) {
                if (i == index) {
                    i++
                    continue
                }
                val oldKey = pageKeyFor(i)
                val hasText = currentText.containsKey(oldKey)
                val hasGeo = currentGeo.containsKey(oldKey)
                if (!hasText && !hasGeo) break
                val newKey = pageKeyFor(newIndex)
                if (hasText) newText[newKey] = currentText[oldKey]!!
                if (hasGeo) newGeo[newKey] = currentGeo[oldKey]!!
                newIndex++
                i++
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
            val fromKey = pageKeyFor(from)
            val toKey = pageKeyFor(to)
            val currentText = readText(id).toMutableMap()
            val currentGeo = readGeo(id).toMutableMap()

            val textValue = currentText.remove(fromKey)
            val geoValue = currentGeo.remove(fromKey)

            if (from < to) {
                // Moving down: shift keys from+1..to down by one.
                for (i in (from + 1)..to) {
                    val oldKey = pageKeyFor(i)
                    val newKey = pageKeyFor(i - 1)
                    currentText[oldKey]?.let { currentText[newKey] = it; currentText.remove(oldKey) }
                    currentGeo[oldKey]?.let { currentGeo[newKey] = it; currentGeo.remove(oldKey) }
                }
            } else {
                // Moving up: shift keys to..from-1 up by one.
                for (i in (to until from).reversed()) {
                    val oldKey = pageKeyFor(i)
                    val newKey = pageKeyFor(i + 1)
                    currentText[oldKey]?.let { currentText[newKey] = it; currentText.remove(oldKey) }
                    currentGeo[oldKey]?.let { currentGeo[newKey] = it; currentGeo.remove(oldKey) }
                }
            }

            textValue?.let { currentText[toKey] = it }
            geoValue?.let { currentGeo[toKey] = it }

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
