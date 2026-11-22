package com.scanwhirl.app.ui.kit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lane C: the reusable Before | After surface. Tested first at 100% in EN and HI, then the
 * off-main bounded decode and the dark-canvas guard (council §4/§6).
 */
abstract class CompareViewBase {

    protected val app: Application get() = ApplicationProvider.getApplicationContext()

    protected fun model(
        beforePath: String? = null,
        afterPath: String? = null,
        beforeCaption: String = "290 KB",
        afterCaption: String = "113 KB",
        page: String? = null
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
        pageLabel = page
    )

    /** Bind and wait for the off-main decode to be rendered (deterministic, no looper guessing). */
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
    fun `default is After and the caption carries the after numbers`() {
        val view = CompareView(app)
        bindAndWait(view, model())
        assertTrue("default pane is After", view.isShowingAfter())
        assertEquals("113 KB", view.captionText())
        assertEquals("Compressed · 113 KB", view.outcomeText())
        assertTrue("After tab selected", view.afterButton().isSelected)
        assertFalse("Before tab not selected", view.beforeButton().isSelected)
    }

    @Test
    fun `tapping Before swaps the caption and the selected tab`() {
        val view = CompareView(app)
        bindAndWait(view, model())
        view.showBefore()
        assertEquals("290 KB", view.captionText())
        assertTrue("Before tab selected", view.beforeButton().isSelected)
        assertFalse(view.afterButton().isSelected)
        view.showAfter()
        assertEquals("113 KB", view.captionText())
        assertTrue(view.afterButton().isSelected)
    }

    @Test
    fun `a missing original is an honest text state, not a blank white page`() {
        val view = CompareView(app)
        bindAndWait(view, model(afterPath = jpeg("after", 100, 140).path))
        view.showBefore()
        assertEquals(app.getString(R.string.cc_no_before_image), view.emptyLabel().text.toString())
        assertEquals(View.GONE, view.pageImageView().visibility)
        view.showAfter()
        assertEquals(View.VISIBLE, view.pageImageView().visibility)
    }

    @Test
    fun `TalkBack names the caption and both 48dp tabs as buttons`() {
        val view = CompareView(app)
        bindAndWait(view, model())
        assertTrue(
            "caption announces outcome and the current numbers",
            view.findViewById<View>(R.id.ccCaption).contentDescription.toString()
                .contains("Compressed · 113 KB") &&
                view.findViewById<View>(R.id.ccCaption).contentDescription.toString()
                    .contains("113 KB")
        )
        assertEquals(app.getString(R.string.cc_show_before), view.beforeButton().contentDescription)
        assertEquals(app.getString(R.string.cc_show_after), view.afterButton().contentDescription)
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
        assertEquals("बाद में", view.afterButton().text.toString())
        assertEquals("पहले", view.beforeButton().text.toString())
    }
}
