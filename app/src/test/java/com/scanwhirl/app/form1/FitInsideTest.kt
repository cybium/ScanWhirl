package com.scanwhirl.app.form1

import com.scanwhirl.app.util.FormCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * J1 / PROMPT 10: the harm each test prevents is named in its method. fitInside is the whole-photo
 * engine; a wrong answer either cuts a portrait, blows it up beyond the source, or pretends an
 * impossible form rule can be met.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FitInsideTest {

    @Test
    fun `a 1368 by 2431 portrait fits 450 by 800 inside the fictional range`() {
        val fit = FormCrop.fitInside(1368, 2431, 350, 600, 450, 800)
        assertTrue("no whole-photo size was found", fit.fits)
        assertEquals(450, fit.width)
        assertEquals(800, fit.height)
        assertTrue(fit.width in 350..600)
        assertTrue(fit.height in 450..800)
    }

    @Test
    fun `a small source is never upscaled into a bigger range`() {
        val fit = FormCrop.fitInside(100, 200, 350, 600, 450, 800)
        assertFalse("the engine invented pixels", fit.fits)
        assertTrue("it still reports the honest source size", fit.width <= 100 && fit.height <= 200)
    }

    @Test
    fun `an impossible range says it does not fit instead of cutting the photo`() {
        val fit = FormCrop.fitInside(600, 500, 1000, 1100, 1000, 1100)
        assertTrue("silently forcing a crop is worse than an honest refusal", fit.doesntFit)
    }

    @Test
    fun `fitting preserves the source aspect and adds no padding of its own`() {
        val source = 1368f / 2431f
        val fit = FormCrop.fitInside(1368, 2431, 350, 600, 450, 800)
        assertTrue(
            "the fit itself must not stretch or pad: ${fit.width}x${fit.height}",
            kotlin.math.abs(fit.aspect - source) < 0.005f
        )
        // Bars are a separate, explicit framing choice in the screen, never part of fitInside.
        assertEquals(450, fit.width)
        assertEquals(800, fit.height)
    }

    @Test
    fun `upscaling is allowed only when the caller explicitly opts in`() {
        val refused = FormCrop.fitInside(100, 200, 350, 600, 450, 800)
        val allowed = FormCrop.fitInside(100, 200, 350, 600, 450, 800, allowUpscale = true)
        assertTrue(refused.doesntFit)
        assertTrue(allowed.fits)
        assertEquals(400, allowed.width)
        assertEquals(800, allowed.height)
    }
}
