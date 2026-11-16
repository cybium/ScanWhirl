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
import com.scanwhirl.app.ui.kit.ProOffer
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 07_reader_dock — Sign in the PDF reader opens the Pro Edit & sign suite, so a Free
 * build must get the Pro offer (the shell's Pro screen, EXTRA_OPEN_PRO), never the editor.
 * The editor itself refuses to open in Free as a backstop for any other caller.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfViewerProGateTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var defaultFactory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        defaultFactory = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = { FakePages(2) }
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        ProOffer.testBuildForTests = null
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultFactory
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
        ProOffer.testBuildForTests = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun assertProOffer(started: android.content.Intent, what: String) {
        assertEquals("$what must open the Pro screen, not the editor", ShellActivity::class.java.name, started.component?.className)
        assertTrue(
            "$what must ask the shell for the Pro screen",
            started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
        )
    }

    @Test
    fun `Free - the reader dock Sign opens the Pro offer instead of the stamp editor`() {
        ProGate.setTestOverride(app, false)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, id, "Sign me")).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.readerDockSign).performClick()
                idle()
                val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
                    "Sign must not be a dead tap"
                }
                assertProOffer(started, "dock Sign")
            }
        }
    }

    @Test
    fun `Free - the stamp editor refuses to open and offers Pro instead`() {
        ProGate.setTestOverride(app, false)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        val controller = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            StampEditorActivity.intent(app, id, StampEditorActivity.TOOL_SIGN)
        ).setup()
        val activity = controller.get()
        idle()

        assertTrue("the editor closes itself in Free", activity.isFinishing)
        val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
            "closing the editor must open the Pro offer"
        }
        assertProOffer(started, "stamp editor backstop")
        controller.pause().stop().destroy()
    }

    @Test
    fun `Pro - the dock Sign still opens the stamp editor`() {
        ProGate.setTestOverride(app, true)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, id, "Sign me")).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.readerDockSign).performClick()
                idle()
                val started = requireNotNull(shadowOf(activity).nextStartedActivity)
                assertEquals(
                    StampEditorActivity::class.java.name,
                    started.component?.className
                )
                assertEquals(
                    StampEditorActivity.TOOL_SIGN,
                    started.getStringExtra(StampEditorActivity.EXTRA_TOOL)
                )
            }
        }
    }

    // ---- the real editor, not just the outgoing intent ---------------------

    private fun editorIntent(id: String) = StampEditorActivity.intent(app, id, StampEditorActivity.TOOL_SIGN)

    @Test
    fun `default TEST_BUILD really creates the stamp editor`() {
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        ActivityScenario.launch<StampEditorActivity>(editorIntent(id)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertFalse("the editor stays open", activity.isFinishing)
                assertNotNull(
                    "the editor's canvas is really on screen",
                    activity.findViewById<View>(R.id.canvas)
                )
            }
        }
    }

    @Test
    fun `a recorded production purchase opens the editor and survives recreation`() {
        // Release rule: only the recorded purchase counts (no test override, no test-build default).
        ProOffer.testBuildForTests = false
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, true)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        ActivityScenario.launch<StampEditorActivity>(editorIntent(id)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertFalse("a recorded purchase opens the editor", activity.isFinishing)
                assertNotNull(activity.findViewById<View>(R.id.canvas))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                idle()
                assertFalse("recreation keeps the editor of a paying user", activity.isFinishing)
                assertNotNull(activity.findViewById<View>(R.id.canvas))
            }
        }
    }

    @Test
    fun `a release build without a recorded purchase refuses the editor`() {
        ProOffer.testBuildForTests = false
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        val controller = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            editorIntent(id)
        ).setup()
        val activity = controller.get()
        idle()

        assertTrue("the release Free editor closes itself", activity.isFinishing)
        val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
            "closing the editor must open the Pro offer"
        }
        assertProOffer(started, "release stamp editor backstop")
        controller.pause().stop().destroy()
    }
}
