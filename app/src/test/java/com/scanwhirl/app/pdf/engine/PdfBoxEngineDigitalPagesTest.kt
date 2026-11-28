package com.scanwhirl.app.pdf.engine

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.PdfEncryptor

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * Harm prevented: a page edit must not silently turn a digital PDF into pixels.
 * Every output is re-opened with Apache PDFBox 3.0.8 (an independent reader that is
 * test-only and never in the APK) and its text is extracted again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfBoxEngineDigitalPagesTest {

    private lateinit var dir: File
    private lateinit var engine: PdfEngine

    @Before
    fun setUp() {
        dir = File(File(java.io.File.separator, "tmp"), "w7a-engine-${System.nanoTime()}")
        dir.mkdirs()
        engine = PdfBoxEngine(ApplicationProvider.getApplicationContext(), File(dir, "work"))
    }

    @After
    fun tearDown() {
        engine.close()
        dir.deleteRecursively()
    }

    private fun makePdf(name: String, pages: Int, marker: String, secret: String? = null): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                    cs.newLineAtOffset(48f, 720f)
                    cs.showText("$marker page ${index + 1}")
                    cs.endText()
                    if (secret != null && index == 0) {
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 12f)
                        cs.newLineAtOffset(48f, 690f)
                        cs.showText(secret)
                        cs.endText()
                    }
                }
                if (secret != null && index == 0) {
                    val action = PDActionURI().apply { uri = "https://example.invalid/$secret" }
                    page.annotations = listOf(PDAnnotationLink().apply {
                        rectangle = PDRectangle(48f, 660f, 300f, 18f)
                        this.action = action
                    })
                }
            }
            if (secret != null) {
                doc.documentInformation = PDDocumentInformation().apply {
                    title = "Private $secret"
                    subject = secret
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun oracleText(file: File): String = Loader.loadPDF(file).use { doc ->
        PDFTextStripper().getText(doc)
    }

    private fun signaturePng(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(120, 60, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
        canvas.drawRect(0f, 0f, 120f, 60f, paint)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return bytes.toByteArray()
    }

    private fun expectError(block: () -> Unit): PdfEngineError {
        try {
            block()
        } catch (e: PdfEngineError) {
            return e
        }
        fail("expected a typed PdfEngineError")
        throw AssertionError("unreachable")
    }

    @Test
    fun copyReorderRotateDeleteMergeSplitKeepSelectableText() {
        val base = makePdf("base.pdf", 3, "PUBLIC_ALPHA")
        val other = makePdf("other.pdf", 2, "PUBLIC_BETA")

        val copy = engine.copyPages(engine.open(base), intArrayOf(0, 2), File(dir, "copy.pdf").path)
        val copyText = oracleText(copy.file)
        assertTrue(copyText.contains("PUBLIC_ALPHA page 1"))
        assertTrue(copyText.contains("PUBLIC_ALPHA page 3"))
        assertFalse(copyText.contains("page 2"))

        engine.reorder(copy, intArrayOf(1, 0))
        engine.rotate(copy, 0, 90)
        engine.delete(copy, intArrayOf(1))
        // PDFTextStripper splits glyphs of rotated text across lines; whitespace is not content.
        val editedText = oracleText(copy.file).replace(Regex("\\s+"), " ")
        assertTrue(editedText.contains("page 3"))
        assertFalse(editedText.contains("page 1"))

        val merged = engine.merge(listOf(base, other), File(dir, "merged.pdf").path)
        assertEquals(5, merged.pageCount)
        val mergedText = oracleText(merged.file)
        assertTrue(mergedText.contains("PUBLIC_ALPHA page 3"))
        assertTrue(mergedText.contains("PUBLIC_BETA page 2"))

        val parts = engine.split(merged, listOf(0..1, 2..4))
        assertEquals(2, parts.size)
        assertTrue(oracleText(parts[0].file).contains("PUBLIC_ALPHA page 2"))
        assertTrue(oracleText(parts[1].file).contains("PUBLIC_BETA page 1"))
        assertFalse(oracleText(parts[0].file).contains("PUBLIC_BETA"))
    }

    @Test
    fun overlayAddsSelectableSignatureWithoutRasterising() {
        val base = makePdf("overlay.pdf", 1, "PUBLIC_ALPHA")
        val handle = engine.open(base)
        engine.overlay(
            handle, 0, signaturePng(), PdfRect(360f, 60f, 160f, 80f),
            "SIGNED BY SCANWHIRL TEST", "2026-11-28"
        )
        val text = oracleText(handle.file)
        assertTrue(text.contains("PUBLIC_ALPHA page 1"))
        assertTrue(text.contains("SIGNED BY SCANWHIRL TEST"))
        assertTrue(text.contains("2026-11-28"))
    }

    @Test
    fun redactionDropsTextInfoAnnotationsAndHistory() {
        val secret = "TOP_SECRET_ALPHA_4821"
        val source = makePdf("redact-source.pdf", 2, "PUBLIC", secret)
        val handle = engine.open(source)
        val safe = engine.redact(
            handle, 0, listOf(PdfRect(0f, 620f, 400f, 160f)), File(dir, "redacted.pdf").path
        )
        val text = oracleText(safe.file)
        assertFalse(text.contains(secret))
        assertTrue(text.contains("PUBLIC page 2"))
        val raw = String(safe.file.readBytes(), Charset.forName("ISO-8859-1"))
        assertFalse(raw.contains(secret))
        assertFalse(raw.contains("/Annots"))
        assertFalse(raw.contains("/Prev"))
        assertEquals(1, Regex("%%EOF").findAll(raw).count())
        assertFalse(oracleText(safe.file).contains("TOP_SECRET"))
        // The redacted page itself is image-only: no text layer survives there.
        val pageZero = Loader.loadPDF(safe.file).use { doc ->
            PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc)
        }
        assertTrue(pageZero.trim().isEmpty())
    }

    @Test
    fun wrongPasswordMalformedAndBudgetsGiveTypedErrors() {
        val plain = makePdf("plain.pdf", 1, "PUBLIC_ALPHA")
        val protected = File(dir, "protected.pdf")
        PdfEncryptor.encrypt(plain, protected, "right-password")

        assertTrue(expectError { engine.open(protected, "wrong-password") } is PdfEngineError.InvalidPassword)
        assertEquals(1, engine.open(protected, "right-password").pageCount)

        val broken = File(dir, "broken.pdf")
        broken.writeText("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
        assertTrue(expectError { engine.open(broken) } is PdfEngineError.MalformedDocument)

        val tiny = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "tiny"),
            budgets = PdfEngineBudgets(maxInputBytes = 16)
        ).use { limited ->
            expectError { limited.open(plain) } is PdfEngineError.LimitExceeded
        }
        assertTrue(tiny)
    }

    @Test
    fun encryptedRoundTripUsesOnlyTheAppEncryptor() {
        val plain = makePdf("round.pdf", 1, "ENCRYPTED_MARKER_7777")
        val handle = engine.open(plain)
        val protected = File(dir, "round-protected.pdf")
        engine.saveEncrypted(handle, protected, "user-fictional")
        assertTrue(oracleTextDecrypted(protected, "user-fictional").contains("ENCRYPTED_MARKER_7777"))
        assertTrue(engine.verifyText(protected, "user-fictional").contains("ENCRYPTED_MARKER_7777"))
        assertTrue(expectError { engine.open(protected, "wrong-password") } is PdfEngineError.InvalidPassword)
    }

    private fun oracleTextDecrypted(file: File, password: String): String {
        val plainBytes = PdfDecryptor.decrypt(file.readBytes(), password)
        val plain = File(dir, "oracle-plain.pdf")
        plain.writeBytes(plainBytes)
        return oracleText(plain)
    }
}
