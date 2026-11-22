package com.scanwhirl.app.util

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.floor

/**
 * FF-P: pure output options for the Passport tool.
 *
 * Two different jobs live behind one tile, and this object keeps their contracts separate:
 *  - PHOTO_FILE is a real JPEG with explicit pixels and an optional, honest KB band.
 *  - PRINT_SHEET is a PDF whose MediaBox is a physical sheet and whose copies are drawn into
 *    exact 35 x 45 mm boxes in PDF points. Nothing depends on a viewer's "fit to page".
 *
 * Geometry is deliberately expressed in points (1 inch = 25.4 mm = 72 pt). No number here is a
 * claim of ICAO, biometric or portal validation: it only describes the file we can make offline.
 */
object PassportOutputOptions {

    const val MIME_JPEG = "image/jpeg"
    const val MIME_PDF = "application/pdf"

    enum class Need { PHOTO_FILE, PRINT_SHEET }

    enum class Sheet { A4, CM_10X15 }

    /**
     * A photo-file request. A null min/max means "no band chosen": the encoder still keeps the
     * exact pixels; we simply do not promise a byte range. KB is decimal ([SizeUnits]).
     */
    data class PhotoSpec(
        val id: String,
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int,
        val minKb: Int? = null,
        val maxKb: Int? = null,
        val sourceNote: String = ""
    ) {
        val hasBand: Boolean get() = minKb != null || maxKb != null
        val minBytes: Long get() = (minKb ?: 1).toLong() * SizeUnits.BYTES_PER_KB
        val maxBytes: Long get() = (maxKb ?: 10_000).toLong() * SizeUnits.BYTES_PER_KB
    }

    const val MISSION_WIDTH = 630
    const val MISSION_HEIGHT = 810
    const val MISSION_DPI = 300

    /** Dated, attributable example from the mission photo guidance in the fitness audit (S12). */
    val MISSION = PhotoSpec(
        "mission", MISSION_WIDTH, MISSION_HEIGHT, MISSION_DPI,
        sourceNote = "Indian mission photo guidance, updated 14 Oct 2026"
    )

    fun custom(widthPx: Int, heightPx: Int, minKb: Int?, maxKb: Int?): PhotoSpec = PhotoSpec(
        "custom", widthPx, heightPx, MISSION_DPI, minKb, maxKb
    )

    const val PRINT_MM_W = 35f
    const val PRINT_MM_H = 45f
    const val PRINT_DPI = 300

    fun mmToPt(mm: Float): Float = mm / 25.4f * 72f

    fun sheetSizePt(sheet: Sheet): Pair<Float, Float> = when (sheet) {
        Sheet.A4 -> mmToPt(210f) to mmToPt(297f)
        Sheet.CM_10X15 -> mmToPt(100f) to mmToPt(150f)
    }

    fun sheetName(sheet: Sheet): String = when (sheet) {
        Sheet.A4 -> "A4"
        Sheet.CM_10X15 -> "10 × 15 cm"
    }

    /** 35 x 45 mm expressed in PDF points: 99.213 x 127.559 pt. */
    fun printBoxPt(): Pair<Float, Float> = mmToPt(PRINT_MM_W) to mmToPt(PRINT_MM_H)

    /** A sensible 300-DPI raster for a 35 x 45 mm print box; the crop is scaled into the box. */
    fun printPixels(): Pair<Int, Int> =
        floor(PRINT_MM_W / 25.4f * PRINT_DPI).toInt() to floor(PRINT_MM_H / 25.4f * PRINT_DPI).toInt()

    data class Box(val xPt: Float, val yPt: Float, val widthPt: Float, val heightPt: Float)

    data class PrintLayout(
        val sheet: Sheet,
        val sheetWidthPt: Float,
        val sheetHeightPt: Float,
        val boxWidthPt: Float,
        val boxHeightPt: Float,
        val columns: Int,
        val rows: Int,
        val boxes: List<Box>
    )

    /** Fits whole 35 x 45 mm copies on the chosen sheet, anchored at the bottom-left corner. */
    fun layout(sheet: Sheet): PrintLayout {
        val (sheetW, sheetH) = sheetSizePt(sheet)
        val (boxW, boxH) = printBoxPt()
        val sheetWmm = if (sheet == Sheet.A4) 210f else 100f
        val sheetHmm = if (sheet == Sheet.A4) 297f else 150f
        val columns = floor(sheetWmm / PRINT_MM_W).toInt().coerceAtLeast(1)
        val rows = floor(sheetHmm / PRINT_MM_H).toInt().coerceAtLeast(1)
        val boxes = ArrayList<Box>(columns * rows)
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                boxes += Box(col * boxW, row * boxH, boxW, boxH)
            }
        }
        return PrintLayout(sheet, sheetW, sheetH, boxW, boxH, columns, rows, boxes)
    }

    /**
     * Minimal, self-contained PDF writer for the print sheet. The JPEG is embedded once and drawn
     * into each exact-size box by the content stream, so physical size never depends on raster DPI
     * or on a viewer scaling the page. We deliberately do not touch the shared PDF writer (FF-G).
     */
    fun buildPrintPdf(jpeg: ByteArray, imageWidthPx: Int, imageHeightPx: Int, sheet: Sheet): ByteArray {
        require(jpeg.isNotEmpty() && imageWidthPx > 0 && imageHeightPx > 0) { "No image to place" }
        val page = layout(sheet)

        fun pt(value: Float): String = String.format(Locale.ROOT, "%.3f", value)

        val content = StringBuilder()
        for (box in page.boxes) {
            content.append("q\n")
            content.append("${pt(box.widthPt)} 0 0 ${pt(box.heightPt)} ${pt(box.xPt)} ${pt(box.yPt)} cm\n")
            content.append("/Im0 Do\n")
            content.append("Q\n")
        }
        val contentBytes = content.toString().toByteArray(Charsets.ISO_8859_1)

        val objects = mutableListOf<ByteArray>()
        fun add(text: String) {
            objects += text.toByteArray(Charsets.ISO_8859_1)
        }
        fun addStream(dictionary: String, stream: ByteArray) {
            objects += dictionary.toByteArray(Charsets.ISO_8859_1) +
                "stream\n".toByteArray(Charsets.ISO_8859_1) +
                stream + "\nendstream".toByteArray(Charsets.ISO_8859_1)
        }

        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add(
            "<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 ${pt(page.sheetWidthPt)} ${pt(page.sheetHeightPt)}] " +
                "/Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>"
        )
        addStream(
            "<< /Type /XObject /Subtype /Image /Width $imageWidthPx /Height $imageHeightPx " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>",
            jpeg
        )
        addStream("<< /Length ${contentBytes.size} >>", contentBytes)

        val out = ByteArrayOutputStream()
        fun write(text: String) {
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            out.write(bytes, 0, bytes.size)
        }

        write("%PDF-1.4\n")
        val offsets = IntArray(objects.size + 1)
        for (i in objects.indices) {
            offsets[i + 1] = out.size()
            write("${i + 1} 0 obj\n")
            out.write(objects[i], 0, objects[i].size)
            write("\nendobj\n")
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n")
        write("0000000000 65535 f \n")
        for (i in 1..objects.size) {
            write(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]))
        }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
