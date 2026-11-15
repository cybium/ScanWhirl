package com.scanwhirl.app.ui.book

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.scanwhirl.app.util.BookSplit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device rotation decision: every candidate turn is recognised and the one that reads wins.
 * The fake recognizer only "reads" the orientation whose top edge carries the marker, so a pass
 * proves the code actually tried both 90/270 (sideways) and 0/180 (upright) candidates.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TextRotationTest {

    @After
    fun resetSeams() {
        TextRotation.wordCounter = null
        TextRotation.chooser = null
    }

    /** White page with a coloured bar along its top edge (the "text" only upright). */
    private fun markedPage(): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).apply {
            drawRect(
                0f, 0f, bitmap.width.toFloat(), bitmap.height * 0.10f,
                Paint().apply { color = Color.RED }
            )
            drawRect(
                bitmap.width * 0.15f, bitmap.height * 0.35f,
                bitmap.width * 0.85f, bitmap.height * 0.75f,
                Paint().apply { color = Color.DKGRAY }
            )
        }
        return bitmap
    }

    private fun barIsOnTop(bitmap: Bitmap): Boolean {
        var red = 0
        val y0 = 0
        val y1 = (bitmap.height * 0.14f).toInt().coerceAtLeast(1)
        for (y in y0 until y1) {
            for (x in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 180 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) {
                    red++
                }
            }
        }
        return red > (bitmap.width / 4) * (y1 - y0) / 2
    }

    @Test
    fun `a sideways page picks 270 only when that turn reads`() = runBlocking {
        val upright = markedPage()
        val sideways = BookSplit.rotate(upright, 90) // bar on the right; 270 turns it up
        val calls = AtomicInteger()
        TextRotation.wordCounter = { page ->
            calls.incrementAndGet()
            if (barIsOnTop(page)) 9 else 0
        }

        assertEquals(270, TextRotation.chooseRotation(sideways, textVertical = true))
        assertEquals("both 90 and 270 must be recognised", 2, calls.get())
        sideways.recycle()
        upright.recycle()
    }

    @Test
    fun `an upright page picks 0 when 0 reads and 180 does not`() = runBlocking {
        val upright = markedPage()
        val calls = AtomicInteger()
        TextRotation.wordCounter = { page ->
            calls.incrementAndGet()
            if (barIsOnTop(page)) 6 else 0
        }

        assertEquals(0, TextRotation.chooseRotation(upright, textVertical = false))
        assertEquals("both 0 and 180 must be recognised", 2, calls.get())
        upright.recycle()
    }

    @Test
    fun `an unreadable crop leaves the engine's choice alone`() = runBlocking {
        val sideways = BookSplit.rotate(markedPage(), 270)
        TextRotation.wordCounter = { 0 }
        assertNull(TextRotation.chooseRotation(sideways, textVertical = true))
        sideways.recycle()
    }

    @Test
    fun `the fake recognizer really is orientation sensitive`() {
        val upright = markedPage()
        assertTrue("marker is on top", barIsOnTop(upright))
        val down = BookSplit.rotate(upright, 180)
        assertTrue("marker is not on top after 180", !barIsOnTop(down))
        down.recycle()
        upright.recycle()
    }
}
