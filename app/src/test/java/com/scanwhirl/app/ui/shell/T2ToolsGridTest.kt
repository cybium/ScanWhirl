package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** T2 owner 2026-11-21: icon grid. Geometry, complete labels and one accessible button. */
abstract class T2ToolsGridBase {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.clearRecentTools(app)
        ProGate.setTestOverride(app, false)
    }

    @After fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        ProGate.setTestOverride(app, null)
        Prefs.clearRecentTools(app)
    }

    @Test fun `grid adapts without losing names targets headings or reading order`() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    shadowOf(Looper.getMainLooper()).idle()
                    activity.findViewById<View>(R.id.swNavTools).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
                    val dm = activity.resources.displayMetrics
                    repeat(2) {
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                        )
                        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
                    }
                    val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
                    assertTrue("jobs are tiles, never rows", ToolsHubScreen.rows(body).isEmpty())
                    for ((job, tools) in ToolRegistry.jobs()) {
                        val card = ToolsHubScreen.cardTagged(body, job) as ViewGroup
                        val heading = (card.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
                        assertTrue("job is a TalkBack heading", ViewCompat.isAccessibilityHeading(heading))
                        val tiles = ToolsHubScreen.tiles(card)
                        assertEquals("every tool in registry order", tools.map { it.id }, tiles.map { it.tool!!.id })
                        val grid = tiles.first().parent as ToolsHubScreen.ToolGrid
                        val widthDp = activity.resources.configuration.screenWidthDp
                        if (scale == 1f) {
                            assertEquals("columns on ${widthDp}dp", if (widthDp >= 360) 5 else 4, grid.columnCount)
                        } else {
                            assertTrue("large fonts reduce columns", grid.columnCount < 5)
                        }
                        for ((index, tile) in tiles.withIndex()) {
                            val tool = tile.tool!!
                            val label = tile.labelView
                            val layout = label.layout
                            assertNotNull("${tool.id} label laid out", layout)
                            assertNull("no ellipsis policy", label.ellipsize)
                            assertEquals("all characters laid out", label.text.length, layout.getLineEnd(layout.lineCount - 1))
                            assertTrue("label height includes all lines", layout.height + label.totalPaddingTop +
                                label.totalPaddingBottom <= label.height + 1)
                            for (line in 0 until layout.lineCount) {
                                assertEquals("no ellipsis", 0, layout.getEllipsisCount(line))
                                assertTrue("${label.text} fits column at $scale", layout.getLineMax(line) <=
                                    label.width - label.totalPaddingLeft - label.totalPaddingRight + 0.5f)
                            }
                            assertTrue("48dp target width", tile.width >= 48 * dm.density)
                            assertTrue("48dp target height", tile.height >= 48 * dm.density)
                            assertTrue("label stays inside tile", label.bottom <= tile.height)
                            assertEquals("full spoken name and entitlement",
                                app.getString(tool.title) + if (tool.pro) ", ${app.getString(R.string.badge_pro)}" else "",
                                tile.contentDescription.toString())
                            val node = tile.createAccessibilityNodeInfo()
                            assertEquals("button role", "android.widget.Button", node.className.toString())
                            assertTrue("one focusable button", node.isFocusable && node.isClickable)
                            assertEquals("no child focus nodes", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                                tile.getChildAt(0).importantForAccessibility)
                            if (index > 0) {
                                val previous = tiles[index - 1]
                                assertTrue("row-major reading order", tile.top > previous.top ||
                                    (tile.top == previous.top && tile.left > previous.left))
                                if (tile.top == previous.top) assertTrue("no horizontal overlap", previous.right <= tile.left)
                            }
                        }
                    }
                    // B3 adds one student-jobs card above the grouped catalogue.
                    assertEquals(ToolJob.entries.size + 1, body.childCount)
                    assertEquals(
                        "the student jobs lead the tab in every font/width leg",
                        ToolsHubScreen.TAG_STUDENT_JOBS,
                        (body.getChildAt(0) as ViewGroup).tag
                    )
                }
            }
        }
    }

    @Test fun `six recent tiles keep order in one scrollable row below the student jobs`() {
        RuntimeEnvironment.setFontScale(2f)
        val ids = listOf("searchable_pdf", "add_text", "remove_metadata", "pdf_to_images", "batch_tools", "protect")
        ids.forEach { Prefs.recordRecentTool(app, it) }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                activity.findViewById<View>(R.id.swNavTools).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
                val dm = activity.resources.displayMetrics
                repeat(2) {
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, dm.widthPixels, dm.heightPixels)
                }
                val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
                val card = ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_RECENT) as ViewGroup
                // B3: three named student jobs lead; T1's Recent follows them, then the groups.
                assertEquals("Recent below the student jobs", card, body.getChildAt(1))
                val tiles = ToolsHubScreen.tiles(card)
                assertEquals(ids.reversed(), tiles.map { it.tool!!.id })
                assertEquals("all tiles on same row", setOf(0), tiles.map { it.top }.toSet())
                val grid = tiles.first().parent as ViewGroup
                val scroll = grid.parent as android.widget.HorizontalScrollView
                assertTrue("row can scroll, not squeeze six tiles", grid.width > scroll.width)
                for (tile in tiles) {
                    val label = tile.labelView
                    val layout = label.layout
                    assertNotNull(layout)
                    assertEquals("full recent label", label.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue("full recent label height", layout.height <= label.height)
                    assertTrue("real touch width", tile.width >= 48 * dm.density)
                }
                scroll.scrollTo(grid.width, 0)
                assertTrue("last tool can be reached in the same row", scroll.scrollX > 0)
                assertTrue("last tile visible after scroll", tiles.last().right - scroll.scrollX <= scroll.width)
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class T2ToolsGridTest : T2ToolsGridBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h740dp-xhdpi")
class T2ToolsGridHindiTest : T2ToolsGridBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class T2ToolsGridNarrowTest : T2ToolsGridBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w320dp-h640dp-xhdpi")
class T2ToolsGridNarrowHindiTest : T2ToolsGridBase()
