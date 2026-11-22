package com.scanwhirl.app.store

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PdfGenerator
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * TEST-ONLY repro for GPT review B4 (ownerGPT01, 22 Nov 2026), against exact base 3e62aeec.
 *
 * B4 is an ORDER bug in the external exact-target path, not a request for original-source PDF
 * MediaBox fidelity (which is explicitly NOT implemented and must not be conflated here):
 *
 *   importPdfToTarget renders the picked PDF into private staging JPEGs, then calls
 *   writePageSizes(staging, sourcePageSizes(...)) -- binding the CHOSEN points (derived from the
 *   rendered 150-DPI raster, because an external PDF's own MediaBox is not preserved) to those
 *   JPEGs' pixels and SHA-256. ONLY THEN does fitStagedPagesToTarget re-encode/downsample the same
 *   JPEGs. writePdfInto -> readPageSizes sees the new hash/pixels, silently falls back to the
 *   smaller POST-FIT raster points, and the exported /MediaBox shrinks.
 *
 * This test drives the real repository path with a fictional two-page PDF whose physical page
 * boxes are distinct, forces a real second-stage downsample with a tiny byte target, parses the
 * exported /MediaBox independently with PDFBox, and asserts the PRE-FIT chosen points survive.
 * Everything except that final geometry assertion is a precondition and must pass; the expected
 * RED is exactly that assertion on the unfixed base.
 */
private val B4_SOURCE_BOXES = listOf(595 to 842, 842 to 595) // A4 portrait / A4 landscape, distinct

/** Deterministic high-frequency fictional ink: remains large after a low-quality JPEG re-encode,
 *  so SizeFit.fitToShare must actually downsample pixels (not merely lower quality). */
private fun b4NoisyPixels(w: Int, h: Int): IntArray {
    val px = IntArray(w * h)
    var seed = 0x1234_5678
    for (i in px.indices) {
        seed = (seed * 1_103_515_245 + 12_345) and 0x7FFF_FFFF
        val n = (seed ushr 16) and 0xFF
        px[i] = (0xFF shl 24) or (n shl 16) or ((255 - n) shl 8) or ((seed and 0xFF) or 0x40)
    }
    return px
}

private fun b4NoisyJpeg(w: Int, h: Int): ByteArray {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    try {
        bmp.setPixels(b4NoisyPixels(w, h), 0, w, 0, 0, w, h)
        val out = ByteArrayOutputStream()
        assertTrue("fixture JPEG encode", bmp.compress(Bitmap.CompressFormat.JPEG, 90, out))
        return out.toByteArray()
    } finally {
        bmp.recycle()
    }
}

/** Existing test seam pattern (see RsExternalCompressRejectTest): a shadow renderer reports the
 *  fictional source PDF's own page boxes as the platform page size, so PdfImport's 150-DPI render
 *  produces the chosen-point raster deterministically. */
@Implements(PdfRenderer::class)
class B4Renderer {
    @Implementation fun __constructor__(fd: ParcelFileDescriptor) = Unit
    @Implementation fun getPageCount(): Int = B4_SOURCE_BOXES.size
    @Implementation fun openPage(index: Int): PdfRenderer.Page {
        val (w, h) = B4_SOURCE_BOXES[index]
        B4Page.reportedWidth = w
        B4Page.reportedHeight = h
        return Shadow.newInstanceOf(PdfRenderer.Page::class.java)
    }
    @Implementation fun close() = Unit
}

@Implements(PdfRenderer.Page::class)
class B4Page {
    companion object {
        var reportedWidth: Int = 0
        var reportedHeight: Int = 0
    }
    @Implementation fun getWidth(): Int = reportedWidth
    @Implementation fun getHeight(): Int = reportedHeight
    @Implementation fun render(bitmap: Bitmap, clip: Rect?, matrix: Matrix?, mode: Int) {
        val w = bitmap.width
        val h = bitmap.height
        bitmap.setPixels(b4NoisyPixels(w, h), 0, w, 0, 0, w, h)
    }
    @Implementation fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], shadows = [B4Renderer::class, B4Page::class])
class ExternalTargetGeometryRegressionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        ScanRepository.resetTestSeams()
        Faults.resetAll()
        OpFaults.reset()
        Durable.resetForTests()
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

    /** Independent PDFBox parse of every page's physical /MediaBox (test-only oracle). */
    private fun mediaBoxes(pdf: File): List<Pair<Int, Int>> = Loader.loadPDF(pdf).use { doc ->
        (0 until doc.numberOfPages).map { i ->
            val box = doc.getPage(i).mediaBox
            box.width.roundToInt() to box.height.roundToInt()
        }
    }

    /** The renderer's 150-DPI raster size for a source box, mirroring PdfImport.renderPage. */
    private fun renderedPixels(box: Pair<Int, Int>): Pair<Int, Int> {
        var scale = 150f / 72f
        val longest = max(box.first, box.second)
        if (longest * scale > 2048f) scale = 2048f / longest
        return (box.first * scale).roundToInt().coerceIn(1, 2048) to
            (box.second * scale).roundToInt().coerceIn(1, 2048)
    }

    @Test(timeout = 180_000)
    fun `external exact-target keeps the chosen pre-fit page boxes`() = runBlocking {
        val repo = ScanRepository(app)
        val tag = UUID.randomUUID().toString().take(8)

        // 1. Fictional source PDF: distinct physical page boxes, written by the real writer.
        val pageImages = B4_SOURCE_BOXES.mapIndexed { i, box ->
            val px = renderedPixels(box)
            File(app.cacheDir, "b4_src_${tag}_$i.jpg").apply {
                writeBytes(b4NoisyJpeg(px.first, px.second))
            }
        }
        val sourcePdf = File(app.cacheDir, "b4_source_$tag.pdf")
        PdfGenerator.imagesToPdf(pageImages, sourcePdf, pageSizes = B4_SOURCE_BOXES)

        // 2. Independent oracle proves the fixture really has two distinct /MediaBoxes.
        val sourceBoxes = mediaBoxes(sourcePdf)
        assertEquals("source fixture page count", 2, sourceBoxes.size)
        assertEquals("source fixture physical boxes", B4_SOURCE_BOXES, sourceBoxes)
        assertEquals("boxes are distinct", 2, sourceBoxes.distinct().size)
        val sourceHashBefore = sha256(sourcePdf)
        assertTrue("fictional source is large enough to beat a tiny target", sourcePdf.length() > 100_000L)

        // 3. The chosen-points contract on this base: derived from the rendered 150-DPI raster.
        val renderPixels = sourceBoxes.map(::renderedPixels)
        val prefitPoints = renderPixels.map { (w, h) -> PdfGenerator.sizeInPoints(w, h) }

        // 4. Real repository path; a tiny target forces a second-stage downsample AFTER the
        //    pre-fit sidecar was written. rejectNotSmallerThan is the MEASURED source size, so a
        //    no-gain rejection cannot mask the geometry outcome.
        val doc = repo.importPdfToTarget(
            Uri.fromFile(sourcePdf), "B4 Fictional", listOf(0, 1),
            targetBytes = 4_000L,
            rejectNotSmallerThan = sourcePdf.length()
        )
        assertNotNull("smaller output must publish", doc)
        doc!!
        assertEquals("both pages kept", 2, doc.pageFiles.size)

        // 5. The picked source is read-only.
        assertEquals("source PDF hash unchanged", sourceHashBefore, sha256(sourcePdf))

        // 6. Output is genuinely smaller than the source, so no-gain rejection is not masking.
        val outputBytes = doc.pdfFile.length()
        assertTrue("output smaller than source: $outputBytes < ${sourcePdf.length()}", outputBytes < sourcePdf.length())

        // 7. PRECONDITION: the second-stage fit really downsampled pixels (not just quality).
        val postPixels = doc.pageFiles.map { f ->
            val info = PdfGenerator.readJpegInfo(f.readBytes())
            info.width to info.height
        }
        assertNotEquals("fit re-encoded/downsampled the staged pages", renderPixels, postPixels)
        postPixels.forEachIndexed { i, (w, h) ->
            assertTrue(
                "page $i downsampled: ${max(w, h)} < ${max(renderPixels[i].first, renderPixels[i].second)}",
                max(w, h) < max(renderPixels[i].first, renderPixels[i].second)
            )
        }
        // Guard against a vacuous test: if post-fit points equalled pre-fit points, B4 is unobservable.
        assertNotEquals(
            "post-fit raster points differ from pre-fit chosen points",
            prefitPoints,
            postPixels.map { (w, h) -> PdfGenerator.sizeInPoints(w, h) }
        )

        // 8. INTENDED RED: the exported /MediaBox must retain the CHOSEN pre-fit points.
        //    On the unfixed base readPageSizes falls back to the smaller post-fit raster, so this
        //    is the single assertion expected to fail. Original-source MediaBox fidelity is NOT
        //    asserted here: that capability is unimplemented and out of scope for B4.
        val exportedBoxes = mediaBoxes(doc.pdfFile)
        assertEquals("exported /MediaBox must keep the pre-fit chosen points", prefitPoints, exportedBoxes)
    }
}
