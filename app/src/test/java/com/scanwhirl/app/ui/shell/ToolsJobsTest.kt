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
import com.scanwhirl.app.ui.kit.ToolJob
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolRow
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * B4-TOOLS: the Tools tab answers "what do I want done?". Seven jobs from UI_PLAN §0b in
 * order, "Make it smaller" first with Compress named, every shipping tool under exactly one
 * job, Pro tools carrying the word Pro on a gold chip, no Soon tile, and search results as
 * readable full-width rows (Read text and Searchable PDF stay two different tools in both
 * languages).
 *
 * The counting and reachability tests fail on the old grouped grid: it had no jobs at all.
 */
abstract class ToolsJobsBase {

    private lateinit var app: Application

    private val density: Float
        get() = app.resources.displayMetrics.density

    private val scaledDensity: Float
        get() = app.resources.displayMetrics.scaledDensity

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // Free build: every Pro tool draws its chip
        DocFixtures.saved(repo, app, pages = 1, name = "Jobs doc")
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

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

    /** Opens the Tools tab and lays out. T1: every job already lists every tool. */
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

    private fun dp(value: Float): Int = (value * density).toInt()

    /** The width a row gets inside its job card (the card keeps 4dp inner gutters). */
    private fun cardContentWidth(row: View): Int {
        val card = row.parent as View
        return card.width - card.paddingLeft - card.paddingRight
    }

    // ---- registry: membership is derived, never counted by hand -------------

    @Test
    fun `the seven jobs are the approved ones in order`() {
        val jobs = ToolRegistry.jobs()
        assertEquals("seven jobs", 7, jobs.size)
        assertEquals("job order", ToolJob.entries.toList(), jobs.map { it.first })
        for ((job, _) in jobs) {
            assertTrue("${job.name} has no name", app.getString(job.title).isNotBlank())
        }
        // English wording is pinned here; Hindi lives in values-hi and is checked by its own run.
        if (Locale.getDefault().language == "en") {
            assertEquals(
                "job names, in reading order",
                listOf(
                    "Make it smaller",
                    "Combine and organise",
                    "Sign and mark up",
                    "Keep it private",
                    "Get the text",
                    "Change the format",
                    "Start something new"
                ),
                jobs.map { app.getString(it.first.title) }
            )
        }
        assertEquals("Make it smaller is first", ToolJob.SMALLER, jobs.first().first)
        assertEquals(
            "Make it smaller names Compress and Resize",
            listOf("compress", "resize"),
            jobs.first().second.map { it.id }
        )
        assertTrue("Sign and mark up is the Pro job", jobs.first { it.first == ToolJob.MARKUP }.first.pro)
    }

    @Test
    fun `every shipping tool sits under exactly one job`() {
        val jobs = ToolRegistry.jobs()
        val listed = jobs.flatMap { it.second }
        val shipping = ToolRegistry.all.filter { it.status != ToolStatus.SOON }
        assertEquals(
            "every shipping tool is reachable through one job",
            shipping.map { it.id }.sorted(),
            listed.map { it.id }.sorted()
        )
        assertEquals("no tool is listed twice", listed.size, listed.map { it.id }.toSet().size)
        for (tool in listed) {
            val under = jobs.count { (_, tools) -> tools.any { it.id == tool.id } }
            assertEquals("${tool.id} is under $under jobs", 1, under)
        }
        assertTrue("no Soon tile in the jobs", listed.none { it.status == ToolStatus.SOON })
        for ((job, tools) in jobs) {
            assertTrue("$job is empty and must not render", tools.isNotEmpty())
        }
    }

    @Test
    fun `the explicit job table is not stale or duplicated`() {
        val table = ToolRegistry.jobTable()
        assertEquals("one entry per tool id", table.size, table.map { it.first }.toSet().size)
        for ((id, _) in table) {
            assertNotNull("$id is mapped to a job but is not in the catalogue", ToolRegistry.byId(id))
        }
    }

    @Test
    fun `every shipping tool is reachable through search by its own name`() {
        for (tool in ToolRegistry.all.filter { it.status != ToolStatus.SOON }) {
            val title = app.getString(tool.title)
            val hits = ToolRegistry.search(app, title).map { it.id }
            assertTrue("\"$title\" does not find ${tool.id}: $hits", hits.contains(tool.id))
        }
    }

    // ---- hub: jobs, rows, Pro chips, no Soon -------------------------------

