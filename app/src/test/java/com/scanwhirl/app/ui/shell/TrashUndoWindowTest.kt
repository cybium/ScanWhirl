package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.TrashActions
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * T32 (phone build 3, DEVICE_BUILD3 minor finding 1): the Trash move's Undo is the
 * safety net for a reversible data action. Two taps at ~3.5-4 s missed a bar that
 * was configured for 6 s, so the safety net is now at least 8 s (and longer when the
 * system "time to take action" accessibility timeout asks for more).
 *
 * This drives the real [TrashActions.showMoved] snackbar and advances the main
 * looper, so it measures the window a finger actually gets - not a constant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrashUndoWindowTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        TrashActions.takePending()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun layoutDecor(activity: android.app.Activity) {
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

    private fun findById(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findById(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    private fun undoAction(activity: android.app.Activity): TextView? =
        findById(
            activity.window.decorView,
            com.google.android.material.R.id.snackbar_action
        ) as? TextView

    @Test
    fun `the Undo window respects the system time to take action setting`() {
        // Never shorter than the 8 s safety net...
        assertTrue(TrashActions.undoDurationMs(null) >= 8_000)
        assertTrue(TrashActions.undoDurationMs(3_000) >= 8_000)
        // ...and longer when AccessibilityManager asks for more.
        assertEquals(20_000, TrashActions.undoDurationMs(20_000))
    }

    @Test
    fun `the Moved bar keeps Undo for at least eight seconds`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Undo window")
        val key = requireNotNull(repo.moveToTrash(id))

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                TrashActions.showMoved(
                    activity,
                    activity.findViewById(android.R.id.content),
                    null,
                    TrashActions.Moved(listOf(key), listOf("Undo window"), 0)
                ) { }
                layoutDecor(activity)
                idle()

                val action = undoAction(activity)
                assertNotNull("the Moved bar with Undo is up", action)
                assertEquals(app.getString(R.string.undo), action!!.text.toString())

                // 7.5 s after the move: on the old 6 s window this was already gone,
                // which is exactly the phone miss the owner reported.
                advance(7_500)
                assertNotNull(
                    "Undo must still be reachable 7.5 s after the move (safety net is 8 s)",
                    undoAction(activity)
                )

                // The timer really runs: the bar is gone by 25 s, so the line above
                // is a real window and not a bar that never expires.
                advance(17_500)
                assertNull("the Moved bar is dismissed after its window", undoAction(activity))
            }
        }
    }

    @Test
    fun `Undo also stays for two documents and lands back in the library`() {
        val one = DocFixtures.saved(repo, app, pages = 1, name = "Undo one")
        val two = DocFixtures.saved(repo, app, pages = 2, name = "Undo two")
        val moved = run {
            val fat = TrashActions.moveAll(repo, listOf(one, two))
            require(fat.keys.size == 2)
            fat
        }

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                TrashActions.showMoved(
                    activity,
                    activity.findViewById(android.R.id.content),
                    null,
                    moved
                ) { }
                layoutDecor(activity)
                idle()

                advance(7_500)
                val action = undoAction(activity)
                assertNotNull("a two-document move also keeps Undo for 7.5 s", action)
                action!!.performClick()
                assertTrue(
                    "Undo brings both documents back",
                    poll { repo.getDocument(one) != null && repo.getDocument(two) != null }
                )
            }
        }
    }

    private fun poll(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }
}
