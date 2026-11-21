package com.scanwhirl.app.tools

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.BaseActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tiny real Activity that records which instance handled a crop result. */
class S1CropReceiverActivity : BaseActivity() {
    var receivedUris: List<Uri>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "crop receiver" })
    }
}

/**
 * S1 review P0: a crop result delivered after a configuration change must be handled by the
 * RECREATED activity, never by the destroyed one whose window, views and lifecycleScope are gone.
 * These tests drive real ActivityControllers and the real GalleryCrop deliver path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S1GalleryCropReceiverTest {

    @Before
    fun setUp() {
        GalleryCrop.startOverride = { true }
        GalleryCrop.pickOverride = null
    }

    @After
    fun tearDown() {
        GalleryCrop.startOverride = null
        GalleryCrop.pickOverride = null
    }

    private fun idle() = shadowOf(
        ApplicationProvider.getApplicationContext<android.app.Application>().mainLooper
    ).idle()

    @Test
    fun `the recreated activity handles the crop result and the destroyed one is never touched`() {
        val controller = Robolectric.buildActivity(S1CropReceiverActivity::class.java).setup()
        val first = controller.get()
        launchGalleryCrop(first, 30, onResult = { uris -> first.receivedUris = uris })
        val state = Bundle()
        controller.saveInstanceState(state)
        controller.pause().stop().destroy()
        val second = Robolectric.buildActivity(S1CropReceiverActivity::class.java).setup(state).get()
        idle()

        GalleryCrop.deliver(second, listOf(Uri.parse("content://crop/receiver.jpg")))
        idle()

        assertNull("the destroyed activity must not receive the pages", first.receivedUris)
        assertNotNull("the recreated activity handles the pages", second.receivedUris)
        assertEquals(1, second.receivedUris!!.size)
    }

    @Test
    fun `the picker fallback after rotation is not delivered to the destroyed activity`() {
        GalleryCrop.startOverride = { false }
        val controller = Robolectric.buildActivity(S1CropReceiverActivity::class.java).setup()
        val first = controller.get()
        var fallbackOnA = false
        GalleryCrop.pickPhotosOnly(first) { fallbackOnA = true }
        val state = Bundle()
        controller.saveInstanceState(state)
        controller.pause().stop().destroy()
        val second = Robolectric.buildActivity(S1CropReceiverActivity::class.java).setup(state).get()
        idle()

        GalleryCrop.deliverFallback(second, listOf(Uri.parse("content://raw/receiver.jpg")))
        idle()

        assertFalse("the destroyed activity must not get the fallback", fallbackOnA)
    }
}
