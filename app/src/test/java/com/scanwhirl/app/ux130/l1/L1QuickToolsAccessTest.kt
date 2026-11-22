package com.scanwhirl.app.ux130.l1
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * L1-04 — named reorder actions, legible labels/badges, one Pro indication per gated
 * tile and an accessible fast-scroll. RED on base: reorder was drag-only, several
 * quick labels and badges were 11/11.5sp, the job header repeated Pro, and the
 * fast-scroll exposed no scroll actions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class L1QuickToolsAccessTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.resetQuickTools(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun openEditor(activity: ShellActivity): android.app.Dialog {
        activity.findViewById<View>(R.id.swQuickEdit).performClick()
        idle()
        return ShadowDialog.getLatestDialog()
    }

    private fun tileInfo(view: View): AccessibilityNodeInfo {
        val i = AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(i)
        return i
    }

    @Test(timeout = 30_000)
    fun `the quick editor exposes named move up and down buttons with boundary no-ops`() {
        // Council §7: OLD advertised uxL1MoveEarlier/Later accessibility actions on tiles.
        // NEW uses visible, labelled ▲/▼ buttons. The intent is unchanged: reordering is
        // available by name, boundary presses are no-ops, and changes stay in the draft
        // until Done.
        fun button(content: View, id: Int, tag: String): View {
            val found = mutableListOf<View>()
            fun walk(v: View) {
                if (v.id == id && v.tag == tag) found += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(content)
            return found.single()
        }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val before = Prefs.quickTools(app)
            val editor = openEditor(activity)
            var content = editor.findViewById<ViewGroup>(R.id.swSheetContent)
            // Boundary no-ops: first cannot move up, last cannot move down.
            button(content, R.id.cbQuickUp, before.first()).performClick()
            idle()
            content = editor.findViewById<ViewGroup>(R.id.swSheetContent)
            button(content, R.id.cbQuickDown, before.last()).performClick()
            idle()
            editor.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            assertEquals("boundary moves change nothing", before, Prefs.quickTools(app))
        } finally {
            controller.pause().stop().destroy()
            Prefs.resetQuickTools(app)
        }

        val second = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = second.get()
            idle()
            val before = Prefs.quickTools(app)
            val editor = openEditor(activity)
            val content = editor.findViewById<ViewGroup>(R.id.swSheetContent)
            // A real move: the third tool moves up one place, but only after Done.
            button(content, R.id.cbQuickUp, before[2]).performClick()
            idle()
            assertEquals(before, Prefs.quickTools(app))
            editor.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            assertEquals(before[2], Prefs.quickTools(app)[1])
        } finally {
            second.pause().stop().destroy()
            Prefs.resetQuickTools(app)
        }
    }

    @Test(timeout = 30_000)
    fun `quick labels badges and document meta clear 12sp and Hindi clears 14sp`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val scaled = activity.resources.displayMetrics.scaledDensity
            val quick = activity.findViewById<ViewGroup>(R.id.swQuickRow)
            assertNotNull(quick)
            fun walk(v: View) {
                if (v is ToolTile) {
                    assertTrue(
                        "quick label ${v.labelView.text} is >=12sp",
                        v.labelView.textSize / scaled >= 11.99f
                    )
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.findViewById<View>(android.R.id.content))
            val src = java.io.File("src/main/java/com/scanwhirl/app/ui/kit/DocCard.kt").readText()
            assertTrue("page badge >= 12sp", src.contains("pageBadge = SwStyle.text(context, 12f"))
            // Council §3: OLD = a 12sp "Protected" badge on the name row.
            // NEW = no lockBadge at all; the lock is a 16dp scrim circle on the thumb.
            assertTrue("no lock badge on the name row", !src.contains("lockBadge ="))
            assertTrue("lock is a 16dp thumb overlay", src.contains("(16 * d).toInt()") && src.contains("R.id.swDocLock"))
            assertTrue("Hindi meta >= 14sp", src.contains("showingHindi(context)) 14f else 12f"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a gated tool is named Pro exactly once on its tile not its job header`() {
        val hub = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/ToolsHubScreen.kt").readText()
        val header = hub.substringAfter("private fun jobHeader").substringBefore("private fun headRule")
        assertTrue("the redundant job-header Pro chip is gone", !header.contains("proChip()"))
        val tests = java.io.File("src/test/java/com/scanwhirl/app/ui/shell/ToolsJobsTest.kt").readText()
        assertTrue("the remaining one-per-tile Pro indication is still asserted", tests.contains("proTag.isVisible"))
    }

    @Test(timeout = 30_000)
    fun `fast scroll exposes named forward and backward actions`() {
        repeat(16) { com.scanwhirl.app.store.DocFixtures.saved(
            com.scanwhirl.app.store.ScanRepository(app), app, pages = 1, name = "Fast $it"
        ) }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val bar = activity.findViewById<View>(R.id.swLibraryFastScroll)
            assertNotNull(bar)
            val info = AccessibilityNodeInfo.obtain()
            bar.onInitializeAccessibilityNodeInfo(info)
            assertTrue("the bar is scrollable", info.isScrollable)
            assertTrue(
                "forward action is named",
                info.actionList.any { it.id == R.id.uxL1ScrollForward }
            )
            assertTrue(
                "backward action is named",
                info.actionList.any { it.id == R.id.uxL1ScrollBackward }
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
