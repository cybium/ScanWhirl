package com.scanwhirl.app.tools

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.After
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
import org.robolectric.shadows.ShadowToast

/**
 * S1 defect 2: GalleryCrop keyed its pending callbacks by Activity instance. After a rotation
 * the result arrives on the recreated activity, the lookup misses, and the picked/cropped pages
 * are silently dropped. The request must survive via saved state and be re-attached; a result
 * that truly has no surviving request must say so instead of vanishing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S1GalleryCropRotationTest {

    @Before
    fun setUp() {
        GalleryCrop.startOverride = null
        GalleryCrop.pickOverride = null
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        GalleryCrop.startOverride = null
        GalleryCrop.pickOverride = null
        ShadowToast.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `cropped pages delivered after rotation still reach the waiting screen`() {
        var got: List<Uri> = emptyList()
        GalleryCrop.startOverride = { true }
        var rotated: ShellActivity? = null
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                launchGalleryCrop(activity, 30, onResult = { got = it })
                assertEquals("a request is waiting", 1, GalleryCrop.pendingCountForTest())
            }
            scenario.recreate()
            idle()
            scenario.onActivity { rotated = it }
            val current = requireNotNull(rotated)
            GalleryCrop.deliver(
                current,
                listOf(Uri.parse("content://crop/after_rotation_1.jpg"), Uri.parse("content://crop/after_rotation_2.jpg"))
            )
            idle()
            assertEquals("both cropped pages survive the rotation", 2, got.size)
            assertEquals("content://crop/after_rotation_2.jpg", got.last().toString())
            assertEquals("the request is consumed", 0, GalleryCrop.pendingCountForTest())
        }
    }

    @Test
    fun `a fallback result delivered after rotation still reaches the waiting screen`() {
        var fallback: List<Uri> = emptyList()
        GalleryCrop.startOverride = { false }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                launchGalleryCrop(activity, 30, onResult = {}, onFallback = { fallback = it })
            }
            scenario.recreate()
            idle()
            var current: ShellActivity? = null
            scenario.onActivity { current = it }
            GalleryCrop.deliverFallback(requireNotNull(current), listOf(Uri.parse("content://raw/after_rotation.jpg")))
            idle()
            assertEquals("the raw photo survives the rotation", 1, fallback.size)
        }
    }

    @Test
    fun `a result with no surviving request shows a message instead of being dropped`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                GalleryCrop.unregister(activity) // nothing launched: no pending request at all.
                ShadowToast.reset()
                GalleryCrop.deliver(activity, listOf(Uri.parse("content://crop/lost.jpg")))
                idle()
                val toast = ShadowToast.getTextOfLatestToast()
                assertNotNull("a lost result must not be silent", toast)
                assertTrue("the message is not an empty toast", toast!!.isNotBlank())
            }
        }
    }
}
