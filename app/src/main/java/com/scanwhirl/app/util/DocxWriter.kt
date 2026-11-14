package com.scanwhirl.app.util

import android.graphics.BitmapFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Writes a minimal, valid Word document (`.docx`) from OCR text, using only `java.util.zip`:
 * `[Content_Types].xml`, `_rels/.rels`, `word/document.xml` and its relationships part.
 *
 * One paragraph per recognised line; a page break separates the pages. With pictures, each page
 * starts with its page image (inline drawing, fitted to the text width) followed by that page's
 * text. Pictures are supplied by a callback, so only one page's JPEG is in memory at a time.
 * The package is built as `.part` and renamed when complete: a failure leaves nothing visible.
 */
object DocxWriter {

    /** Text-only export: the historical API. */
    fun write(outFile: File, pages: List<List<String>>) {
        require(pages.isNotEmpty()) { "No text to write" }
        writePackage(outFile, pages.size, { pages[it] }, { null })
    }

    /**
     * Text + page pictures. [pagePicture] returns the page's JPEG (or null for a text-only page);
     * it is called once per page, in order, while the package is written.
     */
    fun writeWithPictures(
        outFile: File,
        pageCount: Int,
        pageLines: (Int) -> List<String>,
        pagePicture: (Int) -> ByteArray?
    ) {
        require(pageCount >= 1) { "No text to write" }
        writePackage(outFile, pageCount, pageLines, pagePicture)
    }

    private fun writePackage(
        outFile: File,
        pageCount: Int,
        pageLines: (Int) -> List<String>,
        pagePicture: (Int) -> ByteArray?
    ) {
        outFile.parentFile?.mkdirs()
        val partial = File(outFile.parentFile, outFile.name + ".part")
        val media = mutableListOf<Triple<String, String, String>>() // id, type, target
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { zip ->
                entry(zip, "[Content_Types].xml", CONTENT_TYPES)
                entry(zip, "_rels/.rels", ROOT_RELS)
                val document = buildString {
                    append(XML_DECL)
                    append("<w:document xmlns:w=\"$W_NS\" xmlns:r=\"$R_NS\" xmlns:wp=\"$WP_NS\" ")
                    append("xmlns:a=\"$A_NS\" xmlns:pic=\"$PIC_NS\"><w:body>")
                    for (index in 0 until pageCount) {
                        if (index > 0) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                        val picture = pagePicture(index)
                        if (picture != null) {
                            val name = String.format(Locale.ROOT, "page_%03d.jpg", index)
                            val id = "rId${index + 1}"
                            media += Triple(id, "$R_NS/image", "media/$name")
                            binaryEntry(zip, "word/media/$name", picture)
                            append(drawingParagraph(id, picture, index + 1))
                        }
                        val lines = pageLines(index)
                        if (lines.isEmpty()) {
                            if (picture == null) append("<w:p/>")
                        } else {
                            lines.forEach { line -> append(paragraph(line)) }
                        }
                    }
                    append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" ")
                    append("w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>")
                    append("</w:body></w:document>")
                }
                entry(zip, "word/document.xml", document)
                entry(zip, "word/_rels/document.xml.rels", documentRels(media))
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

    private fun binaryEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun documentRels(items: List<Triple<String, String, String>>): String = buildString {
        append(XML_DECL)
        append("<Relationships xmlns=\"$PKG_NS\">")
        for ((id, type, target) in items) {
            append("<Relationship Id=\"$id\" Type=\"$type\" Target=\"$target\"/>")
        }
        append("</Relationships>")
    }

    /** An inline picture fitted to the text column (9,638 twips = 6,120,130 EMU), aspect kept. */
    private fun drawingParagraph(relId: String, jpeg: ByteArray, index: Int): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw IllegalStateException("Page $index can't be read as an image")
        }
        val scale = min(
            MAX_IMAGE_WIDTH_EMU.toDouble() / opts.outWidth,
            MAX_IMAGE_HEIGHT_EMU.toDouble() / opts.outHeight
        )
        val cx = (opts.outWidth * scale).roundToLong().coerceIn(1L, MAX_IMAGE_WIDTH_EMU)
        val cy = (opts.outHeight * scale).roundToLong().coerceIn(1L, MAX_IMAGE_HEIGHT_EMU)
        return buildString {
            append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
            append("<wp:docPr id=\"$index\" name=\"Page $index\"/>")
            append("<a:graphic><a:graphicData uri=\"$PIC_URI\">")
            append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$index\" name=\"Page $index\"/>")
            append("<pic:cNvPicPr/></pic:nvPicPr>")
            append("<pic:blipFill><a:blip r:embed=\"$relId\"/>")
            append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
            append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
            append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
        }
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

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val PIC_URI = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val PKG_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"

    /** 9,638 twips of text column × 635 EMU/twip. */
    private const val MAX_IMAGE_WIDTH_EMU = 6_120_130L

    /** 14,570 twips of text height × 635 EMU/twip. */
    private const val MAX_IMAGE_HEIGHT_EMU = 9_251_950L

    private const val CONTENT_TYPES =
        XML_DECL +
            "<Types xmlns=\"$CT_NS\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        XML_DECL +
            "<Relationships xmlns=\"$PKG_NS\">" +
            "<Relationship Id=\"rId1\" " +
            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
            "Target=\"word/document.xml\"/></Relationships>"
}
