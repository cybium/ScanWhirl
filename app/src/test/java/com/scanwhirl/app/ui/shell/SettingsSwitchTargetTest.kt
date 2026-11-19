package com.scanwhirl.app.ui.shell

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.kit.SwStyle
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T32 (phone build 3, DEVICE_BUILD3 minor finding 4): "Make PDFs searchable"'s switch
 * measured 66 px (~24 dp) tall at 200 % font on the phone - half the 48 dp touch
 * target the other rows keep. The switch is the row's only control, so its own box
 * must be a full target at every font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsSwitchTargetTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
    }

    private fun measureSwitchAt(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val decor = activity.window.decorView
            val dm = activity.resources.displayMetrics
            repeat(2) {
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                )
                decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            val toggle = activity.findViewById<View>(R.id.switchSearchablePdf)
            assertNotNull("the searchable-PDF switch exists", toggle)
            val target = SwStyle.dp(activity, 48f)
            assertTrue(
                "at ${scale}x the searchable-PDF switch is ${toggle.height}px " +
                    "(${toggle.height / activity.resources.displayMetrics.density} dp) tall; " +
                    "a touch target needs ${target}px (48 dp)",
                toggle.height >= target
            )
            assertTrue(
                "at ${scale}x the switch must be visible on the row",
                toggle.isShown && toggle.width >= target
            )
        }
    }

    @Test
    fun `the searchable-PDF switch is a 48 dp target at 200 percent font`() {
        measureSwitchAt(2.0f)
    }

    @Test
    fun `the searchable-PDF switch is a 48 dp target at 100 percent font`() {
        measureSwitchAt(1.0f)
    }
}
