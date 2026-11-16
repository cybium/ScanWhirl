package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 06_pro — "See all N Pro tools" is copy generated from the catalogue, not a literal:
 * N is the number of Pro tools that are on sale (a Pro tool still tagged Soon is not), and it
 * matches the PRO tags a Free user sees in the hub that the button opens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProCountTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the See all label counts the Pro tools on sale`() {
        val expected = ToolRegistry.proCount()
        assertEquals(
            "one Pro tool in the catalogue is only Coming soon, so it is not for sale",
            ToolRegistry.all.count { it.pro } - 1,
            expected
        )

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.showPro()
                idle()
                val more = activity.findViewById<TextView>(R.id.swProMore)
                assertEquals(
                    "the label is generated from the registry",
                    activity.getString(R.string.pro_more_tools, expected),
                    more.text.toString()
                )

                more.performClick()
                idle()
                val badges = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                    .count { it.proTag.visibility == View.VISIBLE }
                assertEquals(
                    "the advertised number matches the PRO tags in the hub",
                    expected,
                    badges
                )
            }
        }
    }
}
