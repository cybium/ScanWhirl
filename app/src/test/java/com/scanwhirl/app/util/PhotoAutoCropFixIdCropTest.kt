package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import kotlin.math.abs

/**
 * fixidcrop: a photo picked from the gallery is cut out and straightened by the app's own page
 * normalisation ([Deskew]) before the person sees it on the crop screen. No new detection code:
 * these tests prove the existing detector is reused and that a photo without a card is untouched.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoAutoCropFixIdCropTest {

    private val cardColour = Color.rgb(232, 232, 232)
    private val tableColour = Color.rgb(27, 42, 107)

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()

    /** A card lying on a dark table: the case the owner photographed. */
    private fun photoOfCardOnTable(
        width: Int = 1200,
        height: Int = 900,
        card: RectF = RectF(300f, 250f, 900f, 630f)
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(tableColour)
            canvas.drawRect(card, Paint().apply { color = cardColour })
            // A card is not a blank sheet: a few darker marks inside must stay inside the crop.
            val ink = Paint().apply { color = Color.rgb(60, 60, 70) }
            for (i in 1..4) {
                val y = card.top + card.height() * (i / 5f)
                canvas.drawRect(
                    card.left + card.width() * 0.12f, y - 4f,
                    card.left + card.width() * 0.6f, y + 4f, ink
                )
            }
            return jpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertClose(expected: Int, actual: Int, tolerance: Int = 30) {
        assertTrue(
            "expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        )
    }

    @Test
    fun `the card is cut out of the photo and straightened`() {
        val source = photoOfCardOnTable()
        val result = PhotoAutoCrop.crop(source)

        assertTrue("the card was found and cropped", result.cropped)
        val cropped = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size))
        try {
            // The card is 600x380 inside the 1200x900 photo: the table is gone.
            assertTrue(
                "the crop is the card, not the whole photo (${cropped.width}x${cropped.height})",
                cropped.width < 900 && cropped.height < 700
            )
            assertTrue("the card is big enough", cropped.width > 450 && cropped.height > 280)
            for (fx in floatArrayOf(0.1f, 0.5f, 0.9f)) {
                for (fy in floatArrayOf(0.1f, 0.5f, 0.9f)) {
                    val pixel = cropped.getPixel(
                        (cropped.width * fx).toInt().coerceIn(0, cropped.width - 1),
                        (cropped.height * fy).toInt().coerceIn(0, cropped.height - 1)
                    )
                    // The inner 80% of the crop is card, never table.
                    if (fx == 0.1f || fx == 0.9f || fy == 0.1f || fy == 0.9f) {
                        assertClose(cardColour, pixel, tolerance = 70)
                    }
                }
            }
        } finally {
            cropped.recycle()
        }
    }

    @Test
    fun `a photo without a card is left exactly as it is`() {
        val plain = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val bytes = try {
            Canvas(plain).drawColor(Color.rgb(120, 120, 120))
            jpeg(plain)
        } finally {
            plain.recycle()
        }

        val result = PhotoAutoCrop.crop(bytes)

        assertFalse("there is no card edge to trust", result.cropped)
        assertArrayEquals("an unchanged photo keeps its bytes", bytes, result.bytes)
    }

    @Test
    fun `a small bright patch is not mistaken for a card`() {
        val plain = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val canvas = Canvas(plain)
            canvas.drawColor(Color.rgb(120, 120, 120))
            canvas.drawRect(RectF(560f, 410f, 660f, 510f), Paint().apply { color = Color.WHITE })
            jpeg(plain)
        } finally {
            plain.recycle()
        }

        val result = PhotoAutoCrop.crop(bytes)

        assertFalse("a 1% patch is not a page", result.cropped)
        assertEquals("nothing is cut wrongly", bytes.size, result.bytes.size)
    }

    @Test
    fun `the detection is the existing page normalisation`() {
        // The same bytes [Deskew] detects must be what the auto-crop uses; no second detector.
        val source = photoOfCardOnTable()
        val quad = requireNotNull(Deskew.detectQuad(source)) { "the test photo must have a card quad" }
        val warped = Deskew.warp(source, quad)

        val result = PhotoAutoCrop.crop(source)

        assertTrue("the auto-crop is the existing quad warp", result.bytes.contentEquals(warped))
    }
}
