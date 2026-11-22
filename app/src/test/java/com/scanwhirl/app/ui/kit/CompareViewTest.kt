package com.scanwhirl.app.ui.kit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lane RS: the proof surface is a STACKED zoom pair (Before above After, full width), one After
 * page for mark tools, and no image at all for state-only tools. Off-main bounded decode and the
 * generation-guarded recycle contract from lane C are unchanged.
 */
abstract class CompareViewBase {

    protected val app: Application get() = ApplicationProvider.getApplicationContext()

    protected fun model(
        beforePath: String? = null,
        afterPath: String? = null,
        beforeCaption: String = "290 KB",
        afterCaption: String = "113 KB",
        page: String? = null,
        mode: CompareMode = CompareMode.ZOOM_STACKED
    ) = CompareModel(
        outcome = "Compressed · 113 KB",
        before = ComparePane(
            imagePath = beforePath, caption = beforeCaption,
            emptyText = app.getString(R.string.cc_no_before_image)
        ),
        after = ComparePane(
            imagePath = afterPath, caption = afterCaption,
            emptyText = app.getString(R.string.cc_no_after_image)
        ),
        pageLabel = page,
        mode = mode
    )

    protected fun bindAndWait(view: CompareView, m: CompareModel) {
        val done = CountDownLatch(1)
        view.onDecodeComplete = { done.countDown() }
        view.bind(m, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined)
        assertTrue("compare decoded and rendered", done.await(20, TimeUnit.SECONDS))
    }

    private fun jpeg(name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return File(app.cacheDir, "cc_${name}_${System.nanoTime()}.jpg").apply {
            writeBytes(out.toByteArray())
        }
    }

    @Test
    fun `before and after are both visible as full width stacked labels`() {
        val view = CompareView(app)
        bindAndWait(view, model())
        assertEquals(View.VISIBLE, view.beforeButton().visibility)
        assertEquals(View.VISIBLE, view.afterButton().visibility)
        assertEquals(app.getString(R.string.cc_before), view.beforeCaptionText())
        assertEquals(app.getString(R.string.rs_after_label), view.captionText())
        assertEquals("Compressed · 113 KB", view.outcomeText())
        val zoom = view.findViewById<View>(R.id.rsZoomCaption)
        assertEquals(View.VISIBLE, zoom.visibility)
        assertEquals(app.getString(R.string.rs_zoom_caption),
            (zoom as android.widget.TextView).text.toString())
    }

    @Test
    fun `the two zoom panes use the same crop rectangle`() {
        val view = CompareView(app)
        val rects = mutableListOf<Rect>()
        view.cropProbe = { rects.add(it) }
        bindAndWait(view, model(
            beforePath = jpeg("rb", 1600, 2100).path,
            afterPath = jpeg("ra", 1600, 2100).path
        ))
        assertEquals("one crop per pane", 2, rects.size)
        assertEquals("identical crop on both pages", rects[0], rects[1])
        val width = rects[0].right - rects[0].left
        assertTrue("about 2.5x zoom (40% of page width): ${rects[0]}",
            width in 620..680)
    }

    @Test
    fun `a missing original is an honest text state, not a blank white page`() {
        val view = CompareView(app)
        bindAndWait(view, model(afterPath = jpeg("after", 100, 140).path))
        assertEquals(app.getString(R.string.cc_no_before_image), view.emptyLabel().text.toString())
        assertEquals(View.GONE, view.beforeImageView().visibility)
        assertEquals(View.VISIBLE, view.pageImageView().visibility)
    }

    @Test
    fun `TalkBack names both panes and keeps 48dp label targets`() {
        val view = CompareView(app)
        bindAndWait(view, model())
        assertTrue("before pane announces the outcome",
            view.beforeButton().contentDescription.toString().contains("Compressed · 113 KB"))
        assertTrue("after pane announces the outcome",
            view.afterButton().contentDescription.toString().contains("Compressed · 113 KB"))
        val target = 48f * app.resources.displayMetrics.density - 1
        assertTrue("Before is a 48dp target", view.beforeButton().minimumHeight >= target)
        assertTrue("After is a 48dp target", view.afterButton().minimumHeight >= target)
    }

