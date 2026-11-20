package com.scanwhirl.app.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 03_tools_hub — every tool in 70_APP_DESIGN.md §3 is registered once,
 * with a group, an icon, a free/pro flag, a status and a launch action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolRegistryTest {

    @Test
    fun `insert pages is ready and the two unfinished organize tools are coming soon`() {
        // Owner 2026-11-16: Batch rename has no reachable rename action and Organize pages has no
        // working reorder, so both are Coming soon instead of shipping a dead tile.
        assertEquals(ToolStatus.SOON, ToolRegistry.byId("organize_pages")?.status)
        assertEquals(ToolStatus.SOON, ToolRegistry.byId("batch_rename")?.status)
        assertEquals(ToolStatus.READY, ToolRegistry.byId("insert_pages")?.status)
        assertNull("Mask Aadhaar folded into Redact's Find", ToolRegistry.byId("mask_aadhaar"))
        assertNull("Hindi is a language choice, not a tile", ToolRegistry.byId("hindi_text"))
        assertEquals(ToolStatus.READY, ToolRegistry.byId("redact")?.status)
    }

    @Test
    fun `the See all count only counts Pro tools that are on sale`() {
        assertEquals(
            "a Coming-soon Pro tile must not be advertised as a Pro tool",
            ToolRegistry.all.count { it.pro && it.status != ToolStatus.SOON },
            ToolRegistry.proCount()
        )
        assertEquals(
            "folder creation and move are FREE (PRO_PLAN line 10-11): tags are still Pro,",
            15,
            ToolRegistry.proCount()
        )
    }

    // fixui: tiles removed (owner F6/F7/F20) - Book pages, Photos to PDF, Business card,
    // Duplicate, Enhance and B&W left the catalogue: 43 - 6 = 37 tiles (35 READY, 2 Coming soon).
    @Test
    fun `catalogue holds all 37 tools, each id once`() {
        assertEquals(37, ToolRegistry.all.size)
        assertEquals(35, ToolRegistry.all.count { it.status == ToolStatus.READY })
        assertEquals(2, ToolRegistry.all.count { it.status == ToolStatus.SOON })
        assertEquals(
            "duplicate tool ids",
            ToolRegistry.all.size,
            ToolRegistry.all.map { it.id }.toSet().size
        )
    }

    @Test
    fun `sixteen tools are Pro`() {
        assertEquals(16, ToolRegistry.all.count { it.pro })
    }

    @Test
    fun `every READY tool has a visible icon`() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        for (tool in ToolRegistry.all.filter { it.status == ToolStatus.READY }) {
            val drawable = app.getDrawable(tool.icon)
            assertNotNull("${tool.id} has no drawable", drawable)
            val bitmap = android.graphics.Bitmap.createBitmap(
                48, 48, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable!!.setBounds(0, 0, 48, 48)
            drawable.draw(canvas)
            var visible = false
            outer@ for (x in 0 until 48) {
                for (y in 0 until 48) {
                    if (bitmap.getPixel(x, y) ushr 24 != 0) {
                        visible = true
                        break@outer
                    }
                }
            }
            assertTrue("${tool.id} (${tool.title}) draws an empty icon", visible)
        }
    }

    @Test
    fun `every tool has a title, an icon and a group`() {
        for (tool in ToolRegistry.all) {
            assertTrue("${tool.id} has a title", tool.title != 0)
            assertTrue("${tool.id} has an icon", tool.icon != 0)
            assertNotNull("${tool.id} has a group", tool.group)
            assertTrue("${tool.id} has keywords", tool.keywords.isNotBlank())
        }
    }

    @Test
    fun `every Soon tool explains itself in one line`() {
        for (tool in ToolRegistry.all.filter { it.status == ToolStatus.SOON }) {
            assertNotNull("${tool.id} needs a coming-soon note", tool.soonNote)
        }
    }

    @Test
    fun `every tile launches something - no dead taps`() {
        val host = RecordingHost()
        for (tool in ToolRegistry.all) {
            host.events.clear()
            tool.launch(host, tool)
            assertTrue(
                "${tool.id} (${tool.status}) is a dead tap",
                host.events.isNotEmpty()
            )
        }
    }

    @Test
    fun `Soon tools open the Coming soon sheet`() {
        val host = RecordingHost()
        val soon = ToolRegistry.all.filter { it.status == ToolStatus.SOON }
        // v2 UI: 03_tools_hub - every catalogue tool ships now; the loop still guards any future
        // Soon entry so it can never become a dead tap.
        for (tool in soon) {
            host.events.clear()
            tool.launch(host, tool)
            assertEquals(listOf("soon:${tool.id}"), host.events)
        }
    }

    @Test
    fun `search finds tools by title and by keyword`() {
        assertTrue(ToolRegistry.search("sign").any { it.id == "sign" })
        assertTrue("'smaller' must find Compress", ToolRegistry.search("smaller").any { it.id == "compress" })
        assertTrue(ToolRegistry.search("छोटा").any { it.id == "compress" })
        assertTrue(ToolRegistry.search("").isEmpty())
    }

    @Test
    fun `groups are in catalogue order and non-empty`() {
        assertEquals(ToolGroup.entries, ToolRegistry.all.map { it.group }.distinct())
        for (group in ToolGroup.entries) {
            assertTrue("$group is empty", ToolRegistry.byGroup(group).isNotEmpty())
        }
    }

    @Test
    fun `search ranks title matches before keyword matches`() {
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        fun ids(q: String) = ToolRegistry.search(app, q).map { it.id }

        assertEquals("a title starts-with wins", "read_text", ids("read").first())
        assertEquals("Sign is the Sign tool", "sign", ids("sign").first())
        assertEquals("a PDF title starts-with wins", "pdf_to_images", ids("pdf").first())
        assertEquals("keyword 'smaller' finds Compress", "compress", ids("small").first())
        assertTrue("a keyword substring still matches", ids("scan").contains("scan"))
    }

    private class RecordingHost : ToolHost {
        val events = mutableListOf<String>()
        override fun open(tool: SwTool) {
            events += "open:${tool.id}"
        }

        override fun comingSoon(tool: SwTool) {
            events += "soon:${tool.id}"
        }
    }
}
