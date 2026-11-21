package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
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

/**
 * Owner decision UX-008: the Home Quick tools row is back (compact, at most two rows), the
 * pencil opens ONE editor sheet, and the tiles use the same ToolTile/ToolHost.launchTool path
 * (including the Pro gate) as the Tools tab. Checked in English and Hindi, 100 % and 200 %.
 */
abstract class U4QuickToolsBase {

    protected lateinit var app: Application
    protected val hi: Boolean get() = app.resources.configuration.locales[0].language == "hi"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.resetQuickTools(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "Quick tools seed")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.resetQuickTools(app)
        ProGate.setTestOverride(app, null)
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun quickTiles(activity: ShellActivity): List<ToolTile> {
        val row = activity.findViewById<View>(R.id.swQuickRow) ?: return emptyList()
        return allTiles(row)
    }

    private fun sheetTiles(): List<ToolTile> {
        val dialog = ShadowDialog.getLatestDialog() ?: return emptyList()
        return allTiles(dialog.findViewById(R.id.swSheetContent) ?: return emptyList())
    }

    private fun allTiles(group: View): List<ToolTile> {
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

    private fun openEditor(activity: ShellActivity) {
        activity.findViewById<View>(R.id.swQuickEdit).performClick()
        idle()
        assertNotNull("the pencil opens the editor sheet", ShadowDialog.getLatestDialog())
    }

    @Test(timeout = 60_000)
    fun `defaults visible with title all tools link and pencil`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val header = activity.findViewById<View>(R.id.swQuickEdit).parent as ViewGroup
                val title = header.getChildAt(0) as TextView
                assertEquals(if (hi) "झटपट काम" else "Quick tools", title.text.toString())
                val all = activity.findViewById<TextView>(R.id.swQuickAll)
                assertEquals(if (hi) "सभी काम ›" else "All tools ›", all.text.toString())
                assertEquals(
                    if (hi) "झटपट काम बदलें" else "Edit quick tools",
                    activity.findViewById<View>(R.id.swQuickEdit).contentDescription.toString()
                )
                assertNotNull("the first row is on Home", activity.findViewById<View>(R.id.swQuickRow))
                assertNull("at most two rows", activity.findViewById<View>(R.id.swQuickRow3))
                assertEquals(
                    "the six shipped defaults are visible",
                    Prefs.DEFAULT_QUICK_TOOLS,
                    quickTiles(activity).map { it.tool!!.id }
                )
                val expected = listOf(
                    R.string.quick_merge, R.string.quick_compress, R.string.quick_sign,
                    R.string.quick_protect, R.string.quick_to_word, R.string.quick_read_text
                ).map { activity.getString(it) }
                assertEquals("labels are the judged short EN/HI words", expected, quickTiles(activity).map { it.labelView.text.toString() })
            }
        }
    }

    @Test(timeout = 60_000)
    fun `a quick tile launches exactly like the Tools tab`() {
        fun quickLaunch(): String? {
            var name: String? = null
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    quickTiles(activity).first { it.tool?.id == "merge" }.performClick()
                    idle()
                    name = shadowOf(activity).nextStartedActivity?.component?.className
                }
            }
            return name
        }
        fun hubLaunch(): String? {
            var name: String? = null
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    activity.findViewById<View>(R.id.swNavTools).performClick()
                    idle()
                    ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                        .first { it.tool?.id == "merge" }.performClick()
                    idle()
                    name = shadowOf(activity).nextStartedActivity?.component?.className
                }
            }
            return name
        }
        val fromHome = quickLaunch()
        val fromHub = hubLaunch()
        assertNotNull("the Home tile opens a screen", fromHome)
        assertEquals("the Home tile runs the same launch as the Tools tab", fromHub, fromHome)
    }

    @Test(timeout = 60_000)
    fun `a Pro quick tile is gated for a free person`() {
        Prefs.setQuickTools(app, listOf("to_word"))
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val tile = quickTiles(activity).first { it.tool?.id == "to_word" }
                assertTrue("the Pro badge is drawn in Free", tile.proTag.isVisible)
                tile.performClick()
                idle()
                assertNotNull(
                    "the one Pro screen opens from the Home quick row",
                    activity.findViewById<View>(R.id.swProScreen)
                )
                assertNull(
                    "a gated tile starts no tool activity",
                    shadowOf(activity).nextStartedActivity
                )
                activity.findViewById<View>(R.id.swProClose).performClick()
                idle()
            }
        }
    }

    @Test(timeout = 60_000)
    fun `editor adds removes reorders and resets with persist and cancel`() {
        val added = ToolRegistry.all.first {
            it.status == ToolStatus.READY && it.id !in Prefs.DEFAULT_QUICK_TOOLS
        }.id
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val dialog = ShadowDialog.getLatestDialog()!!
                assertEquals(
                    if (hi) "झटपट काम बदलें" else "Edit quick tools",
                    dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                assertTrue(
                    "the editor explains its gestures",
                    dialog.findViewById<ViewGroup>(R.id.swSheetContent)?.let { root ->
                        allText(root).any { it.text.toString() == app.getString(R.string.u4_quick_editor_hint) }
                    } == true
                )
                assertTrue(
                    "the reset control is in the sheet",
                    allText(dialog.findViewById(R.id.swSheetContent)).any {
                        it.text.toString() == app.getString(R.string.u4_quick_reset)
                    }
                )
                sheetTiles().first { it.tool?.id == added }.performClick()
                idle()
                dialog.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
        }
        assertTrue("the added id is saved", Prefs.quickTools(app).contains(added))

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue("the new tile is on Home after relaunch", quickTiles(activity).any { it.tool?.id == added })
                openEditor(activity)
                sheetTiles().first { it.tool?.id == added }.performClick()
                idle()
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetSecondary).performClick()
                idle()
            }
        }
        assertTrue("Cancel discarded the removal", Prefs.quickTools(app).contains(added))

        // Reorder and reset are driven through the same draft the sheet renders.
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val first = activity.findViewById<ViewGroup>(R.id.swTabContent).getChildAt(0)
                val library = if (first is LibraryScreen) first else (first as ViewGroup).getChildAt(0) as? LibraryScreen
                assertNotNull("the Home screen is a LibraryScreen", library)
                val last = quickTiles(activity).last().tool!!.id
                library!!.moveQuickTool(last, 0)
                idle()
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
        }
        assertEquals("the drag order is saved", quickFirstId(), Prefs.quickTools(app).first())

        // LEAD DECISION (L1-04): OLD clicked Reset then the editor's Done and expected the
        // defaults to be saved. NEW clicks Reset -> "Reset quick tools?" -> Reset, then the
        // editor's Done; it also checks Cancel keeps the previously saved set. Draft-only
        // semantics and persistence are both preserved.
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val editor = ShadowDialog.getLatestDialog()!!
                allText(editor.findViewById(R.id.swSheetContent))
                    .first { it.text.toString() == app.getString(R.string.u4_quick_reset) }
                    .performClick()
                idle()
                val confirm = ShadowDialog.getLatestDialog()!!
                assertEquals(
                    "Reset asks first",
                    app.getString(R.string.ux130_l1_reset_quick),
                    confirm.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                confirm.findViewById<View>(R.id.swSheetSecondary).performClick()
                idle()
                editor.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
        }
        assertEquals("canceling Reset keeps the saved order", quickFirstId(), Prefs.quickTools(app).first())

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val editor = ShadowDialog.getLatestDialog()!!
                allText(editor.findViewById(R.id.swSheetContent))
                    .first { it.text.toString() == app.getString(R.string.u4_quick_reset) }
                    .performClick()
                idle()
                val confirm = ShadowDialog.getLatestDialog()!!
                confirm.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
                editor.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
        }
        assertEquals("reset + Done restores the shipped six", Prefs.DEFAULT_QUICK_TOOLS, Prefs.quickTools(app))
        assertTrue(
            "quick_tools is in the backup allowlist",
            java.io.File("src/main/java/com/scanwhirl/app/store/backup/FullBackup.kt")
                .readText().contains("\"quick_tools\" to SettingType.TEXT")
        )
    }

    @Test(timeout = 60_000)
    fun `editor tiles speak add or remove and the twelve limit is focusable`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val removeTile = sheetTiles().first { it.tool?.id == "merge" }
                assertEquals(
                    "a selected tile speaks its remove action",
                    activity.getString(R.string.quick_remove, activity.getString(R.string.tool_merge)),
                    removeTile.contentDescription.toString()
                )
                val addTile = sheetTiles().first { !Prefs.quickTools(activity).contains(it.tool!!.id) }
                assertEquals(
                    "an available tile speaks its add action",
                    activity.getString(R.string.quick_add, activity.getString(addTile.tool!!.title)),
                    addTile.contentDescription.toString()
                )
            }
        }
        val twelve = ToolRegistry.all.filter { it.status == ToolStatus.READY }
            .map { it.id }.distinct().take(12)
        assertEquals("the catalogue can fill twelve", 12, twelve.size)
        Prefs.setQuickTools(app, twelve)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val dialog = ShadowDialog.getLatestDialog()!!
                val limit = activity.getString(R.string.quick_max_reached)
                val focusableHint = allChildren(dialog.findViewById(R.id.swSheetContent)!!)
                    .filter { it.isFocusable && it.contentDescription?.toString() == limit }
                    .toList()
                assertTrue("the max-12 hint is on a focusable view", focusableHint.isNotEmpty())
                val blocked = sheetTiles().filter { !twelve.contains(it.tool?.id) }
                if (blocked.isNotEmpty()) {
                    assertTrue(
                        "a blocked add tile still speaks the limit",
                        blocked.all { it.contentDescription?.toString() == limit && it.isFocusable }
                    )
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `pro is not spoken for a pro tool when the person has pro`() {
        val proId = ToolRegistry.all.first { it.pro && it.status == ToolStatus.READY }.id
        Prefs.setQuickTools(app, listOf(proId))
        ProGate.setTestOverride(app, true)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val tile = quickTiles(activity).first { it.tool?.id == proId }
                assertFalse(
                    "a Pro build never speaks PRO",
                    tile.contentDescription.toString().contains(activity.getString(R.string.badge_pro))
                )
            }
        }
        ProGate.setTestOverride(app, false)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val tile = quickTiles(activity).first { it.tool?.id == proId }
                assertTrue(
                    "Free still hears PRO on a gated tile",
                    tile.contentDescription.toString().contains(activity.getString(R.string.badge_pro))
                )
            }
        }
    }

    private fun quickFirstId(): String {
        var id: String? = null
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                id = quickTiles(activity).firstOrNull()?.tool?.id
            }
        }
        return requireNotNull(id)
    }

    @Test(timeout = 60_000)
    fun `editor and tiles reflow at 200 percent without clipping`() {
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val density = activity.resources.displayMetrics.density
                val row = activity.findViewById<View>(R.id.swQuickRow)!!
                val label = allTiles(row).first().labelView
                val layout = label.layout
                assertNotNull("the quick label is laid out", layout)
                assertFalse("no ellipsis at 200 %", (0 until layout!!.lineCount).any { layout.getEllipsisCount(it) > 0 })
                assertTrue("text fits its box", layout.height <= label.height + 1)
                assertTrue("a tile is at least 48dp", allTiles(row).first().height >= 48 * density)

                openEditor(activity)
                val dialog = ShadowDialog.getLatestDialog()!!
                assertTrue(
                    "the editor content scrolls instead of clipping",
                    allChildren(dialog.findViewById(R.id.swSheetContent)).any { it is ScrollView }
                )
                val problems = mutableListOf<String>()
                for (tile in sheetTiles()) {
                    val tv = tile.labelView
                    val l = tv.layout ?: continue
                    for (line in 0 until l.lineCount) {
                        if (l.getEllipsisCount(line) > 0) problems += "ellipsized \"${tv.text}\""
                        if (l.getLineMax(line) > tv.width - tv.totalPaddingLeft - tv.totalPaddingRight + 1) {
                            problems += "wide line in \"${tv.text}\""
                        }
                    }
                }
                assertTrue("editor labels wrap: $problems", problems.isEmpty())
            }
        }
    }

    private fun allChildren(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allChildren(view.getChildAt(i)))
    }

    private fun allText(group: View): List<TextView> =
        allChildren(group).filterIsInstance<TextView>().toList()
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class U4QuickToolsEnTest : U4QuickToolsBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class U4QuickToolsHiTest : U4QuickToolsBase()
