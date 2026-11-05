package com.scanwhirl.app.util

import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Minimal PDF writer that embeds JPEG pages via DCTDecode.
 * Pure Kotlin: no android.graphics.pdf (native, untestable off-device),
 * and lossless — JPEG bytes are embedded as-is, never re-encoded.
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

    fun imagesToPdf(images: List<File>, outFile: File) {
        require(images.isNotEmpty()) { "No images to convert" }

        val pages = images.map { file ->
            require(file.exists() && file.length() > 0L) { "Missing page image: ${file.name}" }
            val bytes = file.readBytes()
            readJpegInfo(bytes) // validates JPEG before writing anything
            bytes
        }

        outFile.parentFile?.mkdirs()
        outFile.delete()

        var position = 0L
        val offsets = LongArray(pages.size * 3 + 3) // 1-based object numbers (index 0 unused)

        FileOutputStream(outFile).use { out ->
            fun w(bytes: ByteArray) {
                out.write(bytes)
                position += bytes.size
            }
            fun w(s: String) = w(s.toByteArray(Charsets.ISO_8859_1))

            w("%PDF-1.4\n")
            w(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

            val kids = (0 until pages.size).joinToString(" ") { "${3 + it * 3} 0 R" }

            offsets[1] = position
            w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            offsets[2] = position
            w("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

            pages.forEachIndexed { i, bytes ->
                val info = readJpegInfo(bytes)
                val pageW = (info.width * 72f / ASSUMED_DPI).roundToInt().coerceAtLeast(1)
                val pageH = (info.height * 72f / ASSUMED_DPI).roundToInt().coerceAtLeast(1)
                val pageObj = 3 + i * 3
                val contentObj = 4 + i * 3
                val imageObj = 5 + i * 3

                offsets[pageObj] = position
                w("$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] " +
                    "/Resources << /XObject << /Im$i $imageObj 0 R >> >> /Contents $contentObj 0 R >>\nendobj\n")

                val content = "q\n$pageW 0 0 $pageH 0 0 cm\n/Im$i Do\nQ\n"
                offsets[contentObj] = position
                w("$contentObj 0 obj\n<< /Length ${content.length} >>\nstream\n${content}endstream\nendobj\n")

                val colorSpace = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
                offsets[imageObj] = position
                w("$imageObj 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                    "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n")
                w(bytes)
                w("\nendstream\nendobj\n")
            }

            val xrefOffset = position
            val total = pages.size * 3 + 2
            val xref = StringBuilder()
            xref.append("xref\n0 ${total + 1}\n")
            xref.append("0000000000 65535 f \n")
            for (num in 1..total) {
                xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[num]))
            }
            w(xref.toString())
            w("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        }

        verifyPdf(outFile, pages.size)
    }

    private fun verifyPdf(file: File, expectedPages: Int) {
        require(file.length() > 0L) { "PDF write produced empty file" }
        val all = file.readText(Charsets.ISO_8859_1)
        require(all.startsWith("%PDF-")) { "Not a PDF" }
        require(all.endsWith("%%EOF\n")) { "PDF not terminated" }
        val count = Regex("/Count (\\d+)").find(all)?.groupValues?.get(1)?.toIntOrNull()
        require(count == expectedPages) { "Page count mismatch: $count != $expectedPages" }
        require(Regex("startxref\\n(\\d+)").find(all) != null) { "Missing startxref" }
    }
}
