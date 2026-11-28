package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import kotlin.math.abs

/**
 * UI-W7-CREATETOOLS — WINNER_SPEC lane 7 ("Create and Tools route polish"):
 *
 *  - "One tap from named PDF import to correct picker": the Create sheet's Import PDF row must
 *    open the system PDF picker itself, with no intermediate Photos/PDF chooser sheet.
 *  - "photo choice never starts wrong source": Import photos opens the photo picker and can
 *    never start the document scanner (the old Auto-crop route opened the Play-services scanner).
 *  - "Cancel no import": a cancelled picker creates nothing and hands focus back to Create.
 *  - Registry exact READY membership, job-labelled expansion with restored focus, 200 % search
 *    that keeps its results on screen, and clear empty/no-results/error states.
 *
 * Red on base 7ad0f104: the PDF row opened [com.scanwhirl.app.ui.ImportSheet] (a second tap) and
 * the photos row opened the Add-photos sheet whose Auto-crop route started the scanner; the hub's
 * "All N tools" door rebuilt the body and dropped keyboard/TalkBack focus entirely.
 */
abstract class W7CreateBase {



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    protected lateinit var app: Application
    protected lateinit var repo: ScanRepository

    protected val density: Float get() = app.resources.displayMetrics.density

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        RuntimeEnvironment.setFontScale(1.0f)
        DocFixtures.saved(repo, app, pages = 1, name = "W7 doc")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.setLocaleTag(app, "")
        ProGate.setTestOverride(app, null)
        ToolsHubScreen.catalogueSource = null
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    protected fun dp(value: Float): Int = (value * density).toInt()

