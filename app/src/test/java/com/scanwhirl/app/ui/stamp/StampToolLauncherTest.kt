package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SettingsActivity
import org.junit.Assert.assertEquals
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
 * L5 Phase B: the Tools-hub bridge. After the lead merges L1, the eight Edit & sign entries
 * call [StampToolLauncher.open]; this test proves the mapping, the picker and the extras.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampToolLauncherTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

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
    fun `the picker opens the editor with the chosen document and tool`() {
        val id = DocFixtures.saved(repo, context, pages = 2, name = "Rent agreement")
        val host = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        StampToolLauncher.open(host, "redact")

        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "a document picker opens" }
        val rows = sheet.findViewById<LinearLayout>(R.id.stampDocumentRows)!!
        assertEquals(1, rows.childCount)
        rows.getChildAt(0).performClick()

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
}
