package com.scanwhirl.app.ux130.fixsh

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
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
 * Integration run 44971138753 saw Sh1ReviewFixTest fail once: after create() the root was
 * already a LayerDrawable. The cause is ordering — the async decode was launched inside
 * onCreate, so a fast IO thread plus a pumped main looper could install the photo before
 * create() returned. This test pumps the looper BEFORE resume; the gradient must still be
 * the background. The photo may appear only after onResume.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class WallpaperOrderTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        Prefs.setShowWallpaper(app, false)
        Prefs.setWallpaperUri(app, "")
    }

    private fun root(activity: ShellActivity): View =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test(timeout = 30_000)
    fun `the gradient survives create and any looper pumping until resume`() {
        val uri = Uri.parse("content://fixshwall/p_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ImportFixtures.image().inputStream())
        Prefs.setWallpaperUri(app, uri.toString())
        Prefs.setShowWallpaper(app, true)
        val controller = Robolectric.buildActivity(ShellActivity::class.java).create()
        try {
            repeat(5) { idle() }
            assertTrue(
                "no async wallpaper may replace the shipped gradient before resume",
                root(controller.get()).background is GradientDrawable
            )
            controller.start().resume()
            val deadline = System.currentTimeMillis() + 8_000
            while (root(controller.get()).background !is LayerDrawable &&
                System.currentTimeMillis() < deadline
            ) {
                idle()
                Thread.sleep(20)
            }
            assertTrue("the wallpaper is applied after resume", root(controller.get()).background is LayerDrawable)
        } finally {
            controller.pause().stop().destroy()
            Prefs.setShowWallpaper(app, false)
            Prefs.setWallpaperUri(app, "")
        }
    }
}
