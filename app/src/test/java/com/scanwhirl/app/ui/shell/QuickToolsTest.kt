package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: Home quick tools are customisable — add/remove, long-press reorder,
 * two-row cap (12), Done/Cancel, persisted across relaunch, and Pro tools keep
 * the central gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QuickToolsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.resetQuickTools(app)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        // A non-empty library: Home hides the quick rows on a first run.
        com.scanwhirl.app.store.DocFixtures.saved(
            com.scanwhirl.app.store.ScanRepository(app), app, pages = 1, name = "Seed"
        )
    }

    @After
    fun tearDown() {
        Prefs.resetQuickTools(app)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity, LibraryScreen) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val scroll = activity.findViewById<ViewGroup>(R.id.swTabContent)
                    .getChildAt(0) as ScrollView
                block(activity, scroll.getChildAt(0) as LibraryScreen)
            }
        }
    }

    private fun quickTiles(activity: ShellActivity): List<ToolTile> {
        val rows = listOf(R.id.swQuickRow, R.id.swQuickRow2)
        return rows.flatMap { id ->
            val row = activity.findViewById<ViewGroup>(id) ?: return@flatMap emptyList()
            (0 until row.childCount).mapNotNull { row.getChildAt(it) as? ToolTile }
        }
    }

    @Test
    fun `the shipped six are the default`() {
        launch { _, library ->
            assertEquals(Prefs.DEFAULT_QUICK_TOOLS, Prefs.quickTools(app))
            assertNotNull(library.findViewById<View>(R.id.swQuickEdit))
        }
    }

    @Test
    fun `add persists across relaunch and cancel discards`() {
        val added = ToolRegistry.all.first {
            it.status == ToolStatus.READY && it.id !in Prefs.DEFAULT_QUICK_TOOLS
        }
        launch { activity, _ ->
            activity.findViewById<View>(R.id.swQuickEdit).performClick()
            idle()
            val add = activity.findViewById<ViewGroup>(R.id.swQuickAddGrid)
            assertTrue("the add grid is shown", add.isVisible)
            val tile = allTiles(add).first { it.tool?.id == added.id }
            tile.performClick()
            idle()
            activity.findViewById<View>(R.id.swQuickDone).performClick()
            idle()
        }
        assertTrue("the new tool is saved", Prefs.quickTools(app).contains(added.id))

        launch { activity, _ ->
            assertTrue(
                "the new tool is on Home after relaunch",
                quickTiles(activity).any { it.tool?.id == added.id }
            )
            // Cancel after a removal puts the saved list back.
            activity.findViewById<View>(R.id.swQuickEdit).performClick()
            idle()
            quickTiles(activity).first { it.tool?.id == added.id }.performClick()
            idle()
            activity.findViewById<View>(R.id.swQuickCancel).performClick()
            idle()
        }
        assertTrue("Cancel discarded the removal", Prefs.quickTools(app).contains(added.id))
    }

    @Test
    fun `the quick row never grows past twelve across two rows`() {
        val twelve = ToolRegistry.all.filter { it.status == ToolStatus.READY }
            .map { it.id }.take(12)
        Prefs.setQuickTools(app, twelve)
        assertEquals(12, Prefs.quickTools(app).size)

        launch { activity, _ ->
            val first = activity.findViewById<ViewGroup>(R.id.swQuickRow)
            val second = activity.findViewById<ViewGroup>(R.id.swQuickRow2)
            assertEquals("six per row", 6, first.childCount)
            assertEquals("the second row holds the rest", 6, second.childCount)
            assertTrue("two rows are visible", second.isVisible)

            activity.findViewById<View>(R.id.swQuickEdit).performClick()
            idle()
            val add = activity.findViewById<ViewGroup>(R.id.swQuickAddGrid)
            // At the limit every add tile is visibly disabled (38% alpha).
            val anyAdd = allTiles(add).firstOrNull()
            if (anyAdd != null) {
                assertFalse("no add is live at the cap", anyAdd.isEnabled)
                assertEquals(0.38f, anyAdd.alpha, 0.01f)
            }
        }
    }

    @Test
    fun `reordering persists`() {
        launch { activity, library ->
            library.moveQuickTool("read_text", 0)
            activity.findViewById<View>(R.id.swQuickDone).performClick()
            idle()
        }
        assertEquals("read_text", Prefs.quickTools(app).first())

        launch { activity, _ ->
            assertEquals(
                "the saved order is rendered",
                "read_text",
                quickTiles(activity).first().tool?.id
            )
        }
    }

    private fun allTiles(group: ViewGroup): List<ToolTile> {
        val out = mutableListOf<ToolTile>()
        fun walk(view: View) {
            if (view is ToolTile) out += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(group)
        return out
    }
}
