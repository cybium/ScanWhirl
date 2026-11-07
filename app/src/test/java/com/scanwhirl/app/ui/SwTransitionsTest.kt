package com.scanwhirl.app.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * test25 Phase 4: window transitions for the Globe↔Shelf warp and the
 * scan-launch reveal. Robolectric records `overridePendingTransition` in
 * `ShadowActivity`, so the exact anim resource ids are assertable; the reveal
 * is gated off under Robolectric, which is what makes the launch synchronous.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SwTransitionsTest {

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        Prefs.setGlobeHomeEnabled(app, true)
    }

    @Test
    fun `pinch-in warps to the shelf`() {
        ActivityScenario.launch(com.scanwhirl.app.ui.globe.GlobeHomeActivity::class.java)
            .use { scenario ->
                scenario.onActivity { activity ->
                    activity.onPinchToShelf()

                    val next = shadowOf(activity).nextStartedActivity
                    assertNotNull("pinch must open the Shelf", next)

                    val shadow = shadowOf(activity)
                    assertEquals(
                        "Shelf must warp in",
                        R.anim.shelf_enter_warp,
                        shadow.pendingTransitionEnterAnimationResourceId
                    )
                    assertEquals(
                        "Globe must warp out",
                        R.anim.globe_exit_warp,
                        shadow.pendingTransitionExitAnimationResourceId
                    )
                }
            }
    }

    @Test
    fun `list button warps to the shelf`() {
        ActivityScenario.launch(com.scanwhirl.app.ui.globe.GlobeHomeActivity::class.java)
            .use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.btnGlobeList).performClick()

                    val next = shadowOf(activity).nextStartedActivity
                    assertNotNull("List must open the Shelf", next)

                    val shadow = shadowOf(activity)
                    assertEquals(
                        R.anim.shelf_enter_warp,
                        shadow.pendingTransitionEnterAnimationResourceId
                    )
                    assertEquals(
                        R.anim.globe_exit_warp,
                        shadow.pendingTransitionExitAnimationResourceId
                    )
                }
            }
    }

    @Test
    fun `scan button launches immediately with the wash hidden`() {
        ActivityScenario.launch(com.scanwhirl.app.ui.globe.GlobeHomeActivity::class.java)
            .use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.fabGlobeScan).performClick()

                    // Ambient motion is off under Robolectric → the reveal
                    // must fall through synchronously (same-frame launch).
                    val next = shadowOf(activity).nextStartedActivity
                    assertNotNull("scan must launch the Shelf in the same frame", next)
                    assertEquals(MainActivity::class.java.name, next.component?.className)
                    assertTrue(
                        "scan flag must be set",
                        next.getBooleanExtra(MainActivity.EXTRA_OPEN_SCANNER, false)
                    )

                    val wash = activity.findViewById<android.view.View>(R.id.scanReveal)
                    assertEquals(
                        "the reveal wash must never show when gated off",
                        android.view.View.GONE,
                        wash.visibility
                    )
                }
            }
    }

    // test26 T3.9: "finishing the shelf warps back to the globe" was deleted
    // here — MainActivity.finish() no longer overrides (Home is the launcher;
    // the globe exits with the app). The no-warp case below still holds.

    @Test
    fun `finishing the shelf without the globe preference plays no warp`() {
        Prefs.setGlobeHomeEnabled(app, false)
        try {
            val controller = org.robolectric.Robolectric
                .buildActivity(MainActivity::class.java)
                .create()
                .start()
                .resume()
            val activity = controller.get()

            activity.finish()

            val shadow = shadowOf(activity)
            // ShadowActivity's default for "never called" is -1, not 0.
            assertEquals(
                "no globe below → no warp",
                -1,
                shadow.pendingTransitionEnterAnimationResourceId
            )
            assertEquals(-1, shadow.pendingTransitionExitAnimationResourceId)
            controller.destroy()
        } finally {
            Prefs.setGlobeHomeEnabled(app, true)
        }
    }
}
