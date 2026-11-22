package com.scanwhirl.app.pdfj

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.PdfToJExport
import com.scanwhirl.app.tools.PdfToJOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * FF-J numeric tests. The "PDF" is a fictional 3-page document whose pages carry a coloured
 * PAGE ONE / PAGE TWO / PAGE THREE watermark. The colour band beside each label is the machine
 * readable form of that watermark, so a wrong-page export is detected without OCR.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfToJExportTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var outDir: File
    private var sourceDocId: String? = null
    private val extraDocIds = mutableListOf<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        outDir = File(app.cacheDir, "ffj_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sourceDocId?.let { File(repo.docsDir, it).deleteRecursively() }
        extraDocIds.forEach { File(repo.docsDir, it).deleteRecursively() }
        outDir.deleteRecursively()
    }

    private fun fixture(): String {
        if (sourceDocId == null) {
            sourceDocId = runBlocking {
                repo.createDocument(
                    "FFJ Certificate",
                    listOf(
                        page("PAGE ONE", Color.RED),
                        page("PAGE TWO", Color.MAGENTA),
                        page("PAGE THREE", Color.CYAN)
                    )
                ).id
            }
        }
        return sourceDocId!!
    }

    private fun page(label: String, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = 26f
            }
            canvas.drawText(label, 14f, 44f, paint)
            // A solid band is the pixel-stable part of the fictional watermark.
            canvas.drawRect(14f, 66f, 226f, 132f, paint)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Detailed fictional page: enough entropy that a 200 x 230 JPEG can reach a real KB band. */
    private fun detailedPage(): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 400, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(11)
        try {
            for (y in 0 until 400) {
                for (x in 0 until 320) {
                    bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
                }
            }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasColor(bytes: ByteArray, color: Int): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        try {
            for (y in 0 until bitmap.height step 2) {
                for (x in 0 until bitmap.width step 2) {
                    val p = bitmap.getPixel(x, y)
                    if (abs(Color.red(p) - Color.red(color)) <= 55 &&
                        abs(Color.green(p) - Color.green(color)) <= 55 &&
                        abs(Color.blue(p) - Color.blue(color)) <= 55
                    ) return true
                }
            }
            return false
        } finally {
            bitmap.recycle()
        }
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun request(
        pages: List<Int>,
        layout: PdfToJOptions.Layout = PdfToJOptions.Layout.SEPARATE,
        format: ScanRepository.ImageExportFormat = ScanRepository.ImageExportFormat.JPG,
        form: PdfToJExport.FormSpec? = null
    ) = PdfToJExport.Request(
        docId = fixture(), pageIndices = pages, format = format, layout = layout,
        quality = 100, form = form
    )

    @Test(timeout = 30_000)
    fun `three page PDF page two gives one image jpeg with no zip and the right watermark`() {
        val result = runBlocking { PdfToJExport.export(repo, request(listOf(1)), outDir, "Doc") }

        assertNull("a single chosen page must not produce a ZIP", result.zip)
        assertEquals(1, result.outputs.size)
        val output = result.outputs.single()
        assertEquals("image/jpeg", output.mime)
        assertTrue("expected a .jpg, got ${output.file.name}", output.file.name.endsWith(".jpg"))
        val bytes = output.file.readBytes()
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertTrue("page 2 watermark (PAGE TWO) is missing", hasColor(bytes, Color.MAGENTA))
        assertFalse("wrong page: PAGE ONE leaked in", hasColor(bytes, Color.RED))
        assertFalse("wrong page: PAGE THREE leaked in", hasColor(bytes, Color.CYAN))
        assertFalse(
            "no ZIP may appear in the separate layout",
            outDir.listFiles().orEmpty().any { it.name.endsWith(".zip") }
        )
    }

    @Test(timeout = 30_000)
    fun `pages one and three give two named JPGs in order`() {
        val result = runBlocking { PdfToJExport.export(repo, request(listOf(0, 2)), outDir, "Doc") }

        assertNull(result.zip)
        assertEquals(listOf("Doc page 1.jpg", "Doc page 3.jpg"), result.outputs.map { it.file.name })
        assertEquals(listOf(0, 2), result.outputs.map { it.pageIndex })
        assertTrue("page 1 first", hasColor(result.outputs[0].file.readBytes(), Color.RED))
        assertTrue("page 3 second", hasColor(result.outputs[1].file.readBytes(), Color.CYAN))
        assertFalse("page 2 must not be exported", hasColor(result.outputs[0].file.readBytes(), Color.MAGENTA))
    }

    @Test(timeout = 30_000)
    fun `PNG export has a real PNG signature`() {
        val result = runBlocking {
            PdfToJExport.export(
                repo,
                request(listOf(1), format = ScanRepository.ImageExportFormat.PNG),
                outDir, "Doc"
            )
        }
        val bytes = result.outputs.single().file.readBytes()
        assertEquals(0x89, bytes[0].toInt() and 0xFF)
        assertEquals("PNG", String(bytes, 1, 3, Charsets.US_ASCII))
        assertEquals("image/png", result.mime)
    }

    @Test(timeout = 30_000)
    fun `a zip is produced only when the bundle is chosen`() {
        val separate = runBlocking { PdfToJExport.export(repo, request(listOf(0, 2)), outDir, "Separate") }
        assertNull("separate must not write a ZIP", separate.zip)
        assertEquals(2, separate.files.size)

        val bundled = runBlocking {
            PdfToJExport.export(repo, request(listOf(0, 2), PdfToJOptions.Layout.ZIP), outDir, "Bundled")
        }
        val zip = requireNotNull(bundled.zip) { "the ZIP bundle was chosen" }
        assertEquals("application/zip", bundled.mime)
        ZipFile(zip).use { z ->
            assertEquals(listOf("page_001.jpg", "page_003.jpg"), z.entries().toList().map { it.name })
            assertTrue("entry 1 is a JPEG", z.getInputStream(z.getEntry("page_001.jpg")).use {
                val b = it.readBytes(); b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
            })
        }
    }

    @Test(timeout = 30_000)
    fun `the optional KB band is checked on the final bytes`() {
        val detailedId = runBlocking { repo.createDocument("Detailed", listOf(detailedPage())).id }
        extraDocIds += detailedId
        val form = PdfToJExport.FormSpec(200, 230, 10_000L, 200_000L, 200)
        val build = { min: Long, max: Long ->
            PdfToJExport.Request(
                detailedId, listOf(0), ScanRepository.ImageExportFormat.JPG,
                PdfToJOptions.Layout.SEPARATE, 100,
                PdfToJExport.FormSpec(200, 230, min, max, 200)
            )
        }
        val result = runBlocking { PdfToJExport.export(repo, build(form.minBytes, form.maxBytes), outDir, "Form") }

        val out = result.outputs.single()
        assertEquals("image/jpeg", out.mime)
        assertEquals(200 to 230, dimensions(out.file.readBytes()))
        assertTrue("final bytes ${out.bytes} must be inside 10-200 KB", out.bytes in 10_000L..200_000L)
        assertTrue("the measured size is the real file size", out.bytes == out.file.length())

        val impossible = runBlocking { runCatching { PdfToJExport.export(repo, build(5_000_000L, 6_000_000L), outDir, "Miss") } }
        assertTrue("an unreachable band must fail honestly", impossible.isFailure)
    }

    @Test(timeout = 30_000)
    fun `a 200 by 230 form crop keeps a circle round instead of stretching the page`() {
        // Whole-page scaling of this 600 x 600 square into 200 x 230 would turn the circle into a
        // 200 : 230 ellipse (a 13% error). An aspect-preserving crop keeps it round within 2%.
        val source = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.WHITE)
        canvas.drawCircle(300f, 300f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        val cropped = PdfToJExport.cropToAspect(source, 200, 230)
        val scaled = Bitmap.createBitmap(200, 230, Bitmap.Config.ARGB_8888)
        try {
            Canvas(scaled).drawBitmap(cropped, null, Rect(0, 0, 200, 230), Paint(Paint.FILTER_BITMAP_FLAG))
            var left = 200; var top = 230; var right = -1; var bottom = -1
            for (y in 0 until 230) {
                for (x in 0 until 200) {
                    val p = scaled.getPixel(x, y)
                    if (Color.red(p) > 180 && Color.green(p) < 90 && Color.blue(p) < 90) {
                        left = minOf(left, x); top = minOf(top, y)
                        right = maxOf(right, x); bottom = maxOf(bottom, y)
                    }
                }
            }
            assertTrue("the crop lost the circle", right > left && bottom > top)
            val ratio = (right - left + 1).toFloat() / (bottom - top + 1)
            assertTrue("circle became an ellipse: $ratio", abs(ratio - 1f) <= 0.02f)
            assertEquals(200f / 230f, cropped.width.toFloat() / cropped.height, 0.01f)
        } finally {
            cropped.recycle()
            scaled.recycle()
            source.recycle()
        }
    }

    @Test(timeout = 30_000)
    fun `an empty selection writes nothing and leaves the source untouched`() {
        val before = File(repo.docsDir, fixture()).listFiles().orEmpty().map { it.name to it.length() }
        val failed = runBlocking { runCatching { PdfToJExport.export(repo, request(emptyList()), outDir, "None") } }
        assertTrue("no pages means no export", failed.isFailure)
        assertEquals(before, File(repo.docsDir, fixture()).listFiles().orEmpty().map { it.name to it.length() })
        assertNull(outDir.listFiles().orEmpty().firstOrNull())
    }
}
