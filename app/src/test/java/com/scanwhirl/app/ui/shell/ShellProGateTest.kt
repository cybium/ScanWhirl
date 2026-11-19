package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.ToolRegistry
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 06_pro — a Pro tool tapped in a Free build opens the Pro screen from
 * the one dispatch point; Free tools and Pro builds are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellProGateTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        DocFixtures.saved(repo, app, pages = 1, name = "Gate doc")
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun proVisible(activity: ShellActivity): Boolean =
        activity.findViewById<View>(R.id.swProScreen)?.isVisible == true

    private fun assertProScreen(activity: ShellActivity, what: String) {
        if (!proVisible(activity)) {
            val started = shadowOf(activity).nextStartedActivity
            val dialog = ShadowDialog.getLatestDialog()
            throw AssertionError(
                "$what must open the Pro screen in Free (started=${started?.component?.className}, " +
                    "dialog=${dialog?.javaClass?.simpleName})"
            )
        }
        activity.findViewById<View>(R.id.swProClose).performClick()
        idle()
    }

    @Test
    fun `Free - every Pro tile in the Tools hub opens the Pro screen`() {
        ProGate.setTestOverride(app, false)
        // v2 UI: 03_tools_hub - a Coming-soon Pro tool is not a hub tile any more (no Soon tiles).
        val proIds = ToolRegistry.all
            .filter { it.pro && it.status != com.scanwhirl.app.ui.kit.ToolStatus.SOON }
            .map { it.id }
        assertEquals("every Pro tool on sale has a tile", proIds.size, proIds.toSet().size)

        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            for (id in proIds) {
                val tile = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                    .firstOrNull { it.tool?.id == id }
                assertNotNull("$id tile is missing", tile)
                tile!!.performClick()
                idle()
                assertProScreen(activity, id)
            }
        }
    }

    @Test
    fun `Free - the Pro QR entry in the Create sheet opens the Pro screen`() {
        ProGate.setTestOverride(app, false)
        launch { activity ->
            activity.findViewById<View>(R.id.swNavScan).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("the Create sheet opens", dialog)
            val qr = dialog!!.findViewById<View>(R.id.swCreateQr)
            assertNotNull("the Create sheet offers QR code", qr)
            qr!!.performClick()
            idle()
            assertProScreen(activity, "qr_code")
            assertNull(
                "a gated QR tap starts no QR activity",
                shadowOf(activity).nextStartedActivity
            )
        }
    }

    @Test
    fun `Free - a Free Create entry still runs instead of the Pro screen`() {
        ProGate.setTestOverride(app, false)
        launch { activity ->
            activity.findViewById<View>(R.id.swNavScan).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("the Create sheet opens", dialog)
            val idCard = dialog!!.findViewById<View>(R.id.swCreateIdCard)
            assertNotNull("the Create sheet offers ID card", idCard)
            idCard!!.performClick()
            idle()
            assertFalse(
                "a Free tool must not be gated",
                proVisible(activity)
            )
            val started = shadowOf(activity).nextStartedActivity
            assertNotNull("the ID card tool opens", started)
            assertEquals(
                com.scanwhirl.app.ui.tools.IdCardActivity::class.java.name,
                started.component?.className
            )
        }
    }

    @Test
    fun `Pro - the same tiles launch their tools`() {
        ProGate.setTestOverride(app, true)
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val toWord = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "to_word" }
            toWord.performClick()
            idle()
            assertFalse(
                "Pro must not see the pitch when the tool is allowed",
                proVisible(activity)
            )
            val started = shadowOf(activity).nextStartedActivity
            assertNotNull("the tool screen opens", started)
            assertEquals(
                com.scanwhirl.app.ui.tools.ToolSetupActivity::class.java.name,
                started.component?.className
            )

            // v2 UI: 03_tools_hub - Save as PDF/A ships now: in Pro it opens its real picker.
            val pdfa = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "pdfa" }
            pdfa.performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertEquals(
                activity.getString(R.string.pdfa_pick),
                dialog.findViewById<android.widget.TextView>(R.id.swSheetTitle).text.toString()
            )
            dialog.dismiss()
        }
    }
}
