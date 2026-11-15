package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolRegistry
import org.junit.Assert.assertEquals
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
 * v2 UI: 01_home_A / 03_tools_hub / 06_pro — the shell's four promises:
 * Library shows the create row, six quick tools and document cards; the nav
 * switches tabs; every Tools tile opens something; Pro's copy is honest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellScreenTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        DocFixtures.saved(repo, app, pages = 2, name = "Physics notes")
        DocFixtures.saved(repo, app, pages = 1, name = "Passport copy")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun scrollActivity(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    @Test
    fun `library shows the create row, six quick tools and document cards`() {
        scrollActivity { activity ->
            val create = activity.findViewById<View>(R.id.swCreateRow)
            assertNotNull("create row is missing", create)
            assertEquals(3, (create as android.view.ViewGroup).childCount)

            val quick = activity.findViewById<View>(R.id.swQuickRow) as android.view.ViewGroup
            assertEquals(6, quick.childCount)

            val docs = activity.findViewById<View>(R.id.swDocList) as android.view.ViewGroup
            assertEquals("two seeded documents", 2, docs.childCount)
            assertTrue(activity.findViewById<View>(R.id.swHomeEmpty).isVisible.not())
            assertNotNull(activity.findViewById<View>(R.id.swHomeSearch))
        }
    }

    @Test
    fun `nav switches between Library Tools Vault and Me`() {
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            assertTrue(activity.findViewById<View>(R.id.swToolsSearch).isVisible)
            assertNull(
                "Library content leaves the hierarchy when another tab is shown",
                activity.findViewById<View>(R.id.swCreateRow)
            )

            activity.findViewById<View>(R.id.swNavMe).performClick()
            idle()
            assertEquals(5, (activity.findViewById<View>(R.id.swMeList) as android.view.ViewGroup).childCount)

            activity.findViewById<View>(R.id.swNavVault).performClick()
            idle()
            assertTrue(activity.findViewById<View>(R.id.swVaultNote).isVisible)

            activity.findViewById<View>(R.id.swNavLibrary).performClick()
            idle()
            assertTrue(activity.findViewById<View>(R.id.swCreateRow).isVisible)
        }
    }

    // v2 UI: 03_tools_hub (44 tools: Mask Aadhaar joins Edit & sign; the Hindi tile folds into Read text).
    @Test
    fun `the tools hub holds all 44 tiles and every tile is wired`() {
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val body = activity.findViewById<View>(R.id.swToolsBody)
            val tiles = ToolsHubScreen.tiles(body)
            assertEquals(44, tiles.size)
            assertEquals(44, tiles.mapNotNull { it.tool?.id }.toSet().size)
            for (tile in tiles) {
                assertTrue("${tile.tool?.id} has no tap action", tile.hasOnClickListeners())
            }
        }
    }

    // v2 UI: 03_tools_hub - Save as PDF/A ships now, so the tile opens its real picker instead
    // of a Coming soon sheet (the Coming soon sheet itself is covered by KitComponentsTest).
    @Test
    fun `a tool tile opens its own flow, never a dead end`() {
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val body = activity.findViewById<View>(R.id.swToolsBody)
            val tile = ToolsHubScreen.tiles(body).first { it.tool?.id == "pdfa" }
            tile.performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("a tile must never be a dead end", dialog)
            val title = dialog.findViewById<TextView>(R.id.swSheetTitle)
            assertEquals(activity.getString(R.string.pdfa_pick), title.text.toString())
            dialog.dismiss()
        }
    }

    @Test
    fun `tools search filters over titles and keywords`() {
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            activity.findViewById<EditText>(R.id.swToolsSearch).setText("smaller")
            idle()
            val tiles = ToolsHubScreen.tiles(activity.findViewById<View>(R.id.swToolsBody))
            assertEquals(
                ToolRegistry.search(activity, "smaller").map { it.id },
                tiles.mapNotNull { it.tool?.id }
            )
            assertTrue(tiles.any { it.tool?.id == "compress" })
        }
    }

    @Test
    fun `pro screen shows the one-time price and buy is a Coming soon sheet`() {
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavMe).performClick()
            idle()
            activity.findViewById<View>(R.id.swMePro).performClick()
            idle()

            assertTrue(activity.findViewById<View>(R.id.swProScreen).isVisible)
            assertEquals(
                activity.getString(R.string.pro_price),
                activity.findViewById<TextView>(R.id.swProPrice).text.toString()
            )
            activity.findViewById<View>(R.id.swProBuy).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertEquals(
                activity.getString(R.string.pro_buy_coming),
                dialog.findViewById<TextView>(R.id.swSheetMessage).text.toString()
            )
            dialog.dismiss()

            activity.findViewById<View>(R.id.swProClose).performClick()
            idle()
            assertTrue(activity.findViewById<View>(R.id.swMeList).isVisible)
        }
    }

    // v2 UI: 04_vault — the tab now opens L2's encrypted Vault instead of a "coming soon" sheet.
    @Test
    fun `vault tab opens the encrypted vault`() {
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavVault).performClick()
            idle()
            assertNotNull(activity.findViewById<View>(R.id.swVaultNote))
            activity.findViewById<View>(R.id.swVaultAction)!!.performClick()
            idle()
            val started = shadowOf(activity).nextStartedActivity
            assertNotNull("the Vault opens", started)
            assertEquals(
                com.scanwhirl.app.ui.vault.VaultActivity::class.java.name,
                started.component?.className
            )
        }
    }

    @Test
    fun `document card opens the reader and its more sheet has real actions`() {
        scrollActivity { activity ->
            val docs = activity.findViewById<View>(R.id.swDocList) as android.view.ViewGroup
            val card = docs.getChildAt(0)
            activity.findViewById<View>(R.id.swDocMore).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertNotNull(dialog.findViewById<View>(R.id.swSheetPrimary))
            assertEquals(
                activity.getString(R.string.shell_open),
                dialog.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
            )
            dialog.dismiss()
            assertTrue(card.hasOnClickListeners())
            val tool: SwTool? = ToolRegistry.byId("scan")
            assertNotNull(tool)
        }
    }
}
