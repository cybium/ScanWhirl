package com.scanwhirl.app.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a minimal, valid Word document (`.docx`) from OCR text, using only `java.util.zip`:
 * `[Content_Types].xml`, `_rels/.rels`, `word/document.xml` and its (empty) relationships part.
 *
 * One paragraph per recognised line; a page break separates the pages. This is a text export,
 * so the document has no page images — that keeps it small and opens in Word, Docs and WPS.
 */
object DocxWriter {

    /**
     * Writes [outFile] atomically: the package is built next to it as `.part` and only renamed
     * when complete, so a failure never leaves a half-written `.docx` a viewer would reject.
     */
    fun write(outFile: File, pages: List<List<String>>) {
        require(pages.isNotEmpty()) { "No text to write" }
        outFile.parentFile?.mkdirs()
        val partial = File(outFile.parentFile, outFile.name + ".part")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { zip ->
                entry(zip, "[Content_Types].xml", CONTENT_TYPES)
                entry(zip, "_rels/.rels", ROOT_RELS)
                entry(zip, "word/document.xml", documentXml(pages))
                entry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS)
            }
            if (partial.length() <= 0L) throw IOException("The Word file is empty")
            if (!partial.renameTo(outFile)) throw IOException("Could not save the Word file")
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    private fun entry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun documentXml(pages: List<List<String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        append("<w:body>")
        pages.forEachIndexed { index, lines ->
            if (index > 0) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            if (lines.isEmpty()) {
                append("<w:p/>")
            } else {
                lines.forEach { line -> append(paragraph(line)) }
            }
        }
        append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" ")
        append("w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>")
        append("</w:body></w:document>")
    }

    private fun paragraph(line: String): String =
        "<w:p><w:r><w:t xml:space=\"preserve\">${escape(line)}</w:t></w:r></w:p>"

    internal fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c == '\u0000' -> Unit // XML cannot carry NUL
                c < ' ' && c != '\n' && c != '\t' -> Unit
                else -> append(c)
            }
        }
    }

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" " +
            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
            "Target=\"word/document.xml\"/></Relationships>"

    private const val DOCUMENT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>"
}
