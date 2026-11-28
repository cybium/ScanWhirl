package com.scanwhirl.app.pdf.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.test.AndroidTestCase
import com.scanwhirl.app.util.PdfEncryptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Runs against the MINIFIED release APK (R8 + resource shrinking). Robolectric
 * builds with -PtestBuild, which disables R8, so this is the only proof that the
 * keep rules and AAR assets survive into the shipped bytecode.
 *
 * Uses the platform AndroidTestCase runner deliberately: the AndroidX runner
 * pulls androidx.tracing into the test process, which is not part of this app.
 */
class PdfEngineMinifiedSmokeTest : AndroidTestCase() {

    private fun makePdf(file: File, marker: String): File {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(48f, 720f)
                cs.showText(marker)
                cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    private fun signaturePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(0f, 0f, 80f, 40f, Paint().apply { color = Color.BLUE })
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return bytes.toByteArray()
    }

    fun testMinifiedEngineOpensMergesOverlaysRedactsAndRoundTripsEncryption() {
        val ctx = context
        val dir = File(ctx.cacheDir, "w7a-minified-${System.nanoTime()}").apply { mkdirs() }
        val engine = PdfBoxEngine(ctx)
        try {
            val a = makePdf(File(dir, "a.pdf"), "MINIFIED_MARKER_A")
            val b = makePdf(File(dir, "b.pdf"), "MINIFIED_MARKER_B")
            val merged = engine.merge(listOf(a, b), File(dir, "merged.pdf").path)
            assertEquals(2, merged.pageCount)
            assertTrue(engine.verifyText(merged.file).contains("MINIFIED_MARKER_A"))
            assertTrue(engine.verifyText(merged.file).contains("MINIFIED_MARKER_B"))

            engine.overlay(
                merged, 0, signaturePng(), PdfRect(300f, 60f, 120f, 60f),
                "MINIFIED_SIGNATURE", "2026-11-28"
            )
            assertTrue(engine.verifyText(merged.file).contains("MINIFIED_SIGNATURE"))

            val protected = File(dir, "protected.pdf")
            engine.saveEncrypted(merged, protected, "user-fictional")
            assertTrue(engine.verifyText(protected, "user-fictional").contains("MINIFIED_MARKER_A"))
            val reopened = engine.open(protected, "user-fictional")
            assertEquals(2, reopened.pageCount)

            val redacted = engine.redact(
                reopened, 0, listOf(PdfRect(0f, 620f, 400f, 160f)), File(dir, "redacted.pdf").path
            )
            assertEquals(2, redacted.pageCount)
            assertFalse(engine.verifyText(redacted.file).contains("MINIFIED_MARKER_A"))
        } finally {
            engine.close()
            dir.deleteRecursively()
        }
    }
}
