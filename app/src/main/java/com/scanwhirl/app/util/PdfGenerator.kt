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
                marker in 0xC0..0xC3 -> {
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
     * @throws java.io.IOException (incl. [Durable.SyncException], [PdfVerifier.InvalidPdfException])
     *   when the PDF cannot be written, synced or verified.
     */
    fun imagesToPdf(
        images: List<File>,
        outFile: File,
        textLayers: Map<Int, OcrPage> = emptyMap()
    ): Set<Int> {
        require(images.isNotEmpty()) { "No images to convert" }

        // Validate every JPEG up front, before touching outFile or the tmp file.
        images.forEach { file ->
            require(file.exists() && file.length() > 0L) { "Missing page image: ${file.name}" }
            val bytes = file.readBytes()
            val info = readJpegInfo(bytes)
            PageImageFormat.checkJpeg(bytes)
            require(info.components == 1 || info.components == 3) { "Page must be grayscale or RGB JPEG: ${file.name}" }
        }

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

                w("%PDF-1.4\n")
                w(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

                val kids = (0 until images.size).joinToString(" ") { "${3 + it * 3} 0 R" }

                val offsets = LongArray(images.size * 3 + 3) // 1-based object numbers (index 0 unused)

                offsets[1] = position
                w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

                offsets[2] = position
                w("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${images.size} >>\nendobj\n")

                images.forEachIndexed { i, imageFile ->
                    val bytes = imageFile.readBytes()
                    val info = readJpegInfo(bytes)
                    val pageW = (info.width * 72f / ASSUMED_DPI).roundToInt().coerceAtLeast(1)
                    val pageH = (info.height * 72f / ASSUMED_DPI).roundToInt().coerceAtLeast(1)
                    val pageObj = 3 + i * 3
                    val contentObj = 4 + i * 3
                    val imageObj = 5 + i * 3

                    offsets[pageObj] = position

                    // Text layer: only when geometry exists, has non-blank lines,
                    // and matches the JPEG's pixel dimensions.
                    val layer = textLayers[i]
                    val hasLayer = layer != null && layer.lines.any { it.text.isNotBlank() } &&
                        layer.width == info.width && layer.height == info.height

                    val resources = if (hasLayer) {
                        "/Resources << /XObject << /Im$i $imageObj 0 R >> ${PdfTextLayer.FONT_DICT} >>"
                    } else {
                        "/Resources << /XObject << /Im$i $imageObj 0 R >> >>"
                    }
                    w("$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] $resources /Contents $contentObj 0 R >>\nendobj\n")

                    val content = buildString {
                        append("q\n$pageW 0 0 $pageH 0 0 cm\n/Im$i Do\nQ\n")
                        if (hasLayer) {
                            layeredPages.add(i)
                            append(PdfTextLayer.contentFor(layer, pageW, pageH))
                        }
                    }.toByteArray(Charsets.ISO_8859_1)
                    offsets[contentObj] = position
                    // /Length is the encoded byte count, never the character count.
                    w("$contentObj 0 obj\n<< /Length ${content.size} >>\nstream\n")
                    w(content)
                    w("endstream\nendobj\n")

                    val colorSpace = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
                    offsets[imageObj] = position
                    w("$imageObj 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n")
                    w(bytes)
                    w("\nendstream\nendobj\n")
                }

                val xrefOffset = position
                val total = images.size * 3 + 2
                val xref = StringBuilder()
                xref.append("xref\n0 ${total + 1}\n")
                xref.append("0000000000 65535 f \n")
                for (num in 1..total) {
                    xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[num]))
                }
                w(xref.toString())
                w("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
                Durable.syncStream(out, tmp)
            }

            PdfVerifier.verify(tmp, expectedImages = images)

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
