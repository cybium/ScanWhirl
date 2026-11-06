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
        outFile.parentFile?.mkdirs()
        val taken = mutableSetOf<String>()
        ZipOutputStream(outFile.outputStream()).use { zip ->
            for (doc in docs) {
                if (!doc.pdfFile.exists() || doc.pdfFile.length() == 0L) continue
                val base = ShareUtils.sanitizeName(doc.name).substringBeforeLast('.')
                val name = DownloadSaver.uniqueName("$base.pdf") { it in taken }
                taken.add(name)
                zip.putNextEntry(ZipEntry(name))
                doc.pdfFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(outFile.length() > 0) { "ZIP export produced an empty file" }
    }
}
