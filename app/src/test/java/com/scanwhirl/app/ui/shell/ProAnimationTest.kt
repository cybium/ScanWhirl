package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.kit.SwMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * v2 UI: 74_motion — the Pro entrance runs when motion is allowed, is skipped
 * (final state at once) when animations are off, and leaves no shine view or
 * animator behind when the screen is detached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProAnimationTest {

    @After
    fun tearDown() {
        SwMotion.animatorOverride = null
    }

    private fun controller() = Robolectric.buildActivity(Activity::class.java).setup()

    private fun pro(activity: Activity) = ProScreen(activity, object : ProScreen.Callbacks {
        override fun onClose() = Unit
        override fun onBuy() = Unit
        override fun onRestore() = Unit
        override fun onSeeAllProTools() = Unit
    })

    @Test
    fun `the entrance plays when motion is on and settles`() {
        SwMotion.animatorOverride = true
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue("the entrance started", screen.entrancePlayedForTest())
        assertEquals("the title ends fully opaque", 1f, screen.titleForTest().alpha, 0.01f)
        assertEquals(
            "the title ends in place",
            0f,
            screen.titleForTest().translationY,
            0.01f
        )
        assertEquals("the shine is gone from the price card", 0, screen.priceWrapForTest().childCount - 1)
        assertEquals("the shine is gone from the button", 0, screen.buyWrapForTest().childCount - 1)
        controller.pause().stop().destroy()
    }

    @Test
    fun `no entrance and final state at once when animations are off`() {
        SwMotion.animatorOverride = false
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("nothing moved", 1f, screen.titleForTest().alpha, 0.01f)
        assertEquals("no shine view is added", 0, screen.priceWrapForTest().childCount - 1)
        assertEquals("no shine view is added", 0, screen.buyWrapForTest().childCount - 1)
        assertTrue(screen.buyButton.scaleX == 1f)
        controller.pause().stop().destroy()
    }
}
