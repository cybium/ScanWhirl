package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * API 24-27 have no ImageDecoder: JPEG goes through BitmapFactory plus an explicit
 * orientation Matrix. Every other class in this suite runs at API 35.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27])
class PageNormalizationApi27Test {
    @get:Rule val tmp = TemporaryFolder()

    private fun corners(bitmap: Bitmap, expected: List<Int>) {
        listOf(15 to 15, bitmap.width - 16 to 15, 15 to bitmap.height - 16,
            bitmap.width - 16 to bitmap.height - 16).forEachIndexed { i, (x, y) ->
            ImportFixtures.assertColor(expected[i], bitmap.getPixel(x, y))
        }
    }

    @Test fun `runs on the pre-ImageDecoder platform`() {
        assertEquals(27, android.os.Build.VERSION.SDK_INT)
    }

    @Test fun `JPEG EXIF matrix on API 27 matches the API 35 expectations`() {
        val expected = mapOf(
            2 to listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            3 to listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            4 to listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            5 to listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            6 to listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            7 to listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            8 to listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
        )
        for ((orientation, colors) in expected) {
            val source = ImportFixtures.oriented(File(tmp.root, "o$orientation.jpg"), orientation)
            val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
            try {
                assertEquals("$orientation", if (orientation >= 5) 80 else 120, bitmap.width)
                corners(bitmap, colors)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `4000x3000 JPEG with EXIF 6 is sampled and upright on API 27`() {
        val source = ImportFixtures.oriented(File(tmp.root, "phone.jpg"), 6, width = 4000, height = 3000)
        val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
        try {
            assertEquals(1500, bitmap.width)
            assertEquals(2000, bitmap.height)
            corners(bitmap, listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN))
        } finally { bitmap.recycle() }
    }

    @Test fun `invalid EXIF orientation is identity on API 27`() {
        val source = ImportFixtures.oriented(File(tmp.root, "bad.jpg"), 9)
        assertArrayEquals(source, ImageTools.normalizePage(source))
    }
}
