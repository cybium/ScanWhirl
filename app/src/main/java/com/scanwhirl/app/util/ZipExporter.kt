package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs several saved scans into one ZIP (Pro batch export). Each document
 * ships its real PDF under its own name; collisions get " (2)", " (3)".
 */
object ZipExporter {

    fun export(docs: List<ScanDocument>, outFile: File) {
        require(docs.isNotEmpty()) { "No scans to export" }
        exportFiles(ShareUtils.documentItems(docs).filter { it.first.length() > 0L }, outFile)
    }

    /**
     * Writes [entries] (file to display name) into [outFile]. Every entry is stored under its
     * sanitised display name; names that collide (ignoring case) are numbered before the
     * extension. Missing files are skipped. H2-1: the library keeps every PDF as `scan.pdf`,
     * so callers pass the document's own name, never the stored file's.
     */
    fun exportFiles(entries: List<Pair<File, String>>, outFile: File) {
        outFile.parentFile?.mkdirs()
        val names = ExportNames.Allocator()
        var written = 0
        try {
            ZipOutputStream(outFile.outputStream()).use { zip ->
                for ((file, displayName) in entries) {
                    if (!file.isFile) continue
                    val name = names.file(ExportNames.sanitizeFileName(displayName))
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    written++
                }
            }
            // NEW-1: a closed empty ZipOutputStream still writes a 22-byte END record, so a
            // length check can never detect "nothing exported". Count the entries instead.
            require(written > 0) { "ZIP export produced no entries" }
        } catch (e: Exception) {
            // No empty archive is left behind for a caller to report as a success.
            outFile.delete()
            throw e
        }
    }
}
