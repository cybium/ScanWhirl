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
import com.scanwhirl.app.ui.kit.ToolJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolRow
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T1 (owner-approved 2026-11-20): everyday tools are one tap from the top of the Tools tab.
 *
 * "Recent tools" is the last up-to-six distinct tools this person launched, most recent first.
 * These tests pin the behaviour the owner asked for:
 *  - a first-time Tools tab has no Recent row at all (no placeholder);
 *  - A, B, A reads A, B (moved, never repeated);
 *  - the list stops at six, newest first, unknown ids are ignored;
 *  - a launch through a job row records it (the one choke point), and a Free person's Pro tool
 *    is still gated and never recorded;
 *  - every catalogue tool is visible with zero extra taps (no "All N tools" door);
 *  - the row is hidden while searching;
 *  - EN and HI headings, and 200 % / 320dp geometry with nothing clipped.
 */
abstract class T1RecentToolsBase {

    protected lateinit var app: Application

    private val density: Float get() = app.resources.displayMetrics.density

    private val scaledDensity: Float get() = app.resources.displayMetrics.scaledDensity

    /** The heading this language must read ("Recent tools" / "हाल के काम"). */
    protected abstract val expectedHeading: String

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // Free build: Pro tools are gated and chipped
        Prefs.clearRecentTools(app)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
        Prefs.clearRecentTools(app)
        ToolsHubScreen.catalogueSource = null
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    protected fun dp(value: Float): Int = (value * density).toInt()

