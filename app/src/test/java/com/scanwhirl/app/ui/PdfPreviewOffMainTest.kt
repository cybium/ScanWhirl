package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/**
 * C01: a locked scan's preview PDF is built off the main thread with a visible progress state,
 * and a rotation reuses the cached preview instead of encoding every page again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfPreviewOffMainTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var defaultPages: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private lateinit var defaultBuilder: (List<java.io.File>, java.io.File) -> Unit

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        defaultPages = PdfViewerActivity.pagesFactory
        defaultBuilder = PdfViewerActivity.previewBuilder
        PdfViewerActivity.pagesFactory = { FakePages(3) }
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultPages
        PdfViewerActivity.previewBuilder = defaultBuilder
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    @Test
    fun `locked preview is built off main and reused after recreate`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 4)
        val builderThreads = Collections.synchronizedList(mutableListOf<String>())
        var builds = 0
        PdfViewerActivity.previewBuilder = { pages, out ->
            builds++
            if (Looper.myLooper() == Looper.getMainLooper()) builderThreads += "MAIN"
            else builderThreads += Thread.currentThread().name
            com.scanwhirl.app.util.PdfGenerator.imagesToPdf(pages, out)
        }

        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, id, "Locked")).use { scenario ->
            val shown = await {
                var ready = false
                scenario.onActivity { a ->
                    val list = a.findViewById<RecyclerView>(R.id.pdfPager)
                    ready = list.adapter?.itemCount == 3 &&
                        a.findViewById<TextView>(R.id.pdfPageIndicator)?.text?.isNotEmpty() == true
                }
                ready
            }
            assertTrue("the reader opens after the preview is ready", shown)
            assertEquals("the preview is built once", 1, builds)
            assertTrue(
                "the preview must never be built on the main thread: $builderThreads",
                builderThreads.none { it == "MAIN" }
            )

            scenario.recreate()
            val again = await {
                var ready = false
                scenario.onActivity { a ->
                    val list = a.findViewById<RecyclerView>(R.id.pdfPager)
                    ready = list.adapter?.itemCount == 3 &&
                        a.findViewById<TextView>(R.id.pdfPageIndicator)?.text?.isNotEmpty() == true
                }
                ready
            }
            assertTrue("the reader returns after recreation", again)
            assertEquals("rotation reuses the cached preview, it does not rebuild it", 1, builds)
        }
    }

    @Test
    fun `an unlocked scan never builds a preview`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Plain")
        var builds = 0
        PdfViewerActivity.previewBuilder = { pages, out ->
            builds++
            com.scanwhirl.app.util.PdfGenerator.imagesToPdf(pages, out)
        }
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, id, "Plain")).use { scenario ->
            val shown = await {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<View>(R.id.pdfPageIndicator)?.visibility == View.VISIBLE
                }
                ready
            }
            assertTrue(shown)
            assertEquals("an ordinary scan opens its own PDF", 0, builds)
        }
    }
}
