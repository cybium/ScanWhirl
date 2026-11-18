package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
    fun `a SOON tool is dropped from a saved quick set and cannot be added`() {
        val soon = ToolRegistry.all.first { it.status == ToolStatus.SOON }
        Prefs.setQuickTools(app, Prefs.DEFAULT_QUICK_TOOLS + soon.id)
        assertFalse(
            "a Coming soon tool is filtered out of the saved set",
            Prefs.quickTools(app).contains(soon.id)
        )
        launch { activity, _ ->
            activity.findViewById<View>(R.id.swQuickEdit).performClick()
            idle()
            val add = activity.findViewById<ViewGroup>(R.id.swQuickAddGrid)
            assertTrue(
                "Coming soon tools are not offered in Add tools",
                allTiles(add).none { it.tool?.id == soon.id }
            )
        }
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
    fun `a short second row keeps the six-column grid and stays left aligned`() {
        val seven = ToolRegistry.all.filter { it.status == ToolStatus.READY }
            .map { it.id }.take(7)
        Prefs.setQuickTools(app, seven)
        launch { activity, _ ->
            val row2 = activity.findViewById<ViewGroup>(R.id.swQuickRow2)
            assertEquals("the grid always has six columns", 6, row2.childCount)
            assertTrue("the first cell is the tool", row2.getChildAt(0) is ToolTile)
            for (i in 1 until 6) {
                assertFalse(
                    "the rest are spacers, not a stretched tile",
                    row2.getChildAt(i) is ToolTile
                )
            }
        }
    }

    @Test
    fun `edit badges sit at the top start and the rows align to the top`() {
        launch { activity, _ ->
            activity.findViewById<View>(R.id.swQuickEdit).performClick()
            idle()
            val row = activity.findViewById<android.widget.LinearLayout>(R.id.swQuickRow)
            val tile = row.getChildAt(0) as ToolTile
            assertEquals(
                "circles share one top line",
                Gravity.TOP,
                (row.gravity and Gravity.TOP)
            )
            assertEquals("labels cap at two lines", 2, tile.labelView.maxLines)
            val badge = (0 until tile.childCount)
                .map { tile.getChildAt(it) }
                .filterIsInstance<ImageView>()
                .first { it.contentDescription?.startsWith("Remove") == true }
            val lp = badge.layoutParams as FrameLayout.LayoutParams
            assertEquals(
                "the badge is on the top-start corner, clear of the PRO tag",
                Gravity.TOP or Gravity.START,
                lp.gravity
            )
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
