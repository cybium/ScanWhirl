package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * ATLAS-F2 verdict for the two screens the atlas rendered empty (030_business_card and
 * 031_photo_crop). Both are render limitations of that sweep, not missing actions:
 *
 *  - Business card opens its own capture sheet (a separate window) with a filled "Scan card"
 *    primary and "Pick from gallery" secondary. The atlas captures the activity decor, so the
 *    sheet is missing from 030's picture even though it owns the screen's only action.
 *  - Crop photo is a flow host: it is only ever opened with picked photos. With no sources it
 *    says so and leaves; it has no standalone screen to render, so 031 is a black frame.
 *
 * These tests pin those two facts so a later change cannot silently turn either into a
 * genuinely empty screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ToolEntryStatesTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    @Test
    fun `Business card opens with a visible primary capture action`() {
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Business card opens its capture sheet on entry", sheet)
                val sheetDialog = sheet!!
                assertTrue("the capture sheet is showing", sheetDialog.isShowing)
                val primary = sheetDialog.findViewById<TextView>(R.id.swSheetPrimary)
                assertNotNull("the capture sheet has a primary action", primary)
                val primaryView = primary!!
                assertTrue("the primary action is visible", primaryView.isVisible)
                assertEquals(
                    "the primary action starts the card scan",
                    activity.getString(R.string.tools_bc_scan),
                    primaryView.text.toString()
                )
                val secondary = sheetDialog.findViewById<TextView>(R.id.swSheetSecondary)
                assertNotNull("a gallery alternative is offered", secondary)
                assertEquals(
                    "the alternative picks from the gallery",
                    activity.getString(R.string.tools_id_pick_gallery),
                    secondary!!.text.toString()
                )
                sheetDialog.dismiss()
            }
        }
    }

    /**
     * B3 count reconciliation: the registry has 38 entries, one of which is still Coming soon.
     * The spec's "usual 37 tools" are the working ones. A SOON entry must never be offered as
     * a working entry point — not even when someone searches its old name.
     */
    @Test
    fun `Coming soon entry is not one of the 37 working tool entry points`() {
        assertEquals(38, ToolRegistry.all.size)
        assertEquals(37, ToolRegistry.shippingCount())
        val soon = ToolRegistry.all.filter { it.status == ToolStatus.SOON }
        assertEquals(listOf("batch_rename"), soon.map { it.id })
        val entry = soon.single()
        assertTrue(
            "a Coming-soon entry is never a search result",
            ToolRegistry.search(app, app.getString(entry.title)).none { it.id == entry.id }
        )
        assertTrue(
            "a Coming-soon entry is never a search alias hit",
            ToolRegistry.search(app, "rename").none { it.id == entry.id }
        )
    }

    @Test
    fun `Crop photo with no picked photo says so and leaves instead of a blank screen`() {
        // The atlas opens this flow host directly, without the picked photos a real flow hands
        // it. That must stay an honest, immediate exit — never a blank interactive screen.
        val controller = Robolectric.buildActivity(PhotoCropFlowActivity::class.java).create()
        try {
            val activity = controller.get()
            assertTrue(
                "no sources: the crop flow finishes instead of showing an empty screen",
                activity.isFinishing
            )
            assertEquals(
                "the flow explains why it closed",
                app.getString(R.string.fixinsertcrop_crop_failed),
                ShadowToast.getTextOfLatestToast()?.toString()
            )
        } finally {
            runCatching { controller.destroy() }
        }
    }
}
