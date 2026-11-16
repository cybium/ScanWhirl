package com.scanwhirl.app.ui.tools

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The OCR screen's "To Word · PRO" chip launches the tool through the plain Context overload,
 * which does not pass through `ToolHost.launchTool`'s gate. `ToolSetupActivity` therefore has a
 * registry-driven backstop: a Pro tool from a Free build never builds its executable UI (and so
 * never reaches `ScanRepository.exportWord`); it lands on the shell's Pro offer instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolSetupProGateTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 1, name = "Word source")
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
    }

    private fun open(toolId: String): Pair<ActivityController<ToolSetupActivity>, ToolSetupActivity> {
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java,
            ToolSetupActivity.intent(app, toolId)
        ).setup()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return controller to activity
    }

    @Test
    fun `Free - a direct Pro tool intent offers Pro and builds no executable UI`() {
        ProGate.setTestOverride(app, false)
        val (controller, activity) = open("to_word")

        assertTrue("the Free setup screen closes itself", activity.isFinishing)
        assertNull(
            "the Word export screen (and its Run button) is never built for Free",
            activity.findViewById<View>(R.id.toolSetupRun)
        )
        val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
            "a Pro tool is not a dead tap"
        }
        assertEquals(ShellActivity::class.java.name, started.component?.className)
        assertTrue(
            "the shell shows its Pro screen",
            started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `Free - a Free tool still opens`() {
        ProGate.setTestOverride(app, false)
        val (controller, activity) = open("compress")

        assertFalse("a Free tool is not gated", activity.isFinishing)
        assertNotNull(
            "the compress setup is really on screen",
            activity.findViewById<View>(R.id.toolSetupRun)
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `Pro - a Pro tool opens`() {
        ProGate.setTestOverride(app, true)
        val (controller, activity) = open("to_word")

        assertFalse(activity.isFinishing)
        assertNotNull(activity.findViewById<View>(R.id.toolSetupRun))
        controller.pause().stop().destroy()
    }

    @Test
    fun `default TEST_BUILD opens a Pro tool`() {
        ProGate.setTestOverride(app, null)
        val (controller, activity) = open("to_word")

        assertFalse("the test build is Pro by default", activity.isFinishing)
        assertNotNull(activity.findViewById<View>(R.id.toolSetupRun))
        controller.pause().stop().destroy()
    }
}
