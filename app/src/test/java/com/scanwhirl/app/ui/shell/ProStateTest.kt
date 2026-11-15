package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.BuildConfig
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: Pro state consistency — a Pro build hides every PRO tag and the Me row
 * says the suite is active; test builds can flip to Free from Settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProStateTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
    }

    @After
    fun tearDown() {
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `a Pro build has no PRO tags and the Me row is active`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue(
                    "test builds start Pro",
                    ProGate.isPro(activity)
                )
                val quick = activity.findViewById<ViewGroup>(R.id.swQuickRow)
                val tiles = (0 until quick.childCount)
                    .mapNotNull { quick.getChildAt(it) as? com.scanwhirl.app.ui.kit.ToolTile }
                assertTrue(tiles.isNotEmpty())
                assertTrue(
                    "no PRO tag on any Pro build tile",
                    tiles.none { it.proTag.visibility == View.VISIBLE }
                )

                activity.findViewById<View>(R.id.swNavMe).performClick()
                idle()
                val meRow = activity.findViewById<View>(R.id.swMePro)
                assertNotNull(meRow)
                val rowTexts = texts(meRow)
                assertTrue(
                    "the Me row thanks the owner: $rowTexts",
                    rowTexts.any { it.contains("active", ignoreCase = true) }
                )
            }
        }
    }

    @Test
    fun `the test-only Free switch is present in a test build and flips the gate`() {
        assertTrue("this suite runs as a test build", BuildConfig.TEST_BUILD)
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = settings.get()
        val toggle = activity.findViewById<SwitchCompat>(R.id.switchTestFree)
        assertNotNull("test-build Settings offers act-as-Free", toggle)
        assertFalse("Pro by default", toggle!!.isChecked)

        toggle.isChecked = true
        assertFalse("the gate now reports Free", ProGate.isPro(activity))
        assertEquals(false, ProGate.testOverride(activity))

        // A Free build shows the PRO tags again.
        val shellController = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val shell = shellController.get()
        idle()
        val quick = shell.findViewById<ViewGroup>(R.id.swQuickRow)
        val tiles = (0 until quick.childCount)
            .mapNotNull { quick.getChildAt(it) as? com.scanwhirl.app.ui.kit.ToolTile }
        assertTrue(
            "Free builds mark Pro tools",
            tiles.any { it.tool?.pro == true && it.proTag.visibility == View.VISIBLE }
        )
        shellController.pause().stop().destroy()
        settings.pause().stop().destroy()
    }
}
