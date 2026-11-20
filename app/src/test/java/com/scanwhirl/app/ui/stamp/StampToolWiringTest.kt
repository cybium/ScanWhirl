package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
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
 * L5 Phase B: the eight Edit & sign catalogue entries are READY, are Pro, and each opens the
 * stamp editor with its own tool already armed after a library document is picked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampToolWiringTest {

    class Host : AppCompatActivity(), ToolHost {
        val events = mutableListOf<String>()
        override fun open(tool: SwTool) { events += "open:${tool.id}" }
        override fun comingSoon(tool: SwTool) { events += "soon:${tool.id}" }
    }

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    /** catalogue id → the tool the editor must be armed with. */
    private val editTools = linkedMapOf(
        "sign" to StampEditorActivity.TOOL_SIGN,
        "add_text" to StampEditorActivity.TOOL_TEXT,
        "draw" to StampEditorActivity.TOOL_DRAW,
        "highlight" to StampEditorActivity.TOOL_HIGHLIGHT,
        "watermark" to StampEditorActivity.TOOL_WATERMARK,
        "page_numbers" to StampEditorActivity.TOOL_PAGE_NUMBERS,
        "stamp" to StampEditorActivity.TOOL_STAMP,
        "redact" to StampEditorActivity.TOOL_REDACT
    )

    private fun clickItem(index: Int) {
        // UI-W6: Stamp/Redact use the shared searchable picker (rows in docPickerList).
        val dialog = ShadowDialog.getLatestDialog()
        val picker = dialog.findViewById<android.view.ViewGroup>(R.id.docPickerList)
        if (picker != null) {
            picker.getChildAt(index).performClick()
        } else {
            dialog.findViewById<android.view.ViewGroup>(R.id.swSheetItems).getChildAt(index).performClick()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `each of the eight entries opens the editor with its own tool preselected`() {
        val docId = DocFixtures.saved(repo, context, pages = 1, name = "KYC")
        val host = Robolectric.buildActivity(Host::class.java).setup().get()

        for ((toolId, editorTool) in editTools) {
            val tool = requireNotNull(ToolRegistry.byId(toolId)) { "$toolId is registered" }
            assertEquals("$toolId must be built now", ToolStatus.READY, tool.status)
            assertTrue("$toolId is a Pro tool", tool.pro)

            tool.launch(host, tool)
            val chooser = requireNotNull(ShadowDialog.getLatestDialog()) {
                "$toolId must ask for a document first"
            }
            assertEquals(
                "$toolId lists the library",
                1,
                chooser.findViewById<android.view.ViewGroup>(R.id.docPickerList)?.childCount
            )
            clickItem(0)

            val started = requireNotNull(shadowOf(host).nextStartedActivity) {
                "$toolId must open the editor"
            }
            assertEquals(StampEditorActivity::class.java.name, started.component?.className)
            assertEquals("$toolId document", docId, started.getStringExtra(StampEditorActivity.EXTRA_DOC_ID))
            assertEquals(
                "$toolId must arm its own editor tool",
                editorTool,
                started.getStringExtra(StampEditorActivity.EXTRA_TOOL)
            )
        }
    }

    @Test
    fun `a host that cannot start an activity gets the honest coming soon sheet`() {
        val host = object : ToolHost {
            val events = mutableListOf<String>()
            override fun open(tool: SwTool) { events += "open:${tool.id}" }
            override fun comingSoon(tool: SwTool) { events += "soon:${tool.id}" }
        }
        val tool = requireNotNull(ToolRegistry.byId("sign"))
        tool.launch(host, tool)
        assertEquals(listOf("soon:sign"), host.events)
    }

    @Test
    fun `with no documents the tool says so instead of opening an empty picker`() {
        val host = Robolectric.buildActivity(Host::class.java).setup().get()
        val tool = requireNotNull(ToolRegistry.byId("redact"))
        tool.launch(host, tool)
        assertNull("no empty picker", ShadowDialog.getLatestDialog())
        assertEquals(context.getString(R.string.stamp_no_documents), ShadowToast.getTextOfLatestToast())
    }
}
