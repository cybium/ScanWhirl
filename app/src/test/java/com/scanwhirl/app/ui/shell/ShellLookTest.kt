package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
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
 * v2 UI: 01_home_A / 03_tools_hub / 04_vault / 06_pro — the shell's look on a
 * 360dp phone: six equal quick columns, two-line tool labels, one tag per tile,
 * the real brand mark and the nav glyphs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ShellLookTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
        DocFixtures.saved(repo, app, pages = 2, name = "Look doc")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    /** Lays the whole shell out exactly as a 360dp wide phone would. */
    private fun layoutRoot(activity: ShellActivity): View {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.EXACTLY)
        val h = android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return root
    }

    private fun drawableId(view: ImageView): Int = shadowOf(view.drawable).createdFromResId

    @Test
    fun `the six quick tools fit 360dp with one-line labels`() {
        launch { activity ->
            layoutRoot(activity)
            val quick = activity.findViewById<ViewGroup>(R.id.swQuickRow)
            assertEquals(6, quick.childCount)
            assertTrue("the row starts on screen", quick.left >= 0)
            assertTrue(
                "the row ends on screen",
                quick.right <= activity.resources.displayMetrics.widthPixels
            )

            var sum = 0
            for (i in 0 until quick.childCount) {
                val tile = quick.getChildAt(i)
                assertTrue("column $i has width", tile.width > 0)
                sum += tile.width
                val circle = tile.findViewById<View>(R.id.swToolIcon)
                assertTrue("column $i circle starts inside", circle.left >= 0)
                assertTrue("column $i circle ends inside", circle.right <= tile.width)
                val label = tile.findViewById<TextView>(R.id.swToolLabel)
                val l = label.layout
                assertNotNull("${label.text} is laid out", l)
                assertEquals("${label.text} stays on one line", 1, l!!.lineCount)
                assertEquals("${label.text} is never ellipsized", 0, l.getEllipsisCount(0))
                val avail = label.width - label.totalPaddingLeft - label.totalPaddingRight
                assertTrue(
                    "${label.text} fits its column (${l.getLineWidth(0)} > $avail)",
                    l.getLineWidth(0) <= avail + 0.5f
                )
            }
            assertTrue("six equal columns fit the row", sum <= quick.width)
        }
    }

    @Test
    fun `tool hub labels use two lines and are fully visible`() {
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            layoutRoot(activity)
            val body = activity.findViewById<View>(R.id.swToolsBody)
            val tiles = ToolsHubScreen.tiles(body)
            assertEquals(com.scanwhirl.app.ui.kit.ToolRegistry.all.size, tiles.size)
            for (tile in tiles) {
                val label = tile.findViewById<TextView>(R.id.swToolLabel)
                val l = label.layout
                assertNotNull("${label.text} is laid out", l)
                assertTrue("${label.text} uses at most 2 lines", l!!.lineCount <= 2)
                for (line in 0 until l.lineCount) {
                    assertEquals("${label.text} line $line is not ellipsized", 0, l.getEllipsisCount(line))
                }
                assertTrue(
                    "${label.text} is fully visible (needs ${l.height}px, has ${label.height}px)",
                    l.height + label.totalPaddingTop + label.totalPaddingBottom <= label.height + 1
                )
            }
        }
    }

    @Test
    fun `a Pro Soon tile shows only the Soon tag and keeps it readable`() {
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val tile = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "pdfa" } // Pro + Soon
            val soon = tile.findViewById<View>(R.id.swToolSoonTag)
            val pro = tile.findViewById<View>(R.id.swToolProTag)
            assertTrue("Soon wins", soon.isVisible)
            assertFalse("the PRO tag is not shown beside Soon", pro.isVisible)
            assertEquals("the tile itself is not dimmed", 1f, tile.alpha, 0f)
            assertTrue("the Soon tag is not dimmed", soon.alpha > 0.9f)
        }
    }

    @Test
    fun `Me rows use the real glyphs and the header uses the brand mark`() {
        launch { activity ->
            assertEquals(
                "header mark",
                R.drawable.ic_brand_mark,
                drawableId(activity.findViewById(R.id.swBrandMark))
            )
            activity.findViewById<View>(R.id.swNavMe).performClick()
            idle()
            val expected = mapOf(
                R.id.swMePro to R.drawable.ic_sym_star_filled,
                R.id.swMeSettings to R.drawable.ic_sym_settings,
                R.id.swMeTrash to R.drawable.ic_sym_delete,
                R.id.swMeAbout to R.drawable.ic_sym_info
            )
            for ((rowId, iconRes) in expected) {
                val row = activity.findViewById<ViewGroup>(rowId)
                assertEquals("row $rowId icon", iconRes, drawableId(row.getChildAt(0) as ImageView))
            }
        }
    }

    @Test
    fun `the nav uses filled glyphs for the open tab and outlines for the rest`() {
        launch { activity ->
            fun navIcon(id: Int) =
                (activity.findViewById<ViewGroup>(id).getChildAt(0) as ImageView)
            assertEquals(R.drawable.ic_sym_folder_filled, drawableId(navIcon(R.id.swNavLibrary)))
            assertEquals(R.drawable.ic_sym_apps, drawableId(navIcon(R.id.swNavTools)))
            assertEquals(R.drawable.ic_sym_shield_lock, drawableId(navIcon(R.id.swNavVault)))
            assertEquals(R.drawable.ic_sym_person, drawableId(navIcon(R.id.swNavMe)))

            activity.findViewById<View>(R.id.swNavVault).performClick()
            idle()
            assertEquals(R.drawable.ic_sym_folder_open, drawableId(navIcon(R.id.swNavLibrary)))
            assertEquals(R.drawable.ic_sym_shield_lock_filled, drawableId(navIcon(R.id.swNavVault)))
        }
    }

    @Test
    fun `every tab scrolls clear of the nav bar and scan disc`() {
        launch { activity ->
            val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
            val scroll = host.getChildAt(0) as ScrollView
            val need = 100 * activity.resources.displayMetrics.density
            assertTrue(
                "content bottom padding ${scroll.paddingBottom}px clears the nav (${need.toInt()}px)",
                scroll.paddingBottom >= need
            )
            assertFalse("content may draw under the nav", scroll.clipToPadding)
        }
    }
}
