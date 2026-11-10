package com.scanwhirl.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfEncryptor
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ScanRepository(private val context: Context) {

    internal val docsDir: File
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    internal val ocrStore: OcrStore
        get() = OcrStore(docsDir)

    /** Process-wide lock for PDF regeneration — several ScanRepository instances exist at once. */
    companion object {
        val PDF_LOCK = Any()
    }

    fun listDocuments(includeVaulted: Boolean = false): List<ScanDocument> {
        return docsDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { dir -> loadDocument(dir) }
            ?.filter { includeVaulted || !it.isVaulted }
            ?: emptyList()
    }

    /** Documents hidden inside the 4-digit PIN Pro Vault. */
    fun listVaultDocuments(): List<ScanDocument> {
        return docsDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { dir -> loadDocument(dir) }
            ?.filter { it.isVaulted }
            ?: emptyList()
    }

    /** Finds a document by ID regardless of whether it is in the Vault. */
    fun getDocument(id: String): ScanDocument? =
        loadDocument(File(docsDir, id))

    internal fun loadDocument(dir: File): ScanDocument? {
        if (!dir.exists() || !dir.isDirectory) return null
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return null
        val lines = meta.readLines()
        if (lines.isEmpty()) return null
        val name = lines[0].ifBlank { "Untitled scan" }
        val createdAt = lines.getOrNull(1)?.toLongOrNull() ?: dir.lastModified()
        val category = lines.getOrNull(2)?.takeIf { CategoryRules.isValid(it) }
            ?: CategoryRules.OTHER
        val pdf = File(dir, "scan.pdf")
        val pages = dir.listFiles { f -> f.extension.equals("jpg", true) || f.extension.equals("jpeg", true) }
            ?.filter { it.length() > 0 }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()
        if (!pdf.exists() && pages.isEmpty()) return null
        val isLocked = File(dir, LOCK_FILE).exists()
        val isVaulted = File(dir, VAULT_FILE).exists()

        // Revision: max lastModified of meta.txt, scan.pdf, locked.meta, vault.flag, page files.
        val allFileTimes = listOf(meta, pdf, File(dir, LOCK_FILE), File(dir, VAULT_FILE))
            .filter { it.exists() }
            .map { it.lastModified() }
            .plus(pages.map { it.lastModified() })
        val revision = if (allFileTimes.isEmpty()) 0L else allFileTimes.maxOrNull() ?: 0L

        // isSearchable: text_layer.flag exists.
        val isSearchable = File(dir, "text_layer.flag").exists()

        return ScanDocument(
            id = dir.name,
            name = name,
            createdAt = createdAt,
            pageCount = pages.size,
            pdfFile = pdf,
            pageFiles = pages,
            isLocked = isLocked,
            isVaulted = isVaulted,
            category = category,
            revision = revision,
            isSearchable = isSearchable
        )
    }

    /**
     * Writes `name / createdAt / category` (meta.txt). The category is
     * guessed from the name unless one is given, so every document lands on
     * a globe ring without the user having to tag anything.
     */
    private fun writeMeta(dir: File, name: String, category: String? = null) {
        val cat = category?.takeIf { CategoryRules.isValid(it) }
            ?: CategoryRules.classify(name)
        // T4.8: a recorded `exports=` line (copies in Documents/ScanWhirl)
        // survives a rewrite instead of being silently dropped.
        val exports = File(dir, "meta.txt").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith(EXPORTS_KEY) }
        File(dir, "meta.txt").writeText(buildString {
            append(name).append('\n')
            append(System.currentTimeMillis()).append('\n')
            append(cat)
            if (exports != null) append('\n').append(exports)
            append('\n')
        })
    }

    /**
     * T4.8 (complaint #18): records a PDF copy this document saved into
     * Documents/ScanWhirl so deleting the document can remove it too.
     * Stored as an optional 4th meta line `exports=<uri1>|<uri2>` — old
     * builds ignore unknown lines, and setCategory/renameDocument rewrite
     * the whole file line-by-line, so it survives those.
     */
    fun addExport(id: String, uri: Uri) {
        val meta = File(File(docsDir, id), "meta.txt")
        if (!meta.exists()) return
        val lines = meta.readLines().toMutableList()
        while (lines.size < 3) lines.add(CategoryRules.OTHER)
        val entry = lines.indexOfFirst { it.startsWith(EXPORTS_KEY) }
        val recorded = if (entry >= 0) {
            lines[entry].removePrefix(EXPORTS_KEY).split("|").filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val merged = (recorded + uri.toString()).distinct().joinToString("|")
        if (entry >= 0) lines[entry] = EXPORTS_KEY + merged else lines.add(EXPORTS_KEY + merged)
        meta.writeText(lines.joinToString("\n"))
    }

    /** T4.8: PDF copies of this document recorded in Documents/ScanWhirl. */
    fun exportsOf(id: String): List<Uri> {
        val meta = File(File(docsDir, id), "meta.txt")
        if (!meta.exists()) return emptyList()
        return meta.readLines()
            .firstOrNull { it.startsWith(EXPORTS_KEY) }
            ?.removePrefix(EXPORTS_KEY)
            ?.split("|")
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
            ?: emptyList()
    }

    /** User-chosen category override (editor → "Categorize"). */
    fun setCategory(id: String, category: String): ScanDocument? {
        require(CategoryRules.isValid(category)) { "Unknown category: $category" }
        val dir = File(docsDir, id)
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return null
        val lines = meta.readLines().toMutableList()
        while (lines.size < 3) lines.add(CategoryRules.OTHER)
        lines[2] = category
        meta.writeText(lines.joinToString("\n"))
        return loadDocument(dir)
    }

    /**
     * Bulk "Move to category" (Home selection mode). One pass per document;
     * missing/locked-away ids are skipped rather than failing the batch.
     */
    fun setCategory(ids: List<String>, category: String) {
        ids.forEach { id -> setCategory(id, category) }
    }

    suspend fun saveScan(
        imageUris: List<Uri>,
        pdfUri: Uri?,
        suggestedName: String?,
        filter: ScanFilter = ScanFilter.ORIGINAL,
        pageFilters: Map<Int, ScanFilter>? = null,
        category: String? = null
    ): ScanDocument = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        val dir = File(docsDir, id).apply { mkdirs() }

        // Per-page filters win over the document-wide one. The scanner's PDF
        // only still matches the pages when every page ends up untouched.
        val effective: (Int) -> ScanFilter = { i -> pageFilters?.get(i) ?: filter }
        val allOriginal = imageUris.indices.all { effective(it) == ScanFilter.ORIGINAL }

        val pageFiles = imageUris.mapIndexed { index, uri ->
            val pageFilter = effective(index)
            val out = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", index))
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not read scanned page ${index + 1}")
            input.use { i ->
                val bytes = i.readBytes()
                if (pageFilter == ScanFilter.ORIGINAL) {
                    out.writeBytes(ImageTools.normalizePage(bytes))
                } else {
                    val oriented = ImageTools.orientJpeg(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(
                        oriented, 0, oriented.size
                    ) ?: throw IllegalStateException("Page ${index + 1} is not an image")
                    val processed = pageFilter.applyTo(bitmap)
                    FileOutputStream(out).use { o ->
                        processed.compress(Bitmap.CompressFormat.JPEG, 92, o)
                    }
                    if (processed !== bitmap) processed.recycle()
                    bitmap.recycle()
                }
            }
            if (!out.exists() || out.length() == 0L) {
                throw IllegalStateException("Scanned page ${index + 1} is empty")
            }
            out
        }

        val pdfFile = File(dir, "scan.pdf")
        if (pdfUri != null && allOriginal) {
            try {
                context.contentResolver.openInputStream(pdfUri)?.use { input ->
                    FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
                }
                if (pdfFile.length() == 0L) pdfFile.delete()
            } catch (_: Exception) {
                pdfFile.delete()
            }
        }

        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            try {
                regeneratePdf(dir)
            } catch (_: Exception) {
                pdfFile.delete()
            }
        }

        val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: nextDocumentName()

        writeMeta(dir, name, category)

        val pages = dir.listFiles { f -> f.extension.equals("jpg", true) && f.length() > 0 }
            ?.sortedBy { it.nameWithoutExtension }
            ?.toList()
            ?: pageFiles

        ScanDocument(
            id = id,
            name = name,
            createdAt = System.currentTimeMillis(),
            pageCount = pages.size,
            pdfFile = pdfFile,
            pageFiles = pages
        ).also { ocrStore.markPending(id) }
    }

    fun deleteDocument(id: String) {
        val dir = File(docsDir, id)
        dir.deleteRecursively()
    }

    fun renameDocument(id: String, newName: String) {
        val dir = File(docsDir, id)
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return
        val lines = meta.readLines().toMutableList()
        while (lines.size < 2) lines.add("")
        lines[0] = newName.trim().ifBlank { lines[0] }
        meta.writeText(lines.joinToString("\n"))
    }

    /** Copies a document (pages + PDF) into a brand-new one. Used by "Save as new". */
    suspend fun duplicateDocument(id: String, suggestedName: String?): ScanDocument =
        withContext(Dispatchers.IO) {
            val src = File(docsDir, id)
            if (!src.exists()) throw IllegalStateException("Scan not found")
            val newId = UUID.randomUUID().toString().take(8)
            val dest = File(docsDir, newId).apply { mkdirs() }
            src.listFiles()?.forEach { f ->
                if (f.isFile) f.copyTo(File(dest, f.name), overwrite = true)
            }
            val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
                ?: nextDocumentName()
            // A copy keeps its original category when it has one.
            val inherited = src.listFiles { f -> f.name == "meta.txt" }
                ?.firstOrNull()?.readLines()?.getOrNull(2)
                ?.takeIf { CategoryRules.isValid(it) }
            // T4.8: exported copies belong to the original — a duplicate
            // starts with none of its own.
            val destMeta = File(dest, "meta.txt")
            if (destMeta.exists()) {
                destMeta.writeText(
                    destMeta.readLines()
                        .filterNot { it.startsWith(EXPORTS_KEY) }
                        .joinToString("\n")
                )
            }
            writeMeta(dest, name, inherited)
            loadDocument(dest) ?: throw IllegalStateException("Copy could not be opened")
        }

    /**
     * test26 T5.2 (§4.5): Extract — saves the given pages as a brand-new
     * document (new dir, pages renumbered, PDF regenerated from the subset).
     * The original is untouched and keeps its exported copies; the category is
     * inherited, like [duplicateDocument].
     */
    suspend fun extractPages(id: String, pageIndices: List<Int>): ScanDocument =
        withContext(Dispatchers.IO) {
            val doc = loadDocument(File(docsDir, id))
                ?: throw IllegalStateException("Scan not found")
            val indices = pageIndices.distinct().sorted()
            require(indices.isNotEmpty()) { "No pages selected" }
            require(indices.all { it in doc.pageFiles.indices }) { "No such page" }

            val (newId, dir) = newDocDir()
            indices.forEachIndexed { i, srcIdx ->
                doc.pageFiles[srcIdx].copyTo(
                    File(dir, String.format(Locale.ROOT, PAGE_FORMAT, i)),
                    overwrite = true
                )
            }
            val first = indices.first() + 1
            val last = indices.last() + 1
            val name = if (first == last) "${doc.name} (page $first)"
            else "${doc.name} (pages $first–$last)"
            finalizeDoc(newId, dir, name, doc.category)
        }

    suspend fun mergeDocuments(
        sourceIds: List<String>,
        suggestedName: String?
    ): ScanDocument = withContext(Dispatchers.IO) {
        require(sourceIds.size >= 2) { "Need at least 2 scans to merge" }

        val sources = sourceIds.map { id ->
            val dir = File(docsDir, id)
            loadDocument(dir) ?: throw IllegalStateException("Scan not found: $id")
        }

        val id = UUID.randomUUID().toString().take(8)
        val dir = File(docsDir, id).apply { mkdirs() }

        var pageNum = 0
        for (doc in sources) {
            for (page in doc.pageFiles) {
                if (!page.exists() || page.length() == 0L) continue
                val out = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", pageNum))
                page.copyTo(out, overwrite = true)
                pageNum++
            }
        }
        if (pageNum == 0) {
            dir.deleteRecursively()
            throw IllegalStateException("No pages to merge")
        }

        val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: "Merged scan ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"

        writeMeta(dir, name)

        val pdfFile = File(dir, "scan.pdf")
        try {
            PdfGenerator.imagesToPdf(pageFilesIn(dir), pdfFile)
        } catch (e: Exception) {
            pdfFile.delete()
        }

        loadDocument(dir)!!
    }

    /**
     * Merges device PDFs (content Uris) in the given order. Pages are rendered
     * and written to disk one at a time — no page-byte list is ever held in
     * memory, so merging many or large PDFs cannot OOM.
     */
    suspend fun mergePdfUris(uris: List<Uri>, suggestedName: String?): ScanDocument =
        withContext(Dispatchers.IO) {
            require(uris.size >= 2) { "Pick at least 2 PDFs" }
            val (id, dir) = newDocDir()
            try {
                var pageNum = 0
                for (uri in uris) {
                    // SAF PDFs are often pipes; the renderer needs a seekable fd.
                    pageNum += PdfPageRenderer.withSeekablePdf(context, uri) { fd ->
                        appendPdfPages(fd, dir, pageNum, dpi = 150f, maxDim = 2048, quality = 85)
                    }
                }
                if (pageNum == 0) throw IllegalStateException("No pages to merge")
                finalizeDoc(id, dir, suggestedName ?: defaultMergedName("Merged PDF"))
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }

    /**
     * Renders every page of the open PDF into [dir], continuing at [start].
     * Returns how many pages were appended.
     */
    private fun appendPdfPages(
        fd: ParcelFileDescriptor,
        dir: File,
        start: Int,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): Int {
        val count = PdfPageRenderer.pageCount(fd)
        require(count > 0) { "PDF has no pages" }
        for (i in 0 until count) {
            val jpeg = PdfPageRenderer.renderPageJpeg(fd, i, dpi, maxDim, quality)
            File(dir, String.format(Locale.ROOT, PAGE_FORMAT, start + i)).writeBytes(jpeg)
        }
        return count
    }

    suspend fun deletePage(id: String, pageIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(pageIndex in doc.pageFiles.indices) { "No such page" }
            require(doc.pageFiles.size > 1) { "Can't delete the last page" }

            val victim = doc.pageFiles[pageIndex]
            origOf(victim).delete()
            victim.delete()
            renumberPages(dir)
            ocrStore.afterDelete(id, pageIndex)
            regeneratePdf(dir)

            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after delete")
        }

    suspend fun rotatePage(id: String, pageIndex: Int, clockwise: Boolean = true): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(pageIndex in doc.pageFiles.indices) { "No such page" }

            val file = doc.pageFiles[pageIndex]
            rotateFile90(file, clockwise)
            // Keep the kept original aligned so a later filter re-apply
            // doesn't undo the rotation.
            origOf(file).takeIf { it.exists() }?.let { rotateFile90(it, clockwise) }
            ocrStore.invalidate(id, file.name)

            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after rotate")
        }

    /**
     * Crops one page to [rect] (full-file pixel coordinates), keeps the kept
     * original in sync so a later filter re-apply stays cropped, and
     * regenerates the PDF.
     */
    suspend fun cropPage(id: String, pageIndex: Int, rect: android.graphics.RectF): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(pageIndex in doc.pageFiles.indices) { "No such page" }
            val file = doc.pageFiles[pageIndex]
            ImageTools.cropTo(file, rect)
            origOf(file).takeIf { it.exists() }?.let { ImageTools.cropTo(it, rect) }
            ocrStore.invalidate(id, file.name)
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after crop")
        }

    private fun rotateFile90(file: File, clockwise: Boolean = true) = ImageTools.rotate90(file, clockwise)

    suspend fun movePage(id: String, fromIndex: Int, toIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(fromIndex in doc.pageFiles.indices) { "No such page" }
            require(toIndex in doc.pageFiles.indices) { "No such position" }

            if (fromIndex != toIndex) {
                val reordered = doc.pageFiles.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                val tmpNames = mutableListOf<File>()
                reordered.forEachIndexed { i, f ->
                    val tmp = File(dir, String.format(Locale.ROOT, "tmp_%03d.jpg", i))
                    f.renameTo(tmp)
                    origOf(f).takeIf { it.exists() }?.renameTo(origOf(tmp))
                    tmpNames.add(tmp)
                }
                tmpNames.forEachIndexed { i, tmp ->
                    val target = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", i))
                    tmp.renameTo(target)
                    origOf(tmp).takeIf { it.exists() }?.renameTo(origOf(target))
                }
                ocrStore.afterMove(id, fromIndex, toIndex)
                regeneratePdf(dir)
            }
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after move")
        }

    private fun pageFilesIn(dir: File): List<File> =
        dir.listFiles { f -> f.extension.equals("jpg", true) && f.length() > 0 }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()

    /** Kept original of a filtered page: `page_001.jpg` -> `page_001.orig` (not a .jpg, never listed). */
    private fun origOf(page: File): File =
        File(page.parentFile, page.nameWithoutExtension + ".orig")

    /**
     * Applies [filter] to one saved page of a document — always re-processing
     * from the kept original so filters never stack, and so "Original"
     * restores the untouched scan. Creates the `.orig` sibling on first use.
     */
    suspend fun setPageFilter(id: String, pageIndex: Int, filter: ScanFilter): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(pageIndex in doc.pageFiles.indices) { "No such page" }
            val file = doc.pageFiles[pageIndex]
            val orig = origOf(file)
            if (filter == ScanFilter.ORIGINAL) {
                if (orig.exists()) {
                    orig.copyTo(file, overwrite = true)
                    orig.delete()
                }
            } else {
                if (!orig.exists()) file.copyTo(orig, overwrite = true)
                val src = BitmapFactory.decodeFile(orig.path)
                    ?: throw IllegalStateException("Unreadable page")
                val out = filter.applyTo(src)
                FileOutputStream(file).use { o -> out.compress(Bitmap.CompressFormat.JPEG, 92, o) }
                if (out !== src) out.recycle()
                src.recycle()
            }
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after filter")
        }

    /** Fresh document directory (never finalised: no meta yet). */
    private fun newDocDir(): Pair<String, File> {
        val id = UUID.randomUUID().toString().take(8)
        return id to File(docsDir, id).apply { mkdirs() }
    }

    /** Writes the name/meta, generates the PDF, and returns the listed document. */
    private fun finalizeDoc(
        id: String,
        dir: File,
        suggestedName: String?,
        category: String? = null
    ): ScanDocument {
        val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: nextDocumentName()
        writeMeta(dir, name, category)
        val pdfFile = File(dir, "scan.pdf")
        try {
            writePdf(dir, pdfFile) // PDF is always written; layers may be empty
        } catch (_: Exception) {
            pdfFile.delete()
        }
        return loadDocument(dir) ?: throw IllegalStateException("Scan could not be saved").also { ocrStore.markPending(id) }
    }

    /** "Merged PDF <timestamp>" when no name is given. */
    private fun defaultMergedName(prefix: String): String =
        "$prefix ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"

    // Shared page-file naming (page_000.jpg, page_001.jpg, …).
    private val PAGE_FORMAT = "page_%03d.jpg"

    /** Creates a brand-new scan document from raw page JPEG bytes. */
    suspend fun createDocument(
        name: String?,
        pageBytes: List<ByteArray>
    ): ScanDocument = withContext(Dispatchers.IO) {
        require(pageBytes.isNotEmpty()) { "No pages" }

        val (id, dir) = newDocDir()

        pageBytes.forEachIndexed { index, bytes ->
            require(bytes.isNotEmpty()) { "Page ${index + 1} is empty" }
            File(dir, String.format(Locale.ROOT, PAGE_FORMAT, index)).writeBytes(bytes)
        }

        finalizeDoc(id, dir, name)
    }

    /** Copies a scan with pages re-encoded at [quality] (optionally downscaled to [maxDim]). */
    suspend fun compressScan(
        id: String,
        quality: Int,
        maxDim: Int?
    ): ScanDocument = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        val compressed = doc.pageFiles.map { file ->
            ImageTools.recompressJpeg(file.readBytes(), quality, maxDim)
        }
        createDocument("${doc.name} (compressed)", compressed)
    }

    /**
     * Imports an external PDF file as a new scan (rendered on-device).
     * [dpi]/[maxDim]/[quality] control the rasterization, i.e. the compression
     * level. Pages are rendered and written one at a time so large PDFs
     * cannot OOM.
     */
    suspend fun importPdfAsScan(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85
    ): ScanDocument = withContext(Dispatchers.IO) {
        val (id, dir) = newDocDir()
        try {
            // Copying once makes every provider (Downloads, Drive, chat apps)
            // readable by PdfRenderer instead of failing "not seekable".
            PdfPageRenderer.withSeekablePdf(context, uri) { fd ->
                appendPdfPages(fd, dir, 0, dpi = dpi, maxDim = maxDim, quality = quality)
            }
            finalizeDoc(id, dir, name)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Imports a device PDF straight into the Hidden Vault (Pro). */
    suspend fun importPdfIntoVault(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85
    ): ScanDocument {
        val doc = importPdfAsScan(uri, name, dpi, maxDim, quality)
        return setVaulted(doc.id, true) ?: doc
    }

    private val LOCK_FILE = "locked.meta"
    private val VAULT_FILE = "vault.flag"

    /** T4.8: prefix of the optional meta.txt line listing exported copies. */
    private val EXPORTS_KEY = "exports="
    private val LOCK_INDEX_FILE = "locked_pdfs.txt"

    /**
     * Rebuilds the document's PDF with the password-protection extra applied
     * (Pro). The page images are untouched; only the PDF wrapper changes.
     *
     * The finished encrypted PDF's SHA-256 is recorded in an app-private
     * index, so if the user later picks that saved/shared copy from storage
     * ScanWhirl can recognise its own lock and offer an honest "Unlock PDF".
     */
    suspend fun protectPdf(id: String, password: String): ScanDocument =
        withContext(Dispatchers.IO) {
            require(password.isNotBlank()) { "Password must not be empty" }
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            val src = File(dir, "plain.pdf")
            if (doc.pageFiles.isNotEmpty()) {
                writePdf(dir, src) // plain PDF is always written for encryption
            } else {
                require(doc.pdfFile.exists() && doc.pdfFile.length() > 0L) { "PDF missing" }
                doc.pdfFile.copyTo(src, overwrite = true)
            }
            val protected = File(dir, "protected.pdf")
            try {
                PdfEncryptor.encrypt(src, protected, password)
                protected.copyTo(doc.pdfFile, overwrite = true)
                File(dir, LOCK_FILE).writeText(Prefs.sha256("pdf_lock:$password"))
                recordLockIndex(doc.pdfFile, id)
            } finally {
                src.delete()
                protected.delete()
            }
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after protection")
        }

    /**
     * Removes password protection from a document by regenerating its clean
     * PDF from the stored page JPEGs and clearing the lock marker.
     */
    suspend fun unlockPdf(id: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            File(dir, LOCK_FILE).delete()
            regeneratePdf(dir)
            pruneLockIndex()
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after unlock")
        }

    /** Checks a user-entered password against the stored lock hash. */
    fun verifyPdfPassword(id: String, password: String): Boolean {
        val lockFile = File(File(docsDir, id), LOCK_FILE)
        if (!lockFile.exists()) return true
        val stored = lockFile.readText().trim()
        return stored == Prefs.sha256("pdf_lock:$password")
    }

    // ------------------------------------------------------------------
    // Password-lock index (recognises ScanWhirl-locked PDFs later)
    // ------------------------------------------------------------------

    private fun recordLockIndex(pdf: File, docId: String) {
        try {
            val hash = Sha256.of(pdf)
            File(context.filesDir, LOCK_INDEX_FILE)
                .appendText("$hash\t$docId\n")
        } catch (_: Exception) {
            // Indexing is a convenience; a failed write must never break locking.
        }
    }

    /** Finds the still-locked ScanWhirl document that produced [hash]. */
    fun findLockedDocumentByHash(hash: String): ScanDocument? {
        val index = File(context.filesDir, LOCK_INDEX_FILE)
        if (!index.exists()) return null
        return index.readLines().asReversed().firstNotNullOfOrNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2 || parts[0] != hash) return@firstNotNullOfOrNull null
            val doc = getDocument(parts[1]) ?: return@firstNotNullOfOrNull null
            if (doc.isLocked) doc else null
        }
    }

    /** Drops index entries whose document is gone or no longer locked. */
    private fun pruneLockIndex() {
        try {
            val index = File(context.filesDir, LOCK_INDEX_FILE)
            if (!index.exists()) return
            val kept = index.readLines().filter { line ->
                val parts = line.split('\t')
                parts.size == 2 && getDocument(parts[1])?.isLocked == true
            }
            index.writeText(kept.joinToString("\n").let { if (it.isBlank()) "" else "$it\n" })
        } catch (_: Exception) {
        }
    }

    /**
     * Builds a temporary unencrypted PDF in cacheDir so the in-app PdfRenderer
     * can display a locked scan after the user enters their password.
     */
    suspend fun unlockedPreviewPdf(id: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            val outDir = File(context.cacheDir, "documents").apply { mkdirs() }
            val preview = File(outDir, "preview_$id.pdf")
            writePdf(dir, preview)
            preview
        }

    /** Moves a scan into or out of the 4-digit PIN Hidden Vault (Pro). */
    fun setVaulted(id: String, vaulted: Boolean): ScanDocument? {
        val dir = File(docsDir, id)
        if (!dir.exists()) return null
        val flag = File(dir, VAULT_FILE)
        if (vaulted) {
            flag.writeText("1")
        } else {
            flag.delete()
        }
        return loadDocument(dir)
    }

    /** OCR text of a document, when it exists: lines keyed by the page file name. */
    fun ocrDoc(id: String): Map<String, String> = ocrStore.readText(id)

    /** True when this document has OCR text saved already. */
    fun hasOcr(id: String): Boolean = File(File(docsDir, id), "ocr.txt").exists()

    /**
     * Replaces one page's OCR text (the OCR Editor's Save, P6) and rewrites
     * `ocr.txt` through [OcrStore.putTexts]. Returns
     * the full updated page map, or null when the document does not exist.
     */
    fun updateOcrPage(id: String, pageKey: String, text: String): Map<String, String>? {
        val dir = File(docsDir, id)
        if (!dir.isDirectory) return null
        ocrStore.putTexts(id, mapOf(pageKey to text))
        return ocrStore.readText(id)
    }

    /**
     * Replaces every given page's OCR text in one rewrite (the OCR Editor's
     * Save-all, P6). Returns the full updated page map, or null when the
     * document does not exist.
     */
    fun updateOcrPages(id: String, pages: Map<String, String>): Map<String, String>? {
        val dir = File(docsDir, id)
        if (!dir.isDirectory) return null
        ocrStore.putTexts(id, pages)
        return ocrStore.readText(id)
    }

    /**
     * Runs OCR over every page (background) and stores text + geometry through
     * [OcrStore]. Returns the number of pages processed. A page the recognizer
     * rejects is stored with empty text and no geometry.
     */
    suspend fun buildOcr(id: String): Int = withContext(Dispatchers.IO) {
        val dir = File(docsDir, id)
        val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
        var count = 0
        for (page in doc.pageFiles) {
            try {
                val result = OcrEngine.recognizeFile(page)
                ocrStore.putPage(id, page.name, result.text, result.page)
                count++
            } catch (_: Exception) {
                // Page unreadable: store empty text, no geometry, honest.
                ocrStore.putPage(id, page.name, "", null)
            }
        }
        count
    }

    private fun renumberPages(dir: File) {
        pageFilesIn(dir).forEachIndexed { i, f ->
            val target = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", i))
            if (f != target) {
                val tmp = File(dir, String.format(Locale.ROOT, "renum_%03d.jpg", i))
                f.renameTo(tmp)
                origOf(f).takeIf { it.exists() }?.renameTo(origOf(tmp))
                tmp.renameTo(target)
                origOf(tmp).takeIf { it.exists() }?.renameTo(origOf(target))
            }
        }
    }

    /** Writes the document's PDF through [writePdf], then maintains `text_layer.flag`. */
    private fun regeneratePdf(dir: File) = synchronized(PDF_LOCK) {
        val pdfFile = File(dir, "scan.pdf")
        val id = dir.name
        val pages = pageFilesIn(dir)
        val layered = if (pages.isNotEmpty()) writePdf(dir, pdfFile) else false
        val searchable = layered && pages.all {
            ocrStore.readGeo(id).containsKey("page_%03d.jpg".format(pages.indexOf(it)))
        }
        val flag = File(dir, "text_layer.flag")
        if (searchable) {
            flag.writeText("")
        } else {
            flag.delete()
        }
    }

    /**
     * Writes [out] from [dir]'s page files, with an invisible text layer built from
     * `OcrStore.readGeo(id)` when `Prefs.makeSearchable` is on. Returns true when at
     * least one page received a layer. Atomic via `.tmp` + rename inside the generator.
     */
    private fun writePdf(dir: File, out: File): Boolean = synchronized(PDF_LOCK) {
        val pages = pageFilesIn(dir)
        if (pages.isEmpty()) {
            false
        } else {
            val id = dir.name
            val textLayers = if (Prefs.makeSearchable(context)) {
                val geo = ocrStore.readGeo(id)
                pages.indices.filter { geo.containsKey("page_%03d.jpg".format(it)) }
                    .associate { i -> i to geo["page_%03d.jpg".format(i)]!! }
            } else {
                emptyMap()
            }
            PdfGenerator.imagesToPdf(pages, out, textLayers).isNotEmpty()
        }
    }

    /**
     * Regenerates the document's PDF with a text layer after indexing completes.
     * No-op when searchable PDFs are off, when the document is locked, or when the
     * directory is gone — the lock must never be dropped.
     */
    fun refreshPdfAfterIndex(id: String) {
        if (!Prefs.makeSearchable(context)) return
        val dir = File(docsDir, id)
        if (!dir.exists() || !dir.isDirectory) return
        if (File(dir, LOCK_FILE).exists()) return
        regeneratePdf(dir)
    }

    /** Next default scan name: "Document 1", "Document 2", …
     *  Starts at max(existing Document-N)+1 so the sequence is predictable
     *  and already-deleted numbers are not reused.  Then advances further
     *  if that name is already taken by a document that was explicitly
     *  renamed to "Document N", making it collision-proof in all cases. */
    fun nextDocumentName(): String {
        val docDirs = docsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        val existingNames = docDirs.mapNotNull { dir ->
            val meta = File(dir, "meta.txt")
            if (meta.exists()) meta.readLines().firstOrNull() else null
        }.toHashSet()
        val pattern = Regex("^Document (\\d+)$")
        val maxN = existingNames.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        var n = maxN + 1
        while (existingNames.contains("Document $n")) n++
        return "Document $n"
    }

    fun displayNameOf(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return null
    }
}
