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

    @Test
    fun `the price and Unlock bar is pinned and visible without scrolling`() {
        SwMotion.animatorOverride = false
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        val width = (360 * activity.resources.displayMetrics.density).toInt()
        val height = (640 * activity.resources.displayMetrics.density).toInt()
        screen.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        )
        screen.layout(0, 0, width, height)

        assertTrue("the Unlock button is on screen", screen.buyButton.isShown)
        assertTrue("the Unlock button has a real size", screen.buyButton.height > 0)
        assertTrue(
            "the bar never falls below the 640dp viewport",
            screen.buyButton.bottom <= height
        )
        assertEquals(
            "the price is on the same pinned bar",
            screen.buyWrapForTest().parent,
            screen.priceWrapForTest().parent
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `the entrance follows the owner order and the shine is clipped to the button`() {
        SwMotion.animatorOverride = true
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        val width = (360 * activity.resources.displayMetrics.density).toInt()
        val height = (640 * activity.resources.displayMetrics.density).toInt()
        screen.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        )
        screen.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(
            "rings -> title -> cascade -> shine -> pulse",
            listOf("rings", "title", "cascade", "shine", "pulse"),
            screen.entranceSteps
        )
        assertTrue("the shine never leaves the button bounds", screen.buyWrapForTest().clipChildren)
        assertTrue("rounded clipping", screen.buyWrapForTest().clipToOutline)
        assertEquals(
            "the shine view is gone after one sweep",
            1,
            screen.buyWrapForTest().childCount
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `the pinned bar does not move between the first and last frame`() {
        SwMotion.animatorOverride = true
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        val width = (360 * activity.resources.displayMetrics.density).toInt()
        val height = (640 * activity.resources.displayMetrics.density).toInt()
        screen.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
        )
        screen.layout(0, 0, width, height)
        fun absoluteTop(view: android.view.View): Int {
            var y = 0
            var node: android.view.View? = view
            while (node != null) {
                y += node.top
                node = node.parent as? android.view.View
            }
            return y
        }
        val firstTop = absoluteTop(screen.buyWrapForTest())
        val firstLeft = screen.buyWrapForTest().left
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("bar top is stable", firstTop, absoluteTop(screen.buyWrapForTest()))
        assertEquals("bar left is stable", firstLeft, screen.buyWrapForTest().left)
        assertTrue("the bar is at the bottom", firstTop > height / 2)
        controller.pause().stop().destroy()
    }

    @Test
    fun `an active Pro build has no buy bar`() {
        SwMotion.animatorOverride = false
        val controller = controller()
        val activity = controller.get()
        val screen = ProScreen(activity, object : ProScreen.Callbacks {
            override fun onClose() = Unit
            override fun onBuy() = Unit
            override fun onRestore() = Unit
            override fun onSeeAllProTools() = Unit
        }, active = true)
        activity.setContentView(screen)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no price card", null, screen.priceWrapForTest().parent)
        assertEquals("no buy button", null, screen.buyWrapForTest().parent)
        controller.pause().stop().destroy()
    }
}
