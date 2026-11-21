package com.scanwhirl.app.util

import android.graphics.BitmapFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Writes a minimal, valid PowerPoint presentation (`.pptx`) with `java.util.zip` only.
 *
 * The package is the smallest complete OOXML presentation PowerPoint accepts:
 * `[Content_Types].xml`, `_rels/.rels`, `ppt/presentation.xml` (+ rels), one slide master, one
 * blank slide layout, one theme, and one slide per page whose only shape is a picture of that
 * page fitted to the slide. When a page has recognised text, a notes master and a notes slide
 * carry it in the speaker notes.
 *
 * Pages are encoded by [write]'s callbacks one at a time (the caller decodes, encodes and
 * recycles one page), so a long document never holds all pictures in memory. The file is built
 * next to the target as `.part` and renamed only when complete: a failure leaves nothing visible.
 */
object PptxWriter {

    /** A4 portrait slide, in English Metric Units (21.0 × 29.7 cm). */
    const val A4_WIDTH_EMU = 7_560_000L
    const val A4_HEIGHT_EMU = 10_692_000L

    /**
     * True when a written deck actually carries speaker notes. The result screen uses this to
     * avoid claiming notes exist for a deck that only holds page pictures. Never throws: an
     * unreadable or incomplete file simply has no notes to describe.
     */
    fun hasNotes(file: File): Boolean = try {
        ZipFile(file).use { it.getEntry("ppt/notesSlides/notesSlide1.xml") != null }
    } catch (_: Exception) {
        false
    }

    /** Slides a talk app can show: 4:3 notes area, the PowerPoint default. */
    private const val NOTES_WIDTH_EMU = 6_858_000L
    private const val NOTES_HEIGHT_EMU = 9_144_000L

