package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.model.ContentKind
import com.scanwhirl.app.model.PageBox
import com.scanwhirl.app.model.PageKind
import com.scanwhirl.app.model.PageRef
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Digital authority is NOT a raster document with extra metadata. No JPEG, OCR,
 * or preview is authoritative. Unknown or damaged members are unavailable.
 * Storage validation proves identity, not PDF syntax; the producer must supply
 * an independent verifier before sealing. No engine dependency in this slice.
 */
internal class DigitalStorage(private val context: Context) {
    companion object {
        const val LIVE = "digital_documents_v1"
        const val TRASH = "digital_trash_v1"
        const val ESCROW = "digital_import_discard_v1"
        const val STAGING = "digital_staging_v1"
        const val DRAFTS = "digital_drafts_v1"
        const val PDF = "scan.pdf"
        const val META = "meta.txt"
        const val PAGES = "pages.v1"
        const val MAX_PDF_BYTES = 64L * 1024 * 1024
        const val MAX_META_BYTES = 64L * 1024
        const val MAX_MANIFEST_BYTES = 1024L * 1024
        const val MAX_PAGES = 500
        private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
        private val shaPattern = Regex("[0-9a-f]{64}")
        private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val fields = setOf("content_kind", "pdf_sha256", "pdf_bytes", "pdf_pages",
            "pdf_generation", "min_reader", "page_manifest_sha256")
        val members = listOf(PDF, META, PAGES)
        fun safeId(id: String) = idPattern.matches(id)
        fun token(value: String) = uuidPattern.matches(value)
        fun isDirectChild(parent: File, child: File): Boolean =
            runCatching { child.canonicalFile == File(parent.canonicalFile, child.name) }.getOrDefault(false)
        fun owned(file: File): Boolean =
            runCatching { file.canonicalFile == file.absoluteFile }.getOrDefault(false)
        fun boundedLines(file: File, max: Long): List<String> {
            if (!owned(file) || !file.isFile || file.length() !in 1..max) throw IOException("Invalid digital member")
            val bytes = file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > max) throw IOException("Digital member too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().lineSequence().toList()
                .dropLastWhile { it.isEmpty() }
        }
        fun validatePages(pages: List<PageRef>) {
            require(pages.size in 1..MAX_PAGES)
            require(pages.map { it.id }.toSet().size == pages.size)
            pages.forEachIndexed { index, page ->
                require(token(page.id) && page.index == index && page.rotation in setOf(0, 90, 180, 270))
                require(page.originIndex == null || page.originIndex in 0 until MAX_PAGES)
                for (box in listOf(page.mediaBox, page.cropBox)) {
                    require(listOf(box.left, box.bottom, box.right, box.top).all { it.isFinite() && kotlin.math.abs(it) <= 1000000.0 })
                    require(box.right > box.left && box.top > box.bottom)
                }
                require(maxOf(page.mediaBox.left, page.cropBox.left) < minOf(page.mediaBox.right, page.cropBox.right) &&
                    maxOf(page.mediaBox.bottom, page.cropBox.bottom) < minOf(page.mediaBox.top, page.cropBox.top))
            }
        }
        private fun boxText(box: PageBox) = listOf(box.left, box.bottom, box.right, box.top).joinToString(",")
        private fun parseBox(s: String): PageBox {
            val a = s.split(",").map { it.toDouble() }
            require(a.size == 4)
            return PageBox(a[0], a[1], a[2], a[3])
        }
        fun encodePages(hash: String, pages: List<PageRef>): String {
            require(shaPattern.matches(hash))
            validatePages(pages)
            return buildString {
                appendLine("scanwhirl-pages-v1")
                appendLine(hash)
                pages.forEach { p ->
                    appendLine(listOf(p.id, p.index, p.kind.name, p.rotation,
                        p.originIndex ?: "-", boxText(p.mediaBox), boxText(p.cropBox)).joinToString("\t"))
                }
            }
        }
        private fun decodePages(file: File, hash: String): List<PageRef> {
            val lines = boundedLines(file, MAX_MANIFEST_BYTES)
            require(lines.size in 3..MAX_PAGES + 2 && lines[0] == "scanwhirl-pages-v1" && lines[1] == hash)
            return lines.drop(2).map { line ->
                val a = line.split('\t')
                require(a.size == 7)
                PageRef(a[0], a[1].toInt(), parseBox(a[5]), parseBox(a[6]),
                    a[3].toInt(), PageKind.valueOf(a[2]), a[4].takeUnless { it == "-" }?.toInt())
            }.also(::validatePages)
        }
        /** Validate even if flags are off. Never repair from preview data. */
        fun read(dir: File): ScanDocument? = try {
            require(owned(dir) && dir.isDirectory)
            val names = dir.listFiles() ?: throw IOException("Cannot list digital document")
            // No locked/Vault or other authoritative members until W7b-7. Markers
            // belong to storage moves, never to a PDF object or legacy raster builder.
            require(names.all { owned(it) && it.isFile && it.name in
                (members + listOf(".ready", ".intent", "meta.txt.tmp", ".ready.tmp", "trashed.txt", "trashed.txt.tmp", ".import-discard", ".import-discard.tmp")) })
            val meta = File(dir, META)
            val lines = boundedLines(meta, MAX_META_BYTES)
            require(lines.size >= 10 && lines[0].isNotBlank())
            val created = lines[1].toLong()
            require(created >= 0 && CategoryRules.isValid(lines[2]))
            val m = mutableMapOf<String, String>()
            for (line in lines.drop(3)) {
                val k = line.substringBefore('=')
                if (k in fields) {
                    require(line.contains('=') && m.put(k, line.substringAfter('=')) == null)
                }
            }
            require(m.keys == fields && m["content_kind"] == "digital-v1" && m["min_reader"] == "1")
            val hash = requireNotNull(m["pdf_sha256"])
            val generation = requireNotNull(m["pdf_generation"])
            val bytes = requireNotNull(m["pdf_bytes"]).toLong()
            require(shaPattern.matches(hash) && token(generation) && bytes in 1..MAX_PDF_BYTES)
            val pdf = File(dir, PDF)
            val manifest = File(dir, PAGES)
            require(owned(pdf) && pdf.isFile && pdf.length() == bytes && Sha256.of(pdf) == hash)
            require(owned(manifest) && manifest.isFile && manifest.length() in 1..MAX_MANIFEST_BYTES)
            require(Sha256.of(manifest) == m["page_manifest_sha256"])
            val pages = decodePages(manifest, hash)
            require(pages.size == m["pdf_pages"]?.toInt())
            val organised = LibraryMeta.read(lines)
            val revision = maxOf(meta.lastModified(), pdf.lastModified(), manifest.lastModified())
            ScanDocument(dir.name, lines[0], created, pages.size, pdf, emptyList(),
                category = lines[2], folder = organised.folder, tags = organised.tags,
                revision = revision, cachedSize = bytes, cachedModified = revision,
                contentKind = ContentKind.DIGITAL, pageRefs = pages, contentGeneration = generation,
                contentSha256 = hash, storageBytes = bytes + meta.length() + manifest.length())
        } catch (_: Exception) { null }
    }

