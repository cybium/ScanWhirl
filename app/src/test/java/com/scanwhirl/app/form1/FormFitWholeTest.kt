package com.scanwhirl.app.form1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.FormCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import kotlin.math.abs

/**
 * FORMPIX: the owner's defect is that Form photo acted like a crop tool. Exam forms need exact
 * pixels and a KB band, so the default must keep the WHOLE source (uniform scale + white
 * letterbox) and cropping must be an explicit, separate choice. Fixtures are fictional.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class FormFitWholeTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun controller(): ActivityController<FormReadyActivity> =
        Robolectric.buildActivity(FormReadyActivity::class.java).setup()

    private fun waitUntil(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        idle()
        return condition()
    }

    private fun solid(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }

    /** White sheet, a centred dark circle and two dark corner blocks that a crop would cut off. */
    private fun markedSource(): Bitmap {
        val bmp = solid(600, 500)
        val canvas = Canvas(bmp)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 20) }
        canvas.drawCircle(300f, 250f, 100f, ink)
        canvas.drawRect(0f, 0f, 40f, 40f, ink)
        canvas.drawRect(560f, 460f, 600f, 500f, ink)
        return bmp
    }

    private fun isWhite(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r > 235 && g > 235 && b > 235
    }

    private fun isDark(color: Int): Boolean {
        val lum = (((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)) / 3
        return lum < 120
    }

    private fun hasDark(bmp: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        for (y in y0 until minOf(y1, bmp.height)) {
            for (x in x0 until minOf(x1, bmp.width)) {
                if (isDark(bmp.getPixel(x, y))) return true
            }
        }
        return false
    }

    private fun darkBox(bmp: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): RectF {
        var minX = x1
        var maxX = -1
        var minY = y1
        var maxY = -1
        for (y in y0 until minOf(y1, bmp.height)) {
            for (x in x0 until minOf(x1, bmp.width)) {
                if (isDark(bmp.getPixel(x, y))) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        return RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
    }

    private fun textOf(activity: FormReadyActivity, id: Int): String =
        activity.findViewById<TextView>(id).text.toString()

    @Test(timeout = 30_000)
    fun `fit whole is the default for every preset and hides the crop editor`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            assertFalse("Fit must be the default", a.cropModeForTest())
            assertEquals(View.GONE, a.findViewById<View>(R.id.formReadyCrop).visibility)
            assertTrue(a.findViewById<View>(R.id.formReadyModeFit).isSelected)
            assertFalse(a.findViewById<View>(R.id.formReadyModeCrop).isSelected)

            a.useBitmapForTest(solid(400, 300), "photo.jpg")
            idle()
            assertFalse("a picked photo must not switch to Crop", a.cropModeForTest())
            assertEquals(View.GONE, a.findViewById<View>(R.id.formReadyCrop).visibility)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `fit whole keeps every corner letterboxes to exact pixels and never stretches`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val src = markedSource()
            a.useBitmapForTest(src, "fit.jpg")
            idle()
            assertEquals("Fit must use the entire source", RectF(0f, 0f, 600f, 500f), a.activeSourceRectForTest())

            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            assertEquals("export must not crop", RectF(0f, 0f, 600f, 500f), a.exportCropRectForTest())
            val out = BitmapFactory.decodeFile(a.lastOutputForTest()!!.path)
            assertNotNull("decoded output missing", out)
            try {
                assertEquals(200, out.width)
                assertEquals(230, out.height)
                // Source 600x500 -> uniform scale 1/3 gives 200x167; the rest is white bands.
                assertTrue("top letterbox must be white", isWhite(out.getPixel(100, 5)))
                assertTrue("bottom letterbox must be white", isWhite(out.getPixel(100, 224)))
                assertTrue("top-left corner was cropped away", hasDark(out, 0, 20, 30, 50))
                assertTrue("bottom-right corner was cropped away", hasDark(out, 180, 200, 178, 200))
                val circle = darkBox(out, 40, 160, 60, 170)
                assertTrue("circle not found: $circle", circle.right > circle.left && circle.bottom > circle.top)
                val ratio = (circle.right - circle.left + 1) / (circle.bottom - circle.top + 1)
                assertTrue("stretched circle: $ratio", abs(ratio - 1f) < 0.12f)
            } finally {
                out.recycle()
            }
            src.recycle()
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `crop is opt in uses the snapped frame and hides again on fit`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.useBitmapForTest(solid(400, 300), "crop.jpg")
            idle()
            assertFalse(a.cropModeForTest())

            a.findViewById<View>(R.id.formReadyModeCrop).performClick()
            idle()
            assertTrue("Crop chip must opt in", a.cropModeForTest())
            assertEquals(View.VISIBLE, a.findViewById<View>(R.id.formReadyCrop).visibility)
            assertTrue(a.findViewById<View>(R.id.formReadyModeCrop).isSelected)

            val frame = RectF(40f, 30f, 200f, 180f)
            a.setCropRectForTest(frame)
            idle()
            val expected = RectF(FormCrop.snapToAspect(frame, 200f / 230f, 400, 300))
            assertEquals("active rect must be snapped in Crop mode", expected, a.activeSourceRectForTest())

            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            assertEquals("export must use the snapped crop", expected, a.exportCropRectForTest())

            a.findViewById<View>(R.id.formReadyModeFit).performClick()
            idle()
            assertFalse("Fit chip must switch back", a.cropModeForTest())
            assertEquals(View.GONE, a.findViewById<View>(R.id.formReadyCrop).visibility)
            assertEquals(RectF(0f, 0f, 400f, 300f), a.activeSourceRectForTest())
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `exam chips apply notice bands suggested pixels and always start in fit`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyExamUgPhoto).performClick()
            idle()
            assertEquals("neet_ug_photo", a.currentSpecIdForTest())
            assertEquals(350, a.currentWidthPxForTest())
            assertEquals(450, a.currentHeightPxForTest())
            assertEquals(10, a.currentMinKbForTest())
            assertEquals(200, a.currentMaxKbForTest())
            assertFalse(a.cropModeForTest())
            assertTrue("pixels must be marked suggested", textOf(a, R.id.formReadyExample).contains("Suggested"))

            a.findViewById<View>(R.id.formReadyExamPgSignature).performClick()
            idle()
            assertEquals("neet_pg_signature", a.currentSpecIdForTest())
            assertEquals(276, a.currentWidthPxForTest())
            assertEquals(118, a.currentHeightPxForTest())
            assertEquals(20, a.currentMinKbForTest())
            assertEquals(80, a.currentMaxKbForTest())
            assertFalse("exam signature cleanup stays opt-in", a.currentCleanupForTest())

            a.setCropModeForTest(true)
            idle()
            a.findViewById<View>(R.id.formReadyExamUgThumb).performClick()
            idle()
            assertFalse("choosing any preset returns to Fit", a.cropModeForTest())
            assertEquals(400, a.currentWidthPxForTest())
            assertEquals(150, a.currentHeightPxForTest())
            assertEquals(4, a.currentMinKbForTest())
            assertEquals(30, a.currentMaxKbForTest())
        } finally {
            c.pause().stop().destroy()
        }
    }
}
