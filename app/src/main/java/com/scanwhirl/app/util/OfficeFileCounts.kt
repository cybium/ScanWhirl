package com.scanwhirl.app.util

import java.io.File
import java.util.zip.ZipFile

/**
 * v2 UI: 03_tools_hub — what a generated Office file actually contains, so the result screen can
 * show a file-type card with a real count instead of an empty white preview:
 *
 * - `.docx`: pages, from the page breaks the writer puts between scan pages (at least one page);
 * - `.xlsx`: sheets, from the `xl/worksheets/sheetN.xml` entries;
 * - `.pptx`: slides, from the `ppt/slides/slideN.xml` entries;
 * - `.zip`: files, from the non-directory entries.
 *
 * Returns null when the package cannot be read, so the caller simply omits the count.
 */
object OfficeFileCounts {

    fun count(file: File): Int? = try {
        when (file.extension.lowercase()) {
            "docx" -> docxPages(file)
            "xlsx" -> zipEntries(file, SHEET).takeIf { it > 0 }
            "pptx" -> zipEntries(file, SLIDE).takeIf { it > 0 }
            "zip" -> zipFileCount(file).takeIf { it > 0 }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun docxPages(file: File): Int? = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: return@use null
        val xml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        PAGE_BREAK.findAll(xml).count() + 1
    }

    private fun zipEntries(file: File, pattern: Regex): Int = ZipFile(file).use { zip ->
        zip.entries().toList().count { !it.isDirectory && pattern.matches(it.name) }
    }

    private fun zipFileCount(file: File): Int = ZipFile(file).use { zip ->
        zip.entries().toList().count { !it.isDirectory }
    }

    private val PAGE_BREAK = Regex("<w:br[^>]*w:type=\"page\"")
    private val SHEET = Regex("xl/worksheets/sheet[0-9]+\\.xml")
    private val SLIDE = Regex("ppt/slides/slide[0-9]+\\.xml")
}
