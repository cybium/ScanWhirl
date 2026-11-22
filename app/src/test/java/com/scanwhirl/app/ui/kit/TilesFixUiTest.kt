package com.scanwhirl.app.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner F6 / F7 / F20: the tiles that did nothing are removed from the catalogue, not hidden
 * behind "Coming soon". Book pages, Photos to PDF, Business card, Duplicate and the two
 * Optimise tiles that only re-opened the viewer (Enhance, B&W) are gone; every other tool stays.
 */
class TilesFixUiTest {

    private val removed = listOf(
        "book_pages", "photos_pdf", "business_card", "duplicate", "enhance", "bw"
    )

    @Test
    fun `the removed tiles are not in the catalogue at all`() {
        for (id in removed) {
            assertNull("$id must be gone from the catalogue", ToolRegistry.byId(id))
        }
        val ids = ToolRegistry.all.map { it.id }
        for (id in removed) {
            assertTrue("$id is still listed", id !in ids)
        }
    }

    @Test
    fun `every other tool is still there`() {
        val kept = listOf(
            "scan", "id_card", "import_pdf", "passport", "qr_code",
            "merge", "split", "extract", "organize_pages", "insert_pages", "folders",
            "batch_rename", "batch_tools",
            "compress", "resize",
            "pdf_to_images", "long_image", "to_word", "to_powerpoint", "to_excel",
            "zip", "searchable_pdf", "pdfa",
            "sign", "add_text", "draw", "highlight", "watermark", "page_numbers", "stamp", "redact",
            "protect", "unlock", "vault", "remove_metadata", "read_text"
        )
        for (id in kept) {
            assertNotNull("$id must stay in the catalogue", ToolRegistry.byId(id))
        }
    }

    @Test
    fun `no group is left empty by the removals`() {
        for ((group, tools) in ToolRegistry.grouped()) {
            assertTrue("$group is empty", tools.isNotEmpty())
        }
        assertTrue(ToolRegistry.byGroup(ToolGroup.CREATE).size >= 5)
        assertTrue(ToolRegistry.byGroup(ToolGroup.ORGANIZE).size >= 8)
        assertEquals(3, ToolRegistry.byGroup(ToolGroup.OPTIMIZE).size)
    }

    @Test
    fun `the derived counts match the shipped catalogue`() {
        // Owner F6/F7/F20 removed six tiles; FORM1 added form_ready; council lane N
        // merged to_text into OCR, so one fewer tile ships.
        // LX added link_index: Pro, READY, one free use (old -> new: 37->38, 36->37,
        // 15->16 on sale, 16->17 pro).
        assertEquals(38, ToolRegistry.all.size)
        assertEquals(37, ToolRegistry.all.count { it.status == ToolStatus.READY })
        assertEquals("one tile is still Coming soon", 1, ToolRegistry.all.count { it.status == ToolStatus.SOON })
        // PRO_PLAN line 10-11 (accepted): folder creation and move-to-folder are FREE;
        // only tags and the other Pro tools stay on sale. Link index is Pro with one free use.
        assertEquals("the Pro catalogue drives the See all count", 16, ToolRegistry.proCount())
        assertEquals(17, ToolRegistry.all.count { it.pro })
    }
}
