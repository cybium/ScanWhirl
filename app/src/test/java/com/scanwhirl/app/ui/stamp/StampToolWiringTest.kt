package com.scanwhirl.app.ui.stamp

import android.app.Application
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** L5 Phase B: the 8 Edit & sign catalogue entries are READY and open the stamp editor. */
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

    private val editTools = listOf(
        "sign", "add_text", "draw", "highlight",
        "watermark", "page_numbers", "stamp", "redact"
    )

    @Test
    fun `the eight edit tools are ready and open the document picker`() {
        DocFixtures.saved(repo, context, pages = 1, name = "KYC")
        val host = Robolectric.buildActivity(Host::class.java).setup().get()

        for (toolId in editTools) {
            val tool = requireNotNull(ToolRegistry.byId(toolId)) { "$toolId is registered" }
            assertEquals("$toolId must be built now", ToolStatus.READY, tool.status)
            tool.launch(host, tool)
            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) {
                "$toolId must open a real screen"
            }
            sheet.dismiss()
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
