package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * L5 Phase B: the Tools-hub bridge. The eight Edit & sign entries call
 * [StampToolLauncher.open], which uses the same document chooser as Merge/Compress
 * (`FlowUtil.pickScan`) behind the Pro gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampToolLauncherTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    private fun clickItem(index: Int) {
        // v2 UI: 03_tools_hub — the shared chooser is now a kit SwSheet (rows in swSheetItems).
        ShadowDialog.getLatestDialog().findViewById<android.view.ViewGroup>(R.id.swSheetItems).getChildAt(index).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `every catalogue id maps to an editor tool`() {
        assertEquals(StampEditorActivity.TOOL_SIGN, StampToolLauncher.toolFor("sign"))
        assertEquals(StampEditorActivity.TOOL_TEXT, StampToolLauncher.toolFor("add_text"))
        assertEquals(StampEditorActivity.TOOL_DRAW, StampToolLauncher.toolFor("draw"))
        assertEquals(StampEditorActivity.TOOL_HIGHLIGHT, StampToolLauncher.toolFor("highlight"))
        assertEquals(StampEditorActivity.TOOL_WATERMARK, StampToolLauncher.toolFor("watermark"))
        assertEquals(StampEditorActivity.TOOL_PAGE_NUMBERS, StampToolLauncher.toolFor("page_numbers"))
        assertEquals(StampEditorActivity.TOOL_STAMP, StampToolLauncher.toolFor("stamp"))
        assertEquals(StampEditorActivity.TOOL_REDACT, StampToolLauncher.toolFor("redact"))
        assertNull("a tool from another lane is not ours", StampToolLauncher.toolFor("merge"))
    }

    @Test
    fun `the shared document chooser opens the editor with the chosen document and tool`() {
        val id = DocFixtures.saved(repo, context, pages = 2, name = "Rent agreement")
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        StampToolLauncher.open(host, "redact")

        val chooser = requireNotNull(ShadowDialog.getLatestDialog()) { "a document chooser opens" }
        // v2 UI: 03_tools_hub — Merge/Compress' chooser is reused; it is a kit SwSheet now.
        val rows = chooser.findViewById<android.view.ViewGroup>(R.id.swSheetItems)
        assertEquals("the saved document is listed", 1, rows.childCount)
        clickItem(0)

        val started = requireNotNull(shadowOf(host).nextStartedActivity) { "the editor starts" }
        assertEquals(StampEditorActivity::class.java.name, started.component?.className)
        assertEquals(id, started.getStringExtra(StampEditorActivity.EXTRA_DOC_ID))
        assertEquals(StampEditorActivity.TOOL_REDACT, started.getStringExtra(StampEditorActivity.EXTRA_TOOL))
    }

    @Test
    fun `an empty library says so instead of opening a dead picker`() {
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        StampToolLauncher.open(host, "sign")
        assertEquals(
            context.getString(R.string.stamp_no_documents),
            ShadowToast.getTextOfLatestToast()
        )
        assertNull("no sheet with no documents", ShadowDialog.getLatestDialog())
    }

    @Test
    fun `a locked document is not offered`() {
        DocFixtures.locked(repo, context)
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        StampToolLauncher.open(host, "sign")
        assertNull("a protected document cannot be stamped", ShadowDialog.getLatestDialog())
        assertTrue(ShadowToast.getTextOfLatestToast().isNotEmpty())
    }

    @Test
    fun `a free build gets the Pro sheet instead of the chooser`() {
        DocFixtures.saved(repo, context, pages = 1)
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        ProGate.setTestOverride(host, false)
        try {
            StampToolLauncher.open(host, "watermark")

            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "the Pro sheet opens" }
            val title = sheet.findViewById<TextView>(R.id.swSheetTitle)
            assertEquals(context.getString(R.string.pro_suite_title), title?.text?.toString())
            assertTrue(
                "nothing in the library may be picked for a Pro tool on Free",
                sheet.findViewById<android.view.View>(android.R.id.list) == null
            )
            assertNull("the editor does not start", shadowOf(host).nextStartedActivity)
        } finally {
            ProGate.setTestOverride(host, null)
        }
    }

    @Test
    fun `a Pro test build is not blocked`() {
        DocFixtures.saved(repo, context, pages = 1)
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue("test builds unlock Pro", ProGate.isPro(host))
        StampToolLauncher.open(host, "sign")
        assertNotNull("the chooser opens", ShadowDialog.getLatestDialog())
    }
}
