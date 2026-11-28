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
import java.io.IOException

/**
 * Digital authority is NOT a raster document with extra metadata. No JPEG, OCR,
 * or preview is authoritative. Unknown or damaged members are unavailable.
 * Storage validation proves identity, not PDF syntax; the producer must supply
 * an independent verifier before sealing. No engine dependency in this slice.
 */
internal class DigitalStorage(private val context: Context) {
    internal data class Stamp(val bytes: Long, val modified: Long, val device: Long,
                              val inode: Long, val changed: Long)
    internal data class Identity(val generation: String, val hash: String, val manifestHash: String,
                                 val pdf: Stamp, val manifest: Stamp)
    private data class Proof(val identity: Identity, val pages: List<PageRef>)

    companion object {
        const val LIVE = "digital_documents_v1"
        const val TRASH = "digital_trash_v1"
        const val ESCROW = "digital_import_discard_v1"
        const val STAGING = "digital_staging_v1"
        const val DRAFTS = "digital_drafts_v1"
        const val PDF = com.scanwhirl.app.util.PdfProtection.PDF_FILE
        const val META = "meta.txt"
        const val PAGES = "pages.v1"
        const val PURGE_INTENT = ".purge-intent"
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
        /** Do not recursively follow any symlink, including one below an owned root. */
        fun deleteOwnedTree(dir: File): Boolean {
            if (!owned(dir)) return false
            if (!dir.exists()) return true
            val paths = mutableListOf<File>()
            fun collect(node: File, depth: Int): Boolean {
                if (depth > 16 || paths.size >= 10000 || !owned(node)) return false
                paths += node
                if (node.isDirectory) {
                    val children = node.listFiles() ?: return false
                    return children.all { collect(it, depth + 1) }
                }
                return node.isFile
            }
            if (!collect(dir, 0)) return false
            return paths.asReversed().all { it.delete() }
        }

        fun boundedHash(file: File): String {
            require(file.isFile && file.length() in 1..MAX_PDF_BYTES)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PDF_BYTES) throw IOException("Digital PDF too large")
                    digest.update(buffer, 0, n)
                }
                if (total == 0L) throw IOException("Digital PDF empty")
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

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
                require(page.originIndex == null || page.originIndex in pages.indices)
                for (box in listOf(page.mediaBox, page.cropBox)) {
                    require(listOf(box.left, box.bottom, box.right, box.top).all { it.isFinite() && kotlin.math.abs(it) <= 1000000.0 })
                    require(box.right > box.left && box.top > box.bottom)
                }
                require(page.cropBox.left >= page.mediaBox.left && page.cropBox.bottom >= page.mediaBox.bottom &&
                    page.cropBox.right <= page.mediaBox.right && page.cropBox.top <= page.mediaBox.top)
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
        // A proof binds both immutable members to generation, declared hashes and
        // filesystem identity. Moving a whole directory does not change its member inodes.
        // Metadata-only edits deliberately do not invalidate it. No PDF IO on recognition.
        private fun stamp(file: File): Stamp {
            val stat = android.system.Os.stat(file.path)
            return Stamp(file.length(), file.lastModified(), stat.st_dev, stat.st_ino, stat.st_ctime)
        }
        private val verified = java.util.concurrent.ConcurrentHashMap<Identity, Proof>()
        @Volatile internal var verificationProbe: ((File) -> Unit)? = null
        internal fun clearVerificationCacheForTests() { verified.clear() }
        fun forget(dir: File) { identity(dir)?.let { verified.remove(it) } }
        /** Cheap name recognition even before startup proof cache is populated. */
        fun recognizedName(dir: File): String? = try {
            if (!owned(dir)) null else boundedLines(File(dir, META), MAX_META_BYTES)
                .takeIf { it.size >= 3 }?.firstOrNull()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        /** Cheap recognition only. Cold/changed authority is unavailable until recovery verifies it. */
        fun read(dir: File): ScanDocument? = readInternal(dir, false)
        /** Full verification belongs to the producer, authenticated restore or startup recovery. */
        fun verify(dir: File): ScanDocument? {
            val key = identity(dir)
            val result = readInternal(dir, true)
            if (result == null && key != null) verified.remove(key)
            return result
        }
        fun identity(dir: File): Identity? = try {
            val meta = boundedLines(File(dir, META), MAX_META_BYTES)
            val fields = meta.drop(3).associate { it.substringBefore('=') to it.substringAfter('=') }
            val pdf = File(dir, PDF)
            val manifest = File(dir, PAGES)
            require(owned(dir) && owned(pdf) && owned(manifest) && pdf.isFile && manifest.isFile)
            Identity(requireNotNull(fields["pdf_generation"]), requireNotNull(fields["pdf_sha256"]),
                requireNotNull(fields["page_manifest_sha256"]), stamp(pdf), stamp(manifest))
        } catch (_: Exception) { null }
        private fun readInternal(dir: File, full: Boolean): ScanDocument? = try {
            require(owned(dir) && dir.isDirectory)
            val names = dir.listFiles() ?: throw IOException("Cannot list digital document")
            // No locked/Vault or other authoritative members until W7b-7. Markers
            // belong to storage moves, never to a PDF object or legacy raster builder.
            require(names.all { owned(it) && it.isFile && it.name in
                (members + listOf(".ready", ".intent", "meta.txt.tmp", ".ready.tmp", "trashed.txt", "trashed.txt.tmp", ".import-discard", ".import-discard.tmp", PURGE_INTENT, "$PURGE_INTENT.tmp")) })
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
            require(owned(pdf) && pdf.isFile && pdf.length() == bytes)
            require(owned(manifest) && manifest.isFile && manifest.length() in 1..MAX_MANIFEST_BYTES)
            val manifestHash = requireNotNull(m["page_manifest_sha256"])
            require(shaPattern.matches(manifestHash))
            val key = Identity(generation, hash, manifestHash, stamp(pdf), stamp(manifest))
            val pages = if (full) {
                verificationProbe?.invoke(pdf)
                require(boundedHash(pdf) == hash && Sha256.of(manifest) == manifestHash)
                val decoded = decodePages(manifest, hash)
                require(identity(dir) == key)
                require(decoded.size == m["pdf_pages"]?.toInt())
                verified[key] = Proof(key, decoded)
                decoded
            } else {
                val proof = verified[key]
                require(proof?.identity == key)
                requireNotNull(proof).pages
            }
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
    fun child(rootName: String, id: String): File {
        require(safeId(id))
        val parent = root(rootName)
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
        if (id.isEmpty() || id.length > 64 || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return true
        return listOf(LIVE, TRASH, ESCROW, STAGING, DRAFTS).filterNot { (ignoreStage && it == STAGING) || (ignoreTrash && it == TRASH) }.any { name ->
            val parent = existingRoot(name)
            if (!owned(parent)) return@any true
            parent.listFiles().orEmpty().any { f ->
                f.name == id || f.name.startsWith("$id~") || f.name.startsWith("$id--") ||
                    f.name.startsWith(".purge-$id-") || f.name.startsWith(".purge-$id~")
            }
        }
    }
    fun writesEnabled(): Boolean {
        val flags = context.getSharedPreferences("digital_capabilities", Context.MODE_PRIVATE)
        return flags.getBoolean("digital_store_read", false) && flags.getBoolean("digital_import", false)
    }

    internal data class PurgeProof(val lines: List<String>, val stamps: Map<String, Stamp>)

    /** Read only bounded marker and filesystem stamps. No PDF content IO. */
    private fun purgeState(hidden: File): PurgeProof? {
        val trash = existingRoot(TRASH)
        if (!owned(trash) || !isDirectChild(trash, hidden) || !owned(hidden) ||
            !hidden.name.startsWith(".purge-") || !hidden.isDirectory) return null
        val files = hidden.listFiles() ?: return null
        if (files.isEmpty()) return PurgeProof(emptyList(), emptyMap())
        val marker = File(hidden, PURGE_INTENT)
        val lines = try { boundedLines(marker, 512) } catch (_: Exception) { return null }
        if (lines.size != 4 || lines[0] != "scanwhirl-digital-purge-v1" ||
            lines[1] != hidden.name || !token(lines[2]) || !shaPattern.matches(lines[3])) return null
        val allowed = members + listOf(".ready", ".intent", "meta.txt.tmp", ".ready.tmp",
            "trashed.txt", "trashed.txt.tmp", ".import-discard", ".import-discard.tmp", PURGE_INTENT, "$PURGE_INTENT.tmp")
        if (!files.all { owned(it) && it.isFile && it.name in allowed }) return null
        return try { PurgeProof(lines, files.associate { it.name to stamp(it) }) } catch (_: Exception) { null }
    }

    /** Called under repository writer locks, after a user-authorized delete/expiry. */
    fun beginPurge(dir: File, hidden: File): PurgeProof {
        val doc = read(dir) ?: throw IOException("Digital document unavailable")
        val authority = identity(dir) ?: throw IOException("Digital document unavailable")
        val trash = root(TRASH)
        if (hidden.parentFile != trash || !isDirectChild(trash, hidden) ||
            !hidden.name.startsWith(".purge-") || trash.list()?.contains(hidden.name) == true)
            throw IOException("Invalid digital purge target")
        val text = "scanwhirl-digital-purge-v1\n${hidden.name}\n${doc.contentGeneration}\n${doc.contentSha256}\n"
        Durable.atomicWriteText(File(dir, PURGE_INTENT), text)
        Durable.syncDir(dir)
        OpFaults.at("digital:purge_marked")
        if (identity(dir) != authority || !dir.renameTo(hidden)) throw IOException("Could not stage digital deletion")
        Durable.syncDir(dir.parentFile!!)
        Durable.syncDir(trash)
        val proof = purgeState(hidden) ?: throw IOException("Digital purge marker unavailable")
        // Whole directory move carries the cached, verified PDF unchanged.
        if (proof.stamps[PDF] != authority.pdf || proof.stamps[PAGES] != authority.manifest)
            throw IOException("Digital purge authority changed")
        verified.remove(authority)
        return proof
    }

    /** On recovery only: prove surviving bytes outside the writer lock. */
    fun verifyPurge(hidden: File): PurgeProof? {
        val proof = purgeState(hidden) ?: return null
        val pdf = File(hidden, PDF)
        if (proof.stamps.containsKey(PDF) &&
            (pdf.length() !in 1..MAX_PDF_BYTES || boundedHash(pdf) != proof.lines[3])) return null
        val meta = File(hidden, META)
        if (proof.stamps.containsKey(META)) {
            val lines = try { boundedLines(meta, MAX_META_BYTES) } catch (_: Exception) { return null }
            if (lines.drop(3).filter { it.startsWith("pdf_generation=") } != listOf("pdf_generation=${proof.lines[2]}") ||
                lines.drop(3).filter { it.startsWith("pdf_sha256=") } != listOf("pdf_sha256=${proof.lines[3]}")) return null
        }
        return proof.takeIf { purgeState(hidden) == proof }
    }

    /** Marker is deleted last. Unknown, linked or inconsistent payload is retained. */
    fun finishPurge(hidden: File, proof: PurgeProof): Boolean {
        if (purgeState(hidden) != proof) return false
        if (proof.stamps.isEmpty()) return hidden.delete()
        val marker = File(hidden, PURGE_INTENT)
        val files = hidden.listFiles() ?: return false
        for (file in files.filter { it.name != PURGE_INTENT }) {
            if (!file.delete()) return false
            OpFaults.at("digital:purge_member:${file.name}")
        }
        Durable.syncDir(hidden)
        if (!marker.delete()) return false
        Durable.syncDir(hidden)
        if (!hidden.delete()) return false
        Durable.syncDir(existingRoot(TRASH))
        return true
    }

    /** Bounded copy of immutable input, verify staged bytes, not the caller's path. */
    fun writeVerified(dir: File, source: File, name: String, generation: String,
                      copyPdf: (File, File) -> Unit, verify: (File) -> List<PageRef>): ScanDocument {
        require(token(generation) && name.isNotBlank() && name.length <= 512 && '\n' !in name && '\r' !in name)
        require(source.isFile && source.length() in 1..MAX_PDF_BYTES)
        val pdf = File(dir, PDF)
        copyPdf(source, dir)
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
        return DigitalStorage.verify(dir) ?: throw IOException("Digital verification failed")
    }
}
