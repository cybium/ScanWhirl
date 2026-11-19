package com.scanwhirl.app.ui.shell

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

/**
 * v2 UI: 03_tools_hub — owner critique 2026-11-18: a tool tile is a 48dp tonal
 * container with a 26dp accent glyph, a 12.5sp semibold label under it, the group
 * clear of the tile's top edge, and the PRO badge on the container's corner.
 * The Tools tab shows no Coming soon tile and its headings are sentence case.
 *
 * Run in English and Hindi at 360dp, the Hindi class also at fontScale 1.3.
 */
abstract class ToolsLookBase {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    private val density: Float
        get() = app.resources.displayMetrics.density

    private val scaledDensity: Float
        get() = app.resources.displayMetrics.scaledDensity

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // a Free build draws every PRO badge
        DocFixtures.saved(repo, app, pages = 1, name = "Look doc")
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dp(value: Float): Int = (value * density).toInt()

    /** Lays the whole shell out as the 360dp phone would. */
    private fun layoutRoot(activity: ShellActivity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    /** Opens the Tools tab and lays the whole shell out as the 360dp phone would. */
    private fun toolsTab(block: (ShellActivity, ViewGroup) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.swNavTools).performClick()
                idle()
                layoutRoot(activity)
                block(activity, activity.findViewById(R.id.swToolsBody))
            }
        }
    }

    /** One tile measured at the width a 4-column grid gives it on a 360dp phone. */
    private fun tileOf(toolId: String, widthDp: Int = 78): ToolTile {
        val tile = ToolTile(app)
        tile.bind(ToolRegistry.byId(toolId)!!)
        val w = View.MeasureSpec.makeMeasureSpec(dp(widthDp.toFloat()), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dp(400f), View.MeasureSpec.AT_MOST)
        tile.measure(w, h)
        tile.layout(0, 0, tile.measuredWidth, tile.measuredHeight)
        return tile
    }

    @Test
    fun `a tile is a 48dp container holding a 26dp glyph with a 12_5sp label below`() {
        val tile = tileOf("compress")

        assertEquals("container width", 48f, tile.iconBox.width / density, 0.5f)
        assertEquals("container height", 48f, tile.iconBox.height / density, 0.5f)
        val containerTopDp = (tile.iconSlot.top + tile.iconBox.top) / density
        assertTrue(
            "container top clears the tile top by >= 12dp, was ${containerTopDp}dp " +
                "(slot top=${tile.iconSlot.top / density}, box top=${tile.iconBox.top / density}, " +
                "slot h=${tile.iconSlot.height / density})",
            containerTopDp >= 12f
        )
        assertTrue("glyph is at least 24dp", tile.iconView.width / density >= 24f)
        assertEquals("glyph width", 26f, tile.iconView.width / density, 0.5f)
        assertEquals("glyph height", 26f, tile.iconView.height / density, 0.5f)

        val labelSp = tile.labelView.textSize / scaledDensity
        assertTrue("label is 12-13sp, was $labelSp", labelSp >= 12f && labelSp <= 13f)

        assertTrue("the label sits under the container", tile.labelView.top >= tile.iconSlot.bottom)
        val labelGap = (tile.labelView.top - tile.iconSlot.bottom) / density
        assertTrue("the label hugs the container (8-12dp gap), was ${labelGap}dp", labelGap in 6f..12f)
        assertTrue(
            "the icon group is centred in the tile",
            abs(tile.iconSlot.left - (tile.width - tile.iconSlot.right)) <= 1
        )
        // Content-sized: 12dp + 48dp + 8dp + label + 8dp, with no fixed minimum height.
        assertEquals("no fixed minimum height", 0, tile.minimumHeight)
        val expected = dp(12f + 48f + 8f + 8f) + (tile.labelView.layout?.height ?: 0)
        assertTrue(
            "the tile is content-sized (${tile.height / density}dp, expected ${expected / density}dp)",
            abs(tile.height - expected) <= dp(1f)
        )
    }

    @Test
    fun `the PRO badge clears the glyph and stays inside the tile`() {
        val tile = tileOf("sign")
        val tag = tile.proTag
        assertTrue("a Free build shows the PRO badge", tag.isVisible)
        assertEquals("the badge says PRO", app.getString(R.string.badge_pro), tag.text.toString())
        assertEquals("the badge is at least 12sp", 12f, tag.textSize / scaledDensity, 0.6f)

        // The badge (child of iconSlot) and the glyph (child of the container) are compared in
        // the slot's coordinates.
        val slot = tile.iconSlot
        val box = tile.iconBox
        val tagRect = Rect(tag.left, tag.top, tag.right, tag.bottom)
        val glyph = Rect(
            box.left + tile.iconView.left,
            box.top + tile.iconView.top,
            box.left + tile.iconView.right,
            box.top + tile.iconView.bottom
        )
        assertFalse("the PRO badge covers the glyph ($tagRect vs $glyph)", Rect.intersects(tagRect, glyph))
        assertTrue(
            "the PRO badge keeps clear of the tile's top edge (${(slot.top + tagRect.top) / density}dp)",
            slot.top + tagRect.top >= dp(2f)
        )
        assertTrue(
            "the PRO badge stays inside the tile",
            slot.left + tagRect.right <= tile.width
        )
    }

    @Test
    fun `the tools tab renders no Coming soon tile and every row has an action`() {
        toolsTab { _, body ->
            val rows = ToolsHubScreen.rows(body)
            assertTrue("the hub renders rows", rows.isNotEmpty())
            val soon = rows.filter { it.tool?.status == ToolStatus.SOON }
            assertTrue("no Coming soon row in the hub: ${soon.map { it.tool?.id }}", soon.isEmpty())
            for (row in rows) {
                assertTrue("${row.tool?.id} must do something", row.hasOnClickListeners())
            }
            // A row is the full width of its job card, not a column in a grid.
            for (row in rows) {
                val card = row.parent as View
                assertEquals(
                    "row ${row.tool?.id} fills its card",
                    card.width - card.paddingLeft - card.paddingRight,
                    row.width
                )
            }
        }
    }

    @Test
    fun `job headings are sentence case 16sp and never ALL CAPS`() {
        toolsTab { _, body ->
            val headers = (0 until body.childCount).mapNotNull { index ->
                ((body.getChildAt(index) as? ViewGroup)?.getChildAt(0) as? ViewGroup)
                    ?.getChildAt(0) as? TextView
            }
            val expected = ToolRegistry.jobs().map { app.getString(it.first.title) }
            assertEquals("one heading per job", expected, headers.map { it.text.toString() })
            for ((index, header) in headers.withIndex()) {
                val text = header.text.toString()
                assertEquals("the heading is not case-shifted", expected[index], text)
                if (Locale.getDefault().language == "en") {
                    assertNotEquals("a heading is never ALL CAPS", text.uppercase(Locale.ROOT), text)
                }
                val sp = header.textSize / scaledDensity
                assertTrue("$text is a 16sp heading, was $sp", sp >= 15.4f && sp <= 16.6f)
            }
        }
    }

    @Test
    fun `tool rows keep whole labels at 100 and 130 percent`() {
        for (scale in listOf(1.0f, 1.3f)) {
            RuntimeEnvironment.setFontScale(scale)
            toolsTab { _, body ->
                val bad = mutableListOf<String>()
                for (row in ToolsHubScreen.rows(body)) {
                    val label = row.labelView
                    val layout = label.layout
                    if (layout == null) {
                        bad += "${label.text}: never laid out"
                        continue
                    }
                    val avail = label.width - label.totalPaddingLeft - label.totalPaddingRight
                    if (layout.lineCount < 1) bad += "${label.text}: no line"
                    for (line in 0 until layout.lineCount) {
                        // B4: a row has the full width, so no label is ever cut with "…".
                        if (layout.getEllipsisCount(line) != 0) {
                            bad += "${label.text}: ellipsized on line $line"
                        }
                        // getLineMax ignores the trailing wrap whitespace, which is never drawn.
                        if (layout.getLineMax(line) > avail + 0.5f) {
                            bad += "${label.text}: line $line overflows ($avail)"
                        }
                    }
                    if (layout.height + label.totalPaddingTop + label.totalPaddingBottom > label.height + 1) {
                        bad += "${label.text}: clipped (${layout.height}px in ${label.height}px)"
                    }
                    val sp = label.textSize / scaledDensity
                    if (sp < 14.4f || sp > 15.6f) bad += "${label.text}: label ${sp}sp"
                }
                assertTrue(
                    "row labels at ${scale}x on 360dp: ${bad.joinToString(" | ")}",
                    bad.isEmpty()
                )
            }
        }
    }

    @Test
    fun `quick tool labels stay whole inside their five columns`() {
        for (scale in listOf(1.0f, 1.3f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    layoutRoot(activity)
                    val row = activity.findViewById<ViewGroup>(R.id.swQuickRow)
                    val bad = mutableListOf<String>()
                    for (index in 0 until row.childCount) {
                        val tile = row.getChildAt(index) as? ToolTile ?: continue
                        val label = tile.labelView
                        val layout = label.layout ?: continue
                        val avail = label.width - label.totalPaddingLeft - label.totalPaddingRight
                        // v2 UI: 01_home_A - five columns: a label may use two lines (11sp
                        // floor), never a third, never a line wider than the column.
                        if (layout.lineCount > 2 || layout.lineCount < 1) bad += "${label.text}: ${layout.lineCount} lines"
                        if (layout.getEllipsisCount(0) != 0) bad += "${label.text}: ellipsized"
                        for (line in 0 until layout.lineCount) {
                            if (layout.getLineMax(line) > avail + 0.5f) {
                                bad += "${label.text}: line $line wider than its column"
                            }
                        }
                        if (layout.height + label.totalPaddingTop + label.totalPaddingBottom > label.height + 1) {
                            bad += "${label.text}: clipped"
                        }
                        // The glyph must still read bigger than the label even in the tight row.
                        assertTrue(
                            "${label.text}: glyph is at least 24dp",
                            tile.iconView.width / density >= 24f
                        )
                    }
                    assertTrue(
                        "quick labels at ${scale}x: ${bad.joinToString(" | ")}",
                        bad.isEmpty()
                    )
                }
            }
        }
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ToolsLookTest : ToolsLookBase()

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h740dp-xhdpi")
class ToolsLookHindiTest : ToolsLookBase()