    fun root(name: String): File {
        require(name in setOf(LIVE, TRASH, ESCROW, STAGING, DRAFTS))
        // filesDir may itself be an Android alias; anchor to its canonical directory.
        val dir = File(context.filesDir.canonicalFile, name)
        if (!owned(dir) || (!dir.isDirectory && !dir.mkdirs())) throw IOException("Digital storage unavailable")
        return dir
    }

    fun existingRoot(name: String): File = File(context.filesDir.canonicalFile, name)
    fun child(root: String, id: String): File {
        require(safeId(id))
        val parent = root(root)
        val file = File(parent, id)
        if (!isDirectChild(parent, file)) throw IOException("Invalid digital path")
        return file
    }
    fun hasLive(id: String): Boolean = safeId(id) &&
        (File(existingRoot(LIVE), id).exists() || existingRoot(LIVE).list()?.contains(id) == true)
    fun liveDirs(): List<File> = dirs(LIVE)
    fun dirs(name: String): List<File> {
        val parent = existingRoot(name)
        if (!owned(parent)) return emptyList()
        return parent.listFiles { f -> f.isDirectory && isDirectChild(parent, f) }.orEmpty().toList()
    }
    fun reserves(id: String, ignoreStage: Boolean = false, ignoreTrash: Boolean = false): Boolean {
        if (!safeId(id)) return true
        return listOf(LIVE, TRASH, ESCROW, STAGING, DRAFTS).filterNot { (ignoreStage && it == STAGING) || (ignoreTrash && it == TRASH) }.any { name ->
            val parent = existingRoot(name)
            if (!owned(parent)) return@any true
            parent.listFiles().orEmpty().any { f ->
                f.name == id || f.name.startsWith("$id~") || f.name.startsWith("$id--") ||
                    f.name.startsWith(".purge-$id-")
            }
        }
    }
    fun writesEnabled(): Boolean {
        val flags = context.getSharedPreferences("digital_capabilities", Context.MODE_PRIVATE)
        return flags.getBoolean("digital_store_read", false) && flags.getBoolean("digital_import", false)
    }

    /** Bounded copy of immutable input, verify staged bytes, not the caller's path. */
    fun writeVerified(dir: File, source: File, name: String, generation: String,
                      verify: (File) -> List<PageRef>): ScanDocument {
        require(token(generation) && name.isNotBlank() && name.length <= 512 && '\n' !in name && '\r' !in name)
        require(source.isFile && source.length() in 1..MAX_PDF_BYTES)
        val pdf = File(dir, PDF)
        source.inputStream().use { input ->
            FileOutputStream(pdf).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PDF_BYTES) throw IOException("Digital PDF too large")
                    output.write(buffer, 0, n)
                }
                Durable.syncStream(output, pdf)
            }
        }
        OpFaults.at("digital:pdf_synced")
        val hash = Sha256.of(pdf)
        val pages = verify(pdf)
        validatePages(pages)
        require(Sha256.of(pdf) == hash)
        val manifest = File(dir, PAGES)
        Durable.writeFile(manifest, encodePages(hash, pages).toByteArray(Charsets.UTF_8))
        OpFaults.at("digital:manifest_synced")
        val meta = buildString {
            appendLine(name)
            appendLine(System.currentTimeMillis())
            appendLine(CategoryRules.classify(name))
            appendLine("content_kind=digital-v1")
            appendLine("pdf_sha256=$hash")
            appendLine("pdf_bytes=${pdf.length()}")
            appendLine("pdf_pages=${pages.size}")
            appendLine("pdf_generation=$generation")
            appendLine("min_reader=1")
            appendLine("page_manifest_sha256=${Sha256.of(manifest)}")
        }
        Durable.writeFile(File(dir, META), meta.toByteArray(Charsets.UTF_8))
        OpFaults.at("digital:meta_synced")
        return read(dir) ?: throw IOException("Digital verification failed")
    }
}
