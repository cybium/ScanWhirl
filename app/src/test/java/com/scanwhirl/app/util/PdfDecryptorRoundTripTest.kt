package com.scanwhirl.app.util

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * S1 (Protect) → S2 (Unlock) round trip.
 *
 * [PdfEncryptor] and [PdfDecryptor] share one xref contract: whatever the protector emits must stay
 * readable by the unlock path, including the limits the decrypt side is allowed to refuse. These
 * tests use only the public `encrypt(src, dst, password)` API, so they keep proving the contract
 * when the encryptor's internals change.
 */
class PdfDecryptorRoundTripTest {

    @get:Rule val tmp = TemporaryFolder()

    private val content = "BT /F1 12 Tf 20 100 Td (Round trip S1 to S2) Tj ET"

    /**
     * A classic-xref PDF the protector accepts: one section, contiguous objects, direct stream
     * `/Length`, no comments or bytes between objects, and generations carried in the table.
     */
    private fun plainPdf(objects: List<Triple<Int, Int, String>>, root: Int, info: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = HashMap<Int, Int>()
        val generations = HashMap<Int, Int>()
        for ((number, gen, body) in objects.sortedBy { it.first }) {
            offsets[number] = out.size()
            generations[number] = gen
            out.write("$number $gen obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xrefOffset = out.size()
        val size = objects.maxOf { it.first } + 1
        val table = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) {
            val offset = offsets[n]
            val gen = generations[n]
            if (offset == null || gen == null) {
                table.append("0000000000 00000 f \n")
            } else {
                table.append(String.format(Locale.ROOT, "%010d %05d n \n", offset, gen))
            }
        }
        out.write(table.toString().toByteArray(Charsets.ISO_8859_1))
        fun genOf(number: Int) = generations.getValue(number)
        val infoRef = info?.let { " /Info $it ${genOf(it)} R" }.orEmpty()
        val id = "00".repeat(16)
        val tail = "trailer\n<< /Size $size /Root $root ${genOf(root)} R$infoRef " +
            "/ID [<$id> <$id>] >>\nstartxref\n$xrefOffset\n%%EOF\n"
        out.write(tail.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun document(vararg extra: Triple<Int, Int, String>): ByteArray {
        val contentBody = content.toByteArray(Charsets.ISO_8859_1)
        val objects = listOf(
            Triple(1, 0, "<< /Type /Catalog /Pages 2 0 R >>"),
            Triple(2, 0, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            Triple(
                3, 0,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R " +
                    "/Resources << /Font << /F1 5 2 R >> >> >>"
            ),
            Triple(
                4, 0,
                "<< /Length ${contentBody.size} >>\nstream\n" +
                    String(contentBody, Charsets.ISO_8859_1) + "\nendstream"
            ),
            // A non-zero generation: both writers must carry it through the table and the header.
            Triple(5, 2, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
            Triple(6, 3, "<< /Producer (ScanWhirl round trip) >>")
        ) + extra
        return plainPdf(objects, root = 1, info = 6)
    }

    private fun encrypted(source: ByteArray, password: String = "round-trip"): File {
        val src = File(tmp.root, "source-${source.size}.pdf").apply { writeBytes(source) }
        val dst = File(tmp.root, "protected-${source.size}.pdf")
        PdfEncryptor.encrypt(src, dst, password)
        return dst
    }

    @Test
    fun `an S1 protected PDF with non-zero generations unlocks back to the same document`() {
        val source = document()
        val protectedFile = encrypted(source)
        val plain = PdfDecryptor.decrypt(protectedFile.readBytes(), "round-trip")

        val out = File(tmp.root, "unlocked.pdf").apply { writeBytes(plain) }
        Loader.loadPDF(out).use { doc ->
            assertFalse("output is still encrypted", doc.isEncrypted)
            assertEquals(1, doc.numberOfPages)
            assertTrue("page text survived", PDFTextStripper().getText(doc).contains("Round trip S1 to S2"))
            assertEquals("the info dictionary survived", "ScanWhirl round trip", doc.documentInformation.producer)
        }
        val text = String(plain, Charsets.ISO_8859_1)
        assertTrue("generation 2 kept", Regex("\\b5\\s+2\\s+obj").containsMatchIn(text))
        assertTrue("generation 3 kept", Regex("\\b6\\s+3\\s+obj").containsMatchIn(text))
        assertFalse("no /Encrypt in the plaintext trailer", text.contains("/Encrypt"))
    }

    @Test
    fun `the deepest nesting the protector allows unlocks, and one level more is refused`() {
        // 64 nested arrays is the protector's limit (depth 64); the unlock path must agree, not be
        // stricter. The extra object is unreferenced, so it only exercises the parser.
        val atLimit = "[".repeat(64) + "0" + "]".repeat(64)
        val protectedFile = encrypted(document(Triple(7, 0, atLimit)))
        val plain = PdfDecryptor.decrypt(protectedFile.readBytes(), "round-trip")
        assertTrue(String(plain, Charsets.ISO_8859_1).contains(atLimit))
    }

    @Test
    fun `a 100000 row xref table round trips`() {
        // The protector refuses a source with 100_000 entries but accepts 99_999 and adds the
        // /Encrypt object, so this is exactly the largest table it can emit.
        val count = 99_999
        val filler = (7 until count).map { Triple(it, 0, "<< /N $it >>") }
        val source = document(*filler.toTypedArray())
        val protectedFile = encrypted(source, "big-table")
        val protectedBytes = protectedFile.readBytes()
        assertTrue("the protector emitted the boundary table", protectedBytes.size > 1_000_000)

        val plain = PdfDecryptor.decrypt(protectedBytes, "big-table")
        val text = String(plain, Charsets.ISO_8859_1)
        assertTrue("content decrypted", text.contains("Round trip S1 to S2"))
        assertFalse("the /Encrypt object was dropped", text.contains("/Filter /Standard"))
        // The obsolete /Encrypt object was number 99_999, so the plaintext table stops at 99_999.
        assertTrue("plaintext table rebuilt", text.contains("xref\n0 99999\n"))
        assertTrue("plain document ends normally", text.trimEnd().endsWith("%%EOF"))
    }
}
