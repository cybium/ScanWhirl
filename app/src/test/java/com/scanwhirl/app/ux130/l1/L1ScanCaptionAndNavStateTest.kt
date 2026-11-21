package com.scanwhirl.app.ux130.l1

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
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

/**
 * L1-03 (implemented part) — the centre action has a visible Scan caption inside its
 * single focus stop, and the four destinations expose real selection state to TalkBack.
 *
 * The wrapping/no-ellipsis part of G6 conflicts with SH1's pinned 200%-Hindi test and is
 * reported to the lead rather than forced through (see final report).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class L1ScanCaptionAndNavStateTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
    }

    private fun info(view: View): AccessibilityNodeInfo {
        val i = AccessibilityNodeInfo.obtain()
        view.onInitializeAccessibilityNodeInfo(i)
        return i
    }

    @Test(timeout = 30_000)
    fun `the centre disc has one visible Scan caption inside the same focus stop`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val disc = activity.findViewById<View>(R.id.swNavScan)
            val caption = activity.findViewById<TextView>(R.id.uxL1ScanCaption)
            assertNotNull("the caption exists", caption)
            assertEquals("Scan", caption!!.text.toString())
            assertEquals(
                "the caption is inside the disc's single focus stop",
                disc, caption.parent
            )
            assertEquals(
                "the caption is silent to TalkBack so there is only one Scan target",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, caption.importantForAccessibility
            )
            assertTrue("the disc keeps its own named action", !disc.contentDescription.isNullOrBlank())
            assertEquals("Scan is 12sp", 12f, caption.textSize / activity.resources.displayMetrics.scaledDensity, 0.1f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the selected destination exposes a selectable current state`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val ids = intArrayOf(R.id.swNavLibrary, R.id.swNavTools, R.id.swNavVault, R.id.swNavMe)
            fun selected(): Set<Int> = ids.filter { info(activity.findViewById(it)).isSelected }.toSet()
            assertEquals("Library starts selected", setOf(R.id.swNavLibrary), selected())
            for (id in ids) {
                val node = info(activity.findViewById(id))
                assertTrue("full label stays in contentDescription", !node.contentDescription.isNullOrBlank())
                assertTrue("className is a stable focus role", !node.className.isNullOrBlank())
            }
            activity.findViewById<View>(R.id.swNavTools).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("selection follows the tab", setOf(R.id.swNavTools), selected())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the caption also carries the judged Hindi word`() {
        val en = java.io.File("src/main/res/values/strings_ux130_l1.xml").readText()
        val hi = java.io.File("src/main/res/values-hi/strings_ux130_l1.xml").readText()
        assertTrue(en.contains("name=\"ux130_l1_scan_caption\">Scan</string>"))
        assertTrue(hi.contains("name=\"ux130_l1_scan_caption\">स्कैन</string>"))
    }
}
