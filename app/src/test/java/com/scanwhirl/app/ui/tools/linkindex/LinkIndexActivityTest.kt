package com.scanwhirl.app.ui.tools.linkindex
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.LinkIndexTrial
import com.scanwhirl.app.util.ProBilling
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    /** Play's billing seam: answers one price (or null when offline). */
    private class PriceGateway(private val answer: String?) : ProBilling.Gateway {
        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) = onReady(true)
        override fun price(onPrice: (String?) -> Unit) = onPrice(answer)
        override fun launch(activity: Activity?) = Unit
        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) = onResult(emptyList())
        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) = onDone(true)
        override fun close() = Unit
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<LinkIndexActivity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        LinkIndexTrial.resetForTests(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        ProBilling.resetForTests()
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

    private fun trialMessage(): String {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the trial offer is shown", dialog)
        return dialog!!.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
    }

    @Test(timeout = 30_000)
    fun `the trial offer substitutes Play localized price`() {
        LinkIndexTrial.autoAcceptForTests = false
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        LinkIndexTrial.resetForTests(app)
        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { PriceGateway("₹299") }
        ProBilling.prefetchPrice(app)
        shadowOf(app.mainLooper).idle()

        controller = Robolectric.buildActivity(LinkIndexActivity::class.java).setup()
        shadowOf(app.mainLooper).idle()
        val message = trialMessage()
        assertTrue(message.contains("₹299"))
        assertFalse(message.contains("{"))
        assertTrue(message.contains("No automatic charge."))
    }

    @Test(timeout = 30_000)
    fun `the trial offer omits the price when Play has no answer`() {
        LinkIndexTrial.autoAcceptForTests = false
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        LinkIndexTrial.resetForTests(app)
        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { PriceGateway(null) }

        controller = Robolectric.buildActivity(LinkIndexActivity::class.java).setup()
        shadowOf(app.mainLooper).idle()
        val message = trialMessage()
        assertTrue(message.contains("Further uses of this tool need Pro (one payment)."))
        assertFalse(message.contains("{"))
        assertFalse(message.contains("%1"))
    }
}