    @Test
    fun `the page host is the dark canvas with a 1dp white-alpha edge, never a white host`() {
        val view = CompareView(app)
        bindAndWait(view, model())
        val host = view.compareFrame().background as ColorDrawable
        assertEquals(
            "canvas is #1E252A, not white",
            ContextCompat.getColor(app, R.color.sw_cc_canvas), host.color
        )
        assertFalse("host is not white", host.color == Color.WHITE)
        val page = view.pageFrameView().background as android.graphics.drawable.GradientDrawable
        assertNotNull("page has a white paper fill with an edge", page)
        assertEquals(
            "edge is white at 24% alpha on #1E252A",
            Color.parseColor("#3DFFFFFF"), ContextCompat.getColor(app, R.color.sw_cc_page_edge)
        )
    }

    @Test(timeout = 30_000)
    fun `rebinding recycles the bitmaps the previous bind decoded`() {
        val view = CompareView(app)
        bindAndWait(view, model(
            beforePath = jpeg("rb-b1", 200, 300).path,
            afterPath = jpeg("rb-a1", 160, 240).path
        ))
        val (firstBefore, firstAfter) = view.heldBitmaps()
        assertNotNull("first before decoded", firstBefore)
        assertNotNull("first after decoded", firstAfter)
        assertFalse("first pages still live", firstBefore!!.isRecycled || firstAfter!!.isRecycled)

        bindAndWait(view, model(
            beforePath = jpeg("rb-b2", 220, 320).path,
            afterPath = jpeg("rb-a2", 180, 260).path
        ))
        assertTrue("previous before freed", firstBefore!!.isRecycled)
        assertTrue("previous after freed", firstAfter!!.isRecycled)
        val (secondBefore, secondAfter) = view.heldBitmaps()
        assertNotNull("second pages decoded", secondBefore)
        assertNotNull(secondAfter)
        assertFalse("new pages are live", secondBefore!!.isRecycled || secondAfter!!.isRecycled)
    }

    @Test(timeout = 30_000)
    fun `detaching recycles both decoded bitmaps and cancels the job`() {
        val view = CompareView(app)
        bindAndWait(view, model(
            beforePath = jpeg("dt-b", 200, 300).path,
            afterPath = jpeg("dt-a", 160, 240).path
        ))
        val (before, after) = view.heldBitmaps()
        val generation = view.decodeGeneration()
        view.onDetachedFromWindow()
        assertTrue("before freed on detach", before!!.isRecycled)
        assertTrue("after freed on detach", after!!.isRecycled)
        assertNull("nothing held after detach", view.heldBitmaps().first)
        assertNull("nothing displayed after detach", view.displayedBitmap())
        assertEquals("in-flight results invalidated", generation + 1, view.decodeGeneration())
    }

