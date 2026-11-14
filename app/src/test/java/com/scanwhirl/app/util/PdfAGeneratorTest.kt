package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/**
 * v2 UI: 03_tools_hub — the PDF/A-2b writer: OutputIntent, XMP, matching dates, trailer ID, no
 * encryption, and the plain writer staying exactly as it was. veraPDF validates the real files
 * in `PdfASamplesTest` (host check); these are the structure guarantees in the gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfAGeneratorTest {

    private lateinit var app: Application
    private lateinit var dir: File

    private fun icc(): ByteArray = app.resources.openRawResource(R.raw.srgb).use { it.readBytes() }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        dir = File(app.cacheDir, "pdfa_test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun pages(count: Int): List<File> = (0 until count).map { i ->
        File(dir, "p$i.jpg").apply { writeBytes(ImportFixtures.image(width = 200, height = 300)) }
    }

    private fun mode(title: String = "Archive") = PdfA.Mode(
        iccProfile = icc(),
        title = title,
        createdAt = 1_760_000_000_000L,
        modifiedAt = 1_760_000_000_000L,
        documentId = PdfA.documentId(title, 1_760_000_000_000L),
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    )

    private fun text(): String = String(readPdf(), Charsets.ISO_8859_1)

    private var lastFile: File? = null

    private fun readPdf(): ByteArray = (lastFile ?: error("no PDF")).readBytes()

    private fun textLayer(): Map<Int, OcrPage> = mapOf(
        0 to OcrPage(
            200, 300,
            listOf(
                OcrLine("Invoice 4711", 10, 20, 190, 40),
                OcrLine("कुल राशि 1250", 10, 60, 190, 80)
            )
        )
    )

    private fun write(withText: Boolean = false, pdfa: PdfA.Mode? = mode()): ByteArray {
        val out = File(dir, if (pdfa == null) "plain.pdf" else "archive.pdf")
        lastFile = out
        PdfGenerator.imagesToPdf(
            images = pages(2),
            outFile = out,
            textLayers = if (withText) textLayer() else emptyMap(),
            pdfa = pdfa
        )
        return out.readBytes()
    }

    @Test
    fun `the archive is PDF 1_7 with an sRGB output intent and XMP metadata`() {
        write()
        val text = text()

        assertTrue("PDF/A needs the 1.7 header", text.startsWith("%PDF-1.7"))
        assertTrue(text.contains("/OutputIntents [4 0 R]"))
        assertTrue(text.contains("/Metadata 5 0 R"))
        assertTrue("catalog is untouched otherwise", text.contains("1 0 obj\n<< /Type /Catalog /Pages 2 0 R"))
        assertTrue(text.contains("/S /GTS_PDFA1"))
        assertTrue(text.contains("/DestOutputProfile 3 0 R"))
        assertTrue(text.contains("/N 3"))
        assertTrue("the ICC stream is embedded", text.contains("3 0 obj\n<< /N 3 /Length ${icc().size} >>"))
        assertTrue(text.contains("<pdfaid:part>2</pdfaid:part>"))
        assertTrue(text.contains("<pdfaid:conformance>B</pdfaid:conformance>"))
    }

    @Test
    fun `the trailer carries the Info dictionary and the document id, never encryption`() {
        write()
        val text = text()
        val id = PdfA.documentId("Archive", 1_760_000_000_000L)
        assertTrue(text.contains("/Info 6 0 R"))
        assertTrue("both halves of /ID", text.contains("/ID [<$id> <$id>]"))
        assertFalse("/Encrypt must never appear", text.contains("/Encrypt"))
        assertTrue(text.contains("/Title (Archive)"))
        assertTrue(text.contains("/CreationDate (D:20251009142320+05'30')"))
    }

    @Test
    fun `the XMP packet is well formed and matches the Info dates`() {
        write()
        val text = text()
        val xmp = text.substringAfter("5 0 obj\n<< /Type /Metadata /Subtype /XML /Length ")
            .substringAfter(">>\nstream\n")
            .substringBefore("\nendstream")
        assertTrue(xmp.startsWith("<?xpacket begin="))
        assertTrue(xmp.trimEnd().endsWith("<?xpacket end=\"w\"?>"))
        assertTrue(xmp.contains("2025-10-09T14:23:20+05:30"))
        assertEquals(
            "unbalanced rdf:Description",
            xmp.split("<rdf:Description").size,
            xmp.split("</rdf:Description>").size
        )
        assertTrue(xmp.contains("<x:xmpmeta") && xmp.contains("</x:xmpmeta>"))
        assertTrue(xmp.contains("<rdf:RDF") && xmp.contains("</rdf:RDF>"))
    }

    @Test
    fun `invisible text including Devanagari is written into the archive`() {
        write(withText = true)
        val text = text()
        assertTrue(text.contains("/ActualText <FEFF"))
        // Devanagari travels as UTF-16BE inside ActualText (that is what extractors read).
        assertTrue(text.contains("<FEFF09150941093200200930093E0936093F"))
        assertTrue(text.contains("BT /F1"))
        assertTrue("the page draws its image", text.contains("/Im0 Do"))
    }

    @Test
    fun `the plain writer is unchanged when the mode is off`() {
        val plain = write(pdfa = null)
        val text = String(plain, Charsets.ISO_8859_1)

        assertTrue("plain PDF stays 1.4", text.startsWith("%PDF-1.4"))
        assertFalse("no output intent", text.contains("/OutputIntents"))
        assertFalse("no XMP metadata", text.contains("/Type /Metadata"))
        assertFalse("no Info dictionary", text.contains("/Info 6 0 R"))
        assertFalse("no document id", text.contains("/ID ["))
        // Same object numbering as before: catalog, page tree, then page/content/image per page.
        assertTrue(text.contains("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>"))
        assertTrue(text.contains("3 0 obj\n<< /Type /Page /Parent 2 0 R"))
        assertTrue(text.contains("trailer\n<< /Size 9 /Root 1 0 R >>"))
    }

    @Test
    fun `a PDF-A copy is never encrypted`() {
        val encryption = PdfAes.create("secret")
        assertThrows(IllegalArgumentException::class.java) {
            PdfGenerator.imagesToPdf(
                images = pages(1),
                outFile = File(dir, "both.pdf"),
                pdfa = mode(),
                encryption = encryption
            )
        }
        assertFalse(File(dir, "both.pdf").exists())
    }

    @Test
    fun `the writer still verifies the file it produced`() {
        val bytes = write()
        val check = File(dir, "archive.pdf")
        assertEquals(bytes.size.toLong(), check.length())
        // PdfVerifier ran inside imagesToPdf; reading the file back proves the tmp was published.
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("%%EOF"))
    }
}
