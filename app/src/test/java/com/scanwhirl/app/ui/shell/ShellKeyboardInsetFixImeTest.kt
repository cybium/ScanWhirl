package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
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
 * Judge finding A6 #17: the shell set a second OnApplyWindowInsetsListener on the
 * root, which replaced the shared helper's listener and padded by the system bar
 * only. With edge-to-edge on S+ the keyboard therefore covered the Home search
 * field and the bottom of every list.
 *
 * One listener must (a) keep the base padding and cutout handling, (b) pad the
 * bottom by max(nav bar, IME), (c) still hide the dock for the keyboard, and
 * (d) return the insets so children receive them too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellKeyboardInsetFixImeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun rootOf(activity: ShellActivity): View =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun dockOf(activity: ShellActivity): View =
        activity.findViewById<View>(R.id.swShellNav).parent as View

    /** systemBars (top 64 / bottom 48), a 90 px display cutout at the top and an IME. */
    private fun insets(imeBottom: Int): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 64, 0, 48))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 90, 0, 0))
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
        .build()

    @Test
    fun `ime 800 over a 48 nav bar pads the content by the ime and hides the dock`() {
        launch { activity ->
            val root = rootOf(activity)
            val dock = dockOf(activity)

            val result = ViewCompat.dispatchApplyWindowInsets(root, insets(imeBottom = 800))
            idle()

            assertEquals(
                "the shell keeps the cutout/status bar at the top",
                90,
                root.paddingTop
            )
            assertEquals(
                "content sits above the keyboard, not just above the nav bar",
                800,
                root.paddingBottom
            )
            assertFalse("the dock hides while the keyboard is up", dock.isVisible)
            assertEquals(
                "the insets stay available to the children",
                800,
                result.getInsets(WindowInsetsCompat.Type.ime()).bottom
            )
        }
    }

    @Test
    fun `without the ime the nav bar pads the content and the dock is visible`() {
        launch { activity ->
            val root = rootOf(activity)
            val dock = dockOf(activity)

            ViewCompat.dispatchApplyWindowInsets(root, insets(imeBottom = 0))
            idle()

            assertEquals("the nav bar gives the bottom padding", 48, root.paddingBottom)
            assertTrue("the dock is back without the keyboard", dock.isVisible)
            assertTrue(
                "the 90 px cutout wins over the 64 px status bar",
                root.paddingTop >= 90
            )
        }
    }

    @Test
    fun `the helper adds insets on top of the view's own padding`() {
        launch { activity ->
            val view = View(activity).apply { setPadding(3, 5, 7, 9) }
            view.applySystemBarsPadding()

            ViewCompat.dispatchApplyWindowInsets(view, insets(imeBottom = 800))

            assertEquals("left base padding is kept", 3, view.paddingLeft)
            assertEquals("top = base + cutout", 5 + 90, view.paddingTop)
            assertEquals("right base padding is kept", 7, view.paddingRight)
            assertEquals("bottom = base + ime", 9 + 800, view.paddingBottom)
        }
    }
}
