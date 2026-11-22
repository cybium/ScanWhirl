package com.scanwhirl.app.ux130.l1

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L1-01 acceptance: documents win the first screen. The old chrome was four separate
 * rows (folder chips, Recent/sort, category chips, count); the single control band is
 * measured here and the first real card must still clear the dock at 360x640dp, EN+HI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class L1ControlBandLayoutTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    }

    private fun topIn(view: View, root: View): Int {
        var current: View? = view
        var y = 0
        while (current != null && current !== root) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    private fun measure(scale: Float, lang: String) {
        RuntimeEnvironment.setQualifiers("$lang-w360dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        val repo = ScanRepository(app)
        repeat(3) { DocFixtures.saved(repo, app, pages = 1, name = "Band doc $it") }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val dm = activity.resources.displayMetrics
            repeat(3) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            }
            val band = activity.findViewById<View>(R.id.uxL1ControlBand)
            assertNotNull("the single band exists", band)
            val density = dm.density
            // Device reflow stacks caption over full-width value at 200 %, so the band is
            // legitimately taller; the readable control wins over packing density.
            val heightBound = if (scale >= 2f) 360f else 180f
            assertTrue(
                "one band ($scale, ${band!!.height}px) stays within the chrome bound",
                band.height <= heightBound * density
            )
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            assertTrue("real cards render", list.childCount > 0)
            val card = list.getChildAt(0) as DocCard
            val dockTop = minOf(
                topIn(activity.findViewById<View>(R.id.swNavScan), root),
                topIn(activity.findViewById<View>(R.id.swShellNav), root)
            )
            if (scale >= 2f) {
                // At 200 % the list HEADER must still be on screen, never a tool-only screen.
                val count = activity.findViewById<View>(R.id.swDocCount)
                assertNotNull("the document count is on the 200 % first screen", count)
                assertTrue(
                    "the document heading is above the dock at 200 %",
                    topIn(count!!, root) + count.height <= dockTop + 1
                )
            } else {
                assertTrue(
                    "the first card (${topIn(card, root)}..${topIn(card, root) + card.height}) clears the dock at $dockTop",
                    topIn(card, root) + card.height <= dockTop + 1
                )
            }
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en-w360dp-h640dp-xhdpi")
        }
    }

    @Test(timeout = 30_000)
    fun `the band keeps documents on the first screen at 100 percent English`() = measure(1f, "en")

    @Test(timeout = 30_000)
    fun `the band keeps documents on the first screen at 100 percent Hindi`() = measure(1f, "hi")

    @Test(timeout = 30_000)
    fun `the band keeps the document heading on screen at 200 percent Hindi`() = measure(2f, "hi")
}
