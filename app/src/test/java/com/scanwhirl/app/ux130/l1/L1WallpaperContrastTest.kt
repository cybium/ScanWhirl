package com.scanwhirl.app.ux130.l1

import android.app.Application
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * G3 (UX regrade) — over a worst-case white wallpaper photo, exposed library body/meta
 * text must reach 4.5:1 and large/UI text 3:1. SH1's scrim strength is already pinned by
 * an existing test, so this lane puts the exposed text on the app's near-opaque glass
 * surface, the same surface its cards/search field use. RED on base: these rows were
 * transparent over the 1.96:1 / 3.03:1 wallpaper composite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class L1WallpaperContrastTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setShowGreeting(app, false)
        Prefs.setIndexBackfillDone(app, true)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    }

    private fun luminance(argb: Int): Double {
        fun linear(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(Color.red(argb)) +
            0.7152 * linear(Color.green(argb)) +
            0.0722 * linear(Color.blue(argb))
    }

    /** Alpha-composite the glass surface over pure white, the worst-case photo. */
    private fun surfaceOverWhite(surface: Int): Int {
        val a = Color.alpha(surface) / 255.0
        fun mix(channel: (Int) -> Int) = (a * channel(surface) + (1 - a) * 255).toInt().coerceIn(0, 255)
        return Color.rgb(mix(Color::red), mix(Color::green), mix(Color::blue))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test(timeout = 30_000)
    fun `dim mid and cyan text clear the bar on the glass surface over white`() {
        val surface = surfaceOverWhite(app.getColor(R.color.glass_surface))
        val dim = app.getColor(R.color.text_hologram_dim)
        val mid = app.getColor(R.color.text_hologram_mid)
        val cyan = app.getColor(R.color.cyan_primary)
        val cDim = contrast(dim, surface)
        val cMid = contrast(mid, surface)
        val cCyan = contrast(cyan, surface)
        assertTrue("dim body text is %.2f:1".format(cDim), cDim >= 4.5)
        assertTrue("mid metadata is %.2f:1".format(cMid), cMid >= 4.5)
        assertTrue("cyan UI/large text is %.2f:1".format(cCyan), cCyan >= 3.0)
        // Review P1: the new search helper (mid, 12sp) and result status (hi, 12.5sp)
        // must both clear the 4.5:1 body/metadata bar on their own readable surfaces.
        val helperColor = app.getColor(R.color.text_hologram_mid)
        val statusColor = app.getColor(R.color.text_hologram_hi)
        val cHelper = contrast(helperColor, surface)
        val cStatus = contrast(statusColor, surface)
        assertTrue("search helper is %.2f:1".format(cHelper), cHelper >= 4.5)
        assertTrue("result status is %.2f:1".format(cStatus), cStatus >= 4.5)
        // The old transparent composite really was below the bar (the test proves something).
        assertTrue("baseline dim over the bare scrim was ~2:1", contrast(dim, Color.rgb(98, 101, 103)) < 4.5)
    }

    @Test(timeout = 30_000)
    fun `every exposed library text row carries a readable surface`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val host = activity.findViewById<ViewGroup>(R.id.swTabContent).getChildAt(0) as ViewGroup
            val lib = host.getChildAt(0) as LibraryScreen
            // Count row, no-match row, active-folder sentence, quick-tools header and
            // the Recent/sort header are the rows that previously sat straight on the wallpaper.
            val rows = listOf<View>(
                activity.findViewById<View>(R.id.swDocCount).parent as View,
                activity.findViewById<View>(R.id.swNoMatches),
                activity.findViewById<View>(R.id.swActiveFilter),
                activity.findViewById<View>(R.id.swQuickAll).parent as View,
                activity.findViewById<View>(R.id.swSort).parent as View,
                activity.findViewById<View>(R.id.swHomeSearch).parent.parent as View, // search wrap
                // Council §7: the helper line became the field's placeholder, and the
                // sort-dependent heading is now its own exposed text row.
                activity.findViewById<View>(R.id.cbDocHeading),
                activity.findViewById<View>(R.id.uxL1SearchStatus)
            )
            for ((index, row) in rows.withIndex()) {
                assertNotNull("row $index has a surface", row.background)
            }
            val surface = surfaceOverWhite(app.getColor(R.color.glass_surface))
            for ((id, color) in listOf(
                R.id.cbDocHeading to app.getColor(R.color.text_hologram_hi),
                R.id.uxL1SearchStatus to app.getColor(R.color.text_hologram_hi)
            )) {
                val c = contrast(color, surface)
                assertTrue("search line $id is %.2f:1, needs 4.5:1".format(c), c >= 4.5)
            }
            val src = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/LibraryScreen.kt").readText()
            assertTrue("the band uses the near-opaque glass surface", src.contains("R.color.glass_surface"))
            assertTrue("the band is applied to the exposed rows", src.contains("background = readableBand()"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
