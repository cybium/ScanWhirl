package com.scanwhirl.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ATLAS-F2 (014_settings_2.0): Settings showed a greyed "Accent / Cyan · Gold coming soon"
 * row. The product rule is that an unbuilt capability is not shown at all, so the row is gone
 * (not merely disabled) and nothing in Settings still promises a future accent.
 *
 * Replaces the H11 test that guarded the coming-soon row: that guard protected the same
 * unbuilt feature this rule now forbids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsAccentFixTextTest {

    private fun textViews(root: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (root is TextView) out += root
        if (root is ViewGroup) for (i in 0 until root.childCount) textViews(root.getChildAt(i), out)
        return out
    }

    @Test
    fun `Settings shows no unbuilt accent row and no coming-soon promise`() {
        ApplicationProvider.getApplicationContext<android.app.Application>()
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertNull(
                "the unbuilt Accent row must not exist in Settings",
                activity.findViewById<View>(R.id.rowAccent)
            )
            assertNull(
                "no coming-soon subtitle is left behind",
                activity.findViewById<View>(R.id.subAccent)
            )
            val promised = textViews(activity.window.decorView)
                .filter { it.isShown }
                .map { it.text?.toString().orEmpty() }
                .filter { it.contains("coming soon", ignoreCase = true) }
            assertFalse(
                "Settings must not announce a feature that does not exist: $promised",
                promised.isNotEmpty()
            )
        }
    }
}