    protected fun shellRoot(activity: ShellActivity): ViewGroup =
        (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup

    protected fun layoutRoot(activity: ShellActivity) {
        val r = shellRoot(activity)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(2) {
            r.measure(w, h)
            r.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    /** Opens the Tools tab (a person taps Tools once) and lays it out. */
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

    protected fun recentCard(body: ViewGroup): View? =
        ToolsHubScreen.cardTagged(body, ToolsHubScreen.TAG_RECENT)

    protected fun headingOf(card: View): TextView =
        ((card as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as TextView

    /** The Recent card's rows, in screen order. */
    protected fun recentRows(body: ViewGroup): List<ToolRow> {
        val card = recentCard(body) ?: return emptyList()
        return ToolsHubScreen.rows(card)
    }

    protected fun allText(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    protected fun toolRow(body: ViewGroup, id: String): ToolRow? =
        ToolsHubScreen.rows(body).firstOrNull { it.tool?.id == id }

    protected fun storeRawRecents(value: String) {
        app.getSharedPreferences("scanwhirl_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString(Prefs.KEY_RECENT_TOOLS, value).apply()
    }

    // ---- the row itself -----------------------------------------------------

    @Test
    fun `a first-time Tools tab has no Recent tools row`() {
        assertTrue("a fresh install has no recents", Prefs.recentTools(app).isEmpty())
        toolsTab { _, body ->
            assertNull("no Recent card before the first launch", recentCard(body))
            assertFalse(
                "no Recent heading placeholder: ${allText(body)}",
                allText(body).contains(expectedHeading)
            )
            // The seven jobs really start at the top: the first card is "Make it smaller".
            assertEquals(
                "the first card is the first job when there are no recents",
                ToolJob.SMALLER,
                (body.getChildAt(0) as? ViewGroup)?.tag
            )
        }
    }

    @Test
    fun `A then B then A reads A then B`() {
        Prefs.recordRecentTool(app, "merge")
        Prefs.recordRecentTool(app, "compress")
        Prefs.recordRecentTool(app, "merge")
        assertEquals(
            "the most recent launch moves to the front and is never repeated",
            listOf("merge", "compress"),
            Prefs.recentTools(app)
        )
        toolsTab { _, body ->
            val card = recentCard(body)
            assertNotNull("a returning person sees the Recent row", card)
            assertEquals(
                "the Recent row is above the seven jobs",
                card,
                body.getChildAt(0)
            )
            assertEquals(expectedHeading, headingOf(card!!).text.toString())
            assertEquals(
                "one tap each, newest first",
                listOf("merge", "compress"),
                ToolsHubScreen.rows(card).map { it.tool?.id }
            )
        }
    }

    @Test
    fun `the list stops at six newest first`() {
        val launched = listOf(
            "compress", "merge", "split", "protect", "to_text", "zip", "read_text", "folders"
        )
        for (id in launched) Prefs.recordRecentTool(app, id)
        val expected = launched.takeLast(Prefs.MAX_RECENT_TOOLS).reversed()
        assertEquals("six is the cap", 6, Prefs.MAX_RECENT_TOOLS)
        assertEquals("newest first, six kept", expected, Prefs.recentTools(app))
        toolsTab { _, body ->
            assertEquals(
                "the Recent row shows the same six in the same order",
                expected,
                recentRows(body).map { it.tool?.id }
            )
        }
    }

    @Test
    fun `unknown ids and Coming-soon ids are ignored`() {
        storeRawRecents("no_such_tool,compress,organize_pages")
        assertEquals(
            "only a READY tool this build ships can be recent",
            listOf("compress"),
            Prefs.recentTools(app)
        )
        toolsTab { _, body ->
            assertEquals(
                "no dead Recent row is rendered",
                listOf("compress"),
                recentRows(body).map { it.tool?.id }
            )
        }
    }

    @Test
    fun `the row is hidden while searching and comes back on clear`() {
        Prefs.recordRecentTool(app, "merge")
        toolsTab { activity, body ->
            assertNotNull("the Recent row is there before searching", recentCard(body))

            val search = activity.findViewById<android.widget.EditText>(R.id.swToolsSearch)
            search.setText("compress")
            idle()
            layoutRoot(activity)
            assertNull("search results replace the Recent row", recentCard(body))

            activity.findViewById<View>(R.id.swToolsClear).performClick()
            idle()
            layoutRoot(activity)
            assertNotNull("clearing search brings the Recent row back", recentCard(body))
        }
    }

    // ---- the one launch path ------------------------------------------------

    @Test
    fun `launching a tool from a job row records it`() {
        toolsTab { activity, body ->
            val folders = toolRow(body, "folders")
            assertNotNull("Folders is a job row", folders)
            folders!!.performClick()
            idle()
            layoutRoot(activity)
            assertEquals(
                "the single launch choke point records a real launch",
                listOf("folders"),
                Prefs.recentTools(app)
            )
        }
    }

    @Test
    fun `a Free persons Pro tool is still gated and never recorded`() {
        toolsTab { activity, body ->
            val split = toolRow(body, "split")
            assertNotNull("Split is a Pro job row", split)
            split!!.performClick()
            idle()
            assertTrue(
                "a Free tap on a Pro tool still opens the Pro screen",
                activity.findViewById<View>(R.id.swProScreen)?.isVisible == true
            )
            assertTrue(
                "a gated tool never ran, so it is never recent: ${Prefs.recentTools(app)}",
                Prefs.recentTools(app).isEmpty()
            )
            assertNull("no Recent row is built from a gated tap", recentCard(body))
        }
    }

    @Test
    fun `every catalogue tool is visible with zero extra taps`() {
        toolsTab { _, body ->
            val ready = ToolRegistry.all.filter { it.status == ToolStatus.READY }
            val rows = ToolsHubScreen.rows(body)
            assertEquals(
                "every READY tool is a row on the first render",
                ready.map { it.id }.sorted(),
                rows.mapNotNull { it.tool?.id }.sorted()
            )
            val texts = allText(body)
            assertFalse(
                "no \"All N tools\" door wording",
                (1..50).any { n -> texts.any { it == app.getString(R.string.tools_job_all, n) } }
            )
            assertFalse(
                "no \"Show fewer\" door wording",
                texts.any { it == app.getString(R.string.tools_job_fewer) }
            )
            for (row in rows) {
                assertTrue("${row.tool?.id} has one tap", row.hasOnClickListeners())
                assertTrue(
                    "${row.tool?.id} is a 48dp+ target (${row.height / density}dp)",
                    row.height >= dp(48f)
                )
            }
        }
    }

    /** The ShellActivity behind a tab body (for layout helpers in nested lambdas). */
    internal fun shellActivityOf(view: View): ShellActivity {
        var context = view.context
        while (context is android.content.ContextWrapper) {
            if (context is ShellActivity) return context
            context = context.baseContext
        }
        throw AssertionError("the Tools page is not inside the shell")
    }

    // ---- EN / HI ------------------------------------------------------------

    @Test
    fun `the heading is the judged words in this language`() {
        Prefs.recordRecentTool(app, "merge")
        toolsTab { _, body ->
            val heading = headingOf(recentCard(body)!!)
            assertEquals(expectedHeading, heading.text.toString())
            val sp = heading.textSize / scaledDensity
            assertTrue("the Recent heading is 16sp, was $sp", sp >= 15.4f && sp <= 16.6f)
        }
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class T1RecentToolsTest : T1RecentToolsBase() {
    override val expectedHeading: String = "Recent tools"
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h740dp-xhdpi")
class T1RecentToolsHindiTest : T1RecentToolsBase() {
    // The Tools tab is "काम", so recents read "हाल के काम" (HindiGlossaryTermsTest bans टूल).
    override val expectedHeading: String = "हाल के काम"
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class T1RecentToolsNarrowTest : T1RecentToolsBase() {
    override val expectedHeading: String = "Recent tools"

    /**
     * The narrowest supported phone at 200 %: the Recent row wraps and grows instead of cutting
     * a word, stays a real 48dp+ target and keeps its place above the jobs.
     */
    @Test
    fun `200 percent on 320dp keeps every recent label whole`() {
        RuntimeEnvironment.setFontScale(2.0f)
        val launched = listOf(
            "searchable_pdf", "add_text", "remove_metadata", "pdf_to_images", "batch_tools", "protect"
        )
        for (id in launched) Prefs.recordRecentTool(app, id)
        toolsTab { _, body ->
            val card = recentCard(body)
            assertNotNull("the Recent card renders at 200 % on 320dp", card)
            assertEquals("it stays above the jobs", card, body.getChildAt(0))

            val heading = headingOf(card!!)
            val headingLayout = heading.layout
            assertNotNull("the heading is laid out", headingLayout)
            for (line in 0 until headingLayout!!.lineCount) {
                assertEquals(
                    "\"${heading.text}\" is ellipsized at 200 % on 320dp",
                    0,
                    headingLayout.getEllipsisCount(line)
                )
                assertTrue(
                    "\"${heading.text}\" spills past its card",
                    headingLayout.getLineMax(line) <=
                        heading.width - heading.totalPaddingLeft - heading.totalPaddingRight + 0.5f
                )
            }
            assertTrue(
                "the heading is clipped (${headingLayout.height}px in ${heading.height}px)",
                headingLayout.height + heading.totalPaddingTop + heading.totalPaddingBottom <=
                    heading.height + 1
            )

            val rows = ToolsHubScreen.rows(card)
            assertEquals("all six recent tools stay visible", launched.reversed(), rows.map { it.tool?.id })
            for (row in rows) {
                val content = (row.parent as View)
                assertEquals(
                    "a recent row fills its card",
                    content.width - content.paddingLeft - content.paddingRight,
                    row.width
                )
                assertTrue(
                    "${row.tool?.id} keeps a 48dp target (${row.height / density}dp)",
                    row.height >= dp(48f)
                )
                assertEquals(
                    "${row.tool?.id} keeps a TalkBack label",
                    app.getString(row.tool!!.title),
                    row.contentDescription.toString().substringBefore(", ")
                )
                val label = row.labelView
                val layout = label.layout
                assertNotNull("${label.text} is laid out", layout)
                for (line in 0 until layout!!.lineCount) {
                    assertEquals(
                        "\"${label.text}\" is ellipsized at 200 % on 320dp",
                        0,
                        layout.getEllipsisCount(line)
                    )
                    assertTrue(
                        "\"${label.text}\" spills past its row",
                        layout.getLineMax(line) <=
                            label.width - label.totalPaddingLeft - label.totalPaddingRight + 0.5f
                    )
                }
                assertTrue(
                    "\"${label.text}\" is clipped",
                    layout.height + label.totalPaddingTop + label.totalPaddingBottom <=
                        label.height + 1
                )
            }

            // The Recent card is above the first job on screen, not floating over it.
            val rect = Rect(0, 0, card.width, card.height)
            shellRoot(shellActivityOf(body)).offsetDescendantRectToMyCoords(card, rect)
            assertTrue("the Recent card has a real height", rect.height() > dp(48f))
        }
    }
}
