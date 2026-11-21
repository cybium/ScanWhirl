package com.scanwhirl.app.ux130.l1

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.shell.ToolsHubScreen
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProBilling
import com.scanwhirl.app.util.ProGate
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
import org.robolectric.shadows.ShadowDialog

/**
 * L1-06 — Searchable PDF has a persistent, named outcome and a purchase failure is not
 * a dead end. RED on base: both outcomes were a vanishing Toast and the failed sheet
 * offered only "Got it".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class L1SearchableOutcomeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        ShellActivity.searchableProbe = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(10)
        }
        idle()
        assertTrue(what, runCatching(condition).getOrDefault(false))
    }

    /** Open Make searchable for the first document through the real Tools > picker route. */
    private fun openSearchableSheet(activity: ShellActivity) {
        activity.findViewById<View>(R.id.swNavTools).performClick()
        idle()
        val hub = activity.findViewById<ViewGroup>(R.id.swToolsBody)
        ToolsHubScreen.tiles(hub).first { it.tool?.id == "searchable_pdf" }.performClick()
        idle()
        val picker = ShadowDialog.getLatestDialog()
        assertNotNull("document chooser opens", picker)
        val list = picker.findViewById<ViewGroup>(R.id.swDocPickerList)
        (list.getChildAt(0) as View).performClick()
        idle()
        assertNotNull("make-searchable sheet opens", ShadowDialog.getLatestDialog())
    }

    private fun label(id: Int): String? =
        ShadowDialog.getLatestDialog()?.findViewById<TextView>(id)?.text?.toString()

    @Test(timeout = 30_000)
    fun `success shows a named outcome with Open and Share only after verification`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Outcome doc")
        DocFixtures.withOcr(repo, id)
        var calls = 0
        ShellActivity.searchableProbe = { docId ->
            calls++
            repo.makeSearchable(docId)
        }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            openSearchableSheet(activity)
            val initial = ShadowDialog.getLatestDialog()
            initial.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            waitFor("verified outcome") { label(R.id.swSheetMessage)?.contains("Text layer added") == true }
            val sheet = ShadowDialog.getLatestDialog()
            assertEquals("Text layer added to Outcome doc.", label(R.id.swSheetMessage))
            assertEquals("Open", label(R.id.swSheetPrimary))
            assertEquals("Share", label(R.id.swSheetSecondary))
            assertEquals(1, calls)
            assertTrue("the document really is searchable", repo.getDocument(id)!!.isSearchable)
            // The success sheet is not the result Undo: no delete route is offered.
            assertFalse("no Undo/remove action", label(R.id.swSheetPrimary)!!.contains("Undo"))
        } finally {
            ShellActivity.searchableProbe = null
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `failure is honest leaves the document unchanged and offers no success actions`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Failed doc")
        DocFixtures.withOcr(repo, id)
        ShellActivity.searchableProbe = { false }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            openSearchableSheet(activity)
            ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            waitFor("failure outcome") {
                label(R.id.swSheetMessage)?.startsWith("Text layer could not be added") == true
            }
            assertEquals(
                "Text layer could not be added. Your document is unchanged.",
                label(R.id.swSheetMessage)
            )
            assertEquals("Close", label(R.id.swSheetPrimary))
            assertFalse("no Open on failure", label(R.id.swSheetPrimary)!! == "Open")
            assertFalse("no Share on failure", label(R.id.swSheetSecondary) == "Share")
            assertTrue("source still present", repo.getDocument(id) != null)
            assertFalse("source untouched", repo.getDocument(id)!!.isSearchable)
        } finally {
            ShellActivity.searchableProbe = null
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `already searchable says so without offering a second run`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Already layered")
        DocFixtures.withOcr(repo, id)
        assertTrue(repo.makeSearchable(id))
        ShellActivity.searchableProbe = { error("must not run again") }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            openSearchableSheet(activity)
            assertEquals("This PDF already has a text layer.", label(R.id.swSheetMessage))
            assertEquals("Got it", label(R.id.swSheetPrimary))
        } finally {
            ShellActivity.searchableProbe = null
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `purchase failure offers Try again Restore and Close through bounded methods`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val method = ShellActivity::class.java.getDeclaredMethod(
                "showBillingState", ProBilling.State::class.java
            )
            method.isAccessible = true
            method.invoke(activity, ProBilling.State.Failed("Play refused the purchase"))
            idle()
            assertEquals("Couldn't complete purchase", label(R.id.swSheetTitle))
            assertEquals("Try again", label(R.id.swSheetPrimary))
            assertEquals("Restore purchase", label(R.id.swSheetSecondary))
            assertNotNull("a Close affordance exists", ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetClose))
            val src = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
            assertTrue("retry calls the bounded buy", src.contains("setPrimary(R.string.ux130_l1_purchase_retry) { startBilling() }"))
            assertTrue("restore calls the bounded restore", src.contains("setSecondary(R.string.ux130_l1_purchase_restore) { restoreBilling() }"))
            assertTrue("restore itself is bounded", src.contains("private var restoreInFlight"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
