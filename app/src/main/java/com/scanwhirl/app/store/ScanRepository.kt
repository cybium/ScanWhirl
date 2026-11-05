package com.scanwhirl.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.ScanFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ScanRepository(private val context: Context) {

    private val docsDir: File
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    fun listDocuments(): List<ScanDocument> {
        return docsDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { dir -> loadDocument(dir) }
            ?: emptyList()
    }

    private fun loadDocument(dir: File): ScanDocument? {
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return null
        val lines = meta.readLines()
        if (lines.isEmpty()) return null
        val name = lines[0].ifBlank { "Untitled scan" }
        val createdAt = lines.getOrNull(1)?.toLongOrNull() ?: dir.lastModified()
        val pdf = File(dir, "scan.pdf")
        val pages = dir.listFiles { f -> f.extension.equals("jpg", true) || f.extension.equals("jpeg", true) }
            ?.filter { it.length() > 0 }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()
        if (!pdf.exists() && pages.isEmpty()) return null
        return ScanDocument(
            id = dir.name,
            name = name,
            createdAt = createdAt,
            pageCount = pages.size,
            pdfFile = pdf,
            pageFiles = pages
        )
    }

    suspend fun saveScan(
        imageUris: List<Uri>,
        pdfUri: Uri?,
        suggestedName: String?,
        filter: ScanFilter = ScanFilter.ORIGINAL,
        pageFilters: Map<Int, ScanFilter>? = null
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
                if (pageFilter == ScanFilter.ORIGINAL) {
                    FileOutputStream(out).use { o -> i.copyTo(o) }
                } else {
                    val bytes = i.readBytes()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException("Page ${index + 1} is not an image")
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

        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\n")

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
        )
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

        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\n")

        val pdfFile = File(dir, "scan.pdf")
        try {
            PdfGenerator.imagesToPdf(pageFilesIn(dir), pdfFile)
        } catch (e: Exception) {
            pdfFile.delete()
        }

        loadDocument(dir)!!
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
            regeneratePdf(dir)

            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after delete")
        }

    suspend fun rotatePage(id: String, pageIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(pageIndex in doc.pageFiles.indices) { "No such page" }

            val file = doc.pageFiles[pageIndex]
            rotateFile90(file)
            // Keep the kept original aligned so a later filter re-apply
            // doesn't undo the rotation.
            origOf(file).takeIf { it.exists() }?.let { rotateFile90(it) }

            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after rotate")
        }

    private fun rotateFile90(file: File) = ImageTools.rotate90(file)

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

    /** Creates a brand-new scan document from raw page JPEG bytes. */
    suspend fun createDocument(
        name: String?,
        pageBytes: List<ByteArray>
    ): ScanDocument = withContext(Dispatchers.IO) {
        require(pageBytes.isNotEmpty()) { "No pages" }

        val id = UUID.randomUUID().toString().take(8)
        val dir = File(docsDir, id).apply { mkdirs() }

        pageBytes.forEachIndexed { index, bytes ->
            require(bytes.isNotEmpty()) { "Page ${index + 1} is empty" }
            File(dir, String.format(Locale.ROOT, "page_%03d.jpg", index)).writeBytes(bytes)
        }

        val finalName = name?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: nextDocumentName()
        File(dir, "meta.txt").writeText("$finalName\n${System.currentTimeMillis()}\n")

        val pdfFile = File(dir, "scan.pdf")
        try {
            PdfGenerator.imagesToPdf(pageFilesIn(dir), pdfFile)
        } catch (e: Exception) {
            pdfFile.delete()
        }

        loadDocument(dir) ?: throw IllegalStateException("Scan could not be saved")
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
     * [dpi]/[maxDim]/[quality] control the rasterization, i.e. the compression level.
     */
    suspend fun importPdfAsScan(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 4096,
        quality: Int = 85
    ): ScanDocument = withContext(Dispatchers.IO) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open PDF")
        val pages = fd.use { PdfPageRenderer.renderJpegs(it, dpi = dpi, maxDim = maxDim, quality = quality) }
        createDocument(name, pages)
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

    private fun regeneratePdf(dir: File) {
        val pdfFile = File(dir, "scan.pdf")
        pdfFile.delete()
        val pages = pageFilesIn(dir)
        if (pages.isNotEmpty()) {
            try {
                PdfGenerator.imagesToPdf(pages, pdfFile)
            } catch (_: Exception) {
                pdfFile.delete()
            }
        }
    }

    /** Next default scan name: "Document 1", "Document 2", … */
    fun nextDocumentName(): String {
        val count = docsDir.listFiles { f -> f.isDirectory }?.size ?: 0
        return "Document ${count + 1}"
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
