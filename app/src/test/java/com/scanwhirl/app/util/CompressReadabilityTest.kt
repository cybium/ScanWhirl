package com.scanwhirl.app.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CP1 defect 3: the old fitter shrank pages to 64 px, which is noise, not text. The new floor
 * stops the shrink and reports the honesty flag; cancellation is checked inside the encode loops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompressReadabilityTest {

    private fun solidPage(w: Int, h: Int): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.WHITE) }

    @Test
    fun `an impossible target stops at the readable floor and says so`() {
        val page = solidPage(800, 1200)
        val fit = ImageTools.compressToFit(
            page, maxBytes = 1L, minLongSide = ImageTools.MIN_READABLE_LONG_SIDE
        )
        assertTrue("the floor was hit", fit.hitFloor)
        assertEquals(
            "the long side never drops below the floor",
            ImageTools.MIN_READABLE_LONG_SIDE,
            maxOf(fit.width, fit.height)
        )
        assertTrue("a real, readable rendition is still returned", fit.bytes.isNotEmpty())
        page.recycle()
    }

    @Test
    fun `a reachable target is still fitted without a floor miss`() {
        val page = solidPage(600, 800)
        val fit = ImageTools.compressToFit(
            page, maxBytes = 200_000L, minLongSide = ImageTools.MIN_READABLE_LONG_SIDE
        )
        assertFalse("no miss when the first rendition fits", fit.hitFloor)
        assertEquals(600, fit.width)
        assertEquals(800, fit.height)
        page.recycle()
    }

    @Test(expected = CancellationException::class)
    fun `cancel is observed inside the encode loop`() {
        val page = solidPage(800, 1200)
        try {
            ImageTools.compressToFit(
                page, maxBytes = 1L, minLongSide = ImageTools.MIN_READABLE_LONG_SIDE,
                isCancelled = { true }
            )
        } finally {
            page.recycle()
        }
    }
}
