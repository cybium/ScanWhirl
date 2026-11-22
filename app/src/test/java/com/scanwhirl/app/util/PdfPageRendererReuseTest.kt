package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PERF item 4. Robolectric cannot construct a real platform PdfRenderer, so the
 * factory seam supplies fakes that count native opens/closes and render page
 * calls. The byte comparison exercises the exact same render/encode code for
 * the old one-renderer-per-page pattern and the new one-renderer-per-job path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfPageRendererReuseTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var fd: ParcelFileDescriptor
    private var savedFactory: ((ParcelFileDescriptor) -> PdfPageRenderer.Renderer)? = null
    private lateinit var created: MutableList<FakeRenderer>
    private lateinit var log: MutableList<String>

    @Before
    fun setUp() {
        val file: File = temp.newFile("fixture.pdf")
        file.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)) // %PDF-
        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        savedFactory = PdfPageRenderer.rendererFactory
        created = mutableListOf()
        log = mutableListOf()
        PdfPageRenderer.rendererFactory = { _ ->
            val renderer = FakeRenderer(PAGES, created.size, log)
            created.add(renderer)
            renderer.opens++
            log += "open:${renderer.id}"
            renderer
        }
    }

    @After
    fun tearDown() {
        savedFactory?.let { PdfPageRenderer.rendererFactory = it }
        fd.close()
    }

    private fun legacyPattern(): List<ByteArray> {
        val count = PdfPageRenderer.pageCount(fd)
        return (0 until count).map { PdfPageRenderer.renderPageJpeg(fd, it, DPI, MAX_DIM, QUALITY) }
    }

    private fun reusePattern(): List<ByteArray> = PdfPageRenderer.withRenderer(fd) { renderer ->
        (0 until renderer.pageCount).map {
            PdfPageRenderer.renderPageJpeg(renderer, it, DPI, MAX_DIM, QUALITY)
        }
    }

    @Test
    fun `old loop opens one native renderer per call, new loop opens exactly one`() {
        val before = created.size
        val legacy = legacyPattern()
        assertEquals("legacy: pageCount + one renderer per page", PAGES + 1, created.size - before)
        created.forEach { assertTrue("legacy renderer closed", it.closed) }

        val atNew = created.size
        val reused = reusePattern()
        assertEquals("new path: a single renderer for the whole job", 1, created.size - atNew)
        assertEquals(1, created.last().opens)
        assertEquals(PAGES, created.last().openedPages)

        assertEquals("same page count", legacy.size, reused.size)
        legacy.forEachIndexed { i, bytes -> assertArrayEquals("page $i is byte-identical", bytes, reused[i]) }
        assertEquals(
            "pages rendered in document order",
            (0 until PAGES).map { "openPage:${created.last().id}:$it" },
            log.filter { it.startsWith("openPage:${created.last().id}:") }
        )
    }

    @Test
    fun `renderJpegs matches a per-page loop and closes its renderer once`() {
        val legacy = legacyPattern()
        val atNew = created.size
        val all = PdfPageRenderer.renderJpegs(fd, DPI, MAX_DIM, QUALITY)
        assertEquals(1, created.size - atNew)
        assertTrue(created.last().closed)
        assertEquals(legacy.size, all.size)
        legacy.forEachIndexed { i, bytes -> assertArrayEquals("page $i", bytes, all[i]) }
    }

    @Test
    fun `renderer is closed when the block is cancelled`() {
        val failure = CancellationException("job cancelled")
        try {
            PdfPageRenderer.withRenderer(fd) { throw failure }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals(failure.message, e.message)
        }
        assertEquals(1, created.size)
        assertTrue("native renderer released on cancellation", created.single().closed)
    }

    @Test
    fun `renderer is closed on OutOfMemoryError`() {
        try {
            PdfPageRenderer.withRenderer(fd) { throw OutOfMemoryError("native heap") }
            fail("OOM must propagate")
        } catch (e: OutOfMemoryError) {
            assertEquals("native heap", e.message)
        }
        assertTrue("native renderer released on OOM", created.single().closed)
    }

    @Test
    fun `per-page render keeps bitmap size, config, render mode and matrix`() {
        PdfPageRenderer.withRenderer(fd) { renderer ->
            PdfPageRenderer.renderPageJpeg(renderer, 1, DPI, MAX_DIM, QUALITY)
        }
        val page = created.single().pages.single()
        // Fixture pages are 100x50 pt; at 150 dpi the longest side is 208 px (<2048).
        assertEquals(208, page.bitmapWidth)
        assertEquals(104, page.bitmapHeight)
        assertEquals(Bitmap.Config.ARGB_8888, page.bitmapConfig)
        assertEquals(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY, page.mode)
        assertEquals(2.08f, page.scaleX, 0.01f)
        assertEquals(2.08f, page.scaleY, 0.01f)
        assertTrue("renderer owned by withRenderer is closed", created.single().closed)
        assertTrue("page closed after encoding", page.closed)
    }

    @Test
    fun `quality is passed through to JPEG encoding`() {
        val bytes = PdfPageRenderer.withRenderer(fd) { renderer ->
            PdfPageRenderer.renderPageJpeg(renderer, 0, DPI, MAX_DIM, QUALITY)
        }
        val expectedBitmap = Bitmap.createBitmap(208, 104, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(expectedBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(5f, 5f, 40f, 30f, android.graphics.Paint().apply { color = Color.RED })
        assertArrayEquals(ImageTools.encodeJpeg(expectedBitmap, QUALITY), bytes)
        expectedBitmap.recycle()
    }

    private companion object {
        const val PAGES = 3
        const val DPI = 150f
        const val MAX_DIM = 2048
        const val QUALITY = 85
    }

    private class FakeRenderer(
        override val pageCount: Int,
        val id: Int,
        private val log: MutableList<String>
    ) : PdfPageRenderer.Renderer {
        var closed = false
        var opens = 0
        var openedPages = 0
        val pages = mutableListOf<FakePage>()

        override fun openPage(index: Int): PdfPageRenderer.Page {
            openedPages++
            log += "openPage:$id:$index"
            val page = FakePage(index, id, log)
            pages.add(page)
            return page
        }

        override fun close() {
            closed = true
            log += "close:$id"
        }
    }

    private class FakePage(
        val index: Int,
        val id: Int,
        private val log: MutableList<String>
    ) : PdfPageRenderer.Page {
        var closed = false
        var bitmapWidth = 0
        var bitmapHeight = 0
        var bitmapConfig: Bitmap.Config? = null
        var mode = -1
        var scaleX = 0f
        var scaleY = 0f

        override val width: Int = 100
        override val height: Int = 50

        override fun render(bitmap: Bitmap, transform: Matrix, mode: Int) {
            bitmapWidth = bitmap.width
            bitmapHeight = bitmap.height
            bitmapConfig = bitmap.config
            this.mode = mode
            val values = FloatArray(9)
            transform.getValues(values)
            scaleX = values[Matrix.MSCALE_X]
            scaleY = values[Matrix.MSCALE_Y]
            log += "render:$id:$index:${bitmap.width}x${bitmap.height}:$mode"
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(5f, 5f, 40f, 30f, android.graphics.Paint().apply { color = Color.RED })
        }

        override fun close() {
            closed = true
            log += "closePage:$id:$index"
        }
    }
}
