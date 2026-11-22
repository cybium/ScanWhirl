package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import com.scanwhirl.app.tools.PdfToJExport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * FF-J raster contract: when a PDF source is retained, the export asks the renderer for the
 * requested DPI/px. A 72 x 72 pt page at 200 DPI must become 200 x 200 px -- not the 150-DPI
 * import default renamed to JPG. Robolectric cannot run PdfRenderer, so the existing factory
 * seam supplies a fake page that records the bitmap it was given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfToJRasterTest {

    private lateinit var fd: ParcelFileDescriptor
    private lateinit var fake: FakePdf
    private var savedFactory: ((ParcelFileDescriptor) -> PdfPageRenderer.Renderer)? = null
    private val temp = TemporaryFolder()

    @Before
    fun setUp() {
        temp.create()
        val file = temp.newFile("source.pdf")
        file.writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)) // %PDF-
        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        savedFactory = PdfPageRenderer.rendererFactory
        fake = FakePdf(PAGES)
        PdfPageRenderer.rendererFactory = { fake }
    }

    @After
    fun tearDown() {
        savedFactory?.let { PdfPageRenderer.rendererFactory = it }
        fd.close()
        temp.delete()
    }

    @Test(timeout = 30_000)
    fun `requested 200 dpi renders a 72 point page to 200 pixels`() {
        val bytes = PdfToJExport.renderFromPdfJpegs(fd, listOf(0), 200f, 4096, 92).single()

        val page = fake.pages.single()
        assertEquals(200, page.bitmapWidth)
        assertEquals(200, page.bitmapHeight)
        assertEquals(200f / 72f, page.scaleX, 0.01f)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        assertEquals(200, opts.outWidth)
        assertEquals(200, opts.outHeight)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
    }

    @Test(timeout = 30_000)
    fun `only the chosen physical pages are rendered in order`() {
        PdfToJExport.renderFromPdfJpegs(fd, listOf(2, 0), 150f, 4096, 92)

        assertEquals(listOf(2, 0), fake.opened)
        assertEquals(2, fake.pages.size)
        assertEquals(PAGES, fake.pageCount)
    }

    private companion object {
        const val PAGES = 3
    }

    private class FakePdf(override val pageCount: Int) : PdfPageRenderer.Renderer {
        var closed = false
        val pages = mutableListOf<FakePage>()
        val opened = mutableListOf<Int>()

        override fun openPage(index: Int): PdfPageRenderer.Page {
            opened += index
            val page = FakePage(index)
            pages += page
            return page
        }

        override fun close() {
            closed = true
        }
    }

    private class FakePage(val index: Int) : PdfPageRenderer.Page {
        override val width: Int = 72
        override val height: Int = 72
        var bitmapWidth = 0
        var bitmapHeight = 0
        var scaleX = 0f

        override fun render(bitmap: Bitmap, transform: Matrix, mode: Int) {
            bitmapWidth = bitmap.width
            bitmapHeight = bitmap.height
            val values = FloatArray(9)
            transform.getValues(values)
            scaleX = values[Matrix.MSCALE_X]
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(8f, 8f, 64f, 64f, android.graphics.Paint().apply { color = Color.RED })
        }

        override fun close() = Unit
    }
}
