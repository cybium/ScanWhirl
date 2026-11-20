package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertFalse
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
 * H3-1 (honesty): a PDF/A-2b file may not carry a non-embedded font (ISO 19005-2 6.2.11.3.1),
 * and every Info dictionary entry must be mirrored in XMP (6.7.3). The archive copy is therefore
 * written image-only — the recognised text stays in the app, not in the archive.
 *
 * veraPDF validation of the produced files is a host step (see the lane checkpoint); these
 * assertions are the byte-level guarantees that run in the gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfAConformanceTest {

    private lateinit var app: Application
    private lateinit var dir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        dir = File(app.cacheDir, "pdfa_conformance").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun icc(): ByteArray = app.resources.openRawResource(R.raw.srgb).use { it.readBytes() }

    private fun mode(title: String = "Archive") = PdfA.Mode(
        iccProfile = icc(),
        title = title,
        createdAt = 1_760_000_000_000L,
        modifiedAt = 1_760_000_000_000L,
        documentId = PdfA.documentId(title, 1_760_000_000_000L),
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    )

    private fun image(): File =
        File(dir, "page.jpg").apply {
            writeBytes(ImportFixtures.image(width = 200, height = 300))
        }

    @Test
    fun `a text layer offered to the archive is left out, so every font in it is embedded`() {
        val out = File(dir, "archive.pdf")
        PdfGenerator.imagesToPdf(
            images = listOf(image()),
            outFile = out,
            textLayers = mapOf(
                0 to OcrPage(
                    200, 300,
                    listOf(OcrLine("Invoice 4711", 10, 20, 190, 40))
                )
            ),
            pdfa = mode()
        )

        val text = String(out.readBytes(), Charsets.ISO_8859_1)
        assertTrue("still a PDF/A archive", text.contains("<pdfaid:part>2</pdfaid:part>"))
        assertFalse("the non-embedded base-14 font must not ship", text.contains("/BaseFont"))
        assertFalse("no invisible text may ship", text.contains("/ActualText"))
        assertFalse("no text-showing operator may ship", text.contains("BT /F1"))
        assertTrue("the page image is still there", text.contains("/Im0 Do"))
    }

    @Test
    fun `every Info key has an XMP counterpart with the same value`() {
        val info = PdfA.infoDict(mode("Invoice 4711"))
        val xmp = PdfA.xmp(mode("Invoice 4711"))

        // Info: /Title /Producer /Creator /CreationDate /ModDate
        assertTrue("Title", info.contains("/Title (Invoice 4711)"))
        assertTrue("Title in XMP", xmp.contains(">Invoice 4711<"))
        assertTrue("Producer", info.contains("/Producer (${PdfA.PRODUCER})"))
        assertTrue("Producer in XMP", xmp.contains("<pdf:Producer>${PdfA.PRODUCER}</pdf:Producer>"))
        assertTrue("Creator", info.contains("/Creator (${PdfA.PRODUCER})"))
        assertTrue(
            "H3-1: /Creator must be mirrored as xmp:CreatorTool",
            xmp.contains("<xmp:CreatorTool>${PdfA.PRODUCER}</xmp:CreatorTool>")
        )
        assertTrue("CreationDate", info.contains("/CreationDate ("))
        assertTrue("CreationDate in XMP", xmp.contains("<xmp:CreateDate>2025-10-09T14:23:20+05:30</xmp:CreateDate>"))
        assertTrue("ModDate", info.contains("/ModDate ("))
        assertTrue("ModDate in XMP", xmp.contains("<xmp:ModifyDate>2025-10-09T14:23:20+05:30</xmp:ModifyDate>"))
    }
}
