package com.scanwhirl.app.ui.tools.linkindex

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.LinkIndexTrial
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** LX: lifecycle + the one-free-use gate. No real documents, no ADB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkIndexActivityTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<LinkIndexActivity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        LinkIndexTrial.resetForTests(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        ShadowDialog.reset()
    }

    @Test(timeout = 30_000)
    fun `the pick step is reachable and the close button finishes`() {
        LinkIndexTrial.autoAcceptForTests = true
        controller = Robolectric.buildActivity(LinkIndexActivity::class.java).setup()
        val activity = controller!!.get()
        shadowOf(app.mainLooper).idle()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.lxStepPick).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.lxStepSet).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.lxStepResult).visibility)
        activity.findViewById<View>(R.id.lxClose).performClick()
        assertTrue(activity.isFinishing)
    }

    @Test(timeout = 30_000)
    fun `a spent free use goes to the shared Pro screen`() {
        LinkIndexTrial.autoAcceptForTests = false
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // force Free even in -PtestBuild
        LinkIndexTrial.resetForTests(app)
        LinkIndexTrial.recordSuccess(app)
        assertEquals(1, LinkIndexTrial.uses(app))

        controller = Robolectric.buildActivity(LinkIndexActivity::class.java).setup()
        val activity = controller!!.get()
        val next = shadowOf(activity).nextStartedActivity
        assertNotNull("the Pro screen opens before a second run", next)
        assertEquals(ShellActivity::class.java.name, next!!.component?.className)
        assertTrue(next.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false))
        assertTrue(activity.isFinishing)
    }

    @Test(timeout = 30_000)
    fun `a first free use shows the honest trial offer`() {
        LinkIndexTrial.autoAcceptForTests = false
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // force Free even in -PtestBuild
        LinkIndexTrial.resetForTests(app)
        controller = Robolectric.buildActivity(LinkIndexActivity::class.java).setup()
        shadowOf(app.mainLooper).idle()
        assertNotNull(ShadowDialog.getLatestDialog())
    }

    @Test(timeout = 30_000)
    fun `manifest entry is internal and the tool id is stable`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(
            manifest.contains(".ui.tools.linkindex.LinkIndexActivity") &&
                manifest.contains("android:exported=\"false\"")
        )
        assertEquals("link_index", LinkIndexActivity.TOOL_ID)
        assertNotNull(LinkIndexActivity.intent(app))
    }

    @Test(timeout = 30_000)
    fun `the tool is a ready Pro tile in the OCR job`() {
        val tool = ToolRegistry.byId(LinkIndexActivity.TOOL_ID)
        assertNotNull(tool)
        assertTrue(tool!!.pro)
        assertEquals(ToolStatus.READY, tool.status)
        assertEquals(ToolJob.TEXT, ToolRegistry.jobOf(tool))
        assertEquals("Link index", app.getString(tool.title))
    }
}
