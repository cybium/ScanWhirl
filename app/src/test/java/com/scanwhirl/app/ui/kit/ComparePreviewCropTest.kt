package com.scanwhirl.app.ui.kit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Result-preview defect (demo 79460c29): a missing Before must not leave a white paper block,
 * a blank rendered page must not be painted white, and Compress/Resize must zoom the SAME
 * content region on both sides instead of a white page margin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ComparePreviewCropTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun page(name: String, w: Int, h: Int, ink: Boolean, inset: Float = 0.12f): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        if (ink) {
            val x0 = (w * inset).toInt()
            val x1 = (w * (1f - inset)).toInt()
            val y0 = (h * inset).toInt()
            val y1 = (h * (1f - inset)).toInt()
            for (y in y0 until y1) for (x in x0 until x1) {
                if ((x + y) % 5 == 0) bmp.setPixel(x, y, Color.BLACK)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return File(app.cacheDir, "rp_${name}_${System.nanoTime()}.jpg").apply {
            writeBytes(out.toByteArray())
        }
    }

    private fun model(before: String?, after: String?, beforeCaption: String = "1.1 MB") =
        CompareModel(
            outcome = "Resized",
            before = ComparePane(
                imagePath = before, caption = beforeCaption,
                emptyText = app.getString(R.string.cc_no_before_image)
            ),
            after = ComparePane(
                imagePath = after, caption = "169 KB",
                emptyText = app.getString(R.string.cc_no_after_image)
            ),
            mode = CompareMode.ZOOM_STACKED
        )

    private fun bindAndWait(view: CompareView, m: CompareModel) {
        val done = CountDownLatch(1)
        view.onDecodeComplete = { done.countDown() }
        view.bind(m, kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
        ), mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue("decode finished", done.await(20, TimeUnit.SECONDS))
    }

    @Test
    fun `null before hides the paper host and keeps the honest empty text on the canvas`() {
        val view = CompareView(app)
        bindAndWait(view, model(null, page("real_after", 600, 800, ink = true).path))
        assertEquals(View.GONE, view.beforeImageView().visibility)
        assertEquals(View.GONE, view.beforePageFrameView().visibility)
        assertEquals(View.VISIBLE, view.pageFrameView().visibility)
        assertEquals(app.getString(R.string.cc_no_before_image), view.emptyLabel().text.toString())
        val canvas = view.compareFrame().background as ColorDrawable
        assertEquals(ContextCompat.getColor(app, R.color.sw_cc_canvas), canvas.color)
        assertFalse("canvas is never white", canvas.color == Color.WHITE)
    }

    @Test
    fun `content box ignores near white margins and blank pages`() {
        val view = CompareView(app)
        val inked = android.graphics.BitmapFactory.decodeFile(
            page("boxed", 1200, 1600, ink = true, inset = 0.28f).path
        )
        try {
            val box = view.contentBox(inked)
            assertNotNull("ink found", box)
            assertTrue("left margin ignored: $box", box!!.left > 0.2f)
            assertTrue("right margin ignored: $box", box.right < 0.8f)
            assertTrue("top margin ignored: $box", box.top > 0.2f)
            assertTrue("bottom margin ignored: $box", box.bottom < 0.8f)
        } finally {
            inked.recycle()
        }
        val blank = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        blank.eraseColor(Color.WHITE)
        assertNull("a white page has no content box", view.contentBox(blank))
        blank.recycle()
    }

    @Test
    fun `before and after share one content rect and it lands on ink`() {
        val view = CompareView(app)
        val rects = mutableListOf<Rect>()
        val relatives = mutableListOf<RectF>()
        view.cropProbe = { rects.add(it) }
        view.relativeCropProbe = { relatives.add(it) }
        bindAndWait(view, model(
            page("c_before", 1200, 1600, ink = true, inset = 0.28f).path,
            page("c_after", 1200, 1600, ink = true, inset = 0.31f).path
        ))
        assertEquals("one crop per pane", 2, rects.size)
        assertEquals("identical relative crop on both pages", rects[0], rects[1])
        assertEquals("one shared rect", 1, relatives.size)
        val rel = relatives.single()
        assertTrue("crop starts inside the page: $rel", rel.left > 0f && rel.top > 0f)
        assertTrue("crop ends inside the page: $rel", rel.right < 1f && rel.bottom < 1f)
        val shown = view.displayedBitmap()
        assertNotNull("after page rendered", shown)
        var dark = 0
        for (y in 0 until shown!!.height step 4) {
            for (x in 0 until shown.width step 4) {
                if (Color.red(shown.getPixel(x, y)) < 160) dark++
            }
        }
        assertTrue("the zoom shows real ink, not a white page", dark > 0)
    }

    @Test
    fun `a blank rendered page is hidden and its numbers stay in the caption`() {
        val view = CompareView(app)
        bindAndWait(view, model(
            page("ink_before", 800, 1000, ink = true).path,
            page("white_after", 800, 1000, ink = false).path
        ))
        assertEquals(View.GONE, view.pageImageView().visibility)
        assertEquals(View.GONE, view.pageFrameView().visibility)
        assertEquals(View.VISIBLE, view.beforePageFrameView().visibility)
        assertTrue("blank side recorded", view.blankFlags().second)
        assertFalse("inked side kept", view.blankFlags().first)
        assertEquals(
            app.getString(R.string.cc_blank_page_after, "169 KB"),
            view.emptyLabel().text.toString()
        )
    }
}
