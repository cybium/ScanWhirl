package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.PdfAFlow
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.stamp.StampToolLauncher
import com.scanwhirl.app.util.ProBilling
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.Locale

/**
 * P2pro regression tests: purchase-UI honesty and the tool-picker privacy leak.
 * The base SHA these were written against belongs in the lane commit, not in source.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class P2ProHonestyTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    @After
    fun tearDown() {
        ProBilling.resetForTests()
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun hiContext(): Context {
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale("hi"))
        return app.createConfigurationContext(config)
    }

    /** Play answers later, on a queue; ALREADY_OWNED then a successful empty query. */
    private class OwnedGateway : ProBilling.Gateway {
        val queue = ArrayDeque<() -> Unit>()
        var purchases: List<ProBilling.Owned>? = emptyList()

        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) { queue.addLast { onReady(true) } }
        override fun price(onPrice: (String?) -> Unit) { queue.addLast { onPrice("₹100") } }
        override fun queryOffer(onOffer: (ProBilling.Offer?) -> Unit) {
            queue.addLast { onOffer(ProBilling.Offer("₹100", "identity")) }
        }
        override fun launch(activity: Activity?) = Unit
        override fun launch(activity: Activity?, offer: ProBilling.Offer): ProBilling.LaunchResult =
            ProBilling.LaunchResult.ALREADY_OWNED
        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) {
            queue.addLast { onResult(purchases) }
        }
        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) {
            queue.addLast { onDone(true) }
        }
        override fun close() = Unit

        fun drain() {
            while (queue.isNotEmpty()) {
                queue.removeFirst()()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test
    fun `ITEM_ALREADY_OWNED with a successful empty query is not a connection failure`() {
        val gateway = OwnedGateway().apply { purchases = emptyList() }
        ProBilling.gatewayFactory = { gateway }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val states = mutableListOf<ProBilling.State>()

        ProBilling.buy(activity, testBuild = false) { states += it }
        gateway.drain()

        val failed = states.filterIsInstance<ProBilling.State.Failed>().lastOrNull()
        assertNotNull("the Buy ends with a message", failed)
        assertFalse(
            "a successful empty answer is not 'Can't reach Google Play'",
            failed!!.message == app.getString(R.string.pro_error_connection)
        )
        assertTrue(
            "the message must say Play found no Pro purchase on this account",
            failed.message.contains("no Pro purchase", ignoreCase = true)
        )
        assertFalse(
            "an empty authoritative answer grants nothing",
            ProGate.isPro(app, testBuild = false)
        )
        assertFalse("the Buy is released", ProBilling.isPurchaseInFlight())
    }

    @Test
    fun `Restore is disabled with an accessible reason while a Buy is open`() {
        val screen = ProScreen(app, object : ProScreen.Callbacks {
            override fun onClose() = Unit
            override fun onBuy() = Unit
            override fun onRestore() = Unit
            override fun onSeeAllProTools() = Unit
        })
        val row = screen.restoreRowForTest()
        assertNotNull("the Pro screen has a Restore row", row)

        screen.setBuying(true)
        assertFalse("a second purchase request cannot be ignored silently", row!!.isEnabled)
        assertEquals(
            "TalkBack says why it is disabled",
            app.getString(R.string.pro_purchase_in_progress),
            row.contentDescription.toString()
        )

        screen.setBuying(false)
        assertTrue("Restore returns when the Buy ends", row.isEnabled)
        assertEquals(app.getString(R.string.pro_restore), row.contentDescription.toString())
    }

    @Test
    fun `stamp Pro copy is customer facing in English and Hindi`() {
        val en = app.getString(R.string.stamp_pro_message)
        val hi = hiContext().getString(R.string.stamp_pro_message)

        assertFalse("no developer 'Test builds are free' wording in English", en.contains("Test builds are free"))
        assertFalse(
            "no developer 'test build' wording in Hindi",
            hi.contains("जाँच के लिए बने ऐप") || hi.contains("टेस्ट बिल्ड")
        )
        assertTrue("English still names Pro", en.contains("Pro", ignoreCase = true))
        assertTrue("Hindi still names Pro", hi.contains("Pro", ignoreCase = true))
    }

    @Test
    fun `a free Stamp tool opens the real Pro screen instead of a got-it sheet`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Stamp doc")
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        ProGate.setTestOverride(host, false)
        try {
            StampToolLauncher.open(host, "watermark")

            val started = shadowOf(host).nextStartedActivity
            assertNotNull("the Pro screen route starts an Activity", started)
            assertEquals(ShellActivity::class.java.name, started!!.component?.className)
            assertTrue(
                "it is the Pro offer, with Buy and Restore",
                started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
            )
            assertNull("no dead-end Got it sheet", ShadowDialog.getLatestDialog())
        } finally {
            ProGate.setTestOverride(host, null)
        }
    }

    @Test
    fun `a free Save as PDF-A opens the real Pro screen instead of a got-it sheet`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Archive doc")
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        ProGate.setTestOverride(host, false)
        try {
            PdfAFlow.start(host, repo)

            val started = shadowOf(host).nextStartedActivity
            assertNotNull("the Pro screen route starts an Activity", started)
            assertEquals(ShellActivity::class.java.name, started!!.component?.className)
            assertTrue(
                "it is the Pro offer, with Buy and Restore",
                started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
            )
            assertNull("no dead-end Got it sheet", ShadowDialog.getLatestDialog())
        } finally {
            ProGate.setTestOverride(host, null)
        }
    }

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun openToolPicker(activity: ShellActivity, toolId: String): ViewGroup {
        activity.findViewById<View>(R.id.swNavTools).performClick()
        idle()
        val tile = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
            .firstOrNull { it.tool?.id == toolId }
        assertNotNull("$toolId tile is missing", tile)
        tile!!.performClick()
        idle()
        return requireNotNull(ShadowDialog.getLatestDialog()) { "the document chooser opens" }
            .findViewById(R.id.swDocPickerList)
    }

    @Test
    fun `the tool document chooser never offers a password protected document`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Open doc")
        DocFixtures.locked(repo, app, pages = 1)

        launch { activity ->
            val list = openToolPicker(activity, "read_text")
            assertEquals("only the unlocked document is offered", 1, list.childCount)
            assertEquals("Open doc", (list.getChildAt(0) as DocCard).doc?.name)
        }
    }

    @Test
    fun `the tool document chooser never offers a vaulted document`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Open doc")
        val secret = DocFixtures.saved(repo, app, pages = 1, name = "Vault doc")
        File(File(repo.docsDir, secret), "vault.flag").writeText("1")

        launch { activity ->
            val list = openToolPicker(activity, "read_text")
            assertEquals("only the non-vaulted document is offered", 1, list.childCount)
            assertEquals("Open doc", (list.getChildAt(0) as DocCard).doc?.name)
        }
    }

    @Test
    fun `when only protected documents exist the chooser shows the honest empty message`() {
        DocFixtures.locked(repo, app, pages = 1)

        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "read_text" }
                .performClick()
            idle()
            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "an empty-state sheet opens" }
            assertNull("no document rows", sheet.findViewById<View>(R.id.swDocPickerList))
            val message = sheet.findViewById<android.widget.TextView>(R.id.swSheetMessage)
            assertNotNull("the honest note is shown", message)
            assertEquals(app.getString(R.string.tool_pick_document_note), message!!.text.toString())
        }
    }
}