/**
 * B4-TOOLS — the first Tools screen used to waste a 125dp tile row and show ~9 tiles. It now
 * starts with the job a person most often wants ("Make it smaller", with Compress named) and
 * uses full-width 56dp rows; the point of this check is that the first job is readable without
 * scrolling and no label is cut.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ToolsDensityTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        DocFixtures.saved(repo, app, pages = 1, name = "Density doc")
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
    }

    @Test
    fun `the first tools screen starts with Make it smaller and names Compress`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                activity.findViewById<View>(R.id.swNavTools).performClick()
                shadowOf(Looper.getMainLooper()).idle()

                val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
                val dm = activity.resources.displayMetrics
                val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
                val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                repeat(2) { root.measure(w, h); root.layout(0, 0, dm.widthPixels, dm.heightPixels) }

                // The tab content stops where the bottom dock starts (780 - 100dp dock).
                val content = activity.findViewById<View>(R.id.swTabContent)
                val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
                val firstHeader = ((body.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup)
                    .getChildAt(0) as TextView
                assertEquals(
                    app.getString(R.string.tools_job_smaller),
                    firstHeader.text.toString()
                )

                val visible = mutableListOf<String>()
                val bad = mutableListOf<String>()
                for (row in ToolsHubScreen.rows(body)) {
                    val rect = Rect(0, 0, row.width, row.height)
                    (root as ViewGroup).offsetDescendantRectToMyCoords(row, rect)
                    if (rect.top >= content.top && rect.bottom <= content.bottom) {
                        visible += row.tool?.id.orEmpty()
                    }
                    val label = row.labelView
                    val layout = label.layout ?: continue
                    for (line in 0 until layout.lineCount) {
                        if (layout.getEllipsisCount(line) != 0) bad += "${label.text}: ellipsized"
                    }
                    if (layout.height + label.totalPaddingTop + label.totalPaddingBottom >
                        label.height + 1
                    ) {
                        bad += "${label.text}: clipped"
                    }
                }
                assertTrue(
                    "Compress and Resize are visible in the first job without scrolling, saw " +
                        "${visible.joinToString(",")}; labels=${bad.joinToString(" | ")}",
                    visible.containsAll(listOf("compress", "resize"))
                )
                assertTrue("labels stay whole: ${bad.joinToString(" | ")}", bad.isEmpty())
            }
        }
    }
}