    @Test(timeout = 30_000)
    fun `no recycled bitmap is drawn after a rebind`() {
        val view = CompareView(app)
        bindAndWait(view, model(
            beforePath = jpeg("nr-b1", 200, 300).path,
            afterPath = jpeg("nr-a1", 160, 240).path
        ))
        val (oldBefore, oldAfter) = view.heldBitmaps()
        bindAndWait(view, model(
            beforePath = jpeg("nr-b2", 220, 320).path,
            afterPath = jpeg("nr-a2", 180, 260).path
        ))
        val recycled = setOf(oldBefore, oldAfter)
        val shownAfter = view.displayedBitmap()
        assertNotNull("new page displayed", shownAfter)
        assertFalse("after pane is not a recycled bitmap", recycled.any { it === shownAfter })
        assertFalse("after pane bitmap is live", shownAfter!!.isRecycled)
        val shownBefore = (view.beforeImageView().drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        assertNotNull("before page displayed", shownBefore)
        assertFalse("before pane is not a recycled bitmap", recycled.any { it === shownBefore })
        assertFalse("before pane bitmap is live", shownBefore!!.isRecycled)
    }

    @Test
    fun `the stacked controls stay usable at 130 and 200 percent font`() {
        listOf(1.3f, 2.0f).forEach { scale ->
            RuntimeEnvironment.setFontScale(scale)
            try {
                val view = CompareView(app)
                bindAndWait(view, model())
                assertEquals(app.getString(R.string.cc_before), view.beforeCaptionText())
                assertEquals(app.getString(R.string.rs_after_label), view.captionText())
                assertTrue("48dp targets survive $scale", view.beforeButton().minimumHeight >=
                    48f * app.resources.displayMetrics.density - 1)
                assertTrue("outcome still present at $scale", view.outcomeText().isNotBlank())
            } finally {
                RuntimeEnvironment.setFontScale(1.0f)
            }
        }
    }

    @Test(timeout = 30_000)
    fun `mark preview includes the full page and bottom right mark`() {
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (y in 270 until 300) for (x in 170 until 200) bmp.setPixel(x, y, Color.BLACK)
        val file = File(app.cacheDir, "rs_mark.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bmp.recycle()
        val view = CompareView(app)
        try {
            bindAndWait(view, model(afterPath = file.path, mode = CompareMode.AFTER_ONLY))
            assertEquals(Rect(0, 0, 200, 300), view.zoomRects().second)
            val shown = requireNotNull(view.displayedBitmap())
            assertTrue(Color.red(shown.getPixel(190, 290)) < 30)
        } finally { view.onDetachedFromWindow() }
    }

    @Test(timeout = 30_000)
    fun `protection is checked immediately before each decode`() {
        val file = jpeg("denied", 200, 300)
        val view = CompareView(app)
        val done = CountDownLatch(1)
        var decoded = 0
        view.decodeProbe = { decoded++ }
        view.onDecodeComplete = { done.countDown() }
        view.bind(model(afterPath = file.path),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined, canDecode = { false })
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(0, decoded)
        assertNull(view.displayedBitmap())
    }

    @Test(timeout = 30_000)
    fun `both page images decode bounded and off the main thread`() {
        val mainThread = Looper.getMainLooper().thread
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val maxDims = Collections.synchronizedList(mutableListOf<Int>())
        val latch = CountDownLatch(2)
        val previous = ImageTools.thumbProbe
        ImageTools.thumbProbe = { maxDim, _, _, _ ->
            threads.add(Thread.currentThread())
            maxDims.add(maxDim)
            latch.countDown()
        }
        try {
            val view = CompareView(app)
            bindAndWait(view, model(
                beforePath = jpeg("b", 1600, 1200).path,
                afterPath = jpeg("a", 900, 700).path
            ))
            assertTrue("both images were decoded", latch.await(10, TimeUnit.SECONDS))
            assertEquals(2, threads.size)
            assertTrue("decode never runs on Main", threads.all { it != mainThread })
            assertTrue("decode is bounded to 1200px", maxDims.all { it == 1200 })
        } finally {
            ImageTools.thumbProbe = previous
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CompareViewEnTest : CompareViewBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompareViewHiTest : CompareViewBase() {

    @Test
    fun `Hindi labels and caption are localized`() {
        assertEquals("पहले", app.getString(R.string.cc_before))
        assertEquals("बाद में", app.getString(R.string.cc_after))
        assertEquals("पहले वाला देखें", app.getString(R.string.cc_show_before))
        assertEquals("मूल पन्ना उपलब्ध नहीं है।", app.getString(R.string.cc_no_before_image))
        val view = CompareView(app)
        bindAndWait(view, model())
        assertEquals("पहले", view.beforeCaptionText())
        assertEquals("बाद में", view.captionText())
    }
}
