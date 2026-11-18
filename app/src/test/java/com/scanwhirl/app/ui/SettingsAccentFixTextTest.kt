package com.scanwhirl.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H11 (found on device, 1.2.2): Settings > Accent looked tappable (chevron) but did
 * nothing. It is not wired yet, so the row must not offer a tap: no chevron, no
 * ripple, no focus, and the "coming soon" subtitle stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAccentFixTextTest {

    private fun imagesIn(view: ViewGroup): List<View> {
        val found = mutableListOf<View>()
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is ImageView) found += child
            if (child is ViewGroup) found += imagesIn(child)
        }
        return found
    }

    @Test
    fun `the accent row offers no tap affordance and keeps the coming-soon note`() {
        ApplicationProvider.getApplicationContext<android.app.Application>()
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val row = activity.findViewById<View>(R.id.rowAccent)
            assertNotNull("Settings shows the Accent row", row)
            assertFalse("the row is not clickable", row.isClickable)
            assertFalse("the row cannot take focus", row.isFocusable)
            assertTrue("a not-yet-wired row looks muted", row.alpha < 1f)
            assertNull("no selectable/ripple background", row.background)
            assertFalse(
                "tapping the row does not pretend to do something",
                row.performClick()
            )
            assertEquals(
                "no chevron or other arrow suggests a screen behind it",
                0,
                imagesIn(row as ViewGroup).size
            )

            val subtitle = activity.findViewById<TextView>(R.id.subAccent)
            assertNotNull("the coming-soon subtitle stays", subtitle)
            assertTrue(
                "the subtitle says it is not available yet: ${subtitle.text}",
                subtitle.text.toString().contains("coming soon", ignoreCase = true)
            )
        }
    }
}
