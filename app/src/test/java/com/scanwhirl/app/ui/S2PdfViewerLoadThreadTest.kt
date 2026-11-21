package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2 item 4: the document-load disk work in PdfViewerActivity.onCreate (getDocument,
 * protectionState, fingerprint stamp) must run off the main thread; FLAG_SECURE/Vault handling is
 * unchanged. A thread probe records where the load ran.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S2PdfViewerLoadThreadTest {

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var defaultPages: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private var defaultProbe: ((String) -> Unit)? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        defaultPages = PdfViewerActivity.pagesFactory
        defaultProbe = PdfViewerActivity.documentLoadProbe
        PdfViewerActivity.pagesFactory = { FakePages(2) }
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultPages
        PdfViewerActivity.documentLoadProbe = defaultProbe
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    @Test
    fun `the document load disk work runs off the main thread`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Thread probe")
        var loadThread: String? = null
        PdfViewerActivity.documentLoadProbe = { loadThread = it }

        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, id, "Thread probe")).use { scenario ->
            val shown = await {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<View>(R.id.pdfPageIndicator)?.visibility == View.VISIBLE
                }
                ready
            }
            assertTrue("the reader must open", shown)
        }

        assertTrue("a document load must have happened", loadThread != null)
        assertNotEquals(
            "document-load disk work must not run on the main thread",
            Looper.getMainLooper().thread.name,
            loadThread
        )
    }
}
