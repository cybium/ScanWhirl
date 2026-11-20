package com.scanwhirl.app.util

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Locale

class PdfProtectInteropTest {
    @get:Rule val tmp = TemporaryFolder()
    private val latin = Charsets.ISO_8859_1
    private val title = "Aadhaar - FAKE 9999"
    private val note = "Fictional note (read only)"
    private val form = "Fictional form 1230"
    private val page = "Fictional page text"
    private val password = "test fixture password"

    private fun imported(
        generation: Int = 0,
        xrefStream: Boolean = false,
        previous: Boolean = false,
        badOffset: Boolean = false,
        badHeader: Boolean = false,
        extraSection: Boolean = false,
        extraObject: Boolean = false,
        extraDict: String = "",
        contentDict: String = ""
    ): String {
        val content = "BT /F1 12 Tf 30 200 Td ($page) Tj ET"
        val hexForm = PdfAes.hex(form.toByteArray(latin)).dropLast(1) // odd digit is padded with zero
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [8 0 R] >> >>",
            "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R /Annots [7 0 R] >>",
            "<< /Length ${content.length} $contentDict >>\r\nstream\r\n$content\r\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Title ($title) $extraDict >>",
            "<< /Type /Annot /Subtype /Text /Rect [0 0 10 10] /Contents (Fictional note \\050read only\\051) >>",
            "<< /FT /Tx /T (fixture) /V <$hexForm> >>",
            "<< /Value <4 1 2> /Empty () /Nested [(a(b)c) (line\r\nbreak) (a\\\r\nb)] >>"
        )
        return buildString {
            append("%PDF-1.7\r\n%âãÏÓ\r\n")
            val offsets = objects.mapIndexed { i, body ->
                length.also { append("${if (badHeader && i == 5) 88 else i + 1} ${if (i == 5) generation else 0} obj\r\n$body\r\nendobj\r\n") }
            }
            if (extraObject) append("77 0 obj\r\n($title)\r\nendobj\r\n")
            val xref = length
            if (xrefStream) {
                append("10 0 obj\n<< /Type /XRef /Size 11 /W [1 2 1] /Root 1 0 R /Length 0 >>\nstream\n\nendstream\nendobj\n")
            } else {
                append("xref\r\n0 ${if (extraSection) 4 else objects.size + 1}\r\n0000000000 65535 f\r\n")
                offsets.forEachIndexed { i, off ->
                    if (extraSection && i == 3) append("4 ${objects.size - 3}\r\n")
                    val offset = if (badOffset && i == 5) 4294967296L + off else off.toLong()
                    append(String.format(Locale.ROOT, "%010d %05d n\r\n", offset, if (i == 5) generation else 0))
                }
                append("trailer\r\n<< /Size ${objects.size + 1} /Root 1 0 R /Info 6 $generation R")
                if (previous) append(" /Pr#65v $xref")
                append(" >>\r\n")
            }
            append("startxref\r\n$xref\r\n%%EOF\r\n")
        }
    }

    @Test fun `imported strings and page text are confidential and round trip through PDFBox`() {
        val src = tmp.newFile("imported.pdf").apply { writeText(imported(generation = 3), latin) }
        val dst = tmp.root.resolve("protected.pdf")
        val original = src.readBytes()
        Loader.loadPDF(src).use { assertEquals(title, it.documentInformation.title) }
        PdfEncryptor.encrypt(src, dst, password)
        val raw = dst.readText(latin)
        for (secret in listOf(title, "Fictional note", form, PdfAes.hex(form.toByteArray(latin)), page)) {
            assertFalse("Plain PDF bytes include fixture string: $secret", raw.contains(secret, ignoreCase = true))
        }
        assertTrue(Regex("(?m)^\\d{10} 00003 n").containsMatchIn(raw))
        assertTrue(raw.contains("6 3 obj\n"))
        assertThrows(InvalidPasswordException::class.java) { Loader.loadPDF(dst, "not the password").close() }
        Loader.loadPDF(dst, password).use {
            assertEquals(title, it.documentInformation.title)
            assertEquals(note, it.getPage(0).annotations.single().contents)
            assertEquals(form, it.documentCatalog.acroForm.getField("fixture").valueAsString)
            assertEquals(page, PDFTextStripper().getText(it).trim())
        }
        assertArrayEquals(original, src.readBytes())
    }

    @Test fun `unsupported or inconsistent structures publish nothing and preserve the source`() {
        val fixtures = listOf(
            imported(xrefStream = true), imported(previous = true), imported(extraSection = true),
            imported(badOffset = true), imported(badHeader = true), imported(extraObject = true),
            imported(extraDict = "/Type /Obj#53tm"), imported(contentDict = "/Filter [/Cr#79pt]"),
            imported(extraDict = "/Type /Sig /ByteRange [0 1 2 3] /Contents (signature)")
        )
        for ((i, input) in fixtures.withIndex()) {
            val src = tmp.newFile("source-$i.pdf").apply { writeText(input, latin) }
            val dst = tmp.root.resolve("dest-$i.pdf")
            val before = src.readBytes()
            assertThrows("fixture $i", IOException::class.java) { PdfEncryptor.encrypt(src, dst, password) }
            assertArrayEquals("fixture $i", before, src.readBytes())
            assertFalse(dst.exists())
            assertFalse(tmp.root.resolve("dest-$i.pdf.tmp").exists())
        }
    }
}
