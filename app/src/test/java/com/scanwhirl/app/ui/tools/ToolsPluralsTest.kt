package com.scanwhirl.app.ui.tools

import android.app.Application
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 03_tools_hub - count copy uses Android plurals, never "1 pages": the setup source list,
 * the result summary, the split/extract previews and the long-image note.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolsPluralsTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        DocFixtures.saved(repo, app, pages = 1, name = "Single page")
        DocFixtures.saved(repo, app, pages = 2, name = "Two pages")
    }

    @Test
    fun `page counts read one page and two pages`() {
        assertEquals(
            "1 page · 10 KB",
            app.resources.getQuantityString(R.plurals.tools_setup_doc_meta, 1, 1, "10 KB")
        )
        assertEquals(
            "2 pages · 10 KB",
            app.resources.getQuantityString(R.plurals.tools_setup_doc_meta, 2, 2, "10 KB")
        )
    }

    @Test
    fun `file and image counts are plural`() {
        assertEquals(
            "1 file · 10 KB",
            app.resources.getQuantityString(R.plurals.tools_result_summary_files, 1, 1, "10 KB")
        )
        assertEquals(
            "2 files · 10 KB",
            app.resources.getQuantityString(R.plurals.tools_result_summary_files, 2, 2, "10 KB")
        )
        assertTrue(
            app.resources.getQuantityString(R.plurals.tools_setup_long_note, 1, 1)
                .startsWith("Makes 1 image,")
        )
        assertTrue(
            app.resources.getQuantityString(R.plurals.tools_setup_long_note, 2, 2)
                .startsWith("Makes 2 images,")
        )
        assertTrue(
            app.resources.getQuantityString(R.plurals.tools_setup_split_preview, 1, 1, "page 1")
                .startsWith("1 new document:")
        )
        assertTrue(
            app.resources.getQuantityString(R.plurals.tools_setup_split_preview, 2, 2, "pages 1-2")
                .startsWith("2 new documents:")
        )
        assertTrue(
            app.resources.getQuantityString(R.plurals.tools_setup_extract_preview, 1, 1, "page 1")
                .startsWith("1 page kept:")
        )
    }

    @Test
    fun `setup source list shows one page and two pages`() {
        val docs = repo.listDocuments()
        val one = docs.first { it.pageCount == 1 }
        val two = docs.first { it.pageCount == 2 }
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "merge")).use { scenario ->
            scenario.onActivity { activity ->
                val source = activity.findViewById<LinearLayout>(R.id.toolSetupSource)
                val rows = source.children().filterIsInstance<LinearLayout>().filter { it.tag is String }
                assertEquals(2, rows.size)
                val meta = rows.associate { row ->
                    // v2 UI: 03_tools_setup_picker — the meta line sits under the name in the
                    // row's text column (the row now leads with a page thumbnail).
                    row.tag as String to row.findViewById<TextView>(R.id.toolSetupDocMeta).text.toString()
                }
                assertTrue("one-page row: ${meta[one.id]}", meta[one.id]!!.startsWith("1 page · "))
                assertTrue("two-page row: ${meta[two.id]}", meta[two.id]!!.startsWith("2 pages · "))
                assertNotNull(meta)
            }
        }
    }

    private fun LinearLayout.children(): List<android.view.View> =
        (0 until childCount).map { getChildAt(it) }
}
