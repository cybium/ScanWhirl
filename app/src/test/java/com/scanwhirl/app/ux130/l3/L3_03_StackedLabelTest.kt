package com.scanwhirl.app.ux130.l3

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L3-03 P1 — at 200% type the setup level rows stack vertically. A vertical
 * LinearLayout gives a child with width 0 + weight 1 no width at all, so the
 * label must use MATCH_PARENT there. EN and HI both checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class L3_03_StackedLabelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun largeFont() {
        val config = Configuration(app.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    @After
    fun resetFont() {
        val config = Configuration(app.resources.configuration).apply { fontScale = 1f }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    private fun labels(activity: ToolSetupActivity): List<TextView> {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = activity.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val found = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.id == R.id.toolSetupLevelLabel) found += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found
    }

    private fun assertVisible() {
        ActivityScenario.launch<ToolSetupActivity>(
            ToolSetupActivity.intent(app, "compress")
        ).use { scenario ->
            scenario.onActivity { activity ->
                val labels = labels(activity)
                assertTrue("four level labels exist", labels.size == 4)
                for (label in labels) {
                    assertTrue(
                        "stacked label has real width: '${label.text}'",
                        label.width > 0 && label.measuredWidth > 0
                    )
                    assertTrue("stacked label keeps its text", label.text.isNotBlank())
                    assertTrue("stacked label is laid out", label.layout != null)
                }
            }
        }
    }

    @Test
    fun `English level labels remain visible at 200 percent`() = assertVisible()

    @Test
    @Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
    fun `Hindi level labels remain visible at 200 percent`() = assertVisible()
}