    /**
     * @param pageCount number of slides (at least 1).
     * @param portrait true for an A4 portrait deck, false for the landscape version (the size
     *   follows the first page's aspect; the caller decides).
     * @param pageJpeg one JPEG per page, in order; called once per page while writing.
     * @param pageNotes the page's notes text, or null/blank for no notes.
     */
    fun write(
        outFile: File,
        pageCount: Int,
        portrait: Boolean,
        pageJpeg: (Int) -> ByteArray,
        pageNotes: (Int) -> String? = { null }
    ) {
        require(pageCount >= 1) { "No pages to present" }
        outFile.parentFile?.mkdirs()
        val slideW = if (portrait) A4_WIDTH_EMU else A4_HEIGHT_EMU
        val slideH = if (portrait) A4_HEIGHT_EMU else A4_WIDTH_EMU
        val notes = (0 until pageCount).map { pageNotes(it)?.takeIf { text -> text.isNotBlank() } }
        val withNotes = notes.any { it != null }

        val partial = File(outFile.parentFile, outFile.name + ".part")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { zip ->
                entry(zip, "[Content_Types].xml", contentTypes(pageCount, withNotes))
                entry(zip, "_rels/.rels", rootRels())
                entry(zip, "ppt/presentation.xml", presentation(pageCount, slideW, slideH, withNotes))
                entry(zip, "ppt/_rels/presentation.xml.rels", presentationRels(pageCount, withNotes))
                entry(zip, "ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER)
                entry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", SLIDE_MASTER_RELS)
                entry(zip, "ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT)
                entry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", SLIDE_LAYOUT_RELS)
                entry(zip, "ppt/theme/theme1.xml", THEME)
                if (withNotes) {
                    entry(zip, "ppt/notesMasters/notesMaster1.xml", NOTES_MASTER)
                    entry(zip, "ppt/notesMasters/_rels/notesMaster1.xml.rels", NOTES_MASTER_RELS)
                }
                for (index in 0 until pageCount) {
                    val jpeg = pageJpeg(index)
                    val (imageW, imageH) = jpegSize(jpeg, index)
                    entry(zip, slidePath(index), slideXml(index + 1, slideW, slideH, imageW, imageH))
                    entry(
                        zip,
                        "ppt/slides/_rels/slide${index + 1}.xml.rels",
                        slideRels(index + 1, withNotes)
                    )
                    binaryEntry(zip, "ppt/media/image${index + 1}.jpeg", jpeg)
                    val text = notes[index]
                    if (withNotes) {
                        entry(zip, notesSlidePath(index), notesXml(text ?: ""))
                        entry(
                            zip,
                            "ppt/notesSlides/_rels/notesSlide${index + 1}.xml.rels",
                            notesSlideRels(index + 1)
                        )
                    }
                }
            }
            if (partial.length() <= 0L) throw IOException("The presentation is empty")
            if (!partial.renameTo(outFile)) throw IOException("Could not save the presentation")
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    private fun slidePath(index: Int): String = "ppt/slides/slide${index + 1}.xml"

    private fun notesSlidePath(index: Int): String = "ppt/notesSlides/notesSlide${index + 1}.xml"

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

    private fun jpegSize(jpeg: ByteArray, index: Int): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw IllegalStateException("Page ${index + 1} can't be read as an image")
        }
        return opts.outWidth to opts.outHeight
    }

    // ---- parts ------------------------------------------------------------

    private fun contentTypes(pageCount: Int, withNotes: Boolean): String = buildString {
        append(XML_DECL)
        append("<Types xmlns=\"$CT_NS\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
        append(
            "<Override PartName=\"/ppt/presentation.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
        )
        append(
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
        )
        append(
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
        )
        append(
            "<Override PartName=\"/ppt/theme/theme1.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>"
        )
        for (i in 1..pageCount) {
            append(
                "<Override PartName=\"/ppt/slides/slide$i.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
            )
        }
        if (withNotes) {
            append(
                "<Override PartName=\"/ppt/notesMasters/notesMaster1.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml\"/>"
            )
            for (i in 1..pageCount) {
                append(
                    "<Override PartName=\"/ppt/notesSlides/notesSlide$i.xml\" " +
                        "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>"
                )
            }
        }
        append("</Types>")
    }

    private fun rootRels(): String = relationships(
        Triple("rId1", "$R_NS/officeDocument", "ppt/presentation.xml")
    )

    private fun presentation(pageCount: Int, slideW: Long, slideH: Long, withNotes: Boolean): String =
        buildString {
            append(XML_DECL)
            append("<p:presentation $NS_DECL>")
            append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
            if (withNotes) {
                append("<p:notesMasterIdLst><p:notesMasterId r:id=\"rId${pageCount + 3}\"/></p:notesMasterIdLst>")
            }
            append("<p:sldIdLst>")
            for (i in 1..pageCount) {
                append("<p:sldId id=\"${255 + i}\" r:id=\"rId${i + 2}\"/>")
            }
            append("</p:sldIdLst>")
            append("<p:sldSz cx=\"$slideW\" cy=\"$slideH\"/>")
            append("<p:notesSz cx=\"$NOTES_WIDTH_EMU\" cy=\"$NOTES_HEIGHT_EMU\"/>")
            append("</p:presentation>")
        }

    private fun presentationRels(pageCount: Int, withNotes: Boolean): String {
        val items = mutableListOf(
            Triple("rId1", "$R_NS/slideMaster", "slideMasters/slideMaster1.xml"),
            Triple("rId2", "$R_NS/theme", "theme/theme1.xml")
        )
        for (i in 1..pageCount) {
            items += Triple("rId${i + 2}", "$R_NS/slide", "slides/slide$i.xml")
        }
        if (withNotes) {
            items += Triple("rId${pageCount + 3}", "$R_NS/notesMaster", "notesMasters/notesMaster1.xml")
        }
        return relationships(items)
    }

    private fun slideXml(index: Int, slideW: Long, slideH: Long, imageW: Int, imageH: Int): String {
        val scale = min(slideW.toDouble() / imageW, slideH.toDouble() / imageH)
        val cx = (imageW * scale).roundToLong().coerceIn(1L, slideW)
        val cy = (imageH * scale).roundToLong().coerceIn(1L, slideH)
        val ox = (slideW - cx) / 2
        val oy = (slideH - cy) / 2
        return buildString {
            append(XML_DECL)
            append("<p:sld $NS_DECL>")
            append("<p:cSld><p:spTree>")
            append(GROUP_SHAPE)
            append("<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page $index\"/>")
            append("<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
            append("<p:blipFill><a:blip r:embed=\"rId1\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>")
            append("<p:spPr><a:xfrm><a:off x=\"$ox\" y=\"$oy\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
            append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>")
            append("</p:spTree></p:cSld>")
            append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
            append("</p:sld>")
        }
    }

    private fun slideRels(index: Int, withNotes: Boolean): String {
        val items = mutableListOf(
            Triple("rId1", "$R_NS/image", "../media/image$index.jpeg")
        )
        if (withNotes) {
            items += Triple("rId2", "$R_NS/notesSlide", "../notesSlides/notesSlide$index.xml")
        }
        return relationships(items)
    }

    private fun notesXml(text: String): String = buildString {
        append(XML_DECL)
        append("<p:notes $NS_DECL>")
        append("<p:cSld><p:spTree>")
        append(GROUP_SHAPE)
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder 2\"/>")
        append("<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>")
        append("<p:nvPr><p:ph type=\"body\" idx=\"3\"/></p:nvPr></p:nvSpPr>")
        append("<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>")
        val lines = text.lines()
        for (line in lines) {
            append("<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>")
            append(escape(line))
            append("</a:t></a:r></a:p>")
        }
        append("<a:p><a:endParaRPr lang=\"en-US\"/></a:p>")
        append("</p:txBody></p:sp>")
        append("</p:spTree></p:cSld>")
        append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        append("</p:notes>")
    }

    private fun notesSlideRels(index: Int): String = relationships(
        Triple("rId1", "$R_NS/notesMaster", "../notesMasters/notesMaster1.xml"),
        Triple("rId2", "$R_NS/slide", "../slides/slide$index.xml")
    )

    private fun relationships(vararg items: Triple<String, String, String>): String =
        relationships(items.toList())

    private fun relationships(items: List<Triple<String, String, String>>): String = buildString {
        append(XML_DECL)
        append("<Relationships xmlns=\"$PKG_NS\">")
        for ((id, type, target) in items) {
            append("<Relationship Id=\"$id\" Type=\"$type\" Target=\"$target\"/>")
        }
        append("</Relationships>")
    }

    /** XML text escaping shared with the notes body (NUL and control characters are dropped). */
    internal fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c == '\u0000' -> Unit
                c < ' ' && c != '\n' && c != '\t' -> Unit
                else -> append(c)
            }
        }
    }

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val PKG_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val NS_DECL = "xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\""

