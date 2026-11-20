package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * S4B (independent re-review, MAJOR 2): the two structural limits are documented, shared constants
 * that the reader lane (S4A, PdfDecryptor) must match — 100 000 xref rows and nesting depth 64 —
 * and PdfEncryptor refuses a source beyond them BEFORE it writes anything.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfEncryptorLimitsTest {

    private fun tempDir(name: String): File {
        val d = File(File(java.io.File.separator, "tmp"), name)
        d.mkdirs()
        return d
    }

    /**
     * A valid single-page PDF whose xref table has [rows] rows (only 1..3 are live) and whose
     * catalog carries [nest] nested arrays. Deterministic and exactly at the boundary.
     */
    private fun sparsePdf(rows: Int, nest: Int): String {
        val nesting = "[".repeat(nest) + "]".repeat(nest)
        val body = StringBuilder("%PDF-1.7\n")
        val offsets = IntArray(4)
        fun obj(number: Int, text: String) {
            offsets[number] = body.length
            body.append(text)
        }
        obj(
            1,
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R" +
                (if (nest > 0) " /Nest $nesting" else "") + " >>\nendobj\n"
        )
        obj(2, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        obj(3, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n")

        val xrefAt = body.length
        body.append("xref\n0 ").append(rows).append('\n')
        body.append("0000000000 65535 f \n")
        for (n in 1 until rows) {
            if (n <= 3) {
                body.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[n]))
            } else {
                body.append("0000000000 00000 f \n")
            }
        }
        body.append("trailer\n<< /Size ").append(rows).append(" /Root 1 0 R >>\n")
        body.append("startxref\n").append(xrefAt).append("\n%%EOF\n")
        return body.toString()
    }

    @Test
    fun `the limits are the shared contract with the reading lane`() {
        assertEquals("max xref rows", 100_000, PdfEncryptor.MAX_XREF_ROWS)
        assertEquals("max nesting depth", 64, PdfEncryptor.MAX_NESTING)
    }

    @Test
    fun `a source at the xref row limit is refused and nothing is written`() {
        val dir = tempDir("pdfenc-xref-limit")
        val src = File(dir, "src.pdf")
        src.writeText(sparsePdf(PdfEncryptor.MAX_XREF_ROWS, nest = 0), Charsets.ISO_8859_1)
        assertTrue("the fixture has a real table", src.length() > 2_000_000)
        val dst = File(dir, "enc.pdf")

        val refused = runCatching { PdfEncryptor.encrypt(src, dst, "pw1234") }

        assertTrue("a source at the limit must be refused, got ${refused.exceptionOrNull()}", refused.isFailure)
        assertEquals("Not a PDF ScanWhirl can protect", refused.exceptionOrNull()?.message)
        assertFalse("nothing was published", dst.exists())
        assertFalse(
            "no temporary output was created",
            dir.list().orEmpty().any { it.startsWith("protect-") }
        )
    }

    @Test
    fun `nesting beyond the limit is refused before anything is written`() {
        val dir = tempDir("pdfenc-nest-over")
        val src = File(dir, "src.pdf").apply {
            writeText(sparsePdf(rows = 100, nest = PdfEncryptor.MAX_NESTING + 1), Charsets.ISO_8859_1)
        }
        val dst = File(dir, "enc.pdf")

        val refused = runCatching { PdfEncryptor.encrypt(src, dst, "pw1234") }

        assertTrue("too deep must be refused, got ${refused.exceptionOrNull()}", refused.isFailure)
        assertEquals("Not a PDF ScanWhirl can protect", refused.exceptionOrNull()?.message)
        assertFalse("nothing was published", dst.exists())
        assertFalse(dir.list().orEmpty().any { it.startsWith("protect-") })
    }

    @Test
    fun `nesting at the limit is still protected`() {
        val dir = tempDir("pdfenc-nest-ok")
        val src = File(dir, "src.pdf").apply {
            writeText(sparsePdf(rows = 100, nest = PdfEncryptor.MAX_NESTING), Charsets.ISO_8859_1)
        }
        val dst = File(dir, "enc.pdf")

        PdfEncryptor.encrypt(src, dst, "pw1234")

        assertTrue("the file is published", dst.isFile && dst.length() > 0)
        assertEquals(true, PdfProtection.isEncrypted(dst))
        assertTrue("still a 1.7 file", dst.readText(Charsets.ISO_8859_1).startsWith("%PDF-1.7"))
        assertTrue("the objects are indexed", PdfEncryptor.objects(dst.readBytes()).size >= 3)
    }

    @Test
    fun `a small source is still protected after the limit checks`() {
        val dir = tempDir("pdfenc-small")
        val jpeg = File(dir, "a.jpg").apply { writeBytes(ImportFixtures.image(width = 400, height = 600)) }
        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpeg), src)
        val dst = File(dir, "enc.pdf")

        PdfEncryptor.encrypt(src, dst, "pw1234")

        assertEquals(true, PdfProtection.isEncrypted(dst))
        assertTrue(dst.length() > src.length())
    }
}
