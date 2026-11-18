package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H13 (found on device, 1.2.2): Merge with one document showed a Continue button that
 * looked ready and did nothing (the message existed only after the tap). The button is
 * now visibly disabled until two documents are picked, and enabled at two.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolSetupContinueFixTextTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val repo = ScanRepository(app)

    @Before
    fun seed() {
        DocFixtures.saved(repo, app, pages = 1, name = "First document")
        DocFixtures.saved(repo, app, pages = 1, name = "Second document")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun rows(activity: Activity): List<View> {
        val host = activity.findViewById<LinearLayout>(R.id.toolSetupSource)
        return (0 until host.childCount).map { host.getChildAt(it) }
            .filter { it.id == R.id.toolSetupDocRow }
    }

    @Test
    fun `merge Continue is visibly disabled until two documents are picked`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "merge")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "the library is listed; rows=${rows(activity).size}",
                    waitUntil { rows(activity).size >= 2 }
                )
                val run = activity.findViewById<View>(R.id.toolSetupRun)
                assertFalse("one document is not enough for Merge", run.isEnabled)
                assertFalse("a disabled Continue is not clickable", run.isClickable)
                assertEquals(
                    "a disabled Continue is visibly greyed",
                    0.38f,
                    run.alpha,
                    0.01f
                )

                rows(activity)[1].performClick()
                idle()
                val ready = activity.findViewById<View>(R.id.toolSetupRun)
                assertTrue("two documents enable Continue", ready.isEnabled)
                assertTrue("the enabled Continue is clickable again", ready.isClickable)
                assertEquals("the button returns to full strength", 1f, ready.alpha, 0.01f)
            }
        }
    }

    @Test
    fun `single document tools keep an enabled Continue`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library is listed", waitUntil { rows(activity).isNotEmpty() })
                val run = activity.findViewById<View>(R.id.toolSetupRun)
                assertTrue("one document is enough for Split", run.isEnabled)
            }
        }
    }
}
