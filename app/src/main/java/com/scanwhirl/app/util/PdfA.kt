package com.scanwhirl.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PDF/A-2b (ISO 19005-2, conformance level B) for ScanWhirl's image-based PDFs.
 *
 * What a viewer/validator needs and this object produces:
 *  - a `GTS_PDFA1` OutputIntent whose `/DestOutputProfile` is an embedded **sRGB ICC profile**
 *    (`res/raw/srgb.icc`, zlib-licensed, see `docs/licenses/icc-sRGB-zlib.txt`);
 *  - an **XMP metadata packet** with `pdfaid:part = 2`, `pdfaid:conformance = B`, `dc:title`
 *    and `xmp:CreateDate` / `xmp:ModifyDate` / `xmp:CreatorTool` / `pdf:Producer` that **match the
 *    Info dictionary** (every Info entry is mirrored, ISO 19005-2 6.7.3);
 *  - a document **`/ID`** in the trailer, no encryption, no transparency (pages are plain JPEG).
 *
 * The archive is **image-only**: the recognised text layer's base-14 Helvetica is not embedded,
 * and PDF/A-2b requires every font to be embedded (6.2.11.3.1), so [PdfGenerator] never writes a
 * text layer into an archive. Everything here is pure text/byte building, so the structure can be
 * unit-tested without a PDF reader; [PdfGenerator] writes the objects and veraPDF is the external
 * host check for the produced files.
 */
object PdfA {

    /** Producer string used by both the Info dictionary and the XMP packet (they must agree). */
    const val PRODUCER = "ScanWhirl"

    /** Everything the writer needs to emit a PDF/A-2b file instead of the plain PDF. */
    data class Mode(
        val iccProfile: ByteArray,
        val title: String,
        val createdAt: Long,
        val modifiedAt: Long = createdAt,
        /** 32 hex characters, the same value in both halves of the trailer /ID. */
        val documentId: String,
        val timeZone: TimeZone = TimeZone.getDefault()
    ) {
        init {
            require(iccProfile.size >= 128) { "An ICC profile is required for PDF/A" }
            require(iccProfile.size >= 40) { "An ICC profile is required for PDF/A" }
        }
    }

    /** `D:YYYYMMDDHHmmSS+HH'mm'` (PDF date, used by the Info dictionary). */
    fun infoDate(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = zone }
        val stamp = format.format(Date(millis))
        val offsetMillis = zone.getOffset(millis)
        val totalMinutes = Math.abs(offsetMillis) / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val sign = if (offsetMillis < 0) '-' else '+'
        // PDF 32000-1 7.9.4: the apostrophe wraps the time-zone minutes.
        return "D:%s%c%02d'%02d'".format(Locale.ROOT, stamp, sign, hours, minutes)
    }

    /** `YYYY-MM-DDThh:mm:ss+HH:MM` (XMP date, same instant as [infoDate]). */
    fun xmpDate(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = zone }
        val stamp = format.format(Date(millis))
        val offsetMillis = zone.getOffset(millis)
        val totalMinutes = Math.abs(offsetMillis) / 60_000
        val sign = if (offsetMillis < 0) '-' else '+'
        return "%s%c%02d:%02d".format(Locale.ROOT, stamp, sign, totalMinutes / 60, totalMinutes % 60)
    }

    /** A document ID: 32 hex characters, derived from the title and time so the halves match. */
    fun documentId(title: String, millis: Long): String =
        Sha256.of((title + "\u0000" + millis + "\u0000" + PRODUCER).toByteArray(Charsets.UTF_8))
            .take(32)
            .lowercase(Locale.ROOT)

    /**
     * The XMP packet. [title] and the dates must be the same values the Info dictionary carries
     * (PDF/A checks that they agree), so both are built from [Mode].
     */
    fun xmp(mode: Mode): String {
        val created = xmpDate(mode.createdAt, mode.timeZone)
        val modified = xmpDate(mode.modifiedAt, mode.timeZone)
        return buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"ScanWhirl\">\n")
            append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("  <rdf:Description rdf:about=\"\"")
            append(" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">\n")
            append("   <pdfaid:part>2</pdfaid:part>\n")
            append("   <pdfaid:conformance>B</pdfaid:conformance>\n")
            append("  </rdf:Description>\n")
            append("  <rdf:Description rdf:about=\"\"")
            append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
            append("   <dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">")
            append(xml(mode.title))
            append("</rdf:li></rdf:Alt></dc:title>\n")
            append("  </rdf:Description>\n")
            append("  <rdf:Description rdf:about=\"\"")
            append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"")
            append(" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n")
            append("   <xmp:CreateDate>").append(created).append("</xmp:CreateDate>\n")
            append("   <xmp:ModifyDate>").append(modified).append("</xmp:ModifyDate>\n")
            append("   <xmp:MetadataDate>").append(modified).append("</xmp:MetadataDate>\n")
            append("   <xmp:CreatorTool>").append(PRODUCER).append("</xmp:CreatorTool>\n")
            append("   <pdf:Producer>").append(PRODUCER).append("</pdf:Producer>\n")
            append("  </rdf:Description>\n")
            append(" </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            append("<?xpacket end=\"w\"?>")
        }
    }

    /** The OutputIntent dictionary body (without the surrounding `<< >>`). */
    fun outputIntentDict(iccObjectNumber: Int, identifier: String = "sRGB IEC61966-2.1"): String =
        "/Type /OutputIntent /S /GTS_PDFA1 /OutputConditionIdentifier (${pdfString(identifier)}) " +
            "/OutputCondition (${pdfString(identifier)}) /Info (${pdfString(identifier)}) " +
            "/DestOutputProfile $iccObjectNumber 0 R"

    /** The Info dictionary body (without `<< >>`); every entry is mirrored in the XMP. */
    fun infoDict(mode: Mode): String =
        "/Title (${pdfString(mode.title)}) /Producer (${pdfString(PRODUCER)}) " +
            "/Creator (${pdfString(PRODUCER)}) " +
            "/CreationDate (${infoDate(mode.createdAt, mode.timeZone)}) " +
            "/ModDate (${infoDate(mode.modifiedAt, mode.timeZone)})"

    /** Escapes a text string for a PDF `(...)` literal (UTF-16BE with BOM when not Latin-1). */
    fun pdfString(value: String): String {
        val latin1 = value.all { it.code in 0x20..0xFF && it != '\n' && it != '\r' }
        if (latin1) {
            return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        }
        val bytes = value.toByteArray(Charsets.UTF_16BE)
        val sb = StringBuilder(6 + bytes.size * 2)
        sb.append("<FEFF")
        bytes.forEach { b -> sb.append("%02X".format(b.toInt() and 0xFF)) }
        sb.append('>')
        return sb.toString()
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