    protected fun root(activity: ShellActivity): ViewGroup =
        (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup

    protected fun layoutRoot(activity: ShellActivity) {
        val r = root(activity)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(2) {
            r.measure(w, h)
            r.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    /** The ScrollView the shell puts around a tab page. */
    protected fun shellScroll(inside: View): android.widget.ScrollView {
        var parent = inside.parent
        while (parent != null && parent !is android.widget.ScrollView) parent = parent.parent
        assertNotNull("the Tools page scrolls", parent)
        return parent as android.widget.ScrollView
    }

    /** [view]'s rect in [ancestor] coordinates (the scroll offsets on the way). */
    protected fun rectIn(view: View, ancestor: View): Rect {
        assertTrue(
            "$view is not inside $ancestor; chain=" + chain(view),
            isInside(view, ancestor)
        )
        var x = 0
        var y = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            x += current.left
            y += current.top
            val parent = current.parent as? View ?: break
            x -= parent.scrollX
            y -= parent.scrollY
            current = parent
        }
        return Rect(x, y, x + view.width, y + view.height)
    }

    private fun chain(view: View): String {
        val out = mutableListOf<String>()
        var current: View? = view
        while (current != null) {
            out += current.javaClass.simpleName + "#" + current.id
            current = current.parent as? View
        }
        return out.joinToString(" < ")
    }

    protected fun isInside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }
}

// ---------------------------------------------------------------------------
// Create sheet: one tap to the right picker, no import on Cancel.
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class W7CreateImportRoutingTest : W7CreateBase() {


    private fun openCreate(activity: ShellActivity): Dialog {
        activity.findViewById<View>(R.id.swNavScan).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the centre Scan action opens the Create sheet", dialog)
        assertTrue("the Create sheet is showing", dialog!!.isShowing)
        return dialog
    }

    @Test(timeout = 60_000)
    fun `Import PDF shows the rasterise notice before the picker`() {
        // DQA D1: deliberately updated — "one tap to picker" is superseded by the approved
        // notice. The picker still opens directly from the notice, with no Photos/PDF chooser.
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val sheet = openCreate(activity)

                sheet.findViewById<View>(R.id.swCreateImport).performClick()
                idle()
                val notice = ShadowDialog.getLatestDialog()!!
                assertTrue("the rasterise notice opens", notice.isShowing)
                assertEquals(
                    activity.getString(R.string.import_raster_notice),
                    notice.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                )
                assertNull("nothing is picked before Continue", shadowOf(activity).nextStartedActivityForResult)
                notice.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()

                val shadow = shadowOf(activity)
                val req = shadow.nextStartedActivityForResult
                assertNotNull(
                    "the PDF row must open the picker itself, not a second chooser sheet",
                    req
                )
                assertEquals(
                    "the system PDF document picker opens",
                    Intent.ACTION_OPEN_DOCUMENT,
                    req!!.intent.action
                )
                assertTrue(
                    "the picker is limited to PDF files",
                    req.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                        ?.contains("application/pdf") == true
                )
                assertFalse("the Create sheet is closed by the choice", sheet.isShowing)
                assertNull("no second picker is launched (no double tap)", shadow.nextStartedActivityForResult)
                assertEquals("choosing imports nothing by itself", 0, repo.listDocuments().size - 1)
            }
        }
    }

    @Test(timeout = 60_000)
    fun `Import photos opens the photo picker and never the scanner`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val sheet = openCreate(activity)
                val toastsBefore = ShadowToast.shownToastCount()

                sheet.findViewById<View>(R.id.swCreatePhotos).performClick()
                idle()

                val shadow = shadowOf(activity)
                val req = shadow.nextStartedActivityForResult
                assertNotNull("the photos row opens a picker", req)
                assertEquals(
                    "the OS photo picker opens",
                    Intent.ACTION_GET_CONTENT,
                    req!!.intent.action
                )
                assertEquals("images only", "image/*", req.intent.type)
                assertTrue(
                    "several pages can be picked at once",
                    req.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                )
                assertNull("only one picker opens", shadow.nextStartedActivityForResult)
                assertFalse("the Create sheet closes", sheet.isShowing)
                // The old route opened the Add photos sheet, whose Auto-crop option started the
                // Play-services scanner (or toasts about it when Play services is absent).
                assertNull(
                    "no photo-choice sheet stands between the row and the picker",
                    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing && it !== sheet }
                )
                assertEquals(
                    "the scanner never runs from the photos row",
                    toastsBefore,
                    ShadowToast.shownToastCount()
                )
                assertEquals("choosing imports nothing by itself", 1, repo.listDocuments().size)
            }
        }
    }

    @Test(timeout = 60_000)
    fun `cancelling the PDF picker imports nothing and returns focus to Create`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val disc = activity.findViewById<View>(R.id.swNavScan)
                val sheet = openCreate(activity)
                sheet.findViewById<View>(R.id.swCreateImport).performClick()
                idle()
                val notice = ShadowDialog.getLatestDialog()!!
                notice.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()

                val shadow = shadowOf(activity)
                val req = shadow.nextStartedActivityForResult
                assertNotNull("the PDF row opens the picker", req)
                shadow.clearNextStartedActivities()
                shadowOf(activity).receiveResult(req!!.intent, Activity.RESULT_CANCELED, null)
                idle()
                layoutRoot(activity)

                assertEquals("Cancel imports nothing", 1, repo.listDocuments().size)
                assertNull("Cancel starts no further screen", shadowOf(activity).nextStartedActivity)
                assertTrue(
                    "focus returns to the Create button (focus=${activity.currentFocus})",
                    disc.hasFocus() || root(activity).findFocus() === disc ||
                        disc.isAccessibilityFocused
                )
            }
        }
    }

    @Test(timeout = 60_000)
    fun `cancelling the photo picker imports nothing and returns focus to Create`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val disc = activity.findViewById<View>(R.id.swNavScan)
                val sheet = openCreate(activity)
                sheet.findViewById<View>(R.id.swCreatePhotos).performClick()
                idle()

                val shadow = shadowOf(activity)
                val req = shadow.nextStartedActivityForResult
                assertNotNull("the photos row opens the picker", req)
                shadow.clearNextStartedActivities()
                shadowOf(activity).receiveResult(req!!.intent, Activity.RESULT_CANCELED, null)
                idle()
                layoutRoot(activity)

                assertEquals("Cancel imports nothing", 1, repo.listDocuments().size)
                assertNull("Cancel starts no further screen", shadowOf(activity).nextStartedActivity)
                assertTrue(
                    "focus returns to the Create button (focus=${activity.currentFocus})",
                    disc.hasFocus() || root(activity).findFocus() === disc ||
                        disc.isAccessibilityFocused
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tools hub: nothing hidden — every tool of every job is on the first render.
// (T1 owner-approved 2026-11-20: the "All N tools" door was removed.)
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class W7ToolsExpansionTest : W7CreateBase() {


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

    /**
     * T1 owner-approved 2026-11-20: no hidden tools. Every job lists all of its tools on the
     * first render — no "All N tools" door, no expansion state, nothing behind an extra tap.
     */
    @Test(timeout = 60_000)
    fun `every catalogue tool is visible with zero extra taps`() {
        toolsTab { _, body ->
            val ready = ToolRegistry.all
                .filter { it.status == com.scanwhirl.app.ui.kit.ToolStatus.READY }
            // T2 owner 2026-11-21: icon grid
            val rows = ToolsHubScreen.tiles(body)
            assertEquals(
                "the whole READY catalogue is on the first render",
                ready.map { it.id }.sorted(),
                rows.mapNotNull { it.tool?.id }.sorted()
            )
            assertEquals("no job is listed twice", ready.size, rows.size)
            for (row in rows) {
                assertTrue("${row.tool?.id} must launch in one tap", row.hasOnClickListeners())
                assertTrue(
                    "${row.tool?.id} keeps a 48dp row (${row.height / density}dp)",
                    row.height >= dp(48f)
                )
            }
            val texts = allText(body)
            assertFalse(
                "an \"All N tools\" door survives in the hub",
                (1..50).any { n -> texts.any { it == app.getString(R.string.tools_job_all, n) } }
            )
            assertFalse(
                "a \"Show fewer\" door survives in the hub",
                texts.any { it == app.getString(R.string.tools_job_fewer) }
            )
        }
    }

    @Test(timeout = 60_000)
    fun `clearing search keeps focus in the field and brings the jobs back`() {
        toolsTab { activity, body ->
            val search = activity.findViewById<EditText>(R.id.swToolsSearch)
            search.requestFocus()
            search.setText("compress")
            idle()
            layoutRoot(activity)
            assertTrue("the results replace the jobs", ToolsHubScreen.rows(body).isNotEmpty())

            activity.findViewById<View>(R.id.swToolsClear).performClick()
            idle()
            layoutRoot(activity)

            assertTrue("the field is empty", search.text.isNullOrEmpty())
            assertTrue(
                "focus stays in the search field so typing can continue " +
                    "(focus=${root(activity).findFocus()})",
                root(activity).findFocus() === search
            )
            assertEquals(
                "the B3 student-jobs card and the seven job cards are back",
                ToolRegistry.jobs().size + 1,
                body.childCount
            )
        }
    }

    @Test(timeout = 60_000)
    fun `every job row is a READY tool and nothing else`() {
        toolsTab { _, body ->
            val ready = ToolRegistry.all
                .filter { it.status == com.scanwhirl.app.ui.kit.ToolStatus.READY }
                .map { it.id }
                .sorted()
            // T2 owner 2026-11-21: icon grid
            val shown = ToolsHubScreen.tiles(body).mapNotNull { it.tool?.id }.sorted()
            assertEquals(
                "the hub lists exactly the READY tools of the registry",
                ready,
                shown
            )
        }
    }

    private fun allText(view: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return out
    }
}

// ---------------------------------------------------------------------------
// Search at 200 % with the keyboard up.
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class W7ToolsSearchVisibilityTest : W7CreateBase() {


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

    @Test(timeout = 60_000)
    fun `typing after scrolling brings the field and its results into view above the keyboard`() {
        RuntimeEnvironment.setFontScale(2.0f)
        toolsTab { activity, body ->
            val scroll = shellScroll(body)
            // The keyboard is up: the shell pads its root by the IME height, exactly as
            // Insets.applySystemBarsPadding does on a device.
            root(activity).setPadding(0, 0, 0, dp(300f))
            layoutRoot(activity)
            // A person scrolled to the bottom of the job list before typing.
            scroll.scrollTo(0, 100000)
            idle()
            layoutRoot(activity)
            val visible = scroll.height - scroll.paddingBottom
            assertTrue("the page scrolls under the keyboard ($visible px)", visible > 0)
            assertTrue("the job list is taller than the visible area", scroll.scrollY > 0)

            val search = activity.findViewById<EditText>(R.id.swToolsSearch)
            search.setText("pdf")
            idle()
            layoutRoot(activity)

            val rows = ToolsHubScreen.rows(body)
            assertTrue("the query has results at 200 %", rows.isNotEmpty())
            val fieldRect = rectIn(search, scroll)
            assertTrue(
                "the field being typed in must stay on screen (field " +
                    "${fieldRect.top}..${fieldRect.bottom} of 0..$visible)",
                fieldRect.top >= 0 && fieldRect.bottom <= visible
            )
            val first = rows.first()
            val rect = rectIn(first, scroll)
            assertTrue(
                "the first result (${first.tool?.id}) must be on screen above the keyboard " +
                    "(row ${rect.top}..${rect.bottom} of 0..$visible)",
                rect.top >= 0 && rect.bottom <= visible
            )
        }
    }

    @Test(timeout = 60_000)
    fun `the search field grows instead of clipping at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        toolsTab { activity, body ->
            val search = activity.findViewById<EditText>(R.id.swToolsSearch)
            search.setText("compress")
            idle()
            layoutRoot(activity)
            val layout = search.layout
            assertNotNull("the query is laid out", layout)
            val needed = layout!!.height + search.totalPaddingTop + search.totalPaddingBottom
            assertTrue(
                "the field is ${search.height}px tall but its text needs ${needed}px: " +
                    "the box must grow with the system font, not clip it",
                search.height + 1 >= needed
            )
            assertTrue(
                "the box still clears a 48dp touch target (${search.height / density}dp)",
                search.height >= dp(48f)
            )
            assertTrue("the results are shown under the field", ToolsHubScreen.rows(body).isNotEmpty())
        }
    }
}

// ---------------------------------------------------------------------------
// Empty / no-results / error states, in both languages, at 320 and 360 dp.
// ---------------------------------------------------------------------------

abstract class W7ToolsStateBase : W7CreateBase() {


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

    protected fun textIn(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    protected fun byTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) byTag(root.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    @Test(timeout = 60_000)
    fun `a query with no hit says so and offers Clear without a stale row`() {
        toolsTab { activity, body ->
            val search = activity.findViewById<EditText>(R.id.swToolsSearch)
            search.setText("zzzqqq")
            idle()
            layoutRoot(activity)

            assertTrue("no tool row survives", ToolsHubScreen.rows(body).isEmpty())
            val texts = textIn(body)
            assertTrue(
                "the query is echoed back: $texts",
                texts.any { it == app.getString(R.string.tools_no_match, "zzzqqq") }
            )
            val clear = byTag(body, ToolsHubScreen.TAG_CLEAR_EMPTY)
            assertNotNull("the no-results state offers Clear", clear)
            assertTrue("Clear is at least 48dp", clear!!.height >= dp(48f))

            clear.performClick()
            idle()
            layoutRoot(activity)

            assertTrue("the field is empty again", search.text.isNullOrEmpty())
            assertEquals(
                "the B3 student-jobs card and the seven job cards are back",
                ToolRegistry.jobs().size + 1,
                body.childCount
            )
            assertTrue(
                "focus stays in the field so typing can continue",
                search.hasFocus() || root(activity).findFocus() === search
            )
        }
    }

    @Test(timeout = 60_000)
    fun `no tools ready is not dressed up as a failed search`() {
        ToolsHubScreen.catalogueSource = { emptyList() }
        toolsTab { activity, body ->
            val texts = textIn(body)
            assertTrue(
                "the empty state names itself: $texts",
                texts.any { it == app.getString(R.string.w7_tools_empty_title) }
            )
            assertTrue(
                "the empty state never shows the no-match wording: $texts",
                texts.none { it == app.getString(R.string.tools_no_match, "") }
            )
            assertNull(
                "the empty state offers no Clear search action",
                byTag(body, ToolsHubScreen.TAG_CLEAR_EMPTY)
            )
            assertTrue("no tool row is invented", ToolsHubScreen.rows(body).isEmpty())
            assertTrue(
                "the empty state says where the documents are",
                texts.any { it == app.getString(R.string.w7_tools_empty_body) }
            )
            // Typing still gets an honest no-match answer in that state.
            activity.findViewById<EditText>(R.id.swToolsSearch).setText("zzzqqq")
            idle()
            layoutRoot(activity)
            assertNotNull(
                "a query always answers",
                byTag(body, ToolsHubScreen.TAG_CLEAR_EMPTY)
            )
        }
    }

    @Test(timeout = 60_000)
    fun `a catalogue failure shows an error state and Try again recovers`() {
        ToolsHubScreen.catalogueSource = { throw IllegalStateException("registry fault") }
        toolsTab { activity, body ->
            val texts = textIn(body)
            assertTrue(
                "the failure is named: $texts",
                texts.any { it == app.getString(R.string.w7_tools_error_title) }
            )
            assertTrue(
                "the failure explains it: $texts",
                texts.any { it == app.getString(R.string.w7_tools_error_body) }
            )
            assertTrue(
                "the failure never pretends there are no tools",
                texts.none { it == app.getString(R.string.w7_tools_empty_title) }
            )
            val retry = byTag(body, ToolsHubScreen.TAG_RETRY)
            assertNotNull("Try again is offered", retry)
            assertTrue("Try again is at least 48dp", retry!!.height >= dp(48f))

            // The fault clears; Try again renders the real catalogue.
            ToolsHubScreen.catalogueSource = null
            retry.performClick()
            idle()
            layoutRoot(activity)

            assertEquals(
                "the student jobs and the grouped jobs render after a retry",
                ToolRegistry.jobs().size + 1,
                body.childCount
            )
            assertNull(
                "Try again launches nothing",
                shadowOf(activity).nextStartedActivity
            )
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class W7ToolsStateTest : W7ToolsStateBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h740dp-xhdpi")
class W7ToolsStateHindiTest : W7ToolsStateBase() {


    @Test(timeout = 60_000)
    fun `the new states are written in Hindi, not English`() {
        ToolsHubScreen.catalogueSource = { emptyList() }
        toolsTab { activity, body ->
            val texts = textIn(body)
            for (key in intArrayOf(
                R.string.w7_tools_empty_title,
                R.string.w7_tools_empty_body
            )) {
                val value = app.getString(key)
                assertTrue("$key has Hindi text", value.any { it.code in 0x0900..0x097F })
                assertTrue("$key is shown", texts.contains(value))
            }
        }
        ToolsHubScreen.catalogueSource = { throw IllegalStateException("fault") }
        toolsTab { _, body ->
            val texts = textIn(body)
            for (key in intArrayOf(
                R.string.w7_tools_error_title,
                R.string.w7_tools_error_body,
                R.string.w7_tools_retry
            )) {
                val value = app.getString(key)
                assertTrue("$key has Hindi text", value.any { it.code in 0x0900..0x097F })
                assertTrue("$key is shown", texts.contains(value))
            }
        }
    }
}

/**
 * 320dp is the narrowest phone the app supports. T1 owner-approved 2026-11-20: nothing is
 * hidden at any width (no "All N tools" door), and the no-results Clear action must stay whole
 * at 200 % on that width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class W7ToolsNarrowTest : W7CreateBase() {


    @Test(timeout = 60_000)
    fun `the whole catalogue is visible and Clear stays whole at 200 percent on 320dp`() {
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.swNavTools).performClick()
                idle()
                layoutRoot(activity)
                val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)

                val ready = ToolRegistry.all
                    .count { it.status == com.scanwhirl.app.ui.kit.ToolStatus.READY }
                // T2 owner 2026-11-21: icon grid
                val rows = ToolsHubScreen.tiles(body)
                assertEquals("every shipping tool shows at 320dp and 200 %", ready, rows.size)
                for (row in rows) {
                    assertTrue(
                        "${row.tool?.id} keeps a 48dp row at 320dp (${row.height / density}dp)",
                        row.height >= dp(48f)
                    )
                    val layout = row.labelView.layout ?: continue
                    for (line in 0 until layout.lineCount) {
                        assertEquals(
                            "\"${row.labelView.text}\" is ellipsized at 200 % on 320dp",
                            0,
                            layout.getEllipsisCount(line)
                        )
                    }
                }

                val search = activity.findViewById<EditText>(R.id.swToolsSearch)
                search.setText("zzzqqq")
                idle()
                layoutRoot(activity)
                val clear = findTag(body, ToolsHubScreen.TAG_CLEAR_EMPTY)
                assertNotNull("Clear is offered at 320dp", clear)
                for (label in labelsIn(clear!!)) {
                    val layout = label.layout ?: continue
                    for (line in 0 until layout.lineCount) {
                        assertEquals(
                            "\"${label.text}\" is ellipsized at 200 % on 320dp",
                            0,
                            layout.getEllipsisCount(line)
                        )
                    }
                }
                assertTrue("Clear keeps a 48dp target", clear.height >= dp(48f))
            }
        }
    }

    private fun labelsIn(view: View): List<TextView> {
        val out = mutableListOf<TextView>()
        if (view is TextView) out += view
        if (view is ViewGroup) for (i in 0 until view.childCount) out += labelsIn(view.getChildAt(i))
        return out.filter { it.text?.isNotBlank() == true && it.visibility == View.VISIBLE }
    }

    private fun findTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findTag(root.getChildAt(i), tag)?.let { return it }
        return null
    }
}


// ---------------------------------------------------------------------------
// Create sheet at 320 dp and 200 %: every entry label stays whole.
// ---------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class W7CreateNarrowTest : W7CreateBase() {


    @Test(timeout = 60_000)
    fun `every Create entry label is whole at 200 percent on 320dp`() {
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                activity.findViewById<View>(R.id.swNavScan).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog()!!
                val decor = sheet.window!!.decorView
                val dm = activity.resources.displayMetrics
                repeat(2) {
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                    )
                    decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
                }
                val problems = mutableListOf<String>()
                for (id in intArrayOf(
                    R.id.swCreateIdCard, R.id.swCreatePassport, R.id.swCreateQr,
                    R.id.swCreatePhotos, R.id.swCreateImport
                )) {
                    val entry = sheet.findViewById<View>(id)
                    assertNotNull("entry $id exists", entry)
                    val label = labelIn(entry)
                    assertNotNull("entry $id has a label", label)
                    val layout = label!!.layout ?: continue
                    for (line in 0 until layout.lineCount) {
                        if (layout.getEllipsisCount(line) != 0) {
                            problems += "$id: ellipsized on line $line"
                        }
                    }
                    if (layout.height + label.totalPaddingTop + label.totalPaddingBottom >
                        label.height + 1
                    ) {
                        problems += "$id: clipped (${layout.height}px in ${label.height}px)"
                    }
                    if (entry.height < dp(48f)) problems += "$id: ${entry.height / density}dp tall"
                }
                assertTrue("Create labels at 200 %/320dp: ${problems.joinToString(" | ")}", problems.isEmpty())
                sheet.dismiss()
            }
        }
    }

    private fun labelIn(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) labelIn(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
