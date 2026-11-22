package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrPage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Minimal PDF writer that embeds JPEG pages via DCTDecode.
 * Pure Kotlin: no android.graphics.pdf (native, untestable off-device),
 * and lossless — JPEG bytes are embedded as-is, never re-encoded.
 *
 * Streaming (one page at a time), atomic output via `.tmp` + fsync + rename,
 * optional invisible text layer, and bounded structural verification by
 * [PdfVerifier] before the output is ever published.
 */
object PdfGenerator {

    private const val ASSUMED_DPI = 150f

    data class JpegInfo(val width: Int, val height: Int, val components: Int)

    /** An OCR line box in page pixels, top-left origin. */
    data class PdfLinkRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * LX: one clickable title. [pageIndex] is the page holding the visible title,
     * [targetPage] the 0-based page its /Dest jumps to, [rect] its OCR box in pixels.
     * [title] becomes the bookmark /Title (encoded as UTF-16BE, so Devanagari survives).
     */
    data class PdfLink(
        val pageIndex: Int,
        val title: String,
        val targetPage: Int,
        val rect: PdfLinkRect
    )

    private const val MAX_LINKS = 2000
    private const val MAX_TITLE_CHARS = 300

    fun readJpegInfo(bytes: ByteArray): JpegInfo {
        require(bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            "Not a JPEG file"
        }
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            when {
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> i += 2
                marker == 0xD9 -> break
                JpegSof.isSupported(marker) -> {
                    val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                    val components = bytes[i + 9].toInt() and 0xFF
                    require(width > 0 && height > 0) { "Invalid JPEG dimensions" }
                    return JpegInfo(width, height, components)
                }
                else -> {
                    val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                    i += 2 + len
                }
            }
        }
        throw IllegalStateException("JPEG header not found")
    }

    /**
     * Writes a PDF from [images] to [outFile], optionally with an invisible
     * text layer from [textLayers] (page index → [OcrPage] geometry).
     *
     * Returns the set of page indices that received a text layer.
     *
     * Streaming: each page JPEG is read, written, and dropped before the next
     * page is processed — no whole-image list is held in memory.
     * Atomicity: output goes to `<outFile>.tmp`, is fsynced, structurally verified
     * against [images] by [PdfVerifier], and only then renamed over `outFile`.
     * There is no copy fallback: if the rename fails the call fails. On any failure
     * the `.tmp` is deleted and an existing `outFile` is left untouched.
     *
     * When [encryption] is given (v2 Protect PDF, ISO 32000-2 V5/R6 AESV3), every stream body is
     * replaced by `16-byte IV + AES-256-CBC/PKCS#7` and `/Length` is the real (larger) byte count,
     * and the trailer gains the `/Encrypt` dictionary and `/ID`. The verifier gets the same key, so
     * the page bytes are still compared before the file is published.
     *
     * LX: when [links] is non-empty the writer also emits a flat `/Outlines` tree and one
     * `/Subtype /Link` annotation per entry (with a `/Dest [page /Fit]`). Strings inside an
     * encrypted PDF would have to be encoded with the object key, which this writer does not do,
     * so links are refused for encrypted / PDF-A output. With no links the bytes are unchanged.
     *
     * @throws java.io.IOException (incl. [Durable.SyncException], [PdfVerifier.InvalidPdfException])
     *   when the PDF cannot be written, synced or verified.
     */
    fun imagesToPdf(
        images: List<File>,
        outFile: File,
        textLayers: Map<Int, OcrPage> = emptyMap(),
        encryption: PdfAes.Encryption? = null,
        pdfa: PdfA.Mode? = null,
        links: List<PdfLink> = emptyList()
    ): Set<Int> {
        require(images.isNotEmpty()) { "No images to convert" }
        require(pdfa == null || encryption == null) {
            "A PDF/A copy is never password-protected"
        }
        require(links.isEmpty() || (encryption == null && pdfa == null)) {
            "Links need an ordinary, unprotected PDF"
        }
        require(links.size <= MAX_LINKS) { "Too many links" }
        links.forEach { link ->
            require(link.pageIndex in images.indices) { "Link source page outside the document" }
            require(link.targetPage in images.indices) { "Link target page outside the document" }
            require(link.title.isNotBlank()) { "A link has no title" }
        }

        // A3: the only plaintext PDF writer never writes scan.pdf beside a lock (I3).
        if (outFile.name == PdfProtection.PDF_FILE &&
            File(outFile.absoluteFile.parentFile, PdfProtection.LOCK_FILE).exists()
        ) {
            throw PdfProtection.PlainWriteRefused(outFile)
        }

        // Every JPEG is validated in the write loop below: one read per page, not two. A bad page
        // still fails before publish because the tmp is deleted and outFile is untouched.
        val tmp = File(outFile.parentFile ?: outFile, "${outFile.name}.tmp")
        tmp.parentFile?.mkdirs()

        val layeredPages = mutableSetOf<Int>()

        try {
            FileOutputStream(tmp).use { out ->
                var position = 0L
                fun w(bytes: ByteArray) {
                    out.write(bytes)
                    position += bytes.size
                }
                fun w(s: String) = w(s.toByteArray(Charsets.ISO_8859_1))

                // PDF/A-2b needs PDF 1.7 and the standard binary comment line.
                w(if (pdfa == null) "%PDF-1.4\n" else "%PDF-1.7\n")
                w(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

                // Object layout: 1 catalog, 2 page tree, then three objects per page. PDF/A adds
                // 3 ICC profile, 4 output intent, 5 XMP metadata, 6 Info before the pages, so the
                // default (non-archive) output is byte-for-byte what earlier versions wrote.
                val pageBase = if (pdfa == null) 3 else 7
                val kids = (0 until images.size).joinToString(" ") { "${pageBase + it * 3} 0 R" }

                // LX: one outline root, one item and one link annotation per entry, appended
                // AFTER the page objects so every existing object number stays the same.
                val linkCount = links.size
                val outlineRoot = if (linkCount == 0) -1 else pageBase + images.size * 3
                val firstOutline = outlineRoot + 1
                val firstAnnot = firstOutline + linkCount
                val total = pageBase + images.size * 3 - 1 + if (linkCount == 0) 0 else 1 + linkCount * 2
                val offsets = LongArray(total + 1) // 1-based object numbers (index 0 unused)
                val linksByPage = links.groupBy { it.pageIndex }
                val annotNumbers = java.util.IdentityHashMap<PdfLink, Int>().apply {
                    links.forEachIndexed { i, link -> put(link, firstAnnot + i) }
                }
                val pagePixels = arrayOfNulls<Pair<Int, Int>>(images.size)
                val pagePoints = arrayOfNulls<Pair<Int, Int>>(images.size)

                offsets[1] = position
                val catalogExtra = (if (pdfa == null) "" else " /Metadata 5 0 R /OutputIntents [4 0 R]") +
                    (if (linkCount == 0) "" else " /Outlines $outlineRoot 0 R")
                w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R$catalogExtra >>\nendobj\n")

                offsets[2] = position
                w("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${images.size} >>\nendobj\n")

                if (pdfa != null) {
                    val icc = pdfa.iccProfile
                    offsets[3] = position
                    w("3 0 obj\n<< /N 3 /Length ${icc.size} >>\nstream\n")
                    w(icc)
                    w("\nendstream\nendobj\n")

                    offsets[4] = position
                    w("4 0 obj\n<< ${PdfA.outputIntentDict(3)} >>\nendobj\n")

                    offsets[5] = position
                    val xmp = PdfA.xmp(pdfa).toByteArray(Charsets.UTF_8)
                    w("5 0 obj\n<< /Type /Metadata /Subtype /XML /Length ${xmp.size} >>\nstream\n")
                    w(xmp)
                    w("\nendstream\nendobj\n")

                    offsets[6] = position
                    w("6 0 obj\n<< ${PdfA.infoDict(pdfa)} >>\nendobj\n")
                }

                images.forEachIndexed { i, imageFile ->
                    require(imageFile.exists() && imageFile.length() > 0L) {
                        "Missing page image: ${imageFile.name}"
                    }
                    val bytes = imageFile.readBytes()
                    val info = readJpegInfo(bytes)
                    PageImageFormat.checkJpeg(bytes)
                    require(info.components == 1 || info.components == 3) {
                        "Page must be grayscale or RGB JPEG: ${imageFile.name}"
                    }
                    val pageW = (info.width * 72f / ASSUMED_DPI).roundToInt().coerceAtLeast(1)
                    val pageH = (info.height * 72f / ASSUMED_DPI).roundToInt().coerceAtLeast(1)
                    pagePixels[i] = info.width to info.height
                    pagePoints[i] = pageW to pageH
                    val pageObj = pageBase + i * 3
                    val contentObj = pageBase + 1 + i * 3
                    val imageObj = pageBase + 2 + i * 3

                    offsets[pageObj] = position

                    // Text layer: only when geometry exists, has non-blank lines,
                    // matches the JPEG's pixel dimensions and really emits text.
                    // H3-1: a PDF/A archive is image-only. The OCR layer's font is a
                    // non-embedded base-14 Helvetica, which ISO 19005-2 6.2.11.3.1 forbids,
                    // so a layer offered by a caller is never written into an archive.
                    val layer = textLayers[i]
                    val layerContent = if (pdfa == null && layer != null &&
                        layer.lines.any { it.text.isNotBlank() } &&
                        layer.width == info.width && layer.height == info.height
                    ) {
                        PdfTextLayer.contentFor(layer, pageW, pageH)
                    } else {
                        ""
                    }
                    // H4-1: a page is layered only when its content stream carries text. Counting
                    // the page before contentFor() ran claimed a layer for a page that emitted none.
                    val hasLayer = layerContent.isNotBlank()

                    val resources = if (hasLayer) {
                        "/Resources << /XObject << /Im$i $imageObj 0 R >> ${PdfTextLayer.FONT_DICT} >>"
                    } else {
                        "/Resources << /XObject << /Im$i $imageObj 0 R >> >>"
                    }
                    // LX: /Annots is appended last so the existing dictionary bytes (and the
                    // verifier's /XObject and /Contents regexes) stay identical when there are none.
                    val annots = if (linkCount == 0) "" else
                        linksByPage[i]?.joinToString(" ", prefix = " /Annots [", postfix = "]") { link ->
                            "${annotNumbers[link]} 0 R"
                        } ?: ""
                    w("$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] $resources /Contents $contentObj 0 R$annots >>\nendobj\n")

                    val content = buildString {
                        append("q\n$pageW 0 0 $pageH 0 0 cm\n/Im$i Do\nQ\n")
                        if (hasLayer) {
                            layeredPages.add(i)
                            append(layerContent)
                        }
                    }.toByteArray(Charsets.ISO_8859_1)
                    val contentBody = encryption?.encryptStream(content) ?: content
                    offsets[contentObj] = position
                    // /Length is the encoded byte count, never the character count.
                    w("$contentObj 0 obj\n<< /Length ${contentBody.size} >>\nstream\n")
                    w(contentBody)
                    // PDF/A needs the EOL marker before endstream to be outside the /Length
                    // (ISO 19005-2 6.1.7.1). The plain writer keeps its historical bytes.
                    w(if (pdfa == null) "endstream\nendobj\n" else "\nendstream\nendobj\n")

                    val colorSpace = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
                    val imageBody = encryption?.encryptStream(bytes) ?: bytes
                    offsets[imageObj] = position
                    w("$imageObj 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${imageBody.size} >>\nstream\n")
                    w(imageBody)
                    w("\nendstream\nendobj\n")
                }

                // LX: flat outline tree + one invisible GoTo link per entry. These objects
                // are written after all page objects, so earlier xref offsets never move.
                if (linkCount > 0) {
                    offsets[outlineRoot] = position
                    w("$outlineRoot 0 obj\n<< /Type /Outlines /First $firstOutline 0 R " +
                        "/Last ${firstOutline + linkCount - 1} 0 R /Count $linkCount >>\nendobj\n")
                    links.forEachIndexed { index, link ->
                        val num = firstOutline + index
                        offsets[num] = position
                        val title = PdfTextLayer.actualTextHex(link.title.take(MAX_TITLE_CHARS)) ?: "<FEFF>"
                        val prev = if (index > 0) " /Prev ${num - 1} 0 R" else ""
                        val next = if (index < linkCount - 1) " /Next ${num + 1} 0 R" else ""
                        val dest = "${pageBase + link.targetPage * 3} 0 R"
                        w("$num 0 obj\n<< /Title $title /Parent $outlineRoot 0 R$prev$next " +
                            "/Dest [ $dest /Fit ] >>\nendobj\n")
                    }
                    links.forEachIndexed { index, link ->
                        val num = firstAnnot + index
                        offsets[num] = position
                        val pixels = pagePixels[link.pageIndex]!!
                        val points = pagePoints[link.pageIndex]!!
                        val box = LinkIndexDetector.toPdfRect(
                            LinkIndexDetector.Box(link.rect.left, link.rect.top, link.rect.right, link.rect.bottom),
                            pixels.first, pixels.second, points.first, points.second
                        )
                        fun pt(v: Int) = String.format(Locale.ROOT, "%.2f", v.toDouble())
                        val dest = "${pageBase + link.targetPage * 3} 0 R"
                        w("$num 0 obj\n<< /Type /Annot /Subtype /Link /Border [0 0 0] " +
                            "/Rect [${pt(box[0])} ${pt(box[1])} ${pt(box[2])} ${pt(box[3])}] " +
                            "/Dest [ $dest /Fit ] >>\nendobj\n")
                    }
                }

                val xrefOffset = position
                val xref = StringBuilder()
                xref.append("xref\n0 ${total + 1}\n")
                xref.append("0000000000 65535 f \n")
                for (num in 1..total) {
                    xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[num]))
                }
                w(xref.toString())
                val trailerExtra = when {
                    pdfa != null -> " /Info 6 0 R /ID [<${pdfa.documentId}> <${pdfa.documentId}>]"
                    encryption != null -> " /Encrypt ${encryption.encryptDict()} " +
                        "/ID [<${PdfAes.hex(encryption.fileId)}> <${PdfAes.hex(encryption.fileId)}>]"
                    else -> ""
                }
                w("trailer\n<< /Size ${total + 1} /Root 1 0 R$trailerExtra >>\nstartxref\n$xrefOffset\n%%EOF\n")
                Durable.syncStream(out, tmp)
            }

            PdfVerifier.verify(
                tmp,
                expectedImages = images,
                decryption = encryption,
                eolBeforeContentEndstream = pdfa != null
            )

            // Atomic publish: file rename replaces outFile atomically. No fallback copy.
            if (!tmp.renameTo(outFile)) {
                throw IOException("Could not publish ${outFile.name}")
            }
            outFile.parentFile?.let { Durable.syncDir(it) } // best effort; see Durable

            return layeredPages
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
