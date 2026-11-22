package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Council §7: the Home Quick tools row keeps the same ToolTile launch path as the Tools
 * tab, but the editor is now newcomer-safe — a plain "Customize" button, a list of
 * checkbox rows with FULL names, ▲▼ buttons instead of drag, one "Choose up to 6 tools.
 * Use the arrows to reorder." instruction, Reset, and a cap of six. The only dismissal
 * is the X; "Save quick tools" is the pinned primary. Checked in EN and HI at 100 % and 200 %.
 */
abstract class U4QuickToolsBase {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
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
        // Council §7: the bare pencil is replaced by a plain "Customize" text button.
        activity.findViewById<View>(R.id.swQuickEdit).performClick()
        idle()
        assertNotNull("Customize opens the editor sheet", ShadowDialog.getLatestDialog())
    }

    private fun sheetContent(): ViewGroup {
        val dialog = ShadowDialog.getLatestDialog() ?: return LinearLayout(app)
        return dialog.findViewById(R.id.swSheetContent) ?: LinearLayout(app)
    }

    private fun checkboxes(): List<CheckBox> {
        val out = mutableListOf<CheckBox>()
        fun walk(v: View) {
            if (v is CheckBox) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(sheetContent())
        return out
    }

    private fun button(id: Int, tag: String): View {
        val found = mutableListOf<View>()
        fun walk(v: View) {
            if (v.id == id && v.tag == tag) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(sheetContent())
        return found.single()
    }

    private fun allChildren(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allChildren(view.getChildAt(i)))
    }

    private fun allText(group: View): List<TextView> =
        allChildren(group).filterIsInstance<TextView>().toList()

    /** The short Home-strip label for a saved tool id, after the council OCR rename. */
    private fun labelFor(activity: ShellActivity, id: String): String = activity.getString(
        when (id) {
            "merge" -> R.string.cb_quick_merge
            "compress" -> R.string.cb_quick_compress
            "sign" -> R.string.cb_quick_sign
            "protect" -> R.string.cb_quick_protect
            "read_text", "ocr" -> R.string.cb_quick_ocr
            "to_word" -> R.string.quick_to_word
            else -> R.string.quick_read_text
        }
    )

    @Test(timeout = 60_000)
    fun `defaults visible with title all tools link and Customize button`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val header = activity.findViewById<View>(R.id.swQuickEdit).parent as ViewGroup
                val title = header.getChildAt(0) as TextView
                assertEquals(if (hi) "झटपट काम" else "Quick tools", title.text.toString())
                val all = activity.findViewById<TextView>(R.id.swQuickAll)
                assertEquals(if (hi) "सभी काम ›" else "All tools ›", all.text.toString())
                // OLD: a bare pencil with contentDescription "Edit quick tools".
                // NEW: a labelled "Customize" text button.
                val customize = activity.findViewById<TextView>(R.id.swQuickEdit)
                assertEquals(if (hi) "बदलें" else "Customize", customize.text.toString())
                assertEquals(
                    if (hi) "झटपट काम बदलें" else "Customize quick tools",
                    customize.contentDescription.toString()
                )
                assertNotNull("the first row is on Home", activity.findViewById<View>(R.id.swQuickRow))
                assertNull("at most one row", activity.findViewById<View>(R.id.swQuickRow2))
                assertEquals(
                    "the five council defaults are visible",
                    Prefs.DEFAULT_QUICK_TOOLS,
                    quickTiles(activity).map { it.tool!!.id }
                )
                // Lane B replaced the pencil with a Customize checkbox editor whose rows use
                // the cb_quick_* labels; lane N keeps the council's five default tiles.
                assertEquals(
                    "labels are the judged short EN/HI words",
                    Prefs.DEFAULT_QUICK_TOOLS.map { labelFor(activity, it) },
                    quickTiles(activity).map { it.labelView.text.toString() }
                )
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
                assertNull("a gated tile starts no tool activity", shadowOf(activity).nextStartedActivity)
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
                // SH2 COPY_DIET: OLD title "Customize quick tools" / "झटपट काम बदलें".
                // NEW title "Quick tools" / "झटपट काम" (P22).
                assertEquals(
                    if (hi) "झटपट काम" else "Quick tools",
                    dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                assertTrue(
                    "the editor explains the gesture-free interaction",
                    allText(sheetContent()).any {
                        it.text.toString() == app.getString(R.string.cb_quick_editor_hint)
                    }
                )
                assertTrue(
                    "the reset control is in the sheet",
                    allChildren(sheetContent()).any { it.id == R.id.cbQuickReset }
                )
                // OLD: a tile click added a seventh. NEW: cap 6, so first uncheck one.
                checkboxes().first { it.tag == Prefs.DEFAULT_QUICK_TOOLS.first() }.isChecked = false
                idle()
                checkboxes().first { it.tag == added }.isChecked = true
                idle()
                dialog.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
        }
        assertTrue("the added id is saved", Prefs.quickTools(app).contains(added))
        assertFalse("the removed id is gone", Prefs.quickTools(app).contains(Prefs.DEFAULT_QUICK_TOOLS.first()))

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue("the new tile is on Home after relaunch", quickTiles(activity).any { it.tool?.id == added })
                openEditor(activity)
                checkboxes().first { it.tag == added }.isChecked = false
                idle()
                // SH2: this non-destructive editor has X only; the X discards the draft.
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetClose).performClick()
                idle()
            }
        }
        assertTrue("X discarded the removal", Prefs.quickTools(app).contains(added))

        // Reorder is driven through the labelled ▲/▼ buttons on the draft rows.
        val beforeReorder = Prefs.quickTools(app)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                button(R.id.cbQuickUp, beforeReorder[1]).performClick()
                idle()
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
        }
        assertEquals("the named reorder is saved", beforeReorder[1], quickFirstId())

        // Reset -> confirm -> Save quick tools restores the shipped defaults; Cancel (the
        // destructive confirmation's own button) keeps the order.
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val editor = ShadowDialog.getLatestDialog()!!
                allChildren(sheetContent()).first { it.id == R.id.cbQuickReset }.performClick()
                idle()
                val confirm = ShadowDialog.getLatestDialog()!!
                assertEquals(app.getString(R.string.ux130_l1_reset_quick),
                    confirm.findViewById<TextView>(R.id.swSheetTitle).text.toString())
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
                allChildren(sheetContent()).first { it.id == R.id.cbQuickReset }.performClick()
                idle()
                ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
                editor.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
        }
        assertEquals("reset + Done restores the shipped defaults",
            Prefs.DEFAULT_QUICK_TOOLS, Prefs.quickTools(app))
        assertTrue(
            "quick_tools is in the backup allowlist",
            java.io.File("src/main/java/com/scanwhirl/app/store/backup/FullBackup.kt")
                .readText().contains("\"quick_tools\" to SettingType.TEXT")
        )
    }

    @Test(timeout = 60_000)
    fun `editor rows use full names and the cap of six is focusable`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                val boxes = checkboxes()
                assertTrue("one checkbox per ready tool", boxes.size > 6)
                val selected = boxes.filter { it.isChecked }.map { it.tag as String }
                assertEquals("saved tools are checked", Prefs.quickTools(app).toSet(), selected.toSet())
                // OLD: short tiles spoke "Add/Remove <tool>". NEW: full names on checkboxes
                // and labelled ▲/▼ buttons, no drag.
                assertTrue(
                    "every row names the full tool",
                    boxes.all { it.text.toString().isNotBlank() }
                )
                assertTrue(
                    "a selected row has named up and down controls",
                    selected.all { id ->
                        runCatching { button(R.id.cbQuickUp, id) }.isSuccess &&
                            runCatching { button(R.id.cbQuickDown, id) }.isSuccess
                    }
                )
                assertNull("no tiles in the editor", allTiles(sheetContent()).firstOrNull())
            }
        }
        // Council §7: cap 6. A saved list longer than six is kept but cannot add.
        val twelve = ToolRegistry.all.filter { it.status == ToolStatus.READY }
            .map { it.id }.distinct().take(12)
        Prefs.setQuickTools(app, twelve)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                openEditor(activity)
                // SH2: OLD = a second focusable "up to 6" line (cbQuickLimit).
                // NEW = one instruction line that already states the cap.
                val instruction = activity.getString(R.string.cb_quick_editor_hint)
                assertTrue("the single instruction line states the cap",
                    allChildren(sheetContent()).any {
                        it.id == R.id.cbQuickHint && (it as? TextView)?.text?.toString() == instruction
                    })
                assertTrue("no duplicate cap line",
                    allChildren(sheetContent()).none { it.id == R.id.cbQuickLimit })
                val blocked = checkboxes().filter { !(it.tag as String).let { id -> twelve.contains(id) } }
                assertTrue("extra tools stay unchecked and cannot add",
                    blocked.all { !it.isChecked && !it.isEnabled })
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
                assertFalse("a Pro build never speaks PRO",
                    tile.contentDescription.toString().contains(activity.getString(R.string.badge_pro)))
            }
        }
        ProGate.setTestOverride(app, false)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val tile = quickTiles(activity).first { it.tool?.id == proId }
                assertTrue("Free still hears PRO on a gated tile",
                    tile.contentDescription.toString().contains(activity.getString(R.string.badge_pro)))
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
                assertTrue("the editor content scrolls instead of clipping",
                    allChildren(sheetContent()).any { it is ScrollView })
                val problems = mutableListOf<String>()
                for (box in checkboxes()) {
                    val l = box.layout ?: continue
                    for (line in 0 until l.lineCount) {
                        if (l.getEllipsisCount(line) > 0) problems += "ellipsized \"${box.text}\""
                        if (l.getLineMax(line) > box.width - box.totalPaddingLeft - box.totalPaddingRight + 1) {
                            problems += "wide line in \"${box.text}\""
                        }
                    }
                }
                assertTrue("editor rows wrap: $problems", problems.isEmpty())
                for (box in checkboxes().filter { it.isChecked }) {
                    assertTrue("▲ is a 48dp target", button(R.id.cbQuickUp, box.tag as String).height >= 48 * density - 1)
                    assertTrue("▼ is a 48dp target", button(R.id.cbQuickDown, box.tag as String).height >= 48 * density - 1)
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class U4QuickToolsEnTest : U4QuickToolsBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class U4QuickToolsHiTest : U4QuickToolsBase()
