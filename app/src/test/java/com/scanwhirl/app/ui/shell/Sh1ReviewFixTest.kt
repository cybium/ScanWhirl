package com.scanwhirl.app.ui.shell

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * SH1 independent review (blocking 1c7701bd):
 *  P1 the wallpaper photo must be decoded off the main thread, with the default
 *     background held until it is ready;
 *  P2 a translucent scrim keeps shell text readable over a photo, and only exists
 *     when a photo is actually shown;
 *  P2 a re-delivered EXTRA_OPEN_PRO (onNewIntent) must open Pro just like a cold start;
 *  P2 Trash's class doc must not claim nothing is ever purged automatically.
 *
 * Lifecycle note: these tests drive [ActivityController] explicitly and destroy it in a
 * finally block. ActivityScenario.close() waits unbounded for a DESTROYED callback and
 * hangs under Robolectric after a reflective onNewIntent() that opens the Pro screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class Sh1ReviewFixTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val shellText: String by lazy {
        File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
    }
    private val trashText: String by lazy {
        File("src/main/java/com/scanwhirl/app/ui/TrashActivity.kt").readText()
    }

    @Before
    fun setUp() {
        Prefs.setShowWallpaper(app, false)
        Prefs.setWallpaperUri(app, "")
        setProbe(null)
    }

    @After
    fun tearDown() {
        Prefs.setShowWallpaper(app, false)
        Prefs.setWallpaperUri(app, "")
        setProbe(null)
    }

    /** Reflection keeps this test compilable against the blocked base, where the seam is absent. */
    private fun setProbe(probe: (() -> Unit)?) {
        val field = runCatching { ShellActivity::class.java.getDeclaredField("wallpaperProbe") }
            .getOrNull() ?: return
        field.isAccessible = true
        field.set(null, probe)
    }

    private fun stageWallpaper() {
        val uri = Uri.parse("content://sh1wall/p_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ImportFixtures.image().inputStream())
        Prefs.setWallpaperUri(app, uri.toString())
        Prefs.setShowWallpaper(app, true)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun root(activity: android.app.Activity): View =
        (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)

    /** Bounded: never spin longer than 8 s even if the decode never completes. */
    private fun awaitWallpaper(activity: ShellActivity) {
        val deadline = System.currentTimeMillis() + 8_000
        while (root(activity).background !is LayerDrawable && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
    }

    private fun shell(): ActivityController<ShellActivity> =
        Robolectric.buildActivity(ShellActivity::class.java).setup()

    @Test(timeout = 30_000)
    fun `wallpaper is decoded off the main thread and applied under a readable scrim`() {
        stageWallpaper()
        val decodeThread = AtomicReference<Thread>()
        setProbe { decodeThread.set(Thread.currentThread()) }

        // create() only: the main looper has not been idled yet, so the IO decode cannot
        // have been applied. This is the "no flash of a wrong background" check.
        val controller = Robolectric.buildActivity(ShellActivity::class.java).create()
        try {
            assertTrue(
                "the shipped gradient is the first background",
                root(controller.get()).background is GradientDrawable
            )
            controller.start().resume()
            val activity = controller.get()
            awaitWallpaper(activity)
            val background = root(activity).background
            assertTrue("the wallpaper was never applied", background is LayerDrawable)

            val layers = background as LayerDrawable
            assertEquals(2, layers.numberOfLayers)
            assertTrue("the bottom layer is the chosen photo", layers.getDrawable(0) is BitmapDrawable)
            val scrim = layers.getDrawable(1)
            assertTrue("the top layer is a colour scrim", scrim is ColorDrawable)
            val alpha = android.graphics.Color.alpha((scrim as ColorDrawable).color)
            assertTrue("scrim alpha $alpha must be ~60-70 percent", alpha in 153..178)

            assertNotNull("the decode probe never ran", decodeThread.get())
            assertNotSame(
                "BitmapFactory read/decode must not run on the main thread",
                Looper.getMainLooper().thread,
                decodeThread.get()
            )
        } finally {
            controller.pause().stop().destroy()
            setProbe(null)
        }
    }

    @Test(timeout = 30_000)
    fun `no wallpaper means no scrim and the plain gradient stays`() {
        val controller = shell()
        try {
            idle()
            val background = root(controller.get()).background
            assertTrue("without a photo the shell keeps its gradient", background is GradientDrawable)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `onCreate does not decode the wallpaper synchronously`() {
        val onCreate = shellText.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("private fun loadWallpaper")
        assertFalse(
            "onCreate must not call Wallpaper.drawable() on the main thread",
            onCreate.contains("Wallpaper.drawable(")
        )
        assertTrue(
            "the decode must run in a withContext(Dispatchers.IO) block",
            shellText.contains("withContext(Dispatchers.IO)")
        )
    }

    @Test(timeout = 30_000)
    fun `a re-delivered open-pro extra opens the Pro screen`() {
        val controller = shell()
        try {
            val activity = controller.get()
            idle()
            assertNull("Pro is not open on a plain launch", activity.findViewById(R.id.swProScreen))
            // onNewIntent is protected on Activity; invoke the override reflectively.
            val deliver = ShellActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            deliver.isAccessible = true
            deliver.invoke(activity, Intent().putExtra(ShellActivity.EXTRA_OPEN_PRO, true))
            idle()
            assertNotNull(
                "onNewIntent must handle EXTRA_OPEN_PRO exactly like onCreate",
                activity.findViewById(R.id.swProScreen)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `Trash documentation matches expiry on resume`() {
        assertFalse(
            "the class doc still claims nothing is purged automatically",
            trashText.contains("Nothing is purged automatically")
        )
        assertTrue("the doc must name the 30-day expiry", trashText.contains("30-day"))
    }
}
