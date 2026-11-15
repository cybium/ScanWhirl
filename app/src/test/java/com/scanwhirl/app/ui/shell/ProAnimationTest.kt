package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.kit.SwMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * v2 UI: 74_motion — the Pro entrance is logo turn + card cascade only, starts
 * after the first layout, and leaves the final state at once when motion is off.
 * Also proves the 8 Pro tool buttons fit a 1080x2460 phone without scrolling.
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

    private fun measure(activity: Activity, screen: ProScreen, heightDp: Int = 820) {
        val density = activity.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (heightDp * density).toInt()
        screen.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        screen.layout(0, 0, width, height)
    }

    @Test
    fun `the entrance plays when motion is on and settles`() {
        SwMotion.animatorOverride = true
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        measure(activity, screen)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue("the entrance started", screen.entrancePlayedForTest())
        assertEquals("the title ends fully opaque", 1f, screen.titleForTest().alpha, 0.01f)
        assertEquals("the title ends in place", 0f, screen.titleForTest().translationY, 0.01f)
        controller.pause().stop().destroy()
    }

    @Test
    fun `the entrance is logo then title then cards, and nothing else`() {
        SwMotion.animatorOverride = true
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        measure(activity, screen)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(
            listOf("rings", "title", "cascade"),
            screen.entranceSteps
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `the eight Pro tool buttons fit a 1080x2460 phone without scrolling`() {
        SwMotion.animatorOverride = false
        val controller = controller()
        val activity = controller.get()
        val screen = pro(activity)
        activity.setContentView(screen)
        controller.visible()
        measure(activity, screen, heightDp = 820)
        shadowOf(Looper.getMainLooper()).idle()

        val cells = screen.featureCellsForTest()
        assertEquals("8 Pro tools in 4 rows", 8, cells.size)
        val limit = (820 * activity.resources.displayMetrics.density).toInt()
        assertTrue(
            "the last tool row is on the first screen (bottom ${cells.last().bottom} of $limit)",
            cells.last().bottom in 1..limit
        )
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
        measure(activity, screen)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("nothing moved", 1f, screen.titleForTest().alpha, 0.01f)
        assertEquals("the entrance never started", emptyList<String>(), screen.entranceSteps)
        assertTrue(screen.buyButton.scaleX == 1f)
        controller.pause().stop().destroy()
    }

    @Test
    fun `an active Pro build has no price or buy controls`() {
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
        assertNull("no price card", screen.priceWrapForTest().parent)
        assertNull("no buy button", screen.buyWrapForTest().parent)
        controller.pause().stop().destroy()
    }
}
