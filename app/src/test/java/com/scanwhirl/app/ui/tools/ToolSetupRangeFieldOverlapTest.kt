package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real-phone defect (run4_2a711443, Split): the page-range input sat behind the pinned Continue
 * button at 100% font. The fix is a scrollable clearance child, not bottom padding, plus
 * adjustResize and focus-time scrolling. This test proves no overlap with the keyboard closed
 * and open, at 360 and 411 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolSetupRangeFieldOverlapTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 1, name = "Range source")
        ProGate.setTestOverride(app, true)
    }

    @After
    fun tearDown() = ProGate.setTestOverride(app, null)

    private fun open(): ActivityController<ToolSetupActivity> {
        val controller = Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "split")
        ).setup()
        idle()
        return controller
    }

    /** Explicit layout pass: Robolectric does not measure on its own. */
    private fun layout(activity: Activity, widthDp: Int, heightDp: Int) {
        val density = activity.resources.displayMetrics.density
        val root = activity.findViewById<View>(android.R.id.content)
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun boundsIn(root: View, view: View): Rect {
        var x = 0
        var y = 0
        var current: View? = view
        while (current != null) {
            x += current.left
            y += current.top
            if (current === root) break
            current = current.parent as? View
        }
        return Rect(x, y, x + view.width, y + view.height)
    }

    private fun findByTag(root: View?, tag: String): View? {
        if (root == null) return null
        if (root.tag == tag) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findByTag(root.getChildAt(i), tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun assertRangeClear(activity: ToolSetupActivity, heightDp: Int) {
        val root = activity.findViewById<View>(android.R.id.content)
        layout(activity, 360, heightDp)
        val scroll = activity.findViewById<ScrollView>(R.id.toolSetupScroll)
        val field = activity.findViewById<EditText>(R.id.toolSetupRanges)
        val run = activity.findViewById<View>(R.id.toolSetupRun)
        val bar = activity.findViewById<View>(R.id.toolSetupBar)
        assertNotNull("the range field exists", field)
        assertNotNull("Continue is pinned", run)

        scroll.scrollTo(0, Int.MAX_VALUE)
        val fieldRect = boundsIn(root, field!!).apply { offset(0, -scroll.scrollY) }
        val runRect = boundsIn(root, run!!)
        val barRect = boundsIn(root, bar!!)
        assertFalse(
            "range field (${fieldRect}) must not overlap Continue (${runRect})",
            Rect.intersects(fieldRect, runRect)
        )
        assertTrue(
            "range field bottom ${fieldRect.bottom} must be above bar top ${barRect.top}",
            fieldRect.bottom <= barRect.top
        )
        assertTrue("the field is still on screen", fieldRect.top >= 0)

        val spacer = findByTag(root, ToolSetupActivity.BOTTOM_SPACER_TAG)
        assertNotNull("scrollable clearance exists", spacer)
        assertTrue(
            "clearance ${spacer!!.height} >= bar ${bar.height}",
            spacer.height >= bar.height
        )
        assertTrue(
            "IME resizes instead of panning",
            (activity.window.attributes.softInputMode and
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) != 0
        )
    }

    @Test(timeout = 30_000)
    @Config(sdk = [35], qualifiers = "w360dp")
    fun `360dp range field clears Continue with keyboard closed and open`() {
        val controller = open()
        try {
            assertRangeClear(controller.get(), 780)
            assertRangeClear(controller.get(), 480) // ~300dp IME at 100% font
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    @Config(sdk = [35], qualifiers = "w411dp")
    fun `411dp range field clears Continue with keyboard closed and open`() {
        val controller = open()
        try {
            assertRangeClear(controller.get(), 780)
            assertRangeClear(controller.get(), 480)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
