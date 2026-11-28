package com.scanwhirl.app.pdf.engine

import android.content.Context
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
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
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
 * Harm prevented: a page edit must not silently turn a digital PDF into pixels or
 * retain a hidden string. Every output is re-opened with Apache PDFBox 3.0.8, an
 * independent reader that is test-only and never shipped in the APK.
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
        // Robolectric runs tests on the main looper; the production guard stays on.
        engine = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "work"),
            enforceWorkerThread = false
        )
    }

    @After
    fun tearDown() {
        engine.close()
        dir.deleteRecursively()
    }

    private fun engineWith(budgets: PdfEngineBudgets): PdfBoxEngine = PdfBoxEngine(
        ApplicationProvider.getApplicationContext(), File(dir, "budget-${System.nanoTime()}"),
        budgets = budgets, enforceWorkerThread = false
    )

    private fun makePdf(
        name: String, pages: Int, marker: String,
        secret: String? = null, secretOnKeptPage: Boolean = false, outlineSecret: Boolean = false
    ): File {
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
                if (secret != null && (index == 0 || (secretOnKeptPage && index == 1))) {
                    val action = PDActionURI().apply { uri = "https://example.invalid/$secret" }
                    page.annotations = listOf(PDAnnotationLink().apply {
                        rectangle = PDRectangle(48f, 650f, 300f, 18f)
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
            if (outlineSecret && secret != null) {
                val outline = PDDocumentOutline()
                val item = PDOutlineItem().apply {
                    title = "Chapter $secret"
                    setDestination(doc.getPage(0))
                }
                outline.addLast(item)
                doc.documentCatalog.documentOutline = outline
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
        canvas.drawRect(0f, 0f, 120f, 60f, android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE
        })
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
    fun pageOperationsKeepExactDigitalPages() {
        val base = makePdf("base.pdf", 3, "PUBLIC_ALPHA")
        val other = makePdf("other.pdf", 2, "PUBLIC_BETA")

        val copy = engine.copyPages(engine.open(base), intArrayOf(0, 2), File(dir, "copy.pdf").path)
        val copyText = oracleText(copy.file)
        assertTrue(copyText.contains("PUBLIC_ALPHA page 1"))
        assertTrue(copyText.contains("PUBLIC_ALPHA page 3"))
        assertFalse(copyText.contains("page 2"))

        // A non-rotated sequence proves reorder and delete actually changed the pages.
        val edited = engine.copyPages(engine.open(base), intArrayOf(0, 1, 2), File(dir, "edited.pdf").path)
        engine.reorder(edited, intArrayOf(2, 0, 1))
        engine.delete(edited, intArrayOf(1))
        val editedDoc = Loader.loadPDF(edited.file)
        val editedText = PDFTextStripper().getText(editedDoc).replace(Regex("\\s+"), " ")
        assertEquals(2, editedDoc.numberOfPages)
        assertTrue(editedText.contains("PUBLIC_ALPHA page 3"))
        assertTrue(editedText.contains("PUBLIC_ALPHA page 2"))
        assertFalse(editedText.contains("page 1"))
        editedDoc.close()

        // Rotation is checked structurally because text order on a rotated page is reader-specific.
        engine.rotate(edited, 0, 90)
        Loader.loadPDF(edited.file).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
            assertTrue(PDFTextStripper().getText(doc).isNotBlank())
        }

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
    fun redactionDropsSecretsFromEveryPageCarrier() {
        val secret = "TOP_SECRET_ALPHA_4821"
        val source = makePdf(
            "redact-source.pdf", 2, "PUBLIC", secret,
            secretOnKeptPage = true, outlineSecret = true
        )
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
        assertFalse(raw.contains("/Names"))
        assertFalse(raw.contains("/Outlines"))
        assertFalse(raw.contains("/Prev"))
        assertEquals(1, Regex("%%EOF").findAll(raw).count())
        assertFalse(PDDocument.load(safe.file).use { it.documentInformation.title ?: "" }.contains(secret))
        Loader.loadPDF(safe.file).use { doc ->
            assertTrue(PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc).trim().isEmpty())
        }
    }

    @Test
    fun wrongPasswordMalformedUnsupportedAndBudgetsGiveTypedErrors() {
        val plain = makePdf("plain.pdf", 1, "PUBLIC_ALPHA")
        val protected = File(dir, "protected.pdf")
        PdfEncryptor.encrypt(plain, protected, "right-password")

        assertTrue(expectError { engine.open(protected, "wrong-password") } is PdfEngineError.InvalidPassword)
        assertEquals(1, engine.open(protected, "right-password").pageCount)

        // An encrypted file whose /Filter is not Standard must be refused before PDFBox sees it.
        val patched = String(protected.readBytes(), Charset.forName("ISO-8859-1"))
            .replace("/Standard", "/NoStdFlt").toByteArray(Charset.forName("ISO-8859-1"))
        val unsupported = File(dir, "unsupported-filter.pdf")
        unsupported.writeBytes(patched)
        assertTrue(expectError { engine.open(unsupported, "right-password") } is PdfEngineError.UnsupportedFeature)

        val broken = File(dir, "broken.pdf")
        broken.writeText("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
        assertTrue(expectError { engine.open(broken) } is PdfEngineError.MalformedDocument)

        assertTrue(engineWith(PdfEngineBudgets(maxInputBytes = 16)).use {
            expectError { it.open(plain) } is PdfEngineError.LimitExceeded
        })
        assertTrue(engineWith(PdfEngineBudgets(maxPages = 1)).use {
            expectError { it.open(makePdf("many.pdf", 2, "M")) } is PdfEngineError.LimitExceeded
        })
        assertTrue(engineWith(PdfEngineBudgets(maxRenderPixels = 1)).use { limited ->
            expectError {
                limited.redact(limited.open(plain), 0, emptyList(), File(dir, "px.pdf").path)
            } is PdfEngineError.LimitExceeded
        })
        assertTrue(engineWith(PdfEngineBudgets(wallTimeMs = 0)).use {
            expectError { it.open(plain) } is PdfEngineError.LimitExceeded
        })
        assertTrue(engineWith(PdfEngineBudgets(maxOutputBytes = 4)).use {
            expectError {
                it.copyPages(it.open(plain), intArrayOf(0), File(dir, "tiny.pdf").path)
            } is PdfEngineError.LimitExceeded
        })
        val bigHandle = engine.open(plain)
        assertTrue(engineWith(PdfEngineBudgets(maxInputBytes = 4)).use { limited ->
            expectError {
                limited.overlay(bigHandle, 0, signaturePng(),
                    PdfRect(0f, 0f, 10f, 10f), "x", "y")
            } is PdfEngineError.LimitExceeded
        })
    }

    @Test
    fun plaintextTempsArePrivateAndRemoved() {
        val plain = makePdf("private.pdf", 1, "PUBLIC_ALPHA")
        val protected = File(dir, "private-protected.pdf")
        PdfEncryptor.encrypt(plain, protected, "user-fictional")
        val scoped = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "private-work"),
            enforceWorkerThread = false
        )
        scoped.open(protected, "user-fictional")
        val temps = scoped.stagedTempPaths()
        assertTrue(temps.isNotEmpty())
        assertTrue(temps.all { it.parentFile == ApplicationProvider.getApplicationContext<Context>().filesDir })
        scoped.close()
        assertTrue(scoped.stagedTempPaths().isEmpty())

        val cancelled = PdfBoxEngine(
            ApplicationProvider.getApplicationContext(), File(dir, "cancel-work"),
            enforceWorkerThread = false
        )
        cancelled.cancel()
        assertTrue(expectError { cancelled.open(plain) } is PdfEngineError.Cancelled)
        cancelled.close()
        assertTrue(cancelled.stagedTempPaths().isEmpty())
    }

    @Test
    fun encryptedRoundTripUsesOnlyTheAppEncryptor() {
        val plain = makePdf("round.pdf", 1, "ENCRYPTED_MARKER_7777")
        val handle = engine.open(plain)
        val protected = File(dir, "round-protected.pdf")
        engine.saveEncrypted(handle, protected, "user-fictional")
        val decrypted = File(dir, "round-plain.pdf")
        decrypted.writeBytes(PdfDecryptor.decrypt(protected.readBytes(), "user-fictional"))
        assertTrue(oracleText(decrypted).contains("ENCRYPTED_MARKER_7777"))
        assertTrue(engine.verifyText(protected, "user-fictional").contains("ENCRYPTED_MARKER_7777"))
        assertTrue(expectError { engine.open(protected, "wrong-password") } is PdfEngineError.InvalidPassword)
    }
}
