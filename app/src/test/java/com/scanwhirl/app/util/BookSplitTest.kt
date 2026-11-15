package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v2 tool "Book pages" engine: gutter detection, the exact-middle fallback, two pages per spread
 * and the guarantee that a picked image is only read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookSplitTest {

    private fun spread(
        width: Int = 900,
        height: Int = 600,
        darkBand: IntRange? = 435..465
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        if (darkBand != null) {
            val canvas = Canvas(bitmap)
            canvas.drawRect(
                darkBand.first.toFloat(),
                0f,
                (darkBand.last + 1).toFloat(),
                height.toFloat(),
                Paint().apply { color = Color.BLACK }
            )
        }
        return bitmap
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun `gutter is found on a two-page spread with a dark middle band`() {
        val bitmap = spread()
        val gutter = BookSplit.scanGutter(bitmap)
        assertTrue("a dark, uniform band in the middle is a gutter: ${BookSplit.debugScan(bitmap)}", gutter.found)
        assertTrue(
            "gutter sits inside the dark band (${gutter.ratio})",
            gutter.ratio in 0.45f..0.55f
        )
        bitmap.recycle()
    }

    @Test
    fun `uniform bitmap falls back to the exact middle`() {
        val bitmap = spread(darkBand = null)
        val gutter = BookSplit.scanGutter(bitmap)
        assertFalse("nothing stands out, so no gutter is claimed", gutter.found)
        assertEquals(0.5f, gutter.ratio, 0.0001f)
        bitmap.recycle()
    }

    @Test
    fun `split produces two pages whose widths add up to the spread`() {
        val bitmap = spread()
        val split = BookSplit.split(bitmap)
        assertEquals(2, split.pages.size)
        assertEquals("no pixels lost or duplicated", bitmap.width, split.left.width + split.right.width)
        assertEquals(bitmap.height, split.left.height)
        assertEquals(bitmap.height, split.right.height)
        assertTrue("left holds the left page", split.left.width > 1)
        assertTrue("right holds the right page", split.right.width > 1)
        split.left.recycle()
        split.right.recycle()
        bitmap.recycle()
    }

    @Test
    fun `two JPEGs come out per spread and the source file is untouched`() {
        val source = jpeg(spread())
        val file = File.createTempFile("book-split-", ".jpg").apply { writeBytes(source) }
        val before = file.readBytes()
        try {
            val (left, right) = BookSplit.splitToJpeg(before, BookSplit.Gutter(0.5f, found = false))
            val leftBitmap = BitmapFactory.decodeByteArray(left, 0, left.size)
            val rightBitmap = BitmapFactory.decodeByteArray(right, 0, right.size)
            assertTrue("left page decodes", leftBitmap.width > 1 && leftBitmap.height > 1)
            assertTrue("right page decodes", rightBitmap.width > 1 && rightBitmap.height > 1)
            assertEquals(leftBitmap.height, rightBitmap.height)
            leftBitmap.recycle()
            rightBitmap.recycle()

            assertArrayEquals("the picked image is read, never rewritten", before, file.readBytes())
        } finally {
            file.delete()
        }
    }

    /** A close-up of one page on a dark table: one text block, no fold. */
    private fun singlePage(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(45, 42, 40))
        val canvas = Canvas(bitmap)
        canvas.drawRect(
            60f, 40f, 760f, 560f,
            Paint().apply { color = Color.rgb(242, 238, 228) }
        )
        val ink = Paint().apply { color = Color.rgb(40, 38, 36); strokeWidth = 2f }
        var y = 90f
        while (y < 540f) {
            canvas.drawLine(120f, y, 700f, y, ink)
            y += 12f
        }
        return bitmap
    }

    @Test
    fun `a one-page close-up has no fold and is offered as one page`() {
        val bitmap = singlePage()
        val gutter = BookSplit.scanGutter(bitmap)
        assertFalse("there is no two-page fold to find: ${BookSplit.debugScan(bitmap)}", gutter.found)
        assertEquals("the line sits in the middle for review", 0.5f, gutter.ratio, 0.001f)
        assertTrue("one text block + no valley is a single page", gutter.singlePage)
        bitmap.recycle()
    }

    @Test
    fun `a uniform bitmap is not offered as one page`() {
        val bitmap = spread(darkBand = null)
        val gutter = BookSplit.scanGutter(bitmap)
        assertFalse(gutter.found)
        assertFalse("no text and no page shape", gutter.singlePage)
        bitmap.recycle()
    }

    @Test
    fun `decode samples a large spread to at most 3000 px on the long side`() {
        val big = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
        big.eraseColor(Color.WHITE)
        val decoded = BookSplit.decode(jpeg(big))
        assertTrue(
            "long side ${decoded.width}x${decoded.height} must stay under the cap",
            maxOf(decoded.width, decoded.height) <= BookSplit.MAX_LONG_SIDE
        )
        decoded.recycle()
    }
}
