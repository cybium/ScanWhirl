package com.scanwhirl.app.ux130.v2l

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.shell.ToolsHubScreen
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
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
 * V2L L2 — the Organize pages tile opens the document chooser and then the existing
 * selectable viewer. RED on base: the tile was SOON and opened a Coming soon sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class V2OrganizeRouteTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test(timeout = 30_000)
    fun `organize pages is a ready tile that opens the document picker then the viewer`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Organize me")
        assertEquals(ToolStatus.READY, ToolRegistry.byId("organize_pages")?.status)
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
            val tile = ToolsHubScreen.tiles(body).first { it.tool?.id == "organize_pages" }
            tile.performClick()
            idle()
            val picker = ShadowDialog.getLatestDialog()
            assertNotNull("the document chooser opens", picker)
            val list = picker!!.findViewById<ViewGroup>(R.id.docPickerList)
            assertNotNull("the picker lists real documents", list)
            assertTrue("the seeded document is listed", list.childCount > 0)
            (list.getChildAt(0) as View).performClick()
            idle()
            val next = shadowOf(activity).nextStartedActivity
            assertNotNull("the existing viewer opens", next)
            assertEquals(
                DocumentViewerActivity::class.java.name,
                next!!.component?.className
            )
            assertEquals(id, next.getStringExtra("doc_id"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
