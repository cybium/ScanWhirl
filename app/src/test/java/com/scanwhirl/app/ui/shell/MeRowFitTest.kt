package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATLAS-F2 (004_me_2.0): the Trash row's wrapped summary ("Deleted documents, / 30 days")
 * was drawn past the card's bottom edge, so the second line was sliced by the card border.
 * MeRecoveryReachTest only checked that the words existed and were not ellipsized, which a
 * vertically clipped TextView passes.
 *
 * These tests check the readable glass: every visible TextView on every Me row must lay out
 * inside its own bounds, with no ellipsis and no line wider than its padding box, at font
 * 1.0/1.3/2.0 on 360dp and at 2.0 on 320dp, in English and Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class MeRowFitTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun poll(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun openMe(scenario: ActivityScenario<ShellActivity>): Map<Int, View> {
        val ids = listOf(
            R.id.swMePro, R.id.swMeAlsoSave, R.id.swMeTheme,
            R.id.swMeSettings, R.id.swMeBackup, R.id.swMeTrash, R.id.swMeAbout
        )
        poll {
            scenario.onActivity { activity ->
                if (activity.findViewById<View>(R.id.swMeTrash) == null) {
                    activity.findViewById<View>(R.id.swNavMe)?.performClick()
                }
            }
            ids.all { id -> var found = false; scenario.onActivity { found = it.findViewById<View>(id) != null }; found }
        }
        val rows = mutableMapOf<Int, View>()
        scenario.onActivity { activity ->
            for (id in ids) {
                activity.findViewById<View>(id)?.let { rows[id] = it }
            }
        }
        return rows
    }

    private fun layout(activity: ShellActivity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun checkRow(activity: ShellActivity, row: View, name: String) {
        val density = activity.resources.displayMetrics.density
        val scale = RuntimeEnvironment.getFontScale()
        val label = "$name @ ${scale}x"
        assertTrue("$label keeps a 48dp target (${row.height}px)", row.height >= (48 * density).toInt())
        assertTrue("$label is on screen", row.isShown)

        var textViews = 0
        fun walk(v: View) {
            if (v is TextView && v.isShown && v.text?.isNotBlank() == true) {
                textViews++
                val tv = v.text.toString()
                val layout = v.layout
                assertNotNull("$label: \"$tv\" was never laid out", layout)
                val lay = layout!!
                val avail = v.width - v.totalPaddingLeft - v.totalPaddingRight
                for (line in 0 until lay.lineCount) {
                    val width = lay.getLineWidth(line)
                    assertTrue(
                        "$label: \"$tv\" line $line is ${width.toInt()}px wide but only ${avail}px " +
                            "fit between paddings",
                        width <= avail + 0.5f
                    )
                    assertEquals(
                        "$label: \"$tv\" line $line is ellipsized",
                        0,
                        lay.getEllipsisCount(line)
                    )
                }
                val needed = lay.height + v.totalPaddingTop + v.totalPaddingBottom
                assertTrue(
                    "$label: \"$tv\" needs ${needed}px (layout ${layout.height}px + pads) but " +
                        "its view is only ${v.height}px tall — the text is cut off",
                    needed <= v.height + 0.5f
                )
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        assertTrue("$label shows its words", textViews >= 2)
    }

    @Test
    fun `every Me row contains its text at 100 130 and 200 percent`() {
        for (scale in floatArrayOf(1.0f, 1.3f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                val rows = openMe(scenario)
                scenario.onActivity { activity ->
                    layout(activity)
                    for ((id, row) in rows) {
                        checkRow(activity, row, activity.resources.getResourceEntryName(id))
                    }
                }
            }
        }
    }

    @Test
    fun `Me rows contain their Hindi text at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        RuntimeEnvironment.setQualifiers("hi-rIN-w360dp-h800dp-xhdpi")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val rows = openMe(scenario)
            scenario.onActivity { activity ->
                layout(activity)
                for ((id, row) in rows) {
                    checkRow(activity, row, activity.resources.getResourceEntryName(id) + " (hi)")
                }
            }
        }
    }

    @Test
    fun `Me rows contain their text on a 320dp phone at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val rows = openMe(scenario)
            scenario.onActivity { activity ->
                layout(activity)
                for ((id, row) in rows) {
                    checkRow(activity, row, activity.resources.getResourceEntryName(id) + " (320dp)")
                }
            }
        }
    }
}