    private const val GROUP_SHAPE =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private const val CLR_MAP =
        "bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" " +
            "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" " +
            "hlink=\"hlink\" folHlink=\"folHlink\""

    private val SLIDE_MASTER: String = buildString {
        append(XML_DECL)
        append("<p:sldMaster $NS_DECL><p:cSld>")
        append("<p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>")
        append("<p:spTree>$GROUP_SHAPE</p:spTree></p:cSld>")
        append("<p:clrMap $CLR_MAP/>")
        append("<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>")
        append("<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>")
        append("</p:sldMaster>")
    }

    private val SLIDE_LAYOUT: String = buildString {
        append(XML_DECL)
        append("<p:sldLayout $NS_DECL type=\"blank\" preserve=\"1\">")
        append("<p:cSld name=\"Blank\"><p:spTree>$GROUP_SHAPE</p:spTree></p:cSld>")
        append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        append("</p:sldLayout>")
    }

    private val SLIDE_MASTER_RELS = relationships(
        Triple("rId1", "$R_NS/slideLayout", "../slideLayouts/slideLayout1.xml"),
        Triple("rId2", "$R_NS/theme", "../theme/theme1.xml")
    )

    private val SLIDE_LAYOUT_RELS = relationships(
        Triple("rId1", "$R_NS/slideMaster", "../slideMasters/slideMaster1.xml")
    )

    private val NOTES_MASTER_RELS = relationships(
        Triple("rId1", "$R_NS/theme", "../theme/theme1.xml")
    )

