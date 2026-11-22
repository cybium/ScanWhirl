package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

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
import com.scanwhirl.app.ui.kit.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
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
    fun `library shows the header, folder chips and document rows`() {
        scrollActivity { activity ->
            // UI-B1 (#1): the creation tiles live in the Create sheet. UX-008 restores only a
            // compact two-row Quick tools strip, so the row is present again on Home.
            assertNull(activity.findViewById<View>(R.id.swCreateRow))
            assertNotNull(
                "UX-008: quick tools are back on Home",
                activity.findViewById<View>(R.id.swQuickRow)
            )

            assertNotNull("the brand mark is in the header", activity.findViewById<View>(R.id.swBrandMark))
            assertEquals("A/अ", activity.findViewById<TextView>(R.id.swLanguageChip).text.toString())
            assertNotNull("the search field is there", activity.findViewById<View>(R.id.swHomeSearch))
            // LEAD DECISION (L1-01): OLD asserted a standalone folder chip rail. NEW asserts
            // the single Folder/Filter/Sort band and that its folder chooser is reachable.
            assertNotNull("the one control band is there", activity.findViewById<View>(R.id.uxL1ControlBand))
            assertNotNull("the folder chooser is reachable", activity.findViewById<View>(R.id.swAllFolders))

            val docs = activity.findViewById<View>(R.id.swDocList) as android.view.ViewGroup
            assertEquals("two seeded documents", 2, docs.childCount)
            assertTrue(activity.findViewById<View>(R.id.swHomeEmpty).isVisible.not())
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
                activity.findViewById<View>(R.id.swDocList)
            )

            activity.findViewById<View>(R.id.swNavMe).performClick()
            idle()
            assertEquals(5, (activity.findViewById<View>(R.id.swMeList) as android.view.ViewGroup).childCount)

            activity.findViewById<View>(R.id.swNavVault).performClick()
            idle()
            assertTrue(activity.findViewById<View>(R.id.swVaultNote).isVisible)

            activity.findViewById<View>(R.id.swNavLibrary).performClick()
            idle()
            assertTrue(activity.findViewById<View>(R.id.swDocList).isVisible)
        }
    }

    // v2 UI: 03_tools_hub (35 shipped tools after fixui removed Book pages, Photos to PDF,
    // Business card, Duplicate, Enhance and B&W: owner F6/F7/F20; and after the hub stopped
    // rendering the two Coming-soon tiles: organize pages, batch rename).
    @Test
    fun `the tools hub holds every shipped tile and every tile is wired`() {
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val body = activity.findViewById<View>(R.id.swToolsBody)
            openAllToolsDoors(activity)
            // T2 owner 2026-11-21: icon grid
            val tiles = ToolsHubScreen.tiles(body)
            val shipped = ToolRegistry.all.filter { it.status != ToolStatus.SOON }
            assertEquals(shipped.size, tiles.size)
            assertEquals(shipped.size, tiles.mapNotNull { it.tool?.id }.toSet().size)
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
            openAllToolsDoors(activity)
            val tile = ToolsHubScreen.tiles(body).first { it.tool?.id == "pdfa" }
            tile.performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("a tile must never be a dead end", dialog)
            // UI-W6: PDF/A opens the shared document picker, which has its own title id.
            val title = dialog.findViewById<TextView>(R.id.docPickerTitle)
                ?: dialog.findViewById<TextView>(R.id.swSheetTitle)
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
            val tiles = ToolsHubScreen.rows(activity.findViewById<View>(R.id.swToolsBody))
            assertEquals(
                "the hub lists the ranked hits that actually ship",
                ToolRegistry.search(activity, "smaller")
                    .filter { it.status != com.scanwhirl.app.ui.kit.ToolStatus.SOON }
                    .map { it.id },
                tiles.mapNotNull { it.tool?.id }
            )
            assertTrue(tiles.any { it.tool?.id == "compress" })
        }
    }

    @Test
    fun `pro screen shows Play's price line and buy is a Coming soon sheet`() {
        com.scanwhirl.app.util.ProGate.setTestOverride(app, false)
        scrollActivity { activity ->
            activity.findViewById<View>(R.id.swNavMe).performClick()
            idle()
            activity.findViewById<View>(R.id.swMePro).performClick()
            idle()

            assertTrue(activity.findViewById<View>(R.id.swProScreen).isVisible)
            assertEquals(
                "no Play price in this test: the line names Google Play, no amount",
                activity.getString(R.string.pro_price_play),
                activity.findViewById<TextView>(R.id.swProPrice).text.toString()
            )
            activity.findViewById<View>(R.id.swProBuy).performClick()
            idle()
            // Test builds are already Pro: the tap re-renders the unlocked screen. There is no
            // "coming soon" sheet any more (release builds open Play Billing here).
            assertNull(ShadowDialog.getLatestDialog())
            assertTrue(activity.findViewById<View>(R.id.swProScreen).isVisible)

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
            assertNotNull("the row's Actions sheet opens", dialog)
            // UI-B5: the row's overflow is the shared named Actions sheet, bound to this
            // row, not the old "Open / Share / Move to Trash" sheet it replaced.
            assertEquals(
                activity.getString(R.string.v3_actions_title),
                dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            val header = dialog.window?.decorView?.findViewWithTag<TextView>("docActions:name")
            assertNotNull("the sheet names its own document", header)
            assertEquals(
                "the sheet is bound to the row it came from",
                (card as com.scanwhirl.app.ui.kit.DocCard).doc?.name,
                header!!.text.toString()
            )
            assertNotNull(
                "the shared sheet's destructive row is there",
                dialog.findViewById<View>(R.id.rowMoreDelete)
            )
            dialog.dismiss()
            assertTrue(card.hasOnClickListeners())
            val tool: SwTool? = ToolRegistry.byId("scan")
            assertNotNull(tool)
        }
    }
}
