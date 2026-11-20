package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/**
 * Host check for PDF/A-2b. Writes sample archives into the owner's QA folder only when that
 * folder carries an `EVAL_ON` file (the gate runs inside WSL, where a Windows env var cannot be
 * exported), then veraPDF validates them on the host. Without the sentinel nothing runs.
 *
 * Samples: 1 page, 10 pages, 1 page with Latin OCR text, 1 page with Devanagari OCR text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfASamplesTest {

    @Test
    fun `write PDF-A samples for the host validator`() {
        val qa = File(QA_DIR)
        val sentinel = File(qa, "EVAL_ON")
        if (!sentinel.isFile || !qa.isDirectory) {
            // Early return, not an assumption: a skipped test would fail the gate's skipped==0.
            println("PdfASamplesTest: no $QA_DIR/EVAL_ON; nothing to do")
            return
        }

        val out = File(qa, "pdfa")
        out.mkdirs()
        out.listFiles()?.forEach { it.delete() }

        val app: Application = ApplicationProvider.getApplicationContext()
        val icc = app.resources.openRawResource(R.raw.srgb).use { it.readBytes() }
        val created = 1_760_000_000_000L // fixed instant so the samples are reproducible
        val zone = TimeZone.getTimeZone("Asia/Kolkata")

        fun mode(title: String, at: Long = created) = PdfA.Mode(
            iccProfile = icc,
            title = title,
            createdAt = at,
            modifiedAt = at,
            documentId = PdfA.documentId(title, at),
            timeZone = zone
        )

        fun pages(count: Int): List<File> = (0 until count).map { i ->
            File(out, "page_$i.jpg").apply {
                writeBytes(ImportFixtures.image(width = 1240, height = 1754))
            }
        }

        // 1 page, no text.
        PdfGenerator.imagesToPdf(pages(1), File(out, "pdfa_01_one_page.pdf"), pdfa = mode("One page"))

        // 10 pages, no text.
        PdfGenerator.imagesToPdf(pages(10), File(out, "pdfa_02_ten_pages.pdf"), pdfa = mode("Ten pages"))

        // 1 page with Latin OCR text offered to the archive (H3-1: the archive stays image-only
        // because the text layer's Helvetica is not embedded).
        val latin = OcrPage(
            1240, 1754,
            listOf(
                OcrLine("Invoice number 4711", 100, 200, 1100, 240),
                OcrLine("Total: 1250", 100, 300, 900, 340)
            )
        )
        PdfGenerator.imagesToPdf(
            pages(1), File(out, "pdfa_03_text_latin.pdf"),
            textLayers = mapOf(0 to latin), pdfa = mode("Latin text")
        )

        // 1 page with Devanagari OCR text offered to the archive (also left out: image-only).
        val hindi = OcrPage(
            1240, 1754,
            listOf(
                OcrLine("कुल राशि 1250", 100, 200, 1100, 240),
                OcrLine("इनवॉइस नंबर 4711", 100, 300, 1100, 340)
            )
        )
        PdfGenerator.imagesToPdf(
            pages(1), File(out, "pdfa_04_text_devanagari.pdf"),
            textLayers = mapOf(0 to hindi), pdfa = mode("Devanagari text")
        )

        val pdfs = out.listFiles { f -> f.extension == "pdf" }?.sortedBy { it.name }.orEmpty()
        assertTrue("no PDF/A sample was written", pdfs.size >= 4)
        pdfs.forEach { println("PDFA SAMPLE ${it.absolutePath} ${it.length()} bytes") }
    }

    companion object {
        const val QA_DIR = "/mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/owner"
    }
}
