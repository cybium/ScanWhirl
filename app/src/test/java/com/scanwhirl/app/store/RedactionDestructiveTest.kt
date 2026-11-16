package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.PdfTextLayer
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Policy review item 1/3: "Hide private info" is destructive. After the copy is saved, neither
 * the PDF's extracted text/`/ActualText` layer nor the OCR store may still hold the hidden
 * number or email; the source document keeps both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RedactionDestructiveTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    private val number = "98765 43210"
    private val email = "ravi@example.com"
    private val line = "Call $number or $email"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Prefs.setMakeSearchable(app, true)
    }

    @After
    fun tearDown() {
        Durable.resetForTests()
    }

    /** The PDF as a text extractor reads it: literal text-show operators and /ActualText. */
    private fun pdfBytes(file: java.io.File): String =
        file.readBytes().toString(Charsets.ISO_8859_1)

    @Test
    fun `Hide drops the hidden number and email from the copy's pdf text and ocr store`() =
        runBlocking {
            val id = DocFixtures.saved(repo, app, pages = 1, name = "Redact me")
            val source = requireNotNull(repo.getDocument(id))
            val page = source.pageFiles.single()
            val (w, h) = requireNotNull(PageReplace.bounds(page))
            repo.ocrStore.putPage(id, page.name, line, OcrPage(w, h, listOf(OcrLine(line, 5, 5, 195, 30))))
            assertTrue("the fixture gets a real PDF text layer", repo.makeSearchable(id))

            val sourcePdf = pdfBytes(source.pdfFile)
            assertTrue("precondition: the source PDF carries the number", sourcePdf.contains(number))
            assertTrue("precondition: the source PDF carries the email", sourcePdf.contains(email))
            assertTrue(
                "precondition: the source PDF has an /ActualText layer",
                sourcePdf.contains("/ActualText")
            )

            val out = repo.stampDocument(
                id,
                listOf(
                    PageStamp.PageEdits(
                        0,
                        listOf(PageStamp.Mark.Redact(PageStamp.Box(0.0f, 0.0f, 1.0f, 0.2f)))
                    )
                ),
                null
            )

            val copyPdf = pdfBytes(out.pdfFile)
            assertFalse("the hidden number must not survive in the PDF", copyPdf.contains(number))
            assertFalse("the hidden email must not survive in the PDF", copyPdf.contains(email))
            assertFalse(
                "no /ActualText layer may carry the hidden line",
                copyPdf.contains(PdfTextLayer.actualTextHex(line)!!)
            )
            assertFalse("the redacted page has no text layer", copyPdf.contains("/ActualText"))
            assertFalse(
                "the copy is not marked searchable",
                java.io.File(repo.docsDir, out.id + "/text_layer.flag").exists()
            )
            assertTrue("the copy has no OCR text", repo.ocrDoc(out.id).isEmpty())
            assertFalse(
                "the copy has no OCR geometry for the redacted page",
                repo.ocrStore.readGeo(out.id).containsKey(page.name)
            )

            // The original is deliberately unchanged (truth-in-advertising: "a copy").
            assertTrue("source OCR text stays", repo.ocrDoc(id)[page.name]?.contains(number) == true)
            assertTrue("source PDF text stays", pdfBytes(source.pdfFile).contains(number))
        }
}
