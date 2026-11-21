package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import java.io.File
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 07_reader_dock — Share · Pages · Text · Sign · More, a page strip and
 * the lock badge in the title. PdfRenderer does not run under Robolectric, so
 * the fake pages seam (F22) carries the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReaderDockTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var defaultFactory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private lateinit var defaultPreviewBuilder: (List<File>, File) -> Unit

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        defaultFactory = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = { FakePages(3) }
        defaultPreviewBuilder = PdfViewerActivity.previewBuilder
        // A locked scan's preview is otherwise encoded from page images off the main thread;
        // the bytes do not matter (pagesFactory is faked), only that the file is non-empty.
        PdfViewerActivity.previewBuilder = { _, out -> out.writeText("fake preview") }
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultFactory
        PdfViewerActivity.previewBuilder = defaultPreviewBuilder
    }

    private fun pollUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        check(condition()) { "the reader did not finish opening in ${timeoutMs}ms" }
    }

    private fun open(docId: String, name: String, block: (PdfViewerActivity) -> Unit) {
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, docId, name))
            .use { scenario ->
                var ref: PdfViewerActivity? = null
                scenario.onActivity { ref = it }
                // A protected document builds its preview on Dispatchers.IO and only then runs
                // afterOpen(), which sets the title, binds the dock and the lock badge. Idling the
                // main looper once races that background work, so wait for the title to be set.
                pollUntil {
                    !ref?.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                        ?.title.isNullOrBlank()
                }
                scenario.onActivity { activity -> block(activity) }
            }
    }

    @Test
    fun `dock has five actions and a page strip`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Rent agreement")
        open(id, "Rent agreement") { activity ->
            for (viewId in listOf(
                R.id.readerDockShare,
                R.id.readerDockPages,
                R.id.readerDockText,
                R.id.readerDockSign,
                R.id.readerDockMore
            )) {
                val cell = activity.findViewById<View>(viewId)
                assertNotNull("dock cell $viewId is missing", cell)
                assertEquals(View.VISIBLE, cell.visibility)
            }
            assertEquals(View.GONE, activity.findViewById<View>(R.id.readerStrip).visibility)
            activity.findViewById<View>(R.id.readerDockPages).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.readerStrip).visibility)
            assertEquals(
                3,
                (activity.findViewById<View>(R.id.readerStripPages) as android.view.ViewGroup).childCount
            )
        }
    }

    // v2 UI: 07_reader_dock - Sign opens the stamp editor armed with Sign for this document.
    @Test
    fun `sign opens the stamp editor with the sign tool`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        open(id, "Sign me") { activity ->
            activity.findViewById<View>(R.id.readerDockSign).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
                "Sign must open the editor"
            }
            assertEquals(
                com.scanwhirl.app.ui.stamp.StampEditorActivity::class.java.name,
                started.component?.className
            )
            assertEquals(
                id,
                started.getStringExtra(
                    com.scanwhirl.app.ui.stamp.StampEditorActivity.EXTRA_DOC_ID
                )
            )
            assertEquals(
                com.scanwhirl.app.ui.stamp.StampEditorActivity.TOOL_SIGN,
                started.getStringExtra(
                    com.scanwhirl.app.ui.stamp.StampEditorActivity.EXTRA_TOOL
                )
            )
        }
    }

    @Test
    fun `a protected document shows the lock badge in the title`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 1)
        open(id, "Rent agreement") { activity ->
            assertBadge(activity)
        }
    }

    /**
     * C1 item 1: the badge assertion once raced the off-main preview build (NPE on
     * `getLogoDescription()`). Ten consecutive cold opens prove the synchronisation is
     * deterministic, not a lucky idle. Same assertion, never skipped or weakened.
     */
    @Test
    fun `the protected badge is deterministic across ten cold opens`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 1)
        repeat(10) { run ->
            open(id, "Rent agreement") { activity ->
                assertBadge(activity, "open ${run + 1}/10")
            }
        }
    }

    private fun assertBadge(activity: PdfViewerActivity, label: String = "") {
        assertEquals(
            "$label protected badge",
            activity.getString(R.string.home_protected),
            activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                .logoDescription.toString()
        )
    }
}
