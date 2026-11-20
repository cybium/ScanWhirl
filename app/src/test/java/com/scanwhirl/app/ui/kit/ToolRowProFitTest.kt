package com.scanwhirl.app.ui.kit

import android.app.Activity
import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATLAS-F2 (002_tools_2.0): under "Split" the gold PRO chip wrapped onto its own line, so it
 * read as a separate row instead of the same tool's marker.
 *
 * The marker belongs to the name: it shares the tool's line, sits after the name and never
 * moves to its own line — at 200 % on a 320dp phone, in English and Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class ToolRowProFitTest {

    private lateinit var app: Application
    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        // The test build defaults to Pro; force the Free UI so the marker is shown.
        ProGate.setTestOverride(app, false)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        ProGate.setTestOverride(app, null)
        controller?.destroy()
        controller = null    }

    /** Created after the font scale/qualifiers so the row is laid out in that configuration. */
    private fun newActivity(): Activity {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller!!.get()
        return activity
    }

    private fun measure(row: ToolRow) {
        val density = activity.resources.displayMetrics.density
        val width = (320 * density).toInt()
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((200 * density).toInt(), View.MeasureSpec.AT_MOST)
        )
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
    }

    private fun check(toolId: String) {
        val tool = ToolRegistry.byId(toolId)
        assertNotNull("the registry still has $toolId", tool)
        val swTool = tool!!
        val row = ToolRow(activity)
        row.bind(swTool)
        measure(row)
        val scale = RuntimeEnvironment.getFontScale()
        val label = "${swTool.id} @ ${scale}x"
        assertTrue("$label shows the PRO marker in a Free build", row.proTag.visibility == View.VISIBLE)
        assertEquals(activity.getString(swTool.title), row.labelView.text.toString())
        assertEquals(activity.getString(com.scanwhirl.app.R.string.badge_pro), row.proTag.text.toString())

        val overlap = minOf(row.labelView.bottom, row.proTag.bottom) -
            maxOf(row.labelView.top, row.proTag.top)
        assertTrue(
            "$label: the PRO chip must share its tool's line (label ${row.labelView.top}.." +
                "${row.labelView.bottom}, PRO ${row.proTag.top}..${row.proTag.bottom})",
            overlap > 0
        )
        assertTrue(
            "$label: the PRO chip follows the name on the same line",
            row.proTag.left >= row.labelView.right - 1
        )

        val layout = row.labelView.layout
        assertNotNull("$label: the name was laid out", layout)
        val lay = layout!!
        val avail = row.labelView.width - row.labelView.totalPaddingLeft - row.labelView.totalPaddingRight
        for (line in 0 until lay.lineCount) {
            assertEquals("$label: the name is ellipsized", 0, lay.getEllipsisCount(line))
            assertTrue(
                "$label: line $line is wider than the name's box",
                lay.getLineWidth(line) <= avail + 0.5f
            )
        }
        val needed = lay.height + row.labelView.totalPaddingTop + row.labelView.totalPaddingBottom
        assertTrue(
            "$label: the name needs ${needed}px but its view is ${row.labelView.height}px tall",
            needed <= row.labelView.height + 0.5f
        )
    }

    @Test
    fun `PRO stays on its tool's line at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        newActivity()
        check("split")
        check("to_excel")
    }

    @Test
    fun `PRO stays on its tool's line in Hindi at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        RuntimeEnvironment.setQualifiers("hi-rIN-w320dp-h640dp-xhdpi")
        newActivity()
        check("split")
        // The Hindi build still names the marker "Pro" (values-hi badge_pro).
        assertTrue(
            "the Hindi marker keeps its word",
            activity.getString(com.scanwhirl.app.R.string.pro_title_pro).isNotBlank()
        )
    }
}
