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
        assertTrue("a dark, uniform band in the middle is a gutter", gutter.found)
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
        // Portrait: the shape rule says one page (a landscape close-up would be a spread).
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(45, 42, 40))
        val canvas = Canvas(bitmap)
        canvas.drawRect(
            40f, 60f, 560f, 740f,
            Paint().apply { color = Color.rgb(242, 238, 228) }
        )
        val ink = Paint().apply { color = Color.rgb(40, 38, 36); strokeWidth = 2f }
        var y = 110f
        while (y < 720f) {
            canvas.drawLine(100f, y, 500f, y, ink)
            y += 12f
        }
        return bitmap
    }

    @Test
    fun `a one-page close-up has no fold and is offered as one page`() {
        val bitmap = singlePage()
        val gutter = BookSplit.scanGutter(bitmap)
        assertFalse("there is no two-page fold to find", gutter.found)
        assertEquals("the line sits in the middle for review", 0.5f, gutter.ratio, 0.001f)
        assertTrue("one text block + no valley is a single page", gutter.singlePage)
        bitmap.recycle()
    }

    /** Two text pages side by side, a dark narrow gutter at the middle. */
    private fun textSpread(width: Int = 900, height: Int = 600): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(242, 238, 228))
        val ink = Paint().apply { color = Color.rgb(40, 38, 36); strokeWidth = 2f }
        for (side in 0..1) {
            val left = if (side == 0) width * 0.08f else width * 0.56f
            val right = if (side == 0) width * 0.44f else width * 0.92f
            var y = height * 0.12f
            while (y < height * 0.88f) {
                canvas.drawLine(left, y, right, y, ink)
                y += height * 0.035f
            }
        }
        return bitmap
    }

    @Test
    fun `a horizontal-text spread is split at its gutter`() {
        val bitmap = textSpread()
        val analysis = BookSplit.analyse(bitmap)
        assertFalse("upright text needs no rotation", analysis.textVertical)
        assertTrue("a spread is found", analysis.gutter.found)
        assertEquals(0.5f, analysis.gutter.ratio, 0.05f)
        val split = BookSplit.split(bitmap, analysis.gutter)
        assertEquals(2, split.pages.size)
        split.left.recycle(); split.right.recycle(); bitmap.recycle()
    }

    /**
     * A pale band inside the right page's text (text lines cross it) is not the spine: the
     * valley must be darker than BOTH text blocks and the cut must not run along a text column.
     */
    private fun spreadWithTextCrossingGap(): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(245, 245, 245))
        // Left page slightly dimmer than the right, like a photographed page in shadow.
        canvas.drawRect(0f, 0f, 440f, 600f, Paint().apply { color = Color.rgb(200, 200, 200) })
        val ink = Paint().apply { color = Color.rgb(35, 35, 35); strokeWidth = 3f }
        var y = 60f
        while (y < 540f) {
            canvas.drawLine(40f, y, 400f, y, ink)
            canvas.drawLine(500f, y, 860f, y, ink)
            y += 24f
        }
        // The real spine: a continuous dark gap with no text across it.
        canvas.drawRect(410f, 0f, 440f, 600f, Paint().apply { color = Color.rgb(90, 90, 90) })
        // A pale "gutter-looking" band inside the right text; the lines below cross it.
        canvas.drawRect(560f, 0f, 585f, 600f, Paint().apply { color = Color.rgb(200, 200, 200) })
        y = 60f
        while (y < 540f) {
            canvas.drawLine(500f, y, 860f, y, ink)
            y += 24f
        }
        return bitmap
    }

    @Test
    fun `a cut must not cross text lines`() {
        val bitmap = spreadWithTextCrossingGap()
        val analysis = BookSplit.analyse(bitmap)
        assertTrue("the real spine is found", analysis.gutter.found)
        assertEquals(
            "the line is the left-side spine, not the pale band inside the right text",
            0.48f, analysis.gutter.ratio, 0.05f
        )
        assertTrue(
            "the pale text-crossed band must not be chosen",
            kotlin.math.abs(analysis.gutter.ratio - 0.63f) > 0.04f
        )
        bitmap.recycle()
    }

    @Test
    fun `a sideways spread is detected by its vertical text and rotated`() {
        val upright = textSpread()
        val sideways = BookSplit.rotate(upright, 90)
        if (sideways !== upright) upright.recycle()
        val analysis = BookSplit.analyse(sideways)
        assertTrue("vertical text is recognised", analysis.textVertical)
        assertTrue("the rotation makes the text upright", analysis.rotation == 90 || analysis.rotation == 270)
        assertTrue("the spine is still found after the turn", analysis.gutter.found)
        assertEquals(0.5f, analysis.gutter.ratio, 0.06f)
        sideways.recycle()
    }

    @Test
    fun `a two-column single page is one page, not a split at the column gap`() {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(242, 238, 228))
        val ink = Paint().apply { color = Color.rgb(40, 38, 36); strokeWidth = 2f }
        for (column in 0..1) {
            val left = if (column == 0) 70f else 330f
            val right = if (column == 0) 270f else 530f
            var y = 100f
            while (y < 820f) {
                canvas.drawLine(left, y, right, y, ink)
                y += 26f
            }
        }
        val analysis = BookSplit.analyse(bitmap)
        assertTrue(
            "a column gap is not a spine on a portrait page",
            analysis.gutter.singlePage
        )
        assertFalse("no split is offered", analysis.gutter.found)
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
