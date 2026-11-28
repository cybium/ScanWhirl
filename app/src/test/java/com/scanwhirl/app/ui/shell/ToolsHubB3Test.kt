package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.StudentJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B3/B4 harm tests for the Tools hub.
 *
 * Every test name says the user harm it prevents: a student must reach the three named jobs
 * without hunting, search must still understand the old/private-language word for Hide info,
 * searching must leave only results, and no working tool may disappear behind the new layout.
 */
abstract class ToolsHubB3Base {

    protected lateinit var app: Application
    protected val density: Float get() = app.resources.displayMetrics.density

    protected abstract val jobLabels: List<String>
    protected abstract val hideInfoLabel: String
    protected abstract val synonymQueries: List<Pair<String, String>>

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        Prefs.clearRecentTools(app)
        // The two Pro jobs open the shared document picker; give them a fictional document.
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "B3 doc")
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
        Prefs.clearRecentTools(app)
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    protected fun dp(value: Float): Int = (value * density).toInt()

    protected fun layoutRoot(activity: ShellActivity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    protected fun toolsTab(block: (ShellActivity, ViewGroup) -> Unit) {
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

    protected fun search(activity: ShellActivity, query: String) {
        activity.findViewById<android.widget.EditText>(R.id.swToolsSearch).setText(query)
        idle()
        layoutRoot(activity)
    }

    protected fun headers(body: ViewGroup): List<String> = (0 until body.childCount).mapNotNull { i ->
        ((body.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)
            ?.getChildAt(0) as? TextView
    }.map { it.text.toString() }

    @Test
    fun `the three student jobs are first and open their tools`() {
        // A Pro job must still open its real tool, so run this leg with Pro unlocked.
        ProGate.setTestOverride(app, true)
        toolsTab { activity, body ->
            val card = ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_STUDENT_JOBS) as? ViewGroup
            assertNotNull("the student jobs lead the tab", card)
            assertEquals("no card sits above the student jobs", body.getChildAt(0), card)
            val rows = ToolsHubScreen.studentJobRows(body)
            assertEquals(
                "the three approved jobs, in order",
                listOf("form_ready", "sign", "redact"),
                rows.map { (it.tag as StudentJob).toolId }
            )
            assertEquals(
                "each job is one short named line in this language",
                jobLabels,
                rows.map { (it.findViewById(R.id.swToolLabel) as TextView).text.toString() }
            )
            val expectedOrder = listOf("form_ready", "sign", "redact")
            for ((index, row) in rows.withIndex()) {
                assertTrue("a job row is a 48dp+ target (${row.height / density}dp)", row.height >= dp(48f))
                assertTrue("a job row is one focus stop", row.isClickable && row.isFocusable)
                assertTrue("a job row has one tap", row.hasOnClickListeners())
                row.performClick()
                idle()
                val started = shadowOf(activity).nextStartedActivity
                val picker = ShadowDialog.getLatestDialog()
                val toast = ShadowToast.getTextOfLatestToast()
                assertTrue(
                    "${expectedOrder[index]} must not be a dead tap",
                    started != null || picker?.isShowing == true || toast != null
                )
                if (expectedOrder[index] == "form_ready") {
                    assertNotNull("Fit a photo opens its own setup screen", started)
                    assertTrue(
                        "the photo job opens FormReady, not a generic tool screen",
                        started!!.component?.className.orEmpty().contains("FormReady")
                    )
                }
                assertEquals(
                    "each job dispatched to its own real tool",
                    expectedOrder[index],
                    Prefs.recentTools(app).first()
                )
            }
            assertEquals(
                "each job dispatched to its own real tool",
                listOf("redact", "sign", "form_ready"),
                Prefs.recentTools(app)
            )
        }
    }

    @Test
    fun `search 'redact' finds Hide info`() {
        toolsTab { activity, body ->
            for (query in listOf("redact", "blackout", "hide")) {
                search(activity, query)
                val hits = ToolsHubScreen.rows(body)
                val hide = hits.firstOrNull { it.tool?.id == "redact" }
                assertNotNull("\"$query\" must find the Hide info tool", hide)
                assertEquals(
                    "the tile reads Hide info for \"$query\"",
                    hideInfoLabel,
                    hide!!.labelView.text.toString()
                )
            }
        }
    }

    @Test
    fun `plain words find the common tools in this language`() {
        toolsTab { activity, body ->
            for ((query, toolId) in synonymQueries) {
                search(activity, query)
                val ids = ToolsHubScreen.rows(body).mapNotNull { it.tool?.id }
                assertTrue("\"$query\" must find $toolId, got $ids", toolId in ids)
            }
        }
    }

    @Test
    fun `searching hides the job cards`() {
        Prefs.recordRecentTool(app, "compress")
        toolsTab { activity, body ->
            assertNotNull(
                "the jobs are there before searching",
                ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_STUDENT_JOBS)
            )
            assertNotNull("Recent is there before searching", ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_RECENT))

            search(activity, "compress")
            assertNull("a query must not leave job cards on screen", ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_STUDENT_JOBS))
            assertNull("a query must not leave Recent on screen", ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_RECENT))
            assertTrue("student job rows are gone", ToolsHubScreen.studentJobRows(body).isEmpty())
            assertEquals("only results remain", listOf(app.getString(R.string.tools_search_results)), headers(body))

            activity.findViewById<View>(R.id.swToolsClear).performClick()
            idle()
            layoutRoot(activity)
            assertNotNull("clearing search brings the student jobs back", ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_STUDENT_JOBS))
            assertNotNull("clearing search brings Recent back", ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_RECENT))
        }
    }

    @Test
    fun `no tool became unreachable`() {
        val shipping = ToolRegistry.all.filter { it.status == ToolStatus.READY }
        assertEquals("38 catalogue entries: 37 working tools and one Coming soon", 38, ToolRegistry.all.size)
        assertEquals("the B3 spec's usual 37 tools all ship", 37, ToolRegistry.shippingCount())
        toolsTab { activity, body ->
            assertEquals(
                "every working tool still has a tile in the grouped catalogue",
                shipping.map { it.id }.sorted(),
                ToolsHubScreen.tiles(body).mapNotNull { it.tool?.id }.sorted()
            )
            for (tool in shipping) {
                assertTrue(
                    "${tool.id} has one tap",
                    ToolsHubScreen.tiles(body).first { it.tool?.id == tool.id }.hasOnClickListeners()
                )
                val byName = ToolRegistry.search(app, app.getString(tool.title)).map { it.id }
                assertTrue("\"${app.getString(tool.title)}\" must still find ${tool.id}", tool.id in byName)
                val title = app.getString(tool.title)
                val byPlainWord = ToolRegistry.search(app, title).map { it.id }
                assertTrue("a renamed tool must keep its own name: ${tool.id}", tool.id in byPlainWord)
            }
            for (job in ToolRegistry.studentJobs()) {
                assertEquals("student job ${job.name} points at a shipping tool", ToolStatus.READY, ToolRegistry.toolFor(job).status)
            }
            // The one SOON entry is not a working tool: no tile, no search row, no dead tap.
            val soon = ToolRegistry.all.first { it.status == ToolStatus.SOON }
            assertFalse("a Coming-soon tile is never shown", ToolsHubScreen.tiles(body).any { it.tool?.id == soon.id })
            search(activity, app.getString(soon.title))
            assertTrue(
                "a Coming-soon entry is never a search hit",
                ToolsHubScreen.rows(body).none { it.tool?.id == soon.id }
            )
        }
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ToolsHubB3Test : ToolsHubB3Base() {
    override val jobLabels = listOf("Fit a photo to a form", "Sign or fill a form", "Hide private info")
    override val hideInfoLabel = "Hide info"
    override val synonymQueries = listOf(
        "make smaller" to "compress", "reduce size" to "compress",
        "combine" to "merge", "reorder" to "organize_pages",
        "rearrange" to "organize_pages", "signature" to "sign"
    )
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h740dp-xhdpi")
class ToolsHubB3HindiTest : ToolsHubB3Base() {
    override val jobLabels = listOf("फ़ोटो को फ़ॉर्म में फिट करें", "फ़ॉर्म पर हस्ताक्षर या जानकारी भरें", "निजी जानकारी छिपाएँ")
    override val hideInfoLabel = "निजी जानकारी छिपाएँ"
    override val synonymQueries = listOf(
        "छोटा करें" to "compress", "आकार घटाएँ" to "compress",
        "जोड़ें" to "merge", "क्रम बदलें" to "organize_pages",
        "फिर से सजाएँ" to "organize_pages", "हस्ताक्षर" to "sign"
    )
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class ToolsHubB3NarrowTest : ToolsHubB3Base() {
    override val jobLabels = listOf("Fit a photo to a form", "Sign or fill a form", "Hide private info")
    override val hideInfoLabel = "Hide info"
    override val synonymQueries = listOf(
        "make smaller" to "compress", "reduce size" to "compress",
        "combine" to "merge", "reorder" to "organize_pages",
        "rearrange" to "organize_pages", "signature" to "sign"
    )

    /** 320dp + 2.0: no row or tile may clip its label, overlap a neighbour or shrink below 48dp. */
    @Test
    fun `320dp font 2 keeps every job and tool label whole with 48dp targets`() {
        RuntimeEnvironment.setFontScale(2.0f)
        toolsTab { _, body ->
            val problems = mutableListOf<String>()
            for (row in ToolsHubScreen.studentJobRows(body)) {
                val label = row.findViewById(R.id.swToolLabel) as TextView
                val layout = label.layout
                if (layout == null) {
                    problems += "${label.text}: never laid out"
                    continue
                }
                for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) != 0) problems += "${label.text}: ellipsized"
                    if (layout.getLineMax(line) > label.width - label.totalPaddingLeft - label.totalPaddingRight + 0.5f) {
                        problems += "${label.text}: wider than its row"
                    }
                }
                if (layout.height + label.totalPaddingTop + label.totalPaddingBottom > label.height + 1) {
                    problems += "${label.text}: clipped"
                }
                if (row.height < dp(48f) || row.width < dp(48f)) problems += "${label.text}: small target"
            }
            for (tile in ToolsHubScreen.tiles(body)) {
                val label = tile.labelView
                val layout = label.layout ?: continue
                for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) != 0) problems += "${label.text}: tile ellipsized"
                    if (layout.getLineMax(line) > label.width - label.totalPaddingLeft - label.totalPaddingRight + 0.5f) {
                        problems += "${label.text}: tile line wider than its column"
                    }
                }
                if (layout.height + label.totalPaddingTop + label.totalPaddingBottom > label.height + 1) {
                    problems += "${label.text}: tile label clipped"
                }
                if (tile.height < dp(48f) || tile.width < dp(48f)) problems += "${label.text}: tile small target"
            }
            // Sibling rows in one card must not paint over each other.
            val rows = ToolsHubScreen.studentJobRows(body)
            for (i in 1 until rows.size) {
                assertTrue("student job rows must not overlap", rows[i - 1].bottom <= rows[i].top)
            }
            assertTrue("320dp / 2.0 layout problems: ${problems.joinToString(" | ")}", problems.isEmpty())
        }
    }
}
