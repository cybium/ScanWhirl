package com.scanwhirl.app.ui.shell

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T32 (phone build 3, DEVICE_BUILD3 minor finding 2): the multi-select bar was part
 * of the scrolling content, so it slid off screen after one scroll, and its bottom
 * overlapped the first row by ~19 dp although the list reserved 92 dp.
 *
 * Now the bar is pinned in the viewport (a sticky header that rides the top edge once
 * the list scrolls past it) and the list reserves the bar's *measured* height, so no
 * row is covered at 100 % or 200 % font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SelectionBarPinTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun scrollOf(activity: ShellActivity): ScrollView {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        return host.getChildAt(0) as ScrollView
    }

    private fun firstCard(activity: ShellActivity): DocCard =
        activity.findViewById<ViewGroup>(R.id.swDocList).getChildAt(0) as DocCard

    private fun layout(activity: ShellActivity) {
        val decor = activity.window.decorView
        val dm = activity.resources.displayMetrics
        repeat(3) {
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
            idle()
        }
    }

    private fun rectInWindow(view: View): Rect {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    /** Waits for [rows] cards, then long-presses the first one to enter selection. */
    private fun waitForRows(activity: ShellActivity, rows: Int) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline &&
            activity.findViewById<ViewGroup>(R.id.swDocList).childCount < rows
        ) {
            idle()
            Thread.sleep(20)
        }
    }

    private fun enterSelection(activity: ShellActivity, rows: Int): View {
        waitForRows(activity, rows)
        firstCard(activity).performLongClick()
        idle()
        layout(activity)
        val bar = activity.findViewById<View>(R.id.swSelectionBar)
        assertTrue("the selection bar is up in selection mode", bar.isVisible && bar.isShown)
        return bar
    }

    private fun assertNoRowCovered(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        try {
            DocFixtures.saved(repo, app, pages = 1, name = "Bar one")
            DocFixtures.saved(repo, app, pages = 1, name = "Bar two")
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    val bar = enterSelection(activity, rows = 2)
                    val barRect = rectInWindow(bar)
                    val rowRect = rectInWindow(firstCard(activity))
                    assertTrue(
                        "at ${scale}x the bar bottom (${barRect.bottom}) covers the first row top " +
                            "(${rowRect.top}); the list must reserve the bar's measured height",
                        barRect.bottom <= rowRect.top
                    )
                    assertTrue(
                        "at ${scale}x the bar itself must be on screen (top ${barRect.top})",
                        barRect.top >= 0 && barRect.height() > 0
                    )
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    @Test
    fun `the selection bar never covers the first row at 1 point 0`() {
        assertNoRowCovered(1.0f)
    }

    @Test
    fun `the selection bar never covers the first row at 2 point 0`() {
        assertNoRowCovered(2.0f)
    }

    @Test
    fun `the selection bar stays in the viewport after the library scrolls`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Bar one")
        DocFixtures.saved(repo, app, pages = 1, name = "Bar two")
        repeat(10) { i -> DocFixtures.saved(repo, app, pages = 1, name = "Filler $i") }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val bar = enterSelection(activity, rows = 12)
                val scroll = scrollOf(activity)
                val viewportTop = rectInWindow(scroll).top + scroll.paddingTop
                val before = rectInWindow(bar)

                scroll.scrollTo(0, 10_000) // clamped to the content's real end
                idle()
                layout(activity)

                val after = rectInWindow(bar)
                assertTrue(
                    "the test needs a real scroll to prove the pin (scrollY=${scroll.scrollY})",
                    scroll.scrollY > before.top + 10
                )
                assertTrue(
                    "the bar must stay pinned at the top of the viewport (it was at " +
                        "${before.top}px; after scrolling ${scroll.scrollY}px it sits at " +
                        "${after.top}px, the viewport starts at $viewportTop)",
                    after.top >= viewportTop - 2 && after.top <= viewportTop + 2
                )
                assertTrue(
                    "the bar is still fully visible after scrolling",
                    after.height() > 0 && after.bottom <= rectInWindow(scroll).bottom + 1
                )
            }
        }
    }
}