    private val NOTES_MASTER: String = buildString {
        append(XML_DECL)
        append("<p:notesMaster $NS_DECL><p:cSld>")
        append("<p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>")
        append("<p:spTree>$GROUP_SHAPE")
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Slide Image Placeholder 1\"/>")
        append("<p:cNvSpPr><a:spLocks noGrp=\"1\" noRot=\"1\" noChangeAspect=\"1\"/></p:cNvSpPr>")
        append("<p:nvPr><p:ph type=\"sldImg\" idx=\"2\"/></p:nvPr></p:nvSpPr>")
        append("<p:spPr><a:xfrm><a:off x=\"381000\" y=\"685800\"/>")
        append("<a:ext cx=\"6096000\" cy=\"3429000\"/></a:xfrm>")
        append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:sp>")
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Notes Placeholder 2\"/>")
        append("<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>")
        append("<p:nvPr><p:ph type=\"body\" sz=\"quarter\" idx=\"3\"/></p:nvPr></p:nvSpPr>")
        append("<p:spPr><a:xfrm><a:off x=\"685800\" y=\"4343400\"/>")
        append("<a:ext cx=\"5486400\" cy=\"4114800\"/></a:xfrm>")
        append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>")
        append("<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/>")
        append("<a:t>Notes</a:t></a:r></a:p></p:txBody></p:sp>")
        append("</p:spTree></p:cSld>")
        append("<p:clrMap $CLR_MAP/>")
        append("<p:notesStyle><a:lvl1pPr><a:defRPr sz=\"1200\"/></a:lvl1pPr></p:notesStyle>")
        append("</p:notesMaster>")
    }

    private val THEME: String = buildString {
        append(XML_DECL)
        append("<a:theme xmlns:a=\"$A_NS\" name=\"ScanWhirl\"><a:themeElements>")
        append("<a:clrScheme name=\"ScanWhirl\">")
        append("<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>")
        append("<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>")
        append("<a:dk2><a:srgbClr val=\"1C252A\"/></a:dk2>")
        append("<a:lt2><a:srgbClr val=\"ECEFF1\"/></a:lt2>")
        append("<a:accent1><a:srgbClr val=\"00F0FF\"/></a:accent1>")
        append("<a:accent2><a:srgbClr val=\"38EF7D\"/></a:accent2>")
        append("<a:accent3><a:srgbClr val=\"FFB800\"/></a:accent3>")
        append("<a:accent4><a:srgbClr val=\"FF8C82\"/></a:accent4>")
        append("<a:accent5><a:srgbClr val=\"8D979E\"/></a:accent5>")
        append("<a:accent6><a:srgbClr val=\"B3BCC2\"/></a:accent6>")
        append("<a:hlink><a:srgbClr val=\"00F0FF\"/></a:hlink>")
        append("<a:folHlink><a:srgbClr val=\"FFB800\"/></a:folHlink>")
        append("</a:clrScheme>")
        append("<a:fontScheme name=\"ScanWhirl\">")
        append("<a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>")
        append("<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>")
        append("</a:fontScheme>")
        append("<a:fmtScheme name=\"ScanWhirl\">")
        append("<a:fillStyleLst>")
        append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>")
        append("<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"50000\"/></a:schemeClr></a:solidFill>")
        append("<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"50000\"/></a:schemeClr></a:solidFill>")
        append("</a:fillStyleLst>")
        append("<a:lnStyleLst>")
        for (width in listOf(6350, 12700, 19050)) {
            append("<a:ln w=\"$width\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">")
            append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>")
            append("<a:prstDash val=\"solid\"/></a:ln>")
        }
        append("</a:lnStyleLst>")
        append("<a:effectStyleLst>")
        repeat(3) { append("<a:effectStyle><a:effectLst/></a:effectStyle>") }
        append("</a:effectStyleLst>")
        append("<a:bgFillStyleLst>")
        append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>")
        append("<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"95000\"/></a:schemeClr></a:solidFill>")
        append("<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"95000\"/></a:schemeClr></a:solidFill>")
        append("</a:bgFillStyleLst>")
        append("</a:fmtScheme>")
        append("</a:themeElements></a:theme>")
    }
}
