package com.scanwhirl.app.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * C04: the process-wide gallery-crop bridge must not retain a destroyed Activity (its Host and
 * Pending lambdas capture the screen, so a WeakHashMap key alone does not help).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GalleryCropLeakTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        GalleryCrop.startOverride = { true }
        GalleryCrop.pickOverride = {}
    }

    @After
    fun tearDown() {
        GalleryCrop.startOverride = null
        GalleryCrop.pickOverride = null
    }

    @Test
    fun `a destroyed screen leaves no host or pending callback behind`() {
        val before = GalleryCrop.hostCountForTest()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(
            "every BaseActivity registers its crop host once",
            before + 1,
            GalleryCrop.hostCountForTest()
        )

        GalleryCrop.launch(activity, maxPages = 30, onCropped = {}, onFallback = {})
        assertEquals("the launched screen is waiting for a result", 1, GalleryCrop.pendingCountForTest())

        controller.pause().stop().destroy()

        assertEquals(
            "a destroyed screen must not stay in the crop bridge",
            before,
            GalleryCrop.hostCountForTest()
        )
        assertEquals(
            "abandoning the scanner must not keep the callbacks (and the activity) alive",
            0,
            GalleryCrop.pendingCountForTest()
        )
    }
}
