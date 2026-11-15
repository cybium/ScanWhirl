package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** v2 UI: 03_tools_hub — PDF/A-2b metadata: XMP values, dates, IDs and string escaping. */
class PdfATest {

    private val zone = TimeZone.getTimeZone("Asia/Kolkata")
    private val at = 1_760_000_000_000L // 2025-10-09T08:53:20Z = 14:23:20 +05:30

    private fun mode(title: String = "Scan 1") = PdfA.Mode(
        iccProfile = ByteArray(200) { 1 },
        title = title,
        createdAt = at,
        modifiedAt = at,
        documentId = PdfA.documentId(title, at),
        timeZone = zone
    )

    @Test
    fun `xmp declares PDF-A-2 level B`() {
        val xmp = PdfA.xmp(mode())
        assertTrue(xmp.startsWith("<?xpacket begin="))
        assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>"))
        assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"))
        assertTrue(xmp.contains("xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\""))
        assertTrue(xmp.contains("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Scan 1</rdf:li>"))
        assertTrue(xmp.trimEnd().endsWith("<?xpacket end=\"w\"?>"))
    }

    @Test
    fun `xmp dates and the info dates describe the same instant`() {
        val xmp = PdfA.xmp(mode())
        val info = PdfA.infoDict(mode())
        // 1_760_000_000_000 ms in +05:30 is 2025-10-09T14:23:20+05:30.
        assertTrue("xmp: $xmp", xmp.contains("<xmp:CreateDate>2025-10-09T14:23:20+05:30</xmp:CreateDate>"))
        assertTrue("xmp: $xmp", xmp.contains("<xmp:ModifyDate>2025-10-09T14:23:20+05:30</xmp:ModifyDate>"))
        assertTrue("info: $info", info.contains("/CreationDate (D:20251009142320+05'30')"))
        assertTrue("info: $info", info.contains("/ModDate (D:20251009142320+05'30')"))
    }

    @Test
    fun `producer and title are the same in XMP and the Info dictionary`() {
        val xmp = PdfA.xmp(mode("Invoice 4711"))
        val info = PdfA.infoDict(mode("Invoice 4711"))
        assertTrue(xmp.contains("<pdf:Producer>${PdfA.PRODUCER}</pdf:Producer>"))
        assertTrue(info.contains("/Producer (${PdfA.PRODUCER})"))
        assertTrue(xmp.contains(">Invoice 4711<"))
        assertTrue(info.contains("/Title (Invoice 4711)"))
    }

    @Test
    fun `a negative time zone keeps its sign`() {
        val west = TimeZone.getTimeZone("America/New_York")
        val info = PdfA.infoDate(at, west)
        val xmp = PdfA.xmpDate(at, west)
        assertTrue(info, info.contains("-"))
        assertTrue(xmp, xmp.contains("-"))
        assertTrue("xmp offset: $xmp", xmp.endsWith("-04:00") || xmp.endsWith("-05:00"))
    }

    @Test
    fun `the document id is 32 lowercase hex characters`() {
        val id = PdfA.documentId("Scan 1", at)
        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
        assertEquals("the id is stable", id, PdfA.documentId("Scan 1", at))
        assertFalse("a different document gets a different id", id == PdfA.documentId("Scan 2", at))
    }

    @Test
    fun `the output intent names the sRGB profile`() {
        val dict = PdfA.outputIntentDict(3)
        assertTrue(dict.contains("/S /GTS_PDFA1"))
        assertTrue(dict.contains("/DestOutputProfile 3 0 R"))
        assertTrue(dict.contains("/OutputConditionIdentifier (sRGB IEC61966-2.1)"))
    }

    @Test
    fun `text strings escape PDF syntax and use UTF-16 for Devanagari`() {
        assertEquals("a \\(b\\) c\\\\d", PdfA.pdfString("a (b) c\\d"))
        assertEquals("<FEFF091509410932>", PdfA.pdfString("कुल"))
        assertEquals("plain", PdfA.pdfString("plain"))
    }

    @Test
    fun `xml special characters in the title are escaped`() {
        val xmp = PdfA.xmp(mode("A & B <c>"))
        assertTrue(xmp.contains("A &amp; B &lt;c&gt;"))
        assertFalse(xmp.contains("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">A & B"))
    }
}
