package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * WP-05: the verifier reads structure FROM the file (xref, Kids, Count, Length),
 * so each test breaks exactly one thing in a REAL generated PDF while keeping the
 * file length (and therefore every other offset) intact, and expects rejection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfVerifierTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun jpegs(count: Int, w: (Int) -> Int = { 120 + it }, h: Int = 80): List<File> =
        (0 until count).map { i ->
            File(tmp.root, "p$i.jpg").apply { writeBytes(ImportFixtures.image(width = w(i), height = h)) }
        }

    private fun pdfOf(images: List<File>, layers: Map<Int, OcrPage> = emptyMap()): File =
        File(tmp.root, "t${System.nanoTime()}.pdf").also { PdfGenerator.imagesToPdf(images, it, layers) }

    /** Replaces exactly one occurrence of [find] with a same-length [replace]. */
    private fun mutated(source: File, find: String, replace: String): File {
        assertEquals("mutation must preserve length", find.length, replace.length)
        val text = source.readText(Charsets.ISO_8859_1)
        val at = text.indexOf(find)
        assertTrue("'$find' not found", at >= 0)
        assertEquals("'$find' must be unique", -1, text.indexOf(find, at + 1))
        val out = File(tmp.root, "m${System.nanoTime()}.pdf")
        out.writeBytes((text.substring(0, at) + replace + text.substring(at + find.length)).toByteArray(Charsets.ISO_8859_1))
        return out
    }

    private fun assertRejected(file: File, pages: Int? = null, images: List<File>? = null, containing: String? = null) {
        try {
            PdfVerifier.verify(file, pages, images)
            fail("verifier accepted a damaged PDF")
        } catch (e: PdfVerifier.InvalidPdfException) {
            if (containing != null) assertTrue(e.message, e.message!!.contains(containing))
        }
    }

    @Test fun `valid pdfs of 1 5 and 30 pages pass with byte level page comparison`() {
        for (n in listOf(1, 5, 30)) {
            val images = jpegs(n)
            PdfVerifier.verify(pdfOf(images), expectedImages = images)
        }
    }

    @Test fun `more than 200 small pages verify - no artificial page ceiling`() {
        val images = jpegs(260, w = { 16 }, h = 16)
        val pdf = pdfOf(images)
        PdfVerifier.verify(pdf, expectedImages = images)
        assertRejected(pdf, pages = 259, containing = "Page count mismatch")
    }

    @Test fun `text layer pdf verifies`() {
        val images = jpegs(2)
        val layer = OcrPage(120, 80, listOf(OcrLine("Hello (world) é", 10, 20, 50, 30)))
        PdfVerifier.verify(pdfOf(images, mapOf(0 to layer)), expectedImages = images)
    }

    @Test fun `empty missing and garbage files are rejected`() {
        assertRejected(File(tmp.root, "none.pdf"), pages = 1)
        assertRejected(File(tmp.root, "empty.pdf").apply { writeBytes(ByteArray(0)) }, pages = 1)
        assertRejected(File(tmp.root, "junk.pdf").apply { writeBytes(ByteArray(4096) { (it % 199).toByte() }) }, pages = 1)
    }

    @Test fun `truncated pdf is rejected at any cut point`() {
        val images = jpegs(3)
        val pdf = pdfOf(images)
        val bytes = pdf.readBytes()
        for (cut in listOf(1, 9, 200, bytes.size / 2, bytes.size - 100)) {
            assertRejected(File(tmp.root, "cut$cut.pdf").apply { writeBytes(bytes.copyOf(bytes.size - cut)) }, pages = 3)
        }
    }

    @Test fun `wrong page count is rejected`() {
        val images = jpegs(3)
        assertRejected(pdfOf(images), pages = 4, containing = "Page count mismatch")
    }

    @Test fun `startxref pointing at the wrong place is rejected`() {
        val images = jpegs(2)
        val pdf = pdfOf(images)
        val text = pdf.readText(Charsets.ISO_8859_1)
        val offset = Regex("startxref\\n(\\d+)\\n").find(text)!!.groupValues[1]
        val wrong = (offset.toInt() - 3).toString().padStart(offset.length, '0')
        assertRejected(mutated(pdf, "startxref\n$offset\n", "startxref\n$wrong\n"), pages = 2)
    }

    @Test fun `xref entry that does not point at its object is rejected`() {
        val images = jpegs(2)
        val pdf = pdfOf(images)
        val text = pdf.readText(Charsets.ISO_8859_1)
        val xref = text.lastIndexOf("xref\n0 ")
        val header = text.indexOf('\n', text.indexOf('\n', xref) + 1) + 1
        val entry3 = text.substring(header + 3 * 20, header + 3 * 20 + 10)
        val bumped = (entry3.toInt() + 1).toString().padStart(10, '0')
        assertRejected(mutated(pdf, "$entry3 00000 n", "$bumped 00000 n"), pages = 2)
    }

    @Test fun `kids cardinality that disagrees with count is rejected`() {
        val images = jpegs(3)
        val pdf = pdfOf(images)
        // /Count 3 stays, but /Kids loses an entry (length preserved with spaces).
        assertRejected(mutated(pdf, "9 0 R]", "     ]"), pages = 3, containing = "/Kids has 2 entries but /Count is 3")
    }

    @Test fun `duplicate kid is rejected`() {
        val images = jpegs(3)
        val pdf = pdfOf(images)
        assertRejected(mutated(pdf, "6 0 R 9 0 R]", "3 0 R 9 0 R]"), pages = 3, containing = "listed twice")
    }

    @Test fun `count that disagrees with the page files is rejected`() {
        val images = jpegs(3)
        val pdf = pdfOf(images)
        assertRejected(mutated(pdf, "/Count 3", "/Count 2"), images = images)
    }

    @Test fun `image stream length that is off by one in either direction is rejected`() {
        val images = jpegs(2)
        val pdf = pdfOf(images)
        val len = images[0].length()
        assertRejected(mutated(pdf, "/Length $len >>", "/Length ${len - 1} >>"), pages = 2)
        val longer = pdfOf(images)
        assertRejected(mutated(longer, "/Length $len >>", "/Length ${len + 1} >>"), pages = 2)
    }

    @Test fun `content stream length that is wrong is rejected`() {
        val images = jpegs(1)
        val pdf = pdfOf(images)
        val text = pdf.readText(Charsets.ISO_8859_1)
        val declared = Regex("<< /Length (\\d+) >>\\nstream\\nq").find(text)!!.groupValues[1]
        val wrong = (declared.toInt() - 1).toString()
        // Same digit count only: keep the file length so no offset moves.
        assertEquals(declared.length, wrong.length)
        assertRejected(mutated(pdf, "<< /Length $declared >>\nstream\nq", "<< /Length $wrong >>\nstream\nq"), pages = 1)
        // ... and one byte too long (swallows the 'e' of endstream).
        val longer = (declared.toInt() + 1).toString()
        assertEquals(declared.length, longer.length)
        assertRejected(mutated(pdf, "<< /Length $declared >>\nstream\nq", "<< /Length $longer >>\nstream\nq"), pages = 1)
    }

    @Test fun `content stream that does not draw its image is rejected`() {
        val images = jpegs(1)
        assertRejected(mutated(pdfOf(images), "/Im0 Do", "/Im9 Do"), pages = 1, containing = "does not draw")
    }

    @Test fun `corrupted image bytes are caught by the byte comparison but not by structure alone`() {
        val images = jpegs(2)
        val pdf = pdfOf(images)
        val bytes = pdf.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        val dataStart = text.indexOf("/DCTDecode /Length") .let { text.indexOf("stream\n", it) + 7 }
        bytes[dataStart + 40] = (bytes[dataStart + 40].toInt() xor 0x55).toByte()
        val corrupted = File(tmp.root, "flip.pdf").apply { writeBytes(bytes) }
        PdfVerifier.verify(corrupted, expectedPages = 2) // structurally sound
        assertRejected(corrupted, images = images, containing = "differs")
    }

    @Test fun `trailer size that disagrees with the xref is rejected`() {
        val images = jpegs(1)
        val pdf = pdfOf(images)
        val text = pdf.readText(Charsets.ISO_8859_1)
        val size = Regex("/Size (\\d+) /Root").find(text)!!.groupValues[1]
        val wrong = (size.toInt() + 1).toString()
        assertEquals(size.length, wrong.length)
        assertRejected(mutated(pdf, "/Size $size /Root", "/Size $wrong /Root"), pages = 1, containing = "/Size")
    }

    @Test fun `generator never publishes a pdf it could not verify`() {
        val images = jpegs(2)
        val out = File(tmp.root, "keep.pdf").apply { writeText("previous good content") }
        // A page that is not a JPEG fails before anything is touched.
        val bad = File(tmp.root, "bad.jpg").apply { writeBytes(ByteArray(2048) { (it % 251).toByte() }) }
        try {
            PdfGenerator.imagesToPdf(images + bad, out)
            fail("expected failure")
        } catch (_: Exception) {
        }
        assertEquals("previous good content", out.readText())
        assertTrue(!File(tmp.root, "keep.pdf.tmp").exists())
    }

    @Test fun `generator surfaces a required fsync failure and leaves the old file`() {
        val images = jpegs(1)
        val out = File(tmp.root, "keep2.pdf").apply { writeText("previous good content") }
        Durable.beforeFileSync = { throw java.io.IOException("injected fsync failure") }
        try {
            PdfGenerator.imagesToPdf(images, out)
            fail("expected SyncException")
        } catch (e: Durable.SyncException) {
            assertTrue(e.message!!.contains("injected"))
        } finally {
            Durable.resetForTests()
        }
        assertEquals("previous good content", out.readText())
        assertTrue(!File(tmp.root, "keep2.pdf.tmp").exists())
    }
}