    @Test
    fun `the tools tab shows the seven jobs in order and no Soon word`() {
        toolsTab { _, body ->
            val headers = jobHeaders(body)
            assertEquals(
                "one card per job, in order",
                ToolRegistry.jobs().map { app.getString(it.first.title) },
                headers
            )
            for (text in allText(body)) {
                assertNotEquals("a Soon tag survives in the hub", app.getString(R.string.tool_soon_tag), text)
                assertNotEquals("a Soon note survives in the hub", app.getString(R.string.tool_coming_soon_note), text)
            }
            val rows = ToolsHubScreen.rows(body)
            assertTrue("the hub draws tool rows", rows.isNotEmpty())
            assertTrue(
                "no Coming soon row: ${rows.map { it.tool?.id }}",
                rows.none { it.tool?.status == ToolStatus.SOON }
            )
            for (row in rows) {
                assertTrue("${row.tool?.id} must do something", row.hasOnClickListeners())
            }
        }
    }

    @Test
    fun `Compress is named inside the first job without scrolling`() {
        toolsTab { activity, body ->
            val card = ToolsHubScreen.cardTagged(body, ToolJob.SMALLER) as ViewGroup
            val header = (card.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
            assertEquals(app.getString(R.string.tools_job_smaller), header.text.toString())

            val compress = ToolsHubScreen.rows(body).firstOrNull { it.tool?.id == "compress" }
            assertNotNull("Compress is not a row in the first job", compress)
            assertEquals(
                "the row says the real tool name",
                app.getString(R.string.tool_compress),
                compress!!.labelView.text.toString()
            )
            val content = activity.findViewById<View>(R.id.swTabContent)
            val rect = Rect(0, 0, compress.width, compress.height)
            val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup
            root.offsetDescendantRectToMyCoords(compress, rect)
            assertTrue(
                "Compress must be on screen without scrolling (row ${rect.top}..${rect.bottom}, " +
                    "content ${content.top}..${content.bottom})",
                rect.top >= content.top && rect.bottom <= content.bottom
            )
            // Rows are full width inside their job card, side by side with nothing else.
            assertEquals(
                "a row fills its job card",
                cardContentWidth(compress),
                compress.width
            )
            assertTrue(
                "the row clears a 48dp touch target (${compress.height / density}dp)",
                compress.height >= dp(48f)
            )
        }
    }

    @Test
    fun `every Pro tool shows the gold Pro word`() {
        toolsTab { _, body ->
            val rows = ToolsHubScreen.rows(body)
            for (tool in ToolRegistry.all.filter { it.pro && it.status != ToolStatus.SOON }) {
                val row = rows.firstOrNull { it.tool?.id == tool.id }
                assertNotNull("Pro tool ${tool.id} has no row", row)
                assertTrue("${tool.id} shows no Pro marker", row!!.proTag.isVisible)
                assertEquals(
                    "${tool.id} marker wording",
                    app.getString(R.string.badge_pro),
                    row.proTag.text.toString()
                )
                assertEquals(
                    "${tool.id} marker is gold",
                    com.scanwhirl.app.ui.kit.SwStyle.color(app, R.color.gold_pro_primary),
                    row.proTag.currentTextColor
                )
                assertTrue(
                    "${tool.id} talkback says Pro",
                    row.contentDescription.contains(app.getString(R.string.badge_pro))
                )
            }
            for (tool in ToolRegistry.all.filter { !it.pro && it.status != ToolStatus.SOON }) {
                val row = rows.firstOrNull { it.tool?.id == tool.id }
                assertNotNull("${tool.id} has no row", row)
                assertFalse("Free tool ${tool.id} shows a Pro marker", row!!.proTag.isVisible)
            }
        }
    }

    /**
     * T1 owner-approved 2026-11-20: no hidden tools. Every job lists all of its tools on the
     * first render — no "All N tools" door, no expansion state, nothing behind a tap.
     */
    @Test
    fun `every job lists all of its tools with no extra tap`() {
        toolsTab { _, body ->
            val organises = ToolRegistry.jobs().first { it.first == ToolJob.ORGANISE }.second
            assertTrue("Combine and organise stays a long job", organises.size > 3)
            val ids = ToolsHubScreen.rows(body).mapNotNull { it.tool?.id }
            for ((job, tools) in ToolRegistry.jobs()) {
                for (tool in tools) {
                    assertTrue("${tool.id} is not visible in job $job", tool.id in ids)
                }
            }
            val texts = allText(body)
            assertFalse(
                "\"All N tools\" door wording survives in the hub",
                texts.any { it == app.getString(R.string.tools_job_all, organises.size) }
            )
            assertFalse(
                "\"Show fewer\" door wording survives in the hub",
                texts.any { it == app.getString(R.string.tools_job_fewer) }
            )
        }
    }

    // ---- search ------------------------------------------------------------

    @Test
    fun `search results are full-width rows with the Pro marker`() {
        toolsTab { activity, body ->
            val search = activity.findViewById<android.widget.EditText>(R.id.swToolsSearch)
            search.setText("word")
            idle()
            layoutRoot(activity)
            val rows = ToolsHubScreen.rows(body)
            assertTrue("search renders rows", rows.isNotEmpty())
            val word = rows.firstOrNull { it.tool?.id == "to_word" }
            assertNotNull("To Word is a search hit", word)
            assertTrue("To Word keeps its Pro marker in search", word!!.proTag.isVisible)
            assertEquals("To Word is Pro", app.getString(R.string.badge_pro), word.proTag.text.toString())
            for (row in rows) {
                assertEquals(
                    "search rows fill their card",
                    cardContentWidth(row),
                    row.width
                )
                assertTrue("search rows are 48dp tall", row.height >= dp(48f))
            }
        }
    }

    @Test
    fun `Read text and Searchable PDF stay two different results`() {
        toolsTab { activity, body ->
            val search = activity.findViewById<android.widget.EditText>(R.id.swToolsSearch)
            search.setText(searchQueryForText())
            idle()
            layoutRoot(activity)
            val rows = ToolsHubScreen.rows(body)
            val read = rows.firstOrNull { it.tool?.id == "read_text" }
            val searchable = rows.firstOrNull { it.tool?.id == "searchable_pdf" }
            assertNotNull("Read text is a hit", read)
            assertNotNull("Searchable PDF is a hit", searchable)
            assertNotEquals(
                "the two tools must not read the same",
                read!!.labelView.text.toString(),
                searchable!!.labelView.text.toString()
            )
            assertEquals(app.getString(R.string.tool_read_text), read.labelView.text.toString())
            assertEquals(app.getString(R.string.tool_searchable_pdf), searchable.labelView.text.toString())
        }
    }

    @Test
    fun `clearing search brings the seven jobs back with no stale result`() {
        toolsTab { activity, body ->
            val search = activity.findViewById<android.widget.EditText>(R.id.swToolsSearch)
            search.setText("sign")
            idle()
            layoutRoot(activity)
            val hits = ToolsHubScreen.rows(body)
            assertEquals(
                "results replace the jobs",
                listOf(app.getString(R.string.tools_search_results)),
                jobHeaders(body)
            )
            assertTrue(hits.isNotEmpty())

            activity.findViewById<View>(R.id.swToolsClear).performClick()
            idle()
            layoutRoot(activity)
            assertTrue("the field is empty", search.text.isNullOrEmpty())
            assertEquals(
                "the seven jobs are back",
                ToolRegistry.jobs().map { app.getString(it.first.title) },
                jobHeaders(body)
            )
            assertEquals(
                "no search hit survives: every shipping tool of every job shows",
                ToolRegistry.all.count { it.status != ToolStatus.SOON },
                ToolsHubScreen.rows(body).size
            )
        }
    }

    @Test
    fun `200 percent keeps every search row whole with the keyboard up`() {
        RuntimeEnvironment.setFontScale(2.0f)
        toolsTab { activity, body ->
            val search = activity.findViewById<android.widget.EditText>(R.id.swToolsSearch)
            search.requestFocus()
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            search.setText(longestSearchQuery())
            idle()
            layoutRoot(activity)

            val rows = ToolsHubScreen.rows(body)
            assertTrue("search renders rows at 200%", rows.isNotEmpty())
            val problems = mutableListOf<String>()
            for (row in rows) {
                val label = row.labelView
                val layout = label.layout
                if (layout == null) {
                    problems += "${label.text}: never laid out"
                    continue
                }
                if (layout.lineCount < 1) problems += "${label.text}: no line"
                if (layout.lineCount > 8) problems += "${label.text}: ${layout.lineCount} lines"
                for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) != 0) {
                        problems += "${label.text}: ellipsized on line $line"
                    }
                    if (layout.getLineMax(line) >
                        label.width - label.totalPaddingLeft - label.totalPaddingRight + 0.5f
                    ) {
                        problems += "${label.text}: line $line wider than the row"
                    }
                }
                if (layout.height + label.totalPaddingTop + label.totalPaddingBottom >
                    label.height + 1
                ) {
                    problems += "${label.text}: clipped (${layout.height}px in ${label.height}px)"
                }
                if (row.width > cardContentWidth(row)) problems += "${label.text}: row wider than its card"
                if (row.height < dp(48f)) problems += "${label.text}: row ${row.height / density}dp"
            }
            // The longest hit must prove it kept the whole word and is not cut anywhere.
            val longest = rows.maxByOrNull { it.labelView.text.length }
            assertNotNull("a longest hit exists", longest)
            val longestLayout = longest!!.labelView.layout
            assertNotNull("the longest hit (${longest.labelView.text}) is laid out", longestLayout)
            for (line in 0 until longestLayout!!.lineCount) {
                assertEquals(
                    "the longest hit (${longest.labelView.text}) is ellipsized on line $line",
                    0,
                    longestLayout.getEllipsisCount(line)
                )
            }
            assertTrue("200% search row problems: ${problems.joinToString(" | ")}", problems.isEmpty())
        }
    }

    @Test
    fun `200 percent uses list rows and the last job clears the dock`() {
        RuntimeEnvironment.setFontScale(2.0f)
        toolsTab { activity, body ->
            val rows = ToolsHubScreen.rows(body)
            assertEquals(
                "at 200% every shipping tool keeps its own full-width row",
                ToolRegistry.all.count { it.status != ToolStatus.SOON },
                rows.size
            )
            for (row in rows) {
                assertEquals("a row fills its job card", cardContentWidth(row), row.width)
                val label = row.labelView
                val layout = label.layout ?: continue
                for (line in 0 until layout.lineCount) {
                    assertTrue(
                        "${label.text} is ellipsized at 200%",
                        layout.getEllipsisCount(line) == 0
                    )
                }
            }

            // Scroll to the very bottom: the last row must sit above the dock, not under it.
            var parent = body.parent
            while (parent != null && parent !is android.widget.ScrollView) parent = parent.parent
            val scroll = parent as android.widget.ScrollView
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            idle()
            layoutRoot(activity)

            val last = rows.last()
            val rect = Rect(0, 0, last.width, last.height)
            val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup
            root.offsetDescendantRectToMyCoords(last, rect)
            val dock = activity.findViewById<View>(R.id.swNavTools).parent as View
            val dockRect = Rect(0, 0, dock.width, dock.height)
            root.offsetDescendantRectToMyCoords(dock, dockRect)
            assertTrue(
                "the last row (${last.tool?.id}) ends at ${rect.bottom}px but the dock starts at " +
                    "${dockRect.top}px",
                rect.bottom <= dockRect.top
            )
            assertTrue(
                "the last row is the last job's last tool (${last.tool?.id})",
                last.tool?.id == ToolRegistry.jobs().last().second.last().id
            )
        }
    }

    @Test
    fun `job headings are sentence case 16sp and never ALL CAPS`() {
        toolsTab { _, body ->
            val headers = bodyHeaders(body)
            assertEquals(ToolRegistry.jobs().size, headers.size)
            for (header in headers) {
                val text = header.text.toString()
                if (Locale.getDefault().language == "en") {
                    assertNotEquals("a heading is never ALL CAPS", text.uppercase(Locale.ROOT), text)
                }
                val sp = header.textSize / scaledDensity
                assertTrue("$text is a 16sp heading, was $sp", sp >= 15.4f && sp <= 16.6f)
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    /** The search term that must show both Read text and Searchable PDF. */
    protected abstract fun searchQueryForText(): String

    /** A search whose hits include the longest label in this language. */
    protected abstract fun longestSearchQuery(): String

    private fun jobHeaders(body: ViewGroup): List<String> = bodyHeaders(body).map { it.text.toString() }

    /**
     * The heading of each job card, in order. The optional "Recent tools" card is not a job, so
     * it is skipped here (T1); its own heading is asserted by the recents tests.
     */
    private fun bodyHeaders(body: ViewGroup): List<TextView> = (0 until body.childCount).mapNotNull { index ->
        val card = body.getChildAt(index) as? ViewGroup ?: return@mapNotNull null
        if (card.tag == ToolsHubScreen.TAG_RECENT) return@mapNotNull null
        val head = card.getChildAt(0) as? ViewGroup ?: return@mapNotNull null
        head.getChildAt(0) as? TextView
    }

    private fun allText(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ToolsJobsTest : ToolsJobsBase() {
    // "ocr" is a keyword of both Read text and Searchable PDF, so the two rows appear together.
    override fun searchQueryForText(): String = "ocr"
    override fun longestSearchQuery(): String = "pdf"
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h740dp-xhdpi")
class ToolsJobsHindiTest : ToolsJobsBase() {
    override fun searchQueryForText(): String = "ocr"
    override fun longestSearchQuery(): String = "pdf"
}
